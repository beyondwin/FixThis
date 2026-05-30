# v0.8 Finalization (Track B + Track C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the two remaining v0.8 tracks — recognize two additional, fixture-gated Compose source-matching conventions (Track B) and remove the now-dead session-polling timer that runs under a healthy SSE session while preserving every explicit recovery path (Track C).

**Architecture:** Track B is additive and reuses existing reasons/weights: it widens the strict-test-tag recognizer (two regex sites kept in sync) to an enumerated alternation, and adds slot-wrapper composable detection that emits the existing `LAYOUT_RENDERER` signal. Track C is evidence-gated: a reliability-harness assertion first proves session polling never fetches under healthy SSE, then `startSessionsPolling()` is narrowed to the fallback-only path (mirroring the already-gated `startLivePreviewPolling()`), and the fallback-only modules are marked.

**Tech Stack:** Kotlin (`:fixthis-compose-core`, `:fixthis-gradle-plugin`, JUnit4), Node-driven source-matching fixture lab (`scripts/source-matching-fixtures.mjs`), browser reliability harness (`scripts/console-browser-reliability.mjs`, Playwright), vanilla console JS modules.

**Spec:** [`docs/superpowers/specs/2026-05-30-v08-trackb-trackc-finalization-design.md`](../specs/2026-05-30-v08-trackb-trackc-finalization-design.md)

---

## File Structure

**Track B — test-tag convention vocabulary (B1):**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt` — core/runtime parser regex → enumerated set.
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt:12` — `strictCompTestTagRegex` → enumerated set.
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`

**Track B — slot-wrapper recognition (B2):**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt` — add `slotWrapperRendererSignals(source)`.
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt:258-278` — call the new detector inside `collectLayoutRendererSignals`.
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` (verification only — no core change expected).

**Track B — fixture lab + docs:**
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `CHANGELOG.md`

**Track C — evidence + removal:**
- Modify: `scripts/console-browser-reliability.mjs` — add healthy-SSE no-poll assertion.
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js` — narrow `startSessionsPolling()` to fallback-only.
- Modify: `fixthis-mcp/src/main/console/sse.js` — header/comment marking fallback-only surface (if needed).
- Modify: `CHANGELOG.md`

---

## Track B1: Test-Tag Convention Vocabulary

The enumerated set is exactly three formats: `comp:<Name>:<id>` (existing), `screen:<Name>:<id>` (colon), `comp.<Name>.<id>` (dot). `<Name>` is `[A-Za-z][A-Za-z0-9]*`; `<id>` is `[A-Za-z0-9_-]+`. No other prefix/delimiter is accepted.

### Task 1: Generalize the core convention parser

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt`

- [ ] **Step 1: Add failing tests for the new enumerated formats**

Append these tests to `TestTagConventionTest.kt` (inside the class):

```kotlin
    @Test
    fun parsesScreenColonConvention() {
        val parsed = TestTagConvention.parse("screen:CartScreen:checkout")

        assertEquals("CartScreen", parsed?.composableName)
        assertEquals("checkout", parsed?.variant)
    }

    @Test
    fun parsesCompDotConvention() {
        val parsed = TestTagConvention.parse("comp.PrimaryButton.submit")

        assertEquals("PrimaryButton", parsed?.composableName)
        assertEquals("submit", parsed?.variant)
    }

    @Test
    fun rejectsTagsOutsideEnumeratedSet() {
        assertNull(TestTagConvention.parse("widget:Foo:bar"))
        assertNull(TestTagConvention.parse("screen.Foo.bar"))
        assertNull(TestTagConvention.parse("comp-Foo-bar"))
        assertNull(TestTagConvention.parse("screen:Foo"))
        assertNull(TestTagConvention.parse("comp.Foo."))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionTest*"`
Expected: FAIL — `parsesScreenColonConvention` and `parsesCompDotConvention` return null.

- [ ] **Step 3: Generalize the parser to the enumerated set**

Replace the body of `TestTagConvention.kt` with:

