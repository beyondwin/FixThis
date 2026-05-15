# Stale Binary Detection — Implementation Details

**Date:** 2026-05-10
**Status:** Implementation reference (companion to `plans/2026-05-10-stale-binary-detection.md`)
**Primary modules:**
- `scripts/build-console-assets.mjs`
- `fixthis-mcp/build.gradle.kts`, `fixthis-compose-sidekick/build.gradle.kts`
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfo.kt` (generated)
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/BuildInfo.kt` (generated)
- `fixthis-mcp/src/main/console/staleness.js` (new)
- `fixthis-mcp/src/main/console/main.js`, `connection.js` (touched)
- `fixthis-mcp/src/main/resources/console/index.html`, `styles.css`, `app.js`
- `fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt`, `BridgeServer.kt`
- `scripts/restart-console.sh` (new)
- 테스트 — 인접 패턴 차용

이 문서는 plan 의 deep technical companion 이다. 각 변경 라인이 왜 그 자리에 있는지, 왜 그 외에는 손대지 않는지를 명시한다. 본 spec/impl details/plan 셋트를 처음 보는 사람이 plan 만 보고 막히면 이 문서를 읽는다.

---

## 1. 콘솔 자산 빌드 시스템 — staleness 진입점

브라우저 콘솔은 `fixthis-mcp/src/main/console/` 의 ES-style 모듈 집합으로 작성되고, 배포 산출물은 단일 파일 `fixthis-mcp/src/main/resources/console/app.js` 다. 번들러 없이 `scripts/build-console-assets.mjs` 가 `sources` 배열 순서로 모듈 본문을 단순 concat 한다.

### 현재 모듈 순서

```
1. state.js
2. api.js
3. connection.js
4. availability.js
5. devices.js
6. preview.js
7. annotations.js
8. history.js
9. prompt.js
10. rendering.js
11. sessions-polling.js
12. shortcuts.js
13. main.js
```

`FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules` (`fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt:480-507` 부근) 가 byte-equal 비교를 수행한다.

### 본 변경의 진입점 (Phase 2)

build-console-assets.mjs 에 두 가지 변경:

#### (a) Build header 임베드 — `state.js` emission 직후

`state.js` 의 본문이 emit 된 후, 다음 두 줄을 추가 inject 한다:

```javascript
            const ConsoleBuildEpochMs = <minute-rounded epoch>;
            const ConsoleBuildGitSha = '<short sha>';
```

**왜 state.js 직후인가:**
- 모든 다른 모듈이 이 두 const 를 참조 가능해야 함 — 즉 가장 먼저 정의돼야 한다.
- state.js 가 이미 모든 module-scope let/const 의 정의 자리이므로 (`pendingMutationCount`, `consecutivePollFailures`, `state.connection` 등), 그 직후가 가장 자연스럽다.
- `api.js` 는 fetch wrapper 만 정의하므로 그 위에 둘 수 없고, staleness.js (신규) 가 build header 를 참조하므로 staleness.js 보다 앞에 와야 한다.

#### (b) 모듈 순서에 `staleness.js` 삽입 — `state.js` 다음, `api.js` 전

```
1. state.js
1.5 (build header)
1.6 staleness.js   ← 추가
2. api.js
3. connection.js
...
```

**왜 connection.js 보다 앞인가:**
- Phase 3 에서 `connection.js` 의 `applyConnectionStatus` 가 `checkProtocolCompat`, `checkSidekickBuildEpoch` 를 호출한다.
- JS 의 함수 declaration 은 hoisting 으로 같은 scope 안에서는 순서 무관하지만, IIFE 로 concat 된 본 번들에서는 연속된 코드 블록이고 모든 함수가 module-scope hoisted 되므로 사실 연도 무관하긴 하다. 그럼에도 reader 의 정신 모델을 위해 dependency 가 명확한 순서를 유지한다.

#### (c) byte-equality 테스트 갱신

`generatedConsoleAppMatchesConsoleSourceModules` 도 변경된 sources 배열과 build header 를 반영해야 한다. 두 가지 접근:

