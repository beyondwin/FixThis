# v1.0-Readiness Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deepen source-matching evidence (recommended shared-component edit surface, layout-wrapper + naming-convention coverage), give interop-risk selections richer Compose-subtree evidence, render that new evidence in the console/handoff, finish the SSE cleanup behind an evidence gate, add a first-class ChatGPT agent path, and harden the release-readiness tracker into a v1.0 external-release gate.

**Architecture:** One umbrella plan, four independently shippable tracks with separate commit boundaries. Tracks A (Kotlin core), B (Kotlin MCP), and D-writer (Kotlin CLI) are net-new/additive and run first; Track C renders A/B evidence and finishes SSE cleanup (instrument → prove → remove or narrow to docs); Track D release hardening runs last so the v1.0 gate verifies the just-landed features. No persisted-JSON or bridge-protocol breaking change — model additions are additive only.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, JUnit/kotlin.test, Node.js (`node:test`, fixture-lab + readiness scripts), Playwright (`scripts/console-browser-reliability.mjs`), Bash, Markdown.

Design: [`docs/superpowers/specs/2026-05-31-v1-readiness-umbrella-design.md`](../specs/2026-05-31-v1-readiness-umbrella-design.md).

---

## Pre-flight findings (read before starting — these scope the tasks honestly)

The following already exist; tasks below **extend/verify**, they do not rebuild:

- **Call-site ranking** — `fixthis-compose-core/.../source/SharedComponentCallSiteRanking.kt` already ranks call sites, does word-aware matching, and marks the top site `mostLikely` (`SourceLocationRef.mostLikely`, `Models.kt:103`). Track A Task 1 builds the *recommended edit surface* on top of this; it does **not** reimplement ranking.
- **Layout/SubcomposeLayout detection** — `fixthis-gradle-plugin/.../source/KotlinSemanticSignalScanner.kt:202-206` already detects `Layout`/`SubcomposeLayout` calls and local declarations and emits `LAYOUT_RENDERER` signals consumed in `SourceMatcher.kt`. Track A Task 2 verifies custom-wrapper coverage and extends only a proven gap.
- **Interop boundary context** — `fixthis-mcp/.../session/TargetBoundaryContextFormatter.kt` already surfaces the single nearest boundary-context node for `POSSIBLE_VIEW_INTEROP` selections. Track B Task 4 widens this to a small ranked set.
- **SSE healthy-path no-poll** — v0.9 Track B already proved healthy SSE does not poll `/api/sessions` and found the `sessionsPollingPaused` projection is *retained* fallback UI, not dead code. Track C Task 7 is therefore gated and may narrow to docs, exactly as v0.9 Task 5 did.
- **Agent writers** — `AgentConfigWriter` interface (`name`, `scope`, `configFile`, `merge`), with `Claude`/`Codex`/`Cursor` writers and `SetupPlanner.selectedWriters` / three `SetupCommand.kt` `--target` choice lists (`"codex","claude","cursor","local","all"`). Track D Task 8 mirrors `CursorConfigWriter` — **if** ChatGPT exposes a file-based MCP config (verified in Task 8 Step 1).
- **Release claim manifests** — `docs/contributing/release-readiness.md` carries per-version "Release Claim Manifest" tables enforced by `scripts/check-release-readiness.mjs`. Track D Task 10 adds the v1.0 manifest the same way the v0.8 one was added.

---

## File Structure

### Track A — Source-matching depth (Tasks 1–3)
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` (additive `SourceLocationRef` field)
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`
- Test: `fixthis-compose-core/src/test/.../source/SharedComponentCallSiteRankingTest.kt`, `.../source/SourceMatcherTest.kt`, `.../identity/TestTagConventionTest.kt`
- Modify: `fixtures/source-matching/manifest.json`, `scripts/source-matching-fixtures-test.mjs`

### Track B — Interop awareness (Task 4)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`
- Test: `fixthis-mcp/src/test/.../session/TargetBoundaryContextFormatterTest.kt`

### Track C — Console/UX (Tasks 5–7)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` (+ its test)
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`, then rebundle to `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `scripts/console-browser-reliability.mjs`
- Modify: `docs/reference/feedback-console-contract.md`

