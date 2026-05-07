package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionAction
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionDetails
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionState
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
import io.beyondwin.fixthis.mcp.console.toConnectionDevice
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.CancellationException

internal class ConsoleConnectionService(
    private val bridge: FixThisBridge,
) {
    fun devices(): List<AdbDevice> = bridge.devices()

    fun selectedDeviceSerial(): String? = bridge.selectedDeviceSerial()

    fun selectDevice(serial: String) {
        val selectedSerial = serial.trim()
        require(selectedSerial.isNotBlank()) { "Device serial must not be blank" }
        val device = devices().firstOrNull { it.serial == selectedSerial }
            ?: throw FeedbackSessionException("DEVICE_NOT_AVAILABLE: Android device is not connected: $selectedSerial")
        if (device.state != "device") {
            throw FeedbackSessionException("DEVICE_NOT_AVAILABLE: Android device is not ready: $selectedSerial (${device.state})")
        }
        bridge.selectDevice(selectedSerial)
    }

    fun disconnectDevice() = bridge.disconnectDevice()

    suspend fun connectionStatus(session: SessionDto): ConsoleConnectionStatus {
        val selectedSerial = bridge.selectedDeviceSerial()
        val devices = try {
            devices()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val raw = error.message ?: error::class.java.simpleName
            return ConsoleConnectionStatus(
                state = ConsoleConnectionState.CHECK_PHONE,
                headline = "Check your phone",
                message = "Unlock your phone or allow debugging, then try again.",
                primaryAction = ConsoleConnectionAction.TRY_AGAIN,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(
                    deviceState = "unknown",
                    bridgeState = "not checked",
                    rawError = raw,
                ),
            )
        }
        val readyDevices = devices.filter { it.state == "device" }
        val selectedDevice = devices.firstOrNull { it.serial == selectedSerial }
        val connectionDevices = devices.map { it.toConnectionDevice(selectedSerial) }

        if (readyDevices.isEmpty()) {
            val unavailable = selectedDevice ?: devices.firstOrNull()
            return ConsoleConnectionStatus(
                state = ConsoleConnectionState.CHECK_PHONE,
                headline = "Check your phone",
                message = "Unlock your phone or allow debugging, then try again.",
                primaryAction = ConsoleConnectionAction.TRY_AGAIN,
                selectedDevice = unavailable?.toConnectionDevice(selectedSerial),
                devices = connectionDevices,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(
                    deviceState = unavailable?.state ?: "none",
                    bridgeState = "not checked",
                ),
            )
        }

        if (selectedSerial == null) {
            return if (readyDevices.size == 1) {
                ConsoleConnectionStatus(
                    state = ConsoleConnectionState.WELCOME,
                    headline = "Connect to your app",
                    message = "We'll find your phone and open the app for you.",
                    primaryAction = ConsoleConnectionAction.START,
                    devices = connectionDevices,
                    packageName = session.packageName,
                    details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "not checked"),
                )
            } else {
                ConsoleConnectionStatus(
                    state = ConsoleConnectionState.CHOOSE_DEVICE,
                    headline = "Choose a device",
                    message = "More than one device is connected.",
                    primaryAction = ConsoleConnectionAction.CHOOSE_DEVICE,
                    devices = connectionDevices,
                    packageName = session.packageName,
                    details = ConsoleConnectionDetails(deviceState = "multiple", bridgeState = "not checked"),
                )
            }
        }

        if (selectedDevice == null || selectedDevice.state != "device") {
            return ConsoleConnectionStatus(
                state = ConsoleConnectionState.CHECK_PHONE,
                headline = "Check your phone",
                message = "Unlock your phone or allow debugging, then try again.",
                primaryAction = ConsoleConnectionAction.TRY_AGAIN,
                selectedDevice = selectedDevice?.toConnectionDevice(selectedSerial),
                devices = connectionDevices,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(deviceState = selectedDevice?.state ?: "missing", bridgeState = "not checked"),
            )
        }

        return try {
            bridge.heartbeat(session.packageName)
            ConsoleConnectionStatus(
                state = ConsoleConnectionState.READY,
                headline = "Ready",
                message = "Your app is connected.",
                primaryAction = ConsoleConnectionAction.CAPTURE,
                selectedDevice = selectedDevice.toConnectionDevice(selectedSerial),
                devices = connectionDevices,
                packageName = session.packageName,
                canCapture = true,
                canNavigate = true,
                details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "connected"),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val raw = error.message ?: error::class.java.simpleName
            val unsupported = raw.contains("not debuggable", ignoreCase = true) ||
                (
                    raw.contains("run-as", ignoreCase = true) &&
                        raw.contains("permission", ignoreCase = true)
                    )
            ConsoleConnectionStatus(
                state = if (unsupported) ConsoleConnectionState.UNSUPPORTED_BUILD else ConsoleConnectionState.OPEN_APP,
                headline = if (unsupported) "This build cannot connect" else "Open the app",
                message = if (unsupported) {
                    "Use a debuggable build with FixThis sidekick enabled."
                } else {
                    "Your phone is connected, but the app is not open."
                },
                primaryAction = if (unsupported) ConsoleConnectionAction.TRY_AGAIN else ConsoleConnectionAction.OPEN_APP,
                selectedDevice = selectedDevice.toConnectionDevice(selectedSerial),
                devices = connectionDevices,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "failed", rawError = raw),
            )
        }
    }

    suspend fun launchAppForSession(session: SessionDto): ConsoleConnectionStatus {
        val beforeLaunch = connectionStatus(session)
        if (beforeLaunch.state != ConsoleConnectionState.WELCOME &&
            beforeLaunch.state != ConsoleConnectionState.OPEN_APP
        ) {
            return beforeLaunch
        }
        if (beforeLaunch.state == ConsoleConnectionState.WELCOME) {
            beforeLaunch.devices
                .filter { it.state == "device" }
                .singleOrNull()
                ?.let { bridge.selectDevice(it.serial) }
        }
        bridge.launchApp(session.packageName)
        return connectionStatus(session).let { afterLaunch ->
            if (afterLaunch.state == ConsoleConnectionState.WELCOME ||
                afterLaunch.state == ConsoleConnectionState.OPEN_APP
            ) {
                afterLaunch.copy(
                    state = ConsoleConnectionState.STARTING,
                    headline = "Opening app",
                    message = "We're opening the app and connecting.",
                    primaryAction = null,
                )
            } else {
                afterLaunch
            }
        }
    }
}
