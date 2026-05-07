package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import kotlin.system.exitProcess

class ConsoleCommand : CoreCliktCommand(name = "console") {
    private val packageName by option("--package", help = "Android application id")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")

    override fun run() {
        val executable = McpExecutableLocator.find()
            ?: throw CliktError(
                "Could not find fixthis-mcp executable. Run :fixthis-mcp:installDist or add fixthis-mcp to PATH.",
            )
        val command = buildConsoleProcessCommand(
            executable = executable,
            packageName = packageName,
            projectDir = File(projectDir).canonicalPath,
        )
        val exitCode = ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor()
        exitProcess(exitCode)
    }
}

internal fun buildConsoleProcessCommand(
    executable: File,
    packageName: String?,
    projectDir: String,
): List<String> = buildList {
    add(executable.absolutePath)
    add("--console")
    packageName?.let {
        add("--package")
        add(it)
    }
    add("--project-dir")
    add(projectDir)
}