### Track D — Distribution (Tasks 8–10)
- Create (conditional): `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/ChatGptConfigWriter.kt`
- Modify: `fixthis-cli/.../commands/SetupPlanner.kt`, `SetupCommand.kt`
- Test: `fixthis-cli/src/test/.../commands/AgentConfigWriterTest.kt`, `SetupPlannerTest.kt`
- Modify: `docs/reference/cli.md`, `docs/getting-started/agent-install-snippet.md`
- Modify: `docs/contributing/release-readiness.md`, `scripts/check-release-readiness.mjs`, `CHANGELOG.md`

---

## Track A — Source-Matching Depth

The shared-component **definition** confidence cap stays MEDIUM (approved). No task in this track raises the definition candidate above medium.

### Task 1: Recommended edit surface for a confidently-disambiguated call site

When exactly one shared-component call site clears the next by the existing margin, expose it as a **recommended edit surface** so agents and the console can point at it directly — while the definition candidate stays MEDIUM. This is additive over the existing `mostLikely` flag: `mostLikely` already means "best-guess ordering"; the new flag means "confident enough to recommend editing here."

**Files:**
- Modify: `fixthis-compose-core/.../model/Models.kt`
- Modify: `fixthis-compose-core/.../source/SharedComponentCallSiteRanking.kt`
- Modify: `fixthis-compose-core/.../model/SourceCandidateMappers.kt` and `.../domain/evidence/SourceHint.kt` (mirror the new field through both mappers)
- Test: `fixthis-compose-core/src/test/.../source/SharedComponentCallSiteRankingTest.kt`

- [ ] **Step 1: Add the additive model field**

In `Models.kt`, extend `SourceLocationRef` with a defaulted field so the wire contract stays backward-compatible:

```kotlin
@Serializable
data class SourceLocationRef(
    val file: String,
    val line: Int? = null,
    val mostLikely: Boolean = false,
    val recommendedEditSite: Boolean = false,
)
```

Mirror the field in `SourceHint.kt`'s `SourceHintLocation` and in both `SourceCandidateMappers.kt` conversion functions (`toSourceHintLocation`, `toSourceLocationRef`) so it round-trips. (`SourceCandidateMappers.kt:47-49`.)

- [ ] **Step 2: Write the failing ranking test**

Append to `SharedComponentCallSiteRankingTest`:

```kotlin
    @Test
    fun marksRecommendedEditSiteWhenTopClearsByMargin() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/A.kt:10\tScreenA\tCancel",
                "ui/B.kt:20\tScreenB\tSave changes",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals("ui/B.kt", ranked[0].file)
        assertEquals(true, ranked[0].mostLikely)
        assertEquals(true, ranked[0].recommendedEditSite)
        assertEquals(false, ranked[1].recommendedEditSite)
    }

    @Test
    fun doesNotRecommendEditSiteWithoutAConfidentMargin() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/A.kt:10\tScreenA\tSave",
                "ui/B.kt:20\tScreenB\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(false, ranked[0].recommendedEditSite)
        assertEquals(false, ranked[1].recommendedEditSite)
    }
```

- [ ] **Step 3: Run to confirm failure**

Run: `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest" --no-daemon`
Expected: FAIL — `recommendedEditSite` is always `false` (field just added, never set).

- [ ] **Step 4: Set `recommendedEditSite` on the confident top site**

In `rankSharedComponentCallSites` (`SharedComponentCallSiteRanking.kt:43-45`), the same `markTop` margin condition that drives `mostLikely` is the confidence signal. Recommend the top site only when it is `mostLikely` **and** the next site scored zero (no competing evidence), keeping recommendation stricter than ordering:

```kotlin
    val secondHasEvidence = secondScore > 0.0
    val recommendTop = markTop && !secondHasEvidence
    return ordered.mapIndexed { index, (site, _) ->
        SourceLocationRef(
            file = site.file,
            line = site.line,
            mostLikely = markTop && index == 0,
            recommendedEditSite = recommendTop && index == 0,
        )
    }
```

- [ ] **Step 5: Run to confirm pass (and existing ranking tests still pass)**

