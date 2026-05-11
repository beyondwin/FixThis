package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.AdbDevice
import kotlinx.serialization.Serializable

@Serializable
data class ConsoleDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val deviceName: String? = null,
    val selected: Boolean = false,
)

@Serializable
data class ConsoleDeviceList(
    val devices: List<ConsoleDevice>,
    val selectedSerial: String? = null,
)

@Serializable
data class SelectDeviceRequest(
    val serial: String,
)

fun AdbDevice.toConsoleDevice(selectedSerial: String?): ConsoleDevice = ConsoleDevice(
    serial = serial,
    state = state,
    model = model,
    product = product,
    deviceName = deviceName,
    selected = serial == selectedSerial,
)