- **A (권장)**: 테스트가 build header 부분을 regex 로 검증 (epoch 이 매번 다르므로). 나머지 모듈은 기존 byte-equal 그대로.
- **B**: 테스트에서 build header 를 mock (고정 epoch 으로 강제 generate) — 더 엄격하지만 build script 에 `--for-test` 플래그 같은 hook 필요.

본 plan 은 A 를 채택. Task 3 의 Step 4 가 그것이다.

### `--console-assets-dir` dev mode

런타임에 `--console-assets-dir <PATH>` 를 주면 서버가 그 디렉토리에서 직접 HTML/CSS/JS 를 읽어 `Cache-Control: no-store` 로 응답한다 (`FeedbackConsoleServerTest.servesConsoleAssetsFromConfiguredDirectoryWithoutCaching` 참고). 이 모드에서는 source 만 고치고 브라우저 hard reload 로 즉시 반영된다.

본 plan 의 Phase 2 에서 build header 가 들어가는 이상, `app.js` 는 매번 build script 를 통과해야 한다. dev mode 라도 `node scripts/build-console-assets.mjs` 를 돌리지 않으면 staleness.js 의 `ConsoleBuildEpochMs` 가 갱신 안 된다 — 이 점은 의도된 trade-off.

---

## 2. `/api/server-version` 와이어 포맷

### Endpoint

```
GET /api/server-version
```

### Response

```json
{
  "serverBuildEpochMs": 1715353200000,
  "serverGitSha": "1040988",
  "bridgeProtocolVersion": 7
}
```

### Headers

```
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
```

`no-store` 는 의도적이다 — 콘솔이 보고 있는 서버 빌드를 캐시로 인해 잘못 추정하면 staleness 감지 자체가 무효해진다.

### 패턴 일관성

기존 `/api/connection`, `/api/sessions`, `/api/session` 핸들러와 동일한 코드 패턴 (`com.sun.net.httpserver.HttpHandler` 또는 인접 wrapper) 을 차용한다. 정확한 routing 파일은 plan Task 5 의 `grep -rn '"/api/' fixthis-mcp/src/main/kotlin/` 로 찾는다.

### 의도된 호환성

- 옛 fixthis-mcp 에는 이 엔드포인트가 없으므로 **404** 를 반환한다 (서버의 default not-found 핸들러).
- 콘솔의 `checkServerStaleness` 는 `resp.status === 404` 를 stale 신호로 명시적으로 처리한다 (network error 와 분리 — §6 참고).
- 본 엔드포인트는 internal contract 으로 둔다 — README 에 외부 노출하지 않고, 외부 도구가 의존하지 않게 한다 (`spec Open Questions Q3`).

---

## 3. `BuildInfo.kt` Gradle 패턴

### 공통 구조

mcp 와 sidekick 각각이 자기 패키지의 `BuildInfo` object 를 build time 에 generate 한다.

```kotlin
// generated
package <module-package>
object BuildInfo {
    const val BUILD_EPOCH_MS: Long = ${minute-rounded epoch}L
    const val GIT_SHA: String = "<short sha>"
}
```

### Gradle 태스크 구조

```kotlin
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    val gitShaProvider = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText
    val nowProvider = providers.provider {
        (System.currentTimeMillis() / 60_000L) * 60_000L
    }
    outputs.dir(outputDir)
    inputs.property("gitSha", gitShaProvider.map { it.trim().ifBlank { "unknown" } })
    inputs.property("buildEpoch", nowProvider)
    doLast {
        // write file
    }
}
```

### 핵심 포인트

#### (a) `providers.exec` 사용 이유

Gradle 의 configuration cache 호환성. `Runtime.exec` 직접 호출은 deprecated. `providers.exec` 는 lazy 평가되어 task graph 구성 시점에 git 호출이 일어나지 않고, 실행 시에만 호출된다.

#### (b) `isIgnoreExitValue = true`

git 이 없거나 repo 가 아닌 환경 (CI 의 일부 단계, `dist` 만 풀어서 실행하는 환경) 에서 실패하지 않게. `ifBlank { "unknown" }` 로 fallback.

