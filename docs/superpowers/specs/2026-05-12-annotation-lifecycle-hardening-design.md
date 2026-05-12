# Spec — Annotation Lifecycle Hardening

Status: Draft
Author: kws (with Claude Opus 4.7 analysis 2026-05-12)
Scope: `:fixthis-compose-core`, `:fixthis-compose-sidekick`, `:fixthis-mcp`, console JS
Related plan: [`../plans/2026-05-12-annotation-lifecycle-hardening-implementation.md`](../plans/2026-05-12-annotation-lifecycle-hardening-implementation.md)

## 배경

2026-05-12 코드 감사에서 어노테이션 라이프사이클(여러 핀의 등록/삭제/수정, 디바이스 단절, 화면 전환)에 걸쳐 13개의 결함과 3개의 고도화 항목이 발견됐다. 핵심 위험은 두 가지로 압축된다:

1. **Silent data loss** — 메모리에만 존재하는 `pendingFeedbackItems` 배열이 새로고침/단절/오류 시 흔적 없이 사라진다.
2. **Silent semantic mismatch** — 회전, 시스템 dialog, 활동(activity) 변경, 멀티 핀 누적 중 디바이스 화면이 바뀌어도 좌표 검증은 통과해 의미가 깨진 핀이 에이전트로 흘러간다.

본 문서는 16개 항목을 5개 phase로 묶어 요구사항·설계·계약·수용 기준을 정의한다. 각 phase는 독립 PR로 머지 가능한 단위다.

## 목표

- 사용자가 작업한 어노테이션은 어떤 단절 시나리오에서도 손실되지 않는다.
- 화면 정합성 위반(회전·dialog·activity 변경·시간 stale)은 에이전트에 도달하기 전에 감지·차단·복구된다.
- UI 표시 식별자와 백엔드 영속화 식별자가 항상 일치한다.
- 회귀 시나리오는 자동화된 emulator 기반 테스트로 영구 보호된다.

## 비목표

- View 시스템 또는 Flutter 지원 (Compose 전용 유지).
- 멀티 사용자/협업 어노테이션 (단일 사용자 워크플로우 유지).
- 외부 클라우드 동기화 (local-first 원칙 유지).
- Bridge 프로토콜의 메이저 재설계 (1.x 호환 유지, minor bump 허용).

## 호환성 영향

- `BridgeProtocol.VERSION`: `"1.2"` → `"1.3"` (BSC-1, BSC-2, BSC-3에서 SnapshotDto 확장).
- `SnapshotDto`, `BridgeScreenSnapshot`에 새 nullable 필드 추가 (기존 페이로드 backwards-compatible).
- `.fixthis/feedback-sessions/<sessionId>/`에 새 디렉터리 `events/` 추가 (Phase A의 event log). 기존 `state.json`은 그대로 유지하되, 마이그레이션 시 event log로부터 파생되도록 변경.

---

## Phase 그룹 개요

| Phase | 주제 | 항목 수 | 대표 결함 |
|-------|------|---------|-----------|
| A | Annotation Lifecycle Hardening (데이터 손실) | 4 | 미저장 휘발, Undo 없음, 부분 실패 |
| B | Screen Integrity Fingerprinting (정합성) | 6 | activityName 미검증, 회전·dialog·드리프트 |
| C | Annotation UX Consistency (식별자 일치) | 2 | sequenceNumber UI 불일치, sent 후 stale 미표시 |
| D | Connection Resilience (연결 안정성) | 3 | 자동 재개·큐 폭주·에러 메시지 |
| E | Regression Test Infrastructure | 1 | EmulatorTestKit 시나리오 playback |

---

## Phase A — Annotation Lifecycle Hardening

### ALH-1 — Pending 어노테이션 영속화 (event log + localStorage 미러)

**현장:** `fixthis-mcp/src/main/resources/console/app.js:72` — `pendingFeedbackItems = []`. `fixthis-mcp/src/main/console/annotations.js:387-412`. 새로고침/USB 단절/탭 닫기 시 즉시 소멸.

