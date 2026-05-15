package io.beyondwin.fixthis.cli.bridge

import io.beyondwin.fixthis.cli.AdbFacade
import io.beyondwin.fixthis.cli.BridgeConnectionException
import io.beyondwin.fixthis.cli.BridgeProtocolException
import io.beyondwin.fixthis.cli.fixThisJson

private const val SessionPath = "files/fixthis/session.json"

internal class BridgeSessionReader(
    private val expectedProtocolVersion: String,
) {
    fun read(adb: AdbFacade, packageName: String): SidekickSession = runCatching {
        fixThisJson.decodeFromString(
            SidekickSession.serializer(),
            adb.runAsCat(packageName, SessionPath),
        )
    }.getOrElse { error ->
        throw BridgeConnectionException(
            "Could not read FixThis bridge session via adb shell run-as $packageName cat $SessionPath: ${error.message}",
        )
    }.also { session ->
        if (session.bridgeProtocolVersion != expectedProtocolVersion) {
            throw BridgeProtocolException(
                "FixThis bridge protocol ${session.bridgeProtocolVersion} is incompatible with CLI protocol $expectedProtocolVersion",
            )
        }
    }
}
