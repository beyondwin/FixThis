# Console Transport Session Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FixThis feedback console resilient to browser request cancellation, SSE reconnects, and cross-session preview races so session switching cannot appear stuck and benign disconnects do not produce noisy server errors.

**Architecture:** Keep the existing `HttpServer` route table and vanilla JavaScript console architecture. Add focused transport helpers around response writes, move SSE setup into a failure-aware path, and centralize browser session-context invalidation so every async preview/draft path uses the same session fence.

**Tech Stack:** Kotlin/JVM 21, `com.sun.net.httpserver.HttpServer`, kotlinx.serialization JSON, vanilla browser JavaScript, Node 20 `node:test`, Gradle/kotlin.test, existing `scripts/build-console-assets.mjs` bundle pipeline.

---

## File Structure

- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt` to expose safe response helpers and a shared client-disconnect classifier.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` so all error response writes use the safe helpers and no catch path can rethrow a client disconnect.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt` to use the shared `sendJson`/`sendText` path rather than handwritten response writes.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt` to compute the initial snapshot before committing SSE headers and to treat keep-alive/client disconnect as normal stream closure.
- Modify `fixthis-mcp/src/main/console/preview.js` to add a reusable session preview context helper instead of ad hoc fences.
- Modify `fixthis-mcp/src/main/console/history.js` so session refresh/open/new/delete paths invalidate preview state when the displayed session changes.
- Modify `fixthis-mcp/src/main/console/annotations.js` so draft annotation freeze uses the same session-context fence as live preview refresh.
- Modify `fixthis-mcp/src/main/console/events.js` so snapshot/session event application clears preview context when the active session changes.
- Modify `scripts/sessionScopedRequests-test.mjs`, `scripts/pendingBoundaryGuard-test.mjs`, or create `scripts/transportSessionResilience-test.mjs` for browser source-level regression tests.
- Modify `scripts/console-tests.json` to include the new browser regression test in the `session` group.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerErrorLoggingTest.kt` for safe error-response write coverage.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt` for SSE snapshot-before-headers behavior.
- Modify `docs/architecture/console-state-sync-design.md` to record the session/preview transport contract.

## Task 1: Make All HTTP Response Writes Client-Disconnect Safe

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerErrorLoggingTest.kt`

- [ ] **Step 1: Write failing tests for disconnects while writing error responses**

Add this test to `FeedbackConsoleServerErrorLoggingTest.kt`:

```kotlin
@Test
fun clientDisconnectsDuringErrorResponseWriteAreNotRethrownOrLogged() {
    val sink = StringBuilder()
    val server = FeedbackConsoleServer(
        routes = listOf(ThrowingRoute(FeedbackConsoleHttpException(400, "bad request"))),
        diagnosticsSink = { sink.appendLine(it) },
    )
    val exchange = FakeHttpExchange(
        method = "GET",
        path = "/api/session",
        responseBody = DisconnectingOutputStream(),
    )

    server.dispatch(exchange)

    assertEquals(400, exchange.statusCode)
    assertFalse(
        sink.toString().contains("Connection reset by peer"),
        "Client disconnects while sending error JSON must not produce diagnostics logs: $sink",
    )
}
```

Add this companion test to verify handwritten response routes do not regress:

```kotlin
@Test
fun serverVersionRouteUsesSharedSafeResponseWriter() {
    val source = java.nio.file.Files.readString(
        java.nio.file.Paths.get(
            "src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt",
        ),
    )

    assertTrue(source.contains("exchange.sendText(200, payload, \"application/json; charset=utf-8\")"))
    assertFalse(source.contains("exchange.responseBody.use"))
}
```

- [ ] **Step 2: Run the focused server logging tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerErrorLoggingTest" --no-daemon
```

Expected: FAIL because `FeedbackConsoleHttpException` catch currently calls `sendErrorJson()` directly and `ServerVersionRoutes.kt` writes to `responseBody` by hand.

- [ ] **Step 3: Move the disconnect classifier to shared console HTTP helpers**

In `ConsoleHttp.kt`, add the public-in-package classifier and safe send helpers:

```kotlin
internal fun Throwable.isClientDisconnect(): Boolean {
    val messages = sequenceOf(this) + suppressed.asSequence() + generateSequence(cause) { it.cause }
    return messages.any { error ->
        val message = error.message?.lowercase().orEmpty()
        message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("stream is closed") ||
            message.contains("insufficient bytes written to stream")
    }
}

internal fun HttpExchange.closeQuietly() {
    runCatching { close() }
}

internal fun HttpExchange.trySendErrorJson(status: Int, message: String) {
    runCatching { sendErrorJson(status, message) }
        .onFailure { error ->
            if (error.isClientDisconnect()) {
                closeQuietly()
            } else {
                throw error
            }
        }
}
```

Remove the duplicate private `Throwable.isClientDisconnect()` from `FeedbackConsoleServer.kt` after moving it.

- [ ] **Step 4: Use safe error-response writes in server dispatch**

Update `FeedbackConsoleServer.dispatch()` catch blocks:

```kotlin
} catch (error: FeedbackConsoleHttpException) {
    exchange.trySendErrorJson(error.statusCode, error.message)
} catch (error: FeedbackSessionException) {
    val httpError = error.toConsoleHttpException()
    exchange.trySendErrorJson(httpError.statusCode, httpError.message)
} catch (error: BridgeConnectionException) {
    exchange.trySendErrorJson(HTTP_SERVICE_UNAVAILABLE, error.message ?: "FixThis bridge is not connected")
} catch (error: Throwable) {
    if (error.isClientDisconnect()) {
        exchange.closeQuietly()
        return
    }
    diagnosticsSink(
        "FeedbackConsoleServer: ${error::class.java.name}: ${error.message}\n${error.stackTraceToString()}",
    )
    exchange.trySendErrorJson(500, error.message ?: error::class.java.simpleName)
}
```

- [ ] **Step 5: Route server-version through shared response helpers**

Update `ServerVersionRoutes.kt`:

```kotlin
override fun handle(exchange: HttpExchange) {
    exchange.requireMethod("GET") {
        val payload = """{"serverBuildEpochMs":${BuildInfo.BUILD_EPOCH_MS},"serverGitSha":"${BuildInfo.GIT_SHA}","bridgeProtocolVersion":"$BridgeProtocolVersion"}"""
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendText(200, payload, "application/json; charset=utf-8")
    }
}
```

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerErrorLoggingTest" --no-daemon
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerErrorLoggingTest.kt
git commit -m "Harden console HTTP response disconnect handling"
```

## Task 2: Make SSE Snapshot And Keep-Alive Failure-Aware

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Write a failing test for snapshot failure before SSE headers**

Add a fake service bridge that throws on device listing to `ConsoleEventsRoutesTest.kt`:

```kotlin
@Test
fun eventsEndpointReturnsJsonErrorWhenSnapshotFailsBeforeStreamStarts() {
    val service = FeedbackSessionService(
        bridge = object : FakeFixThisBridge() {
            override fun devices(): List<AdbDevice> {
                throw IllegalStateException("adb unavailable")
            }
        },
        store = FeedbackSessionStore(),
        projectRoot = "/repo",
        defaultPackageName = "io.github.beyondwin.fixthis.sample",
    )
    val server = FeedbackConsoleServer(service)
    try {
        server.start()
        val connection = URI("${server.url}/api/events").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000

        assertEquals(500, connection.responseCode)
        assertTrue(connection.errorStream.bufferedReader().readText().contains("adb unavailable"))
    } finally {
        server.stop()
    }
}
```

Add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
```

