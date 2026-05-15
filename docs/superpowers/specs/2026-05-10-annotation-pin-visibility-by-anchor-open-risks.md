# Annotation Pin Visibility by Anchor — Open Risks (post-merge)

**Date:** 2026-05-10
**Status:** Open follow-ups after merge `d27f674`
**Companion to:**
- `plans/2026-05-10-annotation-pin-visibility-by-anchor.md`
- `specs/2026-05-10-annotation-pin-visibility-by-anchor-implementation-details.md`

머지된 변경의 자동 검증은 통과(`./gradlew :fixthis-mcp:test` 410/0)했지만, 이 fix 의 가치는 브라우저 콘솔 UX 일관성에 있고 자동 테스트가 그것을 직접 측정하지 못한다. 아래는 머지 시점에 의식적으로 남겨둔 리스크 목록이다.

---

## R1. 수동 스모크 미실행 (highest priority)

**상태:** 미수행. plan 의 Task 4 (S1~S11) 가 디바이스/에뮬레이터를 요구해서 sub-agent 가 실행할 수 없었고, 머지 시점까지 사람이 한 번도 돌리지 않았다.

**절차적 약점 (자기 비판):** manual gate 가 필요한 fix 는 통상 머지 *전* 에 처리하는 것이 정석. 본 머지는 자동 테스트 통과만으로 합쳐졌고, 그 결과 R2~R4 가 모두 "R1 결과 의존" 상태로 남았다. 다음 유사 워크스트림에서는 manual smoke 를 머지 전 차단 단계로 둘 것.

**왜 중요한가:** 자동 테스트는 다음만 보장한다.
- JS 번들이 source 모듈과 byte-equal (asset-equality test)
- HTML 출력에 anchor-aware 필터의 구조적 마커 substring 이 존재
- node --check 통과

자동 테스트가 보장하지 않는 것:
- 같은 화면 1초 폴링에서 핀이 실제로 깜빡이지 않음
- 다른 화면 네비게이트 시 직전 핀이 실제로 사라짐
- 같은 화면 복귀 시 핀이 실제로 다시 등장
- area annotation 의 의도된 한계 동작
- focus 클릭으로 캔버스 전환 시 같은 screen 의 다른 핀들도 함께 표시
- **R3 의 nodeUid 충돌 가설 (S2/S3 시나리오에서 직전 화면 핀이 새 화면에 잘못 보이는지)**

이 fix 의 핵심 가설("같은 화면 재캡처 시 nodeUid 보존, 다른 화면 네비 시 변경") 이 실제 앱에서 깨지면 11 시나리오가 한꺼번에 잘못 동작할 수 있다.

