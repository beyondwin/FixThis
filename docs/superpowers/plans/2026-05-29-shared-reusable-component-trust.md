# Shared Reusable Component Trust Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect Compose component definitions invoked at >= 2 call sites at source-index time and make the matcher flag them as shared, cap their confidence at MEDIUM, and emit a verify-the-call-site caution instead of presenting them as precise edit targets.

**Architecture:** A new cross-file fan-in pass in the Gradle source-index generator attaches a `SHARED_COMPONENT` signal (value = call-site count) to definition entries. The core matcher reads that signal, and when the candidate matched *as a definition*, emits a `SHARED_COMPONENT_DEFINITION` reason that drives a new `SHARED_COMPONENT` risk flag, a MEDIUM confidence cap, and a caution. No re-ranking, no call-site resolution (behavior option A).

**Tech Stack:** Kotlin, JUnit4, kotlinx.serialization. Modules: `:fixthis-gradle-plugin` (index generation), `:fixthis-compose-core` (matcher + contracts). Gradle build via `./gradlew`.

Source design spec: `docs/superpowers/specs/2026-05-29-shared-reusable-component-trust-design.md`

---

## File Structure

**`:fixthis-compose-core` (consumes the index, owns trust logic):**
- Modify `.../compose/core/source/SourceIndex.kt` — add `SHARED_COMPONENT` to `SourceSignalKind`.
- Modify `.../compose/core/source/SourceMatcher.kt` — add `SHARED_COMPONENT -> 0.0` weight; emit `SHARED_COMPONENT_DEFINITION` reason.
- Modify `.../compose/core/source/SourceMatchReason.kt` — add `SHARED_COMPONENT_DEFINITION`.
- Modify `.../compose/core/source/EvidenceProfile.kt` — add `hasSharedComponentDefinition`.
- Modify `.../compose/core/source/SourceRiskClassifier.kt` — add the cap branch.
- Modify `.../compose/core/source/SourceConfidencePolicy.kt` — add the caution branch.
- Modify `.../compose/core/source/SourceCandidateRiskPrecedence.kt` — insert `SHARED_COMPONENT`.
- Modify `.../compose/core/model/SourceCandidateRisk.kt` — add `SHARED_COMPONENT`.
- Modify `.../compose/core/domain/evidence/SourceHint.kt` — add `SourceHintRisk.SHARED_COMPONENT`.
- Modify `.../compose/core/model/SourceCandidateMappers.kt` — map both directions.

**`:fixthis-gradle-plugin` (produces the index):**
- Modify `.../gradle/source/SourceIndexAssets.kt` — add `SHARED_COMPONENT` to `SourceSignalKindAsset`.
- Create `.../gradle/source/ComposableCallSiteFanIn.kt` — pure call-site counter + threshold constant.
- Modify `.../gradle/source/KotlinSemanticSignalScanner.kt` — expose `commentRanges()` as `internal`.
- Modify `.../gradle/source/SourceIndexGenerator.kt` — apply the fan-in pass.

**Tests:**
- Modify `.../compose/core/source/SourceIndexSerializationTest.kt`
- Modify `.../compose/core/source/SourceMatcherTest.kt`
- Modify `.../compose/core/source/SourceCandidateRiskPrecedenceTest.kt`
- Create `.../gradle/source/ComposableCallSiteFanInTest.kt`
- Create `.../gradle/source/SourceIndexGeneratorTest.kt`

**Docs:**
- Modify `docs/reference/mcp-tools.md`, `docs/reference/feedback-console-contract.md`, `CHANGELOG.md`, `docs/product/roadmap.md`.

> **Trust-evaluation note:** The Source Matching Trust Program asks for fixture-lab coverage before confidence behavior changes. Because the pinned sample repos are not checked out in this workspace, this plan satisfies that gate deterministically with a Gradle **generator integration test** (synthetic multi-file sources, Task 3) plus core matcher unit tests (Tasks 4, 6). A pinned-repo `source-index` manifest case is a deferred follow-up (Task 7 documents it) to avoid committing unverifiable expected paths.

---

