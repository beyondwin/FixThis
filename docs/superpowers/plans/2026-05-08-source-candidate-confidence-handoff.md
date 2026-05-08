# Source Candidate Confidence And Handoff Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis source candidates safer, more compact, and more diagnostic. Source matching becomes margin-aware and conservative; `SourceCandidate` carries optional ranking, margin, evidence-strength, risk-flag, and caution metadata; default console (and `DetailMode.COMPACT`) Markdown switches to a compact `src? file:line conf; why=...; risk=...` token shape with a single top-level verification rule and screen-level screenshot/overlay context; persisted multi-item screens detect overlapping annotation targets and split them into separate handoff groups; existing JSON stays backward-compatible and `DetailMode.PRECISE`/`FULL` keep their existing wire format.

**Architecture:** Implementation lands in ordered, compatibility-preserving slices. Phase 0 locks the spec (constants, mappings, fixtures, test-impact inventory) without changing code. Phase 1 introduces additive types (`SourceEvidenceStrength`, `SourceCandidateRisk`, optional `SourceCandidate` fields) and proves JSON round-trip safety. Phase 2 adds the internal `EvidenceProfile`/`MarginContext`/risk-precedence helpers in `fixthis-compose-core`. Phase 3 rewires `SourceMatcher` confidence using profile + margin and emits new reason tokens. Phase 4 updates `SourceInterpretationFactory` caution generation. Phase 5 adds COMPACT-only formatter changes in `FeedbackQueueFormatter` and `FixThisMarkdownFormatter`. Phase 6 adds an overlap detector and groups same-screen items in the formatter. Phase 7 mirrors COMPACT formatter changes in `fixthis-mcp/src/main/console/prompt.js`. Phase 8 is parity, fixture, and prompt-budget tests. Phase 9 is documentation, manual smoke, and final validation.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Gradle/JUnit/JUnit-Kotlin, vanilla JS (no transpile) under `fixthis-mcp/src/main/console/`, Node `--check` for JS syntax, optional Node script execution (via `vm.createContext` in a controlled test harness) for JS/Kotlin parity tests.

---

## Source Documents

