package io.github.beyondwin.fixthis.cli.bridge

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.AdbFacade
import io.github.beyondwin.fixthis.cli.BridgeConnectionException
import io.github.beyondwin.fixthis.cli.BridgeProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeSessionReaderTest {
    @Test
    fun readsSessionAndValidatesProtocol() {
        val reader = BridgeSessionReader(expectedProtocolVersion = "1.3")

        val session = reader.read(FakeAdb(sessionJson(protocol = "1.3")), "io.app")

        assertEquals("io.app", session.packageName)
        assertEquals("fixthis_io.app", session.socketName)
    }

    @Test
    fun rejectsUnsupportedProtocolBeforeForwarding() {
        val error = kotlin.runCatching {
            BridgeSessionReader(expectedProtocolVersion = "1.3")
                .read(FakeAdb(sessionJson(protocol = "2.0")), "io.app")
        }.exceptionOrNull()

        assertTrue(error is BridgeProtocolException)
        assertEquals("FixThis bridge protocol 2.0 is incompatible with CLI protocol 1.3", error?.message)
    }

    @Test
    fun wrapsUnreadableSessionJson() {
        val error = kotlin.runCatching {
            BridgeSessionReader(expectedProtocolVersion = "1.3").read(FakeAdb("not-json"), "io.app")
        }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
        assertTrue(
            error?.message.orEmpty()
                .startsWith("Could not read FixThis bridge session via adb shell run-as io.app cat"),
        )
    }

    private class FakeAdb(private val sessionJson: String) : AdbFacade {
        override fun devices(): List<AdbDevice> = listOf(AdbDevice("device-1", "device"))
        override fun runAsCat(packageName: String, path: String): String = sessionJson
        override fun forward(localPort: Int, socketAddress: String) = Unit
        override fun removeForward(localPort: Int) = Unit
        override fun pull(androidPath: String, destination: File) = Unit
        override fun launchApp(packageName: String) = Unit
    }

    private companion object {
        fun sessionJson(protocol: String): String = """
            {
              "schemaVersion": "1.0",
              "packageName": "io.app",
              "socketName": "fixthis_io.app",
              "socketAddress": "localabstract:fixthis_io.app",
              "token": "token-1",
              "sidekickVersion": "0.1.0",
              "bridgeProtocolVersion": "$protocol",
              "createdAtEpochMillis": 1,
              "processStartEpochMillis": 1
            }
        """.trimIndent()
    }
}
