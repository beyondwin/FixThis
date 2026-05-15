package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.console.enrichSessionWithStaleness
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationResult
import io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSwipeDirection
import io.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbe
import io.beyondwin.fixthis.mcp.session.HostSourceFreshnessResult
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.textContent
import io.beyondwin.fixthis.mcp.toolResult
import io.beyondwin.fixthis.mcp.tools.handlers.McpToolHandler
import io.beyondwin.fixthis.mcp.tools.handlers.defaultMcpToolHandlers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

private const val INSTALL_STALE_HINT = "Run ./gradlew :app:installDebug then cold-launch the app"
private val resolvedStatuses = setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.WONT_FIX)

internal data class FixThisToolBridgePorts(
    val packageResolver: PackageResolver,
    val screenBridge: ScreenBridge,
    val sourceIndexBridge: SourceIndexBridge,
)

internal data class FixThisToolDispatcherServices(
    val defaultPackageName: String?,
    val projectRoot: File,
    val feedbackService: FeedbackSessionService,
    val cache: BridgeResultCache,
    val freshnessProbe: HostSourceFreshnessProbe,
    val consoleManager: ConsoleServerManager,
)

internal class FixThisToolDispatcher(
    handlers: List<McpToolHandler>,
) {
    private val handlersByName = handlers.associateBy { it.name }

    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val handler = handlersByName[name] ?: throw FixThisToolException("Unknown FixThis tool: $name")
        return handler.handle(arguments)
    }
}

internal fun FixThisToolDispatcher(
    ports: FixThisToolBridgePorts,
    services: FixThisToolDispatcherServices,
): FixThisToolDispatcher {
    val operations = FixThisToolOperations(ports, services)
    return FixThisToolDispatcher(defaultMcpToolHandlers(operations))
}

