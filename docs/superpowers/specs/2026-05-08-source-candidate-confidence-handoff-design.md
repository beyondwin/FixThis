# Source Candidate Confidence And Handoff Evidence Design

**Date:** 2026-05-08
**Status:** Draft for review
**Related work:** `docs/superpowers/specs/2026-05-08-project-improvement-stabilization-design.md`
**Primary modules:** `fixthis-compose-core`, `fixthis-mcp`, `fixthis-gradle-plugin`, `sample`, project docs

## Purpose

FixThis source candidates are useful agent hints, but they are not guaranteed
file or line mappings. The next improvement should make those hints safer and
more compact: source matching should become more conservative when evidence is
weak or ambiguous, JSON should preserve enough diagnostic metadata to debug
ranking decisions, and agent-facing Markdown should tell agents to verify
source hints against code and screenshots before editing.

The desired product behavior is:

- agents receive concise target and source context;
- source hints are framed as candidates, not instructions;
- ambiguous matches carry visible risk flags;
- screenshots and item markers help disambiguate visual feedback;
- JSON remains complete and backward-compatible;
- no user app code is modified to add required test tags.

## Current State

The project already has the necessary foundation:

- Source Index v2 typed `signals` distinguish UI text, string resources, test
  tags, strict `comp:` convention tags, content descriptions, roles, activity
  names, and arbitrary literals.
- `SourceMatcher` ranks candidates and uses typed signal weights, but candidate
  confidence still depends mainly on absolute raw score.
- `SourceCandidate` contains file, line, score, matched terms, match reasons,
  and `SelectionConfidence`.
- `TargetEvidence.sourceInterpretation` summarizes the top source candidate and
  carries low-confidence caution text.
- `DetailMode.COMPACT/PRECISE/FULL` exists for Markdown output.
- The feedback console saves pending annotations when `Copy Prompt` or
  `Send Agent` persists a frozen preview into a session evidence snapshot.
- One persisted screen can already hold multiple feedback items.

The remaining issue is calibration. A candidate can look too authoritative when
the top two candidates are close, when the match is text-only, or when evidence
comes mostly from nearby nodes or arbitrary string literals. The console prompt
also still repeats verbose fields and uses `Likely Source`, which can make
heuristic hints sound more definitive than they are.

## Goals

- Make `HIGH` confidence rare and defensible.
- Include top-candidate margin and evidence strength in confidence decisions.
- Keep strict `comp:<ComposableName>:<variant>` tags as optional high-value
  evidence without making test tags required.
- Cap confidence for text-only, nearby-only, activity-only, area-selection, and
  arbitrary-literal matches.
- Add optional source-candidate metadata for diagnostics and prompt rendering.
- Make default console handoff Markdown compact, candidate-oriented, and
  guarded by a single top-level verification rule.
- Preserve full JSON evidence for tools and debugging.
- Include screenshot, overlay, and crop references when they reduce item
  ambiguity.
- Detect overlapping annotation targets and avoid sending them as one compact
  agent task unless the handoff makes the separation explicit.
- Add tests that measure confidence safety, prompt shape, prompt size, and
  fixture-level matching behavior.

## Non-Goals

- Do not automatically insert `Modifier.testTag(...)` into user source.
- Do not require all Composables to have tags.
- Do not replace the source-index regex extractor with Kotlin PSI in this
  workstream.
- Do not send screenshots to an external AI API from FixThis.
- Do not duplicate full screenshots per annotation when multiple annotations
  share one frozen preview.
- Do not remove existing JSON fields or break existing persisted sessions.

## Design Principles

Source matching should optimize for safe guidance, not false certainty. A
candidate that is probably useful but not clearly unique should remain visible,
but its confidence and caution should make the uncertainty obvious.

Agent handoff should separate screen-level evidence from item-level evidence.
One screen can have one full screenshot and one numbered overlay, while each
feedback item carries its own marker number, target labels, bounds, optional
crop, source candidate, and risk flags.

Markdown is for action. JSON is for completeness. The compact prompt should not
repeat raw evidence that is better stored in JSON, but it should expose enough
context for an agent to know what to inspect next.

## Source Matching Model

`SourceMatcher` should compute an internal evidence profile for each candidate
before converting it to `SourceCandidate`.

Suggested internal fields:

```kotlin
private data class EvidenceProfile(
    val rawScore: Double,
    val strongEvidenceCount: Int,
    val mediumEvidenceCount: Int,
    val weakEvidenceCount: Int,
    val selectedEvidenceCount: Int,
    val nearbyEvidenceCount: Int,
    val hasStrictCompTag: Boolean,
    val hasSelectedTestTag: Boolean,
    val hasSelectedUiText: Boolean,
    val hasSelectedContentDescription: Boolean,
    val hasSelectedRole: Boolean,
    val hasArbitraryLiteral: Boolean,
    val hasActivityOnly: Boolean,
)
```

