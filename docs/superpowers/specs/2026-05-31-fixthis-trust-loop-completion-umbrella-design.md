# FixThis Trust Loop Completion Umbrella - Design

**Date:** 2026-05-31
**Status:** Approved (design); implementation plan pending
**Topic:** Agent-loop lifecycle proof, runtime source-trust expansion, and
release-gate evidence aggregation

## Summary

FixThis now has a credible public v1.x surface: published install paths,
agent-first setup, real Copy Prompt evidence, source-candidate confidence,
interop warnings, and SSE-first console sync. The next high-leverage umbrella
should prove the full trust loop instead of adding another isolated capability.

The trust loop is:

```text
runtime UI evidence
-> honest source and target confidence
-> agent handoff
-> agent claims and resolves work
-> console and persisted session reflect outcome
-> release gate proves the evidence behind public claims
```

This umbrella combines three tracks:

- **Track A: External Agent Loop E2E Smoke.**
- **Track B: Runtime Source-Trust Expansion.**
- **Track C: Release Gate Report Pack.**

The tracks are independently shippable, but they defend one product promise:
FixThis should give coding agents local, debug-only Android UI evidence they
can trust before editing, and maintainers should be able to prove that promise
before release.

## Approved Direction

Use one umbrella spec with three bounded tracks:

1. First, expand runtime source-trust fixture coverage for risky evidence.
2. Second, prove the post-handoff agent lifecycle from claim through
   resolution.
3. Third, aggregate the evidence into a release-gate report that normalizes
   each public claim as `pass`, `deferred`, or `fail`.

This direction keeps the work product-focused rather than release-checklist
only. Agent lifecycle proof shows FixThis is useful after prompt generation;
runtime trust expansion keeps the evidence honest; the release gate makes the
result operationally verifiable.

## Goals

- Verify an external fixture can complete the FixThis lifecycle after handoff:
  `handoff -> read -> claim -> resolve -> persisted session -> console
  reflected`.
- Keep shared-component, interop-risk, visual-area, and weak source evidence
  useful without presenting it as exact edit ownership.
- Add runtime fixture assertions that fail when evidence becomes too confident
  for risky selections.
- Produce one release-gate report that aggregates core evidence as `pass`,
  `deferred`, or `fail`.
- Make deferred evidence explicit, with concrete reasons such as missing
  Android device, unavailable registry, missing credentials, or locked emulator.
- Reuse existing fixture labs, MCP JSON-RPC helpers, console browser automation,
  evidence runner patterns, and release-readiness checks.
- Preserve persisted MCP/session JSON compatibility contracts.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting.
- No WebView DOM inspection.
- No automatic code edits in the target Android app during smoke validation.
- No new package registry or distribution channel such as PyPI or Docker.
- No bridge-protocol or persisted JSON breaking change.
- No committed `.fixthis/`, `graphify-out/`, Android build output,
  screenshots, generated reports, local fixture workspaces, or Graphify output.

## Architecture Context

- `:fixthis-compose-core` owns source matching, target evidence, confidence,
  shared-component risk, and edit-surface classification. It must stay free of
  MCP, CLI, Android UI, and `.fixthis/` dependencies.
- `:fixthis-mcp` owns feedback sessions, compact/full handoff rendering, MCP
  tools, local HTTP console routes, console assets, and feedback lifecycle
  persistence.
- `:fixthis-cli` owns install-agent, doctor, package setup, and agent config
  writer flows.
- The browser console uses `/api/events` as the push-first state-sync channel,
  with polling kept for EventSource failure, manual refresh, or recovery.
- Release evidence currently spans `scripts/evidence-runner.mjs`,
  `scripts/release-reality-check.mjs`, `scripts/check-release-readiness.mjs`,
  connected smoke scripts, `docs/contributing/release-readiness.md`, and
  `docs/releases/unreleased.md`.

Persisted fields such as `items`, `screens`, `itemId`, `screenId`,
`targetEvidence`, `targetReliability`, and `sourceCandidates` are compatibility
contracts. New evidence must be optional and additive.

## Track B: Runtime Source-Trust Expansion

### Problem

FixThis already reports source candidates, edit-surface hints, target
confidence, shared-component caution, and interop-risk context. The remaining
trust risk is calibration under runtime evidence: risky selections should
provide useful starting points without implying exact source ownership.

The highest-risk cases are:

- reusable shared components where editing the definition changes many call
  sites;
- AndroidView/WebView or other interop boundaries where Compose can identify
  the host but not the View/WebView internals;
- visual-area selections where the selected region is not a clean component;
- weak or ambiguous source candidates that should remain verification hints.

### Design

Extend the runtime fixture lab so each risky case states the trust failure mode
it protects:

```text
runtime fixture case
-> generated source index
-> screen capture / selected target evidence
-> source candidates + edit-surface role
-> confidence cap + risk/caution tokens
-> compact/full handoff rendering
-> fixture report classification
```

