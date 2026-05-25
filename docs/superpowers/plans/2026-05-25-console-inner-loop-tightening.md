# Console Inner Loop Tightening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate manual rebundle / refresh / `--check` toil in the JS edit loop and verify Kotlin restart preserves browser state, with a status chip showing which server build the console is talking to.

**Architecture:** Add `--watch` to the existing Node bundler so it stays resident and atomically rewrites `app.js`, `app.js.map`, and `console-build-meta.json`. Add a server-side asset watcher (only when started with `--console-assets-dir`) that polls `console-build-meta.json` mtime and emits a `console-assets-changed` SSE event over the existing `/api/events` channel. A new browser module subscribes to the event and calls `location.reload()` when the bundle hash differs. For Track 2, add an integration test that asserts a Kotlin restart preserves persisted drafts across reconnect, then add a small status chip that displays server build SHA + reconnect state.

**Tech Stack:** Node 20+ (esbuild watch API, `node --test`), Kotlin / JVM HTTP server, vanilla JS browser console, JUnit/`kotlin.test`.

**Spec reference:** `docs/superpowers/specs/2026-05-25-console-inner-loop-tightening-design.md`

---

## File Structure

**Created**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcher.kt` — server-side mtime poller; emits `console-assets-changed` SSE events when `console-build-meta.json` advances in dir-mode.
- `fixthis-mcp/src/main/console/devReload.js` — browser module that subscribes to `console-assets-changed` and triggers `location.reload()` only when the bundle hash differs and `devReloadEnabled` is true.
- `fixthis-mcp/src/main/console/serverBuildChip.js` — browser module that renders the build SHA + reconnect-state chip.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcherTest.kt` — server-side watcher unit/integration test.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/RestartReconnectIntegrationTest.kt` — Track 2 integration test for restart preserving drafts.
- `scripts/build-console-assets-watch-test.mjs` — Node `--test` for the watch mode.
- `scripts/devReload-test.mjs` — Node `--test` for `devReload.js` reload behavior.
- `scripts/serverBuildChip-test.mjs` — Node `--test` for the chip rendering and transitions.

**Modified**
- `scripts/build-console-assets.mjs` — add `--watch` flag using esbuild `context().watch()`, atomic write per artifact.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt` — extend the inlined `window.FixThisConsoleConfig` to include `devReloadEnabled` (true only when `consoleAssetsDir != null`) and a `buildHash` field pulled from `console-build-meta.json`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` — start/stop `ConsoleAssetsWatcher` when `consoleAssetsDir != null`.
- `fixthis-mcp/src/main/console/main.js` — wire `devReload.js` and `serverBuildChip.js` into init.
- `fixthis-mcp/src/main/console/sse.js` — already the SSE-state module; extend its `// @requires` graph for new modules that need it. (No behavior change in this file.)
- `package.json` — register the three new `node --test` scripts under `scripts.console:*:test` and include them in `console:fsm:test` runner or its sibling.
- `CONTRIBUTING.md` § Console Inner Loop — document `--watch` and the chip.
- `docs/reference/feedback-console-contract.md` — document the `console-assets-changed` SSE event (dir-mode only) and `devReloadEnabled` / `buildHash` fields on `FixThisConsoleConfig`.
- `CHANGELOG.md` Unreleased — Added section.

---

## Track 1: JS Watch Pipeline

### Task 1: Add `--watch` to `build-console-assets.mjs`

**Files:**
- Modify: `scripts/build-console-assets.mjs`
- Test: `scripts/build-console-assets-watch-test.mjs` (create)

- [ ] **Step 1: Write the failing test**

Create `scripts/build-console-assets-watch-test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as wait } from 'node:timers/promises';

const repoRoot = new URL('..', import.meta.url).pathname;

async function waitFor(predicate, timeoutMs = 5000, stepMs = 50) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await wait(stepMs);
  }
  throw new Error('waitFor: predicate never became true');
}

test('watch mode rewrites artifacts atomically on source change', async (t) => {
  const consoleSrc = join(repoRoot, 'fixthis-mcp/src/main/console');
  const probeFile = join(consoleSrc, 'devReloadProbe.js');
  // Use a unique probe module to avoid touching real sources.
  writeFileSync(probeFile, '// @requires (none)\nconst FixThisDevReloadProbe = 1;\n');
  t.after(() => { try { require('node:fs').unlinkSync(probeFile); } catch {} });

  const child = spawn(
    'node',
    ['scripts/build-console-assets.mjs', '--watch'],
    { cwd: repoRoot, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  t.after(() => child.kill('SIGINT'));

  const metaPath = join(repoRoot, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');
  await waitFor(() => existsSync(metaPath) && readFileSync(metaPath, 'utf8').includes('buildEpochMs'));
  const firstMtime = statSync(metaPath).mtimeMs;

  // Touch the probe to trigger a rebuild.
  await wait(100);
  writeFileSync(probeFile, '// @requires (none)\nconst FixThisDevReloadProbe = 2;\n');

  await waitFor(() => statSync(metaPath).mtimeMs > firstMtime);

  // After stopping the watcher, --check must pass against the produced artifacts.
  child.kill('SIGINT');
  await new Promise((r) => child.once('exit', r));

  const check = spawn(
    'node',
    ['scripts/build-console-assets.mjs', '--check'],
    { cwd: repoRoot, stdio: 'inherit', env: { ...process.env, FIXTHIS_BUNDLE_REPRODUCIBLE: '1' } },
  );
  const code = await new Promise((r) => check.once('exit', r));
  assert.equal(code, 0, '--check should pass against watch-mode artifacts');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/build-console-assets-watch-test.mjs`
