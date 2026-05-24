# SSE Preview And Runtime Trust Expansion Design

Date: 2026-05-25
Status: Ready for user review
Scope: two-step FixThis hardening program: remove redundant live preview
polling by making SSE preview delivery primary, then expand runtime source
matching trust fixtures

## Summary

FixThis should next harden two areas in sequence. First, complete the
push-first console state-sync work by making `preview-ready` Server-Sent Events
the primary live preview path and reducing interval preview polling to a
fallback-only recovery path. Second, expand runtime source matching trust
fixtures beyond the initial Reply case so the new runtime fixture tier becomes
a meaningful false-confidence regression net.

The order matters. The console still has redundant preview pull paths even
though `/api/events` already emits `preview-ready`. Tightening that transport
first reduces state-sync noise before adding more device-backed runtime trust
coverage.

## Goals

- Make SSE `preview-ready` the normal automatic preview update path.
- Keep manual refresh and preview polling available only as recovery paths when
  EventSource is disconnected, unavailable, or explicitly bypassed.
- Preserve active-session fencing so late or cross-session preview payloads
  cannot replace the visible preview.
- Keep the existing capture-unavailable readiness behavior: readiness-only
  payloads update connection/readiness UI without replacing `state.preview`.
- Add runtime source matching fixture coverage after the console transport path
  is simpler.
- Cover multiple runtime trust failure modes: selector drift, ambiguous
  targets, source confidence, target reliability warnings, source risk flags,
  and candidate ranking.
- Preserve FixThis constraints: debug-only, Compose-only, local-first,
  additive public output schemas, and no committed local artifacts.

## Non-Goals

- Do not add a new browser transport such as WebSocket.
- Do not remove manual refresh.
- Do not require runtime source matching fixtures in CI or release checks.
- Do not broaden FixThis to release builds, XML/View exact targeting, WebView
  DOM inspection, Flutter, React Native, iOS, or cloud review behavior.
- Do not rename persisted MCP/session JSON fields such as `items`, `screens`,
  `targetEvidence`, `targetReliability`, `sourceCandidates`, or
  `editSurfaceCandidates`.
- Do not commit `.fixthis/`, runtime fixture workspaces, screenshots, build
  reports, Android build output, or `graphify-out/`.

## Design Principles

1. Complete the console transport simplification before expanding runtime
   source matching evaluation.
2. SSE and fallback polling must feed one preview application contract.
3. Fallback polling exists for recovery, not as a parallel happy path.
4. Runtime trust fixtures must observe real runtime fields, not infer trust
   from source-index-only data.
5. Fixture reports must distinguish product regressions from local device,
   bridge, or capture environment failures.
6. Public MCP output compatibility is stricter than local fixture schema
   compatibility.

## Phase C: SSE Preview Polling Reduction

### Current Problem

The console already has `/api/events`, `ConsoleEventBus`, and `preview-ready`
events. It also still starts `livePreviewPolling` during normal initialization.
That leaves two automatic preview update paths active: push events and interval
fetches. The duplicate paths increase request churn and make state ownership
harder to reason about.

The desired end state is not "no fallback." The desired end state is
push-first: EventSource owns automatic preview updates while connected, and
fallback preview polling runs only when SSE is unavailable or an explicit
recovery/manual refresh path asks for it.

### Components

#### `LivePreviewService`

`LivePreviewService` remains the server source for preview availability. When a
new preview frame is ready, it emits `preview-ready` through `ConsoleEventBus`
with the active `sessionId` and preview payload. It should not depend on browser
polling behavior.

#### `ConsoleEventBus` and `/api/events`

The existing event stream remains the only server-push channel. It owns event
ids, replay, overflow behavior, keep-alives, and subscriber fan-out.
`preview-ready` events that can mutate visible preview state must include a
top-level `sessionId`.

#### `events.js` and `sse.js`

