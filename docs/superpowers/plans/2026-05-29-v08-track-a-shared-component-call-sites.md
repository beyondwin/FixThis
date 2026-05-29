# v0.8 Track A — Shared-Component Call-Site Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the source matcher flags a candidate as a shared component definition, attach the inventory of call-site locations (`file:line`) so the agent can verify the right usage instead of grepping — without raising confidence or claiming a precise target.

**Architecture:** The Gradle plugin already counts composable call-site fan-in (`ComposableCallSiteFanIn.kt`) and emits a `SHARED_COMPONENT` count signal. This plan retains the call-site *locations* (not just the count), serializes them as additive `SHARED_COMPONENT_CALL_SITE` signals on the source-index entry, teaches the core matcher to read them into a new additive `SourceCandidate.callSites` field, and renders them in the feedback console. Everything is additive: new enum members and new optional fields only — no renames, no confidence change, `:fixthis-compose-core` stays pure.

**Tech Stack:** Kotlin (Gradle plugin + `:fixthis-compose-core`), kotlinx.serialization, JUnit4 (Gradle plugin) / kotlin.test (core), Node.js `node:test` for console JS.

**Spec:** `docs/superpowers/specs/2026-05-29-v08-source-matching-depth-console-sync-umbrella-design.md` (Track A).

**Key constants:**
- `SHARED_COMPONENT_FANIN_THRESHOLD = 2` (existing, in `ComposableCallSiteFanIn.kt`).
- `SHARED_COMPONENT_CALLSITE_LIMIT = 10` (new) — max call-site locations emitted per definition. The list is best-effort context, not a complete inventory; consistent with the spec non-goal "never claim a complete inventory beyond the cap," no separate overflow count is added.

**Wire format:** each call-site location is one `SHARED_COMPONENT_CALL_SITE` signal whose `value` is `"<relativeSourcePath>:<line>"` (e.g. `"sample/src/main/java/ui/ScreenA.kt:42"`). The existing `SHARED_COMPONENT` count signal is unchanged.

---

## Task 1: Gradle — capture call-site locations (not just counts)

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ComposableCallSiteFanInTest.kt` (keep existing tests):

```kotlin
@Test
fun recordsCallSiteLocationsByFileAndLine() {
    val sources = listOf(
        CallSiteSource(
            path = "ui/ScreenA.kt",
            content = "@Composable\nfun ScreenA() {\n  PrimaryButton(\"Save\")\n}\n",
        ),
        CallSiteSource(
            path = "ui/ScreenB.kt",
            content = "@Composable\nfun ScreenB() {\n  PrimaryButton(\"Cancel\")\n}\n",
        ),
    )

    val sites = composableCallSites(sources, definitionNames = setOf("PrimaryButton"))

    assertEquals(
        listOf(
            ComposableCallSite(file = "ui/ScreenA.kt", line = 3),
            ComposableCallSite(file = "ui/ScreenB.kt", line = 3),
        ),
        sites["PrimaryButton"],
    )
}

@Test
fun callSiteLocationsExcludeDeclarationsStringsAndMemberCalls() {
    val sources = listOf(
        CallSiteSource(
            path = "ui/PrimaryButton.kt",
            content = "@Composable\nfun PrimaryButton(label: String) {}\n",
        ),
        CallSiteSource(
            path = "ui/Screen.kt",
            content =
                "@Composable\n" +
                    "fun Screen() {\n" +
                    "  val s = \"PrimaryButton(\"\n" + // string literal, ignored
                    "  obj.PrimaryButton(\"x\")\n" + // member call, ignored
                    "  PrimaryButton(\"real\")\n" + // real call site, line 5
                    "}\n",
        ),
    )

    val sites = composableCallSites(sources, definitionNames = setOf("PrimaryButton"))

    assertEquals(listOf(ComposableCallSite(file = "ui/Screen.kt", line = 5)), sites["PrimaryButton"])
}
```

If the test file has no imports for `assertEquals`, add `import org.junit.Assert.assertEquals` at the top.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest*" --no-daemon`
Expected: FAIL — `CallSiteSource`, `ComposableCallSite`, and `composableCallSites` are unresolved references.

- [ ] **Step 3: Implement location capture**

In `ComposableCallSiteFanIn.kt`, add the types and a location-producing function, then redefine `composableCallSiteCounts` in terms of it so the existing count behavior is preserved (DRY):

