package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.cli.BridgeClient
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationRequest
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbe
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

private fun defaultFeedbackSessionService(
    bridge: FixThisBridge,
    defaultPackageName: String?,
    projectRoot: File,
): FeedbackSessionService {
    val feedbackSessionPaths = FeedbackSessionPaths(projectRoot)
    return FeedbackSessionService(
        bridge = bridge,
        store = FeedbackSessionStore(
            persistence = FeedbackSessionPersistence(feedbackSessionPaths),
            eventLogWriterProvider = { sessionId ->
                EventLogWriter(feedbackSessionPaths.eventLogDirectory(sessionId))
            },
            eventLogReaderProvider = { sessionId ->
                EventLogReader(feedbackSessionPaths.eventLogDirectory(sessionId))
            },
        ),
        projectRoot = projectRoot.absolutePath,
        defaultPackageName = defaultPackageName,
    )
}

// Public construction surface is intentionally broad: CLI, MCP tests, and console
// startup inject different seams without requiring a separate container.
@Suppress("LongParameterList")
class FixThisTools(
    private val bridge: FixThisBridge = CliFixThisBridge(BridgeClient()),
    private val defaultPackageName: String? = null,
    private val projectRoot: File = File(".").canonicalFile,
    private val feedbackService: FeedbackSessionService = defaultFeedbackSessionService(
        bridge = bridge,
        defaultPackageName = defaultPackageName,
        projectRoot = projectRoot,
    ),
    private val consoleAssetsDir: File? = null,
    private val consolePort: Int = 0,
    private val freshnessProbe: HostSourceFreshnessProbe = HostSourceFreshnessProbe(projectRoot),
) {
    private val cache = BridgeResultCache(defaultPackageName = defaultPackageName)
    private val consoleManager = ConsoleServerManager(
        service = feedbackService,
        consoleAssetsDir = consoleAssetsDir,
        consolePort = consolePort,
    )
    private val toolDispatcher = FixThisToolDispatcher(
        ports = FixThisToolBridgePorts(
            packageResolver = bridge,
            screenBridge = bridge,
            sourceIndexBridge = bridge,
        ),
        services = FixThisToolDispatcherServices(
            defaultPackageName = defaultPackageName,
            projectRoot = projectRoot,
            feedbackService = feedbackService,
            cache = cache,
            freshnessProbe = freshnessProbe,
            consoleManager = consoleManager,
        ),
    )
    private val resourceDispatcher = FixThisResourceDispatcher(
        packageResolver = bridge,
        screenBridge = bridge,
        defaultPackageName = defaultPackageName,
        cache = cache,
    )

    fun listTools(): JsonArray = McpToolRegistry.listTools()

    fun listResources(): JsonArray = McpToolRegistry.listResources()

    fun close() {
        consoleManager.stop()
    }

    suspend fun call(name: String, arguments: JsonObject): JsonObject = toolDispatcher.call(name, arguments)

    suspend fun readResource(uri: String): JsonObject = resourceDispatcher.read(uri)
}

interface PackageResolver {
    fun resolvePackageName(packageOverride: String?): String
}

interface DeviceBridge {
    fun devices(): List<AdbDevice> = emptyList()
    fun selectedDeviceSerial(): String? = null
    fun selectDevice(serial: String) = Unit
    fun disconnectDevice() = Unit
}

interface AppRuntimeBridge {
    fun launchApp(packageName: String) = Unit
}

interface ScreenBridge {
    suspend fun status(packageName: String): JsonObject
    suspend fun heartbeat(packageName: String): JsonObject = status(packageName)
    suspend fun inspectCurrentScreen(packageName: String): JsonObject
    suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject
    suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String? = null,
        screenId: String? = null,
        destinationDirectory: File? = null,
    ): JsonObject
}

interface NavigationBridge {
    suspend fun performNavigation(
        packageName: String,
        request: FeedbackNavigationRequest,
    ): JsonObject = error("FixThis bridge does not support navigation")
}

interface SourceIndexBridge {
    suspend fun readSourceIndex(packageName: String): JsonObject = JsonObject(emptyMap())
}

interface FixThisBridge :
    PackageResolver,
    DeviceBridge,
    AppRuntimeBridge,
    ScreenBridge,
    NavigationBridge,
    SourceIndexBridge

class CliFixThisBridge(private val client: BridgeClient) : FixThisBridge {
    override fun resolvePackageName(packageOverride: String?): String = client.resolvePackageName(packageOverride)

    override fun devices(): List<AdbDevice> = client.devices()

    override fun selectedDeviceSerial(): String? = client.selectedDeviceSerial()

    override fun selectDevice(serial: String) = client.selectDevice(serial)

    override fun disconnectDevice() = client.disconnectDevice()

    override fun launchApp(packageName: String) = client.launchApp(packageName)

    override suspend fun status(packageName: String): JsonObject = client.request(packageName, "status")

    override suspend fun heartbeat(packageName: String): JsonObject = client.request(packageName, "heartbeat")

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = client.request(
        packageName,
        "inspectCurrentScreen",
    )

    override suspend fun verifyUiChange(
        packageName: String,
        expectedText: String,
        role: String?,
    ): JsonObject = client.request(
        packageName = packageName,
        method = "verifyUiChange",
        params = buildJsonObject {
            put("expectedText", expectedText)
            role?.let { put("role", it) }
        },
    )

    override suspend fun performNavigation(
        packageName: String,
        request: FeedbackNavigationRequest,
    ): JsonObject = client.performNavigation(
        packageName = packageName,
        request = McpProtocol.json.encodeToJsonElement(FeedbackNavigationRequest.serializer(), request).jsonObject,
    )

    override suspend fun readSourceIndex(packageName: String): JsonObject = client.readSourceIndex(packageName)

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = client.captureScreenSnapshot(
        packageName = packageName,
        sessionId = sessionId,
        screenId = screenId,
        destinationDirectory = destinationDirectory,
    )
}

class FixThisToolException(message: String) : RuntimeException(message)
