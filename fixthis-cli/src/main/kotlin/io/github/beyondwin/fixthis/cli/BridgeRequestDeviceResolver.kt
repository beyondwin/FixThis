package io.github.beyondwin.fixthis.cli

internal object BridgeRequestDeviceResolver {
    fun resolve(devices: List<AdbDevice>, selectedDeviceSerial: String?): String? {
        if (selectedDeviceSerial == null) return resolveUnselected(devices)
        return requireSelected(devices, selectedDeviceSerial)
    }

    private fun resolveUnselected(devices: List<AdbDevice>): String? {
        val readyDevices = devices.filter { it.state == "device" }
        if (readyDevices.isEmpty()) {
            throw NoDeviceException("No connected Android device or emulator found")
        }
        if (devices.size == 1) return null
        if (readyDevices.size != 1) {
            throw NoDeviceException("More than one connected Android device or emulator found; choose a device")
        }
        return readyDevices.single().serial
    }

    private fun requireSelected(devices: List<AdbDevice>, selectedDeviceSerial: String): String {
        val device = devices.firstOrNull { it.serial == selectedDeviceSerial }
            ?: throw NoDeviceException("Selected Android device is not connected: $selectedDeviceSerial")
        if (device.state != "device") {
            throw NoDeviceException("Selected Android device is not ready: $selectedDeviceSerial (${device.state})")
        }
        return selectedDeviceSerial
    }
}
