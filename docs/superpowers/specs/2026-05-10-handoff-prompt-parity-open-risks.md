# Handoff Prompt Parity + Bootstrap MCP — Open Risks (post-merge)

**Date:** 2026-05-10
**Status:** Open follow-ups after merges `65033ac` (Pair A) and `a6839d6` (Pair B)
**Companion to:**
- `plans/2026-05-10-handoff-prompt-parity.md`
- `specs/2026-05-10-handoff-prompt-parity-design.md`
- `plans/2026-05-10-contributor-bootstrap-mcp.md`
- `specs/2026-05-10-contributor-bootstrap-mcp-design.md`

머지된 변경은 자동 테스트 (5-module 647/0) + 라이브 console 의 새 엔드포인트 curl 검증을 통과했다. 그러나 fix 의 가치는 **사용자가 브라우저에서 Copy Prompt / Save to MCP 버튼을 누르는 경험**에 있고, 자동 테스트가 그것을 직접 측정하지 못한다. 또한 일부 변경은 새 contract 의 외부 영향까지 자동 가드하지 못한다. 아래는 머지 시점에 의식적으로 남겨둔 리스크 목록이다.

검증 우선순위: **H1 → H3 → H5 → H2/H4/H6**.

---

## H1. 브라우저 UX 미검증 (highest priority)

**상태:** 미수행. plan Task 4 의 manual smoke (Step 5) 가 device/emulator 를 요구해서 sub-agent 가 실행할 수 없었고, 머지 시점까지 사람이 한 번도 돌리지 않았다. 다만 서버 contract (`POST /api/sessions/{sid}/handoff-preview`, `/api/agent-handoffs`) 는 라이브 console 에 curl 로 호출해 200 응답 + 모든 spec 필드 (`id:`, `session_id:`, `agent_protocol:`) + 4 가지 에러 케이스 (400/404 + JSON 본문) 를 검증했다.

**절차적 약점 (자기 비판):** manual smoke 가 필요한 UX fix 는 머지 *전* 에 처리하는 것이 정석. annotation-pin-visibility-by-anchor 와 동일 패턴 — 자동 테스트 통과만으로 합쳤고, 그 결과 H1 이 나머지 H 들의 게이트로 남았다. 다음 유사 워크스트림은 manual smoke 를 머지 전 차단 단계로 둘 것.

**왜 중요한가:** 자동 테스트가 보장하지 않는 것:
- `copyPrompt` / `sendAgentPrompt` 가 실제 브라우저에서 클릭 시 무반응 / 클립보드 실패 없이 동작
- `withMutationLock` 으로 in-flight 시 중복 클릭 방지 동작
- `flashCopied` ("Copied ✓" 라벨 1.5s 후 복원) UX
- `persistAndCollectItemIds` fallback 분기: newly-persisted 가 0 일 때 `currentPromptAnnotations()` 로 폴백
- `copyTextToClipboard` 의 비-secure context (HTTP localhost) 처리 — `navigator.clipboard` 미가용 시 textarea fallback
- Save to MCP 후 console 이 실시간으로 SENT 전이 반영 (DRAFT→SENT)