Run: `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest" --tests "*SourceMatcherTest" --no-daemon`
Expected: PASS, including the v0.9 margin/tie guards. The definition candidate's MEDIUM cap is untouched — confirm no `SourceMatcherTest` confidence assertion changed.

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRankingTest.kt
git commit -m "feat(source): recommend the confidently-disambiguated shared-component call site"
```

### Task 2: Verify and extend custom layout-wrapper coverage

`Layout`/`SubcomposeLayout` are already detected. The gap the spec targets is a **custom composable wrapper** that internally calls `Layout` (e.g. `fun AppGrid(...) { Layout(...) }`) — a target rendered inside it should map to `AppGrid`'s `LAYOUT_RENDERER` context. This task proves current behavior with a test and extends only if a real gap is found.

**Files:**
- Test: `fixthis-compose-core/src/test/.../source/SourceMatcherTest.kt` (or the scanner test under `fixthis-gradle-plugin/src/test/...` — choose whichever module owns the existing `LAYOUT_RENDERER` assertions; locate in Step 1)
- Modify (only if Step 3 shows a gap): `fixthis-gradle-plugin/.../source/KotlinSemanticSignalScanner.kt`

- [ ] **Step 1: Locate existing LAYOUT_RENDERER coverage**

Run: `grep -rln "LAYOUT_RENDERER\|layoutRendererSignals\|LayoutRenderer" --include=*.kt . | grep -v /build/ | grep test`
Expected: find the test(s) that assert `LAYOUT_RENDERER` emission. Read the closest one to learn the harness shape (how source strings are fed to `layoutRendererSignals` / the scanner, and how owner attribution is asserted).

- [ ] **Step 2: Add a failing-or-confirming custom-wrapper test**

Add a test feeding a custom wrapper that delegates to `Layout`, asserting the wrapper function owns a `LAYOUT_RENDERER` signal. Adapt the call to the harness found in Step 1; representative shape:

```kotlin
    @Test
    fun customComposableWrappingLayoutOwnsLayoutRendererSignal() {
        val source = """
            import androidx.compose.ui.layout.Layout
            @Composable fun AppGrid(content: @Composable () -> Unit) {
                Layout(content = content) { measurables, constraints -> layout(0, 0) {} }
            }
        """.trimIndent()

        val signals = layoutRendererSignals(source)

        assertTrue(signals.any { it.ownerComposable == "AppGrid" })
    }
```

- [ ] **Step 3: Run; branch on the result**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSemanticSignalScanner*" --no-daemon` (use the module/test class from Step 1)
- **If PASS:** coverage already exists. Keep the test as a regression lock and skip to Step 5 (no scanner change).
- **If FAIL:** the scanner does not attribute the inner `Layout` call to the enclosing custom composable. In `KotlinSemanticSignalScanner.kt`, extend the owner attribution so an enclosing `@Composable fun` wrapping a `Layout`/`SubcomposeLayout` call is recorded as a layout renderer owner. Use the existing `layoutRendererRegex` / `localLayoutRendererDeclarationRegex` machinery (lines ~202-206); do not add a parallel detector.

- [ ] **Step 4: Re-run to confirm pass**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSemanticSignalScanner*" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/test fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt
git commit -m "test(source): lock custom layout-wrapper LAYOUT_RENDERER coverage"
```

(If Step 3 was PASS with no scanner change, drop the scanner path from the `git add`.)

### Task 3: Expand composable-name conventions + pin a fixture-lab case

Add one more idiomatic strict-tag convention and pin a fixture-lab case so the recommended-edit-site path (Task 1) and the medium cap stay regression-locked.

**Files:**
- Modify: `fixthis-compose-core/.../identity/TestTagConvention.kt` (+ `TestTagConventionTest.kt`)
- Modify: `fixtures/source-matching/manifest.json`, `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Read the convention set and its gradle-side mirror**

Run: `sed -n '1,40p' fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`
Expected: see the three enumerated regexes (`comp:`, `screen:`, `comp.`) and the note "Kept in sync with the gradle-side strictCompTestTagRegex." Any convention added here MUST be mirrored gradle-side; locate that regex with `grep -rn "strictCompTestTagRegex" --include=*.kt . | grep -v /build/`.

- [ ] **Step 2: Write a failing convention test**

Append to `TestTagConventionTest` a case for the new convention (choose a form actually emitted by the strict-tag scanner — verify against the gradle-side regex from Step 1; representative `screen.<Name>.<id>`):

```kotlin
    @Test
    fun parsesDotDelimitedScreenConvention() {
        val parsed = TestTagConvention.parse("screen.Profile.avatar")
        assertEquals("Profile", parsed?.composableName)
    }
```

