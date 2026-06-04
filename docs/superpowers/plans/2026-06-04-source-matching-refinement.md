# Source Matching Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sharpen source-candidate trustworthiness by adding configurable testTag conventions (coverage), per-role confidence refinement, and confident shared-component cap relaxation (calibration), each guarded by an extended measurement corpus.

**Architecture:** Convention config flows DSL → serialized source-index header → core matcher (core has no gradle dependency, so the convention set travels as data, not types). Calibration changes live in `EditSurfaceConfidencePolicy` (per-role ceiling) and `SourceRiskClassifier` (shared-component cap). A measurement-first ordering lands the precision gate before any calibration change.

**Tech Stack:** Kotlin, Gradle plugin, kotlinx.serialization, JUnit/kotlin.test, gradle TestKit.

**Sequencing:** Phase 0 (measurement) → Phase 1 (C1 conventions) → Phase 2 (K1 role) → Phase 3 (K2 cap).

**Module commands:**
- Core tests: `./gradlew :fixthis-compose-core:test`
- MCP tests: `./gradlew :fixthis-mcp:test`
- Gradle plugin tests: `./gradlew :fixthis-gradle-plugin:test`

---

## Phase 0 — Measurement foundation

### Task 1: Corpus precision gate (HIGH-confidence must pin the right file)

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`

The existing `v06CorpusMeetsEditSurfaceRegressionGate` checks role, top-3 membership, and "weak cases not HIGH". It does NOT assert that a HIGH-confidence node case has its top-1 source candidate on the expected owner file. Add that precision gate.

- [ ] **Step 1: Write the failing test**

Add this method to `HandoffEvaluationCorpusTest`:

```kotlin
    @Test
    fun highConfidenceCasesPinExpectedOwnerAsTopCandidate() {
        val failures = HandoffEvaluationFixtures.loadCorpus().cases.mapNotNull { case ->
            if (case.targetType != "node") return@mapNotNull null
            if (!case.allowHighConfidence) return@mapNotNull null
            val ownerContains = case.correctness.ownerContains
            val topFile = case.sourceCandidates
                .maxByOrNull { it.score }
                ?.file
                ?.substringAfterLast('/')
                ?: return@mapNotNull "${case.id}: HIGH-eligible case has no source candidates"
            if (!topFile.contains(ownerContains.substringAfterLast('/'))) {
                "${case.id}: top-1 candidate $topFile does not match owner $ownerContains"
            } else {
                null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }
```

- [ ] **Step 2: Run test to verify it passes against current corpus**

Run: `./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon`
Expected: PASS (current 9 cases already satisfy this — the gate is now locked so future cases cannot regress it).

If it FAILS, an existing case violates the precision invariant; fix the corpus JSON entry's candidate ordering before proceeding (do not weaken the gate).

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test(mcp): add HIGH-confidence top-candidate precision gate"
```

---

### Task 2: SHARED_COMPONENT fixture-lab assertion

**Files:**
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SharedComponentSignalFixtureTest.kt` (create)

Roadmap follow-up: assert the scanner emits a `SHARED_COMPONENT` signal for a reused component definition (high fan-in). Use the existing `SourceIndexGenerator` against a synthetic in-memory project.

- [ ] **Step 1: Inspect the generator entry point**

Run: `grep -n "fun generate\|class SourceIndexGenerator\|ComposableCallSiteFanIn" fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt`
Expected: prints the public generate signature and the fan-in detector. Use the exact signature in Step 2 (adjust the constructor/`generate(...)` call to match what is printed).

- [ ] **Step 2: Write the failing test**

Create `SharedComponentSignalFixtureTest.kt`. Write a fixture with one reusable component (`PrimaryButton`) called from two screens, then assert a `SHARED_COMPONENT` signal exists on the component-definition entry. Adapt the generator call to the signature printed in Step 1:

```kotlin
package io.github.beyondwin.fixthis.gradle.source

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SharedComponentSignalFixtureTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun reusedComponentDefinitionEmitsSharedComponentSignal() {
        val src = File(projectDir, "src/main/java/app").apply { mkdirs() }
        File(src, "PrimaryButton.kt").writeText(
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun PrimaryButton(label: String) { Text(label) }
            """.trimIndent(),
        )
        File(src, "HomeScreen.kt").writeText(
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun HomeScreen() { PrimaryButton("Save"); PrimaryButton("Cancel") }
            """.trimIndent(),
        )
        File(src, "CheckoutScreen.kt").writeText(
            """
            package app
            import androidx.compose.runtime.Composable
            @Composable fun CheckoutScreen() { PrimaryButton("Pay") }
            """.trimIndent(),
        )

        val index = SourceIndexGenerator(projectDir, projectDir).generate(listOf(src))

        val buttonEntry = index.entries.first { it.file.endsWith("PrimaryButton.kt") }
        assertTrue(
            buttonEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT },
            "expected SHARED_COMPONENT signal on reused PrimaryButton definition; got ${buttonEntry.signals}",
        )
    }
}
```

- [ ] **Step 3: Run test to verify it fails (or reveals the real generator API)**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SharedComponentSignalFixtureTest" --no-daemon`
Expected: FAIL — either a compile error on the generator signature (fix the call to match Step 1) or an assertion failure. If the assertion fails because the fan-in threshold needs ≥N call sites, add more call sites until the documented threshold is met (check `ComposableCallSiteFanIn` for the threshold constant).

