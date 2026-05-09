# MCP Handoff Workflow — Follow-up Hardening Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three follow-up risks identified after the May 2026 handoff workflow merge (`27645a4`):
1. Polling tick has no failure backoff or "Reconnecting…" UI (deviation from original spec).
2. Original plan/spec for T21 describes a 3-site filter removal that no longer matches the codebase.
3. `mergeSessionIntoState` triggers per-item highlight transitions with no upper bound.

**Architecture:** Three independent client-side hardening tasks plus a build/docs phase. Phase A wires polling failure backoff + "Reconnecting feedback updates…" sub-line on the existing connection card. Phase B adds a length-based guard in `mergeSessionIntoState` so bulk transitions skip the highlight cascade. Phase C amends the original plan and spec documents in-place with correction notes so future re-runs don't re-trigger the T21 escalation. No backend, MCP-tool, or HTTP-payload changes.

**Tech Stack:** Vanilla ES module JS console concatenated by `scripts/build-console-assets.mjs`; tests assert console behavior by string-inspecting the served HTML in `FeedbackConsoleServerTest.kt` (`:fixthis-mcp:test`).

**Reference spec:** `docs/superpowers/specs/2026-05-10-mcp-handoff-followups-design.md`

---

## File Structure

### Phase A — Polling backoff + Reconnecting UI

**Modify:**
- `fixthis-mcp/src/main/console/sessions-polling.js` — add failure counter, threshold, pause/resume logic
- `fixthis-mcp/src/main/console/state.js` — add `state.connection.sessionsPollingPaused` flag (or hoisted module global)
- `fixthis-mcp/src/main/console/connection.js` — surface paused state on the connection card
- `fixthis-mcp/src/main/console/main.js` — wire `visibilitychange` recovery
- `fixthis-mcp/src/main/console/prompt.js` (or wherever `withMutationLock` lives in the call chain) — wire post-mutation recovery
- `fixthis-mcp/src/main/resources/console/app.js` — auto-regenerated bundle

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

### Phase B — Highlight burst guard

**Modify:**
- `fixthis-mcp/src/main/console/rendering.js` — add `BULK_CHANGE_HIGHLIGHT_THRESHOLD` and length guard in `mergeSessionIntoState`
- `fixthis-mcp/src/main/resources/console/app.js` — auto-regenerated

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

### Phase C — Plan/spec correction notes

**Modify:**
- `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md`
- `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md`

### Phase D — Build + docs

**Run:**
- `node scripts/build-console-assets.mjs`
- `./gradlew :fixthis-mcp:test :fixthis-mcp:installDist`

**Modify:**
- `CHANGELOG.md`

---

# Phase A — Polling backoff + Reconnecting UI

## Task 1: Add failure counter, threshold, and pause logic to `sessions-polling.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun sessionsPollingDeclaresFailureBackoffConstants() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("let consecutivePollFailures"), "must declare failure counter")
    assertTrue(html.contains("MaxConsecutivePollFailures") || html.contains("= 5"),
        "must declare threshold (named const or literal 5)")
}

@Test
fun pollSessionsTickResetsFailureCounterOnSuccess() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "pollSessionsTick")
    assertTrue(body.contains("consecutivePollFailures = 0") || body.contains("consecutivePollFailures=0"),
        "tick must reset counter on success")
}

@Test
fun pollSessionsTickIncrementsFailureCounterOnError() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "pollSessionsTick")
    assertTrue(body.contains("consecutivePollFailures++") || body.contains("consecutivePollFailures += 1"),
        "tick must increment counter on failure")
}

