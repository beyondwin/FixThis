package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import io.github.beyondwin.fixthis.mcp.console.ConsoleConnectionAction
import io.github.beyondwin.fixthis.mcp.console.ConsoleConnectionState
import io.github.beyondwin.fixthis.mcp.session.connection.ConsoleConnectionService
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsoleConnectionServiceTest {
    @Test
    fun noReadyDevicesMapsToCheckPhone() = runBlocking {
        val service = connectionService(
            devices = listOf(AdbDevice("device-1", "unauthorized")),
        )

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.CHECK_PHONE, status.state)
        assertEquals(ConsoleConnectionAction.TRY_AGAIN, status.primaryAction)
        assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, status.readiness?.state)
    }

    @Test
    fun multipleReadyDevicesMapsToDeviceBlockedChooseDeviceReadiness() = runBlocking {
        val service = connectionService(
            devices = listOf(
                AdbDevice("device-1", "device"),
                AdbDevice("device-2", "device"),
            ),
        )

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.CHOOSE_DEVICE, status.state)
        assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, status.readiness?.state)
        assertTrue(status.readiness?.nextAction.orEmpty().contains("Choose"))
    }

    @Test
    fun singleReadyDeviceWithoutSelectionMapsToWelcome() = runBlocking {
        val service = connectionService(
            devices = listOf(AdbDevice("device-1", "device")),
        )

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.WELCOME, status.state)
        assertEquals(ConsoleConnectionAction.START, status.primaryAction)
        assertEquals(FirstRunReadinessState.NEEDS_APP_LAUNCH, status.readiness?.state)
    }

    @Test
    fun unsupportedBridgeFailureMapsToUnsupportedBuildReadiness() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(AdbDevice("device-1", "device")),
            heartbeatError = IllegalStateException("run-as: package not debuggable"),
        )
        bridge.selectDevice("device-1")
        val service = ConsoleConnectionService(bridge)

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.UNSUPPORTED_BUILD, status.state)
        assertEquals(FirstRunReadinessState.UNSUPPORTED_BUILD, status.readiness?.state)
        assertEquals("Install a debuggable build with FixThis enabled.", status.readiness?.nextAction)
    }

    @Test
    fun launchAppSelectsOnlyReadyDeviceForWelcomeState() = runBlocking {
        val bridge = FakeFixThisBridge(devicesOverride = listOf(AdbDevice("device-1", "device")))
        val service = ConsoleConnectionService(bridge)

        service.launchAppForSession(session())

        assertEquals(listOf("io.github.beyondwin.fixthis.sample"), bridge.launchedPackages)
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
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )
}
