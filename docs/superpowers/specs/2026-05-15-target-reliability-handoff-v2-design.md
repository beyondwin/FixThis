# Spec - Target Reliability Handoff v2

Status: Design approved, pending implementation plan
Date: 2026-05-15
Scope: `:fixthis-compose-core`, `:fixthis-compose-sidekick`,
`:fixthis-mcp`, feedback console JS, Claude/Codex-facing docs and diagnostics

## Summary

FixThis already captures Compose semantics, screenshots, source candidates,
screen fingerprints, and saved feedback sessions. The next product-quality
step is to make the handoff more honest about target reliability.

This design adds a `TargetReliability` layer that tells the coding agent when a
selected target is strongly grounded in Compose semantics and source evidence,
when it is weakly grounded, and when it may represent an AndroidView/WebView or
other non-Compose visual area. The purpose is not to fully support View-based
UI in this pass. The purpose is to stop overclaiming precision when FixThis is
outside its Compose-first evidence model.

The primary package is "reliable handoff". It combines product functionality
with reliability hardening and a small amount of Claude/Codex DX polish. Cursor
and Aider first-class setup writers are explicitly out of scope.

## Goals

- Add an explicit reliability classification for annotation targets.
- Surface low-confidence, stale, and possible interop warnings in both Copy
  Prompt and Save to MCP handoffs.
- Detect visual-area-only selections where Compose semantics cannot explain
  the selected pixels.
- Detect likely AndroidView/WebView/interoperability zones as warnings, not as
  full source-matching support.
- Preserve all existing persisted JSON compatibility contracts.
- Keep `:fixthis-compose-core` free of Android, MCP, console, and `.fixthis/`
  path concerns.
- Improve Claude/Codex-facing docs and diagnostics so agents know how to act on
  reliability warnings.

## Non-Goals

- Cursor or Aider first-class MCP/config writers.
- Release build support.
- Full View hierarchy inspection, WebView DOM inspection, or Flutter support.
- Source matching for non-Compose pixels.
- SSE migration or live preview push sync.
- Cloud sync, external API calls, or multi-user collaboration.
- Renaming persisted MCP JSON fields such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, or `sourceCandidates`.

## Current State

FixThis currently provides strong evidence when a user selects a meaningful
Compose semantics node with useful source candidates. It is weaker in three
important cases:

- The selected area is a visual region with no meaningful Compose node.
- The rendered area may be hosted by AndroidView, WebView, or another
  non-Compose subtree.
- The frozen screen, current screen, source index, or draft workspace may have
  drifted between capture and handoff.

Recent work has already improved session durability, draft workspace recovery,
screen fingerprinting, source staleness detection, console FSM boundaries, and
CI coverage. This design builds on those foundations by making the remaining
uncertainty explicit in the agent-facing handoff.

## Product Principle

FixThis should prefer an honest warning over false precision.

When the target is well grounded, the handoff should be concise and confident.
When the target is not well grounded, the handoff should still be useful, but
it must tell the agent to verify before editing. A visual annotation is still
valuable; it just must not pretend that a Compose source candidate explains
pixels that Compose semantics did not expose.

## Architecture

### `:fixthis-compose-core`

Add a pure target reliability model and calculator. The core layer receives
evidence that is already domain-shaped and returns a compact judgment:

- `confidence`: `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN`
- `reasons`: short machine-readable reason codes plus human-readable summary
- `warnings`: source-stale, visual-area-only, possible-interop, forced-mismatch,
  fingerprint-unavailable, or low-source-margin signals

The calculator depends only on core models such as selected target, nearby
nodes, source candidates, screen fingerprint metadata, source freshness, and
selection bounds. It does not import Android runtime types, MCP DTOs, console
state, or file-system paths.

### `:fixthis-compose-sidekick`

The sidekick continues to capture raw screen evidence. It may add additive
bridge fields needed to reason about Compose coverage around the selection:

- selected target bounds
- root bounds
- meaningful semantics node coverage near the selection
- whether the selection was node-based or visual-area-only

The sidekick does not make final product claims such as "this is definitely a
WebView". It provides enough raw evidence for the core/MCP layer to emit a
possible interop warning.

