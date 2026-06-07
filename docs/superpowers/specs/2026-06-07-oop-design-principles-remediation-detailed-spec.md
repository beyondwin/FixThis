# FixThis OOP 설계 원칙 리메디에이션 (엄브렐라) 상세 스펙

Date: 2026-06-07
Status: Draft for review
Scope: `:fixthis-cli` setup 커맨드 계층, `:fixthis-compose-core` `SourceMatcher`,
`:fixthis-mcp` `FeedbackSessionStoreDelegate`. 세 트랙은 독립적으로 머지 가능하며
Track A → B → C 순서로 순차 실행한다.
Related implementation plan:
[`../plans/2026-06-07-oop-design-principles-remediation-implementation.md`](../plans/2026-06-07-oop-design-principles-remediation-implementation.md)

## Summary

세 군데의 설계 부채를 인프런 *이펙티브 자바*·*오브젝트 — 설계 원칙편* 강의 원칙과
1:1로 매핑해 제거한다. **모두 동작 보존(behavior-preserving) 리팩토링이며 제품 동작
변경이나 새 기능 추가가 아니다.** 기존 테스트(현재 801개 통과)는 전 과정에서 녹색을
유지해야 한다.

- **Track A — CLI 커맨드 계층 (1순위, 위험도·학습효과 최대).**
  `SetupCommand` / `InitCommand` / `InstallAgentCommand`가 서로를 CLI argv 문자열로
  재직렬화·재파싱해 호출하고, 누적 결과를 전역 가변 `ThreadLocal` 싱글톤
  `SetupRunResults`에 쌓으며, 중첩 호출의 표준출력을 `System.setOut`으로 통째 갈아끼워
  억제한다. 이 셋은 한 덩어리의 결합이다. 도메인 동작을 `SetupService`(순수 협력 객체)로
  추출하고, 누적 결과는 `SetupReport`를 명시적으로 주입해 받는다. 전역 상태와
  stdout 해킹, 테스트 전용 쌍둥이 메서드, `Pair<Triple<…>>` 익명 튜플이 모두 사라진다.
- **Track B — `SourceMatcher` (2순위).** 점수 가중치 `when` 분기를
  `SourceSignalKind` 열거 상수의 고유 필드로 옮기고(열거 타입에 행위 부여), 엔트리
  내부를 헤집어 계산하는 weight-hit 확장 함수들을 데이터 주인인 `SourceIndexEntry`로
  이동한다(묻지 말고 시켜라).
- **Track C — `FeedbackSessionStoreDelegate` 마무리 (3순위, 범위 최소).** 이벤트 백킹
  뮤테이션의 익명 `Pair<JsonObject, () -> T>`를 명명 타입 `EventBackedMutation<T>`로
  교체하고, `compactionFailureSink` 유무로 갈리는 생성자 분기를 단일 nullable 경로로
  통합한다.

모듈 경계(ADR-0001/0002, `compose-core`의 무(無) android/MCP/CLI 의존)는 그대로
유지된다. 세 트랙 모두 모듈 간 의존 방향을 바꾸지 않는다.

## Analysis Method

분석에 사용한 명령(2026-06-07, branch `main`):

```bash
# 실제 코드(픽스처 제외) 크기 핫스팟
find fixthis-compose-core fixthis-compose-sidekick fixthis-mcp fixthis-cli \
  fixthis-gradle-plugin -path "*/src/main/*" -name "*.kt" -exec wc -l {} + | sort -rn | head -31

# Track A 결합 증거
#  - 전역 가변 싱글톤
grep -n "object SetupRunResults" fixthis-cli/src/main/.../commands/SetupCommand.kt
#  - 커맨드가 커맨드를 argv로 재파싱
grep -n "SetupCommand().parse\|InitCommand().parse" fixthis-cli/src/main/.../commands/SetupCommand.kt
#  - stdout 리다이렉트 해킹
grep -n "System.setOut" fixthis-cli/src/main/.../commands/SetupCommand.kt
#  - 전역 상태가 commit 클래스까지 침투
grep -n "SetupRunResults.applied.get()" fixthis-cli/src/main/.../commands/TwoPhaseConfigCommit.kt

# Track B
grep -n "baseMatchWeight\|signalOrLegacyWeightHit\|bestSignalHit" \
  fixthis-compose-core/src/main/.../source/SourceMatcher.kt
sed -n '38,64p' fixthis-compose-core/src/main/.../source/SourceIndex.kt   # SourceSignal/Kind

# Track C
grep -n "Pair<JsonObject\|compactionFailureSink" \
  fixthis-mcp/src/main/.../session/lifecycle/store/FeedbackSessionStoreDelegate.kt
```