**검증 방법:**
```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.github.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

브라우저 devtools 에서 진단:
```js
visibleScreenNodeUids(latestScreen())
savedEvidenceItems().map(i => ({ id: i.itemId, uid: i.target?.nodeUid, screenId: i.screenId }))
(() => {
  const v = visibleScreenNodeUids(latestScreen());
  return savedEvidenceItems().map(i => ({ id: i.itemId, hit: !!i.target?.nodeUid && v.has(i.target.nodeUid) }));
})()
```

**R3 동시 검증 (S2/S3 진행 중):** 두 화면을 각각 1회 이상 캡처한 뒤 devtools 에서

```js
const a = new Set(visibleScreenNodeUids(screenAtIndex(0)));
const b = new Set(visibleScreenNodeUids(screenAtIndex(1)));
[...a].filter(x => b.has(x))   // 교집합이 비어 있어야 R3 가설 안전
```

교집합이 비어 있지 않으면 R3 의 "nodeUid 충돌 없음" 가정이 깨진 것이며, 그 경우 R3 별 plan 을 즉시 띄운다.

**완료 조건:** plan 의 S1~S11 11 시나리오 통과 (S10 은 의도된 한계 확인) + R3 교집합 검증 통과.

---

## R2. Compose recomposition 으로 인한 핀 깜빡임 (spec F1)

**상태:** 의도된 단순 규칙. 미관측, 미해결.

**문제:** 빠른 recomposition 으로 SemanticsNode 가 잠시 dispose → recreate 될 때 nodeId 가 일시적으로 부재 또는 재할당될 수 있다. 그 짧은 순간에는 `visibleUids.has(nodeUid)` 가 false 가 되어 핀이 한 프레임 사라진다.

**왜 지금 fix 안 했나:** 일반 NavHost 기반 Compose 앱에서 흔하지 않을 것으로 가정. 사용자 보고가 발생하기 전에 hysteresis 도입은 over-engineering.

**대응 방안 (예약):** spec section 6 F1 "Hysteresis follow-up" 그대로 — `rendering.js` 에 모듈-스코프 캐시:

```js
const pinLastSeenAt = new Map(); // itemId -> epochMs
const PIN_HYSTERESIS_MS = 800;
```

필터 안에서 hit 이 true 면 `pinLastSeenAt.set(item.itemId, now())`, false 면 `now() - (pinLastSeenAt.get(item.itemId) || 0) <= PIN_HYSTERESIS_MS` 면 표시 유지.

**트리거:** R1 스모크 또는 사용자가 같은 화면에서 핀 깜빡임을 보고하면 별도 plan 으로 진행.

---

## R3. nodeUid 충돌 (spec F2)

**상태:** 가정 + R1 동시 검증 추가됨 (이전 "방어 없음" → "스모크 단계에서 1회 정량 검증").

**문제:** 두 다른 logical screen 이 우연히 같은 `compose:rootIndex:treeKind:nodeId` uid 를 가지면, 직전 화면의 anchor 가 새 화면의 노드와 매치되어 잘못 표시된다.

**왜 지금 fix 안 했나:** 일반 NavHost 패턴에서 `rootIndex` 가 화면별로 다르거나, 같다면 컴포지션 dispose → recreate 가 nodeId 를 재할당시키므로 collision 가능성 낮다고 가정. 단 — **단일 NavHost 패턴에서는 rootIndex=0 이 화면 간 공유될 수 있어** 이 가정이 어디서 깨질지 코드만 보고는 단정 어렵다. 그래서 R1 스모크 안에 교집합 1회 측정을 묶어 정량 신호를 확보한다 (위 R1 의 "R3 동시 검증" 블록).

**대응 방안 (예약):** backend 측에 screen-level identifier (예: composition hash) 를 추가하고 anchor 매칭에 같이 사용. fixthis-compose-sidekick 의 `SemanticsNodeMapper.uidFor` 변경이 필요. 별도 spec 가치 있는 규모.

**트리거:**
- R1 의 nodeUid 교집합 검증에서 비어 있지 않은 결과가 나오면 즉시
- 또는 S2/S3 시나리오에서 직전 화면 핀이 새 화면에 잘못 보이면 (정성 신호)

---

## R4. Area annotation UX 변화

**상태:** 의도된 새 동작이지만 사용자 인식 가능한 회귀.

**문제:** area annotation 은 nodeUid 가 없으므로 `screenId` 일치 fallback 만 사용한다. 라이브 폴링 중에는 매 캡처마다 새 screenId 가 발급되므로 area 핀은 자기 원본 캡처에서만 보인다. 같은 logical screen 에서 라이브 폴링 중이어도 사라진다.

**왜 지금 fix 안 했나:** area 는 기획상 component anchor 가 잡히지 않을 때의 fallback 이라 흔하지 않다고 가정. CHANGELOG 한 줄에 "with screenId equality kept as a fallback for area-only annotations" 로 명시.

**사용자가 "버그" 로 오인할 가능성:** 실제로 area 만 쓰는 사용자는 fix 전과 후 모두 동일한 UX 겪음 (둘 다 자기 캡처에서만 표시). 변경 없음. 다만 component + area 혼용 사용자는 component 핀은 폴링에 살아있고 area 핀은 사라지는 것을 보고 의아해할 수 있음.

**대응 방안 (예약 — out of scope for current plan):** area annotation 에 logical-screen 식별자를 backend 에서 부여 (R3 와 같은 방향). 또는 area 를 만들 때 가장 가까운 component 를 anchor 로 자동 첨부.

**트리거:** 사용자 보고. 자체적으로는 R1 의 S10 시나리오에서 의도된 동작임을 확인.

---

## R5. plan/spec 문서 stale (작업 추적 정합성)

**상태:** 머지된 코드는 정확하지만 plan 문서가 실제와 다름.

**문제:** `docs/superpowers/plans/2026-05-10-annotation-pin-visibility-by-anchor.md` 는 머지 시점 그대로이고, 런 도중 추가된 Task 6 (FeedbackConsoleServerTest 어서션 갱신) 가 plan 본문에는 없다. spec 도 section 5.1 이 "새 테스트는 추가하지 않는다" 라고 적혀 있어 실제와 다소 어긋난다.

**실해:** 0. 코드는 정확. 다만 회고/감사용으로 plan 을 다시 읽으면 "어 Task 6 어디서 나온 거지?" 가 생긴다.

**대응 방안:**

옵션 A (가벼움): 본 문서를 plan 의 단일 출처 보완재로 두고, plan 자체는 손대지 않음. (현재 채택)

옵션 B (정확): plan 에 Task 6 절을 추가하고 spec section 5.1 의 "새 테스트 없음" 문장을 "기존 구조 마커 어서션 2개 갱신" 으로 수정. plan 은 한 번 합쳐지면 historical artifact 로 다루는 프로젝트 관행상 보통 안 한다.

**트리거:** 다른 사람이 plan 을 보고 혼동하면 옵션 B 로 갱신.

---

## 우선순위 (R 시리즈)

1. **R1** — 머지된 fix 의 실제 효과를 사람이 확인. R3 교집합 검증을 같은 세션에 묶음. 다른 모든 항목은 R1 결과에 의존.
2. **R2** — R1 에서 깜빡임 관측되면 별도 plan.
3. **R3** — R1 의 교집합 검증에서 비어 있지 않으면 즉시 plan. 비어 있으면 사용자 보고 대기.
4. **R4** — 사용자 보고 대기.
5. **R5** — 선택. 보통 그냥 둠.

---

## Out of scope (반복 명시)

본 fix 는 브라우저 콘솔 한정. 다음은 모두 **의도적으로** 안 건드렸다.

- backend / Kotlin DTO / persistence
- nodeId 식별자 자체의 안정성 보강
- area annotation 을 다른 화면에서 보이게 하는 새 anchor 시스템
- preview-session 동기화 일반론

---

# MCP Handoff Follow-ups — Open Risks (post-merge `1040988`)

**Date:** 2026-05-10
**Status:** Open follow-ups after merge `1040988` (mcp-handoff-followups, Tasks 1-8)
**Companion to:**
- `plans/2026-05-10-mcp-handoff-followups.md`
- `specs/2026-05-10-mcp-handoff-followups-design.md`

위 R1–R5 는 `annotation-pin-visibility-by-anchor` 워크스트림의 open risks 다. 아래 H1–H8 은 별개의 머지(`mcp-handoff-followups` — 폴링 백오프 + 하이라이트 버스트 가드 + plan/spec 정정)에서 발생한 open risks 로, 사용자 요청에 따라 같은 파일에 모았다. (구조 코멘트: 다음에 비슷한 통합이 또 있다면 별 파일로 분리하는 것이 검색·참조 측면에서 낫다.)

자동 검증 통과: `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test` → 613/0. 그러나 머지된 변경의 가치는 라이브 폴링 실패 회복 UX 와 시각적 노이즈 억제에 있고, 자동 테스트는 그 둘을 직접 측정하지 못한다.

---

## H1. HTML/CSS 결합 의존성 — silent visual regression 경로

[중간 우선순위]

**상태:** 코드 단위 회귀 테스트로는 잡히지 않음. 머지 시점 reviewer 가 직접 확인.

**문제:** `connection.js` 가 `connectionDetailsBody.textContent` 에 `\n` 을 넣어 "Reconnecting feedback updates…" 서브라인을 만든다. 이것이 줄바꿈으로 렌더링되려면 해당 엘리먼트가 `<pre>` + CSS `white-space: pre-wrap` 이어야 한다. 다른 엘리먼트로 바뀌거나 CSS 가 변경되면 sub-line 이 인라인으로 뭉개지지만 JS 단위 테스트는 통과한다.

**왜 지금 fix 안 했나:** 현재 사양 충족. 단순 fix 이고 reviewer 가 인덱스에서 직접 확인했음.

**대응 방안 (예약):**
- 옵션 A (권장): `connection.js` 인접에 의존성 코멘트 — `// Requires: <pre id="connectionDetailsBody"> with white-space: pre-wrap`. ROI 우수, 구현 1 분.
- 옵션 B: `FeedbackConsoleServerTest.kt` 에 HTML 어서션 추가 — `assertTrue(html.contains("<pre id=\"connectionDetailsBody\""))` + 스타일 매칭. 더 견고하지만 over-test 가능.

