# OOP 핫스팟 리팩토링 — 구현 계획

- **날짜**: 2026-06-07
- **스펙**: [2026-06-07-oop-hotspot-refactor-design.md](./2026-06-07-oop-hotspot-refactor-design.md)
- **방식**: TDD(특성화 테스트 보존) 기반 순수 리팩토링. 각 Task는 독립 커밋·되돌리기 가능.
- **공통 검증**: 각 Task 종료 시 해당 모듈 테스트 그린 + detekt + spotless. 전체 완료 시
  full gradle matrix + 콘솔 에셋 체크 + `git diff --check`.

## 작업 순서 개요

대상 3(SetupCommand) → 대상 2(SourceMatcher) → 대상 1(StoreDelegate) 순으로 **위험도 오름차순**
진행. 가장 격리된 것부터 손에 익히고, 가장 까다로운 동시성/직렬화 핫스팟을 마지막에.

- 대상 3: Task 1
- 대상 2: Task 2
- 대상 1: Task 3 → Task 4 → Task 5 → Task 6 → Task 7(정리/검증)

---

## Task 1: `TwoPhaseConfigCommit` 추출 (대상 3)

**목표**: `SetupCommand.runWritePlansAtomic`의 3-phase 트랜잭션을 전용 객체로 이관.

**RED**
- `TwoPhaseConfigCommitTest` 신규 작성: stage→commit 성공, `AtomicMoveNotSupportedException`
  폴백, 커밋 중 실패 시 롤백 복원, 스테이징 실패 시 정리, 에러 메시지 문구 검증. 주입 seam
  (`move`/`forceFile`/`forceDirectory`/`copyForRollback`/`emit`)으로 실패 주입.
- 기존 `SetupCommand`의 원자 커밋 관련 테스트가 동작을 이미 커버하면 그 케이스를 신규 객체
  테스트로 이전/복제.

**GREEN**
- `fixthis-cli/.../commands/TwoPhaseConfigCommit.kt` 생성: 생성자에 주입 seam, `commit(plans)`
  구현(현재 `runWritePlansAtomic` 본문 그대로 이관). `CliktError` 문구·`SetupRunResults.applied`
  누적 동일 유지.
- `SetupCommand.runWritePlansAtomic` → `TwoPhaseConfigCommit(...).commit(plans)` 위임.
- `runWritePlansAtomicForTest`도 동일 객체로 위임(중복 제거).

**검증**: `:fixthis-cli:test` 그린, SetupCommand 라인 ~110 감소, detekt `LongMethod`/
`CyclomaticComplexMethod` 억제 제거 가능 확인.

**불변식**: dry-run 경로, 파일명(`.fixthis-staging`/`.fixthis-rollback`), fsync 순서,
에러 메시지 동일.

---

## Task 2: `SourceMatcher.score()` 조합 메서드 분해 (대상 2)

**목표**: 155줄 `score()`를 4개 private 메서드로 추출.

**RED**
- 신규 테스트는 불필요(외부 동작 불변). 대신 **특성화 보강**: 기존
  `SourceMatcher`/`SourceMatching` 테스트가 selected-only, nearby-only, activity-only,
  파생 reason(arbitrary literal / untyped fallback / layout renderer / shared component) 각
  경로를 커버하는지 점검하고, 비는 경로가 있으면 추출 **전에** 특성화 테스트를 추가(RED 대신
  안전망 확보).

**GREEN**
- `scoreSelectedNode(entry, selectedNode, accumulator): Double` 추출(현 101~143).
- `scoreNearbyNodes(entry, nearbyNodes, accumulator): Double` 추출(현 145~191).
- `scoreActivity(entry, activityName, accumulator): Double` 추출(현 193~200).
- `deriveDerivedReasons(entry, ctx, matchReasons, sharedOwners)` 추출(현 202~232).
- `score()`를 누산 4줄 + 후처리 호출 + `MatchScore` 반환으로 축약.

**검증**: `:fixthis-compose-core:test` 그린. 누산 순서(선택→근접→액티비티)와 `scoredEvidence`
중복 제거·`linkedSet` reason 순서 **보존** 확인(점수·reason·정렬 값 동일).

**불변식**: `match`/companion `match`/`SourceCandidate` 결과 값 동일.

---

## Task 3: `SessionEventPayloadFactory` 추출 (대상 1-F)

**목표**: 이벤트 payload JSON 직렬화를 delegate에서 분리. **byte 동일성**이 가장 중요한
단계라 먼저 격리.

**RED**
- `SessionEventPayloadFactoryTest` 신규: 각 payload(`screen`, `screenWithItems`, `item`,
  `items`, `deleteScreen`, `deleteItem`, `updateDraftItems`)가 기존 delegate가 만들던 JSON과
  **문자열/구조 동일**함을 검증(골든 비교).

**GREEN**
- `session/lifecycle/event/SessionEventPayloadFactory.kt` 생성. `putItems`,
  `addScreenWithItemsPayload`, 변이 메서드 내 인라인 `buildJsonObject{...}`, 모듈 `eventLogJson`
  이관. 기존 `SessionEventPayloads`와 중복되면 그쪽으로 통합(단일 소스).
- delegate의 각 변이 메서드가 팩토리 함수 호출로 payload 생성하도록 교체.

**검증**: `:fixthis-mcp:test` 그린(이벤트 로그/리플레이 테스트 포함). 직렬화 byte 동일.

