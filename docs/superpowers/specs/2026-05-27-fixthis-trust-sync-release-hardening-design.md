# FixThis Trust Sync Release Hardening Design

Date: 2026-05-27
Status: Ready for user review
Scope: one umbrella hardening program covering interop trust evidence,
SSE pull-path retirement and observability, and release/agent-install
evidence alignment.

Related work:

- [Trust Program Phase 2 - Handoff Rendering](2026-05-25-trust-program-phase2-handoff-rendering-design.md)
- [SSE Preview And Runtime Trust Expansion](2026-05-25-sse-preview-and-runtime-trust-expansion-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)

## Summary

FixThis has recently improved source-matching trust, long-form handoff
rendering, SSE-first preview delivery, and runtime trust fixtures. The next
high-leverage step is to connect those improvements into a single trust-to-
release program:

1. make AndroidView/WebView and visual-area handoffs more explicit about
   boundary risk instead of implying exact Compose source ownership;
2. finish the console state-sync migration by reducing happy-path pull refresh
   dependence and adding lightweight event-stream observability;
3. turn the resulting behavior into release evidence that keeps public install,
   agent setup, release notes, and validation commands aligned.

The tracks are intentionally ordered. Interop trust makes handoff quality more
honest. SSE hardening makes the console state model less noisy. Release
evidence then documents only the claims those two tracks can prove.

## Goals

- Improve handoff behavior for AndroidView/WebView, visual-area, and weak
  Compose target cases without promising exact source-line mapping.