**계약:**
- 클라이언트 측: 매 `appendPendingItem()` / `updatePendingItem()` / `removePendingItem()` 호출 시 동일 트랜잭션으로 `localStorage["fixthis.pending.<sessionId>"]`에 직렬화 저장.
- 콘솔 부팅 시 `localStorage` 스캔 → `pendingFeedbackItems` 복구 → 사용자에게 "복구된 작업이 있습니다 (n개)" 배너 노출, **명시적 수락 후에만** 카드에 노출.
- `beforeunload` 핸들러: `pendingFeedbackItems.length > 0` && `dirty=true` 이면 브라우저 기본 confirm dialog 발동.
- 서버 측: 모든 mutation은 `events/<epochMillis>-<seq>.jsonl` append-only 라인으로 먼저 fsync, 성공 시에만 메모리 `sessions` 맵 갱신 (write-ahead log; ALH-3과 통합).

**수용 기준:**
- 핀 3개 추가 → 콘솔 새로고침 → 복구 배너 노출 → "복구" 클릭 → 3개 모두 카드에 재현.
- 핀 3개 추가 → adb USB 단절 → 콘솔 새로고침 → 동일하게 복구 가능.
- 자동화 테스트 (`PendingItemRecoveryTest.kt` + `pendingItemRecovery.test.js`)가 위 두 시나리오를 검증.

### ALH-2 — Undo / Redo 히스토리

**현장:** `fixthis-mcp/src/main/resources/console/app.js` 전체에 `undo`/`redo` 키워드 0건.

**계약:**
- 클라이언트 메모리 스택 두 개: `undoStack: Op[]`, `redoStack: Op[]`. 깊이 50.
- `Op` 형태: `{ kind: 'add'|'update'|'delete', before?: Item, after?: Item, sessionId, screenId, timestamp }`.
- 삭제 직후 5초 토스트: "어노테이션 #N 삭제됨 — 되돌리기" 버튼.
- 키바인딩: `Cmd/Ctrl+Z` undo, `Cmd/Ctrl+Shift+Z` redo (단, 입력 포커스가 `<textarea>`/`<input>`이 아닐 때만).
- 서버 mutation 호출 시 `Op`도 함께 ALH-1의 event log에 append (replay 가능하게).

**수용 기준:**
- 핀 추가 → 삭제 → 토스트의 되돌리기 → 동일 itemId, sequenceNumber, target으로 부활.
- Cmd+Z 50회 반복 후 Cmd+Shift+Z 50회로 정확히 동일 상태 복원.
- 서버 재시작 후에도 event log 재생으로 동일 상태 도달.

### ALH-3 — Transactional commit (write-ahead log)

**현장:** `fixthis-mcp/src/main/kotlin/.../session/FeedbackDraftService.kt:128-141` — `addScreenWithItems` → `commitSessionMutation`이 메모리 먼저 갱신 후 디스크 쓰기. 실패 시 메모리/디스크 불일치.

**계약:**
- `FeedbackSessionStore`에 `EventLogWriter` 의존성 주입.
- 모든 mutation 메서드(`addScreen`, `addItem`, `updateDraftItem`, `deleteDraftItem`, `deleteScreen`, `markSent`, …)는:
  1. `Event` 객체 생성 (`type`, `payload`, `expectedVersion`).
  2. `EventLogWriter.append(event)` 동기 호출 — `FileChannel.write` + `force(true)` (fsync) 성공 시에만 진행.
  3. 성공 후 메모리 `sessions` 맵 갱신.
  4. fsync 실패 시 `EventLogException` throw, 메모리 변경 없음.
- 부팅 시 `events/*.jsonl` 시간 순 replay → 메모리 상태 재구성. 기존 `state.json`은 snapshot cache로만 사용 (raw source는 event log).
- 매 100 events마다 또는 5분마다 snapshot 생성 (`state.json` 재기록).