@Suppress("TooManyFunctions")
internal class FixThisToolOperations(
    private val ports: FixThisToolBridgePorts,
    private val services: FixThisToolDispatcherServices,
) {
    private val openConsoleLock = Any()

    internal suspend fun status(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val status = ports.screenBridge.status(packageName)
        services.cache.cacheStatus(packageName, status)
        val freshness = evaluateFreshness(packageName, status)
        jsonToolResult(statusPayload(packageName, status, freshness))
    }

    internal suspend fun getCurrentScreen(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val screen = ports.screenBridge.inspectCurrentScreen(packageName)
        services.cache.cacheScreen(packageName, screen)
        jsonToolResult(
            buildJsonObject {
                put("screen", screen)
            },
        )
    }

    internal suspend fun verifyUiChange(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val expectedText = arguments.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_verify_ui_change requires expectedText")
        val role = arguments.stringParam("role")?.takeIf { it.isNotBlank() }
        val bridgeResult = ports.screenBridge.verifyUiChange(packageName, expectedText, role)
        jsonToolResult(normalizeVerifyUiChangeResult(bridgeResult, role))
    }

    internal suspend fun openFeedbackConsole(arguments: JsonObject): JsonObject = bridgeToolResult {
        val opened = openConsole(
            packageName = arguments.stringParam("packageName"),
            sessionId = arguments.stringParam("sessionId"),
            newSession = arguments.booleanParam("newSession") == true,
        )
        jsonToolResult(openFeedbackConsolePayload(opened))
    }

    internal suspend fun listFeedbackSessions(arguments: JsonObject): JsonObject = bridgeToolResult {
        val sessions = services.feedbackService.listSessions(
            packageNameOverride = arguments.stringParam("packageName"),
            includeClosed = arguments.booleanParam("includeClosed") == true,
        )
        val payload = McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject
        jsonToolResult(
            buildJsonObject {
                put("projectRoot", services.projectRoot.absolutePath)
                put("sessions", payload.getValue("sessions"))
                put("skippedSessions", payload.getValue("skippedSessions"))
            },
        )
    }

    internal suspend fun captureScreen(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val screen = services.feedbackService.captureScreen(session.sessionId)
        services.cache.cacheSnapshot(session.packageName, screen)
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
        val result = services.feedbackService.navigate(session.sessionId, request)
        result.screen?.let { screen -> services.cache.cacheSnapshot(session.packageName, screen) }
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
        val item = services.feedbackService.resolveFeedback(session.sessionId, itemId, status, summary)
        jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
    }

    internal suspend fun claimFeedback(arguments: JsonObject): JsonObject = bridgeToolResult {
        val session = requestedSession(arguments)
        val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_claim_feedback requires itemId")
        val agentNote = arguments.stringParam("agentNote")?.takeIf { it.isNotBlank() }
        val item = services.feedbackService.claimFeedback(session.sessionId, itemId, agentNote)
        jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private suspend fun bridgeToolResult(block: suspend () -> JsonObject): JsonObject = try {
        block()
    } catch (error: FixThisToolException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw FixThisToolException(error.message ?: "Invalid FixThis tool arguments")
    } catch (error: Throwable) {
        toolResult(
            isError = true,
            content = listOf(textContent(error.message ?: error::class.java.simpleName)),
        )
    }

    private fun jsonToolResult(payload: JsonObject): JsonObject = toolResult(
        content = listOf(
            textContent(
                text = fixThisJson.encodeToString(JsonObject.serializer(), payload),
                mimeType = "application/json",
            ),
        ),
    )

    private fun statusPayload(
        packageName: String,
        status: JsonObject,
        freshness: HostSourceFreshnessResult,
    ): JsonObject = buildJsonObject {
        put("deviceConnected", true)
        put("packageName", packageName)
        put("appRunning", status["activity"] != null)
        put("sidekickConnected", true)
        put("currentActivity", status["activity"] ?: JsonPrimitive(""))
        put("composeRoots", status["rootsCount"] ?: JsonPrimitive(0))
        put("sourceIndexAvailable", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
        put("installStale", JsonPrimitive(freshness.installStale))
        freshness.reason?.let { put("installStaleReason", JsonPrimitive(it)) }
        if (freshness.installStale) {
            put("installStaleHint", JsonPrimitive(INSTALL_STALE_HINT))
        }
        freshness.installedAtEpochMillis?.let { put("installedAtEpochMillis", JsonPrimitive(it)) }
        put(
            "newerSourceFiles",
            buildJsonArray {
                freshness.sampleNewerFiles.forEach { add(JsonPrimitive(it)) }
            },
        )
        put("bridge", status)
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
                services.feedbackService.listSessions(packageNameOverride = packageName, includeClosed = false)
                    .sessions
                    .mapTo(mutableSetOf()) { it.sessionId }
            } else {
                emptySet()
            }
            val session = services.feedbackService.openSession(
                packageNameOverride = packageName,
                sessionId = requestedSessionId,
                newSession = newSession,
            )
            val resumed = when {
                requestedSessionId != null -> true
                newSession -> false
                else -> session.sessionId in resumableBefore
            }
            return services.consoleManager.open(session = session, resumed = resumed)
        }
    }

    private fun normalizeVerifyUiChangeResult(bridgeResult: JsonObject, role: String?): JsonObject {
        val bridgeMatchingNodes = bridgeResult["matchingNodes"] as? JsonArray
        val matchedText = bridgeResult.stringParam("matchedText")
        val found = bridgeResult.booleanParam("found")
            ?: bridgeResult.booleanParam("verified")
            ?: bridgeMatchingNodes?.isNotEmpty()
            ?: (matchedText != null)
        val matchingNodes = bridgeMatchingNodes ?: buildJsonArray {
            if (found && matchedText != null) {
                add(
                    buildJsonObject {
                        put("text", matchedText)
                        role?.let { put("role", it) }
                    },
                )
            }
        }

        return buildJsonObject {
            put("found", found)
            put("matchingNodes", matchingNodes)
            if (bridgeResult.requiresNestedBridgeDetails(bridgeMatchingNodes)) {
                put("bridge", bridgeResult)
            }
        }
    }

    private fun resolvePackageName(arguments: JsonObject): String {
        val packageOverride = arguments.stringParam("packageName")?.takeIf { it.isNotBlank() }
        val packageName = ports.packageResolver.resolvePackageName(packageOverride ?: services.defaultPackageName)
        if (packageOverride == null) services.cache.rememberDefaultPackage(packageName)
        return packageName
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

    private fun JsonObject.requiresNestedBridgeDetails(normalizedMatchingNodes: JsonArray?): Boolean {
        val normalizedKeys = setOf("found", "matchingNodes")
        return keys.any { it !in normalizedKeys } ||
            booleanParam("found") == null ||
            normalizedMatchingNodes == null
    }

    private fun requestedSession(arguments: JsonObject): SessionDto {
        val sessionId = arguments.stringParam("sessionId")?.takeIf { it.isNotBlank() }
        return if (sessionId == null) {
            services.feedbackService.currentSession()
        } else {
            services.feedbackService.getSession(sessionId)
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

    private suspend fun evaluateFreshness(
        packageName: String,
        status: JsonObject,
    ): HostSourceFreshnessResult {
        val sourceIndexAvailable = status["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull == true
        val installEpoch = status["installEpochMillis"]?.jsonPrimitive?.longOrNull
        return if (!sourceIndexAvailable) {
            unavailableFreshness(installEpoch, "source index not available")
        } else {
            val raw = runCatching { ports.sourceIndexBridge.readSourceIndex(packageName) }.getOrNull()
            val indexElement = raw?.get("sourceIndex")
            val sourceIndex = indexElement?.let {
                runCatching { McpProtocol.json.decodeFromJsonElement<SourceIndex>(it) }.getOrNull()
            }
            if (sourceIndex == null) {
                unavailableFreshness(installEpoch, "source index could not be read")
            } else {
                services.freshnessProbe.evaluate(sourceIndex, installEpoch)
            }
        }
    }

    private fun unavailableFreshness(
        installEpoch: Long?,
        reason: String,
    ): HostSourceFreshnessResult = HostSourceFreshnessResult(
        installStale = false,
        newerFileCount = 0,
        totalIndexedFiles = 0,
        installedAtEpochMillis = installEpoch,
        sampleNewerFiles = emptyList(),
        reason = reason,
    )
}

internal fun JsonObject.stringParam(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanParam(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.floatParam(name: String): Float? = (this[name] as? JsonPrimitive)?.floatOrNull