### `:fixthis-mcp`

MCP owns persistence compatibility and agent-facing rendering.

- Attach reliability metadata to saved feedback items.
- Keep legacy session decoding working when reliability metadata is absent.
- Render reliability in compact Markdown with the same wording for Copy Prompt
  and Save to MCP.
- Preserve existing field names and add only optional fields.

The persisted shape is an additive optional `targetReliability` object on each
saved item, as a sibling of `targetEvidence`. `targetEvidence` keeps its
current meaning as captured target evidence; reliability is the derived
judgment over that evidence. The implementation plan should avoid duplicating
the same reliability object inside `targetEvidence`.

### Feedback Console JS

The console should not turn reliability into a heavy workflow gate.

- High and medium confidence items keep the current flow.
- Low confidence or warning items show compact badges in the item row and the
  handoff readiness summary.
- Saving remains allowed unless the existing validation/session/fingerprint
  guard returns a blocking error.
- Recovery and draft flows must keep reliability metadata scoped to the
  originating session/workspace.

### Claude/Codex DX

Docs and diagnostics should explain reliability warnings in action terms:

- High confidence: source candidates are likely safe starting points.
- Medium confidence: inspect candidates before editing.
- Low confidence: treat source candidates as hints only.
- Possible interop: verify whether the rendered pixels come from AndroidView,
  WebView, or another non-Compose boundary before changing Compose code.

Cursor and Aider setup writers remain excluded.

## Data Model

The implementation should introduce a compact model equivalent to:

```kotlin
data class TargetReliability(
    val confidence: TargetConfidence,
    val reasons: List<TargetReliabilityReason>,
    val warnings: List<TargetReliabilityWarning>,
)
```

Recommended confidence values:

- `HIGH`: strict composable identity or strong source candidate evidence, stable
  screen context, meaningful selected node.
- `MEDIUM`: usable Compose node and source hints, but weaker margin or missing
  some corroborating evidence.
- `LOW`: visual-area-only target, no meaningful Compose node, possible interop
  area, stale source/screen signal, or very weak source margin.
- `UNKNOWN`: legacy sessions or missing evidence where the formatter should
  omit strong claims.

Recommended warning codes:

- `VISUAL_AREA_ONLY`
- `NO_MEANINGFUL_COMPOSE_TARGET`
- `POSSIBLE_VIEW_INTEROP`
- `LOW_SOURCE_CANDIDATE_MARGIN`
- `SOURCE_INDEX_STALE`
- `SCREEN_FINGERPRINT_MISMATCH_FORCED`
- `SCREEN_FINGERPRINT_UNAVAILABLE`
- `SENSITIVE_TEXT_REDACTED`

Reason and warning text must not include raw sensitive text. Redacted text
markers may explain that evidence was withheld, but they must not lower the
privacy bar established by `RedactionPolicy`.

## Handoff UX

Each item should render at most one concise reliability line plus optional
warning lines when needed.

Examples:

```text
Target confidence: high - strict composable tag + close source margin.
Target confidence: medium - Compose node matched, but source margin is narrow.
Target confidence: low - visual area only; no meaningful Compose semantics node.
Warning: possible AndroidView/WebView area; source candidates may not explain rendered pixels.
Warning: screen changed after capture; user force-saved this item.
```

The handoff should avoid long educational prose. The detailed explanation
belongs in docs; the prompt should make the next agent action obvious.

## Blocking Errors vs Saved Warnings

### Blocking errors

These continue to prevent save or handoff:

- preview/session id mismatch
- frozen preview record missing
- invalid item payload
- screenshot artifact promotion failure
- current capture failure when the operation requires current capture
- force override attempting to bypass a non-mismatch validation failure

### Saved warnings

These do not block save; they are persisted and rendered:

- low source candidate margin
- no meaningful Compose target
- possible AndroidView/WebView/non-Compose interop area
- fingerprint unavailable
- source index possibly stale
- user force-saved a fingerprint mismatch
- sensitive/redacted target text reduced available evidence

## Compatibility

- Existing sessions without reliability metadata remain readable.
- Existing public field names remain unchanged.
- New persisted fields are optional and additive.
- JSON formatter includes complete reliability metadata when present.
- Markdown formatter omits the reliability block for legacy `UNKNOWN` targets
  unless a warning is available.
