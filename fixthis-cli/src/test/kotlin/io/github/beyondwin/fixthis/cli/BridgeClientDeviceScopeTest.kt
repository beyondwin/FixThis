package io.github.beyondwin.fixthis.cli

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.collectRuntimeEvidence
import io.github.beyondwin.fixthis.cli.runtime.runtimeEvidenceContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun runtimeEvidencePinsContextBridgeAndCollectorCommandsToOneDeviceScope() {
        val adb = DeviceScopeAdbFacade(
            devices = listOf(AdbDevice("emulator-5554", "device")),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { DeviceScopeBridgeSocket() },
        )

        val context = client.runtimeEvidenceContext("io.github.beyondwin.fixthis.sample")
        client.collectRuntimeEvidence(
            packageName = "io.github.beyondwin.fixthis.sample",
            kind = CliRuntimeEvidenceKind.MEMORY_SUMMARY,
            screenCapturedAtEpochMillis = 1L,
        )

        assertEquals("emulator-5554", context.deviceSerial)
        assertTrue(adb.executeSerials.isNotEmpty())
        assertTrue(adb.executeSerials.all { it == "emulator-5554" })
        assertTrue(adb.runAsSerials.all { it == "emulator-5554" })
        assertTrue(adb.forwardSerials.all { it == "emulator-5554" })
        assertTrue(adb.removeForwardSerials.all { it == "emulator-5554" })
    }

    @Test
    fun runtimeEvidencePinnedSerialSurvivesSelectionChangeAcrossCapturePhases() {
        val adb = DeviceScopeAdbFacade(
            devices = listOf(
                AdbDevice("emulator-5554", "device"),
                AdbDevice("emulator-5556", "device"),
            ),
        )
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = { DeviceScopeBridgeSocket() },
        )
        client.selectDevice("emulator-5554")
        val start = client.runtimeEvidenceContext("io.github.beyondwin.fixthis.sample")
        client.selectDevice("emulator-5556")
        adb.executeSerials.clear()

        client.collectRuntimeEvidence(
            packageName = "io.github.beyondwin.fixthis.sample",
            kind = CliRuntimeEvidenceKind.MEMORY_SUMMARY,
            screenCapturedAtEpochMillis = 1L,
            deviceSerial = start.deviceSerial,
        )

        assertTrue(adb.executeSerials.isNotEmpty())
        assertTrue(adb.executeSerials.all { it == "emulator-5554" })
    }

    @Test
    fun runtimeEvidenceContextStartsShortBoundedBridgeEnrichmentConcurrently() {
        val adb = DeviceScopeAdbFacade(devices = listOf(AdbDevice("emulator-5554", "device")))
        val starts = CountDownLatch(2)
        val connectorThreads = Collections.synchronizedSet(mutableSetOf<Long>())
        val readTimeouts = Collections.synchronizedList(mutableListOf<Int>())
        val client = BridgeClient(
            adb = adb,
            projectRoot = temporaryFolder.newFolder(),
            portAllocator = { 34567 },
            socketConnector = {
                connectorThreads += Thread.currentThread().threadId()
                starts.countDown()
                check(starts.await(1, TimeUnit.SECONDS)) { "bridge enrichment requests did not start concurrently" }
                DeviceScopeBridgeSocket(readTimeouts)
            },
        )

        val context = client.runtimeEvidenceContext("io.github.beyondwin.fixthis.sample")

        assertEquals("1.3", context.bridgeProtocolVersion)
        assertEquals("screen-fingerprint", context.currentScreenFingerprint)
        assertEquals(2, connectorThreads.size)
        assertEquals(listOf(1_250, 1_250), readTimeouts.sorted())
    }

    private class DeviceScopeBridgeSocket(
        private val readTimeouts: MutableList<Int>? = null,
    ) : BridgeSocket {
        override val input = ByteArrayInputStream(
            frame(
                """
                {
                  "id": "req_1",
                  "ok": true,
                  "result": {
                    "bridgeProtocolVersion": "1.3",
                    "installEpochMillis": 1234,
                    "activity": "MainActivity",
                    "fingerprint": "screen-fingerprint"
                  }
                }
                """.trimIndent(),
            ),
        )
        override val output = ByteArrayOutputStream()
        override var readTimeoutMillis: Int = 0
            set(value) {
                field = value
                readTimeouts?.add(value)
            }
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
        val runAsSerials: MutableList<String?> = Collections.synchronizedList(mutableListOf()),
        val forwardSerials: MutableList<String?> = Collections.synchronizedList(mutableListOf()),
        val removeForwardSerials: MutableList<String?> = Collections.synchronizedList(mutableListOf()),
        val executeSerials: MutableList<String?> = Collections.synchronizedList(mutableListOf()),
    ) : AdbFacade {
        override fun devices(): List<AdbDevice> = devices

        override fun forDevice(serial: String?): AdbFacade = DeviceScopeAdbFacade(
            devices = devices,
            selectedSerial = serial,
            runAsSerials = runAsSerials,
            forwardSerials = forwardSerials,
            removeForwardSerials = removeForwardSerials,
            executeSerials = executeSerials,
        )

        override fun execute(arguments: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
            executeSerials += selectedSerial
            val stdout = when {
                arguments.take(3) == listOf("shell", "pm", "path") -> "package:/data/app/base.apk"
                arguments.take(2) == listOf("shell", "pidof") -> "123"
                arguments.take(3) == listOf("shell", "dumpsys", "meminfo") -> "TOTAL PSS: 42"
                else -> ""
            }
            return AdbExecutionResult(0, stdout, "", false, false, false)
        }

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