Fixture assertions should support both positive and negative trust contracts.
A case may require that a specific source candidate appears, but it may also
require that confidence stays at or below a level, that a caution token appears,
or that an interop/visual target never renders as an exact high-confidence edit
surface.

The fixture report should distinguish:

- environment failure, such as missing ADB or locked device;
- setup failure, such as source-index generation failure;
- runtime capture failure;
- trust mismatch, such as overconfident evidence or missing caution text.

### Components

- `fixtures/source-matching/manifest.json`: add or extend runtime trust cases
  with explicit `trustPurpose` and expected confidence/risk constraints.
- `scripts/source-matching-fixtures.mjs`: add assertion and report fields only
  where the current manifest cannot express trust expectations.
- `fixthis-compose-core` tests: lock ranking, confidence caps, risk flags,
  shared-component behavior, and visual/interop target handling.
- `fixthis-mcp` tests: lock compact/full handoff rendering, target action
  language, caution text, and edit-surface semantics.
- Docs: update source-matching and fixture-lab references so future cases state
  the trust risk they guard.

### Error Handling

- Observed confidence higher than expected fails by default.
- Conservative downgrades are allowed only when a case explicitly permits them.
- Missing caution/risk tokens fail for cases designed to prevent overclaiming.
- Missing source candidates fail only for cases that require a candidate; visual
  or interop cases may instead require honest low-confidence rendering.
- Runtime setup errors are reported separately from trust mismatches.

### Acceptance Criteria

- Runtime fixture cases document `trustPurpose`.
- Shared-component, interop-risk, visual-area, and weak-candidate cases each
  have at least one runtime trust assertion.
- Reports distinguish environment failure, setup failure, capture failure, and
  trust mismatch.
- Handoff rendering tests prove risky evidence remains caveated in compact and
  detailed output.
- No persisted JSON field is renamed or made required.

## Track A: External Agent Loop E2E Smoke

### Problem

The real Copy Prompt smoke proves FixThis can open the real console, create
annotations, copy compact prompt text, and persist handoff timestamps. The next
product risk begins after prompt generation: agents need to claim feedback,
complete work, resolve items, and have the outcome reflected in local session
state and the console.

Without this lifecycle proof, FixThis can appear successful at handoff time
while still failing the workflow that makes feedback actionable.

### Design

Add an external agent-loop smoke focused on lifecycle semantics:

```text
external fixture app
-> install-agent / doctor readiness
-> open feedback console
-> create annotation
-> Save to MCP, plus Copy Prompt protocol parity
-> read feedback queue
-> claim item
-> simulated agent completion
-> resolve item
-> persisted status
-> console status reflection
```

The strict primary path should use **Save to MCP**, because claim and resolve
are MCP-backed lifecycle operations. Copy Prompt parity should verify that the
copied prompt contains enough session/item protocol context for a chat-style
agent to hand work back to an MCP-enabled environment.

Simulated completion means the smoke records deterministic resolution summaries
through `fixthis_resolve_feedback`. It does not patch the fixture app. App patch
correctness belongs to downstream agent verification, not lifecycle smoke.

### Components

- New script: `scripts/agent-loop-smoke.mjs`.
- New contract tests: `scripts/agent-loop-smoke-test.mjs`.
- Package scripts: `agent-loop:smoke` and `agent-loop:smoke:test`.
- Reuse `scripts/mcp-json-rpc-client.mjs` for MCP JSON-RPC calls.
- Reuse fixture install/runtime helpers from the source-matching fixture lab.
- Reuse browser console automation patterns from the real Copy Prompt and
  console reliability scripts.
- Persist reports under `build/reports/fixthis-agent-loop/`.

### Error Handling

- Missing ADB, missing device, locked device, or unauthorized device fails
  strict mode and is `deferred` only in non-strict evidence aggregation.
- MCP startup failure records process stderr, command, port, and report path.
- Console readiness failure records screenshot and browser error context.
- Claim failure distinguishes item not found, already claimed, stale session,
  and transport failure.
- Resolve failure distinguishes invalid status, missing summary, stale session,
  persistence failure, and transport failure.
- Console reflection failure records persisted status and actual rendered row
  state.

### Acceptance Criteria

- Contract tests cover argument parsing, fixture selection, lifecycle
  assertions, report rendering, and strict/non-strict environment handling.
- A strict connected run proves at least one external fixture completes
  `handoff -> read -> claim -> resolve -> console reflected`.
- The smoke validates `resolved`, `needs_clarification`, and `not_fixed`
  rendering either in one run or deterministic focused cases.
- Copy Prompt parity proves session/item protocol tokens are present for the
  same handoff.
- Generated `.fixthis/` data and reports remain ignored and uncommitted.

## Track C: Release Gate Report Pack

### Problem

FixThis has many strong individual checks: release reality, source-trust
fixtures, real Copy Prompt smoke, agent-loop smoke, console reliability, docs
consistency, and release-readiness rules. Maintainers still need one report that
answers whether current public claims are supported by current evidence.