- [ ] **Step 4: Iterate until PASS**

Adjust the fixture (call-site count, import lines) until the test passes. Do NOT change production thresholds — the test must conform to the existing detector.

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SharedComponentSignalFixtureTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SharedComponentSignalFixtureTest.kt
git commit -m "test(gradle): pin SHARED_COMPONENT signal for reused component definition"
```

---

## Phase 1 — C1 configurable testTag conventions

### Task 3: Configurable `TestTagConventionSet` + validation (core)

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionValidation.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionSetTest.kt` (create)

Design: `TestTagConventionSet` holds compiled patterns and parses tags. `TestTagConvention.parse` delegates to a `Default` set (the current 4 patterns) so existing identity-hint callers are unchanged. A validator enforces anchored, length-bounded, two-capture-group patterns for custom config.

- [ ] **Step 1: Write the failing test**

Create `TestTagConventionSetTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestTagConventionSetTest {
    @Test
    fun defaultSetParsesColonAndDotForms() {
        assertEquals("PrimaryButton", TestTagConventionSet.Default.parse("comp:PrimaryButton:checkout")?.composableName)
        assertEquals("Settings", TestTagConventionSet.Default.parse("screen.Settings.heading")?.composableName)
        assertNull(TestTagConventionSet.Default.parse("MyScreen_button"))
    }

    @Test
    fun customPatternStringsParseUnderscoreScheme() {
        val set = TestTagConventionSet.fromPatternStrings(
            listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"),
        )
        assertEquals("MyScreen", set.parse("MyScreen_button")?.composableName)
        assertEquals("button", set.parse("MyScreen_button")?.variant)
    }

    @Test
    fun emptyPatternStringsFallBackToDefault() {
        val set = TestTagConventionSet.fromPatternStrings(emptyList())
        assertEquals("PrimaryButton", set.parse("comp:PrimaryButton:checkout")?.composableName)
    }

    @Test
    fun validationRejectsUnanchoredOrOverlongPatterns() {
        assertTrue(TestTagConventionValidation.validate("^([A-Za-z]+)_([A-Za-z0-9]+)$").isValid)
        assertFalse(TestTagConventionValidation.validate("([A-Za-z]+)_(.+)").isValid) // not anchored
        assertFalse(TestTagConventionValidation.validate("^" + "a".repeat(300) + "$").isValid) // too long
        assertFalse(TestTagConventionValidation.validate("^([A-Za-z]+)$").isValid) // needs 2 groups
    }
}
```

Add the missing import at the top of the test: `import kotlin.test.assertFalse`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionSetTest" --no-daemon`
Expected: FAIL — `TestTagConventionSet` / `TestTagConventionValidation` unresolved.

- [ ] **Step 3: Implement `TestTagConventionValidation`**

Create `TestTagConventionValidation.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConventionValidation {
    private const val MAX_PATTERN_LENGTH = 200

    data class Result(val isValid: Boolean, val reason: String? = null)

    fun validate(pattern: String): Result {
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return Result(false, "pattern exceeds $MAX_PATTERN_LENGTH characters")
        }
        if (!pattern.startsWith("^") || !pattern.endsWith("$")) {
            return Result(false, "pattern must be anchored with ^ and $")
        }
        val compiled = runCatching { Regex(pattern) }.getOrElse {
            return Result(false, "pattern is not a valid regex: ${it.message}")
        }
        val groupCount = compiled.toPattern().matcher("").groupCount()
        if (groupCount < 2) {
            return Result(false, "pattern must capture group 1 = composable name, group 2 = variant")
        }
        return Result(true)
    }
}
```

- [ ] **Step 4: Refactor `TestTagConvention.kt` to add `TestTagConventionSet`**

Replace the file body with:

```kotlin
package io.github.beyondwin.fixthis.compose.core.identity

class TestTagConventionSet internal constructor(private val patterns: List<Regex>) {
    data class Parsed(val composableName: String, val variant: String)

    fun parse(testTag: String?): Parsed? {
        if (testTag == null) return null
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(testTag)?.let { match ->
                Parsed(composableName = match.groupValues[1], variant = match.groupValues[2])
            }
        }
    }

    companion object {
        private val DEFAULT_PATTERNS = listOf(
            Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
            Regex("^screen:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
            Regex("^comp\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
            Regex("^screen\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
        )

        val Default: TestTagConventionSet = TestTagConventionSet(DEFAULT_PATTERNS)

        /** Builds a set from serialized pattern strings; empty input falls back to [Default]. */
        fun fromPatternStrings(patterns: List<String>): TestTagConventionSet {
            val valid = patterns.filter { TestTagConventionValidation.validate(it).isValid }
            return if (valid.isEmpty()) Default else TestTagConventionSet(valid.map(::Regex))
        }
    }
}

object TestTagConvention {
    data class Parsed(val composableName: String, val variant: String)

    /** Backward-compatible default-set parse used by identity hints. */
    fun parse(testTag: String?): Parsed? =
        TestTagConventionSet.Default.parse(testTag)?.let { Parsed(it.composableName, it.variant) }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionSetTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Run the full core suite (no caller regressions)**

Run: `./gradlew :fixthis-compose-core:test --no-daemon`
Expected: PASS — `TestTagConvention.parse` still returns the same `Parsed` shape for existing callers (`OccurrenceCalculator`, `IdentityHintFactory`, `SourceMatcher`, `EditSurfaceCandidateService`).

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionValidation.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionSetTest.kt
git commit -m "feat(core): configurable testTag convention set with validation"
```

