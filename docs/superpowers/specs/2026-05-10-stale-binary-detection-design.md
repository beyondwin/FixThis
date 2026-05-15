# Console ↔ Bridge Stale-Binary Detection — Design

**Status:** Draft
**Owner:** kws
**Date:** 2026-05-10
**Related work:**
- Real incident: `merge 1040988` (`mcp-handoff-followups`) 직후 디바이스 HOME 디버깅 세션
- `docs/superpowers/specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md` 의 H6 (Sub-agent worktree-path drift) 와 인접한 운영 hygiene 영역

---

## Summary

`fixthis-mcp` 콘솔 서버의 `--console-assets-dir` 플래그는 HTML/CSS/JS 를 source 디렉토리에서 라이브 리드하지만 Kotlin 서버 코드는 JAR 로 메모리에 고정된다. 그 결과 오래 살아있는 fixthis-mcp 프로세스는 **fresh JS ↔ stale Kotlin** 미스매치를 만들고, 새 BridgeStatus 필드(예: `appForeground`) 가 응답에서 누락되어 콘솔의 새 UX 분기가 silent 하게 비활성된다. 본 spec 은 이 미스매치를 (1) 운영 절차로 예방하고 (2) 자동 감지해 콘솔이 사용자에게 alert 하도록 하는 단계적 개선을 정의한다.

---

## Motivation

### 실측 사례 (2026-05-10)

- 사용자가 `mcp-handoff-followups` 8 commit 머지 (`1040988`) 직후 "디바이스 HOME 누르면 라이브 프리뷰 갱신 안 됨, 'Sample app is in the background' 오버레이도 안 뜸" 보고
- 진단 결과: 사용자가 접속 중인 fixthis-mcp 프로세스 (PID 24966) 가 **2026-05-08 14:43** 시작
  - JAR 파일 자체는 이미 삭제됐지만 JVM 메모리에 로드된 phantom binary
  - `--console-assets-dir /Users/kws/source/android/FixThis/fixthis-mcp/src/main/resources/console` 로 source 의 fresh JS 서빙
  - 그러나 Kotlin 서버 코드는 머지 전 JAR
- `appForeground` 필드는 BridgeStatus 에 **2026-05-09 20:51** 에 추가됨 (`f95ea92 feat(sidekick): emit screenInteractive/keyguardLocked/appForeground/pictureInPicture in status()`)
- 즉, 사용자 환경의 Kotlin 서버는 `appForeground` 가 무엇인지 모르는 상태로 응답을 만들고 있었고, 콘솔 JS 의 `computeBlockedReason` 분기는 영영 안 탐
- 진단 소요 시간 — 약 30 분 (ps + lsof + git log + 코드 흐름 추적). 1 분 컷이어야 했던 사례.

### 왜 이게 반복적인 문제인가

- `--console-assets-dir` 가 라이브 리드를 제공해서 **JS 변경은 즉각 반영**됨 → 개발자/사용자가 "변경이 적용됐다" 고 잘못 가정
- Kotlin 변경은 프로세스 재시작 + JAR 재빌드 필요 — 이 절차가 어디에도 명문화되지 않음
- 한 머신에 같은 포트 (`60006`) 를 잡는 여러 백그라운드 fixthis-mcp 가 누적되는 패턴 (sample 사용자 환경에서 `ps aux | grep fixthis` 로 3 개 동시 검출됨)
- 로컬 개발자가 `--console-port` 를 명시 안 하면 OS 가 새 포트 할당 → 브라우저 탭은 옛 포트에 계속 붙어 있음

---

## Goals