- Keep persisted MCP/session JSON additive and compatible with existing
  `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, `sourceCandidates`, and `editSurfaceCandidates`
  contracts.
- Preserve `:fixthis-compose-core` as the source of domain trust policy while
  keeping MCP, CLI, Android UI, and `.fixthis/` path concerns outside it.
- Reduce redundant `/api/session` and `/api/sessions` fetches on mutation
  happy paths once SSE is healthy.
- Keep manual refresh, SSE failure recovery, replay overflow recovery, and
  reconnect fallback behavior available.
- Add lightweight event-stream observability for event count, reconnects, and
  replay overflow or dropped-event recovery.
- Align release readiness, changelog, unreleased notes, CLI/docs checks, and
  agent-install evidence with the actual hardening claims.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting and no WebView DOM inspection.
- No Flutter, React Native, iOS, or cloud review behavior.
- No automatic code edits inside FixThis itself.
- No package-manager or registry claim unless a clean install test exists for
  that channel.
- No removal of manual refresh.
- No new browser transport such as WebSocket.
- No committed `.fixthis/`, runtime fixture workspaces, screenshots, Android
  build output, or `graphify-out/` artifacts.

## Design Principles

1. Trust language must not outrun evidence. If the target may cross an interop
   boundary, source candidates are hints, not edit instructions.
2. Existing public output contracts are stricter than local fixture schemas.
3. SSE is the normal passive state-sync path; pull refresh is retained for
   manual and recovery paths.
4. Observability should explain state-sync failures without creating a new
   durable telemetry system.
5. Release notes must be evidence-backed. If a claim lacks a local validation
   command, narrow the claim or mark it deferred.

## Track A: Interop Evidence Trust

### Current Problem

FixThis already warns when a target may involve AndroidView/WebView or a visual
area instead of a meaningful Compose semantics node. The current handoff can
still feel too source-like because it often presents candidate paths near the
warning without enough separation between:

- source-origin evidence for visible text;
- likely Compose boundary or parent context;
- visual area or interop risk where rendered pixels may not be owned by the
  suggested Compose file.

For an agent, this distinction matters. A candidate source path is useful
context, but editing it as if it owns the target pixels can be the wrong move.

### Architecture

`fixthis-compose-core` remains the policy boundary for target reliability,
source candidate confidence, source risk flags, and edit-surface role
classification. The implementation should prefer existing additive fields:

- `targetReliability.warnings`
- `targetEvidence.warnings`
- `sourceCandidates[].riskFlags`
- `sourceCandidates[].caution`
- `editSurfaceCandidates[].role`
- `editSurfaceCandidates[].note`

New persisted JSON fields are allowed only if the existing fields cannot carry
the evidence without ambiguity. If new fields are needed, they must be
additive, optional, and documented before use.

`fixthis-mcp` owns agent-facing wording. PRECISE, FULL, and COMPACT handoffs
should make interop and visual-area boundaries visible through action guidance,
not just raw warning tokens.

### Components

- `TargetReliability` policy: keep confidence low or unknown when selected
  pixels may be outside meaningful Compose ownership.
- `EditSurfaceCandidate` classification: distinguish `INTEROP_RISK`,
  `VISUAL_AREA`, `LAYOUT_OR_STYLE`, and `CALL_SITE` hints where possible.
- `FeedbackQueueFormatter`: render long-form action guidance for interop,
  visual-area, and no-meaningful-target cases.
- `CompactHandoffRenderer`: keep compact trust-essential tokens equivalent to
  PRECISE output without expanding the compact prompt into prose.
- Handoff evaluation corpus: add focused cases for AndroidView/interoperability
  and visual-area no-source behavior.
- Source-matching fixture lab: add only local, stable, semantics-backed runtime
  cases. Coordinate-only cases stay out of scope unless a deterministic
  selector exists.

### Data Flow

1. Capture and selection produce a semantics node, visual area, or fallback
   target.
2. Core trust policy maps weak target evidence into low confidence and warning
   tokens such as `POSSIBLE_VIEW_INTEROP`, `VISUAL_AREA_ONLY`, or
   `NO_MEANINGFUL_COMPOSE_TARGET`.
3. Source matching ranks candidates when available, but interop warnings cap
   how strongly the handoff may recommend editing those paths.
4. MCP/session mapping persists only additive evidence.
5. Markdown renderers separate "likely source/context" from "verify boundary
   before editing" action guidance.

### Error Handling

- If interop evidence is incomplete, do not promote confidence. Render the
  target as low or unknown confidence.
- If a source candidate exists with interop risk, render it as context or a
  hint, not a precise edit target.
- If no source candidate exists, preserve that fact and avoid inventing source
  paths from nearby labels.
- Older persisted sessions without new evidence keep their current rendering
  behavior except for safe additive guidance already derivable from existing
  warnings.

### Acceptance Criteria

- PRECISE and FULL handoffs for interop-risk items include an explicit action
  to verify the boundary before editing source candidates.
- COMPACT handoff preserves equivalent trust-essential tokens for interop and
  visual-area cases.
- Visual-area no-source items do not render a fake likely source.
- Source candidates with interop warnings are presented as hints/context.
- At least one AndroidView or interop-risk evaluation case and one visual-area
  no-source case protect against overconfident rendering.

## Track B: SSE Pull-Path Retirement And Observability

### Current Problem

SSE Phase 2 made preview delivery push-first: connected consoles can receive
`preview-ready` events without automatic preview polling. Session and summary
state still retain redundant pull refreshes in mutation happy paths. Those
pulls are useful defense while event coverage is young, but they obscure
ownership and create unnecessary `/api/session` and `/api/sessions` traffic
when EventSource is healthy.

The remaining migration should not remove recovery. It should make pull refresh
rare and explainable.

### Architecture

`ConsoleEventBus` and `/api/events` remain the passive sync channel.
Browser-side event handlers own normal remote/session state application when
EventSource is healthy. `refreshSessions()` remains the fallback implementation
for manual refresh, SSE failure, replay overflow, reconnect recovery, and
explicit user recovery.

Mutation handlers should rely on direct server responses and emitted SSE
events for happy-path state. Any retained post-mutation refresh must be
justified by a concrete synchronous need, such as needing a full server snapshot
before continuing a command.

### Components

- `ConsoleEventBus`: expose lightweight process-local counters for emitted
  event count, subscriber reconnect count, replay count, and overflow recovery.
- `/api/events`: keep initial snapshot before streaming headers and quiet
  handling of browser disconnects.
- `events.js`: keep active-session fencing for `session-updated`,
  `sessions-updated`, and `preview-ready`.
- `sessions-polling.js` and `refreshSessions()`: narrow to fallback and manual
  refresh ownership.
- Mutation call sites in console JS: remove or gate happy-path refreshes where
  SSE and server responses already provide the state transition.
- Tests and browser proof: assert that healthy EventSource paths avoid
  redundant pull refresh while fallback paths still work.

### Data Flow

1. A mutation route writes session state.
2. The server emits the corresponding event through `ConsoleEventBus`.
3. Browser EventSource receives the event and applies it only if the
   `sessionId` belongs to the active session, or updates summaries where
   appropriate.
4. If EventSource is healthy, the mutation handler avoids automatic
   `refreshSessions()` unless the local command needs a synchronous snapshot.
5. If EventSource errors, replay overflows, or the user clicks manual refresh,
   fallback refresh fetches `/api/sessions` and `/api/session` in lockstep.

### Error Handling

- Cross-session events must not replace active detail or preview state.
- Replay overflow triggers snapshot or fallback refresh, not silent drift.
- Browser request cancellation stays a normal transport event.
- Missed server emit points should be caught by route/event tests. Manual
  refresh remains the final recovery path during local use.
- Observability counters are diagnostic only; they do not become persisted
  session JSON.

### Acceptance Criteria

- Healthy EventSource mutation paths reduce redundant `/api/session` and
  `/api/sessions` fetches.
- Manual refresh still works.
- SSE disconnect or unavailable EventSource resumes fallback state refresh.
- Replay overflow produces full-state recovery.
- Active-session fencing remains covered for session and preview events.
- Event-stream diagnostics expose event count, reconnect count, and overflow
  or dropped-event recovery state.

## Track C: Release And Agent Install Evidence Pack

### Current Problem

FixThis already has public install surfaces across GitHub Releases, Homebrew,
npm, MCP Registry, Maven Central, and the Gradle Plugin Portal. The project
also has many local checks. The risk is drift: release notes may claim behavior
that is not tied to the right command, or docs may advertise a channel whose
clean install was not verified for the current package version.

Track C makes the hardening claims from Track A and Track B release-reviewable.

### Architecture

Release evidence remains file-backed and local-first:

- `CHANGELOG.md` records chronological user-visible changes.
- `docs/releases/unreleased.md` summarizes current `main`.
- `docs/contributing/release-readiness.md` owns release and registry claim
  rules.
- CLI/docs checks validate that agent setup instructions and command surfaces
  do not drift.
- Existing package-version checks ensure release helper metadata stays aligned
  with `FIXTHIS_VERSION`.

Track C should not add package channels. It should clarify what current
channels can claim and what evidence is required before future channels such as
PyPI or Docker are advertised.

### Components

- Release readiness checklist: add or tighten rows for interop trust evidence,
  SSE pull-path retirement, event-stream observability, and agent-install docs.
- Release notes and changelog: describe only behavior that shipped and has a
  validation command.
- Docs consistency checks: keep `fixthis install-agent`, `fixthis init`,
  `fixthis doctor`, MCP Registry, Homebrew, npm, and Gradle plugin guidance
  aligned.
- Evidence command matrix: give maintainers a single list for trust, console,
  release, and agent-install checks.

### Data Flow

1. Track A and Track B define the claims.
2. Tests and browser proofs produce local evidence.
3. Release docs link each claim to the relevant command.
4. If a command depends on Android SDK, an emulator, or external registry
   state, the release issue records whether that evidence passed or was
   explicitly deferred.
5. Package-manager and registry claims remain limited to channels with clean
   install tests.

### Error Handling

- If Android SDK or an unlocked emulator is unavailable, connected/runtime
  evidence is marked deferred rather than implied.
- If a package-manager claim lacks a clean install test, it is not advertised
  as ready.
- If docs and CLI output disagree, the release is blocked until one source is
  corrected.
- If a release note has no verification command, the claim is narrowed or
  removed.

### Acceptance Criteria

- Release readiness references the validation commands for Track A and Track B.
- `CHANGELOG.md` and `docs/releases/unreleased.md` describe the hardening
  without overclaiming unsupported channels.
- Agent setup docs and CLI surface checks are part of the evidence matrix.
- Future package channels remain explicitly not claimed unless tested.

## Test And Evidence Matrix

| Area | Required evidence |
| --- | --- |
| Interop and visual-area trust rendering | `./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatter*" --tests "*CompactHandoffRenderer*" --no-daemon` |
| Core target reliability and source confidence | `./gradlew :fixthis-compose-core:test --no-daemon` |
| Handoff corpus and prompt parity | `npm run handoff:eval:test` |
| Source-matching fixtures | `npm run source-matching:fixtures:test` |
| Runtime trust, when Android SDK/device is available | `npm run source-matching:fixtures:runtime -- --strict` |
| SSE event contract | `node --test scripts/consoleEvents-test.mjs` |
| Console reliability contract | `npm run console:reliability:test` |
| Browser SSE/polling proof | `npm run console:browser:reliability` |
| Release readiness | `node scripts/check-release-readiness.mjs` |
| Agent setup docs | `npm run docs:agent-bootstrap:test` |
| CLI/docs surface | `bash scripts/check-docs-cli-surface.sh` |
| Version metadata | `npm run release:version:check` |
| Final local integration candidate | `npm run ci:local` |

