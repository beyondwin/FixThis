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

    // These are ratchets. When a file shrinks, lower its budget in the same commit.
    // Separate maps keep production source growth from being hidden by large tests.
    private val productionKotlinBudgets = mapOf(
        "${mcpMain}session/lifecycle/store/FeedbackSessionStoreDelegate.kt" to 499,
        "${mcpMain}session/lifecycle/event/SessionEventPayloadFactory.kt" to 97,
        "${mcpMain}session/lifecycle/store/SessionStateStore.kt" to 94,
        "${mcpMain}session/lifecycle/event/eventlog/SessionCompactionCoordinator.kt" to 86, // C1: nullable sink + internal fallback property
        "${mcpMain}session/lifecycle/event/SessionBootReplayer.kt" to 146,
        "${mcpMain}session/lifecycle/event/SessionReplayEngine.kt" to 340,
        "${mcpMain}tools/FixThisToolDispatcher.kt" to 70,
        "${mcpMain}tools/ToolCallSupport.kt" to 70,
        "${mcpMain}tools/ScreenToolOperations.kt" to 180,
        "${mcpMain}tools/FeedbackToolOperations.kt" to 320,
        "${mcpMain}session/draft/FeedbackDraftService.kt" to 430,
        "${mcpMain}session/target/TargetEvidenceService.kt" to 320,
        "${mcpMain}tools/McpToolRegistry.kt" to 290,
        "${sidekickBridge}BridgeServer.kt" to 180,
        "${sidekickBridge}BridgeModels.kt" to 220,
        "${sidekickBridge}AndroidBridgeEnvironment.kt" to 180,
        // Budget reflects Task 2's composed-method decomposition of score() (value-identical).
        "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 681,
        "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
        "${gradlePlugin}task/GenerateFixThisSourceIndexTask.kt" to 140,
        "${gradlePlugin}source/KotlinSourceScanner.kt" to 379,
    )
    private val consoleJsBudgets = mapOf(
        "fixthis-mcp/src/main/console/annotations.js" to 670,
        "fixthis-mcp/src/main/console/history.js" to 567,
        "fixthis-mcp/src/main/console/presentation/annotationDetailView.js" to 568,
        "fixthis-mcp/src/main/console/main.js" to 460,
        "fixthis-mcp/src/main/console/state.js" to 470,
        "fixthis-mcp/src/main/console/domain/consoleReducer.js" to 410,
    )
    private val testBudgets = mapOf(
        "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt" to 2_618,
        "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt" to 1_750,
        "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt" to 1_680,
        "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt" to 1_313,
        "${mcpConsoleTest}ConsoleAssetContractTest.kt" to 1_085,
        "${mcpConsoleTest}ConsoleFeedbackItemRoutesTest.kt" to 920,
    )
    private val remediationBudgets = mapOf(
        "${mcpMain}session/lifecycle/store/FeedbackSessionStore.kt" to 250,
        "${sidekickBridge}BridgeServer.kt" to 180,
        "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
        "fixthis-mcp/src/main/console/rendering.js" to 200,
    )

    @Test
    fun handwrittenKotlinFilesStayUnderBudgetUnlessExplicitlyAllowed() {
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
            if (lines > maxLines) HotspotOverflow(path = path, lines = lines, budget = maxLines) else null
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Architecture hotspot budget exceeded.")
                appendLine("These budgets are ratchets. If a file shrinks, lower the matching budget in the same commit.")
                appendLine("If a file grows, prefer splitting responsibilities or recording a justified architecture decision instead of silently raising the budget.")
                appendLine("For task routing, read docs/architecture/agent-code-compass.md.")
                appendLine()
                append(offenders.joinToString(separator = "\n") { "${it.path} has ${it.lines} lines, budget is ${it.budget}" })
            },
        )
    }

    private data class HotspotOverflow(
        val path: String,
        val lines: Int,
        val budget: Int,
    )

    private fun remediationBudgetEnabled(path: String): Boolean = File(root, path).isFile && System.getenv("FIXTHIS_STRICT_ARCH_BUDGETS") == "true"
}
