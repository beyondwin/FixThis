# Trust Program Phase 2 — Handoff Rendering Design

Date: 2026-05-25
Status: Ready for user review
Scope: agent-facing PRECISE/FULL Markdown handoff rendering in
`fixthis-mcp` — make source confidence, target reliability, edit-surface
role, and warning guidance legible without changing persisted JSON or
core matcher policy.

Parent program: [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
(Phase 2: Handoff Trust).

## Summary

The compact handoff renderer (`CompactHandoffRenderer`) already exposes
`editSurfaceCandidates`, `targetReliability`, rank-1 cautions, and
warning lines. The PRECISE/FULL Markdown renderer
(`FeedbackQueueFormatter`) does not — it shows source candidates with
matched terms and reasons, plus a thin target-confidence line, but
omits edit-surface candidates, rank-1 caution notes, source/edit-surface
role separation, and condition-specific guidance for visual-area or
view-interop targets.

This is an inversion: the **compact** prompt currently carries more
trust information than the **long-form** prompt that agents read by
default. Phase 2 closes that gap by extending `FeedbackQueueFormatter`
only. Core policy, persisted JSON, and the compact renderer are not
touched.

## Product Goal

Make PRECISE/FULL Markdown handoffs answer, for every item:

- Which file is the visible text's source, and how confident are we?
- Where should the agent actually edit (color, typography, spacing,
  component renderer)?
- How much should the selected target be trusted before editing?
- For visual-area or view-interop targets, what should the agent do
  instead of trusting source paths?

## Non-Goals

- No changes to `:fixthis-compose-core` policy, scoring, or evidence
  profiles.
- No changes to `CompactHandoffRenderer` output (frozen for this phase).
- No new persisted JSON fields or enum values. JSON additivity contract
  preserved as-is.
- No new Compose matcher patterns (Phase 3 scope).
- No new Gradle source-index signals.
- No new fixture-lab cases (Phase 1 scope; existing trust manifest
  unchanged).
- No new MCP API surface or session schema bumps.
- No console UI changes.

## Current State

`fixthis-mcp/src/main/kotlin/.../mcp/session/FeedbackQueueFormatter.kt`
renders three modes:

- `COMPACT` → dispatches to `CompactHandoffRenderer.render(session)`.
- `PRECISE` → `appendFeedbackItem` with `sourceCandidateLimit = 3`.
- `FULL` → `appendFeedbackItem` with `sourceCandidateLimit = Int.MAX_VALUE`.

The PRECISE/FULL path emits:

- `Target:` block with optional `targetReliability` confidence line and
  one Warning line per `TargetReliabilityWarning`.
- `Likely Source:` block with one numbered entry per source candidate,
  showing path, owner, confidence, optional stale-marker suffix,
  optional `matched:` line, and optional `reasons:` line.

It does not emit:

- `editSurfaceCandidates` in any form.
- `sourceCandidate.caution` text for the rank-1 candidate.
- Pairing or separation between text-origin and edit-surface evidence.
- Condition-specific action guidance for visual-area, view-interop,
  no-meaningful-Compose-target, or sensitive-text-redacted cases.

All eight `TargetReliabilityWarning` enum values already have
`handoffMessage()` strings defined in
`fixthis-compose-core/.../model/Models.kt`, so Phase 2 needs only
rendering changes.

## Architecture

Single-file extension of `FeedbackQueueFormatter`. New private helpers
extend the existing `appendLikelySource` and `appendTargetReliability`
paths.

```
FeedbackQueueFormatter (PRECISE | FULL)              [modified]
  appendTarget(item)
    appendTargetReliability(reliability)             [extended]
      appendVisualInteropGuidance(warnings)          [new]
  appendLikelySource(sourceCandidates, target, max)  [extended]
    buildEditSurfacePairing(item)                    [new]
    for each sourceCandidate (rank):
      appendSourceCandidateLine(candidate, rank)     [extended]
      appendEditSurfaceSubLines(paired[rank])        [new]
    appendRank1Caution(sourceCandidates.first)       [new]
    appendOrphanEditSurfaces(orphans)                [new]
```

`CompactHandoffRenderer` is unchanged. Compact and PRECISE/FULL share
no rendering code in this phase — the two output styles (compact
inline tokens vs. Markdown bullets) make a shared abstraction
premature; revisit only if Phase 3 introduces tokens that both
renderers must surface.

## Components

### Edit-Surface Pairing

Given an `AnnotationDto`, group its `editSurfaceCandidates` into either
a per-source-candidate sub-list or an orphan list.

```
buildEditSurfacePairing(item) ->
  paired:  Map<sourceCandidateIndex, List<EditSurfaceCandidate>>
  orphans: List<EditSurfaceCandidate>
```

Algorithm:

1. For each `editSurfaceCandidate e`, iterated in the input order of
   `item.editSurfaceCandidates`:
   - If `e.file` is blank or null, classify as orphan.
   - Otherwise, find the first `sourceCandidate` (by ranking order) whose
     `file` equals `e.file` exactly. If found, append `e` to that
     candidate's paired list. Otherwise, classify as orphan.
2. Trim each per-source paired list to at most 2 entries, preserving
   input order.
3. Trim the orphan list to at most 2 entries, preserving input order.

Owner-symbol matching is intentionally out of scope; file equality is
the V1 rule. If matching turns out to be too coarse, extend in a
follow-up — not in this phase.

### Source Candidate Line (extended)

Existing format kept verbatim:

```
1. `<file>#<owner>()` <confidence> confidence<stale-suffix>
   - matched: `<term>`, `<term>`
   - reasons: <REASON_A>, <REASON_B>
```

When the candidate has paired edit-surface entries, append one bullet
per paired entry:

```
   - edit: <kindToken><roleToken> -> `<file>:<line>` (conf=<level>, why=<reasons>)
```

Token rules (mirror `CompactHandoffRenderer.formatEditSurfaceLine`):

- `kindToken`: `containerColor` | `textColor` | `typography` | `spacing`
  | `chipColor` | `componentRenderer` | `unknown`.
- `roleToken`: ` role=<lower-kebab>` if `role != null`, else empty.
- File suffix: `file:line` if `line != null`, else `file`.
- `conf=<lower>` from `EditSurfaceConfidence`.
- `why=<csv>` of lower-kebab `reasons`.

If `editSurfaceCandidate.note` is present and non-blank, add an
additional bullet directly under the same `edit:` line:

```
   - edit-note: <note>
```

(`edit-note` is used instead of `note:` so that rank-1 source cautions
(rendered as `note:` at the candidates-block level) and per-edit notes
do not collide visually.)

### Rank-1 Source Caution

After all source candidate entries have been emitted, if the first
candidate carries a non-blank `caution`, append:

```
- note: <caution>
```

Only rank-1's caution is rendered (matches `CompactHandoffRenderer`
behavior).

### Orphan Edit Surfaces Block

If `orphans` is non-empty, append a sub-section to `Likely Source:`:

```
Edit Surfaces (unpaired):
1. <kindToken><roleToken> -> `<file>:<line>` (conf=<level>, why=<reasons>)
2. ...
```

The unpaired block follows the same indentation as paired entries: when
an unpaired `editSurfaceCandidate.note` is non-blank, render it as a
sub-bullet under the numbered entry:

```
Edit Surfaces (unpaired):
1. <kindToken>... -> `<file>:<line>` (conf=..., why=...)
   - edit-note: <note>
```

When `sourceCandidates` is empty but `editSurfaceCandidates` is not,
the `Likely Source:` section opens with:

```
No source candidate; edit-surface hints:
1. <kindToken>... -> `<file>:<line>` (conf=..., why=...)
```

When both lists are empty, the existing message is preserved
exactly:

```
No source candidate from current evidence; search by target labels and request.
```

### Target Reliability — Action Lines

`appendTargetReliability` retains its existing two-line shape:

```
- Target confidence: <level> - <preciseActionGuidance>.
- Warning: <handoffMessage>.
```

After the last Warning line, if the warnings include any of the four
condition-specific values, append one `Action:` line per matched
condition, in this stable enum-order:

| Warning                          | Action line text                                                                                              |
|----------------------------------|---------------------------------------------------------------------------------------------------------------|
| `VISUAL_AREA_ONLY`               | `use screenshot/bounds first, then check whether Compose source explains the pixels.`                         |
| `POSSIBLE_VIEW_INTEROP`          | `treat source candidates as hints only; AndroidView/WebView may own the pixels.`                              |
| `NO_MEANINGFUL_COMPOSE_TARGET`   | `no Compose semantics node covers this — search by surrounding labels.`                                       |
| `SENSITIVE_TEXT_REDACTED`        | `source candidates were ranked without the redacted text — corroborate before editing.`                       |

Format:

```
- Action: <text>
```

Other warnings (`LOW_SOURCE_CANDIDATE_MARGIN`, `SOURCE_INDEX_STALE`,
`SCREEN_FINGERPRINT_MISMATCH_FORCED`, `SCREEN_FINGERPRINT_UNAVAILABLE`)
produce only the existing Warning line — they already convey actionable
guidance in the warning message itself.

## Data Flow

```
Core (fixthis-compose-core)                       [unchanged]
  SourceMatcher -> List<SourceCandidate>
  EditSurfaceCalculator -> List<EditSurfaceCandidateDto>
  TargetReliabilityCalculator -> TargetReliability(confidence, warnings, ...)
  ↓ (additive optional fields on AnnotationDto)
MCP session persistence                            [unchanged]
  ↓
FeedbackQueueFormatter (PRECISE | FULL)           [modified — Phase 2]
  appendTarget()
    appendTargetReliability()
      appendVisualInteropGuidance()  <-- new
  appendLikelySource()
    buildEditSurfacePairing()       <-- new
    appendSourceCandidateLine()
      appendEditSurfaceSubLines()   <-- new
    appendRank1Caution()             <-- new
    appendOrphanEditSurfaces()       <-- new
  ↓
Markdown output -> MCP session response -> agent
```

Render ordering invariants:

- Source candidates retain core ranking order.
- Per-candidate `edit:` sub-lines retain input order.
- Rank-1 caution appears after all candidate entries, before any orphan
  block.
- Orphan edit-surface block appears after the rank-1 caution.
- Action lines always appear after all Warning lines under
  `Target:`.

## Error Handling

| Input state                                                | Rendering behavior                                                                                |
|------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `targetReliability == null` (old session)                  | No `Target confidence` line, no Warning lines, no Action lines. Existing behavior preserved.      |
| `confidence == UNKNOWN`, `warnings.isEmpty()`              | Existing literal line preserved: `Target confidence: unknown - verify manually before editing.`    |
| `editSurfaceCandidates` empty (old session or no signal)   | No `edit:` sub-lines anywhere; no orphan block.                                                   |
| `sourceCandidates` empty + `editSurfaceCandidates` empty   | Existing literal preserved: `No source candidate from current evidence; search by target labels and request.` |
| `sourceCandidates` empty + `editSurfaceCandidates` non-empty | New header: `No source candidate; edit-surface hints:` followed by numbered orphan list.        |
| `sourceCandidate.caution` null/blank                       | No `note:` line for that candidate.                                                               |
| `editSurfaceCandidate.line == null`                        | Render `file` only (matches Compact).                                                             |
| `editSurfaceCandidate.role == null`                        | Omit `role=` token entirely.                                                                      |
| Multiple source candidates share the same `file`           | Edit-surface pairs to the highest-ranked match only; duplicates never produced.                   |
| `editSurfaceCandidate.file` blank/null                     | Classified as orphan.                                                                             |
| `VISUAL_AREA_ONLY` present                                 | Source candidates still rendered as hints; Action line appended.                                  |
| `POSSIBLE_VIEW_INTEROP` present                            | Source candidates still rendered as hints; Action line appended.                                  |
| Both `VISUAL_AREA_ONLY` and `SENSITIVE_TEXT_REDACTED`      | Two Action lines, in enum order.                                                                  |

Markdown safety:

- All paths, notes, warning messages, and Action text pass through the
  existing `inlineSafe()` utility to neutralize backticks and pipes.

Compact-vs-PRECISE drift policy:

- The two renderers retain different formats by intent. Equivalence is
  asserted only at the token level (presence of file paths, confidence
  levels, warning bodies) — not at line-count or character-level.

## Testing

### Unit tests (new or extended)

Target file:
`fixthis-mcp/src/test/kotlin/.../session/FeedbackQueueFormatterPhase2Test.kt`
(new) or appended to the existing `FeedbackSessionServiceTest.kt`
suite — whichever keeps the test surface most focused.

Pairing and rendering:

1. Same-file source ↔ edit pair renders as inline `edit:` sub-line.
2. Unpaired edit-surface appears in `Edit Surfaces (unpaired):` block.
3. Empty source + non-empty edit-surface emits the `No source candidate;
   edit-surface hints:` header.
4. Empty source + empty edit-surface preserves the legacy literal.
5. Rank-1 caution renders once, after all candidate entries.
6. Rank-2 caution is never rendered.
7. Per-source `edit:` sub-line cap = 2; additional pairs are dropped.
8. Orphan block cap = 2.
9. `role == null` produces no `role=` token.
10. `line == null` produces `file` only (no `:line` suffix).
11. PRECISE renders at most 3 source candidates; FULL renders all.
12. Markdown escaping holds for backtick/pipe-bearing file paths and
    notes.

Visual/Interop Action lines:

13. `VISUAL_AREA_ONLY` → exact Action text emitted.
14. `POSSIBLE_VIEW_INTEROP` → exact Action text emitted.
15. `NO_MEANINGFUL_COMPOSE_TARGET` → exact Action text emitted.
16. `SENSITIVE_TEXT_REDACTED` → exact Action text emitted.
17. `SOURCE_INDEX_STALE` alone → Warning line only, no Action line.
18. Action lines always appear after the last Warning line.
19. Multiple Action-qualifying warnings render in stable enum order.

Compatibility regression guards:

20. `targetReliability == null`: byte-equal output vs. pre-Phase-2
    snapshot for an old-session fixture.
21. `editSurfaceCandidates.isEmpty()`: byte-equal output vs.
    pre-Phase-2 snapshot for an item with only source candidates.
22. `sourceCandidate.caution == null`: no `note:` line emitted.

### Corpus regression (`HandoffEvaluationCorpusTest`)

23. Existing corpus snapshots updated only for items that now legitimately
    gain new lines (paired edit-surface, caution, or Action). All other
    snapshots stay byte-equal.
24. One new corpus case exercising paired edit-surface output — snapshot
    locks the expected Markdown.

### Renderer equivalence sanity

25. For a single shared `AnnotationDto`, assert Compact and PRECISE
    outputs both contain:
    - the same `targetReliability.confidence` literal (lowercase),
    - each `editSurfaceCandidate.file` substring,
    - each warning's `handoffMessage()` body substring.

    Do not compare line counts, ordering, or whitespace.

### Validation commands

Fast:

```
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackQueueFormatter*' \
  --tests '*FeedbackSessionService*' \
  --tests '*HandoffEvaluationCorpus*'
```

Full local matrix before PR: `CONTRIBUTING.md` § Required Local Checks.

## Implementation Notes

- All new helpers stay private to `FeedbackQueueFormatter`. No new
  public API.
- No new files unless a focused test class makes maintenance cleaner.
- Detekt budgets in `:fixthis-mcp` may need a small `complexity`
  refresh if the formatter grows past the current threshold; prefer
  splitting helpers over raising the budget.
- No changes to `:fixthis-compose-core`, `:fixthis-android-sidekick`,
  or the Gradle plugin.

## Success Criteria

- Every PRECISE/FULL handoff for an item with `editSurfaceCandidates`
  shows the paired or orphan `edit:` line(s).
- Every PRECISE/FULL handoff with a rank-1 `caution` shows the
  `note:` line.
- Every PRECISE/FULL handoff with one of the four Action-qualifying
  warnings shows the matching Action line, in enum order.
- Existing handoffs without any of the new signals remain byte-equal
  to their pre-Phase-2 output.
- Compact and PRECISE renderers agree on confidence levels, file
  identifiers, and warning bodies (token-level equivalence test
  passes).
- No new persisted JSON fields. No new enum values. No new core
  policy. No changes to `CompactHandoffRenderer`.

## Out of Scope (Phase 3 Hand-off)

- New Compose matcher patterns (Layout/SubcomposeLayout owner signals,
  shared component renderer hints, indirect string/resource patterns,
  container/style call-site evidence).
- Fixture-lab manifest expansion for new pattern coverage.
- Shared trust-rendering module across Compact and PRECISE — revisit
  only when Phase 3 introduces tokens that both renderers must surface
  identically.
