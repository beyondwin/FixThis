# Compact Handoff Prompt v2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement v2 of the compact feedback handoff prompt: replace the bare `src?` token with a `candidates:` block (up to 3 candidates with computed margin and matched terms), add explicit `viewport:`, `activity:`, `ui:`, and `box=(L,T)-(R,B) [W×H]` formatting, derive an `instance i/N` label for items grouped by `(file:line, testTag)` with a collision `note:` line, detect true duplicate markers and surface them as `targetRisk=duplicate-of-marker-N`, and populate the previously-dead `SourceCandidate.scoreMargin` field in the matcher. All v2 changes are renderer-side except one matcher fix; `DetailMode.PRECISE`, `DetailMode.FULL`, and JSON wire formats stay unchanged. Kotlin (`CompactHandoffRenderer.kt`) and JS (`prompt.js`) renderers must produce byte-identical source-line bytes.

**Architecture:** Implementation lands in ordered, compatibility-preserving slices. Phase 0 locks the spec, captures the baseline test suite, and adds the one-line matcher fix that populates `scoreMargin`. Phase 1 lands renderer-side cosmetic changes in Kotlin (`viewport:`, `activity:`, `ui:` line, `box=(...)-(...) [W×H]`, severity prefix, screen short-id) — no new information classes, just format. Phase 2 adds multi-candidate output (`candidates:` block, ≤3 entries, rank-1 `margin=` and `matched=`). Phase 3 adds the `InstanceGroupingHelper` and emits `instance i/N` plus the collision `note:`. Phase 4 adds the `DuplicateMarkerDetector` and `targetRisk=duplicate-of-marker-N`. Phase 5 mirrors phases 1-4 in `fixthis-mcp/src/main/console/prompt.js` (re-using v1 mapping/helpers where possible). Phase 6 is tests, parity, and prompt-budget regression guards. Phase 7 is docs and the bundled `app.js` regeneration / mirror. Each phase commits independently; each task within a phase follows strict TDD (red → green → refactor → commit).

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Gradle/JUnit/JUnit-Kotlin, vanilla JS (no transpile) under `fixthis-mcp/src/main/console/`, Node `--check` for JS syntax, optional Node script execution (via `vm.createContext` in a controlled test harness) for JS/Kotlin parity tests. The v1 PromptParityTest harness is reused.

---

## Source Documents

