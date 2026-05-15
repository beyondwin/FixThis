# Spec — Deep Review Cleanup (Items 1–5)

Status: Draft
Date: 2026-05-15
Baseline: `main @ 5d39617f`
Scope: `:fixthis-mcp` console assets/server, `:fixthis-cli` test harness, `fixthis-mcp/src/main/console/` JS modules
Related implementation plan: [`../plans/2026-05-15-deep-review-cleanup-implementation.md`](../plans/2026-05-15-deep-review-cleanup-implementation.md)

## Summary

심층 코드 리뷰(2026-05-15)에서 검증된 5개 결함 후보가 추출됐다. 각각 단독으로는 작지만 운영 진단성·테스트 결정성·향후 리팩터 비용을 좌우하는 항목이라 한 사이클에 묶어 정리한다.

대상 결함:

1. `FeedbackConsoleAssets`가 `reproducible` 메타 빌드에서 콘솔 index를 서빙할 때마다 `git rev-parse`를 fork하고 `Process`를 정리하지 않는다.
2. `FeedbackConsoleAssets`/`FeedbackConsoleServer`가 광역 catch에서 예외를 무음 처리해 운영 시 원인 추적이 불가능하다.
3. `AdbTest`가 마커 파일을 `Thread.sleep(10)` 루프로 폴링해 CI flakiness를 유발한다.
4. 콘솔 SPA 모듈이 `document`/`window` 레벨 리스너를 전역 등록하면서 teardown 경로가 없다. 현재는 무해하지만 HMR/세션 전환 도입 시 즉시 누수된다.
5. `McpServer`가 in-flight 요청 등록을 `isActive` 이중 체크 + finally remove 멱등성에 의존해 brittle하다. UNDISPATCHED launch와 등록 사이 race를 정면으로 검증하는 테스트가 없다.

비-목표(이 사이클에서 처리하지 않음):

- HTTP 라우트 안 `runBlocking` 패턴 전면 교체 — Ktor 마이그레이션 ADR이 별도로 필요.
- `SemanticsInspector` 비용 최적화 — 실측 프로파일 없이는 추정.
- 콘솔 메타 캐싱이 inotify/git refs 감시까지 가야 하는지 — 현 사이클은 모듈 수명 캐시까지만.

## Current Verification Baseline

리뷰 시점 통과 명령:

```bash
./gradlew :fixthis-mcp:test :fixthis-cli:test \
  :fixthis-compose-core:test :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test --continue
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
npm run console:test:all
git diff --check
```

추가로 직접 확인한 1차 자료:

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt:43-48`
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt:84`
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt:245`
- `fixthis-mcp/src/main/console/history.js`, `main.js` 등의 전역 `addEventListener` 호출 지점
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/McpServer.kt:43-77`

## Goals

- 콘솔 index 서빙이 git 호출 비용을 모듈 수명 동안 한 번만 지불한다.
- `git rev-parse` Process가 stdout/stderr 모두 배수되고 `waitFor(timeout)` 후 `destroy()`된다.
- 콘솔 자산/HTTP 서버의 광역 catch가 예외 메시지와 stack을 stderr/diagnostics로 흘린다(응답 본문 구조는 그대로).
- `AdbTest`가 sleep 폴링 없이 결정적으로 마커 도착을 기다린다.
- 콘솔 모듈이 모듈별 `disposeHandlers()`를 노출하고, 부트 컨트롤러가 한 곳에서 호출 가능한 teardown 슬롯을 갖는다.
- `McpServer`의 in-flight 등록을 단일 헬퍼로 묶어, "launch가 register 전에 완료"되는 race를 자극하는 단위 테스트를 통과한다.

## Non-Goals

- 콘솔 자산 빌드 메타(`console-build-meta.json`) 포맷 변경.
- `runBlocking` 라우트 패턴 일괄 제거.
- 콘솔 HMR/세션 전환 자체 도입(슬롯만 마련, 실제 호출자는 별도 작업).
- McpServer 동시성 모델 전면 재설계(InFlightRegistry API 자체는 유지).

## Decisions

