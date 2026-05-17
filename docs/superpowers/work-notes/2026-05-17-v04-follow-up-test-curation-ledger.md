# v0.4 Follow-Up Test Curation Ledger

Date: 2026-05-17
Scope: v0.4 follow-up risk burn-down after Phase 1 legacy purge

## Rule

A test stays only when its failure describes a current user, agent, or release
contract. A test may be deleted only when this ledger records one reason:

- `Covered by`: a higher-level current-contract test protects the behavior.
- `Merged into`: a clearer scenario test absorbs the duplicated coverage.
- `No contract`: the behavior is unsupported or purely internal implementation
  shape.
- `Demoted to`: an explicit manual, nightly, perf, smoke, or observation command
  owns the coverage.

## Resolved Decisions

| File | Current contract | Final action | Reason |
| --- | --- | --- | --- |
| `scripts/historyPendingDedupe-test.mjs` | Current schema-v2 recovery dedupe | Trimmed | Keyless semantic dedupe coverage was removed; client draft key coverage stays. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | Handoff Markdown grammar | Kept | Grammar scenarios still own compact handoff formatting; no duplicate deletion was needed in this follow-up. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt` | Session facade behavior | Kept with focused helper coverage | Route-visible behavior remains here; pure handoff mutation behavior is covered by `FeedbackSessionHandoffMutationTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | Store JSON/session lifecycle | Kept with focused helper coverage | Serialization and lifecycle contracts remain here; event payload shape is covered by `SessionEventPayloadsTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | MCP tool contracts | Kept | Boundary contract stays; no duplicated setup was removed in this follow-up. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` | Console asset/public symbol contract | Kept | Public symbols and bundle contracts still protect the console resource boundary. |
| `scripts/console-browser-smoke.mjs` | Browser end-to-end smoke | Demoted to final milestone owner | Not run for every small refactor; final verification owner is `npm run console:smoke`. |

## Completed Curation

| Removed or moved test | Reason | Replacement or owner |
| --- | --- | --- |
| `history pending items skip legacy recovery that semantically matches a server item` | `No contract` | Pre-v0.4/keyless semantic recovery is unsupported; current draft-key dedupe remains covered by `history pending items skip server items with the same draft identity`. |
| `history pending items drop legacy pin-only item with exact empty comment` | `Merged into` | Empty-comment recovery dropping remains covered by `dropEmptyHistoryPendingItems` through current key-based recovery tests. |
| Per-task browser end-to-end smoke gate | `Demoted to` | Final milestone owner command: `npm run console:smoke`; focused console unit/contract tests own ordinary refactor feedback. |