@Test
fun pollSessionsTickPausesAfterThreshold() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "pollSessionsTick")
    assertTrue(body.contains("setSessionsPollingPaused(true)") || body.contains("stopSessionsPolling()"),
        "tick must pause polling once threshold reached")
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*sessionsPolling*Failure*' --tests '*pollSessionsTick*' -i
```

Expected: failures (counter / threshold not declared yet).

- [ ] **Step 3: Add the failure counter and threshold to `state.js`**

In `fixthis-mcp/src/main/console/state.js`, in the same section as the existing polling globals (after `let pendingMutationCount = 0;`), add:

```javascript
let consecutivePollFailures = 0;
const MaxConsecutivePollFailures = 5;
```

Read state.js first to confirm exact location of existing polling globals.

- [ ] **Step 4: Wrap `pollSessionsTick` body in try/catch with counter logic**

In `fixthis-mcp/src/main/console/sessions-polling.js`, restructure `pollSessionsTick`:

```javascript
async function pollSessionsTick() {
  try {
    const listResp = await fetch('/api/sessions', {
      headers: lastSessionsEtag ? { 'If-None-Match': lastSessionsEtag } : {}
    });
    if (listResp.status === 200) {
      lastSessionsEtag = listResp.headers.get('ETag');
      const data = await listResp.json();
      state.sessionSummaries = data.sessions || [];
      renderSessionsList();
    }

    if (state.session?.sessionId) {
      const sessResp = await fetch('/api/session', {
        headers: lastSessionEtag ? { 'If-None-Match': lastSessionEtag } : {}
      });
      if (sessResp.status === 200) {
        lastSessionEtag = sessResp.headers.get('ETag');
        const fresh = await sessResp.json();
        if (fresh) {
          mergeSessionIntoState(fresh);
          renderInspectorRegion();
        }
      }
    }

    // success path: reset counter and ensure not paused
    consecutivePollFailures = 0;
    if (state.connection?.sessionsPollingPaused) {
      setSessionsPollingPaused(false);
    }
  } catch (err) {
    consecutivePollFailures++;
    if (consecutivePollFailures >= MaxConsecutivePollFailures) {
      setSessionsPollingPaused(true);
      stopSessionsPolling();
    }
    // Swallow error silently while in backoff window — no toast for transient failures.
  }
}
```

The `setSessionsPollingPaused` helper is added in Task 2 alongside the state flag. For now this references a forward symbol; the bundle order (state.js → ... → connection.js → ... → sessions-polling.js → ... → main.js) means runtime resolution is fine, but if the test gate at Step 5 fails because the function literally doesn't exist yet, define a placeholder that does nothing in this task and let Task 2 wire the real implementation.

Modify `startSessionsPolling` to also reset the counter at startup:

```javascript
function startSessionsPolling() {
  stopSessionsPolling();
  consecutivePollFailures = 0;
  sessionsPollingTimer = setInterval(() => {
    if (shouldPollSessions()) pollSessionsTick().catch(() => {
      // pollSessionsTick already handles its own failures; this catch is defensive.
    });
  }, SessionsPollIntervalMs);
}
```

- [ ] **Step 5: Rebuild bundle and run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*sessionsPolling*' --tests '*pollSessionsTick*' -i
```

Expected: green for the new assertions. Other FeedbackConsoleServerTest tests still pass.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/sessions-polling.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): polling failure backoff with 5-failure threshold

Task: 1 (followups)
Risk: mid
Files: sessions-polling.js, state.js, app.js, FeedbackConsoleServerTest.kt"
```

---

## Task 2: Add `sessionsPollingPaused` state flag and `setSessionsPollingPaused` helper

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js` (or `connection.js` — wherever the helper makes most sense)
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun stateConnectionDeclaresSessionsPollingPaused() {
    val html = FeedbackConsoleAssets.indexHtml
    // The flag must be declared on state.connection (or a sibling module-level let).
    assertTrue(
        html.contains("sessionsPollingPaused"),
        "must declare sessionsPollingPaused flag on state.connection"
    )
    assertTrue(
        html.contains("function setSessionsPollingPaused"),
        "must declare setSessionsPollingPaused helper"
    )
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*stateConnectionDeclaresSessionsPollingPaused*' -i
```

Expected: failures.

- [ ] **Step 3: Add the flag to `state.connection` and the helper**

In `state.js`, find where `state.connection` is initialized (search for `state.connection = ` or similar). Add `sessionsPollingPaused: false` as a new field in the initializer.

In `sessions-polling.js`, add the helper after the existing functions:

```javascript
function setSessionsPollingPaused(paused) {
  if (state.connection.sessionsPollingPaused === paused) return;
  state.connection.sessionsPollingPaused = paused;
  // Re-render the connection card to surface the change.
  renderConnection(state.connection.current);
}
```

If `state.connection.current` doesn't exist or the call fails at runtime, inspect `connection.js` to confirm the right re-render entrypoint (it might be `applyConnectionStatus(state.connection.current)` or a dedicated renderer).

- [ ] **Step 4: Rebuild and run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/sessions-polling.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): sessionsPollingPaused state flag and helper

Task: 2 (followups)
Risk: low
Files: state.js, sessions-polling.js, app.js, FeedbackConsoleServerTest.kt"
```

---

## Task 3: Surface paused state on connection card

**Files:**
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing test**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun renderConnectionSurfacesSessionsPollingPaused() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "renderConnection")
    assertTrue(
        body.contains("sessionsPollingPaused") || body.contains("Reconnecting feedback updates"),
        "renderConnection must consult the paused flag and surface a Reconnecting message"
    )
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*renderConnectionSurfacesSessionsPollingPaused*' -i
```

Expected: fail.

- [ ] **Step 3: Modify `renderConnection`**

In `connection.js`, locate `renderConnection(status)` (around line 127). After the existing `connectionMessage.textContent = ...` assignment, add:

```javascript
if (state.connection.sessionsPollingPaused) {
  // Surface a sub-line indicating sessions polling is paused after consecutive failures.
  // Uses the existing connectionDetailsBody as the secondary message channel; if a
  // dedicated sub-line element exists, prefer that.
  const baseDetails = connectionDetailsText(status);
  connectionDetailsBody.textContent = baseDetails
    ? baseDetails + ' · Reconnecting feedback updates…'
    : 'Reconnecting feedback updates…';
}
```

If `connectionDetailsBody` is hidden when `viewState === 'ready'`, ensure it stays visible while paused:

```javascript
connectionDetails.hidden = viewState === 'ready'
  && !state.connection.hasEverConnected
  && !state.connection.sessionsPollingPaused;
