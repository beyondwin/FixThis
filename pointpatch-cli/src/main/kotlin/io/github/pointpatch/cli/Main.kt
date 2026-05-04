package io.github.pointpatch.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.github.pointpatch.cli.commands.DoctorCommand
import io.github.pointpatch.cli.commands.McpCommand
import io.github.pointpatch.cli.commands.RunCommand
import io.github.pointpatch.cli.commands.SetupCommand
import io.github.pointpatch.cli.commands.StatusCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = CoreNoOpCliktCommand(name = "pointpatch")
        .subcommands(
            StatusCommand(),
            RunCommand(),
            DoctorCommand(),
            SetupCommand(),
            McpCommand(),
        )
    try {
        command.parse(args)
    } catch (error: CliktError) {
        error.message?.takeIf { it.isNotBlank() }?.let { System.err.println(it) }
        exitProcess(error.statusCode)
    }
}
