# v0.4 Test Contract Inventory

Date: 2026-05-17
Scope: v0.4 maintainability phase 1 legacy purge

## Curation Rule

A test stays only when its failure can be tied to a current user, agent, or
release contract. Tests that only prove pre-v0.4 local artifact compatibility
are removed in v0.4.

## Inventory

| File | Current contract | Action | Reason |
| --- | --- | --- | --- |
| `scripts/draftStorageAdapter-test.mjs` | Schema-v2 workspace storage | Keep and trim | Keep workspace keying, malformed payload cleanup, empty-entry filtering; remove schema-v1 migration coverage. |
| `scripts/pendingItemRecovery-test.mjs` | Schema-v1 pending mirror recovery | Delete or reduce | The covered behavior is pre-v0.4 recovery. Current recovery is covered by `scripts/draftWorkspace-test.mjs`, `scripts/draftUseCases-test.mjs`, and `scripts/pendingRecoveryVariant-test.mjs`. |
| `scripts/pendingBoundaryGuard-test.mjs` | Boundary cleanup around pending recovery | Trim | Keep current workspace/session boundary assertions; remove pending mirror cleanup assertions. |
| `scripts/sessionScopedRequests-test.mjs` | Session-scoped request paths | Trim | Keep session-id request assertions; remove direct loading of `pendingPersistence.js`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt` | Preview and screenshot route safety | Keep and update | Preserve route safety while dropping `.fixthis/artifacts` fallback. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | Session store lifecycle, replay, handoff | Keep and trim | Remove pre-v0.4 fixture compatibility and update handoff setup calls. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt` | Draft service behavior | Keep and update | Replace no-item handoff call with current item-id-aware setup. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | MCP protocol and tool behavior | Keep and update | Replace no-item service handoff setup with current item-id-aware calls. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` | Source ranking/confidence/risk output | Keep | Rename internal fallback constant without changing wire label assertions. |

## Removed Coverage

| Removed behavior | Deletion gate |
| --- | --- |
| `localStorage["fixthis.pending.<sessionId>"]` schema-v1 migration | `No contract`: pre-v0.4 local recovery is unsupported. Current schema-v2 recovery remains covered. |
| `.fixthis/artifacts` screenshot fallback | `No contract`: pre-v0.4 screenshot artifact paths are unsupported. Current persisted session roots remain covered. |
| semantic duplicate detection for items without client draft keys | `No contract`: retry idempotency now requires current client draft keys. |
| deprecated no-item `sendDraftToAgent(sessionId)` service overload | `Covered by`: item-id-aware handoff route and service tests. |
| old `{prompt}` body compatibility | `No contract`: current `/api/agent-handoffs` requires `itemIds`. |

## Phase 1 Deferred Track 1 Candidates

These files appear in the spec's Track 1 candidate list but are deferred to a
later v0.4 plan because phase 1 only covers legacy purge. Listing them here
keeps the contract-map work traceable.

| File | Deferred to | Reason |
| --- | --- | --- |
| `fixthis-mcp/src/test/kotlin/.../CompactHandoffRendererTest.kt` | Track 1 follow-up plan | Handoff Markdown contract curation; not touched by legacy purge. |
| `fixthis-mcp/src/test/kotlin/.../FeedbackSessionServiceTest.kt` | Track 3 plan | Touched only incidentally by no-item handoff removal; full curation belongs with the MCP session split. |
| `fixthis-mcp/src/test/kotlin/.../McpProtocolTest.kt` | Track 1 / Track 3 | Phase 1 updates only the deprecated handoff setup calls. |
| `fixthis-mcp/src/test/kotlin/.../FeedbackSessionStoreTest.kt` | Track 1 / Track 3 | Phase 1 trims the pre-v0.4 fixture cases only. |
| `fixthis-mcp/src/test/kotlin/.../ConsoleAssetContractTest.kt` | Track 2 plan | Console asset contract curation belongs with the console refactor. |
| `scripts/console-browser-smoke.mjs` | Track 1 demotion review | Smoke harness demotion is part of the verification-strategy work, not the legacy purge. |

## Phase 1 Completion

| Check | Result |
| --- | --- |
| `node --test scripts/v04LegacyPurgeContract-test.mjs` | PASS |
| `node scripts/run-console-tests.mjs canonical pending draft session` | PASS |
| `node scripts/build-console-assets.mjs --check` | PASS |
| `./gradlew :fixthis-mcp:test --tests '*ConsolePreviewRoutesTest' --tests '*ConsoleFeedbackDraftDeduplicationRoutesTest' --tests '*FeedbackDraftServiceTest' --tests '*McpProtocolTest' --no-daemon` | PASS |
| `./gradlew :fixthis-compose-core:test --tests '*SourceMatcherTest' --tests '*EvidenceProfileTest' --no-daemon` | PASS |
| `npm run ci:local:fast` | PASS |

Phase 1 removed pre-v0.4 pending mirrors, old screenshot artifact fallback,
semantic retry dedupe without client draft keys, the no-item handoff service
overload, and internal source matcher legacy terminology. Current canonical
contracts remain guarded by focused tests.
