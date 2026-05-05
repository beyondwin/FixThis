package io.github.pointpatch.mcp.tools

import io.github.pointpatch.cli.BridgeClient
import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.format.PointPatchMarkdownFormatter
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.console.FeedbackConsoleServer
import io.github.pointpatch.mcp.resourceText
import io.github.pointpatch.mcp.session.CapturedScreen
import io.github.pointpatch.mcp.session.FeedbackNavigationAction
import io.github.pointpatch.mcp.session.FeedbackNavigationRequest
import io.github.pointpatch.mcp.session.FeedbackNavigationResult
import io.github.pointpatch.mcp.session.FeedbackSwipeDirection
import io.github.pointpatch.mcp.session.FeedbackItem
import io.github.pointpatch.mcp.session.FeedbackItemStatus
import io.github.pointpatch.mcp.session.FeedbackQueueFormatter
import io.github.pointpatch.mcp.session.FeedbackSession
import io.github.pointpatch.mcp.session.FeedbackSessionList
import io.github.pointpatch.mcp.session.FeedbackSessionPaths
import io.github.pointpatch.mcp.session.FeedbackSessionPersistence
import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.textContent
import io.github.pointpatch.mcp.toolResult
import java.io.File
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val DefaultFeedbackTimeoutMillis = 60_000L
private const val MaxRecentOverridePackages = 8

