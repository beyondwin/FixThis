# v0.4 Maintainability Phase 1 Legacy Purge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove pre-v0.4 local artifact and recovery compatibility paths, curate the tests that protected those paths, and leave current v0.4 contracts guarded before deeper console/MCP/core/CLI refactors begin.

**Architecture:** This is the first executable slice of the approved v0.4 maintainability design. It removes old-input compatibility at the browser storage, console route, MCP session, and source-matching terminology boundaries while preserving current canonical output contracts. The remaining v0.4 plans start from the smaller surface this phase leaves behind.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, Gradle, Node 20 `node:test`, vanilla browser JavaScript bundled by `scripts/build-console-assets.mjs`, existing FixThis console and MCP test harnesses.

Spec: [`../specs/2026-05-17-v04-maintainability-contract-first-refactor-design.md`](../specs/2026-05-17-v04-maintainability-contract-first-refactor-design.md)

---

## Scope Check

The approved v0.4 design covers multiple subsystems. A single implementation plan for every console, MCP, core, CLI, and test-architecture refactor would be too large to execute safely. This plan covers the first independent slice:

- define the test curation inventory;
- add a guard that pre-v0.4 compatibility paths stay removed;
- remove schema-v1 browser pending mirrors and migration;
- remove `.fixthis/artifacts` screenshot fallback;
- remove legacy semantic duplicate detection for draft items without client draft keys;
- remove deprecated no-item handoff service calls;
- rename source matcher internal `LEGACY_FALLBACK` terminology while preserving its wire label;
- update docs so they no longer claim old local artifacts are supported.

Separate follow-up plans cover console global projection retirement, MCP session collaborator splitting, core source-matcher policy extraction, and CLI setup cleanup.

## File Structure

### New files

| Path | Responsibility |
| --- | --- |
| `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md` | Records the test curation inventory, delete/merge/demote reasons, and current-contract owners for this phase. |
| `scripts/v04LegacyPurgeContract-test.mjs` | Repository-level Node contract test that prevents reintroduction of pre-v0.4 local artifact compatibility paths. |

### Modified browser console files

| Path | Responsibility after this plan |
| --- | --- |
| `fixthis-mcp/src/main/console/draftPorts.js` | Draft ports expose schema-v2 workspace storage only; no legacy pending migration port. |
| `fixthis-mcp/src/main/console/draftStorageAdapter.js` | Stores and loads schema-v2 workspaces; no `fixthis.pending.<sessionId>` migration or cleanup helpers. |
| `fixthis-mcp/src/main/console/main.js` | Loads recovery from schema-v2 workspaces only; no pending mirror or legacy migration fallback. |
| `fixthis-mcp/src/main/console/history.js` | History counts and delete/clear flows use schema-v2 workspaces only. |
| `fixthis-mcp/src/main/console/annotations.js` | Annotation persistence no longer clears or tracks schema-v1 pending mirrors. |
| `fixthis-mcp/src/main/console/pendingRecoveryUi.js` | Recovery UI no longer reads or clears schema-v1 pending mirrors. |
| `fixthis-mcp/src/main/console/pendingPersistence.js` | Deleted; schema-v1 pending mirror is unsupported in v0.4. |

### Modified Kotlin files

| Path | Responsibility after this plan |
| --- | --- |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt` | Serves screenshots only from preview cache and persisted v0.4 session roots. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt` | Serves persisted screen screenshots only from the v0.4 session root. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` | Deduplicates incoming batch items by current client draft keys only. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt` | Owns current client draft key helpers only. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt` | Deleted; old semantic retry protection is unsupported in v0.4. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` | Exposes item-id-aware handoff only. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt` | Uses clearer internal fallback terminology while preserving the existing wire label. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` | Refers to the renamed untyped fallback reason. |

### Modified tests and config

| Path | Change |
| --- | --- |
| `scripts/console-tests.json` | Adds `scripts/v04LegacyPurgeContract-test.mjs` to the `canonical` group and removes legacy pending tests from active groups. |
| `scripts/draftStorageAdapter-test.mjs` | Removes schema-v1 migration assertions and keeps schema-v2 storage tests. |
| `scripts/pendingItemRecovery-test.mjs` | Deleted or reduced to a current-contract test file with no schema-v1 mirror assertions. |
| `scripts/pendingBoundaryGuard-test.mjs` | Removes assertions for `clearPendingMirror` and `activePendingMirrorSessions`. |
| `scripts/sessionScopedRequests-test.mjs` | Stops loading `pendingPersistence.js`; keeps session scoping assertions that still protect current behavior. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt` | Updates latest screenshot fallback expectations away from `.fixthis/artifacts`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | Removes pre-v0.4 source-candidate fixture compatibility and updates handoff tests to current item-id-aware calls. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt` | Uses item-id-aware handoff or direct store tests for current behavior. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | Replaces service no-item handoff setup calls with current item-id-aware calls. |