- Bridge protocol changes, if needed, are additive and synchronized through the
  existing protocol-version checks.
- Release builds remain unsupported and unaffected.

## Phases

### Phase 1 - Core model and Markdown output

- Add pure `TargetReliability` model/calculator in `:fixthis-compose-core`.
- Attach reliability metadata in MCP save/handoff paths using existing
  evidence.
- Update compact handoff Markdown and JSON output.
- Add legacy decode and formatter tests.

This phase should ship value without requiring sidekick bridge changes.

### Phase 2 - Console warning display

- Add compact row/readiness warnings for low confidence and interop warnings.
- Keep save behavior non-blocking for warnings.
- Verify draft recovery and session switching preserve warning scope.

### Phase 3 - Sidekick interop evidence

- Add raw evidence for semantics coverage around selected areas.
- Add at least one AndroidView/WebView-like regression fixture. Prefer the
  sample app so the browser console flow can exercise it; use a sidekick test
  fixture only if the sample app cannot represent the case without unrelated
  UI churn.
- Add additive bridge fields and protocol sync tests if the field crosses the
  bridge boundary.

### Phase 4 - Claude/Codex diagnostics and docs

- Update `docs/guides/agents.md`, `docs/reference/mcp-tools.md`, and related
  troubleshooting docs with warning interpretation.
- Extend `fixthis_status` or `fixthis doctor` diagnostics where the existing
  output naturally fits capability and staleness signals.
- Keep Cursor/Aider setup support out of scope.

## Test Plan

### Core tests

- High confidence from strict composable identity plus strong source margin.
- Medium confidence from usable Compose node with weaker corroboration.
- Low confidence from visual-area-only target.
- Low confidence or warning from possible interop evidence.
- Stale source/screen signals reduce confidence or add warnings.
- Redacted/sensitive text does not leak into reasons.

### MCP/session tests

- Legacy sessions decode without reliability metadata.
- Reliability metadata persists and reloads.
- Copy Prompt and Save to MCP render equivalent reliability wording.
- Force-saved mismatch records a warning.
- Low-confidence items remain saveable.
- JSON includes reliability metadata without renaming existing fields.

### Console JS tests

- Readiness summary renders warnings.
- Item rows render confidence and warning badges without layout breakage.
- Visual-area annotation save payload preserves warning context.
- Draft recovery keeps reliability metadata scoped to the correct session.
- Session switching cannot leak warnings between workspaces.

### Sidekick/bridge tests

- Coordinates with meaningful Compose nodes produce coverage evidence.
- Coordinates without meaningful Compose nodes produce visual-area evidence.
- AndroidView/WebView-like regression fixture emits possible interop evidence.
- Additive bridge fields remain optional for compatibility.

## Acceptance Criteria

- Existing sample flow, Copy Prompt, and Save to MCP still work.
- Existing saved sessions open unchanged.
- New saved items can include target reliability metadata.
- Agent-facing Markdown clearly distinguishes high-confidence targets from
  visual-only or possible-interop targets.
- Low-confidence annotations can still be saved.
- Possible AndroidView/WebView regions are not presented as reliably
  source-matched Compose targets.
- Claude/Codex docs explain how to respond to reliability warnings.
- No release-build support, external API call, or Cursor/Aider setup writer is
  introduced.

## Risks

- Interop detection may be heuristic. Mitigation: label it as "possible
  interop" and avoid blocking user workflow.
- More metadata can bloat prompts. Mitigation: one concise confidence line and
  warning lines only when needed.
- Reliability logic may duplicate source-matching policy. Mitigation: keep
  source scoring in the existing source package and make reliability consume
  summarized evidence.
- Console warnings may feel noisy. Mitigation: show only low/conflict warnings
  in the main readiness surface.

## Future Work

- SSE event stream migration for real-time console state sync.
- Full View hierarchy inspection if FixThis later expands beyond Compose V1.
- Release publication to Maven Central and the Gradle Plugin Portal.
- First-class integrations for agents beyond Claude/Codex only if explicitly
  prioritized later.