---

### Task 4: Add convention header to `SourceIndex` + use it in `SourceMatcher`

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:251-259`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherConventionTest.kt` (create)

- [ ] **Step 1: Write the failing serialization + matcher test**

Create `SourceMatcherConventionTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertTrue

class SourceMatcherConventionTest {
    private fun node(testTag: String) = FixThisNode(
        uid = "n", composeNodeId = 1, rootIndex = 0, treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 10f, 10f), testTag = testTag, path = listOf("root"),
    )

    @Test
    fun matcherRecognizesCustomConventionFromHeader() {
        val index = SourceIndex(
            schemaVersion = "1.3",
            testTagConventions = listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"),
            entries = listOf(
                SourceIndexEntry(
                    file = "app/MyScreen.kt",
                    line = 10,
                    signals = listOf(SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "MyScreen")),
                ),
            ),
        )
        val candidates = SourceMatcher(index).match(node("MyScreen_button"), emptyList(), null)
        assertTrue(
            candidates.any { it.file == "app/MyScreen.kt" },
            "expected custom-convention tag to match MyScreen owner; got $candidates",
        )
    }

    @Test
    fun matcherFallsBackToDefaultWhenHeaderEmpty() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "app/PrimaryButton.kt",
                    line = 5,
                    signals = listOf(SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "PrimaryButton")),
                ),
            ),
        )
        val candidates = SourceMatcher(index).match(node("comp:PrimaryButton:checkout"), emptyList(), null)
        assertTrue(candidates.any { it.file == "app/PrimaryButton.kt" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherConventionTest" --no-daemon`
Expected: FAIL — `testTagConventions` is not a parameter of `SourceIndex`.

- [ ] **Step 3: Add the header field to `SourceIndex`**

In `SourceIndex.kt`, modify the `SourceIndex` data class (additive, bump schema):

```kotlin
@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.3",
    val sourceRoot: SourceRoot? = null,
    val testTagConventions: List<String> = emptyList(),
    val entries: List<SourceIndexEntry> = emptyList(),
)
```

- [ ] **Step 4: Use the convention set in `SourceMatcher`**

In `SourceMatcher.kt`, add a field built from the index and use it where `TestTagConvention.parse` is currently called (`addSelectedTestTagScore`, line ~251).

Add near the top of the class body (after the constructor):

```kotlin
    private val conventions: TestTagConventionSet =
        TestTagConventionSet.fromPatternStrings(sourceIndex.testTagConventions)
```

Add the import:

```kotlin
import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet
```

Then in `addSelectedTestTagScore`, replace `TestTagConvention.parse(testTag)` with `conventions.parse(testTag)`:

```kotlin
        conventions.parse(testTag)?.let { parsed ->
            val conventionScore = addIfMatches(
                hit = entry.conventionComposableWeightHit(parsed.composableName),
                term = parsed.composableName,
                reason = SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE,
                accumulator = accumulator,
            )
            score = maxOf(score, conventionScore)
        }
```

Remove the now-unused `import io.github.beyondwin.fixthis.compose.core.identity.TestTagConvention` only if no other reference remains in the file (grep first).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherConventionTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Update the schema-version serialization test**

In `SourceIndexSerializationTest.kt`, update any assertion expecting `"1.2"` to `"1.3"` and add a round-trip assertion that `testTagConventions` survives serialization. Run:

`grep -n '1\.2\|testTagConventions' fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt`

Change the expected default `schemaVersion` to `"1.3"`. Add:

```kotlin
        val withConventions = SourceIndex(testTagConventions = listOf("^([A-Za-z]+)_([A-Za-z0-9]+)$"))
        val roundTrip = json.decodeFromString(SourceIndex.serializer(), json.encodeToString(SourceIndex.serializer(), withConventions))
        assertEquals(listOf("^([A-Za-z]+)_([A-Za-z0-9]+)$"), roundTrip.testTagConventions)
```