- Design spec: `docs/superpowers/specs/2026-05-08-source-candidate-confidence-handoff-design.md` (this plan's design input). If the spec file does not yet exist in the repository, the design is also reproduced in full in this plan's "Design Reference" appendix below.
- Related implementation plan: `docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md`
- Related design: `docs/superpowers/specs/2026-05-08-project-improvement-stabilization-design.md`
- Stable target evidence: `docs/superpowers/plans/2026-05-07-stable-target-evidence-v1-implementation.md`
- Console UX: `docs/design-feedback-console-ux.md`

## Execution Rules

- Use a new implementation branch or worktree, preferably `codex/source-candidate-confidence-handoff`.
- Keep each task independently reviewable and commit after each task.
- Follow strict TDD: write the failing test first, run it and confirm the failure, implement, run again and confirm pass, only then commit.
- Each step targets 2-5 minutes of focused work. If a step grows, split it.
- Update the checkbox state in this file as tasks complete.
- After each task, run the listed validation command and record `PASS`, `FAIL`, or `SKIPPED` with the reason in your task notes.
- Preserve unrelated local changes. If a target file has uncommitted edits unrelated to this plan, read those changes first and preserve them.
- Do not run broad process cleanup (no `killall node`, `pkill node`).
- Do not modify user app code in `sample/src/main` to add `Modifier.testTag(...)`.
- Do not change Markdown wire format for `DetailMode.PRECISE` or `DetailMode.FULL`. Only `DetailMode.COMPACT` and the JS console prompt change.
- All confidence tokens emitted in any Markdown surface MUST be lowercase (`high`, `medium`, `low`).

## Current Baseline

- Branch: `main`. Recent commit at plan creation: `66b7ee6 fix: keep saved annotations tied to sessions`.
- `SourceCandidate` is in `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt` and has the fields documented in the spec; it has no risk/margin/evidence-strength fields yet.
- `SourceMatcher` (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`) currently classifies confidence purely from `rawScore` thresholds (`HIGH >= 100.0`, `MEDIUM >= 55.0`, else `LOW`).
- `SourceInterpretationFactory` (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`) emits a fixed caution string only for `LOW`/`NONE`.
- `FeedbackQueueFormatter.toMarkdown` (`fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`) emits `Likely Source:` plus `matched:` and `reasons:` sub-bullets in all `DetailMode` values.
- `FixThisMarkdownFormatter` (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt`) emits `## Top Source` (COMPACT) / `## Source Candidates` (PRECISE) / `## Source candidates` (FULL).
- Console JS prompt (`fixthis-mcp/src/main/console/prompt.js`) emits `'   Likely Source:'` followed by `promptLikelySources(...)`.
- Existing test files asserting `SelectionConfidence.HIGH`/`MEDIUM` (full inventory locked in Phase 0):
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt`
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt`
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt`
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/selection/NodeSelectorTest.kt` (reads `SelectionInfo.confidence`, NOT `SourceCandidate.confidence`; out of scope for this plan)
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

## Execution Order Preamble

The original 1-9 phase shape is preserved, but **definitions come before call sites**:
- Phase 0 locks the spec.
- Phase 1 adds the additive types and the JSON round-trip test (no behavior).
- Phase 2 adds pure helpers (no matcher wiring).
- Phase 3 wires `SourceMatcher` to use those helpers and produces the new behavior.
- Phase 7 follows the same rule: Step 7.1 adds the JS overlap helpers as dead code first; Step 7.2 adds the orchestration that calls them.

Phase 1 tests must use only fields/enums introduced inside Phase 1 itself plus those that already exist; they MUST NOT reference helpers added in Phase 2. Phase 7 step 7.2's prompt code must NOT be committed before step 7.1's helpers (otherwise the bundle throws at runtime).

### Known wire divergence between Kotlin and JS

The Kotlin `FixThisRect.formatBounds()` emits floats with a `.0` suffix (`10.0,20.0,110.0,70.0`). The JS `formatBounds(b)` concatenates `b.left + ','` and prints integers as integers when the input JSON encodes integers. The parity test in Phase 8 only compares the `src?` lines because of this. The `target:` line bounds-format difference is documented (here and in `docs/feedback-console-contract.md`) as a known per-runtime stylization, not a contract divergence: callers who need byte-stable bounds should consume JSON, not the compact Markdown.

---

## File Structure

### Modify (Kotlin sources)

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt` - extend `SourceCandidate` with optional `ranking`, `scoreMargin`, `evidenceStrength`, `riskFlags`, `caution` fields.
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` - add `EvidenceProfile`/`MarginContext`, classify confidence by profile + margin, emit new reason tokens (`selected stringResource`, `arbitrary literal`, `legacy fallback`), populate new optional candidate fields.
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt` - generate caution from precedence-resolved risk flag + confidence.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt` - add COMPACT-only `appendCompactLikelySource` and screen-level grouping (overlay/screenshot reference, top-level verification rule, overlap groups).
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` - COMPACT-only switch to `src?` token line; PRECISE/FULL untouched.
- `fixthis-mcp/src/main/console/prompt.js` - mirror COMPACT formatter behavior (`src?`, `why=`, `risk=`, top-level rule, overlap groups, lowercase confidence).
- `fixthis-mcp/src/main/resources/console/app.js` - bundled equivalent regenerated from the source files in `fixthis-mcp/src/main/console/`. (Inspect to confirm whether `prompt.js` is concatenated or if `app.js` is hand-edited; mirror changes in both if hand-edited.)

### Create (Kotlin sources)

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceEvidenceStrength.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt` (internal)
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/MarginContext.kt` (internal)
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationOverlapDetector.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`

### Modify or create (tests)

- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt` (create) - JSON round-trip for old and new fields.
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` (modify) - update existing assertions and add ambiguity/text-only/nearby-only/activity-only/literal-only/strict-comp tests.
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt` (modify) - update caution behavior tests.
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedenceTest.kt` (create).
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/EvidenceProfileTest.kt` (create).
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/MarginContextTest.kt` (create).
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt` (modify) - keep PRECISE/FULL assertions, add COMPACT `src?` assertions.
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt` (modify) - update HIGH-confidence fixtures so they still classify HIGH under new rules (single-candidate strict-comp evidence).
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt` (modify) - keep PRECISE assertions; add COMPACT compact-token tests, screen-level overlay test, prompt-size budget test.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationOverlapDetectorTest.kt` (create).
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` (create).
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/PromptParityTest.kt` (create) - JS/Kotlin parity test driven by Node, gated on Node availability.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` (modify, optional) - confirm sessions deserialize when JSON contains old `SourceCandidate` shape (no behavioral change beyond confirming round-trip).

### Create (test resources)

- `fixthis-mcp/src/test/resources/parity/session.json` - canonical prompt-parity fixture session.
- `fixthis-mcp/src/test/resources/parity/expected-prompt.txt` - canonical expected console prompt output.
- `fixthis-mcp/src/test/resources/parity/run-prompt.js` - small Node harness that loads `session.json`, evaluates `prompt.js` definitions inside `vm.createContext` (controlled test sandbox; checked-in source only), calls `currentAnnotationsPromptCompact(session.items)`, and prints output to stdout.
- `fixthis-mcp/src/test/resources/legacy/source-candidate-v1.json` - legacy persisted candidate JSON used by the round-trip test.

### Modify (docs)

- `docs/feedback-console-contract.md` - document compact handoff schema (token mapping, top-level rule, overlap policy).
- `docs/mcp.md` - document new `SourceCandidate` optional fields and confidence rules.
- `CHANGELOG.md` - record the user-visible changes.

---

## Phase 0 - Spec Lockdown And Impact Inventory (no code changes)

Phase 0 is mandatory. It produces the constants, tables, and test-impact list every later phase reads. **No production source files are edited in Phase 0.** Output of Phase 0 is appended to this plan document under "Phase 0 Outputs", or saved into `docs/superpowers/specs/2026-05-08-source-candidate-confidence-handoff-design.md` if that file is created.

### Step 0.1 - Lock numeric constants and gap policy

- [ ] Append the following constants block under "Phase 0 Outputs" in this file:

```
SourceMatcher confidence constants (final, locked):
- CLEAR_MARGIN = 0.20            // strict separation required for HIGH eligibility
- CLOSE_RACE_MARGIN = 0.15       // below this, attach AMBIGUOUS risk and downgrade one level
- AMBIGUITY_GAP_POLICY: scoreMargin in [0.15, 0.20) is the "MEDIUM ceiling" zone:
    confidence is capped at MEDIUM, no AMBIGUOUS risk is flagged, no caution from margin.
- scoreMargin formula: (topScore - nextScore) / max(topScore, 0.001)
- Single-candidate result: scoreMargin = 1.0
- Empty result list: no candidate emitted, no margin
- Score fed into the formula is the post-normalization candidate score in [0.0, 1.0]
  (i.e., SourceCandidate.score = rawScore / HIGH_CONFIDENCE_SCORE coerced to [0,1]).
- SourceCandidate.score is unchanged from today's normalization. The new fields are additive.
```

Validation: `git diff --check` produces no errors; nothing else changes.

Commit message: `docs(plan): lock source candidate confidence constants`.

### Step 0.2 - Lock evidence classification table

- [ ] Append the table below under "Phase 0 Outputs":

| Reason string emitted by matcher | Signal kind / origin | Strength |
| --- | --- | --- |
| `selected testTag convention composable` (parsed `comp:<Composable>:<variant>` testTag matched against composable symbol) | from `STRICT_COMP_TEST_TAG` or `COMPOSABLE_SYMBOL` typed signal, OR legacy `symbols`/`file` fallback | `STRONG` |
| `selected testTag` (selected testTag value matches a typed `STRICT_COMP_TEST_TAG` or `TEST_TAG` signal) | from typed `STRICT_COMP_TEST_TAG`/`TEST_TAG` signal | `STRONG` |
| `selected testTag` (selected testTag value matched only via legacy `testTags` list with no typed signal) | legacy fallback | `WEAK` (counted under `legacy fallback`) |
| `selected text` matched via `UI_TEXT` typed signal | typed `UI_TEXT` | `MEDIUM` |
| `selected text` matched via `STRING_RESOURCE` typed signal | typed `STRING_RESOURCE` | `MEDIUM` (also emit `selected stringResource` reason; see step 0.5) |
| `selected text` matched only via `ARBITRARY_STRING_LITERAL` | typed `ARBITRARY_STRING_LITERAL` | `WEAK` (also emit `arbitrary literal` reason; see step 0.5) |
| `selected text` matched only via legacy text/excerpt fallback | legacy | `WEAK` (also emit `legacy fallback` reason) |
| `selected contentDescription` matched via `CONTENT_DESCRIPTION` or `STRING_RESOURCE` typed signal | typed | `MEDIUM` |
| `selected contentDescription` matched via `ARBITRARY_STRING_LITERAL` | typed literal | `WEAK` |
| `selected role` (combined with at least one other selected typed signal) | typed `ROLE` | `MEDIUM` |
| `selected role` (only signal present from selected node) | typed `ROLE` | `WEAK` |
| `nearby text` / `nearby contentDescription` / `nearby testTag` / `nearby role` | any kind | `WEAK` |
| `activity` | typed `ACTIVITY_NAME` or legacy | `WEAK` |
| `selected stringResource` (new reason, emitted when a typed `STRING_RESOURCE` signal carried the selected term) | typed `STRING_RESOURCE` | `MEDIUM` |
| `arbitrary literal` (new reason, emitted when only `ARBITRARY_STRING_LITERAL` matched) | typed `ARBITRARY_STRING_LITERAL` | `WEAK` |
| `legacy fallback` (new reason, emitted when only legacy non-signal lists matched) | legacy `text`/`testTags`/`symbols`/`excerpt`/etc. | `WEAK` |

Consolidation rule (review finding #8): when both `selected testTag` (literal) and `selected testTag convention composable` fire on the same candidate, the candidate has `STRONG` evidence once. Never double-count for confidence classification (the existing `score = max(...)` clamp already prevents score double-counting; this rule extends to evidence counting).

Validation: `git diff --check`. Commit: `docs(plan): lock evidence classification table`.

### Step 0.3 - Lock confidence rules

- [ ] Append:

```
Confidence classification rules (post-margin, per candidate):

1. Compute EvidenceProfile from the candidate's matched reason set.
2. Compute scoreMargin from sorted candidates (single -> 1.0, empty -> n/a).
3. Determine baseConfidence:
   - HIGH if (strongEvidenceCount >= 1 AND scoreMargin >= CLEAR_MARGIN)
       OR (mediumEvidenceCount >= 2 AND mediumEvidenceCount counts >= 2 distinct
           selected medium evidence kinds AND scoreMargin >= CLEAR_MARGIN).
   - MEDIUM if (selected UI text OR selected contentDescription) AND another
       selected signal (any strength), regardless of margin (subject to caps).
   - MEDIUM if strong evidence with scoreMargin in [CLOSE_RACE_MARGIN, CLEAR_MARGIN).
   - LOW otherwise (provided rawScore > 0).
   - NONE if no evidence and rawScore == 0.
4. Apply caps (lower bound only; never upgrade):
   - text-only selected match (only "selected text" fired) -> max MEDIUM (TEXT_ONLY).
   - nearby-only match (no selected reason fired, only nearby reasons) -> max LOW (NEARBY_ONLY).
   - activity-only match (only "activity" reason fired) -> max LOW (ACTIVITY_ONLY).
   - arbitrary literal-only match (only "arbitrary literal" fired) -> max LOW (ARBITRARY_LITERAL).
   - legacy-only match (only "legacy fallback" fired) -> max LOW (LEGACY_FALLBACK).
   - visual area selection (passed in by caller) -> max LOW (AREA_SELECTION).
5. Apply ambiguity downgrade:
   - if scoreMargin < CLOSE_RACE_MARGIN AND candidate count >= 2:
     downgrade by one level (HIGH -> MEDIUM, MEDIUM -> LOW, LOW unchanged) and
     attach AMBIGUOUS risk.
   - if CLOSE_RACE_MARGIN <= scoreMargin < CLEAR_MARGIN AND baseConfidence == HIGH:
     cap to MEDIUM. No AMBIGUOUS risk attached.
6. Risk flags accumulate in this iteration order (deterministic):
   AMBIGUOUS, AREA_SELECTION, TEXT_ONLY, NEARBY_ONLY, ARBITRARY_LITERAL,
   ACTIVITY_ONLY, LEGACY_FALLBACK.
7. Final confidence is determined after both caps and ambiguity downgrade.
   The resulting evidenceStrength is STRONG if hasStrong, MEDIUM if hasMedium
   else WEAK (unaffected by margin/caps).
```

Validation: `git diff --check`. Commit: `docs(plan): lock confidence classification rules`.

### Step 0.4 - Lock risk-precedence and caution generator

- [ ] Append:

```
Risk precedence (highest first):
  AMBIGUOUS > AREA_SELECTION > TEXT_ONLY > NEARBY_ONLY > ARBITRARY_LITERAL >
  ACTIVITY_ONLY > LEGACY_FALLBACK

Caution generator (used by SourceInterpretationFactory):

Inputs: confidence, riskFlags (already ordered by precedence).

If riskFlags is empty and confidence in {HIGH, MEDIUM}: caution = null.
If riskFlags is empty and confidence == LOW: caution =
    "Top source candidate has low confidence; verify before editing."
If riskFlags is empty and confidence == NONE: caution =
    "No source candidate was available from current evidence."

Otherwise pick the highest-precedence flag and emit:
  AMBIGUOUS         -> "Verify this source candidate before editing; top candidates are close."
  AREA_SELECTION    -> "Visual-area selection; use screenshot and bounds before editing."
  TEXT_ONLY         -> "Text-only match; confirm against screenshot and code."
  NEARBY_ONLY       -> "Nearby-only match; confirm against screenshot and code."
  ARBITRARY_LITERAL -> "Match relied on a generic string literal; confirm against screenshot and code."
  ACTIVITY_ONLY     -> "Activity-only match; confirm against screenshot and code."
  LEGACY_FALLBACK   -> "Legacy-fallback match; confirm against screenshot and code."

Caution is also written to SourceCandidate.caution (top candidate only; non-top
candidates may set caution from their own flags).
```

Validation: `git diff --check`. Commit: `docs(plan): lock caution and risk precedence`.

### Step 0.5 - Lock new reason-token emissions in matcher

- [ ] Append:

```
Reason emission additions (Phase 3 will implement):

1. When the matched signal kind is STRING_RESOURCE and the originating reason
   bucket would be "selected text" or "selected contentDescription", also add
   "selected stringResource" to matchReasons.
2. When ALL the matched terms came only via ARBITRARY_STRING_LITERAL signals
   (no UI_TEXT / CONTENT_DESCRIPTION / TEST_TAG / STRICT_COMP_TEST_TAG /
   ROLE / ACTIVITY_NAME / COMPOSABLE_SYMBOL signals fired), append
   "arbitrary literal" exactly once.
3. When ALL the candidate's matched terms came only via the legacy fallback
   (signal-less candidates list), append "legacy fallback" exactly once.

These are derived after scoring; they do not change rawScore. Their reason
strings are stable identifiers used by the formatter mapping.
```

Reason-token mapping table (Phase 5/7 will use it):

| Reason string | Token |
| --- | --- |
| `selected text` | `text` |
| `selected contentDescription` | `contentDescription` |
| `selected testTag` | `tag` |
| `selected testTag convention composable` | `compTag` |
| `selected role` | `role` |
| `nearby text` | `nearbyText` |
| `nearby contentDescription` | `nearbyContentDescription` |
| `nearby testTag` | `nearbyTag` |
| `nearby role` | `nearbyRole` |
| `activity` | `activity` |
| `selected stringResource` | `stringRes` |
| `arbitrary literal` | `literal` |
| `legacy fallback` | `legacy` |

Validation: `git diff --check`. Commit: `docs(plan): lock reason emissions and token mapping`.

### Step 0.6 - Lock JSON parity-fixture and parity-runner approach

- [ ] Append:

```
Parity fixtures (Phase 8 will create):

- fixthis-mcp/src/test/resources/parity/session.json
- fixthis-mcp/src/test/resources/parity/expected-prompt.txt
- fixthis-mcp/src/test/resources/parity/run-prompt.js (Node harness)

Parity runner approach: PromptParityTest spawns
`node parity/run-prompt.js parity/session.json` from a Gradle test, captures
stdout, normalizes line endings, and compares against expected-prompt.txt. The
Node harness loads `prompt.js` source as a string, then evaluates the script
inside a fresh `vm.createContext({ ... })` sandbox with a controlled set of
helper stubs (no DOM, no network). This is a checked-in test fixture only;
production code never executes prompt.js this way.

The Kotlin formatter is exercised in the same test by deserializing
parity/session.json into a SessionDto, calling
FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT), and asserting
that the COMPACT Markdown contains the same set of compact-token lines (`src?`,
`why=`, `risk=`) the JS prompt produces, after a normalizer that strips Markdown
headers and per-screen verification rule lines.

If `node` is not on PATH, the test marks itself SKIPPED with a clear reason
("node not available; install Node 18+ to run parity test"). It does NOT fail.
```

Validation: `git diff --check`. Commit: `docs(plan): lock parity fixture approach`.

### Step 0.7 - Lock overlap detector coordinate space

- [ ] Append:

```
Overlap detector coordinate space:

- All bounds passed to AnnotationOverlapDetector are FixThisRect values from
  AnnotationDto.target (Node.boundsInWindow or Area.boundsInWindow). Compose
  capture pipeline already normalizes these to window coordinates in pixels.
- IoSA (intersection-over-smaller-area) is dimensionless. Threshold 0.25 is
  unit-independent and applies directly.
- For the visual-area-vs-visual-area case, ANY non-zero intersection counts
  (the threshold is effectively 0.0 IoSA for that case).
- For the center-distance fallback (24dp), the detector accepts an optional
  density parameter (Float, default 1.0). The threshold is computed as
  24f * density window-pixels. Sessions persisted today do not record density,
  so the default-1.0 path is used; FixThis docs add a note that this is
  conservative for high-density screens (more overlaps reported, fewer missed).
- A future migration may capture density alongside bounds; until then the
  default is documented in CHANGELOG.md and docs/feedback-console-contract.md.
```

Validation: `git diff --check`. Commit: `docs(plan): lock overlap coordinate policy`.

### Step 0.8 - Lock test-impact inventory

- [ ] Append a precise table of every test-file location with `SelectionConfidence.HIGH` or `SelectionConfidence.MEDIUM` and the new expected behavior:

| File | Line | Current expectation | New expectation under Phase 3 rules | Justification |
| --- | --- | --- | --- | --- |
| `SourceMatcherTest.kt` | 51 | `SelectionConfidence.HIGH` for top candidate | `HIGH` retained | Selected text + testTag (literal STRONG) + role + activity vs second candidate (text + activity only). Normalized topScore approx 0.9, nextScore approx 0.4, scoreMargin > 0.5 >= CLEAR_MARGIN. Strong evidence + clear margin -> HIGH. |
| `SourceMatcherTest.kt` | 208 | `SelectionConfidence.MEDIUM` at score 0.65 | `HIGH` (intentional update) | Selected `comp:` testTag with strict-comp-style match against single candidate (margin = 1.0). Strong evidence + single candidate -> HIGH. Test must update. |
| `SourceInterpretationFactoryTest.kt` | 20-27 | Caution null for HIGH | Caution null retained | No risk flags on construction; HIGH+empty flags -> caution null. |
| `SourceInterpretationFactoryTest.kt` | 80-90 | Default fixture HIGH | Caution null retained | Same as above. |
| `TargetEvidenceModelTest.kt` | 95, 127, 147 | HIGH source confidence | Retained where the fixture exposes strong evidence and a single candidate; downgrade to MEDIUM if the fixture has multiple close candidates and the new rules require it. Verify each fixture; default to MEDIUM if uncertain and update the assertion. | New rules can downgrade. |
| `FixThisMarkdownFormatterTest.kt` | 79, 121, 326, 374, 447 | HIGH visible in PRECISE/FULL Markdown | PRECISE/FULL Markdown unchanged because tokens still come from `confidence.name.lowercase()` | Wire format change is COMPACT-only. |
| `FixThisMarkdownFormatterTest.kt` | 334, 342, 399, 422 | MEDIUM visible | Same | Same. |
| `FeedbackQueueFormatterTest.kt` | 62 | HIGH single source candidate, asserts `Likely Source:` block | Default mode is PRECISE; PRECISE keeps `Likely Source:` block. No change. | Default mode locked to PRECISE. |
| `FeedbackQueueFormatterTest.kt` | 180 | HIGH on visual-area candidate, output `low confidence` | Same. Visual-area cap unchanged at the formatter layer. | Existing area cap preserved by formatter. |
| `FeedbackQueueFormatterTest.kt` | 325 | HIGH single source candidate, asserts no internal IDs | Same. | PRECISE format unchanged. |
| `FeedbackQueueFormatterTest.kt` | 553, 561 | HIGH/MEDIUM in PRECISE/FULL | Same. | PRECISE/FULL unchanged. |
| `FeedbackSessionStoreTest.kt` | 659 | HIGH on persisted candidate | Same. Backward-compatible deserialization (Phase 1) preserves field. | No behavior change in store. |

(The plan does not edit `NodeSelectorTest.kt:44`; that test asserts `SelectionInfo.confidence`, not `SourceCandidate.confidence`, and is out of scope.)

Validation: `git diff --check`. Commit: `docs(plan): lock test-impact inventory`.

### Step 0.9 - Phase 0 sign-off

- [ ] Read all eight Phase 0 outputs end-to-end. Confirm there are no contradictions and no missing values for any field referenced in Phases 1-9. If a contradiction exists, fix it now in Phase 0.

Validation: visual review only.

---

## Phase 1 - Additive Types And JSON Round-Trip

Goal: introduce `SourceEvidenceStrength` and `SourceCandidateRisk` enums and extend `SourceCandidate` with five optional fields, all with safe defaults. No behavior change yet.

### Step 1.1 - Add `SourceCandidateSerializationTest` (TDD; will fail until 1.4)

- [ ] Locate the canonical `Json` instance used by the project. Check `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/` and `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/`. The existing import `io.beyondwin.fixthis.cli.fixThisJson` (used in `FeedbackQueueFormatter.kt`) is the project-wide instance. Use it from the test.

- [ ] Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCandidateSerializationTest {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun deserializesLegacyJsonWithoutNewFields() {
        val legacy = """
            {
              "file": "Foo.kt",
              "line": 12,
              "score": 0.5,
              "matchedTerms": ["Pay now"],
              "matchReasons": ["selected text"],
              "confidence": "MEDIUM"
            }
        """.trimIndent()

        val candidate = json.decodeFromString(SourceCandidate.serializer(), legacy)

        assertEquals("Foo.kt", candidate.file)
        assertEquals(12, candidate.line)
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertEquals(null, candidate.ranking)
        assertEquals(null, candidate.scoreMargin)
        assertEquals(null, candidate.evidenceStrength)
        assertTrue(candidate.riskFlags.isEmpty())
        assertEquals(null, candidate.caution)
    }

    @Test
    fun serializesNewFieldsWhenPresentAndOmitsDefaults() {
        val candidate = SourceCandidate(
            file = "Bar.kt",
            line = 7,
            score = 0.8,
            matchedTerms = listOf("Pay now"),
            matchReasons = listOf("selected text"),
            confidence = SelectionConfidence.MEDIUM,
            ranking = 1,
            scoreMargin = 0.42,
            evidenceStrength = SourceEvidenceStrength.MEDIUM,
            riskFlags = listOf(SourceCandidateRisk.TEXT_ONLY),
            caution = "Text-only match; confirm against screenshot and code.",
        )

        val text = json.encodeToString(SourceCandidate.serializer(), candidate)
        val round = json.decodeFromString(SourceCandidate.serializer(), text)

        assertEquals(candidate, round)
        assertTrue(text.contains("\"ranking\""))
        assertTrue(text.contains("\"riskFlags\""))
    }

    @Test
    fun roundTripsLegacyToCurrent() {
        val legacy = """
            {
              "file": "Foo.kt",
              "score": 0.3,
              "matchReasons": ["selected text"],
              "confidence": "LOW"
            }
        """.trimIndent()

        val candidate = json.decodeFromString(SourceCandidate.serializer(), legacy)
        val reSerialized = json.encodeToString(SourceCandidate.serializer(), candidate)
        val round = json.decodeFromString(SourceCandidate.serializer(), reSerialized)

        assertNotNull(round)
        assertEquals(SelectionConfidence.LOW, round.confidence)
        assertTrue(round.riskFlags.isEmpty())
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*"`. Confirm compilation failure (`SourceEvidenceStrength`, `SourceCandidateRisk`, and the new `SourceCandidate` fields do not exist yet).

Commit: `test(compose-core): assert SourceCandidate JSON round-trip safety`.

### Step 1.2 - Add `SourceEvidenceStrength` enum

- [ ] Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceEvidenceStrength.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceEvidenceStrength {
    STRONG,
    MEDIUM,
    WEAK,
}
```

Validation: `./gradlew :fixthis-compose-core:compileKotlin`. Should compile. Round-trip test still fails (other types missing).

Commit: `feat(compose-core): add SourceEvidenceStrength enum`.

### Step 1.3 - Add `SourceCandidateRisk` enum

- [ ] Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceCandidateRisk {
    AMBIGUOUS,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,
    LEGACY_FALLBACK,
}
```

Validation: `./gradlew :fixthis-compose-core:compileKotlin`. Should compile.

Commit: `feat(compose-core): add SourceCandidateRisk enum`.

### Step 1.4 - Extend `SourceCandidate` with optional fields

- [ ] Edit `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`. Replace the existing `SourceCandidate` data class with:

```kotlin
@Serializable
data class SourceCandidate(
    val file: String,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SelectionConfidence,
    val ranking: Int? = null,
    val scoreMargin: Double? = null,
    val evidenceStrength: SourceEvidenceStrength? = null,
    val riskFlags: List<SourceCandidateRisk> = emptyList(),
    val caution: String? = null,
)
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*"`. All three tests should pass.

- [ ] Run the broader test suite to confirm no incidental breakage: `./gradlew :fixthis-compose-core:test :fixthis-mcp:test`. Confirm green. Existing `SourceCandidate` constructions without the new arguments still work because all five new fields have defaults.

Commit: `feat(compose-core): extend SourceCandidate with optional ranking/margin/risk metadata`.

### Step 1.5 - Add legacy persisted fixture round-trip test

- [ ] Open `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` and confirm the discriminator value used by `AnnotationTargetDto.Area`. The current value is `@SerialName("visual_area")`. Use this exact string in the fixture below.

- [ ] Create `fixthis-mcp/src/test/resources/legacy/source-candidate-v1.json` (an annotation list element shape persisted prior to this work, with no new optional fields):

```json
{
  "schemaVersion": "1.0",
  "sessionId": "session-legacy-1",
  "packageName": "io.beyondwin.fixthis.sample",
  "projectRoot": "/repo",
  "createdAtEpochMillis": 1,
  "updatedAtEpochMillis": 2,
  "items": [
    {
      "itemId": "item-1",
      "screenId": "screen-1",
      "createdAtEpochMillis": 2,
      "updatedAtEpochMillis": 2,
      "target": { "type": "visual_area", "boundsInWindow": { "left": 0.0, "top": 0.0, "right": 10.0, "bottom": 10.0 } },
      "sourceCandidates": [
        {
          "file": "Foo.kt",
          "line": 12,
          "score": 0.5,
          "matchedTerms": ["Pay now"],
          "matchReasons": ["selected text"],
          "confidence": "MEDIUM"
        }
      ],
      "comment": "legacy"
    }
  ]
}
```

- [ ] Add a new test to `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`:

```kotlin
@Test
fun deserializesLegacySessionJsonWithoutNewSourceCandidateFields() {
    val raw = javaClass.classLoader.getResource("legacy/source-candidate-v1.json")!!.readText()
    val session = fixThisJson.decodeFromString(SessionDto.serializer(), raw)
    val candidate = session.items.single().sourceCandidates.single()

    assertEquals("Foo.kt", candidate.file)
    assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
    assertTrue(candidate.riskFlags.isEmpty())
    assertEquals(null, candidate.scoreMargin)
}
```

Add the `import` lines required at the top of the file (`io.beyondwin.fixthis.cli.fixThisJson`, `io.beyondwin.fixthis.compose.core.model.SelectionConfidence`, plus `kotlin.test.assertTrue`/`assertEquals`).

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackSessionStoreTest.deserializesLegacySessionJsonWithoutNewSourceCandidateFields*"`. Confirm pass.

Commit: `test(mcp): pin legacy session JSON deserialization for new SourceCandidate fields`.

---

## Phase 2 - Pure Helpers (`EvidenceProfile`, `MarginContext`, risk precedence)

Goal: introduce internal-only helpers as plain Kotlin data classes/functions. No behavior wired into the matcher yet.

### Step 2.1 - `SourceCandidateRiskPrecedence` (TDD)

- [ ] Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedenceTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceCandidateRiskPrecedenceTest {
    @Test
    fun pickHighestPrecedenceFlag() {
        val flags = listOf(
            SourceCandidateRisk.TEXT_ONLY,
            SourceCandidateRisk.AMBIGUOUS,
            SourceCandidateRisk.LEGACY_FALLBACK,
        )

        assertEquals(
            SourceCandidateRisk.AMBIGUOUS,
            SourceCandidateRiskPrecedence.highest(flags),
        )
    }

    @Test
    fun emptyFlagsReturnsNull() {
        assertNull(SourceCandidateRiskPrecedence.highest(emptyList()))
    }

    @Test
    fun precedenceOrderIsAmbiguousAreaTextNearbyLiteralActivityLegacy() {
        assertEquals(
            listOf(
                SourceCandidateRisk.AMBIGUOUS,
                SourceCandidateRisk.AREA_SELECTION,
                SourceCandidateRisk.TEXT_ONLY,
                SourceCandidateRisk.NEARBY_ONLY,
                SourceCandidateRisk.ARBITRARY_LITERAL,
                SourceCandidateRisk.ACTIVITY_ONLY,
                SourceCandidateRisk.LEGACY_FALLBACK,
            ),
            SourceCandidateRiskPrecedence.orderedHighestFirst,
        )
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateRiskPrecedenceTest*"`. Confirm compilation failure.

- [ ] Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

object SourceCandidateRiskPrecedence {
    val orderedHighestFirst: List<SourceCandidateRisk> = listOf(
        SourceCandidateRisk.AMBIGUOUS,
        SourceCandidateRisk.AREA_SELECTION,
        SourceCandidateRisk.TEXT_ONLY,
        SourceCandidateRisk.NEARBY_ONLY,
        SourceCandidateRisk.ARBITRARY_LITERAL,
        SourceCandidateRisk.ACTIVITY_ONLY,
        SourceCandidateRisk.LEGACY_FALLBACK,
    )

    fun highest(flags: Collection<SourceCandidateRisk>): SourceCandidateRisk? {
        if (flags.isEmpty()) return null
        val present = flags.toSet()
        return orderedHighestFirst.firstOrNull { it in present }
    }

    fun ordered(flags: Collection<SourceCandidateRisk>): List<SourceCandidateRisk> {
        if (flags.isEmpty()) return emptyList()
        val present = flags.toSet()
        return orderedHighestFirst.filter { it in present }
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateRiskPrecedenceTest*"`. Confirm pass.

Commit: `feat(compose-core): add SourceCandidateRiskPrecedence helper`.

### Step 2.2 - `EvidenceProfile` (TDD)

- [ ] Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/EvidenceProfileTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceProfileTest {
    @Test
    fun strictCompTagAndSelectedTagAreStrong() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf(
                "selected testTag",
                "selected testTag convention composable",
                "activity",
            ),
            rawScore = 120.0,
        )

        assertEquals(SourceEvidenceStrength.STRONG, profile.strength())
        assertTrue(profile.hasStrictCompTag)
        assertTrue(profile.hasSelectedTestTag)
        assertEquals(1, profile.selectedStrongCount)
    }

    @Test
    fun selectedTextOnlyIsTextOnlyAndMedium() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf("selected text"),
            rawScore = 45.0,
        )

        assertEquals(SourceEvidenceStrength.MEDIUM, profile.strength())
        assertTrue(profile.isTextOnly)
        assertFalse(profile.isNearbyOnly)
    }

    @Test
    fun nearbyOnlyAndActivityOnlyDetected() {
        val nearby = EvidenceProfile.fromReasons(
            reasons = listOf("nearby text", "nearby role"),
            rawScore = 30.0,
        )
        val activity = EvidenceProfile.fromReasons(
            reasons = listOf("activity"),
            rawScore = 15.0,
        )

        assertTrue(nearby.isNearbyOnly)
        assertEquals(SourceEvidenceStrength.WEAK, nearby.strength())
        assertTrue(activity.isActivityOnly)
        assertEquals(SourceEvidenceStrength.WEAK, activity.strength())
    }

    @Test
    fun arbitraryLiteralAndLegacyOnlyDetected() {
        // Realistic: the matcher always emits a bucket reason ("selected text"
        // / "selected contentDescription") alongside the origin marker.
        val literal = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "arbitrary literal"),
            rawScore = 12.0,
        )
        val legacy = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "legacy fallback"),
            rawScore = 5.0,
        )

        assertTrue(literal.isArbitraryLiteralOnly)
        assertEquals(SourceEvidenceStrength.MEDIUM, literal.strength())
        assertTrue(legacy.isLegacyFallbackOnly)
        assertEquals(SourceEvidenceStrength.MEDIUM, legacy.strength())
    }

    @Test
    fun arbitraryLiteralWithStrongEvidenceIsNotLiteralOnly() {
        val mixed = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "selected testTag", "arbitrary literal"),
            rawScore = 80.0,
        )

        assertFalse(mixed.isArbitraryLiteralOnly)
        assertEquals(SourceEvidenceStrength.STRONG, mixed.strength())
    }

    @Test
    fun selectedTextAndContentDescriptionMakesTwoMediums() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "selected contentDescription"),
            rawScore = 80.0,
        )

        assertEquals(SourceEvidenceStrength.MEDIUM, profile.strength())
        assertEquals(2, profile.distinctSelectedMediumKinds)
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*EvidenceProfileTest*"`. Confirm compilation failure.

