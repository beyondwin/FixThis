# v0.6 Umbrella Roadmap Design

Date: 2026-05-18
Status: Ready for user review
Scope: v0.6 milestone framing across handoff quality, Studio reliability, and
release-grade evidence

## Summary

v0.6 moves FixThis from "a tool that can get a first agent handoff working" to
"a repeatable agent handoff system that improves real code-change outcomes and
can prove its release claims."

The release is an umbrella milestone, not one large implementation plan. It has
three independent tracks:

1. **Track A: Handoff Intelligence + Evaluation** - make handoffs more useful
   to coding agents, and prove that improvement with a deterministic corpus
   before changing the intelligence layer.
2. **Track B: Studio Reliability** - make the browser console's session,
   draft, save, recovery, and stale-preview flows reliable under repeated use.
3. **Track C: Release Grade** - ensure v0.6 release notes and documentation
   claims are backed by executable evidence, not maintainer memory.

Track A is the product lead. Track B and Track C are supporting tracks that
make Track A safe to use and honest to publish.

## Product Goal

The v0.6 user-facing claim is:

> FixThis handoffs should help an agent find the right edit surface more often,
> remain stable through repeated Studio use, and ship only with evidence that
> supports the release claims.

This follows naturally from v0.5. v0.5 focuses on README-first onboarding and
first-run trust. v0.6 focuses on what happens after the first handoff: whether
the agent can act on the handoff effectively, whether Studio preserves that
handoff accurately, and whether the project can publish those claims with
confidence.

## Non-Goals

- No production runtime support.
- No release-build sidekick behavior.
- No XML/View, WebView DOM, Flutter, React Native, iOS, or cloud workflow
  expansion.
