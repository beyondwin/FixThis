package io.github.beyondwin.fixthis.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

class BridgeClientSocketRetryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retriesNegotiatedSocketNameWhenBridgeClosesBeforeResponding() = runBlocking {
        val adb = FakeAdbFacade()
        val bridgeSockets = listOf(
            ClosingBridgeSocket(),
            CapturingBridgeSocket(
                responsePayload = """
                    {
                      "id": "req_2",
                      "ok": true,
                      "result": {
                        "bridgeProtocolVersion": "1.3",
                        "activity": "MainActivity"
                      }
                    }
                """.trimIndent(),
            ),
        )
        val sockets = ArrayDeque(bridgeSockets)
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 + adb.forwarded.size },
            socketConnector = { sockets.removeFirst() },
        )

        val result = client.request("io.github.beyondwin.fixthis.sample", "status")

        assertEquals("MainActivity", result.getValue("activity").jsonPrimitive.content)
        assertEquals(
            listOf(
                34567 to "localabstract:fixthis_io.github.beyondwin.fixthis.sample",
                34568 to "localabstract:fixthis_io.github.beyondwin.fixthis.sample-1",
            ),
            adb.forwarded,
        )
        assertEquals(listOf(34567, 34568), adb.removedForwards)
    }

    private class ClosingBridgeSocket : BridgeSocket {
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val output: OutputStream = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        override fun close() = Unit
    }

    private class CapturingBridgeSocket(responsePayload: String) : BridgeSocket {
        override val input: InputStream = ByteArrayInputStream(frame(responsePayload))
        override val output: OutputStream = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
        override fun close() = Unit

        private fun frame(payload: String): ByteArray {
            val output = ByteArrayOutputStream()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            output.write((bytes.size ushr 24) and 0xff)
            output.write((bytes.size ushr 16) and 0xff)
            output.write((bytes.size ushr 8) and 0xff)
            output.write(bytes.size and 0xff)
            output.write(bytes)
            return output.toByteArray()
        }
    }

    private class FakeAdbFacade : AdbFacade {
        val forwarded: MutableList<Pair<Int, String>> = mutableListOf()
        val removedForwards: MutableList<Int> = mutableListOf()

        override fun devices(): List<AdbDevice> = listOf(AdbDevice("emulator-5554", "device"))

        override fun runAsCat(packageName: String, path: String): String {
            assertEquals("io.github.beyondwin.fixthis.sample", packageName)
            assertEquals("files/fixthis/session.json", path)
            return """
                {
                  "schemaVersion": "1.0",
                  "packageName": "io.github.beyondwin.fixthis.sample",
                  "socketName": "fixthis_io.github.beyondwin.fixthis.sample",
                  "socketAddress": "localabstract:fixthis_io.github.beyondwin.fixthis.sample",
                  "token": "token-1",
                  "sidekickVersion": "0.1.0",
                  "bridgeProtocolVersion": "1.3",
                  "createdAtEpochMillis": 1,
                  "processStartEpochMillis": 1
                }
            """.trimIndent()
        }

        override fun forward(localPort: Int, socketAddress: String) {
            forwarded += localPort to socketAddress
        }

        override fun removeForward(localPort: Int) {
            removedForwards += localPort
        }

        override fun pull(androidPath: String, destination: File) = Unit

        override fun launchApp(packageName: String) = Unit
    }
}
