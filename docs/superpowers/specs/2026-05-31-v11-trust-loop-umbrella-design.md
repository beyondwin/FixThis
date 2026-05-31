# v1.1 Trust Loop Umbrella - Design

**Date:** 2026-05-31
**Status:** Approved (design); implementation plan pending
**Topic:** v1.0 release reality, external agent loop closure, and runtime source-trust expansion

## Summary

FixThis has reached a v1.0-ready shape for source matching, interop warnings,
SSE-first console sync, agent install docs, and release claim manifests. The next
high-leverage program should prove the full trust loop after v1.0:

1. the release/install claims match real external-user paths;
2. the handoff lifecycle works past prompt creation into claim and resolution;
3. runtime source-trust fixtures keep weak or risky evidence from being
   over-presented as exact edit ownership.

This is one umbrella because all three tracks defend the same product promise:
FixThis should give coding agents local, debug-only Android UI evidence they can
trust before editing an app. Each track remains independently testable and
shippable so a release-channel issue does not weaken handoff lifecycle checks,
and a runtime fixture failure does not block documentation honesty.

## Approved Direction

The approved approach is a three-track umbrella:

- **Track A: v1.0 release reality check.**
- **Track B: external agent loop end-to-end smoke.**
- **Track C: runtime source-trust expansion.**

The umbrella does not automatically tag or publish a release. It designs the
evidence and guardrails needed to decide whether a v1.x release claim is real.

## Goals

- Tie every release-facing claim to a concrete local command, registry check, or
  explicitly deferred reason.
- Verify an external Android fixture can complete the FixThis loop from
  install/doctor through feedback handoff, claim, resolution, persistence, and
  console reflection.
- Extend runtime source-trust coverage for high-risk cases: shared reusable
  components, interop-risk selections, visual-area selections, and weak
  source-candidate evidence.
- Keep weak evidence useful as context without presenting it as exact source
  ownership.
- Reuse existing evidence runner, fixture lab, MCP helpers, console browser
  automation, and runtime fixture install helpers instead of creating parallel
  validation systems.
- Preserve all persisted MCP/session JSON compatibility contracts.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting and no WebView DOM inspection.
- No automatic code edits in the external fixture app during the smoke. The
  Track B smoke verifies FixThis lifecycle semantics, not app patch correctness.
- No new package channel such as PyPI or Docker.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  generated reports, local fixture workspaces, or Graphify output.
- No bridge-protocol or persisted JSON breaking change.

## Architecture

The umbrella is evidence-first and additive.

- **Release evidence layer:** `docs/contributing/release-readiness.md`,
  `docs/releases/*`, `scripts/check-release-readiness.mjs`, and
  `scripts/evidence-runner.mjs` own public claim validation.
- **Agent-loop smoke layer:** a new smoke script should orchestrate existing
  CLI, MCP, console, browser, and fixture helpers to verify lifecycle behavior
  end-to-end.
- **Runtime source-trust layer:** `fixtures/source-matching/manifest.json`,
  `scripts/source-matching-fixtures.mjs`, `fixthis-compose-core`, and
  `fixthis-mcp` own runtime trust observations and handoff rendering assertions.

Track B proves the workflow finishes. Track C proves the workflow remains honest
about source confidence. The evidence runner can aggregate both results, but the
scripts should stay separate so failures are attributable.

Core invariants remain unchanged:

- `:fixthis-compose-core` has no dependency on MCP, CLI, Android UI, or
  `.fixthis/` paths.
- Bridge protocol changes require the existing coordinated version checklist;
  none are expected here.
