# v1.0 Ship-Prep + Evidence-Depth Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widen the Compose source patterns FixThis recognizes (Track B), give each edit-surface role its own confidence rubric with an explainable basis (Track A), then prepare — but do not tag/publish — the v1.0 release (Track C).

**Architecture:** Source signals are emitted at build time by the Gradle plugin scanner (`fixthis-gradle-plugin`), serialized into a `SourceIndex`, and consumed at runtime by `SourceMatcher` (`fixthis-compose-core`). The MCP module (`fixthis-mcp`) maps matched source candidates onto edit-surface roles. Track B adds scanner detectors + matcher consumption; Track A adds an MCP-side per-role confidence policy (core stays role-agnostic — architecture invariant); Track C runs the release evidence pack and drafts release docs.

**Tech Stack:** Kotlin, Gradle (`./gradlew`), kotlin.test/JUnit, kotlinx.serialization, Node check scripts (`scripts/*.mjs`).

**Waves (sequential):** Wave 1 = Track B (Tasks 1–5) → Wave 2 = Track A (Tasks 6–9) → Wave 3 = Track C (Tasks 10–12). Track A consumes the new Track B reasons; Track C documents both.

**Conventions used throughout:**
- The plugin enum `SourceSignalKindAsset` (`fixthis-gradle-plugin/.../source/SourceIndexAssets.kt`) and the core enum `SourceSignalKind` (`fixthis-compose-core/.../source/SourceIndex.kt`) are mapped by **name equality** during (de)serialization. Any new value MUST be added to BOTH with the identical name.
- New scanner detectors mirror `slotWrapperRendererSignals` / `layoutRendererSignals` in `KotlinSemanticSignalScanner.kt` and are wired into entries inside `KotlinSourceScanner.collectLayoutRendererSignals` (line ~258) using `entriesByLine.entryFor(...).addSignal(kind, value)`.
- Regexes in this plan are concrete starting points. TDD order is enforced: write the failing test first, watch it fail, then implement/iterate the regex until green.
- Run a single test class with: `./gradlew :MODULE:test --tests "*ClassName" --no-daemon`.

---

## Wave 1 — Track B: source-matching pattern breadth

### Task 1: Lazy-list item lambda → item-content owner

