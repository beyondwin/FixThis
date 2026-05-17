# v0.4 Follow-Up Risk Burn-Down Detailed Spec

Date: 2026-05-17
Status: Ready for implementation planning
Depends on:
[`2026-05-17-v04-maintainability-contract-first-refactor-design.md`](2026-05-17-v04-maintainability-contract-first-refactor-design.md)
and completed Phase 1 legacy purge

## Summary

Phase 1 removed the highest-risk pre-v0.4 local compatibility paths: schema-v1
browser pending mirrors, old screenshot artifact fallback, server-side semantic
retry dedupe without client draft keys, deprecated no-item handoff service calls,
and internal `SourceMatchReason.LEGACY_FALLBACK` naming.

The remaining v0.4 work is a risk burn-down, not a feature wave. It should make
the codebase easier to change while keeping current user, agent, and release
contracts stable. The follow-up tracks are:

1. complete the test-contract curation pass;
2. retire console global projections and remaining browser recovery heuristics;
3. split MCP session responsibilities under event-log and handoff contracts;
4. stabilize source-matching policy names and boundaries;
5. separate CLI setup/install/doctor planning from command rendering and writes;
6. update docs and verification gates so the release story matches the code.

The key policy stays unchanged: pre-v0.4 local artifact recovery is unsupported,
but current persisted MCP JSON and handoff output contracts remain protected.

## Goals

- Remove tests whose failure cannot be tied to a current user, agent, or release
  contract.
- Record a concrete delete, merge, covered-by, or demoted-to reason for every
  removed test.
- Reduce console reliance on module-global compatibility state and broad
  `state.*` projections.
- Remove remaining browser semantic dedupe for recovery items that lack
  `clientWorkspaceId` / `clientDraftItemId`.
- Split large MCP session code around pure mutation, event payload, handoff, and
  persistence/replay responsibilities.
- Rename misleading internal source-matching fallback concepts without changing
  current serialized output values or Markdown reason tokens unless the change is
  explicitly additive and documented.
- Keep CLI setup JSON shape, exit codes, dry-run redaction, global-write guard,
  and atomic write behavior stable while extracting side-effect-free planning.
- Make final v0.4 verification meaningful but not inflated by low-signal tests.

## Non-Goals

- No support for pre-v0.4 `.fixthis/` recovery data, schema-v1 localStorage
  drafts, old screenshot artifact roots, or old console request bodies.
- No rename of the protected persisted MCP JSON field names called out in
  `AGENTS.md`: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, and `sourceCandidates`.
- No release-runtime support; FixThis remains debug-only.
- No View/XML, Flutter, React Native, iOS, or cloud workflow expansion.
- No visual redesign of FixThis Studio.
- No broad rewrite that changes console, MCP, core, and CLI behavior in one
  commit.

## Current Baseline

### Completed Phase 1

- `scripts/v04LegacyPurgeContract-test.mjs` guards the removed pre-v0.4 local
  compatibility paths.
- `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md`
  records Phase 1 curation and verification.
- The repo has a clean working tree after Phase 1 and is ahead of `origin/main`
  with the v0.4 commits.

### Console

- The console already has canonical reducer/store/ports surfaces under
  `fixthis-mcp/src/main/console/domain`, `application`, and `adapters`.
- `state.js` still owns compatibility holders for draft runtime data and still
  receives projected FSM snapshots from `consoleApp.js`.
- `consoleFsmContract-test.mjs` currently accepts up to 10 module-level `let`
  declarations in `state.js`; its own comment marks a follow-up to tighten the
  target after draft runtime holders are moved.
- `historyPendingDedupe.js` still contains semantic dedupe behavior for recovery
  items without client draft keys. That is the main remaining browser-side
  conflict with the v0.4 local-artifact cutoff.

### MCP Session Layer

- `FeedbackSessionService.kt` is already a façade over smaller collaborators,
  but it still centralizes connection, capture, annotation, handoff, and evidence
  pass-throughs.
- `FeedbackSessionStoreDelegate.kt` remains a large lock-owning store that mixes
  in-memory state, persistence reads, event-log write-ahead, compaction error
  recording, handoff batch mutation, item status mutation, replay bootstrapping,
  and helper serialization.