### Modified docs

| Path | Change |
| --- | --- |
| `docs/reference/feedback-console-contract.md` | Removes schema-v1 pending mirror migration claims. |
| `docs/reference/mcp-tools.md` | Removes legacy localStorage readability claim. |
| `docs/reference/output-schema.md` | Removes `.fixthis/artifacts` compatibility language. |
| `docs/reference/privacy.md` | Removes old pending mirror migration language. |
| `docs/architecture/overview.md` | Updates browser recovery and artifact path descriptions for v0.4. |
| `docs/guides/troubleshooting.md` | Replaces migration guidance with `fixthis clean` / unsupported-version guidance. |

## Tasks

### Task 1: Create the v0.4 test contract inventory

**Files:**
- Create: `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md`

- [ ] **Step 1: Write the inventory document**

Create `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md` with this content:

```markdown
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
```

- [ ] **Step 2: Verify the document has no placeholder text**

Run:

```bash
rg -n "PLACEHOLDER|UNDECIDED|FILL_ME_IN|FIX_BEFORE_PLAN_EXECUTION" docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md
```

Expected: no output.

- [ ] **Step 3: Commit the inventory**

Run:

```bash
git add docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md
git commit -m "docs: inventory v0.4 test contracts"
```

Expected: commit succeeds.

### Task 2: Add the legacy purge contract test

**Files:**
- Create: `scripts/v04LegacyPurgeContract-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the failing contract test**

Create `scripts/v04LegacyPurgeContract-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function assertNoPattern(path, pattern, reason) {
  assert.doesNotMatch(source(path), pattern, `${path}: ${reason}`);
}

test('browser console no longer exposes schema-v1 pending mirror APIs', () => {
  assert.equal(
    existsSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js')),
    false,
    'pendingPersistence.js is schema-v1 pending mirror support and must stay removed',
  );
  for (const path of [
    'fixthis-mcp/src/main/console/draftPorts.js',
    'fixthis-mcp/src/main/console/draftStorageAdapter.js',
    'fixthis-mcp/src/main/console/main.js',
    'fixthis-mcp/src/main/console/history.js',
    'fixthis-mcp/src/main/console/annotations.js',
    'fixthis-mcp/src/main/console/pendingRecoveryUi.js',
  ]) {
    assertNoPattern(path, /fixthis\.pending|migrateLegacyPending|clearLegacyPending|restorePendingState|persistPendingState|clearPendingMirror|activePendingMirrorSessions/, 'pre-v0.4 pending mirror support is unsupported');
  }
});

test('console screenshot routes do not allow old artifact roots', () => {
  for (const path of [
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt',
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt',
  ]) {
    assertNoPattern(path, /\.fixthis\/artifacts|legacyRoot|legacyArtifactsDir/, 'pre-v0.4 screenshot artifact fallback is unsupported');
  }
});

test('session store does not dedupe retries using pre-client-key semantic fallback', () => {
  assert.equal(
    existsSync(resolve(root, 'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt')),
    false,
    'semantic duplicate fallback for old draft items must stay removed',
  );
  for (const path of [
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt',
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt',
  ]) {
    assertNoPattern(path, /legacySemanticDraftKey|incomingSemanticDraftKey|existingLegacySemanticKeys|semanticDraftKey/, 'current idempotency uses client draft keys only');
  }
});

test('MCP session service exposes item-id-aware handoff only', () => {
  assertNoPattern(
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt',
    /fun sendDraftToAgent\(sessionId: String\): SessionDto/,
    'deprecated no-item handoff overload must stay removed',
  );
});

