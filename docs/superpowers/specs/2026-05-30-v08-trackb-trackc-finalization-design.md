# v0.8 Finalization Umbrella: Track B Pattern Expansion + Track C SSE Sync Design

Date: 2026-05-30
Status: Ready for user review
Scope: Complete the two remaining v0.8 tracks in a single umbrella — Track B
(source-matcher pattern expansion, the remaining "name conventions" portion) and
Track C (SSE console sync finalization). Track A (shared-component call-site
context + ranking) already shipped.

Related work:

- [v0.8 Source Matching Depth & Console Sync Umbrella](2026-05-29-v08-source-matching-depth-console-sync-umbrella-design.md)
- [Shared-Component Call-Site Ranking](2026-05-30-shared-component-callsite-ranking-design.md)
- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Console state sync](../../architecture/console-state-sync-design.md)
- [v0.7 Studio Reliability Umbrella](2026-05-18-v07-studio-reliability-umbrella-design.md)

## Summary

The 2026-05-29 v0.8 umbrella framed three independent tracks. Track A (shared
component call-site inventory plus call-site ranking) has fully landed. This spec
closes the remaining two tracks so the full v0.8 release claim can ship:

> FixThis source matching stays honest and explainable: more Compose patterns
> resolve with evidence-backed confidence, and the console runs push-first
> without redundant polling when SSE is healthy.

Both tracks are independent and may land in either order; suggested order is
Track B first (small, additive) then Track C (evidence-gated removal on a stable
baseline).

## Why Now

- Track B is the last roadmap item under "smarter source matching." The
  `Layout(...)` / `SubcomposeLayout(...)` wrapper recognition already shipped as
  the `LAYOUT_RENDERER` typed signal. The roadmap's remaining Track B item is "a
  small, enumerated set of additional composable-name conventions." The matcher
  already has the typed-signal and fixture-lab discipline to add them safely.
- Track C is the remaining SSE work. SSE is the primary session/preview path;
  the roadmap calls for removing manual recovery and polling "only after local
  evidence shows it is unused." v0.7 left an explicit open decision to defer
  fallback-file renames until a clear fallback-only call surface exists.

The gap is not missing primitives. The matcher has typed source-index signals
and the trust fixture lab; the console has SSE with replay/snapshot recovery and
reliability proof infrastructure. This umbrella finishes both lines.

## Product Goal

This umbrella supports the source-matching and console portions of the v0.8
release claim:

> More Compose patterns resolve with evidence-backed, explainable confidence,
> and a healthy console performs zero redundant polling while preserving every
> explicit recovery path.

The claim is about honesty and explainability, not exact source-line edits. New
patterns are bounded by their supporting evidence; polling removal is bounded by
recorded evidence that a branch never fires under a healthy session.

## Non-Goals

- No confidence inflation. New Track B patterns reuse existing reasons/weights or
  add only medium-bounded hints; no pattern makes a candidate HIGH on its own.
- No open-ended "recognize all naming conventions" scope. Track B adds exactly
  the two enumerated additions specified below — nothing inferred at runtime.