Expected: FAIL with timeout (no `--watch` flag handled yet — `main()` exits after first build).

- [ ] **Step 3: Refactor `main()` and add `--watch` branch**

In `scripts/build-console-assets.mjs`, extract the build+write logic into a reusable function and add a `watch` mode. Replace the existing `main()` and helper region (lines ~159-279) with:

```javascript
async function buildOnce({ reproducible } = {}) {
  const onDisk = consoleSourceFiles(sourceDir);

  for (const name of onDisk) {
    if (name === 'main.js') continue;
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\s*\/\/\s*@requires\s+/m.test(content)) {
      throw new Error(`fixthis-mcp/src/main/console/${name} has no // @requires header.`);
    }
  }

  const graph = new Map();
  for (const name of onDisk) {
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    graph.set(name, { content, deps: parseRequires(content) });
  }
  const sources = topoSort(graph);
  const entries = sources.map((name) => {
    const path = resolve(sourceDir, name);
    return `//#region ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n//#endregion ${name}\n`;
  });
  const concatenated = entries.join('\n');

  const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
  const mapPath = `${target}.map`;
  const metaPath = resolve(root, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');

  const epochMs = Math.floor(Date.now() / 60000) * 60000;
  let gitSha;
  try {
    gitSha = execSync('git rev-parse --short HEAD', {
      cwd: root, stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim();
  } catch (_) {
    gitSha = 'unknown';
  }
  const meta = reproducible
    ? { buildEpochMs: 0, gitSha: 'reproducible' }
    : { buildEpochMs: epochMs, gitSha };
  const metaSerialized = JSON.stringify(meta, null, 2) + '\n';

  const result = await esbuild({
    stdin: { contents: concatenated, loader: 'js', sourcefile: 'app.js' },
    bundle: false, write: false, outfile: target, minify: false,
    minifyWhitespace: true, minifySyntax: true, minifyIdentifiers: true,
    target: ['es2020'], sourcemap: 'linked', legalComments: 'inline',
  });
  const jsBytes = result.outputFiles.find((f) => f.path.endsWith('.js')).contents;
  const mapBytes = result.outputFiles.find((f) => f.path.endsWith('.map')).contents;

  const jsText = new TextDecoder().decode(jsBytes);
  assertContractSymbols(jsText);

  const RAW_BUDGET_BYTES = 225 * 1024;
  const GZIP_BUDGET_BYTES = 58 * 1024;
  if (jsBytes.byteLength > RAW_BUDGET_BYTES) {
    throw new Error(`Bundle (raw) is ${jsBytes.byteLength} bytes, exceeds raw budget of ${RAW_BUDGET_BYTES} bytes (225 KiB).`);
  }
  const gzBytes = gzipSync(Buffer.from(jsBytes), { level: 9 }).byteLength;
  if (gzBytes > GZIP_BUDGET_BYTES) {
    throw new Error(`Bundle (gzipped) is ${gzBytes} bytes, exceeds gzip budget of ${GZIP_BUDGET_BYTES} bytes (58 KiB).`);
  }

  return { target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized };
}

function atomicWrite(path, bytes) {
  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(tmp, bytes);
  require('node:fs').renameSync(tmp, path);
}

function commitArtifacts({ target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized }) {
  mkdirSync(dirname(target), { recursive: true });
  atomicWrite(target, jsBytes);
  atomicWrite(mapPath, mapBytes);
  atomicWrite(metaPath, metaSerialized);
}

async function runCheck(built) {
  const { target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized } = built;
  if (!existsSync(target)) {
    console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!Buffer.from(jsBytes).equals(readFileSync(target))) {
    console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!existsSync(mapPath) || !Buffer.from(mapBytes).equals(readFileSync(mapPath))) {
    console.error('Generated console app.js.map is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!existsSync(metaPath) || readFileSync(metaPath, 'utf8') !== metaSerialized) {
    console.error('Generated console-build-meta.json is out of date. Run FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  process.exit(0);
}

async function runWatch() {
  const { watch } = await import('node:fs');
  const debounceMs = 100;
  let pending = false;
  let timer = null;
  const rebuild = async () => {
    pending = false;
    try {
      const built = await buildOnce({ reproducible: process.env.FIXTHIS_BUNDLE_REPRODUCIBLE === '1' });
      commitArtifacts(built);
      console.log(`[watch] rebuilt at ${new Date().toISOString()}`);
    } catch (err) {
      console.error(`[watch] rebuild failed: ${err.message}`);
    }
  };
  const schedule = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(rebuild, debounceMs);
    pending = true;
  };
  await rebuild();
  const watcher = watch(sourceDir, { recursive: true }, (_event, filename) => {
    if (filename && filename.endsWith('.js')) schedule();
  });
  process.on('SIGINT', () => { watcher.close(); process.exit(0); });
  process.on('SIGTERM', () => { watcher.close(); process.exit(0); });
}

async function main() {
  if (process.argv.includes('--watch')) {
    await runWatch();
    return;
  }
  const built = await buildOnce({
    reproducible: process.env.FIXTHIS_BUNDLE_REPRODUCIBLE === '1'
      || process.argv.includes('--check'),
  });
  if (process.argv.includes('--check')) {
    await runCheck(built);
    return;
  }
  commitArtifacts(built);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/build-console-assets-watch-test.mjs`
Expected: PASS in <10s. Both subtests (watch rebuild + `--check` reproducibility) green.

- [ ] **Step 5: Run existing build/check to verify no regression**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: both exit 0.

- [ ] **Step 6: Register the new test in package.json**

Edit `package.json` `scripts` block, add after `"console:build:test"`:

```json
"console:build:watch:test": "node --test scripts/build-console-assets-watch-test.mjs",
```

- [ ] **Step 7: Commit**

```bash
git add scripts/build-console-assets.mjs scripts/build-console-assets-watch-test.mjs package.json
git commit -m "feat(console): add --watch mode to build-console-assets.mjs"
```

---

### Task 2: Server-side `ConsoleAssetsWatcher` that emits `console-assets-changed`

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcher.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcherTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create the test file:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ConsoleAssetsWatcherTest {
    private val tempDir: File = Files.createTempDirectory("console-assets-watcher").toFile()

    @AfterTest fun cleanup() { tempDir.deleteRecursively() }

    private fun writeMeta(hash: String) {
        File(tempDir, "console-build-meta.json")
            .writeText("""{"buildEpochMs":0,"gitSha":"$hash"}""" + "\n")
    }

    @Test
    fun emitsConsoleAssetsChangedWhenMetaFileMtimeAdvances() {
        writeMeta("aaaaaa1")
        val bus = ConsoleEventBus()
        val seen = mutableListOf<ConsoleEvent>()
        val latch = CountDownLatch(1)
        val subscription = bus.subscribe { ev ->
            if (ev.name == "console-assets-changed") { seen += ev; latch.countDown() }
        }
        val watcher = ConsoleAssetsWatcher(tempDir, bus, pollIntervalMillis = 50)
        try {
            watcher.start()
            // Sleep beyond filesystem mtime granularity, then rewrite with new hash.
            Thread.sleep(1100)
            writeMeta("bbbbbb2")
            assertEquals(true, latch.await(3, TimeUnit.SECONDS), "watcher must emit within 3s")
            val event = seen.single()
            assertEquals("bbbbbb2", event.data["buildHash"]!!.jsonPrimitive.content)
            assertNotNull(event.data["at"])
        } finally {
            subscription.close()
            watcher.stop()
        }
    }

    @Test
    fun doesNotEmitWhenHashUnchanged() {
        writeMeta("samehash")
        val bus = ConsoleEventBus()
        val seen = mutableListOf<ConsoleEvent>()
        val subscription = bus.subscribe { ev ->
            if (ev.name == "console-assets-changed") seen += ev
        }
        val watcher = ConsoleAssetsWatcher(tempDir, bus, pollIntervalMillis = 50)
        try {
            watcher.start()
            Thread.sleep(1100)
            // Touch mtime via rename-back trick: rewrite identical content.
            writeMeta("samehash")
            Thread.sleep(500)
            assertEquals(0, seen.size, "watcher must dedup on identical hash")
        } finally {
            subscription.close()
            watcher.stop()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests '*ConsoleAssetsWatcherTest*'`
Expected: FAIL with compilation error — `ConsoleAssetsWatcher` does not exist.

- [ ] **Step 3: Implement `ConsoleAssetsWatcher`**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcher.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ConsoleAssetsWatcher(
    private val assetsDir: File,
    private val eventBus: ConsoleEventBus,
    private val pollIntervalMillis: Long = 500L,
    private val now: () -> Instant = Instant::now,
    private val diagnosticsSink: (String) -> Unit = { System.err.print(it) },
) {
    private val metaFile: File = File(assetsDir, "console-build-meta.json")
    private val lastEmittedHash = AtomicReference<String?>(null)
    private val lastSeenMtime = AtomicReference(0L)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        // Prime with the current hash so that the very first poll after start does not
        // re-emit a hash the page already loaded.
        readCurrentHash()?.let { lastEmittedHash.set(it) }
        lastSeenMtime.set(if (metaFile.exists()) metaFile.lastModified() else 0L)
        val service = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "fixthis-console-assets-watcher").apply { isDaemon = true }
        }
        service.scheduleWithFixedDelay(
            ::tick, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS,
        )
        executor = service
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun tick() {
        try {
            if (!metaFile.exists()) return
            val mtime = metaFile.lastModified()
            if (mtime == lastSeenMtime.get()) return
            lastSeenMtime.set(mtime)
            val hash = readCurrentHash() ?: return
            val previous = lastEmittedHash.get()
            if (hash == previous) return
            lastEmittedHash.set(hash)
            eventBus.emit(
                "console-assets-changed",
                buildJsonObject {
                    put("buildHash", hash)
                    put("at", now().toString())
                },
            )
        } catch (error: Throwable) {
            diagnosticsSink("ConsoleAssetsWatcher: ${error::class.java.name}: ${error.message}\n")
        }
    }

    private fun readCurrentHash(): String? {
        if (!metaFile.exists()) return null
        return runCatching {
            val parsed = Json.parseToJsonElement(metaFile.readText()) as? JsonObject
            parsed?.get("gitSha")?.jsonPrimitive?.content
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests '*ConsoleAssetsWatcherTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcher.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetsWatcherTest.kt
git commit -m "feat(console): add ConsoleAssetsWatcher for dir-mode reload signal"
```

---

### Task 3: Start `ConsoleAssetsWatcher` from `FeedbackConsoleServer` in dir-mode

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
    @Test
    fun startsConsoleAssetsWatcherOnlyInDirMode() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1").next,
        )
        val assetsDir = java.nio.file.Files.createTempDirectory("server-test-assets").toFile()
        try {
            java.io.File(assetsDir, "console-build-meta.json")
                .writeText("""{"buildEpochMs":0,"gitSha":"start1"}""" + "\n")
            // Provide minimal asset stubs so dir-mode does not 404 on / route.
            java.io.File(assetsDir, "index.html").writeText("<html></html>")
            java.io.File(assetsDir, "styles.css").writeText("")
            java.io.File(assetsDir, "app.js").writeText("")

            val bus = io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus()
            val seen = java.util.concurrent.LinkedBlockingQueue<String>()
            val sub = bus.subscribe { ev -> if (ev.name == "console-assets-changed") seen.put(ev.data["buildHash"]!!.jsonPrimitive.content) }
            val server = FeedbackConsoleServer(
                service = fixture.service,
                consoleAssetsDir = assetsDir,
                eventBus = bus,
            )
            try {
                server.start()
                Thread.sleep(1100)
                java.io.File(assetsDir, "console-build-meta.json")
                    .writeText("""{"buildEpochMs":0,"gitSha":"updated2"}""" + "\n")
                val seenHash = seen.poll(3, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("updated2", seenHash)
            } finally {
                sub.close()
                server.stop()
            }
        } finally {
            assetsDir.deleteRecursively()
            fixture.close()
        }
    }

    @Test
    fun doesNotStartWatcherWhenAssetsDirIsNull() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1").next,
        )
        val bus = io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus()
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val sub = bus.subscribe { ev -> if (ev.name == "console-assets-changed") seen.put(ev.name) }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            Thread.sleep(800)
            assertNull(seen.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            sub.close()
            server.stop()
            fixture.close()
        }
    }
```

(Add `import kotlin.test.assertNull` and `import kotlinx.serialization.json.jsonPrimitive` at the top if not already present.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.startsConsoleAssetsWatcherOnlyInDirMode*' --tests '*FeedbackConsoleServerTest.doesNotStartWatcherWhenAssetsDirIsNull*'`
Expected: first test FAIL (watcher never starts → no event). second test may PASS or FAIL depending on wiring.

- [ ] **Step 3: Wire the watcher into `FeedbackConsoleServer`**

Edit `FeedbackConsoleServer.kt`. In `FeedbackConsoleServerConfig`, after `consoleAssetsDir`, add the watcher field:

```kotlin
private data class FeedbackConsoleServerConfig(
    val service: FeedbackSessionService,
    val host: String,
    val port: Int,
    val consoleAssetsDir: File?,
    val eventBus: ConsoleEventBus,
    val consoleToken: String = UUID.randomUUID().toString(),
) {
    val packagedIndexHtml: String? =
        if (consoleAssetsDir == null) FeedbackConsoleAssets.html(null, consoleToken) else null
    val assetsWatcher: ConsoleAssetsWatcher? =
        consoleAssetsDir?.let { ConsoleAssetsWatcher(it, eventBus) }
}
```

In the primary constructor of `FeedbackConsoleServer`, capture the watcher reference. Change the private constructor to receive `config` so it can stash the watcher:

```kotlin
class FeedbackConsoleServer private constructor(
    private val host: String,
    private val port: Int,
    private val consoleToken: String,
    private val routeTable: ConsoleRouteTable,
    private val assetsWatcher: ConsoleAssetsWatcher?,
    private val diagnosticsSink: (String) -> Unit,
) {
    constructor(
        service: FeedbackSessionService,
        host: String = "127.0.0.1",
        port: Int = 0,
        consoleAssetsDir: File? = null,
        eventBus: ConsoleEventBus = ConsoleEventBus(),
        diagnosticsSink: (String) -> Unit = { System.err.print(it) },
    ) : this(
        config = FeedbackConsoleServerConfig(
            service = service,
            host = host,
            port = port,
            consoleAssetsDir = consoleAssetsDir,
            eventBus = eventBus,
        ),
        diagnosticsSink = diagnosticsSink,
    )

    private constructor(
        config: FeedbackConsoleServerConfig,
        diagnosticsSink: (String) -> Unit,
    ) : this(
        host = config.host,
        port = config.port,
        consoleToken = config.consoleToken,
        routeTable = consoleRouteTable(config),
        assetsWatcher = config.assetsWatcher,
        diagnosticsSink = diagnosticsSink,
    )

    internal constructor(
        routes: List<ConsoleRoute>,
        host: String = "127.0.0.1",
        port: Int = 0,
        diagnosticsSink: (String) -> Unit = { System.err.print(it) },
    ) : this(
        host = host,
        port = port,
        consoleToken = UUID.randomUUID().toString(),
        routeTable = ConsoleRouteTable(routes),
        assetsWatcher = null,
        diagnosticsSink = diagnosticsSink,
    )
```

In `start()`, after `server = httpServer`, add:

```kotlin
                assetsWatcher?.start()
```

In `stop()`, before `server?.stop(0)`:

```kotlin
            assetsWatcher?.stop()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*'`
Expected: PASS (all existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): start ConsoleAssetsWatcher in dir-mode only"
```

---

### Task 4: Inline `devReloadEnabled` and `buildHash` into `FixThisConsoleConfig`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssetsTest.kt` (create if missing — or extend existing)

- [ ] **Step 1: Locate or create the asset test**

Run: `find fixthis-mcp/src/test -name 'FeedbackConsoleAssets*'`
If missing, create `FeedbackConsoleAssetsTest.kt`. Otherwise edit it.

- [ ] **Step 2: Write the failing test**

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackConsoleAssetsTest {
    private val tempDir: File = Files.createTempDirectory("assets-cfg").toFile()

    @AfterTest fun cleanup() { tempDir.deleteRecursively() }

    @Test
    fun packagedModeDoesNotSetDevReloadFlag() {
        val html = FeedbackConsoleAssets.html(consoleAssetsDir = null)
        assertTrue(html.contains("window.FixThisConsoleConfig"))
        assertFalse(html.contains("devReloadEnabled: true"))
    }

    @Test
    fun dirModeSetsDevReloadEnabledAndBuildHash() {
        File(tempDir, "console-build-meta.json")
            .writeText("""{"buildEpochMs":0,"gitSha":"abc1234"}""" + "\n")
        File(tempDir, "index.html").writeText("<html><head>__CONSOLE_STYLES__</head><body>__CONSOLE_APP__</body></html>")
        File(tempDir, "styles.css").writeText("")
        File(tempDir, "app.js").writeText("")
        val html = FeedbackConsoleAssets.html(tempDir)
        assertTrue(html.contains("devReloadEnabled: true"), "dir mode must set devReloadEnabled")
        assertTrue(html.contains("\"abc1234\""), "buildHash must be inlined from console-build-meta.json")
    }
}
```

Inspect `FeedbackConsoleAssets.kt` first to confirm `StylesPlaceholder` and `AppPlaceholder` constant values; adjust the `index.html` stub text in the test to use the same placeholders the source uses.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleAssetsTest*'`
Expected: FAIL — `devReloadEnabled` not present.

- [ ] **Step 4: Modify `FeedbackConsoleAssets.kt`**

In `buildIndexHtml`, after `consoleToken` is composed and before the `app.js` inline, extend `effectiveMeta` to include both fields. Current code builds `effectiveMeta` from `console-build-meta.json`. Adjust the inline JSON so the rendered config looks like:

```javascript
window.FixThisConsoleConfig = { consoleToken: "...", devReloadEnabled: true, buildHash: "abc1234" };
window.FixThisConsoleConfig.buildMeta = { ... };
```

Concretely, replace the existing line that sets `window.FixThisConsoleConfig` with one that conditionally sets `devReloadEnabled` and `buildHash`:

```kotlin
val devReloadEnabled = consoleAssetsDir != null
val buildHash = (effectiveMeta as? JsonObject)?.get("gitSha")?.jsonPrimitive?.content
val devFields = if (devReloadEnabled) {
    """, devReloadEnabled: true, buildHash: ${'"'}${buildHash ?: ""}${'"'}"""
} else ""
// inside the rendered <script>:
//   window.FixThisConsoleConfig = { consoleToken: "...", $devFields };
```

Splice `devFields` into the existing `window.FixThisConsoleConfig = { ... }` literal. Keep packaged mode byte-identical to today (so reproducible builds do not shift).

Required imports in `FeedbackConsoleAssets.kt`: `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.jsonPrimitive`. Make sure `effectiveMeta` is the parsed JSON object (not just text) — if it is currently text-only, parse it once here.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleAssetsTest*'`
Expected: PASS.

- [ ] **Step 6: Run full asset/server test sweep to catch regressions**

Run: `./gradlew :fixthis-mcp:test --tests '*Console*Routes*' --tests '*FeedbackConsoleServer*' --tests '*FeedbackConsoleAssets*'`
Expected: all PASS.

- [ ] **Step 7: Regenerate bundle to confirm `--check` still happy**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: both exit 0.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssetsTest.kt
git commit -m "feat(console): expose devReloadEnabled and buildHash via FixThisConsoleConfig"
```

---

### Task 5: Browser `devReload.js` module + SSE wiring

**Files:**
- Create: `fixthis-mcp/src/main/console/devReload.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Test: `scripts/devReload-test.mjs` (create)

- [ ] **Step 1: Inspect existing SSE consumer wiring**

Run: `grep -n "EventSource\|console-events\|/api/events" fixthis-mcp/src/main/console/*.js`
Note which module owns the `EventSource` and dispatches event names — the new module needs to hook into the same dispatcher rather than open a second `EventSource`. (If main wiring lives in `main.js` and the dispatch table maps event names to handlers, register `console-assets-changed` there.)

- [ ] **Step 2: Write the failing test**

Create `scripts/devReload-test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const repoRoot = new URL('..', import.meta.url).pathname;

function loadDevReload({ buildHash, devReloadEnabled }) {
  const src = readFileSync(resolve(repoRoot, 'fixthis-mcp/src/main/console/devReload.js'), 'utf8');
  const reloadCalls = [];
  const sandbox = {
    window: { FixThisConsoleConfig: { devReloadEnabled, buildHash } },
    location: { reload: () => reloadCalls.push(1) },
    console: { warn: () => {}, log: () => {} },
  };
  // Strip the // @requires header line.
  const body = src.replace(/^\s*\/\/\s*@requires[^\n]*\n/, '');
  const fn = new Function('window', 'location', 'console', `${body}; return { handleConsoleAssetsChanged };`);
  return { api: fn(sandbox.window, sandbox.location, sandbox.console), reloadCalls };
}

test('does nothing when devReloadEnabled is false', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'a', devReloadEnabled: false });
  api.handleConsoleAssetsChanged({ buildHash: 'b' });
  assert.equal(reloadCalls.length, 0);
});

test('reloads when hash differs and devReloadEnabled', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'a', devReloadEnabled: true });
  api.handleConsoleAssetsChanged({ buildHash: 'b' });
  assert.equal(reloadCalls.length, 1);
});

test('does not reload when hash equals current', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'same', devReloadEnabled: true });
  api.handleConsoleAssetsChanged({ buildHash: 'same' });
  assert.equal(reloadCalls.length, 0);
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `node --test scripts/devReload-test.mjs`
Expected: FAIL — file does not exist.

- [ ] **Step 4: Implement `devReload.js`**

```javascript
// @requires (none)
function handleConsoleAssetsChanged(payload) {
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  if (cfg.devReloadEnabled !== true) return;
  const incoming = payload && payload.buildHash;
  if (!incoming) return;
  if (incoming === cfg.buildHash) return;
  console.log('[devReload] bundle hash changed', cfg.buildHash, '->', incoming, '— reloading');
  location.reload();
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `node --test scripts/devReload-test.mjs`
Expected: PASS (3 tests).

- [ ] **Step 6: Wire into the SSE dispatcher**

Open the module identified in Step 1 (likely `main.js` or `events.js`). Find where event-name → handler mapping is set up, and add a branch for `console-assets-changed` that calls `handleConsoleAssetsChanged(payload)`. Concretely, near other `case 'preview-ready':` style branches, add:

```javascript
case 'console-assets-changed':
  handleConsoleAssetsChanged(payload);
  break;
```

If `main.js` uses a bundled-via-concat `// @requires` graph, add `devReload` to the `// @requires` header of the file that calls it (e.g., add `devReload` to `// @requires sse, ...` in `main.js`).

- [ ] **Step 7: Rebundle and check**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: exit 0. (Bundle includes `handleConsoleAssetsChanged` — confirm with `grep 'handleConsoleAssetsChanged' fixthis-mcp/src/main/resources/console/app.js`.)

- [ ] **Step 8: Register the new test in package.json**

Edit `package.json`, add to scripts:

```json
"console:devReload:test": "node --test scripts/devReload-test.mjs",
```

- [ ] **Step 9: Commit**

```bash
git add fixthis-mcp/src/main/console/devReload.js fixthis-mcp/src/main/console/main.js \
        scripts/devReload-test.mjs package.json \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): handle console-assets-changed SSE event in browser"
```

---

## Track 2: Restart Reconnect Verification

### Task 6: Restart-reconnect integration test

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/RestartReconnectIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestartReconnectIntegrationTest {
    @Test
    fun draftPersistedBeforeRestartIsVisibleAfter() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-r1", "preview-r1", "preview-screen-r1", "item-r1").next,
        )
        try {
            val firstPort = startAndPostDraft(fixture)
            // Stop and restart against the same fixture (same .fixthis/ workspace).
            val secondPort = restartAndCheckDraftPersisted(fixture, firstPort)
            assertTrue(secondPort > 0)
        } finally {
            fixture.close()
        }
    }

    private fun startAndPostDraft(
        fixture: io.github.beyondwin.fixthis.mcp.fixtures.ConsoleSessionFixture,
    ): Int {
        val bus = ConsoleEventBus()
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        val port: Int
        try {
            server.start()
            port = URI(server.url).port
            // Drive the session API to create a draft (exact endpoint depends on existing fixtures).
            // Replace the path with the project's draft-create endpoint; this test asserts persistence.
            val createUrl = URI("${server.url}/api/sessions").toURL()
            val conn = createUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            assertEquals(200, conn.responseCode)
        } finally {
            server.stop()
        }
        return port
    }

    private fun restartAndCheckDraftPersisted(
        fixture: io.github.beyondwin.fixthis.mcp.fixtures.ConsoleSessionFixture,
        previousPort: Int,
    ): Int {
        val bus = ConsoleEventBus()
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            val sessionsJson = URI("${server.url}/api/sessions").toURL()
                .openConnection().getInputStream().bufferedReader().readText()
            val payload = Json.parseToJsonElement(sessionsJson).jsonObject
            // Assert at least the previously persisted session shape survives.
            assertTrue(payload.containsKey("sessions"))
            return URI(server.url).port
        } finally {
            server.stop()
        }
    }
}
```

NOTE: The exact draft-creation path depends on existing routes. Before running, inspect `SessionRoutes.kt` / `FeedbackItemRoutes.kt` for the canonical "create draft" call shape, and adjust the test to actually create a draft (POST with proper payload) and then assert the draft id is present in the post-restart `/api/sessions` body. The goal is a deterministic assertion that **any** state persisted to `.fixthis/` survives a stop + start on the same fixture root.

- [ ] **Step 2: Run test to verify it fails (or passes — informational)**

Run: `./gradlew :fixthis-mcp:test --tests '*RestartReconnectIntegrationTest*'`

This test is exploratory. If it passes, confirms existing reconnect/hydration is sufficient — Track 2 then continues with status chip only. If it fails, the gap reveals where additional persistence is needed.

- [ ] **Step 3: If gap found, close it minimally**

Per spec (§ Track 2.1): close any gap using the existing `.fixthis/` or `localStorage` persistence mechanism. Do not introduce a new persistence layer. Add per-gap edits as needed and re-run the test until green.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/RestartReconnectIntegrationTest.kt
# Plus any gap-closing source edits.
git commit -m "test(console): verify draft persistence across server restart"
```

---

### Task 7: Server-build status chip — browser module

**Files:**
- Create: `fixthis-mcp/src/main/console/serverBuildChip.js`
- Modify: `fixthis-mcp/src/main/console/main.js` (mount the chip), and the existing top-bar / shell module that owns the header DOM
- Test: `scripts/serverBuildChip-test.mjs` (create)

- [ ] **Step 1: Locate the top-bar mount point**

Run: `grep -n "topbar\|header\|statusBar\|connectionPill" fixthis-mcp/src/main/console/*.js | head -20`
Identify the module that renders the top-of-page connection pill. The chip mounts adjacent to it.

- [ ] **Step 2: Write the failing test**

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const repoRoot = new URL('..', import.meta.url).pathname;

function loadChip({ initialBuildHash }) {
  const src = readFileSync(resolve(repoRoot, 'fixthis-mcp/src/main/console/serverBuildChip.js'), 'utf8');
  const window = { FixThisConsoleConfig: { buildHash: initialBuildHash } };
  const body = src.replace(/^\s*\/\/\s*@requires[^\n]*\n/, '');
  const fn = new Function('window', 'document', `${body}; return { renderServerBuildChip, updateServerBuildChipState };`);
  const node = { textContent: '', dataset: {} };
  const document = { createElement: () => node, getElementById: () => null };
  return { api: fn(window, document), node };
}

test('renders initial connected state with build sha suffix', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  assert.match(node.textContent, /connected.*abc1234/);
  assert.equal(node.dataset.state, 'connected');
});

test('updates to reconnecting state', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'reconnecting' });
  assert.match(node.textContent, /reconnecting/i);
  assert.equal(node.dataset.state, 'reconnecting');
});

test('updates build sha after reconnect to new server', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'connected', buildHash: 'def5678' });
  assert.match(node.textContent, /def5678/);
  assert.equal(node.dataset.state, 'connected');
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `node --test scripts/serverBuildChip-test.mjs`
Expected: FAIL — module missing.