class PointPatchTools(
    private val bridge: PointPatchBridge = CliPointPatchBridge(BridgeClient()),
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
) {
    private val cacheLock = Any()
    private val consoleLock = Any()
    private val latestAnnotations = mutableMapOf<String, JsonObject>()
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
            "pointpatch_status" -> bridgeToolResult {
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
            "pointpatch_get_current_screen" -> bridgeToolResult {
                val usesDefaultPackage = arguments.stringParam("packageName").isNullOrBlank()
                val packageName = resolvePackageName(arguments)
                val screen = bridge.inspectCurrentScreen(packageName)
                cacheScreen(packageName, screen)
                jsonToolResult(buildJsonObject {
                    put("screen", screen)
                    if (
                        arguments.booleanParam("includeScreenshot") != false &&
                        usesDefaultPackage &&
                        latestAnnotation(packageName).hasScreenshotArtifact("desktopFullPath", "fullPath")
                    ) {
                        put("screenshotResource", "pointpatch://screenshot/latest/full.png")
                    }
                })
            }
            "pointpatch_get_ui_feedback" -> bridgeToolResult {
                val packageName = resolvePackageName(arguments)
                val timeoutMillis = arguments.longParam("timeoutMs")
                    ?: arguments.longParam("timeoutMillis")
                    ?: DefaultFeedbackTimeoutMillis
                require(timeoutMillis > 0) { "timeoutMs must be greater than 0" }
                val capture = bridge.startFeedbackCapture(packageName, timeoutMillis)
                val annotation = capture["annotation"] as? JsonObject
                if (annotation != null) cacheAnnotation(packageName, annotation)
                val annotationPayload = annotation
                    ?: unavailable("PointPatch feedback capture did not return an annotation.")
                val markdown = annotation?.toMarkdown()
                    ?: "PointPatch feedback capture did not return an annotation."
                toolResult(
                    content = listOf(
                        textContent(pointPatchJson.encodeToString(JsonObject.serializer(), annotationPayload), "application/json"),
                        textContent(markdown, "text/markdown"),
                    ),
                )
            }
            "pointpatch_verify_ui_change" -> bridgeToolResult {
                val packageName = resolvePackageName(arguments)
                val expectedText = arguments.stringParam("expectedText")?.takeIf { it.isNotBlank() }
                    ?: throw PointPatchToolException("pointpatch_verify_ui_change requires expectedText")
                val role = arguments.stringParam("role")?.takeIf { it.isNotBlank() }
                val bridgeResult = bridge.verifyUiChange(packageName, expectedText, role)
                jsonToolResult(normalizeVerifyUiChangeResult(bridgeResult, role))
            }
            "pointpatch_open_feedback_console" -> bridgeToolResult {
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
                    put("session", McpProtocol.json.encodeToJsonElement(FeedbackSession.serializer(), opened.session))
                })
            }
            "pointpatch_list_feedback_sessions" -> bridgeToolResult {
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
            "pointpatch_capture_screen" -> bridgeToolResult {
                val session = requestedSession(arguments)
                val screen = feedbackService.captureScreen(session.sessionId)
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    put("screen", McpProtocol.json.encodeToJsonElement(CapturedScreen.serializer(), screen))
                })
            }
            "pointpatch_navigate_app" -> bridgeToolResult {
                val session = requestedSession(arguments)
                val result = feedbackService.navigate(session.sessionId, arguments.navigationRequest())
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    McpProtocol.json.encodeToJsonElement(FeedbackNavigationResult.serializer(), result)
                        .jsonObject
                        .forEach { (key, value) -> put(key, value) }
                })
            }
            "pointpatch_list_feedback" -> bridgeToolResult {
                val session = requestedSession(arguments)
                jsonToolResult(buildJsonObject {
                    put("sessionId", session.sessionId)
                    put("packageName", session.packageName)
                    put("status", session.status.name.lowercase())
                    put("screensCount", session.screens.size)
                    put("itemsCount", session.items.size)
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
            "pointpatch_read_feedback" -> bridgeToolResult {
                val session = requestedSession(arguments).focusedOn(arguments.stringParam("itemId"))
                toolResult(
                    content = listOf(
                        textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
                        textContent(FeedbackQueueFormatter.toMarkdown(session), "text/markdown"),
                    ),
                )
            }
            "pointpatch_resolve_feedback" -> bridgeToolResult {
                val session = requestedSession(arguments)
                val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
                    ?: throw PointPatchToolException("pointpatch_resolve_feedback requires itemId")
                val status = arguments.stringParam("status")?.takeIf { it.isNotBlank() }?.toFeedbackItemStatus()
                    ?: throw PointPatchToolException("pointpatch_resolve_feedback requires status")
                val summary = arguments.stringParam("summary")
                val item = feedbackService.resolveFeedback(session.sessionId, itemId, status, summary)
                jsonToolResult(McpProtocol.json.encodeToJsonElement(FeedbackItem.serializer(), item).jsonObject)
            }
            else -> throw PointPatchToolException("Unknown PointPatch tool: $name")
        }

    suspend fun readResource(uri: String): JsonObject =
        when (uri) {
            "pointpatch://session/current" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                val status = latestStatus(packageName) ?: bridge.status(packageName).also { cacheStatus(packageName, it) }
                buildJsonObject {
                    put("packageName", packageName)
                    put("status", status)
                }
            }
            "pointpatch://screen/current" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                latestScreen(packageName) ?: bridge.inspectCurrentScreen(packageName).also { cacheScreen(packageName, it) }
            }
            "pointpatch://annotation/latest" -> resourceText(
                uri,
                pointPatchJson.encodeToString(
                    JsonObject.serializer(),
                    latestAnnotation(resolveDefaultPackageName())
                        ?: unavailable("No feedback annotation has been captured for the default package in this MCP session."),
                ),
            )
            "pointpatch://screenshot/latest/full.png" -> screenshotResource(uri, "desktopFullPath", "fullPath")
            "pointpatch://screenshot/latest/crop.png" -> screenshotResource(uri, "desktopCropPath", "cropPath")
            "pointpatch://source-index" -> bridgeResource(uri) {
                val packageName = resolveDefaultPackageName()
                val status = latestStatus(packageName) ?: bridge.status(packageName).also { cacheStatus(packageName, it) }
                buildJsonObject {
                    put("available", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
                    put("source", "bridge-status")
                }
            }
            else -> throw PointPatchToolException("Unknown PointPatch resource: $uri")
        }

    private suspend fun bridgeToolResult(block: suspend () -> JsonObject): JsonObject =
        try {
            block()
        } catch (error: PointPatchToolException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw PointPatchToolException(error.message ?: "Invalid PointPatch tool arguments")
        } catch (error: Throwable) {
            toolResult(
                isError = true,
                content = listOf(textContent(error.message ?: error::class.java.simpleName)),
            )
        }

    private suspend fun bridgeResource(uri: String, block: suspend () -> JsonObject): JsonObject =
        resourceText(uri, pointPatchJson.encodeToString(JsonObject.serializer(), block()))

    private fun screenshotResource(uri: String, vararg pathKeys: String): JsonObject {
        val packageName = resolveDefaultPackageName()
        val path = latestAnnotation(packageName).screenshotArtifactPath(*pathKeys)
        return resourceText(
            uri,
            pointPatchJson.encodeToString(
                JsonObject.serializer(),
                if (path == null) unavailable("No screenshot artifact is available for $uri") else buildJsonObject {
                    put("path", path)
                    put("note", "PointPatch exposes screenshot artifacts as desktop-readable paths.")
                },
            ),
        )
    }

    private fun jsonToolResult(payload: JsonObject): JsonObject =
        toolResult(content = listOf(textContent(pointPatchJson.encodeToString(JsonObject.serializer(), payload), "application/json")))

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
            val server = consoleServer ?: FeedbackConsoleServer(feedbackService).also { consoleServer = it }
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

    private fun cacheAnnotation(packageName: String, annotation: JsonObject) {
        synchronized(cacheLock) {
            latestAnnotations[packageName] = annotation
            rememberCachedPackage(packageName)
        }
    }

    private fun cacheScreen(packageName: String, screen: JsonObject) {
        synchronized(cacheLock) {
            latestScreens[packageName] = screen
            rememberCachedPackage(packageName)
        }
    }

    private fun cacheStatus(packageName: String, status: JsonObject) {
        synchronized(cacheLock) {
            latestStatuses[packageName] = status
            rememberCachedPackage(packageName)
        }
    }

    private fun latestAnnotation(packageName: String): JsonObject? =
        synchronized(cacheLock) { latestAnnotations[packageName] }

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
            latestAnnotations.remove(evictedPackage)
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
                ?: throw PointPatchToolException("pointpatch_navigate_app requires action"),
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
            throw PointPatchToolException(
                "pointpatch_navigate_app does not support argument: ${unsupportedKeys.first()}",
            )
        }
    }

    private fun JsonObject.requiresNestedBridgeDetails(normalizedMatchingNodes: JsonArray?): Boolean {
        val normalizedKeys = setOf("found", "matchingNodes")
        return keys.any { it !in normalizedKeys } ||
            booleanParam("found") == null ||
            normalizedMatchingNodes == null
    }

    private fun JsonObject?.hasScreenshotArtifact(vararg pathKeys: String): Boolean =
        screenshotArtifactPath(*pathKeys) != null

    private fun JsonObject?.screenshotArtifactPath(vararg pathKeys: String): String? {
        val screenshot = this?.get("screenshot") as? JsonObject
        return pathKeys.firstNotNullOfOrNull { key -> (screenshot?.get(key) as? JsonPrimitive)?.contentOrNull }
    }

    private fun JsonObject.toMarkdown(): String {
        val annotation = McpProtocol.json.decodeFromJsonElement<PointPatchAnnotation>(this)
        return PointPatchMarkdownFormatter.format(annotation)
    }

    private fun requestedSession(arguments: JsonObject): FeedbackSession {
        val sessionId = arguments.stringParam("sessionId")?.takeIf { it.isNotBlank() }
        return if (sessionId == null) {
            feedbackService.currentSession()
        } else {
            feedbackService.getSession(sessionId)
        }
    }

    private fun FeedbackSession.focusedOn(itemId: String?): FeedbackSession {
        val focusedItemId = itemId?.takeIf { it.isNotBlank() } ?: return this
        val item = items.firstOrNull { it.itemId == focusedItemId }
            ?: throw PointPatchToolException("Unknown feedback item: $focusedItemId")
        return copy(
            screens = screens.filter { it.screenId == item.screenId },
            items = listOf(item),
        )
    }

    private fun String.toFeedbackItemStatus(): FeedbackItemStatus =
        when (this) {
            "resolved" -> FeedbackItemStatus.RESOLVED
            "needs_clarification" -> FeedbackItemStatus.NEEDS_CLARIFICATION
            "wont_fix" -> FeedbackItemStatus.WONT_FIX
            else -> throw PointPatchToolException("Unsupported feedback resolution status: $this")
        }

    private fun String.toNavigationAction(): FeedbackNavigationAction =
        when (this) {
            "back" -> FeedbackNavigationAction.BACK
            "tap" -> FeedbackNavigationAction.TAP
            "swipe" -> FeedbackNavigationAction.SWIPE
            else -> throw PointPatchToolException("Unsupported navigation action: $this")
        }

    private fun String.toSwipeDirection(): FeedbackSwipeDirection =
        when (this) {
            "up" -> FeedbackSwipeDirection.UP
            "down" -> FeedbackSwipeDirection.DOWN
            "left" -> FeedbackSwipeDirection.LEFT
            "right" -> FeedbackSwipeDirection.RIGHT
            else -> throw PointPatchToolException("Unsupported swipe direction: $this")
        }

    private fun unavailable(message: String): JsonObject = buildJsonObject {
        put("available", false)
        put("message", message)
    }
}

