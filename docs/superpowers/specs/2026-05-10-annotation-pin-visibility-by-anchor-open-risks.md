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

이 fix 의 핵심 가설("같은 화면 재캡처 시 nodeUid 보존, 다른 화면 네비 시 변경") 이 실제 앱에서 깨지면 11 시나리오가 한꺼번에 잘못 동작할 수 있다.

**검증 방법:**
```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
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

**완료 조건:** plan 의 S1~S11 11 시나리오 통과 (S10 은 의도된 한계 확인).

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

**상태:** 가정만 하고 방어 없음.

**문제:** 두 다른 logical screen 이 우연히 같은 `compose:rootIndex:treeKind:nodeId` uid 를 가지면, 직전 화면의 anchor 가 새 화면의 노드와 매치되어 잘못 표시된다.

**왜 지금 fix 안 했나:** 일반 NavHost 패턴에서 `rootIndex` 가 화면별로 다르거나, 같다면 컴포지션 dispose → recreate 가 nodeId 를 재할당시키므로 collision 가능성 낮다고 가정. 정량적으로 검증 안 함.

**대응 방안 (예약):** backend 측에 screen-level identifier (예: composition hash) 를 추가하고 anchor 매칭에 같이 사용. fixthis-compose-sidekick 의 `SemanticsNodeMapper.uidFor` 변경이 필요. 별도 spec 가치 있는 규모.

**트리거:** R1 스모크 시나리오 S2/S3 에서 직전 화면 핀이 새 화면에 잘못 보이면.

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

## 우선순위

1. **R1** — 머지된 fix 의 실제 효과를 사람이 확인. 다른 모든 항목은 R1 에서 발견되거나 확인된 사실에 의존한다.
2. **R2/R3** — R1 에서 관측되면 별도 plan.
3. **R4** — 사용자 보고 대기.
4. **R5** — 선택. 보통 그냥 둠.

---

## Out of scope (반복 명시)

본 fix 는 브라우저 콘솔 한정. 다음은 모두 **의도적으로** 안 건드렸다.

- backend / Kotlin DTO / persistence
- nodeId 식별자 자체의 안정성 보강
- area annotation 을 다른 화면에서 보이게 하는 새 anchor 시스템
- preview-session 동기화 일반론