Evidence categories:

- Strong evidence:
  - selected strict `comp:` test tag;
  - selected testTag convention composable match;
  - exact selected testTag match from a typed `TEST_TAG` or
    `STRICT_COMP_TEST_TAG` signal.
- Medium evidence:
  - selected UI text from a typed `UI_TEXT` signal;
  - selected content description from a typed `CONTENT_DESCRIPTION` signal;
  - selected role when combined with another selected signal;
  - selected string resource match.
- Weak evidence:
  - arbitrary string literal;
  - nearby-only text, content description, test tag, or role;
  - activity-only match;
  - legacy excerpt or symbol fallback without typed selected evidence;
  - partial text containment matches.

The matcher should rank by raw score first, but confidence should use both the
candidate's evidence profile and the score margin against the next candidate.

Margin fields:

- `scoreMargin`: normalized top candidate score minus next candidate score.
- `ranking`: one-based candidate rank after sorting.
- `isCloseRace`: true when `scoreMargin` is below the ambiguity threshold.

Recommended initial thresholds:

- `HIGH` requires strong selected evidence and a meaningful margin, or multiple
  independent selected medium evidence types with a meaningful margin.
- `MEDIUM` allows selected UI text or selected content description plus another
  signal, or strong evidence with a small margin.
- `LOW` covers weak, nearby-only, area-derived, activity-only, and close-race
  candidates.
- `NONE` remains for no match.

Initial margin constants:

- `CLEAR_MARGIN = 0.20`: enough separation for a candidate to remain eligible
  for `HIGH` when its evidence profile qualifies.
- `CLOSE_RACE_MARGIN = 0.15`: below this margin, add `AMBIGUOUS` and downgrade
  confidence by one level.
- Single-candidate result sets treat margin as `1.0`, but confidence caps still
  apply.

Confidence caps:

- text-only selected match: maximum `MEDIUM`;
- nearby-only match: maximum `LOW`;
- activity-only match: maximum `LOW`;
- arbitrary-literal-only match: maximum `LOW`;
- visual area selection: maximum `LOW` unless a future item-level crop/marker
  classifier proves stronger evidence;
- close top-2 margin: downgrade one level and add `ambiguous` risk.

These thresholds should start conservative. If later fixture and agent
benchmarks show too many useful candidates are downgraded, the caps can be
relaxed with evidence.

## Source Candidate Metadata

Extend `SourceCandidate` with optional/default fields so existing persisted JSON
and readers remain compatible:

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

Suggested enums:

```kotlin
@Serializable
enum class SourceEvidenceStrength {
    STRONG,
    MEDIUM,
    WEAK,
}

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

`caution` should be generated from risk flags and confidence. It should be short
and agent-facing, for example:

- `Verify this source candidate before editing; top candidates are close.`
- `Text-only match; confirm against screenshot and code.`
- `Visual-area selection; use screenshot and bounds before editing.`

`SourceInterpretationFactory` should use the candidate's own caution when
present, and should add caution for `MEDIUM` candidates when risk flags include
`AMBIGUOUS`, `TEXT_ONLY`, or `AREA_SELECTION`.

## Screenshot And Marker Evidence

Screenshots are valuable evidence, but they should not bloat every item.

Persistence policy:

- `Annotate` freezes the latest preview.
- `Add annotation` creates browser-side pending items only.
- `Copy Prompt` and `Send Agent` persist written pending items and promote the
  frozen preview into one durable evidence snapshot.
- One frozen preview maps to one persisted `screenId`, one full screenshot, and
  many feedback items.
- Full screenshot artifacts stay screen-level.
- Item crops and marker references are item-level.

Recommended artifact policy:

- Always preserve the screen-level full screenshot path when available.
- Generate or preserve a numbered overlay screenshot when a persisted screen has
  more than one item, or when any item is a visual-area selection.
- Generate item-level crops for visual-area selections and for ambiguous source
  candidates when crop data is available from the existing screenshot.
- Do not duplicate the full screenshot per item.
- Keep screenshots local under `.fixthis/feedback-sessions/`.
- Continue documenting that screenshots can contain sensitive data and should be
  reviewed before sharing exported artifacts.

Overlap policy:

- Detect overlap among persisted items that share the same `screenId`.
- Treat items as overlapping when their bounds intersect with meaningful area,
  when one item mostly contains another, or when two visual-area selections have
  near-identical centers.
- Compact handoff should not silently batch overlapping items as ordinary
  sibling tasks.
- Default behavior should split overlapping items into separate agent handoff
  groups. A single `Send Agent` action can still create one persisted handoff
  batch for audit history, but the Markdown snapshot should render overlap
  groups separately with clear headers.
- If overlapping items must appear in one prompt, each item must include a
  marker, bounds, crop if available, and `targetRisk=overlap`; the prompt should
  tell the agent to resolve one marker at a time.
- Non-overlapping items from the same screen can remain grouped under one
  screen-level screenshot because marker numbers and bounds are enough to keep
  them distinct.

Recommended initial overlap thresholds:

- Any non-zero intersection between two visual-area selections counts as
  overlapping.
- Node or mixed node/area targets count as overlapping when intersection-over-
  smaller-area is at least `0.25`.
- Near-identical center fallback applies when center distance is under `24px`
  and either target has weak semantic labels.

If overlay generation is too large for the first implementation slice, the
minimum viable version is:

- include stable item marker numbers in Markdown;
- include bounds for every item;
- include full screenshot path once per screen;
- split overlapping items into separate Markdown groups;
- add crop paths only when already available.

Overlay generation can then follow as a second slice without changing the
source-confidence model.

## Handoff Markdown Contract

Default console `Copy Prompt` and `Send Agent` output should use compact
Markdown.

Top-level guardrail appears once:

```text
Rule: source hints are candidates; verify screenshot, target, and code before editing.
```

Screen-level context appears once per screen:

```text
Screen screen-1: Checkout
screenshot: .fixthis/feedback-sessions/.../screen-1-full.png
overlay: .fixthis/feedback-sessions/.../screen-1-marked.png
```

When overlapping targets exist, render separate groups instead of one ordinary
screen item list:

```text
Overlap group A: handle one marker at a time.
screen: screen-1 Checkout
screenshot: .fixthis/feedback-sessions/.../screen-1-full.png

