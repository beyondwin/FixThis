# v0.4 Follow-Up Risk Burn-Down Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the post-Phase-1 v0.4 maintainability tracks by removing remaining current-risk legacy heuristics, curating low-signal tests, and splitting console, MCP, core source matching, and CLI setup responsibilities under stable contracts.

**Architecture:** Execute this as independent, reviewable tracks after the completed Phase 1 legacy purge. Each track starts with a focused contract test or ledger entry, makes one structural change, runs only the meaningful focused tests for the touched surface, and commits before the next track. Current persisted MCP JSON and handoff output remain stable even when internal names change.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, Gradle, Node 20 `node:test`, vanilla browser JavaScript bundled by `scripts/build-console-assets.mjs`, existing FixThis console/MCP/CLI test harnesses.

Spec: [`../specs/2026-05-17-v04-follow-up-risk-burn-down-detailed-spec.md`](../specs/2026-05-17-v04-follow-up-risk-burn-down-detailed-spec.md)

---

## Scope Check

This is a master implementation plan for five follow-up tracks. The tracks are
independent enough to execute as separate commits or separate workers, but they
share the same v0.4 contract policy:

- pre-v0.4 local artifact recovery stays unsupported;
- current persisted JSON and handoff output stay compatible;
- meaningless tests are removed only with a recorded reason;
- expensive observation checks are demoted only with an owner command.

Do not batch console, MCP, source, and CLI code changes in one commit. The
recommended execution order is exactly the task order below.

## File Structure

### New files created by this plan

| Path | Responsibility |
| --- | --- |
| `docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md` | Records every deleted, merged, and demoted test for the remaining v0.4 tracks. |
| `fixthis-mcp/src/main/console/draftRuntimeState.js` | Owns draft runtime compatibility holders currently declared as module-level state in `state.js`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutationTest.kt` | Focused tests for handoff candidate selection and item state transitions. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutation.kt` | Pure handoff mutation helper extracted from `FeedbackSessionStoreDelegate.kt`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloadsTest.kt` | Focused tests for event-log payload JSON keys and serialized values. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloads.kt` | Event-log payload builders extracted from store delegate mutation methods. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceFallbackRiskSerializationTest.kt` | Ensures internal fallback-risk renaming keeps the serialized current-contract value. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt` | Source risk confidence-cap classification extracted from `SourceMatcher.kt`. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlannerTest.kt` | Planner tests for setup writer selection, MCP entry creation, dry-run metadata, and global guard inputs. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlanner.kt` | Side-effect-free setup planning extracted from `SetupCommand.kt`. |

### Existing files modified by this plan

| Path | Change |
| --- | --- |
| `scripts/historyPendingDedupe-test.mjs` | Remove keyless semantic dedupe tests; add current-schema key requirement coverage. |
| `fixthis-mcp/src/main/console/historyPendingDedupe.js` | Drop keyless recovered items instead of semantic-deduping them. |
| `scripts/v04LegacyPurgeContract-test.mjs` | Extend guard so keyless browser semantic recovery dedupe stays removed. |
| `scripts/consoleFsmContract-test.mjs` | Tighten `state.js` module-level holder budget after `draftRuntimeState.js` extraction. |
| `fixthis-mcp/src/main/console/state.js` | Delegate draft runtime holder access to `draftRuntimeState.js`; reduce module-level state. |
| `fixthis-mcp/src/main/console/consoleApp.js` | Keep only documented projections that still have read sites. |
| `fixthis-mcp/src/main/console/history.js`, `connection.js`, `sessions-polling.js`, `rendering.js`, `main.js` | Migrate remaining read sites from `state.*` projections to use-case getters or selectors when touched. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` | Replace private handoff and event payload helper logic with extracted collaborators. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt` | Rename fallback risk internally while preserving serialized value. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt` | Rename domain fallback risk internally. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt` | Update source candidate/source hint risk mappings. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt` | Use renamed fallback risk. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt` | Rename fallback profile helpers to untyped-fallback terminology. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt` | Use renamed fallback risk while keeping caution text stable. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` | Delegate risk/caution classification to `SourceRiskClassifier.kt`. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` | Update enum references while keeping wire reason assertions. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedenceTest.kt` | Update enum references and ordering assertions. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt` | Keep current JSON compatibility assertions. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` | Delegate planning to `SetupPlanner.kt` while preserving command behavior. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt` | Keep behavior-level command tests; move planner-only assertions to `SetupPlannerTest.kt`. |
| `docs/reference/feedback-console-contract.md`, `docs/reference/output-schema.md`, `docs/reference/source-matching.md`, `docs/reference/cli.md`, `CHANGELOG.md` | Document final v0.4 behavior, remaining serialized labels, and removed/demoted tests. |

## Execution Rules

- Keep each task as a separate commit.
- Before deleting a test, add or update the curation ledger entry that explains
  why the coverage is safe to delete.
- For each code task, write the failing guard first unless the task is a pure
  extraction with existing focused tests.
- Run the focused tests listed in the task. Do not expand the test set only to
  look thorough.
- Run `npm run ci:local:fast` at integration checkpoints: after Task 4, after
  Task 7, and before final docs.

## Tasks

### Task 1: Create the follow-up test curation ledger

**Files:**
- Create: `docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md`

- [ ] **Step 1: Write the ledger**

Create `docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md`:

```markdown
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
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt` | Session façade behavior | Trim during Track 3 | Keep route-visible behavior; move pure handoff assertions to `FeedbackSessionHandoffMutationTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | Store JSON/session lifecycle | Trim during Track 3 | Keep serialization and lifecycle contracts; move event payload assertions to `SessionEventPayloadsTest`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | MCP tool contracts | Keep | Boundary contract; reduce only duplicated setup after Track 3. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` | Console asset/public symbol contract | Trim during Track 2 | Keep public symbols and bundle contract; delete arbitrary helper-name checks once selectors own behavior. |
| `scripts/console-browser-smoke.mjs` | Browser end-to-end smoke | Keep for final milestone | Do not run for every small edit; final verification owns it. |

## Completed Curation

| Removed or moved test | Reason | Replacement or owner |
| --- | --- | --- |
```