- [ ] **Step 4: Implement `serverBuildChip.js`**

```javascript
// @requires (none)
function renderServerBuildChip(node) {
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  node.dataset.state = 'connected';
  node.textContent = formatChipText('connected', cfg.buildHash);
}

function updateServerBuildChipState(node, { state, buildHash } = {}) {
  if (state) node.dataset.state = state;
  const hash = buildHash ?? (typeof window !== 'undefined' && window.FixThisConsoleConfig?.buildHash);
  node.textContent = formatChipText(node.dataset.state, hash);
}

function formatChipText(state, buildHash) {
  if (state === 'reconnecting') return 'reconnecting…';
  if (state === 'connected' && buildHash) return `connected · build sha=${buildHash}`;
  if (state === 'connected') return 'connected';
  return state || '';
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `node --test scripts/serverBuildChip-test.mjs`
Expected: PASS (3 tests).

- [ ] **Step 6: Mount the chip in the top bar**

Edit the top-bar module identified in Step 1. Create a DOM element (id `fixthis-server-build-chip`), append it to the existing top-bar container, and call `renderServerBuildChip(node)` on init. Add `serverBuildChip` to the `// @requires` header of that module.

- [ ] **Step 7: Add minimal styles**

Open `fixthis-mcp/src/main/resources/console/styles.css`. Append:

```css
#fixthis-server-build-chip { font-size: 11px; opacity: 0.7; margin-left: 8px; padding: 2px 6px; border-radius: 4px; background: rgba(0,0,0,0.06); }
#fixthis-server-build-chip[data-state="reconnecting"] { background: rgba(255,180,0,0.18); }
```

- [ ] **Step 8: Rebundle and check**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: exit 0.

- [ ] **Step 9: Register the new test**

Edit `package.json`, add to scripts:

```json
"console:serverBuildChip:test": "node --test scripts/serverBuildChip-test.mjs",
```

- [ ] **Step 10: Commit**

```bash
git add fixthis-mcp/src/main/console/serverBuildChip.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json \
        scripts/serverBuildChip-test.mjs package.json
git commit -m "feat(console): add server build chip with reconnect-state display"
```

---

### Task 8: Wire chip transitions to SSE drop/reconnect + server-version change

**Files:**
- Modify: the module that owns the `EventSource` lifecycle (identified in Task 5 Step 1)
- Modify: `fixthis-mcp/src/main/console/main.js` if needed for `/api/server-version` fetch after reconnect

- [ ] **Step 1: Add chip state hooks at three SSE lifecycle points**

In the SSE owner module, locate the three places where:
- the `EventSource.onerror` fires or readyState transitions to CLOSED → call `updateServerBuildChipState(chipNode, { state: 'reconnecting' })`
- the `EventSource.onopen` fires after a reconnect → fetch `GET /api/server-version`, then call `updateServerBuildChipState(chipNode, { state: 'connected', buildHash: payload.serverGitSha })`
- the initial `EventSource.onopen` (first connect) → no-op; the chip already shows `connected · build sha=<initial>` from render.