```kotlin
/** A source file's relative path plus its full text, used for call-site scanning. */
internal data class CallSiteSource(val path: String, val content: String)

/** A single resolved call site of a composable definition. */
internal data class ComposableCallSite(val file: String, val line: Int)

/**
 * Finds the call sites of each composable definition across the given sources.
 *
 * A call site is `Name(` where `Name` is a known definition and the occurrence is not the
 * `fun Name(` declaration, not inside a string or comment, and not a qualified `receiver.Name(`
 * member call. Occurrences are distinct by source position, so each invocation counts once.
 * The definition's own declaration is excluded.
 */
internal fun composableCallSites(
    sources: List<CallSiteSource>,
    definitionNames: Set<String>,
): Map<String, List<ComposableCallSite>> {
    if (definitionNames.isEmpty()) return emptyMap()
    val sites = linkedMapOf<String, MutableList<ComposableCallSite>>()
    sources.forEach { source ->
        val text = source.content
        val ignoredRanges = text.callSiteIgnoredRanges()
        composableCallRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name !in definitionNames) return@forEach
            val start = match.range.first
            if (ignoredRanges.any { start in it }) return@forEach
            if (start > 0 && text[start - 1] == '.') return@forEach
            if (text.isFunctionDeclarationBefore(start)) return@forEach
            val line = text.lineNumberAt(start)
            sites.getOrPut(name) { mutableListOf() }.add(ComposableCallSite(file = source.path, line = line))
        }
    }
    return sites
}

internal fun composableCallSiteCounts(
    sources: List<String>,
    definitionNames: Set<String>,
): Map<String, Int> = composableCallSites(
    sources = sources.map { CallSiteSource(path = "", content = it) },
    definitionNames = definitionNames,
).mapValues { it.value.size }

private fun String.lineNumberAt(offset: Int): Int {
    var line = 1
    for (i in 0 until offset) {
        if (this[i] == '\n') line += 1
    }
    return line
}
```

Keep the existing `composableCallRegex`, `funDeclarationBeforeNameRegex`, `callSiteIgnoredRanges()`, and `isFunctionDeclarationBefore(...)` as-is.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest*" --no-daemon`
Expected: PASS (new location tests and pre-existing count tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt
git commit -m "feat(source): capture composable call-site locations"
```

---

## Task 2: Gradle — emit `SHARED_COMPONENT_CALL_SITE` signals

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt` (add enum member)
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt` (add the limit constant)
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SourceIndexGeneratorTest.kt`:

```kotlin
@Test
fun emitsCallSiteSignalsForReusedComponentDefinition() {
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
    val callSiteValues = definitionEntry.signals
        .filter { it.kind == SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE }
        .map { it.value }
        .toSet()

    assertEquals(
        setOf("ui/ScreenA.kt:2", "ui/ScreenB.kt:2"),
        callSiteValues,
    )
}

@Test
fun doesNotEmitCallSiteSignalsForSingleUseDefinition() {
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
    assertFalse(definitionEntry.signals.any { it.kind == SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE })
}
```

Note: each caller body is on line 2 of its trimmed source (line 1 is `@Composable`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SourceIndexGeneratorTest*" --no-daemon`
Expected: FAIL — `SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE` is unresolved.

- [ ] **Step 3a: Add the enum member**

In `SourceIndexAssets.kt`, add to `enum class SourceSignalKindAsset` (after `SHARED_COMPONENT`):

```kotlin
    SHARED_COMPONENT,
    SHARED_COMPONENT_CALL_SITE,
}
```

- [ ] **Step 3b: Add the limit constant**

In `ComposableCallSiteFanIn.kt`, below the existing threshold constant:

```kotlin
internal const val SHARED_COMPONENT_FANIN_THRESHOLD: Int = 2

/** Maximum call-site locations emitted per shared component definition (best-effort context, not a complete inventory). */
internal const val SHARED_COMPONENT_CALLSITE_LIMIT: Int = 10
```

- [ ] **Step 3c: Emit call-site signals in the generator**

In `SourceIndexGenerator.kt`, replace `composableCallSiteCounts(...)` usage with `composableCallSites(...)` and emit both the count signal and capped call-site signals. Replace the body of `generate(...)`'s tail and `withSharedComponentSignal`:

```kotlin
        val callSites = composableCallSites(
            sources = sortedKotlinFiles.map { file ->
                CallSiteSource(
                    path = file.canonicalFile.relativeSourcePath(projectDirectory, rootProjectDirectory),
                    content = file.readText(),
                )
            },
            definitionNames = definitionNames,
        )
        return SourceIndexAsset(
            sourceRoot = SourceRootAsset(
                gradlePath = projectPath,
                projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                    .let { if (it == ".") "" else it },
            ),
            entries = entries.map { it.withSharedComponentSignal(callSites) },
        )
    }

    private fun SourceIndexEntryAsset.withSharedComponentSignal(
        callSites: Map<String, List<ComposableCallSite>>,
    ): SourceIndexEntryAsset {
        val best = signals
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .mapNotNull { symbol -> callSites[symbol.value]?.let { symbol.value to it } }
            .maxByOrNull { it.second.size }
        val sites = best?.second.orEmpty()
        if (sites.size < SHARED_COMPONENT_FANIN_THRESHOLD) return this
        val callSiteSignals = sites.take(SHARED_COMPONENT_CALLSITE_LIMIT).map { site ->
            SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE, "${site.file}:${site.line}")
        }
        return copy(
            signals = signals +
                SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT, sites.size.toString()) +
                callSiteSignals,
        )
    }
```

This keeps the existing `SHARED_COMPONENT` count signal (value = total fan-in) so `flagsReusedComponentDefinitionWithFanInCount` still asserts `"2"`, and adds the capped location signals.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*SourceIndexGeneratorTest*" --tests "*ComposableCallSiteFanInTest*" --no-daemon`
Expected: PASS, including the pre-existing `flagsReusedComponentDefinitionWithFanInCount` and `doesNotFlagSingleUseComponentDefinition`.

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGeneratorTest.kt
git commit -m "feat(source): emit shared-component call-site signals"
```

---

## Task 3: Core — add `SHARED_COMPONENT_CALL_SITE` signal kind

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` (`baseMatchWeight`)
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SourceIndexSerializationTest.kt`:

```kotlin
@Test
fun roundTripsSharedComponentCallSiteSignal() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "ui/PrimaryButton.kt",
                line = 8,
                symbols = listOf("PrimaryButton"),
                signals = listOf(
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "3"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42"),
                ),
            ),
        ),
    )

    val json = Json { ignoreUnknownKeys = true }
    val decoded = json.decodeFromString<SourceIndex>(json.encodeToString(index))

    val callSite = decoded.entries.single().signals.single {
        it.kind == SourceSignalKind.SHARED_COMPONENT_CALL_SITE
    }
    assertEquals("ui/ScreenA.kt:42", callSite.value)
}
```

Match the existing test's import style for `Json`, `encodeToString`, `decodeFromString`, and `assertEquals` (copy the imports already present at the top of the file).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceIndexSerializationTest*" --no-daemon`
Expected: FAIL — `SourceSignalKind.SHARED_COMPONENT_CALL_SITE` is unresolved.

- [ ] **Step 3a: Add the enum member**

In `SourceIndex.kt`, add to `enum class SourceSignalKind` (after `SHARED_COMPONENT`):

```kotlin
    SHARED_COMPONENT,
    SHARED_COMPONENT_CALL_SITE,
}
```

- [ ] **Step 3b: Give it zero match weight**

In `SourceMatcher.kt`, in the `baseMatchWeight` `when`, change the `SHARED_COMPONENT` branch to cover both classification markers:

```kotlin
            SourceSignalKind.SHARED_COMPONENT,
            SourceSignalKind.SHARED_COMPONENT_CALL_SITE,
            -> 0.0
            SourceSignalKind.ARBITRARY_STRING_LITERAL -> 0.35
```

(Removing the standalone `SourceSignalKind.SHARED_COMPONENT -> 0.0` line, now folded into the shared branch.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceIndexSerializationTest*" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt
git commit -m "feat(source): add SHARED_COMPONENT_CALL_SITE signal kind"
```

---

## Task 4: Core — add `SourceLocationRef` and `SourceCandidate.callSites`

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SourceCandidateSerializationTest.kt`:

```kotlin
@Test
fun roundTripsCallSites() {
    val candidate = SourceCandidate(
        file = "ui/PrimaryButton.kt",
        line = 8,
        score = 0.5,
        confidence = SelectionConfidence.MEDIUM,
        callSites = listOf(
            SourceLocationRef(file = "ui/ScreenA.kt", line = 42),
            SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
        ),
    )

    val json = Json { ignoreUnknownKeys = true }
    val decoded = json.decodeFromString<SourceCandidate>(json.encodeToString(candidate))

    assertEquals(candidate.callSites, decoded.callSites)
}

@Test
fun defaultsCallSitesToEmpty() {
    val candidate = SourceCandidate(
        file = "ui/Once.kt",
        score = 0.9,
        confidence = SelectionConfidence.HIGH,
    )
    assertEquals(emptyList<SourceLocationRef>(), candidate.callSites)
}
```

Copy the file's existing imports for `Json`, `encodeToString`, `decodeFromString`, and `assertEquals`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*" --no-daemon`
Expected: FAIL — `SourceLocationRef` unresolved and `callSites` is not a parameter of `SourceCandidate`.

- [ ] **Step 3: Add the type and field**

In `Models.kt`, add a new serializable type near `SourceCandidate`:

```kotlin
@Serializable
data class SourceLocationRef(
    val file: String,
    val line: Int? = null,
)
```

Add the field to `SourceCandidate` (append after `ownerComposable`):

```kotlin
    val ownerComposable: String? = null,
    val callSites: List<SourceLocationRef> = emptyList(),
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt
git commit -m "feat(source): add callSites field to SourceCandidate"
```

---

## Task 5: Core — populate `callSites` in the matcher

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SourceMatcherTest.kt` (uses the existing `node(...)` and `SAMPLE_SOURCE_PREFIX` helpers):

```kotlin
@Test
fun populatesCallSitesForSharedComponentDefinition() {
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
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42"),
                    SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenB.kt:13"),
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

    assertEquals(
        listOf(
            SourceLocationRef(file = "ui/ScreenA.kt", line = 42),
            SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
        ),
        candidate.callSites,
    )
}

@Test
fun leavesCallSitesEmptyForNonSharedComponentMatch() {
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
                ),
                excerpt = "Text(\"Save changes\")",
            ),
        ),
    )

    val candidate = SourceMatcher(index).match(
        selectedNode = node(uid = "save", text = listOf("Save changes")),
        nearbyNodes = emptyList(),
        activityName = null,
    ).first()

    assertEquals(emptyList<SourceLocationRef>(), candidate.callSites)
}
```

Ensure the file imports `io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef` (add if missing).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*" --no-daemon`
Expected: FAIL — `candidate.callSites` is empty (field not populated) for the shared-component case.

