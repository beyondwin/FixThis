package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.beyondwin.fixthis.cli.Adb
import io.github.beyondwin.fixthis.cli.BridgeClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class StatusCommand : CoreCliktCommand(name = "status") {
    private val packageName by option("--package", help = "Android application id to connect to")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")

    override fun run() {
        val root = File(projectDir).canonicalFile
        val client = BridgeClient(adb = Adb.forProject(root), projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }

        echo("package: $resolvedPackage")
        val status = failAsCliError {
            runBlocking {
                client.request(resolvedPackage, "status")
            }
        }

        echo("sidekick: connected")
        echo("activity: ${status["activity"]?.jsonPrimitive?.contentOrNull ?: "(none)"}")
        echo("roots: ${status["rootsCount"]?.jsonPrimitive?.intOrNull ?: 0}")
        echo("sidekickVersion: ${status["sidekickVersion"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"}")
        echo("bridgeProtocolVersion: ${status["bridgeProtocolVersion"]?.jsonPrimitive?.contentOrNull ?: "(unknown)"}")
        echo("sourceIndexAvailable: ${status["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull ?: false}")
    }
}