A selection inside a `LazyColumn`/`LazyRow`/`LazyVerticalGrid` `items{}` / `itemsIndexed{}` lambda should map to the enclosing item-content composable, surfaced through the existing owner-function path (reason `selected owner composable`). New signal kind `LAZY_ITEM_OWNER`, consumed like `LAMBDA_OWNER_FUNCTION`.

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt:45-60` (add enum value)
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt:45-60` (add mirrored enum value)
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt` (add detector)
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt:258-289` (wire detector)
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:353-372` (consume in `conventionComposableWeightHit` kinds)
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Write the failing scanner test**

Add to `KotlinSourceScannerTest.kt`:

```kotlin
@Test
fun `lazy list item lambda emits item-owner signal for the item composable`() {
    val source = """
        package demo
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        @Composable
        fun OrderList(orders: List<Order>) {
            LazyColumn {
                items(orders) { order ->
                    OrderRow(order)
                }
            }
        }
    """.trimIndent()

    val signals = lazyItemOwnerSignals(source)

    assertEquals(listOf("OrderRow"), signals.map { it.composable })
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: FAIL — `lazyItemOwnerSignals` unresolved.

- [ ] **Step 3: Implement the scanner detector**

In `KotlinSemanticSignalScanner.kt`, add (mirroring `slotWrapperRendererSignals`):

```kotlin
internal data class KotlinLazyItemOwnerSignal(
    val range: IntRange,
    val composable: String,
)

private val lazyItemLambdaRegex =
    Regex("""\b(?:items|itemsIndexed)\s*\([^)]*\)\s*\{[^{}]*?\b([A-Z][A-Za-z0-9_]*)\s*\(""")

internal fun lazyItemOwnerSignals(source: String): List<KotlinLazyItemOwnerSignal> {
    val ignoredRanges = source.layoutRendererIgnoredRanges()
    return lazyItemLambdaRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range }) return@mapNotNull null
            val nameGroup = match.groups[1] ?: return@mapNotNull null
            KotlinLazyItemOwnerSignal(range = nameGroup.range, composable = nameGroup.value)
        }
        .toList()
}
```

- [ ] **Step 4: Run the scanner test until it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS. Iterate the regex if the captured name is wrong.

- [ ] **Step 5: Add the enum values (both modules) and wire the detector**

In `SourceIndexAssets.kt` enum `SourceSignalKindAsset`, add `LAZY_ITEM_OWNER,` after `LAMBDA_OWNER_FUNCTION,`.
In `SourceIndex.kt` enum `SourceSignalKind`, add the identical `LAZY_ITEM_OWNER,` in the same position.
In `KotlinSourceScanner.kt` `collectLayoutRendererSignals`, after the `slotWrapperRendererSignals` block (line ~288), add:

```kotlin
lazyItemOwnerSignals(source).forEach { signal ->
    entriesByLine.entryFor(
        file = file,
        lineNumber = signal.range.startLine(lineStartOffsets),
        lines = lines,
        packageName = packageName,
        className = classNameAt(signal.range.first, classDeclarations),
    ).apply {
        addSignal(SourceSignalKindAsset.LAZY_ITEM_OWNER, signal.composable)
    }
}
```

- [ ] **Step 6: Write the failing matcher test**

The matcher must treat `LAZY_ITEM_OWNER` like an owner-function signal so a selection whose testTag-convention composable is `OrderRow` maps to that entry with reason `selected owner composable`. Add to `SourceMatcherTest.kt`:

```kotlin
@Test
fun `lazy item owner signal maps selection to item composable as owner`() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "OrderList.kt",
                line = 8,
                signals = listOf(SourceSignal(SourceSignalKind.LAZY_ITEM_OWNER, "OrderRow")),
            ),
        ),
    )
    val node = FixThisNode(testTag = "comp:OrderRow:0")
    val candidates = SourceMatcher.match(index, node, emptyList(), activityName = null)

    assertEquals("OrderList.kt", candidates.first().file)
    assertTrue("selected owner composable" in candidates.first().matchReasons)
}
```

(If `FixThisNode`'s constructor needs more args, copy the construction style already used elsewhere in `SourceMatcherTest.kt`.)

- [ ] **Step 7: Run it to confirm it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon`
Expected: FAIL — no candidate / wrong reason.

- [ ] **Step 8: Consume the signal in the matcher**

In `SourceMatcher.conventionComposableWeightHit` (line ~353), add `SourceSignalKind.LAZY_ITEM_OWNER` to the `kinds` set alongside `COMPOSABLE_SYMBOL`, `STRICT_COMP_TEST_TAG`, `LAMBDA_OWNER_FUNCTION`. In `recordMatch` (line ~265), extend the owner-function remap so a `LAZY_ITEM_OWNER` hit on the convention-composable reason also emits `SELECTED_OWNER_FUNCTION`:

```kotlin
hit.signalKind == SourceSignalKind.LAMBDA_OWNER_FUNCTION &&
    reason == SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE ->
    SourceMatchReason.SELECTED_OWNER_FUNCTION
hit.signalKind == SourceSignalKind.LAZY_ITEM_OWNER &&
    reason == SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE ->
    SourceMatchReason.SELECTED_OWNER_FUNCTION
```

Give `LAZY_ITEM_OWNER` a `baseMatchWeight` in `SourceMatcher.baseMatchWeight` (line ~431): add it to the `1.0` group (same as `LAMBDA_OWNER_FUNCTION`).

- [ ] **Step 9: Run both test classes until green**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add fixthis-gradle-plugin/src fixthis-compose-core/src
git commit -m "feat(source): map lazy-list item lambda selections to the item composable"
```

### Task 2: Navigation destination → destination owner

A selection inside a `composable("route") { ... }` block in a `NavHost` should map to the destination composable, via the owner-function path. New signal kind `NAV_DESTINATION_OWNER`, consumed like Task 1.

**Files:** same set as Task 1.

- [ ] **Step 1: Write the failing scanner test**

```kotlin
@Test
fun `nav destination lambda emits destination-owner signal`() {
    val source = """
        package demo
        import androidx.navigation.compose.NavHost
        import androidx.navigation.compose.composable
        @Composable
        fun AppNav(nav: NavHostController) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    """.trimIndent()

    val signals = navDestinationOwnerSignals(source)

    assertEquals(listOf("HomeScreen", "SettingsScreen"), signals.map { it.composable })
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: FAIL — `navDestinationOwnerSignals` unresolved.

- [ ] **Step 3: Implement the detector**

In `KotlinSemanticSignalScanner.kt`:

```kotlin
internal data class KotlinNavDestinationSignal(
    val range: IntRange,
    val composable: String,
)

private val navDestinationRegex =
    Regex("""\bcomposable\s*\([^)]*\)\s*\{[^{}]*?\b([A-Z][A-Za-z0-9_]*)\s*\(""")

internal fun navDestinationOwnerSignals(source: String): List<KotlinNavDestinationSignal> {
    val ignoredRanges = source.layoutRendererIgnoredRanges()
    return navDestinationRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range }) return@mapNotNull null
            val nameGroup = match.groups[1] ?: return@mapNotNull null
            KotlinNavDestinationSignal(range = nameGroup.range, composable = nameGroup.value)
        }
        .toList()
}
```

- [ ] **Step 4: Run the scanner test until it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Add enum values (both modules) and wire**

Add `NAV_DESTINATION_OWNER,` to BOTH `SourceSignalKindAsset` and `SourceSignalKind` (identical name). In `collectLayoutRendererSignals`, after the Task 1 block, add:

```kotlin
navDestinationOwnerSignals(source).forEach { signal ->
    entriesByLine.entryFor(
        file = file,
        lineNumber = signal.range.startLine(lineStartOffsets),
        lines = lines,
        packageName = packageName,
        className = classNameAt(signal.range.first, classDeclarations),
    ).apply {
        addSignal(SourceSignalKindAsset.NAV_DESTINATION_OWNER, signal.composable)
    }
}
```

- [ ] **Step 6: Write the failing matcher test**

```kotlin
@Test
fun `nav destination owner signal maps selection to destination composable`() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "AppNav.kt",
                line = 7,
                signals = listOf(SourceSignal(SourceSignalKind.NAV_DESTINATION_OWNER, "HomeScreen")),
            ),
        ),
    )
    val node = FixThisNode(testTag = "comp:HomeScreen:0")
    val candidates = SourceMatcher.match(index, node, emptyList(), activityName = null)

    assertEquals("AppNav.kt", candidates.first().file)
    assertTrue("selected owner composable" in candidates.first().matchReasons)
}
```

- [ ] **Step 7: Run it to confirm it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 8: Consume in the matcher**

Add `SourceSignalKind.NAV_DESTINATION_OWNER` to the `conventionComposableWeightHit` kinds set. Add the remap clause in `recordMatch` (same shape as Task 1, for `NAV_DESTINATION_OWNER`). Add `NAV_DESTINATION_OWNER` to the `1.0` group in `baseMatchWeight`.

- [ ] **Step 9: Run both classes until green**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add fixthis-gradle-plugin/src fixthis-compose-core/src
git commit -m "feat(source): map navigation destination selections to the destination composable"
```