- **Design spec:** `docs/superpowers/specs/2026-05-09-compact-handoff-prompt-v2-design.md` (this plan's design input)
- v1 design: `docs/superpowers/specs/2026-05-08-source-candidate-confidence-handoff-design.md`
- v1 plan: `docs/superpowers/plans/2026-05-08-source-candidate-confidence-handoff.md`
- Console contract: `docs/feedback-console-contract.md`
- MCP schema: `docs/mcp.md`
- v1 prompt parity fixture: `fixthis-mcp/src/test/resources/parity/session.json`

## Execution Rules

- Use a new implementation branch or worktree, preferably `codex/compact-handoff-prompt-v2`.
- Keep each task independently reviewable and commit after each task.
- Follow strict TDD: write the failing test first, run it and confirm the failure, implement, run again and confirm pass, only then commit.
- Each step targets 2-5 minutes of focused work. If a step grows, split it.
- Update the checkbox state in this file as tasks complete.
- After each task, run the listed validation command and record `PASS`, `FAIL`, or `SKIPPED` with the reason in your task notes.
- Preserve unrelated local changes. If a target file has uncommitted edits unrelated to this plan, read those changes first and preserve them.
- Do not run broad process cleanup (no `killall node`, `pkill node`).
- Do not modify user app code in `sample/src/main` to add `Modifier.testTag(...)`.
- Do not change Markdown wire format for `DetailMode.PRECISE` or `DetailMode.FULL`. Only `DetailMode.COMPACT` and the JS console prompt change.
- All confidence tokens emitted in any Markdown surface MUST be lowercase (`high`, `medium`, `low`) — same as v1 contract.
- Kotlin and JS renderers MUST produce byte-identical bytes for the `~ ` candidate lines, the `instance` token, the `note:` lines, and the `targetRisk=duplicate-...` token. Bounds-format byte divergence between Kotlin (`.0` floats) and JS (integers when the input is integer JSON) remains as documented in v1; the v2 parity test compares source/instance/note/risk content but not bounds bytes.

## Current Baseline

- Branch: `main`. Recent commit at plan creation: `15be25f feat(console): merge console-uiux-fixes - 12 UI/UX defects fixed`.
- v1 compact renderer: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt:1-125` (entire file, 125 lines).
- v1 JS prompt: `fixthis-mcp/src/main/console/prompt.js:120-253` (compact functions); v1 reason map at lines 132-146.
- `SourceCandidate` fields: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt:98-110` (declared `scoreMargin`, `evidenceStrength`, `riskFlags`, `caution`, `matchedTerms`, `ranking` — `scoreMargin` is empirically `null` on every wire candidate, see Phase 0.4).
- `FixThisNode.path`: `Models.kt:83`. Empirically populated as `["root", "node:2", "node:61", "node:73"]` style — opaque numerics, distinct leaves per Composable instance.
- `SnapshotScreenshotDto.width / height`: `SessionDtoModels.kt:63-64`.
- `SnapshotDto.activityName`: `SessionDtoModels.kt:41`.
- v1 Kotlin tests:
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationOverlapDetectorTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/PromptParityTest.kt`
- v1 JS prompt-parity Node harness: `fixthis-mcp/src/test/resources/parity/run-prompt.js`
- v1 prompt-budget test (TBD location, locked in Phase 0.3).

## Execution Order Preamble

Definitions before call sites: Phase 0 locks fixtures and adds the matcher fix in isolation (no renderer change); Phase 1-4 update the Kotlin renderer in slices that each compile and pass on their own; Phase 5 ports each Kotlin slice to JS in the same order; Phase 6 adds the integrating tests last. Phase 1 tests must use only renderer changes introduced inside Phase 1 itself plus those that already exist; they MUST NOT reference grouping helpers added in Phase 3.

### Known wire divergence between Kotlin and JS (carried over from v1)

The Kotlin `FixThisRect.formatBounds()` emits floats with a `.0` suffix (`10.0,20.0,110.0,70.0`). The JS equivalent prints integers as integers when the input JSON encodes integers. v2 retains the v1 documented stance: byte-stable callers consume JSON, not the compact Markdown. The v2 parity test compares source-block, instance, note, and risk-token content but not bounds bytes.

### v2 box format note

`box=(L,T)-(R,B) [W×H]` — Kotlin and JS will both compute integer width/height from the `right - left` / `bottom - top` floats and render those as integers. The (L,T,R,B) values inside the parentheses retain the same per-runtime divergence as v1 (Kotlin `.0` suffix, JS bare integer if input is integer). The size suffix `[W×H]` is integer-only on both sides — the parity test asserts the size suffix is byte-identical even though the LTRB values may differ.

---

## File Structure

### Modify (Kotlin sources)

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` — replace `compactTargetSummary()` and `compactSourceLine()`; add `viewport:` and `activity:` lines in `render()`; integrate `InstanceGroupingHelper` and `DuplicateMarkerDetector` outputs; render `candidates:` block.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt` — add `FixThisRect.formatBox(): String` returning `"(L,T)-(R,B) [W×H]"`. Keep existing `formatBounds()` for PRECISE/FULL formatters.
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` — populate `scoreMargin` on rank-1 `SourceCandidate` (single-line addition in `toCandidate()`/result-list assembly).
- `fixthis-mcp/src/main/console/prompt.js` — mirror Kotlin renderer: replace `compactSourceLine` and `compactTargetLine`; add `compactViewportLine`, `compactActivityLine`, `computeInstanceLabels`, `computeDuplicateMarkers`, `compactCandidatesBlock`.
- `fixthis-mcp/src/main/resources/console/app.js` — bundled equivalent regenerated from the source files in `fixthis-mcp/src/main/console/`. (Inspect to confirm whether `prompt.js` is concatenated by `build-console-assets.mjs` or hand-edited; mirror changes in both if hand-edited. v1 plan task 7.4 left a note that this is concatenated; verify in Phase 0.5.)

### Create (Kotlin sources)

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/InstanceGroupingHelper.kt` — pure helper that takes `List<AnnotationDto>` for one screen and returns `Map<itemId, InstanceLabel>` plus the set of group-leader item IDs (used to decide where to emit the collision note).
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/DuplicateMarkerDetector.kt` — pure helper that takes ordered `List<AnnotationDto>` and returns `Map<itemId, Int /* refers-to marker number */>`.

### Modify (tests)

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` — add v2 assertions for AC-1..AC-5; existing v1 assertions either updated (where shape changes) or kept as-is (where unchanged: `Rule:` line, package/items lines, screenshot reference, overlap-group structure, crop line).
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/PromptParityTest.kt` — extend to compare v2 candidate/instance/note/risk content between Kotlin and JS for a fixture with multi-candidate, instance grouping, and duplicate items.
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` — add a test that `scoreMargin` is populated when ≥2 candidates exist; assert remains `null` when 0 or 1 candidate exists.

### Create (tests)

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/InstanceGroupingHelperTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/DuplicateMarkerDetectorTest.kt`

### Create or extend (test resources)

- `fixthis-mcp/src/test/resources/parity/session-v2.json` — fixture session with: 1 screen, 4 items, multi-candidate (3+), 3 items sharing `(file:line, testTag)` with distinct `path` leaves, 1 true duplicate of item 1.
- `fixthis-mcp/src/test/resources/parity/expected-prompt-v2.txt` — expected v2 prompt output (regenerated when format changes).
- `fixthis-mcp/src/test/resources/parity/run-prompt.js` — extend the existing v1 harness to also accept a v2 fixture path; same `vm.createContext` pattern.

### Modify (docs)

- `docs/feedback-console-contract.md` — replace v1 token grammar table with v2; document `viewport:`, `activity:`, `ui:`, `box=`, `candidates:`, `instance`, collision `note:`, `targetRisk=duplicate-of-marker-N`. Note the v1 → v2 token migration for tool-using consumers.
- `docs/mcp.md` — update `SourceCandidate` field docs to clarify `scoreMargin` is now populated by the matcher.
- `CHANGELOG.md` — record the user-visible changes.

---

## Phase 0 — Lock spec, baseline, and matcher scoreMargin fix

Goal: capture the v1 test baseline, lock the v2 fixtures, and ship the one-line matcher fix in isolation so v2 renderer work can rely on it.

### Task 0.1 — Capture baseline test pass count

- [x] Run the full Gradle test suite: `./gradlew test`. Record total tests, pass count, and any pre-existing failures in this file under "Phase 0 baseline notes". Stop and ask if the baseline is broken in a way that blocks v2.

**Validation:** `./gradlew test`
**Expected:** ≥281 tests pass (matches recent baselines from console-uiux-fixes / annotation-screen-mismatch); ≤2 pre-existing failures.

### Task 0.2 — Inventory v1 renderer/test surface

- [ ] Read `CompactHandoffRenderer.kt` end-to-end. Confirm functions to be modified: `render`, `appendCompactItem`, `compactTargetSummary`, `compactSourceLine`. Confirm `reasonTokenFor` mapping is unchanged in v2 (used inside `matched=[...]`).
- [ ] Read `prompt.js` lines 120-253. Confirm functions to be modified: `compactSourceLine`, `compactTargetLine`, `compactItemLines`, `compactScreenHeader`, `currentAnnotationsPromptCompact`. Confirm `FIXTHIS_REASON_TOKEN_MAP` is reused.
- [ ] Read `CompactHandoffRendererTest.kt` and list every assertion that will need to change. Add the list to this file under "Phase 0 inventory notes". (Tests that assert `bounds=` literal, `target: Node` literal, `src? ` literal — these need v2 updates. Tests that assert `Rule:` line, package line, items line, overlap header — these stay.)

**Validation:** Visual inventory; commit a brief notes section to this file.
**Expected:** Inventory is comprehensive; no surprise tests in unrelated paths.

### Task 0.3 — Locate and read the v1 prompt-budget test

- [ ] Find the v1 prompt-budget regression guard (it was introduced in v1 plan Phase 8). Likely location: `FeedbackQueueFormatterTest.kt` or `CompactHandoffRendererTest.kt`. Note the exact assertion and the budget value.
- [ ] Calculate the v2 budget: `1.5 × v1_baseline_chars` for an N-item, 1-candidate-each prompt, where v2 will render the same N items with up to 3 candidates each. Record the formula and the concrete number for the existing fixture in this file.

**Validation:** Visual; record formula.
**Expected:** v1 budget exists; v2 formula derived.

### Task 0.4 — Verify scoreMargin is null on real wire data

- [ ] Run a one-shot Python or Kotlin script against an existing session JSON (e.g. `.fixthis/feedback-sessions/a8483865-fd81-4075-a313-2a44d7a6c1d4/session.json`) and confirm every `sourceCandidates[*].scoreMargin` is `null`. This confirms the matcher fix in Phase 0.6 is necessary.

**Validation:** Inspection script output.
**Expected:** `scoreMargin == null` for ≥1 candidate where ≥2 candidates exist on the same item.

### Task 0.5 — Confirm console asset bundling pipeline

- [ ] Read `build-console-assets.mjs`. Confirm whether `prompt.js` source is concatenated into `app.js` automatically (so v2 changes to `prompt.js` flow into `app.js` after running the build), or whether `app.js` is hand-edited. Record finding in this file.
- [ ] If concatenated: note the exact build command needed after Phase 5 changes.

**Validation:** Visual.
**Expected:** Confirmed concatenation; build command captured.

### Task 0.6 — Matcher: populate scoreMargin (TDD)

- [ ] **Red:** Add a test in `SourceMatcherTest.kt` that calls the matcher with input producing ≥2 candidates and asserts `result[0].scoreMargin != null` and equals `result[0].score - result[1].score` within ε=1e-6. Run; expect failure.
- [ ] **Green:** In `SourceMatcher.kt`, locate the function that constructs the result list (`match()` or wherever `List<SourceCandidate>` is finalized). After sorting by score, assign rank-1's `scoreMargin = sortedScores[0] - sortedScores[1]` (only when `sortedScores.size >= 2`). Run; expect pass.
- [ ] Add a second test: 1 candidate → `scoreMargin == null`; 0 candidates → empty list. Run; expect pass.
- [ ] Run full module: `./gradlew :fixthis-compose-core:test`. Expect all pass.
- [ ] Commit: `fix(matcher): populate scoreMargin on rank-1 source candidate when runner-up exists`.

**Validation:** `./gradlew :fixthis-compose-core:test`
**Expected:** All tests pass; new tests included.

### Task 0.7 — Initialize CHANGELOG entry

- [ ] Add an `## [Unreleased]` entry in `CHANGELOG.md` under "Changed" with a placeholder line: `- Compact feedback handoff prompt v2 (clarity, multi-candidate, instance disambiguation) — see docs/superpowers/specs/2026-05-09-compact-handoff-prompt-v2-design.md`. Refine wording in Phase 7.

**Validation:** Visual.
**Expected:** Entry exists.

---

## Phase 1 — Kotlin renderer cosmetics

Goal: switch the v1 token shape to v2 in Kotlin only. No new information; format-only changes.

### Task 1.1 — Add `formatBox()` in FormatterExtensions (TDD)

- [ ] **Red:** Create or extend a `FormatterExtensionsTest.kt` with a test: `FixThisRect(left=28f, top=212f, right=692f, bottom=419f).formatBox()` returns `"(28.0,212.0)-(692.0,419.0) [664×207]"`. Run; expect failure.
- [ ] **Green:** Add `internal fun FixThisRect.formatBox(): String` to `FormatterExtensions.kt`. Use existing float-format for L/T/R/B (same as `formatBounds`); compute `width = (right - left).toInt()`, `height = (bottom - top).toInt()`. Concatenate. Run; expect pass.
- [ ] Add a test for negative dimensions (right < left) returning `[0×0]` size suffix (use `coerceAtLeast(0)`). Run; expect pass.
- [ ] Commit: `feat(formatter): add formatBox() with explicit (L,T)-(R,B) [W×H] shape`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*FormatterExtensions*"`
**Expected:** All pass.

### Task 1.2 — Renderer: viewport line (TDD)

- [ ] **Red:** Add a test in `CompactHandoffRendererTest.kt`: a session whose screen has `screenshot(width=720, height=1480)` produces a screen block containing `"viewport: 720×1480"` on its own line, after the `screenshot:` line. Run; expect failure.
- [ ] **Green:** In `render()`, after the `screenshot:` line, append `appendLine("viewport: ${w}×${h}")` when both width and height are non-null. Run; expect pass.
- [ ] Add a test: width or height null → no viewport line. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): emit viewport line when screenshot has dims`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"`
**Expected:** All pass.

