# Console Harness Blocked Scenarios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote `network-outage` and `slow-handoff` console harness scenarios from skipped placeholders to real browser checks.

**Architecture:** Add stable DOM test IDs to existing console elements, expand the fake bridge fixture to serve the actual console routes, then make the harness drive real draft/handoff flows for outage and slow-response cases. Keep the current custom harness runner and skip reporting for future scenarios.

**Tech Stack:** Vanilla browser JavaScript, generated console bundle, Node 20 test runner, Playwright, fake HTTP bridge fixture, npm scripts.

**Related spec:** [`../specs/2026-05-15-console-harness-blocked-scenarios-detailed-spec.md`](../specs/2026-05-15-console-harness-blocked-scenarios-detailed-spec.md)

---

## File Structure

- Modify `fixthis-mcp/src/main/resources/console/index.html` to add stable `data-testid` attributes.
- Modify `fixthis-mcp/src/main/console/connection.js` to project reconnect visibility as a state attribute.
- Modify `fixthis-mcp/src/main/console/main.js` to add `data-testid` on the dynamically created pending recovery banner.
- Modify `fixthis-mcp/src/main/resources/console/app.js` and `.map` only through bundle regeneration.
- Modify `scripts/console-fixture/fakeBridgeServer.mjs` to serve realistic session, sessions, connection, device, preview, batch save, and handoff endpoints.
- Modify `scripts/console-fixture/scenarios/networkOutage.mjs` and `slowHandoff.mjs` to configure route behavior through `runScenario`.
- Modify `scripts/console-harness.mjs` to remove blocked status and drive the actual flows.
- Modify `scripts/console-harness.test.mjs` and `scripts/console-fixture/fakeBridgeServer-test.mjs`.
- Modify `CHANGELOG.md` after the scenarios are active.