**불변식**: 이벤트 로그 JSON 필드명·구조는 호환성 계약 — 변경 금지.

---

## Task 4: `SessionStateStore` 추출 (대상 1-G)

**목표**: 인메모리 `sessions` 맵 + 영속화 R/W를 분리.

**RED**
- `SessionStateStoreTest` 신규: `put`/`get`/`commit`/`loadIfPersisted`/`all`,
  시퀀스 마이그레이션 보정(`withMigratedItemSequenceCounter`) 적용 검증, persistence null/비-null
  양쪽.

**GREEN**
- 기존 `SessionStateCache.kt` 확장 또는 `SessionStateStore.kt` 신규. `sessions`, `save`,
  `getSessionLocked`, `loadPersistedSessionIfAvailable`, `commitSessionMutation` 이관.
- store는 **lock-free**; delegate가 기존처럼 `synchronized(lock)` 안에서 호출(잠금 의미 보존).
- `currentSessionId`는 수명주기 책임이므로 delegate 잔류.

**검증**: `:fixthis-mcp:test` 그린. `*ForDomain` 경로(current-session 포인터 비-hijack) 회귀
없음 확인.

**불변식**: 동시성 관찰 동작 동일(잠금 범위 불변).

---

## Task 5: `SessionCompactionCoordinator` 추출 (대상 1-D)

**목표**: 압축 락·throttle·실패 sink를 분리.

**RED**
- `SessionCompactionCoordinatorTest` 신규: 임계치 초과 시 `runOnce` 호출, 성공 시 throttle
  리셋, 실패 시 throttle 규칙(첫 실패·N번째·윈도 경과) 검증, 세션별 락 격리.

**GREEN**
- `session/lifecycle/event/eventlog/SessionCompactionCoordinator.kt` 생성.
  `CompactionFailureThrottleState`, `compactEventLogAfterMutation`,
  `shouldEmitCompactionFailure`, `resetCompactionFailureThrottle`, `compactionLock`,
  `compactionLocks`, `compactionFailureThrottle`, 상수 2개, `logCompactionFailure` 이관.
- throttle 상태 접근은 코디네이터 내부 락으로 캡슐화(현 `synchronized(lock)` 의미 이전).
- delegate의 `withEventBackedMutation`/`withOptionalEventBackedMutation` 말미가
  `coordinator.compactAfterMutation(sessionId)` 호출하도록 교체.

**검증**: `:fixthis-mcp:test` 그린. 압축 실패 throttle 회귀 테스트(과거 4건 회귀 수정 영역)
통과.

**불변식**: 압축 실패가 skipped/corrupt 신호로 새지 않음. WARN throttle 동작 동일.

---

## Task 6: `SessionBootReplayer` 추출 (대상 1-E)

**목표**: 부트 리플레이 오케스트레이션 + 스킵 추적 분리.

**RED**
- `SessionBootReplayerTest` 신규: 이벤트 있는/없는 세션 리플레이, 스킵된 세션 기록,
  `skippedList` 필터(packageName/includeClosed), post-replay `currentSessionId` 재도출 규칙.

**GREEN**
- `session/lifecycle/event/SessionBootReplayer.kt` 생성. `init` 리플레이 루프,
  `replaySessionEvents`, `recordReplaySkippedSession`, `replaySkippedSessionList`,
  `replaySkippedSessions` 이관. 계산은 기존 `SessionReplayEngine` 재사용.
- delegate `init`은 `bootReplayer.replayAll(stateStore, journal)` 호출 + 결과로
  `currentSessionId` 재도출. `init`은 `lock` 미보유 — 비동기화 호출 계약 유지.

**검증**: `:fixthis-mcp:test` 그린(부트 리플레이/스킵 세션 테스트 포함).

**불변식**: 부트 시 상태 재구성·current-session 선택 동작 동일.

---

## Task 7: delegate 정리 + 전체 검증

**목표**: 분해 마무리 및 게이트 통과.

- `FeedbackSessionStoreDelegate`에서 이관된 멤버 제거, 협력 객체 조립만 남김.
  목표 ~350줄 이하, `@Suppress("LargeClass", "TooManyFunctions")` 제거(가능 시
  `LongParameterList`도).
- `FeedbackSessionStore` facade 시그니처 불변 확인.
- **경계 테스트 path-key 갱신**: `ArchitectureHotspotBudgetTest` 및 패키지 경계 테스트의
  파일 예산/허용 목록에 신규 파일 반영.
- 전체 검증: full gradle matrix + 콘솔 에셋 체크 + 콘솔 JS 하네스 + detekt + spotless +
  `git diff --check`.
- `EventLogFailureModeTest.renameFailure` macOS 로컬 실패는 기존 이슈로 waive(CI/Linux 권위).

---

## 완료 정의(DoD)

- [ ] 대상 3개 핫스팟이 단일 추상화 수준으로 읽힘.
- [ ] 추출된 협력 객체 5종 각각 단위 테스트 보유.
- [ ] 기존 ~801 테스트 + 신규 테스트 전부 그린(CI/Linux).
- [ ] detekt 억제 주석(`LargeClass`/`LongMethod`/`TooManyFunctions`) 제거.
- [ ] 공개 API 시그니처·이벤트 로그 byte·원자 커밋·점수 결과 불변(회귀 없음).
- [ ] 경계/예산 테스트 path-key 갱신 완료.