- **G1.** 운영 절차의 단순화: stale 프로세스 종료 + 재빌드 + 재시작을 단일 커맨드로 (`scripts/restart-console.sh`).
- **G2.** 코드 변경 후 재시작 의무를 CLAUDE.md 에 명문화 — 신규 contributor 와 자기-자신 (오케스트레이터) 모두가 같은 규약을 따른다.
- **G3.** 콘솔 boot 시 stale 서버 자동 감지 + dismissible 배너로 안내 ("Server build is older than this console. Restart fixthis-mcp.").
- **G4.** BridgeStatus DTO 에 build timestamp / git sha 정식 노출 + protocol version 강제 검증으로, 다음 BridgeStatus 신규 필드 PR 부터는 누락 시 자동 alert.

---

## Non-Goals

- Kotlin 서버 코드 hot-reload — JVM class redefinition 은 인프라 수준 변경, 본 spec 범위 밖.
- 다중 fixthis-mcp 인스턴스 자동 종료/통합 — orchestration 은 별도.
- IDE plugin 통합, CI/CD 알림 — 선택적 후속 작업.
- Sample APK 자동 재설치 자동화 (옵션으로만 제공).

---

## Background — Current State

### `--console-assets-dir` 동작

`fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt` 가 두 가지 모드를 지원:

- (default) JAR 내 `resources/console/` 에서 packaged HTML/CSS/JS 서빙
- `--console-assets-dir <path>` 가 설정되면 그 경로에서 매 요청 라이브 리드

라이브 리드 모드는 콘솔 UI 개발 시 편리하지만, Kotlin 코드는 JAR 에 고정되므로 JS-only 변경만 즉각 반영된다.

### BridgeStatus DTO 의 호환성 약속

`fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt` 에서 `BridgeProtocol.VERSION: Int` 와 `BridgeStatus` 데이터 클래스가 정의된다. 새 필드는 nullable 로 추가하면 backward-compat — 옛 sidekick 은 필드를 비워서 응답하고 새 콘솔은 `?? null` 로 graceful degrade. 그러나 **null vs missing 이 콘솔 UX 분기에서 의미 있게 갈리는 경우** (예: `appForeground === false`) 새 sidekick + 옛 콘솔 또는 그 반대 조합에서 silent fail.

### 현재 정의된 검증 표면

- `node --check fixthis-mcp/src/main/resources/console/app.js` — JS 문법
- `FeedbackConsoleServerTest.kt` — 번들 HTML 의 string presence 어서션
- 자동 회귀 테스트 — `:fixthis-mcp:test` 387/0
- 그러나: 실행 중 프로세스의 JAR 빌드 시각이 source 와 얼마나 차이 나는지 검증하는 메커니즘 없음.

---

## Conceptual Model

### Detection 의 본질

세 개의 timestamp/version 이 일치해야 시스템이 정합:

1. **Console JS bundle** 의 build epoch — `app.js` 가 마지막으로 `build-console-assets.mjs` 로 생성된 시각
2. **fixthis-mcp JAR** 의 build epoch — Kotlin 서버 코드가 마지막으로 컴파일된 시각
3. **sidekick (in sample APK)** 의 build epoch — 디바이스에 설치된 sidekick 의 빌드 시각

콘솔이 boot 후:
- (2) 와 (1) 의 차이가 임계값 (예: 5 분) 을 넘으면 → "Restart fixthis-mcp" 배너
- (3) 와 (1) 의 차이가 임계값을 넘으면 → "Reinstall sample app" 배너
- 둘 다 정상이지만 `bridgeProtocolVersion < MinimumSupportedProtocolVersion` 이면 → 빨간 배너 (incompat)

### 우선순위 결정

신호 강도 vs 구현 비용:
- **명시적 build epoch 비교** — 강한 신호, 구현 ~3 시간
- **protocol version 비교** — 약한 신호 (필드 추가 시 누가 bump 안 하면 무용), 구현 ~1 시간
- **운영 절차 + 도구 지원** — 진단/예방 양쪽 커버, 구현 ~30 분

본 spec 은 셋 다 phase 로 나누어 점진적 채택을 유도한다.

---

## Architecture

### Phase 1 — Documentation + Helper Script (P0)

순수 운영 hygiene. 코드 0 줄.