```kotlin
package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConvention {
    // Enumerated convention set: comp:<Name>:<id>, screen:<Name>:<id>, comp.<Name>.<id>.
    // Name = [A-Za-z][A-Za-z0-9]*, id = [A-Za-z0-9_-]+. Closed set — no other
    // prefix/delimiter is accepted. Kept in sync with the gradle-side
    // strictCompTestTagRegex.
    private val patterns = listOf(
        Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
        Regex("^screen:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
        Regex("^comp\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
    )

    data class Parsed(
        val composableName: String,
        val variant: String,
    )

    fun parse(testTag: String?): Parsed? {
        if (testTag == null) return null
        for (pattern in patterns) {
            val match = pattern.matchEntire(testTag) ?: continue
            return Parsed(composableName = match.groupValues[1], variant = match.groupValues[2])
        }
        return null
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionTest*"`
Expected: PASS — all tests including the pre-existing `parsesStrictCompTag`, `rejectsPartialOrUnanchoredTags`, `acceptsVariantHyphenAndUnderscore`.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt
git commit -m "feat(core): recognize screen: and comp. testTag conventions"
```

### Task 2: Generalize the gradle strict-tag recognizer

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt:12`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `KotlinSourceScannerTest.kt` (inside the class):

```kotlin
    @Test
    fun `emits STRICT_COMP_TEST_TAG for screen and dot conventions`() {
        val file = tempDir.newFile("ConventionTags.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.runtime.Composable

                @Composable
                fun CartScreen() {
                    Box(modifier = Modifier.testTag("screen:CartScreen:checkout"))
                    Box(modifier = Modifier.testTag("comp.PrimaryButton.submit"))
                    Box(modifier = Modifier.testTag("widget:NotAConvention:x"))
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
        val strictValues = entries.flatMap { it.signals }
            .filter { it.kind == SourceSignalKindAsset.STRICT_COMP_TEST_TAG }
            .map { it.value }

        assertTrue(strictValues.contains("screen:CartScreen:checkout"))
        assertTrue(strictValues.contains("comp.PrimaryButton.submit"))
        assertTrue(strictValues.none { it == "widget:NotAConvention:x" })
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest*"`
Expected: FAIL — `screen:`/`comp.` tags do not emit `STRICT_COMP_TEST_TAG`.

- [ ] **Step 3: Generalize `strictCompTestTagRegex`**

In `KotlinSemanticSignalScanner.kt`, replace line 12:

```kotlin
private val strictCompTestTagRegex = Regex("""comp:[A-Za-z_][A-Za-z0-9_]*:.+""")
```

with the enumerated alternation (kept in sync with `TestTagConvention.kt`):

```kotlin
private val strictCompTestTagRegex =
    Regex("""(?:comp:[A-Za-z_][A-Za-z0-9_]*:.+|screen:[A-Za-z_][A-Za-z0-9_]*:.+|comp\.[A-Za-z_][A-Za-z0-9_]*\..+)""")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt
git commit -m "feat(source): index screen: and comp. strict testTag conventions"
```

### Task 3: Fixture-lab case for the new conventions

**Files:**
- Modify: `fixtures/source-matching/manifest.json`

> **Note:** Inspect the current manifest before editing — match its existing
> `source-index` case shape (`id`, `mode`, `expectedEntryPathContains`,
> `expectedSignal`). Pick a pinned fixture repo/commit already present in the
> manifest that contains a composable using a `comp:`-style tag, and add a
> sibling case asserting a `STRICT_COMP_TEST_TAG` signal value. If no existing
> fixture commit carries a `screen:`/`comp.` tag, add the case against the
> local `fixthis-sample` module instead (the manifest supports local module
> entries — follow the existing local-module case shape).

- [ ] **Step 1: Add a failing fixture case**

Add a new case to the appropriate fixture's `cases` array asserting the new convention indexes as a strict tag, e.g.:

```json
{
  "id": "<fixture-id>-screen-convention-strict-tag",
  "mode": "source-index",
  "expectedEntryPathContains": "<path/to/Composable/using/screen:Tag>.kt",
  "expectedSignal": { "kind": "STRICT_COMP_TEST_TAG", "value": "screen:<Name>:<variant>" }
}
```

- [ ] **Step 2: Run the fixture lab to verify it fails (if the tag is not yet present in the fixture source)**

Run: `node scripts/source-matching-fixtures.mjs --case "<fixture-id>-screen-convention-strict-tag"`
Expected: FAIL until either the fixture source carries the tag or the local sample is tagged.

> If the chosen fixture source has no such tag, use the local `fixthis-sample`
> module: add a `screen:<Name>:<variant>` testTag to an existing sample
> composable, then point the case at that file. This keeps the case
> deterministic and self-contained.

- [ ] **Step 3: Make the case pass**

Ensure the referenced source carries the convention tag (add it to `fixthis-sample` if using the local path), then re-run.

- [ ] **Step 4: Run the full source-matching fixture suite**

Run: `node scripts/source-matching-fixtures.mjs`
Expected: PASS — the new case green, all pre-existing cases still green (no regression).

- [ ] **Step 5: Commit**

```bash
git add fixtures/source-matching/manifest.json fixthis-sample
git commit -m "test(fixtures): cover screen: convention strict-tag indexing"
```

---

## Track B2: Slot-Wrapper Composable Recognition

Recognize a composable that declares a content slot of the canonical shape `content: @Composable (...) -> Unit` and emit the existing `LAYOUT_RENDERER` signal on that wrapper's owner entry, so a node selected inside the wrapper resolves to it as a medium edit-surface hint. No new signal kind, reason, or weight.

### Task 4: Detect slot-wrapper declarations in the scanner

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt:258-278`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `KotlinSourceScannerTest.kt`. It asserts the slot-wrapper entry carries `LAYOUT_RENDERER` co-located with its owner function, mirroring the existing Layout test (lines 261-269):

```kotlin
    @Test
    fun `emits LAYOUT_RENDERER for content-slot wrapper composable`() {
        val file = tempDir.newFile("CardSlot.kt").apply {
            writeText(
                """
                package com.example
                import androidx.compose.runtime.Composable

                @Composable
                fun CardSlot(content: @Composable () -> Unit) {
                    content()
                }

                @Composable
                fun PlainCard() {
                    val title = "Hello"
                }
                """.trimIndent(),
            )
        }

        val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
        val wrapperEntry = entries.single { entry ->
            entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "CardSlot" }
        }

        assertTrue(
            wrapperEntry.signals.any { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "CardSlot" },
        )
        assertTrue(
            entries.none { entry ->
                entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "PlainCard" }
            },
        )
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest*"`
Expected: FAIL — no `LAYOUT_RENDERER` signal valued `CardSlot` exists yet.

- [ ] **Step 3: Add the slot-wrapper detector**

In `KotlinSemanticSignalScanner.kt`, add a regex and a detector that returns the owner composable name plus the declaration range. Add near the other layout-renderer declarations (around line 189):