### Task 3: Additional layout wrappers (Box/Row/Column wrappers + Scaffold slots)

Broaden the existing slot-wrapper detection so custom composables that wrap `Box`/`Row`/`Column` and expose a trailing `content: @Composable () -> Unit` slot, plus `Scaffold` named slots (`topBar`/`bottomBar`), are recognized as layout-renderer context (existing `LAYOUT_RENDERER` signal — no new enum value).

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt:210-213` (`slotWrapperRegex`) and `slotWrapperRendererSignals`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`

- [ ] **Step 1: Write the failing scanner test**

```kotlin
@Test
fun `scaffold slot wrapper composable is recognized as a layout renderer`() {
    val source = """
        package demo
        import androidx.compose.material3.Scaffold
        @Composable
        fun CardSection(content: @Composable (Int) -> Unit) {
            Column { content(0) }
        }
        @Composable
        fun BarHost(topBar: @Composable () -> Unit) {
            Scaffold(topBar = topBar) {}
        }
    """.trimIndent()

    val signals = slotWrapperRendererSignals(source)

    assertEquals(setOf("CardSection", "BarHost"), signals.map { it.composable }.toSet())
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: FAIL — only `CardSection` (or neither) is matched; `BarHost`'s `topBar` slot is missed by the current `content`-only regex.

- [ ] **Step 3: Broaden `slotWrapperRegex`**

Replace `slotWrapperRegex` (line ~210) so it matches a slot parameter named `content`, `topBar`, or `bottomBar`:

```kotlin
private val slotWrapperRegex =
    Regex(
        """@Composable\b[\s\S]{0,400}?\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\b(?:content|topBar|bottomBar)\s*:\s*@Composable\b[^()]*\([^)]*\)\s*->\s*Unit""",
    )