- No automatic code edits inside FixThis.
- No guaranteed exact source-line mapping.
- No broad visual redesign of FixThis Studio.
- No rename of persisted MCP JSON compatibility fields such as `items`,
  `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, or `sourceCandidates`.
- No requirement that every connected, package-manager, compatibility, or
  performance check become a PR-time gate.

## Current Baseline

The repository already has the right foundations:

- README-first onboarding, agent bootstrap docs, and first-run readiness gates
  are in place for v0.5.
- `fixthis install-agent`, `fixthis doctor --json`, MCP setup, and
  `.fixthis/agent-setup.*` surfaces exist.
- Handoffs already include selected target evidence, source candidates,
  confidence, warning metadata, screenshots, and compact Markdown.
- The console already has session-scoped preview and update events, draft
  recovery, stale-preview handling, idempotent draft saves, and handoff
  persistence.
- Release readiness, docs/CLI surface checks, observation checks, package
  integrity tests, and release notes structure exist.

The gap is not missing primitives. The gap is that post-first-run quality is
not yet measured as a product outcome, and release claims are not yet tied to a
single v0.6 evidence bundle.

## Milestone Shape

v0.6 should be planned as one umbrella spec with three implementation plans:

- `v0.6-handoff-intelligence`
- `v0.6-studio-reliability`
- `v0.6-release-grade`

Each implementation plan can land independently. The umbrella release claim is
available only when all three track gates have passing evidence or the release
notes explicitly narrow the claim.

## Track A: Handoff Intelligence + Evaluation

### Goal

Make agent handoffs move beyond "here are three source candidates" toward "here
is the likely edit surface, why it is likely, and when not to trust it."

Track A must be measured. It is not acceptable to ship a more complex source or
intent heuristic based only on intuition.

### A0: Handoff Evaluation Corpus

Create a deterministic corpus from the sample app and stored session fixtures.
Each case represents a realistic feedback item with an expected edit surface.

Example categories:

- Button or label copy change: `COPY_OR_DATA` or direct call site.
- Reusable card visual change: `COMPONENT_DEFINITION`.
- One repeated list item copy change: call site or data source, not the shared
  component definition.
- Spacing, padding, alignment, or size feedback: `LAYOUT_OR_STYLE`.
- Dragged empty area or weak-semantics target: `VISUAL_AREA`.
- AndroidView/WebView or interop warning case: `INTEROP_RISK`.
- Ambiguous text repeated across files: low confidence, not a high-confidence
  edit-surface claim.

Each corpus item should include:

- user comment;
- selected node or visual-area evidence;
- source candidates;
- expected edit-surface label;
- expected caution behavior when ambiguity or interop is present;
- optional expected first-inspection file when that can be asserted without
  pretending source mapping is exact.

### A1: Baseline Measurement

Before changing intelligence behavior, measure current output against the
corpus. The baseline should capture:

- top-1 and top-3 source-candidate coverage;
- edit-surface label accuracy for cases where the current system already emits
  an edit-surface hint;
- warning precision for visual-area, ambiguous, stale-source, and interop
  cases;
- high-confidence wrong-surface count;
- compact prompt size for corpus handoffs.

This establishes whether v0.6 actually improves handoff quality.

### A2: Intelligence Changes

After the baseline exists, implement the smallest changes that improve measured
agent usefulness:

- classify edit surface as `CALL_SITE`, `COMPONENT_DEFINITION`,
  `COPY_OR_DATA`, `LAYOUT_OR_STYLE`, `VISUAL_AREA`, or `INTEROP_RISK`;
- use the user comment as an input signal for intent, but never as the sole
  high-confidence source signal;
- distinguish source candidate roles where existing data supports it: rendering
  call site, owner composable, reusable component definition, copied string or
  data source, and weak fallback;
- preserve explainability by rendering why an edit surface was suggested;
- lower confidence or emit warnings when evidence is weak, repeated, stale, or
  interop-adjacent.

The implementation should prefer small policy objects and existing source
matching boundaries over a broad rewrite.

### A3: Regression Gate

Track A can claim improvement only if the evaluation harness shows:

- top-3 candidate coverage is preserved or improved;
- high-confidence wrong edit-surface count is zero;
- visual-area, interop, stale-source, and ambiguous cases are not overclaimed;
- prompt size remains within the existing compact handoff budget or any budget
  increase is explicitly accepted in release notes;
- persisted JSON compatibility fields are unchanged;
- existing handoff Markdown grammar changes are additive and documented.

### A4: Real-Agent Trial

The deterministic corpus is the PR gate. A real-agent trial is release evidence,
not a per-PR blocker.

For release evidence, run a small set of real handoff tasks through an agent and
record whether the agent inspected the expected first file or edit surface
before editing. The output can be a work note or release evidence appendix.
This observation should inform the next corpus update.

## Track B: Studio Reliability

### Goal

Ensure that Studio reliably preserves, saves, recovers, and displays handoffs
through repeated use.

Track B does not change source matching or handoff intelligence. It protects the
state pipeline that Track A relies on.

### Scope

Track B covers:

- **Session switch safety:** late SSE, polling, and preview responses must not
  mutate the active session when their `sessionId` does not match.
- **Draft recovery clarity:** browser-local draft recovery, saved MCP items,
  pin-only residuals, stale draft conflicts, and deleted sessions must have
  clear ownership and non-overlapping recovery behavior.
- **Duplicate save idempotency:** reconnects, retries, and slow responses must
  not create duplicate saved annotations for the same draft item ids.
- **Stale preview behavior:** frozen preview, stale frame, capture unavailable,
  screen mismatch, and device-blocked states must gate buttons and input
  consistently.
- **Console state contracts:** reducer, selector, and route tests should protect
  user-visible state transitions instead of incidental DOM or global-state
  implementation details.

### Acceptance

- Cross-session update tests cover SSE, preview polling, session polling, and
  saved item mutation paths.
- Draft recovery scenarios cover written comments, pin-only residuals, stale
  server draft conflicts, session deletion, and Save to MCP completion.
- Idempotent save tests prove retries do not duplicate work and that adding one
  new written item to a retried draft persists only the new item.
- Console smoke or harness scenarios exercise the user-visible blocked/stale
  paths that matter for the v0.6 release claim.
- Any reduction in console global projections is guarded by behavior tests, not
  source-shape assertions alone.

## Track C: Release Grade

### Goal

Make every major v0.6 release claim traceable to executable evidence.

Track C is not a new product feature. It is a release contract that prevents
documentation, CLI behavior, MCP tools, output schema, and artifact claims from
drifting apart after Track A and Track B land.

### C1: Release Claim Manifest

Add a v0.6 release-claim section to `docs/contributing/release-readiness.md`.
It should list each claim and the evidence required before that claim can appear
in release notes.

Example claims:

- Handoff Intelligence improved measured edit-surface usefulness.
- Studio preserves drafts, saved items, and session updates under repeated use.
- Public install and MCP setup docs match the shipped CLI and artifacts.

If evidence is missing, release notes must narrow or omit the claim.

### C2: Docs, CLI, MCP, And Schema Drift Gates

Extend existing drift checks instead of creating a parallel system.

The checks should verify:

- README, AGENTS.md, MCP.md, and getting-started docs reference valid CLI
  commands and flags;
- MCP tool reference matches the actual registry for tool names and major
  field contracts;
- output-schema docs still name protected persisted JSON fields;
- release notes do not advertise unverified or unpublished install paths;
- docs that describe v0.6 Track A/B behavior include the same caution language
  used by the handoff output.

### C3: Track Evidence Gates

Track C owns the release-time bundle of A/B evidence:

- Track A: evaluation corpus summary, baseline comparison, regression-gate
  results, and optional real-agent trial notes.
- Track B: console reliability contract/smoke results, including session,
  draft, duplicate-save, stale-preview, and blocked-device coverage.
- Cross-track: compact prompt budget, persisted JSON compatibility, MCP
  reference, and release note accuracy.

### C4: Observation Gate Policy

Connected tests, nightly compatibility, and package-manager checks should not
automatically become PR-time gates. Track C should make their release policy
explicit:

- if observation checks are ready, v0.6 can cite them directly;
- if observation checks are not ready, release notes must record the deferral;
- if a release claim depends on a not-ready observation check, hold the release
  or narrow the claim;
- `npm run checks:observation -- --json` output should be attached to release
  evidence.

### C5: Artifact Integrity Gate

Release evidence should confirm that shipped install paths match documentation:

- GitHub CLI/MCP tarball and checksum sidecar exist;
- npm wrapper resolves the expected package and checksum;
- Homebrew formula points at the intended release package;
- Gradle plugin and Maven coordinates match README and release notes;
- MCP Registry metadata matches the published package and docs;
- release/minify consumer fixture is run or explicitly deferred.

## Architecture And Data Flow

The umbrella data flow is:

```text
Compose screen capture
-> selected target + user comment
-> source candidates + target evidence
-> Track A: edit-surface interpretation + measured quality
-> Track B: stable draft/session/save/recovery pipeline
-> Track C: release evidence gates and docs/contracts
```

Track A consumes handoff evidence and produces improved interpretation. Track B
ensures that evidence is saved and recovered correctly. Track C ensures that
the claims made about those behaviors are tied to checks.

## Contracts

The following contracts are protected:

- Persisted MCP JSON field names: `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, and `sourceCandidates`.
- Debug-only and Compose-only product scope.
- Local-first behavior with no external AI API calls.
- Source candidates remain explainable hints, not guaranteed exact answers.
- Visual-area, interop, ambiguous, and stale-source cases must remain cautious.
- Release claims require matching evidence or must be narrowed.