```kotlin
private val slotWrapperRegex =
    Regex("""@Composable\b[\s\S]{0,400}?\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\bcontent\s*:\s*@Composable\b[^)]*->\s*Unit""")

internal data class KotlinSlotWrapperSignal(
    val range: IntRange,
    val composable: String,
)

internal fun slotWrapperRendererSignals(source: String): List<KotlinSlotWrapperSignal> {
    val ignoredRanges = source.layoutRendererIgnoredRangesForSlots()
    return slotWrapperRegex.findAll(source)
        .mapNotNull { match ->
            if (ignoredRanges.any { range -> match.range.first in range }) return@mapNotNull null
            // Anchor the signal on the `fun <Name>(` token so it co-locates with
            // the owner-function entry (where LAMBDA_OWNER_FUNCTION lands).
            val nameGroup = match.groups[1] ?: return@mapNotNull null
            KotlinSlotWrapperSignal(range = nameGroup.range, composable = nameGroup.value)
        }
        .toList()
}

private fun String.layoutRendererIgnoredRangesForSlots(): List<IntRange> =
    kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList() + commentRanges()
```

In `KotlinSourceScanner.kt`, extend `collectLayoutRendererSignals` (after the existing `layoutRendererSignals(source).forEach { ... }` block, before the method closes at line 278) to also emit slot-wrapper signals, gated by the same `includeLayoutRendererSignals` flag the method already respects:

```kotlin
        slotWrapperRendererSignals(source).forEach { signal ->
            entriesByLine.entryFor(
                file = file,
                lineNumber = signal.range.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(signal.range.first, classDeclarations),
            ).apply {
                addSignal(SourceSignalKindAsset.LAYOUT_RENDERER, signal.composable)
            }
        }
```

> **Co-location requirement:** the test asserts the `LAYOUT_RENDERER` entry also
> carries `LAMBDA_OWNER_FUNCTION = <wrapper>`. Anchoring the signal on the
> `fun <Name>(` token (the declaration line) places it on the same entry the
> owner tracker labels with the wrapper name. If the owner signal is attached on
> a different line in this codebase, adjust the anchor line so the two signals
> share an entry (the failing test will tell you).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest*"`
Expected: PASS, and the pre-existing `indexes Layout and SubcomposeLayout renderer calls with owner function` test still green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt
git commit -m "feat(source): emit LAYOUT_RENDERER for content-slot wrapper composables"
```

### Task 5: Verify the matcher surfaces slot wrappers (no core change)

**Files:**
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

This task confirms the existing core `LAYOUT_RENDERER_CONTEXT` path (SourceMatcher.kt:192-197) already handles slot wrappers, since they reuse the `LAYOUT_RENDERER` signal. It is a guard test, not new production code.

- [ ] **Step 1: Write the verification test**

Append to `SourceMatcherTest.kt`, mirroring `strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence` (line 982) but with a slot-wrapper-style entry:

```kotlin
    @Test
    fun slotWrapperLayoutRendererSurfacesAsMediumConfidence() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/CardSlot.kt",
                        line = 12,
                        signals = listOf(
                            SourceSignal(SourceSignalKind.LAYOUT_RENDERER, "CardSlot"),
                            SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "CardSlot"),
                        ),
                        excerpt = "fun CardSlot(content: @Composable () -> Unit) {",
                    ),
                ),
            ),
        )

        val match = matcher.match(
            selectedNode = node(uid = "body", testTag = "comp:CardSlot:body"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).single()

        assertEquals("sample/src/main/java/CardSlot.kt", match.file)
        assertEquals(SelectionConfidence.MEDIUM, match.confidence)
        assertTrue(match.matchReasons.contains("layout renderer context"))
        assertEquals("CardSlot", match.ownerComposable)
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*"`
Expected: PASS with no production change. If it fails, the slot wrapper needs the same treatment as Layout — but per SourceMatcher.kt:192-197 the `LAYOUT_RENDERER` signal kind is already sufficient, so no core edit should be required.

- [ ] **Step 3: Commit**

```bash
git add fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "test(core): guard slot-wrapper layout-renderer surfacing"
```

### Task 6: Fixture-lab case for slot-wrapper recognition

**Files:**
- Modify: `fixtures/source-matching/manifest.json`
- Possibly modify: `fixthis-sample` (add a slot-wrapper composable if no pinned fixture has one)

- [ ] **Step 1: Add a failing fixture case**

Add a `source-index` case asserting the slot wrapper emits `LAYOUT_RENDERER`:

```json
{
  "id": "<fixture-id>-slot-wrapper-layout-renderer",
  "mode": "source-index",
  "expectedEntryPathContains": "<path/to/SlotWrapper>.kt",
  "expectedSignal": { "kind": "LAYOUT_RENDERER", "value": "<WrapperName>" }
}
```

> If no pinned fixture repo contains a `content: @Composable () -> Unit` slot
> wrapper, add one to `fixthis-sample` (a small wrapper composable) and point
> the case at it — same approach as Task 3.

- [ ] **Step 2: Run the case to verify it fails (if source not yet present)**

Run: `node scripts/source-matching-fixtures.mjs --case "<fixture-id>-slot-wrapper-layout-renderer"`
Expected: FAIL until the source carries a slot wrapper.

- [ ] **Step 3: Make the case pass**

Ensure the referenced source has a slot wrapper, then re-run the single case.

- [ ] **Step 4: Run the full fixture suite**

Run: `node scripts/source-matching-fixtures.mjs`
Expected: PASS — new case green, no regression on existing cases.

- [ ] **Step 5: Commit**

```bash
git add fixtures/source-matching/manifest.json fixthis-sample
git commit -m "test(fixtures): cover slot-wrapper layout-renderer indexing"
```

### Task 7: Track B docs + bridge-protocol verification

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Verify no wire/protocol change**

Run: `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest*"`
Expected: PASS — Track B adds no new signal kind, reason, or persisted field, so `BridgeProtocol.VERSION` is unchanged. Confirm no edits were made to `BridgeProtocol.VERSION`, `MinimumSupportedProtocolVersion`, `BridgeClient.kt`, or `ServerVersionRoutes.kt`.

- [ ] **Step 2: Add a changelog entry under `## Unreleased` → `### Added`**

```markdown
- Source matching now recognizes two additional opt-in conventions. Test tags in
  the `screen:<Name>:<id>` and dot-delimited `comp.<Name>.<id>` forms are treated
  the same as the existing `comp:<Name>:<id>` strict convention, resolving the
  named composable owner at the same confidence. A composable that exposes a
  `content: @Composable (...) -> Unit` slot is recognized as a layout wrapper, so
  a node selected inside it surfaces the wrapper as a medium-confidence edit
  surface. Both additions reuse existing evidence weights — neither makes a
  source candidate high confidence on its own.
```

- [ ] **Step 3: Run spotless and commit**

```bash
./gradlew spotlessApply
git add CHANGELOG.md
git commit -m "docs: changelog for Track B convention + slot-wrapper recognition"
```

---

## Track C: SSE Console Sync Finalization

Under a healthy SSE session, `shouldUseSessionFallbackPolling()` (sse.js:25) returns false, so the 2-second `startSessionsPolling()` interval ticks but every tick no-ops in `shouldPollSessions()` (sessions-polling.js:21-26). The preview poll is already gated (`startLivePreviewPolling()` early-returns at preview.js:79). Track C proves the session timer is dead under health, then stops creating it — mirroring the preview gate — and marks the fallback-only surface.

### Task 8: Prove session polling never fetches under healthy SSE

**Files:**
- Modify: `scripts/console-browser-reliability.mjs`

- [ ] **Step 1: Read the harness to find the happy-path scenario and the fetch-instrumentation pattern**

Run: `node scripts/console-browser-reliability.mjs`
Expected: the current reliability suite passes. Note how scenarios open a console page, drive SSE events via the fake bridge, and assert state. Identify where to add a counter for session-poll HTTP requests (the `api.sessions` fetch hits a `/api/sessions` style path).

- [ ] **Step 2: Add a failing assertion scenario**

Add a scenario that, with a healthy SSE session for ~3 seconds, asserts the console issued zero session-poll fetches. Use Playwright request interception to count requests to the sessions endpoint:

```javascript
async function assertNoSessionPollingUnderHealthySse({ fixture, context }) {
  const page = await openConsolePage(context, fixture.url);
  let sessionPollCount = 0;
  page.on('request', (request) => {
    const url = request.url();
    // Session fallback polling hits the sessions list endpoint via api.sessions.
    if (/\/api\/sessions(\?|$)/.test(url) && request.method() === 'GET') {
      sessionPollCount += 1;
    }
  });

  // Establish a healthy SSE session and let any (dead) polling interval run.
  await page.waitForFunction(
    () => window.FixThisConsoleDebug?.isConsoleEventsConnected?.() === true,
    null,
    { timeout: 8000 },
  );
  await new Promise((resolve) => setTimeout(resolve, 3000));

  assert.equal(
    sessionPollCount,
    0,
    `expected zero session-poll fetches under healthy SSE, saw ${sessionPollCount}`,
  );
  await page.close();
}
```

Wire it into the harness's scenario runner alongside the existing scenarios, and expose `isConsoleEventsConnected` on `window.FixThisConsoleDebug` if it is not already exposed (check `sse.js` / the debug bridge; if missing, add it to the debug surface in the module that builds `FixThisConsoleDebug`).

- [ ] **Step 3: Run the harness to confirm the assertion holds on current code**

Run: `node scripts/console-browser-reliability.mjs`
Expected: PASS. This proves the session-poll fetch never fires under healthy SSE on the *current* code — the evidence gate that justifies removal. (If it FAILS — i.e. polling does fetch — stop and report; the removal premise is wrong.)

- [ ] **Step 4: Commit**

```bash
git add scripts/console-browser-reliability.mjs fixthis-mcp/src/main/console/sse.js
git commit -m "test(console): assert zero session polling under healthy SSE"
```

### Task 9: Narrow session polling to the fallback-only path

**Files:**
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js:34-42`

- [ ] **Step 1: Add a harness assertion that no session timer is armed under healthy SSE**

Extend the Task 8 scenario (or add a sibling) to assert the interval is not even created. Expose the timer-armed state for the assertion — add a debug accessor in `sessions-polling.js` and surface it via `FixThisConsoleDebug`:

```javascript
function isSessionsPollingArmed() {
  return sessionsPollingTimer !== null;
}
```

Then in the harness, after the healthy-SSE wait:

```javascript
const armed = await page.evaluate(() => window.FixThisConsoleDebug?.isSessionsPollingArmed?.() === true);
assert.equal(armed, false, 'expected no session-polling timer under healthy SSE');
```

- [ ] **Step 2: Run the harness to verify the new assertion fails**

Run: `node scripts/console-browser-reliability.mjs`
Expected: FAIL — `startSessionsPolling()` currently always creates the timer, so `armed` is `true`.

- [ ] **Step 3: Narrow `startSessionsPolling()` to fallback-only**

In `sessions-polling.js`, make `startSessionsPolling()` early-return when SSE is healthy, mirroring `startLivePreviewPolling()` (preview.js:77-85):

```javascript
            function startSessionsPolling() {
              stopSessionsPolling();
              // Fallback-only: do not arm the polling timer while SSE is healthy.
              // The SSE-drop path re-invokes startSessionsPolling() to resume.
              if (!shouldUseSessionFallbackPolling()) return;
              pollingUseCases.startSessionsPolling();
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(() => {
                  // pollSessionsTick already handles its own failures; this catch is defensive.
                });
              }, SessionsPollIntervalMs);
            }
