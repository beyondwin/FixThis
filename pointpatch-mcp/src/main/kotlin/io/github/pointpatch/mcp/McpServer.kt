package io.github.pointpatch.mcp

import io.github.pointpatch.cli.BridgeClient
import io.github.pointpatch.mcp.tools.CliPointPatchBridge
import io.github.pointpatch.mcp.tools.PointPatchTools
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

class McpServer(private val protocol: McpProtocol = McpProtocol()) {
    suspend fun run(input: InputStream, output: OutputStream, diagnostics: OutputStream) {
        diagnostics.writeDiagnostic("PointPatch MCP server started")
        val reader = input.bufferedReader(Charsets.UTF_8)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = protocol.handleLine(line) ?: continue
            writer.write(response)
            writer.newLine()
            writer.flush()
        }
    }

    private fun OutputStream.writeDiagnostic(message: String) {
        write((message + "\n").toByteArray(Charsets.UTF_8))
        flush()
    }
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
