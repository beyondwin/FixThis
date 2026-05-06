package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.AdbDevice
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