### Task 1.3 — Renderer: activity line (TDD)

- [ ] **Red:** Test: a screen with `displayName="Home"` and `activityName="MainActivity"` produces a `"activity: MainActivity"` line. A screen with `displayName == activityName` produces no activity line. A screen with `activityName == null` produces no activity line. Run; expect failures.
- [ ] **Green:** In `render()`, after the `viewport:` line (if present), append `appendLine("activity: ${name}")` when `activityName != null && activityName != displayName`. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): emit activity line when distinct from displayName`.

**Validation:** Same as 1.2.
**Expected:** All pass.

### Task 1.4 — Renderer: screen short-id (TDD)

- [ ] **Red:** Test: a screen with `screenId="4ce1eaa3-1e20-4da0-b3be-1a5c806fa934"` is rendered as `"Screen 4ce1eaa3: <displayName>"` (first 8 hex chars). Edge: an ID shorter than 8 chars renders the whole ID. Run; expect failures.
- [ ] **Green:** In `render()`, change `appendLine("Screen ${screenId}: ...")` to `appendLine("Screen ${screenId.take(8)}: ...")`. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): truncate screen UUID to 8-char prefix in header`.

**Validation:** Same as 1.2.
**Expected:** All pass.

### Task 1.5 — Renderer: ui line replaces target line (TDD)