```

- [ ] **Step 4: Ensure the SSE-drop path re-arms polling**

Confirm that the SSE disconnect handler calls `startSessionsPolling()` after `setConsoleEventsConnected(false)`. Check `events.js` (around the `startSessionsPolling()` call at line 94) and the connection-state transitions. If the existing disconnect handler already calls `startSessionsPolling()` after marking SSE disconnected, no change is needed — the early-return now passes because `shouldUseSessionFallbackPolling()` is true. If it calls `startSessionsPolling()` *before* marking disconnected, reorder so the connected flag is cleared first.

- [ ] **Step 5: Run the harness — healthy-SSE assertions and the reconnect/fallback scenarios must all pass**

Run: `node scripts/console-browser-reliability.mjs`
Expected: PASS — zero session polling and no armed timer under healthy SSE, AND the existing reconnect/fallback scenarios (where SSE drops) still poll and recover.

- [ ] **Step 6: Run the console reliability unit suite**

Run: `npm run console:reliability:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/sessions-polling.js scripts/console-browser-reliability.mjs
git commit -m "fix(console): arm session polling only on SSE fallback path"
```

### Task 10: Mark the fallback-only module surface

**Files:**
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js` (header comment)
- Modify: `fixthis-mcp/src/main/console/previewPoll.js` (header comment)
- Modify: `fixthis-mcp/src/main/console/sse.js` (header comment, if needed)

