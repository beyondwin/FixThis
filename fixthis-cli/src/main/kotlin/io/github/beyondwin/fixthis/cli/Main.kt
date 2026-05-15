package io.github.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.github.beyondwin.fixthis.cli.commands.CleanCommand
import io.github.beyondwin.fixthis.cli.commands.ConsoleCommand
import io.github.beyondwin.fixthis.cli.commands.DoctorCommand
import io.github.beyondwin.fixthis.cli.commands.InitCommand
import io.github.beyondwin.fixthis.cli.commands.InstallAgentCommand
import io.github.beyondwin.fixthis.cli.commands.McpCommand
import io.github.beyondwin.fixthis.cli.commands.RunCommand
import io.github.beyondwin.fixthis.cli.commands.SetupCommand
import io.github.beyondwin.fixthis.cli.commands.SetupErrorRedactor
import io.github.beyondwin.fixthis.cli.commands.StatusCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = buildRootCommand()
    try {
        command.parse(args)
    } catch (error: CliktError) {
        printCliktError(command, error)
        exitProcess(error.statusCode)
    }
}

internal fun buildRootCommand(): CoreNoOpCliktCommand = CoreNoOpCliktCommand(name = "fixthis")
    .subcommands(
        StatusCommand(),
        RunCommand(),
        DoctorCommand(),
        InitCommand(),
        InstallAgentCommand(),
        SetupCommand(),
        McpCommand(),
        ConsoleCommand(),
        CleanCommand(),
    )

internal fun printCliktError(command: CoreNoOpCliktCommand, error: CliktError) {
    command.getFormattedHelp(error)
        ?.takeIf { it.isNotBlank() }
        ?.let { message ->
            if (error.printError) {
                System.err.println(message)
            } else {
                System.out.println(message)
            }
        }
    if (DiagnosticContext.verbose) {
        error.cause?.let {
            // Defense-in-depth: stack traces can contain home paths or echoed
            // settings.json fragments. Route through SetupErrorRedactor before
            // printing.
            System.err.println(SetupErrorRedactor.redact(it.stackTraceToString()))
        }
    }
}

// Test-only convenience: skips command construction by reusing buildRootCommand.
internal fun renderCliktErrorForTest(error: CliktError) {
    printCliktError(buildRootCommand(), error)
}
