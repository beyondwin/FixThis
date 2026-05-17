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

## Pending Decisions

| File | Current contract | Planned action | Reason |
| --- | --- | --- | --- |
| `scripts/historyPendingDedupe-test.mjs` | Current schema-v2 recovery dedupe | Trim | Remove keyless semantic dedupe coverage; keep client draft key coverage. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | Handoff Markdown grammar | Merge selected duplicates | Keep grammar scenarios; merge repetitive source-candidate/warning shape assertions. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt` | Session facade behavior | Trim during Track 3 | Keep route-visible behavior; move pure handoff assertions to `FeedbackSessionHandoffMutationTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | Store JSON/session lifecycle | Trim during Track 3 | Keep serialization and lifecycle contracts; move event payload assertions to `SessionEventPayloadsTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | MCP tool contracts | Keep | Boundary contract; reduce only duplicated setup after Track 3. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` | Console asset/public symbol contract | Trim during Track 2 | Keep public symbols and bundle contract; delete arbitrary helper-name checks once selectors own behavior. |
| `scripts/console-browser-smoke.mjs` | Browser end-to-end smoke | Keep for final milestone | Do not run for every small edit; final verification owns it. |

## Completed Curation

| Removed or moved test | Reason | Replacement or owner |
| --- | --- | --- |
| `history pending items skip legacy recovery that semantically matches a server item` | `No contract` | Pre-v0.4/keyless semantic recovery is unsupported; current draft-key dedupe remains covered by `history pending items skip server items with the same draft identity`. |
| `history pending items drop legacy pin-only item with exact empty comment` | `Merged into` | Empty-comment recovery dropping remains covered by `dropEmptyHistoryPendingItems` through current key-based recovery tests. |