```

ADAPT to the actual surrounding code — read `renderConnection` first to understand which DOM nodes to use. The goal is a non-disruptive sub-line that does NOT replace the bridge headline.

- [ ] **Step 4: Rebuild and run all tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/connection.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): surface sessions-polling paused state on connection card

Task: 3 (followups)
Risk: mid
Files: connection.js, app.js, FeedbackConsoleServerTest.kt"
```

---

## Task 4: Wire visibility-change and post-mutation recovery hooks

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/state.js` (the `withMutationLock` helper)
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun visibilityChangeRecoversFromPolledFailure() {
    val html = FeedbackConsoleAssets.indexHtml
    // The visibilitychange handler must restart polling when paused.
    assertTrue(
        html.contains("sessionsPollingPaused") && html.contains("startSessionsPolling"),
        "visibility handler must consult sessionsPollingPaused and call startSessionsPolling"
    )
}

@Test
fun withMutationLockRecoversFromPolledFailure() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "withMutationLock")
    assertTrue(
        body.contains("sessionsPollingPaused") || body.contains("startSessionsPolling"),
        "withMutationLock finally-block must restart polling if paused"
    )
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*RecoversFromPolledFailure*' -i
```

Expected: fail.

- [ ] **Step 3: Update visibilitychange handler in `main.js`**

In `main.js`, locate the existing `document.addEventListener('visibilitychange', ...)` handler (added in Task 28 of the original plan). Add a recovery branch:

```javascript
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
  if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
  startLivePreviewPolling();
  // Recovery: if polling was paused due to repeated failures, restart it.
  if (!document.hidden && state.connection.sessionsPollingPaused) {
    startSessionsPolling();
  } else {
    startSessionsPolling();
  }
});
```

Simplify: `startSessionsPolling` already calls `stopSessionsPolling` first AND resets the counter, so calling it unconditionally on visibility-change is safe. The `if` branch above is just for documentation; the actual code can be:

```javascript
if (!document.hidden) startSessionsPolling();
```

(replacing the existing unconditional `startSessionsPolling()` with the visibility-gated version). Verify the existing call site is unconditional and decide whether to gate it or leave it as-is.

- [ ] **Step 4: Update `withMutationLock` in `state.js`**

In `state.js`, modify `withMutationLock`:

```javascript
async function withMutationLock(fn) {
  pendingMutationCount++;
  try {
    return await fn();
  } finally {
    pendingMutationCount--;
    // Recovery: if a successful mutation completed AND polling was paused, restart it.
    if (pendingMutationCount === 0 && state.connection?.sessionsPollingPaused) {
      startSessionsPolling();
    }
  }
}
```

Note: This restarts polling on ANY mutation completion (success OR exception in the inner `fn`). To restart only on success, wrap the recovery inside the `try` block before the `return await fn()` returns. The spec says "User-initiated mutation succeeds → restart" so the success-only variant is correct:

```javascript
async function withMutationLock(fn) {
  pendingMutationCount++;
  let succeeded = false;
  try {
    const result = await fn();
    succeeded = true;
    return result;
  } finally {
    pendingMutationCount--;
    if (succeeded && pendingMutationCount === 0 && state.connection?.sessionsPollingPaused) {
      startSessionsPolling();
    }
  }
}
```

- [ ] **Step 5: Rebuild and run all tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): recover sessions polling on visibility-change and mutation success