**트리거:** `index.html` 또는 `styles.css` 리팩터 PR 이 들어오면 — 또는 다음 콘솔 UI 작업 묶음에 옵션 A 한 줄 포함.
**Resolved:** 2026-05-10, commit 29f235c (옵션 A 채택 — connection.js 인라인 코멘트). 트리거 조건은 더 이상 활성 risk 가 아님.

---

## H2. 번들 모듈 순서 의존성 (런타임 lookup)

[중간 우선순위]

**상태:** 안정 동작 중. 명시적 의존성 그래프 없음.

**문제:** `state.js` 의 `withMutationLock` finally 블록이 `sessions-polling.js` 에 정의된 `startSessionsPolling` 을 호출한다. 같은 IIFE 로 concat 되기 때문에 런타임 함수 lookup 이 작동한다. 그러나 `scripts/build-console-assets.mjs` 가 모듈 순서를 바꾸거나 `sessions-polling.js` 를 빠뜨리면 `ReferenceError` 가 난다.

**왜 지금 fix 안 했나:** 현재 빌드 스크립트가 안정적. 명시적 모듈 의존성 그래프 도입은 over-engineering.

**대응 방안 (예약):**
- 옵션 A (권장): `scripts/build-console-assets.mjs` 안에 모듈 순서를 강제하는 명시적 array + 누락 검증. 실효성 더 높음.
- 옵션 B: 번들 결과의 `node --check` 외에 노드 런타임에서 페어wise 함수 호출 가능성을 한 번 dry-run.

