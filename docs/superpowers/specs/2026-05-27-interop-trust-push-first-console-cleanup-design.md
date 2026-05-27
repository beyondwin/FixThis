# Interop Trust And Push-First Console Cleanup Design

Date: 2026-05-27
Status: Ready for user review
Scope: one umbrella hardening program with two independently verifiable
tracks: interop boundary evidence and SSE-first console cleanup.

Related work:

- [FixThis V1 Trust, Install, And Inner-Loop Hardening](2026-05-27-fixthis-v1-trust-install-inner-loop-hardening-design.md)
- [FixThis Trust Sync Release Hardening](2026-05-27-fixthis-trust-sync-release-hardening-design.md)
- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Console State Sync](../../architecture/console-state-sync-design.md)

## Summary

FixThis now has stronger source trust, agent install evidence, and local
verification profiles. The next high-leverage V1 improvement is to make weak
AndroidView/WebView boundary targets more explicit and to finish moving the
feedback console toward a push-first state model.

This design combines two tracks because they support the same product promise:
FixThis should hand agents local evidence they can trust without overstating
what FixThis can know. Track A prevents interop-adjacent targets from looking
like exact Compose source ownership. Track B removes redundant automatic pull
refreshes when `/api/events` is healthy while preserving recovery behavior.

The implementation should keep the tracks separate in tests and commits. Track
A reduces downstream wrong-edit risk. Track B reduces console state drift and
runtime complexity after the trust boundary is clearer.

## Goals

- Make AndroidView/WebView-adjacent targets render as verification-first
  interop evidence, not exact source targets.
- Keep Compose host, nearby owner, selected bounds, and source candidates useful
  as context while preventing high-confidence overclaiming.
- Prefer existing persisted contracts before adding fields:
  `targetEvidence`, `targetReliability`, `sourceCandidates`, and
  `editSurfaceCandidates`.
- Keep MCP/session JSON additive and backward-compatible.
- Keep `:fixthis-compose-core` free of MCP, CLI, Android UI, and `.fixthis/`
  path concerns.
- Make SSE the normal passive console update path for sessions and previews.
- Restrict automatic preview/session polling to disconnected, unsupported, or
  recovery states.
- Preserve manual refresh and fallback polling as explicit escape hatches.

## Non-Goals

- No XML/View exact source targeting.
- No WebView DOM inspection.
- No AccessibilityService or production runtime behavior.
- No new browser transport, WebSocket channel, or cloud review service.
- No automatic code edits inside FixThis.
- No release-build support.
- No breaking rename of persisted MCP JSON fields such as `items`, `screens`,
  `itemId`, `screenId`, `targetEvidence`, `targetReliability`,
  `sourceCandidates`, or `editSurfaceCandidates`.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  local fixture workspaces, or generated evidence reports.

## Design Principles

1. Boundary evidence is useful only when uncertainty stays visible.
2. Nearby Compose evidence can identify context, but it must not imply exact
   ownership of AndroidView/WebView pixels.
3. Existing JSON contracts should carry the new guidance unless a clearly
   missing concept requires an optional additive field.
4. SSE health owns passive freshness. Polling is recovery infrastructure, not
   the steady-state console model.
5. Recovery paths must stay boring: stale preview, hidden tab, frozen draft,
   replay overflow, and manual refresh should remain understandable and
   testable.

## Track A: Interop Boundary Evidence

### Current Problem

FixThis can already warn with `POSSIBLE_VIEW_INTEROP`, but the warning is still
coarse. Agents need to know what is known, what is nearby context, and what must
be verified before editing. A selected AndroidView/WebView boundary may have a
nearby Compose owner or source candidate, but that candidate does not prove that
the selected pixels are rendered by Compose source.

The desired behavior is not exact View targeting. The desired behavior is honest
handoff evidence: selected bounds, possible interop boundary, nearby Compose
context, likely host surface, and concise verification guidance.

### Architecture

`:fixthis-compose-core` remains the policy boundary for target reliability,
confidence caps, reasons, and warning tokens. `:fixthis-mcp` persists captured
evidence and renders it for agents through JSON and Markdown. The Gradle
source-index and matcher continue to provide source candidates, but interop
cases treat those candidates as context unless stronger Compose evidence exists.

The implementation should first use existing contracts:

