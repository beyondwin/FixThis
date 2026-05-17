# v0.4 Maintainability Contract-First Refactor Design

Date: 2026-05-17
Status: Approved for implementation planning
Scope: browser feedback console, `:fixthis-mcp` session layer,
`:fixthis-compose-core` source matching, `:fixthis-cli` setup surfaces, and
test suite curation

## Summary

v0.4 is a quality milestone, not a feature-expansion release. The goal is to
make FixThis easier to change without weakening the user, agent, or release
contracts that make the tool trustworthy.

The priority order is:

1. prevent regressions;
2. reduce test maintenance cost;
3. shrink the blast radius of future feature work.

The chosen strategy is contract-first refactoring:

1. lock down the contracts that must survive refactoring;
2. curate the test suite so only meaningful contract coverage remains;
3. remove pre-v0.4 local artifact and recovery compatibility paths;
4. refactor console, MCP session, core source matching, and CLI setup code
   under that protection.

v0.4 deliberately does not support pre-v0.4 local recovery artifacts. Old
`.fixthis/` sessions, schema-v1 browser drafts, legacy screenshot artifact
fallbacks, old draft retry keys, and old console request bodies may be ignored
or rejected with a clear message instead of migrated forward. The current
canonical field names remain protected.

## Goals

- Preserve or strengthen behavior contracts for handoff Markdown, persisted
  MCP JSON, draft/session lifecycle, event-log replay, source matching
  ranking/risk output, CLI setup JSON, CLI exit codes, and remediation fields.
- Remove tests that only freeze implementation details and cannot explain a
  real user, agent, or release failure.
- Merge duplicate tests into focused scenario contracts where one scenario
  protects the same behavior more clearly.
- Demote expensive observation-style tests out of PR-time checks when they are
  valuable but not appropriate as local or per-PR gates.
- Reduce reliance on console global state projections and broad MCP session
  orchestration classes.
- Delete pre-v0.4 compatibility code that only exists to migrate old local
  artifacts, draft recovery data, artifact paths, request bodies, or retry
  identities.
- Preserve current canonical output contracts and field names even while old
  input compatibility is removed.

## Non-Goals

- No View/XML/WebView, Flutter, React Native, iOS, or production-runtime
  support.