test('source matching uses untyped fallback terminology internally', () => {
  assertNoPattern(
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt',
    /LEGACY_FALLBACK/,
    'internal enum name should not call current fallback behavior legacy',
  );
  assert.match(
    source('fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt'),
    /UNTYPED_FALLBACK\("legacy fallback"\)/,
    'wire label remains backward-compatible while internal terminology is current',
  );
});
```

- [ ] **Step 2: Add the contract test to the canonical console test group**

Edit `scripts/console-tests.json` and add `"scripts/v04LegacyPurgeContract-test.mjs"` as the last entry in the `canonical` array:

```json
"scripts/v04LegacyPurgeContract-test.mjs"
```

- [ ] **Step 3: Verify the contract test fails before removal**

Run:

```bash
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected: FAIL. The failure should mention at least `pendingPersistence.js`, `.fixthis/artifacts`, or `LEGACY_FALLBACK`.

- [ ] **Step 4: Commit the failing contract test**

Run:

```bash
git add scripts/v04LegacyPurgeContract-test.mjs scripts/console-tests.json
git commit -m "test: add v0.4 legacy purge contract"
```

Expected: commit succeeds with a known failing test. Keep the next task in the same branch before opening a PR.

### Task 3: Remove schema-v1 browser pending mirror support

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftPorts.js`
- Modify: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Delete: `fixthis-mcp/src/main/console/pendingPersistence.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/pendingRecoveryUi.js`
- Modify: `scripts/draftStorageAdapter-test.mjs`
- Delete or reduce: `scripts/pendingItemRecovery-test.mjs`
- Modify: `scripts/console-tests.json`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/sessionScopedRequests-test.mjs`

- [ ] **Step 1: Remove the legacy storage port**

In `fixthis-mcp/src/main/console/draftPorts.js`, change the storage port comment to:

```js
//   storage: { saveWorkspace(envelope): void, deleteWorkspace(sessionId, workspaceId): void, loadWorkspacesForSession(sessionId): object[] },
```

Change the fake storage object to:

```js
    storage: {
      saveWorkspace: () => {},
      deleteWorkspace: () => {},
      loadWorkspacesForSession: () => [],
    },
```

- [ ] **Step 2: Remove legacy migration from draft storage**

In `fixthis-mcp/src/main/console/draftStorageAdapter.js`, remove:

- `LegacyPendingKeyPrefix`;
- `normalizeLegacyDraftItem`;
- `clearLegacyPending`;
- `migrateLegacyPending`.

The returned adapter object must be exactly:

```js
  return {
    saveWorkspace,
    loadWorkspacesForSession,
    deleteWorkspace,
    deleteWorkspacesForSession,
  };
```

- [ ] **Step 3: Delete schema-v1 pending mirror implementation**

Run:

```bash
git rm fixthis-mcp/src/main/console/pendingPersistence.js
```

Expected: file is removed from the index.

- [ ] **Step 4: Remove pending mirror state and fallback calls from main.js**

In `fixthis-mcp/src/main/console/main.js`, remove the top-level declaration:

```js
const activePendingMirrorSessions = new Set();
```

Replace `loadDraftRecoveryForSession` with:

```js
            function loadDraftRecoveryForSession(sessionId) {
              const storage = createBrowserDraftPorts().storage;
              return newestDraftRecovery(storage.loadWorkspacesForSession(sessionId));
            }
```

Replace the restored recovery line in `loadPendingRecoveryForCurrentSession` with:

```js
              const restored = loadDraftRecoveryForSession(sessionId);
```

Remove every call to:

```js
clearPendingMirror(...)
createBrowserDraftPorts().storage.clearLegacyPending?.(...)
activePendingMirrorSessions.add(...)
activePendingMirrorSessions.delete(...)
activePendingMirrorSessions.has(...)
```

- [ ] **Step 5: Remove pending mirror calls from history, annotations, and recovery UI**

In these files, delete calls to `clearPendingMirror(...)`, `restorePendingState(...)`, and `activePendingMirrorSessions.*`:

```text
fixthis-mcp/src/main/console/history.js
fixthis-mcp/src/main/console/annotations.js
fixthis-mcp/src/main/console/pendingRecoveryUi.js
```

After this step, this command must print no output:

```bash
rg -n "fixthis\\.pending|migrateLegacyPending|clearLegacyPending|restorePendingState|persistPendingState|clearPendingMirror|activePendingMirrorSessions" fixthis-mcp/src/main/console scripts -g '!v04LegacyPurgeContract-test.mjs'
```

- [ ] **Step 6: Trim draft storage tests to schema-v2 only**

In `scripts/draftStorageAdapter-test.mjs`, remove the two tests named:

```text
schema v1 pending envelope migrates into schema v2 workspace recovery
schema v1 pending migration consumes the legacy mirror once
```

Keep the tests for:

```text
workspace storage is keyed by captured session and workspace
deleteWorkspace removes stored workspace and index entry
deleteWorkspacesForSession removes every indexed workspace and the index
loadWorkspacesForSession drops malformed indexed workspace payloads
dropEmptyEntries removes exact empty comments while keeping whitespace comments
loadWorkspacesForSession ignores stored workspaces with no non-empty entries
```

- [ ] **Step 7: Remove the pending mirror test file from active test groups**

In `scripts/console-tests.json`, remove `"scripts/pendingItemRecovery-test.mjs"` from the `pending` group. Keep `"scripts/pendingRecoveryVariant-test.mjs"`.

Then remove the file:

```bash
git rm scripts/pendingItemRecovery-test.mjs
```

- [ ] **Step 8: Trim source-inspection tests that mention pending mirrors**

Update `scripts/pendingBoundaryGuard-test.mjs` by removing assertions that match:

```js
clearPendingMirror(sessionId)
activePendingMirrorSessions.delete(sessionId)
clearPendingMirror(composerSessionId)
activePendingMirrorSessions.delete(composerSessionId)
```

Update `scripts/sessionScopedRequests-test.mjs` by removing the `readFileSync` load of `fixthis-mcp/src/main/console/pendingPersistence.js` and any assertions that inspect `persistPendingState`, `restorePendingState`, `persistPendingItems`, `restorePendingItems`, or `clearPendingMirror`.

- [ ] **Step 9: Run browser contract tests**

Run:

```bash
node --test scripts/draftStorageAdapter-test.mjs scripts/v04LegacyPurgeContract-test.mjs
node scripts/run-console-tests.mjs pending draft session canonical
node scripts/build-console-assets.mjs --check
```

Expected: all commands pass.

- [ ] **Step 10: Commit the browser legacy purge**

Run:

```bash
git add fixthis-mcp/src/main/console scripts/console-tests.json scripts/draftStorageAdapter-test.mjs scripts/pendingBoundaryGuard-test.mjs scripts/sessionScopedRequests-test.mjs scripts/v04LegacyPurgeContract-test.mjs
git add -u fixthis-mcp/src/main/console/pendingPersistence.js scripts/pendingItemRecovery-test.mjs
git commit -m "refactor(console): remove pre-v0.4 pending mirror support"
```

Expected: commit succeeds.

### Task 4: Remove `.fixthis/artifacts` screenshot fallback

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetRoutesTest.kt` if it asserts artifact fallback
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt` if it hard-codes `.fixthis/artifacts`

- [ ] **Step 1: Remove legacy root from latest screenshot lookup**

In `PreviewScreenshotResponder.kt`, replace:

```kotlin
val legacyRoot = File(projectRoot, ".fixthis/artifacts").canonicalFile
val roots = listOf(previewRoot, persistedRoot, legacyRoot)
```

with:

```kotlin
val roots = listOf(previewRoot, persistedRoot)
```

- [ ] **Step 2: Remove legacy root from persisted screen screenshot route**

In `ArtifactRoutes.kt`, replace:

```kotlin
val legacyArtifactsDir = File(session.projectRoot, ".fixthis/artifacts").canonicalFile
val isAllowedArtifact = screenshotFile.toPath().startsWith(sessionArtifactsDir.toPath()) ||
    screenshotFile.toPath().startsWith(legacyArtifactsDir.toPath())
```

with:

```kotlin
val isAllowedArtifact = screenshotFile.toPath().startsWith(sessionArtifactsDir.toPath())
```

- [ ] **Step 3: Update preview route tests**

In `ConsolePreviewRoutesTest.kt`, replace fixtures that create paths under:

```text
.fixthis/artifacts/
```

with paths under the current persisted session root. Use:

```kotlin
val paths = FeedbackSessionPaths(projectRoot)
val artifact = paths.rootDirectory.resolve("session-1/screens/screen-1/full.png")
artifact.parentFile.mkdirs()
artifact.writeBytes(byteArrayOf(1, 2, 3))
```

When the test needs to prove old paths are rejected, assert a 404 for:

```kotlin
val oldArtifact = projectRoot.resolve(".fixthis/artifacts/screen-1/full.png")
```