The browser SSE subscriber applies `preview-ready` events through the same
preview application function used by fallback polling. It must keep the active
session fence: a preview event for any non-active session is ignored and must
not clear the current preview or draft state.

The SSE connection state becomes an input to fallback polling. While
EventSource is open and healthy, automatic interval preview polling is stopped
or never started. When EventSource enters an error or reconnecting state,
fallback polling may resume.

#### Preview fallback polling

The existing `livePreviewPolling` subsystem is narrowed to a recovery adapter.
It runs only when SSE is disconnected/unavailable or when an explicit manual
recovery path requests a preview fetch. A successful fallback preview response
uses the same application path as SSE. A failed fallback keeps the existing
reconnecting surface behavior.

#### Shared preview application path

SSE and fallback polling must share one browser-side function for applying a
preview. That function owns:

- active `sessionId` checks
- stale generation checks
- readiness-only payload handling
- draft/annotate freeze protection
- preview state writes
- preview-only rendering

This keeps behavior substitutable: a preview from SSE and a preview from
fallback polling follow the same contract.

### Data Flow

```text
LivePreviewService
  -> ConsoleEventBus emits preview-ready
  -> browser EventSource receives preview-ready
  -> shared preview application path validates session/generation/readiness
  -> state.preview updates or readiness UI updates
  -> preview-only render

SSE disconnected or unavailable
  -> fallback preview polling starts
  -> polling fetch receives preview payload
  -> same shared preview application path
```

### Error Handling

- SSE disconnect is a fallback trigger, not a user-visible product failure by
  itself.
- Malformed `preview-ready` payloads are ignored, logged as console warnings,
  and treated as fallback-refresh candidates.
- Cross-session `preview-ready` payloads are ignored.
- Readiness-only payloads update availability UI but do not replace
  `state.preview`.
- Fallback polling keeps the existing retry/backoff and reconnecting status
  surface.
- Manual refresh remains available even when SSE and fallback polling are both
  unhealthy.

### Acceptance Criteria

- Automatic preview polling does not run while EventSource is open and healthy.
- A `preview-ready` event updates the active session preview without waiting
  for the polling interval.
- A `preview-ready` event for another session does not replace the visible
  preview.
- SSE disconnect resumes fallback preview polling.
- Manual preview refresh still works.
- Existing capture-unavailable readiness behavior is preserved for both SSE and
  fallback payloads.

### Tests

- JS contract tests for SSE connection state controlling preview fallback.
- JS contract tests for one shared preview application path.
- JS contract tests for cross-session `preview-ready` fencing.
- JS contract tests for readiness-only preview payloads.
- Browser smoke follow-up proving preview updates through SSE and falls back
  after EventSource failure.

## Phase A: Runtime Trust Fixture Expansion

### Current Problem

The runtime fixture tier now exists, but the committed manifest starts with a
single Reply runtime-trust case. That proves the path can run, but it is not yet
a broad enough trust net for source matching. A single case cannot protect
selector drift, ambiguous target behavior, medium-confidence cases, warning
presence/absence, source risk flags, and candidate ranking across varied app
structures.

### Components

#### `fixtures/source-matching/manifest.json`

The manifest remains the local fixture contract. New `runtime-trust` cases
should be added deliberately, with each case representing a distinct failure
mode rather than broad sample-app coverage for its own sake.

Fixture selection order:

1. Keep the existing Reply button target as the baseline happy-path case.
2. Add one FixThis sample app runtime case for warning or risk behavior that
   external samples cannot reliably provide.
3. Add one Jetsnack runtime case for copy/text or component-owner matching when
   a stable text, test tag, content description, or role selector exists on the
   initial screen.
4. Add a Now in Android multi-module runtime case only after planning verifies
   a stable launch-state selector. Skip it for this implementation cycle when
   the target requires coordinates, scroll setup, account/network state, or
   fragile timing.

#### `RuntimeTrustFixtureRunner`

