package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessFailureCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import io.github.beyondwin.fixthis.cli.readiness.classifyBridgeFailure
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
        val report = DoctorService().run(packageName = packageName, projectRoot = root)

        if (jsonOutput) {
            echo(renderDoctorJsonReport(report))
        } else {
            report.checks.forEach { check ->
                if (check.ok) {
                    echo("OK   ${check.label}")
                } else {
                    echo("FAIL ${check.label}: ${check.message}")
                    echo("  ↳ fix: ${check.fix}")
                }
            }
        }

        if (!report.ok) {
            throw CliktError("${report.checks.count { !it.ok }} doctor check(s) failed")
        }
    }
}

internal data class DoctorReport(
    val packageName: String?,
    val checks: List<DoctorCheckResult>,
) {
    val ok: Boolean = checks.all { it.ok }
    val readiness: FirstRunReadiness
        get() = checks.firstOrNull { !it.ok }?.readiness ?: FirstRunReadinessCatalog.ready(
            details = packageName?.let { mapOf("packageName" to it) } ?: emptyMap(),
        )
}

internal data class DoctorCheckResult(
    val name: String,
    val label: String,
    val ok: Boolean,
    val message: String? = null,
    val fix: String? = null,
    val readiness: FirstRunReadiness? = null,
)

internal fun readinessForDoctorCheck(
    name: String,
    message: String?,
    fix: String,
): FirstRunReadiness = when (name) {
    "android_project_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "Android project root was not found.",
        fix = fix,
    )
    "fixthis_project_metadata_found" -> readinessForProjectMetadata(name, message)
    "adb_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "ADB is unavailable.",
        fix = fix,
    )
    "device_connected" -> FirstRunReadinessCatalog.deviceBlocked(
        cause = message ?: "No ready Android device or emulator is connected.",
        fix = fix,
    )
    "sidekick_session_found" -> readinessForSidekickSession(message)
    else -> FirstRunReadinessFailureCatalog.unknown(
        cause = "Doctor check failed: $name",
        details = mapOf("check" to name),
    )
}

private fun readinessForProjectMetadata(name: String, message: String?): FirstRunReadiness {
    val raw = message.orEmpty()
    return if (isAmbiguousPackageMessage(raw)) {
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

private fun isAmbiguousPackageMessage(message: String): Boolean {
    val hasMultiplePackageIds = message.contains("Multiple Android applicationId", ignoreCase = true)
    val asksForExplicitPackage = message.contains("Pass --package", ignoreCase = true)
    return hasMultiplePackageIds || asksForExplicitPackage
}

private fun readinessForSidekickSession(message: String?): FirstRunReadiness {
    val classified = classifyBridgeFailure(message)
    if (classified.state != FirstRunReadinessState.UNKNOWN_ERROR) {
        return classified
    }

    val raw = message.orEmpty()
    return FirstRunReadinessCatalog.needsAppLaunch(
        cause = message ?: "FixThis sidekick session was not found.",
        details = if (raw.isBlank()) emptyMap() else mapOf("rawError" to raw),
    )
}

internal fun renderDoctorJsonReport(report: DoctorReport): String = fixThisJson.encodeToString(
    buildJsonObject {
        put("schemaVersion", "1.0")
        put("ok", report.ok)
        report.packageName?.let { put("packageName", it) }
        put(
            "readiness",
            fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), report.readiness).jsonObject,
        )
        put("nextAction", report.readiness.nextAction)
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