(Match the test's existing `json` instance and import style.)

- [ ] **Step 7: Run the full core suite**

Run: `./gradlew :fixthis-compose-core:test --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherConventionTest.kt
git commit -m "feat(core): source-index convention header drives matcher testTag parsing"
```

---

### Task 5: DSL config + scanner emission + asset serialization (gradle)

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisExtension.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt:5-10`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt:15-16,218`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

Design: DSL exposes named presets + validated custom patterns. The task resolves them to anchored regex strings, validates with `TestTagConventionValidation`, writes them into `SourceIndexAsset.testTagConventions`, and feeds the strict-tag matcher so custom conventions also emit `STRICT_COMP_TEST_TAG`.

- [ ] **Step 1: Inspect how the task builds the asset and calls the scanner**

Run: `grep -n "SourceIndexAsset(\|strictCompTestTag\|isStrictCompTestTag\|schemaVersion\|FixThisExtension\|conventions" fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt`
Expected: shows where `SourceIndexAsset` is constructed and where `isStrictCompTestTag()` is called. Use these exact call sites in Steps 4–5.

- [ ] **Step 2: Write the failing functional test**

In `GenerateFixThisSourceIndexTaskTest.kt`, add a test that configures a custom convention and asserts the generated asset JSON carries it and that a matching tag yields a `STRICT_COMP_TEST_TAG` signal. Follow the file's existing TestKit setup pattern (build script + `runner.build()`); add:

```kotlin
    @Test
    fun customTestTagConventionFlowsIntoGeneratedAsset() {
        // Arrange: build script enabling a custom underscore convention.
        // (Reuse this test class's existing helper that writes settings/build files;
        //  append the fixthis { testTagConventions = ... } block to the app build script.)
        writeAppBuildScriptWithFixThisBlock(
            """
            fixthis {
                testTagConventionPatterns.set(listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)\$"))
            }
            """.trimIndent(),
        )
        writeComposable(
            name = "MyScreen",
            body = """Text("Hi", modifier = Modifier.testTag("MyScreen_title"))""",
        )

        val result = runGenerateSourceIndex()

        val asset = readGeneratedSourceIndexJson()
        assertTrue(asset.contains("\"testTagConventions\""), "asset missing testTagConventions header")
        assertTrue(asset.contains("MyScreen_title"), "expected custom tag indexed")
        assertTrue(asset.contains("\"STRICT_COMP_TEST_TAG\""), "expected strict signal for custom convention")
    }
```

Where `writeAppBuildScriptWithFixThisBlock`, `writeComposable`, `runGenerateSourceIndex`, and `readGeneratedSourceIndexJson` are this test class's existing helpers (reuse them — match the names already present; if names differ, adapt to the existing helpers found in Step 1's file scan).

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*GenerateFixThisSourceIndexTaskTest" --no-daemon`
Expected: FAIL — `testTagConventionPatterns` is not a DSL property; asset has no `testTagConventions`.

- [ ] **Step 4: Add the DSL property**

In `FixThisExtension.kt`, add:

```kotlin
    val testTagConventionPatterns: org.gradle.api.provider.ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())
```

- [ ] **Step 5: Add the header field to `SourceIndexAsset`**

In `SourceIndexAssets.kt`, modify:

```kotlin
@Serializable
internal data class SourceIndexAsset(
    val schemaVersion: String = "1.3",
    val sourceRoot: SourceRootAsset? = null,
    val testTagConventions: List<String> = emptyList(),
    val entries: List<SourceIndexEntryAsset> = emptyList(),
)
```

- [ ] **Step 6: Make the strict-tag matcher convention-aware**

In `KotlinSemanticSignalScanner.kt`, change `isStrictCompTestTag` to accept extra patterns. Replace line 218:

```kotlin
internal fun String.isStrictCompTestTag(extraPatterns: List<Regex> = emptyList()): Boolean =
    strictCompTestTagRegex.matches(this) || extraPatterns.any { it.matches(this) }
```

Thread `extraPatterns` from the scanner's caller (the call site found in Step 1). The scanner constructor or `scan(...)` should receive the resolved convention regexes; pass them down to the `isStrictCompTestTag` call. If the scanner has no constructor seam, add a `conventionPatterns: List<Regex> = emptyList()` constructor parameter to `KotlinSemanticSignalScanner` / `KotlinSourceScanner` and use it at the strict-tag call site.

- [ ] **Step 7: Resolve + validate + serialize in the task**

ARCHITECTURE CORRECTION (orchestrator, 2026-06-04): `fixthis-gradle-plugin` is a
**standalone included build** (`includeBuild("fixthis-gradle-plugin")`, its own
`settings.gradle.kts` with `rootProject.name = "fixthis-gradle-plugin"`). It
CANNOT declare `implementation(project(":fixthis-compose-core"))` — it cannot
resolve sibling subprojects, which is exactly why it already MIRRORS core types
(`SourceIndexAsset` mirrors `SourceIndex`, `SourceSignalKindAsset` mirrors
`SourceSignalKind`). Core's `TestTagConventionValidation` is therefore NOT
reachable. Mirror the validation rules locally instead.

Add a small internal validator in the gradle plugin (e.g.
`fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/TestTagConventionPatternValidator.kt`)
that mirrors the core `TestTagConventionValidation` contract exactly: anchored
(`^...$`), length `<= 200`, compiles as a valid regex, `>= 2` capture groups,
and the ReDoS guard (reject a quantified group — `)` immediately followed by
`*`/`+`/`{` — and adjacent unbounded quantifiers — two of `*`/`+` in a row).
Return an `isValid: Boolean` (+ optional reason).

In `GenerateFixThisSourceIndexTask.kt`, at the generator-construction site
(line ~74, where `SourceIndexGenerator(...)` is built), resolve + HARD-FAIL on
any invalid pattern:

```kotlin
val requestedConventions = extension.testTagConventionPatterns.get()
val invalid = requestedConventions.filterNot { TestTagConventionPatternValidator.validate(it).isValid }
require(invalid.isEmpty()) {
    "FixThis: invalid testTagConventionPatterns $invalid; each must be anchored (^...\$), <=200 chars, " +
        "have >=2 capture groups, and avoid backtracking-prone (nested/adjacent unbounded) quantifiers"
}
```

Thread `requestedConventions` into `SourceIndexGenerator` (new constructor param
`conventionPatterns: List<String> = emptyList()`); inside the generator pass
`conventionPatterns.map(::Regex)` to `KotlinSourceScanner` (Step 6) and set
`testTagConventions = conventionPatterns` on the returned `SourceIndexAsset`.

Note: the local validator is a deliberate MIRROR of core's
`TestTagConventionValidation` (same duplication pattern as the enum mirrors).
Keep the two in sync if either changes.

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*GenerateFixThisSourceIndexTaskTest" --no-daemon`
Expected: PASS

