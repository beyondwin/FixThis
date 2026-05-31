# Release Gate, Interop Evidence, and SSE Closure Umbrella - Design

**Date:** 2026-05-31
**Status:** Approved (design); implementation plan pending
**Topic:** Release evidence packaging, AndroidView/WebView-risk evidence depth,
and SSE state-sync debt closure

## Summary

FixThis now has a v1.1 Trust Loop evidence pack covering release reality checks,
external agent lifecycle smoke, and runtime source-trust calibration. The next
high-leverage umbrella should close the loop around three adjacent risks:

1. release evidence must be packaged into a single artifact maintainers can use
   to decide whether a source release claim is real;
2. AndroidView/WebView-risk selections need deeper Compose-side context without
   pretending FixThis can target XML/View/WebView source exactly;
3. the feedback console should finish the SSE-first state-sync cleanup by
   proving which pull/recovery paths are fallback-only before removing or
   isolating them.

This umbrella intentionally combines all three tracks because they defend one
public promise: FixThis should give coding agents local, debug-only Android UI
evidence they can trust before editing an app, and maintainers should be able
to prove that claim before release.

## Approved Direction

The approved approach is one umbrella with three independently verifiable
tracks:

- **Track A: Release Gate / Evidence Report Pack.**
- **Track B: AndroidView / Interop Evidence Depth v2.**
- **Track C: SSE State Sync Debt Closure.**

Implementation should land Track B first, Track C second, and Track A last.
Track A may add reusable report plumbing early if useful, but the final release
gate report must run after Tracks B and C so it includes their evidence.

## Goals

- Produce a single release-gate report that normalizes all relevant evidence as
  `pass`, `deferred`, or `fail`.
- Make deferred evidence explicit, with concrete reasons such as missing Android
  device, locked emulator, unavailable registry, or maintainer credentials.
- Enrich AndroidView/WebView-risk handoffs with reliable Compose boundary and
  subtree context while preserving caution and low-confidence semantics.
- Prevent interop-risk handoffs from appearing as high-confidence exact source
  ownership.
- Prove healthy EventSource sessions avoid redundant session, history, and
  preview polling before deleting or quarantining legacy recovery code.
- Keep release-readiness docs, unreleased notes, and evidence-runner profiles
  aligned with the actual commands.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting.
- No WebView DOM inspection.
- No new registry or package channel such as PyPI or Docker.
- No automatic code edits in the target Android app.
- No bridge-protocol or persisted MCP JSON breaking change.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  generated reports, or local fixture workspaces.

## Architecture Context

- `:fixthis-compose-core` owns source matching and confidence signals. It must
  stay free of MCP, CLI, Android UI, and `.fixthis/` dependencies.
- `:fixthis-mcp` owns feedback sessions, compact handoff rendering, console
  routes, the browser console assets, MCP tools, and runtime fixture helpers.
- The feedback console uses `/api/events` as the push-first state-sync channel,
  with polling retained only for EventSource failure, manual refresh, or
  recovery.
- Release evidence currently spans `scripts/evidence-runner.mjs`,
  `scripts/release-reality-check.mjs`, `scripts/check-release-readiness.mjs`,
  `docs/contributing/release-readiness.md`, `docs/releases/unreleased.md`, and
  the connected smoke scripts.

