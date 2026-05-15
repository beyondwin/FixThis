package io.github.beyondwin.fixthis.gradle

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Structural smoke test for `fixthis-compose-sidekick/consumer-rules.pro`.
 *
 * **Scope downgrade rationale.** The plan's BR-2 ideal is a fixture Android
 * app project executed under `GradleRunner` with `assembleRelease` and
 * `minifyEnabled = true`, verifying that R8 — supplied our consumer rules —
 * produces a successful release build. That requires the Android Gradle
 * Plugin classpath, an Android SDK reachable from the TestKit-isolated
 * runner, Compose BOM resolution, and several minutes of CI time per run.
 * The plan explicitly allows the structural-only fallback:
 *
 *   > (a) verifies `consumer-rules.pro` is well-formed (no `-keep` syntax
 *   > errors) by attempting to load it OR (b) runs a simpler structural
 *   > check against the consumer rules file.
 *
 * This test takes path (b): it parses `consumer-rules.pro` line-by-line and
 * asserts:
 *
 *  1. Every non-blank, non-comment line is a ProGuard directive (starts with
 *     `-`). Catches typos like missing leading dash or unbalanced braces at
 *     the line level.
 *  2. The required keep rules cover the symbols the sidekick reflects on
 *     today, plus the public bridge surface.
 *
 * The file path is injected via the `fixthis.consumerRules.path` system
 * property by the Gradle `functionalTest` task. This keeps the test free
 * of hard-coded relative paths and lets the Gradle task declare the file
 * as an input for up-to-date checks.
 */
class SidekickConsumerRulesTest {

    private lateinit var consumerRules: File
    private lateinit var nonCommentLines: List<String>
    private lateinit var contents: String

    @Before
    fun setUp() {
        val path = System.getProperty("fixthis.consumerRules.path")
        assertNotNull(
            "fixthis.consumerRules.path system property is not set; " +
                "ensure the Gradle functionalTest task wires it.",
            path,
        )
        consumerRules = File(path!!)
        assertTrue(
            "consumer-rules.pro must exist at ${consumerRules.absolutePath}",
            consumerRules.isFile,
        )
        contents = consumerRules.readText()
        nonCommentLines = consumerRules.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun `consumer rules file is non-empty`() {
        assertTrue(
            "consumer-rules.pro is empty",
            consumerRules.length() > 0,
        )
        assertTrue(
            "consumer-rules.pro contains no directive lines",
            nonCommentLines.isNotEmpty(),
        )
    }

    @Test
    fun `every non-comment line is a ProGuard directive`() {
        // ProGuard directives start with `-`. Continuation lines inside a rule
        // body are indented braces / class members and don't start with `-`,
        // but the *trimmed* form still doesn't start with `-` for the body
        // lines `{`, `*;`, `public <init>();`, `}`. Allow those as bodies.
        val ruleBodyLineStarts = setOf("{", "}", "*;", "public", "private", "protected")
        nonCommentLines.forEach { line ->
            val ok = line.startsWith("-") || ruleBodyLineStarts.any { line.startsWith(it) }
            assertTrue(
                "Line is neither a ProGuard directive nor a recognised rule-body line: '$line'",
                ok,
            )
        }
    }

    @Test
    fun `braces are balanced`() {
        val open = contents.count { it == '{' }
        val close = contents.count { it == '}' }
        assertTrue(
            "Unbalanced braces in consumer-rules.pro: $open open vs $close close",
            open == close,
        )
    }

    @Test
    fun `keeps Compose reflection entry points`() {
        // Reflected from ComposeRootFinder / SemanticsInspector.
        assertContainsKeep("androidx.compose.ui.node.RootForTest")
        assertContainsKeep("androidx.compose.ui.semantics.SemanticsOwner")
        // SemanticsOwnerKt hosts `getAllSemanticsNodes` (top-level extension).
        assertContainsKeep("androidx.compose.ui.semantics.SemanticsOwnerKt")
        assertContainsKeep("androidx.compose.ui.platform.AndroidComposeView")
    }

    @Test
    fun `keeps public sidekick bridge surface`() {
        assertContainsKeep("io.github.beyondwin.fixthis.compose.sidekick.bridge.")
    }

    @Test
    fun `keeps FixThisInitializer entry point`() {
        // androidx.startup instantiates Initializer via reflection.
        assertContainsKeep(
            "io.github.beyondwin.fixthis.compose.sidekick.init.FixThisInitializer",
        )
    }

    private fun assertContainsKeep(symbol: String) {
        val hasKeep = nonCommentLines.any { line ->
            line.startsWith("-keep") && line.contains(symbol)
        }
        assertTrue(
            "Expected a -keep rule referencing '$symbol' in consumer-rules.pro, " +
                "but none was found. Lines:\n" + nonCommentLines.joinToString("\n"),
            hasKeep,
        )
    }
}