#### (c) `inputs.property` 명시

Gradle 의 task UP-TO-DATE 검사를 위해. epoch 가 분 단위이므로 같은 분 내 재실행은 캐시 hit, 다음 분에 재생성. git sha 가 바뀌어도 재생성.

#### (d) sourceSets wiring — 모듈 타입 차이

**fixthis-mcp** (JVM 모듈):

```kotlin
kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }
```

**fixthis-compose-sidekick** (Android 모듈):

```kotlin
android.sourceSets.named("main") {
    java.srcDir(generateBuildInfo.map { it.outputs.files })
}
tasks.matching { it.name == "compileDebugKotlin" || it.name == "compileReleaseKotlin" }
    .configureEach { dependsOn(generateBuildInfo) }
```

Android Gradle Plugin 은 `kotlin.sourceSets` 가 아니라 `android.sourceSets[…].java.srcDir` 로 generated kotlin source 를 추가한다. 그리고 컴파일 태스크 이름이 variant 별로 다르므로 (`compileDebugKotlin`, `compileReleaseKotlin`) `tasks.matching` 패턴이 더 안전하다.

이 wiring 의 정확한 패턴은 `:fixthis-compose-sidekick:tasks` 의 출력으로 확인 가능. 본 plan 은 권장 패턴만 제시하고, implementer 가 `compileDebugKotlin` 의 정확한 이름을 검증하도록 한다.

#### (e) `.gitignore`

generated source dir 은 추적하지 않는다.

```
fixthis-mcp/build/generated/source/buildinfo/
fixthis-compose-sidekick/build/generated/source/buildinfo/
```

Gradle 의 `build/` 가 root 에서 이미 ignored 라면 redundant 하지만 명시적으로 추가하는 것이 안전.

---

## 4. `ConsoleBuildEpochMs` 라운딩 결정

### 채택: 분 단위 (60_000ms)

```javascript
const buildEpoch = Math.floor(Date.now() / 60000) * 60000;
```

### 이유

- **Dirty diff 회피**: app.js 가 매 빌드마다 변경되면 git status 가 항상 dirty → 개발자가 "변경했나?" 확인 어려움. 같은 분 내 incremental rebuild 는 동일 epoch 으로 동일 bytes.
- **False negative window**: epoch 분 단위 + threshold 5분 → 사용자가 4분 59초 차이 나는 stale 서버에 붙어 있으면 알람 없음. 그러나 이 윈도우는 짧아서 운영 risk 가 작음.
- **대안 1 — git sha 단독 비교**: epoch 무시하고 git sha 만 비교. 같은 commit 에서 빌드된 두 인스턴스는 동일 sha → false negative. 단, 머지/리빌드 시 sha 가 항상 갱신되므로 실용 케이스에서는 충분.
- **대안 2 — 30초 라운딩**: false negative 윈도우 절반. 그러나 같은 30초 윈도우 안 재빌드는 흔치 않으므로 dirty diff 회피 효과는 거의 동일.

본 plan 은 분 단위를 채택. 후속 사용자 보고에 따라 30초로 조정 가능 (단순 상수 변경).

### Granularity 일관성

mcp build.gradle.kts, sidekick build.gradle.kts, build-console-assets.mjs 모두 같은 라운딩 적용. 라운딩 단위가 다르면 정상 케이스에서도 false positive 가능.

---

## 5. Banner UI — 위치, dismiss 정책, hash 계산

### DOM 구조

```html
<div id="stalenessBanner" class="staleness-banner" role="alert" hidden>
  <span data-headline></span>
  <code data-detail></code>
  <button type="button" data-dismiss aria-label="Dismiss">×</button>
</div>
```

### 위치

`<body>` 의 첫 자식. body 전체 layout 을 가리지 않게 `position: sticky; top: 0;` + `z-index: 100`. 높이 ≤ 40px (1줄 메시지 + dismiss 버튼).

### Severity 계층

- `warning` (노란색): build epoch 차이 5분 초과
- `critical` (빨간색): protocol version mismatch

`data-severity` 속성으로 CSS 분기:

```css
.staleness-banner { background: #fff7e6; border-bottom: 1px solid #f0c000; }
.staleness-banner[data-severity="critical"] { background: #ffe6e6; border-bottom-color: #d04040; }
```

`renderStalenessBanner` 가 처음 호출될 때 severity 를 set. 이후 같은 banner 가 재호출되면 마지막 severity 가 우선 (즉, critical → warning 으로는 떨어지지 않음 — implementation 단순화). 첫 mismatch 가 critical 이면 끝까지 critical 유지.

### Dismiss 정책 — sessionStorage + hash

```javascript
const StalenessDismissKey = 'fixthis.console.stalenessDismissedHash';

// dismiss 시
sessionStorage.setItem(StalenessDismissKey, banner.dataset.hash || '');

// renderStalenessBanner 진입 시
const dismissed = sessionStorage.getItem(StalenessDismissKey);
if (dismissed === info.hash) return;  // 같은 mismatch 는 다시 안 띄움
```

#### Hash 종류

| 검사 | hash |
|---|---|
| /api/server-version 404 | `'pre-version-endpoint'` |
| 서버 build epoch drift | `${server.serverGitSha}-${ConsoleBuildGitSha}` |
| Protocol version 너무 낮음 | `protocol-${v}` |
| Sidekick build epoch drift | `sidekick-${sidekickEpoch}` |

#### 의도

- 같은 mismatch 가 재진입 시마다 banner 띄우면 dismiss 의 의미가 없음 → hash 동일 시 silent.
- 다른 mismatch (서버 재빌드 후 또 stale, 또는 protocol bump) 면 새 hash → 다시 띄움.
- sessionStorage 라 탭 닫으면 초기화 — 새 세션은 fresh.

### Re-render 전략

콘솔 boot 시 1회 (`checkServerStaleness`) + heartbeat 로 매번 (`applyConnectionStatus` 끝에서 `checkProtocolCompat`, `checkSidekickBuildEpoch`). 즉, server build 가 dynamic 하게 바뀌는 상황 (예: 서버 재시작 후 옛 → 새) 에서 자동으로 banner 가 사라지지는 않는다 (사용자가 reload 해야 사라짐).

이 limitation 을 받아들이는 이유: 서버 재시작 시 **콘솔 자체도 재시작 필요한 경우가 압도적으로 많기** 때문. 옛 콘솔이 새 서버에 붙어 있는 케이스는 드물고, hard reload 로 즉시 해결.

---

## 6. `/api/server-version` 부재 처리 (옛 fixthis-mcp 호환성)

### 결정 트리

```
fetch('/api/server-version')
  ├─ resp.ok (200)
  │   └─ JSON parse
  │       ├─ Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs) > StaleThresholdMs
  │       │   → render banner (warning, hash = serverGitSha-ConsoleBuildGitSha)
  │       └─ else: silent
  ├─ resp.status === 404
  │   → render banner (warning, hash = 'pre-version-endpoint')
  ├─ resp.status >= 500
  │   → silent (서버 일시 장애 가능, ambiguous)
  └─ throw (network error / JSON parse fail)
      → silent (네트워크 장애 vs 잘못된 응답 분리 어려움)
```

### 왜 404 만 stale 신호인가

- `/api/server-version` 이 명시적으로 없는 서버 = 본 plan 머지 이전 fixthis-mcp = stale.
- 5xx 는 서버가 살아있긴 하지만 일시 에러 가능 — `/api/connection` 이 정상이면 stale 아님. ambiguous 케이스라 silent.
- network error 는 콘솔 자체의 접속 문제 — 다른 layer 에서 이미 alert 하고 있음 (`showError`, connection card 의 reconnect 분기).

### 구현 측 코드

`staleness.js` 의 `checkServerStaleness`:

```javascript
async function checkServerStaleness() {
  try {
    const resp = await fetch('/api/server-version');
    if (resp.status === 404) {
      renderStalenessBanner({
        severity: 'warning',
        headline: 'Server is older than this console',
        detail: 'Restart fixthis-mcp to apply the latest server code.',
        hash: 'pre-version-endpoint',
      });
      return;
    }
    if (!resp.ok) return;
    const server = await resp.json();
    const drift = Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs);
    if (drift > StaleThresholdMs) {
      renderStalenessBanner({
        severity: 'warning',
        headline: 'Server build is older than this console',
        detail: `Client ${ConsoleBuildGitSha} → Server ${server.serverGitSha}. Restart fixthis-mcp.`,
        hash: `${server.serverGitSha}-${ConsoleBuildGitSha}`,
      });
    }
  } catch (_err) {
    // silent
  }
}
```

---

## 7. `BridgeStatus.sidekickBuildEpochMs` nullable 추가

### DTO 변경

`fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt`:

```kotlin
@Serializable
data class BridgeStatus(
    val activity: String?,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: Int,
    val sourceIndexAvailable: Boolean,
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
    val installEpochMillis: Long? = null,
    val sidekickBuildEpochMs: Long? = null,   // ← 추가
)
```

`= null` 기본값으로 backward-compat. 옛 sidekick 은 이 필드를 응답하지 않고, kotlinx.serialization 이 missing 을 null 로 deserialize.

### `BridgeProtocol.VERSION` bump

`BridgeProtocol.VERSION` 을 +1 bump. plan Task 9 가 EXPECTED_NEW_VERSION 을 implementer 가 코드 확인 후 결정하도록 함 (현재 값을 미리 박지 않음 — 머지 시점까지 다른 PR 이 bump 할 수 있음).

### `BridgeServer.status()` 채움

```kotlin
override suspend fun status(): BridgeStatus {
    // 기존 필드 채움
    return BridgeStatus(
        activity = inspection.activity,
        rootsCount = inspection.roots.size,
        sidekickVersion = sidekickVersion,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        sourceIndexAvailable = sourceIndexResult.sourceIndexAvailable,
        screenInteractive = powerManager?.isInteractive,
        keyguardLocked = keyguardManager?.isKeyguardLocked,
        appForeground = lifecycleCallbacks.isAppForeground(),
        pictureInPicture = resumedActivity?.isInPictureInPictureMode,
        installEpochMillis = readInstallEpochMillis(),
        sidekickBuildEpochMs = BuildInfo.BUILD_EPOCH_MS,   // ← 추가
    )
}
```

### Wire 호환성 매트릭스

| Console | Sidekick | 결과 |
|---|---|---|
| 새 | 새 | 정상. drift 비교 작동. |
| 새 | 옛 | `sidekickBuildEpochMs = null` → console `checkSidekickBuildEpoch` 가 null 가드로 skip. `bridgeProtocolVersion` 이 옛 값이면 `checkProtocolCompat` 가 critical banner. |
| 옛 | 새 | 새 필드 unknown → kotlinx.serialization 이 strict 모드면 fail, lenient 모드면 무시. 본 프로젝트는 lenient (확인 필요). |
| 옛 | 옛 | 본 plan 머지 전 상태. 영향 없음. |

---

## 8. Protocol version compat — heartbeat path 어디서 검증

### Hook 위치

`connection.js:108` 부근의 `applyConnectionStatus` 끝, 기존 `renderPreviewRegion()` 호출 직후:

```javascript
              renderPreviewRegion();
              checkProtocolCompat(status);          // ← 추가
              checkSidekickBuildEpoch(status);      // ← 추가
            }
```

### 왜 `applyConnectionStatus` 인가

- heartbeat polling (1초 간격) 이 매 tick 마다 `refreshConnection()` → `applyConnectionStatus(status)` 를 호출.
- 같은 status 에서 protocol version + sidekick epoch 두 가지를 한 번에 검증 가능.
- Console boot 시 첫 heartbeat 이전에 alert 못 하지만, 1초만 기다리면 첫 heartbeat 이 온다 — 충분.
- 별도 polling timer 가 필요 없음.

### Idempotency

`renderStalenessBanner` 가 sessionStorage hash 비교로 중복 render 를 막으므로, 매 tick 호출돼도 첫 mismatch 1회만 banner 가 뜨고 dismiss 후 silent.