- `SessionReplayEngine.kt` and `SessionMutationService.kt` already hold useful
  extracted logic and should be extended rather than bypassed.

### Core Source Matching

- `SourceMatchReason` now uses `UNTYPED_FALLBACK` internally while preserving the
  wire reason label `"legacy fallback"`.
- `SourceCandidateRisk.LEGACY_FALLBACK` and `SourceHintRisk.LEGACY_FALLBACK`
  remain. These names are not old local artifact support; they are current risk
  values emitted in persisted JSON and handoff data.
- The remaining risk is terminology drift: internal code and docs can imply the
  fallback is about old versions even though it means signal-less source-index
  matching.

### CLI Setup Surface

- `SetupCommand.kt` still mixes Clikt command parsing, package resolution, SDK
  and executable discovery, MCP config planning, dry-run rendering, atomic writes,
  `init`, `install-agent`, JSON report collection, and global-write policy.
- `SetupRunResults` uses thread-local mutable state to bridge nested command
  execution. It works today, but it makes result ownership harder to reason
  about when setup flows are refactored.
- Existing tests protect setup, init, install-agent, doctor, global guard,
  redaction, dry-run output, and release package behavior.

## Protected Contracts

| Surface | Must remain stable |
| --- | --- |
| Persisted MCP JSON | Current field names, default decoding for current-schema optional fields, session/item/screen identity, target evidence, target reliability, and source candidates. |
| Handoff Markdown | Compact item grammar, screen grouping, item ids, source candidate reason tokens, warnings, and agent protocol footer. |
| Console workflow | Start, device/session selection, annotate, save to MCP, copy prompt, send to agent, session switch, stale preview conflict, recovery from current schema-v2 draft workspaces. |
| Browser storage | Current schema-v2 draft workspace storage only. No schema-v1 pending mirror migration. |
| MCP session lifecycle | Open/list/close/delete/claim/resolve/read/handoff behavior across HTTP routes and MCP tools. |
| Event log | Write-ahead semantics, replay idempotency, checkpoint validation, compaction failure reporting, and current event payload shape. |
| Source matching | Rank order, confidence, margin, reason labels, risk flags, caution text, stale source metadata, and source path resolution behavior. |
| CLI setup | JSON report schema, exit codes, dry-run redaction budget, global config guard, atomic config writes, and documented commands. |
| Verification gates | Local fast checks stay focused; expensive smoke/perf/connected checks have explicit owner commands instead of being silently removed. |

## Remaining Risk Register

| ID | Risk | Severity | Why it remains | Required mitigation |
| --- | --- | --- | --- | --- |
| R1 | Console compatibility projections hide real data ownership. | High | `state.js` and `consoleApp.js` still bridge FSM/store state into broad slots for read-only callers. | Move read sites to selectors/use-cases, then tighten projection contract tests and reduce `state.js` module-level holders. |
| R2 | Browser recovery can still dedupe local items semantically without client keys. | High | `historyPendingDedupe.js` still has a no-key semantic dedupe path. | Require `workspaceId + draftItemId` for dedupe and drop keyless recovered items with an explicit current-schema message. |
| R3 | Large low-signal tests can block refactors without protecting contracts. | High | Several MCP and console tests exceed 1,000 lines and mix route contracts with implementation-shape assertions. | Extend the curation ledger before deleting tests; remove only with `Covered by`, `Merged into`, `No contract`, or `Demoted to`. |
| R4 | MCP session mutations are hard to reason about inside one large delegate. | High | Store state, lock scope, persistence, event log, and handoff batch mutation are coupled. | Extract pure mutation and payload builders first; only then reduce lock-owned orchestration. |
| R5 | Event-log replay regressions can be masked by broad store tests. | Medium | Replay behavior is split between delegate init and `SessionReplayEngine`; tests may pass through unrelated setup. | Keep focused replay tests while extracting boot/replay wiring behind explicit collaborators. |
| R6 | Source fallback terminology is misleading. | Medium | The current emitted risk value is `LEGACY_FALLBACK`, but the meaning is signal-less/untyped evidence. | Introduce internal `UNTYPED_FALLBACK` naming where safe; preserve serialized value unless a documented additive migration exists. |
| R7 | Renaming source risk enum values can break persisted JSON. | High | `SourceCandidateRisk` is serialized. Changing enum entry names without `@SerialName` or a custom serializer changes wire values. | Add serialization tests before any enum rename; keep `"LEGACY_FALLBACK"` as the serialized value for v0.4. |
| R8 | CLI setup refactor can break global-write guard or partial exit behavior. | High | `install-agent` uses target filtering and exit code `PARTIAL` for skipped global writes. | Extract planning under existing JSON and exit-code tests; keep command parsing thin and observable. |
| R9 | Verification can become expensive but still miss release contracts. | Medium | Smoke, perf, release package, and observation checks are valuable but not always suitable for every small edit. | Assign each expensive check to final, nightly, manual, or PR-time ownership in the ledger. |
| R10 | Docs can imply old local artifacts still work. | Medium | Some reference docs use `legacy` for current compatibility and old examples. | Split docs language into current compatibility, unsupported pre-v0.4 local data, and current serialized output labels. |