**트리거:** 빌드 스크립트 변경 또는 콘솔 모듈 추가/제거 PR.
**Resolved:** 2026-05-10, commit a7adb0a (옵션 A 채택 — build-console-assets.mjs 에 directory-scan + Set diff 누락 검증 추가). 미선언 신규 .js 파일은 빌드 시점에 즉시 실패한다.

---

## H3. Paused 진입 전 10초 무음 구간

[중간 우선순위 — 다음 콘솔 UI 작업 첫 후보]

**상태:** spec 이 의도한 동작 ("silently absorbs up to 5 consecutive failures"). 단 사용자 관점 UX 는 의도와 분리해서 평가 필요.

**문제:** 5회 실패 × 2초 폴링 간격 = paused 상태로 들어가기 전 약 10초 동안 화면은 stale 인데 어떤 UX 시그널도 없다. 사용자는 "데이터가 안 바뀌는데?" 라고 느낄 수 있다.

**왜 지금 fix 안 했나:** spec design section "Risks & Mitigation" 마지막 줄 — "If telemetry shows users misinterpret 'paused' as 'broken', switch to a retry indicator with a spinner." 텔레메트리 신호 없이 미리 인디케이터 추가는 over-engineering.

**재평가:** 위 도그마는 "큰 변경" 에는 맞지만, 작은 spinner 한 개(추정 수십 줄) 는 텔레메트리 없이도 정당화 가능한 수준이다. 10초는 사용자 입장에서 짧지 않다. **다음 콘솔 UI 작업이 발생하면 같이 묶을 첫 후보**로 둔다 (단독 PR 가치는 여전히 낮음).

**대응 방안 (예약):** 폴링 실패 1~4 회 동안 connection card 에 작은 retry spinner. paused 후에는 현재 "Reconnecting feedback updates…" 메시지 그대로.

**트리거:** 다음 콘솔 UI 작업 PR (묶음), 또는 사용자 보고/텔레메트리에서 "console doesn't react" 류 신호.

---

## H4. paused → reconnect 라이프사이클 E2E 자동화 부재

[낮음 우선순위]

