# OOP 핫스팟 리팩토링 — 설계 스펙

- **날짜**: 2026-06-07
- **상태**: Draft (구현 대기)
- **성격**: 순수 행위 보존(behavior-preserving) 구조 개선. 신규 동작 없음.
- **근거**: 조영호 『오브젝트』 — 기초편(courseId 334416), 설계 원칙편(courseId 336658).
  - 단일 책임 원칙([5-1](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=276707))
  - 의존성을 기준으로 분리하기([5-5](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=279485))
  - 단일 추상화 수준 원칙 / 조합 메서드([3-1](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=276193), [3-2](https://www.inflearn.com/courses/lecture?courseId=336658&unitId=276244))
  - 절차에서 객체로(기초편 2-4)

## 1. 배경과 목표

FixThis 코드베이스는 이미 상당히 모듈화되어 있고(타깃 sealed interface — ADR-0009,
session 패키지 분해 — ADR-0008), 명백한 enum-switch·디미터 위반은 정리된 상태다. 남은
설계 부채는 **단일 책임 / 단일 추상화 수준** 관점의 3개 핫스팟에 집중되어 있다.

목표는 외부에서 관찰 가능한 동작을 일절 바꾸지 않으면서, 한 단위가 "무엇을 하고, 어떻게
쓰며, 무엇에 의존하는지"를 한눈에 답할 수 있도록 책임 경계를 정리하는 것이다.

**비목표(Non-goals)**
- 새 기능·새 동작 추가. 점수/이벤트 로그/원자 커밋의 결과는 byte/value 동일해야 한다.
- 관련 없는 리팩토링(스타일 정리, 무관한 파일 이동).
- 공개 API 시그니처 변경. `FeedbackSessionStore`, `SourceMatcher`, `SetupCommand`의
  외부 진입점 시그니처는 불변.

## 2. 성공 기준

- 전체 Gradle 매트릭스 + 콘솔 에셋 체크 + detekt + spotless 통과(CONTRIBUTING § Required local checks).
- 기존 테스트(~801개) 전부 그린. 신규 테스트는 추출된 협력 객체의 단위 테스트로 추가.
- `FeedbackSessionStoreDelegate`, `SourceMatcher.score()`, `SetupCommand.runWritePlansAtomic`
  각각이 단일 추상화 수준으로 읽힌다(고수준 메서드 안에 저수준 직렬화/락/트랜잭션 세부가 섞이지 않음).
- `detekt`의 `LargeClass`/`LongMethod`/`TooManyFunctions` 억제 주석 제거 가능.

---

## 3. 대상 1 — `FeedbackSessionStoreDelegate` 협력 객체 분해

### 3.1 현재 상태

`fixthis-mcp/.../session/lifecycle/store/FeedbackSessionStoreDelegate.kt` (696줄).
`@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")`가 달려 있고 주석으로
"split into smaller responsibilities once the event-log API stabilises — see #ALH-followup"
라고 분해 부채가 명시되어 있다. 공개 facade `FeedbackSessionStore`가 이 delegate에 1:1로
위임한다.

한 클래스가 가진 변경 이유(책임):

| # | 책임 | 현재 멤버 |
|---|---|---|
| A | 세션 수명주기 | `openSession`, `closeSession`, `openExistingSession`, `currentSession`, `listSessions`, `currentSessionId` |
| B | 도메인 변이 | `addScreen`, `addScreenWithItems`, `deleteScreen`, `addItem`, `clearDraftItems`, `sendDraftToAgent`, `markItemsHandedOff`, `markReadyForAgent`, `updateItemStatus`, `claimFeedback`, `updateDraftItem`, `deleteDraftItem`, `*ForDomain` |
| C | 이벤트 기반 변이 오케스트레이션 | `withEventBackedMutation`, `withOptionalEventBackedMutation`, `requireOpenSessionForMutation` |
| D | 압축 throttle + 락 | `CompactionFailureThrottleState`, `compactEventLogAfterMutation`, `shouldEmitCompactionFailure`, `resetCompactionFailureThrottle`, `compactionLock`, `compactionLocks`, `compactionFailureThrottle` |
| E | 부트 리플레이 오케스트레이션 | `init` 리플레이 루프, `replaySessionEvents`, `recordReplaySkippedSession`, `replaySkippedSessionList`, `replaySkippedSessions` |
| F | 이벤트 payload JSON 직렬화 | `putItems`, `addScreenWithItemsPayload`, 변이 메서드마다 인라인된 `buildJsonObject{...}` |
| G | 인메모리 상태 + 영속화 R/W | `sessions` 맵, `save`, `getSessionLocked`, `loadPersistedSessionIfAvailable`, `commitSessionMutation` |

A·B·C는 delegate의 본래 책임(세션 상태 머신). D·E·F·G는 분리 가능한 협력 책임이다.

### 3.2 추출 설계

기존 패턴(`SessionMutationService` 124줄, `SessionReplayEngine` 313줄, `SessionArtifactJanitor`)을
따라 같은 `session/lifecycle/` 하위에 협력 객체를 추가한다. 모두 delegate가 생성자에서
조립하고, delegate는 호출만 한다.

#### (D) `SessionCompactionCoordinator`
- **위치**: `session/lifecycle/event/eventlog/SessionCompactionCoordinator.kt`
- **책임**: 세션별 압축 락, 임계치 기반 `runOnce` 호출, 실패 throttle 상태, 실패 sink 호출.
- **흡수 멤버**: `CompactionFailureThrottleState`, `compactEventLogAfterMutation`,
  `shouldEmitCompactionFailure`, `resetCompactionFailureThrottle`, `compactionLock`,
  `compactionLocks`, `compactionFailureThrottle`, 상수 `COMPACTION_FAILURE_EMIT_EVERY`,
  `COMPACTION_FAILURE_EMIT_WINDOW_MILLIS`, `logCompactionFailure`.
- **인터페이스**: `fun compactAfterMutation(sessionId: String)`. 생성자: `compactorProvider`,
  `threshold`, `clock`, `failureSink`.
- **의존**: `EventLogCompactionTask`. delegate의 `lock`과 독립된 자체 동기화를 유지하되,
  throttle 상태 접근은 코디네이터 내부 동기화로 캡슐화(현재 `synchronized(lock)`로 보호되던
  의미를 코디네이터 내부 락으로 이전 — 외부 관찰 동작 동일).

#### (F) `SessionEventPayloadFactory`
- **위치**: `session/lifecycle/event/SessionEventPayloadFactory.kt`
- **책임**: 변이 → 이벤트 로그 `JsonObject` payload 직렬화 전담.
- **흡수 멤버**: `putItems`, `addScreenWithItemsPayload`, 변이 메서드 내 인라인
  `buildJsonObject{...}`(screen/item/screenId/itemId/items 페이로드), 모듈 `eventLogJson`.
- **인터페이스**: 의도별 팩토리 함수 — `screen(...)`, `screenWithItems(...)`, `item(...)`,
  `items(...)`, `deleteScreen(...)`, `deleteItem(...)`, `updateDraftItems(...)`. 기존
  `SessionEventPayloads`와 역할이 겹치면 그쪽으로 통합 검토(중복 제거, 단일 소스).
- **불변식**: 직렬화 결과 JSON은 기존과 **byte 동일**(이벤트 로그 호환성 계약 — CLAUDE.md).

#### (G) `SessionStateStore`
- **위치**: 기존 `session/lifecycle/store/SessionStateCache.kt`(39줄) 확장 또는 신규
  `SessionStateStore.kt`.
- **책임**: 인메모리 `sessions` 맵 소유 + 영속화 읽기/쓰기 + 시퀀스 마이그레이션 보정.
- **흡수 멤버**: `sessions`, `save`, `getSessionLocked`, `loadPersistedSessionIfAvailable`,
  `commitSessionMutation`.
- **인터페이스**: `get(sessionId)`, `put(session)`, `commit(previous, updated)`,
  `loadIfPersisted(sessionId)`, `all()`. 동기화는 delegate의 단일 `lock`을 계속 사용하기
  위해 store는 lock-free로 두고 **호출 측(delegate)이 `synchronized(lock)` 안에서 호출**하는
  현재 계약을 유지(잠금 의미 보존). `currentSessionId` 포인터는 수명주기 책임이므로
  delegate에 잔류.

#### (E) `SessionBootReplayer`
- **위치**: `session/lifecycle/event/SessionBootReplayer.kt`
- **책임**: 부트 시 알려진 세션들에 대한 리플레이 루프 + 스킵된 세션 추적.
- **흡수 멤버**: `init`의 리플레이 루프, `replaySessionEvents`, `recordReplaySkippedSession`,
  `replaySkippedSessionList`, `replaySkippedSessions`.
- **인터페이스**: `fun replayAll(stateStore, journal): ReplayResult`(스킵 목록 포함),
  `fun skippedList(packageName, includeClosed): List<SkippedFeedbackSession>`.
- **의존**: 계산은 이미 `SessionReplayEngine`이 수행. 이 객체는 오케스트레이션만 담당.
- **주의**: `init` 블록은 `lock` 미보유 시점이므로 현 계약(비동기화 호출) 유지.

### 3.3 분해 후 delegate

`FeedbackSessionStoreDelegate`는 A(수명주기) + B(도메인 변이) + C(이벤트 변이
오케스트레이션)만 남고, D/E/F/G는 협력 객체에 위임한다. 목표 라인 수: 696 → ~350 이하,
`@Suppress("LargeClass")` 제거.

```
FeedbackSessionStore (facade, 불변)
  └─ FeedbackSessionStoreDelegate (수명주기 + 도메인 변이 + 변이 오케스트레이션)
       ├─ SessionStateStore         (G: 인메모리 맵 + 영속화)
       ├─ SessionEventPayloadFactory (F: payload 직렬화)
       ├─ SessionCompactionCoordinator (D: 압축 + throttle)
       ├─ SessionBootReplayer        (E: 부트 리플레이 오케스트레이션)
       ├─ SessionEventJournal        (기존)
       ├─ SessionMutationService     (기존)
       └─ SessionReplayEngine        (기존, BootReplayer가 사용)
```

### 3.4 대안 검토

- **(a) 점진 협력 객체 추출 — 권장**: 위 4개를 순차 추출. 각 단계가 테스트로 보호되고
  되돌리기 쉬움.
- **(b) delegate 전면 재작성**: 이벤트 로그 동기화/잠금 의미를 한 번에 재설계. 위험 대비
  이득 불균형, byte 호환성 회귀 위험 큼. 기각.
- **(c) throttle만 추출**: 가장 작지만 god-class 잔존. 부채 미해소. 기각.

---

## 4. 대상 2 — `SourceMatcher.score()` 조합 메서드화

### 4.1 현재 상태

`fixthis-compose-core/.../source/SourceMatcher.kt:87` `score()`는 155줄(87~240)에 걸쳐:
- `selectedNode`의 text/editableText/contentDescription/stateDescription/testTag/role 누산(101~143)
- `nearbyNodes`의 동일 필드 반복 누산(145~191)
- `activityName` 누산(193~200)
- 파생 reason 후처리(202~232) — arbitrary literal / untyped fallback / layout renderer /
  shared component 마커.

거의 동일한 `addIfMatches(...)` 블록이 6개 이상 펼쳐져 단일 추상화 수준·DRY가 약하다.

### 4.2 추출 설계 (SourceMatcher 내부 private 추출만)

- `private fun scoreSelectedNode(entry, selectedNode, accumulator): Double` — 101~143 이관.
- `private fun scoreNearbyNodes(entry, nearbyNodes, accumulator): Double` — 145~191 이관.
- `private fun scoreActivity(entry, activityName, accumulator): Double` — 193~200 이관.
- `private fun deriveDerivedReasons(entry, ctx, matchReasons, sharedOwners): Unit` — 202~232 이관.

`score()`는 다음처럼 읽히는 조합 메서드가 된다:

```
var raw = 0.0
raw += scoreSelectedNode(entry, selectedNode, acc)
raw += scoreNearbyNodes(entry, nearbyNodes, acc)
raw += scoreActivity(entry, activityName, acc)
deriveDerivedReasons(entry, ctx, matchReasons, sharedOwners)
return MatchScore(entry, raw, matchedTerms.toList(), matchReasons.toList())
```

### 4.3 불변식

- 외부 API(`match`, companion `match`)와 `SourceCandidate` 결과는 불변.
- 점수·reason·정렬 결과가 **값 동일**. 누산 순서를 보존(선택→근접→액티비티) — `scoredEvidence`
  중복 제거와 reason `linkedSet` 순서가 결과에 영향을 주므로 순서 변경 금지.

---

## 5. 대상 3 — `SetupCommand.runWritePlansAtomic` 트랜잭션 객체 추출

### 5.1 현재 상태

`fixthis-cli/.../commands/SetupCommand.kt:99` `runWritePlansAtomic`는 3-phase 원자 커밋:
1. Phase 1: 전체 쓰기를 `.fixthis-staging`으로 stage + fsync.
2. Phase 1.5: 기존 타깃을 `.fixthis-rollback`으로 스냅샷.
3. Phase 2: `ATOMIC_MOVE`(미지원 시 copy+delete 폴백) 커밋 + 부모 디렉터리 fsync, 실패 시
   롤백 복원.

이 트랜잭션 알고리즘이 CLI 명령 메서드에 인라인되어 SRP·단일 추상화 수준을 깬다. 이미 옆에
`AtomicConfigFileWriter`(fsync seam)가 존재한다.

### 5.2 추출 설계

- **위치**: `fixthis-cli/.../commands/TwoPhaseConfigCommit.kt`
- **책임**: `List<SetupWritePlan>`을 받아 stage→snapshot→commit→(실패 시)rollback 하는
  원자 커밋 트랜잭션 전담.
- **주입 seam 이관**(테스트 호환 위해 시그니처 보존): `move`, `forceFile`, `forceDirectory`,
  `copyForRollback`, `emit`.
- **인터페이스**: `fun commit(plans: List<SetupWritePlan>)`. `CliktError` 메시지 문구·예외
  타입·`SetupRunResults.applied` 누적은 그대로 유지.
- `SetupCommand`는 plan 생성 후 `TwoPhaseConfigCommit(...).commit(plans)`만 호출.
  `runWritePlansAtomicForTest`도 동일 객체로 위임(중복 제거).

### 5.3 불변식

- dry-run 경로(`applyWritePlan(..., dryRun=true)`)는 변경 없음.
- 스테이징/롤백 파일명(`.fixthis-staging`, `.fixthis-rollback`), 에러 메시지, fsync 순서,
  `SetupRunResults.applied` 보고 동작 모두 동일.

---

## 6. 교차 위험과 검증

- **경계 테스트 path-key**: `ArchitectureHotspotBudgetTest` 및 패키지 경계 테스트가 파일
  경로/클래스 목록에 의존. 신규 파일 추가 시 해당 예산/허용 목록 갱신 필요(과거 session 분해
  시 동일 이슈 발생).
- **이벤트 로그 byte 호환성**: payload 직렬화 변경은 호환성 계약(CLAUDE.md). 직렬화 결과
  동일성 회귀 테스트로 가드.
- **동시성 의미 보존**: delegate `lock`의 보호 범위가 협력 객체 추출로 바뀌면 안 됨. store는
  lock-free, 코디네이터는 자체 락 — 현재 관찰 동작과 동일함을 테스트로 확인.
- **EventLogFailureModeTest**: macOS APFS rename 의미로 로컬에서 `renameFailure` 실패는
  기존 이슈(회귀 아님). CI/Linux가 권위.
- **검증 절차**: 각 추출 단계 후 관련 모듈 테스트 → 전체 단계 완료 후 full gradle matrix +
  detekt + spotless + `git diff --check`.

## 7. 산출물

- 본 스펙: `docs/superpowers/specs/2026-06-07-oop-hotspot-refactor-design.md`
- 구현 계획: `docs/superpowers/specs/2026-06-07-oop-hotspot-refactor-implementation.md`
- (선택) `detekt` 억제 제거 및 협력 객체 도입이 아키텍처 결정에 해당하면 ADR 보강 검토.