## Task 1: Add the `SHARED_COMPONENT` source signal kind

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:408-424`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt:44-58`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt`

- [ ] **Step 1: Write the failing serialization test**

Add this test to `SourceIndexSerializationTest.kt` (use the same `Json` setup the existing tests use; if the file defines a private `json`, reuse it — otherwise use `Json { }`):

```kotlin
@Test
fun roundTripsSharedComponentSignal() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "app/ui/Button.kt",
                line = 10,
                symbols = listOf("PrimaryButton"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "3"),
                ),
                excerpt = "@Composable fun PrimaryButton(",
            ),
        ),
    )

    val encoded = Json.encodeToString(SourceIndex.serializer(), index)
    val decoded = Json.decodeFromString(SourceIndex.serializer(), encoded)

    val signal = decoded.entries.single().signals.single { it.kind == SourceSignalKind.SHARED_COMPONENT }
    assertEquals("3", signal.value)
}
```

If `Json` and `assertEquals` are not yet imported in the file, add:
```kotlin
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceIndexSerializationTest.roundTripsSharedComponentSignal"`
Expected: FAIL — `SourceSignalKind.SHARED_COMPONENT` is unresolved (compile error).

- [ ] **Step 3: Add the enum member in core**

In `SourceIndex.kt`, add the member to `SourceSignalKind` (after `LAYOUT_RENDERER`):

```kotlin
@Serializable
enum class SourceSignalKind {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
    STRING_RESOURCE_RESOLVED,
    LAMBDA_OWNER_FUNCTION,
    LAYOUT_RENDERER,
    SHARED_COMPONENT,
}
```

- [ ] **Step 4: Give the new kind a zero match weight**

In `SourceMatcher.kt`, the `baseMatchWeight` `when (this)` is exhaustive. Add the `SHARED_COMPONENT` branch alongside `LAYOUT_RENDERER` (it is a classification marker and must never add to a score):

```kotlin
            SourceSignalKind.LAYOUT_RENDERER -> LAYOUT_RENDERER_BASE_WEIGHT
            SourceSignalKind.SHARED_COMPONENT -> 0.0
            SourceSignalKind.ARBITRARY_STRING_LITERAL -> 0.35
```

- [ ] **Step 5: Add the mirror member in the Gradle asset enum**

In `SourceIndexAssets.kt`, add `SHARED_COMPONENT` to `SourceSignalKindAsset` (after `LAYOUT_RENDERER`) so the two enums serialize to matching names:

```kotlin
@Serializable
internal enum class SourceSignalKindAsset {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
    STRING_RESOURCE_RESOLVED,
    LAMBDA_OWNER_FUNCTION,
    LAYOUT_RENDERER,
    SHARED_COMPONENT,
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceIndexSerializationTest.roundTripsSharedComponentSignal"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt
git commit -m "feat(source): add SHARED_COMPONENT source signal kind"
```

---

## Task 2: Implement the composable call-site fan-in counter