**수용 기준:**
- 디스크 풀 시뮬레이션 시 mutation 실패하지만 메모리 무변. 클라이언트는 명확한 5xx 에러 수신.
- 프로세스 강제 종료(SIGKILL) 후 재시작 → 마지막 fsync 성공 mutation까지 정확히 복원.
- `EventLogReplayTest.kt`가 100개 random mutation을 두 번 replay해 동일 상태 검증.

### ALH-4 — Append-only event log (CRDT 스타일)

**현장:** 신규 인프라.

**계약:**
- 디렉터리: `.fixthis/feedback-sessions/<sessionId>/events/`
- 파일 명명: `<epochMillis>-<sequenceNumber>.jsonl` (한 파일 = 한 event, 정렬 가능).
- Event 스키마:
  ```jsonc
  {
    "version": 1,                  // event log schema version
    "eventId": "uuid",             // 멱등 키
    "sequenceNumber": 42,          // 단조 증가, 세션 단위
    "epochMillis": 1715500000000,
    "actor": "console" | "mcp",
    "type": "addItem" | "updateItem" | "deleteItem"
          | "addScreen" | "deleteScreen"
          | "markSent" | "claim" | "resolve",
    "payload": { /* type-specific */ },
    "parentEventId": "uuid"        // 멱등성/순서 보존
  }
  ```
- Event는 **CRDT 의미가 아니라 append-only event sourcing**. 단일 사용자 가정이므로 충돌 해결은 last-write-wins (sequenceNumber 비교)만 사용.
- Compaction: 1000 event 또는 7일 도달 시 백그라운드 compactor가 새로운 snapshot + truncated event log 생성. 이전 events는 `events/archive/` 이동.

**수용 기준:**
- Mutation 100개 후 `.fixthis/feedback-sessions/<id>/events/`에 100개 파일 존재.
- 파일 무작위 삭제 시 replay가 명확한 에러 보고하고 fail-stop (data corruption 마스킹 금지).
- Compaction 후에도 `state.json` 결과 동일.

---

## Phase B — Screen Integrity Fingerprinting

### SIF-1 — Snapshot 모델 확장 (orientation, window, system UI)

**현장:** `fixthis-compose-core/src/main/kotlin/.../domain/snapshot/Snapshot.kt:9-18`, `fixthis-mcp/src/main/kotlin/.../session/SessionDtoModels.kt:38-47` — `activityName`만 존재.

**계약:**
- `Snapshot` (core) 및 `SnapshotDto` (mcp)에 다음 nullable 필드 추가:
  ```kotlin
  val orientation: ScreenOrientation? = null,    // PORTRAIT | LANDSCAPE | REVERSE_PORTRAIT | REVERSE_LANDSCAPE
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val densityDpi: Int? = null,
  val windowMode: WindowMode? = null,            // FULLSCREEN | SPLIT_SCREEN | FREEFORM | PIP
  val systemUiVisible: Boolean? = null,          // 시스템 dialog/IME/notification panel 노출 여부
  val systemUiKind: String? = null,              // "permission_dialog" | "ime" | "notification_shade" | null
  val fingerprint: String? = null,               // SIF-2의 hash
  ```
- `BridgeScreenSnapshot` 동일 확장. `BridgeProtocol.VERSION` `"1.2"` → `"1.3"`. `BridgeProtocolVersionSyncTest`가 4-site 동기화 검증 (`docs/reference/bridge-protocol.md` 참고).
- 기존 페이로드는 모든 신규 필드 `null`로 backwards-compatible.

**수용 기준:**
- `BridgeProtocolVersionSyncTest` PASS.
- `:fixthis-mcp:test`의 `SnapshotDtoSerializationTest`가 모든 필드 round-trip.
- 기존 `state.json` 파일(1.2 시절)이 마이그레이션 없이 로드 가능.

### SIF-2 — Fingerprint hash 생성 및 비교

**현장:** 신규.

