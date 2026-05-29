# v0.8 Source Matching Depth & Console Sync Finalization Umbrella Design

Date: 2026-05-29
Status: Ready for user review
Scope: v0.8 roadmap framing across three independent tracks — shared-component
call-site context, source-matcher pattern expansion, and SSE console sync
finalization.

Related work:

- [Shared Reusable Component Trust](2026-05-29-shared-reusable-component-trust-design.md)
- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)
- [v0.7 Studio Reliability Umbrella](2026-05-18-v07-studio-reliability-umbrella-design.md)
- [Console state sync](../../architecture/console-state-sync-design.md)

## Summary

v0.8 deepens source-matching honesty and finishes the SSE console migration.

The release is centered on two roadmap lines that are now ready to advance:
"smarter source matching" and "finish SSE-driven console state sync." The main
user-visible claim is:

> When FixThis points at a shared component it hands the agent the call-site
> inventory instead of just a warning, it recognizes more Compose patterns with
> explainable confidence, and a healthy console no longer runs fallback polling.

v0.8 should land as an umbrella milestone with three independent tracks:

1. **Track A: Shared-Component Call-Site Context** — extend the just-shipped
   `SHARED_COMPONENT` trust work so a shared-definition handoff carries the
   inventory of call-site locations as best-effort context, while confidence
   stays capped and no precise-target claim is made.
2. **Track B: Source-Matcher Pattern Expansion** — recognize `Layout` and
   `SubcomposeLayout` wrappers and a small, enumerated set of additional
   composable-name conventions, each gated by fixture-lab coverage before
   confidence is allowed to rise.
3. **Track C: SSE Console Sync Finalization** — remove fallback polling and
   manual-recovery code that local evidence shows unused under a healthy
   EventSource session, keeping only explicit recovery paths.

Tracks A and B advance source-matching trust; Track C closes the console
state-sync migration that v0.7 began. Each track can ship independently, but the
v0.8 release claim needs all three tracks or the release notes must narrow the
claim.

## Why Now

- Track A is the direct, deliberate follow-on to the 2026-05-29 shared-component
  work, which flagged shared definitions, capped them at MEDIUM, and emitted a
  caution telling the agent to "verify the specific call site" — without telling
  the agent where those call sites are. The fan-in pass already locates distinct
  call sites by source location; that inventory is currently discarded after
  counting.
- Track B is the next batch of patterns the roadmap explicitly lists under
  "smarter source matching" (`Layout`/`SubcomposeLayout` wrappers, more
  composable-name conventions) and the matcher already has the typed-signal and
  fixture-lab discipline to add them safely.
- Track C is the remaining SSE work the roadmap describes: SSE is now the
  primary session/preview path and the roadmap calls for removing manual
  recovery and polling "only after local evidence shows it is unused." v0.7 left
  an explicit open decision to defer fallback-file renames until a clear
  fallback-only call surface exists.

The gap is not missing primitives. FixThis already has the fan-in pass, the
typed source-index signals, the trust fixture lab, SSE with replay/snapshot
recovery, and console reliability proof infrastructure. v0.8 finishes these
three lines.

## Product Goal

v0.8 should support this release claim:

> FixThis source matching stays honest and explainable: a shared component
> definition hands over its call-site inventory rather than a precise edit
> claim, more Compose patterns resolve with evidence-backed confidence, and the
> console runs push-first without redundant polling when SSE is healthy.

The claim is intentionally about honesty and explainability, not exact
source-line edits. It does not promise that FixThis resolves which call site
rendered a selected node; it promises the agent gets enough verified context to
decide quickly.

## Non-Goals

- No runtime-to-source offset mapping. FixThis still does not resolve which
  specific call site rendered a selected node, and Track A makes no such claim.
- No precise-target claim for shared component definitions. Confidence stays
  capped at MEDIUM; the call-site list is best-effort context, not a target.