- [ ] Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength

internal data class EvidenceProfile(
    val rawScore: Double,
    val reasons: Set<String>,
) {
    val hasStrictCompTag: Boolean = "selected testTag convention composable" in reasons
    val hasSelectedTestTag: Boolean = "selected testTag" in reasons
    val hasSelectedUiText: Boolean = "selected text" in reasons
    val hasSelectedContentDescription: Boolean = "selected contentDescription" in reasons
    val hasSelectedRole: Boolean = "selected role" in reasons
    val hasSelectedStringResource: Boolean = "selected stringResource" in reasons
    val hasArbitraryLiteral: Boolean = "arbitrary literal" in reasons
    val hasLegacyFallback: Boolean = "legacy fallback" in reasons
    val hasActivity: Boolean = "activity" in reasons
    val hasAnySelected: Boolean =
        hasSelectedTestTag || hasStrictCompTag || hasSelectedUiText ||
            hasSelectedContentDescription || hasSelectedRole || hasSelectedStringResource
    val hasAnyNearby: Boolean = reasons.any { it.startsWith("nearby ") }

    // Cap predicates accept the bucket reason ("selected text" / "selected
    // contentDescription" / "selected role") together with the origin marker
    // reason ("arbitrary literal" / "legacy fallback"). They check that no
    // STRONG-class evidence and no nearby/activity reasons are present.
    private val bucketReasons: Set<String> = setOf(
        "selected text", "selected contentDescription", "selected role"
    )
    private val originMarkerReasons: Set<String> = setOf(
        "arbitrary literal", "legacy fallback"
    )
    private val nonStrongBucketAndOriginReasons: Set<String> =
        bucketReasons + originMarkerReasons

    val isTextOnly: Boolean = reasons == setOf("selected text")
    val isNearbyOnly: Boolean = reasons.isNotEmpty() && reasons.all { it.startsWith("nearby ") }
    val isActivityOnly: Boolean = reasons == setOf("activity")
    // "Arbitrary-literal-only" / "legacy-fallback-only" mean: every reason in
    // the set is either a bucket reason (text/contentDescription/role) or the
    // origin marker itself, the marker is present, and no STRONG / nearby /
    // activity / stringResource reasons are present.
    val isArbitraryLiteralOnly: Boolean =
        "arbitrary literal" in reasons &&
            reasons.all { it in nonStrongBucketAndOriginReasons } &&
            !hasSelectedTestTag && !hasStrictCompTag && !hasSelectedStringResource &&
            !hasAnyNearby && !hasActivity
    val isLegacyFallbackOnly: Boolean =
        "legacy fallback" in reasons &&
            reasons.all { it in nonStrongBucketAndOriginReasons } &&
            !hasSelectedTestTag && !hasStrictCompTag && !hasSelectedStringResource &&
            !hasAnyNearby && !hasActivity

    val selectedStrongCount: Int =
        (if (hasStrictCompTag || hasSelectedTestTag) 1 else 0)
    val distinctSelectedMediumKinds: Int =
        (if (hasSelectedUiText) 1 else 0) +
            (if (hasSelectedContentDescription) 1 else 0) +
            (if (hasSelectedStringResource) 1 else 0) +
            (if (hasSelectedRole && hasAnySelected && !hasOnlyRole()) 1 else 0)

    private fun hasOnlyRole(): Boolean = reasons == setOf("selected role")

    fun strength(): SourceEvidenceStrength = when {
        selectedStrongCount > 0 -> SourceEvidenceStrength.STRONG
        distinctSelectedMediumKinds > 0 -> SourceEvidenceStrength.MEDIUM
        else -> SourceEvidenceStrength.WEAK
    }

    companion object {
        fun fromReasons(reasons: Collection<String>, rawScore: Double): EvidenceProfile =
            EvidenceProfile(rawScore = rawScore, reasons = reasons.toSet())
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*EvidenceProfileTest*"`. Confirm pass.

Commit: `feat(compose-core): add internal EvidenceProfile helper`.

### Step 2.3 - `MarginContext` (TDD)

- [ ] Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/MarginContextTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginContextTest {
    @Test
    fun singleScoreYieldsMarginOne() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.6), index = 0)

        assertEquals(1.0, context.scoreMargin, 0.0)
        assertFalse(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun closeRaceMarginIsAmbiguous() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.5, 0.45), index = 0)

        assertTrue(context.scoreMargin < 0.15)
        assertTrue(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun mediumCeilingZoneIsNotAmbiguous() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.5, 0.41), index = 0)

        assertTrue(context.scoreMargin in 0.15..0.20)
        assertFalse(context.isAmbiguous)
        assertTrue(context.isMediumCeiling)
    }

    @Test
    fun clearMarginIsNeitherAmbiguousNorCeiling() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.9, 0.5), index = 0)

        assertEquals((0.9 - 0.5) / 0.9, context.scoreMargin, 1e-9)
        assertFalse(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun nonTopCandidateUsesPrevAndNextDifferenceForRanking() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.9, 0.7, 0.5), index = 1)

        // For non-top candidates, scoreMargin reuses the top-vs-next gap.
        // The rank reflects 1-based position.
        assertEquals(2, context.ranking)
        assertTrue(context.scoreMargin > 0.0)
    }

    @Test
    fun zeroTopScoreFallsBackToSafeDenominator() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.0), index = 0)

        assertEquals(1.0, context.scoreMargin, 0.0)
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*MarginContextTest*"`. Confirm compilation failure.

- [ ] Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/MarginContext.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

