package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.Adb
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.BridgeClient
import kotlinx.coroutines.runBlocking
import java.io.File

internal interface DoctorEnvironment {
    fun requireAndroidProject(root: File)
    fun resolvePackageName(root: File, packageName: String?): String
    fun listDevices(root: File): List<AdbDevice>
    fun requestStatus(root: File, packageName: String)
}

internal class RealDoctorEnvironment : DoctorEnvironment {
    override fun requireAndroidProject(root: File) {
        require(root.resolve("settings.gradle.kts").exists() || root.resolve("settings.gradle").exists()) {
            "settings.gradle(.kts) was not found"
        }
    }

    override fun resolvePackageName(root: File, packageName: String?): String = BridgeClient(adb = Adb.forProject(root), projectRoot = root).resolvePackageName(packageName)

    override fun listDevices(root: File): List<AdbDevice> = Adb.forProject(root).devices()

    override fun requestStatus(root: File, packageName: String) {
        val adb = Adb.forProject(root)
        val client = BridgeClient(adb = adb, projectRoot = root)
        runBlocking {
            client.request(packageName, "status")
        }
    }
}

internal class DoctorService(
    private val environment: DoctorEnvironment = RealDoctorEnvironment(),
) {
    fun run(packageName: String?, projectRoot: File): DoctorReport {
        val root = projectRoot.canonicalFile
        var resolvedPackage: String? = null
        val checks = mutableListOf<DoctorCheckResult>()

        fun check(name: String, label: String, fix: String, block: () -> Unit) {
            runCatching(block).fold(
                onSuccess = {
                    checks += DoctorCheckResult(name = name, label = label, ok = true)
                },
                onFailure = { error ->
                    checks += DoctorCheckResult(
                        name = name,
                        label = label,
                        ok = false,
                        message = error.message,
                        fix = fix,
                        readiness = readinessForDoctorCheck(name, error.message, fix),
                    )
                },
            )
        }

        check(
            name = "android_project_found",
            label = "Android project found",
            fix = "Run from an Android repository root or pass --project-dir.",
        ) {
            environment.requireAndroidProject(root)
        }
        check(
            name = "fixthis_project_metadata_found",
            label = "FixThis project metadata found",
            fix = "Run `./gradlew fixthisSetup` or pass --package <applicationId>.",
        ) {
            resolvedPackage = environment.resolvePackageName(root, packageName)
        }
        check(
            name = "adb_found",
            label = "ADB found",
            fix = "Install Android SDK platform-tools or set ANDROID_HOME.",
        ) {
            environment.listDevices(root)
        }
        check(
            name = "device_connected",
            label = "device connected",
            fix = "Start an emulator or connect a device, then run `adb devices`.",
        ) {
            require(hasConnectedAndroidDevice(environment.listDevices(root))) {
                "No connected Android device or emulator found"
            }
        }
        check(
            name = "sidekick_session_found",
            label = "sidekick session found",
            fix = "Build and run the debug app with FixThis sidekick installed.",
        ) {
            val pkg = resolvedPackage ?: environment.resolvePackageName(root, packageName)
            environment.requestStatus(root, pkg)
        }

        return DoctorReport(packageName = resolvedPackage, checks = checks)
    }
}
