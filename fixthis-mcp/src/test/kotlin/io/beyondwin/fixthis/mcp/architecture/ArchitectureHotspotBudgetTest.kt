package io.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureHotspotBudgetTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun handwrittenKotlinFilesStayUnderBudgetUnlessExplicitlyAllowed() {
        val budgets = mapOf(
            "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt" to 1_050,
            "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt" to 920,
            "fixthis-compose-sidekick/src/main/kotlin/" +
                "io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 740,
            "fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 620,
            "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 560,
            "fixthis-gradle-plugin/src/main/kotlin/" +
                "io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt" to 470,
        )
        val offenders = budgets.mapNotNull { (path, maxLines) ->
            val file = File(root, path)
            val lines = file.readLines().size
            if (lines > maxLines) "$path has $lines lines, budget is $maxLines" else null
        }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }
}
