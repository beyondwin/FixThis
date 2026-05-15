# Deep Review Cleanup Implementation Plan (Items 1–5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 심층 리뷰에서 검증된 5개 결함(콘솔 자산 git fork·예외 무음·테스트 sleep 폴링·SPA teardown 슬롯·MCP in-flight race)을 한 사이클에 닫는다.

**Architecture:** 기존 로컬-퍼스트 구조와 호환성 계약을 유지. JVM 측은 `:fixthis-mcp`의 console 서브패키지에 한정해 캐시·로깅 보강. CLI 테스트는 fixture API에 latch만 추가. 콘솔 JS는 전역 리스너를 가진 모듈에 한해 `dispose()`를 도입하고 부트 컨트롤러가 호출. MCP 동시성 모델은 그대로 두되 헬퍼 추출 + race 테스트만 추가.

**Tech Stack:** Kotlin/JVM 21, kotlinx.coroutines, JUnit/kotlin.test, vanilla browser JavaScript, Node 20+ built-in test runner.

**Related spec:** [`../specs/2026-05-15-deep-review-cleanup-detailed-spec.md`](../specs/2026-05-15-deep-review-cleanup-detailed-spec.md)

---

## File Structure

Modify these Kotlin files:

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt` — Process lifecycle 정리, stderr drain, 모듈 수명 캐시, 예외 stderr 로그.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` — 광역 catch에서 diagnostics sink 호출(주입 가능).
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/McpServer.kt` — in-flight 등록 헬퍼 `trackRequest(...)` 추출.

Add these Kotlin files:

- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssetsTest.kt` — 캐시·git-missing·stderr drain 단위 테스트.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerErrorLoggingTest.kt` — diagnostics sink 단위 테스트.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpServerInFlightRaceTest.kt` — UNDISPATCHED launch가 register 전에 완료되는 경로 검증.

Modify these CLI test files:

- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt` — `Thread.sleep` 폴링 → `CountDownLatch.await`.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/.../<fixture>` — fixture가 마커 생성 시 latch.countDown() 호출(파일은 task에서 확정).

Modify these console JS files:

- `fixthis-mcp/src/main/console/history.js` — `init(...)` → `{ dispose }` 반환.
- `fixthis-mcp/src/main/console/main.js` — 부트 시 dispose 슬롯 등록.
- `fixthis-mcp/src/main/resources/console/app.js`, `app.js.map`, `console-build-meta.json` — `node scripts/build-console-assets.mjs`로만 재생성. 수기 편집 금지.

Add these console JS test files:

- `scripts/consoleDispose-test.mjs` — 등록·해제 전후 리스너 카운트 동일성 검증.

## Conventions

- 영속화된 MCP JSON 필드명 변경 금지: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `sourceCandidates`.
- `.fixthis/` 커밋 금지.
- `fixthis-mcp/src/main/resources/console/app.js`는 수기 편집 금지 — 항상 빌드 스크립트로 재생성.
- 각 Task 종료 후 해당 모듈 단위 테스트 → Task 5 종료 후 Final Verification matrix 실행.
- 각 Task 단위로 커밋한다. 커밋 메시지는 `<scope>: <one-liner>` 형식, 기존 컨벤션 따름.

---

### Task 1: 콘솔 자산 git fork 정리 + 캐시

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Add test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssetsTest.kt`

- [ ] **Step 1.1: 실패하는 테스트 추가**

`FeedbackConsoleAssetsTest`에 다음 3개 케이스를 추가한다:

```kotlin
@Test
fun reproducibleMetaResolvesRuntimeShaOnlyOnce() {
    val resolver = CountingShaResolver(value = "abcdef1")
    val assets = FeedbackConsoleAssets(shaResolver = resolver, clock = { 1000L })
    repeat(5) { assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "") }
    assertEquals(1, resolver.calls)
}

@Test
fun reproducibleMetaFallsBackWhenGitMissing() {
    val errors = StringBuilder()
    val assets = FeedbackConsoleAssets(
        shaResolver = ThrowingShaResolver(IOException("git not found")),
        clock = { 1000L },
        errSink = { errors.appendLine(it) },
    )
    val html = assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "")
    assertContains(html, "\"gitSha\":\"unknown\"")
    assertContains(errors.toString(), "git not found")
}