- [ ] **Red:** Test: an item whose `selectedNode.role="MetricCard"` and `selectedNode.testTag="comp:MetricCard:summary"` and `target.boundsInWindow=(28,212,692,419)` produces `  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,212.0)-(692.0,419.0) [664×207]`. (Two-space indent, single space between tokens, double-space before `box=`.) Run; expect failure.
- [ ] **Green:** Rename `compactTargetSummary` to `compactUiLine` (or add new function and have the call site choose). Build the line as: indent + `ui: ` + role + ` tag=` + tag + `  box=` + `formatBox()`. Fall back to `(unlabelled)` for missing tag and `Node`/`Area` for missing role (preserve v1 fallback semantics). Run; expect pass.
- [ ] Add tests for: missing testTag → `tag=(none)` (or omitted entirely — choose; record decision); missing role → `Node`/`Area`; preserve `targetRisk=overlap` suffix when item is in an overlap group.
- [ ] Commit: `feat(handoff-v2): replace target line with ui line + formatBox`.

**Validation:** Same as 1.2.
**Expected:** All pass.

### Task 1.6 — Renderer: severity prefix (TDD)

- [ ] **Red:** Test: an item with `severity=HIGH` and `comment="레드 카드"` renders as `1. [marker 1] [!] 레드 카드`. Severity `MED` and `LOW` render without prefix (no change from v1). Run; expect failure.
- [ ] **Green:** In `appendCompactItem`, prepend `"[!] "` to the title when `item.severity == AnnotationSeverityDto.HIGH`. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): prefix [!] for HIGH severity items`.

**Validation:** Same as 1.2.
**Expected:** All pass.

### Task 1.7 — Update existing CompactHandoffRendererTest assertions

- [ ] Identify v1 tests that asserted `bounds=L,T - R,B` literals or `target: Node "tag"` literals (inventoried in Task 0.2). Update each to assert v2 `box=(...)` and `ui: <role> tag=...` shapes. Run; expect all pass.
- [ ] Commit: `test(handoff-v2): update existing v1 assertions to v2 token shape`.

**Validation:** `./gradlew :fixthis-mcp:test`
**Expected:** All pass.

---

## Phase 2 — Kotlin renderer multi-candidate output

Goal: replace the single `src? ...` line with a `candidates:` block (≤3 entries, rank-1 has `margin=` and `matched=`).

### Task 2.1 — Renderer: candidates block heading (TDD)

- [ ] **Red:** Test: any item with ≥1 source candidate produces a `  candidates:` line followed by ≥1 candidate line. Items with zero candidates produce a single `  candidates:` line followed by `    ~ unknown` (preserve v1's `src? unknown` semantics under v2 token shape). Run; expect failure.
- [ ] **Green:** Replace `compactSourceLine` with `appendCandidatesBlock(item)`. Emit `appendLine("  candidates:")` then iterate up to N=3 candidates and emit `~ ` lines (next task). Empty list → emit `~ unknown`. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): replace src? with candidates: block heading`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"`
**Expected:** All pass.

### Task 2.2 — Renderer: rank-1 candidate line with margin and matched (TDD)

- [ ] **Red:** Test: rank-1 candidate with `file=src/.../HomeScreen.kt`, `line=44`, `confidence=MEDIUM`, `scoreMargin=0.30`, `matchedTerms=["testTag:summary","compTag:MetricCard"]`, `matchReasons=["selected testTag","selected testTag convention composable","nearby testTag"]` renders as:
  `    ~ src/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[testTag, compTag, nearbyTag]`
  (Three-space outer indent, `~ ` prefix, double-space between major tokens, single space between key-value words.) Run; expect failure.