This settles the v0.7 deferred decision: now that polling is provably fallback-only, mark the surface. Do **not** rename the grep'd identifiers (`pollSessionsTick` / `startSessionsPolling` / `withMutationLock`) — the asset-contract tests depend on them; only clarify the module docs.

- [ ] **Step 1: Update the `sessions-polling.js` header comment**

Replace the top comment so it states the fallback-only contract:

```javascript
// @requires state.js, api.js
            // sessions-polling.js — FALLBACK-ONLY session polling. The timer is
            // armed only while SSE is unavailable (shouldUseSessionFallbackPolling()).
            // Under a healthy EventSource session no timer is created and no
            // /api/sessions poll fetch occurs (asserted by
            // scripts/console-browser-reliability.mjs). Explicit recovery
            // (reconnect, manual refresh) re-invokes startSessionsPolling().
```

- [ ] **Step 2: Confirm the asset-contract grep identifiers still resolve**

Run: `node scripts/build-console-assets.mjs --check`
Expected: PASS — `pollSessionsTick`, `startSessionsPolling`, `withMutationLock` still present; bundle within size budget.

- [ ] **Step 3: Run the console asset + JS harness checks**

Run: `npm run console:reliability:test && node scripts/build-console-assets.mjs --check`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/console/sessions-polling.js fixthis-mcp/src/main/console/previewPoll.js fixthis-mcp/src/main/console/sse.js
git commit -m "docs(console): mark session/preview polling as fallback-only"
```

### Task 11: Track C changelog

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add a changelog entry under `## Unreleased` → `### Changed`**

