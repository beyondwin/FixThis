package io.github.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FIXTHIS_CLI_VERSION = "0.6.0"

internal fun renderCliVersion(
    json: Boolean,
    cliVersion: String = FIXTHIS_CLI_VERSION,
    bridgeProtocolVersion: String = BridgeProtocolVersion,
): String = if (json) {
    fixThisJson.encodeToString(
        buildJsonObject {
            put("cliVersion", cliVersion)
            put("bridgeProtocolVersion", bridgeProtocolVersion)
        },
    ) + "\n"
} else {
    "fixthis $cliVersion (bridge protocol v$bridgeProtocolVersion)\n"
}

class VersionCommand : CoreCliktCommand(name = "version") {
    private val json by option("--json", help = "Print version as JSON").flag(default = false)
    override fun run() {
        echo(renderCliVersion(json = json).trimEnd())
    }
}