- [ ] **Green:** Implement helper `formatCandidateLine(candidate, rank, computedMargin)` that:
  - emits `~ ${candidate.fileWithLine()}` then `  conf=${candidate.confidence.name.lowercase()}`;
  - if `rank == 1` and (margin from candidate or computed) is non-null, append `  margin=${"%.2f".format(margin)}`;
  - if `rank == 1`, append `  matched=[${tokens.joinToString(", ")}]` where tokens are `candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().take(4)`.
- [ ] Add a test: rank 2 and rank 3 candidates emit only `~ <file>:<line>  conf=<lvl>` (no `margin=`, no `matched=`). Run; expect pass.
- [ ] Commit: `feat(handoff-v2): rank-1 candidate line with margin and matched terms`.

**Validation:** Same as 2.1.
**Expected:** All pass.

### Task 2.3 — Renderer: cap at 3 candidates (TDD)

- [ ] **Red:** Test: an item with 5 source candidates emits exactly 3 `~ ` lines (ranks 1, 2, 3). Run; expect failure (assuming default emits all).
- [ ] **Green:** Apply `.take(MAX_CANDIDATES_RENDERED)` where the constant is defined at the top of the file as `private const val MAX_CANDIDATES_RENDERED = 3`. Run; expect pass.
- [ ] Add test: 1 candidate → 1 line; 2 candidates → 2 lines; 0 candidates → `~ unknown`.
- [ ] Commit: `feat(handoff-v2): cap candidates block at 3 entries`.

**Validation:** Same as 2.1.
**Expected:** All pass.

### Task 2.4 — Renderer: prefer wire scoreMargin, else compute (TDD)

- [ ] **Red:** Test A: candidate with non-null `scoreMargin=0.42` emits `margin=0.42` (wire value, not recomputed). Test B: candidate with `scoreMargin=null` and `score=0.95`, runner-up with `score=0.65` emits `margin=0.30` (computed from ranks). Test C: only 1 candidate, `scoreMargin=null` → no `margin=` token. Run; expect failures.
- [ ] **Green:** In the rank-1 branch, compute `effectiveMargin = candidate.scoreMargin ?: (rank1.score - rank2?.score)?.takeIf { it > 0 }`. Render `margin=` only when `effectiveMargin != null`. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): renderer fallback computes margin when wire field is null`.

**Validation:** Same as 2.1.
**Expected:** All pass.

### Task 2.5 — Renderer: candidate caution → note line (TDD)

- [ ] **Red:** Test: rank-1 candidate with `caution="treat as low-confidence"` causes a `  note: treat as low-confidence` line to appear immediately after the candidates block. Run; expect failure.
- [ ] **Green:** After the candidates block, if `rank1?.caution != null && rank1.caution.isNotBlank()`, emit `appendLine("  note: ${caution.inlineSafe()}")`. Run; expect pass.
- [ ] Add test: caution is null/blank → no note line.
- [ ] Commit: `feat(handoff-v2): emit candidate caution as note line`.

**Validation:** Same as 2.1.
**Expected:** All pass.

---

## Phase 3 — Kotlin instance grouping and collision note

Goal: derive `instance i/N` and the collision `note:` from existing `path` data.

### Task 3.1 — InstanceGroupingHelper as pure helper (TDD)

- [ ] **Red:** Create `InstanceGroupingHelperTest.kt` with tests:
  - 3 items with same `(file:line, testTag)` and distinct path leaves → returns map with all 3 itemIds, labels `(1/3, 2/3, 3/3)` ordered by `path.joinToString("/")`;
  - 2 items with same `(file:line, testTag)` but identical path → labels `(1/2, 2/2)` (still grouped — duplicate detection runs separately);
  - 1 item alone → returned map does NOT include this item;
  - mixed: 2 items in group A + 1 lone item → only group A's 2 items are in the map;
  - items missing `selectedNode` or candidates → not grouped (excluded);
  - leaderItemIds set contains the first item of each group (used by collision note placement).
  Run; expect compile or test failures.
- [ ] **Green:** Create `InstanceGroupingHelper.kt` with:
  ```kotlin
  data class InstanceLabel(val index: Int, val total: Int)
  data class InstanceGrouping(
      val labels: Map<String /*itemId*/, InstanceLabel>,
      val leaderItemIds: Set<String>,
  )
  object InstanceGroupingHelper {
      fun compute(items: List<AnnotationDto>): InstanceGrouping { /* see Appendix B in spec */ }
  }
  ```
  Implement; run; expect pass.
- [ ] Commit: `feat(handoff-v2): InstanceGroupingHelper for list-rendered widget disambiguation`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*InstanceGrouping*"`
**Expected:** All pass.

### Task 3.2 — Renderer: emit instance i/N on ui line (TDD)

- [ ] **Red:** Test: a screen with 3 items grouped into instance 1/3, 2/3, 3/3 produces ui lines ending with `  instance 1/3`, `  instance 2/3`, `  instance 3/3` (in path-leaf order). A screen with 1 lone item produces no `instance` token. Run; expect failure.
- [ ] **Green:** In `render()`, before iterating items, call `InstanceGroupingHelper.compute(itemsForScreen)`. Pass the result to `appendCompactItem`. In `appendCompactItem`, after the `box=...` token but before any `targetRisk=` suffix, append `  instance ${label.index}/${label.total}` if the item has a label. Run; expect pass.
- [ ] Commit: `feat(handoff-v2): emit instance i/N on grouped ui lines`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"`
**Expected:** All pass.