```markdown
- The feedback console no longer arms its 2-second session-polling timer while
  the SSE event stream is healthy; polling is now created only on the SSE
  fallback path. A healthy EventSource session issues zero `/api/sessions` poll
  fetches, asserted by the browser reliability harness. Explicit recovery
  (reconnect, manual refresh) still resumes polling when SSE is unavailable.
```

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for fallback-only session polling"
```

---

## Final Verification (before any PR)

- [ ] **Run the full local required checks** per `CONTRIBUTING.md`:

```bash
./gradlew build
node scripts/source-matching-fixtures.mjs
npm run console:reliability:test
node scripts/console-browser-reliability.mjs
node scripts/build-console-assets.mjs --check
git diff --check
```

Expected: all green. Track B adds no wire change (bridge-protocol sync test green); Track C keeps every explicit recovery path and the asset-contract identifiers.

---

## Self-Review Notes

- **Spec coverage:** B1 (Tasks 1-3), B2 (Tasks 4-6), Track B docs/protocol (Task 7), Track C evidence (Task 8), removal (Task 9), rename/mark (Task 10), Track C docs (Task 11). All spec acceptance criteria mapped.
- **Confidence inflation guard:** B1 reuses `STRICT_COMP_TEST_TAG` + `SELECTED_TEST_TAG_CONVENTION_COMPOSABLE` (no new score); B2 reuses `LAYOUT_RENDERER` + `LAYOUT_RENDERER_CONTEXT` (weight 0.75, medium-capped). Verified against SourceMatcher.kt:192-197 and SourceScoringPolicy.
- **Two-regex sync:** Task 1 (core parser) and Task 2 (gradle recognizer) both move to the same enumerated set; the changelog and comments note the sync requirement.
- **Evidence gate:** Task 8 must pass on current code before Task 9 removes anything — if session polling actually fetches under healthy SSE, the removal premise is reported as wrong rather than forced.
- **No identifier renames in Track C:** asset-contract grep tokens preserved; only module docs clarified.