- [ ] **Step 4: Run focused screenshot route tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ConsolePreviewRoutesTest' --tests '*ConsoleAssetRoutesTest' --no-daemon
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected: all tests pass.

- [ ] **Step 5: Commit screenshot fallback removal**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt \
        scripts/v04LegacyPurgeContract-test.mjs
git commit -m "refactor(mcp): remove legacy screenshot artifact fallback"
```

Expected: commit succeeds. If one of the listed test files was unchanged, omit it from `git add`.

### Task 5: Remove pre-client-key semantic duplicate fallback

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt`
- Delete: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackDraftDeduplicationRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

- [ ] **Step 1: Remove semantic fallback from batch dedupe**

In `FeedbackSessionStoreDelegate.kt`, replace:

```kotlin
val existingClientKeys = session.items.mapNotNull { it.clientDraftKey() }.toSet()
val existingLegacySemanticKeys = existingLegacySemanticKeysForScreen(session, screen)
val newItems = items.filterNot { item ->
    val clientDuplicate = item.clientDraftKey()?.let { it in existingClientKeys } == true
    val legacyDuplicate = item.incomingSemanticDraftKey()?.let { it in existingLegacySemanticKeys } == true
    clientDuplicate || legacyDuplicate
}
```

with:

```kotlin
val existingClientKeys = session.items.mapNotNull { it.clientDraftKey() }.toSet()
val newItems = items.filterNot { item ->
    item.clientDraftKey()?.let { it in existingClientKeys } == true
}
```

- [ ] **Step 2: Remove old semantic helper file**

Run:

```bash
git rm fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt
```

- [ ] **Step 3: Remove helper from current draft dedupe file**

In `FeedbackSessionStoreDraftDeduplication.kt`, delete the entire `existingLegacySemanticKeysForScreen` function. The file should still contain:

```kotlin
internal fun matchingClientDraftItems(session: SessionDto, items: List<AnnotationDto>): List<AnnotationDto>
internal fun duplicateScreenFor(session: SessionDto, duplicateItems: List<AnnotationDto>, requestedScreen: SnapshotDto): SnapshotDto
internal fun screenForIncomingBatch(session: SessionDto, duplicateItems: List<AnnotationDto>, requestedScreen: SnapshotDto, idGenerator: () -> String, now: Long): SnapshotDto
internal fun appendScreenIfMissing(session: SessionDto, screen: SnapshotDto): List<SnapshotDto>
internal fun createScreenItems(session: SessionDto, screen: SnapshotDto, items: List<AnnotationDto>, now: Long, idGenerator: () -> String): List<AnnotationDto>
internal fun AnnotationDto.clientDraftKey(): String?
```

- [ ] **Step 4: Remove tests that expect old semantic dedupe**

In `ConsoleFeedbackDraftDeduplicationRoutesTest.kt` and `FeedbackSessionStoreTest.kt`, delete test cases whose names or fixtures mention:

```text
legacy
semantic duplicate
without client draft key
old draft
```

Keep tests where duplicate detection uses both `clientWorkspaceId` and `clientDraftItemId`.

- [ ] **Step 5: Run focused dedupe tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleFeedbackDraftDeduplicationRoutesTest' --tests '*FeedbackSessionStoreTest' --no-daemon
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected: all tests pass.

- [ ] **Step 6: Commit semantic fallback removal**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackDraftDeduplicationRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt \
        scripts/v04LegacyPurgeContract-test.mjs