1. [marker 1] Move the card title down
target: Text "Revenue" bounds=40,120,220,152 targetRisk=overlap
crop: .fixthis/feedback-sessions/.../item-1-crop.png
src?: DashboardCard.kt:44 medium; why=text; risk=text_only

Overlap group B: handle one marker at a time.
screen: screen-1 Checkout
screenshot: .fixthis/feedback-sessions/.../screen-1-full.png

2. [marker 2] Increase the card padding
target: Card bounds=24,96,360,220 targetRisk=overlap
crop: .fixthis/feedback-sessions/.../item-2-crop.png
src?: DashboardCard.kt:31 medium; why=nearbyText+role; risk=nearby_only
```

Item-level context is compact and independent:

```text
1. [marker 1] Make the login button text bigger
target: Button "Login" bounds=40,620,320,672
crop: .fixthis/feedback-sessions/.../item-1-crop.png
src?: LoginScreen.kt:42 medium; why=text+role; risk=ambiguous
```

Prompt rules:

- Use `src?` instead of `Likely Source`.
- Use `why=` with compressed reason tokens, not long `matchedTerms` and
  `matchReasons` lists.
- Use `risk=` only when risk flags exist.
- Default to top 1 source candidate.
- Include top 2 or top 3 only when the top candidate is ambiguous, low
  confidence, area-derived, or in a close race.
- Omit default `Severity` and `Status`.
- Do not repeat the screenshot path for each item on the same screen.
- Split overlapping targets into separate Markdown groups, or add
  `targetRisk=overlap` and one-marker-at-a-time instructions when a single
  prompt is unavoidable.
- For `DetailMode.PRECISE`, include more target evidence and up to 3 source
  candidates.
- For `DetailMode.FULL`, include raw matched terms, match reasons, scores,
  margins, risk flags, screenshot metadata, and all candidates.

Reason token mapping:

- `selected text` -> `text`
- `selected contentDescription` -> `contentDescription`
- `selected testTag` -> `tag`
- `selected testTag convention composable` -> `compTag`
- `selected role` -> `role`
- `nearby text` -> `nearbyText`
- `nearby contentDescription` -> `nearbyContentDescription`
- `nearby testTag` -> `nearbyTag`
- `nearby role` -> `nearbyRole`
- `activity` -> `activity`

## Detailed Implementation Plan

### Phase 1: Contract Tests For Conservative Confidence

Add failing tests before implementation:

- text-only selected UI text never becomes `HIGH`;
- two close candidates downgrade the top candidate and add `AMBIGUOUS`;
- strict `comp:` tag can produce strong evidence when the margin is clear;
- nearby-only match is capped at `LOW`;
- arbitrary-literal-only match is capped at `LOW`;
- area-derived source candidates carry `AREA_SELECTION` risk in handoff output.

Primary test file:

- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

### Phase 2: Source Candidate Metadata

Add optional metadata fields and enums to the core model. Keep defaults so
serialization remains additive. Update any tests that construct
`SourceCandidate` only if Kotlin default parameters are insufficient.

Primary files:

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModels.kt`
- `docs/output-schema.md`

### Phase 3: Evidence Profile And Margin-Based Confidence

