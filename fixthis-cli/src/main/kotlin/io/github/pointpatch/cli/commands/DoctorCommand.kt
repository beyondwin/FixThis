package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.pointpatch.cli.Adb
import io.github.pointpatch.cli.AdbDevice
import io.github.pointpatch.cli.BridgeClient
import java.io.File
import kotlinx.coroutines.runBlocking

internal fun hasConnectedAndroidDevice(devices: List<AdbDevice>): Boolean =
    devices.any { it.state == "device" }

class DoctorCommand : CoreCliktCommand(name = "doctor") {
    private val packageName by option("--package", help = "Android application id to diagnose")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        val root = File(projectDir).canonicalFile
        val adb = Adb.forProject(root)
        val client = BridgeClient(adb = adb, projectRoot = root)
        var failures = 0

        fun check(label: String, block: () -> Unit) {
            try {
                block()
                echo("OK   $label")
            } catch (error: Throwable) {
                failures += 1
                echo("FAIL $label: ${error.message}")
            }
        }

        var resolvedPackage: String? = null
        check("Android project found") {
            require(root.resolve("settings.gradle.kts").exists() || root.resolve("settings.gradle").exists()) {
                "settings.gradle(.kts) was not found"
            }
        }
        check("PointPatch project metadata found") {
            resolvedPackage = client.resolvePackageName(packageName)
        }
        check("ADB found") {
            adb.devices()
        }
        check("device connected") {
            require(hasConnectedAndroidDevice(adb.devices())) { "No connected Android device or emulator found" }
        }
        check("sidekick session found") {
            val pkg = resolvedPackage ?: client.resolvePackageName(packageName)
            runBlocking {
                client.request(pkg, "status")
            }
        }

        if (failures > 0) {
            throw com.github.ajalt.clikt.core.CliktError("$failures doctor check(s) failed")
        }
    }
}