- No visual redesign of FixThis Studio.
- No cloud sync, remote review service, or external AI API integration.
- No rename of persisted MCP JSON compatibility fields such as `items`,
  `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, or
  `sourceCandidates`.
- No migration support for pre-v0.4 `.fixthis/` sessions, schema-v1 draft
  recovery, old screenshot artifact locations, or old console request shapes.
- No required bridge protocol bump unless implementation discovers an
  unavoidable additive compatibility need.
- No broad rewrite that changes multiple subsystems before their contracts are
  covered.

## Design Principles

### Contract before structure

Refactors start only after the visible contract is identified. If a test fails,
the failure should say which user, agent, or release contract was violated.

### Less test volume, more signal

The target is not maximum test count. The target is enough high-signal tests to
catch real regressions while letting internal structure change.

### Pre-v0.4 compatibility is not carried forward

Code labeled `legacy` is not automatically bad, but v0.4 will not preserve old
local recovery behavior for its own sake. If a legacy path exists only to read
pre-v0.4 local artifacts or request shapes, remove it. If a path protects the
current canonical output contract, rename or isolate it instead of deleting it.

### Additive contracts only

v0.4 may add metadata, diagnostics, test helpers, or internal contract files.
It must not rename persisted fields or break existing install/setup command
surfaces.

## Protected Contract Map

These contracts are the v0.4 guardrails. Refactoring may change internal files,
helpers, and test layout, but must not weaken these outputs.

| Contract | Protected behavior |
| --- | --- |
| Handoff Markdown | Compact prompt grammar, item ids, screen grouping, source candidates, target confidence, warnings, and agent protocol footer stay stable unless changed additively. |
| Persisted MCP JSON | Current canonical field names, session ids, screen ids, item ids, target evidence, reliability, and source candidates stay stable. Pre-v0.4 fixture deserialization is not protected unless it matches the current canonical schema. |
| Draft lifecycle | Current schema-v2 browser draft, workspace recovery, partial save, idempotent retry, stale preview conflict, and pin-only residual behavior remain explicit. Schema-v1 pending draft migration is not protected. |
| Session lifecycle | Open/list/close/delete/claim/resolve/handoff behavior remains stable across HTTP routes and MCP tools. |
| Event log | Replay, checkpoint, compaction, sequence numbering, and crash recovery keep their existing durability guarantees. |
| Source matching | Public ranking, confidence, margin, matched reason tokens, risk flags, and caution output remain explainable and compatible. |
| CLI setup | `install-agent`, `init`, `doctor`, `setup`, JSON report shape, exit codes, remediation fields, dry-run safety, and atomic write behavior remain stable. |
| Release/install safety | Checksum verification, release package tests, detekt baseline ratchet, and observation gate commands remain enforceable. |

## Test Curation Policy

Every touched test is classified into one of four buckets.

### Keep

Keep tests that protect a real contract:

- user-visible workflow behavior;
- MCP tool input/output behavior;
- persisted JSON compatibility;
- handoff Markdown grammar;
- source matching rank/confidence/risk behavior;
- setup/install safety;
- release integrity;
- crash recovery or idempotency guarantees.

### Merge

Merge tests that exercise the same contract through nearly identical fixtures.
The merged test should use a scenario helper or fixture builder that makes the
contract clearer than the original repeated assertions.

### Delete

Delete tests that freeze implementation details without protecting a contract.
Examples:

- internal function call order where output is already covered;
- wrapper methods that only delegate;
- DOM wiring details already covered by an end-to-end console scenario;
- snapshot-like checks that fail on harmless structure changes;
- tests whose failure cannot be tied to a user, agent, or release contract in
  one sentence.

### Demote

Demote tests that are valuable but too expensive or environment-sensitive for
PR-time confidence. Browser stress, long smoke, performance observation,
connected Android, and lower-bound compatibility checks may run nightly,
manually, or through observation gates instead of blocking every local edit.

### Deletion gate

Any removed test must record one of these reasons in the v0.4 implementation
plan or PR notes:

- `Covered by`: names the higher-level contract/scenario test that protects
  the same behavior.
- `No contract`: explains why failure would not break a user, agent, or
  release contract.
- `Demoted to`: names the nightly/manual/perf/observation command that now owns
  the coverage.
- `Merged into`: names the new scenario or fixture that absorbed it.

## Legacy Retirement Policy

The current codebase contains several meanings of `legacy`. v0.4 removes the
ones tied to pre-v0.4 local compatibility and keeps only current output
contracts.

### Purge during v0.4

These paths are removal targets:

- browser draft migration from `localStorage["fixthis.pending.<sessionId>"]`
  into schema-v2 draft workspaces;
- fallback screenshot artifact reads from `.fixthis/artifacts`;
- semantic duplicate detection for persisted items that predate
  `clientWorkspaceId` and `clientDraftItemId`;
- deprecated `sendDraftToAgent(sessionId)` call sites that bypass compact
  handoff item selection;
- old `{prompt}` body compatibility and related route branches;
- compatibility tests whose only purpose is proving pre-v0.4 draft/session/
  artifact shapes still load;
- old local recovery docs that imply pre-v0.4 artifacts are supported.

### Retire after replacement

These paths are internal transition debt and should be removed after their
read/write sites move to the canonical surface:

- console FSM projections into broad `state.*` slots once read sites use
  explicit accessors or store selectors;
- bridge functions whose only purpose is to let new modules read or mutate old
  global state;
- tests that exist only to prove a legacy implementation detail still exists.

### Rename without wire breakage

`legacy fallback` in source matching describes an untyped fallback evidence
path, not necessarily old code. The internal model should use a clearer name
such as `UNTYPED_FALLBACK`, while preserving the existing wire label and
Markdown token unless a documented additive migration changes the public
output.

### User-facing behavior for old data

Old local artifacts do not need migration. When the console or CLI encounters a
pre-v0.4 artifact shape, the preferred behavior is to ignore it, delete it
through `fixthis clean`, or return a clear unsupported-version message. It
should not silently reinterpret old data through compatibility heuristics.

## Track 1 - Contract Map and Test Curation

### Scope

- Inventory large and high-churn test files.
- Map each test file to the contract it protects.
- Identify delete, merge, and demote candidates before major refactors.
- Introduce fixture builders only where they reduce repeated setup or make a
  contract easier to read.

### Candidate files

- `fixthis-mcp/src/test/kotlin/.../CompactHandoffRendererTest.kt`
- `fixthis-mcp/src/test/kotlin/.../FeedbackSessionServiceTest.kt`
- `fixthis-mcp/src/test/kotlin/.../McpProtocolTest.kt`
- `fixthis-mcp/src/test/kotlin/.../FeedbackSessionStoreTest.kt`
- `fixthis-mcp/src/test/kotlin/.../ConsoleAssetContractTest.kt`
- `scripts/console-browser-smoke.mjs`

### Acceptance

- A committed inventory lists the contract owner for each curated test file.
- Deleted tests have a deletion-gate reason.
- Merged tests have clearer scenario names than the deleted duplicates.
- PR-time commands are not expanded only to compensate for lower confidence.

## Track 2 - Console JS Refactor

### Scope

- Reduce `annotations.js`, `history.js`, `state.js`, and related modules'
  reliance on broad global state.
- Move read sites toward explicit store selectors, reducer state, or use-case
  APIs.
- Keep current Studio workflow and labels unless a small wording cleanup is
  needed to remove duplicated states.
- Retire removable legacy projections only after the read sites are migrated.

### Acceptance

- Draft, annotate, handoff, session switch, recovery, and stale-preview
  contracts remain covered.
- New code enters through reducer/use-case/helper boundaries, not fresh global
  mutation sites.
- `state.*` projection count decreases or each remaining projection has a
  documented compatibility reason.
- Console asset contract tests focus on public symbols and route contracts, not
  arbitrary implementation names.

## Track 3 - MCP Session Refactor

### Scope

- Split responsibilities currently concentrated around session store delegate,
  draft service, handoff lifecycle, and event-log replay.
- Keep current repository persistence and event-log formats compatible.
- Replace deprecated draft-to-agent call paths with item-id-aware handoff calls.
- Preserve current idempotent draft persistence while removing old semantic
  dedupe for items without client draft keys.

### Acceptance

- Session store, draft persistence, handoff lifecycle, and event replay can be
  tested independently.
- Current-schema `session.json` and event-log fixtures still deserialize and
  replay.
- Deprecated no-item handoff call sites are removed.
- Route and MCP tests verify behavior at the boundary rather than internal
  storage steps.

## Track 4 - Core Source Matching Stabilization

### Scope

- Break `SourceMatcher` policy into smaller units where doing so reduces
  coupling or clarifies confidence/risk decisions.
- Keep scoring explainable and output-oriented.
- Rename internal fallback concepts where the current `legacy` terminology is
  misleading, while preserving wire labels.

### Acceptance

- Tests protect rank order, confidence, margin, reason tokens, risk flags, and
  caution behavior, not every incidental intermediate score.
- Typed signal matching and untyped fallback behavior remain covered.
- Public source-index and handoff output compatibility is preserved.
- New policy units can be read without understanding the whole matcher.

## Track 5 - CLI Setup Surface Cleanup

### Scope

- Split setup/install/init/doctor responsibilities where the current command
  classes mix detection, rendering, writing, and recovery policy.
- Keep JSON report shape, exit codes, remediation fields, dry-run redaction,
  and atomic write behavior stable.
- Prefer contract assertions over full string snapshots.

### Acceptance

- CLI JSON tests assert schema and remediation semantics, not full prose when
  prose is not the contract.
- Setup config writers remain protected against accidental global writes.
- Doctor/install-agent recovery guidance continues to use the shared
  readiness vocabulary.
- Existing documented commands keep working.

## Rollout Order

1. Write the v0.4 test inventory and contract map.
2. Apply first-pass test delete, merge, and demote changes.
3. Retire the lowest-risk deprecated API call sites.
4. Refactor console read sites away from removable global projections.
5. Split MCP session responsibilities behind existing route and tool
   contracts.
6. Stabilize source matching policy names and boundaries.
7. Clean up CLI setup surfaces under JSON/exit-code contract tests.
8. Run final verification and update docs with removed tests, demoted tests,
   removed pre-v0.4 compatibility paths, and any remaining current-contract
   fallback paths.

## Verification Strategy

Use a layered verification set instead of running every expensive check for
every small edit.

Required during implementation:

- focused tests for the touched module;
- contract tests for handoff Markdown, persisted JSON, draft/session lifecycle,
  source matching, and CLI setup when those surfaces are touched;
- console JS unit/harness tests for touched browser modules;
- `npm run ci:local:fast` before broad integration checkpoints.

Required before declaring the milestone complete:

- `npm run ci:local`;
- `npm run console:test:all`;
- `npm run console:smoke` or a documented reason it was demoted for the final
  pass;
- `npm run first-run:smoke`;
- `npm run perf:test`;
- `npm run release:package:test`;
- `npm run release:npm:test`;
- `npm run checks:observation -- --json`.

## Rollback and Safety

- Make each track mergeable independently.
- Avoid public behavior changes in the same commit as large internal moves.
- Remove pre-v0.4 compatibility tests only after adding or identifying the
  current-contract test that owns the behavior going forward.
- When deleting tests, prefer deleting after the replacement contract is in
  place, not before.
- If a refactor requires changing output, classify it as additive, update the
  contract docs, and preserve current persisted field names.

## Done Criteria

v0.4 maintainability work is done when:

- curated tests are lower-noise and each remaining large test file has a clear
  reason to stay large or has been decomposed;
- each deleted test has a recorded delete, merge, covered-by, or demoted
  reason;
- removable console legacy projections and deprecated handoff APIs have been
  reduced;
- pre-v0.4 draft/session/artifact/request compatibility paths have been
  removed or replaced by clear unsupported-version behavior;
- any fallback path that remains is documented as protecting a current
  contract, not old local artifact migration;
- MCP session responsibilities are easier to test independently;
- source matching policy names and boundaries are clearer without reducing
  handoff quality;
- CLI setup tests protect contract shape and safety, not incidental wording;
- full milestone verification passes or any deferred observation check is
  explicitly documented with the command that owns it.