@Test
fun shaResolverRejectsNonHexOutput() {
    val resolver = StaticShaResolver(value = "fatal: not a git repo")
    val assets = FeedbackConsoleAssets(shaResolver = resolver, clock = { 1000L })
    val html = assets.buildIndexHtml(consoleAssetsDir = null, consoleToken = "")
    assertContains(html, "\"gitSha\":\"unknown\"")
}
```

테스트 실행 → 컴파일 실패 확인.

- [ ] **Step 1.2: 구현**

`FeedbackConsoleAssets.kt`를 다음과 같이 변경:

1. 클래스/오브젝트 시그니처에 다음을 주입 가능하게 노출 (default는 현 동작 유지):
   - `shaResolver: () -> String?` — 기본 구현은 `ProcessBuilder(... "git" "rev-parse" "--short" "HEAD" ...).redirectErrorStream(true).start()` + `waitFor(2, SECONDS)` + 결과 정규화.
   - `clock: () -> Long = System::currentTimeMillis`.
   - `errSink: (String) -> Unit = { System.err.println(it) }`.
2. 기본 `shaResolver`:
   - `Process`를 try/finally로 감싸 `destroy()` 호출.
   - `waitFor` timeout 시 false 반환 → `destroy()` 후 null.
   - stdout 텍스트를 `^[0-9a-f]{7,40}$` 정규식으로 검증; 미매치 시 null.
   - 예외 catch → `errSink("FeedbackConsoleAssets: gitSha resolution failed: ${e.message}")` 후 null.
3. `effectiveBuildMetaJson()`이 결과를 `@Volatile var cachedRuntimeSha: String?`에 캐시. 캐시 키는 `("reproducible", cachedRuntimeSha)`로 단순.

- [ ] **Step 1.3: 검증**

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackConsoleAssetsTest*"
```

3개 케이스 모두 통과.

- [ ] **Step 1.4: 커밋**

`fix(console): cache git SHA resolution and reap subprocess`

---

### Task 2: 콘솔 자산/서버 예외 로깅

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Add test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerErrorLoggingTest.kt`

- [ ] **Step 2.1: 실패하는 테스트 추가**

```kotlin
@Test
fun handlerExceptionsAreLoggedToDiagnosticsSink() {
    val sink = StringBuilder()
    val server = FeedbackConsoleServer(
        routes = listOf(ThrowingRoute(IllegalStateException("boom"))),
        diagnosticsSink = { sink.appendLine(it) },
    )
    val exchange = FakeHttpExchange(method = "GET", path = "/api/anything")
    server.dispatch(exchange)
    assertEquals(500, exchange.statusCode)
    assertContains(sink.toString(), "IllegalStateException")
    assertContains(sink.toString(), "boom")
}
```

(라우트 디스패치를 노출하는 `dispatch(exchange)` 내부 메서드가 없으면 visibility를 `internal`로 열어 테스트 진입점 마련.)

- [ ] **Step 2.2: 구현**

1. `FeedbackConsoleServer`의 `catch (error: Throwable)` 블록(현 위치 ≈ line 84)에서 응답 전송 직전에 `diagnosticsSink("FeedbackConsoleServer: ${error::class.java.name}: ${error.message}\n${error.stackTraceToString()}")` 호출.
2. 생성자에 `diagnosticsSink: (String) -> Unit = { System.err.print(it) }` 주입(default 동작 유지).
3. 응답 본문 구조는 변경하지 않는다(외부 계약 유지).

- [ ] **Step 2.3: 검증**

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackConsoleServerErrorLoggingTest*"
```

- [ ] **Step 2.4: 커밋**

`fix(console): log handler exceptions to diagnostics sink`

---

### Task 3: AdbTest sleep 폴링 제거