- [ ] **Step 3: Run to confirm failure, then add the regex on both sides**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionTest" --no-daemon`
Expected: FAIL. Add the matching regex to `TestTagConvention.conventions` AND the mirrored gradle-side `strictCompTestTagRegex`, keeping them identical (the sync note is load-bearing).

- [ ] **Step 4: Run convention + scanner tests**

Run: `./gradlew :fixthis-compose-core:test --tests "*TestTagConventionTest" :fixthis-gradle-plugin:test --tests "*StrictComp*" --no-daemon`
Expected: PASS on both sides.

- [ ] **Step 5: Pin a fixture-lab case for the recommended edit site**

Following the v0.8 `fixthis-sample-shared-component` pattern, add/extend a `local-project` case in `fixtures/source-matching/manifest.json` selecting a reused-component call site that should now carry a recommended edit site, expecting `confidence: "medium"` + `riskFlags: ["SHARED_COMPONENT"]` (definition stays medium). Inspect existing keys first: `sed -n '1,80p' fixtures/source-matching/manifest.json`.

- [ ] **Step 6: Run the fixture-lab test**

Run: `node scripts/source-matching-fixtures-test.mjs`
Expected: PASS including the pinned case. If the evaluator does not assert call-site/recommended fields, extend it minimally to read `callSites[].recommendedEditSite` for this case (follow the existing assertion idiom).

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt fixthis-compose-core/src/test fixtures/source-matching/manifest.json scripts/source-matching-fixtures-test.mjs
git commit -m "feat(source): add dot-delimited screen convention and pin recommended-edit-site fixture"
```

---

## Track B — Interop Awareness (Richer Subtree Evidence)

### Task 4: Surface multiple ranked boundary-context nodes

`TargetBoundaryContextFormatter` currently emits only the single nearest context node (`firstOrNull()`). Widen it to a small ranked set (top N, N=3) so an interop-risk selection carries a clearer map of the surrounding Compose subtree. Non-interop selections stay unchanged (empty).

**Files:**
- Modify: `fixthis-mcp/.../session/TargetBoundaryContextFormatter.kt`
- Test: `fixthis-mcp/src/test/.../session/TargetBoundaryContextFormatterTest.kt`

- [ ] **Step 1: Read the formatter test for AnnotationDto construction**

Run: `sed -n '1,120p' fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
Expected: learn how tests build an `AnnotationDto` with `POSSIBLE_VIEW_INTEROP` warning and multiple `nearbyNodes`. Reuse that builder.

- [ ] **Step 2: Write the failing multi-context test**

```kotlin
    @Test
    fun preciseLinesIncludeUpToThreeRankedContextNodes() {
        val item = interopItemWith( // builder from existing test file
            nearbyNodes = listOf(
                nodeWithTag("comp:Header:bar"),
                nodeWithTag("comp:Card:body"),
                nodeWithTag("comp:Footer:cta"),
                nodeWithTag("comp:Extra:zzz"),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        val contextLines = lines.filter { it.contains("Boundary context:") }
        assertEquals(3, contextLines.size)
    }

    @Test
    fun nonInteropSelectionStillProducesNoContext() {
        val item = nonInteropItemWith(nearbyNodes = listOf(nodeWithTag("comp:Header:bar")))
        assertTrue(TargetBoundaryContextFormatter.preciseLines(item).isEmpty())
    }
```

- [ ] **Step 3: Run to confirm failure**

Run: `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon`
Expected: FAIL — only one context line is produced today.

- [ ] **Step 4: Widen the formatter to a ranked top-N**

In `TargetBoundaryContextFormatter.kt`, replace the single `boundaryContextNode()` (`firstOrNull()`) with a `boundaryContextNodes(limit = 3)` that keeps the existing ranking comparator (`comp:`-prefixed first, then smaller `boundsInWindow.area`) and `take(limit)`. Emit one "Boundary context:" line per node; keep the single trailing "does not prove Compose owns the selected pixels" note. Keep `compactLine` returning the single top node. Preserve the `hasInteropBoundary()` gate so non-interop selections stay empty.

- [ ] **Step 5: Run to confirm pass**