1. **git SHA 캐시 키**: 캐시는 모듈(`object` 또는 `FeedbackConsoleAssets` 인스턴스) 수명. 프로세스 재시작 시 자동 무효화. refs 감시는 도입하지 않음 — 콘솔은 dev/loopback 전용이고 SHA는 화면 표시용 메타에 불과.
2. **stderr drain**: `ProcessBuilder.redirectErrorStream(true)`를 사용해 두 스트림을 합친다. 본문은 `git rev-parse --short HEAD` 한 줄이라 충돌하지 않는다.
3. **타임아웃**: `process.waitFor(2, TimeUnit.SECONDS)`. 초과 시 `destroy()` 후 `"unknown"`.
4. **예외 로깅 출력 경로**: 콘솔 서버는 이미 `FeedbackConsoleServer` 안에서 stderr를 쓰는 경로가 있으므로 동일 채널을 사용. 자산 로더는 정적 컨텍스트이므로 `System.err`로 직접 출력.
5. **AdbTest 동기화**: `CountDownLatch.await(5, SECONDS)`로 전환. fixture가 마커를 만드는 코드 경로에서 latch.countDown()을 호출하도록 fixture를 약간 수정.
6. **콘솔 teardown 컨벤션**: 각 모듈은 `init({...deps})` → `{ dispose }` 반환 또는 모듈 스코프 `let teardown = []` + `export function dispose()`. 기존 모듈 중 전역 리스너를 가진 것만 컨버전 — 일괄 리팩터는 비-목표.
7. **MCP 헬퍼 시그니처**: `private suspend fun trackRequest(message: McpRequest, registry: InFlightRegistry, writeResponse: suspend (String) -> Unit)` 한 함수로 launch + register + finally remove를 캡슐화. 외부 호출자는 한 줄.

## Risks

- **R1**: 콘솔 자산 캐시가 stale SHA를 표시할 수 있다. 완화: 캐시는 프로세스 수명 한정 + 빌드 메타가 `reproducible` 모드 전용이라 dev에서만 영향.
- **R2**: `redirectErrorStream(true)`가 stdout에 stderr 텍스트를 섞을 수 있다. 완화: 결과 문자열을 `\s+` trim 후 첫 토큰이 short SHA 정규식(`^[0-9a-f]{7,40}$`)에 매치할 때만 채택.
- **R3**: McpServer 헬퍼 추출이 기존 cancellation 시멘틱(`notifications/cancelled`)을 깨뜨릴 수 있다. 완화: 기존 `InFlightRegistryTest` + 신규 race 테스트 둘 다 통과해야 머지.
- **R4**: 콘솔 `dispose` 슬롯을 도입했는데 호출하는 곳이 없으면 dead code로 보일 수 있다. 완화: 헬퍼만 export하고, 부트 컨트롤러에 `window.__fixthisDispose` 디버그 훅 1개만 노출(테스트가 호출).

## Acceptance Criteria

- `FeedbackConsoleAssets`의 git fork가 콘솔 index N회 서빙 시 N>1에서도 1회만 발생함을 단위 테스트가 검증한다.
- `git` 바이너리가 PATH에 없는 환경(`PATH=""`)에서도 콘솔 index가 200을 반환하고, stderr에 한 줄의 진단 로그가 남는다.
- `FeedbackConsoleServer.handle`이 예외 응답을 보낼 때 stderr 또는 주입된 diagnostics sink에 `Throwable.toString()`이 기록된다(단위 테스트가 captured sink로 검증).
- `AdbTest`의 마커 대기 경로에 `Thread.sleep`이 없다(`grep -n "Thread.sleep" fixthis-cli/src/test`가 해당 라인에서 0회).
- 콘솔 부트 시 등록된 전역 리스너 수와 `dispose()` 호출 후 등록 수가 동일(테스트 헬퍼가 `EventTarget` 카운트).
- `McpServer`의 race 테스트(launch가 register 전에 즉시 완료되는 경로)가 추가되고 통과한다. 기존 `InFlightRegistryTest`도 통과.
- 최종 검증 명령(Verification Baseline)이 모두 통과한다.

## Out-of-Scope Follow-ups

- Ktor 기반 라우트 마이그레이션 ADR.
- 콘솔 모듈 전체 IoC 도입(현재는 전역 리스너 보유 모듈만 부분 컨버전).
- `SemanticsInspector` 비용 프로파일링.