같은 hash 가 dismiss 되어도, **다른 hash** (예: protocol bump 후 새 mismatch) 가 나오면 다시 banner 뜸.

---

## 9. Test Strategy

### 자동 테스트로 보장되는 것

| Invariant | 검증 |
|---|---|
| Bundle 에 build epoch + git sha 존재 | string presence (`FeedbackConsoleServerTest.consoleBundleEmbedsBuildEpochAndGitSha`) |
| Bundle 의 byte-equality 가 staleness.js 와 build header 포함 | `generatedConsoleAppMatchesConsoleSourceModules` 확장 |
| `BuildInfo.BUILD_EPOCH_MS > 0`, `GIT_SHA` 비어있지 않음 | `BuildInfoTest` |
| `/api/server-version` 200 응답 with 3 필드 | `ServerVersionEndpointTest` |
| `staleness.js` 가 module 로드되고 const 정의 | `stalenessModuleExposesCheckAndRender` |
| `boot sequence 가 checkServerStaleness 호출` | `bootSequenceCallsCheckServerStaleness` (string presence) |
| `applyConnectionStatus 가 checkProtocolCompat 호출` | string presence in function body |
| `BridgeStatus 시리얼라이제이션이 sidekickBuildEpochMs 포함` | `bridgeStatusIncludesSidekickBuildEpoch` |
| `BridgeProtocol.VERSION >= EXPECTED_NEW_VERSION` | `bridgeProtocolVersionBumpedForBuildEpochField` |

### 자동 테스트가 보장 못 하는 것

| Symptom | 수동 검증 시나리오 |
|---|---|
| 옛 fixthis-mcp + 새 콘솔 → 노란 banner 출현 | 머지 전 fixthis-mcp 인스턴스 살린 채로 새 build header 임베드된 콘솔 페이지 로드 |
| Banner dismiss 후 재진입 silent | Dismiss 클릭 후 hard reload — 같은 hash 이면 안 뜨는지 |
| Network 일시 장애 silent | 서버 잠시 종료 → 콘솔이 banner 안 띄우는지 |
| 옛 sample APK + 새 fixthis-mcp → 빨간 banner | 옛 sample APK 인스톨된 디바이스 + 새 fixthis-mcp restart |
| sessionStorage clear 후 재진입 다시 banner | `sessionStorage.clear()` devtools 명령 + reload |
| Banner dismiss 가 가시 영역만 가리고 layout shift 없는지 | 시각 검증 |

### Test 데이터 패턴

기존 `FeedbackConsoleServerTest.kt` 가 `javascriptFunctionBody(html, "<funcName>")` 헬퍼를 제공. staleness.js 의 함수들도 같은 헬퍼로 본문 추출 + assert.

build epoch 의 정확한 값은 매번 다르므로 어서션은 **존재** 와 **format pattern** (regex `\d+`) 만 검증.

---

## 10. Manual Smoke Scenarios — Phase 별

### Phase 1 (restart-console.sh)

```bash
# 사전: 기존 fixthis-mcp/cli console 인스턴스를 띄워둔다 (또는 두 개)
ps aux | grep -E 'fixthis-(mcp|cli).*console' | grep -v grep | wc -l
# Expected: >= 1

# 실행
bash scripts/restart-console.sh

# 검증
ps aux | grep -E 'fixthis-(mcp|cli).*console' | grep -v grep | wc -l
# Expected: == 1 (스크립트가 띄운 새 인스턴스만)

# 명명된 screen session 도 청소됐는지
screen -ls 2>/dev/null | grep fixthis-console
# Expected: empty 또는 새로 띄운 1개만
```

### Phase 2 (staleness banner)

#### Scenario A: 옛 fixthis-mcp + 새 콘솔