**1.1 CLAUDE.md 갱신**
새 섹션 "After Code Changes — Restart Console Stack" 추가. 핵심 메시지: "console JS 는 라이브 리드, Kotlin 은 재시작 필요. 머지 후 항상 `scripts/restart-console.sh` 실행."

**1.2 `scripts/restart-console.sh` 작성**
shell script (~50 줄):
```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 1. 기존 fixthis-mcp/cli console 프로세스 종료
pkill -f 'fixthis-mcp.*--console' || true
pkill -f 'fixthis-cli.*console' || true
# 명명된 screen 세션 정리
screen -ls 2>/dev/null \
  | grep -E 'fixthis-console-[0-9]+' \
  | awk '{print $1}' \
  | xargs -I {} screen -S {} -X quit 2>/dev/null || true

# 2. installDist (incremental gradle, UP-TO-DATE 이면 5초)
cd "$ROOT"
./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist

# 3. 옵션: sample APK 재설치
if [[ "${1:-}" == "--with-app" ]]; then
  ./gradlew :app:assembleDebug :app:installDebug
  shift
fi

# 4. 콘솔 시작 (포트는 --console-port 인자 또는 기본 60006)
exec "$ROOT/fixthis-cli/build/install/fixthis/bin/fixthis" console \
  --package io.github.beyondwin.fixthis.sample \
  "$@"
```

**1.3 `scripts/restart-console.sh` 실행 권한 부여**
```bash
chmod +x scripts/restart-console.sh
git add scripts/restart-console.sh
```

### Phase 2 — Build-time Embedding + Console Banner (P1)

**2.1 `scripts/build-console-assets.mjs` 수정**
번들 출력 상단에 build epoch + git sha 임베드:
```javascript
// 기존 concat 직전:
const buildEpochMs = Date.now();
const gitSha = (await execAsync('git rev-parse --short HEAD')).stdout.trim();
const header = `
            const ConsoleBuildEpochMs = ${buildEpochMs};
            const ConsoleBuildGitSha = '${gitSha}';
`;
output = header + output; // 또는 위치는 state.js 직후가 자연스러움
```

테스트 (`FeedbackConsoleServerTest.kt`):
```kotlin
@Test
fun consoleBundleEmbedsBuildEpochAndGitSha() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("const ConsoleBuildEpochMs = "), "must embed build epoch")
    assertTrue(html.contains("const ConsoleBuildGitSha = '"), "must embed git sha")
}
```

**2.2 fixthis-mcp Gradle: BuildInfo.kt 생성**
`fixthis-mcp/build.gradle.kts` 에 `generateBuildInfo` 태스크:
```kotlin
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    outputs.dir(outputDir)
    doLast {
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
        val target = outputDir.get().file("io/github/beyondwin/fixthis/mcp/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText("""
            package io.github.beyondwin.fixthis.mcp
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${System.currentTimeMillis()}L
                const val GIT_SHA: String = "$gitSha"
            }
        """.trimIndent())
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }
```

**2.3 `/api/server-version` endpoint 추가**
`fixthis-mcp/.../console/FeedbackConsoleHttpServer.kt` (혹은 그에 상응하는 파일) 에 핸들러:
```kotlin
"/api/server-version" -> respondJson(mapOf(
    "serverBuildEpochMs" to BuildInfo.BUILD_EPOCH_MS,
    "serverGitSha" to BuildInfo.GIT_SHA,
    "bridgeProtocolVersion" to BridgeProtocol.VERSION,
))
```

테스트:
```kotlin
@Test
fun serverVersionEndpointReturnsBuildInfo() {
    val response = httpGet("/api/server-version")
    assertEquals(200, response.statusCode)
    val json = parseJson(response.body)
    assertNotNull(json["serverBuildEpochMs"])
    assertNotNull(json["serverGitSha"])
    assertNotNull(json["bridgeProtocolVersion"])
}
```