git add -u fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt
git commit -m "refactor(mcp): require client draft keys for retry dedupe"
```

Expected: commit succeeds.

### Task 6: Remove deprecated no-item handoff service overload

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Delete the deprecated service overload**

In `FeedbackSessionService.kt`, delete this entire declaration:

```kotlin
@Deprecated(
    "Test/MCP-tool only. Production code must use sendDraftToAgent(sessionId, itemIds) " +
        "to keep CompactHandoffRenderer as the single source of truth for handoff markdown.",
    ReplaceWith("sendDraftToAgent(sessionId, itemIds)"),
)
fun sendDraftToAgent(sessionId: String): SessionDto = feedbackDraftService.sendDraftToAgent(sessionId)
```

- [ ] **Step 2: Replace test setup calls with item-id-aware handoff**

For each test call that currently looks like:

```kotlin
service.sendDraftToAgent(session.sessionId)
```

replace it with:

```kotlin
service.sendDraftToAgent(session.sessionId, listOf(item.itemId))
```

If the test created more than one item and expects all of them to be handed off, use:

```kotlin
service.sendDraftToAgent(session.sessionId, session.items.map { it.itemId })
```

Use the variable name that exists in the test. Do not introduce a no-item wrapper.

- [ ] **Step 3: Verify no no-item service calls remain**

Run:

```bash
rg -n "service\\.sendDraftToAgent\\([^,\\n]+\\)" fixthis-mcp/src/test fixthis-mcp/src/main
```

Expected: no output.

- [ ] **Step 4: Run focused handoff tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackDraftServiceTest' --tests '*McpProtocolTest' --tests '*ConsoleFeedbackItemRoutesTest' --no-daemon
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected: all tests pass.

- [ ] **Step 5: Commit no-item handoff removal**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt \
        scripts/v04LegacyPurgeContract-test.mjs
git commit -m "refactor(mcp): remove deprecated no-item handoff service"
```

Expected: commit succeeds.

### Task 7: Rename source matcher fallback terminology internally

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfileTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` if it references the enum name
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` if it references the enum name

- [ ] **Step 1: Rename the enum constant while preserving wire label**

In `SourceMatchReason.kt`, replace:

```kotlin
LEGACY_FALLBACK("legacy fallback"),
```

with:

```kotlin
UNTYPED_FALLBACK("legacy fallback"),
```

- [ ] **Step 2: Rename references**

Run:

```bash
perl -0pi -e 's/LEGACY_FALLBACK/UNTYPED_FALLBACK/g' \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfileTest.kt
```

- [ ] **Step 3: Update comments and test names**

In source matcher comments and test names, replace phrasing that calls the current fallback path "legacy-only" with "untyped fallback". Keep expected wire labels and Markdown tokens as `"legacy fallback"` and `"legacy"` when those are output contracts.

For example, a test name should change from:

```kotlin
fun legacyFallbackOnlyMatchEmitsLegacyReasonAndCapsAtLow()
```

to:

```kotlin
fun untypedFallbackMatchEmitsLegacyWireReasonAndCapsAtLow()
```

- [ ] **Step 4: Verify internal enum name is gone and wire label remains**

Run:

```bash
rg -n "LEGACY_FALLBACK" fixthis-compose-core fixthis-mcp
rg -n "\"legacy fallback\"" fixthis-compose-core fixthis-mcp
node --test scripts/v04LegacyPurgeContract-test.mjs
```

Expected:

- first command prints no output;
- second command prints expected formatter/wire-label references;
- contract test passes.

- [ ] **Step 5: Run focused source matcher tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests '*SourceMatcherTest' --tests '*EvidenceProfileTest' --no-daemon
```

Expected: tests pass.

- [ ] **Step 6: Commit terminology cleanup**

Run:

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfileTest.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt \
        scripts/v04LegacyPurgeContract-test.mjs
git commit -m "refactor(core): rename source fallback terminology"
```

Expected: commit succeeds. If one of the optional formatter files was unchanged, omit it from `git add`.

### Task 8: Remove old local artifact support from docs

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/privacy.md`
- Modify: `docs/architecture/overview.md`
- Modify: `docs/guides/troubleshooting.md`

- [ ] **Step 1: Update feedback console contract**

In `docs/reference/feedback-console-contract.md`, remove the bullet that says legacy schema-v1 `localStorage["fixthis.pending.<sessionId>"]` envelopes are migrated. Add this bullet under Persistence Semantics:

```markdown
- v0.4 supports schema-v2 `fixthis.workspace.*` draft recovery only. Pre-v0.4
  `fixthis.pending.<sessionId>` mirrors are ignored; use `fixthis clean` or
  clear browser storage if an old local recovery entry is confusing the
  console.