Persisted MCP/session JSON fields remain compatibility contracts. Fields such
as `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
`targetReliability`, and `sourceCandidates` must not be renamed. New evidence
fields must be optional and additive.

## Track B: AndroidView / Interop Evidence Depth v2

### Problem

FixThis already warns when a selection may cross an AndroidView/WebView
boundary and surfaces nearby Compose boundary context. That keeps the handoff
honest, but the agent still needs stronger context for where to inspect first:
which Compose host owns the interop boundary, which ancestor or slot shaped the
visible region, and which source candidates are merely verification hints.

The risk is overclaiming. An AndroidView/WebView-risk handoff must not look
like an exact source target just because FixThis found a nearby Compose node.

### Design

Represent interop-risk evidence as boundary context rather than exact source
ownership:

```text
selected runtime target
-> interop-risk classification
-> Compose boundary host
-> nearest Compose ancestors / slot or sibling context
-> ranked source candidates as verification hints
-> compact handoff + console Evidence panel caution
```

The runtime and MCP layers should expose a structured boundary-context model
that can represent:

- the selected target and why it is interop-risk;
- the likely Compose host or boundary node;
- nearby ancestor, sibling, or slot context when available;
- up to a small capped set of source candidates;
- a target action such as `inspect-and-corroborate` or
  `verify-manually`, never an exact edit command.

Compact handoff and console Evidence rendering should make the caution visible:
the agent starts by inspecting the host composable and corroborating runtime
evidence, not by treating the View/WebView implementation as source-matched.

### Error Handling

- If the boundary host is absent, render a cautious "verify manually" path
  instead of inventing ownership.
- If candidates disagree or are weak, preserve them as hints and keep
  confidence low or caveated.
- If the selected target is not interop-risk, existing non-interop rendering
  remains unchanged.
- If fixture/runtime capture fails, the strict connected command fails; the
  non-strict release gate may record a concrete deferred reason.

### Acceptance Criteria

- AndroidView/WebView-risk selections render boundary context and caution text
  in compact handoff output.
- The console Evidence panel renders the same interop caution semantics.
- No interop-risk handoff renders as high-confidence exact source ownership.
- Non-interop source matching and handoff output remain unchanged except for
  additive optional fields.

## Track C: SSE State Sync Debt Closure

### Problem

The console is now push-first over `/api/events`, but some polling and manual
recovery paths still exist. Some are legitimate fallbacks; others may be
obsolete after the SSE migration. Removing them without evidence risks
breaking recovery, but leaving all of them in place keeps state-sync behavior
harder to reason about.

### Design

Instrument first, then remove or quarantine:

```text
healthy EventSource session
-> run console reliability flow
-> record session/history/preview request counts
-> identify fallback-only paths
-> delete proven-dead code or label it as fallback-only
-> keep manual refresh and SSE failure recovery alive
```

The browser reliability proof should assert that a healthy EventSource session
does not rely on steady-state `/api/session`, `/api/sessions`, or preview
polling. Mutation paths should be classified into:

- **SSE-owned:** server event applies authoritative state without local pull;
- **synchronous local refresh:** kept only when mutation response semantics
  require immediate authoritative state;
- **fallback-only:** runs after EventSource disconnect, replay overflow, manual
  refresh, or explicit recovery;
- **dead:** no longer reachable in the instrumented healthy or fallback paths.

Only the `dead` category is removed. `fallback-only` code remains but should be
named, tested, and documented as such.

### Error Handling

- EventSource unavailable or disconnected restarts fallback polling as before.
- Replay overflow triggers a full refresh rather than partial state reuse.
- Manual refresh remains available.
- In-flight edits and hidden-tab behavior must keep their existing protections
  against clobbering user input.
- If instrumentation cannot prove a path is unused, do not remove it in this
  umbrella.

### Acceptance Criteria

- Browser reliability output records EventSource health and request counts for
  session, history, and preview endpoints.
- Healthy SSE sessions show zero redundant steady-state polling.
- Fallback behavior remains covered for disconnect, replay overflow, and manual
  refresh.
- Removed code has a test or reliability proof showing it is not used by the
  healthy or fallback path.
- Any retained recovery code is clearly named or documented as fallback-only.

## Track A: Release Gate / Evidence Report Pack

### Problem

The repository has many strong individual checks: release reality, trust
evidence, agent-loop smoke, runtime fixture lab, console reliability, docs
consistency, and readiness rules. The remaining maintainer problem is
aggregation. A release decision needs one report that says which public claims
are backed by current evidence, which checks were deferred for concrete
environment reasons, and which claims fail.

### Design

Add a release-gate profile that aggregates existing and new evidence:

```text
evidence command
-> normalized result
-> pass / deferred / fail
-> JSON + Markdown report
-> release-readiness claim table
-> release issue attachment
```

The report should be written under
`build/reports/fixthis-release-gate/` and include:

- command name and profile;
- status: `pass`, `deferred`, or `fail`;
- elapsed time;
- report paths for nested commands when available;
- strictness mode;
- deferred reason or failure summary;
- release claims unlocked by the evidence.

The top-level command is `npm run release:gate`. It should be discoverable near
the existing release/evidence scripts and may delegate to an evidence-runner
profile internally.

### Evidence Inputs

The release gate should include, at minimum:

- release reality checks for public package and registry surfaces;
- existing v1.1 Trust Loop evidence;
- Track B interop evidence tests and connected/runtime fixture coverage when
  available;
- Track C console reliability proof for healthy SSE request counts;
- docs consistency and release-readiness rules;
- diff/whitespace checks suitable for a release commit.

Connected Android commands should run in strict mode when an unlocked emulator
or device is available. If not available, the non-strict report records
`deferred` with the exact Android environment reason. Strict mode must fail
deferred connected evidence.

### Error Handling

- A missing command mapping is `fail`, not skipped.
- A public registry value that contradicts docs is `fail`.
- Network unavailable can be `deferred` in non-strict mode, but not in strict
  mode.
- Missing credentials are `deferred` only when the command documents the exact
  credential or manual evidence needed.
- A release note claim without a matching readiness row and evidence command is
  `fail`.

### Acceptance Criteria

- One release-gate command writes JSON and Markdown reports.
- Each tracked claim maps to at least one evidence command.
- The release gate distinguishes `pass`, `deferred`, and `fail`.
- Strict mode fails on deferred or failed checks.
- `docs/contributing/release-readiness.md` and `docs/releases/unreleased.md`
  describe only evidence-backed claims or explicitly deferred checks.

## Data Flow

```text
Interop-risk selection
-> runtime target evidence + Compose boundary context
-> source candidates remain verification hints
-> compact handoff / console Evidence panel
-> agent inspects host composable first