- Persisted fields such as `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, and `sourceCandidates` are compatibility
  contracts. New fields must be optional and additive.

## Track A: v1.0 Release Reality Check

### Problem

The repository now contains v1.0 release notes, release-readiness claims, public
install paths, and package-channel documentation. The risk is not lack of docs;
the risk is drift between docs, tags, package artifacts, registry metadata,
install commands, and what an external user can actually run.

### Design

Add a release reality profile that treats each public surface as one of three
states:

- **verified:** a command or registry query proves the claim from a clean
  environment or current checkout;
- **deferred:** the check requires credentials, network state, or an external
  artifact not available in the local run, and the report records the reason;
- **mismatch:** docs or release notes claim something that evidence cannot
  support.

Release claims flow through the existing readiness model:

```text
release claim
-> release-readiness manifest row
-> required evidence command or registry check
-> evidence report / release issue attachment
-> release note claim allowed
```

The implementation plan should prefer extending existing scripts over adding a
parallel release checker.

### Components

- `docs/contributing/release-readiness.md`: add a v1.1 Trust Loop claim
  manifest tying claims to evidence.
- `docs/releases/unreleased.md`: keep user-facing language aligned with what the
  evidence can prove. A versioned release note file is created only when a
  concrete release is prepared.
- `scripts/check-release-readiness.mjs`: fail when required manifest sections or
  evidence mappings are absent.
- `scripts/evidence-runner.mjs`: expose a release reality profile or extend the
  release profile with clear deferred reporting.
- Package/install checks: verify Homebrew, npm, GitHub Release, MCP Registry,
  Maven Central, and Gradle Plugin Portal claims only when the environment can
  query them reliably.

### Error Handling

- Network or registry unavailable is `deferred`, not `passed`.
- A registry response that contradicts docs is `mismatch` and fails the check.
- Missing tags, missing tarballs, stale package versions, or release-note version
  drift must fail the strict profile.
- Checks requiring maintainer credentials must document the required secret or
  manual evidence rather than silently skipping.

### Acceptance Criteria

- Each advertised install surface has one manifest row and one evidence path.
- Strict release reality checks fail on claim mismatch.
- Non-strict evidence reports record deferred checks with reasons.
- Release notes do not claim a tag, artifact, or package version that the
  evidence layer cannot observe or explicitly defer.

## Track B: External Agent Loop E2E

### Problem

The existing real Copy Prompt smoke verifies that FixThis can open the real
feedback console, create written annotations, copy a compact prompt, and persist
handoff timestamps. The next lifecycle risk starts after prompt generation:
agents must claim items, complete work, resolve items, and have that resolution
reflected in local session state and the console.

### Design

Add an external agent-loop smoke focused on FixThis lifecycle semantics:

```text
external fixture app
-> fixthis install-agent / doctor
-> open feedback console
-> create annotation
-> Copy Prompt or Save to MCP
-> read handoff item
-> claim item
-> simulated agent completion
-> resolve item
-> persisted session status
-> console shows resolved / needs clarification / not fixed
```

The primary strict path should use `Save to MCP`, because claim and resolve are
MCP-backed lifecycle operations. Copy Prompt parity should be verified by
ensuring the copied prompt carries the same item/session protocol tokens and can
drive the same claim/resolve calls when MCP is available.

"Simulated agent completion" means the script records a deterministic summary
and status through `fixthis_resolve_feedback`. It does not patch the external
app, because that would couple lifecycle validation to fixture-specific source
diffs and rollback behavior.

### Components

- New script: `scripts/agent-loop-smoke.mjs`.
- New contract tests: `scripts/agent-loop-smoke-test.mjs`.
- New package scripts: `agent-loop:smoke` and `agent-loop:smoke:test`.
- Reuse existing MCP JSON-RPC helpers and parsing patterns from the real Copy
  Prompt smoke.
- Reuse runtime fixture installation helpers from the source-matching fixture
  lab.
- Reuse browser automation patterns for console readiness, annotation creation,
  and rendered status assertions.
- Persist artifacts under `build/reports/fixthis-agent-loop/`.

### Error Handling

- Missing device or emulator fails strict mode and becomes deferred only in a
  non-strict evidence profile.
- MCP startup failure records process stderr, command, port, and report path.
- Console readiness failure records screenshot and browser error context.
- Claim failure distinguishes "item not found", "already claimed", and transport
  failure.
- Resolve failure distinguishes invalid status, missing summary, stale session,
  and persistence failure.
- Console reflection failure records the persisted session status and the actual
  rendered row state.

### Acceptance Criteria

- The pure contract test covers argument parsing, fixture selection, lifecycle
  assertions, report rendering, and strict/non-strict environment behavior.
- A strict connected run can prove at least one external fixture completes
  `handoff -> claim -> resolve -> console reflected`.
- The smoke validates `resolved`, `needs_clarification`, and `not_fixed` status
  rendering either in one run or through deterministic focused cases.
- The smoke never commits local `.fixthis/` or generated report artifacts.

## Track C: Runtime Source-Trust Expansion

### Problem

FixThis already warns on weak target evidence and provides source candidates,
edit-surface roles, call-site context, and interop boundary context. The next
trust risk is calibration under runtime evidence: high-risk selections should
produce useful guidance without accidentally upgrading uncertainty into exact
edit ownership.

### Design

Expand runtime trust fixtures around cases where false confidence is costly:

```text
runtime fixture app
-> generated source index
-> runtime screen capture
-> selected target evidence
-> source candidate / edit-surface candidate
-> confidence + risk flags + caution text
-> compact/full handoff rendering
-> fixture report classification
```

The fixture lab should assert both positive and negative trust contracts. A case
can require a specific candidate, but it can also require that confidence stays
medium or low, that a caution token appears, or that a visual/interop selection
does not render as exact source ownership.

### Components

- `fixtures/source-matching/manifest.json`: add runtime trust cases for shared
  reusable components, interop-risk selections, visual-area selections, and weak
  source-candidate evidence.
- `scripts/source-matching-fixtures.mjs`: extend runtime assertion/reporting only
  where current report fields cannot express the new trust expectations.
- `fixthis-compose-core` tests: lock candidate ranking, confidence caps, risk
  flags, and source matcher behavior.
- `fixthis-mcp` tests: lock compact/full handoff rendering, edit-surface roles,
  confidence basis, and caution text.
- Docs: update fixture-lab and source-trust references so future cases state the
  trust failure mode they protect.

### Error Handling

- Expected confidence higher than observed may be allowed only when the case
  explicitly permits conservative downgrades.
- Observed confidence higher than expected fails by default.
- Missing caution/risk tokens fail when the case is designed to protect an
  overclaiming scenario.
- Runtime fixture setup failure is separate from trust mismatch in the report.
- Source-index generation failure records the fixture id, module, command, and
  log path.

### Acceptance Criteria

- Runtime fixture cases document `trustPurpose`.
- Reports distinguish environment failure, setup failure, and trust mismatch.
- Shared-component, interop-risk, and visual-area cases each have at least one
  runtime trust assertion.
- Handoff rendering tests prove weak evidence remains caveated in compact and
  detailed output.

## Cross-Track Evidence Profiles

The implementation plan should add or extend evidence profiles without hiding
canonical commands:

- **Fast/local contract**
  - `node --test scripts/agent-loop-smoke-test.mjs`
  - `npm run source-matching:fixtures:test`
  - `npm run evidence:test`
  - release-readiness and docs/CLI drift checks
- **Connected/runtime**
  - `npm run agent-loop:smoke -- --strict`
  - `npm run source-matching:fixtures:runtime -- --strict`
  - `npm run real-copy-prompt:smoke -- --strict`
- **Release reality**
  - `npm run evidence:release`
  - package/registry verification commands where available
  - `node scripts/check-release-readiness.mjs`
  - `bash scripts/check-docs-cli-surface.sh`

Strict connected commands fail when required devices or artifacts are missing.
Non-strict evidence profiles may defer them, but must record the reason.

## Documentation Plan

- This design spec is the approved source for the next implementation plan.
- The implementation plan should live under `docs/superpowers/plans/` with the
  same topic and track structure.
- `docs/contributing/release-readiness.md` should receive the v1.1 Trust Loop
  claim manifest during implementation.
- `docs/product/roadmap.md` should mention v1.1 trust loop only as the next
  evidence-focused post-v1.0 hardening line.
- `CHANGELOG.md` and release notes should be updated only when implementation
  changes land, not as part of this design-only spec.

## Sequencing

1. Track A release reality manifest/checks.
2. Track B agent-loop smoke contract and implementation.
3. Track C runtime source-trust fixture expansion.
4. Cross-track evidence runner integration.
5. Documentation updates and final verification.

Track B and Track C can be implemented in parallel after Track A defines the
evidence vocabulary. Final release reality checks should run last so they verify
the finished umbrella rather than pre-implementation assumptions.

## Risks

| Risk | Mitigation |
| --- | --- |
| Umbrella grows too large to land safely. | Keep tracks independently shippable and stop at evidence integration boundaries. |
| Release checks depend on mutable registry/network state. | Separate verified, deferred, and mismatch states; fail only mismatches in non-strict mode. |
| Agent-loop smoke becomes an app-patching test. | Simulate completion through MCP resolution; leave app diff validation out of scope. |
| Runtime trust fixtures become brittle to visual changes. | Assert trust tokens, confidence, and candidate behavior rather than pixel-perfect screenshots except where needed for console readiness. |
| Weak evidence gets upgraded accidentally. | Treat confidence over-promotion as a default failure in Track C. |

## Implementation Defaults

- Track B uses the existing Reply runtime fixture as the primary external app
  for the strict lifecycle smoke. Jetsnack and the bundled FixThis sample remain
  optional matrix cases after the primary path passes.
- Copy Prompt parity is an assertion within the primary lifecycle smoke: copied
  prompt text must carry the same session/item protocol tokens, while the actual
  claim/resolve lifecycle runs through MCP.
- Release reality checks extend the existing `evidence:release` profile first.
  A new named sub-profile is allowed only if the existing profile becomes too
  broad to report clearly.