**2.4 Console staleness check 모듈**
새 파일 `fixthis-mcp/src/main/console/staleness.js`:
```javascript
const StaleThresholdMs = 5 * 60 * 1000;

async function checkServerStaleness() {
  try {
    const resp = await fetch('/api/server-version');
    if (!resp.ok) {
      renderStalenessBanner({
        message: 'Server is older than this console (no /api/server-version). Restart fixthis-mcp.',
        clientGitSha: ConsoleBuildGitSha,
      });
      return;
    }
    const server = await resp.json();
    const drift = Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs);
    if (drift > StaleThresholdMs) {
      renderStalenessBanner({
        message: 'Server build is older than this console. Restart fixthis-mcp to apply latest code.',
        clientGitSha: ConsoleBuildGitSha,
        serverGitSha: server.serverGitSha,
        driftMs: drift,
      });
    }
  } catch (err) {
    // network or JSON parse error — ambiguous, do not banner
  }
}

function renderStalenessBanner(info) {
  const banner = document.getElementById('stalenessBanner');
  if (!banner) return;
  banner.hidden = false;
  banner.querySelector('[data-message]').textContent = info.message;
  const detail = info.serverGitSha
    ? `Client ${info.clientGitSha} → Server ${info.serverGitSha}`
    : `Client ${info.clientGitSha}`;
  banner.querySelector('[data-detail]').textContent = detail;
}

document.getElementById('stalenessBanner')?.querySelector('[data-dismiss]')
  ?.addEventListener('click', () => {
    document.getElementById('stalenessBanner').hidden = true;
  });
```

`main.js` boot sequence 끝 (`startSessionsPolling` 다음) 에 추가:
```javascript
checkServerStaleness().catch(() => { /* never block */ });
```

**2.5 HTML/CSS — banner element 추가**
`fixthis-mcp/src/main/resources/console/index.html` (실제 HTML 호스팅 파일) 의 body 상단에:
```html
<div id="stalenessBanner" class="staleness-banner" hidden>
  <span data-message></span>
  <code data-detail></code>
  <button data-dismiss type="button" aria-label="Dismiss">×</button>
</div>
```

`styles.css` 에 노란 배너 스타일.

**2.6 테스트**
```kotlin
@Test
fun stalenessModuleEmbedsThresholdAndCheck() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("const StaleThresholdMs"))
    assertTrue(html.contains("function checkServerStaleness"))
    assertTrue(html.contains("function renderStalenessBanner"))
}

@Test
fun bootSequenceCallsStalenessCheck() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "<no — boot is top-level>")
    // 또는 단순 string assert
    assertTrue(html.contains("checkServerStaleness().catch"))
}
```

**2.7 `build-console-assets.mjs` 가 staleness.js 도 concat 하도록 module 순서 정의**
state.js 직후에 staleness.js 를 끼워넣는다 (ConsoleBuildEpochMs 가 먼저 정의돼야 staleness 가 참조 가능).

### Phase 3 — BridgeStatus Version + Protocol Compat (P2)

**3.1 BridgeStatus DTO 확장**
`fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt` 의 `BridgeStatus` 에 nullable 필드 추가:
```kotlin
data class BridgeStatus(
    // ... 기존 필드 ...
    val sidekickBuildEpochMs: Long? = null,
)
```

`BridgeProtocol.VERSION` 을 `current + 1` 로 bump.

**3.2 sidekick BuildInfo + 채움**
fixthis-compose-sidekick `build.gradle.kts` 에 `BuildInfo.kt` 생성 태스크 (Phase 2.2 와 동일 패턴).

`BridgeServer.kt` 의 `status()` 가 `sidekickBuildEpochMs = BuildInfo.BUILD_EPOCH_MS` 로 채움.

**3.3 콘솔 protocol version compat check**
`staleness.js` 에 추가:
```javascript
const MinimumSupportedProtocolVersion = 4;  // 현재 기준; 새 필드 추가 시 같이 bump

function checkProtocolCompat(status) {
  const v = status?.bridgeProtocolVersion ?? 0;
  if (v < MinimumSupportedProtocolVersion) {
    renderStalenessBanner({
      severity: 'critical',
      message: `Bridge protocol v${v} is too old. Reinstall sample APK.`,
      clientGitSha: ConsoleBuildGitSha,
    });
  }
}
```