- [ ] **Step 2: Verify the ledger has no placeholder markers**

Run:

```bash
rg -n "TB[D]|TO[D]O|PLACEHOLDE[R]|FILL_ME_I[N]|UNDECIDE[D]" docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md
```

Expected: no output.

- [ ] **Step 3: Commit the ledger**

Run:

```bash
git add docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md
git commit -m "docs: ledger v0.4 follow-up test curation"
```

Expected: commit succeeds.

### Task 2: Remove keyless browser semantic recovery dedupe

**Files:**
- Modify: `scripts/historyPendingDedupe-test.mjs`
- Modify: `fixthis-mcp/src/main/console/historyPendingDedupe.js`
- Modify: `scripts/v04LegacyPurgeContract-test.mjs`
- Modify: `docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md`

- [ ] **Step 1: Replace keyless semantic dedupe tests with current-schema coverage**

In `scripts/historyPendingDedupe-test.mjs`, delete these tests:

```js
test('history pending items skip legacy recovery that semantically matches a server item', () => {
```

```js
test('history pending items drop legacy pin-only item with exact empty comment', () => {
```

Add this test after `history pending items keep new draft with same semantic target when client key differs`:

```js
test('history pending items drop keyless recovered items because v0.4 requires draft identity', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        clientWorkspaceId: 'ws-a',
        clientDraftItemId: 'draft-1',
        target: { type: 'visual_area', boundsInWindow: bounds },
        comment: 'saved',
      }],
    },
  });

  const keylessRecoveredItem = { targetType: 'area', bounds, comment: 'unsaved local note' };
  const pending = m.dedupePendingHistoryItemsForSession(
    { sessionId: 'session-a' },
    [keylessRecoveredItem],
    'ws-a',
  );

  assert.deepEqual(pending, []);
});
```

