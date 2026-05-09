package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.cli.BridgeClient
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.beyondwin.fixthis.mcp.resourceText
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationResult
import io.beyondwin.fixthis.mcp.session.FeedbackSwipeDirection
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.textContent
import io.beyondwin.fixthis.mcp.toolResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val MaxRecentOverridePackages = 8
private val resolvedStatuses = setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.WONT_FIX)

class FixThisTools(
    private val bridge: FixThisBridge = CliFixThisBridge(BridgeClient()),
    private val defaultPackageName: String? = null,
    private val projectRoot: File = File(".").canonicalFile,
    private val feedbackService: FeedbackSessionService = FeedbackSessionService(
        bridge = bridge,
        store = FeedbackSessionStore(
            persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot)),
        ),
        projectRoot = projectRoot.absolutePath,
        defaultPackageName = defaultPackageName,
    ),
    private val consoleAssetsDir: File? = null,
    private val consolePort: Int = 0,
) {
    private val cacheLock = Any()
    private val consoleLock = Any()
    private val latestScreens = mutableMapOf<String, JsonObject>()
    private val latestStatuses = mutableMapOf<String, JsonObject>()
    private val cachedPackageOrder = linkedSetOf<String>()
    private var defaultCachePackage: String? = defaultPackageName?.takeIf { it.isNotBlank() }
    private var consoleServer: FeedbackConsoleServer? = null

    fun listTools(): JsonArray = buildJsonArray {
        ToolDefinitions.forEach { add(it.toJson()) }
    }

    fun listResources(): JsonArray = buildJsonArray {
        ResourceDefinitions.forEach { resource ->
            add(
                buildJsonObject {
                    put("uri", resource.uri)
                    put("name", resource.name)
                    put("mimeType", resource.mimeType)
                    put("description", resource.description)
                },
            )
        }
    }

    suspend fun call(name: String, arguments: JsonObject): JsonObject =
        when (name) {
            "fixthis_status" -> bridgeToolResult {
                val packageName = resolvePackageName(arguments)
                val status = bridge.status(packageName)
                cacheStatus(packageName, status)
                jsonToolResult(buildJsonObject {
                    put("deviceConnected", true)
                    put("packageName", packageName)
                    put("appRunning", status["activity"] != null)
                    put("sidekickConnected", true)
                    put("currentActivity", status["activity"] ?: JsonPrimitive(""))
                    put("composeRoots", status["rootsCount"] ?: JsonPrimitive(0))
                    put("sourceIndexAvailable", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
                    put("bridge", status)
                })
            }
            "fixthis_get_current_screen" -> bridgeToolResult {
                val packageName = resolvePackageName(arguments)
                val screen = bridge.inspectCurrentScreen(packageName)
                cacheScreen(packageName, screen)
                jsonToolResult(buildJsonObject {
                    put("screen", screen)
                })
            }
            "fixthis_verify_ui_change" -> bridgeToolResult {
                val packageName = resolvePackageName(arguments)
                val expectedText = arguments.stringParam("expectedText")?.takeIf { it.isNotBlank() }
                    ?: throw FixThisToolException("fixthis_verify_ui_change requires expectedText")
                val role = arguments.stringParam("role")?.takeIf { it.isNotBlank() }
                val bridgeResult = bridge.verifyUiChange(packageName, expectedText, role)
                jsonToolResult(normalizeVerifyUiChangeResult(bridgeResult, role))
            }
            "fixthis_open_feedback_console" -> bridgeToolResult {
                val opened = openConsole(
                    packageName = arguments.stringParam("packageName"),
                    sessionId = arguments.stringParam("sessionId"),
                    newSession = arguments.booleanParam("newSession") == true,
                )
                jsonToolResult(buildJsonObject {
                    put("sessionId", opened.session.sessionId)
                    put("packageName", opened.session.packageName)
                    put("projectRoot", opened.session.projectRoot)
                    put("consoleUrl", opened.consoleUrl)
                    put("resumed", opened.resumed)
                    put("session", McpProtocol.json.encodeToJsonElement(SessionDto.serializer(), opened.session))
                })
            }
            "fixthis_list_feedback_sessions" -> bridgeToolResult {
                val sessions = feedbackService.listSessions(
                    packageNameOverride = arguments.stringParam("packageName"),
                    includeClosed = arguments.booleanParam("includeClosed") == true,
                )
                val payload = McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject
                jsonToolResult(buildJsonObject {
                    put("projectRoot", projectRoot.absolutePath)
                    put("sessions", payload.getValue("sessions"))
                    put("skippedSessions", payload.getValue("skippedSessions"))
                })
            }
            "fixthis_capture_screen" -> bridgeToolResult {
                val session = requestedSession(arguments)
                val screen = feedbackService.captureScreen(session.sessionId)
                cacheSnapshot(session.packageName, screen)
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    put("screen", McpProtocol.json.encodeToJsonElement(SnapshotDto.serializer(), screen))
                })
            }
            "fixthis_navigate_app" -> bridgeToolResult {
                val request = arguments.navigationRequest()
                val session = requestedSession(arguments)
                val result = feedbackService.navigate(session.sessionId, request)
                result.screen?.let { screen -> cacheSnapshot(session.packageName, screen) }
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    McpProtocol.json.encodeToJsonElement(FeedbackNavigationResult.serializer(), result)
                        .jsonObject
                        .forEach { (key, value) -> put(key, value) }
                })
            }
            "fixthis_list_feedback" -> bridgeToolResult {
                val session = requestedSession(arguments)
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    put("packageName", session.packageName)
                    put("status", session.status.name.lowercase())
                    put("screensCount", session.screens.size)
                    put("itemsCount", session.items.size)
                    put("draftItemsCount", session.items.count { it.delivery == FeedbackDelivery.DRAFT })
                    put("sentBatchesCount", session.handoffBatches.size)
                    put(
                        "unresolvedSentItemsCount",
                        session.items.count { it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses },
                    )
                    put("items", buildJsonArray {
                        session.items.forEach { item ->
                            add(buildJsonObject {
                                put("itemId", item.itemId)
                                put("screenId", item.screenId)
                                put("status", item.status.name.lowercase())
                                put("comment", item.comment)
                            })
                        }
                    })
                })
            }
            "fixthis_read_feedback" -> bridgeToolResult {
                val detailMode = DetailMode.fromWire(arguments.stringParam("detailMode"))
                val session = requestedSession(arguments).focusedOn(arguments.stringParam("itemId"))
                toolResult(
                    content = listOf(
                        textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
                        textContent(FeedbackQueueFormatter.toMarkdown(session, detailMode), "text/markdown"),
                    ),
                )
            }
            "fixthis_resolve_feedback" -> bridgeToolResult {
                val session = requestedSession(arguments)
                val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
                    ?: throw FixThisToolException("fixthis_resolve_feedback requires itemId")
                val status = arguments.stringParam("status")?.takeIf { it.isNotBlank() }?.toFeedbackItemStatus()
                    ?: throw FixThisToolException("fixthis_resolve_feedback requires status")
                val summary = arguments.stringParam("summary")
                val item = feedbackService.resolveFeedback(session.sessionId, itemId, status, summary)
                jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
            }
            else -> throw FixThisToolException("Unknown FixThis tool: $name")
        }

    suspend fun readResource(uri: String): JsonObject =
        when (uri) {
            "fixthis://session/current" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                val status = latestStatus(packageName) ?: bridge.status(packageName).also { cacheStatus(packageName, it) }
                buildJsonObject {
                    put("packageName", packageName)
                    put("status", status)
                }
            }
            "fixthis://screen/current" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                latestScreen(packageName) ?: bridge.inspectCurrentScreen(packageName).also { cacheScreen(packageName, it) }
            }
            "fixthis://screenshot/latest/full.png" -> screenshotResource(uri, "desktopFullPath", "fullPath")
            "fixthis://screenshot/latest/crop.png" -> screenshotResource(uri, "desktopCropPath", "cropPath")
            "fixthis://source-index" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                val status = latestStatus(packageName) ?: bridge.status(packageName).also { cacheStatus(packageName, it) }
                buildJsonObject {
                    put("available", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
                    put("source", "bridge-status")
                }
            }
            else -> throw FixThisToolException("Unknown FixThis resource: $uri")
        }

    private suspend fun bridgeToolResult(block: suspend () -> JsonObject): JsonObject =
        try {
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

    private suspend fun bridgeResource(uri: String, block: suspend () -> JsonObject): JsonObject =
        resourceText(uri, fixThisJson.encodeToString(JsonObject.serializer(), block()))

    private fun screenshotResource(uri: String, vararg pathKeys: String): JsonObject {
        val packageName = resolveDefaultPackageName()
        val path = latestScreen(packageName).screenshotArtifactPath(*pathKeys)
        return resourceText(
            uri,
            fixThisJson.encodeToString(
                JsonObject.serializer(),
                if (path == null) unavailable("No screenshot artifact is available for $uri") else buildJsonObject {
                    put("path", path)
                    put("note", "FixThis exposes screenshot artifacts as desktop-readable paths.")
                },
            ),
        )
    }

    private fun jsonToolResult(payload: JsonObject): JsonObject =
        toolResult(content = listOf(textContent(fixThisJson.encodeToString(JsonObject.serializer(), payload), "application/json")))

    private fun openConsole(packageName: String?, sessionId: String?, newSession: Boolean): OpenFeedbackConsoleResult {
        synchronized(consoleLock) {
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
            val server = consoleServer ?: FeedbackConsoleServer(
                service = feedbackService,
                consoleAssetsDir = consoleAssetsDir,
                port = consolePort,
            ).also { consoleServer = it }
            return OpenFeedbackConsoleResult(
                session = session,
                consoleUrl = server.start(),
                resumed = when {
                    requestedSessionId != null -> true
                    newSession -> false
                    else -> session.sessionId in resumableBefore
                },
            )
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

    private fun cacheScreen(packageName: String, screen: JsonObject) {
        synchronized(cacheLock) {
            latestScreens[packageName] = screen
            rememberCachedPackage(packageName)
        }
    }

    private fun cacheSnapshot(packageName: String, screen: SnapshotDto) {
        cacheScreen(packageName, McpProtocol.json.encodeToJsonElement(SnapshotDto.serializer(), screen).jsonObject)
    }

    private fun cacheStatus(packageName: String, status: JsonObject) {
        synchronized(cacheLock) {
            latestStatuses[packageName] = status
            rememberCachedPackage(packageName)
        }
    }

    private fun latestScreen(packageName: String): JsonObject? =
        synchronized(cacheLock) { latestScreens[packageName] }

    private fun latestStatus(packageName: String): JsonObject? =
        synchronized(cacheLock) { latestStatuses[packageName] }

    private fun resolvePackageName(arguments: JsonObject): String {
        val packageOverride = arguments.stringParam("packageName")?.takeIf { it.isNotBlank() }
        val packageName = bridge.resolvePackageName(packageOverride ?: defaultPackageName)
        if (packageOverride == null) rememberDefaultPackage(packageName)
        return packageName
    }

    private fun resolveDefaultPackageName(): String =
        bridge.resolvePackageName(defaultPackageName).also { rememberDefaultPackage(it) }

    private fun rememberDefaultPackage(packageName: String) {
        synchronized(cacheLock) {
            defaultCachePackage = packageName
            rememberCachedPackage(packageName)
        }
    }

    private fun rememberCachedPackage(packageName: String) {
        cachedPackageOrder.remove(packageName)
        cachedPackageOrder.add(packageName)
        evictOldOverridePackages()
    }

    private fun evictOldOverridePackages() {
        while (cachedPackageOrder.count { it != defaultCachePackage } > MaxRecentOverridePackages) {
            val evictedPackage = cachedPackageOrder.firstOrNull { it != defaultCachePackage } ?: return
            cachedPackageOrder.remove(evictedPackage)
            latestScreens.remove(evictedPackage)
            latestStatuses.remove(evictedPackage)
        }
    }

    private fun JsonObject.stringParam(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longParam(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.booleanParam(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.floatParam(name: String): Float? =
        (this[name] as? JsonPrimitive)?.floatOrNull

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

    private fun JsonObject?.screenshotArtifactPath(vararg pathKeys: String): String? {
        val screenshot = this?.get("screenshot") as? JsonObject
        return pathKeys.firstNotNullOfOrNull { key -> (screenshot?.get(key) as? JsonPrimitive)?.contentOrNull }
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

    private fun String.toFeedbackItemStatus(): AnnotationStatusDto =
        when (this) {
            "resolved" -> AnnotationStatusDto.RESOLVED
            "needs_clarification" -> AnnotationStatusDto.NEEDS_CLARIFICATION
            "wont_fix" -> AnnotationStatusDto.WONT_FIX
            else -> throw FixThisToolException("Unsupported feedback resolution status: $this")
        }

    private fun String.toNavigationAction(): FeedbackNavigationAction =
        when (this) {
            "back" -> FeedbackNavigationAction.BACK
            "tap" -> FeedbackNavigationAction.TAP
            "swipe" -> FeedbackNavigationAction.SWIPE
            else -> throw FixThisToolException("Unsupported navigation action: $this")
        }

    private fun String.toSwipeDirection(): FeedbackSwipeDirection =
        when (this) {
            "up" -> FeedbackSwipeDirection.UP
            "down" -> FeedbackSwipeDirection.DOWN
            "left" -> FeedbackSwipeDirection.LEFT
            "right" -> FeedbackSwipeDirection.RIGHT
            else -> throw FixThisToolException("Unsupported swipe direction: $this")
        }

    private fun unavailable(message: String): JsonObject = buildJsonObject {
        put("available", false)
        put("message", message)
    }
}

interface FixThisBridge {
    fun resolvePackageName(packageOverride: String?): String
    fun devices(): List<AdbDevice> = emptyList()
    fun selectedDeviceSerial(): String? = null
    fun selectDevice(serial: String) = Unit
    fun disconnectDevice() = Unit
    fun launchApp(packageName: String) = Unit
    suspend fun status(packageName: String): JsonObject
    suspend fun heartbeat(packageName: String): JsonObject = status(packageName)
    suspend fun inspectCurrentScreen(packageName: String): JsonObject
    suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject
    suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
        error("FixThis bridge does not support navigation")
    suspend fun readSourceIndex(packageName: String): JsonObject = JsonObject(emptyMap())
    suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String? = null,
        screenId: String? = null,
        destinationDirectory: File? = null,
    ): JsonObject
}

class CliFixThisBridge(private val client: BridgeClient) : FixThisBridge {
    override fun resolvePackageName(packageOverride: String?): String =
        client.resolvePackageName(packageOverride)

    override fun devices(): List<AdbDevice> =
        client.devices()

    override fun selectedDeviceSerial(): String? =
        client.selectedDeviceSerial()

    override fun selectDevice(serial: String) =
        client.selectDevice(serial)

    override fun disconnectDevice() =
        client.disconnectDevice()

    override fun launchApp(packageName: String) =
        client.launchApp(packageName)

    override suspend fun status(packageName: String): JsonObject =
        client.request(packageName, "status")

    override suspend fun heartbeat(packageName: String): JsonObject =
        client.request(packageName, "heartbeat")

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject =
        client.request(packageName, "inspectCurrentScreen")

    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
        client.request(
            packageName = packageName,
            method = "verifyUiChange",
            params = buildJsonObject {
                put("expectedText", expectedText)
                role?.let { put("role", it) }
            },
        )

    override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
        client.performNavigation(
            packageName = packageName,
            request = McpProtocol.json.encodeToJsonElement(FeedbackNavigationRequest.serializer(), request).jsonObject,
        )

    override suspend fun readSourceIndex(packageName: String): JsonObject =
        client.readSourceIndex(packageName)

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject =
        client.captureScreenSnapshot(
            packageName = packageName,
            sessionId = sessionId,
            screenId = screenId,
            destinationDirectory = destinationDirectory,
        )
}

class FixThisToolException(message: String) : RuntimeException(message)

private data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }
}

private data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
)

private data class OpenFeedbackConsoleResult(
    val session: SessionDto,
    val consoleUrl: String,
    val resumed: Boolean,
)

private val ToolDefinitions = listOf(
    ToolDefinition(
        name = "fixthis_status",
        description = "Check whether the FixThis sidekick bridge is reachable for the debug app.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .fixthis/project.json or server --package is used."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_get_current_screen",
        description = "Inspect the current Compose screen through the FixThis sidekick bridge.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .fixthis/project.json or server --package is used."),
            "includeScreenshot" to booleanProperty("Whether to include the latest screenshot resource URI when available."),
            "includeSemantics" to booleanProperty("Whether the caller wants semantics data. V1 bridge inspection returns semantics nodes."),
            "maxNodes" to integerProperty("Maximum nodes requested by the caller. V1 bridge may return fewer or ignore this hint."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_verify_ui_change",
        description = "Verify that expected UI text is present on the current screen through the FixThis sidekick bridge.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .fixthis/project.json or server --package is used."),
            "expectedText" to stringProperty("Text expected to appear on the current screen."),
            "role" to stringProperty("Optional semantic role hint, such as Button."),
            required = listOf("expectedText"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_open_feedback_console",
        description = "Open or return the local FixThis feedback console for the current MCP session.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .fixthis/project.json or server --package is used."),
            "sessionId" to stringProperty("Exact feedback session id to reopen."),
            "newSession" to booleanProperty("Create a new session instead of resuming the current or latest persisted session."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_list_feedback_sessions",
        description = "List persisted FixThis feedback sessions for this project.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Optional Android application id filter."),
            "includeClosed" to booleanProperty("Whether to include closed feedback sessions."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_capture_screen",
        description = "Capture the current Android screen into the active FixThis feedback session.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_navigate_app",
        description = "Perform one debug-only navigation action against the app and optionally capture the resulting screen.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "action" to stringProperty("One of back, tap, or swipe."),
            "x" to numberProperty("Tap x coordinate. Required for tap."),
            "y" to numberProperty("Tap y coordinate. Required for tap."),
            "direction" to stringProperty("Swipe direction: up, down, left, or right. Required for swipe."),
            "distance" to numberProperty("Optional swipe distance."),
            "captureAfter" to booleanProperty("Whether to capture a screen after navigation. Defaults to true."),
            required = listOf("action"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_list_feedback",
        description = "List feedback queue summaries for the active FixThis feedback session.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_read_feedback",
        description = "Read the feedback queue as annotation JSON and Markdown.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Optional feedback item id to focus the returned payload."),
            "detailMode" to enumStringProperty(
                description = "Markdown detail level. JSON remains complete regardless of this value.",
                values = listOf("compact", "precise", "full"),
            ),
        ),
    ),
    ToolDefinition(
        name = "fixthis_resolve_feedback",
        description = "Mark a feedback item as resolved, needing clarification, or not fixed.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to update."),
            "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
            "summary" to stringProperty("Agent summary shown in the console."),
            required = listOf("itemId", "status"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_claim_feedback",
        description = "Mark a feedback item as in-progress before starting work. Call this AFTER reading the item and BEFORE making code changes. Returns the updated item. The user's browser console reflects the change within 2 seconds. After this call you must eventually call fixthis_resolve_feedback for the same itemId.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to claim."),
            "agentNote" to stringProperty("Optional short note shown next to the item in the user's console."),
            required = listOf("itemId"),
        ),
    ),
)

private val ResourceDefinitions = listOf(
    ResourceDefinition("fixthis://session/current", "Current FixThis session", "Current bridge session and sidekick status."),
    ResourceDefinition("fixthis://screen/current", "Current FixThis screen", "Current inspected Compose screen."),
    ResourceDefinition("fixthis://screenshot/latest/full.png", "Latest full screenshot", "Desktop-readable latest full screenshot artifact path."),
    ResourceDefinition("fixthis://screenshot/latest/crop.png", "Latest crop screenshot", "Desktop-readable latest crop screenshot artifact path."),
    ResourceDefinition("fixthis://source-index", "FixThis source index", "Source index availability reported by the sidekick bridge."),
)

private fun objectSchema(
    vararg properties: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
    )
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
    put("additionalProperties", false)
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun enumStringProperty(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun integerProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun numberProperty(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}
