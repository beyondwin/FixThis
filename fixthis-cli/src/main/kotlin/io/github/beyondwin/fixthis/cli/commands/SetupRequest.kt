package io.github.beyondwin.fixthis.cli.commands

import java.io.File

internal data class SetupRequest(
    val packageName: String?,
    val projectRoot: File,
    val target: String,
    val serverName: String,
    val write: Boolean,
    val dryRun: Boolean,
    val fullDiff: Boolean = false,
    val verbose: Boolean = false,
)

internal data class InstallRequest(
    val packageName: String?,
    val projectRoot: File,
    val target: String,
    val serverName: String,
    val applyGradlePlugin: Boolean,
    val pluginVersion: String,
    val dryRun: Boolean,
    val verbose: Boolean,
)
