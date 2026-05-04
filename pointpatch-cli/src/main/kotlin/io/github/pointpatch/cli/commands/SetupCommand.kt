package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.pointpatch.cli.BridgeClient
import io.github.pointpatch.cli.pointPatchJson
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        val root = File(projectDir).canonicalFile
        val client = BridgeClient(projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }
        val command = File(System.getProperty("java.class.path"))
        val config = buildJsonObject {
            put("command", "pointpatch")
            put("args", "mcp --package $resolvedPackage --project-dir ${root.absolutePath}")
            put("packageName", resolvedPackage)
            put("projectRoot", root.absolutePath)
            put("note", "Task 12 will provide the MCP stdio server implementation.")
            put("classpathHint", command.path)
        }
        echo(pointPatchJson.encodeToString(config))
    }
}

class McpCommand : CoreCliktCommand(name = "mcp") {
    private val packageName by option("--package", help = "Android application id")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        System.err.println(
            "pointpatch mcp is reserved for the Task 12 MCP server. " +
                "Requested package=${packageName ?: "(from project config)"}, projectDir=$projectDir",
        )
        throw com.github.ajalt.clikt.core.CliktError("MCP server is not implemented in Task 11")
    }
}