- No persisted MCP JSON field rename (`items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, `sourceCandidates`,
  `editSurfaceCandidates`, `callSites`). New fields/enum members are additive
  only; this umbrella adds none to the wire.
- No new `:fixthis-compose-core` dependency on MCP, CLI, Android UI, local
  artifact paths, or `.fixthis/`. The clean-architecture ratchet stays green.
- No removal of every polling path in Track C in one step. Removal is
  evidence-gated and preserves explicit recovery.
- No consolidation of the five polling modules into one in Track C. Track C
  clarifies the fallback-only surface and renames; it does not re-architect the
  module split (that would add regression risk without proportional value).
- No release-build sidekick behavior; no XML/View, WebView DOM, Flutter, React
  Native, iOS, or cloud workflow expansion.

## Current Baseline

### Track B baseline

- The Gradle plugin's `KotlinSourceScanner` emits `COMPOSABLE_SYMBOL` for
  `@Composable fun Name(...)` declarations and `LAYOUT_RENDERER` for `Layout(`
  and `SubcomposeLayout(` call sites inside composable owners.
- `SourceMatcher` (in `:fixthis-compose-core`) scores `LAYOUT_RENDERER` via
  `LAYOUT_RENDERER_BASE_WEIGHT = 0.75` and records the explainable reason
  `LAYOUT_RENDERER_CONTEXT`. It surfaces this as a medium-confidence edit-surface
  hint when the selected target uses a strict `comp:<Composable>:...` test tag;
  the signal does not by itself make a candidate HIGH.
- The test-tag convention is governed by **two** regexes that must stay in sync:
  - Gradle/index side: `KotlinSemanticSignalScanner.kt`'s
    `strictCompTestTagRegex = comp:[A-Za-z_][A-Za-z0-9_]*:.+`. A source testTag
    matching this emits the `STRICT_COMP_TEST_TAG` signal (via
    `KotlinSourceScanner.addSignal(... STRICT_COMP_TEST_TAG ...)`).
  - Core/identity side: `TestTagConvention.kt`'s
    `^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$`, which parses the *selected
    node's* testTag to extract the composable `<Name>` for owner resolution.
  - A strict-tag match ultimately produces the
    `SELECTED_TEST_TAG_CONVENTION_COMPOSABLE` match reason, scored at
    `SELECTED_TEST_TAG_CONVENTION_SCORE = 65.0`.
- The trust fixture lab is driven by `scripts/source-matching-fixtures.mjs`, with
  cases declared in `fixtures/source-matching/manifest.json` against pinned
  external Compose-sample commits (`source-index` and `runtime-trust` modes).

### Track C baseline

- `/api/events` streams snapshot, session, sessions, device, connection, and
  preview events; SSE is the primary path with polling retained as fallback.
- Five console modules carry polling/fallback responsibilities:
  - `previewPoll.js` — late-preview-poll session-equality gate
    (`dropStalePreviewPoll`); drops responses whose `sessionId` no longer matches
    the active session.
  - `sessions-polling.js` — owns the `setInterval` handle
    (`SessionsPollIntervalMs = 2000`) and the top-level identifiers
    (`pollSessionsTick` / `startSessionsPolling` / `withMutationLock`) that the
    asset-contract tests grep for; delegates logic to `pollingUseCases.js`.
  - `pollingFsm.js` — pure reducer for the sessions-polling lifecycle (no DOM,
    fetch, timers, or globals).
  - `pollingUseCases.js` — pure action dispatchers over the FSM and an async
    sessions port.
  - `pollingBrowserAdapter.js` — wires the use cases into the browser with real
    fetches and side effects.
- `npm run console:reliability:test` and `npm run console:browser:reliability`
  proof runners exist. The healthy-SSE session is documented to no longer rely on
  automatic preview/session polling, but no harness assertion records whether the
  fallback branches actually fire, and the fallback start/stop conditions and
  dead branches are not yet narrowed or removed.

## Track B: Source-Matcher Pattern Expansion

### Goal

Recognize an explicit, enumerated set of two additional composable-name
conventions, each gated by a fixture-lab case, without raising confidence beyond
the supporting evidence.

### Addition B1 — Test-Tag Convention Vocabulary (deterministic, zero-risk)

The recognizer currently accepts only `comp:<Name>:<id>`. Extend the enumerated
convention set with exactly two additional unambiguous formats:

- `screen:<Name>:<id>` — a screen-level convention.
- `comp.<Name>.<id>` — a dot-delimited variant of the existing colon form.

**Index/core layer.** Generalize **both** convention regexes — in lockstep — from
a single pattern to the enumerated set above (an explicit alternation, not an
open pattern):

- `KotlinSemanticSignalScanner.kt`'s `strictCompTestTagRegex` (so source-side
  tags in the new formats still emit `STRICT_COMP_TEST_TAG`), and
- `TestTagConvention.kt`'s parser (so the selected node's tag in the new formats
  yields the same `Parsed(composableName, variant)` and feeds owner resolution).

Both must accept the same enumerated set; drift between them would index a tag
the runtime can't parse, or vice versa. A match on any enumerated format produces
the **same** `STRICT_COMP_TEST_TAG` signal and the **same**
`SELECTED_TEST_TAG_CONVENTION_COMPOSABLE` match reason and score (`65.0`) as the
existing `comp:` form. The extracted `<Name>` feeds composable owner resolution
identically.

**Why this is zero confidence-inflation risk.** No new reason, signal kind, or
score is introduced; the additional formats map onto the existing strict-tag
path, so a target tagged `screen:Cart:checkout` scores exactly as a target tagged
`comp:Cart:checkout` would. The only change is which literal strings the
recognizer accepts.

**Boundary.** The enumerated set is closed: `comp:`, `screen:` (colon), and
`comp.` (dot). No other prefixes or delimiters are inferred. A tag that matches
none of the enumerated formats is treated exactly as today (plain `TEST_TAG`).

### Addition B2 — Slot-Wrapper Composable Recognition (LAYOUT_RENDERER class)

Some custom layout wrappers do not call `Layout(`/`SubcomposeLayout(` directly
but expose a content slot — `content: @Composable (...) -> Unit` — and render it.
A node selected inside such a wrapper should resolve to the wrapper's owning
composable, the same way the existing `LAYOUT_RENDERER` pass handles
`Layout`/`SubcomposeLayout`.

**Index layer (Gradle).** Extend the scanner so that a composable declaring a
content-slot parameter of the shape `content: @Composable ... -> Unit` (the
canonical Compose slot signature) attaches a `LAYOUT_RENDERER` signal to its
owning composable entry, reusing the existing `LAYOUT_RENDERER` machinery. The
detection is a conservative declaration-shape match on the parameter signature;
it does not attempt to prove the slot is actually invoked.

**Core layer.** No new reason or weight. The slot-wrapper match reuses the
existing `LAYOUT_RENDERER` signal kind, `LAYOUT_RENDERER_CONTEXT` reason, and
`LAYOUT_RENDERER_BASE_WEIGHT = 0.75`. Confidence is therefore bounded exactly as
the current layout-renderer hint: a medium edit-surface hint, never HIGH on its
own.

**Boundary.** Recognition is limited to the canonical `content: @Composable
(...) -> Unit` slot-parameter shape. Other slot names or trailing-lambda
conventions are out of scope for this iteration (they can be a future enumerated
addition).

### Trust discipline (both additions)

Each addition gets a fixture-lab case under `fixtures/source-matching/` (declared
in `manifest.json`, driven by `scripts/source-matching-fixtures.mjs`) added as a
failing case **first**, then implemented to green. Existing fixture expectations
must stay green — no new flags or confidence changes on previously-stable
targets.

### Acceptance (Track B)

- A target tagged with any of `comp:<Name>:<id>`, `screen:<Name>:<id>`, or
  `comp.<Name>.<id>` resolves the composable owner via the existing
  `SELECTED_TEST_TAG_CONVENTION_COMPOSABLE` reason at the existing score; a tag
  matching none is treated as a plain `TEST_TAG` (unchanged).
- A node inside a composable whose declaration exposes a
  `content: @Composable (...) -> Unit` slot resolves to that wrapper composable
  via the existing `LAYOUT_RENDERER_CONTEXT` reason, bounded to a medium hint.
- Each addition has a passing fixture-lab case; no regression on existing fixture
  expectations.
- No new `SourceMatchReason` member, no new `SourceSignalKind` member, no new
  score constant; no persisted/wire field change. `BridgeProtocol.VERSION` is
  verified unchanged.

## Track C: SSE Console Sync Finalization

### Goal

Remove fallback polling and manual-recovery branches that recorded local
evidence shows never fire under a healthy EventSource session, keeping every
explicit recovery path (manual refresh, reconnect, replay-overflow →
authoritative snapshot). Clarify the fallback-only module surface, settling the
v0.7 deferred rename decision.

### Design

**Step 1 — Evidence first.** Strengthen the reliability harness
(`npm run console:browser:reliability` / the reliability proof runner) to record,
during a healthy SSE session, whether any fallback branch fires:

- preview-poll auto-start,
- session polling auto-start (`startSessionsPolling` tick under healthy SSE),
- lockstep pull refreshes,
- manual-recovery branches.

The harness asserts **zero** automatic preview/session polling on a healthy
EventSource session. This instrumentation lands first and must pass on the
current code before any removal — establishing the baseline that the removed
branches were genuinely dead under health.

**Step 2 — Evidence-gated removal.** Remove only branches proven unused under
healthy SSE. Prime candidates, in order:

- preview-poll auto-start (the automatic start of preview polling while a healthy
  SSE preview stream is delivering),
- lockstep pull refreshes (pull-based session refresh that duplicates pushed SSE
  session events).

Before deleting, narrow each branch's start/stop conditions so it is reachable
only on the fallback (SSE-unavailable / degraded) path. Explicit recovery
requests (manual refresh, reconnect, replay-overflow → snapshot) are untouched.

**Step 3 — Settle the v0.7 open decision.** Once the surviving polling behavior
is narrowed to fallback-only, rename or clearly mark the affected modules as
fallback-only (e.g., the preview/session polling entry points). The five-module
split is preserved; only the surface is clarified. The asset-contract grep
identifiers (`pollSessionsTick` / `startSessionsPolling` / `withMutationLock`)
are kept or their contract test updated in lockstep if a rename touches them.

### Acceptance (Track C)

- A healthy EventSource session performs zero automatic preview or session
  polling, asserted by the reliability harness.
- Removed fallback branches cause no reconnect/recovery regression in the browser
  reliability scenarios.
- Fallback-only modules are renamed or clearly marked fallback-only, resolving
  the v0.7 deferred decision; asset-contract identifier grep stays green (or its
  test is updated in lockstep).
- `console:reliability:test` and `console:browser:reliability` proof stay green.

## Data Flow

### Track B — Pattern Resolution Flow

1. The scanner recognizes (a) an enumerated test-tag convention format, emitting
   `STRICT_COMP_TEST_TAG`, or (b) a content-slot wrapper declaration, attaching
   `LAYOUT_RENDERER` to the owning composable entry.
2. `SourceMatcher` scores the entry through the existing strict-tag /
   layout-renderer paths, recording the existing reason and producing confidence
   bounded by the existing weight.
3. A fixture-lab case asserts the expected reason/confidence before the addition
   ships.

### Track C — Console Sync Flow

1. SSE delivers session/preview events through the existing shared apply paths.
2. The reliability harness records whether any fallback poll/recovery branch
   fires during a healthy session, and asserts zero auto-polling.
3. Branches with no evidence of firing under health are narrowed to fallback-only
   reachability, then removed; explicit recovery remains; fallback-only modules
   are renamed/marked.

## Error Handling

- **Unknown test-tag format (B1):** a tag matching none of the enumerated
  formats falls through to plain `TEST_TAG` handling; no crash, no convention
  reason.
- **Non-slot composable (B2):** a composable without the canonical content-slot
  signature attaches no `LAYOUT_RENDERER` signal; behavior unchanged.
- **Malformed signal (Track B):** an unrecognized or malformed signal is ignored
  by core without raising confidence; no crash.
- **SSE unavailable (Track C):** fallback polling still starts; only
  healthy-session auto-polling is removed.
- **Replay overflow / reconnect (Track C):** explicit recovery requests an
  authoritative snapshot, unchanged.

## Testing Strategy

### Track B

- Gradle: each enumerated test-tag format produces `STRICT_COMP_TEST_TAG` with
  the correct extracted `<Name>`; a non-enumerated tag does not. Content-slot
  wrapper declaration attaches `LAYOUT_RENDERER`; a non-slot composable does not.
- Core: enumerated formats score via the existing strict-tag reason at the
  existing score; slot-wrapper scores via the existing layout-renderer reason,
  bounded to medium.
- Fixture lab: one case per addition (B1 and B2) asserting the expected
  reason/confidence; regression cases stay green.

### Track C

- Reliability harness asserts zero automatic preview/session polling on a healthy
  SSE session (passes on current code before removal).
- Reconnect, replay-overflow, and manual-refresh recovery scenarios stay green
  after removal.
- Asset-contract identifier grep (`pollSessionsTick` / `startSessionsPolling` /
  `withMutationLock`) stays green, or its contract test is updated in lockstep
  with any rename.

### Cross-Cutting

- Clean-architecture boundary ratchet stays green (core purity).
- Full local Gradle matrix, console asset/JS syntax and harness checks, and
  `git diff --check` per `CONTRIBUTING.md` required checks before each track's PR.
- `BridgeProtocol.VERSION` impact verified for Track B (expected unchanged — no
  wire field added).

## Implementation Sequencing

Tracks are independent and may land in any order. Suggested order:

1. **Track B** first — additive, small, fixture-gated, no wire change.
2. **Track C** second — evidence-gated removal benefits from a stable baseline;
   instrument the harness, prove branches dead, narrow, remove, rename.

Within each track: add the failing fixture/contract test first, implement to
green, then run the full local matrix.

## Risks

- **Confidence inflation (Track B):** new patterns could over-claim. Mitigation:
  both additions reuse existing reasons/weights (no new score), and each is
  fixture-gated before shipping.
- **Test-tag over-matching (B1):** a broader recognizer could capture unintended
  tags. Mitigation: the set is a closed enumeration of three exact formats, not
  an open pattern; non-matching tags fall through unchanged.
- **Slot-shape false positives (B2):** the declaration-shape match could flag a
  non-wrapper. Mitigation: confidence stays a medium hint (never HIGH alone), and
  the match is limited to the canonical `content: @Composable (...) -> Unit`
  signature; a fixture case pins expected behavior.
- **Over-aggressive polling removal (Track C):** removing fallback too early
  could hurt reconnect recovery. Mitigation: instrument and prove dead under
  health first, narrow start/stop to fallback-only before deleting, keep explicit
  recovery.
- **Contract drift (Track C):** a rename could break the asset-contract grep.
  Mitigation: keep the grep identifiers or update the contract test in lockstep
  with the rename.

## Open Decisions

- Track B enumerated set is fixed by this spec: test-tag formats `comp:`,
  `screen:` (colon) and `comp.` (dot); slot-wrapper signature `content:
  @Composable (...) -> Unit`. No further conventions in this iteration.
- Track C fallback-module rename: resolved within Track C once the fallback-only
  surface is clear (settles the v0.7 deferred decision). The exact new
  names/markers are chosen during implementation once narrowing is complete; the
  five-module split is preserved.

## Approval Notes

The user approved on 2026-05-30:

1. A single umbrella spec completing the two remaining v0.8 tracks (B and C);
   Track A is already shipped and excluded.
2. Track B scope limited to two deterministic, fixture-gated additions chosen by
   recommendation: test-tag convention vocabulary extension and slot-wrapper
   composable recognition, both reusing existing reasons/weights (no confidence
   inflation, no wire change).
3. Track C scope as evidence-gated minimal removal plus fallback-only rename
   chosen by recommendation: instrument the harness, remove only branches proven
   dead under healthy SSE (preview-poll auto-start, lockstep pull), preserve
   explicit recovery, and clarify the fallback-only module surface without
   consolidating the five modules.