```bash
# 1. 새 코드 머지 전에 fixthis-mcp 빌드 + 시작
git checkout <pre-Phase-2-commit>
./gradlew :fixthis-mcp:installDist
fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp \
  --console --package io.github.beyondwin.fixthis.sample &
OLD_MCP_PID=$!

# 2. Phase 2 머지로 돌아옴
git checkout main

# 3. 새 콘솔 JS 만 빌드
node scripts/build-console-assets.mjs

# 4. 브라우저에서 http://localhost:60006 hard reload (Cmd+Shift+R)

# 5. 노란 banner 출현 확인
#    - "Server is older than this console" headline
#    - dismiss 버튼 클릭 → 사라짐
#    - hard reload → 같은 hash 이므로 안 뜸

# Cleanup
kill $OLD_MCP_PID
```

#### Scenario B: 정상 케이스 — banner 안 뜸

```bash
bash scripts/restart-console.sh
# 브라우저 hard reload → banner 없음
```

### Phase 3 (protocol compat)

```bash
# 1. 옛 sample APK 가 디바이스에 설치된 상태로 둔다 (옛 BridgeProtocol.VERSION)
# 2. 새 fixthis-mcp restart
bash scripts/restart-console.sh

# 3. 콘솔이 디바이스 연결 상태 진입 후 1초 내
#    빨간 banner: "Bridge protocol vN is too old. Reinstall the sample APK..."

# 4. dismiss 후 재진입 silent

# 5. 새 sample APK 설치
./gradlew :app:installDebug

# 6. 콘솔 hard reload → banner 없음 (또는 sidekickBuildEpoch drift > threshold 면 다른 hash 의 새 banner)
```

---

## 11. CHANGELOG 항목 (Phase 4)

`CHANGELOG.md` 의 `## Unreleased` 아래:

```markdown
### Added
- `scripts/restart-console.sh` 헬퍼. stale fixthis-mcp/cli 콘솔 프로세스를 종료하고 incremental gradle 빌드 + 재시작을 단일 커맨드로 묶음. `--with-app` 으로 샘플 APK 도 같이 재설치.
- 콘솔 boot 시 자동 staleness 감지. `fixthis-mcp` JAR 또는 sidekick 빌드가 5분 이상 차이 나면 dismissable banner 로 알림. 새 endpoint `/api/server-version` (server build epoch + git sha + bridge protocol version) + `BridgeStatus.sidekickBuildEpochMs` 필드 추가.

### Changed
- `BridgeProtocol.VERSION` bump (sidekickBuildEpochMs 필드 추가에 따른 minor bump). 옛 sample APK 가 새 콘솔에 연결되면 빨간 banner 로 reinstall 안내.

### Docs
- `CLAUDE.md` 에 "After Code Changes — Restart Console Stack" 섹션 추가. fixthis-mcp 코드 변경 후 재시작 의무 명문화.
- `CLAUDE.md` 에 "Bridge Protocol Compatibility" 섹션 추가. BridgeStatus / BridgeProtocol 시그니처 변경 PR 의 VERSION bump 규칙 명시.
```

---

## 12. Edge Cases / Gotchas

### 12.1 `git rev-parse` 가 실패하는 환경

CI 의 일부 단계나 `dist` 만 풀어둔 환경에서 git 실행 안 됨. `isIgnoreExitValue = true` + `ifBlank { "unknown" }` 로 안전 fallback. `GIT_SHA = "unknown"` 이면 hash 가 `unknown-${ConsoleBuildGitSha}` 가 되어 매번 다른 hash 처럼 보일 수 있음 — 이 케이스는 운영 환경이 아니므로 수용.

### 12.2 분 단위 라운딩의 false negative

build A 와 build B 가 같은 분 안에 일어나면 epoch 같음 → drift 0 → no banner. Threshold 5분 보다 작으므로 사실 무관.

### 12.3 BuildInfo.kt 가 generated source 라 IDE 가 인식 못 할 가능성

`./gradlew :fixthis-mcp:compileKotlin` 한 번 실행하면 generated source 가 만들어지고, IntelliJ/AS 가 빌드 결과를 다시 인덱싱하면 인식. 처음 plan 따라 실행할 때 IDE 빨간 줄 나와도 무시하고 gradle 빌드 → 사라짐.

### 12.4 generateBuildInfo 가 non-incremental