### Task 3.3 — Renderer: collision note on group leader (TDD)

- [ ] **Red:** Test: a screen with 3 items in a single instance group produces exactly ONE `  note: ... list-rendered ... disambiguate by instance index` line, attached to the FIRST item (lowest path-leaf order) in the group, immediately after the candidates block (and any caution note). The other 2 items have no extra note line. Run; expect failure.
- [ ] **Green:** Pass the `leaderItemIds` set from Phase 3.1 into `appendCompactItem`. After the candidates block + caution note, if `item.itemId in leaderItemIds`, append `appendLine("  note: ${groupSize} markers map to same call site — likely list-rendered; disambiguate by instance index")`. Wording locked here. Run; expect pass.
- [ ] Add test: items in an overlap group are NOT eligible for a collision note (overlap and collision are different signals; overlap supersedes). Run; expect pass.
- [ ] Commit: `feat(handoff-v2): collision note on instance-group leader`.

**Validation:** Same as 3.2.
**Expected:** All pass.

---

## Phase 4 — Kotlin duplicate marker detection

Goal: when two items share `(file:line, testTag, path leaves, bounds)`, surface `targetRisk=duplicate-of-marker-N` on the later item.

### Task 4.1 — DuplicateMarkerDetector as pure helper (TDD)

- [ ] **Red:** Create `DuplicateMarkerDetectorTest.kt` with tests:
  - 2 items identical in (fileLine, testTag, path, bounds) → second item maps to first item's marker number;
  - 3 items, items 1 and 3 identical, item 2 different → item 3 maps to marker 1;
  - items differing only in `bounds` → not duplicates;
  - items differing only in `path leaves` → not duplicates;
  - markers are 1-indexed and follow the global `appendCompactItem` counter.
  Run; expect compile/test failures.
- [ ] **Green:** Create `DuplicateMarkerDetector.kt`:
  ```kotlin
  object DuplicateMarkerDetector {
      data class Item(val itemId: String, val markerNumber: Int, val key: Key)
      data class Key(val fileLine: String?, val testTag: String?, val pathLeaves: List<String>, val bounds: FixThisRect)
      fun detect(items: List<Item>): Map<String /*itemId*/, Int /*refers-to marker*/> {
          // Group by Key; for groups of size>=2, map all-but-first to the first's markerNumber.
      }
  }
  ```
  Implement; run; expect pass.
- [ ] Commit: `feat(handoff-v2): DuplicateMarkerDetector helper`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*DuplicateMarker*"`
**Expected:** All pass.

### Task 4.2 — Renderer: emit targetRisk=duplicate-of-marker-N (TDD)

- [ ] **Red:** Test: a screen with item 1 and item 4 sharing identical key produces item 4's ui line ending with `; targetRisk=duplicate-of-marker-1`. Item 4 still emits its own candidates block (do not suppress). Item 1 has no risk token. Run; expect failure.
- [ ] **Green:** After the global `appendCompactItem` loop builds the marker mapping (or pass DupMarkerDetector input pre-computed before the loop with a 2-pass over items to assign marker numbers first), append `; targetRisk=duplicate-of-marker-${refMarker}` to the ui line when the detector returns a hit. Run; expect pass.
- [ ] Add test: a duplicate item that ALSO is in an instance group still gets the duplicate-of-marker token (the duplicate token takes precedence over instance label since they refer to the same call site by definition). Document precedence in spec Open Question 2 if not already locked.
- [ ] Commit: `feat(handoff-v2): emit targetRisk=duplicate-of-marker-N for true duplicates`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"`
**Expected:** All pass.

---

## Phase 5 — JS prompt.js parity

Goal: mirror Phases 1-4 in `prompt.js` so the Kotlin and JS renderers produce byte-identical source/instance/note/risk content.

### Task 5.1 — JS: viewport, activity, screen short-id (parity for Phase 1.2-1.4)

- [ ] In `prompt.js compactScreenHeader`, append `viewport: ${w}×${h}` when present and `activity: ${name}` when distinct. Truncate `screenId` to 8 chars.
- [ ] **Verification:** run the v1 prompt-parity Node harness against `session-v2.json` (created in Phase 6.1) and confirm screen header lines match Kotlin output byte-for-byte.
- [ ] Commit: `feat(handoff-v2): mirror viewport/activity/short-id in JS`.

### Task 5.2 — JS: ui line + box format (parity for Phase 1.5)

- [ ] Replace `compactTargetLine` with a `compactUiLine` that emits `  ui: <role> tag=<tag>  box=(L,T)-(R,B) [W×H]`. Reuse `formatBounds` for the (L,T,R,B) part; compute width/height in JS as integers.
- [ ] Commit: `feat(handoff-v2): mirror ui line + box format in JS`.

### Task 5.3 — JS: severity prefix (parity for Phase 1.6)

- [ ] In `compactItemLines`, prepend `[!] ` to the title when `item.severity === 'high'`.
- [ ] Commit: `feat(handoff-v2): mirror severity prefix in JS`.

### Task 5.4 — JS: candidates block + margin + matched (parity for Phase 2)

- [ ] Replace `compactSourceLine` (returning a single line) with `compactCandidatesBlock` (returning an array of lines: heading + up to 3 candidates + optional caution note). Reuse `FIXTHIS_REASON_TOKEN_MAP`.
- [ ] Implement margin fallback: prefer `candidate.scoreMargin`; else compute from rank-1 minus rank-2 score; format with `.toFixed(2)`.
- [ ] Commit: `feat(handoff-v2): mirror candidates block in JS`.

