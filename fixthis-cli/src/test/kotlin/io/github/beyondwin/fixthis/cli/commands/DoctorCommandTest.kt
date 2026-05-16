package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.parse
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
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
                        readiness = FirstRunReadinessCatalog.envBlocker(
                            cause = "No connected Android device or emulator found",
                            fix = "Start an emulator or connect a device, then run `adb devices`.",
                        ),
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
        val readiness = failed.getValue("readiness").jsonObject
        assertEquals("ENV_BLOCKER", readiness.getValue("state").jsonPrimitive.content)
        assertTrue(readiness.getValue("nextAction").jsonPrimitive.content.contains("emulator"))
    }

    @Test
    fun doctorTextOutputIncludesFixHintAfterFail() {
        val out = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            val cmd = DoctorCommand()
            try {
                cmd.parse(arrayOf("--project-dir", java.io.File.createTempFile("fxt", "").parentFile.absolutePath))
            } catch (_: Throwable) {
                // doctor exits non-zero on missing project; we just want stdout
            }
        } finally {
            System.setOut(oldOut)
        }
        val text = out.toString()
        assertTrue(
            "Expected fix-hint format in text output, got:\n$text",
            text.lines().any { it.trimStart().startsWith("↳ fix:") },
        )
    }
}