Run: `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon`
Expected: PASS for both new tests and all existing ones.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt
git commit -m "feat(interop): surface up to three ranked boundary-context nodes"
```

---

## Track C — Console / UX (depends on Tasks 1 and 4)

### Task 5: Render the recommended call site in handoff + console

Surface Task 1's `recommendedEditSite` so the handoff and console point at the confident call site. Reuse the existing "Shared component used at" / `mostLikely` rendering paths in `annotationDetailView.js` and `app.js`.

**Files:**
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
- Rebundle: `fixthis-mcp/src/main/resources/console/app.js` (generated)
- Modify (if it renders call sites): `fixthis-mcp/.../session/CompactHandoffRenderer.kt` (+ test)

- [ ] **Step 1: Find the current call-site render**

Run: `grep -n "callSites\|mostLikely\|most likely\|used at" fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
Expected: locate where each call site renders and where `mostLikely` adds its "(most likely)" label. The recommended label attaches at the same point.

- [ ] **Step 2: Add a JS unit test (node:test) for the recommended label**

Locate the existing JS test harness: `grep -rln "annotationDetailView\|renderAnnotation" fixthis-mcp/src/main/console --include=*.test.js` (or the repo's console JS test dir). Add a case asserting a call site with `recommendedEditSite: true` renders an "edit here" / "(recommended)" marker distinct from "(most likely)". Match the file's existing assertion style.

- [ ] **Step 3: Run the JS test to confirm failure**

Run the console JS harness (per `CONTRIBUTING.md` § console JS harnesses — e.g. `node --test fixthis-mcp/src/main/console/...`).
Expected: FAIL — no recommended marker yet.

- [ ] **Step 4: Render the marker**

In `annotationDetailView.js`, when a call site has `recommendedEditSite`, render a clear "(recommended edit site)" marker. Keep `mostLikely` rendering intact (a site can be both). Do not raise any confidence label — this is verification context.

- [ ] **Step 5: Rebundle and verify**

Run: `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: rebundles `app.js` within size budget; `--check` passes.

- [ ] **Step 6: Mirror in the handoff renderer if applicable**

Run: `grep -n "callSite\|most likely\|Used at" fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- If the handoff renders call sites: add the recommended marker there too, test-first in `CompactHandoffRendererTest`.
- If it does not (current grep shows none): skip — record that the handoff does not enumerate call sites.

- [ ] **Step 7: Visual verification (REQUIRED for UI per project convention)**

Drive the console in a browser (Playwright) on an item whose selected node resolves to a recommended call site; screenshot the Evidence section and confirm the recommended marker renders and is visually distinct. `console:smoke` is known-broken — do not rely on it.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/presentation/annotationDetailView.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): mark the recommended shared-component edit site"
```

### Task 6: Render richer interop subtree context in the console

Surface Task 4's multiple boundary-context lines in the console interop section.

**Files:**
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js` (+ rebundle `app.js`)

- [ ] **Step 1: Find the interop/boundary render**

Run: `grep -n "boundary\|interop\|Boundary context\|View interop" fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
Expected: locate where boundary context renders today (single line).

- [ ] **Step 2: JS test for multiple context rows**

Add a node:test case feeding an annotation whose boundary context now has multiple entries; assert each renders as its own row. Match existing harness style.

- [ ] **Step 3: Run to confirm failure**

Run the console JS harness.
Expected: FAIL — only one row rendered.

- [ ] **Step 4: Render each boundary-context entry**

Update `annotationDetailView.js` to iterate the boundary-context entries and render one row each, preserving the existing "does not prove Compose owns the selected pixels" caveat once.

- [ ] **Step 5: Rebundle + visual verification**

Run: `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`, then Playwright-screenshot an interop-risk item and confirm multiple context rows render. Expected: PASS + correct visuals.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/presentation/annotationDetailView.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): render richer interop boundary subtree context"
```

### Task 7: Finish SSE cleanup (evidence-gated)

Extend the reliability proof to cover the remaining manual-recovery surface (preview polling in addition to session polling), then remove only what is provably unused. If nothing is provably dead, narrow to docs — exactly as v0.9 Task 5 did.

**Files:**
- Modify: `scripts/console-browser-reliability.mjs`
- Modify (only if proven dead): the relevant console JS recovery module
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Extend the proof to assert zero preview polling under healthy SSE**