- [ ] **Step 9: Update remaining schema-version assertions**

Run: `grep -rn '"1\.2"' fixthis-gradle-plugin/src/test fixthis-mcp/src/test`

Update ONLY the SOURCE-INDEX `schemaVersion` assertions to `"1.3"` — these are
`GenerateFixThisSourceIndexTaskTest.kt` lines 72 and 129
(`assertEquals("1.2", index.getValue("schemaVersion")...)`).

DO NOT TOUCH `McpProtocolTest.kt:1526` `put("bridgeProtocolVersion", "1.2")` —
that is the BRIDGE protocol version, a completely separate constant from the
source-index schemaVersion. Changing it would corrupt the bridge handshake and
break `BridgeProtocolVersionSyncTest`. (Orchestrator correction 2026-06-04: the
original plan wrongly listed McpProtocolTest here.)

Run both suites:

`./gradlew :fixthis-gradle-plugin:test :fixthis-mcp:test --no-daemon`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add fixthis-gradle-plugin/src/main fixthis-gradle-plugin/src/test fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat(gradle): testTagConventions DSL flows to source-index header and strict signals"
```

---

### Task 6: Thread conventions into edit-surface owner resolution (mcp)

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt:11-22,63-66`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt:156,250`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateConventionTest.kt` (create)

`ownerCandidate` parses the selected tag via `TestTagConvention.parse` (default set only). Thread a `TestTagConventionSet` so custom-convention owners resolve too.

- [ ] **Step 1: Write the failing test**

Create `EditSurfaceCandidateConventionTest.kt`. Build an `AnnotationDto` whose selected node has a custom-convention tag and a source candidate on the matching owner, then assert an owner edit-surface candidate is produced when the custom set is supplied. Model the DTO construction on `HandoffEvaluationFixtures.annotationFor` (same package, so internal symbols are reachable):

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertTrue

class EditSurfaceCandidateConventionTest {
    @Test
    fun customConventionResolvesOwnerEditSurface() {
        val bounds = FixThisRect(0f, 0f, 100f, 100f)
        val node = FixThisNode(
            uid = "n", composeNodeId = 1, rootIndex = 0, treeKind = TreeKind.MERGED,
            boundsInWindow = bounds, testTag = "MyScreen_title", path = listOf("root"),
        )
        val item = AnnotationDto(
            itemId = "i", screenId = "s", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node(node.uid, bounds),
            selectedNode = node,
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "app/MyScreen.kt", line = 10, score = 0.9,
                    confidence = SelectionConfidence.MEDIUM, matchReasons = listOf("selected testTag convention composable"),
                    matchedTerms = listOf("MyScreen"), ownerComposable = "MyScreen",
                ),
            ),
            comment = "make this heading red",
        )
        val conventions = TestTagConventionSet.fromPatternStrings(listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"))
        val candidates = EditSurfaceCandidateService.build(item, screen = null, conventions = conventions)
        assertTrue(
            candidates.any { it.file == "app/MyScreen.kt" },
            "expected owner edit-surface for custom convention; got $candidates",
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateConventionTest" --no-daemon`
Expected: FAIL — `build` has no `conventions` parameter.

- [ ] **Step 3: Add the parameter and use it**

In `EditSurfaceCandidateService.kt`, change `build` and `ownerCandidate` to accept conventions (default `TestTagConventionSet.Default` keeps existing callers working):

```kotlin
import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet

    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
        conventions: TestTagConventionSet = TestTagConventionSet.Default,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        val roleDecision = EditSurfaceRoleClassifier.classify(item, intent)
        val candidates = when {
            item.sourceCandidates.isEmpty() -> listOf(emptySourceCandidate(intent, roleDecision))
            intent.primaryKind == EditSurfaceKindDto.UNKNOWN &&
                roleDecision.role != EditSurfaceRoleDto.COPY_OR_DATA &&
                roleDecision.role != EditSurfaceRoleDto.LAYOUT_OR_STYLE -> emptyList()
            else -> sourceCandidates(item, screen, intent, roleDecision, conventions)
        }
        return candidates.distinctBy { it.file to it.line }.take(2)
    }
```

Thread `conventions` through `sourceCandidates(...)` into `ownerCandidate(...)`, and in `ownerCandidate` replace the two `TestTagConvention.parse(...)` calls with `conventions.parse(...)`. Update the `sourceCandidates` private function signature to accept and forward `conventions`.

- [ ] **Step 4: Pass the index conventions from `TargetEvidenceService`**

In `TargetEvidenceService.kt` at lines 156 and 250, the `sourceIndex` is in scope (the methods receive/hold it). Change both calls to:

```kotlin
EditSurfaceCandidateService.build(
    item,
    screen,
    conventions = TestTagConventionSet.fromPatternStrings(sourceIndex?.testTagConventions.orEmpty()),
)
```

Add the import `import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet`. If `sourceIndex` is not in scope at line 250 (refresh path), use the `sourceIndex` parameter that method receives (`refreshSourceEvidence(... sourceIndex: SourceIndex)` at line 205) — it is non-null there, so use `sourceIndex.testTagConventions`.

- [ ] **Step 5: Run test + full mcp suite**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateConventionTest" --no-daemon`
Expected: PASS

Run: `./gradlew :fixthis-mcp:test --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateConventionTest.kt
git commit -m "feat(mcp): edit-surface owner resolution honors project testTag conventions"
```

---

### Task 7: Corpus case for custom-convention tag

**Files:**
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt:16-29`

Add a 10th case exercising a custom-convention selection that resolves to the right owner via the candidate's `ownerComposable` (the corpus uses precomputed candidates, so this verifies the role/confidence path end-to-end, not the matcher).

- [ ] **Step 1: Add the case to `v06-corpus.json`**

Append before the closing `]` of `cases` (add a comma after the previous case):

```json
    {
      "id": "custom-convention-owner",
      "comment": "Make this heading red",
      "targetType": "node",
      "selectedText": ["Profile"],
      "selectedTestTag": "ProfileScreen_heading",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProfileScreen.kt",
          "line": 33,
          "score": 70,
          "confidence": "MEDIUM",
          "matchReasons": ["selected testTag convention composable"],
          "matchedTerms": ["ProfileScreen"],
          "ownerComposable": "ProfileScreen"
        }
      ],
      "expectedRole": "COMPONENT_DEFINITION",
      "expectedTop3Contains": "ProfileScreen.kt",
      "allowHighConfidence": false,
      "correctness": {
        "category": "shared-component",
        "ownerContains": "ProfileScreen.kt",
        "expectedRole": "COMPONENT_DEFINITION",
        "maxConfidence": "MEDIUM",
        "requiredCautions": [],
        "releaseCritical": true,
        "promptUsabilityRequired": true
      }
    }