- [ ] **Step 2: Run the test to confirm it fails before implementation**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
```

Expected: FAIL because keyless non-empty recovered items are still retained or semantic-deduped.

- [ ] **Step 3: Remove semantic dedupe from history recovery**

Edit `fixthis-mcp/src/main/console/historyPendingDedupe.js`:

- delete `historyItemSemanticKey`;
- delete `hasLegacySemanticDedupeKey`;
- delete the `persistedSemanticKeys` set;
- make `dedupePendingHistoryItemsForSession` keep only recovered items that have a current client draft key and do not already exist server-side.

The final `dedupePendingHistoryItemsForSession` body should have this shape:

```js
function dedupePendingHistoryItemsForSession(session, pendingItems, workspaceId) {
  const filteredPendingItems = dropEmptyHistoryPendingItems(pendingItems);
  const persisted = persistedItemsForHistoryDedupe(session);
  const persistedClientKeys = new Set(persisted.map(persistedClientDraftKey).filter(Boolean));
  return filteredPendingItems.filter((item) => {
    const clientKey = pendingClientDraftKey(item, workspaceId);
    if (!clientKey) return false;
    return !persistedClientKeys.has(clientKey);
  });
}
```

- [ ] **Step 4: Extend the v0.4 legacy purge guard**

In `scripts/v04LegacyPurgeContract-test.mjs`, add this test before the source matching test:

```js
test('browser history recovery does not semantic-dedupe keyless local items', () => {
  assertNoPattern(
    'fixthis-mcp/src/main/console/historyPendingDedupe.js',
    /historyItemSemanticKey|hasLegacySemanticDedupeKey|persistedSemanticKeys/,
    'v0.4 recovery requires client draft keys instead of keyless semantic matching',
  );
});
```

- [ ] **Step 5: Record the deleted tests in the ledger**

Append rows under `Completed Curation`:

```markdown
| `history pending items skip legacy recovery that semantically matches a server item` | `No contract` | Pre-v0.4/keyless semantic recovery is unsupported; current draft-key dedupe remains covered by `history pending items skip server items with the same draft identity`. |
| `history pending items drop legacy pin-only item with exact empty comment` | `Merged into` | Empty-comment recovery dropping remains covered by `dropEmptyHistoryPendingItems` through current key-based recovery tests. |
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs scripts/v04LegacyPurgeContract-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add scripts/historyPendingDedupe-test.mjs fixthis-mcp/src/main/console/historyPendingDedupe.js scripts/v04LegacyPurgeContract-test.mjs docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md
git commit -m "refactor(console): require draft keys for history recovery dedupe"
```

Expected: commit succeeds.

### Task 3: Move draft runtime holders out of `state.js`

**Files:**
- Create: `fixthis-mcp/src/main/console/draftRuntimeState.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `scripts/consoleFsmContract-test.mjs`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/console-tests.json` only if the new test file requires a new group entry

- [ ] **Step 1: Add a failing holder-budget assertion**

In `scripts/consoleFsmContract-test.mjs`, tighten the empty-pending target from `10` to `5`:

```js
const target = PENDING_EXTRACTION.size === 0 ? 5 : 40;
```

Run:

```bash
node --test scripts/consoleFsmContract-test.mjs
```

Expected: FAIL if `state.js` still has more than 5 module-level `let` declarations.

- [ ] **Step 2: Create the draft runtime holder module**

Create `fixthis-mcp/src/main/console/draftRuntimeState.js`:

```js
// @requires draftWorkspace.js, undoRedo.js
// draftRuntimeState.js - owns draft compatibility holders while callers migrate
// from state.js helpers to canonical store/selectors.

function createDraftRuntimeState({ persistCurrentDraftWorkspaceIfNeeded } = {}) {
  let draftFlowState = null;
  let draftPinsState = [];
  let draftFocusIndexState = null;
  let draftSelectionState = null;
  let undoRedoHistory = createHistory();
  let draftWorkspace = createEmptyDraftWorkspace();
  let draftCommandQueue = null;

  function currentDraftWorkspace() {
    return draftWorkspace;
  }

  function draftFlow() {
    return draftFlowState;
  }

  function draftItemList() {
    return draftPinsState;
  }

  function draftFocusIndex() {
    return draftFocusIndexState;
  }

  function setDraftFocusIndex(index) {
    draftFocusIndexState = index;
  }

  function draftSelection() {
    return draftSelectionState;
  }

  function setDraftSelection(selection) {
    draftSelectionState = selection;
  }

  function currentUndoRedoHistory() {
    return undoRedoHistory;
  }

  function replaceUndoRedoHistory(nextHistory) {
    undoRedoHistory = nextHistory || createHistory();
  }

  function currentDraftCommandQueue() {
    return draftCommandQueue;
  }

  function replaceDraftCommandQueue(nextQueue) {
    draftCommandQueue = nextQueue || null;
  }

  function replaceDraftWorkspace(nextWorkspace) {
    draftWorkspace = nextWorkspace || createEmptyDraftWorkspace();
    syncDraftWorkspaceCompatibility();
    if (typeof persistCurrentDraftWorkspaceIfNeeded === 'function') {
      persistCurrentDraftWorkspaceIfNeeded();
    }
  }

  function syncDraftWorkspaceCompatibility() {
    if (draftWorkspace.lifecycle === DraftLifecycle.EMPTY) {
      draftFlowState = null;
      draftPinsState = [];
      draftFocusIndexState = null;
      draftSelectionState = null;
      undoRedoHistory = createHistory();
      return;
    }
    draftFlowState = {
      context: draftWorkspace.context,
      previewId: draftWorkspace.context?.previewId || null,
      screen: draftWorkspace.screen,
      screenshotUrl: draftWorkspace.screenshotUrl,
      workspaceId: draftWorkspace.workspaceId,
    };
    draftPinsState = draftWorkspace.items || [];
    draftFocusIndexState = draftWorkspace.focusIndex;
    draftSelectionState = draftWorkspace.selection;
  }

  return {
    currentDraftWorkspace,
    draftFlow,
    draftItemList,
    draftFocusIndex,
    setDraftFocusIndex,
    draftSelection,
    setDraftSelection,
    currentUndoRedoHistory,
    replaceUndoRedoHistory,
    currentDraftCommandQueue,
    replaceDraftCommandQueue,
    replaceDraftWorkspace,
    syncDraftWorkspaceCompatibility,
  };
}
```

- [ ] **Step 3: Wire the module into `state.js`**

In `fixthis-mcp/src/main/console/state.js`:

- add `draftRuntimeState.js` to the `// @requires` list;
- replace module-level `let draftFlowState`, `draftPinsState`,
  `draftFocusIndexState`, `draftSelectionState`, `undoRedoHistory`,
  `draftWorkspace`, and `draftCommandQueue` declarations with:

