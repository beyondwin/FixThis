package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.beyondwin.fixthis.compose.core.identity.OccurrenceCalculator
import io.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceMatcher
import io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactory
import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
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
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
    private val previewCache: PreviewSnapshotCache = PreviewSnapshotCache(MaxRetainedPreviews),
    private val sourceIndexRegistry: SourceIndexRegistry = SourceIndexRegistry(),
) {
    private val screenshotArtifactPromoter = ScreenshotArtifactPromoter()
    private val sessionLock = Any()
    private val previewSavesInFlight = mutableSetOf<String>()

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
            ".fixthis/preview-cache/${session.sessionId}/$previewId",
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
        val sourceIndex = readSourceIndexOrNull(session.packageName, preview.screen)
        previewCache.put(
            PreviewRecord(
                sessionId = session.sessionId,
                projectRoot = session.projectRoot,
                snapshot = preview,
                sourceIndex = sourceIndex,
            ),
        ).forEach { it.deletePreviewCacheDirectory() }
        return preview
    }

    fun previewScreenshotFile(sessionId: String, previewId: String): File {
        val record = previewCache.get(sessionId, previewId)
            ?: throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
        val screenshotPath = record.snapshot.screen.screenshot?.desktopFullPath
            ?: throw FeedbackSessionException("PREVIEW_SCREENSHOT_NOT_FOUND: Screenshot not found for preview: $previewId")
        val screenshotFile = File(screenshotPath).canonicalFile
        val previewDirectory = File(
            File(record.projectRoot).canonicalFile,
            ".fixthis/preview-cache/$sessionId/$previewId",
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
        bounds: FixThisRect,
        comment: String,
    ): AnnotationDto {
        val session = store.getSession(sessionId)
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
        return store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(bounds),
                comment = comment,
                status = if (comment.isBlank()) AnnotationStatusDto.OPEN else AnnotationStatusDto.READY,
                targetEvidence = targetEvidenceFor(
                    targetType = FeedbackTargetType.AREA,
                    selectedNode = null,
                    screen = screen,
                    sourceCandidates = emptyList(),
                ),
            ),
        )
    }

    suspend fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
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
        val evidenceNodes = when (targetType) {
            FeedbackTargetType.AREA -> areaEvidenceNodes(screen, storedBounds)
            FeedbackTargetType.NODE -> nodeEvidenceNodes(screen, selectedNode!!)
        }
        val sourceIndex = readSourceIndexOrNull(session.packageName, screen)
        val sourceSelectedNode = when (targetType) {
            FeedbackTargetType.AREA -> evidenceNodes.firstOrNull()
            FeedbackTargetType.NODE -> selectedNode
        }
        val sourceNearbyNodes = when (targetType) {
            FeedbackTargetType.AREA -> evidenceNodes.drop(1)
            FeedbackTargetType.NODE -> evidenceNodes
        }
        val sourceCandidates = sourceCandidatesFor(sourceIndex, sourceSelectedNode, sourceNearbyNodes, screen.activityName)
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
                nearbyNodes = evidenceNodes,
                sourceCandidates = sourceCandidates,
                comment = comment,
                status = AnnotationStatusDto.OPEN,
                targetEvidence = targetEvidenceFor(
                    targetType = targetType,
                    selectedNode = selectedNode,
                    screen = screen,
                    sourceCandidates = sourceCandidates,
                ),
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
            val record = previewCache.get(sessionId, previewId)
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
            val persistedScreen = screenshotArtifactPromoter.promote(
                projectRoot = preview.projectRoot,
                sessionId = sessionId,
                screen = preview.snapshot.screen,
            )
            val updated = store.addScreenWithItems(sessionId, persistedScreen, feedbackItems)
            val removedPreview = synchronized(sessionLock) {
                previewSavesInFlight.remove(inFlightKey)
                previewCache.remove(sessionId, previewId)
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

    private fun validateFinitePositiveBounds(bounds: FixThisRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) { "Selection bounds must have positive size" }
    }

    private fun validateBoundsInsideScreenshot(screen: SnapshotDto, bounds: FixThisRect) {
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
                McpProtocol.json.decodeFromJsonElement<FixThisError>(element)
            }.orEmpty(),
        )
    }

    private suspend fun readSourceIndexOrNull(packageName: String, screen: SnapshotDto): SourceIndex? {
        if (!screen.sourceIndexAvailable) return null
        if (sourceIndexRegistry.contains(packageName)) return sourceIndexRegistry.cached(packageName)
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
        sourceIndexRegistry.put(packageName, sourceIndex)
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
        val sourceCandidates = sourceCandidatesFor(sourceIndex, sourceSelectedNode, sourceNearbyNodes, screen.activityName)
        return AnnotationDto(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = target,
            selectedNode = selectedNode,
            nearbyNodes = evidenceNodes,
            sourceCandidates = sourceCandidates,
            comment = pending.comment,
            status = if (pending.comment.isBlank()) AnnotationStatusDto.OPEN else AnnotationStatusDto.READY,
            targetEvidence = targetEvidenceFor(
                targetType = pending.targetType,
                selectedNode = selectedNode,
                screen = screen,
                sourceCandidates = sourceCandidates,
            ),
        )
    }

    private fun PreviewRecord.deletePreviewCacheDirectory() {
        val previewRoot = File(projectRoot, ".fixthis/preview-cache/$sessionId").canonicalFile
        val previewDirectory = File(previewRoot, snapshot.previewId).canonicalFile
        if (previewDirectory.toPath().startsWith(previewRoot.toPath())) {
            previewDirectory.deleteRecursively()
        }
    }

    private fun areaEvidenceNodes(screen: SnapshotDto, bounds: FixThisRect): List<FixThisNode> {
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

    private fun nodeEvidenceNodes(screen: SnapshotDto, selectedNode: FixThisNode): List<FixThisNode> =
        screen.allNodes()
            .asSequence()
            .filter { it.uid != selectedNode.uid }
            .filter { it.rootIndex == selectedNode.rootIndex }
            .filter { it.hasMeaningfulSemantic() }
            .sortedWith(
                compareBy<FixThisNode> { it.boundsInWindow.centerDistanceTo(selectedNode.boundsInWindow) }
                    .thenBy { it.boundsInWindow.area }
                    .thenBy { it.uid },
            )
            .distinctBy { it.uid }
            .take(MaxEvidenceNodes)
            .toList()

    private fun sourceCandidatesFor(
        sourceIndex: SourceIndex?,
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
        activityName: String?,
    ) = sourceIndex
        ?.takeIf { it.entries.isNotEmpty() }
        ?.let { SourceMatcher.match(it, selectedNode, nearbyNodes, activityName) }
        .orEmpty()

    private fun targetEvidenceFor(
        targetType: FeedbackTargetType,
        selectedNode: FixThisNode?,
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
    ): TargetEvidence {
        val identityHint = IdentityHintFactory.from(selectedNode)
        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = selectedNode,
            nodes = screen.roots.flatMap { root -> root.mergedNodes },
            identityHint = identityHint,
        )
        return TargetEvidence(
            identityHint = identityHint,
            occurrence = occurrence,
            sourceInterpretation = SourceInterpretationFactory.from(sourceCandidates),
            evidenceQuality = if (identityHint != null || occurrence != null || sourceCandidates.isNotEmpty()) {
                EvidenceQuality.STRUCTURED
            } else {
                EvidenceQuality.BASIC
            },
            screenshotKinds = screen.screenshot.availableKinds(),
            warnings = buildList {
                if (targetType == FeedbackTargetType.AREA) {
                    add("Occurrence is not applicable for visual area selections.")
                }
                if (targetType == FeedbackTargetType.NODE && selectedNode == null) {
                    add("No selected semantics node was available for target evidence.")
                }
            },
        )
    }

    private fun SnapshotScreenshotDto?.availableKinds(): List<String> {
        val screenshot = this ?: return emptyList()
        return buildList {
            if (!screenshot.fullPath.isNullOrBlank() || !screenshot.desktopFullPath.isNullOrBlank()) add("full")
            if (!screenshot.cropPath.isNullOrBlank() || !screenshot.desktopCropPath.isNullOrBlank()) add("crop")
        }
    }

    private fun SnapshotDto.allNodes(): List<FixThisNode> =
        roots.flatMap { root -> root.mergedNodes + root.unmergedNodes }

    private fun FixThisRect.intersects(other: FixThisRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun FixThisRect.intersectionArea(other: FixThisRect): Float {
        val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return width * height
    }

    private fun FixThisRect.centerDistanceTo(other: FixThisRect): Float {
        val dx = ((left + right) / 2f) - ((other.left + other.right) / 2f)
        val dy = ((top + bottom) / 2f) - ((other.top + other.bottom) / 2f)
        return dx * dx + dy * dy
    }

    private data class AreaEvidenceNode(
        val node: FixThisNode,
        val overlaps: Boolean,
        val overlapArea: Float,
        val centerDistance: Float,
    )

    private companion object {
        const val MaxRetainedPreviews = 3
        const val MaxEvidenceNodes = 8
    }
}