internal data class MarginContext(
    val ranking: Int,
    val scoreMargin: Double,
) {
    val isAmbiguous: Boolean get() = scoreMargin < CLOSE_RACE_MARGIN
    val isMediumCeiling: Boolean get() =
        scoreMargin >= CLOSE_RACE_MARGIN && scoreMargin < CLEAR_MARGIN

    companion object {
        const val CLEAR_MARGIN: Double = 0.20
        const val CLOSE_RACE_MARGIN: Double = 0.15
        private const val SAFE_DENOMINATOR: Double = 0.001

        fun of(scoresHighestFirst: List<Double>, index: Int): MarginContext {
            require(index >= 0 && index < scoresHighestFirst.size) {
                "index out of range"
            }
            val ranking = index + 1
            if (scoresHighestFirst.size == 1) {
                return MarginContext(ranking = ranking, scoreMargin = 1.0)
            }
            val top = scoresHighestFirst[0]
            val next = scoresHighestFirst.getOrElse(1) { 0.0 }
            val denominator = if (top <= 0.0) SAFE_DENOMINATOR else top
            // For ranking display, every candidate carries the same top-vs-next margin.
            val margin = ((top - next) / denominator).coerceAtMost(1.0).coerceAtLeast(0.0)
            return MarginContext(ranking = ranking, scoreMargin = margin)
        }
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*MarginContextTest*"`. Confirm pass.

Commit: `feat(compose-core): add internal MarginContext helper`.

---

## Phase 3 - Wire `SourceMatcher` to use new helpers

Goal: change `SourceMatcher.toCandidate` and emission paths so confidence is determined from `EvidenceProfile + MarginContext`, new fields are populated, and the new reason tokens (`selected stringResource`, `arbitrary literal`, `legacy fallback`) are emitted exactly as Phase 0 specified. Update existing matcher tests per the Phase 0 inventory.

### Step 3.1 - Update `SourceMatcherTest.conventionTestTagLiteralAndComposableMatchesDoNotStackScore` to expect `HIGH`

- [ ] Edit `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`. Replace the assertion at line 208:

```kotlin
assertEquals(SelectionConfidence.MEDIUM, match.confidence)
```

with:

```kotlin
assertEquals(SelectionConfidence.HIGH, match.confidence)
assertEquals(1, match.ranking)
assertEquals(1.0, match.scoreMargin!!, 0.0)
assertEquals(io.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG, match.evidenceStrength)
assertTrue(match.riskFlags.isEmpty())
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*"`. Confirm `conventionTestTagLiteralAndComposableMatchesDoNotStackScore` now fails.

Commit: `test(compose-core): expect HIGH for single-candidate strict-comp tag matches`.

### Step 3.2 - Add new failing tests to `SourceMatcherTest`

- [ ] Append the following tests to `SourceMatcherTest.kt`:

```kotlin
@Test
fun ambiguousTopTwoMarginAttachesAmbiguousRiskAndDowngrades() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "ScreenA.kt",
                    line = 1,
                    text = listOf("Save"),
                    testTags = listOf("save"),
                ),
                SourceIndexEntry(
                    file = "ScreenB.kt",
                    line = 1,
                    text = listOf("Save"),
                    testTags = listOf("save"),
                ),
            ),
        ),
    )

    val matches = matcher.match(
        selectedNode = node(uid = "save", text = listOf("Save"), testTag = "save"),
        nearbyNodes = emptyList(),
        activityName = null,
    )

    assertTrue(matches.size >= 2)
    val top = matches.first()
    assertTrue(top.confidence != SelectionConfidence.HIGH)
    assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.AMBIGUOUS in top.riskFlags)
}

@Test
fun textOnlyMatchIsCappedAtMedium() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "TextOnly.kt",
                    line = 1,
                    text = listOf("Hello"),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "hello", text = listOf("Hello")),
        nearbyNodes = emptyList(),
        activityName = null,
    ).single()

    assertTrue(
        match.confidence == SelectionConfidence.MEDIUM || match.confidence == SelectionConfidence.LOW,
    )
    assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.TEXT_ONLY in match.riskFlags)
}

@Test
fun nearbyOnlyMatchIsCappedAtLow() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "NearbyOnly.kt",
                    line = 1,
                    text = listOf("Pay"),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "anchor"),
        nearbyNodes = listOf(node(uid = "pay-text", text = listOf("Pay"))),
        activityName = null,
    ).singleOrNull()

    if (match != null) {
        assertEquals(SelectionConfidence.LOW, match.confidence)
        assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.NEARBY_ONLY in match.riskFlags)
    }
}

@Test
fun activityOnlyMatchIsCappedAtLow() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "ActivityOnly.kt",
                    line = 1,
                    activityNames = listOf("MainActivity"),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "x"),
        nearbyNodes = emptyList(),
        activityName = "io.beyondwin.fixthis.sample.MainActivity",
    ).singleOrNull()

    if (match != null) {
        assertEquals(SelectionConfidence.LOW, match.confidence)
        assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.ACTIVITY_ONLY in match.riskFlags)
    }
}

@Test
fun arbitraryLiteralOnlyMatchIsCappedAtLow() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "Literal.kt",
                    line = 1,
                    signals = listOf(
                        SourceSignal(
                            kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                            value = "Pay now",
                            confidenceWeight = 0.35,
                        ),
                    ),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "pay", text = listOf("Pay now")),
        nearbyNodes = emptyList(),
        activityName = null,
    ).single()

    assertEquals(SelectionConfidence.LOW, match.confidence)
    assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.ARBITRARY_LITERAL in match.riskFlags)
    assertTrue("arbitrary literal" in match.matchReasons)
}

@Test
fun legacyFallbackOnlyMatchEmitsLegacyReasonAndCapsAtLow() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "LegacyOnly.kt",
                    line = 1,
                    text = listOf("Pay now"),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "pay", text = listOf("Pay now")),
        nearbyNodes = emptyList(),
        activityName = null,
    ).single()

    // The fixture has no typed signals, so the legacy fallback origin marker
    // fires and dominates over the text-only cap (legacy fallback is more
    // specific / stronger evidence about candidate quality).
    assertEquals(SelectionConfidence.LOW, match.confidence)
    assertTrue(io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.LEGACY_FALLBACK in match.riskFlags)
    assertTrue("legacy fallback" in match.matchReasons)
}

@Test
fun strongEvidenceWithClearMarginRemainsHigh() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "AppPrimaryButton.kt",
                    line = 12,
                    symbols = listOf("AppPrimaryButton"),
                    testTags = listOf("comp:AppPrimaryButton:primary"),
                ),
                SourceIndexEntry(
                    file = "Other.kt",
                    line = 1,
                    text = listOf("primary"),
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "btn", testTag = "comp:AppPrimaryButton:primary"),
        nearbyNodes = emptyList(),
        activityName = null,
    ).first()

    assertEquals(SelectionConfidence.HIGH, match.confidence)
    assertTrue(match.scoreMargin!! >= 0.20)
}
```

The helper `node(uid = ...)` is already defined in the test file.

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*"`. Confirm the new tests fail (red).

Commit: `test(compose-core): add ambiguity, cap, and clear-margin matcher tests`.

### Step 3.3 - Implement matcher changes (the production change)

- [ ] Edit `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`.

  1. Track in `score()` which signal kinds fired per matched term so we can emit `selected stringResource`, `arbitrary literal`, and `legacy fallback` reasons. The simplest approach is to thread a `Set<SourceSignalKind>` into the match call chain. Modify `signalOrLegacyWeight` to return a small data carrier:

  ```kotlin
  private data class WeightHit(val weight: Double, val signalKind: SourceSignalKind?, val viaLegacy: Boolean)
  ```

  and have `addIfMatches` accept `WeightHit` instead of a raw `Double`. In each call site, when the carrier is `signalKind == STRING_RESOURCE` and the bucket reason is `selected text` or `selected contentDescription`, additionally call `matchReasons.add("selected stringResource")`.

  2. After scoring, post-process the candidate's reason set:
     - if every element of `matchedTerms` came only from `ARBITRARY_STRING_LITERAL` and there are no other typed signals or legacy fallbacks: add `"arbitrary literal"` once.
     - if every element of `matchedTerms` came only from the legacy fallback path (no typed signal fired for any term and all `matchedTerms.size > 0`): add `"legacy fallback"` once.

  3. After producing the sorted list of `MatchScore`, build a normalized score list (the same value used for `SourceCandidate.score`):

  ```kotlin
  val normalizedScores = matchScores.map {
      (it.rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0)
  }
  ```

  and pass `(matchScores, normalizedScores)` into the new `toCandidate(index, normalizedScores)` function.

  4. Replace `MatchScore.toCandidate` with:

  ```kotlin
  private fun MatchScore.toCandidate(
      index: Int,
      normalizedScores: List<Double>,
  ): SourceCandidate {
      val profile = EvidenceProfile.fromReasons(matchReasons, rawScore)
      val margin = MarginContext.of(normalizedScores, index)
      val baseConfidence = baseConfidenceFor(profile, margin)
      val capInfo = applyCaps(profile, baseConfidence, areaSelection = false)
      val (afterAmbiguity, ambiguousFlag) = applyAmbiguityDowngrade(
          confidence = capInfo.confidence,
          margin = margin,
          totalCandidates = normalizedScores.size,
      )
      val flags = SourceCandidateRiskPrecedence.ordered(
          buildList {
              ambiguousFlag?.let(::add)
              addAll(capInfo.flags)
          },
      )
      val caution = cautionFor(afterAmbiguity, flags)
      return SourceCandidate(
          file = entry.file,
          line = entry.line,
          score = (rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0),
          matchedTerms = matchedTerms,
          matchReasons = matchReasons,
          confidence = afterAmbiguity,
          ranking = margin.ranking,
          scoreMargin = margin.scoreMargin,
          evidenceStrength = profile.strength(),
          riskFlags = flags,
          caution = caution,
      )
  }

  private fun baseConfidenceFor(profile: EvidenceProfile, margin: MarginContext): SelectionConfidence {
      val clear = margin.scoreMargin >= MarginContext.CLEAR_MARGIN
      return when {
          profile.rawScore <= 0.0 -> SelectionConfidence.NONE
          profile.selectedStrongCount > 0 && clear -> SelectionConfidence.HIGH
          profile.distinctSelectedMediumKinds >= 2 && clear -> SelectionConfidence.HIGH
          profile.hasSelectedUiText || profile.hasSelectedContentDescription -> SelectionConfidence.MEDIUM
          profile.selectedStrongCount > 0 -> SelectionConfidence.MEDIUM
          else -> SelectionConfidence.LOW
      }
  }

  private data class CapResult(
      val confidence: SelectionConfidence,
      val flags: List<SourceCandidateRisk>,
  )

  private fun applyCaps(
      profile: EvidenceProfile,
      baseConfidence: SelectionConfidence,
      areaSelection: Boolean,
  ): CapResult {
      // Order matters: stronger (more-specific) caps fire before weaker ones,
      // and only the strongest cap that fires contributes the LOW flag. The
      // text-only flag is added independently (it represents a different
      // observation about the candidate, even when overridden by literal/legacy).
      val flags = mutableListOf<SourceCandidateRisk>()
      var confidence = baseConfidence

      if (profile.isArbitraryLiteralOnly) {
          flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
          confidence = capAt(confidence, SelectionConfidence.LOW)
      } else if (profile.isLegacyFallbackOnly) {
          flags.add(SourceCandidateRisk.LEGACY_FALLBACK)
          confidence = capAt(confidence, SelectionConfidence.LOW)
      } else if (profile.isNearbyOnly) {
          flags.add(SourceCandidateRisk.NEARBY_ONLY)
          confidence = capAt(confidence, SelectionConfidence.LOW)
      } else if (profile.isActivityOnly) {
          flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
          confidence = capAt(confidence, SelectionConfidence.LOW)
      } else if (profile.isTextOnly) {
          flags.add(SourceCandidateRisk.TEXT_ONLY)
          confidence = capAt(confidence, SelectionConfidence.MEDIUM)
      }

      if (areaSelection) {
          flags.add(SourceCandidateRisk.AREA_SELECTION)
          confidence = capAt(confidence, SelectionConfidence.LOW)
      }
      return CapResult(confidence, flags)
  }

  private fun applyAmbiguityDowngrade(
      confidence: SelectionConfidence,
      margin: MarginContext,
      totalCandidates: Int,
  ): Pair<SelectionConfidence, SourceCandidateRisk?> {
      if (totalCandidates < 2) return confidence to null
      return when {
          margin.isAmbiguous -> downgrade(confidence) to SourceCandidateRisk.AMBIGUOUS
          margin.isMediumCeiling && confidence == SelectionConfidence.HIGH ->
              SelectionConfidence.MEDIUM to null
          else -> confidence to null
      }
  }

  private fun downgrade(confidence: SelectionConfidence): SelectionConfidence = when (confidence) {
      SelectionConfidence.HIGH -> SelectionConfidence.MEDIUM
      SelectionConfidence.MEDIUM -> SelectionConfidence.LOW
      else -> confidence
  }

  private fun capAt(current: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence {
      val rank = mapOf(
          SelectionConfidence.HIGH to 3,
          SelectionConfidence.MEDIUM to 2,
          SelectionConfidence.LOW to 1,
          SelectionConfidence.NONE to 0,
      )
      return if ((rank[current] ?: 0) > (rank[ceiling] ?: 0)) ceiling else current
  }

  private fun cautionFor(
      confidence: SelectionConfidence,
      flags: List<SourceCandidateRisk>,
  ): String? {
      val highest = SourceCandidateRiskPrecedence.highest(flags)
      if (highest != null) {
          return when (highest) {
              SourceCandidateRisk.AMBIGUOUS ->
                  "Verify this source candidate before editing; top candidates are close."
              SourceCandidateRisk.AREA_SELECTION ->
                  "Visual-area selection; use screenshot and bounds before editing."
              SourceCandidateRisk.TEXT_ONLY ->
                  "Text-only match; confirm against screenshot and code."
              SourceCandidateRisk.NEARBY_ONLY ->
                  "Nearby-only match; confirm against screenshot and code."
              SourceCandidateRisk.ARBITRARY_LITERAL ->
                  "Match relied on a generic string literal; confirm against screenshot and code."
              SourceCandidateRisk.ACTIVITY_ONLY ->
                  "Activity-only match; confirm against screenshot and code."
              SourceCandidateRisk.LEGACY_FALLBACK ->
                  "Legacy-fallback match; confirm against screenshot and code."
          }
      }
      return when (confidence) {
          SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
          SelectionConfidence.NONE -> "No source candidate was available from current evidence."
          else -> null
      }
  }
  ```

  5. Update the `match(...)` function so it constructs `normalizedScores` and calls `toCandidate(index, normalizedScores)`:

  ```kotlin
  fun match(
      selectedNode: FixThisNode?,
      nearbyNodes: List<FixThisNode>,
      activityName: String?
  ): List<SourceCandidate> {
      if (selectedNode == null || sourceIndex.entries.isEmpty()) return emptyList()

      val matchScores = sourceIndex.entries.asSequence()
          .map { entry -> score(entry, selectedNode, nearbyNodes, activityName) }
          .filter { it.rawScore > 0.0 }
          .sortedWith(
              compareByDescending<MatchScore> { it.rawScore }
                  .thenBy { it.entry.file }
                  .thenBy { it.entry.line ?: Int.MAX_VALUE }
          )
          .take(MAX_CANDIDATES)
          .toList()

      val normalizedScores = matchScores.map {
          (it.rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0)
      }
      return matchScores.mapIndexed { index, score -> score.toCandidate(index, normalizedScores) }
  }
  ```

  6. Add the `arbitrary literal` / `legacy fallback` post-processing inside `score()` once per candidate (after the per-term loops), guarded by tracking which signal kinds fired vs which fell back to the legacy path. The implementer can pass two booleans through `addIfMatches`:

  ```kotlin
  private class ScoreContext {
      var anyTypedSignalNonLiteral: Boolean = false
      var anyArbitraryLiteralSignal: Boolean = false
      var anyLegacyOnly: Boolean = false
      var anyTermMatched: Boolean = false
  }
  ```

  After all addIfMatches calls in `score()`:

  ```kotlin
  if (ctx.anyTermMatched) {
      if (!ctx.anyTypedSignalNonLiteral && !ctx.anyLegacyOnly && ctx.anyArbitraryLiteralSignal) {
          matchReasons.add("arbitrary literal")
      }
      if (!ctx.anyTypedSignalNonLiteral && !ctx.anyArbitraryLiteralSignal && ctx.anyLegacyOnly) {
          matchReasons.add("legacy fallback")
      }
  }
  ```

  Each `addIfMatches`/`signalOrLegacyWeight` returns its `WeightHit`; the score path updates the context: if the kind is one of `UI_TEXT`, `CONTENT_DESCRIPTION`, `TEST_TAG`, `STRICT_COMP_TEST_TAG`, `ROLE`, `STRING_RESOURCE`, `ACTIVITY_NAME`, `COMPOSABLE_SYMBOL` -> `anyTypedSignalNonLiteral = true`. If kind is `ARBITRARY_STRING_LITERAL` -> `anyArbitraryLiteralSignal = true`. If `viaLegacy = true` -> `anyLegacyOnly = true`.

  Implementer note: in the existing `signalOrLegacyWeight`, `signalWeight` returns a Double already. Refactor it to return `WeightHit` by also tracking which signal was max-weight.

  7. For `STRING_RESOURCE`, when `signalWeight` matched a `STRING_RESOURCE` signal AND the calling reason was `selected text` or `selected contentDescription`, add `"selected stringResource"` to `matchReasons` from inside `addIfMatches` (or post-call) so the formatter can show `stringRes` separately.

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*" --tests "*EvidenceProfileTest*" --tests "*MarginContextTest*" --tests "*SourceCandidateRiskPrecedenceTest*"`. Confirm all pass. The earlier `ranksSelectedNodeTextTagRoleAndActivityMatches` (line 51) test must still pass with `HIGH`; if it does not, inspect normalized scores for the second candidate and confirm margin >= 0.20.

- [ ] If any pre-existing test still fails, follow the Phase 0 inventory: only `conventionTestTagLiteralAndComposableMatchesDoNotStackScore` was intentionally re-asserted; every other pre-existing matcher test must still pass.

Commit: `feat(compose-core): margin- and evidence-aware confidence in SourceMatcher`.

### Step 3.4 - Update tests in adjacent suites that referenced HIGH

- [ ] Run: `./gradlew :fixthis-compose-core:test :fixthis-mcp:test`. List all failures. For each failing test, consult the Phase 0 inventory:

  - `TargetEvidenceModelTest.kt` (lines 95, 127, 147): inspect each fixture. If a fixture has a single `SourceCandidate` with strict-comp-style match the assertion stays HIGH. If it has multiple text-only candidates the assertion must update to MEDIUM and the test must add an assertion that no `riskFlags` contains `AMBIGUOUS` if the margin is in the medium-ceiling zone.
  - `FeedbackQueueFormatterTest.kt`: pre-existing tests use `DetailMode.PRECISE` (the default). PRECISE is unchanged, so the existing assertions must pass. If any of them fail, the PRECISE path was inadvertently modified - revert in Phase 5 before claiming pass here.
  - `FixThisMarkdownFormatterTest.kt`: same - PRECISE/FULL unchanged.

- [ ] For each fixture you change, prefer constructing fixtures that exercise the new rules deliberately rather than papering over the change. Confirm the test still expresses the original intent.

Commit (one per affected file, or one combined): `test: align SourceCandidate confidence assertions with Phase 3 rules`.

---

## Phase 4 - `SourceInterpretationFactory` caution wiring

Goal: caution text comes from the factory using the precedence-resolved risk flag and confidence. The matcher already populates `SourceCandidate.caution`, but the factory should still produce its own caution for `SourceInterpretation.caution` to remain stable for older readers.

### Step 4.1 - Update `SourceInterpretationFactoryTest`

- [ ] Edit `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt`. Add three more granular tests. Add `import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk`.

```kotlin
@Test
fun ambiguousFlagProducesAmbiguityCaution() {
    val candidate = SourceCandidate(
        file = "Foo.kt",
        line = 1,
        score = 0.5,
        confidence = SelectionConfidence.MEDIUM,
        riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
    )
    val interpretation = SourceInterpretationFactory.from(listOf(candidate))

    assertEquals(
        "Verify this source candidate before editing; top candidates are close.",
        interpretation.caution,
    )
}

@Test
fun areaSelectionFlagProducesAreaCaution() {
    val candidate = SourceCandidate(
        file = "Foo.kt",
        line = 1,
        score = 0.5,
        confidence = SelectionConfidence.LOW,
        riskFlags = listOf(SourceCandidateRisk.AREA_SELECTION, SourceCandidateRisk.TEXT_ONLY),
    )
    val interpretation = SourceInterpretationFactory.from(listOf(candidate))

    assertEquals(
        "Visual-area selection; use screenshot and bounds before editing.",
        interpretation.caution,
    )
}

@Test
fun textOnlyFlagProducesTextOnlyCaution() {
    val candidate = SourceCandidate(
        file = "Foo.kt",
        line = 1,
        score = 0.5,
        confidence = SelectionConfidence.MEDIUM,
        riskFlags = listOf(SourceCandidateRisk.TEXT_ONLY),
    )
    val interpretation = SourceInterpretationFactory.from(listOf(candidate))

    assertEquals(
        "Text-only match; confirm against screenshot and code.",
        interpretation.caution,
    )
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceInterpretationFactoryTest*"`. Confirm new tests fail.

Commit: `test(compose-core): pin SourceInterpretation caution generator wiring`.

### Step 4.2 - Implement `SourceInterpretationFactory` change

- [ ] Edit `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.SourceInterpretation

object SourceInterpretationFactory {
    fun from(sourceCandidates: List<SourceCandidate>): SourceInterpretation {
        val top = sourceCandidates.firstOrNull()
            ?: return SourceInterpretation(
                caution = "No source candidate was available from current evidence.",
            )

        return SourceInterpretation(
            topCandidate = SourceCandidateSummary(
                file = top.file,
                line = top.line,
                confidence = top.confidence,
            ),
            reasonSummary = top.matchReasons.take(5),
            caution = top.caution ?: defaultCaution(top),
        )
    }

    private fun defaultCaution(top: SourceCandidate): String? {
        val highest = SourceCandidateRiskPrecedence.highest(top.riskFlags)
        if (highest != null) {
            return when (highest) {
                SourceCandidateRisk.AMBIGUOUS ->
                    "Verify this source candidate before editing; top candidates are close."
                SourceCandidateRisk.AREA_SELECTION ->
                    "Visual-area selection; use screenshot and bounds before editing."
                SourceCandidateRisk.TEXT_ONLY ->
                    "Text-only match; confirm against screenshot and code."
                SourceCandidateRisk.NEARBY_ONLY ->
                    "Nearby-only match; confirm against screenshot and code."
                SourceCandidateRisk.ARBITRARY_LITERAL ->
                    "Match relied on a generic string literal; confirm against screenshot and code."
                SourceCandidateRisk.ACTIVITY_ONLY ->
                    "Activity-only match; confirm against screenshot and code."
                SourceCandidateRisk.LEGACY_FALLBACK ->
                    "Legacy-fallback match; confirm against screenshot and code."
            }
        }
        return when (top.confidence) {
            SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
            SelectionConfidence.NONE -> "No source candidate was available from current evidence."
            else -> null
        }
    }
}
```

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*SourceInterpretationFactoryTest*"`. Confirm pass.

Commit: `feat(compose-core): wire SourceInterpretation caution to risk precedence`.

---

## Phase 5 - COMPACT formatter changes (`FeedbackQueueFormatter` + `FixThisMarkdownFormatter`)

Goal: only `DetailMode.COMPACT` (and the default-PRECISE-replacement only when configured by callers) gets the new compact tokens. Keep PRECISE and FULL byte-stable.

### Step 5.1 - Add COMPACT format tests to `FeedbackQueueFormatterTest`

- [ ] Append the following tests to `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`. Add `import kotlin.test.assertNotNull` if not already present.

```kotlin
@Test
fun compactMarkdownEmitsTopLevelVerificationRule() {
    val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

    assertTrue(
        markdown.contains(
            "Rule: source hints are candidates; verify screenshot, target, and code before editing."
        ),
    )
}

@Test
fun compactMarkdownEmitsCompactSourceTokenLine() {
    val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

    val lines = markdown.lines()
    val sourceLine = lines.firstOrNull { it.trim().startsWith("src?") }
    assertNotNull(sourceLine, "Expected a 'src?' line in COMPACT markdown")
    assertTrue(sourceLine!!.contains("AppPrimaryButton.kt:42"))
    assertTrue(sourceLine.contains("high") || sourceLine.contains("medium") || sourceLine.contains("low"))
    assertTrue(sourceLine.contains("why="))
    assertFalse(markdown.contains("matched:"))
    assertFalse(markdown.contains("reasons:"))
}

@Test
fun compactMarkdownConfidenceTokenIsLowercase() {
    val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

    assertFalse(markdown.contains(" HIGH "))
    assertFalse(markdown.contains(" MEDIUM "))
    assertFalse(markdown.contains(" LOW "))
}

@Test
fun preciseMarkdownPreservesLikelySourceWireFormat() {
    val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.PRECISE)

    assertTrue(markdown.contains("Likely Source:"))
    assertTrue(markdown.contains("matched:"))
    assertTrue(markdown.contains("reasons:"))
    assertFalse(markdown.contains("src?"))
    assertFalse(
        markdown.contains(
            "Rule: source hints are candidates; verify screenshot, target, and code before editing."
        ),
    )
}

@Test
fun compactMarkdownIncludesScreenshotAndOverlayWhenAvailable() {
    val session = sessionWithScreenshotAndOverlay()
    val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

    assertTrue(markdown.contains("screenshot:"))
    assertTrue(markdown.contains("Checkout"))
}

private fun sessionWithScreenshotAndOverlay(): SessionDto = SessionDto(
    sessionId = "session-1",
    packageName = "io.beyondwin.fixthis.sample",
    projectRoot = "/repo",
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 5L,
    screens = listOf(
        SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 1L,
            displayName = "Checkout",
            screenshot = SnapshotScreenshotDto(
                desktopFullPath = "/repo/.fixthis/feedback-sessions/session-1/artifacts/screens/screen-1/screen-1-full.png",
                width = 720,
                height = 1600,
            ),
        ),
    ),
    items = listOf(
        AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 2L,
            updatedAtEpochMillis = 2L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
            comment = "Make this bigger",
            sequenceNumber = 1,
        ),
    ),
)
```

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest*"`. Confirm new tests fail (red), and all pre-existing tests still pass.

Commit: `test(mcp): pin COMPACT formatter shape and PRECISE wire stability`.

### Step 5.2 - Implement `FeedbackQueueFormatter` COMPACT path

- [ ] Edit `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`. Add a separate COMPACT rendering branch driven from `toMarkdown`:

```kotlin
fun toMarkdown(session: SessionDto, detailMode: DetailMode): String =
    when (detailMode) {
        DetailMode.COMPACT -> toCompactMarkdown(session)
        DetailMode.PRECISE, DetailMode.FULL -> toLegacyMarkdown(session, detailMode)
    }

private fun toLegacyMarkdown(session: SessionDto, detailMode: DetailMode): String = buildString {
    // existing buildString body, unchanged
}

private fun toCompactMarkdown(session: SessionDto): String = buildString {
    appendLine("# FixThis Feedback Handoff")
    appendLine()
    appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
    appendLine()
    appendLine("- Package: `${session.packageName}`")
    appendLine("- Feedback Items: `${session.items.size}`")
    appendLine()

    val orderedItems = session.items.withIndex()
        .sortedWith(
            compareBy<IndexedValue<AnnotationDto>> { it.value.sequenceNumber ?: Int.MAX_VALUE }
                .thenBy { it.index },
        )
    if (orderedItems.isEmpty()) {
        appendLine("No feedback items.")
        appendLine()
        return@buildString
    }

    val itemsByScreen = orderedItems.groupBy { it.value.screenId }
    var globalCounter = 0
    itemsByScreen.forEach { (screenId, indexedItems) ->
        val screen = session.screens.firstOrNull { it.screenId == screenId }
        val displayName = screen?.displayName ?: "Screen"
        appendLine("Screen ${screenId}: ${displayName.inlineSafe()}")
        screen?.screenshot?.desktopFullPath?.let {
            appendLine("screenshot: ${it.inlineSafe()}")
        }
        appendLine()
        indexedItems.forEach { entry ->
            globalCounter += 1
            appendCompactItem(globalCounter, entry.value, isOverlap = false)
        }
    }
}

private fun StringBuilder.appendCompactItem(number: Int, item: AnnotationDto, isOverlap: Boolean) {
    val title = item.comment.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(No request provided)"
    appendLine("${number}. [marker $number] ${title.inlineSafe()}")
    val targetSummary = compactTargetSummary(item)
    val targetLine = if (isOverlap) "target: $targetSummary; targetRisk=overlap" else "target: $targetSummary"
    appendLine(targetLine)
    item.screenshotCrop?.desktopCropPath?.let { appendLine("crop: ${it.inlineSafe()}") }
    val sourceLine = compactSourceLine(item)
    if (sourceLine != null) appendLine(sourceLine)
    appendLine()
}

private fun compactTargetSummary(item: AnnotationDto): String {
    val node = item.selectedNode
    val role = node?.role?.takeIf { it.isNotBlank() } ?: when (item.target) {
        is AnnotationTargetDto.Area -> "Area"
        is AnnotationTargetDto.Node -> "Node"
    }
    val label = node?.text?.firstOrNull()
        ?: node?.contentDescription?.firstOrNull()
        ?: node?.testTag
        ?: "(unlabelled)"
    val bounds = when (val target = item.target) {
        is AnnotationTargetDto.Area -> target.boundsInWindow.formatBounds()
        is AnnotationTargetDto.Node -> target.boundsInWindow.formatBounds()
    }
    return "${role.inlineSafe()} \"${label.inlineSafe()}\" bounds=$bounds"
}

private fun compactSourceLine(item: AnnotationDto): String? {
    val candidate = item.sourceCandidates.firstOrNull() ?: return null
    val confidence = candidate.confidence.name.lowercase()
    val why = candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().joinToString("+")
    val risk = candidate.riskFlags.firstOrNull()?.name?.lowercase()?.replace('_', '-')
    val parts = mutableListOf("src? ${candidate.fileWithLine()} $confidence")
    if (why.isNotBlank()) parts.add("why=$why")
    if (risk != null) parts.add("risk=$risk")
    return parts.joinToString("; ")
}

private fun reasonTokenFor(reason: String): String? = when (reason) {
    "selected text" -> "text"
    "selected contentDescription" -> "contentDescription"
    "selected testTag" -> "tag"
    "selected testTag convention composable" -> "compTag"
    "selected role" -> "role"
    "nearby text" -> "nearbyText"
    "nearby contentDescription" -> "nearbyContentDescription"
    "nearby testTag" -> "nearbyTag"
    "nearby role" -> "nearbyRole"
    "activity" -> "activity"
    "selected stringResource" -> "stringRes"
    "arbitrary literal" -> "literal"
    "legacy fallback" -> "legacy"
    else -> null
}
```

The existing `appendLikelySource`, `appendFeedbackItem`, etc. remain unchanged and continue to handle PRECISE/FULL. The existing `private fun SourceCandidate.fileWithLine()` and `inlineSafe()` extensions are reused.

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest*"`. Confirm new tests pass and pre-existing tests still pass.

Commit: `feat(mcp): COMPACT FeedbackQueueFormatter emits src? token shape`.

### Step 5.3 - Add COMPACT-only test to `FixThisMarkdownFormatterTest`

- [ ] Edit `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt`. Add a focused COMPACT test:

```kotlin
@Test
fun compactFormatEmitsSrcTokenLineWithLowercaseConfidence() {
    val annotation = annotationWithSingleSource(
        confidence = SelectionConfidence.MEDIUM,
        reasons = listOf("selected text"),
        risk = SourceCandidateRisk.TEXT_ONLY,
    )

    val markdown = FixThisMarkdownFormatter.format(annotation, DetailMode.COMPACT)

    val sourceLine = markdown.lines().firstOrNull { it.trim().startsWith("src?") }
    assertNotNull(sourceLine)
    assertTrue(sourceLine!!.contains(" medium"))
    assertTrue(sourceLine.contains("why=text"))
    assertTrue(sourceLine.contains("risk=text-only"))
}
```

Add a helper `annotationWithSingleSource(confidence, reasons, risk)` to the same file. Use the existing fixture builder pattern in the file (the test file already constructs `FixThisAnnotation` instances inline; copy the smallest one and parameterize source candidate fields).

Required imports: `io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk`.

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*FixThisMarkdownFormatterTest*"`. Confirm new test fails. Confirm pre-existing PRECISE/FULL tests still pass (their wire format must not change).

Commit: `test(compose-core): pin COMPACT FixThisMarkdownFormatter token shape`.

### Step 5.4 - Implement `FixThisMarkdownFormatter` COMPACT change

- [ ] Edit `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt`. Replace the COMPACT branch's source-candidate rendering only:

```kotlin
private fun formatCompact(annotation: FixThisAnnotation): String = buildString {
    appendLine("# FixThis Feedback")
    appendLine()
    appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
    appendLine()
    appendLine("Request:")
    appendFreeFormBlock(annotation.userComment)
    appendLine()
    appendLine("Target:")
    appendCompactTarget(annotation)
    appendLine()
    appendCompactSourceLine(annotation.sourceCandidates.firstOrNull())
}

private fun StringBuilder.appendCompactSourceLine(candidate: SourceCandidate?) {
    if (candidate == null) {
        appendLine("src? unknown; why=; risk=")
        return
    }
    val confidence = candidate.confidence.name.lowercase()
    val why = candidate.matchReasons
        .mapNotNull { compactReasonToken(it) }
        .distinct()
        .joinToString("+")
    val risk = candidate.riskFlags.firstOrNull()?.name?.lowercase()?.replace('_', '-')
    val parts = mutableListOf("src? ${candidate.location()} $confidence")
    if (why.isNotBlank()) parts.add("why=$why")
    if (risk != null) parts.add("risk=$risk")
    appendLine(parts.joinToString("; "))
}

private fun compactReasonToken(reason: String): String? = when (reason) {
    "selected text" -> "text"
    "selected contentDescription" -> "contentDescription"
    "selected testTag" -> "tag"
    "selected testTag convention composable" -> "compTag"
    "selected role" -> "role"
    "nearby text" -> "nearbyText"
    "nearby contentDescription" -> "nearbyContentDescription"
    "nearby testTag" -> "nearbyTag"
    "nearby role" -> "nearbyRole"
    "activity" -> "activity"
    "selected stringResource" -> "stringRes"
    "arbitrary literal" -> "literal"
    "legacy fallback" -> "legacy"
    else -> null
}
```

`PRECISE` (`formatPrecise`) and `FULL` (`formatFull`) remain unchanged.

- [ ] Run: `./gradlew :fixthis-compose-core:test --tests "*FixThisMarkdownFormatterTest*"`. Confirm pass.

Commit: `feat(compose-core): COMPACT FixThisMarkdownFormatter switches to src? token`.

---

## Phase 6 - Overlap detector and grouping

Goal: detect overlapping persisted items per screen and split them into explicit groups in COMPACT output. Same-screen non-overlapping items remain grouped under one screen header.

### Step 6.1 - `AnnotationOverlapDetector` (TDD)

- [ ] Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationOverlapDetectorTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationOverlapDetectorTest {
    @Test
    fun nonOverlappingNodeBoundsAreNotGrouped() {
        val a = AnnotationOverlapDetector.Item(id = "a", bounds = FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item(id = "b", bounds = FixThisRect(50f, 50f, 60f, 60f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(2, groups.size)
        assertFalse(groups.any { it.size > 1 })
    }

    @Test
    fun anyAreaSelectionIntersectionGroupsItems() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 100f, 100f), isAreaSelection = true)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(99f, 99f, 200f, 200f), isAreaSelection = true)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.first().map { it.id }.toSet())
    }

    @Test
    fun ioSAAboveQuarterCountsAsOverlapForNodes() {
        // Smaller bounds 10x10 = 100. Intersection 5x5 = 25. IoSA = 25/100 = 0.25
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(5f, 5f, 100f, 100f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
    }

    @Test
    fun ioSABelowQuarterDoesNotGroup() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(9f, 9f, 100f, 100f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(2, groups.size)
    }

    @Test
    fun centerDistanceFallbackUsesDefaultDensity() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false, hasWeakLabels = true)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(20f, 0f, 30f, 10f), isAreaSelection = false, hasWeakLabels = true)
        // Center distance is ~20px. With density 1.0 the threshold is 24, so overlap.

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
    }
}
```

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*AnnotationOverlapDetectorTest*"`. Confirm compilation failure.