```js
            const draftRuntime = createDraftRuntimeState({
              persistCurrentDraftWorkspaceIfNeeded: () => persistCurrentDraftWorkspaceIfNeeded(),
            });
```

- replace helper bodies so they delegate to `draftRuntime`, for example:

```js
            function currentDraftWorkspace() {
              return draftRuntime.currentDraftWorkspace();
            }

            function draftFlow() {
              return draftRuntime.draftFlow();
            }

            function replaceDraftWorkspace(nextWorkspace) {
              draftRuntime.replaceDraftWorkspace(nextWorkspace);
            }
```

- replace direct reads/writes of `undoRedoHistory` and `draftCommandQueue` in
  `state.js` with `draftRuntime.currentUndoRedoHistory()`,
  `draftRuntime.replaceUndoRedoHistory(nextHistory)`,
  `draftRuntime.currentDraftCommandQueue()`, and
  `draftRuntime.replaceDraftCommandQueue(nextQueue)`.

- [ ] **Step 4: Update runtime contract assertions**

In `scripts/consoleCanonicalRuntimeContract-test.mjs`, add
`fixthis-mcp/src/main/console/draftRuntimeState.js` to the list checked by
`runtime draft helpers use readable canonical names`.

Keep the existing assertion:

```js
assert.match(state, /function draftFlow\(\)/);
```

The compatibility helper names stay available while their holders move.

- [ ] **Step 5: Run focused console contract tests**

Run:

```bash
node --test scripts/consoleFsmContract-test.mjs scripts/consoleCanonicalRuntimeContract-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/main/console/draftRuntimeState.js fixthis-mcp/src/main/console/state.js scripts/consoleFsmContract-test.mjs scripts/consoleCanonicalRuntimeContract-test.mjs
git commit -m "refactor(console): move draft runtime holders out of state"
```

Expected: commit succeeds.

### Task 4: Reduce console FSM projections with selector read sites

**Files:**
- Modify: `fixthis-mcp/src/main/console/consoleApp.js`
- Modify as needed: `fixthis-mcp/src/main/console/connection.js`
- Modify as needed: `fixthis-mcp/src/main/console/sessions-polling.js`
- Modify as needed: `fixthis-mcp/src/main/console/rendering.js`
- Modify as needed: `fixthis-mcp/src/main/console/main.js`
- Modify: `scripts/consoleCanonicalWorkflow-test.mjs`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`

- [ ] **Step 1: Inventory projection read sites**

Run:

```bash
rg -n "state\\.(connection|previewFsm|pollingFsm)" fixthis-mcp/src/main/console scripts
```

Expected: output lists each remaining read site. Copy the list into the commit
message body or PR notes after the task is complete.

- [ ] **Step 2: Add a guard for no new projection reads in migrated modules**

In `scripts/consoleCanonicalRuntimeContract-test.mjs`, add:

```js
test('migrated console modules do not read FSM projection slots directly', () => {
  const migratedFiles = [
    'fixthis-mcp/src/main/console/connection.js',
    'fixthis-mcp/src/main/console/sessions-polling.js',
  ];
  for (const file of migratedFiles) {
    const text = source(file);
    assert.doesNotMatch(text, /state\.(connection|previewFsm|pollingFsm)/, file);
  }
});
```

- [ ] **Step 3: Migrate only the guarded read sites**

For each migrated file:

- replace `state.connection` reads with `connectionUseCases.getState()`;
- replace `state.previewFsm` reads with `previewUseCases.getState()`;
- replace `state.pollingFsm` reads with `pollingUseCases.getState()`;
- keep writes routed through existing use-case dispatch methods.

Do not remove the projection in `consoleApp.js` until the inventory command no
longer finds a production read site for that slot.

- [ ] **Step 4: Document remaining projections in `consoleApp.js`**

For any projection left in `consoleApp.js`, keep a one-line comment naming the
remaining read-site owner. The comment must be specific:

```js
// Remaining projection: rendering.js reads state.previewFsm until preview model
// construction moves to consoleSelectors.
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs scripts/consoleCanonicalWorkflow-test.mjs
node scripts/run-console-tests.mjs canonical session
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Run the integration checkpoint**

Run:

```bash
npm run ci:local:fast
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-mcp/src/main/console scripts/consoleCanonicalRuntimeContract-test.mjs scripts/consoleCanonicalWorkflow-test.mjs
git commit -m "refactor(console): reduce FSM projection read sites"
```

Expected: commit succeeds.