`applyConnectionStatus` 의 끝 (heartbeat 응답을 받은 직후) 에 호출.

**3.4 sidekick build epoch 도 staleness 비교에 통합**
`status.sidekickBuildEpochMs` 와 `ConsoleBuildEpochMs` 비교. drift 가 임계값 넘으면 sidekick-stale 배너 (메시지: "Sample app sidekick is older than console. Reinstall via `./gradlew :app:installDebug`.").

**3.5 PR 가이드 업데이트**
`CONTRIBUTING.md` (또는 `CLAUDE.md`) 에 명문화:
> BridgeStatus 또는 BridgeProtocol 에 새 필드를 추가하거나 시그니처를 바꾸는 PR 은 다음 두 가지를 같이 수정한다:
> 1. `BridgeProtocol.VERSION` 을 +1 bump
> 2. 콘솔의 `MinimumSupportedProtocolVersion` 도 같이 bump (새 필드를 콘솔이 사용한다면)
>
> CI lint 가 BridgeProtocol.kt 변경 PR 에서 VERSION 변경이 없으면 fail 처리한다 (선택).

---

## Data Model

### 새 필드/엔드포인트

| 위치 | 추가 |
|---|---|
| `fixthis-mcp/src/main/console/app.js` (bundled) | `const ConsoleBuildEpochMs: number`, `const ConsoleBuildGitSha: string`, `const StaleThresholdMs: number`, `const MinimumSupportedProtocolVersion: number` |
| `fixthis-mcp/.../BuildInfo.kt` (generated) | `object BuildInfo { BUILD_EPOCH_MS, GIT_SHA }` |
| `fixthis-compose-sidekick/.../BuildInfo.kt` (generated) | 동상 |
| HTTP `GET /api/server-version` | `{serverBuildEpochMs, serverGitSha, bridgeProtocolVersion}` |
| `BridgeStatus.sidekickBuildEpochMs: Long?` | nullable, backward-compat |
| `state.connection` 추가 안 함 — staleness 는 module-scope let 로 관리 |

### 기존 호환성

- `ConsoleBuildEpochMs` 가 매 빌드마다 갱신되므로 `app.js` 가 매 빌드 dirty diff. mitigation: 분 단위 라운딩 (`Math.floor(now / 60000) * 60000`) 으로 같은 분 내 재빌드는 동일 값.
- `/api/server-version` 이 없는 옛 fixthis-mcp 는 404 반환 — 콘솔이 이를 stale 신호로 처리.
- 옛 sidekick 은 `sidekickBuildEpochMs` 를 응답하지 않음 (null) — 콘솔이 null 이면 sidekick-stale 배너는 띄우지 않음.

---

## Testing Strategy

### Phase 1
- 자동 테스트 없음 — shell script 만. 수동 검증: `bash scripts/restart-console.sh` 실행 → 모든 기존 fixthis-mcp 종료 + 새 인스턴스 시작 + 브라우저 접속 가능.

### Phase 2
- `consoleBundleEmbedsBuildEpochAndGitSha` (string presence)
- `serverVersionEndpointReturnsBuildInfo` (HTTP integration test)
- `stalenessModuleEmbedsThresholdAndCheck` (string presence)
- `bootSequenceCallsStalenessCheck` (string presence)
- 수동 검증: stale fixthis-mcp 띄운 채로 fresh JS 만 갱신 → 콘솔 boot 시 노란 배너 출현.