## 원칙 매핑

| 발견 | 위치 | 위반 원칙 | 강의 |
| --- | --- | --- | --- |
| 전역 가변 `ThreadLocal` 싱글톤에 커맨드들이 쓰고 읽음 | `SetupCommand.kt:25-34`, `TwoPhaseConfigCommit.kt:97` | 가변성·별칭, 의존 객체 주입 | EJ Item 17·Item 5 / 오브젝트 4-1 |
| 커맨드가 커맨드를 argv 재직렬화로 호출 | `SetupCommand.kt:252-273, 409-436` | 디미터 법칙, 의존성 역전 | 오브젝트 6-1, 7-2/7-3 |
| `System.setOut` 전역 리다이렉트로 출력 억제 | `SetupCommand.kt:476-488` | 명령-쿼리 분리 | 오브젝트 6-2 |
| 테스트 전용 쌍둥이 메서드 + `Pair<Triple<…>>` | `SetupCommand.kt:120-141` | 테스트 가능성=DI, 명명 타입 | EJ Item 5 |
| 가중치 `when(SourceSignalKind)` 분기 | `SourceMatcher.kt:508-530` | int 상수 대신 열거 타입 | EJ Item 34 / 오브젝트 8-3 |
| weight-hit 확장 함수가 엔트리 내부를 헤집음 | `SourceMatcher.kt:400-504` | 묻지 말고 시켜라(feature envy) | 오브젝트 6-1 |
| 익명 `Pair<JsonObject, () -> T>` | `FeedbackSessionStoreDelegate.kt:455, 471` | 명명 타입 선호 | EJ — 데이터 묶음은 클래스로 |