- [ ] **Step 2: Run the focused SSE test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest.eventsEndpointReturnsJsonErrorWhenSnapshotFailsBeforeStreamStarts" --no-daemon
```

Expected: FAIL because `/api/events` currently sends HTTP 200 headers before calling `snapshot()`.

- [ ] **Step 3: Compute snapshot before committing SSE headers**

Update the start of `ConsoleEventRoutes.handle()`:

```kotlin
override fun handle(exchange: HttpExchange) {
    exchange.requireMethod("GET") {
        val initialSnapshot = snapshot()
        val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
            ?: exchange.queryParameter("lastEventId")?.toLongOrNull()
            ?: INITIAL_EVENT_ID
        val replay = eventBus.eventsAfter(lastEventId)

        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Connection", "keep-alive")
        exchange.sendResponseHeaders(HTTP_OK, 0)

        val output = exchange.responseBody
        val subscriberClosed = CountDownLatch(1)
        fun send(event: ConsoleEvent) {
            output.writeSseEvent(event.name, event.id, event.data)
        }

        try {
            output.writeSseEvent("snapshot", null, initialSnapshot)
            if (replay.overflow) {
                output.writeSseEvent(
                    "replay-overflow",
                    null,
                    buildJsonObject {
                        replay.oldestAvailableEventId?.let { put("oldestAvailableEventId", it) }
                    },
                )
            } else {
                replay.events.forEach(::send)
            }
            val subscription = eventBus.subscribe { event ->
                runCatching { send(event) }.onFailure { subscriberClosed.countDown() }
            }
            try {
                while (!subscriberClosed.await(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    runCatching {
                        output.write(": keep-alive\n\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }.onFailure { error ->
                        if (error.isClientDisconnect()) {
                            subscriberClosed.countDown()
                        } else {
                            throw error
                        }
                    }
                }
            } finally {
                subscription.close()
            }
        } finally {
            runCatching { output.close() }
        }
    }
}
```

- [ ] **Step 4: Add a source contract test for keep-alive disconnect handling**

Add to `ConsoleEventsRoutesTest.kt`:

```kotlin
@Test
fun eventsRouteTreatsKeepAliveClientDisconnectAsNormalClosure() {
    val source = java.nio.file.Files.readString(
        java.nio.file.Paths.get(
            "src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt",
        ),
    )

    assertTrue(source.contains("runCatching {"))
    assertTrue(source.contains("error.isClientDisconnect()"))
    assertTrue(source.contains("subscriberClosed.countDown()"))
}
```

- [ ] **Step 5: Run SSE tests and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest" --no-daemon
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "Harden console SSE lifecycle"
```

## Task 3: Centralize Browser Session Preview Context Fences

**Files:**
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Create: `scripts/transportSessionResilience-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing browser source tests for shared fences**

Create `scripts/transportSessionResilience-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (file) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', file), 'utf8');
const preview = read('preview.js');
const history = read('history.js');
const annotations = read('annotations.js');
const events = read('events.js');

function functionBody(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `${name} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${name} body did not close`);
}

test('preview module exposes reusable session preview context helpers', () => {
  assert.match(preview, /function capturePreviewContext\(\)/);
  assert.match(preview, /function previewContextStillCurrent\(context\)/);
  assert.match(preview, /sessionId: state\.session\?\.sessionId \|\| null/);
  assert.match(preview, /context\.sessionId === \(state\.session\?\.sessionId \|\| null\)/);
});

test('refreshPreview and startDraftAnnotationFlow both use the shared context helper', () => {
  const refreshPreview = functionBody(preview, 'refreshPreview');
  const startDraftAnnotationFlow = functionBody(annotations, 'startDraftAnnotationFlow');
  assert.match(refreshPreview, /const previewContext = capturePreviewContext\(\);/);
  assert.match(refreshPreview, /previewContextStillCurrent\(previewContext\)/);
  assert.match(startDraftAnnotationFlow, /const previewContext = capturePreviewContext\(\);/);
  assert.match(startDraftAnnotationFlow, /previewContextStillCurrent\(previewContext\)/);
});

test('session refresh clears preview when the server current session changes', () => {
  const refreshSessions = functionBody(history, 'refreshSessions');
  assert.match(refreshSessions, /const previousSessionId = state\.session\?\.sessionId \|\| null;/);
  assert.match(refreshSessions, /const nextSessionId = currentSession\?\.sessionId \|\| null;/);
  assert.match(refreshSessions, /if \(previousSessionId !== nextSessionId\) clearPreview\(\);/);
});

test('SSE snapshot and session updates clear preview when active session changes', () => {
  const startConsoleEvents = functionBody(events, 'startConsoleEvents');
  assert.match(startConsoleEvents, /applySessionFromServer\(data\.session \|\| null\)/);
  assert.match(startConsoleEvents, /applySessionFromServer\(session\)/);
  assert.match(startConsoleEvents, /function applySessionFromServer\(session\)/);
  assert.match(startConsoleEvents, /if \(previousSessionId !== nextSessionId\) clearPreview\(\);/);
});
```

- [ ] **Step 2: Register and run the failing browser test**

Add the new test to the `session` group in `scripts/console-tests.json`:

```json
"scripts/transportSessionResilience-test.mjs"
```

Run:

```bash
npm run console:session:test
```

Expected: FAIL because shared preview context helpers and automatic session-change preview invalidation do not exist yet.

- [ ] **Step 3: Add reusable preview context helpers**

Add to `preview.js` near `clearPreview()`:

```js
function capturePreviewContext() {
  return {
    sessionId: state.session?.sessionId || null,
    contextGeneration: previewUseCases.getState().contextGeneration,
  };
}

function previewContextStillCurrent(context) {
  return Boolean(context) &&
    context.sessionId === (state.session?.sessionId || null) &&
    context.contextGeneration === previewUseCases.getState().contextGeneration;
}
```

Update `refreshPreview()`:

```js
async function refreshPreview() {
  error.textContent = '';
  if (!state.session || draftFlow()) return;
  const previewContext = capturePreviewContext();
  try {
    const preview = await previewUseCases.request();
    if (draftFlow() || !previewContextStillCurrent(previewContext)) return;
    if (preview?.screen?.systemUiVisible && state.preview) {
      state.preview.stale = true;
      state.preview.obstructedBySystemUi = preview.screen.systemUiKind || 'system_ui';
      markPreviewStale(true);
      renderPreviewOnly();
      return;
    }
    setConsolePreview({
      ...preview,
      activity: state.connection?.availability?.activity ?? null,
      frozenAtEpochMillis: Date.now(),
      stale: false,
    });
    if (userConnectionState(state.connection.current) === 'ready') markPreviewStale(false);
    renderPreviewOnly();
  } catch (cause) {
    markPreviewStale(true);
    refreshConnection({ preservePreviewStale: true }).catch(() => {});
    throw cause;
  }
}
```

- [ ] **Step 4: Fence draft annotation preview capture**

Update `annotations.js` inside `startDraftAnnotationFlow()`:

```js
const previewContext = capturePreviewContext();
let preview = state.preview;
if (previewUseCases.getState().inFlight || !preview) {
  preview = await previewUseCases.request();
  if (!previewContextStillCurrent(previewContext)) return;
  preview = {
    ...preview,
    activity: state.connection?.availability?.activity ?? null,
    frozenAtEpochMillis: Date.now(),
    stale: false,
  };
  setConsolePreview(preview);
}
if (!previewContextStillCurrent(previewContext) || !state.preview) {
  return;
}
```

- [ ] **Step 5: Clear preview on server-driven session changes**

Update `history.js` in `refreshSessions()`:

```js
async function refreshSessions() {
  const previousSessionId = state.session?.sessionId || null;
  const [response, currentSession] = await Promise.all([
    requestJson('/api/sessions'),
    requestJson('/api/session'),
  ]);
  const nextSessionId = currentSession?.sessionId || null;
  if (previousSessionId !== nextSessionId) clearPreview();
  setConsoleSession(currentSession || null);
  renderSessionsListFromPayload(response.sessions || []);
  return response.sessions || [];
}
```

Update `events.js` inside `startConsoleEvents()`:

```js
function applySessionFromServer(session) {
  const previousSessionId = state.session?.sessionId || null;
  const nextSessionId = session?.sessionId || null;
  if (previousSessionId !== nextSessionId) clearPreview();
  setConsoleSession(session || null);
}
```

Use it in the snapshot handler:

```js
if ('session' in data) {
  if (!data.session && previousDisplayedSessionId) clearDisplayedSessionState();
  else applySessionFromServer(data.session || null);
}
```

Use it in the `session-updated` handler:

```js
const session = data.session;
applySessionFromServer(session);
loadPendingRecoveryForCurrentSession();
render();
```

- [ ] **Step 6: Rebuild console assets and run browser tests**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
npm run console:session:test
npm run console:preview:test
```

Expected: PASS for all listed tests.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/preview.js \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/events.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/transportSessionResilience-test.mjs \
  scripts/console-tests.json
git commit -m "Fence console preview state by active session"
```

## Task 4: Add End-To-End Regression Coverage And Architecture Notes

**Files:**
- Modify: `scripts/console-fixture/scenarios-test.mjs`
- Modify: `scripts/console-fixture/scenarios/multiTab.mjs`
- Modify: `docs/architecture/console-state-sync-design.md`
- Test: `scripts/console-fixture/scenarios-test.mjs`

- [ ] **Step 1: Add scenario assertions for rapid session switching**

Extend `scripts/console-fixture/scenarios/multiTab.mjs` with this exported scenario:

```js
export async function rapidSessionSwitchCancelsOldPreview({ page, server }) {
  await page.goto(server.consoleUrl);
  await page.getByRole('button', { name: 'Start' }).click();
  await server.createSession({ sessionId: 'session-a', title: 'Session A' });
  await server.createSession({ sessionId: 'session-b', title: 'Session B' });
  await server.delayPreviewForSession('session-a', 250);

  await page.getByText('Session A').click();
  await page.getByText('Session B').click();

  await page.waitForFunction(() => window.FixThisConsoleDebug?.getState?.().session?.sessionId === 'session-b');
  const preview = await page.evaluate(() => window.FixThisConsoleDebug?.getState?.().preview);
  if (preview && preview.sessionId === 'session-a') {
    throw new Error('stale session-a preview remained visible after switching to session-b');
  }
}
```

If the fake server does not yet expose `delayPreviewForSession`, implement it as a map of per-session artificial delays in `scripts/console-fixture/fakeBridgeServer.mjs`:

```js
const previewDelays = new Map();

function delayPreviewForSession(sessionId, delayMs) {
  previewDelays.set(sessionId, Number(delayMs) || 0);
}

async function maybeDelayPreview(sessionId) {
  const delayMs = previewDelays.get(sessionId) || 0;
  if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
}
```

Call `await maybeDelayPreview(sessionId)` before the fake preview response is sent.

- [ ] **Step 2: Register the scenario test**

In `scripts/console-fixture/scenarios-test.mjs`, add:

```js
test('rapid session switching cancels old preview without stale UI', async () => {
  await runScenario('rapidSessionSwitchCancelsOldPreview');
});
```

- [ ] **Step 3: Run the harness scenario**

Run:

```bash
npm run console:harness:test
```

Expected: PASS, including the new rapid session switching scenario.

- [ ] **Step 4: Document the transport/session contract**

Append this section to `docs/architecture/console-state-sync-design.md`:

```markdown
## Transport And Session Resilience Contract

The console treats browser request cancellation as a normal transport event. Server routes must route response writes through `ConsoleHttp` helpers so `connection reset`, `broken pipe`, closed streams, and fixed-length close errors are closed quietly rather than logged as server defects.

The active session is the ownership boundary for preview state. Any server-driven current-session change, user session switch, device switch, or draft context reset must invalidate `state.preview` through `clearPreview()`. Async preview completions may update UI only when both the captured `sessionId` and preview `contextGeneration` still match the current console state.

SSE `/api/events` must compute its initial snapshot before sending streaming headers. After headers are committed, keep-alive write failures caused by client disconnect close the subscription quietly; server-side snapshot failures remain normal JSON errors before the stream begins.
```

- [ ] **Step 5: Run full relevant verification**

Run:

```bash
npm run console:session:test
npm run console:preview:test
npm run console:harness:test
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: all commands PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/console-fixture/scenarios-test.mjs \
  scripts/console-fixture/scenarios/multiTab.mjs \
  scripts/console-fixture/fakeBridgeServer.mjs \
  docs/architecture/console-state-sync-design.md
git commit -m "Document and test console transport session resilience"
```

## Final Verification

- [ ] **Step 1: Check no generated console assets are stale**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
```

Expected: PASS with no output.

- [ ] **Step 2: Run the MCP console test suite**

Run:

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run focused browser console suites**

Run:

```bash
npm run console:session:test
npm run console:preview:test
npm run console:harness:test
```

Expected: all Node test suites report `fail 0`.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git diff --stat
git status --short
```

Expected: only intended files are modified before the final commit, and no unrelated untracked files are staged.

## Self-Review

- Spec coverage: The plan covers all observed recurrence classes: image/JSON error response cancellation, SSE reconnect/failure behavior, browser-side stale preview application after session changes, and regression harness coverage.
- Placeholder scan: No task depends on an unspecified implementation; every code-changing step includes concrete snippets and every verification step includes exact commands and expected outcomes.
- Type consistency: Kotlin helper names are `isClientDisconnect`, `closeQuietly`, and `trySendErrorJson`; browser helper names are `capturePreviewContext` and `previewContextStillCurrent`; these names are used consistently across tasks.
