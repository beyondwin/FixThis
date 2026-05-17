package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.beyondwin.fixthis.cli.Adb
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessFailureCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import io.github.beyondwin.fixthis.cli.readiness.classifyBridgeFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

internal fun hasConnectedAndroidDevice(devices: List<AdbDevice>): Boolean = devices.any { it.state == "device" }

class DoctorCommand : CoreCliktCommand(name = "doctor") {
    private val packageName by option("--package", help = "Android application id to diagnose")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val jsonOutput by option(
        "--json",
        help = "Print a structured machine-readable report",
    ).flag(default = false)

    override fun run() {
        val root = File(projectDir).canonicalFile
        val adb = Adb.forProject(root)
        val client = BridgeClient(adb = adb, projectRoot = root)
        var failures = 0
        val checks = mutableListOf<DoctorCheckResult>()

        fun check(name: String, label: String, fix: String, block: () -> Unit) {
            try {
                block()
                checks += DoctorCheckResult(name = name, label = label, ok = true)
                if (!jsonOutput) {
                    echo("OK   $label")
                }
            } catch (error: Throwable) {
                failures += 1
                val readiness = readinessForDoctorCheck(name, error.message, fix)
                checks += DoctorCheckResult(
                    name = name,
                    label = label,
                    ok = false,
                    message = error.message,
                    fix = fix,
                    readiness = readiness,
                )
                if (!jsonOutput) {
                    echo("FAIL $label: ${error.message}")
                    echo("  ↳ fix: $fix")
                }
            }
        }

        var resolvedPackage: String? = null
        check(
            name = "android_project_found",
            label = "Android project found",
            fix = "Run from an Android repository root or pass --project-dir.",
        ) {
            require(root.resolve("settings.gradle.kts").exists() || root.resolve("settings.gradle").exists()) {
                "settings.gradle(.kts) was not found"
            }
        }
        check(
            name = "fixthis_project_metadata_found",
            label = "FixThis project metadata found",
            fix = "Run `./gradlew fixthisSetup` or pass --package <applicationId>.",
        ) {
            resolvedPackage = client.resolvePackageName(packageName)
        }
        check(
            name = "adb_found",
            label = "ADB found",
            fix = "Install Android SDK platform-tools or set ANDROID_HOME.",
        ) {
            adb.devices()
        }
        check(
            name = "device_connected",
            label = "device connected",
            fix = "Start an emulator or connect a device, then run `adb devices`.",
        ) {
            require(hasConnectedAndroidDevice(adb.devices())) { "No connected Android device or emulator found" }
        }
        check(
            name = "sidekick_session_found",
            label = "sidekick session found",
            fix = "Build and run the debug app with FixThis sidekick installed.",
        ) {
            val pkg = resolvedPackage ?: client.resolvePackageName(packageName)
            runBlocking {
                client.request(pkg, "status")
            }
        }

        if (jsonOutput) {
            echo(renderDoctorJsonReport(DoctorReport(packageName = resolvedPackage, checks = checks)))
        }
        if (failures > 0) {
            throw CliktError("$failures doctor check(s) failed")
        }
    }
}

internal data class DoctorReport(
    val packageName: String?,
    val checks: List<DoctorCheckResult>,
) {
    val ok: Boolean = checks.all { it.ok }
}

internal data class DoctorCheckResult(
    val name: String,
    val label: String,
    val ok: Boolean,
    val message: String? = null,
    val fix: String? = null,
    val readiness: FirstRunReadiness? = null,
)

internal fun readinessForDoctorCheck(name: String, message: String?, fix: String): FirstRunReadiness = when (name) {
    "android_project_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "Android project root was not found.",
        fix = fix,
    )
    "fixthis_project_metadata_found" -> {
        val raw = message.orEmpty()
        if (raw.contains("Multiple Android applicationId", ignoreCase = true) ||
            raw.contains("Pass --package", ignoreCase = true)
        ) {
            FirstRunReadinessCatalog.configRecoverable(
                cause = raw.ifBlank { "FixThis could not choose a unique Android applicationId." },
                details = mapOf("check" to name),
            )
        } else {
            FirstRunReadinessCatalog.needsInstall(
                cause = message ?: "FixThis project metadata was not found.",
            )
        }
    }
    "adb_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "ADB is unavailable.",
        fix = fix,
    )
    "device_connected" -> FirstRunReadinessCatalog.deviceBlocked(
        cause = message ?: "No ready Android device or emulator is connected.",
        fix = fix,
    )
    "sidekick_session_found" -> {
        val classified = classifyBridgeFailure(message)
        if (classified.state == FirstRunReadinessState.UNKNOWN_ERROR) {
            val raw = message.orEmpty()
            FirstRunReadinessCatalog.needsAppLaunch(
                cause = message ?: "FixThis sidekick session was not found.",
                details = if (raw.isBlank()) emptyMap() else mapOf("rawError" to raw),
            )
        } else {
            classified
        }
    }
    else -> FirstRunReadinessFailureCatalog.unknown(
        cause = "Doctor check failed: $name",
        details = mapOf("check" to name),
    )
}

internal fun renderDoctorJsonReport(report: DoctorReport): String = fixThisJson.encodeToString(
    buildJsonObject {
        put("schemaVersion", "1.0")
        put("ok", report.ok)
        report.packageName?.let { put("packageName", it) }
        put(
            "checks",
            buildJsonArray {
                report.checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("name", check.name)
                            put("label", check.label)
                            put("status", if (check.ok) "ok" else "fail")
                            check.message?.let { put("message", it) }
                            check.fix?.let { put("fix", it) }
                            check.readiness?.let {
                                put(
                                    "readiness",
                                    fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject,
                                )
                            }
                        },
                    )
                }
            },
        )
    },
) + "\n"
