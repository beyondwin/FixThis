package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionPackageBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val session = "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session"

    private data class BoundaryRule(
        val sourceGroup: String,
        val forbiddenGroups: List<String>,
        val reason: String,
        val allowedException: String? = null,
    ) {
        val forbiddenImportRegex: Regex = Regex(
            """^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(${forbiddenGroups.joinToString("|")})\.""",
        )
    }

    private val rules = listOf(
        BoundaryRule(
            sourceGroup = "editsurface",
            forbiddenGroups = listOf("lifecycle\\.store", "handoff", "preview", "connection"),
            reason = "edit-surface analysis consumes DTO/core evidence and must not reach into storage, handoff rendering, preview capture, or connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "handoff",
            forbiddenGroups = listOf("lifecycle\\.store", "preview", "connection"),
            reason = "handoff rendering must not orchestrate persistence, preview capture, or connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "preview",
            forbiddenGroups = listOf("handoff"),
            reason = "preview capture may orchestrate store/target services under ADR-0008 E1 but must not depend on handoff rendering",
            allowedException = "ADR-0008 E1 allows preview -> lifecycle.store and preview -> target only",
        ),
        BoundaryRule(
            sourceGroup = "target",
            forbiddenGroups = listOf("lifecycle\\.store", "preview", "connection"),
            reason = "target evidence must stay independent of persistence, preview capture, and connection recovery",
            allowedException = "ADR-0008 E2 currently tolerates target -> handoff formatting helpers only",
        ),
        BoundaryRule(
            sourceGroup = "source",
            forbiddenGroups = listOf("lifecycle\\.store", "handoff", "preview", "target"),
            reason = "source freshness/path resolution must not orchestrate persistence, handoff rendering, preview capture, or target evidence",
        ),
        BoundaryRule(
            sourceGroup = "lifecycle/event",
            forbiddenGroups = listOf("preview", "connection"),
            reason = "event-sourced lifecycle code must not depend on preview capture or connection recovery",
            allowedException = "ADR-0008 E3 currently tolerates lifecycle/event -> handoff state models only",
        ),
        BoundaryRule(
            sourceGroup = "lifecycle/store",
            forbiddenGroups = listOf("connection", "target"),
            reason = "session storage must not depend on connection recovery or target evidence services",
            allowedException = "ADR-0008 E4 currently tolerates lifecycle/store -> handoff state models only",
        ),
        BoundaryRule(
            sourceGroup = "draft",
            forbiddenGroups = listOf("connection"),
            reason = "draft workflow must not depend on connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "connection",
            forbiddenGroups = listOf("handoff", "preview", "target"),
            reason = "connection recovery must not depend on handoff rendering, preview capture, or target evidence",
        ),
    )

    @Test
    fun sessionPackagesRespectDocumentedDependencyDirection() {
        val violations = rules.flatMap(::violationsFor)

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Session package dependency boundary violated.")
                appendLine("Read docs/architecture/adr/0008-session-package-decomposition.md before adding a new direction.")
                appendLine("Remove the import, move shared code to a lower package, or update ADR-0008 and this rule in the same commit.")
                appendLine()
                append(violations.joinToString(separator = "\n"))
            },
        )
    }

    private fun violationsFor(rule: BoundaryRule): List<String> = File(root, "$session/${rule.sourceGroup}").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (rule.forbiddenImportRegex.containsMatchIn(line)) {
                    buildString {
                        append("${file.relativeTo(root)}:${index + 1}: $line")
                        append("\n  sourceGroup=${rule.sourceGroup}")
                        append("\n  forbidden=${rule.forbiddenGroups.joinToString()}")
                        append("\n  reason=${rule.reason}")
                        rule.allowedException?.let { append("\n  allowedException=$it") }
                    }
                } else {
                    null
                }
            }
        }.toList()
}
