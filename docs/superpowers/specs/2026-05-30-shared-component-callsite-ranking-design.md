# Shared-Component Call-Site Ranking Hint Design

Date: 2026-05-30
Status: Ready for user review
Scope: v0.8 Track A follow-up — rank the shared-component call-site inventory by
nearby selection evidence as a best-effort hint, without making a precise-target
claim.

Related work:

- [v0.8 Source Matching Depth & Console Sync Umbrella](2026-05-29-v08-source-matching-depth-console-sync-umbrella-design.md)
- [Shared Reusable Component Trust](2026-05-29-shared-reusable-component-trust-design.md)
- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)

## Summary

v0.8 Track A shipped the shared-component call-site inventory: when a candidate
is flagged `SHARED_COMPONENT`, the handoff carries
`sourceCandidates[].callSites` — a best-effort list of `{file, line}` locations
where the reused definition is invoked. The list is currently emitted in static
source order (`file`, then `line`), with no signal about which usage most likely
rendered the selected node.

This follow-up adds a best-effort **ranking** over that list. Each call site
gains lightweight static context at index time (the enclosing composable/function
name and the call's string-literal arguments). At match time, `SourceMatcher`
scores each call site against the selected node's runtime evidence and reorders
the list by likelihood. The top entry may carry a soft "most likely
(best-effort)" label when its score margin is clear.

The release claim does not change: FixThis still does not resolve which call site
rendered a selection. The ranking is a verification convenience — it puts the
most plausible usage first so the agent checks it sooner — not a target claim.
Confidence stays capped at MEDIUM.

## Why Now

Track A's approved design explicitly left "nearby-evidence narrowing" as a
follow-up. The inventory now exists and the fan-in pass already locates each
call site by `file + offset`; capturing a little more context per site is a
small, additive extension. `SourceMatcher.match` already receives the selection
evidence needed to rank (`selectedNode` text/editableText/contentDescription/
role, `nearbyNodes`, `activityName`), so no new input plumbing is required.

## Product Goal

> When FixThis hands over a shared component's call-site inventory, the usage
> most likely to match the selected node is listed first, as a best-effort hint
> — so the agent verifies the right call site sooner. FixThis still does not
> claim which call site rendered the selection.

## Non-Goals

- No runtime-to-source offset mapping; no precise-target claim. The ranking is a
  soft ordering hint, not a resolved target.
- No confidence rise. The candidate stays capped at MEDIUM; ranking affects only
  the order of the `callSites` list, never the candidate's score or tier.
- No change to the existing `SHARED_COMPONENT` flag, caution semantics ("verify
  the listed call sites"), threshold (fan-in >= 2), or call-site cap (10) /
  overflow behavior.
- No persisted MCP JSON field rename. New per-call-site context and any ordering
  metadata are additive and tolerant of absence.
- No new `:fixthis-compose-core` dependency. Core reads serialized index data
  only; the clean-architecture ratchet stays green.
- No open-ended evidence expansion. Ranking evidence is limited to two static
  signals per call site (enclosing function/composable name, call-site string
  literals) matched against existing selection evidence.

## Current Baseline

- `ComposableCallSiteFanIn.composableCallSites` returns
  `Map<String, List<ComposableCallSite>>` where `ComposableCallSite(file, line)`
  is distinct by source position, excludes the declaration, and ignores
  strings/comments via `callSiteIgnoredRanges`.
- `SHARED_COMPONENT_CALLSITE_LIMIT = 10` caps emitted locations; overflow is
  summarized as a count.
- `SourceMatcher.match(selectedNode, nearbyNodes, activityName)` scores index
  entries against selection evidence and emits `SourceCandidate`s.
  `SHARED_COMPONENT_DEFINITION` matches populate `callSites:
  List<SourceLocationRef>`, add `SourceCandidateRisk.SHARED_COMPONENT`, and cap
  confidence at MEDIUM.
- The console renders a "Shared component used at" Evidence row from
  `sourceCandidates[].callSites`.

## Design

### Index layer (Gradle)

Extend `ComposableCallSite` with two additive, optional context fields populated
during the existing fan-in scan:

- `enclosingName: String?` — the name of the `fun`/composable that lexically
  contains the call site (nearest enclosing top-level or nested function
  declaration). Resolved from the same text the scan already walks; `null` when
  no enclosing declaration is found.
- `argLiterals: List<String>` — string literals appearing in the call's argument
  list (e.g. `MyButton(text = "Submit")` → `["Submit"]`), extracted from the
  parenthesized argument span using the existing quoted-string range helper so
  comments and nested strings stay consistent. Bounded by a small per-site cap
  (e.g. 8) to avoid noise; empty when none.

Both fields ride the existing additive call-site serialization. The cap (10) and
overflow behavior are unchanged; context is captured only for the call sites that
are already retained.

### Core layer (`SourceMatcher`)

Add a ranking pass that runs only when a candidate carries `callSites` (i.e. a
`SHARED_COMPONENT_DEFINITION` match):

1. For each call site, compute a `callSiteScore` from token overlap between the
   site's static context (`enclosingName`, `argLiterals`) and the selection
   evidence already available to `match` — `selectedNode.text`,
   `editableText`, `contentDescription`, `role`, and `activityName`. Reuse the
   existing text-normalization/tokenization helpers; literal-text overlap weighs
   highest, enclosing-name overlap is a weaker signal.
2. Reorder `callSites` by `callSiteScore` descending, with a **stable tiebreak**
   on the existing order (`file`, then `line`) so zero-evidence cases preserve
   today's output exactly.
3. Mark the top entry as `mostLikely = true` (additive on `SourceLocationRef`)
   **only** when its score is non-zero and exceeds the second entry by a clear
   margin (a fixed policy threshold). Otherwise no entry is marked.

The candidate's own `rawScore`, normalized confidence, tier, risk flag, and
caution are untouched. Ranking is internal to the `callSites` list.

### Output / MCP / console

- `sourceCandidates[].callSites` is serialized in ranked order. The optional
  `mostLikely` flag is additive and tolerated when absent.
- The console renders the "Shared component used at" row in the provided order
  and, when `mostLikely` is set, adds a subtle "most likely (best-effort)"
  affordance on that one entry. Absence of the flag and unknown fields are
  tolerated (pre-follow-up output, single-use definitions).
- The caution text is unchanged: it still tells the agent to verify the listed
  call sites and makes no claim about which one rendered the selection.

### Trust discipline

Add fixture-lab coverage under `fixtures/source-matching/` (via
`scripts/source-matching-fixtures.mjs`) before shipping:

- A shared-definition case where one call site's literal/enclosing context
  matches the selection evidence — asserts that site ranks first and is marked
  `mostLikely`, while confidence stays MEDIUM (not HIGH).
- A case with no matching evidence — asserts the order equals today's static
  `file`/`line` order and no entry is marked `mostLikely`.
- Existing shared-component and regression fixtures stay green.

## Data Flow

1. Gradle fan-in finds distinct call sites and, for each retained site, captures
   `enclosingName` and `argLiterals` alongside `file`/`line`.
2. The index serializes the call-site list (with context) additively, capped at
   the limit with an overflow count.
3. On a `SHARED_COMPONENT_DEFINITION` match, `SourceMatcher` populates
   `callSites`, then scores and reorders them against the selection evidence and
   sets `mostLikely` on the clear top entry (if any).
4. The ranked `callSites` flow through MCP/session output additively.
5. The console renders them in order, highlighting `mostLikely` when present;
   absence is tolerated.

## Error Handling

- **No call-site context (pre-follow-up index, or scan found none):**
  `enclosingName`/`argLiterals` absent → every site scores 0 → order falls back
  to today's `file`/`line` order; no `mostLikely` mark.
- **No selection evidence overlap:** all scores 0 → stable static order, no
  `mostLikely`.
- **Tie at the top:** margin not exceeded → no `mostLikely` mark; order still
  deterministic via the stable tiebreak.
- **Malformed/unknown serialized context:** ignored by core without raising
  confidence or crashing.
- **Overflow (more than the cap):** unchanged — capped list plus overflow count;
  ranking applies only within the retained list and never implies completeness.

## Testing Strategy

- **Gradle:** `enclosingName` resolution (nested vs top-level, none),
  `argLiterals` extraction (named/positional args, strings vs comments, per-site
  cap), context captured only for retained sites, cap/overflow boundary
  unchanged.
- **Core:** ranking reorders by evidence overlap; stable tiebreak preserves
  static order with no evidence; `mostLikely` set only past the margin;
  confidence still MEDIUM-capped; candidate score/tier/caution unchanged;
  malformed context tolerated.
- **Fixture lab:** matching-evidence case (top-ranked + `mostLikely`, MEDIUM),
  no-evidence case (static order, no mark); regressions green.
- **Console:** renders ranked order; highlights `mostLikely`; tolerates absence
  and unknown fields (tolerance test).
- **Cross-cutting:** clean-architecture ratchet green; full local Gradle matrix,
  console asset/JS checks, `git diff --check`; `BridgeProtocol.VERSION` impact
  verified (expected additive, no bump, but checked).

## Risks

- **False precision:** a "most likely" mark could read as "edit here."
  Mitigation: confidence stays MEDIUM-capped; the mark is shown only past a clear
  margin and labeled best-effort; caution copy is unchanged and still says verify
  the listed call sites.
- **Weak/misleading ranking signal:** literal/enclosing-name overlap can be
  coincidental. Mitigation: literal-text overlap weighted above name overlap;
  ties and zero-evidence fall back to deterministic static order rather than
  guessing.
- **Index noise/size:** per-site context could bloat output. Mitigation:
  per-site literal cap and the existing call-site limit/overflow.
- **Contract drift:** new fields must stay tolerant downstream. Mitigation:
  console tolerance tests and bridge-protocol verification.

## Open Decisions

- Exact serialization of `enclosingName`/`argLiterals` into the call-site
  encoding (resolved during implementation as the minimal additive form; not a
  design fork).
- The numeric weights and the `mostLikely` margin threshold (fixed during
  planning, gated by fixtures).

## Relationship to the Rest of v0.8

This follow-up is independent of v0.8 Track B (source-matcher pattern expansion)
and Track C (SSE console sync finalization), both of which already have approved
designs in the v0.8 umbrella spec and proceed straight to implementation plans.
This design covers only the Track A ranking follow-up.

## Approval Notes

The user approved on 2026-05-30:

1. Pursuing all remaining v0.8 work — Track B, Track C, and the Track A
   call-site follow-up — as independent tracks.
2. Designing the Track A follow-up as a nearby-evidence-based ranking hint that
   respects the "no precise-target claim" non-goal (confidence stays MEDIUM).
3. Limiting ranking evidence to two static per-call-site signals: the call's
   string-literal arguments and the enclosing function/composable name.