```

- [ ] **Step 4: Run the test until it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS. Confirm the existing slot-wrapper tests in the same class still pass (do not narrow prior behavior).

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src
git commit -m "feat(source): recognize scaffold slot wrappers as layout-renderer context"
```

### Task 4: Modifier-chain evidence signal (clickable / semantics identifiers)

Extract a stable identifier from `.semantics { ... }` / `.testTag(...)`-adjacent modifier chains and emit it as a test-tag-like evidence signal so a selection carrying that identifier scores the entry. New signal kind `MODIFIER_TARGET`, consumed in the test-tag weight-hit set.

**Files:**
- Modify: `SourceIndexAssets.kt` (enum), `SourceIndex.kt` (enum)
- Modify: `KotlinSemanticSignalScanner.kt` (detector), `KotlinSourceScanner.kt` (wire)
- Modify: `SourceMatcher.kt:347-351` (`testTagWeightHit` kinds set)
- Test: `KotlinSourceScannerTest.kt`, `SourceMatcherTest.kt`

- [ ] **Step 1: Write the failing scanner test**

```kotlin
@Test
fun `semantics contentDescription literal emits modifier-target signal`() {
    val source = """
        package demo
        @Composable
        fun SaveButton() {
            Box(Modifier.semantics { contentDescription = "save-cta" }.clickable { }) {}
        }
    """.trimIndent()

    val signals = modifierTargetSignals(source)

    assertEquals(listOf("save-cta"), signals.map { it.value })
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: FAIL — `modifierTargetSignals` unresolved.

- [ ] **Step 3: Implement the detector**

```kotlin
internal data class KotlinModifierTargetSignal(
    val range: IntRange,
    val value: String,
)

private val modifierSemanticsLiteralRegex =
    Regex("""\.semantics\s*\{[^{}]*?\bcontentDescription\s*=\s*"((?:\\.|[^"\\])*)"""")

internal fun modifierTargetSignals(source: String): List<KotlinModifierTargetSignal> {
    val ignoredRanges = source.commentRanges()
    return modifierSemanticsLiteralRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range }) return@mapNotNull null
            val group = match.groups[1] ?: return@mapNotNull null
            KotlinModifierTargetSignal(range = group.range, value = group.value)
        }
        .toList()
}
```

- [ ] **Step 4: Run the scanner test until it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Add enum values (both modules) and wire**

Add `MODIFIER_TARGET,` to BOTH enums (identical name). In `collectLayoutRendererSignals`, after the Task 2 block, add:

```kotlin
modifierTargetSignals(source).forEach { signal ->
    entriesByLine.entryFor(
        file = file,
        lineNumber = signal.range.startLine(lineStartOffsets),
        lines = lines,
        packageName = packageName,
        className = classNameAt(signal.range.first, classDeclarations),
    ).apply {
        addSignal(SourceSignalKindAsset.MODIFIER_TARGET, signal.value)
        contentDescriptions += signal.value
    }
}
```

- [ ] **Step 6: Write the failing matcher test**

```kotlin
@Test
fun `modifier-target signal scores a contentDescription selection`() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "SaveButton.kt",
                line = 5,
                signals = listOf(SourceSignal(SourceSignalKind.MODIFIER_TARGET, "save-cta")),
            ),
        ),
    )
    val node = FixThisNode(contentDescription = listOf("save-cta"))
    val candidates = SourceMatcher.match(index, node, emptyList(), activityName = null)

    assertEquals("SaveButton.kt", candidates.first().file)
}
```

- [ ] **Step 7: Run it to confirm it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon`
Expected: FAIL — no candidate (signal kind not consumed).

- [ ] **Step 8: Consume in the matcher**

In `contentDescriptionWeightHit` (line ~337), add `SourceSignalKind.MODIFIER_TARGET` to the `kinds` set. Add `MODIFIER_TARGET` to the `1.0` group in `baseMatchWeight`.