interface PointPatchBridge {
    fun resolvePackageName(packageOverride: String?): String
    suspend fun status(packageName: String): JsonObject
    suspend fun inspectCurrentScreen(packageName: String): JsonObject
    suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject
    suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject
    suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
        error("PointPatch bridge does not support navigation")
    suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String? = null,
        screenId: String? = null,
        destinationDirectory: File? = null,
    ): JsonObject
}

class CliPointPatchBridge(private val client: BridgeClient) : PointPatchBridge {
    override fun resolvePackageName(packageOverride: String?): String =
        client.resolvePackageName(packageOverride)

    override suspend fun status(packageName: String): JsonObject =
        client.request(packageName, "status")

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject =
        client.request(packageName, "inspectCurrentScreen")

    override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject =
        client.startFeedbackCapture(packageName, timeoutMillis)

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

class PointPatchToolException(message: String) : RuntimeException(message)

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
    val session: FeedbackSession,
    val consoleUrl: String,
    val resumed: Boolean,
)

private val ToolDefinitions = listOf(
    ToolDefinition(
        name = "pointpatch_status",
        description = "Check whether the PointPatch sidekick bridge is reachable for the debug app.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_get_current_screen",
        description = "Inspect the current Compose screen through the PointPatch sidekick bridge.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
            "includeScreenshot" to booleanProperty("Whether to include the latest screenshot resource URI when available."),
            "includeSemantics" to booleanProperty("Whether the caller wants semantics data. V1 bridge inspection returns semantics nodes."),
            "maxNodes" to integerProperty("Maximum nodes requested by the caller. V1 bridge may return fewer or ignore this hint."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_get_ui_feedback",
        description = "Compatibility wrapper for single-item PointPatch feedback capture. Prefer pointpatch_open_feedback_console plus feedback queue tools for new workflows.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
            "instruction" to stringProperty("Instruction shown by the MCP client to the user before capture."),
            "timeoutMs" to integerProperty("Capture timeout in milliseconds."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_verify_ui_change",
        description = "Verify that expected UI text is present on the current screen through the PointPatch sidekick bridge.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
            "expectedText" to stringProperty("Text expected to appear on the current screen."),
            "role" to stringProperty("Optional semantic role hint, such as Button."),
            required = listOf("expectedText"),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_open_feedback_console",
        description = "Open or return the local PointPatch feedback console for the current MCP session.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
            "sessionId" to stringProperty("Exact feedback session id to reopen."),
            "newSession" to booleanProperty("Create a new session instead of resuming the current or latest persisted session."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_list_feedback_sessions",
        description = "List persisted PointPatch feedback sessions for this project.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Optional Android application id filter."),
            "includeClosed" to booleanProperty("Whether to include closed feedback sessions."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_capture_screen",
        description = "Capture the current Android screen into the active PointPatch feedback session.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_navigate_app",
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
        name = "pointpatch_list_feedback",
        description = "List feedback queue summaries for the active PointPatch feedback session.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_read_feedback",
        description = "Read the feedback queue as annotation JSON and Markdown.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Optional feedback item id to focus the returned payload."),
        ),
    ),
    ToolDefinition(
        name = "pointpatch_resolve_feedback",
        description = "Mark a feedback item as resolved, needing clarification, or not fixed.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to update."),
            "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
            "summary" to stringProperty("Agent summary shown in the console."),
            required = listOf("itemId", "status"),
        ),
    ),
)

private val ResourceDefinitions = listOf(
    ResourceDefinition("pointpatch://session/current", "Current PointPatch session", "Current bridge session and sidekick status."),
    ResourceDefinition("pointpatch://screen/current", "Current PointPatch screen", "Current inspected Compose screen."),
    ResourceDefinition("pointpatch://annotation/latest", "Latest PointPatch annotation", "Latest annotation captured by pointpatch_get_ui_feedback."),
    ResourceDefinition("pointpatch://screenshot/latest/full.png", "Latest full screenshot", "Desktop-readable latest full screenshot artifact path."),
    ResourceDefinition("pointpatch://screenshot/latest/crop.png", "Latest crop screenshot", "Desktop-readable latest crop screenshot artifact path."),
    ResourceDefinition("pointpatch://source-index", "PointPatch source index", "Source index availability reported by the sidekick bridge."),
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
