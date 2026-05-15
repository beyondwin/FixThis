package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.AdbDevice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

    @Test
    fun doctorJsonReportIncludesStableStatusAndFixFields() {
        val report = renderDoctorJsonReport(
            DoctorReport(
                packageName = "com.example.agent",
                checks = listOf(
                    DoctorCheckResult(
                        name = "android_project_found",
                        label = "Android project found",
                        ok = true,
                    ),
                    DoctorCheckResult(
                        name = "device_connected",
                        label = "device connected",
                        ok = false,
                        message = "No connected Android device or emulator found",
                        fix = "Start an emulator or connect a device, then run `adb devices`.",
                    ),
                ),
            ),
        )

        val root = Json.parseToJsonElement(report).jsonObject
        assertEquals("1.0", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("com.example.agent", root.getValue("packageName").jsonPrimitive.content)
        assertEquals("false", root.getValue("ok").jsonPrimitive.content)
        val failed = root.getValue("checks").jsonArray[1].jsonObject
        assertEquals("device_connected", failed.getValue("name").jsonPrimitive.content)
        assertEquals("fail", failed.getValue("status").jsonPrimitive.content)
        assertTrue(failed.getValue("fix").jsonPrimitive.content.contains("adb devices"))
    }
}
