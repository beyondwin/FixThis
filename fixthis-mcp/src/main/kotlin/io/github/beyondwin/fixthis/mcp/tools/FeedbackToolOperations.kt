package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.console.enrichSessionWithStaleness
import io.github.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationAction
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationResult
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackSwipeDirection
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.textContent
import io.github.beyondwin.fixthis.mcp.toolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

private val resolvedStatuses = setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.WONT_FIX)

/**
 * Feedback-session MCP tools: console open, capture, navigation, and the queue
 * read/list/claim/resolve lifecycle. Depends on the feedback session service,
 * console manager, result cache, and project root — not on the live screen ports.
 */
@Suppress("TooManyFunctions")
internal class FeedbackToolOperations(
    private val feedbackService: FeedbackSessionService,
    private val consoleManager: ConsoleServerManager,
    private val cache: BridgeResultCache,
    private val projectRoot: File,
) {
    private val openConsoleLock = Any()

    internal suspend fun openFeedbackConsole(arguments: JsonObject): JsonObject = bridgeToolResult {
        val opened = openConsole(
            packageName = arguments.stringParam("packageName"),
            sessionId = arguments.stringParam("sessionId"),
            newSession = arguments.booleanParam("newSession") == true,
        )
        jsonToolResult(openFeedbackConsolePayload(opened))
    }

    internal suspend fun listFeedbackSessions(arguments: JsonObject): JsonObject = bridgeToolResult {
        val sessions = feedbackService.listSessions(
            packageNameOverride = arguments.stringParam("packageName"),
            includeClosed = arguments.booleanParam("includeClosed") == true,
        )
        val payload = McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject
        jsonToolResult(
            buildJsonObject {
                put("projectRoot", projectRoot.absolutePath)
                put("sessions", payload.getValue("sessions"))
                put("skippedSessions", payload.getValue("skippedSessions"))
            },
        )
    }

    internal suspend fun captureScreen(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val screen = feedbackService.captureScreen(session.sessionId)
        cache.cacheSnapshot(session.packageName, screen)
        jsonToolResult(
            buildJsonObject {
                put("sessionId", session.sessionId)
                put("screen", McpProtocol.json.encodeToJsonElement(SnapshotDto.serializer(), screen))
            },
        )
    }

    internal suspend fun navigateApp(arguments: JsonObject): JsonObject = bridgeToolResult {
        val request = arguments.navigationRequest()
        val session = requestedSession(arguments)
        val result = feedbackService.navigate(session.sessionId, request)
        result.screen?.let { screen -> cache.cacheSnapshot(session.packageName, screen) }
        jsonToolResult(
            buildJsonObject {
                put("sessionId", session.sessionId)
                McpProtocol.json.encodeToJsonElement(FeedbackNavigationResult.serializer(), result)
                    .jsonObject
                    .forEach { (key, value) -> put(key, value) }
            },
        )
    }

    internal suspend fun listFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val includeAll = arguments.booleanParam("includeAll") ?: false
        val visibleItems = if (includeAll) {
            session.items
        } else {
            session.items.filter {
                it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses
            }
        }
        jsonToolResult(listFeedbackPayload(session, visibleItems))
    }

    internal suspend fun readFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val detailMode = DetailMode.fromWire(arguments.stringParam("detailMode"))
        val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
        val includeAll = arguments.booleanParam("includeAll") ?: false
        val baseSession = requestedSession(arguments)
        val session = baseSession
            .focusedOn(itemId)
            .filteredForAgent(showAll = includeAll || itemId != null)
            .let { feedbackService.refreshSourceEvidenceForHandoff(it) }
        toolResult(
            content = listOf(
                textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
                textContent(FeedbackQueueFormatter.toMarkdown(session, detailMode), "text/markdown"),
            ),
        )
    }

    internal suspend fun resolveFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_resolve_feedback requires itemId")
        val status = arguments.stringParam("status")?.takeIf { it.isNotBlank() }?.toFeedbackItemStatus()
            ?: throw FixThisToolException("fixthis_resolve_feedback requires status")
        val summary = arguments.stringParam("summary")
        val item = feedbackService.resolveFeedback(session.sessionId, itemId, status, summary)
        jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
    }

    internal suspend fun claimFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_claim_feedback requires itemId")
        val agentNote = arguments.stringParam("agentNote")?.takeIf { it.isNotBlank() }
        val item = feedbackService.claimFeedback(session.sessionId, itemId, agentNote)
        jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
    }

    private fun openFeedbackConsolePayload(opened: OpenFeedbackConsoleResult): JsonObject = buildJsonObject {
        put("sessionId", opened.session.sessionId)
        put("packageName", opened.session.packageName)
        put("projectRoot", opened.session.projectRoot)
        put("consoleUrl", opened.consoleUrl)
        put("resumed", opened.resumed)
        put("session", enrichSessionWithStaleness(opened.session))
    }

    private fun listFeedbackPayload(
        session: SessionDto,
        visibleItems: List<AnnotationDto>,
    ): JsonObject = buildJsonObject {
        put("sessionId", session.sessionId)
        put("packageName", session.packageName)
        put("status", session.status.name.lowercase())
        put("screensCount", session.screens.size)
        put("itemsCount", session.items.size)
        put("draftItemsCount", session.items.count { it.delivery == FeedbackDelivery.DRAFT })
        put("sentBatchesCount", session.handoffBatches.size)
        put(
            "unresolvedSentItemsCount",
            session.items.count {
                it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses
            },
        )
        put(
            "items",
            buildJsonArray {
                visibleItems.forEach { item -> add(feedbackItemPayload(item)) }
            },
        )
    }

    private fun feedbackItemPayload(item: AnnotationDto): JsonObject {
        val handedOff = item.lastHandedOffAtEpochMillis
        val stale = handedOff != null && item.updatedAtEpochMillis > handedOff
        return buildJsonObject {
            put("itemId", item.itemId)
            put("screenId", item.screenId)
            put("status", item.status.name.lowercase())
            put("delivery", item.delivery.name.lowercase())
            put("staleAfterHandoff", stale)
            put("comment", item.comment)
        }
    }

    private fun openConsole(packageName: String?, sessionId: String?, newSession: Boolean): OpenFeedbackConsoleResult {
        synchronized(openConsoleLock) {
            val requestedSessionId = sessionId?.takeIf { it.isNotBlank() }
            val resumableBefore = if (requestedSessionId == null && !newSession) {
                feedbackService.listSessions(packageNameOverride = packageName, includeClosed = false)
                    .sessions
                    .mapTo(mutableSetOf()) { it.sessionId }
            } else {
                emptySet()
            }
            val session = feedbackService.openSession(
                packageNameOverride = packageName,
                sessionId = requestedSessionId,
                newSession = newSession,
            )
            val resumed = when {
                requestedSessionId != null -> true
                newSession -> false
                else -> session.sessionId in resumableBefore
            }
            return consoleManager.open(session = session, resumed = resumed)
        }
    }

    private fun JsonObject.navigationRequest(): FeedbackNavigationRequest {
        requireOnlyNavigationKeys()
        return FeedbackNavigationRequest(
            action = stringParam("action")?.toNavigationAction()
                ?: throw FixThisToolException("fixthis_navigate_app requires action"),
            x = floatParam("x"),
            y = floatParam("y"),
            direction = stringParam("direction")?.toSwipeDirection(),
            distance = floatParam("distance"),
            captureAfter = booleanParam("captureAfter") != false,
        ).also { it.validate() }
    }

    private fun JsonObject.requireOnlyNavigationKeys() {
        val allowedKeys = setOf("sessionId", "action", "x", "y", "direction", "distance", "captureAfter")
        val unsupportedKeys = keys.filterNot { it in allowedKeys }
        if (unsupportedKeys.isNotEmpty()) {
            throw FixThisToolException(
                "fixthis_navigate_app does not support argument: ${unsupportedKeys.first()}",
            )
        }
    }

    private fun requestedSession(arguments: JsonObject): SessionDto {
        val sessionId = arguments.stringParam("sessionId")?.takeIf { it.isNotBlank() }
        return if (sessionId == null) {
            feedbackService.currentSession()
        } else {
            feedbackService.getSession(sessionId)
        }
    }

    private fun SessionDto.focusedOn(itemId: String?): SessionDto {
        val focusedItemId = itemId?.takeIf { it.isNotBlank() } ?: return this
        val item = items.firstOrNull { it.itemId == focusedItemId }
            ?: throw FixThisToolException("Unknown feedback item: $focusedItemId")
        return copy(
            screens = screens.filter { it.screenId == item.screenId },
            items = listOf(item),
            handoffBatches = if (item.delivery == FeedbackDelivery.SENT) {
                handoffBatches
                    .filter { it.batchId == item.handoffBatchId }
                    .map { it.copy(itemIds = listOf(item.itemId)) }
            } else {
                emptyList()
            },
        )
    }

    private fun SessionDto.filteredForAgent(showAll: Boolean): SessionDto {
        if (showAll) return this
        val visible = items.filter { it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses }
        return copy(items = visible)
    }

    private fun String.toFeedbackItemStatus(): AnnotationStatusDto = when (this) {
        "resolved" -> AnnotationStatusDto.RESOLVED
        "needs_clarification" -> AnnotationStatusDto.NEEDS_CLARIFICATION
        "wont_fix" -> AnnotationStatusDto.WONT_FIX
        else -> throw FixThisToolException("Unsupported feedback resolution status: $this")
    }

    private fun String.toNavigationAction(): FeedbackNavigationAction = when (this) {
        "back" -> FeedbackNavigationAction.BACK
        "tap" -> FeedbackNavigationAction.TAP
        "swipe" -> FeedbackNavigationAction.SWIPE
        else -> throw FixThisToolException("Unsupported navigation action: $this")
    }

    private fun String.toSwipeDirection(): FeedbackSwipeDirection = when (this) {
        "up" -> FeedbackSwipeDirection.UP
        "down" -> FeedbackSwipeDirection.DOWN
        "left" -> FeedbackSwipeDirection.LEFT
        "right" -> FeedbackSwipeDirection.RIGHT
        else -> throw FixThisToolException("Unsupported swipe direction: $this")
    }
}
