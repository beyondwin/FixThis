package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.tools.PointPatchBridge
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
        val payload = bridge.captureScreenSnapshot(session.packageName)
        val inspection = payload["inspection"]?.jsonObject
        val activityName = payload["activity"]?.jsonPrimitive?.contentOrNull
            ?: inspection?.get("activity")?.jsonPrimitive?.contentOrNull

        val screen = CapturedScreen(
            screenId = "pending",
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

    fun markReadyForAgent(sessionId: String): FeedbackSession = store.markReadyForAgent(sessionId)

    fun resolveFeedback(sessionId: String, itemId: String, status: FeedbackItemStatus, summary: String?): FeedbackItem =
        store.updateItemStatus(sessionId, itemId, status, summary)
}