### Task 5.5 — JS: instance grouping (parity for Phase 3.1-3.2)

- [ ] Implement `computeInstanceLabels(items)` mirroring Kotlin `InstanceGroupingHelper.compute`. Return `{ labels: Map<itemId, {index,total}>, leaderItemIds: Set<itemId> }`. Same grouping key (fileLine + testTag), same path-string ordering.
- [ ] In `compactItemLines`, append `  instance i/total` to ui line when item has a label.
- [ ] Commit: `feat(handoff-v2): mirror instance grouping in JS`.

### Task 5.6 — JS: collision note (parity for Phase 3.3)

- [ ] When item is in `leaderItemIds` and not in an overlap group, append `  note: ${size} markers map to same call site — likely list-rendered; disambiguate by instance index` after the candidates block.
- [ ] Commit: `feat(handoff-v2): mirror collision note in JS`.

### Task 5.7 — JS: duplicate detection (parity for Phase 4)

- [ ] Implement `computeDuplicateMarkers(items)` mirroring Kotlin `DuplicateMarkerDetector.detect`. Same key (fileLine + testTag + pathLeaves + bounds).
- [ ] Append `; targetRisk=duplicate-of-marker-${ref}` to the ui line of duplicates.
- [ ] Commit: `feat(handoff-v2): mirror duplicate-marker risk in JS`.

### Task 5.8 — JS: regenerate bundled app.js

- [ ] Per Phase 0.5 finding: run the console asset bundling command (likely `node build-console-assets.mjs` or `npm run build:console`) and commit the regenerated `fixthis-mcp/src/main/resources/console/app.js`.
- [ ] Manual smoke: open the console in a browser, capture a real annotation, click `Copy Prompt`, verify the prompt structure matches the v2 grammar.
- [ ] Commit: `chore(handoff-v2): regenerate bundled console app.js`.

---

## Phase 6 — Tests, parity, and budget guard

Goal: lock the v2 contract with cross-language parity and a token-budget regression guard.

### Task 6.1 — Create v2 prompt-parity fixture

- [ ] Create `fixthis-mcp/src/test/resources/parity/session-v2.json`:
  - 1 screen with `screenId="abcd1234-...UUID..."`, `displayName="Home"`, `activityName="MainActivity"`, `screenshot.width=720`, `screenshot.height=1480`;
  - 4 items: items 1-3 share `(file:HomeScreen.kt:44, testTag:comp:MetricCard:summary)` with distinct path leaves; item 4 is a true duplicate of item 1 (same key, same path, same bounds);
  - rank-1 candidate has `score=0.95`, runner-up `score=0.65`, third `score=0.20` — so renderer-computed margin = 0.30;
  - one item has `severity=high` to exercise `[!]` prefix;
  - leave `scoreMargin=null` on all candidates to exercise the renderer-computed path.
- [ ] Create `fixthis-mcp/src/test/resources/parity/expected-prompt-v2.txt` with the expected output (paste from a manual Kotlin run; lock here).
- [ ] Commit: `test(handoff-v2): v2 prompt-parity fixtures`.

### Task 6.2 — Extend PromptParityTest for v2

- [ ] Add a test `kotlinAndJsCompactPromptsMatch_v2` that:
  - loads `session-v2.json`;
  - runs Kotlin renderer on the deserialized session;
  - runs Node harness on the same JSON via `vm.createContext` (re-using the v1 harness pattern);
  - splits both outputs into lines and compares the lines that are EXPECTED to be byte-stable: any line containing `~ `, `instance`, `note:`, `targetRisk=`, `viewport:`, `activity:`, `Screen `, `[!]`, `Rule:`, `Package:`, `Annotations:`, `screenshot:`, `crop:`, the literal `candidates:` heading;
  - does NOT compare lines containing `box=` (per known wire divergence in v1).
- [ ] If Node is not available on the runner, the test SKIPS (mirror v1 behavior). Record the skip mechanism if changed from v1.
- [ ] Commit: `test(handoff-v2): cross-language parity for v2 source/instance/note/risk content`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*PromptParity*"`
**Expected:** Pass on machines with Node ≥18; skipped otherwise.

### Task 6.3 — Prompt-budget regression guard

- [ ] Extend the v1 prompt-budget test (located in Phase 0.3): add an assertion that the v2 rendering of `session-v2.json` is ≤ `1.5 × v1_baseline_size_for_equivalent_4item_session`. Document baseline number in the test comment; recompute if v2 fixtures change.
- [ ] Commit: `test(handoff-v2): token budget regression guard`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*Budget*"` (or wherever the v1 test lives).
**Expected:** Pass.

### Task 6.4 — Backward-compat smoke against v1 fixture

- [ ] Add a test that runs the v2 renderer against the existing v1 parity fixture (`session.json` from v1) and asserts the output:
  - contains the v1 `Rule:` line verbatim;
  - contains a `~ ` line for every item;
  - does NOT throw on items with empty `sourceCandidates`;
  - does NOT emit `margin=` on items with only 1 candidate and null `scoreMargin`.
- [ ] Commit: `test(handoff-v2): backward-compat smoke against v1 fixture`.

**Validation:** `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"`
**Expected:** Pass.

### Task 6.5 — Full module test pass

- [ ] Run `./gradlew test`. Compare to baseline from Task 0.1. Expect: same pass count + new v2 tests, no new failures.
- [ ] If any unexpected failures appear: stop, do NOT mass-update assertions, investigate root cause and ask.
- [ ] Commit: (no commit; this is a verification gate.)

