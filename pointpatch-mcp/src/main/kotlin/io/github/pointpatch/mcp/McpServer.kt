package io.github.pointpatch.mcp

import io.github.pointpatch.cli.BridgeClient
import io.github.pointpatch.mcp.tools.CliPointPatchBridge
import io.github.pointpatch.mcp.tools.PointPatchTools
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlin.system.exitProcess

class McpServer(private val protocol: McpProtocol = McpProtocol()) {
    suspend fun run(input: InputStream, output: OutputStream, diagnostics: OutputStream) {
        diagnostics.writeDiagnostic("PointPatch MCP server started")
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
                        lateinit var requestJob: Job
                        requestJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            try {
                                val response = protocol.handleRequest(message) ?: return@launch
                                writeResponse(response)
                            } catch (error: CancellationException) {
                                diagnostics.writeDiagnostic("Cancelled MCP request ${message.idKey}")
                            } finally {
                                synchronized(inFlight) {
                                    if (inFlight[message.idKey]?.job === requestJob) {
                                        inFlight.remove(message.idKey)
                                    }
                                }
                            }
                        }
                        synchronized(inFlight) {
                            inFlight[message.idKey] = InFlightRequest(message.method, requestJob)
                        }
                        requestJob.start()
                    }
                }
            }
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

    private data class InFlightRequest(val method: String, val job: Job)
}

fun main(args: Array<String>) {
    val options = runCatching { McpOptions.parse(args) }.getOrElse { error ->
        System.err.println(error.message ?: error::class.java.simpleName)
        exitProcess(2)
    }
    val bridge = CliPointPatchBridge(BridgeClient(projectRoot = options.projectDir))
    val tools = PointPatchTools(bridge = bridge, defaultPackageName = options.packageName)
    runBlocking {
        McpServer(McpProtocol(tools)).run(
            input = System.`in`,
            output = System.out,
            diagnostics = System.err,
        )
    }
}

private data class McpOptions(val packageName: String?, val projectDir: File) {
    companion object {
        fun parse(args: Array<String>): McpOptions {
            var packageName: String? = null
            var projectDir = File(".").canonicalFile
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
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
                    else -> throw IllegalArgumentException("Unknown pointpatch-mcp argument: $arg")
                }
            }
            return McpOptions(packageName = packageName, projectDir = projectDir)
        }
    }
}
