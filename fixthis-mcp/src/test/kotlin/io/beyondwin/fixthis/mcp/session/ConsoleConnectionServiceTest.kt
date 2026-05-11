package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionAction
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConsoleConnectionServiceTest {
    @Test
    fun noReadyDevicesMapsToCheckPhone() = runBlocking {
        val service = connectionService(
            devices = listOf(AdbDevice("device-1", "unauthorized")),
        )

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.CHECK_PHONE, status.state)
        assertEquals(ConsoleConnectionAction.TRY_AGAIN, status.primaryAction)
    }

    @Test
    fun singleReadyDeviceWithoutSelectionMapsToWelcome() = runBlocking {
        val service = connectionService(
            devices = listOf(AdbDevice("device-1", "device")),
        )

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.WELCOME, status.state)
        assertEquals(ConsoleConnectionAction.START, status.primaryAction)
    }

    @Test
    fun launchAppSelectsOnlyReadyDeviceForWelcomeState() = runBlocking {
        val bridge = FakeFixThisBridge(devicesOverride = listOf(AdbDevice("device-1", "device")))
        val service = ConsoleConnectionService(bridge)

        service.launchAppForSession(session())

        assertEquals(listOf("io.beyondwin.fixthis.sample"), bridge.launchedPackages)
        assertEquals("device-1", bridge.selectedDeviceSerial())
    }

    @Test
    fun deviceSelectionRejectsUnavailableDevice() {
        val service = connectionService(
            devices = listOf(AdbDevice("device-1", "device")),
        )

        val error = assertFailsWith<FeedbackSessionException> {
            service.selectDevice("missing")
        }

        assertEquals("DEVICE_NOT_AVAILABLE: Android device is not connected: missing", error.message)
    }

    @Test
    fun disconnectClearsSelectedDevice() {
        val bridge = FakeFixThisBridge(devicesOverride = listOf(AdbDevice("device-1", "device")))
        val service = ConsoleConnectionService(bridge)
        service.selectDevice("device-1")

        service.disconnectDevice()

        assertEquals(null, service.selectedDeviceSerial())
    }

    private fun connectionService(
        devices: List<AdbDevice> = listOf(AdbDevice("device-1", "device")),
    ): ConsoleConnectionService = ConsoleConnectionService(FakeFixThisBridge(devicesOverride = devices))

    private fun session(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )
}