- [ ] **Step 3: Populate callSites in `toCandidate`**

In `SourceMatcher.kt`, add a private helper and read it in `toCandidate`. Add the helper to the class:

```kotlin
    private fun MatchScore.sharedComponentCallSites(profile: EvidenceProfile): List<SourceLocationRef> {
        if (!profile.hasSharedComponentDefinition) return emptyList()
        return entry.signals
            .filter { it.kind == SourceSignalKind.SHARED_COMPONENT_CALL_SITE }
            .map { signal ->
                val raw = signal.value
                val sep = raw.lastIndexOf(':')
                if (sep <= 0) {
                    SourceLocationRef(file = raw, line = null)
                } else {
                    val file = raw.substring(0, sep)
                    val line = raw.substring(sep + 1).toIntOrNull()
                    SourceLocationRef(file = file, line = line)
                }
            }
    }
```

In `toCandidate`, after `val profile = EvidenceProfile.fromMatchReasons(...)`, compute the call sites, then add `callSites = ...` to the returned `SourceCandidate(...)`:

```kotlin
        val profile = EvidenceProfile.fromMatchReasons(matchReasons, rawScore)
        val callSites = sharedComponentCallSites(profile)
```

...and in the `return SourceCandidate(` block, append:

```kotlin
            ownerComposable = entry.ownerComposable,
            callSites = callSites,
        )
```