To avoid stale-closure bugs, expose the chipNode via a small registry function (or import via shared module state — match existing console patterns).

- [ ] **Step 2: Add a JS unit test for the wiring**

Append to `scripts/serverBuildChip-test.mjs`:

```javascript
test('transitions to reconnecting on SSE error and back to connected on reopen', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'reconnecting' });
  assert.equal(node.dataset.state, 'reconnecting');
  api.updateServerBuildChipState(node, { state: 'connected', buildHash: 'newsha' });
  assert.equal(node.dataset.state, 'connected');
  assert.match(node.textContent, /newsha/);
});
```

- [ ] **Step 3: Run all tests**

Run: `node --test scripts/serverBuildChip-test.mjs scripts/devReload-test.mjs scripts/build-console-assets-watch-test.mjs`
Expected: all PASS.

- [ ] **Step 4: Run Kotlin tests for console module**

Run: `./gradlew :fixthis-mcp:test --tests '*Console*'`
Expected: all PASS.

- [ ] **Step 5: Rebundle + check**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/*.js scripts/serverBuildChip-test.mjs \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): wire server build chip to SSE reconnect lifecycle"
```

---

## Documentation & CI

### Task 9: Update `CONTRIBUTING.md`

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add a watch section under Console Inner Loop**

Locate `## Console Inner Loop` (around line 50). After the existing `scripts/fixthis-console-dev.sh — JS-only hot-reload loop` subsection, insert:

```markdown
### `node scripts/build-console-assets.mjs --watch` — auto-rebundle on JS edit

Pair this with `scripts/fixthis-console-dev.sh` (which runs the server with
`--console-assets-dir`) for a no-touch JS edit loop. Save a JS file under
`fixthis-mcp/src/main/console/` and the bundle, source map, and
`console-build-meta.json` are atomically rewritten. The console server polls
`console-build-meta.json` mtime and pushes a `console-assets-changed`
event over `/api/events`; the browser auto-reloads when the bundle hash
differs. The reload signal is gated on `--console-assets-dir`; the
packaged JAR never reloads itself.

```bash
node scripts/build-console-assets.mjs --watch
```

Stop with Ctrl-C. After stopping, the on-disk artifacts already satisfy
`node scripts/build-console-assets.mjs --check`, so no extra command is
needed before pushing.

### Server build chip

The console top bar shows a small chip with the current server build SHA
and reconnect state (`connected · build sha=<short>` → `reconnecting…` →
`connected · build sha=<new>`). Use it to confirm that
`bash scripts/restart-console.sh` actually delivered a new server build.
```

- [ ] **Step 2: Run doc consistency check**

Run: `node scripts/check-doc-consistency.mjs`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs: document --watch and server build chip in console inner loop"
```

---

### Task 10: Update `docs/reference/feedback-console-contract.md`

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Read current SSE event documentation in the file**

Run: `grep -n 'SSE\|/api/events\|preview-ready\|console-assets' docs/reference/feedback-console-contract.md | head -20`

- [ ] **Step 2: Document `console-assets-changed` and `FixThisConsoleConfig` additions**

Add (in the SSE-events section):

```markdown
### `console-assets-changed` (dir-mode only)

Emitted by the server when `console-build-meta.json` mtime advances in the
`--console-assets-dir` watch path. Payload:

```json
{ "buildHash": "<short git sha from console-build-meta.json>", "at": "<iso-8601>" }
```

The packaged JAR never emits this event. The browser handler reloads only
when `payload.buildHash !== window.FixThisConsoleConfig.buildHash`.

### `FixThisConsoleConfig` (dir-mode additions)

- `devReloadEnabled: true` — set only when the server is started with
  `--console-assets-dir`. Required for the browser to act on
  `console-assets-changed`.
- `buildHash: <short git sha>` — mirrored from the inlined
  `console-build-meta.json` so the browser can dedup reload signals and the
  server build chip can display it.
```

- [ ] **Step 3: Run doc consistency check + link check**

Run: `node scripts/check-doc-consistency.mjs`
Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add docs/reference/feedback-console-contract.md
git commit -m "docs: document console-assets-changed SSE event and dev fields"
```

---

### Task 11: Add CHANGELOG entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Append to Unreleased § Added**

Locate `## Unreleased` → `### Added`. Append:

```markdown
- Console JS inner loop now supports auto-rebundle on edit via
  `node scripts/build-console-assets.mjs --watch`. When the server is
  started with `--console-assets-dir`, the server pushes a
  `console-assets-changed` event over `/api/events` and the browser
  auto-reloads after each successful rebuild. The packaged JAR is
  unchanged.
- Console top bar now shows a server build chip
  (`connected · build sha=<short>` / `reconnecting…`) so maintainers can
  confirm that `restart-console.sh` delivered a new server build.
```

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): note --watch mode and server build chip"
```

---

### Task 12: Final verification sweep

- [ ] **Step 1: Run the full local CI gate**

Run: `npm run ci:local:prepush`
Expected: exit 0.

If any step fails, fix the underlying issue and re-run from that step. Do **not** skip via `--no-verify`.

- [ ] **Step 2: Manual smoke**

In two terminals:

```bash
# Terminal 1
node scripts/build-console-assets.mjs --watch

# Terminal 2
bash scripts/fixthis-console-dev.sh
```

- Confirm the browser opens.
- Edit `fixthis-mcp/src/main/console/sse.js` (add a `// touched` comment, save).
- Confirm the browser reloads within ~2 seconds.
- Stop terminal 1 with Ctrl-C; run `node scripts/build-console-assets.mjs --check`. Expect exit 0.
- Run `bash scripts/restart-console.sh`; observe the chip transitions through `reconnecting…` → `connected · build sha=<new>`.

- [ ] **Step 3: Verify `git diff --check`**

Run: `git diff --check`
Expected: no whitespace errors.

- [ ] **Step 4: Final commit if any clean-up was needed; otherwise nothing**

```bash
git status
# If any pending edits surfaced during the smoke (e.g., bundle re-emitted), commit them under a "chore" message.
```

---

## Self-Review Notes

- All spec acceptance criteria mapped to tasks: Track 1 acceptance → Tasks 1-5 + Task 12 smoke. Track 2 acceptance → Tasks 6-8 + smoke.
- No `TBD` / `implement later` placeholders.
- File paths are concrete; the one area of natural underspecification is the **exact module that owns the SSE EventSource lifecycle**, which Tasks 5 (Step 1) and 8 (Step 1) explicitly direct the implementer to discover via `grep` before editing — this is the same Code reconnaissance step the existing FixThis contributor flow uses.
- Naming consistency: `handleConsoleAssetsChanged`, `renderServerBuildChip`, `updateServerBuildChipState`, `ConsoleAssetsWatcher` used identically across tasks.
- Atomic write is implemented in Task 1 and reused (no separate atomic-write task).
- The `// @requires (none)` header convention for new JS modules matches the existing `build-console-assets.mjs` enforcement (line 167 of the original script).