## Track 1 - Test Contract Curation

### Scope

Track 1 completes the curation work that Phase 1 started. It is allowed to
delete or demote tests, but only when the deletion reason is recorded and the
remaining suite still protects the public contract.

### Candidate files

- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- `scripts/console-browser-smoke.mjs`
- `scripts/historyPendingDedupe-test.mjs`

### Requirements

- Create a follow-up curation ledger before deleting tests.
- Prefer scenario tests that describe the contract in their name.
- Delete tests that only prove helper names, line ordering, or obsolete
  implementation layout.
- Merge duplicate setup-heavy tests when one scenario protects the same route,
  JSON, Markdown, or lifecycle contract.
- Demote browser smoke/perf/connected tests only when a command owns the demoted
  coverage.

### Acceptance

- Every removed test appears in the ledger with one deletion-gate reason.
- `npm run ci:local:fast` still passes after each curation slice.
- Focused module tests explain what contract they protect.
- No test is kept solely because it existed before v0.4.

## Track 2 - Console Projection Retirement

### Scope

Track 2 moves console read sites away from broad `state.*` projections and
removes remaining browser recovery heuristics that conflict with the v0.4 local
artifact cutoff.

### Requirements

- Remove semantic recovery dedupe for items without client draft keys from
  `historyPendingDedupe.js`.
- Current schema-v2 recovery may keep items only when `workspaceId` and
  `draftItemId` identify a draft that is not already persisted by the server.
- Move draft runtime holders out of `state.js` module-level `let` declarations
  into a small runtime object or the canonical store.
- Move read sites for `state.connection`, `state.previewFsm`, and
  `state.pollingFsm` toward use-case getters or selectors.
- Tighten `consoleFsmContract-test.mjs` after the state holder move.
- Keep UI labels, button behavior, and current workflows unchanged.

### Acceptance

- `scripts/historyPendingDedupe-test.mjs` no longer has tests for keyless
  semantic recovery dedupe.
- `state.js` module-level holder count decreases and the contract target is
  tightened in the same slice that achieves it.
- Console canonical runtime tests still prove runtime modules do not mutate old
  draft globals.
- `node scripts/run-console-tests.mjs canonical pending draft session` passes.
- `node scripts/build-console-assets.mjs --check` passes.

## Track 3 - MCP Session Responsibility Split

### Scope

Track 3 reduces risk in `FeedbackSessionStoreDelegate.kt` and adjacent session
services without changing route or MCP tool contracts.

### Requirements

- Extract pure handoff candidate and item-update logic before moving lock-owned
  state.
- Extract event payload construction into a small helper so mutation behavior
  and serialization shape can be tested independently.
- Keep event-log write-ahead append inside the lock until a later design proves
  another ordering is safe.
- Keep replay behavior in `SessionReplayEngine`; move only bootstrap/wiring if
  it makes the delegate smaller without changing persistence reads.
- Keep façade method names used by routes and MCP tools unless all callers move
  in the same small slice.
- Preserve current-schema `session.json` and event-log replay behavior.

### Acceptance

- Store delegate size and responsibility count decrease without changing public
  store method signatures.
- Focused tests cover handoff candidate selection, handoff batch item updates,
  event payload shape, replay checkpoint skip behavior, and current route/tool
  output.