### Task 5: Extract pure MCP handoff mutation logic

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutation.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutationTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` only if enum/helper references move

- [ ] **Step 1: Write focused handoff mutation tests**

Create `FeedbackSessionHandoffMutationTest.kt` with tests for:

- draft items matching `targetItemIds` become `SENT`, `READY`, and receive batch metadata;
- already-sent ready items can be re-handed off and keep `sentAtEpochMillis`;
- non-target items stay unchanged;
- empty candidates return `null` so the delegate throws the existing
  `NO_DRAFT_FEEDBACK` error.

Use this complete file body as the starting point, then add the re-handoff case
beside the first test:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedbackSessionHandoffMutationTest {
    @Test
    fun `draft target item becomes sent and ready`() {
        val session = sessionWithItems(
            item("item-1", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
            item("item-2", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
        )

        val result = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = listOf("item-1"),
            markdownSnapshot = "handoff",
            now = 200L,
            batchId = "batch-1",
        )

        val updated = result!!.session
        assertEquals(listOf("item-1"), result.batch.itemIds)
        assertEquals(FeedbackDelivery.SENT, updated.items.single { it.itemId == "item-1" }.delivery)
        assertEquals(AnnotationStatusDto.READY, updated.items.single { it.itemId == "item-1" }.status)
        assertEquals(FeedbackDelivery.DRAFT, updated.items.single { it.itemId == "item-2" }.delivery)
    }

    @Test
    fun `no matching candidates returns null`() {
        val session = sessionWithItems(
            item("item-1", delivery = FeedbackDelivery.DRAFT, status = AnnotationStatusDto.OPEN),
        )

        val result = FeedbackSessionHandoffMutation.prepare(
            session = session,
            targetItemIds = listOf("missing"),
            markdownSnapshot = "handoff",
            now = 200L,
            batchId = "batch-1",
        )

        assertNull(result)
    }

    private fun sessionWithItems(vararg items: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "pkg",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(screen()),
        items = items.toList(),
    )

    private fun screen(): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        activityName = "MainActivity",
        displayName = "MainActivity",
    )

    private fun item(
        id: String,
        delivery: FeedbackDelivery,
        status: AnnotationStatusDto,
    ): AnnotationDto = AnnotationDto(
        itemId = id,
        screenId = "screen-1",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
        delivery = delivery,
        status = status,
        sentAtEpochMillis = if (delivery == FeedbackDelivery.SENT) 100L else null,
    )
}
```

- [ ] **Step 2: Run the new tests and confirm they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionHandoffMutationTest' --no-daemon
```

Expected: FAIL because `FeedbackSessionHandoffMutation` does not exist.

- [ ] **Step 3: Add the pure handoff helper**

Create `FeedbackSessionHandoffMutation.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

internal data class PreparedHandoffMutation(
    val session: SessionDto,
    val batch: FeedbackHandoffBatch,
)