- [ ] Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationOverlapDetector.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.math.hypot

object AnnotationOverlapDetector {
    data class Item(
        val id: String,
        val bounds: FixThisRect,
        val isAreaSelection: Boolean,
        val hasWeakLabels: Boolean = false,
    )

    private const val IOSA_THRESHOLD = 0.25
    private const val WEAK_LABEL_CENTER_DISTANCE_DP = 24f

    fun detect(items: List<Item>, density: Float = 1.0f): List<List<Item>> {
        if (items.size < 2) return items.map { listOf(it) }

        val groupOf = IntArray(items.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (groupOf[x] != x) {
                groupOf[x] = groupOf[groupOf[x]]
                x = groupOf[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) groupOf[rb] = ra
        }

        for (i in items.indices) {
            for (j in (i + 1) until items.size) {
                if (overlaps(items[i], items[j], density)) {
                    union(i, j)
                }
            }
        }

        return items.indices.groupBy { root(it) }.values.map { idxs -> idxs.map { items[it] } }
    }

    private fun overlaps(a: Item, b: Item, density: Float): Boolean {
        val intersection = intersectionArea(a.bounds, b.bounds)
        if (a.isAreaSelection && b.isAreaSelection) {
            return intersection > 0.0
        }
        val smaller = minOf(a.bounds.area, b.bounds.area)
        if (smaller > 0f && (intersection / smaller) >= IOSA_THRESHOLD) return true

        if (a.hasWeakLabels || b.hasWeakLabels) {
            val centerDistance = centerDistance(a.bounds, b.bounds)
            val threshold = WEAK_LABEL_CENTER_DISTANCE_DP * density
            if (centerDistance <= threshold) return true
        }
        return false
    }

    private fun intersectionArea(a: FixThisRect, b: FixThisRect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val w = (right - left).coerceAtLeast(0f)
        val h = (bottom - top).coerceAtLeast(0f)
        return w * h
    }

    private fun centerDistance(a: FixThisRect, b: FixThisRect): Float {
        val cx = ((a.left + a.right) / 2f) - ((b.left + b.right) / 2f)
        val cy = ((a.top + a.bottom) / 2f) - ((b.top + b.bottom) / 2f)
        return hypot(cx.toDouble(), cy.toDouble()).toFloat()
    }
}
```

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*AnnotationOverlapDetectorTest*"`. Confirm pass.

Commit: `feat(mcp): add AnnotationOverlapDetector with IoSA and dp-aware fallback`.

### Step 6.2 - Wire overlap groups into compact formatter

- [ ] Add a test to `FeedbackQueueFormatterTest.kt`:

```kotlin
@Test
fun compactMarkdownSplitsOverlappingItemsIntoExplicitGroups() {
    val session = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 5L,
        screens = listOf(SnapshotDto("screen-1", 2L, displayName = "Checkout")),
        items = listOf(
            AnnotationDto(
                itemId = "a",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 100f)),
                comment = "Resize",
                sequenceNumber = 1,
            ),
            AnnotationDto(
                itemId = "b",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Area(FixThisRect(99f, 99f, 200f, 200f)),
                comment = "Recolor",
                sequenceNumber = 2,
            ),
        ),
    )

    val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

    assertTrue(markdown.contains("Overlap group"))
    assertTrue(markdown.contains("Resize"))
    assertTrue(markdown.contains("Recolor"))
    assertTrue(markdown.contains("targetRisk=overlap") || markdown.contains("resolve one marker at a time"))
}
```

- [ ] Run: confirm fail.

- [ ] Edit `FeedbackQueueFormatter.toCompactMarkdown` so the per-screen group block calls `AnnotationOverlapDetector.detect(...)`. Treat each detected group with `size > 1` as an "Overlap group" subsection. Each item carries `targetRisk=overlap` appended to its target line, and the group header line reads:

```
Overlap group N (resolve one marker at a time):
```

Implementation sketch:

```kotlin
val detectorItems = indexedItems.map { entry ->
    AnnotationOverlapDetector.Item(
        id = entry.value.itemId,
        bounds = when (val t = entry.value.target) {
            is AnnotationTargetDto.Area -> t.boundsInWindow
            is AnnotationTargetDto.Node -> t.boundsInWindow
        },
        isAreaSelection = entry.value.target is AnnotationTargetDto.Area,
        hasWeakLabels = entry.value.selectedNode == null ||
            (entry.value.selectedNode?.text?.isEmpty() ?: true),
    )
}
val groups = AnnotationOverlapDetector.detect(detectorItems)
val itemById = indexedItems.associateBy { it.value.itemId }

groups.forEachIndexed { groupIndex, group ->
    if (group.size > 1) {
        appendLine("Overlap group ${groupIndex + 1} (resolve one marker at a time):")
    }
    group.forEach { detectorItem ->
        val entry = itemById[detectorItem.id] ?: return@forEach
        globalCounter += 1
        appendCompactItem(globalCounter, entry.value, isOverlap = group.size > 1)
    }
    if (group.size > 1) appendLine()
}
```

- [ ] Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest*"`. Confirm pass.

Commit: `feat(mcp): split overlapping items into explicit COMPACT groups`.

### Step 6.3 - Add `CompactHandoffRenderer` (extraction)

- [ ] Extract the COMPACT helpers (`appendCompactItem`, `compactTargetSummary`, `compactSourceLine`, `reasonTokenFor`, screen-grouping orchestration) into a new file `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`. `FeedbackQueueFormatter.toCompactMarkdown(session)` becomes a one-liner that delegates to `CompactHandoffRenderer.render(session)`.

- [ ] Add `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` with one focused test:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactHandoffRendererTest {
    @Test
    fun renderEmitsTopLevelRuleAndScreenHeader() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(markdown.contains("Rule: source hints are candidates"))
        assertTrue(markdown.contains("Screen "))
    }
}
```

- [ ] Run: `./gradlew :fixthis-mcp:test`. Confirm green.

Commit: `refactor(mcp): extract CompactHandoffRenderer for focused tests`.

---

## Phase 7 - Console JS prompt (`prompt.js`)

Goal: mirror the COMPACT formatter exactly so the in-browser `Copy Prompt` and `Send Agent` buttons emit the same shape as Kotlin COMPACT output.

> Note: Phase 7 follows the same "definitions before call sites" rule as Phase 1/2. Step 7.1 adds the overlap-detection helpers FIRST; Step 7.2 then adds the orchestration that calls them. Do not split or reorder these steps.

### Step 7.1 - Add JS overlap-detection helpers (no call sites yet)

- [ ] Edit `fixthis-mcp/src/main/console/prompt.js`. Add the following helpers near the top of the prompt-related section. They must be placed earlier in the file than `currentAnnotationsPromptCompact` (added in Step 7.2). They mirror `AnnotationOverlapDetector` exactly (same constants `IOSA_THRESHOLD = 0.25`, `WEAK_LABEL_CENTER_DISTANCE_DP = 24`, density default `1.0`):

```javascript
            const FIXTHIS_IOSA_THRESHOLD = 0.25;
            const FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP = 24;

            function rectArea(r) {
              if (!r) return 0;
              return Math.max(r.right - r.left, 0) * Math.max(r.bottom - r.top, 0);
            }
            function rectIntersection(a, b) {
              const left = Math.max(a.left, b.left);
              const top = Math.max(a.top, b.top);
              const right = Math.min(a.right, b.right);
              const bottom = Math.min(a.bottom, b.bottom);
              return Math.max(right - left, 0) * Math.max(bottom - top, 0);
            }
            function rectCenterDistance(a, b) {
              const ax = (a.left + a.right) / 2; const ay = (a.top + a.bottom) / 2;
              const bx = (b.left + b.right) / 2; const by = (b.top + b.bottom) / 2;
              return Math.hypot(ax - bx, ay - by);
            }
            function compactOverlapItems(item) {
              const t = item.target || {};
              const bounds = (t.boundsInWindow) || (item.bounds) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
              const isAreaSelection = (t.type === 'visual_area');
              const hasWeakLabels = !item.selectedNode || !(item.selectedNode.text && item.selectedNode.text.length);
              return { item: item, bounds: bounds, isAreaSelection: isAreaSelection, hasWeakLabels: hasWeakLabels };
            }
            function compactOverlapsBetween(a, b, density) {
              const inter = rectIntersection(a.bounds, b.bounds);
              if (a.isAreaSelection && b.isAreaSelection) return inter > 0;
              const smaller = Math.min(rectArea(a.bounds), rectArea(b.bounds));
              if (smaller > 0 && (inter / smaller) >= FIXTHIS_IOSA_THRESHOLD) return true;
              if (a.hasWeakLabels || b.hasWeakLabels) {
                const threshold = FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP * (density || 1);
                if (rectCenterDistance(a.bounds, b.bounds) <= threshold) return true;
              }
              return false;
            }
            function compactDetectOverlap(items, density) {
              if (!items || items.length < 2) return (items || []).map(it => [it]);
              const wrapped = items.map(compactOverlapItems);
              const parent = wrapped.map((_, i) => i);
              function find(i) { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
              function union(a, b) { const ra = find(a); const rb = find(b); if (ra !== rb) parent[rb] = ra; }
              for (let i = 0; i < wrapped.length; i++) {
                for (let j = i + 1; j < wrapped.length; j++) {
                  if (compactOverlapsBetween(wrapped[i], wrapped[j], density)) union(i, j);
                }
              }
              const groups = {};
              wrapped.forEach((w, i) => {
                const r = find(i);
                if (!groups[r]) groups[r] = [];
                groups[r].push(w.item);
              });
              return Object.keys(groups).map(k => groups[k]);
            }
```

- [ ] Run `node --check fixthis-mcp/src/main/console/prompt.js` and `node --check fixthis-mcp/src/main/resources/console/app.js`. Confirm syntax pass. (No call site exists yet; helpers are dead code at this commit.)

Commit: `feat(console): add JS overlap-detection helpers mirroring Kotlin`.

### Step 7.2 - Update `prompt.js` reason mapping and source-line emission (calls 7.1's helpers)

- [ ] Edit `fixthis-mcp/src/main/console/prompt.js`. Replace the legacy `Likely Source:` block. The replacement uses the reason-token map and emits a single `src?` line per item, with screen-level grouping, overlap groups, and a top-level rule. The overlap helpers added in Step 7.1 are now the call sites.

```javascript
            const FIXTHIS_REASON_TOKEN_MAP = {
              'selected text': 'text',
              'selected contentDescription': 'contentDescription',
              'selected testTag': 'tag',
              'selected testTag convention composable': 'compTag',
              'selected role': 'role',
              'nearby text': 'nearbyText',
              'nearby contentDescription': 'nearbyContentDescription',
              'nearby testTag': 'nearbyTag',
              'nearby role': 'nearbyRole',
              'activity': 'activity',
              'selected stringResource': 'stringRes',
              'arbitrary literal': 'literal',
              'legacy fallback': 'legacy'
            };

            function compactReasonTokens(reasons) {
              const seen = new Set();
              const tokens = [];
              (reasons || []).forEach(reason => {
                const token = FIXTHIS_REASON_TOKEN_MAP[String(reason || '').trim()];
                if (token && !seen.has(token)) {
                  seen.add(token);
                  tokens.push(token);
                }
              });
              return tokens.join('+');
            }

            function compactRiskToken(candidate, target) {
              const flags = (candidate && candidate.riskFlags) || [];
              if (flags.length > 0) {
                return String(flags[0]).toLowerCase().replace(/_/g, '-');
              }
              if (target && target.type === 'visual_area') return 'area-selection';
              return null;
            }

            function compactSourceLine(item) {
              const candidate = (item.sourceCandidates || [])[0];
              if (!candidate) return null;
              const file = promptScalarValue(candidate.file);
              if (!file) return null;
              const location = file + (candidate.line ? ':' + candidate.line : '');
              const confidence = String(candidate.confidence || 'unknown').toLowerCase();
              const why = compactReasonTokens(candidate.matchReasons);
              const risk = compactRiskToken(candidate, item.target);
              const parts = ['src? ' + location + ' ' + confidence];
              if (why) parts.push('why=' + why);
              if (risk) parts.push('risk=' + risk);
              return '   ' + parts.join('; ');
            }

            function compactTargetLine(item, isOverlap) {
              const node = item.selectedNode || {};
              const role = promptScalarValue(node.role) || (item.target && item.target.type === 'visual_area' ? 'Area' : 'Node');
              const label = (node.text && node.text[0]) || (node.contentDescription && node.contentDescription[0]) || node.testTag || '(unlabelled)';
              const bounds = promptItemBounds(item);
              const base = '   target: ' + role + ' "' + label + '" bounds=' + (bounds ? formatBounds(bounds) : 'unknown');
              return isOverlap ? (base + '; targetRisk=overlap') : base;
            }

            function compactItemLines(item, marker, isOverlap) {
              const lines = [];
              const title = (String(item.comment || '').split('\n')[0] || '').trim() || promptItemTitle(item, marker - 1);
              lines.push(String(marker) + '. [marker ' + marker + '] ' + title);
              lines.push(compactTargetLine(item, isOverlap));
              const sourceLine = compactSourceLine(item);
              if (sourceLine) lines.push(sourceLine);
              return lines;
            }

            function compactScreenHeader(screenId, screen) {
              const lines = ['Screen ' + screenId + ': ' + (screen && screen.displayName ? screen.displayName : 'Screen')];
              const screenshotPath = screen && screen.screenshot && (screen.screenshot.desktopFullPath || screen.screenshot.fullPath);
              if (screenshotPath) lines.push('screenshot: ' + screenshotPath);
              return lines;
            }

            function currentAnnotationsPromptCompact(annotations) {
              const list = annotations || currentPromptAnnotations();
              if (!state.session || list.length === 0) {
                throw new Error('Select a history item with annotations before sending it to an agent.');
              }
              const lines = [
                'FixThis feedback handoff',
                '',
                'Rule: source hints are candidates; verify screenshot, target, and code before editing.',
                '',
                'Package: ' + (state.session.packageName || 'unknown'),
                'Annotations: ' + list.length,
                ''
              ];
              const screensById = {};
              (state.session.screens || []).forEach(screen => {
                if (screen && screen.screenId) screensById[screen.screenId] = screen;
              });
              const grouped = {};
              list.forEach(item => {
                const id = item.screenId || 'unknown-screen';
                if (!grouped[id]) grouped[id] = [];
                grouped[id].push(item);
              });
              let counter = 0;
              Object.keys(grouped).forEach(screenId => {
                lines.push.apply(lines, compactScreenHeader(screenId, screensById[screenId]));
                lines.push('');
                const overlapGroups = compactDetectOverlap(grouped[screenId]);
                overlapGroups.forEach((group, groupIdx) => {
                  const isOverlap = group.length > 1;
                  if (isOverlap) {
                    lines.push('Overlap group ' + (groupIdx + 1) + ' (resolve one marker at a time):');
                  }
                  group.forEach(item => {
                    counter += 1;
                    lines.push.apply(lines, compactItemLines(item, counter, isOverlap));
                    lines.push('');
                  });
                });
              });
              return lines.join('\n').replace(/\n+$/, '\n');
            }
```

- [ ] Replace the existing `currentAnnotationsPrompt` function so it now delegates to `currentAnnotationsPromptCompact`. The legacy `Likely Source:` block in `prompt.js` is removed:

```javascript
            function currentAnnotationsPrompt(annotations) {
              return currentAnnotationsPromptCompact(annotations);
            }
```

- [ ] Run: `node --check fixthis-mcp/src/main/resources/console/app.js` and `node --check fixthis-mcp/src/main/console/prompt.js`. Confirm syntax pass.

- [ ] If `fixthis-mcp/src/main/resources/console/app.js` is generated/concatenated, regenerate it. If it is hand-edited, manually mirror the same change. Inspect the file to determine which (`grep -n "currentAnnotationsPrompt" fixthis-mcp/src/main/resources/console/app.js`).

Commit: `feat(console): COMPACT prompt token shape, top-level rule, screen grouping`.

### Step 7.3 - Sanity-run JS overlap behavior with a tiny Node fixture

- [ ] Create a quick standalone fixture script (test-only) at `fixthis-mcp/src/test/resources/parity/run-overlap.js`:

```javascript
#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const promptSourcePath = path.join(__dirname, '..', '..', 'main', 'console', 'prompt.js');
const promptSource = fs.readFileSync(promptSourcePath, 'utf8');
const sandbox = {
  state: { session: { items: [] } },
  promptItemBounds: (item) => (item.target && item.target.boundsInWindow) || null,
  Math: Math, Number: Number, Object: Object, String: String, Array: Array, Set: Set, Map: Map,
  console: console
};
const context = vm.createContext(sandbox);
vm.runInContext(promptSource, context, { filename: promptSourcePath });

const items = [
  { target: { type: 'visual_area', boundsInWindow: { left: 0, top: 0, right: 100, bottom: 100 } } },
  { target: { type: 'visual_area', boundsInWindow: { left: 99, top: 99, right: 200, bottom: 200 } } }
];
const groups = context.compactDetectOverlap(items);
if (groups.length !== 1) {
  console.error('expected 1 overlap group, got ' + groups.length);
  process.exit(1);
}
console.log('OK');
```

- [ ] Run `node fixthis-mcp/src/test/resources/parity/run-overlap.js`. Confirm output `OK`.

Commit: `test(console): sanity-run JS overlap detector against tiny fixture`.

---

## Phase 8 - Parity, fixture, and prompt-budget tests

Goal: prove JS and Kotlin emit equivalent COMPACT prompts; assert prompt size shrinks vs. baseline.

### Step 8.1 - Create parity fixtures

- [ ] Create `fixthis-mcp/src/test/resources/parity/session.json`. Use a minimal multi-item session with one strict-comp source candidate. The fixture is the canonical session shape produced by `FeedbackQueueFormatter.toJson` for a hand-built `SessionDto`.

```json
{
  "schemaVersion": "1.0",
  "sessionId": "session-parity",
  "packageName": "io.beyondwin.fixthis.sample",
  "projectRoot": "/repo",
  "createdAtEpochMillis": 1,
  "updatedAtEpochMillis": 2,
  "screens": [
    { "screenId": "screen-1", "capturedAtEpochMillis": 1, "displayName": "Checkout" }
  ],
  "items": [
    {
      "itemId": "item-1",
      "screenId": "screen-1",
      "createdAtEpochMillis": 2,
      "updatedAtEpochMillis": 2,
      "target": { "type": "semantics_node", "nodeUid": "btn", "boundsInWindow": { "left": 10.0, "top": 20.0, "right": 110.0, "bottom": 70.0 } },
      "selectedNode": {
        "uid": "btn", "composeNodeId": 42, "rootIndex": 0, "treeKind": "MERGED",
        "boundsInWindow": { "left": 10.0, "top": 20.0, "right": 110.0, "bottom": 70.0 },
        "text": ["Pay now"], "role": "Button", "testTag": "comp:AppPrimaryButton:primary"
      },
      "sourceCandidates": [
        {
          "file": "AppPrimaryButton.kt", "line": 42, "score": 0.95,
          "matchedTerms": ["AppPrimaryButton"],
          "matchReasons": ["selected testTag", "selected testTag convention composable"],
          "confidence": "HIGH",
          "ranking": 1, "scoreMargin": 1.0,
          "evidenceStrength": "STRONG",
          "riskFlags": []
        }
      ],
      "comment": "Increase button contrast",
      "sequenceNumber": 1
    }
  ]
}
```

- [ ] Create `fixthis-mcp/src/test/resources/parity/expected-prompt.txt` (UTF-8, single trailing newline):

```
FixThis feedback handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

Package: io.beyondwin.fixthis.sample
Annotations: 1

Screen screen-1: Checkout

1. [marker 1] Increase button contrast
   target: Button "Pay now" bounds=10,20,110,70
   src? AppPrimaryButton.kt:42 high; why=tag+compTag

```

(Bounds are emitted by `formatBounds(item.target.boundsInWindow)` which renders integers when the rect uses integer values; if the JS path emits `10.0,20.0,...`, update the expected file to match. Run the harness once and capture exact output.)

- [ ] Create `fixthis-mcp/src/test/resources/parity/run-prompt.js` (Node harness using `vm.createContext`):

```javascript
#!/usr/bin/env node
// Test-only Node harness. Loads checked-in prompt.js inside a sandboxed
// vm.createContext with a minimal stub global. Not used in production.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const sessionPath = process.argv[2];
if (!sessionPath) {
  console.error('Usage: node run-prompt.js <session.json>');
  process.exit(2);
}

const session = JSON.parse(fs.readFileSync(sessionPath, 'utf8'));
const promptSourcePath = path.join(__dirname, '..', '..', 'main', 'console', 'prompt.js');
const promptSource = fs.readFileSync(promptSourcePath, 'utf8');

const sandbox = {
  state: { session: session },
  error: { textContent: '' },
  promptActionInFlight: false,
  console: console,
  // Stubs for environment-dependent helpers prompt.js relies on:
  currentPromptAnnotations: () => session.items || [],
  selectedHistorySummary: () => null,
  formatSessionLabel: () => '',
  promptItemTitle: (item, idx) => {
    const c = String(item.comment || '').split('\n')[0] || '';
    return c || ('Annotation ' + (idx + 1));
  },
  promptItemBounds: (item) => {
    if (item.target && item.target.boundsInWindow) return item.target.boundsInWindow;
    return null;
  },
  formatBounds: (b) => b.left + ',' + b.top + ',' + b.right + ',' + b.bottom,
  promptScalarValue: (v) => {
    const s = String(v == null ? '' : v).trim();
    return s || null;
  },
  Math: Math, Number: Number, Object: Object, String: String, Array: Array,
  Set: Set, Map: Map
};

const context = vm.createContext(sandbox);
vm.runInContext(promptSource, context, { filename: promptSourcePath });

const out = context.currentAnnotationsPromptCompact(session.items || []);
process.stdout.write(out + (out.endsWith('\n') ? '' : '\n'));
```

The harness uses `vm.createContext` (a controlled V8 sandbox); it does not use `eval`. The script is checked-in test-only code; production never executes `prompt.js` this way.

- [ ] Run `node fixthis-mcp/src/test/resources/parity/run-prompt.js fixthis-mcp/src/test/resources/parity/session.json` from the repo root. Capture output. If it does not match `expected-prompt.txt`, write the captured output to `expected-prompt.txt` so the test pins the JS output exactly. Iterate on bounds-format rendering until output matches.

Commit: `test(mcp): add JS/Kotlin compact prompt parity fixture`.

### Step 8.2 - `PromptParityTest`

- [ ] Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/PromptParityTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PromptParityTest {
    @Test
    fun jsAndKotlinCompactPromptsMatchExpectedFixture() {
        val resourceRoot = File("src/test/resources/parity")
        val sessionFile = File(resourceRoot, "session.json")
        val expectedFile = File(resourceRoot, "expected-prompt.txt")
        val runner = File(resourceRoot, "run-prompt.js")
        assumeTrue("parity fixtures present", sessionFile.exists() && expectedFile.exists() && runner.exists())

        val nodeAvailable = nodeOnPath()
        val expected = expectedFile.readText().trimEnd('\n')

        val sessionText = sessionFile.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionText)
        val kotlinMarkdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        // Compare only the compact-token lines:
        val kotlinCompactLines = kotlinMarkdown.lines().filter { it.trim().startsWith("src?") }
        val expectedCompactLines = expected.lines().filter { it.trim().startsWith("src?") }
        assertEquals(expectedCompactLines, kotlinCompactLines)

        if (!nodeAvailable) return

        val process = ProcessBuilder("node", runner.absolutePath, sessionFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trimEnd('\n')
        process.waitFor()
        assertEquals(expected, output)
    }

    private fun nodeOnPath(): Boolean = try {
        val process = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        process.waitFor()
        process.exitValue() == 0
    } catch (_: Throwable) {
        false
    }
}
```

- [ ] Run `./gradlew :fixthis-mcp:test --tests "*PromptParityTest*"`. Pass when both Kotlin and Node agree.

Commit: `test(mcp): pin JS/Kotlin compact prompt parity`.

### Step 8.3 - Prompt-budget test

- [ ] Add to `FeedbackQueueFormatterTest.kt`:

```kotlin
@Test
fun compactPromptIsShorterThanPreciseForRepresentativeSession() {
    val session = sessionWithTargetEvidenceAndSources()
    val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
    val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
    assertTrue(
        "expected COMPACT (${compact.length}) shorter than PRECISE (${precise.length})",
        compact.length < precise.length,
    )
}
```

- [ ] Run `./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest*"`. Pass.

Commit: `test(mcp): COMPACT prompt size is below PRECISE for representative fixture`.

---

## Phase 9 - Documentation, smoke, final validation

### Step 9.1 - Update `docs/feedback-console-contract.md`

- [ ] If the file does not yet exist, create it with the structure documented in `docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md`. Otherwise, append a section "Compact handoff schema" that documents:
  - top-level rule line.
  - screen header (`Screen <id>: <displayName>` + optional `screenshot:` and `overlay:` lines).
  - item lines (`N. [marker N] ...`, `target:`, optional `crop:`, `src? file:line confidence; why=...; risk=...`).
  - reason-token mapping table from Phase 0.5.
  - confidence is lowercase.
  - overlap groups: header, `targetRisk=overlap`, "resolve one marker at a time". Coordinate space: window pixels with default density 1.0; `24dp` center-distance fallback is conservative on high-density screens.

Validation: visual review.

Commit: `docs(console): document compact handoff schema`.

### Step 9.2 - Update `docs/mcp.md`

- [ ] Add a section listing the new optional `SourceCandidate` fields and explaining backward-compatibility (older sessions deserialize correctly; the formatter emits new fields only when present).

Commit: `docs(mcp): document new SourceCandidate optional fields`.

### Step 9.3 - Update `CHANGELOG.md`

- [ ] Add entries:

```
- SourceMatcher confidence is now margin- and evidence-aware. HIGH is reserved for strong evidence with a clear top-vs-next margin. Visual-area, text-only, nearby-only, activity-only, arbitrary-literal, and legacy-fallback matches carry explicit risk caps and caution text.
- SourceCandidate gains optional ranking, scoreMargin, evidenceStrength, riskFlags, and caution metadata. Older persisted sessions remain compatible.
- The console "Copy Prompt" / "Send Agent" output and DetailMode.COMPACT Markdown switch to a compact `src? file:line confidence; why=tokens; risk=token` shape with a single top-level verification rule and screen-level screenshot context. PRECISE/FULL output is unchanged.
- Same-screen annotation targets that overlap (visual-area intersection, IoSA >= 0.25, or weak-label center distance <= 24dp at default density) are split into explicit overlap groups in compact handoff.
```

Commit: `docs(changelog): record source candidate confidence and compact handoff changes`.

### Step 9.4 - Manual smoke

- [ ] Build CLI and MCP installs to confirm nothing broke at the assembly level:

```
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
```

- [ ] Sanity-check the bundled console asset:

```
node --check fixthis-mcp/src/main/resources/console/app.js
```

- [ ] Run `git diff --check`. No whitespace errors.

### Step 9.5 - Final validation gate

- [ ] Run the full validation suite from the plan input:

```
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

- [ ] Confirm all green. Record `PASS` in this file's task notes.

Commit: `chore: final validation pass for source candidate confidence handoff`.

---

## Phase 0 Outputs

### 0.1 Constants

```
SourceMatcher confidence constants (final, locked):
- CLEAR_MARGIN = 0.20            // strict separation required for HIGH eligibility
- CLOSE_RACE_MARGIN = 0.15       // below this, attach AMBIGUOUS risk and downgrade one level
- AMBIGUITY_GAP_POLICY: scoreMargin in [0.15, 0.20) is the "MEDIUM ceiling" zone:
    confidence is capped at MEDIUM, no AMBIGUOUS risk is flagged, no caution from margin.
- scoreMargin formula: (topScore - nextScore) / max(topScore, 0.001)
- Single-candidate result: scoreMargin = 1.0
- Empty result list: no candidate emitted, no margin
- Score fed into the formula is the post-normalization candidate score in [0.0, 1.0]
  (i.e., SourceCandidate.score = rawScore / HIGH_CONFIDENCE_SCORE coerced to [0,1]).
- SourceCandidate.score is unchanged from today's normalization. The new fields are additive.
```

### 0.2 Evidence classification

| Reason string emitted by matcher | Signal kind / origin | Strength |
| --- | --- | --- |
| `selected testTag convention composable` (parsed `comp:<Composable>:<variant>` testTag matched against composable symbol) | from `STRICT_COMP_TEST_TAG` or `COMPOSABLE_SYMBOL` typed signal, OR legacy `symbols`/`file` fallback | `STRONG` |
| `selected testTag` (selected testTag value matches a typed `STRICT_COMP_TEST_TAG` or `TEST_TAG` signal) | from typed `STRICT_COMP_TEST_TAG`/`TEST_TAG` signal | `STRONG` |
| `selected testTag` (selected testTag value matched only via legacy `testTags` list with no typed signal) | legacy fallback | `WEAK` (counted under `legacy fallback`) |
| `selected text` matched via `UI_TEXT` typed signal | typed `UI_TEXT` | `MEDIUM` |
| `selected text` matched via `STRING_RESOURCE` typed signal | typed `STRING_RESOURCE` | `MEDIUM` (also emit `selected stringResource` reason; see step 0.5) |
| `selected text` matched only via `ARBITRARY_STRING_LITERAL` | typed `ARBITRARY_STRING_LITERAL` | `WEAK` (also emit `arbitrary literal` reason; see step 0.5) |
| `selected text` matched only via legacy text/excerpt fallback | legacy | `WEAK` (also emit `legacy fallback` reason) |
| `selected contentDescription` matched via `CONTENT_DESCRIPTION` or `STRING_RESOURCE` typed signal | typed | `MEDIUM` |
| `selected contentDescription` matched via `ARBITRARY_STRING_LITERAL` | typed literal | `WEAK` |
| `selected role` (combined with at least one other selected typed signal) | typed `ROLE` | `MEDIUM` |
| `selected role` (only signal present from selected node) | typed `ROLE` | `WEAK` |
| `nearby text` / `nearby contentDescription` / `nearby testTag` / `nearby role` | any kind | `WEAK` |
| `activity` | typed `ACTIVITY_NAME` or legacy | `WEAK` |
| `selected stringResource` (new reason, emitted when a typed `STRING_RESOURCE` signal carried the selected term) | typed `STRING_RESOURCE` | `MEDIUM` |
| `arbitrary literal` (new reason, emitted when only `ARBITRARY_STRING_LITERAL` matched) | typed `ARBITRARY_STRING_LITERAL` | `WEAK` |
| `legacy fallback` (new reason, emitted when only legacy non-signal lists matched) | legacy `text`/`testTags`/`symbols`/`excerpt`/etc. | `WEAK` |

Consolidation rule (review finding #8): when both `selected testTag` (literal) and `selected testTag convention composable` fire on the same candidate, the candidate has `STRONG` evidence once. Never double-count for confidence classification (the existing `score = max(...)` clamp already prevents score double-counting; this rule extends to evidence counting).

### 0.3 Confidence classification rules

```
Confidence classification rules (post-margin, per candidate):

1. Compute EvidenceProfile from the candidate's matched reason set.
2. Compute scoreMargin from sorted candidates (single -> 1.0, empty -> n/a).
3. Determine baseConfidence:
   - HIGH if (strongEvidenceCount >= 1 AND scoreMargin >= CLEAR_MARGIN)
       OR (mediumEvidenceCount >= 2 AND mediumEvidenceCount counts >= 2 distinct
           selected medium evidence kinds AND scoreMargin >= CLEAR_MARGIN).
   - MEDIUM if (selected UI text OR selected contentDescription) AND another
       selected signal (any strength), regardless of margin (subject to caps).
   - MEDIUM if strong evidence with scoreMargin in [CLOSE_RACE_MARGIN, CLEAR_MARGIN).
   - LOW otherwise (provided rawScore > 0).
   - NONE if no evidence and rawScore == 0.
4. Apply caps (lower bound only; never upgrade):
   - text-only selected match (only "selected text" fired) -> max MEDIUM (TEXT_ONLY).
   - nearby-only match (no selected reason fired, only nearby reasons) -> max LOW (NEARBY_ONLY).
   - activity-only match (only "activity" reason fired) -> max LOW (ACTIVITY_ONLY).
   - arbitrary literal-only match (only "arbitrary literal" fired) -> max LOW (ARBITRARY_LITERAL).
   - legacy-only match (only "legacy fallback" fired) -> max LOW (LEGACY_FALLBACK).
   - visual area selection (passed in by caller) -> max LOW (AREA_SELECTION).
5. Apply ambiguity downgrade:
   - if scoreMargin < CLOSE_RACE_MARGIN AND candidate count >= 2:
     downgrade by one level (HIGH -> MEDIUM, MEDIUM -> LOW, LOW unchanged) and
     attach AMBIGUOUS risk.
   - if CLOSE_RACE_MARGIN <= scoreMargin < CLEAR_MARGIN AND baseConfidence == HIGH:
     cap to MEDIUM. No AMBIGUOUS risk attached.
6. Risk flags accumulate in this iteration order (deterministic):
   AMBIGUOUS, AREA_SELECTION, TEXT_ONLY, NEARBY_ONLY, ARBITRARY_LITERAL,
   ACTIVITY_ONLY, LEGACY_FALLBACK.
7. Final confidence is determined after both caps and ambiguity downgrade.
   The resulting evidenceStrength is STRONG if hasStrong, MEDIUM if hasMedium
   else WEAK (unaffected by margin/caps).
```

### 0.4 Risk precedence and caution generator

```
Risk precedence (highest first):
  AMBIGUOUS > AREA_SELECTION > TEXT_ONLY > NEARBY_ONLY > ARBITRARY_LITERAL >
  ACTIVITY_ONLY > LEGACY_FALLBACK

Caution generator (used by SourceInterpretationFactory):

Inputs: confidence, riskFlags (already ordered by precedence).

If riskFlags is empty and confidence in {HIGH, MEDIUM}: caution = null.
If riskFlags is empty and confidence == LOW: caution =
    "Top source candidate has low confidence; verify before editing."
If riskFlags is empty and confidence == NONE: caution =
    "No source candidate was available from current evidence."

Otherwise pick the highest-precedence flag and emit:
  AMBIGUOUS         -> "Verify this source candidate before editing; top candidates are close."
  AREA_SELECTION    -> "Visual-area selection; use screenshot and bounds before editing."
  TEXT_ONLY         -> "Text-only match; confirm against screenshot and code."
  NEARBY_ONLY       -> "Nearby-only match; confirm against screenshot and code."
  ARBITRARY_LITERAL -> "Match relied on a generic string literal; confirm against screenshot and code."
  ACTIVITY_ONLY     -> "Activity-only match; confirm against screenshot and code."
  LEGACY_FALLBACK   -> "Legacy-fallback match; confirm against screenshot and code."

Caution is also written to SourceCandidate.caution (top candidate only; non-top
candidates may set caution from their own flags).
```

### 0.5 New reason emissions and token mapping

Reason emission additions (Phase 3 will implement):

1. When the matched signal kind is STRING_RESOURCE and the originating reason
   bucket would be "selected text" or "selected contentDescription", also add
   "selected stringResource" to matchReasons.
2. When ALL the matched terms came only via ARBITRARY_STRING_LITERAL signals
   (no UI_TEXT / CONTENT_DESCRIPTION / TEST_TAG / STRICT_COMP_TEST_TAG /
   ROLE / ACTIVITY_NAME / COMPOSABLE_SYMBOL signals fired), append
   "arbitrary literal" exactly once.
3. When ALL the candidate's matched terms came only via the legacy fallback
   (signal-less candidates list), append "legacy fallback" exactly once.

These are derived after scoring; they do not change rawScore. Their reason
strings are stable identifiers used by the formatter mapping.

| Reason string | Token |
| --- | --- |
| `selected text` | `text` |
| `selected contentDescription` | `contentDescription` |
| `selected testTag` | `tag` |
| `selected testTag convention composable` | `compTag` |
| `selected role` | `role` |
| `nearby text` | `nearbyText` |
| `nearby contentDescription` | `nearbyContentDescription` |
| `nearby testTag` | `nearbyTag` |
| `nearby role` | `nearbyRole` |
| `activity` | `activity` |
| `selected stringResource` | `stringRes` |
| `arbitrary literal` | `literal` |
| `legacy fallback` | `legacy` |

### 0.6 Parity fixtures

```
Parity fixtures (Phase 8 will create):

- fixthis-mcp/src/test/resources/parity/session.json
- fixthis-mcp/src/test/resources/parity/expected-prompt.txt
- fixthis-mcp/src/test/resources/parity/run-prompt.js (Node harness)

Parity runner approach: PromptParityTest spawns
`node parity/run-prompt.js parity/session.json` from a Gradle test, captures
stdout, normalizes line endings, and compares against expected-prompt.txt. The
Node harness loads `prompt.js` source as a string, then evaluates the script
inside a fresh `vm.createContext({ ... })` sandbox with a controlled set of
helper stubs (no DOM, no network). This is a checked-in test fixture only;
production code never executes prompt.js this way.

The Kotlin formatter is exercised in the same test by deserializing
parity/session.json into a SessionDto, calling
FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT), and asserting
that the COMPACT Markdown contains the same set of compact-token lines (`src?`,
`why=`, `risk=`) the JS prompt produces, after a normalizer that strips Markdown
headers and per-screen verification rule lines.

