package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.AdbDevice
import kotlinx.serialization.Serializable

@Serializable
enum class ConsoleConnectionState {
    WELCOME,
    READY,
    OPEN_APP,
    STARTING,
    RECONNECT,
    CHOOSE_DEVICE,
    CHECK_PHONE,
    UNSUPPORTED_BUILD,
}

@Serializable
data class ConsoleConnectionStatus(
    val state: ConsoleConnectionState,
    val headline: String,
    val message: String,
    val primaryAction: ConsoleConnectionAction? = null,
    val selectedDevice: ConsoleConnectionDevice? = null,
    val devices: List<ConsoleConnectionDevice> = emptyList(),
    val packageName: String,
    val canCapture: Boolean = false,
    val canNavigate: Boolean = false,
    val canUseCachedWork: Boolean = true,
    val availability: ConsoleAvailabilitySignals? = null,
    val details: ConsoleConnectionDetails = ConsoleConnectionDetails(),
)

@Serializable
data class ConsoleAvailabilitySignals(
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
    val rootsCount: Int? = null,
)

@Serializable
enum class ConsoleConnectionAction {
    START,
    OPEN_APP,
    RECONNECT,
    TRY_AGAIN,
    CHOOSE_DEVICE,
    CAPTURE,
}

@Serializable
data class ConsoleConnectionDevice(
    val serial: String,
    val state: String,
    val label: String,
    val selected: Boolean = false,
)

@Serializable
data class ConsoleConnectionDetails(
    val deviceState: String? = null,
    val bridgeState: String? = null,
    val rawError: String? = null,
)

fun AdbDevice.toConnectionDevice(selectedSerial: String?): ConsoleConnectionDevice =
    ConsoleConnectionDevice(
        serial = serial,
        state = state,
        label = model ?: deviceName ?: product ?: serial,
        selected = serial == selectedSerial,
    )