- [ ] **Step 9: Run both classes until green**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add fixthis-gradle-plugin/src fixthis-compose-core/src
git commit -m "feat(source): use semantics modifier identifiers as content-description evidence"
```

### Task 5: Pinned fixture-lab cases for lazy-list and navigation

Add pinned `source-index` fixture-lab cases asserting the lazy-list and navigation patterns resolve to the expected composable. Follow the existing fixture-lab layout described in `docs/guides/source-matching-fixture-lab.md`.

**Files:**
- Read first: `docs/guides/source-matching-fixture-lab.md` (locate the fixtures dir + runner)
- Create: two fixture cases under the fixtures directory the guide names
- Test: the fixtures test runner (`npm run source-matching:fixtures:test` per the release manifest)

- [ ] **Step 1: Read the fixture-lab guide and locate the fixtures dir + runner**

Run: `sed -n '1,80p' docs/guides/source-matching-fixture-lab.md` and note (a) the fixtures directory, (b) the case file format, (c) the exact `npm` script. Do not invent paths — use what the guide specifies.

- [ ] **Step 2: Write the failing fixture cases**

Create one fixture case for a `LazyColumn { items(...) { OrderRow(...) } }` source whose expected top candidate is the `OrderRow` owner, and one for `composable("home") { HomeScreen() }` expecting the `HomeScreen` destination, using the case schema from Step 1.

- [ ] **Step 3: Run the fixtures runner to confirm it fails**

Run: `npm run source-matching:fixtures:test`
Expected: FAIL — new cases not yet satisfied (or the index needs regeneration per the guide).

- [ ] **Step 4: Regenerate the pinned index / make cases pass**

Follow the guide's regeneration step (the lazy/nav signals now emitted by Tasks 1–2 should make the cases resolve). Iterate until the runner is green.

- [ ] **Step 5: Run the fixtures runner until it passes**

Run: `npm run source-matching:fixtures:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add <fixtures-dir-from-step-1>
git commit -m "test(source): pin lazy-list and navigation fixture-lab cases"
```

---

## Wave 2 — Track A: per-role confidence scoring (MCP)

### Task 6: Add an additive `confidenceBasis` field to `EditSurfaceCandidateDto`

The per-role rubric (Task 7) produces a short "why" string. Surface it on the DTO as an additive, default-null field (JSON compatibility contract — additive only).

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt:117-127`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/` (add `EditSurfaceCandidateDtoSerializationTest.kt`)

- [ ] **Step 1: Write the failing serialization test**

Create `EditSurfaceCandidateDtoSerializationTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditSurfaceCandidateDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `confidenceBasis defaults to null and round-trips`() {
        val dto = EditSurfaceCandidateDto(
            kind = EditSurfaceKindDto.COMPONENT_RENDERER,
            file = "Foo.kt",
            confidence = SelectionConfidence.MEDIUM,
            confidenceBasis = "call site matched: selected owner composable",
        )
        val decoded = json.decodeFromString(
            EditSurfaceCandidateDto.serializer(),
            json.encodeToString(EditSurfaceCandidateDto.serializer(), dto),
        )
        assertEquals("call site matched: selected owner composable", decoded.confidenceBasis)

        val legacy = json.decodeFromString(
            EditSurfaceCandidateDto.serializer(),
            """{"kind":"COMPONENT_RENDERER","file":"Foo.kt","confidence":"MEDIUM"}""",
        )
        assertNull(legacy.confidenceBasis)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateDtoSerializationTest" --no-daemon`
Expected: FAIL — `confidenceBasis` unresolved.

- [ ] **Step 3: Add the field**

In `SessionDtoModels.kt`, add to `EditSurfaceCandidateDto` (after `role`):

```kotlin
    val role: EditSurfaceRoleDto? = null,
    val confidenceBasis: String? = null,
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateDtoSerializationTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src
git commit -m "feat(mcp): add additive confidenceBasis to edit-surface candidate DTO"
```

### Task 7: Introduce `EditSurfaceConfidencePolicy` (per-role rubric)

Replace the flat per-role cap with a rubric: each of the six roles derives a confidence from the matched source candidate's confidence and role-relevant evidence, bounded by the role's existing ceiling, and emits a role-specific basis string.

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`

- [ ] **Step 1: Write the failing policy test**

Create `EditSurfaceConfidencePolicyTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditSurfaceConfidencePolicyTest {
    private fun candidate(
        confidence: SelectionConfidence,
        reasons: List<String> = listOf("selected owner composable"),
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 10,
        score = 0.8,
        matchedTerms = emptyList(),
        matchReasons = reasons,
        confidence = confidence,
    )

    @Test
    fun `interop risk is always low with a boundary basis`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.INTEROP_RISK,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
        assertTrue(result.basis.contains("interop", ignoreCase = true))
    }

    @Test
    fun `call site caps high source confidence at medium and explains the basis`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.basis.contains("selected owner composable"))
    }

    @Test
    fun `call site with low source confidence stays low (not flat medium)`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = candidate(SelectionConfidence.LOW),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }

    @Test
    fun `component definition stays capped at medium with a shared-definition basis`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.basis.contains("call site", ignoreCase = true))
    }

    @Test
    fun `copy or data with a string-resource match is medium`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(SelectionConfidence.MEDIUM, listOf("selected stringResource")),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `null source candidate yields none confidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = null,
        )
        assertEquals(SelectionConfidence.NONE, result.confidence)
    }
}
```