## Testing Strategy

Track A:

- corpus fixture tests;
- baseline metric command;
- edit-surface classification unit tests;
- compact handoff regression tests;
- JSON compatibility tests;
- optional release evidence from a real-agent trial.

Track B:

- console reducer and selector contract tests;
- route tests for session, item, preview, and handoff behavior;
- SSE and polling stale-session tests;
- duplicate-save/idempotency tests;
- console smoke or harness scenarios for blocked and stale states.

Track C:

- release-readiness script rules for v0.6 claims;
- docs/CLI/MCP surface checks;
- output-schema protected-field checks;
- package and registry metadata checks;
- observation JSON capture for release evidence.

## Rollout

1. Write the Track A implementation plan first because it defines the measured
   product value.
2. Write the Track B implementation plan second because it protects the Studio
   state pipeline that carries the improved handoff.
3. Write the Track C implementation plan third because it binds A/B evidence to
   release readiness.
4. Land each track in independent PR-sized slices.
5. Publish v0.6 claims only after the evidence bundle is complete.

## Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| Intelligence changes add complexity without improving outcomes. | Require corpus baseline and measured improvement before shipping. |
| Comment-intent heuristics overfit sample phrasing. | Keep user comment as one signal, not the sole source of confidence. |
| Edit-surface labels create false certainty. | Require zero high-confidence wrong-surface cases and preserve caution warnings. |
| Console reliability work becomes a visual redesign. | Limit Track B to state, recovery, save, and smoke contracts. |
| Release gates slow every PR. | Keep slow connected and compatibility checks as release/observation evidence unless separately promoted. |
| Docs drift after implementation. | Track C ties release claims to executable checks and protected schema references. |

## Acceptance For The Umbrella Spec

The v0.6 umbrella design is accepted when:

- the milestone is split into Track A, Track B, and Track C implementation
  plans;
- Track A starts with evaluation corpus and baseline measurement;
- Track B protects repeated Studio use without expanding source matching scope;
- Track C requires evidence for every major v0.6 release claim;
- no track expands beyond FixThis V1 constraints.