Console mutation or passive update
-> SSE event applies authoritative server state
-> fallback polling only when EventSource is unavailable or recovering
-> reliability report records request counts and fallback reasons

Release gate
-> run evidence commands
-> normalize each result as pass / deferred / fail
-> write JSON + Markdown report
-> readiness docs allow only claims backed by evidence
```

## Testing Strategy

- Kotlin/MCP tests for interop boundary evidence, target action tokens, compact
  handoff caution text, and non-interop regression behavior.
- Console JS and route tests for Evidence panel rendering and fallback trigger
  behavior.
- Browser reliability check proving healthy SSE has zero redundant session,
  history, and preview polling.
- Release report renderer tests for `pass`, `deferred`, and `fail`
  classification.
- Evidence-runner profile tests proving the release gate includes Track B,
  Track C, docs, and release-readiness commands.
- Connected strict checks when an unlocked emulator or device is available.

## Sequencing

1. **Track B first:** deepen interop evidence and lock the caution contract.
2. **Track C second:** instrument SSE request counts, then remove or quarantine
   only proven-dead recovery paths.
3. **Track A last:** aggregate the new Track B/C proof with existing v1.1 trust
   evidence into one release-gate report.
4. **Docs and changelog finalization:** update user-facing claims only after the
   backing tests or deferred evidence are recorded.

## Implementation Defaults

- The aggregate release command is `npm run release:gate`.
- Track B should reuse existing `targetEvidence`, `targetReliability`,
  `sourceCandidates`, and boundary-context shapes first. Add a new field only
  when those cannot represent the interop evidence without overloading an
  existing contract; any new field must be optional and additive.
- Track C deletes only code proven dead by instrumentation and tests. Recovery
  code that remains reachable under EventSource failure, replay overflow,
  manual refresh, hidden-tab behavior, or in-flight edits stays in place as a
  named fallback-only path.