**계약:**
- 생성: 사이드킥의 `captureScreenSnapshot()` 마지막 단계에서:
  ```kotlin
  fingerprint = sha256(
    "$activityName|$orientation|$widthPx×$heightPx@$densityDpi|$windowMode|$systemUiKind"
  ).take(16)  // 16자 hex prefix
  ```
- 비교: `FeedbackDraftService.savePreviewFeedbackItems()`가 저장 직전 현재 스냅샷을 1회 추가 캡처해 `frozen.fingerprint != current.fingerprint`이면 mutation 차단, `ScreenFingerprintMismatch` 응답 반환.
- 클라이언트는 mismatch 응답 수신 시 모달 표시: 옵션 A "현재 화면 다시 캡처하고 핀 좌표 재매핑" / 옵션 B "그래도 강제 저장 (기록만)" / 옵션 C "취소".
- 강제 저장 선택 시 event log에 `forceMismatchOverride=true` 메타 기록.

**수용 기준:**
- 회전 시뮬레이션(폭/높이 swap) 시 fingerprint 변경 → mismatch 모달 노출.
- 같은 스크린에서 재캡처 시 fingerprint 동일 → 무경고 저장.
- `FingerprintComputationTest.kt`가 동일 입력에서 안정성, 다른 입력에서 충돌 없음을 검증 (1000개 합성).

### SIF-3 — 회전 감지 (orientation 필드 활용)

**현장:** `fixthis-compose-sidekick/src/main/kotlin/.../bridge/BridgeServer.kt`, `Configuration` 변경 콜백 부재.

**계약:**
- 사이드킥에서 `Activity.resources.configuration.orientation` 값을 매 capture 시 `Snapshot.orientation`에 기록.
- 추가로 `Application.registerComponentCallbacks2`로 `onConfigurationChanged` 후크 → `BridgeStatus`에 `lastOrientationChangeAtEpochMillis` 필드 추가.
- 클라이언트는 freeze 후 status 폴링에서 `lastOrientationChangeAtEpochMillis > frozenAtEpochMillis` 감지 시 즉시 `state.preview.stale = true` 마킹.

**수용 기준:**
- 에뮬레이터에서 `adb shell settings put system user_rotation 1` 실행 시 1초 이내 콘솔에 stale 배너 노출.
- SIF-2의 fingerprint mismatch도 함께 발동 (이중 보호).

### SIF-4 — 시스템 UI 가림 감지

**현장:** capture pipeline 전체 — 권한 dialog/IME/notification shade 감지 코드 0건.

**계약:**
- 사이드킥에서 `WindowInsetsCompat.isVisible(Type.ime())` (IME) 및 ADB 사이드 채널의 `dumpsys window windows | grep mCurrentFocus` 결과를 결합해 다음을 판정:
  - `systemUiKind = "ime"` if IME visible.
  - `systemUiKind = "permission_dialog"` if `mCurrentFocus`에 `com.android.permissioncontroller` 또는 `com.google.android.permissioncontroller`.
  - `systemUiKind = "notification_shade"` if focus가 `StatusBar`.
  - 그 외 `null`.
- `Snapshot.systemUiVisible`은 `systemUiKind != null`로 derive.

**수용 기준:**
- 권한 dialog 표시 상태 capture 시 `systemUiKind == "permission_dialog"` 반환.
- SIF-2와 결합되어 사용자가 "보이는 화면"과 "annotated 화면"의 차이를 모달로 인지.

### SIF-5 — 단절 순간 frozen preview의 stale 마킹

**현장:** `fixthis-compose-sidekick/src/main/kotlin/.../bridge/BridgeServer.kt:436` — `lastScreenSnapshot` 캐시가 단절 후에도 valid한 듯 노출.

**계약:**
- `BridgeStatus`에 `lastSuccessfulCaptureAtEpochMillis` 필드 추가.
- 클라이언트 `staleness.js`에서 다음 조건 OR로 stale 마킹:
  - `now - lastSuccessfulCaptureAtEpochMillis > MAX_PREVIEW_AGE_MS` (기본 30000).
  - `bridgeStatus.connection != "connected"` 동안 `state.preview.frozenAtEpochMillis > now - 5000`.