### Phase 3
- `bridgeStatusIncludesSidekickBuildEpoch` (DTO 시리얼라이제이션)
- `bridgeProtocolVersionBumped` (compile-time 또는 lint)
- `consoleHasMinimumProtocolVersionConstant` (string presence)
- `applyConnectionStatusCallsCheckProtocolCompat` (string presence)
- 수동 검증: BridgeProtocol.VERSION 을 강제로 한 단계 낮춰 sample APK 빌드 → 콘솔이 빨간 배너 띄우는지.

### 수동 / E2E (자동 harness 없음)
각 phase 별 1 시나리오:
- Phase 1: 기존 instance 가 있는 상태에서 `restart-console.sh` → 새 instance 만 살아있는지 (`pgrep -f fixthis-mcp` 이 PID 1 개)
- Phase 2: build-console-assets 만 돌리고 fixthis-mcp 는 그대로 둔 채 브라우저 새로고침 → 노란 배너
- Phase 3: 옛 sample APK + 새 fixthis-mcp 조합에서 빨간 배너

---

## Phased Rollout Order

각 phase 는 독립 mergeable.

### Phase 1 (P0, ~30–60 분)

- 1.1 CLAUDE.md 한 섹션 추가
- 1.2 scripts/restart-console.sh 작성
- 1.3 chmod + 커밋

**Acceptance:** `bash scripts/restart-console.sh` 실행 시 기존 fixthis-mcp/cli 프로세스가 모두 종료되고, gradle installDist 가 돌고, 새 콘솔이 boot 한다.

### Phase 2 (P1, ~3 시간)

- 2.1 build-console-assets.mjs 에 build epoch + git sha 임베드 + 단위 테스트
- 2.2 fixthis-mcp gradle BuildInfo 생성 + 단위 테스트
- 2.3 /api/server-version 핸들러 + integration 테스트
- 2.4 staleness.js 모듈 + 단위 테스트
- 2.5 index.html banner element + styles.css
- 2.6 build-console-assets.mjs 모듈 순서에 staleness.js 포함
- 2.7 main.js boot sequence 에 checkServerStaleness 호출 + 단위 테스트
- 2.8 수동 검증

**Acceptance:** stale fixthis-mcp (예: 5 분 이전 build epoch) 를 띄운 채로 콘솔 접속하면 노란 배너 출현 + Restart 안내. 정상 케이스에서는 배너 없음. 자동 테스트 387 + 신규 모두 PASS.

### Phase 3 (P2, ~반일)

- 3.1 BridgeProtocol.kt — sidekickBuildEpochMs 추가 + VERSION bump
- 3.2 sidekick gradle BuildInfo + status() 채움
- 3.3 staleness.js 에 protocol compat check 추가
- 3.4 sidekick build epoch 비교 통합
- 3.5 CONTRIBUTING.md / CLAUDE.md PR 가이드 갱신

**Acceptance:** 옛 sample APK + 새 fixthis-mcp 조합에서 빨간 배너; 새 sample APK + 새 fixthis-mcp 정상; protocol version 누락 PR 이 CI 또는 코드 리뷰에서 잡힘.

---

## Risks & Mitigation

| Risk | Mitigation |
|---|---|
| `ConsoleBuildEpochMs` 갱신으로 매 빌드 `app.js` dirty diff 양산 | epoch 을 분 단위로 라운딩 — `Math.floor(now / 60000) * 60000`. 또는 git sha 만 사용. |
| 사용자가 stale banner dismiss 한 뒤 같은 세션에서 재진입 시 다시 나타남 | dismiss 시 sessionStorage 에 hash 기록, 같은 hash 면 다시 안 띄움. 새 hash (= 새 mismatch) 면 띄움. |
| `/api/server-version` 이 옛 fixthis-mcp 에 없음 → 404 → 콘솔이 stale 로 판단해야 하나 ambiguous (네트워크 에러 가능) | 404 와 network error 분리: 404 만 stale 판정, network error 는 silent. |
| BridgeProtocol.VERSION bump 깜빡 (협업 risk) | CI lint 또는 PR template checklist. 단, 본 spec 은 lint 강제까지 요구하지 않음 — 후속 작업으로 둠. |
| Phase 3 protocol compat check 가 너무 엄격해 정상 케이스도 차단 | 처음엔 warning level 만 (배너 노란색), 한 sprint 운영 후 critical 로 격상. |
| `restart-console.sh` 가 사용자가 의도한 다른 fixthis-mcp 인스턴스도 죽임 | --console 플래그가 들어간 프로세스만 매치. 명확히 문서화. 옵션으로 `--dry-run` 추가 가능. |
| Phase 2 의 banner UI 가 콘솔 핵심 영역을 가림 | top-bar, dismiss 가능, height ≤ 40px. styles.css 에 z-index 명시. |

