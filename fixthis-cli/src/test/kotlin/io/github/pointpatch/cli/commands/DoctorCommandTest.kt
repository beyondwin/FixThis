package io.github.pointpatch.cli.commands

import io.github.pointpatch.cli.AdbDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorCommandTest {
    @Test
    fun connectedDeviceCheckRequiresReadyDevice() {
        assertFalse(
            hasConnectedAndroidDevice(
                listOf(
                    AdbDevice("emulator-5554", "offline"),
                    AdbDevice("R5CT", "unauthorized"),
                ),
            ),
        )
        assertTrue(
            hasConnectedAndroidDevice(
                listOf(
                    AdbDevice("emulator-5554", "offline"),
                    AdbDevice("R5CT", "device"),
                ),
            ),
        )
    }
}