- `targetReliability.warnings`
- `targetReliability.reasons`
- `targetEvidence.warnings`
- `sourceCandidates[].riskFlags`
- `sourceCandidates[].caution`
- `sourceCandidates[].evidenceStrength`
- `sourceCandidates[].scoreMargin`
- `editSurfaceCandidates[].role`
- `editSurfaceCandidates[].note`

If these cannot express host-context evidence clearly, add only optional,
documented fields under MCP/session-owned DTOs. Do not rename existing fields.

### Components

- `TargetReliabilityCalculator`: cap confidence for possible interop,
  visual-area-only, and no-meaningful-Compose-target cases.
- `TargetEvidenceService`: preserve selected bounds, nearby Compose node
  context, source candidates, and reliability metadata when a target crosses an
  interop boundary.
- `EditSurfaceRoleClassifier`: classify `INTEROP_RISK` as a verification-first
  surface, separate from `CALL_SITE` and `COMPONENT_DEFINITION`.
- `EditSurfaceCandidateService`: prefer interop guidance when target reliability
  says the selected area may be outside exact Compose ownership.
- Handoff renderers: render source candidates as context and render a short
  action line that tells agents to verify runtime target ownership before
  editing.
- Fixture and corpus tests: include AndroidView/WebView-like boundary cases that
  prove confidence does not climb to high from nearby Compose context alone.

### Data Flow

1. The user clicks a Compose-adjacent element or drags a visual area in the
   browser console.
2. The frozen preview persists screenshot bounds, semantics nodes, source
   candidates, screen fingerprint data, and target reliability metadata.
3. Core reliability policy detects weak evidence, interop risk, visual-only
   selection, stale evidence, or no meaningful Compose node.
4. Edit-surface classification marks interop cases as verification-first
   surfaces.
5. MCP JSON and Markdown handoffs include selected bounds, nearby Compose
   context, likely host surface, source candidates as context, and explicit
   verify-before-editing guidance.

### Error Handling

- Possible AndroidView/WebView targets cannot become high confidence from
  nearby Compose evidence alone.
- No-meaningful-Compose-target cases should prefer `visual-area` or
  `interop-risk` guidance over invented source ownership.
- Source candidates derived from nearby labels, owners, or weak evidence should
  render with caution.
- Existing sessions without newer interop evidence must deserialize and render
  normally.
- If runtime evidence is unavailable, the handoff should say so through existing
  reliability or caution fields instead of silently promoting confidence.

### Acceptance Criteria

- Interop-risk targets do not render exact source ownership in compact,
  precise, or full handoff output.
- AndroidView/WebView boundary selections cannot be promoted to high confidence
  by nearby Compose source candidates alone.
- Handoffs include enough context for an agent to inspect the likely Compose
  host without treating it as guaranteed ownership.
- Existing MCP JSON remains backward-compatible.
- Fixture or corpus coverage proves the boundary behavior.

## Track B: SSE Polling Retirement

### Current Problem

SSE Phase 1 made `/api/events` the primary state-sync channel for sessions,
devices, connection state, and preview-ready updates. Fallback preview polling
and session polling still exist, and some automatic paths can perform pull
refreshes even when EventSource is healthy.

The remaining work is not a new transport. It is narrowing automatic pull paths
so they only run during EventSource failure, unsupported browser behavior,
manual refresh, explicit recovery, or user-triggered escape hatches.

### Architecture

The console keeps the existing transport stack:

- `ConsoleEventBus` and `/api/events` on the server.
- `events.js` as the browser SSE subscriber.
- `preview.js` for preview fetch and fallback preview polling.
- `sessions-polling.js`, `pollingFsm.js`, and `pollingUseCases.js` for session
  fallback polling and mutation protection.

EventSource health should become the single steady-state gate for automatic
refresh. While healthy, `snapshot`, `session-updated`, `sessions-updated`, and
`preview-ready` are the passive update path. Polling resumes only when SSE is
disconnected, unavailable, or explicitly recovering from replay overflow or
manual refresh.

### Components

- `events.js` and SSE state: expose EventSource health and recovery state in a
  way preview/session fallback code can query without duplicating logic.
- `preview.js`: narrow `shouldAutoFetchPreviewFallback()` so visibility-resume,
  boot, and interval refreshes skip automatic preview fetch while SSE is
  healthy.
- `sessions-polling.js`: treat passive session polling as fallback/recovery
  behavior instead of a normal companion to healthy SSE.
