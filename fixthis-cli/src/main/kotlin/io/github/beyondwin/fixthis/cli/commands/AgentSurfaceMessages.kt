package io.github.beyondwin.fixthis.cli.commands

internal object AgentSurfaceMessages {

    fun template(cause: String, verify: String, fixes: List<String>): String = buildString {
        appendLine(cause)
        appendLine("  verify: $verify")
        fixes.forEach { appendLine("  fix:    $it") }
    }.trimEnd()

    fun noAppModule(packageName: String): String = template(
        cause = "Multi-module project has no app module matching '$packageName'.",
        verify = "./gradlew projects",
        fixes = listOf(
            "pass --package <correct-applicationId>",
            "apply plugin manually: id(\"io.github.beyondwin.fixthis.compose\")",
        ),
    )

    fun releaseOnlyVariant(): String = template(
        cause = "Detected release-only assembly; FixThis sidekick attaches debug builds only.",
        verify = "./gradlew tasks --group=build | grep Debug",
        fixes = listOf(
            "add a debug build variant",
            "use `fixthis run --variant debug`",
        ),
    )

    fun viewSystemMixed(modulePath: String): String {
        val grepRoot = modulePath.trimStart(':').replace(':', '/') + "/src/main"
        return template(
            cause = "Module '$modulePath' contains View-based activities; FixThis supports Compose only.",
            verify = "grep -r 'setContentView' $grepRoot",
            fixes = listOf(
                "migrate to ComponentActivity + setContent",
                "target a different module via --package",
            ),
        )
    }

    fun missingApplicationId(): String = template(
        cause = "No unique applicationId found in build files.",
        verify = "./gradlew :app:properties | grep applicationId",
        fixes = listOf(
            "pass --package <applicationId>",
            "run from the app module directory",
        ),
    )
}