---

## Rollback

각 phase 는 독립 git revertable.

- Phase 1 revert → restart 스크립트 + 문서만 사라짐. 기존 동작 동일.
- Phase 2 revert → /api/server-version, banner, staleness 모듈 모두 사라짐. BuildInfo 생성 태스크는 유해하지 않으므로 그대로 둘지 같이 revert 할지 PR 별로 결정.
- Phase 3 revert → BridgeStatus 의 새 nullable 필드는 backward-compat 라 즉시 revert 안전. VERSION bump 는 같이 revert. 콘솔의 protocol compat check 사라짐.

---

## Open Questions / Future Work

- **Q1**: `ConsoleBuildEpochMs` 라운딩 단위는 분(60s) 이 적절한가? 빌드가 같은 분 내에 자주 일어나면 staleness 가 false negative 가 될 수 있음. 30s 또는 git sha 단독 비교가 더 견고할 수 있음.
- **Q2**: dismissible banner 를 1 회 dismiss 후 다음 reload 까지 안 띄울지, fresh hash 가 들어오면 다시 띄울지? 본 spec 은 fresh hash → 재출현 으로 가정.
- **Q3**: Phase 2 의 `/api/server-version` 을 stable contract 로 둘지, 내부 디버깅용으로만 둘지? stable 이면 외부 도구가 의존할 수 있음.
- **Q4**: Phase 3 의 sidekick build epoch 비교는 in-process sidekick 에 대해서만 적용. system service 로 운영되는 sidekick 인스턴스가 향후 등장하면 비교 의미가 달라짐.
- **Q5**: CI 가 BridgeProtocol.kt 변경 시 VERSION bump 를 강제하는 lint 를 추가할지? 본 spec 은 강제하지 않고, 후속 작업으로 둠.
- **Q6**: `restart-console.sh` 의 `--with-app` 플래그 외에 `--no-rebuild` (기존 JAR 재사용) 플래그도 필요한가? gradle UP-TO-DATE 가 5초이므로 일반적으로 불필요할 듯.
- **Q7**: 멀티 머신 환경 (개발자 A 의 콘솔 + 개발자 B 의 fixthis-mcp 원격 접속) 에서 epoch 비교는 시계 동기 가정 — NTP 가 정상이면 무시 가능.

---

## Implementation Notes (for new session pickup)

새 세션에서 본 spec 을 받으면:
1. **Phase 1 만 한다면** — kws-claude-multi-agent-executor 또는 단독으로. 30 분 내 머지 가능. 코드 리뷰는 가벼움.
2. **Phase 2 까지** — multi-agent-executor 권장. ~5 task (build script, gradle, endpoint, JS module, HTML/CSS). 각 phase 끝에 `bash scripts/restart-console.sh` 로 수동 검증.
3. **Phase 3 까지** — sidekick gradle 변경이 들어가서 `:fixthis-compose-sidekick:testDebugUnitTest` 가 SDK 경로 필요 (worktree 환경 hygiene). orchestrator phase 0 에서 `local.properties` 미리 prime 권장 (annotation-pin-visibility-by-anchor-open-risks H5 와 같은 패턴).

머지 후 검증 명령어 (모든 phase):
```bash
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
bash scripts/restart-console.sh && curl -s http://localhost:60006/api/server-version | jq .
```