Run: `sed -n '1,160p' scripts/console-browser-reliability.mjs`
Expected: find the existing zero-`/api/sessions`-poll assertion (added in v0.9). Add a sibling counter+assertion for the preview-poll endpoint under a healthy EventSource (grep the file for the preview-fetch URL it already references). Representative:

```js
let previewPollCount = 0;
page.on('request', (req) => {
  if (req.url().includes('/api/preview')) previewPollCount += 1;
});
// ... healthy SSE window ...
assert.equal(previewPollCount, 0, 'healthy SSE session must not poll preview');
```

- [ ] **Step 2: Run the proof**

Run: `node scripts/console-browser-reliability.mjs`
Expected: PASS. If nonzero, the healthy path still polls — fix the arming logic before any removal; do not proceed to Step 3 deletions.

- [ ] **Step 3: Gate — identify any provably-dead recovery code**

Run: `grep -rn "manualRecover\|recover\|retryPoll\|reconnectPoll" fixthis-mcp/src/main/console | head`
- For each candidate, confirm it is **not** read/rendered on the retained disconnected-fallback path (the spec retains that path). If a symbol is provably unrendered AND unused under both healthy and fallback paths, remove it test-first.
- If every candidate is still referenced (the likely outcome, per v0.9 findings), this step is a **NO-OP** — record which symbols are retained and why (cite the file:line that reads them).

- [ ] **Step 4: Rebuild if any JS changed; re-run proof**

Run: `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check && node scripts/console-browser-reliability.mjs` (skip the rebuild if Step 3 was a no-op).
Expected: PASS including both zero-poll assertions.

- [ ] **Step 5: Update the console contract doc**

In `docs/reference/feedback-console-contract.md`, state that healthy SSE performs no session **and no preview** polling (proven), and record the gate outcome (what was removed, or that retained-fallback recovery code is intentionally kept and which contract tests pin it).

- [ ] **Step 6: Commit**

```bash
git add scripts/console-browser-reliability.mjs docs/reference/feedback-console-contract.md
git commit -m "test(console): prove zero preview polling under healthy SSE; record cleanup gate outcome"
```

---

## Track D — Distribution (ChatGPT path + release hardening)

### Task 8: ChatGPT agent path — verify config surface, then writer or documented connector

**This task begins with a verification gate.** ChatGPT may not expose a file-based MCP config like `.cursor/mcp.json`. Do not invent a config path.

**Files:**
- Create (conditional): `fixthis-cli/.../commands/ChatGptConfigWriter.kt`
- Test (conditional): `fixthis-cli/src/test/.../commands/AgentConfigWriterTest.kt`
- Modify (fallback branch): `docs/getting-started/agent-install-snippet.md`, `docs/reference/cli.md`

- [ ] **Step 1: Verify ChatGPT's MCP config surface (decision gate)**