The release risk is drift: docs can claim a package, registry entry, install
path, or trust guarantee that local evidence cannot prove.

### Design

Add a release-gate profile that aggregates evidence:

```text
evidence command
-> normalized result
-> pass / deferred / fail
-> JSON + Markdown report
-> readiness claim table
-> release issue or release-note attachment
```

The top-level command should be `npm run release:gate`. It may delegate to
`scripts/evidence-runner.mjs`, but the user-facing result should be a single
JSON and Markdown report under `build/reports/fixthis-release-gate/`.

Each report entry should include:

- command name and profile;
- status: `pass`, `deferred`, or `fail`;
- strictness mode;
- elapsed time;
- nested report paths when available;
- deferred reason or failure summary;
- release claims unlocked by the evidence.

### Evidence Inputs

The gate should include:

- release reality checks for Homebrew, npm, GitHub Release, MCP Registry, Maven
  Central, and Gradle Plugin Portal claims when queryable;
- Track B runtime source-trust fixture evidence;
- Track A external agent-loop smoke evidence;
- existing real Copy Prompt smoke evidence;
- console reliability evidence for SSE-first sync;
- docs consistency and release-readiness rules;
- release note/readiness claim mapping checks;
- diff/whitespace checks appropriate for release preparation.

Connected Android commands run in strict mode only when an unlocked emulator or
device is available. Non-strict aggregation may record them as `deferred`, but
strict release mode fails on any deferred required evidence.

### Error Handling

- Missing evidence command mapping is `fail`.
- Public registry values contradicting docs are `fail`.
- Network unavailable is `deferred` in non-strict mode and `fail` in strict
  mode when the check is required.
- Missing maintainer credentials are `deferred` only when the report names the
  credential or manual evidence needed.
- A release-note claim without a readiness row and evidence command is `fail`.
- A connected Android skip must use a concrete reason such as
  `SKIPPED_NO_DEVICE`, `SKIPPED_LOCKED_DEVICE`, or
  `SKIPPED_UNAUTHORIZED_DEVICE`.

### Acceptance Criteria

- `npm run release:gate` writes JSON and Markdown reports.
- Each tracked public claim maps to at least one evidence command.
- The release gate distinguishes `pass`, `deferred`, and `fail`.
- Strict mode fails on deferred or failed required evidence.
- `docs/contributing/release-readiness.md` and `docs/releases/unreleased.md`
  describe only evidence-backed claims or explicitly deferred checks.
- The report includes Track A and Track B results once those tracks land.

## Data Flow

```text
Risky runtime selection
-> target/source evidence with confidence and caution
-> fixture assertion prevents overclaiming
-> compact/full handoff preserves caveat
-> agent loop consumes handoff
-> MCP lifecycle records claim and resolution
-> console reflects final status
-> release gate aggregates proof
```

## Testing Strategy

- Kotlin unit tests for source matching, confidence caps, risk flags,
  shared-component behavior, visual selections, and interop-risk handling.
- MCP/session tests for compact/full handoff rendering, lifecycle persistence,
  claim/resolve state transitions, and output-schema compatibility.
- Console JS/browser tests for status reflection, Copy Prompt parity, and
  rendered caution semantics.
- Contract tests for `agent-loop-smoke.mjs` and release-gate report rendering.
- Runtime fixture tests in strict mode when a connected unlocked device or
  emulator is available.
- Evidence-runner profile tests proving release gate includes Track A, Track B,
  docs, release-readiness, and release reality checks.
- Final local hygiene: `git diff --check` and generated-artifact review before
  commit or PR.

## Sequencing

1. **Track B first:** expand runtime source-trust assertions and lock caution
   contracts before lifecycle smoke consumes the evidence.
2. **Track A second:** add the external agent-loop smoke and prove handoff work
   can be claimed, resolved, persisted, and reflected in the console.
3. **Track C third:** aggregate Track A/B evidence with existing release
   reality and docs checks into one release-gate report.
4. **Docs finalization:** update release-readiness and unreleased notes only
   after backing evidence paths exist or a deferred reason is explicit.

## Implementation Defaults

- Prefer extending existing scripts and helpers over introducing parallel
  harnesses.
- Keep runtime trust fixture assertions declarative in the manifest when
  possible.
- Keep lifecycle smoke deterministic; no fixture-app source patching.
- Keep release-gate output local under `build/reports/`.
- Treat weak evidence as useful context, not an edit command.
- Add optional fields only when existing `targetEvidence`,
  `targetReliability`, `sourceCandidates`, or boundary-context shapes cannot
  express the evidence honestly.

## Open Items for Implementation Planning

- Choose the first external fixture for strict agent-loop smoke based on the
  existing runtime fixture install path and connected-device reliability.
- Decide whether status variants (`resolved`, `needs_clarification`,
  `not_fixed`) run in one smoke session or in focused deterministic cases.
- Inventory current source-matching manifest fields before adding new report
  keys.
- Decide whether `npm run release:gate` is a direct script or a named
  `evidence-runner` profile wrapper.
