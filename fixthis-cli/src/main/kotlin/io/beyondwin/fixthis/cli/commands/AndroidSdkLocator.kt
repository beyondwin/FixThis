package io.beyondwin.fixthis.cli.commands

import java.io.File

internal object AndroidSdkLocator {
    data class SdkLocation(val home: File, val adb: File, val source: String)

    fun find(
        env: Map<String, String> = System.getenv(),
        userHome: File = File(System.getProperty("user.home")),
    ): SdkLocation? =
        candidates(env, userHome).firstOrNull { it.adb.isFile && it.adb.canExecute() }

    private fun candidates(env: Map<String, String>, userHome: File): Sequence<SdkLocation> = sequence {
        sdkLocation(env["ANDROID_HOME"], "ANDROID_HOME")?.let { yield(it) }
        sdkLocation(env["ANDROID_SDK_ROOT"], "ANDROID_SDK_ROOT")?.let { yield(it) }
        sdkLocation(userHome.resolve("Library/Android/sdk").absolutePath, "macOS default")?.let { yield(it) }
        sdkLocation(userHome.resolve("Android/Sdk").absolutePath, "Linux default")?.let { yield(it) }
        env["LOCALAPPDATA"]?.let { sdkLocation(File(it, "Android/Sdk").absolutePath, "Windows default") }?.let { yield(it) }
        sdkLocation(userHome.resolve(".android/sdk").absolutePath, "fallback")?.let { yield(it) }
    }

    private fun sdkLocation(path: String?, source: String): SdkLocation? {
        val home = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        return SdkLocation(home = home, adb = home.resolve(adbRelativePath()), source = source)
    }

    private fun adbRelativePath(): String =
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "platform-tools/adb.exe"
        } else {
            "platform-tools/adb"
        }
}
