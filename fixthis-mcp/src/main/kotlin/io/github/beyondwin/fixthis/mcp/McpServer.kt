package io.github.beyondwin.fixthis.mcp

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import io.github.beyondwin.fixthis.mcp.tools.FixThisTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

class McpServer(private val protocol: McpProtocol = McpProtocol()) {
    suspend fun run(input: InputStream, output: OutputStream, diagnostics: OutputStream) {
        diagnostics.writeDiagnostic("FixThis MCP server started")
        val reader = input.bufferedReader(Charsets.UTF_8)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val writeMutex = Mutex()
        val registry = InFlightRegistry()

        suspend fun writeResponse(response: String) {
            writeMutex.withLock {
                writer.write(response)
                writer.newLine()
                writer.flush()
            }
        }

        coroutineScope {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                when (val message = protocol.decodeLine(line)) {
                    is McpIncoming.ImmediateResponse -> writeResponse(message.response)
                    is McpIncoming.Notification -> handleNotification(message, registry)
                    is McpRequest -> {
                        if (!message.method.isCancellableRequest()) {
                            protocol.handleRequest(message)?.let { response -> writeResponse(response) }
                            continue
                        }
                        trackRequest(
                            message = message,
                            context = RequestTrackerContext(
                                scope = this,
                                registry = registry,
                                writeResponse = ::writeResponse,
                                diagnosticsLog = { diagnostics.writeDiagnostic(it) },
                                handleRequest = { protocol.handleRequest(it) },
                            ),
                        )
                    }
                }
            }
            cancelInFlightRequests(registry)
        }
    }

    /**
     * Launch the handler for a cancellable [message] and register the resulting
     * [Job] under the request id so that `notifications/cancelled` can find it.
     *
     * The launch uses [CoroutineStart.UNDISPATCHED] so the body starts on the
     * caller's thread, but if it suspends or completes immediately the job may
     * already be inactive by the time we reach [InFlightRegistry.register].
     * The double `isActive` check below preserves the original behaviour: skip
     * registration entirely when the job has already terminated, and otherwise
     * remove a stale entry that the `finally` block raced past.
     */
    internal suspend fun trackRequest(
        message: McpRequest,
        context: RequestTrackerContext,
    ) {
        val requestJob = context.scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                val response = context.handleRequest(message) ?: return@launch
                context.writeResponse(response)
            } catch (error: CancellationException) {
                context.diagnosticsLog("Cancelled MCP request ${message.idKey}")
            } finally {
                context.registry.remove(message.idKey)
            }
        }
        if (requestJob.isActive) {
            context.registry.register(message.idKey, InFlightRequest(message.method, requestJob))
            if (!requestJob.isActive) {
                context.registry.remove(message.idKey)
            }
        }
    }

    /** Test-only entry point that exposes [trackRequest] without touching production diagnostics. */
    internal suspend fun trackRequestForTest(
        message: McpRequest,
        registry: InFlightRegistry,
        writeResponse: suspend (String) -> Unit,
        handleRequest: suspend (McpRequest) -> String? = { protocol.handleRequest(it) },
        diagnosticsLog: (String) -> Unit = { /* test no-op */ },
    ) = coroutineScope {
        trackRequest(
            message = message,
            context = RequestTrackerContext(
                scope = this,
                registry = registry,
                writeResponse = writeResponse,
                diagnosticsLog = diagnosticsLog,
                handleRequest = handleRequest,
            ),
        )
    }

    internal data class RequestTrackerContext(
        val scope: CoroutineScope,
        val registry: InFlightRegistry,
        val writeResponse: suspend (String) -> Unit,
        val diagnosticsLog: (String) -> Unit,
        val handleRequest: suspend (McpRequest) -> String?,
    )

    private fun OutputStream.writeDiagnostic(message: String) {
        write((message + "\n").toByteArray(Charsets.UTF_8))
        flush()
    }

    private suspend fun handleNotification(
        notification: McpIncoming.Notification,
        registry: InFlightRegistry,
    ) {
        if (notification.method != "notifications/cancelled") return
        val requestId = (notification.params["requestId"] as? JsonPrimitive).validRequestIdOrNull() ?: return
        val requestKey = requestId.requestIdKey()
        val request = registry.remove(requestKey) ?: return
        if (request.method == "initialize") return
        request.job.cancel(CancellationException("MCP request cancelled"))
    }

    private suspend fun cancelInFlightRequests(registry: InFlightRegistry) {
        val requests = registry.consumeAll()
        requests.forEach { request ->
            request.job.cancelAndJoin()
        }
    }

    private fun String.isCancellableRequest(): Boolean = this == "tools/call" || this == "resources/read"
}