Add the import `import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef` to `SourceMatcher.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*" --no-daemon`
Expected: PASS, including the pre-existing shared-component tests (`emitsSharedComponentDefinitionReasonWhenDefinitionMatchesAndSignalPresent`, `capsSharedComponentDefinitionAtMediumWithFlagAndCaution`).

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source): populate shared-component call sites on candidate"
```

---

## Task 6: Core — carry `callSites` through `SourceHint` round-trip

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappersTest.kt` (create if it does not exist)

- [ ] **Step 1: Write the failing test**

If `SourceCandidateMappersTest.kt` does not exist, create it:

```kotlin
package io.github.beyondwin.fixthis.compose.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceCandidateMappersTest {
    @Test
    fun preservesCallSitesAcrossSourceHintRoundTrip() {
        val candidate = SourceCandidate(
            file = "ui/PrimaryButton.kt",
            line = 8,
            score = 0.5,
            confidence = SelectionConfidence.MEDIUM,
            callSites = listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
            ),
        )

        val roundTripped = candidate.toSourceHint().toSourceCandidate()

        assertEquals(candidate.callSites, roundTripped.callSites)
    }
}
```

If the file already exists, add only the `preservesCallSitesAcrossSourceHintRoundTrip` test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateMappersTest*" --no-daemon`
Expected: FAIL — `SourceHint` has no `callSites` and the round-trip drops it.