Task: 4 (followups)
Risk: mid
Files: main.js, state.js, app.js, FeedbackConsoleServerTest.kt"
```

---

# Phase B — Highlight burst guard

## Task 5: Add `BULK_CHANGE_HIGHLIGHT_THRESHOLD` and length guard in `mergeSessionIntoState`

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing test**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun mergeSessionIntoStateSkipsHighlightOnBulkChange() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "mergeSessionIntoState")
    assertTrue(
        body.contains("BULK_CHANGE_HIGHLIGHT_THRESHOLD") || body.contains(">= 6") || body.contains("> 5"),
        "mergeSessionIntoState must guard against bulk highlight cascade"
    )
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*mergeSessionIntoStateSkipsHighlight*' -i
```

Expected: fail.

- [ ] **Step 3: Refactor `mergeSessionIntoState` to compute `changed` first and guard**

In `rendering.js`, locate `mergeSessionIntoState`. Currently the highlight logic is an inline `forEach` (around line 639–655). Refactor to:

```javascript
const BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6;

function mergeSessionIntoState(fresh) {
  const previous = state.session;
  const preservedComment = comment.value;
  const preservedFocusedSavedItemId = focusedSavedItemId;
  const preservedFocusedPendingIndex = focusedPendingItemIndex;
  const preservedSelection = currentSelection;

  state.session = fresh;

  comment.value = preservedComment;
  focusedSavedItemId = preservedFocusedSavedItemId;
  focusedPendingItemIndex = preservedFocusedPendingIndex;
  currentSelection = preservedSelection;

  // Compute which items actually changed status since last tick.
  const previousStatusById = new Map(
    (previous?.items || []).map(item => [item.itemId, item.status])
  );
  const changed = (fresh.items || []).filter(item => {
    const before = previousStatusById.get(item.itemId);
    return before && before !== item.status;
  });

  // Bulk-change guard: skip highlight cascade for ticks with too many transitions.
  if (changed.length >= BULK_CHANGE_HIGHLIGHT_THRESHOLD) return;

  // Per-item highlight (existing logic, unchanged).
  changed.forEach(item => {
    requestAnimationFrame(() => {
      document.querySelectorAll(`[data-item-id="${CSS.escape(item.itemId)}"]`).forEach(node => {
        node.setAttribute('data-just-changed', 'true');
        setTimeout(() => node.removeAttribute('data-just-changed'), 800);
      });
    });
  });
}
```

The constant `BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6` should be at the top of the bundled JS scope (or directly above the function). Inside the function is also acceptable.

- [ ] **Step 4: Rebuild and run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green. Existing `mergeSessionIntoStatePreservesUserState` test still passes (we kept all the preserved fields).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): suppress highlight cascade on bulk status change

Task: 5 (followups)
Risk: mid
Files: rendering.js, app.js, FeedbackConsoleServerTest.kt"
```

---

# Phase C — Plan/spec correction notes

## Task 6: Append correction note to original plan's Task 21

**Files:**
- Modify: `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md`

- [ ] **Step 1: Locate Task 21**

```bash
grep -n "## Task 21:" docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md
```

- [ ] **Step 2: Append correction note**

Edit the file to insert this blockquote at the END of Task 21's body (just before the `---` separator that introduces Task 22, around line 1599):

```markdown
> **Correction note (2026-05-10 follow-up)**: This task's instructions
> were written against an earlier code state. At execution time the
> orchestrator narrowed scope to ONLY `preview.js:75`
> (`latestPersistedScreen`) because:
>
> - `rendering.js`: the filter had already been removed in pre-existing
>   main work; no changes needed.
> - `annotations.js:80`: the filter is inside `currentPromptAnnotations()`,
>   the SEND/COPY path. Removing it would break the send-once invariant
>   and the Send/Copy button enable/disable logic.
> - `preview.js:75`: the only display-side filter still in place — this
>   is what was actually changed (commit `ba31262`).
>
> The original three-site instruction is retained above for historical
> context. A future re-run of this plan should follow the narrowed scope.
```

- [ ] **Step 3: Verify document is well-formed**

```bash
grep -c "Correction note (2026-05-10 follow-up)" docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md
```

Expected: `1`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md
git commit -m "docs(plan): correct T21 scope note for handoff workflow plan

Task: 6 (followups)
Risk: low
Files: docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md"
```

---

## Task 7: Append correction note to original spec's filter removal section

**Files:**
- Modify: `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md`

- [ ] **Step 1: Locate the inspector filter removal section**

```bash
grep -n "Inspector filter removal\|delivery !== 'sent'" docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md
```