```

- [ ] **Step 2: Update the stable-coverage assertion**

In `HandoffEvaluationCorpusTest.kt`, add `"custom-convention-owner"` to the expected id list in `corpusHasStableV06Coverage`.

- [ ] **Step 3: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test(mcp): corpus case for custom testTag convention owner"
```

---

## Phase 2 — K1 per-role confidence refinement

### Task 8: Raise `CALL_SITE` ceiling to HIGH with strong evidence

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt:42-45`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt` (create or modify if it exists)
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`

The current `CALL_SITE` branch caps at MEDIUM. K1: a CALL_SITE whose source candidate is already HIGH should stay HIGH (the role no longer suppresses strong, unambiguous evidence). COMPONENT_DEFINITION/COPY_OR_DATA/LAYOUT_OR_STYLE/VISUAL_AREA/INTEROP_RISK ceilings are unchanged.

- [ ] **Step 1: Check for an existing policy test**

Run: `ls fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt 2>/dev/null && echo EXISTS || echo CREATE`

- [ ] **Step 2: Write the failing test**

Create (or add to) `EditSurfaceConfidencePolicyTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class EditSurfaceConfidencePolicyTest {
    private fun candidate(confidence: SelectionConfidence) = SourceCandidate(
        file = "app/CheckoutScreen.kt", line = 42, score = 0.95,
        confidence = confidence, matchReasons = listOf("selected text"),
        matchedTerms = listOf("Continue"), ownerComposable = "CheckoutScreen",
    )

    @Test
    fun callSiteKeepsHighConfidenceWithStrongEvidence() {
        val result = EditSurfaceConfidencePolicy.score(EditSurfaceRoleDto.CALL_SITE, candidate(SelectionConfidence.HIGH))
        assertEquals(SelectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun componentDefinitionStillCapsAtMedium() {
        val result = EditSurfaceConfidencePolicy.score(EditSurfaceRoleDto.COMPONENT_DEFINITION, candidate(SelectionConfidence.HIGH))
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon`
Expected: FAIL — `callSiteKeepsHighConfidenceWithStrongEvidence` gets MEDIUM.

- [ ] **Step 4: Raise the CALL_SITE ceiling**

In `EditSurfaceConfidencePolicy.kt`, change the `CALL_SITE` branch:

```kotlin
            EditSurfaceRoleDto.CALL_SITE -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.HIGH),
                "call site matched${reasonSuffix(reasons)}",
            )
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Add a corpus case proving the gain (and that weak call-sites stay capped)**

Add a case to `v06-corpus.json` (comma after previous) where a strong unambiguous call-site is allowed HIGH:

```json
    {
      "id": "strong-call-site-high",
      "comment": "Move this button to the bottom",
      "targetType": "node",
      "selectedText": ["Checkout"],
      "selectedTestTag": "comp:CheckoutButton:primary",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/CartScreen.kt",
          "line": 64,
          "score": 96,
          "confidence": "HIGH",
          "matchReasons": ["selected testTag convention composable"],
          "matchedTerms": ["CheckoutButton"],
          "ownerComposable": "CheckoutButton"
        }
      ],
      "expectedRole": "CALL_SITE",
      "expectedTop3Contains": "CartScreen.kt",
      "allowHighConfidence": true,
      "correctness": {
        "category": "call-site",
        "ownerContains": "CartScreen.kt",
        "expectedRole": "CALL_SITE",
        "maxConfidence": "HIGH",
        "requiredCautions": [],
        "releaseCritical": true,
        "promptUsabilityRequired": true
      }
    }
```

Note: confirm the intent for this comment classifies as `CALL_SITE` (not COPY_OR_DATA). "Move this button" is a layout/position intent, not copy. If `EditSurfaceRoleClassifier` routes it elsewhere, adjust the comment to a clearly non-copy, non-spacing call-site intent (e.g. "Swap this button for an outlined variant") and re-run. Update `corpusHasStableV06Coverage`'s id list to include `"strong-call-site-high"`.

- [ ] **Step 7: Run the corpus + full mcp suite**

Run: `./gradlew :fixthis-mcp:test --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt fixthis-mcp/src/test
git commit -m "feat(mcp): call-site role keeps HIGH confidence under strong evidence (K1)"
```

---

## Phase 3 — K2 confident shared-component cap relaxation

### Task 9: Relax shared-component MEDIUM cap for a confident single call-site

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt:12-52`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:519-543`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt` (create or modify)

`applyCaps` always caps shared-component candidates at MEDIUM. K2: when call-site ranking marked exactly one `mostLikely` site with a clear margin AND a selected owner/tag locator is present, allow HIGH. The `mostLikely` decision is computed in `SourceMatcher.toCandidate` via `sharedComponentCallSites`; pass a boolean into `applyCaps`.

- [ ] **Step 1: Write the failing test**

Create (or extend) `SourceRiskClassifierTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRiskClassifierTest {
    private fun sharedProfile() = EvidenceProfile.fromMatchReasons(
        listOf(
            SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE,
            SourceMatchReason.SHARED_COMPONENT_DEFINITION,
        ),
        rawScore = 90.0,
    )

    @Test
    fun confidentSingleCallSiteAllowsHighDespiteSharedComponent() {
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile(),
            baseConfidence = SelectionConfidence.HIGH,
            confidentCallSite = true,
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
        assertTrue(result.flags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }

    @Test
    fun ambiguousSharedComponentStillCapsAtMedium() {
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile(),
            baseConfidence = SelectionConfidence.HIGH,
            confidentCallSite = false,
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceRiskClassifierTest" --no-daemon`
Expected: FAIL — `applyCaps` has no `confidentCallSite` parameter.

- [ ] **Step 3: Add the parameter and conditional cap**

In `SourceRiskClassifier.kt`, change `applyCaps`:

```kotlin
    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
        confidentCallSite: Boolean = false,
    ): Result {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence

        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isUntypedFallbackOnly -> {
                flags.add(SourceCandidateRisk.UNTYPED_FALLBACK)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.hasArbitraryLiteral && !profile.hasSelectedTestTag && !profile.hasStrictCompTag -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            if (!confidentCallSite) {
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        return Result(confidence, flags)
    }
```

Note: `capAt` raises *up to* a floor in the existing code (it returns `ceiling` when `current < ceiling`). Re-read its semantics — here we want to NOT lower a HIGH to MEDIUM when confident. Since `capAt(HIGH, MEDIUM)` currently returns HIGH (because `HIGH.ordinal < MEDIUM.ordinal` is false), verify the direction: confirm `SelectionConfidence` ordinal order is NONE<LOW<MEDIUM<HIGH. If so, `capAt` as written is actually a floor, not a ceiling — meaning the existing shared-component "cap" only raises low confidence to MEDIUM and never lowers HIGH. **Run Step 4's diagnostic before implementing** to confirm real behavior, then adjust: if `capAt` is a floor, the correct K2 change is to use a true ceiling helper for the shared-component case. Add if needed:

```kotlin
    private fun ceilingAt(
        current: SelectionConfidence,
        ceiling: SelectionConfidence,
    ): SelectionConfidence = if (current.ordinal > ceiling.ordinal) ceiling else current
```

and use `ceilingAt(confidence, SelectionConfidence.MEDIUM)` in the `!confidentCallSite` branch.

- [ ] **Step 4: Diagnostic — confirm SelectionConfidence ordinal + capAt semantics**

Run: `grep -n "enum class SelectionConfidence" -A8 fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SelectionConfidence.kt`
Expected: confirms declaration order. Ensure the test from Step 1 reflects true intended behavior (HIGH stays HIGH when confident; HIGH→MEDIUM when not). Use `ceilingAt` for the lowering case.

- [ ] **Step 5: Thread `confidentCallSite` from `SourceMatcher.toCandidate`**

In `SourceMatcher.kt` `toCandidate` (line ~519-543), the call sites are computed via `sharedComponentCallSites(...)`. A "confident" call-site = the produced `callSites` list has exactly one entry with `recommendedEditSite == true`. Compute it and pass to `applyCaps`:

```kotlin
        val callSites = sharedComponentCallSites(selectionTokens, sharedCallSitesByOwner)
        val confidentCallSite = callSites.count { it.recommendedEditSite } == 1 &&
            (profile.hasSelectedOwnerFunction || profile.hasStrictCompTag || profile.hasSelectedTestTag)
        // ...
        val capInfo = SourceRiskClassifier.applyCaps(profile, baseConfidence, confidentCallSite)
```

Ensure `profile.hasStrictCompTag` / `hasSelectedTestTag` exist on `EvidenceProfile` (they do — see `EvidenceProfile.kt`).

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceRiskClassifierTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: Run the full core suite (no calibration regressions)**

Run: `./gradlew :fixthis-compose-core:test --no-daemon`
Expected: PASS. If a pre-existing shared-component test now expects MEDIUM but gets HIGH, confirm whether that test's scenario is a confident single call-site; if so, update its expectation, otherwise the threading is too loose — tighten the `confidentCallSite` condition.

- [ ] **Step 8: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt
git commit -m "feat(core): confident single call-site relaxes shared-component cap (K2)"
```

---

### Task 10: End-to-end matcher test for confident vs. ambiguous shared component

**Files:**
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherConventionTest.kt` (reuse) or create `SourceMatcherSharedComponentTest.kt`

Guard the K2 path through the real `SourceMatcher.match`, not just the classifier unit.

- [ ] **Step 1: Inspect the call-site signal value format**

Run: `grep -n "SHARED_COMPONENT_CALL_SITE\|parseCallSiteSignal\|\\\\t" fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt`
Expected: confirms the `location<TAB>enclosing<TAB>lit|lit` value format (from `parseCallSiteSignal`). Use that exact tab-separated format to build the fixture signals.

- [ ] **Step 2: Write the failing/again-passing test**

Create `SourceMatcherSharedComponentTest.kt`. Build a `SourceIndex` where a shared component has two call sites, one of whose literals/enclosing strongly overlaps the selection tokens (so ranking marks one `mostLikely` with a clear margin), and assert the top candidate's confidence can be HIGH. Then build the ambiguous variant (both sites equal) and assert MEDIUM. Use the signal format from Step 1:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceMatcherSharedComponentTest {
    private fun node(testTag: String, text: List<String>) = FixThisNode(
        uid = "n", composeNodeId = 1, rootIndex = 0, treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 10f, 10f), text = text, testTag = testTag, path = listOf("root"),
    )

    private fun sharedIndex(callSites: List<String>) = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "app/PrimaryButton.kt", line = 7,
                signals = listOf(
                    SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "PrimaryButton"),
                    SourceSignal(SourceSignalKind.SHARED_COMPONENT, "PrimaryButton"),
                ) + callSites.map { SourceSignal(SourceSignalKind.SHARED_COMPONENT_CALL_SITE, it) },
            ),
        ),
    )

    @Test
    fun confidentCallSiteYieldsHigh() {
        // One call site's literal "Checkout" overlaps the selection; the other does not.
        val index = sharedIndex(
            listOf(
                "app/CartScreen.kt:64\tCartScreen\tCheckout",
                "app/HomeScreen.kt:20\tHomeScreen\tDismiss",
            ),
        )
        val top = SourceMatcher(index).match(node("comp:PrimaryButton:checkout", listOf("Checkout")), emptyList(), null).first()
        assertEquals(SelectionConfidence.HIGH, top.confidence)
    }

    @Test
    fun ambiguousCallSitesStayMedium() {
        val index = sharedIndex(
            listOf(
                "app/CartScreen.kt:64\tCartScreen\tShared",
                "app/HomeScreen.kt:20\tHomeScreen\tShared",
            ),
        )
        val top = SourceMatcher(index).match(node("comp:PrimaryButton:checkout", listOf("Shared")), emptyList(), null).first()
        assertEquals(SelectionConfidence.MEDIUM, top.confidence)
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherSharedComponentTest" --no-daemon`
Expected: PASS. If `confidentCallSiteYieldsHigh` returns MEDIUM, check that base confidence reached HIGH before the cap (a strict-comp testTag + clear margin) — if base was MEDIUM, the K2 relaxation correctly cannot raise it; strengthen the fixture's evidence (e.g. add selected text overlap) so base confidence is HIGH. If `ambiguousCallSitesStayMedium` returns HIGH, the `confidentCallSite` condition is too loose — revisit Task 9 Step 5.

- [ ] **Step 4: Commit**

```bash
git add fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherSharedComponentTest.kt
git commit -m "test(core): end-to-end confident vs ambiguous shared-component confidence"
```

---

## Final verification

- [ ] **Run the affected module suites**

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test :fixthis-gradle-plugin:test --no-daemon
```
Expected: all PASS.

- [ ] **Run the handoff eval evidence target**

```bash
npm run handoff:eval:test
```
Expected: PASS (corpus role/precision gates green).

- [ ] **Run the local CI mirror before any release claim**

```bash
npm run ci:local
```
Expected: PASS. (Per `CONTRIBUTING.md` required local checks.)

- [ ] **Update docs**

- Add a `## Unreleased` CHANGELOG entry describing: configurable testTag conventions, source-index schema `1.3`, call-site HIGH confidence, confident shared-component cap relaxation.
- In `docs/releases/unreleased.md`, bump the source-index schema note `1.2 → 1.3` and note the additive `testTagConventions` header.
- In `docs/product/roadmap.md` "Smarter source matching", mark "more composable-name conventions" and the per-role confidence scoring items as delivered.

- [ ] **Commit docs**

```bash
git add CHANGELOG.md docs/releases/unreleased.md docs/product/roadmap.md
git commit -m "docs: record source matching refinement (conventions, schema 1.3, calibration)"
```

---

## Self-Review Notes

- **Spec coverage:** Measurement (Tasks 1–2), C1 conventions (Tasks 3–7), K1 (Task 8), K2 (Tasks 9–10). All four selected spec items covered; out-of-scope items (C2/C3) excluded.
- **Backward compat:** `testTagConventions` defaults to empty → `TestTagConventionSet.Default`; `TestTagConvention.parse` retains old shape for identity-hint callers; schema bump is additive.
- **Known seams to verify during execution:** exact `SourceIndexGenerator` signature (Task 2/5 Step 1), `capAt` floor-vs-ceiling semantics (Task 9 Step 3–4), corpus role routing for the new CALL_SITE case (Task 8 Step 6). Each has an inline diagnostic step before the change.