If `node` is not on PATH, the test marks itself SKIPPED with a clear reason
("node not available; install Node 18+ to run parity test"). It does NOT fail.
```

### 0.7 Overlap detector coordinate space

```
Overlap detector coordinate space:

- All bounds passed to AnnotationOverlapDetector are FixThisRect values from
  AnnotationDto.target (Node.boundsInWindow or Area.boundsInWindow). Compose
  capture pipeline already normalizes these to window coordinates in pixels.
- IoSA (intersection-over-smaller-area) is dimensionless. Threshold 0.25 is
  unit-independent and applies directly.
- For the visual-area-vs-visual-area case, ANY non-zero intersection counts
  (the threshold is effectively 0.0 IoSA for that case).
- For the center-distance fallback (24dp), the detector accepts an optional
  density parameter (Float, default 1.0). The threshold is computed as
  24f * density window-pixels. Sessions persisted today do not record density,
  so the default-1.0 path is used; FixThis docs add a note that this is
  conservative for high-density screens (more overlaps reported, fewer missed).
- A future migration may capture density alongside bounds; until then the
  default is documented in CHANGELOG.md and docs/feedback-console-contract.md.
```

### 0.8 Test impact inventory

| File | Line | Current expectation | New expectation under Phase 3 rules | Justification |
| --- | --- | --- | --- | --- |
| `SourceMatcherTest.kt` | 51 | `SelectionConfidence.HIGH` for top candidate | `HIGH` retained | Selected text + testTag (literal STRONG) + role + activity vs second candidate (text + activity only). Normalized topScore approx 0.9, nextScore approx 0.4, scoreMargin > 0.5 >= CLEAR_MARGIN. Strong evidence + clear margin -> HIGH. |
| `SourceMatcherTest.kt` | 208 | `SelectionConfidence.MEDIUM` at score 0.65 | `HIGH` (intentional update) | Selected `comp:` testTag with strict-comp-style match against single candidate (margin = 1.0). Strong evidence + single candidate -> HIGH. Test must update. |
| `SourceInterpretationFactoryTest.kt` | 20-27 | Caution null for HIGH | Caution null retained | No risk flags on construction; HIGH+empty flags -> caution null. |
| `SourceInterpretationFactoryTest.kt` | 80-90 | Default fixture HIGH | Caution null retained | Same as above. |
| `TargetEvidenceModelTest.kt` | 95, 127, 147 | HIGH source confidence | Retained where the fixture exposes strong evidence and a single candidate; downgrade to MEDIUM if the fixture has multiple close candidates and the new rules require it. Verify each fixture; default to MEDIUM if uncertain and update the assertion. | New rules can downgrade. |
| `FixThisMarkdownFormatterTest.kt` | 79, 121, 326, 374, 447 | HIGH visible in PRECISE/FULL Markdown | PRECISE/FULL Markdown unchanged because tokens still come from `confidence.name.lowercase()` | Wire format change is COMPACT-only. |
| `FixThisMarkdownFormatterTest.kt` | 334, 342, 399, 422 | MEDIUM visible | Same | Same. |
| `FeedbackQueueFormatterTest.kt` | 62 | HIGH single source candidate, asserts `Likely Source:` block | Default mode is PRECISE; PRECISE keeps `Likely Source:` block. No change. | Default mode locked to PRECISE. |
| `FeedbackQueueFormatterTest.kt` | 180 | HIGH on visual-area candidate, output `low confidence` | Same. Visual-area cap unchanged at the formatter layer. | Existing area cap preserved by formatter. |
| `FeedbackQueueFormatterTest.kt` | 325 | HIGH single source candidate, asserts no internal IDs | Same. | PRECISE format unchanged. |
| `FeedbackQueueFormatterTest.kt` | 553, 561 | HIGH/MEDIUM in PRECISE/FULL | Same. | PRECISE/FULL unchanged. |
| `FeedbackSessionStoreTest.kt` | 659 | HIGH on persisted candidate | Same. Backward-compatible deserialization (Phase 1) preserves field. | No behavior change in store. |

(The plan does not edit `NodeSelectorTest.kt:44`; that test asserts `SelectionInfo.confidence`, not `SourceCandidate.confidence`, and is out of scope.)

---

## Design Reference (verbatim)

(The design spec text from the plan input is reproduced here so this plan stands alone.)

> Source matching should optimize for safe guidance, not false certainty. A
> candidate that is probably useful but not clearly unique should remain visible,
> but its confidence and caution should make the uncertainty obvious.
>
> Agent handoff should separate screen-level evidence from item-level evidence.
> One screen can have one full screenshot and one numbered overlay, while each
> feedback item carries its own marker number, target labels, bounds, optional
> crop, source candidate, and risk flags.
>
> Markdown is for action. JSON is for completeness. The compact prompt should not
> repeat raw evidence that is better stored in JSON, but it should expose enough
> context for an agent to know what to inspect next.

The full design spec text is preserved in the plan input message and should be
attached to PR descriptions when this plan ships.
