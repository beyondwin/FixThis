package io.github.beyondwin.fixthis.cli

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BridgeClientDeviceScopeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unselectedRequestScopesToOnlyReadyDeviceWhenOfflineDeviceIsPresent() = runBlocking {
        val adb = DeviceScopeAdbFacade(
            devices = listOf(
                AdbDevice("emulator-5556", "device"),
                AdbDevice("emulator-5554", "offline"),
            ),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { DeviceScopeBridgeSocket() },
        )

        client.request("io.github.beyondwin.fixthis.sample", "status")

        assertEquals(null, client.selectedDeviceSerial())
        assertEquals(listOf("emulator-5556"), adb.runAsSerials)
        assertEquals(listOf("emulator-5556"), adb.forwardSerials)
        assertEquals(listOf("emulator-5556"), adb.removeForwardSerials)
    }

    private class DeviceScopeBridgeSocket : BridgeSocket {
        override val input = ByteArrayInputStream(
            frame(
                """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "bridgeProtocolVersion": "1.3",
                    "activity": "MainActivity"
                  }
                }
                """.trimIndent(),
            ),
        )
        override val output = ByteArrayOutputStream()
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

    private class DeviceScopeAdbFacade(
        private val devices: List<AdbDevice>,
        private val selectedSerial: String? = null,
        val runAsSerials: MutableList<String?> = mutableListOf(),
        val forwardSerials: MutableList<String?> = mutableListOf(),
        val removeForwardSerials: MutableList<String?> = mutableListOf(),
    ) : AdbFacade {
        override fun devices(): List<AdbDevice> = devices

        override fun forDevice(serial: String?): AdbFacade = DeviceScopeAdbFacade(
            devices = devices,
            selectedSerial = serial,
            runAsSerials = runAsSerials,
            forwardSerials = forwardSerials,
            removeForwardSerials = removeForwardSerials,
        )

        override fun runAsCat(packageName: String, path: String): String {
            runAsSerials += selectedSerial
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

        override fun currentFocusOutput(): String? = null

        override fun forward(localPort: Int, socketAddress: String) {
            forwardSerials += selectedSerial
        }

        override fun removeForward(localPort: Int) {
            removeForwardSerials += selectedSerial
        }

        override fun pull(androidPath: String, destination: File) = Unit

        override fun launchApp(packageName: String) = Unit
    }
}