- Stale 시 freeze 위에 시각적 오버레이("연결 끊김 후 캡처 — 좌표가 의미 없을 수 있음").

**수용 기준:**
- adb kill-server 후 30초 내에 콘솔에 단절 + stale 배지 노출.
- 재연결 후 1회 capture 성공 시 자동으로 stale 해제.

### SIF-6 — 다중 핀 누적 중 화면 drift 감지

**현장:** `fixthis-mcp/src/main/console/annotations.js:387-412` — 동일 freeze 안의 모든 pending item이 `addItemsFlow.screen` 하나에 묶임.

**계약:**
- 매 `appendPendingItem()` 호출 직후 백그라운드 `pingActivity()` (≤200ms 단발 헬스 체크) 발사.
- 응답 activity가 `addItemsFlow.activity`와 다르면 다음 핀 추가 입력 폼에 inline 경고: "이 화면은 핀 #1과 다른 화면입니다. 새 freeze를 권장합니다." + "새 화면으로 분리" 버튼.
- 사용자가 "분리" 선택 시 새 `addItemsFlow` 시작 (새 freeze + 새 screenId 발급).

**수용 기준:**
- 시뮬레이션: pending item 1개 추가 → adb 명령으로 다른 activity 진입 → 두번째 pending item 추가 시도 시 경고 노출.
- "분리" 선택 시 두 핀이 서로 다른 `screenId`로 저장.

---

## Phase C — Annotation UX Consistency

### AUC-1 — sequenceNumber UI 노출 (배열 인덱스 결별)

**현장:** `fixthis-mcp/src/main/resources/console/app.js:1120, 2258` — `'#' + (index + 1)` 형식으로 배열 인덱스 표시.

**계약:**
- UI에서 표시되는 핀 번호는 `item.sequenceNumber` (서버 발급)를 직접 사용.
- 백엔드는 sequenceNumber를 monotonic non-reusable로 보장 (삭제 후 재번호 금지). `FeedbackSessionStore.nextItemSequenceNumber`(`fixthis-mcp/src/main/kotlin/.../session/FeedbackSessionStore.kt:405-406`)를 max+1 → DB-style sequence(저장된 카운터)로 교체.
- 보조 표시: shortId(`item.itemId.take(6)`)도 핀 카드에 작게 노출 (디버깅/에이전트 대화용).

**수용 기준:**
- 핀 1, 2, 3 추가 → 2번 삭제 → 새 핀 추가 시 `#4`로 표시 (`#2` 재사용 금지).
- `SequenceNumberStabilityTest.kt`가 100개 random add/delete 후에도 단조성 유지 검증.

### AUC-2 — Sent 후 수정 시 staleAfterHandoff 마킹

**현장:** `fixthis-mcp/src/main/kotlin/.../session/AnnotationRepository.kt:74-88, 352, 374-375` — sent 상태 어노테이션 수정 시 stale 플래그 미세팅.

**계약:**
- `AnnotationRepository.updateDraftFeedback()`는 draft에 대해서만 호출됨이 명확하나, `updateSentFeedback()` 신규 메서드 추가:
  - 입력: `sessionId`, `itemId`, 변경 필드.
  - 동작: 항목의 `staleAfterHandoff = true`, `lastModifiedAfterHandoffAtEpochMillis = now()` 세팅.
- 콘솔 카드에 "Sent · Modified" 배지 (노란색).
- MCP 핸드오프 응답(`fixthis_read_feedback`)의 markdown에 `(modified after handoff)` 마커 포함.

**수용 기준:**
- Sent 항목 수정 → 카드 배지 + JSON `staleAfterHandoff=true` + MD에 표시.
- Sent 후 재발송(re-handoff) 시 플래그 자동 클리어.

---

## Phase D — Connection Resilience

