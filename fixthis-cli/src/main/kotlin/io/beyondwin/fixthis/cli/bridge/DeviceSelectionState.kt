package io.beyondwin.fixthis.cli.bridge

class DeviceSelectionState {
    @Volatile
    private var selectedSerial: String? = null

    fun select(serial: String) {
        require(serial.isNotBlank()) { "Device serial must not be blank" }
        selectedSerial = serial
    }

    fun clear() {
        selectedSerial = null
    }

    fun selected(): String? = selectedSerial
}
