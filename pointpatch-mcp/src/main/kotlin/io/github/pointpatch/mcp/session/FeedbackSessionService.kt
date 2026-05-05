package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.console.FeedbackTargetType
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import kotlinx.coroutines.CancellationException
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

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): FeedbackSession =
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
                            it.status != FeedbackSessionStatus.CLOSED
                    }
                    ?.let { return@synchronized it }
                store.listSessions(packageName = packageName)
                    .sessions
                    .firstOrNull { it.projectRoot == projectRoot }
                    ?.let { return@synchronized store.openExistingSession(it.sessionId) }
            }
            store.openSession(packageName = packageName, projectRoot = projectRoot)
        }

    fun currentSession(): FeedbackSession =
        store.currentSession() ?: openSession(null)

    fun getSession(sessionId: String): FeedbackSession = store.getSession(sessionId)

    fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
        val packageName = packageNameOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.resolvePackageName(it) }
        return store.listSessions(packageName = packageName, includeClosed = includeClosed)
    }

    fun closeSession(sessionId: String): FeedbackSession = store.closeSession(sessionId)

    suspend fun captureScreen(sessionId: String): CapturedScreen {
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
        val inspection = payload["inspection"]?.jsonObject
        val activityName = payload["activity"]?.jsonPrimitive?.contentOrNull
            ?: inspection?.get("activity")?.jsonPrimitive?.contentOrNull

        val screen = CapturedScreen(
            screenId = screenId,
            capturedAtEpochMillis = 0L,
            activityName = activityName,
            displayName = activityName?.substringAfterLast('.') ?: "Screen ${session.screens.size + 1}",
            screenshot = payload["screenshot"]?.jsonObject?.let {
                McpProtocol.json.decodeFromJsonElement<FeedbackScreenshot>(it)
            },
            roots = (inspection?.get("roots") ?: payload["roots"])?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement<FeedbackScreenRoot>(element)
            }.orEmpty(),
            sourceIndexAvailable = payload["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull
                ?: inspection?.get("sourceIndexAvailable")?.jsonPrimitive?.booleanOrNull
                ?: false,
            errors = (inspection?.get("errors") ?: payload["errors"])?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement<PointPatchError>(element)
            }.orEmpty(),
        )
        return store.addScreen(sessionId, screen)
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
    ): FeedbackItem =
        store.addItem(
            sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(bounds),
                comment = comment,
                status = if (comment.isBlank()) FeedbackItemStatus.OPEN else FeedbackItemStatus.READY,
            ),
        )

    fun addFeedbackItem(
        sessionId: String,
        screenId: String,
        targetType: FeedbackTargetType,
        bounds: PointPatchRect,
        nodeUid: String?,
        comment: String,
    ): FeedbackItem {
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
            FeedbackTargetType.AREA -> FeedbackTarget.Area(storedBounds)
            FeedbackTargetType.NODE -> FeedbackTarget.Node(
                nodeUid = selectedNode!!.uid,
                boundsInWindow = storedBounds,
            )
        }
        return store.addItem(
            sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = target,
                selectedNode = selectedNode,
                comment = comment,
                status = FeedbackItemStatus.OPEN,
            ),
        )
    }

    fun clearDraftItems(sessionId: String): FeedbackSession =
        store.clearDraftItems(sessionId)

    fun sendDraftToAgent(sessionId: String): FeedbackSession =
        store.sendDraftToAgent(
            sessionId,
            markdownSnapshot = FeedbackQueueFormatter.toMarkdown(store.getSession(sessionId)),
        )

    fun markReadyForAgent(sessionId: String): FeedbackSession = store.markReadyForAgent(sessionId)

    fun resolveFeedback(sessionId: String, itemId: String, status: FeedbackItemStatus, summary: String?): FeedbackItem =
        store.updateItemStatus(sessionId, itemId, status, summary)

    private fun validateFinitePositiveBounds(bounds: PointPatchRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) { "Selection bounds must have positive size" }
    }

    private fun validateBoundsInsideScreenshot(screen: CapturedScreen, bounds: PointPatchRect) {
        val width = screen.screenshot?.width?.toFloat() ?: return
        val height = screen.screenshot?.height?.toFloat() ?: return
        require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
            "Selection bounds must be inside the screenshot"
        }
    }
}