### CR-1 — 자동 세션 재개 (재연결 후 sessionId 보존)

**현장:** `fixthis-mcp/src/main/resources/console/app.js:844-846` — 재연결 시 sessionId 보존 보장 부재.

**계약:**
- 콘솔 부팅 시 `localStorage["fixthis.activeSessionId"]` 우선 조회 → 서버에 존재 확인 (`fixthis_list_feedback_sessions`) → 존재 시 자동 attach, 없으면 세션 카드 표시.
- BridgeStatus 단절 → 재연결 시 같은 sessionId 유지 (URL/state 모두).
- 단, 사용자가 명시적으로 "새 세션 시작" 클릭하면 localStorage 키 삭제.

**수용 기준:**
- 핀 작업 중 adb kill-server → restart → 콘솔 자동 재연결 → 동일 세션, 동일 pending items 유지.

### CR-2 — 단절 중 액션 큐 dedup

**현장:** `fixthis-mcp/src/main/resources/console/app.js:602-616` — 폴링 백오프 30s 동안 액션이 메모리 큐에 누적, 재연결 시 일괄 발사.

**계약:**
- `actionQueue: Map<string, Action>` (key = action 종류, e.g. "navigate", "capture", "savePending").
- 동일 key의 새 액션이 들어오면 이전 것 덮어씀 ("최신만 실행" 정책).
- 재연결 후 큐 flush는 100ms 간격으로 직렬 실행 (폭주 방지).

**수용 기준:**
- 단절 중 navigate × 5회 호출 → 재연결 시 1회만 실행.
- `actionQueueDedup.test.js`가 시뮬레이션으로 검증.

### CR-3 — 에러 메시지 액션 가이드

**현장:** `fixthis-compose-sidekick/src/main/kotlin/.../bridge/BridgeClient.kt:312-314` 외 다수 — "Could not read FixThis bridge session via adb" 등 tech-only 메시지.

**계약:**
- 모든 사용자-facing 에러는 `UserFacingError(code, title, body, suggestedActions)` 구조로 통일.
- `code`: `BRIDGE_NOT_REACHABLE`, `DEVICE_LOCKED`, `APP_NOT_RUNNING`, `PERMISSION_DENIED`, `DISK_FULL`, …
- `suggestedActions`: 각 에러 코드별로 정의된 액션 리스트 (예: `BRIDGE_NOT_REACHABLE` → ["USB 연결 확인", "`adb kill-server && adb start-server` 실행", "디바이스 화면 잠금 해제"]).
- 콘솔은 `code`별 i18n 메시지 매핑 + 액션 버튼 렌더링.

**수용 기준:**
- 모든 사용자 노출 에러가 `UserFacingError` 통과 (grep으로 raw exception message 노출 0건).
- 5개 대표 에러 시나리오에 대해 사용자가 액션 버튼만으로 복구 가능.

---

## Phase E — Regression Test Infrastructure

### RTI-1 — EmulatorTestKit (시나리오 playback)

**현장:** 신규 모듈 `:fixthis-emulator-testkit` (test-only).

**계약:**
- JVM 테스트에서 호출 가능한 DSL 제공:
  ```kotlin
  emulatorScenario("annotation survives rotation") {
    given { sample app on home screen }
    annotate { pin at (200, 400), comment = "test" }
    rotateDevice(LANDSCAPE)
    expect { fingerprintMismatchModalShown() }
    chooseRecaptureOption()
    expect { pinAt(200, 400).remappedTo(landscapeCoords) }
  }
  ```
- 내부 구현:
  - `adb` 명령(rotation, app kill, dialog 띄우기)을 ProcessBuilder로 실행.
  - 콘솔 측은 headless Playwright로 driving.
  - 사이드킥은 실제 emulator의 sample app 사용.