```

- [ ] **Step 2: Update MCP tools reference**

In `docs/reference/mcp-tools.md`, replace text that says `localStorage["fixthis.pending.<sessionId>"]` envelopes are still readable with:

```markdown
The browser console stores unsent draft recovery in schema-v2
`fixthis.workspace.*` entries. v0.4 does not read pre-v0.4
`fixthis.pending.<sessionId>` mirrors.
```

- [ ] **Step 3: Update output schema artifact paths**

In `docs/reference/output-schema.md`, remove `.fixthis/artifacts/` as a supported screenshot path. State that current persisted screen artifacts live under `.fixthis/feedback-sessions/<session-id>/`.

- [ ] **Step 4: Update privacy and troubleshooting**

In `docs/reference/privacy.md`, remove claims that old pending mirrors may still be read for migration. In `docs/guides/troubleshooting.md`, replace legacy migration guidance with:

```markdown
FixThis v0.4 does not migrate pre-v0.4 browser pending mirrors. If stale local
recovery appears after upgrading, clear browser storage for the FixThis console
origin and run `fixthis clean --project-dir .` to remove old local artifacts.
```

- [ ] **Step 5: Update architecture overview**

In `docs/architecture/overview.md`, update browser draft recovery and artifact path paragraphs so they describe only:

```text
.fixthis/feedback-sessions/
.fixthis/preview-cache/
localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]
```

- [ ] **Step 6: Verify docs no longer advertise removed support**

Run:

```bash
rg -n "fixthis\\.pending|schema-v1|still readable|still read|migrated|\\.fixthis/artifacts" docs/reference docs/architecture docs/guides
```

Expected: no output for claims of active support. Historical design specs under `docs/superpowers/` and old plans may still mention the old paths and are not part of this command.

- [ ] **Step 7: Commit docs cleanup**

Run:

```bash
git add docs/reference/feedback-console-contract.md \
        docs/reference/mcp-tools.md \
        docs/reference/output-schema.md \
        docs/reference/privacy.md \
        docs/architecture/overview.md \
        docs/guides/troubleshooting.md
git commit -m "docs: document v0.4 local artifact cutoff"
```

Expected: commit succeeds.

### Task 9: Run phase 1 verification and update inventory

**Files:**
- Modify: `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md`

- [ ] **Step 1: Run legacy purge and console verification**

Run:

```bash
node --test scripts/v04LegacyPurgeContract-test.mjs
node scripts/run-console-tests.mjs canonical pending draft session
node scripts/build-console-assets.mjs --check
```

Expected: all commands pass.

- [ ] **Step 2: Run focused Kotlin verification**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ConsolePreviewRoutesTest' --tests '*ConsoleFeedbackDraftDeduplicationRoutesTest' --tests '*FeedbackDraftServiceTest' --tests '*McpProtocolTest' --no-daemon
./gradlew :fixthis-compose-core:test --tests '*SourceMatcherTest' --tests '*EvidenceProfileTest' --no-daemon
```

Expected: all commands pass.

- [ ] **Step 3: Run fast local CI**

Run:

```bash
npm run ci:local:fast
```

Expected: pass. If it fails outside files touched by this plan, record the failing command and exact unrelated failure in the inventory instead of masking it.

- [ ] **Step 4: Update the inventory with final status**

Append this section to `docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md`:

```markdown
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
```

If a command was not run because of an environment blocker, replace `PASS` with the exact blocker and do not mark the milestone complete.

- [ ] **Step 5: Commit phase completion note**

Run:

```bash
git add docs/superpowers/work-notes/2026-05-17-v04-test-contract-inventory.md
git commit -m "docs: record v0.4 phase 1 verification"
```

Expected: commit succeeds.

## Self-Review Checklist

Before marking this plan complete, verify:

- [ ] `rg -n "fixthis\\.pending|migrateLegacyPending|clearLegacyPending|restorePendingState|persistPendingState|clearPendingMirror|activePendingMirrorSessions" fixthis-mcp/src/main/console scripts -g '!v04LegacyPurgeContract-test.mjs'` prints no output.
- [ ] `rg -n "\\.fixthis/artifacts|legacyRoot|legacyArtifactsDir" fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console docs/reference docs/architecture docs/guides` prints no active support claims.
- [ ] `rg -n "legacySemanticDraftKey|incomingSemanticDraftKey|existingLegacySemanticKeys|semanticDraftKey" fixthis-mcp/src/main/kotlin scripts -g '!v04LegacyPurgeContract-test.mjs'` prints no output.
- [ ] `rg -n "fun sendDraftToAgent\\(sessionId: String\\): SessionDto|service\\.sendDraftToAgent\\([^,\\n]+\\)" fixthis-mcp/src/main fixthis-mcp/src/test` prints no output.
- [ ] `rg -n "LEGACY_FALLBACK" fixthis-compose-core fixthis-mcp` prints no output.
- [ ] Current-schema draft, session, handoff, source matching, and screenshot route tests pass.
