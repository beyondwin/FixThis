@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureHotspotBudgetTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val mcpMain = "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/"
    private val mcpConsoleTest = "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/"
    private val sidekickBridge =
        "fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/"
    private val gradlePlugin =
        "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/"

    @Test
    fun handwrittenKotlinFilesStayUnderBudgetUnlessExplicitlyAllowed() {
        // These are ratchets. When a file shrinks, lower its budget in the same commit.
        // Separate maps keep production source growth from being hidden by large tests.
        val productionKotlinBudgets = mapOf(
            "${mcpMain}session/FeedbackSessionStoreDelegate.kt" to 700,
            "${mcpMain}session/SessionReplayEngine.kt" to 340,
            "${mcpMain}tools/FixThisToolDispatcher.kt" to 520,
            "${mcpMain}session/FeedbackDraftService.kt" to 500,
            "${mcpMain}session/TargetEvidenceService.kt" to 440,
            "${mcpMain}tools/McpToolRegistry.kt" to 290,
            "${sidekickBridge}BridgeServer.kt" to 180,
            "${sidekickBridge}BridgeModels.kt" to 220,
            "${sidekickBridge}AndroidBridgeEnvironment.kt" to 180,
            "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 540,
            "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
            "${gradlePlugin}task/GenerateFixThisSourceIndexTask.kt" to 130,
            "${gradlePlugin}source/KotlinSourceScanner.kt" to 330,
        )
        val consoleJsBudgets = mapOf(
            "fixthis-mcp/src/main/console/annotations.js" to 670,
            "fixthis-mcp/src/main/console/history.js" to 560,
            "fixthis-mcp/src/main/console/presentation/annotationDetailView.js" to 520,
            "fixthis-mcp/src/main/console/main.js" to 450,
            "fixthis-mcp/src/main/console/state.js" to 470,
            "fixthis-mcp/src/main/console/domain/consoleReducer.js" to 410,
        )
        val testBudgets = mapOf(
            "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt" to 2_400,
            "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt" to 1_750,
            "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt" to 1_680,
            "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt" to 1_300,
            "${mcpConsoleTest}ConsoleAssetContractTest.kt" to 1_080,
            "${mcpConsoleTest}ConsoleFeedbackItemRoutesTest.kt" to 920,
        )
        val remediationBudgets = mapOf(
            "${mcpMain}session/FeedbackSessionStore.kt" to 250,
            "${sidekickBridge}BridgeServer.kt" to 180,
            "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
            "fixthis-mcp/src/main/console/rendering.js" to 200,
        )
        val currentBudgets = productionKotlinBudgets + consoleJsBudgets + testBudgets
        val budgets = currentBudgets + remediationBudgets.mapNotNull { (path, budget) ->
            when {
                remediationBudgetEnabled(path) -> path to budget
                path in currentBudgets -> path to currentBudgets.getValue(path)
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

    private fun remediationBudgetEnabled(path: String): Boolean = File(root, path).isFile && System.getenv("FIXTHIS_STRICT_ARCH_BUDGETS") == "true"
}