The runner remains the repository-local device-backed evaluator. It launches
the app, captures a screen, resolves `runtimeTarget`, creates a feedback item,
extracts observed trust fields, and returns machine-readable observations to
the JavaScript report runner.

New work should improve observation breadth only where the runner already owns
the correct boundary. It should not drive the browser feedback console.

#### `RuntimeTargetResolver`

Selector resolution remains semantics-based with `text`, `testTag`,
`contentDescription`, and `role`. Coordinate and visual-area selectors stay out
of scope for this phase because they are fragile across device density and
layout changes.

#### `scripts/source-matching-fixtures.mjs`

The JavaScript runner keeps orchestration, report generation, and validation.
It should classify runtime trust outcomes clearly:

- product or matcher failures
- target selector failures
- expected observation failures
- environment downgrades

### Data Flow

```text
manifest runtime-trust case
  -> JS runner prepares fixture work copy
  -> debug runtime is enabled
  -> app builds, installs, and launches
  -> MCP fixture runner captures current screen
  -> RuntimeTargetResolver selects one semantics node
  -> FeedbackSessionService builds annotation evidence
  -> observed sourceCandidates and targetReliability are returned
  -> JS runner validates expected confidence, warnings, risks, and ranking
  -> JSON and Markdown reports summarize results
```

### Error Handling

- Missing device, launch failure, bridge failure, or capture failure remains
  `pass_with_environment_downgrade` by default and fails under `--strict`.
- `target_not_found` means selector drift or app startup drift.
- `target_ambiguous` means the selector is too broad and must be narrowed.
- Missing confidence/risk/warning observations are explicit failures for
  runtime-trust cases.
- Source-index-only downgrade labels such as `trust_observation_not_configured`
  remain invalid for manifest schema v2 runtime trust cases.

### Acceptance Criteria

- Runtime trust coverage includes more than the initial Reply case.
- Each new runtime case has a documented reason tied to a distinct trust failure
  mode.
- Default runtime runs still downgrade local environment failures instead of
  failing when no usable device is present.
- Strict runtime runs fail for environment unavailability.
- Reports clearly separate runtime product failures from environment
  downgrades.
- Source-index fixture behavior remains fast and deterministic.

### Tests

- Manifest contract tests for new runtime cases and allowed selector fields.
- Runner unit tests for selector drift and ambiguity labels.
- Report contract tests for runtime confidence, warning, risk, and ranking
  validation.
- Default-vs-strict environment downgrade tests.
- Targeted Kotlin tests around runtime observation mapping where new fields are
  validated.

## Sequencing

Implementation planning should split this design into two task groups:

1. **SSE preview polling reduction.** Add tests around current behavior, route
   SSE and fallback payloads through one preview application path, prevent
   automatic preview polling while SSE is healthy, and verify fallback recovery.
2. **Runtime trust fixture expansion.** Add focused runtime-trust cases and
   any required observation/report tests after the console preview path is
   simpler.

The second task group should not start by widening all fixture apps. It should
start with the smallest set of runtime cases that proves distinct failure
modes.

## Verification Strategy

For the design-to-plan handoff, expected verification should include:

- `git diff --check`
- relevant console JS contract tests
- relevant console browser smoke or reliability command for SSE preview
  behavior
- `npm run source-matching:fixtures:test`
- targeted `:fixthis-mcp:test` and `:fixthis-compose-core:test` cases for
  runtime trust observation changes
- `npm run source-matching:fixtures:runtime` as an opt-in local device check
  when an emulator or device is available
- strict runtime command only when the local environment is intentionally ready
  for a device-backed gate

## Documentation Updates

Phase C should update console state-sync documentation and feedback console
reference material so preview polling is described as fallback-only.

Phase A should update the source matching fixture lab guide and source matching
reference only for behavior that actually changes. It should not present
runtime fixtures as CI, release, or public install requirements.