Research the current ChatGPT desktop/connector MCP configuration mechanism. Determine: does ChatGPT read a writable, project-local or per-user JSON file (path + `mcpServers`-style shape)?
- **Branch A — a writable file exists:** record the exact path + JSON shape; proceed to Step 2 (writer).
- **Branch B — no writable file (UI/connector-only):** ChatGPT cannot be a file `AgentConfigWriter`. Skip Steps 2–4; document the ChatGPT connector / Copy-Prompt path in `docs/getting-started/agent-install-snippet.md` instead, and record the decision in the commit message. Then continue to Task 10 (skip Task 9's target registration).

- [ ] **Step 2: (Branch A) Write failing writer tests**

Mirror `AgentConfigWriterTest`'s Cursor cases for ChatGPT, using the path/shape confirmed in Step 1:

```kotlin
    @Test
    fun chatgptWriterTargetsConfirmedConfigPath() {
        val writer = ChatGptConfigWriter()
        assertEquals("chatgpt", writer.name)
        assertTrue(writer.configFile(java.io.File("/repo")).path.endsWith("<confirmed/path>"))
    }

    @Test
    fun chatgptMergePreservesOtherServers() {
        val current = """{"mcpServers":{"playwright":{"command":"npx","args":["-y","@playwright/mcp"]}}}"""
        val merged = ChatGptConfigWriter().merge(current, entry)
        assertTrue(merged.contains("\"playwright\""))
        assertTrue(merged.contains("\"fixthis\""))
    }
```

- [ ] **Step 3: (Branch A) Implement `ChatGptConfigWriter.kt`**

Mirror `CursorConfigWriter` (`name`, `scope`, `configFile`, `merge` with the same `mcpServers` merge/guard semantics), substituting the confirmed path and any ChatGPT-specific server JSON shape from Step 1. Reuse `McpConfigEntry` and `fixThisJson`.

- [ ] **Step 4: (Branch A) Run to confirm pass**

Run: `./gradlew :fixthis-cli:test --tests "*AgentConfigWriterTest" --no-daemon`
Expected: PASS, existing writers unaffected.

- [ ] **Step 5: Commit (either branch)**

```bash
# Branch A:
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/ChatGptConfigWriter.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt
git commit -m "feat(cli): add ChatGPT MCP config writer"
# Branch B:
git add docs/getting-started/agent-install-snippet.md docs/reference/cli.md
git commit -m "docs(cli): document ChatGPT connector/Copy-Prompt path (no file-based MCP config)"
```

### Task 9: (Branch A only) Register `chatgpt` target + CLI + docs

Skip this task entirely if Task 8 took Branch B.

**Files:**
- Modify: `fixthis-cli/.../commands/SetupPlanner.kt` (+ `SetupPlannerTest.kt`)
- Modify: `fixthis-cli/.../commands/SetupCommand.kt` (three `--target` choice lists)
- Modify: `docs/reference/cli.md`, `docs/getting-started/agent-install-snippet.md`

- [ ] **Step 1: Failing selection tests**

Append to `SetupPlannerTest`:

```kotlin
    @Test
    fun chatgptTargetSelectsChatGptWriter() {
        assertEquals(listOf("chatgpt"), SetupPlanner.selectedWriters("chatgpt").map { it.name })
    }

    @Test
    fun allTargetIncludesChatGpt() {
        assertTrue(SetupPlanner.selectedWriters("all").map { it.name }.contains("chatgpt"))
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :fixthis-cli:test --tests "*SetupPlannerTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Register the writer**

In `SetupPlanner.kt` add `"chatgpt" -> listOf(ChatGptConfigWriter())` and append `ChatGptConfigWriter()` to `allWriters()`. In `SetupCommand.kt`, add `"chatgpt"` to all three `.choice(...)` lists (lines ~46, ~305, ~424).

- [ ] **Step 4: Run tests + CLI dry run**

Run: `./gradlew :fixthis-cli:test --tests "*SetupPlannerTest" --no-daemon && ./gradlew :fixthis-cli:installDist --no-daemon && ./fixthis-cli/build/install/fixthis/bin/fixthis install-agent --project-dir . --target chatgpt --dry-run`
Expected: tests PASS; dry-run lists the planned ChatGPT write with no invalid-`--target` error.

- [ ] **Step 5: Document + surface check**

Add `chatgpt` to `docs/reference/cli.md` `--target` values and to `docs/getting-started/agent-install-snippet.md`. Run: `bash scripts/check-docs-cli-surface.sh && ./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --no-daemon`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlanner.kt fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlannerTest.kt docs/reference/cli.md docs/getting-started/agent-install-snippet.md
git commit -m "feat(cli): accept --target chatgpt and document it"
```

### Task 10: v1.0 release claim manifest + check rule + changelog

Harden the release tracker into a v1.0 external-release gate, mirroring the v0.8 manifest pattern. No tag push.

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Read the v0.8 manifest section as the template**

Run: `grep -n "Release Claim Manifest" docs/contributing/release-readiness.md` then read the v0.8 section and the required-section list in `scripts/check-release-readiness.mjs` (`sed -n '1,80p' scripts/check-release-readiness.mjs`).

- [ ] **Step 2: Add a `## v1.0 Release Claim Manifest` section**

Add a table mapping each claim landed in this umbrella to its evidence command. Cover only what shipped:

```markdown
## v1.0 Release Claim Manifest

v1.0 may claim the items below only when the release issue includes evidence for each.

| Claim | Required evidence |
| --- | --- |
| A confidently-disambiguated shared-component call site is surfaced as a recommended edit surface; the definition stays capped at medium. | `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest" --tests "*SourceMatcherTest" --no-daemon` and `node scripts/source-matching-fixtures-test.mjs`. |
| Custom composables wrapping Layout/SubcomposeLayout carry layout-renderer context. | `./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSemanticSignalScanner*" --no-daemon`. |
| Interop-risk selections surface multiple ranked boundary-context nodes. | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon`. |
| Healthy SSE sessions perform no session and no preview polling. | `node scripts/console-browser-reliability.mjs`. |
| <ChatGPT row — phrase per Task 8 outcome: first-class writer (Branch A) OR documented connector path (Branch B)>. | `./gradlew :fixthis-cli:test --tests "*AgentConfigWriterTest" --tests "*SetupPlannerTest" --no-daemon` (Branch A) / `bash scripts/check-docs-cli-surface.sh` (Branch B). |
```

Drop or rephrase any row whose claim did not ship (e.g. Track A Task 2 no-op, Task 7 docs-only, Task 8 branch).

- [ ] **Step 3: Add the v1.0 enforcement rule**

In `scripts/check-release-readiness.mjs`, add `"## v1.0 Release Claim Manifest"` to the required-section list using the file's existing assertion idiom.

- [ ] **Step 4: Run the readiness check + every evidence command**

Run: `node scripts/check-release-readiness.mjs` then each command in the manifest.
Expected: PASS. If any evidence command fails, narrow/remove that claim row rather than weakening the check.

- [ ] **Step 5: Update the changelog Unreleased section**

In `CHANGELOG.md`, add the umbrella's user-facing entries (recommended edit surface, layout-wrapper coverage if changed, richer interop context, no-preview-poll proof, ChatGPT path) under `## Unreleased`. No version heading, no tag.

- [ ] **Step 6: Commit**

```bash
git add docs/contributing/release-readiness.md scripts/check-release-readiness.mjs CHANGELOG.md
git commit -m "docs(release): add v1.0 release claim manifest and check rule"
```

---

## Final Integration Pass

- [ ] **Step 1: Run the full required local checks**

Run the full Gradle matrix, console asset check, console JS syntax check, console JS harnesses, and `git diff --check` per [`CONTRIBUTING.md`](../../../CONTRIBUTING.md#required-local-checks). Per the CME pre-merge note, copy `local.properties` into the worktree before the Gradle matrix; the run's gates skip `spotlessCheck`, so run `./gradlew spotlessCheck` explicitly.
Expected: all green.

- [ ] **Step 2: Commit any formatting/asset refresh**

```bash
git add -A
git commit -m "chore(v1-readiness): final integration formatting and asset refresh"
```

---

## Self-Review Notes

- **Spec coverage:** Track A → Tasks 1 (recommended edit surface, definition stays medium), 2 (layout-wrapper coverage), 3 (conventions + fixture pin); Track B → Task 4 (multi-node subtree context); Track C → Tasks 5 (render A), 6 (render B), 7 (SSE cleanup, gated); Track D → Tasks 8 (ChatGPT writer/connector, verification-gated), 9 (target registration, Branch A only), 10 (v1.0 manifest + check rule, no tag). Registry channels / Aider / cap raise are absent by design (out of scope).
- **Honesty gates:** Task 2 branches on whether layout coverage already passes (no rebuild); Task 7 removal is conditioned on the zero-poll proof and a retained-fallback check (likely no-op); Task 8 branches on whether ChatGPT exposes a writable MCP config (no invented path); Task 9 is skipped under Branch B.
- **Type consistency:** new `SourceLocationRef.recommendedEditSite` (defaulted, additive) is mirrored in `SourceHintLocation` and both `SourceCandidateMappers` conversions; `ChatGptConfigWriter` implements the same `AgentConfigWriter` interface as `CursorConfigWriter`; `selectedWriters` branch names match the `SetupCommand` `--target` choices.
- **Wire safety:** all model changes are additive defaulted fields — no persisted-JSON or bridge-protocol break.
- **Adapt-points to verify at execution:** the module/test owning `LAYOUT_RENDERER` assertions (Task 2 Step 1), the gradle-side `strictCompTestTagRegex` mirror (Task 3 Step 1), the console JS test harness location (Tasks 5–6), the preview-poll endpoint URL (Task 7 Step 1), ChatGPT's config surface (Task 8 Step 1), and `check-release-readiness.mjs` required-section idiom (Task 10 Step 3).
```