## Task 1: DOM Contracts For Harness Selectors

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`

- [ ] **Step 1: Add failing asset contract test**

Add to `ConsoleAssetContractTest`:

```kotlin
@Test
fun harnessSelectorsAreStable() {
    val html = ConsoleSourceFixtures.readAll()
    assertTrue(html.contains("""data-testid="connection-card""""))
    assertTrue(html.contains("""data-testid="save-to-mcp-button""""))
    assertTrue(html.contains("""data-testid="copy-prompt-button""""))
    assertTrue(html.contains("""data-testid="prompt-readiness""""))
    assertTrue(html.contains("""data-testid="global-status""""))
    assertTrue(html.contains("""data-reconnect-visible"""))
    assertTrue(html.contains("""data-testid="pending-recovery-banner""""))
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest.harnessSelectorsAreStable" --no-daemon
```

Expected: FAIL because the attributes are not present.

- [ ] **Step 3: Add static attributes**

In `index.html`, update:

```html
<span id="promptReadiness" class="prompt-readiness" aria-live="polite" data-testid="prompt-readiness">No annotations ready</span>
<button id="copyPromptButton" class="with-icon" type="button" disabled data-testid="copy-prompt-button">
<button id="sendAgentButton" class="primary with-icon" type="button" disabled data-testid="save-to-mcp-button">
<section id="connectionCard" class="connection-card" data-connection-state="welcome" data-reconnect-visible="false" data-testid="connection-card" aria-live="polite">
<p id="error" class="global-status" role="status" aria-live="polite" data-testid="global-status" hidden></p>
```

- [ ] **Step 4: Add dynamic attributes**

In `connection.js`, after `connectionCard.dataset.connectionState = viewState;`:

```js
              connectionCard.dataset.reconnectVisible = viewState === 'reconnect' || state.connection.sessionsPollingPaused ? 'true' : 'false';
```

In `main.js`, after creating `pendingRecoveryBanner`:

```js
              banner.dataset.testid = 'pending-recovery-banner';
```

- [ ] **Step 5: Regenerate and verify**

Run:

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest.harnessSelectorsAreStable" --no-daemon
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html fixthis-mcp/src/main/console/connection.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt
git commit -m "test(console): add stable harness selectors"
```

## Task 2: Fake Bridge Route Coverage

**Files:**
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Test: `scripts/console-fixture/fakeBridgeServer-test.mjs`

- [ ] **Step 1: Add route contract tests**

Add to `fakeBridgeServer-test.mjs`:

```js
test('fake bridge serves core console routes', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    for (const path of ['/api/session', '/api/sessions', '/api/connection', '/api/devices', '/api/preview']) {
      const response = await fetch(fixture.url + path);
      assert.equal(response.status, 200, path);
      assert.match(response.headers.get('content-type') || '', /application\/json/);
    }
  } finally {
    await fixture.close();
  }
});

test('fake bridge records handoff request count', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    const response = await fetch(fixture.url + '/api/agent-handoffs', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 'session-1', itemIds: ['item-1'] }),
    });
    assert.equal(response.status, 200);
    assert.equal(fixture.getRequestLog().filter((r) => r.path === '/api/agent-handoffs').length, 1);
  } finally {
    await fixture.close();
  }
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: FAIL on routes that currently return 404 or wrong shape.

- [ ] **Step 3: Implement minimal fixture state**

In `fakeBridgeServer.mjs`, add a mutable state:

```js
function initialSession() {
  return {
    sessionId: 'session-1',
    ordinal: 1,
    status: 'working',
    createdAtEpochMillis: Date.now(),
    updatedAtEpochMillis: Date.now(),
    nextItemSequenceNumber: 1,
    screens: [],
    items: [],
    handoffBatches: [],
  };
}

function sessionSummary(session) {
  return {
    sessionId: session.sessionId,
    ordinal: session.ordinal,
    status: session.status,
    updatedAtEpochMillis: session.updatedAtEpochMillis,
    openItemCount: session.items.filter((item) => item.status !== 'resolved').length,
    resolvedItemCount: session.items.filter((item) => item.status === 'resolved').length,
    itemCount: session.items.length,
    screenCount: session.screens.length,
  };
}
```

Handle core routes:

```js
if (url.pathname === '/api/session' && req.method === 'GET') {
  json(res, session);
  return;
}
if (url.pathname === '/api/sessions' && req.method === 'GET') {
  json(res, { sessions: [sessionSummary(session)] });
  return;
}
if (url.pathname === '/api/connection' && req.method === 'GET') {
  json(res, { state: scenarioState.forceState || 'READY', connection: 'connected' });
  return;
}
if (url.pathname === '/api/devices' && req.method === 'GET') {
  json(res, { devices: [{ serial: 'fake-device', label: 'Fake Device', selected: true }], selectedDeviceSerial: 'fake-device' });
  return;
}
if (url.pathname === '/api/preview' && req.method === 'GET') {
  json(res, fakePreview());
  return;
}
```

Define `json` helper:

```js
function json(res, body, status = 200) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(body));
}
```

- [ ] **Step 4: Verify and commit**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: PASS.

Commit:

```bash
git add scripts/console-fixture/fakeBridgeServer.mjs scripts/console-fixture/fakeBridgeServer-test.mjs
git commit -m "test(console): expand fake bridge route coverage"
```

## Task 3: Slow Handoff Scenario

**Files:**
- Modify: `scripts/console-harness.mjs`
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `scripts/console-fixture/scenarios/slowHandoff.mjs`
- Test: `scripts/console-harness.test.mjs`

- [ ] **Step 1: Add scenario status test**

Add to `console-harness.test.mjs`:

```js
test('slow handoff is not marked blocked', () => {
  const all = selectScenarios('all');
  assert.ok(all.includes('slow-handoff'));
  const source = readFileSync('scripts/console-harness.mjs', 'utf8');
  assert.doesNotMatch(source, /'slow-handoff'[\s\S]{0,160}@blocked-pending-impl/);
});
```

- [ ] **Step 2: Remove blocked status**

In `SCENARIOS`, change:

```js
'slow-handoff': {
  apply: applySlowHandoff,
  requiredViewports: Object.keys(VIEWPORTS),
},
```

- [ ] **Step 3: Implement slow handoff assertion**

Replace the `slow-handoff` branch in `runCell` with:

```js
    } else if (scenarioKey === 'slow-handoff') {
      await page.waitForSelector('[data-testid="save-to-mcp-button"]');
      await page.evaluate(() => {
        const button = document.querySelector('[data-testid="save-to-mcp-button"]');
        button.disabled = false;
      });
      const before = fixture.getRequestLog().filter((r) => r.path === '/api/agent-handoffs').length;
      await page.click('[data-testid="save-to-mcp-button"]');
      await page.waitForFunction(() => document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled === true);
      await page.click('[data-testid="save-to-mcp-button"]').catch(() => {});
      await page.waitForFunction(() => document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled === false, null, { timeout: 8000 });
      const after = fixture.getRequestLog().filter((r) => r.path === '/api/agent-handoffs').length;
      if (after - before !== 1) throw new Error(`expected one handoff request, got ${after - before}`);
```

Before relying on the button click, seed one saved annotation through the fake
bridge session state and call `refresh()` in the page:

```js
      await fixture.seedAnnotation({ itemId: 'item-1', comment: 'Slow handoff regression' });
      await page.evaluate(() => refresh());
      await page.waitForFunction(() => document.querySelector('[data-testid="save-to-mcp-button"]')?.disabled === false);
```

Add `seedAnnotation` to the fixture object:

```js
    seedAnnotation: async ({ itemId = 'item-1', comment = 'Harness annotation' } = {}) => {
      session.items = [{
        itemId,
        annotationId: itemId,
        screenId: 'screen-1',
        label: 'Harness annotation',
        comment,
        severity: 'medium',
        status: 'open',
        delivery: 'draft',
        targetType: 'area',
        bounds: { left: 10, top: 10, right: 120, bottom: 80 },
      }];
      session.screens = session.screens.length ? session.screens : [fakeScreen()];
      session.updatedAtEpochMillis = Date.now();
    },
```

- [ ] **Step 4: Verify and commit**

Run:

```bash
node --test scripts/console-harness.test.mjs
node scripts/console-harness.mjs --matrix slow-handoff --viewport mobile-390 --fail-on-skips
```

Expected: PASS and no SKIP lines.

Commit:

```bash
git add scripts/console-harness.mjs scripts/console-harness.test.mjs scripts/console-fixture/fakeBridgeServer.mjs scripts/console-fixture/scenarios/slowHandoff.mjs
git commit -m "test(console): enable slow handoff harness scenario"
```

## Task 4: Network Outage Scenario

**Files:**
- Modify: `scripts/console-harness.mjs`
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `scripts/console-fixture/scenarios/networkOutage.mjs`
- Test: `scripts/console-harness.test.mjs`

- [ ] **Step 1: Add scenario status test**

Add to `console-harness.test.mjs`:

```js
test('network outage is not marked blocked', () => {
  const source = readFileSync('scripts/console-harness.mjs', 'utf8');
  assert.doesNotMatch(source, /'network-outage'[\s\S]{0,160}@blocked-pending-impl/);
});
```

- [ ] **Step 2: Remove blocked status**

In `SCENARIOS`, change:

```js
'network-outage': {
  apply: applyNetworkOutage,
  requiredViewports: Object.keys(VIEWPORTS),
},
```

- [ ] **Step 3: Implement outage state in fixture**

In fake bridge request handlers, when `scenarioState.pollErrno === 'ECONNREFUSED'`, make `/api/session`, `/api/sessions`, and `/api/agent-handoffs` fail by destroying the socket or returning 503. Keep `/` assets available.

```js
function shouldFailForOutage(path) {
  return scenarioState.pollErrno === 'ECONNREFUSED' &&
    (path === '/api/session' || path === '/api/sessions' || path === '/api/agent-handoffs');
}
```

Use it at the start of API handling:

```js
if (shouldFailForOutage(url.pathname)) {
  req.socket.destroy();
  return;
}
```

- [ ] **Step 4: Implement outage harness assertion**

Replace the `network-outage` branch with:

```js
    if (scenarioKey === 'network-outage') {
      await page.waitForSelector('[data-testid="connection-card"]');
      await page.evaluate(() => {
        window.dispatchEvent(new Event('focus'));
      });
      await page.waitForFunction(() => {
        const card = document.querySelector('[data-testid="connection-card"]');
        return card?.dataset.reconnectVisible === 'true' ||
          /reconnect|paused|recover/i.test(card?.textContent || '');
      }, null, { timeout: 8000 });
      const recoveryVisible = await page.locator('[data-testid="pending-recovery-banner"]').count();
      const cardText = await page.locator('[data-testid="connection-card"]').textContent();
      if (!/reconnect|paused|recover/i.test(cardText || '') && recoveryVisible === 0) {
        throw new Error('network outage did not surface reconnect or recovery UI');
      }
```

- [ ] **Step 5: Verify and commit**

Run:

```bash
node --test scripts/console-harness.test.mjs
node scripts/console-harness.mjs --matrix network-outage --viewport mobile-390 --fail-on-skips
```

Expected: PASS and no SKIP lines.

Commit:

```bash
git add scripts/console-harness.mjs scripts/console-harness.test.mjs scripts/console-fixture/fakeBridgeServer.mjs scripts/console-fixture/scenarios/networkOutage.mjs
git commit -m "test(console): enable network outage harness scenario"
```

## Task 5: Full Matrix And Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/contributing/connected-tests.md` if it mentions blocked harness scenarios

- [ ] **Step 1: Update changelog**

Replace the existing note that `network-outage` and `slow-handoff` are
deferred with:

```markdown
  - Network-outage and slow-handoff console harness scenarios now run as active
    matrix cells with stable DOM contracts for reconnect, pending recovery, and
    in-flight handoff states.
```

- [ ] **Step 2: Run final verification**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs scripts/console-harness.test.mjs
node scripts/console-harness.mjs --matrix network-outage,slow-handoff --viewport all --fail-on-skips
npm run console:harness:test
npm run console:test:all
node scripts/build-console-assets.mjs --check
git diff --check
```

Expected: all PASS, no SKIP lines for `network-outage` or `slow-handoff`.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md docs/contributing/connected-tests.md
git commit -m "docs(console): mark blocked harness scenarios active"
```

## Self-Review

- Spec coverage: stable DOM contracts, fake bridge route coverage, network
  outage, slow handoff, skip policy, docs, and final verification are covered.
- Placeholder scan: all tasks include exact file paths, concrete selectors,
  commands, expected results, and commit commands.
- Type and name consistency: selector names match the spec table and harness
  plan: `connection-card`, `save-to-mcp-button`, `copy-prompt-button`,
  `prompt-readiness`, `pending-recovery-banner`, and `global-status`.
