package io.github.beyondwin.fixthis.cli.bridge

import io.github.beyondwin.fixthis.cli.AdbFacade
import io.github.beyondwin.fixthis.cli.BridgeConnectionException
import io.github.beyondwin.fixthis.cli.BridgeProtocolException
import io.github.beyondwin.fixthis.cli.fixThisJson

private const val SESSION_PATH = "files/fixthis/session.json"

internal class BridgeSessionReader(
    private val expectedProtocolVersion: String,
) {
    fun read(adb: AdbFacade, packageName: String): SidekickSession = runCatching {
        fixThisJson.decodeFromString(
            SidekickSession.serializer(),
            adb.runAsCat(packageName, SESSION_PATH),
        )
    }.getOrElse { error ->
        throw BridgeConnectionException(
            "Could not read FixThis bridge session via adb shell run-as $packageName " +
                "cat $SESSION_PATH: ${error.message}",
        )
    }.also { session ->
        if (session.bridgeProtocolVersion != expectedProtocolVersion) {
            throw BridgeProtocolException(
                "FixThis bridge protocol ${session.bridgeProtocolVersion} is incompatible " +
                    "with CLI protocol $expectedProtocolVersion",
            )
        }
    }
}