**Files:**
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt`
- Modify: (Task 시작 시 확정) fixture가 마커를 생성하는 위치.

- [ ] **Step 3.1: 현 코드 위치 확인**

```bash
rg -n "Thread.sleep" fixthis-cli/src/test
```

`AdbTest.kt:245` 부근의 `while (!marker.exists()) { Thread.sleep(10) }` 패턴을 식별. 마커가 생성되는 fixture가 같은 파일 안인지 외부인지 확인.

- [ ] **Step 3.2: 구현**

1. 마커 생성 직후 `latch.countDown()`을 호출하도록 fixture를 변경. fixture가 외부 프로세스라 latch 주입 불가능하면 `WatchService`로 디렉터리 감시.
2. 호출 측은 `assertTrue(latch.await(5, TimeUnit.SECONDS), "marker did not arrive")`.
3. `Thread.sleep` 라인 삭제.

- [ ] **Step 3.3: 검증**

```bash
./gradlew :fixthis-cli:test --tests "*AdbTest*"
rg -n "Thread.sleep" fixthis-cli/src/test  # 해당 라인 0회
```

- [ ] **Step 3.4: 커밋**

`test(cli): replace sleep polling with latch in AdbTest`

---

### Task 4: 콘솔 SPA dispose 컨벤션

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Add test: `scripts/consoleDispose-test.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js` (빌드 스크립트로만)

- [ ] **Step 4.1: 실패하는 테스트 추가**

`scripts/consoleDispose-test.mjs`:

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { initHistory } from '../fixthis-mcp/src/main/console/history.js';

test('history dispose removes every listener it added', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>');
  const counts = new Map();
  const origAdd = dom.window.document.addEventListener.bind(dom.window.document);
  const origRemove = dom.window.document.removeEventListener.bind(dom.window.document);
  dom.window.document.addEventListener = (type, fn, ...rest) => {
    counts.set(type, (counts.get(type) ?? 0) + 1);
    return origAdd(type, fn, ...rest);
  };
  dom.window.document.removeEventListener = (type, fn, ...rest) => {
    counts.set(type, (counts.get(type) ?? 0) - 1);
    return origRemove(type, fn, ...rest);
  };
  const { dispose } = initHistory({ document: dom.window.document });
  dispose();
  for (const [, n] of counts) assert.equal(n, 0);
});
```

(`jsdom`이 dev dep로 없으면 lightweight fake EventTarget로 대체 — 자세한 선택은 Task 4 시작 시 결정.)

- [ ] **Step 4.2: 구현**

1. `history.js`를 `export function initHistory({ document, ... }) { const handlers = []; ...; handlers.push([target, type, fn]); return { dispose() { for (const [t, type, fn] of handlers) t.removeEventListener(type, fn); } }`로 리팩터.
2. `main.js`(또는 부트 컨트롤러)가 `initHistory(...)`의 결과를 `disposers` 배열에 모으고, `window.__fixthisDispose = () => disposers.forEach(d => d.dispose())`로 디버그 훅 노출.
3. 전역 리스너를 가진 다른 모듈이 있으면 같은 패턴으로 컨버전 — 단, 본 Task는 `history.js` + 부트 슬롯만 필수. 추가 모듈은 follow-up.

- [ ] **Step 4.3: 번들 재생성 + 검증**

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --test scripts/consoleDispose-test.mjs
npm run console:test:all
```

- [ ] **Step 4.4: 커밋**

`refactor(console): add dispose convention for history module`

---

### Task 5: McpServer in-flight 등록 헬퍼 + race 테스트

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/McpServer.kt`
- Add test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpServerInFlightRaceTest.kt`

- [ ] **Step 5.1: 실패하는 race 테스트 추가**

```kotlin
@Test
fun trackedRequestRegistersEvenWhenJobCompletesImmediately() = runBlocking {
    val registry = InFlightRegistry()
    val protocol = McpProtocol(
        handler = { _ -> "" /* completes immediately, no response written */ },
    )
    val server = McpServer(protocol)
    val message = McpRequest(idKey = "id-1", method = "tools/call", raw = JsonObject(emptyMap()))
    server.trackRequestForTest(message, registry) { /* writer noop */ }
    // After immediate completion, registry must not retain the entry.
    assertNull(registry.snapshot()[message.idKey])
}

