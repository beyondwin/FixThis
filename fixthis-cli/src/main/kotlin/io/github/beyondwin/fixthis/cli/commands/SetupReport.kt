package io.github.beyondwin.fixthis.cli.commands

internal class SetupReport {
    val applied = mutableListOf<InstallAgentJsonReport.Applied>()
    val skipped = mutableListOf<InstallAgentJsonReport.Skipped>()
    val errors = mutableListOf<InstallAgentJsonReport.ErrorEntry>()
}