- [ ] **Step 3a: Add `callSites` to `SourceHint`**

In `SourceHint.kt`, define a domain location type and add the field:

```kotlin
data class SourceHintLocation(
    val file: String,
    val line: Int? = null,
)
```

Append to `data class SourceHint(...)` after `ownerComposable`:

```kotlin
    val ownerComposable: String? = null,
    val callSites: List<SourceHintLocation> = emptyList(),
)
```

- [ ] **Step 3b: Map both directions**

In `SourceCandidateMappers.kt`, add private converters and wire them into both mapper functions:

```kotlin
private fun SourceLocationRef.toSourceHintLocation(): SourceHintLocation =
    SourceHintLocation(file = file, line = line)

private fun SourceHintLocation.toSourceLocationRef(): SourceLocationRef =
    SourceLocationRef(file = file, line = line)
```

In `SourceHint.toSourceCandidate()`, append:

```kotlin
    ownerComposable = ownerComposable,
    callSites = callSites.map(SourceHintLocation::toSourceLocationRef),
)
```

In `SourceCandidate.toSourceHint()`, append:

```kotlin
    ownerComposable = ownerComposable,
    callSites = callSites.map(SourceLocationRef::toSourceHintLocation),
)
```

Add `import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHintLocation` to `SourceCandidateMappers.kt` (it already imports the `evidence` package types).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateMappersTest*" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappersTest.kt
git commit -m "feat(source): carry call sites through SourceHint mapping"
```

---

## Task 7: Console — render the call-site list

**Files:**
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
- Test: `scripts/annotationDetailActions-test.mjs`

The console reads the annotation JSON directly; the new `sourceCandidates[].callSites` field appears automatically (additive serialization). This task surfaces it in the Evidence section and proves absence is tolerated.

- [ ] **Step 1: Write the failing test**

Add to `scripts/annotationDetailActions-test.mjs`:

```javascript
test('evidence details list shared-component call sites when present', () => {
  const evidenceDetailsHtml = functionBody(detailSource, 'function evidenceDetailsHtml(item)');
  // The body must reference callSites so a shared-component candidate's usages render.
  assert.match(evidenceDetailsHtml, /callSites/);
});