- No persisted MCP JSON field rename (`items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, `sourceCandidates`,
  `editSurfaceCandidates`). New fields and enum members are additive only.
- No new `:fixthis-compose-core` dependency on MCP, CLI, Android UI, local
  artifact paths, or `.fixthis/`. The clean-architecture ratchet stays green.
- No release-build sidekick behavior; no XML/View, WebView DOM, Flutter, React
  Native, iOS, or cloud workflow expansion.
- No requirement that the fixture lab become a CI, release, or public-install
  gate in this iteration.
- No open-ended "recognize all naming conventions" scope in Track B — the added
  conventions are an explicit, enumerated set decided during planning.
- No requirement to remove every polling path in Track C in one step; removal is
  evidence-gated and preserves explicit recovery.

## Current Baseline

- The Gradle plugin's `KotlinSemanticSignalScanner` produces typed source-index
  signals on `SourceIndexEntry`, including `SHARED_COMPONENT` (added 2026-05-29),
  `LAYOUT_RENDERER`, `COMPOSABLE_SYMBOL`, `LAMBDA_OWNER_FUNCTION`, and the
  test-tag-convention signals.
- The composable call-site fan-in pass counts distinct call sites by
  `file + offset`, excludes the declaration, and ignores strings/comments via
  the existing ignored-range helpers. It currently emits only a count as the
  `SHARED_COMPONENT` signal value.
- `SourceMatcher` (in `:fixthis-compose-core`) emits
  `SHARED_COMPONENT_DEFINITION`, adds `SourceCandidateRisk.SHARED_COMPONENT`,
  caps confidence at MEDIUM, and `SourceConfidencePolicy.cautionFor` returns a
  count-agnostic caution.
- `/api/events` streams snapshot, session, sessions, device, connection, and
  preview events; SSE is the primary path with polling retained as fallback.
- `previewPoll.js` and session polling exist as fallback/recovery adapters;
  healthy EventSource sessions are documented to no longer rely on automatic
  preview or session polling, but the fallback start/stop conditions and dead
  branches are not yet evidence-asserted or removed.
- `npm run console:reliability:test` and `npm run console:browser:reliability`
  proof runners exist.

## Track A: Shared-Component Call-Site Context

### Goal

When a candidate is flagged `SHARED_COMPONENT`, the handoff carries the
inventory of call-site locations (`file:line`) as best-effort context so the
agent can verify the right usage quickly instead of grepping. Confidence stays
capped; no precise-target claim is added.

### Design

**Index layer (Gradle).** The fan-in pass already finds distinct call sites by
`file + offset`. Extend it to retain the call-site locations, not just the
count. Because `SourceIndexEntry` signals are string-valued, add a minimal
additive serialization for the call-site list (implementation picks the minimal
encoding — e.g., an additive structured field on the index entry, or a
delimited signal value), capped at `SHARED_COMPONENT_CALLSITE_LIMIT` (= 10) to
avoid noise. Locations beyond the cap are summarized as an overflow count.

**Core layer.** When `SHARED_COMPONENT_DEFINITION` is present, populate a new
additive field `callSites: List<SourceLocationRef>` on the candidate from the
serialized signal data. Core reads the serialized data only; it gains no new
dependency and the architecture ratchet stays green.

**Output / MCP / console.** Surface `sourceCandidates[].callSites` as an
additive field. The console renders a "used at" list near the existing caution.
Absence of the field is tolerated (single-use definitions and pre-v0.8 output).

**Caution update.** Because the inventory is now actually provided, the caution
may reference it honestly: shared component used in multiple places — verify the
listed call sites before editing. The caution still does not claim which call
site rendered the selection.

### Acceptance

- A shared-component candidate's handoff includes up to
  `SHARED_COMPONENT_CALLSITE_LIMIT` call-site `file:line` references, with an
  overflow indicator when more exist.
- Confidence stays capped at MEDIUM; no precise-target language is introduced.
- A single-use composable definition carries no call-site list and is
  unaffected.
- The console renders the call-site list when present and ignores its absence.
- `BridgeProtocol.VERSION` impact is verified during implementation against
  `docs/reference/bridge-protocol.md` (expected additive, no bump, but checked).

## Track B: Source-Matcher Pattern Expansion

### Goal

Expand the set of Compose source patterns FixThis recognizes while keeping
confidence explainable and evidence-backed.

### Design

**Index layer (Gradle).** Recognize `Layout(` and `SubcomposeLayout(` wrapper
composables similarly to the existing `LAYOUT_RENDERER` pass, so a selected node
inside a custom layout wrapper resolves to the wrapper's owning composable with
an explainable signal. Recognize an explicit, enumerated set of additional
composable-name conventions (the specific set is fixed during planning, not
left open-ended).

**Core layer.** Treat the new patterns as typed signals with explainable
`SourceMatchReason` members (additive). Scoring must not inflate confidence
beyond the supporting evidence; new patterns follow the existing weight
conventions.

**Trust discipline.** Each new pattern gets a fixture-lab case under
`fixtures/source-matching/` (via `scripts/source-matching-fixtures.mjs`) before
any confidence rise is allowed to ship, consistent with the Source Matching
Trust Program. Regression fixtures must stay green.

### Acceptance

- A node inside a custom `Layout`/`SubcomposeLayout` wrapper resolves to the
  wrapper composable with an explainable reason.
- Each newly recognized name convention has a passing fixture-lab case.
- No regression on existing fixture expectations (no new flags or confidence
  changes on previously-stable targets).
- New `SourceMatchReason` members are additive; no persisted-field rename.

## Track C: SSE Console Sync Finalization

### Goal

Remove fallback polling and manual-recovery code that local evidence shows
unused under a healthy EventSource session, keeping only explicit recovery
(manual refresh, reconnect, replay overflow → authoritative snapshot).

### Design

**Evidence first.** Strengthen the local reliability harness
(`npm run console:browser:reliability` / reliability proof runner) to record
whether fallback preview/session polling or manual-recovery branches fire during
a healthy SSE session.

**Evidence-gated removal.** Remove only the fallback branches proven unused
under healthy SSE — preview-poll auto-start and lockstep pull refreshes are the
prime candidates. Explicit recovery paths stay. Narrow fallback start/stop
conditions before deleting, matching the v0.7 sequencing discipline.

**Resolve the v0.7 open decision.** v0.7 deferred renaming fallback files until
a clear fallback-only call surface existed. Track C settles that: once the
behavior is narrowed to fallback-only, rename/clarify those modules to a
fallback-only surface.

### Acceptance

- A healthy EventSource session performs zero automatic preview or session
  polling, asserted by the reliability harness.
- Removed fallback branches cause no reconnect/recovery regression in the
  browser reliability scenarios.
- Fallback-only modules are renamed or clearly marked fallback-only, resolving
  the v0.7 open decision.
- Existing `console:reliability:test` and `console:browser:reliability` proof
  stays green.

## Data Flow

### Shared-Component Call-Site Flow (Track A)

1. Gradle fan-in pass finds distinct call sites of a composable definition by
   `file + offset` and counts them.
2. When the count `>= SHARED_COMPONENT_FANIN_THRESHOLD`, the index retains both
   the count and the call-site locations (capped at the call-site limit, with an
   overflow count).
3. `SourceMatcher` emits `SHARED_COMPONENT_DEFINITION` on a definition match,
   adds the risk flag, caps confidence at MEDIUM, and populates `callSites`.
4. The candidate's `callSites` and updated caution flow through MCP/session
   output additively.
5. The console renders the call-site list near the caution; absence is tolerated.

### Pattern Resolution Flow (Track B)

1. The scanner recognizes a `Layout`/`SubcomposeLayout` wrapper or a registered
   name convention and attaches a typed signal to the entry.
2. `SourceMatcher` scores the entry, records an explainable reason, and produces
   confidence bounded by evidence.
3. A fixture-lab case asserts the expected reason/confidence before the pattern
   ships with raised confidence.

### Console Sync Flow (Track C)

1. SSE delivers session/preview events through the existing shared apply paths.
2. The reliability harness records whether any fallback poll/recovery branch
   fires during a healthy session.
3. Branches with no evidence of firing are narrowed, then removed; explicit
   recovery remains.

## Error Handling

- **Missing call-site data (Track A):** if the index carries no call-site list
  (single-use definition or pre-v0.8 output), the candidate simply omits
  `callSites`; the existing caution still applies.
- **Call-site overflow (Track A):** more than the limit → show the capped list
  plus an overflow count; never claim a complete inventory beyond the cap.
- **Unknown pattern signal (Track B):** an unrecognized or malformed signal is
  ignored by core without raising confidence; no crash.
- **SSE unavailable (Track C):** fallback polling still starts; only
  healthy-session auto-polling is removed.
- **Replay overflow / reconnect (Track C):** explicit recovery requests an
  authoritative snapshot, unchanged.

## Testing Strategy

### Track A

- Gradle: call-site location retention — declarations excluded, strings/comments
  ignored, distinct-by-location, cap + overflow boundary.
- Core: `callSites` populated only on `SHARED_COMPONENT_DEFINITION`; confidence
  still capped at MEDIUM; updated caution returned.
- Fixture lab: a shared-definition case asserts the call-site list is present
  and confidence is not HIGH; single-use case asserts no list.
- Console: renders the list when present; tolerates absence and unknown fields.

### Track B

- Gradle: `Layout`/`SubcomposeLayout` wrapper recognition and each enumerated
  name convention.
- Core: explainable reasons emitted; confidence bounded by evidence.
- Fixture lab: one case per new pattern; regression cases stay green.

### Track C

- Reliability harness asserts zero automatic preview/session polling on a
  healthy SSE session.
- Reconnect, replay-overflow, manual-refresh recovery scenarios stay green after
  removal.

### Cross-Cutting

- Clean-architecture boundary ratchet stays green (core purity).
- Full local Gradle matrix, console asset/JS checks, and `git diff --check` per
  `CONTRIBUTING.md` required checks before each track's PR.
- `BridgeProtocol.VERSION` impact verified for Track A's additive field.

## Implementation Sequencing

Tracks are independent and may land in any order. Suggested order:

1. **Track A** first — it is the smallest, highest-value increment and builds
   directly on freshly-landed code.
2. **Track B** next — additive matcher coverage, fixture-gated.
3. **Track C** last — evidence-gated removal benefits from a stable console
   baseline.

Within each track: add failing fixture/contract tests first, implement to green,
then run the full local matrix.

## Risks

- **False precision (Track A):** providing call sites could read as "edit here."
  Mitigation: confidence stays MEDIUM-capped; caution and console copy frame the
  list as verification context, not a target.
- **Index size / noise (Track A):** large fan-in could bloat output. Mitigation:
  cap the list with an overflow count.
- **Confidence inflation (Track B):** new patterns could over-claim. Mitigation:
  fixture-lab gate before any confidence rise; evidence-bounded weights.
- **Over-aggressive polling removal (Track C):** removing fallback too early
  could hurt reconnect recovery. Mitigation: evidence-gate and narrow before
  deleting; keep explicit recovery.
- **Contract drift:** additive fields/enums must stay tolerant downstream.
  Mitigation: console tolerance tests and bridge-protocol verification.

## Open Decisions

- Track A serialization of call-site locations into the source index (additive
  structured field vs. delimited signal value). Resolved during implementation
  as the minimal additive encoding; not a design fork.
- Track B's exact enumerated set of additional name conventions. Fixed during
  planning, gated by fixtures.
- Track C fallback-file renames. Resolved within Track C once the fallback-only
  surface is clear (settles the v0.7 deferred decision).

## Approval Notes

The user approved on 2026-05-29:

1. A v0.8 umbrella covering three tracks: shared-component call-site context,
   source-matcher pattern expansion, and SSE console sync finalization.
2. Track A depth: provide the call-site inventory as best-effort context
   (recommended option), with nearby-evidence narrowing left as a follow-up.
3. The track architecture, non-goals, and V1 boundary constraints above.