(Confirm the `SourceCandidate` constructor parameter names against `fixthis-compose-core/.../model/SourceCandidate.kt`; fill any required params that lack defaults using neutral values.)

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon`
Expected: FAIL — `EditSurfaceConfidencePolicy` unresolved.

- [ ] **Step 3: Implement the policy**

Create `EditSurfaceConfidencePolicy.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal data class EditSurfaceConfidenceResult(
    val confidence: SelectionConfidence,
    val basis: String,
)

internal object EditSurfaceConfidencePolicy {
    fun score(
        role: EditSurfaceRoleDto,
        sourceCandidate: SourceCandidate?,
    ): EditSurfaceConfidenceResult {
        val source = sourceCandidate?.confidence ?: SelectionConfidence.NONE
        val reasons = sourceCandidate?.matchReasons.orEmpty()
        return when (role) {
            EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceConfidenceResult(
                SelectionConfidence.LOW,
                "interop boundary: verify runtime target before editing",
            )
            EditSurfaceRoleDto.VISUAL_AREA -> EditSurfaceConfidenceResult(
                SelectionConfidence.LOW,
                "visual-area selection: no precise semantics node",
            )
            EditSurfaceRoleDto.COMPONENT_DEFINITION -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "shared component definition: editing it changes every call site",
            )
            EditSurfaceRoleDto.COPY_OR_DATA -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "matched copy/data source${reasonSuffix(reasons)}",
            )
            EditSurfaceRoleDto.LAYOUT_OR_STYLE -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.LOW),
                "layout/style edit applies at the call site",
            )
            EditSurfaceRoleDto.CALL_SITE -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "call site matched${reasonSuffix(reasons)}",
            )
        }
    }

    private val order = listOf(
        SelectionConfidence.NONE,
        SelectionConfidence.LOW,
        SelectionConfidence.MEDIUM,
        SelectionConfidence.HIGH,
    )

    private fun cap(value: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence =
        if (order.indexOf(value) <= order.indexOf(ceiling)) value else ceiling

    private fun reasonSuffix(reasons: List<String>): String {
        val top = reasons.take(2)
        return if (top.isEmpty()) "" else ": ${top.joinToString(", ")}"
    }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon`
Expected: PASS. (If a basis assertion mismatches the wording above, align the test's `contains` substrings to the implemented strings.)

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src
git commit -m "feat(mcp): add per-role edit-surface confidence rubric"
```

### Task 8: Wire the policy into `EditSurfaceCandidateService`

Replace the static `minConfidence(confidence, roleDecision.confidenceCap)` cap (and `emptySourceCandidate`'s static cap) with the rubric output, attaching `confidenceBasis`.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt:45-61,135-161`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/` (existing `EditSurfaceCandidateService` tests — find with grep)

- [ ] **Step 1: Find and read the existing service tests**

Run: `grep -rl "EditSurfaceCandidateService" fixthis-mcp/src/test --include=*.kt`. Read the matched test(s) to learn the `AnnotationDto`/`SnapshotDto` construction style.

- [ ] **Step 2: Write a failing assertion that a CALL_SITE candidate carries a non-null basis**

Add to the existing service test class (using its helpers):

```kotlin
@Test
fun `call site edit surface carries a confidence basis`() {
    val item = annotationWithOwnerSourceCandidate() // reuse an existing helper that yields a CALL_SITE
    val candidates = EditSurfaceCandidateService.build(item, screen = null)
    val callSite = candidates.first { it.role == EditSurfaceRoleDto.CALL_SITE }
    assertNotNull(callSite.confidenceBasis)
    assertTrue(callSite.confidenceBasis!!.contains("call site"))
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon`
Expected: FAIL — `confidenceBasis` is null.

- [ ] **Step 4: Wire the policy**

In `toEditSurface` (line ~135), replace the `confidence = minConfidence(...)` line and add the basis:

```kotlin
private fun SourceCandidate.toEditSurface(
    kind: EditSurfaceKindDto,
    roleDecision: EditSurfaceRoleDecision,
    confidence: SelectionConfidence,
    reasons: List<EditSurfaceReasonDto>,
): EditSurfaceCandidateDto {
    val scored = EditSurfaceConfidencePolicy.score(roleDecision.role, this)
    return EditSurfaceCandidateDto(
        kind = kind,
        role = roleDecision.role,
        file = file,
        repoFile = repoFile,
        line = line,
        confidence = scored.confidence,
        reasons = reasons.distinct(),
        note = roleDecision.note ?: TEXT_SOURCE_NOTE.takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
        confidenceBasis = scored.basis,
    )
}
```

In `emptySourceCandidate` (line ~45), set `confidence = EditSurfaceConfidencePolicy.score(roleDecision.role, null).confidence` and `confidenceBasis = EditSurfaceConfidencePolicy.score(roleDecision.role, null).basis` (compute once into a local `scored`). Delete the now-unused `confidence` parameter from `toEditSurface` and its call sites, and delete `minConfidence` if no other caller remains (grep first).

- [ ] **Step 5: Run the full MCP session test set until green**

Run: `./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --tests "*EditSurfaceConfidencePolicyTest" --no-daemon`
Expected: PASS. Update any existing service assertions whose expected confidence changed because the rubric now derives from source confidence rather than a flat MEDIUM — adjust them to the rubric's honest value (do not weaken the rubric to match a stale assertion).

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src
git commit -m "feat(mcp): drive edit-surface confidence from the per-role rubric"
```

### Task 9: Render `confidenceBasis` in the compact handoff (and console)

Surface the basis string in the compact handoff so agents see why a role got its confidence.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` (edit-surface row)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Read the renderer's edit-surface row**

Run: `grep -n "editSurface\|EditSurface\|confidence\|role" fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt | head -30`. Identify where an edit-surface candidate's confidence is rendered.

- [ ] **Step 2: Write the failing renderer test**

Add to `CompactHandoffRendererTest.kt` (mirroring an existing edit-surface render test):

```kotlin
@Test
fun `compact handoff renders the edit-surface confidence basis`() {
    val output = renderHandoffWithCallSiteEditSurface() // reuse/adapt an existing fixture builder
    assertTrue(output.contains("call site matched"))
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon`
Expected: FAIL — basis not present in output.

- [ ] **Step 4: Render the basis**

In the edit-surface row, append the basis when present, e.g. after the confidence token:

```kotlin
candidate.confidenceBasis?.let { basis -> append(" — ").append(basis) }
```

Match the surrounding renderer's exact string-builder style (indentation, separators).

- [ ] **Step 5: Run renderer tests until green**

Run: `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon`
Expected: PASS.

- [ ] **Step 6: Console visual verification (project convention)**

If the console renders edit-surface confidence, surface the basis there too, then run the project's visual verification per `[[feedback_visual_verification]]` (Playwright screenshot + geometry assert; `console:smoke` is known-broken — use the browser-reliability harness). If the console does not render this row, record "no console change" and skip.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src
git commit -m "feat(mcp): show the per-role confidence basis in the compact handoff"
```

---

## Wave 3 — Track C: v1.0 release preparation (no tag/publish)

### Task 10: Add v1.0 release-claim rows for the Track A/B work

**Files:**
- Modify: `docs/contributing/release-readiness.md:107-118` (v1.0 Release Claim Manifest table)

- [ ] **Step 1: Add claim rows**

Append to the v1.0 Release Claim Manifest table:

```markdown
| Lazy-list item lambdas and navigation destinations map a selection to the item/destination composable. | `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Each edit-surface role reports a role-specific confidence and an explainable basis. | `./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --tests "*EditSurfaceCandidateServiceTest" --tests "*CompactHandoffRendererTest" --no-daemon`. |
```

- [ ] **Step 2: Verify the doc-consistency check passes**

Run: `node scripts/check-doc-consistency.mjs && node scripts/check-release-readiness.mjs`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/contributing/release-readiness.md
git commit -m "docs(release): add v1.0 claims for source patterns and per-role confidence"
```

### Task 11: Draft the v1.0 changelog and release notes

**Files:**
- Modify: `CHANGELOG.md`
- Create: `docs/releases/v1.0.0.md`

- [ ] **Step 1: Read the prior release-note format**

Read `docs/releases/v0.8.0.md` and the top of `CHANGELOG.md` to mirror their structure and version-heading conventions.

- [ ] **Step 2: Write the changelog entry**

Add a `CHANGELOG.md` entry covering: lazy-list item mapping, navigation-destination mapping, broadened layout-wrapper recognition, modifier-chain evidence, and per-role edit-surface confidence with an explainable basis. Use the same bullet style as existing entries.

- [ ] **Step 3: Write `docs/releases/v1.0.0.md`**

Mirror `docs/releases/v0.8.0.md`: summary, highlights (the five items above), and a note that this release remains debug-only, local/ADB-only, Compose-only (unchanged V1 scope). State explicitly that PyPI/Docker are not added.

- [ ] **Step 4: Verify version-contract and doc checks**

Run: `npm run release:version:check && node scripts/check-doc-consistency.mjs`
Expected: PASS. (If `release:version:check` requires the version constant bumped to `1.0.0`, follow the script's reported file — do not guess.)

- [ ] **Step 5: Commit**

```bash
git add CHANGELOG.md docs/releases/v1.0.0.md
git commit -m "docs(release): draft v1.0.0 changelog and release notes"
```

### Task 12: Run the full v1.0 evidence pack and record it (stop before tag/publish)

**Files:**
- No source changes. Produces evidence only.

- [ ] **Step 1: Run the full Gradle matrix + console checks**

Run the required local checks from `CONTRIBUTING.md` (`gradle matrix`, console asset check, console JS syntax check, console JS harnesses, `git diff --check`). Capture pass/fail.

- [ ] **Step 2: Run the v1.0 manifest evidence commands**

Run each command listed in the v1.0 Release Claim Manifest (including the two rows added in Task 10) and the "Required Before Next Source Release" checklist (`docs/contributing/release-readiness.md:159-166`):

```bash
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
git diff --check
```

- [ ] **Step 3: Record evidence and any deferrals**

Summarize results for the release issue. If an Android SDK / unlocked emulator is unavailable, record `npm run source-matching:fixtures:runtime -- --strict` as **deferred** (per the manifest) rather than implying it passed.

- [ ] **Step 4: STOP — hand off tagging to a human**

Do NOT run `git tag`, `git push --tags`, or any `npm`/Homebrew/Maven publish. State in the final summary that v1.0 is evidence-green and ready for a human to cut the tag and publish.

---

## Self-Review

**Spec coverage:**
- Track A (per-role confidence) → Tasks 6–9 (DTO field, rubric, service wiring, render). ✓
- Track B patterns: lazy-list (Task 1), navigation (Task 2), layout wrappers (Task 3), modifier-chain (Task 4), fixtures (Task 5). ✓ All four spec patterns covered.
- Track C (release prep, no tag/publish): claims (Task 10), changelog/notes (Task 11), evidence + stop (Task 12). ✓
- Architecture invariant (core role-agnostic): Track A lives entirely in `fixthis-mcp`. ✓
- Additive JSON only: `confidenceBasis` defaults null with a legacy-decode test. ✓
- Caps preserved: rubric ceilings match the prior `confidenceCap`s (INTEROP/VISUAL/LAYOUT low; COMPONENT_DEFINITION/COPY/CALL_SITE medium). ✓
- No tag/publish: Task 12 Step 4 hard-stops. ✓

**Placeholder scan:** Regexes/strings are concrete; tests carry real code. Fixture paths in Task 5 are resolved by reading the guide first (Step 1) because the exact dir is owned by `docs/guides/source-matching-fixture-lab.md`, not guessable safely — the task makes that read the first step. ✓

**Type consistency:** New enum value `LAZY_ITEM_OWNER`, `NAV_DESTINATION_OWNER`, `MODIFIER_TARGET` added to BOTH `SourceSignalKindAsset` and `SourceSignalKind`. `EditSurfaceConfidencePolicy.score(role, sourceCandidate)` signature is used identically in Task 7 (def), Task 8 (service), and the tests. `EditSurfaceConfidenceResult(confidence, basis)` fields consistent. `confidenceBasis` field name consistent across Tasks 6, 8, 9. ✓