- 시나리오 파일: `fixthis-emulator-testkit/src/main/resources/scenarios/*.kts`.
- 필수 시나리오 (RTI-2):
  - `annotation-survives-disconnect.kts` (ALH-1)
  - `undo-redo-roundtrip.kts` (ALH-2)
  - `kill-9-replay.kts` (ALH-3)
  - `rotation-mismatch.kts` (SIF-3)
  - `permission-dialog-detection.kts` (SIF-4)
  - `multi-pin-activity-drift.kts` (SIF-6)
  - `sequence-number-stability.kts` (AUC-1)
  - `auto-resume-on-reconnect.kts` (CR-1)

**수용 기준:**
- `./gradlew :fixthis-emulator-testkit:connectedAndroidTest`가 emulator 환경에서 8개 시나리오 모두 PASS.
- CI nightly job에 통합 (기존 `connected-tests.yml` 활용).
- 실패 시 emulator 화면 녹화(`adb shell screenrecord`)와 콘솔 console.log를 artifact로 업로드.

---

## 의존성·순서

```
SIF-1 (Snapshot 확장) ─────► SIF-2 (fingerprint) ─────► SIF-3, 4, 5, 6 (활용)
                          │
ALH-4 (event log) ────────┼─► ALH-3 (transactional) ──► ALH-1, 2
                          │
AUC-1 (sequenceNumber) 독립
AUC-2 (staleAfterHandoff) 독립
CR-1, CR-2, CR-3 독립

RTI-1 (EmulatorTestKit)은 위 phase들 산출물을 검증하므로 마지막.
```

권장 머지 순서: ALH-4 → ALH-3 → ALH-1 → ALH-2 → SIF-1 → SIF-2 → SIF-3/4/5/6 (병렬 가능) → AUC-1/2 (병렬) → CR-1/2/3 (병렬) → RTI-1.

## 위험·완화

| 위험 | 영향 | 완화 |
|------|------|------|
| Event log fsync 빈도가 mutation latency 증가 | UX hitch | mutation은 낙관적 응답, fsync는 비동기 + ack 필요 시 await. 단 ALH-3 수용 기준은 동기 fsync 가정. |
| Bridge 1.2 ↔ 1.3 mismatch (구버전 사이드킥) | 일부 필드 null | 모든 신규 필드 nullable, fingerprint null이면 비교 skip + 경고 배너 |
| Fingerprint 충돌 (서로 다른 화면 동일 hash) | False negative mismatch | 16자 hex (64bit)로도 단일 세션 내 충돌 확률 극소; 실패 시 활성화된 `forceMismatchOverride` 옵션으로 사용자 우회 가능 |
| Compaction 중 crash | event log 손상 | Compaction은 새 파일에 쓴 후 atomic rename, 실패 시 원본 그대로 |
| EmulatorTestKit이 CI에서 flaky | 신뢰도 저하 | retry-once 정책 + 실패 시 artifact 보존; nightly only, PR 차단 안 함 |

## Open Questions

1. ALH-2의 Undo가 server-side mutation (delete)도 되돌릴지 vs 클라이언트 메모리 limited인지 — **결정: 서버 mutation도 event log compensating event로 되돌린다**.
2. SIF-4의 `dumpsys window` 의존이 root 없이 모든 디바이스에서 동작하는지 — 검증 필요. 일부 OEM에서 안 되면 ime+inset만으로 best-effort.
3. RTI-1의 시나리오를 GitHub Actions의 macOS runner에서도 돌릴지 vs Linux KVM only — Linux KVM 권장 (성능).

## 참고

- 기존 plan: [`docs/superpowers/plans/2026-05-09-annotation-screen-mismatch-fix.md`](../plans/2026-05-09-annotation-screen-mismatch-fix.md) (SIF-1, SIF-2의 일부 설계 토대).
- Bridge 프로토콜 절차: [`docs/reference/bridge-protocol.md`](../../reference/bridge-protocol.md).
- 영속화 컨벤션: [`docs/reference/output-schema.md`](../../reference/output-schema.md) — 본 spec은 schema **확장**만 수행, 기존 필드명 변경 금지.