- `:fixthis-mcp:test` focused filters for store, service, event log, route, and
  protocol tests pass before moving to the next track.

## Track 4 - Source Matching Stabilization

### Scope

Track 4 makes source-matching terminology and policy boundaries clearer while
preserving current output.

### Requirements

- Keep `SourceMatchReason.UNTYPED_FALLBACK("legacy fallback")` wire label for
  v0.4.
- If `SourceCandidateRisk.LEGACY_FALLBACK` is renamed internally, preserve the
  serialized JSON value `"LEGACY_FALLBACK"` with a serialization test before the
  rename.
- If `SourceHintRisk.LEGACY_FALLBACK` is renamed, keep mapper tests proving the
  public `SourceCandidateRisk` round trip stays compatible.
- Extract only policy units that reduce coupling: origin tracking, risk
  classification, caution text, or candidate construction.
- Rename internal profile helpers such as `hasLegacyFallback` and
  `isLegacyFallbackOnly` to untyped-fallback terminology when the risk enum is
  renamed.
- Do not change source ranking, confidence, margin, reason labels, or caution
  text in the same commit as a structural extraction.

### Acceptance

- Source matcher tests continue to protect rank order, confidence ceilings,
  reason labels, risk flag ordering, and caution text.
- Serialization tests prove old current-contract JSON with
  `"LEGACY_FALLBACK"` still decodes.
- Handoff renderer and Markdown formatter still map `"legacy fallback"` to the
  compact `legacy` reason token.

## Track 5 - CLI Setup Surface Cleanup

### Scope

Track 5 extracts side-effect-free setup planning while preserving CLI behavior.

### Requirements

- Keep Clikt command classes responsible for parsing and user-facing output.
- Extract setup planning, writer selection, MCP entry creation, and dry-run
  rendering into testable collaborators.
- Keep atomic write behavior and rollback semantics unchanged.
- Keep `install-agent --json` report fields, `PARTIAL` exit behavior, skipped
  target readiness payloads, and global-write guard behavior unchanged.
- Do not broaden install paths or agent targets in v0.4.

### Acceptance

- Existing CLI tests pass with no golden-output churn unless the prose is not a
  protected contract.
- New planner tests can exercise setup planning without invoking nested Clikt
  commands.
- `scripts/check-docs-cli-surface.sh`, release package tests, and focused
  `:fixthis-cli:test` filters pass.

## Track 6 - Verification and Documentation Closure

### Requirements

- Update reference docs to distinguish:
  - unsupported pre-v0.4 local artifact recovery;
  - current persisted JSON compatibility;
  - current serialized source risk labels kept for agent compatibility.
- Update the curation ledger with final deleted, merged, and demoted test
  entries.
- Update `CHANGELOG.md` with the final v0.4 maintainability behavior changes.
- Run final milestone verification or document an explicit owner command for
  any demoted observation check.

### Final verification target

- `npm run ci:local`
- `npm run console:test:all`
- `npm run console:smoke` or a ledger entry naming the demoted owner command
- `npm run first-run:smoke`
- `npm run perf:test`
- `npm run release:package:test`
- `npm run release:npm:test`
- `npm run checks:observation -- --json`

## Rollout Order

1. Extend the test curation ledger and remove only clearly meaningless tests.
2. Remove keyless semantic recovery dedupe from browser history recovery.
3. Move console draft runtime holders out of `state.js` module-level state.
4. Reduce remaining console FSM projections where read sites can use selectors.
5. Extract MCP handoff and event-payload pure logic.
6. Extract source-matching policy units and, if safe, rename internal fallback
   enum names with serialization guards.
7. Extract CLI setup planning and report collection.
8. Update docs, changelog, and final verification evidence.

## Done Criteria

This follow-up program is complete when:

- Phase 1 legacy purge remains guarded by `scripts/v04LegacyPurgeContract-test.mjs`;
- keyless browser semantic recovery dedupe is removed;
- each deleted test has a ledger reason;
- console global holders and projections have been reduced with contract tests;
- MCP session code has smaller pure units for handoff and event payloads;
- source matching terminology is clear internally and stable externally;
- CLI setup planning can be tested without nested command execution;
- docs no longer imply pre-v0.4 local recovery support;
- final verification passes or every demoted check has an explicit owner command.