Refactor `SourceMatcher` enough to compute per-candidate evidence profiles,
score margins, rankings, risk flags, evidence strength, and caution text.

Keep ranking behavior deterministic:

- raw score descending;
- file path ascending;
- line ascending with nulls last.

Then compute rank and margin after sorting.

Primary file:

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`

### Phase 4: Source Interpretation Cautions

Update `SourceInterpretationFactory` so target evidence reflects the richer
candidate caution. It should caution on low confidence, ambiguous medium
confidence, text-only matches, area selections, and missing source candidates.

Primary files:

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`

### Phase 5: Compact Handoff Formatter

Update Kotlin Markdown output first because it is easier to test and is used by
MCP tools. Preserve MCP wire compatibility by keeping
`FeedbackQueueFormatter.toMarkdown(session)` equivalent to `DetailMode.PRECISE`.
Add an explicit compact path for console `Copy Prompt` and `Send Agent`.

The compact formatter should:

- emit one top-level guardrail;
- group items by screen;
- show screen screenshot once;
- show item marker, request, target, bounds, optional crop, and `src?`;
- compress `why=` and `risk=`;
- include extra candidates only when ambiguous.

Primary files:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`

### Phase 6: Console Prompt Parity

Update browser console prompt generation to match the compact handoff contract.
The console should not keep a separate stale prompt format.

Primary file:

- `fixthis-mcp/src/main/console/prompt.js`

Tests should verify generated prompts from console routes or browser smoke
fixtures when practical. If direct browser prompt tests are too brittle, keep
Kotlin formatter tests as the canonical contract and make console prompt helpers
mirror the same reason/risk token rules.

### Phase 7: Screenshot, Marker, And Crop Policy

Start with the minimum viable artifact changes:

- screen-level full screenshot path appears once in Markdown when available;
- item marker numbers are stable and visible;
- bounds remain present for every item;
- overlapping item bounds are detected and rendered as separate Markdown groups;
- existing crop paths are included when available.

Then add overlay/crop generation if the first slice shows the agent still
confuses multiple annotations on one screen.

Primary files for first slice:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ScreenshotArtifactPromoter.kt`

Overlay generation can be added later as a focused artifact utility if needed.

### Phase 8: Fixture-Level Validation

Extend the sample fixture with source-confidence expectations:

- strict `comp:` tag scene;
- repeated card scene;
- generic text-only button scene;
- visual-area scene;
- arbitrary literal scene;
- weak-semantics fallback scene.

Validate:

- expected file is top 1 or top 3 depending on scene;
- false `HIGH` count is zero for known ambiguous scenes;
- caution exists when expected;
- prompt output includes marker and screenshot context for multi-item screens.
- overlapping annotations are not rendered as one ordinary same-screen task
  list.

Primary files:

- `sample/fixthis-coverage.json`
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`

### Phase 9: Agent A/B Smoke Benchmark

Create a small manual or scripted benchmark with 5 to 10 representative
feedback batches:

- one simple text change with high-confidence tag evidence;
- one repeated UI item;
- one visual spacing request;
- one ambiguous generic button;
- one multi-annotation same-screen batch;
- one weak semantics fallback.

For each batch compare old-style and new-style handoff:

- did the agent edit the correct file;
- did the agent inspect screenshot/crop before editing when source was risky;
- did the agent confuse item markers;
- did the first patch pass relevant tests;
- how long was the prompt.

This benchmark does not block unit-test completion, but it should guide any
future relaxation of confidence thresholds.

## Validation Commands

Minimum local validation:

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Full project validation when artifact or console changes are included:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

## Success Criteria

- Existing persisted sessions still deserialize.
- JSON includes additive source candidate metadata without removing old fields.
- Known text-only and ambiguous fixtures no longer produce `HIGH`.
- Strict `comp:` tag fixtures can still produce high-confidence candidates when
  the top candidate is clearly separated.
- Compact prompt uses `src?`, `why=`, and `risk=` instead of verbose
  `Likely Source`, `matched`, and `reasons` blocks.
- Multi-item same-screen handoff includes stable item markers and screen-level
  screenshot context once.
- Overlapping same-screen annotations are split into explicit overlap groups or
  marked with `targetRisk=overlap` and one-marker-at-a-time instructions.
- Prompt length for representative batches is lower than the current console
  prompt while preserving target evidence.
- Tests cover matcher confidence, source interpretation caution, formatter
  shape, and prompt budget.

## Detail Mode Default Decision

Use split defaults:

- keep MCP `detailMode` default as `PRECISE`;
- make console `Copy Prompt` and `Send Agent` explicitly compact;
- let agents request `full` through `fixthis_read_feedback` when investigating
  raw evidence.

This avoids surprising existing MCP tool users while fixing the primary console
handoff path.