@Test
fun cancellingTrackedRequestRemovesFromRegistry() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val protocol = McpProtocol(handler = { _ -> gate.await(); "" })
    val server = McpServer(protocol)
    val registry = InFlightRegistry()
    val message = McpRequest(idKey = "id-2", method = "tools/call", raw = JsonObject(emptyMap()))
    val job = launch { server.trackRequestForTest(message, registry) { /* noop */ } }
    while (registry.snapshot()[message.idKey] == null) yield()
    registry.remove(message.idKey)?.job?.cancel()
    gate.complete(Unit)
    job.join()
    assertNull(registry.snapshot()[message.idKey])
}
```

`InFlightRegistry`에 `snapshot()` 또는 동등한 read-only view가 없다면 `internal fun snapshot(): Map<String, InFlightRequest>`를 추가한다.

- [ ] **Step 5.2: 구현**

1. `McpServer.run(...)` 안의 `is McpRequest -> { ... }` 블록을 다음 헬퍼로 추출:

   ```kotlin
   internal suspend fun trackRequest(
       message: McpRequest,
       registry: InFlightRegistry,
       writeResponse: suspend (String) -> Unit,
   ) = coroutineScope {
       val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
           try {
               protocol.handleRequest(message)?.let { writeResponse(it) }
           } catch (error: CancellationException) {
               diagnostics.writeDiagnostic("Cancelled MCP request ${message.idKey}")
           } finally {
               registry.remove(message.idKey)
           }
       }
       if (job.isActive) {
           registry.register(message.idKey, InFlightRequest(message.method, job))
           if (!job.isActive) registry.remove(message.idKey)
       }
   }
   ```

2. 호출 측은 `trackRequest(message, registry, ::writeResponse)` 한 줄.
3. 테스트 진입점으로 `internal suspend fun trackRequestForTest(...)`를 노출.

- [ ] **Step 5.3: 검증**

```bash
./gradlew :fixthis-mcp:test --tests "*McpServerInFlightRaceTest*"
./gradlew :fixthis-mcp:test --tests "*InFlightRegistryTest*"
./gradlew :fixthis-mcp:test --tests "*McpServerTest*"
```

기존 + 신규 테스트 모두 통과.

- [ ] **Step 5.4: 커밋**

`refactor(mcp): extract in-flight tracking helper with race test`

---

## Final Verification

모든 Task 종료 후 다음을 순서대로 실행:

```bash
./gradlew :fixthis-mcp:test :fixthis-cli:test \
  :fixthis-compose-core:test :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test --continue
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
npm run console:test:all
git diff --check
```

전부 통과해야 PR로 진행한다.

## Rollback Strategy

- Task별 단일 커밋이라 `git revert <sha>` 한 번으로 개별 롤백 가능.
- Task 1의 캐시 회귀 우려가 있으면 `shaResolver` 주입을 항상 caller가 새로 만드는 식으로 한 줄 변경해 캐시 비활성.
- Task 4가 `app.js` 번들을 건드리므로 롤백 시 반드시 `node scripts/build-console-assets.mjs`를 재실행해 `app.js`와 `console-build-meta.json`이 정합되도록 한다.

## Open Questions

- **Q1**: `FeedbackConsoleAssets`의 `errSink` default를 `System.err` 대신 `ConsoleLogger`(존재한다면)로 흘려야 하나? — 코드베이스 전체에 단일 로깅 facade가 없어 Task 1은 stderr로 가되, 향후 facade 도입 시 한 줄 교체.
- **Q2**: Task 4에서 `jsdom`을 dev dep로 추가할지, 자체 fake EventTarget을 사용할지 — Task 시작 시 `package.json`의 기존 dev deps 보고 결정. 가벼운 fake가 가능하면 그 쪽이 의존성 면에서 이득.
- **Q3**: Task 5의 race 테스트가 의도한 race를 실제로 자극하는지 추가 검증이 필요하면 `repeat(1000) { ... }` stress 모드를 옵션으로 둘 수 있다. 기본 1회로 시작.
