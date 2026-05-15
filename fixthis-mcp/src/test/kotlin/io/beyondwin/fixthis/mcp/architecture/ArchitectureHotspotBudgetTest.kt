package io.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureHotspotBudgetTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val mcpMain = "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/"
    private val mcpConsoleTest = "fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/"
    private val sidekickBridge =
        "fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/"
    private val gradlePlugin =
        "fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/"

    @Test
    fun handwrittenKotlinFilesStayUnderBudgetUnlessExplicitlyAllowed() {
        val legacyBudgets = mapOf(
            "${mcpMain}session/FeedbackSessionStore.kt" to 250,
            "${mcpMain}session/SessionReplayEngine.kt" to 340,
            "${mcpMain}tools/FixThisTools.kt" to 230,
            "${mcpMain}tools/FixThisToolDispatcher.kt" to 540,
            "${mcpMain}tools/McpToolRegistry.kt" to 290,
            "${mcpConsoleTest}ConsoleFeedbackItemRoutesTest.kt" to 2_900,
            "${sidekickBridge}BridgeServer.kt" to 260,
            "${sidekickBridge}BridgeModels.kt" to 220,
            "${sidekickBridge}AndroidBridgeEnvironment.kt" to 180,
            "fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 560,
            "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 560,
            "${gradlePlugin}task/GenerateFixThisSourceIndexTask.kt" to 130,
            "${gradlePlugin}source/KotlinSourceScanner.kt" to 330,
        )
        val remediationBudgets = mapOf(
            "${mcpMain}session/FeedbackSessionStore.kt" to 250,
            "${sidekickBridge}BridgeServer.kt" to 180,
            "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
            "fixthis-mcp/src/main/console/rendering.js" to 200,
        )
        val budgets = legacyBudgets + remediationBudgets.mapNotNull { (path, budget) ->
            when {
                remediationBudgetEnabled(path) -> path to budget
                path in legacyBudgets -> path to legacyBudgets.getValue(path)
                else -> null
            }
        }.toMap()

        val offenders = budgets.mapNotNull { (path, maxLines) ->
            val file = File(root, path)
            val lines = file.readLines().size
            if (lines > maxLines) "$path has $lines lines, budget is $maxLines" else null
        }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun remediationBudgetEnabled(path: String): Boolean =
        File(root, path).isFile && System.getenv("FIXTHIS_STRICT_ARCH_BUDGETS") == "true"
}