강의 링크: 이펙티브 자바 [1부](https://www.inflearn.com/courses/lecture?courseId=328628)·[2부](https://www.inflearn.com/courses/lecture?courseId=329668) /
오브젝트 — 설계 원칙편 [코스](https://www.inflearn.com/courses/lecture?courseId=336658)
(6-1 [디미터·묻지말고시켜라](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=280443),
6-2 [CQS](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=280444),
7-2 [DIP](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=283585),
8-3 [다형적 OCP](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=283699)).

---

## Track A — CLI setup 커맨드 계층 결합 해체

### 현재 구조

```
InstallAgentCommand.run()
  ├─ GlobalScopeGuard.decide(...)               // 순수 (유지)
  ├─ captureStdoutWhenJson(json) {              // System.setOut 해킹
  │     InitCommand().parse(argv 문자열 리스트)   // 커맨드→커맨드 재파싱
  │        └─ SetupCommand().parse(argv 문자열)   // 다시 재파싱
  │              └─ TwoPhaseConfigCommit.commit() // 여기서 전역 SetupRunResults.applied 에 push
  │  }
  └─ SetupRunResults.applied/skipped/errors.get() 를 읽어 JSON 리포트 구성
```

세 가지 결합이 한 덩어리다:

1. **전역 가변 상태.** `SetupRunResults`(`object` + `ThreadLocal<MutableList>`)에 한
   호출 경로가 쓰고(`TwoPhaseConfigCommit.kt:97`, `SetupCommand.applyWritePlan:158`)
   다른 호출 경로가 읽는다(`InstallAgentCommand.run:439-462`). 누적 상태가 인자가
   아니라 숨은 채널로 흐른다. → EJ Item 17(가변성 최소화)·Item 5(의존 객체 주입),
   오브젝트 4-1(별칭 문제).
2. **argv 왕복.** `InstallAgent → Init → Setup`이 서로의 옵션을 문자열 리스트로
   재조립해 `.parse()`로 호출한다. 호출자가 피호출자의 CLI 표면 전체를 알아야 하고,
   옵션 하나만 바뀌어도 문자열 조립부가 깨진다. → 오브젝트 6-1(디미터), 7-2/7-3(DIP).
3. **stdout 리다이렉트.** 중첩 커맨드가 사람용 메시지를 echo하므로, JSON 모드에서
   이를 숨기려 `System.setOut(...)`으로 전역 표준출력을 통째 교체한다(`:476-488`).
   이는 (1)·(2)의 증상이다. 도메인 로직을 서비스로 빼면 echo 자체가 서비스 밖으로
   나가므로 해킹이 불필요해진다. → 오브젝트 6-2(명령-쿼리 분리).

### 목표 설계

순수 도메인 동작을 `SetupService`로 추출한다. 부수효과(파일 쓰기)는 서비스가 맡되,
**사람용 출력(echo)과 누적 리포트는 호출자가 주입한 협력 객체로 분리**한다.

```kotlin
// 신규: SetupReport — 누적 결과를 담는 명시적 가변 컬렉터(전역 아님, 호출 스코프 소유)
internal class SetupReport {
    val applied = mutableListOf<InstallAgentJsonReport.Applied>()
    val skipped = mutableListOf<InstallAgentJsonReport.Skipped>()
    val errors = mutableListOf<InstallAgentJsonReport.ErrorEntry>()
}

// 신규: SetupService — 패키지 해석/플랜 생성/원자적 쓰기. echo·report는 주입받음.
internal class SetupService(
    private val report: SetupReport,
    private val emit: (String) -> Unit,
) {
    fun writeConfigs(request: SetupRequest): Unit       // = 기존 SetupCommand.run의 --write 경로
    fun installAgent(request: InstallRequest): Unit     // = 기존 InitCommand.writeInstallArtifacts + gradle/agent files
}
```

- 세 커맨드(`SetupCommand`/`InitCommand`/`InstallAgentCommand`)는 옵션을 파싱해
  `SetupRequest`/`InstallRequest` 값 객체로 만들고 `SetupService`를 **직접 호출**한다.
  더 이상 서로를 `.parse(argv)`로 호출하지 않는다.
- `SetupReport`는 `InstallAgentCommand.run()`에서 한 번 생성해 서비스에 주입하고,
  실행 후 그 인스턴스에서 직접 JSON 리포트를 만든다. 전역 `SetupRunResults` 삭제.
- JSON 모드에서 echo를 숨길 때는 `emit = {}`(no-op 람다)를 주입한다. `System.setOut`
  교체와 `captureStdoutWhenJson` 삭제.
- `TwoPhaseConfigCommit`은 `SetupRunResults.applied`에 직접 쓰던 줄(`:97-101`)을
  제거하고, 커밋된 plan을 호출자(`SetupService`)가 `report.applied`에 기록한다.
- `runWritePlansAtomicForTest` / `applyWritePlanForTest` 및 `Pair<Triple<String,
  String, File>>` 시그니처 삭제. 테스트는 `SetupService`에 가짜 `emit`/`SetupReport`,
  그리고 이미 존재하는 `TwoPhaseConfigCommit`의 주입 seam을 그대로 사용한다.

### 동작 보존 계약

- `fixthis setup`(--write 없음): stdout에 MCP client config JSON 그대로.
- `fixthis setup --write` / `--dry-run`: 쓰는 파일 집합·내용·echo 문구 동일.
- `fixthis install-agent --json`: stdout의 JSON 리포트 바이트 동일(applied/skipped/
  errors/next/readiness/restartRequired 필드와 순서 유지).
- 종료 코드: OK=0, skipped=`ExitCode.PARTIAL`, errors=`ExitCode.INTERNAL_ERROR`,
  no-android=`ExitCode.PARTIAL` 유지.
- `GlobalScopeGuard` 결정 로직과 `effectiveTarget` 강등(codex→none, all→local) 유지.

---

## Track B — `SourceMatcher` 점수 책임 재배치

### B-1. 가중치를 열거 타입으로 (EJ Item 34)

현재(`SourceMatcher.kt:508-530`):

```kotlin
private val SourceSignalKind.baseMatchWeight: Double
    get() = when (this) {
        SourceSignalKind.STRICT_COMP_TEST_TAG -> 1.15
        ...
        SourceSignalKind.ARBITRARY_STRING_LITERAL -> 0.35
    }
```

문제: 새 `SourceSignalKind`를 추가하면서 이 `when`을 갱신하지 않으면 컴파일은
통과하되(분기는 exhaustive하지만 추가 시점에 누락 감지가 없음) 의미상 0점 처리되는
경로가 생기기 쉽고, 가중치 정의가 enum과 떨어져 있어 응집도가 낮다.

목표: 가중치는 시그널 종류의 고유 속성이므로 `SourceSignalKind` 생성자 인자로 이동.

```kotlin
@Serializable
enum class SourceSignalKind(val baseMatchWeight: Double) {
    COMPOSABLE_SYMBOL(1.0),
    UI_TEXT(1.0),
    STRING_RESOURCE(0.85),
    TEST_TAG(1.0),
    STRICT_COMP_TEST_TAG(1.15),
    CONTENT_DESCRIPTION(1.0),
    ROLE(0.85),
    ACTIVITY_NAME(0.85),
    ARBITRARY_STRING_LITERAL(0.35),
    STRING_RESOURCE_RESOLVED(1.0),
    LAMBDA_OWNER_FUNCTION(1.0),
    LAZY_ITEM_OWNER(1.0),
    NAV_DESTINATION_OWNER(1.0),
    MODIFIER_TARGET(1.0),
    LAYOUT_RENDERER(0.75),
    SHARED_COMPONENT(0.0),
    SHARED_COMPONENT_CALL_SITE(0.0),
}
```

`SourceMatcher`의 `baseMatchWeight` 확장 프로퍼티와 `LAYOUT_RENDERER_BASE_WEIGHT`
상수를 삭제하고 `signal.kind.baseMatchWeight`를 직접 참조. 값은 표와 1:1 동일하므로
점수 결과 불변.

> 주의: `SourceSignalKind`는 `@Serializable enum`이다. 직렬화 와이어 포맷은 enum
> **이름**만 사용하므로 생성자 인자 추가는 JSON 호환성에 영향이 없다(스펙 검증 항목).

### B-2. 묻지 말고 시켜라 — weight-hit를 엔트리로 이동 (오브젝트 6-1)

현재 `SourceMatcher`는 `SourceIndexEntry`에 대한 private 확장 함수
(`textLikeWeightHit`, `contentDescriptionWeightHit`, `testTagWeightHit`,
`roleWeightHit`, `activityWeightHit`, `conventionComposableWeightHit`,
`signalOrLegacyWeightHit`, `bestSignalHit`)로 엔트리의 `signals`/`text`/`symbols`/
`excerpt` 등을 꺼내 가중치를 계산한다. 데이터는 엔트리 소유인데 계산은 매처가 한다 —
전형적 feature envy.

목표: weight-hit 계산을 `SourceIndexEntry`(또는 `compose.core.source` 내 동반
파일 `SourceIndexEntryMatching.kt`)의 멤버/확장으로 옮긴다. `WeightHit`,
`matchesAny`, `normalizedForMatch`, `legacyWeight`도 같은 책임 단위이므로 동반 이동.
`SourceMatcher`는 "엔트리에게 가중치를 물어보고 점수를 조합"만 남는다.

```kotlin
// compose.core.source 내부(엔트리 소유 책임)
internal data class WeightHit(val weight: Double, val signalKind: SourceSignalKind?, val viaLegacy: Boolean)

internal fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit = ...
internal fun SourceIndexEntry.contentDescriptionWeightHit(term: String): WeightHit = ...
// ... (현재 SourceMatcher의 :400-504 본문을 그대로 이동, 동작 불변)
```

`SourceMatcher`는 `entry.textLikeWeightHit(term)` 형태로 호출. 로직은 글자 그대로
이동하므로 점수 결과 불변. 이 단계는 순수 이동(move refactor)이며 B-1 이후에 한다
(이동 대상 코드가 `baseMatchWeight`를 참조하기 때문).

### 동작 보존 계약

- 동일 입력(selectedNode/nearbyNodes/activityName + SourceIndex)에 대해
  `match()` 결과(파일·라인·score·matchReasons·confidence·riskFlags·callSites)가
  완전히 동일. 기존 `SourceMatcher*Test`와 코퍼스 정밀도 게이트가 회귀 가드.

---

## Track C — `FeedbackSessionStoreDelegate` 마무리 다듬기

이미 6개 협력 객체(`journal`, `replayEngine`, `bootReplayer`,
`compactionCoordinator`, `mutations`, `artifactJanitor`)로 분해돼 DIP가 적용된
상태다. 남은 두 가지만 다듬는다(범위 최소, 동작 불변).

### C-1. 익명 Pair → 명명 타입

`withEventBackedMutation`/`withOptionalEventBackedMutation`이 받는
`Pair<JsonObject, () -> T>`는 "이벤트 페이로드 + 지연 적용 람다"라는 도메인 개념이다.
명명 타입으로 의도를 드러낸다.

```kotlin
internal data class EventBackedMutation<T>(
    val payload: JsonObject,
    val apply: () -> T,
)
```

`prepare` 콜백들의 `… to { … }` 반환을 `EventBackedMutation(payload) { … }`로 교체.
호출부 구조 동일, 가독성만 향상. 동작 불변.

### C-2. 생성자 분기 통합

현재(`:82-95`) `compactionFailureSink == null` 여부로 `SessionCompactionCoordinator`
생성자를 if/else로 가른다. `SessionCompactionCoordinator`가 nullable
`compactionFailureSink`를 받도록(또는 이미 받는다면) 단일 경로로 통합한다.

> 검증 필요: `SessionCompactionCoordinator` 생성자가 nullable sink를 수용하는지 구현
> 확인 후, 비수용이면 nullable 오버로드 추가. 수용하면 delegate의 if/else만 제거.

### 동작 보존 계약

- 모든 세션 뮤테이션의 이벤트 로그 append 순서·실패 시 fail-stop·compaction 트리거
  타이밍 불변. 기존 ~482개 세션 테스트가 회귀 가드.

---

## 순서·의존성

```
Track A (CLI)  ── 독립, 먼저 (위험도 최대, 학습효과 최대)
   └─ 머지 후 →
Track B (SourceMatcher)  ── B-1(열거 가중치) → B-2(weight-hit 이동) 순서 강제
   └─ 머지 후 →
Track C (delegate)  ── 독립, 마지막 (범위 최소)
```

각 트랙은 그 자체로 빌드·테스트 통과하는 완결 단위다. 트랙 간 파일 겹침이 없어
순서는 권장(위험 순)일 뿐 기술적 강제는 아니나, 사용자 요청에 따라 A→B→C로 순차
실행한다. 트랙 **내부** 단계는 위에 명시한 순서를 지킨다(B-1→B-2, A는 서비스 추출→
커맨드 배선→전역/해킹 제거 순).

## 비목표 (Non-Goals)

- 제품 동작·CLI 표면·JSON 와이어 포맷 변경 없음.
- `compose-core`의 도메인 포트/유즈케이스 신규 도입 없음(별도 스펙 영역).
- 모듈 경계·의존 방향 변경 없음.
- `SourceMatcher`의 점수 정책(버킷/캡/리스크) 튜닝 없음 — 순수 구조 이동만.

## 리스크 & 완화

| 리스크 | 완화 |
| --- | --- |
| Track A에서 JSON 리포트 바이트가 미세하게 달라짐 | 리팩토링 전 `install-agent --json` 골든 출력을 캡처해 문자열 동등 비교 테스트로 고정 |
| `SetupRunResults` 삭제 시 누락 참조 | grep `SetupRunResults` 0건 확인을 완료 게이트에 포함 |
| B-1 enum 인자 추가가 직렬화에 영향 | enum 라운드트립 직렬화 테스트로 와이어 이름 불변 확인 |
| B-2 이동 중 미세한 로직 변형 | 코퍼스 정밀도 게이트 + 기존 `SourceMatcher*Test` 녹색 유지, diff는 순수 이동만 |
| C-2에서 sink 미수용 | 구현 확인 전제 단계를 plan Task에 포함 |

## 검증 (완료 게이트)

전 트랙 공통, [`CONTRIBUTING.md`](../../../CONTRIBUTING.md#required-local-checks) 기준:

```bash
./gradlew :fixthis-cli:test :fixthis-compose-core:test :fixthis-mcp:test
./gradlew spotlessCheck detekt
git diff --check
grep -rn "SetupRunResults" fixthis-cli/src   # Track A 완료 시 0건
grep -rn "System.setOut" fixthis-cli/src     # Track A 완료 시 0건(테스트 제외)
```