fun main(args: Array<String>) {
    val options = runCatching { McpOptions.parse(args) }.getOrElse { error ->
        System.err.println(error.message ?: error::class.java.simpleName)
        exitProcess(2)
    }
    if (options.consoleMode) {
        val bridge = CliFixThisBridge(BridgeClient(projectRoot = options.projectDir))
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = options.packageName,
            projectRoot = options.projectDir,
            consoleAssetsDir = options.consoleAssetsDir,
            consolePort = options.consolePort,
        )
        val result = runBlocking {
            tools.call("fixthis_open_feedback_console", JsonObject(emptyMap()))
        }
        val startup = consoleStartupResult(result)
        if (startup.isError) {
            System.err.println(startup.text)
            exitProcess(1)
        }
        System.out.println(startup.text)
        Thread.currentThread().join()
        return
    }
    val bridge = CliFixThisBridge(BridgeClient(projectRoot = options.projectDir))
    val tools = fixThisToolsForOptions(options, bridge)
    runBlocking {
        McpServer(McpProtocol(tools)).run(
            input = System.`in`,
            output = System.out,
            diagnostics = System.err,
        )
    }
}

internal fun fixThisToolsForOptions(options: McpOptions, bridge: CliFixThisBridge): FixThisTools = FixThisTools(
    bridge = bridge,
    defaultPackageName = options.packageName,
    projectRoot = options.projectDir,
    consoleAssetsDir = options.consoleAssetsDir,
    consolePort = options.consolePort,
)

internal data class ConsoleStartupResult(val isError: Boolean, val text: String)

internal fun consoleStartupResult(result: JsonObject): ConsoleStartupResult {
    val text = result["content"]?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("text")?.jsonPrimitive?.contentOrNull
        ?: error("Console tool did not return JSON content")
    val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
    return ConsoleStartupResult(isError = isError, text = text)
}

internal data class McpOptions(
    val packageName: String?,
    val projectDir: File,
    val consoleMode: Boolean,
    val consoleAssetsDir: File?,
    val consolePort: Int = 0,
) {
    companion object {
        fun parse(args: Array<String>): McpOptions {
            var packageName: String? = null
            var projectDir = File(".").canonicalFile
            var consoleAssetsDir: File? = null
            var consolePort = 0
            var consoleMode = false
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--console" -> {
                        consoleMode = true
                        index += 1
                    }
                    "--package" -> {
                        packageName = args.getOrNull(index + 1)
                            ?: throw IllegalArgumentException("--package requires a value")
                        index += 2
                    }
                    "--project-dir" -> {
                        projectDir = File(
                            args.getOrNull(index + 1)
                                ?: throw IllegalArgumentException("--project-dir requires a value"),
                        ).canonicalFile
                        index += 2
                    }
                    "--console-assets-dir" -> {
                        consoleAssetsDir = File(
                            args.getOrNull(index + 1)
                                ?: throw IllegalArgumentException("--console-assets-dir requires a value"),
                        ).canonicalFile
                        index += 2
                    }
                    "--console-port" -> {
                        val rawPort = args.getOrNull(index + 1)
                            ?: throw IllegalArgumentException("--console-port requires a value")
                        consolePort = rawPort.toIntOrNull()
                            ?.takeIf { it in 0..65535 }
                            ?: throw IllegalArgumentException("--console-port must be between 0 and 65535")
                        index += 2
                    }
                    else -> throw IllegalArgumentException("Unknown fixthis-mcp argument: $arg")
                }
            }
            return McpOptions(
                packageName = packageName,
                projectDir = projectDir,
                consoleMode = consoleMode,
                consoleAssetsDir = consoleAssetsDir,
                consolePort = consolePort,
            )
        }
    }
}