## Implementation Boundaries

- Track A may touch `fixthis-compose-core`, `fixthis-mcp`, fixture manifests,
  handoff corpus data, and source-matching fixture scripts.
- Track B may touch `fixthis-mcp` server event code, console JS, console
  tests, and browser reliability scripts.
- Track C may touch release, docs, changelog, and release-check scripts.
- The implementation plan should split these tracks into independently
  verifiable tasks. A failure in runtime trust fixtures must not block SSE
  fallback correctness, and a package-channel evidence gap must not weaken
  handoff trust wording.
- Dirty local `.fixthis/` and `graphify-out/` artifacts remain uncommitted.

## Rollout Order

1. Implement Track A first to reduce overconfident agent edits in weak target
   situations.
2. Implement Track B second so event-stream ownership and fallback behavior are
   clean before release claims are updated.
3. Implement Track C last, using the exact evidence produced by Track A and
   Track B.
4. Run the smallest focused checks after each track, then run the broader
   local integration candidate before shipping.

## Open Decisions Resolved In This Spec

- All three proposed improvements are included in one umbrella spec.
- The tracks remain separate implementation units even though the design lives
  in one document.
- The recommended order is interop trust, then SSE hardening, then release
  evidence.
- The design preserves local-first, debug-only, Compose-first boundaries.