The relevant section is approximately at line 417–426.

- [ ] **Step 2: Append correction note**

Edit the file to add this blockquote inside the "Inspector filter removal" section, after the existing diff snippets:

```markdown
> **Implementation correction (2026-05-10 follow-up)**: At execution
> time only `preview.js:75` was modified. The `rendering.js:226` filter
> had already been removed; the `annotations.js:65` reference moved to
> `annotations.js:80` inside `currentPromptAnnotations()` where the
> filter is intentional (send-once invariant). See follow-up plan
> `2026-05-10-mcp-handoff-followups.md` for context.
```

- [ ] **Step 3: Verify**

```bash
grep -c "Implementation correction (2026-05-10 follow-up)" docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md
```

Expected: `1`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md
git commit -m "docs(spec): correct inspector filter removal scope for handoff workflow spec

Task: 7 (followups)
Risk: low
Files: docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md"
```

---

# Phase D — Build + docs

## Task 8: Final build, install, sweep, CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Rebuild bundle and run full unit-test sweep**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test
```

Expected: BUILD SUCCESSFUL across all 5 modules.

- [ ] **Step 2: Repackage distributions**

```bash
./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist
```

- [ ] **Step 3: Lint**

```bash
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Both should be silent.

- [ ] **Step 4: Add CHANGELOG entry**

In `CHANGELOG.md`, prepend under the `## Unreleased` section (or whichever upcoming-version section is current):

```markdown
### Changed
- Sessions polling now silently absorbs up to 5 consecutive failures, then pauses and surfaces a "Reconnecting feedback updates…" sub-line on the connection card. Polling resumes automatically when the tab becomes visible again or the user takes any successful mutating action.
- Bulk status changes (≥6 items in a single polling tick) skip the per-item highlight effect to avoid visual noise; single-item updates highlight as before.

### Docs
- `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md` and `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md` amended in-place with correction notes for T21 (`delivery !== 'sent'` filter removal) — the original 3-site instruction targeted code that had already been refactored; only `preview.js:75` was actually modified at execution time.
```

- [ ] **Step 5: Commit any remaining bundle regeneration + CHANGELOG**

```bash
# If app.js was regenerated:
git add fixthis-mcp/src/main/resources/console/app.js CHANGELOG.md
# Otherwise just CHANGELOG:
git add CHANGELOG.md

git commit -m "chore: rebundle app.js and add CHANGELOG entry for handoff follow-ups

Task: 8 (followups)
Risk: low
Files: CHANGELOG.md (+ app.js if regenerated)"
```

---

## Self-Review

(Performed by the plan author before handing off.)

**Spec coverage:**
- Goal 1 (silent failure absorption + Reconnecting UI): Tasks 1–4 ✓
- Goal 2 (plan/spec correction notes): Tasks 6–7 ✓
- Goal 3 (highlight burst guard): Task 5 ✓
- Goal 4 (no schema/MCP/HTTP changes): all tasks are client-side or docs-only ✓

**Placeholder scan:** No `TBD`/`TODO`. Where helpers may not exist (`renderConnection` re-render entrypoint, exact line numbers in `connection.js`), tasks instruct the implementer to inspect existing analogues — concrete enough.

**Type/name consistency:**
- `consecutivePollFailures` (let in state.js) referenced in sessions-polling.js — same name across all sites.
- `MaxConsecutivePollFailures = 5` — single declaration, single comparison site.
- `state.connection.sessionsPollingPaused` — flag name consistent across state.js, sessions-polling.js, connection.js, main.js.
- `setSessionsPollingPaused(paused)` — signature consistent across sessions-polling.js (definition) and pollSessionsTick (call).
- `BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6` — single declaration in rendering.js, single comparison site.
- `startSessionsPolling()` — already exists from original plan; recovery hooks call it directly.

**Forward-reference handling:**
- Task 1's `pollSessionsTick` references `setSessionsPollingPaused` which is defined in Task 2. Bundle order means runtime resolution works, but Task 1's tests do NOT depend on Task 2 being done — they assert on declarations and counter mechanics, not on the helper's runtime behavior. Task 2's tests assert the helper exists. Order of execution: Task 1 → Task 2 (mandatory).

**Test coverage gaps:**
- Manual / E2E (no harness for): connection-card paused message rendering, visual no-flash on bulk, post-mutation auto-resume. Acceptable — listed in spec testing section.

No additional fixes required.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-10-mcp-handoff-followups.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration. ~50–70 min total.

**2. Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints. Same total time.

**Which approach?**