**문제:** 자동 검증은 함수 단위로만 보장한다. "5회 실패 → paused → 카드 메시지 → 회복(visibilitychange or withMutationLock) → 카운터 리셋 → 메시지 제거" 의 전체 시퀀스를 자동으로 도는 테스트가 없다. 단계별 회귀가 한 군데만 깨져도 다른 곳은 통과해서 silent fail 가능.

**왜 지금 fix 안 했나:** spec testing strategy 가 명시적으로 이 흐름을 "Manual / E2E (no harness for)" 로 분류. 현재 리포에는 브라우저 자동화 인프라가 없다.

**대응 방안 (예약):** Playwright 또는 비슷한 브라우저 자동화 도입 시 첫 시나리오. `MockServer` 로 5회 500 → 정상 응답 시퀀스를 만들고 콘솔 DOM 변화를 단계별로 검증.

**트리거:** 라이브 회귀 발견 또는 브라우저 자동화 인프라 도입 결정.

---

## H5. 재진입 시 `local.properties` 재생성 필요 (orchestrator 환경)

[낮음 우선순위]

**문제:** 이번 머지 worktree 에서 `:fixthis-compose-sidekick:testDebugUnitTest` 가 Android SDK 경로를 못 찾아 sub-agent 가 worktree 에 `local.properties` 를 만들었다 (`sdk.dir=/Users/kws/Library/Android/sdk`). gitignored 라 worktree 제거 시 함께 삭제됐지만, 이후 새 worktree 마다 같은 작업이 필요하다.

**왜 지금 fix 안 했나:** worktree 단위 환경 자동 셋업은 orchestrator skill 영역. 이번 머지의 코드/문서 범위 밖.

**대응 방안 (예약):**
- 옵션 A (권장): `kws-skills/kws-claude-multi-agent-executor` 의 Phase 0 에 "Android SDK prime" 단계 추가 (main 체크아웃의 `local.properties` 를 worktree 로 복사)
- 옵션 B: 리포 루트에 `scripts/prime-worktree.sh` 를 두고 worktree 생성 직후 호출

**트리거:** 다음 orchestrator 실행이 같은 SDK 경로 이슈로 막히면 (지금 이미 한 번 발생).

---

## H6. Sub-agent worktree-path drift 재발 (Reviewer)

[중간 우선순위 — 우선순위 상향 (orchestrator 신뢰도 직결)]

**상태:** 알려진 패턴(`feedback_subagent_worktree_drift.md`) 재발. 단발 사고가 아니라 **반복 패턴**으로 굳어지고 있어 우선순위를 "낮음"에서 "중간"으로 올린다.

**문제:** 이번 Task 7 Combined Reviewer 가 main 체크아웃 경로(`/Users/kws/source/android/FixThis/...`)로 드리프트해 "blockquote 가 없다" false FAIL 을 보고했다. orchestrator 가 worktree 경로를 명시해 재디스패치 → PASS 확인. 사용자 feedback memory 의 "Sub-agents drift into main checkout when prompts mention main-only paths" 패턴 그대로 재발.

**왜 지금 fix 안 했나:** orchestrator 가 직접 재디스패치로 처리. skill 자체의 프롬프트 템플릿 변경 사항이라 별도 PR 가치.

**대응 방안 (예약):** Combined Reviewer 프롬프트 템플릿 갱신:
- `FILES_REVIEWED` 의 모든 경로가 `<worktree_path>/` prefix 로 시작하지 않으면 자동 ESCALATE 또는 자동 재디스패치
- 또는 Reviewer 프롬프트 첫 줄에 "ALL Read tool calls MUST use absolute paths starting with <worktree>/" 강제 명시

**트리거:** **다음 multi-agent 실행 직전에 반드시 적용**. 또 한 번 재발하면 false-FAIL 이 silent regression 으로 빠질 수 있음.

---

## H7. Visibilitychange 시 즉시 재폴링 (정상이나 약간 wasteful)

[낮음 우선순위]

**문제:** 탭이 활성화될 때마다 `startSessionsPolling()` 이 무조건 호출되어 카운터 리셋 + 즉시 fetch 가 발생한다. spec 준수 동작이지만 잦은 탭 전환 시 백엔드 부하가 약간 증가.