**검증 방법:**
```bash
./scripts/bootstrap-mcp.sh --package io.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

브라우저에서:
1. 디바이스 연결 후 어노테이션 1개에 코멘트 작성
2. **Copy Prompt** 클릭 → 라벨이 "Copied ✓" 로 깜빡 → 1.5s 후 복원
3. 다른 곳에 붙여넣기 → 본문에 `id:`, `session_id:`, `agent_protocol:` 모두 포함 확인
4. **Save to MCP** 클릭 → 토스트 "Saved to MCP ✓ — agent will pick up" 확인
5. 콘솔에서 해당 아이템 status 가 SENT 로 전이됐는지 (배지 색상 변경) 확인
6. devtools Network 탭: `/api/sessions/.../handoff-preview` 와 `/api/agent-handoffs` 요청이 200 으로 떠야 하고, 후자의 response body 에 `{session, prompt}` 둘 다 존재해야 함

H1 가설 ("서버 contract == UX 동작") 이 실제 앱에서 깨지면 5 가지 시나리오가 한꺼번에 잘못 동작할 수 있다.

---

## H2. 에러 응답 shape 변경의 외부 영향 — **CLOSED**

**상태:** 머지 완료. **모든 `/api/*` 라우트** 의 에러 응답이 `text/plain "<message>"` 에서 `application/json {"error":"<message>"}` 로 변경됨 (Task 2 재시도에서 spec §4.1 준수를 위해 중앙화된 에러 핸들러 수정).

**검증 결과 (2026-05-10):** repo 전체 grep 으로 `/api/*` 응답 본문을 텍스트로 파싱하는 비-테스트 호출자 없음 확인. Adb.kt 의 errorStream 매치는 adb 외부 명령이지 우리 console API 와 무관. **Close.**

---

## H3. Convenience overload bypass — **CLOSED (option A 적용)**

**상태:** Task 3 재시도에서 추가된 `FeedbackSessionService.sendDraftToAgent(sessionId): SessionDto` 에 `@Deprecated("test/MCP-tool only", ReplaceWith(...))` 마킹 적용. 컴파일 시 호출처 (test 7곳) 에서 deprecation warning 발생, production 코드에서 신규 사용 시 lint 경고로 감지된다. 테스트는 모두 PASS.

---

## H4. `fixthis_read_feedback` 출력도 동시 변경됨 — **CLOSED**

**상태:** 의도된 변경 (spec §7 명시). Task 1 의 cosmetic 조정 — 스크린샷 fallback (`desktopFullPath ?: fullPath`) 과 Overlap 헤더 후 빈 줄 제거 — 가 `fixthis_read_feedback` 출력에도 그대로 적용된다.

**검증 결과 (2026-05-10):** 외부에 빈 줄/공백 의존 grep 결과 — 코드 호출자 없음. 단 `docs/feedback-console-contract.md:65` 의 EBNF 정의가 옛 빈 줄 패턴 (`"" item_block+`) 을 명시하고 있어 새 코드와 불일치 발견. 동일 follow-up 에서 contract 정의를 새 코드에 맞춰 수정 (`overlap_block = "Overlap group " N " (resolve one marker at a time):" item_block+`). **Close.**

---

## H5. `bootstrap-mcp.sh --write` 미검증 — **CLOSED (실제 버그 발견 → fix 적용)**

**상태:** 사용자 머신에서 임시 프로젝트 클론 + smoke 실행으로 검증. **실제 production 버그를 잡음.**

**발견한 버그:** `set -u` 와 `"${EXTRA_FLAGS[@]}"` 의 결합으로, `--dry-run` 인자가 안 주어졌을 때 빈 배열 expansion 이 항상 `unbound variable` 에러로 깨짐. 즉 fresh contributor 가 `./scripts/bootstrap-mcp.sh --package <id>` (가장 일반적인 사용 패턴) 로 실행하면 100% 실패. 단위 시그니처는 `bash -n` 통과하는 잠재적 결함.

**Fix:** `"${EXTRA_FLAGS[@]}"` → `${EXTRA_FLAGS[@]+"${EXTRA_FLAGS[@]}"}` (표준 bash 빈 배열 안전 expansion 패턴).

**부수 발견 (H7 신규):** Java `System.getProperty("user.home")` 가 `HOME` env var 를 무시함 — smoke 시 `HOME=$FAKE_HOME` 으로 격리 의도했으나 codex config 는 실제 `/Users/kws/.codex/config.toml` 에 작성됨. 이는 `fixthis setup` 자체의 동작 (sysctl/sysprop 기반 user.home 결정) 으로 production 에서는 정상이지만, **테스트 격리가 안 되므로 향후 H5 같은 자동화 smoke 에서는 별도 user-home override 가 필요**하다. 본 검증에서는 사용자 codex config 가 임시 경로로 일시 오염되었고 즉시 정상 경로로 복구됨.

---

## H6. PromptParityTest 삭제로 회귀 가드 사라짐

**상태:** Task 4 에서 의도적으로 삭제 (JS 렌더러 자체가 사라졌으므로 parity 테스트는 N/A).

**왜 중요한가:** 이제 Kotlin 이 단일 진실 원천. 향후 `CompactHandoffRenderer` 변경이 `fixthis_read_feedback` 출력과 Copy Prompt 출력을 동시에 깨뜨려도 (둘 다 같은 함수 호출하므로) 그것을 잡을 별도 회귀 가드는 없다.

**현재 가드:**
- `CompactHandoffRendererTest` — 64 케이스, 모든 주요 분기
- `FeedbackConsoleServerTest.handoffPreviewEndpoint*` — 4 케이스
- `FeedbackConsoleServerTest.agentHandoffs*` — 4 케이스 (포함 DRAFT→SENT scope)

**왜 충분한가:** 64 케이스가 사실상 spec 의 모든 contract 분기를 커버. parity test 는 JS 의 잘못된 구현으로부터 보호하는 게 본질이었으므로 JS 가 사라진 지금 무의미.

**기각 조건:** 추가 가드 불필요 — 다만 향후 새 필드 추가 시 (`agent_protocol:` 류) `CompactHandoffRendererTest` 에 케이스 추가 강제하는 lint 룰을 고려할 수 있음 (out of scope).

---

## 권장 액션 순서

**남은 항목 (사용자 액션 필요):**
1. **H1 디바이스 smoke** — 5 분, 가장 큰 가치
2. **H5 임시 HOME smoke** — 5 분, fresh contributor 안전망

**자동 close 완료 (2026-05-10 follow-up):**
- ~~H2 외부 영향~~ — grep 결과 호출자 없음
- ~~H3 overload bypass~~ — `@Deprecated` 마킹 + ReplaceWith 힌트
- ~~H4 fixthis_read_feedback 동시 변경~~ — contract 문서 동기화
- ~~H6 PromptParityTest 삭제~~ — `CompactHandoffRendererTest` 64 케이스로 충분

H1, H5 만 디바이스/머신 액세스 필요한 수동 단계로 남는다.