- Mutation paths: keep synchronous server responses when they are needed for
  immediate UI state, but skip redundant follow-up pulls when SSE is healthy and
  the response already carries authoritative state.
- Console reliability tests: pin the no-redundant-fetch contract for preview,
  session detail, and session list refresh under healthy SSE.

### Data Flow

1. The console opens `/api/events`.
2. The server sends a `snapshot` with active session, session summaries,
   devices, and connection state.
3. While EventSource is healthy, browser handlers apply `session-updated`,
   `sessions-updated`, `devices-updated`, `connection-updated`, and
   `preview-ready`.
4. Automatic preview/session polling remains stopped or inactive in the healthy
   state.
5. If EventSource disconnects, overflows, or is unavailable, fallback polling
   resumes with existing recovery surfaces.
6. Manual refresh remains available and can force explicit pull refresh.

### Error Handling

- SSE disconnect must not blank current preview, draft, session, or history
  state.
- Replay overflow forces snapshot or refresh recovery instead of applying stale
  partial transitions.
- Hidden tab, frozen draft, active edit, and in-flight mutation protections
  continue to prevent clobbering user work.
- EventSource-unavailable browsers keep the existing fallback polling path.
- Manual refresh stays independent of EventSource health.

### Acceptance Criteria

- With EventSource healthy, automatic preview fallback does not fetch
  `/api/preview`.
- With EventSource healthy, passive session polling does not perform redundant
  `/api/session` or `/api/sessions` refreshes.
- `preview-ready` over SSE updates the visible preview without polling.
- EventSource disconnect or unsupported state restarts fallback polling.
- Manual refresh, hidden-tab recovery, frozen-draft protections, and in-flight
  mutation guards continue to pass.

## Test And Evidence Matrix

| Area | Required evidence |
| --- | --- |
| Core interop reliability | `./gradlew :fixthis-compose-core:test --tests "*TargetReliabilityCalculatorTest" --no-daemon` |
| MCP interop evidence | targeted `:fixthis-mcp:test` coverage for `TargetEvidenceService`, `EditSurfaceRoleClassifier`, and `EditSurfaceCandidateService` |
| Handoff rendering | targeted `:fixthis-mcp:test` renderer coverage for compact, precise, and full interop guidance |
| Handoff corpus | `npm run handoff:eval:test` with an interop boundary case included |
| SSE contract | `node --test scripts/studioReliabilityContract-test.mjs` |
| Console browser reliability | `npm run console:browser:reliability` with a healthy-SSE no-preview-fetch proof |
| Console fallback behavior | targeted console JS tests for EventSource failure, hidden tab, draft mode, and manual refresh |
| Broad local check | `npm run evidence:fast` or `npm run ci:local:fast` after focused checks pass |
| Graph refresh after code changes | `graphify update .` |

## Implementation Boundaries

- Track A may touch `:fixthis-compose-core`, `:fixthis-mcp` session/evidence
  services, handoff renderers, fixture/corpus tests, and reference docs.
- Track B may touch `fixthis-mcp/src/main/console`, console route/event tests,
  browser reliability scripts, and console state-sync docs.
- Track A must not introduce release-build, AccessibilityService, XML exact
  targeting, or WebView DOM behavior.
- Track B must not introduce WebSocket or a parallel state-sync transport.
- Both tracks must preserve persisted JSON compatibility.
- Docs and release notes should update only after behavior and tests land.

## Rollout Order

1. Implement Track A first to reduce downstream wrong-edit risk.
2. Add focused interop reliability, session, renderer, and corpus tests.
3. Implement Track B after Track A has clarified how weak target evidence is
   rendered.
4. Add healthy-SSE no-redundant-fetch tests plus disconnected fallback tests.
5. Update reference docs, roadmap status, changelog, and evidence runner matrix
   if command coverage changes.
6. Run focused verification, then a broad local profile, then `graphify update .`
   after code changes.

## Open Decisions Resolved In This Spec

- A and B ship under one umbrella spec with independent track boundaries.
- Track A is first because false source confidence is the higher product risk.
- Track B is cleanup of existing SSE architecture, not a transport redesign.
- Interop support remains guidance and evidence, not exact View/WebView source
  targeting.
- Polling remains as fallback and manual recovery, not as the healthy steady
  state.
