package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.AdbDevice
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.source.SourceIndex
import io.github.pointpatch.compose.core.source.SourceMatcher
import io.github.pointpatch.mcp.console.FeedbackPreviewSnapshot
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.console.FeedbackTargetType
import io.github.pointpatch.mcp.console.AnnotationDraftDto
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeedbackSessionService(
    private val bridge: PointPatchBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
) {
    private val sessionLock = Any()
    private val previewSnapshots = linkedMapOf<String, PreviewRecord>()
    private val previewSavesInFlight = mutableSetOf<String>()
    private val sourceIndexLock = Any()
    private val sourceIndexCache = mutableMapOf<String, SourceIndex?>()

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): SessionDto =
        synchronized(sessionLock) {
            sessionId?.takeIf { it.isNotBlank() }?.let { return@synchronized store.openExistingSession(it) }
            val packageName = bridge.resolvePackageName(
                packageNameOverride?.takeIf { it.isNotBlank() } ?: defaultPackageName,
            )
            if (!newSession) {
                store.currentSession()
                    ?.takeIf {
                        it.packageName == packageName &&
                            it.projectRoot == projectRoot &&
                            it.status != SessionStatusDto.CLOSED
                    }
                    ?.let { return@synchronized it }
                store.listSessions(packageName = packageName)
                    .sessions
                    .firstOrNull { it.projectRoot == projectRoot }
                    ?.let { return@synchronized store.openExistingSession(it.sessionId) }
            }
            store.openSession(packageName = packageName, projectRoot = projectRoot)
        }

    fun currentSession(): SessionDto =
        store.currentSession() ?: openSession(null)

    fun getSession(sessionId: String): SessionDto = store.getSession(sessionId)

    fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
        val packageName = packageNameOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.resolvePackageName(it) }
        return store.listSessions(packageName = packageName, includeClosed = includeClosed)
    }

    fun closeSession(sessionId: String): SessionDto = store.closeSession(sessionId)

    fun devices(): List<AdbDevice> = bridge.devices()

    fun selectedDeviceSerial(): String? = bridge.selectedDeviceSerial()

    fun selectDevice(serial: String) {
        val selectedSerial = serial.trim()
        require(selectedSerial.isNotBlank()) { "Device serial must not be blank" }
        val device = devices().firstOrNull { it.serial == selectedSerial }
            ?: throw FeedbackSessionException("DEVICE_NOT_AVAILABLE: Android device is not connected: $selectedSerial")
        if (device.state != "device") {
            throw FeedbackSessionException("DEVICE_NOT_AVAILABLE: Android device is not ready: $selectedSerial (${device.state})")
        }
        bridge.selectDevice(selectedSerial)
    }

    fun disconnectDevice() = bridge.disconnectDevice()

    suspend fun captureScreen(sessionId: String): SnapshotDto {
        val session = store.getSession(sessionId)
        val screenId = store.nextId()
        val artifactDirectory = FeedbackSessionPaths(File(session.projectRoot))
            .screenArtifactDirectory(session.sessionId, screenId)
        val payload = bridge.captureScreenSnapshot(
            packageName = session.packageName,
            sessionId = session.sessionId,
            screenId = screenId,
            destinationDirectory = artifactDirectory,
        )
        val screen = payload.toCapturedScreen(
            screenId = screenId,
            fallbackDisplayName = "Screen ${session.screens.size + 1}",
        )
        return store.addScreen(sessionId, screen)
    }

    suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot {
        val session = store.getSession(sessionId)
        val previewId = store.nextId()
        val screenId = store.nextId()
        val artifactDirectory = File(
            session.projectRoot,
            ".pointpatch/preview-cache/${session.sessionId}/$previewId",
        )
        val payload = bridge.captureScreenSnapshot(
            packageName = session.packageName,
            sessionId = session.sessionId,
            screenId = screenId,
            destinationDirectory = artifactDirectory,
        )
        val preview = FeedbackPreviewSnapshot(
            previewId = previewId,
            screen = payload.toCapturedScreen(screenId = screenId, fallbackDisplayName = "Draft screen"),
        )
        val sourceIndex = readPreviewSourceIndexOrNull(session.packageName, preview.screen)
        synchronized(sessionLock) {
            previewSnapshots[previewId] = PreviewRecord(
                sessionId = session.sessionId,
                projectRoot = session.projectRoot,
                snapshot = preview,
                sourceIndex = sourceIndex,
            )
            while (previewSnapshots.size > MaxRetainedPreviews) {
                previewSnapshots.remove(previewSnapshots.keys.first())?.deletePreviewCacheDirectory()
            }
        }
        return preview
    }

    fun previewScreenshotFile(sessionId: String, previewId: String): File {
        val record = synchronized(sessionLock) {
            previewSnapshots[previewId]?.takeIf { it.sessionId == sessionId }
                ?: throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
        }
        val screenshotPath = record.snapshot.screen.screenshot?.desktopFullPath
            ?: throw FeedbackSessionException("PREVIEW_SCREENSHOT_NOT_FOUND: Screenshot not found for preview: $previewId")
        val screenshotFile = File(screenshotPath).canonicalFile
        val previewDirectory = File(
            File(record.projectRoot).canonicalFile,
            ".pointpatch/preview-cache/$sessionId/$previewId",
        ).canonicalFile
        if (
            !screenshotFile.isFile ||
            screenshotFile.extension.lowercase() != "png" ||
            !screenshotFile.toPath().startsWith(previewDirectory.toPath())
        ) {
            throw FeedbackSessionException("PREVIEW_SCREENSHOT_NOT_FOUND: Screenshot not found for preview: $previewId")
        }
        return screenshotFile
    }

    suspend fun navigate(sessionId: String, request: FeedbackNavigationRequest): FeedbackNavigationResult {
        request.validate()
        val session = store.getSession(sessionId)
        val bridgeResult = bridge.performNavigation(session.packageName, request)
        val performed = bridgeResult["performed"]?.jsonPrimitive?.booleanOrNull ?: false
        val activity = bridgeResult["activityName"]?.jsonPrimitive?.contentOrNull
            ?: bridgeResult["activity"]?.jsonPrimitive?.contentOrNull
        val message = bridgeResult["message"]?.jsonPrimitive?.contentOrNull
        if (!request.captureAfter || !performed) {
            return FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
            )
        }

        return try {
            val screen = captureScreen(sessionId)
            FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
                screen = screen,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            FeedbackNavigationResult(
                performed = performed,
                action = request.action,
                activityName = activity,
                message = message,
                captureError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: PointPatchRect,
        comment: String,
    ): AnnotationDto =
        store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(bounds),
                comment = comment,
                status = if (comment.isBlank()) AnnotationStatusDto.OPEN else AnnotationStatusDto.READY,
            ),
        )

    fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: PointPatchRect,
        nodeUid: String?,
        comment: String,
    ): AnnotationDto {
        require(comment.isNotBlank()) { "Feedback comment must not be blank" }
        val session = store.getSession(sessionId)
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        val selectedNode = if (targetType == FeedbackTargetType.NODE) {
            val uid = nodeUid?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Node feedback requires nodeUid")
            screen.roots.asSequence()
                .flatMap { root -> (root.mergedNodes + root.unmergedNodes).asSequence() }
                .firstOrNull { node -> node.uid == uid }
                ?: throw IllegalArgumentException("Selected node does not exist on screen: $uid")
        } else {
            null
        }
        val storedBounds = selectedNode?.boundsInWindow ?: bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(screen, storedBounds)
        val target = when (targetType) {
            FeedbackTargetType.AREA -> AnnotationTargetDto.Area(storedBounds)
            FeedbackTargetType.NODE -> AnnotationTargetDto.Node(
                nodeUid = selectedNode!!.uid,
                boundsInWindow = storedBounds,
            )
        }
        return store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = target,
                selectedNode = selectedNode,
                comment = comment,
                status = AnnotationStatusDto.OPEN,
            ),
        )
    }

    fun savePreviewFeedbackItems(
        sessionId: String,
        previewId: String,
        items: List<AnnotationDraftDto>,
    ): SessionDto {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        val inFlightKey = "$sessionId:$previewId"
        val preview = synchronized(sessionLock) {
            val record = previewSnapshots[previewId]?.takeIf { it.sessionId == sessionId }
                ?: throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
            if (!previewSavesInFlight.add(inFlightKey)) {
                throw FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: $previewId")
            }
            record
        }

        return try {
            val feedbackItems = items.map { pending ->
                buildFeedbackItemForDraft(preview.snapshot.screen, preview.sourceIndex, pending)
            }
            val persistedScreen = promotePreviewArtifacts(
                projectRoot = preview.projectRoot,
                sessionId = sessionId,
                screen = preview.snapshot.screen,
            )
            val updated = store.addScreenWithItems(sessionId, persistedScreen, feedbackItems)
            val removedPreview = synchronized(sessionLock) {
                previewSavesInFlight.remove(inFlightKey)
                previewSnapshots.remove(previewId)
            }
            removedPreview?.deletePreviewCacheDirectory()
            updated
        } catch (error: Throwable) {
            synchronized(sessionLock) {
                previewSavesInFlight.remove(inFlightKey)
            }
            throw error
        }
    }

    fun clearDraftItems(sessionId: String): SessionDto =
        store.clearDraftItems(sessionId)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto =
        store.deleteScreen(sessionId, screenId)

    fun sendDraftToAgent(sessionId: String, prompt: String? = null): SessionDto =
        store.sendDraftToAgent(
            sessionId,
            markdownSnapshot = prompt?.takeIf { it.isNotBlank() } ?: FeedbackQueueFormatter.toMarkdown(store.getSession(sessionId)),
        )

    fun markReadyForAgent(sessionId: String): SessionDto = store.markReadyForAgent(sessionId)

    fun resolveFeedback(sessionId: String, itemId: String, status: AnnotationStatusDto, summary: String?): AnnotationDto =
        store.updateItemStatus(sessionId, itemId, status, summary)

    private fun validateFinitePositiveBounds(bounds: PointPatchRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) { "Selection bounds must have positive size" }
    }

    private fun validateBoundsInsideScreenshot(screen: SnapshotDto, bounds: PointPatchRect) {
        val width = screen.screenshot?.width?.toFloat() ?: return
        val height = screen.screenshot?.height?.toFloat() ?: return
        require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
            "Selection bounds must be inside the screenshot"
        }
    }

    private fun JsonObject.toCapturedScreen(screenId: String, fallbackDisplayName: String): SnapshotDto {
        val inspection = this["inspection"]?.jsonObject
        val activityName = this["activity"]?.jsonPrimitive?.contentOrNull
            ?: inspection?.get("activity")?.jsonPrimitive?.contentOrNull
        return SnapshotDto(
            screenId = screenId,
            capturedAtEpochMillis = 0L,
            activityName = activityName,
            displayName = activityName?.substringAfterLast('.') ?: fallbackDisplayName,
            screenshot = this["screenshot"]?.jsonObject?.let {
                McpProtocol.json.decodeFromJsonElement<SnapshotScreenshotDto>(it)
            },
            roots = (inspection?.get("roots") ?: this["roots"])?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement<SnapshotRootDto>(element)
            }.orEmpty(),
            sourceIndexAvailable = this["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull
                ?: inspection?.get("sourceIndexAvailable")?.jsonPrimitive?.booleanOrNull
                ?: false,
            errors = (inspection?.get("errors") ?: this["errors"])?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement<PointPatchError>(element)
            }.orEmpty(),
        )
    }

    private suspend fun readPreviewSourceIndexOrNull(packageName: String, screen: SnapshotDto): SourceIndex? {
        if (!screen.sourceIndexAvailable) return null
        synchronized(sourceIndexLock) {
            if (sourceIndexCache.containsKey(packageName)) return sourceIndexCache[packageName]
        }
        val result = runCatching { bridge.readSourceIndex(packageName) }.getOrElse { return null }
        val available = result["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull ?: false
        val sourceIndexElement = result["sourceIndex"]
        val sourceIndex = if (available && sourceIndexElement != null) {
            runCatching {
                McpProtocol.json.decodeFromJsonElement<SourceIndex>(sourceIndexElement)
                    .takeIf { it.entries.isNotEmpty() }
            }.getOrNull()
        } else {
            null
        }
        synchronized(sourceIndexLock) {
            sourceIndexCache[packageName] = sourceIndex
        }
        return sourceIndex
    }

    private fun buildFeedbackItemForDraft(
        screen: SnapshotDto,
        sourceIndex: SourceIndex?,
        pending: AnnotationDraftDto,
    ): AnnotationDto {
        val selectedNode = when (pending.targetType) {
            FeedbackTargetType.NODE -> {
                val uid = pending.nodeUid?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Node feedback requires nodeUid")
                screen.allNodes().firstOrNull { it.uid == uid }
                    ?: throw IllegalArgumentException("Selected node does not exist on preview: $uid")
            }
            FeedbackTargetType.AREA -> null
        }
        val storedBounds = selectedNode?.boundsInWindow ?: pending.bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(screen, storedBounds)
        val evidenceNodes = when (pending.targetType) {
            FeedbackTargetType.AREA -> areaEvidenceNodes(screen, storedBounds)
            FeedbackTargetType.NODE -> nodeEvidenceNodes(screen, selectedNode!!)
        }
        val sourceSelectedNode = when (pending.targetType) {
            FeedbackTargetType.AREA -> evidenceNodes.firstOrNull()
            FeedbackTargetType.NODE -> selectedNode
        }
        val sourceNearbyNodes = when (pending.targetType) {
            FeedbackTargetType.AREA -> evidenceNodes.drop(1)
            FeedbackTargetType.NODE -> evidenceNodes
        }
        val target = when (pending.targetType) {
            FeedbackTargetType.AREA -> AnnotationTargetDto.Area(storedBounds)
            FeedbackTargetType.NODE -> AnnotationTargetDto.Node(
                nodeUid = selectedNode!!.uid,
                boundsInWindow = storedBounds,
            )
        }
        return AnnotationDto(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = target,
            selectedNode = selectedNode,
            nearbyNodes = evidenceNodes,
            sourceCandidates = sourceCandidatesFor(sourceIndex, sourceSelectedNode, sourceNearbyNodes, screen.activityName),
            comment = pending.comment,
            status = if (pending.comment.isBlank()) AnnotationStatusDto.OPEN else AnnotationStatusDto.READY,
        )
    }

    private fun promotePreviewArtifacts(projectRoot: String, sessionId: String, screen: SnapshotDto): SnapshotDto {
        val screenshot = screen.screenshot ?: return screen
        val artifactDirectory = FeedbackSessionPaths(File(projectRoot))
            .screenArtifactDirectory(sessionId, screen.screenId)
        if (!artifactDirectory.exists()) {
            check(artifactDirectory.mkdirs()) {
                "Could not create PointPatch artifact directory: ${artifactDirectory.absolutePath}"
            }
        }
        val promotedFullPath = promoteScreenshotPath(
            sourcePath = screenshot.desktopFullPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-full.png",
        )
        val promotedCropPath = promoteScreenshotPath(
            sourcePath = screenshot.desktopCropPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-crop.png",
        )
        return screen.copy(
            screenshot = screenshot.copy(
                desktopFullPath = promotedFullPath ?: screenshot.desktopFullPath,
                desktopCropPath = promotedCropPath ?: screenshot.desktopCropPath,
            ),
        )
    }

    private fun promoteScreenshotPath(sourcePath: String?, artifactDirectory: File, fileName: String): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        require(source.exists() && source.isFile) { "Preview screenshot artifact is missing: $sourcePath" }
        val destination = artifactDirectory.resolve(fileName)
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        return destination.absolutePath
    }

    private fun PreviewRecord.deletePreviewCacheDirectory() {
        val previewRoot = File(projectRoot, ".pointpatch/preview-cache/$sessionId").canonicalFile
        val previewDirectory = File(previewRoot, snapshot.previewId).canonicalFile
        if (previewDirectory.toPath().startsWith(previewRoot.toPath())) {
            previewDirectory.deleteRecursively()
        }
    }

    private fun areaEvidenceNodes(screen: SnapshotDto, bounds: PointPatchRect): List<PointPatchNode> {
        val evidenceNodes = screen.allNodes()
            .asSequence()
            .filter { it.hasMeaningfulSemantic() }
            .map { node ->
                AreaEvidenceNode(
                    node = node,
                    overlaps = node.boundsInWindow.intersects(bounds),
                    overlapArea = node.boundsInWindow.intersectionArea(bounds),
                    centerDistance = node.boundsInWindow.centerDistanceTo(bounds),
                )
            }
            .toList()
        val hasOverlappingEvidence = evidenceNodes.any { it.overlaps }
        return evidenceNodes
            .asSequence()
            .filter { evidence ->
                if (hasOverlappingEvidence) {
                    evidence.overlaps
                } else {
                    true
                }
            }
            .sortedWith(
                compareByDescending<AreaEvidenceNode> { it.overlaps }
                    .thenByDescending { it.overlapArea }
                    .thenBy { it.centerDistance }
                    .thenBy { it.node.boundsInWindow.area }
                    .thenBy { it.node.uid },
            )
            .map { it.node }
            .distinctBy { it.uid }
            .take(MaxEvidenceNodes)
            .toList()
    }

    private fun nodeEvidenceNodes(screen: SnapshotDto, selectedNode: PointPatchNode): List<PointPatchNode> =
        screen.allNodes()
            .asSequence()
            .filter { it.uid != selectedNode.uid }
            .filter { it.rootIndex == selectedNode.rootIndex }
            .filter { it.hasMeaningfulSemantic() }
            .sortedWith(
                compareBy<PointPatchNode> { it.boundsInWindow.centerDistanceTo(selectedNode.boundsInWindow) }
                    .thenBy { it.boundsInWindow.area }
                    .thenBy { it.uid },
            )
            .distinctBy { it.uid }
            .take(MaxEvidenceNodes)
            .toList()

    private fun sourceCandidatesFor(
        sourceIndex: SourceIndex?,
        selectedNode: PointPatchNode?,
        nearbyNodes: List<PointPatchNode>,
        activityName: String?,
    ) = sourceIndex
        ?.takeIf { it.entries.isNotEmpty() }
        ?.let { SourceMatcher.match(it, selectedNode, nearbyNodes, activityName) }
        .orEmpty()

    private fun SnapshotDto.allNodes(): List<PointPatchNode> =
        roots.flatMap { root -> root.mergedNodes + root.unmergedNodes }

    private fun PointPatchRect.intersects(other: PointPatchRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun PointPatchRect.intersectionArea(other: PointPatchRect): Float {
        val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return width * height
    }

    private fun PointPatchRect.centerDistanceTo(other: PointPatchRect): Float {
        val dx = ((left + right) / 2f) - ((other.left + other.right) / 2f)
        val dy = ((top + bottom) / 2f) - ((other.top + other.bottom) / 2f)
        return dx * dx + dy * dy
    }

    private data class PreviewRecord(
        val sessionId: String,
        val projectRoot: String,
        val snapshot: FeedbackPreviewSnapshot,
        val sourceIndex: SourceIndex?,
    )

    private data class AreaEvidenceNode(
        val node: PointPatchNode,
        val overlaps: Boolean,
        val overlapArea: Float,
        val centerDistance: Float,
    )

    private companion object {
        const val MaxRetainedPreviews = 3
        const val MaxEvidenceNodes = 8
    }
}
