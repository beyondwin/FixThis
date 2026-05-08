package io.beyondwin.fixthis.mcp

import io.beyondwin.fixthis.cli.BridgeClient
import io.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import io.beyondwin.fixthis.mcp.tools.FixThisTools
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.system.exitProcess

class McpServer(private val protocol: McpProtocol = McpProtocol()) {
    suspend fun run(input: InputStream, output: OutputStream, diagnostics: OutputStream) {
        diagnostics.writeDiagnostic("FixThis MCP server started")
        val reader = input.bufferedReader(Charsets.UTF_8)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val writeMutex = Mutex()
        val inFlight = mutableMapOf<String, InFlightRequest>()

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
                    is McpIncoming.Notification -> handleNotification(message, inFlight)
                    is McpRequest -> {
                        if (!message.method.isCancellableRequest()) {
                            protocol.handleRequest(message)?.let { response -> writeResponse(response) }
                            continue
                        }
                        val requestJob = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            try {
                                val response = protocol.handleRequest(message) ?: return@launch
                                writeResponse(response)
                            } catch (error: CancellationException) {
                                diagnostics.writeDiagnostic("Cancelled MCP request ${message.idKey}")
                            } finally {
                                synchronized(inFlight) {
                                    inFlight.remove(message.idKey)
                                }
                            }
                        }
                        if (requestJob.isActive) {
                            synchronized(inFlight) {
                                inFlight[message.idKey] = InFlightRequest(message.method, requestJob)
                                if (!requestJob.isActive) {
                                    inFlight.remove(message.idKey)
                                }
                            }
                        }
                    }
                }
            }
            cancelInFlightRequests(inFlight)
        }
    }

    private fun OutputStream.writeDiagnostic(message: String) {
        write((message + "\n").toByteArray(Charsets.UTF_8))
        flush()
    }

    private fun handleNotification(
        notification: McpIncoming.Notification,
        inFlight: MutableMap<String, InFlightRequest>,
    ) {
        if (notification.method != "notifications/cancelled") return
        val requestId = (notification.params["requestId"] as? JsonPrimitive).validRequestIdOrNull() ?: return
        val requestKey = requestId.requestIdKey()
        val request = synchronized(inFlight) { inFlight.remove(requestKey) } ?: return
        if (request.method == "initialize") return
        request.job.cancel(CancellationException("MCP request cancelled"))
    }

    private suspend fun cancelInFlightRequests(inFlight: MutableMap<String, InFlightRequest>) {
        val requests = synchronized(inFlight) {
            inFlight.values.toList().also { inFlight.clear() }
        }
        requests.forEach { request ->
            request.job.cancelAndJoin()
        }
    }

    private fun String.isCancellableRequest(): Boolean =
        this == "tools/call" || this == "resources/read"

    private data class InFlightRequest(val method: String, val job: Job)
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

internal fun fixThisToolsForOptions(options: McpOptions, bridge: CliFixThisBridge): FixThisTools =
    FixThisTools(
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