internal object FeedbackSessionHandoffMutation {
    fun prepare(
        session: SessionDto,
        targetItemIds: List<String>?,
        markdownSnapshot: String?,
        now: Long,
        batchId: String,
    ): PreparedHandoffMutation? {
        val targetSet = targetItemIds?.toSet()
        val candidates = session.items.filter { item ->
            val matchesTarget = targetSet == null || item.itemId in targetSet
            matchesTarget &&
                (
                    item.delivery == FeedbackDelivery.DRAFT ||
                        (item.delivery == FeedbackDelivery.SENT && item.status == AnnotationStatusDto.READY)
                    )
        }
        if (candidates.isEmpty()) return null
        val batch = FeedbackHandoffBatch(
            batchId = batchId,
            sequenceNumber = session.handoffBatches.size + 1,
            createdAtEpochMillis = now,
            itemIds = candidates.map { it.itemId },
            markdownSnapshot = markdownSnapshot,
        )
        val updatedItems = session.items.map { item ->
            val matchesTarget = targetSet == null || item.itemId in targetSet
            when {
                item.delivery == FeedbackDelivery.DRAFT && matchesTarget -> item.copy(
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = batch.batchId,
                    sentAtEpochMillis = now,
                    lastHandedOffAtEpochMillis = now,
                    status = AnnotationStatusDto.READY,
                    updatedAtEpochMillis = now,
                )
                item.delivery == FeedbackDelivery.SENT &&
                    item.status == AnnotationStatusDto.READY &&
                    matchesTarget -> item.copy(
                        handoffBatchId = batch.batchId,
                        lastHandedOffAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                else -> item
            }
        }
        return PreparedHandoffMutation(
            session = session.copy(
                items = updatedItems,
                handoffBatches = session.handoffBatches + batch,
                status = SessionStatusDto.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            ),
            batch = batch,
        )
    }
}
```

- [ ] **Step 4: Replace private handoff logic in the delegate**

In `FeedbackSessionStoreDelegate.kt`, replace `sendDraftToAgent`,
`buildHandoffBatch`, and `buildUpdatedItemsForHandoff` usage with
`FeedbackSessionHandoffMutation.prepare(...)`.

The delegate must still throw the same error:

```kotlin
val prepared = FeedbackSessionHandoffMutation.prepare(
    session = session,
    targetItemIds = targetItemIds,
    markdownSnapshot = markdownSnapshot,
    now = now,
    batchId = idGenerator(),
) ?: throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionHandoffMutationTest' --tests '*FeedbackSessionStoreTest' --tests '*FeedbackSessionServiceTest' --tests '*McpProtocolTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutation.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionHandoffMutationTest.kt
git commit -m "refactor(mcp): extract handoff mutation policy"
```

Expected: commit succeeds.

### Task 6: Extract MCP event payload builders

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloads.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloadsTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`

- [ ] **Step 1: Write payload shape tests**

Create `SessionEventPayloadsTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionEventPayloadsTest {
    @Test
    fun `handoff payload keeps session batch and items keys`() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix",
        )
        val batch = FeedbackHandoffBatch(
            batchId = "batch-1",
            sequenceNumber = 1,
            createdAtEpochMillis = 3L,
            itemIds = listOf("item-1"),
            markdownSnapshot = "handoff",
        )

        val payload = SessionEventPayloads.handoff(
            sessionId = "session-1",
            batch = batch,
            updatedItems = listOf(item),
        )

        assertEquals("session-1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("batch-1", payload.getValue("batch").jsonObject.getValue("batchId").jsonPrimitive.content)
        assertEquals("item-1", payload.getValue("items").jsonArray.single().jsonObject.getValue("itemId").jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*SessionEventPayloadsTest' --no-daemon
```

Expected: FAIL because `SessionEventPayloads` does not exist.

- [ ] **Step 3: Add the payload builder**

Create `SessionEventPayloads.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val sessionEventPayloadJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object SessionEventPayloads {
    fun screen(sessionId: String, screen: SnapshotDto): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("screen", sessionEventPayloadJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
    }

    fun items(sessionId: String, items: List<AnnotationDto>): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put(
            "items",
            sessionEventPayloadJson.encodeToJsonElement(
                ListSerializer(AnnotationDto.serializer()),
                items,
            ),
        )
    }

    fun handoff(
        sessionId: String,
        batch: FeedbackHandoffBatch,
        updatedItems: List<AnnotationDto>,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("batch", sessionEventPayloadJson.encodeToJsonElement(FeedbackHandoffBatch.serializer(), batch))
        put(
            "items",
            sessionEventPayloadJson.encodeToJsonElement(
                ListSerializer(AnnotationDto.serializer()),
                updatedItems,
            ),
        )
    }
}
```

- [ ] **Step 4: Replace duplicate payload construction**

In `FeedbackSessionStoreDelegate.kt`:

- replace the `addScreen` payload with `SessionEventPayloads.screen(sessionId, captured)`;
- replace `markItemsHandedOff`, `updateItemStatus`, `claimFeedback`, and
  `clearDraftItems` item-list payloads with `SessionEventPayloads.items(...)`;
- replace `sendDraftToAgent` payload construction with
  `SessionEventPayloads.handoff(...)`;
- keep custom metadata merging in `addScreenWithItemsPayload` until it gets its
  own focused test.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*SessionEventPayloadsTest' --tests '*FeedbackSessionStoreEventLogTest' --tests '*FeedbackSessionStoreTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloads.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionEventPayloadsTest.kt
git commit -m "refactor(mcp): extract session event payload builders"
```

Expected: commit succeeds.

### Task 7: Stabilize source fallback risk naming with wire compatibility

**Files:**
- Create: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceFallbackRiskSerializationTest.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify tests under `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source`
- Modify: `scripts/v04LegacyPurgeContract-test.mjs`

- [ ] **Step 1: Add serialization guard before renaming**

Create `SourceFallbackRiskSerializationTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceFallbackRiskSerializationTest {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Test
    fun `untyped fallback risk keeps current wire value`() {
        val candidate = SourceCandidate(
            file = "Foo.kt",
            score = 0.25,
            confidence = SelectionConfidence.LOW,
            riskFlags = listOf(SourceCandidateRisk.UNTYPED_FALLBACK),
        )

        val encoded = json.encodeToString(SourceCandidate.serializer(), candidate)
        val decoded = json.decodeFromString(SourceCandidate.serializer(), encoded)

        assertTrue(encoded.contains("\"LEGACY_FALLBACK\""))
        assertEquals(listOf(SourceCandidateRisk.UNTYPED_FALLBACK), decoded.riskFlags)
    }

    @Test
    fun `current wire value decodes into untyped fallback risk`() {
        val decoded = json.decodeFromString(
            SourceCandidate.serializer(),
            """
            {
              "file": "Foo.kt",
              "score": 0.25,
              "confidence": "LOW",
              "riskFlags": ["LEGACY_FALLBACK"]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(SourceCandidateRisk.UNTYPED_FALLBACK), decoded.riskFlags)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests '*SourceFallbackRiskSerializationTest' --no-daemon
```

Expected: FAIL because `SourceCandidateRisk.UNTYPED_FALLBACK` does not exist.

- [ ] **Step 3: Rename the risk enum with a serialized alias**

Edit `SourceCandidateRisk.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceCandidateRisk {
    AMBIGUOUS,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,
    @SerialName("LEGACY_FALLBACK")
    UNTYPED_FALLBACK,
}
```

Edit `SourceHint.kt` so `SourceHintRisk.LEGACY_FALLBACK` becomes
`SourceHintRisk.UNTYPED_FALLBACK`.

- [ ] **Step 4: Update profile helpers, mappings, and policy references**

Replace references:

```bash
rg -n "LEGACY_FALLBACK" fixthis-compose-core/src/main/kotlin fixthis-compose-core/src/test/kotlin
```

Expected before edits: references in mappings, policy, precedence, matcher, and
tests.

Update those references to `UNTYPED_FALLBACK`, except comments or assertions
that intentionally mention the serialized wire value `"LEGACY_FALLBACK"`.

In `EvidenceProfile.kt`, rename:

```kotlin
val hasLegacyFallback: Boolean
val isLegacyFallbackOnly: Boolean
```

to:

```kotlin
val hasUntypedFallback: Boolean
val isUntypedFallbackOnly: Boolean
```

Keep the underlying reason check pointed at `SourceMatchReason.UNTYPED_FALLBACK`.

- [ ] **Step 5: Extract source risk classification**

Create `SourceRiskClassifier.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

internal object SourceRiskClassifier {
    data class Result(
        val confidence: SelectionConfidence,
        val flags: List<SourceCandidateRisk>,
    )

    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
    ): Result {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence
        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isUntypedFallbackOnly -> {
                flags.add(SourceCandidateRisk.UNTYPED_FALLBACK)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }
        return Result(confidence, flags)
    }

    private fun capAt(
        confidence: SelectionConfidence,
        maximum: SelectionConfidence,
    ): SelectionConfidence = if (confidence.ordinal > maximum.ordinal) maximum else confidence
}
```

Then update `SourceMatcher.kt` to call `SourceRiskClassifier.applyCaps(...)`.
If this creates a duplicate `capAt`, remove the old private `capAt` only after
all call sites are updated.

- [ ] **Step 6: Extend the repository guard**

In `scripts/v04LegacyPurgeContract-test.mjs`, update the source matching test
comment and add a check that production Kotlin only keeps `LEGACY_FALLBACK` as a
serialization token:

```js
test('source fallback risk keeps legacy token only as serialized output', () => {
  const files = [
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateRisk.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceCandidateRiskPrecedence.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt',
  ];
  for (const file of files) {
    const text = source(file);
    if (file.endsWith('SourceCandidateRisk.kt')) {
      assert.match(text, /@SerialName\("LEGACY_FALLBACK"\)\s+UNTYPED_FALLBACK/);
    } else {
      assert.doesNotMatch(text, /LEGACY_FALLBACK/, file);
    }
  }
});
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests '*SourceFallbackRiskSerializationTest' --tests '*SourceCandidateSerializationTest' --tests '*SourceMatcherTest' --tests '*EvidenceProfileTest' --tests '*SourceCandidateRiskPrecedenceTest' --no-daemon
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected: PASS.

- [ ] **Step 8: Run integration checkpoint**

Run:

```bash
npm run ci:local:fast
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add fixthis-compose-core/src/main/kotlin fixthis-compose-core/src/test/kotlin scripts/v04LegacyPurgeContract-test.mjs
git commit -m "refactor(core): rename fallback risk with wire alias"
```

Expected: commit succeeds.

### Task 8: Extract CLI setup planning

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlanner.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlannerTest.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt` only when planner-only assertions move

- [ ] **Step 1: Create planner tests**

Create `SetupPlannerTest.kt` with tests for:

- `target = "codex"` selects only the Codex writer;
- `target = "claude"` selects only the Claude writer;
- `target = "all"` selects both writers;
- executable fallback uses `fixthis mcp` when no installed MCP executable is
  found;
- dry-run metadata preserves writer name, scope, path, and content.

Use writer fakes in the test instead of writing user config files.

- [ ] **Step 2: Run and confirm failure**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*SetupPlannerTest' --no-daemon
```

Expected: FAIL because `SetupPlanner` does not exist.

- [ ] **Step 3: Add `SetupPlanner.kt`**

Create a side-effect-free planner with this public internal shape:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import java.io.File

internal data class SetupPlanRequest(
    val target: String,
    val projectRoot: File,
    val entry: McpConfigEntry,
)

internal data class SetupWritePlan(
    val writerName: String,
    val scope: String,
    val configFile: File,
    val content: String,
)

internal object SetupPlanner {
    fun selectedWriters(target: String): List<AgentConfigWriter> = when (target) {
        "codex" -> listOf(CodexConfigWriter())
        "claude" -> listOf(ClaudeConfigWriter())
        else -> listOf(CodexConfigWriter(), ClaudeConfigWriter())
    }

    fun buildWritePlans(
        writers: List<AgentConfigWriter>,
        projectRoot: File,
        entry: McpConfigEntry,
    ): List<SetupWritePlan> = writers.map { writer ->
        val configFile = writer.configFile(projectRoot)
        val current = configFile.takeIf { it.isFile }?.readText()
        SetupWritePlan(
            writerName = writer.name,
            scope = writer.scope,
            configFile = configFile,
            content = writer.merge(current, entry),
        )
    }
}
```

Keep `buildMcpConfigEntry` as the existing top-level function unless the tests
make extraction clearly useful.

- [ ] **Step 4: Delegate `SetupCommand` planning**

In `SetupCommand.kt`:

- replace private `selectedWriters` with `SetupPlanner.selectedWriters`;
- replace private `buildWritePlans` with `SetupPlanner.buildWritePlans`;
- adapt `AgentConfigWritePlan` usage to either `SetupWritePlan` or a local
  conversion at the atomic-write boundary;
- keep `runWritePlansAtomic`, `applyWritePlanForTest`, and dry-run rendering
  behavior unchanged.

- [ ] **Step 5: Run focused CLI tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*SetupPlannerTest' --tests '*SetupCommandTest' --tests '*InitAgentCommandTest' --tests '*InstallAgentJsonReportTest' --tests '*GlobalScopeGuardTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Run CLI surface checks**

Run:

```bash
bash scripts/check-docs-cli-surface.sh
npm run release:package:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlanner.kt fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupPlannerTest.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "refactor(cli): extract setup planning"
```

Expected: commit succeeds.

### Task 9: Final docs, changelog, and milestone verification

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/source-matching.md`
- Modify: `docs/reference/cli.md`
- Modify: `docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update docs language**

Make these documentation changes:

- `feedback-console-contract.md`: state that browser recovery is schema-v2
  draft workspace only and requires client draft identity.
- `output-schema.md`: keep `LEGACY_FALLBACK` documented as a serialized risk
  token if Task 7 kept that wire value; clarify it means untyped fallback
  evidence, not support for old local artifacts.
- `source-matching.md`: use "untyped fallback" for explanation and mention
  the current wire reason label `"legacy fallback"` only where output examples
  require it.
- `cli.md`: keep `install-agent`, `init`, `doctor`, global guard, dry-run, and
  JSON report examples aligned with extracted planner behavior.

- [ ] **Step 2: Finalize the curation ledger**

For each deleted, merged, or demoted test in this plan, make sure the ledger
has a row under `Completed Curation`.

Run:

```bash
rg -n "No contract|Covered by|Merged into|Demoted to" docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md
```

Expected: output includes every curation row touched by this plan.

- [ ] **Step 3: Update changelog**

Add a v0.4 entry describing:

- keyless browser recovery dedupe removal;
- console projection reduction;
- MCP handoff/event payload extraction;
- source fallback risk internal rename with wire compatibility;
- CLI setup planning extraction;
- test curation and demotion policy.

- [ ] **Step 4: Run final verification**

Run:

```bash
npm run ci:local
npm run console:test:all
npm run console:smoke
npm run first-run:smoke
npm run perf:test
npm run release:package:test
npm run release:npm:test
npm run checks:observation -- --json
```

Expected: PASS for each command. If a smoke/perf/observation command is
environment-blocked, record the concrete blocker and the owner command in the
curation ledger before declaring the milestone complete.

- [ ] **Step 5: Commit final docs**

Run:

```bash
git add docs/reference/feedback-console-contract.md docs/reference/output-schema.md docs/reference/source-matching.md docs/reference/cli.md docs/superpowers/work-notes/2026-05-17-v04-follow-up-test-curation-ledger.md CHANGELOG.md
git commit -m "docs: close v0.4 follow-up risk burn-down"
```

Expected: commit succeeds.

## Self-Review Checklist

- Every task starts with either a ledger update, a failing test, or an explicit
  inventory command.
- Every code task has a focused verification command.
- Every deleted test has a ledger reason.
- The plan preserves current persisted MCP JSON field names.
- The source fallback rename preserves `"LEGACY_FALLBACK"` as the current
  serialized risk token.
- No task asks workers to run broad tests as a substitute for focused coverage.
- Final verification commands match the approved v0.4 maintainability spec.