매 빌드마다 epoch 이 갱신되면 incremental kotlin 컴파일이 항상 invalidated. 분 단위 라운딩으로 `inputs.property("buildEpoch", nowProvider)` 가 같은 분 내 동일 → UP-TO-DATE 가능.

그러나 `nowProvider` 가 매번 호출될 때 `System.currentTimeMillis()` 도 매번 호출되므로 strictly UP-TO-DATE 는 아님 — Gradle 이 input 비교로 같은 값 인식 시 skip. 분 경계에서는 invalidate 발생.

### 12.5 staleness.js 가 main.js 보다 앞 → 함수 호출 시점

`checkServerStaleness()` 가 `await fetch(...)` 기반 async 함수. main.js 의 boot sequence 끝에서 `.catch(() => {})` 로 silent 호출. 응답 늦더라도 다른 boot 작업을 막지 않음.

### 12.6 sidekickBuildEpochMs 가 BridgeStatus 응답 JSON 에 어떻게 들어가나

kotlinx.serialization 의 nullable Long 필드. 기본값 null + 명시적으로 채우면 number. `Json` configuration 의 `explicitNulls` 가 true 면 null 일 때도 `"sidekickBuildEpochMs":null` 로 송신, false 면 누락. 본 프로젝트의 Json instance 설정 확인 필요 — `console.js` 의 `?? null` fallback 으로 어느 쪽이든 graceful.

### 12.7 Banner dismiss 후 페이지 새로고침 시 행동

sessionStorage 라 같은 탭에서는 유지. 새 탭/창 이면 초기화. 의도된 행동 — "이 세션 동안 같은 mismatch 는 다시 안 보여달라".

새 hash (다른 mismatch) 면 같은 탭이라도 다시 banner 뜸.

### 12.8 dismiss 클릭과 outside-click 의 차이

본 plan 은 dismiss button 클릭만 처리. ESC 키 / outside click / swipe 등은 처리 안 함. 후속 UX 개선 가치 있음.

---

## 13. Out of Scope (반복 명시)

본 plan / impl details 는 다음을 **의도적으로** 안 건드린다:

- Kotlin 서버 코드 hot-reload (JVM class redefinition). 인프라 수준.
- 멀티 fixthis-mcp 인스턴스 자동 발견 + 종료 통합. orchestration 별도.
- IDE plugin / VS Code extension 통합.
- CI lint 가 `BridgeProtocol.kt` 변경 PR 에서 `VERSION` bump 누락을 강제하는 규칙. CONTRIBUTING.md 가이드만 추가.
- `/api/server-version` 의 외부 stable API 약속. internal 으로 둠.
- Banner dismiss 의 ESC/outside-click/swipe 지원.
- 멀티 머신 환경 (개발자 A 의 콘솔 + 개발자 B 의 fixthis-mcp 원격) — NTP 시계 동기 가정.
- Sample APK 자동 재배포 — `--with-app` 옵션으로만 노출.

---

## 14. Reading Order for First-time Implementer

1. 이 문서의 §1 (build pipeline) — 어디에 무엇이 들어가는지 picture 잡는다.
2. plan 의 Phase 1 Task 1, 2 — 가벼운 doc + script 부터 머지 가능.
3. impl details §3 (BuildInfo Gradle 패턴) — Phase 2 Task 4 와 Phase 3 Task 10 의 Gradle wiring 핵심.
4. plan 의 Phase 2 Task 3, 4, 5 — bundle header + BuildInfo + endpoint 순서.
5. impl details §2, §6 — wire format 과 fallback 결정 규칙.
6. plan 의 Phase 2 Task 6, 7, 8 — staleness module + banner UI + boot wiring.
7. impl details §7, §8 — Phase 3 의 BridgeStatus DTO + heartbeat path 검증 위치.
8. plan 의 Phase 3 Task 9, 10, 11 — sidekick 측 변경 + console protocol compat.
9. impl details §10 — manual smoke 시나리오로 마무리 검증.
10. plan 의 Phase 4 Task 13 — final build + CHANGELOG.

각 단계 사이에 `bash scripts/restart-console.sh` 로 dev 환경을 fresh 하게 유지.