test('source candidate call-site formatting tolerates absent callSites', () => {
  const helper = functionBody(detailSource, 'function sourceCandidateCallSites(candidate)');
  // Guards against undefined/empty before mapping.
  assert.match(helper, /candidate(\?\.|\.)callSites/);
  assert.match(helper, /\|\| \[\]/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/annotationDetailActions-test.mjs`
Expected: FAIL — `sourceCandidateCallSites` not found and `evidenceDetailsHtml` does not reference `callSites`.

- [ ] **Step 3: Add the helper and a row**

In `annotationDetailView.js`, add a helper near `sourceCandidateLine`:

```javascript
            function sourceCandidateCallSites(candidate) {
              const sites = (candidate && candidate.callSites) || [];
              return sites
                .map(site => (site.line == null ? site.file : site.file + ':' + site.line))
                .filter(Boolean);
            }
```

In `evidenceDetailsHtml(item)`, after the `candidates` line and before `const warnings`, add a call-sites row built from the top candidate:

```javascript
              const callSites = sourceCandidateCallSites(sourceCandidates[0]);
              const callSiteRows = callSites.length
                ? [['Shared component used at', callSites.join(', ')]]
                : [];
```

Then include `callSiteRows` in `bodyRows`:

```javascript
              const bodyRows = evidenceRows.concat(reliabilityRows, candidates, callSiteRows, warnings);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/annotationDetailActions-test.mjs`
Expected: PASS.

- [ ] **Step 5: Rebundle and check the console bundle**

Run: `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: bundle rebuilds (`app.js` within size budget) and `--check` reports the bundle is in sync.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/presentation/annotationDetailView.js fixthis-mcp/src/main/resources/console scripts/annotationDetailActions-test.mjs
git commit -m "feat(console): render shared-component call sites in evidence"
```

(Include whatever `build-console-assets.mjs` regenerated under `fixthis-mcp/src/main/resources/console`; run `git status` to confirm the regenerated paths before adding.)

---

## Task 8: Docs — document the call-site context

**Files:**
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Update MCP tool docs**

In `docs/reference/mcp-tools.md`, find the section describing `sourceCandidates` fields (search for `riskFlags` or `caution`). Add a sentence describing the new field:

> `sourceCandidates[].callSites` — for a candidate flagged `SHARED_COMPONENT`, a best-effort list of `{ file, line }` call-site locations where the reused component definition is invoked (capped at 10). This is verification context, not a precise edit target; confidence stays capped at MEDIUM.

- [ ] **Step 2: Update the console contract**

In `docs/reference/feedback-console-contract.md`, find where source-candidate evidence rendering is described and add:

> The Evidence section renders a "Shared component used at" row listing the top candidate's `callSites` when present. Absence of `callSites` is tolerated (single-use definitions and older payloads omit it).

- [ ] **Step 3: Verify bridge-protocol impact (no code change expected)**

Read `docs/reference/bridge-protocol.md`. Confirm that adding the optional `callSites` field to `sourceCandidates[]` is additive and does NOT require bumping `BridgeProtocol.VERSION` (the spec's verification item). If — contrary to expectation — the protocol doc requires a bump for any new candidate field, stop and flag it before proceeding; do not bump silently.

- [ ] **Step 4: Commit**

```bash
git add docs/reference/mcp-tools.md docs/reference/feedback-console-contract.md
git commit -m "docs: document shared-component call-site context"
```

---

## Task 9: Full local verification

**Files:** none (verification only).

- [ ] **Step 1: Run the focused module suites**

Run: `./gradlew :fixthis-gradle-plugin:test :fixthis-compose-core:test --no-daemon`
Expected: PASS.

- [ ] **Step 2: Run the architecture ratchet (core purity)**

Run: `./gradlew :fixthis-compose-core:test --tests "*Architecture*" --no-daemon`
Expected: PASS — `:fixthis-compose-core` gained no new dependency (the call-site data arrives as a serialized signal/field only).

- [ ] **Step 3: Run the console JS suites and bundle check**

Run: `node --test scripts/annotationDetailActions-test.mjs && node scripts/build-console-assets.mjs --check`
Expected: PASS / bundle in sync.

- [ ] **Step 4: Run the full required local checks**

Run the full Gradle matrix, console asset check, console JS syntax check, console JS harnesses, and `git diff --check` exactly as listed in `CONTRIBUTING.md` § Required local checks.
Expected: all green.

- [ ] **Step 5: Confirm no regression in the source-matching fixture lab**

Run the source-matching fixture evaluation per `scripts/source-matching-fixtures.mjs` (see `CONTRIBUTING.md`). Expected: existing fixtures stay green; previously-HIGH single-use targets are unchanged and gain no `callSites`.

---

## Self-Review Notes

**Spec coverage (Track A):**
- "carries the inventory of call-site locations as best-effort context" → Tasks 1, 2 (capture + emit), 5 (populate), 7 (render).
- "confidence stays capped; no precise-target claim" → unchanged caps/caution (Task 5 verifies existing shared-component tests stay green); zero match weight (Task 3).
- "single-use definitions carry no call-site list" → Task 2 `doesNotEmitCallSiteSignalsForSingleUseDefinition`, Task 5 `leavesCallSitesEmptyForNonSharedComponentMatch`.
- "console renders the list when present and ignores absence" → Task 7 (both tests).
- "additive enums/fields only, core purity, bridge-protocol verified" → Tasks 3, 4 additive; Task 8 Step 3 + Task 9 Step 2.
- Cap at `SHARED_COMPONENT_CALLSITE_LIMIT = 10` → Task 2. The spec's "overflow count" is intentionally simplified to a hard cap with no completeness claim (honors the non-goal "never claim a complete inventory beyond the cap"); the count-agnostic caution is unchanged.

**Type consistency:** `CallSiteSource`, `ComposableCallSite`, `SHARED_COMPONENT_CALLSITE_LIMIT` (Gradle); `SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE` ↔ `SourceSignalKind.SHARED_COMPONENT_CALL_SITE` (enum names match for kotlinx serialization); `SourceLocationRef(file, line)` (core model) ↔ `SourceHintLocation(file, line)` (domain) with mappers in Task 6; `SourceCandidate.callSites` / `SourceHint.callSites`; console helper `sourceCandidateCallSites`. Names are consistent across tasks.