**Validation:** `./gradlew test`
**Expected:** Baseline + new tests, no unexplained failures.

---

## Phase 7 — Docs and final validation

### Task 7.1 — Update feedback-console-contract.md

- [ ] Replace the v1 token grammar section with the v2 grammar from the design spec (Appendix-style table is fine).
- [ ] Add a "v1 → v2 token migration" sub-section listing each renamed/replaced token and the rationale.
- [ ] Commit: `docs(contract): document v2 compact handoff prompt grammar`.

### Task 7.2 — Update mcp.md

- [ ] In the `SourceCandidate` field reference, update `scoreMargin` to "populated by the matcher when at least one runner-up exists; otherwise null".
- [ ] Commit: `docs(mcp): document scoreMargin population`.

### Task 7.3 — Finalize CHANGELOG entry

- [ ] Replace the placeholder Unreleased entry with a one-paragraph user-visible summary: "Compact feedback handoff prompt v2 — replaced single-line `src?` hint with a multi-candidate `candidates:` block, added `viewport:`, `activity:`, `instance i/N`, collision and duplicate-marker notes; matcher now populates `scoreMargin`. PRECISE/FULL detail modes and JSON wire format unchanged."
- [ ] Commit: `docs(changelog): record v2 compact handoff prompt`.

### Task 7.4 — Manual smoke on a real session

- [ ] Pick a real session from `.fixthis/feedback-sessions/` (not a fixture). Render it via the Kotlin renderer (e.g. through MCP tool or a one-shot test invocation), and via the JS renderer in the browser console (`Copy Prompt`). Diff the source/instance/note/risk lines.
- [ ] Read both outputs as if you were an agent: confirm that for the original `a8483865-fd81-4075` collision case (3 markers same source), the v2 prompt makes the list-rendered nature unambiguous.
- [ ] Record findings in the plan as "Smoke notes". If something is unclear, file a follow-up issue rather than expanding scope.

### Task 7.5 — Final validation gate

- [ ] Run `./gradlew test` and confirm full pass.
- [ ] Run `node --check fixthis-mcp/src/main/console/prompt.js` to confirm JS syntax.
- [ ] Run the bundled `app.js` syntax check: `node --check fixthis-mcp/src/main/resources/console/app.js`.
- [ ] If green: ready for review/merge.

---

## Validation Quick Reference

| Phase | Command | Expected |
|---|---|---|
| 0.6 | `./gradlew :fixthis-compose-core:test` | All pass + new scoreMargin tests |
| 1.x | `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRenderer*"` | All pass |
| 2.x | Same | All pass |
| 3.x | `./gradlew :fixthis-mcp:test --tests "*InstanceGrouping*" --tests "*CompactHandoffRenderer*"` | All pass |
| 4.x | `./gradlew :fixthis-mcp:test --tests "*DuplicateMarker*" --tests "*CompactHandoffRenderer*"` | All pass |
| 5.x | `node --check fixthis-mcp/src/main/console/prompt.js` then PromptParityTest | Pass |
| 6.x | `./gradlew test` | Baseline + new |
| 7.5 | `./gradlew test` + `node --check` on prompt.js and app.js | All green |

---

## Phase 0 baseline notes

- **Command:** `./gradlew test`
- **Git SHA at baseline:** `6e475198566771d64ab36c733e5bb92b55ae0011`
- **Branch:** `compact-handoff-prompt-v2-20260509-183739`
- **Total tests:** 283
- **Passing:** 281
- **Failing:** 2 (pre-existing)
- **Pre-existing failures:**
  1. `FeedbackConsoleServerTest.consoleHtmlUsesModeAwareStudioInspector` (`FeedbackConsoleServerTest.kt:629`)
  2. `FeedbackConsoleServerTest.consoleHtmlAnnotationSaveUsesCurrentSelectionPayload` (`FeedbackConsoleServerTest.kt:1905`)
- **Assessment:** Baseline is acceptable for v2 (≥281 pass, ≤2 pre-existing failures). The 2 failures are pre-existing and unrelated to v2 renderer work; they do not block this plan.

## Phase 0 inventory notes

(To be filled in during Task 0.2.)

## Smoke notes

(To be filled in during Task 7.4.)

---

## Appendix: Worked v2 example for reference

For the original collision case (4 items, 1 screen, 3 instance-grouped + 1 duplicate):

```
# FixThis Feedback Handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

- Package: `io.beyondwin.fixthis.sample`
- Feedback Items: `4`

Screen 4ce1eaa3: MainActivity
screenshot: /…/4ce1eaa3-…-full.png
viewport: 720×1480

1. [marker 1] 이건 레드
  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,212.0)-(692.0,419.0) [664×207]  instance 1/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[tag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium
    ~ src/main/java/.../StudioHeader.kt:27 conf=low
  note: 3 markers map to same call site — likely list-rendered; disambiguate by instance index

2. [marker 2] 이건 블루
  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,668.0)-(692.0,875.0) [664×207]  instance 2/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[tag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium
    ~ src/main/java/.../FixThisDemoData.kt:121 conf=low

3. [marker 3] 이건 보라
  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,440.0)-(692.0,647.0) [664×207]  instance 3/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[tag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium

4. [marker 4] (No request provided)
  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,212.0)-(692.0,419.0) [664×207]; targetRisk=duplicate-of-marker-1
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[tag, compTag, nearbyTag]
```
