package io.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.beyondwin.fixthis.cli.commands.ConsoleCommand
import io.beyondwin.fixthis.cli.commands.DoctorCommand
import io.beyondwin.fixthis.cli.commands.McpCommand
import io.beyondwin.fixthis.cli.commands.RunCommand
import io.beyondwin.fixthis.cli.commands.SetupCommand
import io.beyondwin.fixthis.cli.commands.StatusCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = CoreNoOpCliktCommand(name = "fixthis")
        .subcommands(
            StatusCommand(),
            RunCommand(),
            DoctorCommand(),
            SetupCommand(),
            McpCommand(),
            ConsoleCommand(),
        )
    try {
        command.parse(args)
    } catch (error: CliktError) {
        error.message?.takeIf { it.isNotBlank() }?.let { System.err.println(it) }
        exitProcess(error.statusCode)
    }
}