**왜 지금 fix 안 했나:** 의도된 동작. spec recovery transitions 에 명시. 영향 미미.

**대응 방안 (예약):** 백엔드 폴링 부하 모니터링 기준선 초과 시 visibility 핸들러를 `if (!document.hidden && state.connection.sessionsPollingPaused) startSessionsPolling();` 로 좁힌다 — paused 상태에서만 강제 재시작, 그 외에는 다음 자연 tick 대기.

**트리거:** 백엔드 메트릭에서 visibility-induced 부하 신호 또는 주기적 부하 리뷰.

---

## H8. `visibilityChangeRecoversFromPolledFailure` 테스트 정밀도 약함

[낮음 우선순위]

**문제:** 어서션이 글로벌 HTML 의 문자열 존재만 검증한다 — `html.contains("sessionsPollingPaused") && html.contains("startSessionsPolling")`. 실제 visibility 핸들러 본문에 `startSessionsPolling()` 호출이 없어도 다른 곳에 같은 문자열이 있어 통과 가능. 향후 visibility 핸들러 리팩터에서 그 호출이 빠지는 회귀를 자동으로 잡지 못한다.

**왜 지금 fix 안 했나:** 기존 테스트 코퍼스의 일관된 패턴 (다수 존재 어서션). 머지 시점 reviewer 가 수용.

**대응 방안 (예약):** visibility 핸들러는 익명 화살표라 기존 `javascriptFunctionBody` 헬퍼가 추출하지 못한다. 핸들러를 named function 으로 분리 (`function onVisibilityChange() {...}`)한 뒤 어서션을 그 함수 본문으로 좁힌다.

**트리거:** 미래에 main.js 의 visibility 영역을 리팩터하는 PR 에 같이 묶어 진행. 단독 PR 가치는 낮음.

---

## 우선순위 (H 시리즈)

1. **H6** — orchestrator 신뢰도 직결. 다음 multi-agent 실행 직전에 반드시.
2. **H3** — 중간. 다음 콘솔 UI 작업 묶음 또는 spinner 단독 PR. (H1, H2 는 2026-05-10 해결됨 — commits 29f235c, a7adb0a.)
3. **H4, H5, H7, H8** — 트리거 기반 (사용자 보고, 텔레메트리, 환경 변경, 인접 리팩터).

---

## Out of scope — Followups (반복 명시)

본 머지(`mcp-handoff-followups`)는 브라우저 콘솔 + 문서 한정. 다음은 모두 **의도적으로** 안 건드렸다.

- 라이브 프리뷰 폴링 (`startLivePreviewPolling` in `main.js`) — 디바이스 HOME 으로 샘플 앱이 background 가면 sidekick 이 in-process 라 캡처 자체가 멈추는 것은 아키텍처상 by-design 이고 본 머지 범위 밖.
- 폴링 간격(2초) 자체의 exponential backoff
- BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6 의 사용자 설정화 (localStorage 등)
- backend 측 폴링/세션 응답 형식 변경
- MCP 도구 시그니처 변경

---

# 통합 우선순위 (R + H)

다음 작업 세션을 잡을 때 참고용 합본:

1. **R1 + R3 동시 검증 스모크** — 가장 먼저. 다른 모든 R 항목의 dependency. (post-merge-essential-followups executor run 에서는 디바이스 부재로 의도적으로 deferred — 사용자 수동 수행 대기.)
2. **H6 orchestrator Reviewer 프롬프트 갱신** — 다음 multi-agent 실행 전 반드시.
3. **R1 결과에 따라** R2/R3 별 plan 여부 결정.
4. **H3** — 다음 콘솔 UI 작업 묶음에 포함. (H1, H2 는 2026-05-10 해결됨.)
5. **H5** — 다음 orchestrator 실행이 SDK 이슈로 막히면.
6. **R4, R5, H4, H7, H8** — 트리거 대기.

## Resolved (2026-05-10, post-merge-essential-followups executor run)

- **H1** (HTML/CSS dependency comment) — commit `29f235c`
- **H2** (build-script drift detector) — commit `a7adb0a`
- **R1, R3** — NOT resolved this run; manual smoke deferred. Operator will run S1–S11 + R3 nodeUid intersection check separately and update markers afterward.