**Files:**
- Create: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt:218` (change `private fun String.commentRanges()` to `internal`)
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ComposableCallSiteFanInTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.gradle.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ComposableCallSiteFanInTest {
    @Test
    fun countsDistinctCallSitesAcrossSourcesAndExcludesDeclaration() {
        val definitionSource = """
            @Composable
            fun PrimaryButton(label: String) { Text(label) }
        """.trimIndent()
        val callerOne = """
            @Composable
            fun ScreenA() {
                PrimaryButton("Save")
                PrimaryButton("Cancel")
            }
        """.trimIndent()
        val callerTwo = """
            @Composable
            fun ScreenB() { PrimaryButton("Delete") }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(definitionSource, callerOne, callerTwo),
            definitionNames = setOf("PrimaryButton"),
        )

        // 3 invocations; the `fun PrimaryButton(` declaration is not counted.
        assertEquals(3, counts["PrimaryButton"])
    }

    @Test
    fun ignoresCallsInStringsCommentsAndQualifiedReceivers() {
        val source = """
            // PrimaryButton("commented out")
            @Composable
            fun Screen() {
                val note = "PrimaryButton(\"in a string\")"
                theme.PrimaryButton("qualified-call")
                PrimaryButton("real")
            }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(source),
            definitionNames = setOf("PrimaryButton"),
        )

        assertEquals(1, counts["PrimaryButton"])
    }

    @Test
    fun returnsNoEntryForSingleUseAndUnknownNames() {
        val source = """
            @Composable
            fun Once() {}
            @Composable
            fun Screen() { Once() }
        """.trimIndent()

        val counts = composableCallSiteCounts(
            sources = listOf(source),
            definitionNames = setOf("Once", "NeverDefined"),
        )

        assertEquals(1, counts["Once"])
        assertNull(counts["NeverDefined"])
        assertFalse(SHARED_COMPONENT_FANIN_THRESHOLD <= (counts["Once"] ?: 0))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest"`
Expected: FAIL — `composableCallSiteCounts` and `SHARED_COMPONENT_FANIN_THRESHOLD` are unresolved.

- [ ] **Step 3: Make `commentRanges` reusable**

In `KotlinSemanticSignalScanner.kt` line 218, change visibility:

```kotlin
internal fun String.commentRanges(): List<IntRange> {
```

(The body is unchanged — only `private` becomes `internal`.)

- [ ] **Step 4: Implement the counter**

Create `ComposableCallSiteFanIn.kt`:

```kotlin
package io.github.beyondwin.fixthis.gradle.source

/** A composable is treated as a shared/reusable component once it is invoked at this many call sites. */
internal const val SHARED_COMPONENT_FANIN_THRESHOLD: Int = 2

private val composableCallRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val funDeclarationBeforeNameRegex = Regex("""\bfun\s+$""")

/**
 * Counts how many call sites invoke each composable definition across the given sources.
 *
 * A call site is `Name(` where `Name` is a known definition and the occurrence is not the
 * `fun Name(` declaration, not inside a string or comment, and not a qualified `receiver.Name(`
 * member call. Occurrences are distinct by source position, so each invocation counts once.
 */
internal fun composableCallSiteCounts(
    sources: List<String>,
    definitionNames: Set<String>,
): Map<String, Int> {
    if (definitionNames.isEmpty()) return emptyMap()
    val counts = mutableMapOf<String, Int>()
    sources.forEach { source ->
        val ignoredRanges = source.callSiteIgnoredRanges()
        composableCallRegex.findAll(source).forEach { match ->
            val name = match.groupValues[1]
            if (name !in definitionNames) return@forEach
            val start = match.range.first
            if (ignoredRanges.any { start in it }) return@forEach
            if (start > 0 && source[start - 1] == '.') return@forEach
            if (source.isFunctionDeclarationBefore(start)) return@forEach
            counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts
}

private fun String.callSiteIgnoredRanges(): List<IntRange> =
    kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList() + commentRanges()

private fun String.isFunctionDeclarationBefore(offset: Int): Boolean {
    val lineStart = lastIndexOf('\n', startIndex = offset - 1).let { if (it == -1) 0 else it + 1 }
    return funDeclarationBeforeNameRegex.containsMatchIn(substring(lineStart, offset))
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt \
        fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt \
        fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt
git commit -m "feat(source): count composable call-site fan-in"
```

---

## Task 3: Attach the `SHARED_COMPONENT` signal in the index generator

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGeneratorTest.kt`

This is the trust-evaluation gate: it proves a reused definition gains the signal and a single-use definition does not, before any confidence behavior changes.

- [ ] **Step 1: Write the failing integration test**

Create `SourceIndexGeneratorTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceIndexGeneratorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kotlin(relativePath: String, body: String): File {
        val file = File(tempFolder.root, relativePath)
        file.parentFile.mkdirs()
        file.writeText(body)
        return file
    }

    private fun generate(files: List<File>): SourceIndexAsset {
        val generator = SourceIndexGenerator(
            projectDirectory = tempFolder.root,
            rootProjectDirectory = tempFolder.root,
            projectPath = ":app",
            json = Json { ignoreUnknownKeys = true },
        )
        return generator.generate(kotlinFiles = files, xmlFiles = emptyList())
    }

    @Test
    fun flagsReusedComponentDefinitionWithFanInCount() {
        val definition = kotlin(
            "ui/PrimaryButton.kt",
            """
            @Composable
            fun PrimaryButton(label: String) {}
            """.trimIndent(),
        )
        val callerA = kotlin(
            "ui/ScreenA.kt",
            """
            @Composable
            fun ScreenA() { PrimaryButton("Save") }
            """.trimIndent(),
        )
        val callerB = kotlin(
            "ui/ScreenB.kt",
            """
            @Composable
            fun ScreenB() { PrimaryButton("Cancel") }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, callerA, callerB))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "PrimaryButton" }
        }
        val sharedSignal = definitionEntry.signals.single { it.kind == SourceSignalKindAsset.SHARED_COMPONENT }
        assertEquals("2", sharedSignal.value)
    }

    @Test
    fun doesNotFlagSingleUseComponentDefinition() {
        val definition = kotlin(
            "ui/OnceCard.kt",
            """
            @Composable
            fun OnceCard() {}
            """.trimIndent(),
        )
        val caller = kotlin(
            "ui/Screen.kt",
            """
            @Composable
            fun Screen() { OnceCard() }
            """.trimIndent(),
        )

        val asset = generate(listOf(definition, caller))

        val definitionEntry = asset.entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL && it.value == "OnceCard" }
        }
        assertFalse(definitionEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SourceIndexGeneratorTest"`
Expected: FAIL — no `SHARED_COMPONENT` signal is attached (the first test finds no such signal).

- [ ] **Step 3: Apply the fan-in pass in the generator**

Replace the body of `generate` in `SourceIndexGenerator.kt` (lines 23-40) with:

```kotlin
    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset {
        val stringResourceResolver = xmlScanner.resolveDefaults(xmlFiles)
        val sortedKotlinFiles = kotlinFiles
            .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
        val entries = buildList {
            sortedKotlinFiles.forEach { addAll(kotlinScanner.scan(it, stringResourceResolver)) }
            xmlFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(xmlScanner.scan(it)) }
        }
        val definitionNames = entries
            .flatMap { entry -> entry.signals }
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .map { it.value }
            .toSet()
        val callSiteCounts = composableCallSiteCounts(
            sources = sortedKotlinFiles.map { it.readText() },
            definitionNames = definitionNames,
        )
        return SourceIndexAsset(
            sourceRoot = SourceRootAsset(
                gradlePath = projectPath,
                projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                    .let { if (it == ".") "" else it },
            ),
            entries = entries.map { it.withSharedComponentSignal(callSiteCounts) },
        )
    }

    private fun SourceIndexEntryAsset.withSharedComponentSignal(
        callSiteCounts: Map<String, Int>,
    ): SourceIndexEntryAsset {
        val maxFanIn = signals
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .mapNotNull { callSiteCounts[it.value] }
            .maxOrNull()
        return if (maxFanIn != null && maxFanIn >= SHARED_COMPONENT_FANIN_THRESHOLD) {
            copy(signals = signals + SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT, maxFanIn.toString()))
        } else {
            this
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SourceIndexGeneratorTest"`
Expected: PASS (both tests)

- [ ] **Step 5: Run the full Gradle-plugin source test suite for regressions**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*.source.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt \
        fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGeneratorTest.kt
git commit -m "feat(source): emit SHARED_COMPONENT signal for reused definitions"
```

---

## Task 4: Emit the `SHARED_COMPONENT_DEFINITION` match reason

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:181-186`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `SourceMatcherTest.kt`:

```kotlin
@Test
fun emitsSharedComponentDefinitionReasonWhenDefinitionMatchesAndSignalPresent() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                line = 8,
                symbols = listOf("PrimaryButton"),
                testTags = listOf("comp:PrimaryButton:root"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                    SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "4"),
                ),
                excerpt = "@Composable fun PrimaryButton(",
            ),
        ),
    )

    val matches = SourceMatcher(index).match(
        selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
        nearbyNodes = emptyList(),
        activityName = null,
    )

    assertTrue(matches.first().matchReasons.contains("shared component definition"))
}

@Test
fun doesNotEmitSharedComponentReasonForTextOnlyMatchInReusedFile() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                line = 8,
                symbols = listOf("PrimaryButton"),
                text = listOf("Save changes"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                    SourceSignal(kind = SourceSignalKind.UI_TEXT, value = "Save changes"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "4"),
                ),
                excerpt = "Text(\"Save changes\")",
            ),
        ),
    )

    val matches = SourceMatcher(index).match(
        selectedNode = node(uid = "save", text = listOf("Save changes")),
        nearbyNodes = emptyList(),
        activityName = null,
    )

    assertFalse(matches.first().matchReasons.contains("shared component definition"))
}
```

Confirm the test file's `node(...)` helper supports the `testTag` and `text` parameters used here (the existing tests at the top of the file already call `node(... testTag = ..., text = ...)`, so it does).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.emitsSharedComponentDefinitionReasonWhenDefinitionMatchesAndSignalPresent" --tests "*SourceMatcherTest.doesNotEmitSharedComponentReasonForTextOnlyMatchInReusedFile"`
Expected: FAIL — `"shared component definition"` reason is never present (first test fails on assertTrue).

- [ ] **Step 3: Add the match reason**

In `SourceMatchReason.kt`, add the member after `LAYOUT_RENDERER_CONTEXT`:

```kotlin
    LAYOUT_RENDERER_CONTEXT("layout renderer context"),
    SHARED_COMPONENT_DEFINITION("shared component definition"),
    ACTIVITY("activity"),
```

- [ ] **Step 4: Emit the reason in the matcher**

In `SourceMatcher.kt`, immediately after the existing `LAYOUT_RENDERER_CONTEXT` post-processing block (after line 186, before `return MatchScore(`), add:

```kotlin
        if (
            entry.signals.any { signal -> signal.kind == SourceSignalKind.SHARED_COMPONENT } &&
            (
                SourceMatchReason.SELECTED_OWNER_FUNCTION in matchReasons ||
                    SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE in matchReasons
            )
        ) {
            matchReasons.add(SourceMatchReason.SHARED_COMPONENT_DEFINITION)
        }
```

- [ ] **Step 5: Expose the flag on `EvidenceProfile`**

In `EvidenceProfile.kt`, add this property next to `hasSelectedOwnerFunction` (around line 10):

```kotlin
    val hasSharedComponentDefinition: Boolean = SourceMatchReason.SHARED_COMPONENT_DEFINITION in reasons
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.emitsSharedComponentDefinitionReasonWhenDefinitionMatchesAndSignalPresent" --tests "*SourceMatcherTest.doesNotEmitSharedComponentReasonForTextOnlyMatchInReusedFile"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source): mark candidates matched as shared component definitions"
```

---

## Task 5: Add the `SHARED_COMPONENT` risk flag and its contract mirrors

This task introduces the `SourceCandidateRisk.SHARED_COMPONENT` enum member. Because three exhaustive `when` sites and one mirror enum reference it, they are all updated in this single commit so the module keeps compiling.

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt:16-24`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt:70-88`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt:13-28`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt:6-14`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedenceTest.kt`

- [ ] **Step 1: Write the failing precedence test**

Add to `SourceCandidateRiskPrecedenceTest.kt`:

```kotlin
@Test
fun sharedComponentRanksBelowAmbiguousAndAboveAreaSelection() {
    val ordered = SourceCandidateRiskPrecedence.ordered(
        listOf(
            SourceCandidateRisk.AREA_SELECTION,
            SourceCandidateRisk.SHARED_COMPONENT,
            SourceCandidateRisk.AMBIGUOUS,
        ),
    )

    assertEquals(
        listOf(
            SourceCandidateRisk.AMBIGUOUS,
            SourceCandidateRisk.SHARED_COMPONENT,
            SourceCandidateRisk.AREA_SELECTION,
        ),
        ordered,
    )
}

@Test
fun sharedComponentIsHighestWhenNoAmbiguity() {
    val highest = SourceCandidateRiskPrecedence.highest(
        listOf(SourceCandidateRisk.TEXT_ONLY, SourceCandidateRisk.SHARED_COMPONENT),
    )
    assertEquals(SourceCandidateRisk.SHARED_COMPONENT, highest)
}
```

If `SourceCandidateRisk` / `assertEquals` are not imported in this test file, add the imports
`import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk` and
`import org.junit.Assert.assertEquals`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateRiskPrecedenceTest"`
Expected: FAIL — `SourceCandidateRisk.SHARED_COMPONENT` is unresolved (compile error).

- [ ] **Step 3: Add the enum member**

In `SourceCandidateRisk.kt`:

```kotlin
@Serializable
enum class SourceCandidateRisk {
    AMBIGUOUS,
    SHARED_COMPONENT,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,

    @SerialName("LEGACY_FALLBACK")
    UNTYPED_FALLBACK,
}
```

- [ ] **Step 4: Add the domain mirror member**

In `SourceHint.kt`, add `SHARED_COMPONENT` to `SourceHintRisk`:

```kotlin
enum class SourceHintRisk {
    AMBIGUOUS,
    SHARED_COMPONENT,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,
    UNTYPED_FALLBACK,
}
```

- [ ] **Step 5: Map both directions**

In `SourceCandidateMappers.kt`, add the branch to both `when` blocks. In `toSourceCandidateRisk` (after the `AMBIGUOUS` branch):

```kotlin
    SourceHintRisk.AMBIGUOUS -> SourceCandidateRisk.AMBIGUOUS
    SourceHintRisk.SHARED_COMPONENT -> SourceCandidateRisk.SHARED_COMPONENT
    SourceHintRisk.TEXT_ONLY -> SourceCandidateRisk.TEXT_ONLY
```

In `toSourceHintRisk` (after the `AMBIGUOUS` branch):

```kotlin
    SourceCandidateRisk.AMBIGUOUS -> SourceHintRisk.AMBIGUOUS
    SourceCandidateRisk.SHARED_COMPONENT -> SourceHintRisk.SHARED_COMPONENT
    SourceCandidateRisk.TEXT_ONLY -> SourceHintRisk.TEXT_ONLY
```

- [ ] **Step 6: Add the caution branch**

In `SourceConfidencePolicy.kt`, add a branch inside `when (highest)` (after the `AMBIGUOUS` branch):

```kotlin
                SourceCandidateRisk.AMBIGUOUS ->
                    "Verify this source candidate before editing; top candidates are close."
                SourceCandidateRisk.SHARED_COMPONENT ->
                    "Shared component definition (used in multiple places); editing it changes every usage. Verify the specific call site before editing."
                SourceCandidateRisk.AREA_SELECTION ->
                    "Visual-area selection; use screenshot and bounds before editing."
```

- [ ] **Step 7: Insert into the precedence list**

In `SourceCandidateRiskPrecedence.kt`, insert `SHARED_COMPONENT` after `AMBIGUOUS`:

```kotlin
    val orderedHighestFirst: List<SourceCandidateRisk> = listOf(
        SourceCandidateRisk.AMBIGUOUS,
        SourceCandidateRisk.SHARED_COMPONENT,
        SourceCandidateRisk.AREA_SELECTION,
        SourceCandidateRisk.TEXT_ONLY,
        SourceCandidateRisk.NEARBY_ONLY,
        SourceCandidateRisk.ARBITRARY_LITERAL,
        SourceCandidateRisk.ACTIVITY_ONLY,
        SourceCandidateRisk.UNTYPED_FALLBACK,
    )
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateRiskPrecedenceTest"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedenceTest.kt
git commit -m "feat(source): add SHARED_COMPONENT risk flag, caution, and precedence"
```

---

## Task 6: Cap shared-component confidence and attach the flag end-to-end

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt:12-47`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `SourceMatcherTest.kt`:

```kotlin
@Test
fun capsSharedComponentDefinitionAtMediumWithFlagAndCaution() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                line = 8,
                symbols = listOf("PrimaryButton"),
                testTags = listOf("comp:PrimaryButton:root"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                    SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "5"),
                ),
                excerpt = "@Composable fun PrimaryButton(",
            ),
        ),
    )

    val candidate = SourceMatcher(index).match(
        selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
        nearbyNodes = emptyList(),
        activityName = null,
    ).first()

    assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
    assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    assertEquals(
        "Shared component definition (used in multiple places); editing it changes every usage. Verify the specific call site before editing.",
        candidate.caution,
    )
}

@Test
fun singleUseDefinitionWithSameEvidenceStaysHigh() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                line = 8,
                symbols = listOf("PrimaryButton"),
                testTags = listOf("comp:PrimaryButton:root"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                    SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                ),
                excerpt = "@Composable fun PrimaryButton(",
            ),
        ),
    )

    val candidate = SourceMatcher(index).match(
        selectedNode = node(uid = "primary", testTag = "comp:PrimaryButton:root"),
        nearbyNodes = emptyList(),
        activityName = null,
    ).first()

    assertEquals(SelectionConfidence.HIGH, candidate.confidence)
    assertFalse(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.capsSharedComponentDefinitionAtMediumWithFlagAndCaution" --tests "*SourceMatcherTest.singleUseDefinitionWithSameEvidenceStaysHigh"`
Expected: FAIL — the first test fails: confidence is HIGH and the `SHARED_COMPONENT` flag is absent (the cap is not yet applied).

- [ ] **Step 3: Apply the cap in the classifier**

In `SourceRiskClassifier.kt`, add an independent ceiling after the existing `when` block, before `return Result(...)`:

```kotlin
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            confidence = capAt(confidence, SelectionConfidence.MEDIUM)
        }

        return Result(confidence, flags)
```

This co-exists with the exclusive `when` (a shared definition that is also text-only keeps both flags; precedence and caution handle display order). `capAt` only lowers confidence, so a definition with LOW base confidence is unaffected.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.capsSharedComponentDefinitionAtMediumWithFlagAndCaution" --tests "*SourceMatcherTest.singleUseDefinitionWithSameEvidenceStaysHigh"`
Expected: PASS

- [ ] **Step 5: Run the whole core source suite for regressions**

Run: `./gradlew :fixthis-compose-core:test --tests "*.source.*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source): cap shared component definitions at medium confidence"
```

---

## Task 7: Documentation and changelog

**Files:**
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/product/roadmap.md`

- [ ] **Step 1: Document the risk flag in the MCP tools reference**

Open `docs/reference/mcp-tools.md`, find the section that lists `sourceCandidates` risk flags / cautions (search for `AMBIGUOUS` or `riskFlags`). Add an entry describing the new flag:

> `SHARED_COMPONENT` — the candidate is a composable definition invoked at multiple call sites. FixThis caps its confidence at medium and cautions that editing the definition changes every usage; the agent should verify the specific call site. This is best-effort guidance, not a precise edit-target claim.

- [ ] **Step 2: Document the caution in the console contract**

Open `docs/reference/feedback-console-contract.md`, find where source-candidate cautions / risk flags are described, and add a sentence noting that a `SHARED_COMPONENT` risk flag renders its caution string like other flags and requires no console-side schema change (the console already renders `caution` text and tolerates additional `riskFlags` members).

- [ ] **Step 3: Add a CHANGELOG entry**

In `CHANGELOG.md`, under `## Unreleased` → `### Added`, add:

```markdown
- Source matching now detects reusable Compose component definitions. When a
  selected target's best candidate is a composable definition invoked at two or
  more call sites, the handoff carries a `SHARED_COMPONENT` risk flag, caps the
  candidate at medium confidence, and cautions that editing the definition
  changes every usage so the agent verifies the specific call site.
```

- [ ] **Step 4: Update the roadmap bullet**

In `docs/product/roadmap.md`, under "Smarter source matching" → "richer handling for shared reusable components", append a parenthetical noting initial coverage shipped:

```markdown
- richer handling for shared reusable components (initial: high fan-in
  component definitions are flagged `SHARED_COMPONENT` and capped at medium
  confidence; future work can disambiguate the specific call site)
```

Also add a deferred follow-up note (so the pinned-repo trust case is tracked):

```markdown
  - Follow-up: add a pinned-repo `source-index` fixture-lab case asserting a
    known reused component definition emits the `SHARED_COMPONENT` signal, once
    a sample repo with a clearly reused component is selected.
```

- [ ] **Step 5: Commit**

```bash
git add docs/reference/mcp-tools.md docs/reference/feedback-console-contract.md CHANGELOG.md docs/product/roadmap.md
git commit -m "docs: document shared component trust flag and caution"
```

---

## Task 8: Console/bridge tolerance check and full verification matrix

**Files:**
- Inspect: console JS that renders `sourceCandidates` (search the console assets for `riskFlags` / `caution`).
- Possibly modify: console JS only if it does not tolerate an unknown risk flag.

- [ ] **Step 1: Locate where the console renders risk flags and cautions**

Run: `grep -rn "riskFlags\|caution" console/ scripts/ 2>/dev/null | grep -vi node_modules | head -40`
(If the console source lives elsewhere, search the repo: `grep -rln "riskFlags" --include=*.js --include=*.mjs .`)

Read the rendering code. Determine whether risk flags are mapped through a fixed lookup (which could drop or error on an unknown flag) or rendered generically.

- [ ] **Step 2: Confirm tolerance, or add minimal handling**

Expected: the console renders the `caution` string directly and either ignores unknown `riskFlags` or renders them as plain chips. If, and only if, an unknown flag would throw or render incorrectly, add a label for `SHARED_COMPONENT` (e.g. "shared component") in the same place existing flags are labeled, and rebundle:

Run (only if console JS changed): `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`

If no console change is needed, record that the console already tolerates the new flag and renders its caution.

- [ ] **Step 3: Check the bridge-protocol question**

Read `docs/reference/bridge-protocol.md`. Confirm that adding an enum member to the existing `riskFlags` field is additive and does NOT require a `BridgeProtocol.VERSION` bump (no new wire field, no removed field, no semantic change to an existing field). If the doc's rules say otherwise, follow the bridge-protocol checklist (update `BridgeProtocol.VERSION`, console `MinimumSupportedProtocolVersion`, and the mirrored constants, then ensure `BridgeProtocolVersionSyncTest` passes). Expected outcome: no bump required.

- [ ] **Step 4: Run the full local verification matrix**

Run the required local checks from `CONTRIBUTING.md`:

```bash
./gradlew :fixthis-compose-core:test :fixthis-gradle-plugin:test :fixthis-mcp:test
./gradlew detekt
git diff --check
```

Also run the console asset check and JS syntax/harness checks per `CONTRIBUTING.md#required-local-checks` (only the console steps relevant to your change set). Expected: all PASS.

- [ ] **Step 5: Commit any console/doc changes from this task**

```bash
git add -A
git commit -m "chore: verify console and bridge tolerance for shared component flag"
```

(If nothing changed in this task, skip the commit.)

---

## Self-Review Notes

- **Spec coverage:** Layer 1 (index fan-in) → Tasks 2-3; Layer 2 (matcher classification) → Tasks 4, 6; Layer 3 (precedence + caution) → Task 5; Layer 4 (edit-surface) → intentionally **no code change** to honor behavior option A (flag + cap + caution only, not edit-surface re-routing, which was the unselected option C); Trust evaluation → Task 3 generator integration test + Tasks 4/6 unit tests, with the pinned-repo fixture case deferred and tracked in Task 7; Contracts/compatibility → Tasks 1, 5, 8; Testing → every task is TDD.
- **Enum fan-out covered:** `SourceSignalKind` (core) + `SourceSignalKindAsset` (gradle) + `baseMatchWeight` when (Task 1); `SourceCandidateRisk` + `SourceHintRisk` + both `SourceCandidateMappers` whens + `SourceConfidencePolicy` when + precedence list (Task 5). These are the only compile sites that reference the affected enums (verified by grep).
- **Type consistency:** `composableCallSiteCounts(sources, definitionNames)` and `SHARED_COMPONENT_FANIN_THRESHOLD` are defined in Task 2 and consumed unchanged in Task 3; `hasSharedComponentDefinition` defined in Task 4 and consumed in Task 6; wire label `"shared component definition"` matches between Task 4's reason and Task 4's matcher test; the caution string is identical in Task 5 (policy) and Task 6 (assertion).
