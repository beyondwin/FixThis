# Spec — Responsive Error and Agent Feedback UX Hardening

Status: Implemented
Date: 2026-05-13
Owner: Console / FixThis MCP
Scope: `:fixthis-mcp` browser feedback console, sample Compose app, sidekick status overlay
Implementation plan: `docs/superpowers/plans/2026-05-13-responsive-error-agent-feedback-ux-implementation.md`
Related audit artifacts:

- `output/playwright/fixthis-desktop-1280-error-agent.png`
- `output/playwright/fixthis-compact-1024-error-agent.png`
- `output/playwright/fixthis-breakpoint-900-error-agent.png`
- `output/playwright/fixthis-mobile-390-error-agent.png`

---

## 1. Background

FixThis는 Android 앱 안에서는 연결 상태 pill만 보여주고, 선택/어노테이션/에이전트 핸드오프는 desktop browser console에서 처리한다. 따라서 콘솔의 좁은 화면 대응, 오류 표시, 에이전트 상태 표시가 제품 신뢰도에 직접 영향을 준다.

이번 감사에서는 실제 콘솔 HTML/CSS를 Playwright로 로드한 뒤 오류 문구, 연결 세부정보, activity drift, 에이전트 in-progress/resolved 상태, 긴 agent summary, staleness banner를 강제로 주입해 1280/1024/900/390 폭에서 확인했다. 특히 390px 모바일 폭에서 document width가 681px까지 늘어나 수평 스크롤이 생겼고, 오류/에이전트 피드백 영역이 좁은 inspector 안에 눌리거나 원시 텍스트처럼 보이는 문제가 확인됐다.

추가로 `npm run console:smoke`는 `scripts/console-browser-smoke.mjs`의 `waitForReady()` 단계에서 `#connectionCard`가 `ready`가 되기를 기다리다 `BROWSER_SMOKE_FAILED: page.waitForFunction: Timeout 30000ms exceeded`로 실패했다. 본 스펙의 구현 전에는 smoke fixture/connection-ready 경로도 함께 정상화해야 한다.

## 2. Problem Statement

현재 문제는 "한 가지 큰 화면에서만 그럴듯하게 보이는 콘솔"에 가깝다는 점이다. 일반적인 데스크톱 폭에서는 치명적으로 보이지 않지만, 에러/상태/에이전트 피드백처럼 텍스트가 길어지는 순간 다음 문제가 겹친다.

1. 900px 이하 responsive rule이 topbar context를 grid처럼 다루지만 실제 display는 flex라서 의도한 줄바꿈이 일어나지 않는다.
2. inspector 하단의 `#error`는 오류/성공/부분 성공/연결 복구 메시지를 모두 맡기에는 너무 작고 위치도 늦게 보인다.
3. MCP에는 `needs_clarification`, `wont_fix`, `agentNote`가 존재하지만 콘솔은 이를 독립 상태로 충분히 표현하지 못한다.
4. activity drift 경고 markup은 존재하지만 CSS가 없어 중요 경고가 원시 DOM처럼 보인다.
5. agent summary와 connection details는 긴 경로/스택/한 줄 요약에서 수평 overflow가 쉽게 생긴다.
6. 샘플 Compose 앱과 sidekick overlay도 좁은 폭, 큰 font scale, split-screen에서 row 기반 레이아웃이 버티는지 검증되지 않았다.

## 3. Goals

- 390px 이상의 모든 폭에서 콘솔 최상위 document가 viewport보다 넓어지지 않는다.
- 에러, 경고, 성공, 부분 성공, 연결 복구 메시지를 사용자가 즉시 볼 수 있는 dedicated status surface로 분리한다.
- 에이전트 피드백 상태를 `Draft / Sent / Modified / In Progress / Needs Clarification / Won't Fix / Resolved`로 명시적으로 보여준다.
- `fixthis_claim_feedback(agentNote)`로 들어온 작업 메모와 `fixthis_resolve_feedback(summary)`로 들어온 완료/질문/미처리 사유를 콘솔 detail에서 읽기 쉽게 표시한다.
- activity drift, stale binary, connection details, long agent summary가 좁은 inspector나 topbar를 밀어내지 않는다.
- Playwright 기반 responsive/error/agent-state regression test를 추가해 같은 문제가 다시 들어오지 않게 한다.
- 샘플 Compose UI와 sidekick overlay의 작은 화면/큰 글꼴 대응 리스크를 별도 추적 가능한 요구사항으로 만든다.

## 4. Non-Goals

- 콘솔을 React/Vue/Svelte 같은 framework로 재작성하지 않는다.
- MCP persisted JSON의 기존 public field 이름을 바꾸지 않는다.
- release build 지원, View/Flutter target 지원, cloud sync는 범위 밖이다.
- agent와 사용자 사이의 실시간 conflict resolution은 범위 밖이다. 본 스펙은 표시와 안전한 재전송 nudge까지만 다룬다.
- 샘플 앱의 시각 디자인을 전면 개편하지 않는다. 좁은 폭과 큰 font scale에서 깨지지 않는 adaptive hardening만 다룬다.

## 5. Priority Map

| ID | Priority | Area | Failure Mode |
|----|----------|------|--------------|
| RUX-1 | P0 | Console responsive shell | 390px에서 document width 681px, topbar/body 수평 overflow |
| RUX-2 | P0 | Error/status surface | 오류가 inspector 하단의 좁은 `<p>`에 숨거나 잘림 |
| RUX-3 | P0 | Agent terminal states | `needs_clarification`/`wont_fix`가 일반 sent 상태처럼 보임 |
| RUX-4 | P1 | Agent notes and summaries | in-progress note 미표시, 긴 summary/path 수평 overflow |
| RUX-5 | P1 | Activity drift warning | 중요 drift 경고가 스타일 없는 원시 블록으로 노출 |
| RUX-6 | P1 | Copy Prompt partial failure | clipboard 성공 후 MCP handoff mark 실패가 조용히 무시됨 |
| RUX-7 | P1 | Connection details/staleness | 긴 raw error/detail이 topbar나 connection card를 밀어냄 |
| RUX-8 | P1 | Browser regression coverage | smoke 실패 및 error/agent responsive fixture 부재 |
| RUX-9 | P2 | Sample Compose adaptive rows | long labels/actions가 row 안에서 좁게 압착될 수 있음 |
| RUX-10 | P2 | Sidekick overlay pill | 작은 화면/split-screen에서 pill이 앱 UI를 덮거나 잘릴 수 있음 |

---

## 6. RUX-1 — Console Responsive Shell

### Current Behavior

Base layout은 `.studio-topbar`를 `220px minmax(360px, 1fr) auto`로 고정하고, `.studio-body`도 `280px minmax(480px, 1fr) 340px`로 넓은 3-column grid를 강제한다. 1099px 이하에서는 `.studio-context`가 `display:flex; flex-wrap:nowrap`로 유지된다. 899px 이하에서는 `.studio-context`에 `grid-template-columns`만 지정되지만 `display:grid`로 바뀌지 않아 해당 rule은 사실상 작동하지 않는다.

Relevant files:

- `fixthis-mcp/src/main/resources/console/styles.css:37` — topbar base grid
- `fixthis-mcp/src/main/resources/console/styles.css:47` — body base grid
- `fixthis-mcp/src/main/resources/console/styles.css:95` — context/actions shared flex rule
- `fixthis-mcp/src/main/resources/console/styles.css:1123` — 1099px context nowrap
- `fixthis-mcp/src/main/resources/console/styles.css:1188` — 899px context grid columns without `display:grid`

### Desired Behavior

- 390px viewport에서 `document.documentElement.scrollWidth <= window.innerWidth`가 항상 true여야 한다.
- Topbar는 좁은 폭에서 brand, device control, interval/actions가 자연스럽게 2-3줄로 내려가야 한다.
- Body는 899px 이하에서 history, canvas, inspector가 single-column flow로 전환되고 각 section이 viewport를 밀어내지 않아야 한다.
- 모든 grid/flex child에는 `min-width:0` 또는 명시적 wrapping policy가 있어야 한다.

### Requirements

- 899px media query에서 `.studio-context`는 실제 grid 또는 wrapping flex 중 하나로 명확히 전환한다.
- `.studio-topbar`, `.studio-shell`, `.studio-body`는 `max-width:100vw` 경계를 가져야 한다.
- `.studio-actions` 버튼들은 390px에서도 한 줄 고정이 아니라 wrapping을 허용한다.
- `.device-control`, `#previewIntervalSelect`, `.clear-device-button`은 텍스트 ellipsis와 accessible title/aria-label을 유지해야 한다.
- `body { overflow:auto }`는 유지하되, 수평 overflow는 regression으로 간주한다.

### Acceptance Criteria

- Playwright에서 390, 768, 900, 1024, 1280 viewport를 열고 `document.scrollingElement.scrollWidth`가 `window.innerWidth + 1`을 넘지 않는다.
- 390px screenshot에서 topbar action이 잘리거나 화면 밖으로 나가지 않는다.
- History/canvas/inspector가 vertical flow로 쌓이며 inspector footer와 status surface가 모두 접근 가능하다.
- 기존 desktop 1280 layout의 3-column 정보 밀도는 유지된다.

---

## 7. RUX-2 — Dedicated Error and Status Surface

### Current Behavior

콘솔의 주요 오류 표시는 inspector 마지막의 `<p id="error" class="error">` 하나다. 이 요소는 `min-height:18px`, 하단 padding만 있으며, inspector가 닫히거나 긴 saved detail을 보고 있을 때 사용자가 즉시 보기 어렵다. `showError()`는 friendly/raw message를 그대로 이 좁은 영역에 쓴다.

Relevant files:

- `fixthis-mcp/src/main/resources/console/index.html:131` — `#error`
- `fixthis-mcp/src/main/resources/console/styles.css:1102` — `.error`
- `fixthis-mcp/src/main/console/main.js:83` — `showError()`

### Desired Behavior

오류/성공/부분 성공/연결 복구 메시지는 console-level status surface에 표시되어야 한다. Inspector 내부의 form validation은 inspector 안에 남길 수 있지만, bridge/MCP/agent handoff 오류는 canvas/topbar 아래의 넓은 영역에서 보여야 한다.

### Requirements

- `#error`를 단순 paragraph가 아니라 dismissible status banner 또는 status stack으로 승격한다.
- 상태 variant를 최소 4개로 분리한다: `error`, `warning`, `success`, `info`.
- 긴 메시지는 `overflow-wrap:anywhere`, `white-space:normal`, `max-width:100%`로 줄바꿈한다.
- 같은 heartbeat 오류 반복은 사용자가 보는 actionable message를 덮어쓰지 않아야 한다.
- Status surface에는 "Copied, but MCP handoff status was not updated" 같은 partial success를 표시할 수 있어야 한다.
- ARIA는 `role="status"` 또는 severity별 `role="alert"`를 명확히 사용한다.

### Acceptance Criteria

- 390px에서 300자 이상의 bridge error가 수평 스크롤 없이 3-6줄로 표시된다.
- Error 다음 success, success 다음 error가 들어와도 색상/아이콘/aria state가 섞이지 않는다.
- Inspector body가 긴 saved annotation detail을 표시 중이어도 latest global error가 화면 상단 또는 visible status area에 노출된다.

---

## 8. RUX-3 — Agent Terminal State Rendering

### Current Behavior

Kotlin DTO와 MCP tool은 `needs_clarification`, `wont_fix`를 지원한다. 하지만 browser console의 lifecycle mapping은 `resolved`, `in_progress`, `sent_modified`, `sent`, `draft`만 반환한다. 따라서 `fixthis_resolve_feedback(status="needs_clarification")` 또는 `status="wont_fix"` 이후에도 사용자는 일반 sent 또는 fallback 상태처럼 해석할 수 있다.

Relevant files:

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt:124` — status enum
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt:474` — allowed agent resolution statuses
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt:433` — MCP string to status mapping
- `fixthis-mcp/src/main/console/annotations.js:51` — lifecycle mapping lacks `needs_clarification`/`wont_fix`
- `fixthis-mcp/src/main/console/rendering.js:356` — detail banner lacks those terminal states

### Desired Behavior

Agent가 작업을 끝냈는지, 못 하겠다고 판단했는지, 사용자에게 질문이 필요한지가 콘솔에서 즉시 구분되어야 한다.

### Requirements

- `lifecyclePhase(item)`는 다음 phase를 반환해야 한다:
  - `draft`
  - `sent`
  - `sent_modified`
  - `in_progress`
  - `needs_clarification`
  - `wont_fix`
  - `resolved`
- `statusLabel(item)`는 `Needs Clarification`, `Won't Fix`를 명시적으로 반환한다.
- `statusClass(item)`에 맞춰 `.st-needs-clarification`, `.st-wont-fix` 스타일을 추가한다.
- `renderSavedAnnotationDetail()`은 두 상태에 대해 별도 banner를 보여준다.
- `needs_clarification`은 사용자 action이 필요한 상태로 취급해 history/open count에서 "done"처럼 숨기지 않는다.
- `wont_fix`는 resolved 계열 terminal state로 취급하되, 실패가 아니라 "agent가 의도적으로 처리하지 않음"으로 설명한다.

### Acceptance Criteria

- MCP로 `needs_clarification` 처리된 item은 history row/detail 모두 `Needs Clarification` 배지와 agent summary/question을 표시한다.
- MCP로 `wont_fix` 처리된 item은 `Won't Fix` 배지와 사유를 표시하며, agent work queue 기본 조회에서는 제외된다.
- 세 상태 `resolved`, `needs_clarification`, `wont_fix` 각각에 대한 console rendering unit 또는 Playwright DOM assertion이 추가된다.

---

## 9. RUX-4 — Agent Notes and Long Summary Wrapping

### Current Behavior

`fixthis_claim_feedback`는 `agentNote`를 받고, store는 이를 `agentSummary`에 저장한다. 하지만 in-progress detail banner는 "Agent working on this — edits locked."만 보여주고 note를 표시하지 않는다. Resolved 상태에서는 `<pre class="annotation-summary">`를 쓰는데, CSS는 `white-space:pre-wrap`과 `overflow-y:auto`만 지정되어 긴 path/token의 horizontal overflow를 막지 못한다.

Relevant files:

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt:181` — `agentNote` tool schema
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt:205` — `agentNote` read
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt:517` — `agentSummary = agentNote`
- `fixthis-mcp/src/main/console/rendering.js:370` — in-progress banner ignores note
- `fixthis-mcp/src/main/console/rendering.js:375` — resolved summary `<pre>`
- `fixthis-mcp/src/main/resources/console/styles.css:1058` — summary lacks long-token wrapping

### Desired Behavior

사용자는 에이전트가 claim하면서 남긴 작업 메모와 완료/질문/미처리 사유를 한 화면에서 읽을 수 있어야 한다. 긴 파일 경로, JSON key, stack trace 일부가 들어와도 inspector를 넓히거나 수평 스크롤을 만들지 않아야 한다.

### Requirements

- In-progress banner는 `agentSummary`가 있으면 "Agent note" subline으로 표시한다.
- Resolved/needs_clarification/wont_fix summary 영역은 prose mode와 code-like mode를 구분하지 않더라도 기본 wrapping은 안전해야 한다.
- `.annotation-summary`에 `overflow-wrap:anywhere`, `word-break:break-word`, `max-width:100%`, `overflow-x:hidden` 또는 의도된 horizontal scroll policy를 명시한다.
- Summary가 240px보다 길면 vertical scroll은 허용하되, scroll container의 focus/keyboard 접근성을 보장한다.

### Acceptance Criteria

- 390px inspector에서 200자짜리 file path가 들어간 `agentSummary`가 화면을 밀어내지 않는다.
- In-progress 상태에서 `agentNote`가 detail banner에 표시된다.
- Summary 영역에 copy 가능한 텍스트가 유지된다.

---

## 10. RUX-5 — Activity Drift Warning Styling

### Current Behavior

`renderPendingItems()`는 `activity-drift-warning` markup을 렌더링하지만 CSS가 없다. 감사 screenshot에서는 warning이 일반 텍스트와 기본 버튼처럼 보였다. 이 경고는 freeze 중 activity가 바뀐 상태를 알려주는 중요한 안전장치라서, 원시 UI처럼 보이면 사용자가 무시할 가능성이 높다.

Relevant files:

- `fixthis-mcp/src/main/console/rendering.js:112` — drift warning render
- `fixthis-mcp/src/main/console/rendering.js:116` — `.activity-drift-warning`
- `fixthis-mcp/src/main/resources/console/styles.css` — matching CSS 없음

### Desired Behavior

Activity drift는 annotation workflow를 계속해도 되는지 판단해야 하는 경고로 표시되어야 한다. Visual severity는 warning이고, primary action은 새 freeze 시작이다.

### Requirements

- `.activity-drift-warning` card 스타일을 추가한다.
- Title, detail, action button의 hierarchy를 분리한다.
- 긴 activity name은 줄바꿈하고, button은 390px에서 아래 줄로 내려간다.
- Warning은 pending list 최상단에 위치하되 list item처럼 오해되지 않아야 한다.
- Button label은 한국어/영어 혼합을 정리한다. 콘솔 기본 언어가 영어라면 "Start new freeze"로 맞춘다.

### Acceptance Criteria

- Activity drift가 true인 fixture에서 경고가 노란 warning treatment로 표시된다.
- 390px에서 expected/actual activity 이름이 길어도 pending item list를 밀어내지 않는다.
- Button click 후 새 freeze/restart flow가 기존 기능대로 동작한다.

---

## 11. RUX-6 — Copy Prompt Partial Failure

### Current Behavior

`copyPrompt()`는 clipboard write 성공 후 `markItemsHandedOff()`를 호출한다. 이 두 번째 call이 실패하면 catch block에서 조용히 무시한다. 결과적으로 사용자는 prompt가 복사됐으니 MCP handoff 상태도 갱신됐다고 생각하지만, 실제 session state는 staleAfterHandoff 계산과 lifecycle 표시가 갱신되지 않을 수 있다.

Relevant files:

- `fixthis-mcp/src/main/console/prompt.js:61` — copy flow
- `fixthis-mcp/src/main/console/prompt.js:76` — mark handoff call
- `fixthis-mcp/src/main/console/prompt.js:80` — silent catch

### Desired Behavior

Clipboard 성공과 MCP 상태 갱신 성공은 분리해서 알려야 한다. 복사는 성공했지만 상태 저장이 실패한 경우는 blocking error가 아니라 warning/partial success다.

### Requirements

- `markItemsHandedOff()` 실패 시 global status surface에 warning을 표시한다.
- Button label은 `Copied`로 유지하되, status message에서 "MCP status not updated"를 알려준다.
- 실패한 itemIds는 retry 가능한 상태로 남아야 한다.
- 같은 상태에서 `Copy Prompt`를 다시 누르면 mark handoff를 재시도해야 한다.

### Acceptance Criteria

- Mock API에서 clipboard success + mark 500 응답을 만들면 warning이 표시된다.
- 이 상태에서 item은 sent_modified 계산이 잘못되지 않는다.
- 재시도 성공 시 warning이 success/info로 대체된다.

---

## 12. RUX-7 — Connection Details and Staleness Banner

### Current Behavior

Connection card의 raw details는 `<details><pre id="connectionDetailsBody">`에 들어간다. CSS는 `max-width:360px`와 `white-space:pre-wrap`을 주지만, `.connection-actions`가 flex no-wrap 또는 좁은 action column 안에 들어갈 때 실제 readable width가 매우 작아질 수 있다. Staleness banner 역시 headline, code detail, dismiss button을 한 줄 flex로 배치해 긴 code detail이 topbar/document width를 밀어낼 수 있다.

Relevant files:

- `fixthis-mcp/src/main/resources/console/index.html:63` — connection actions
- `fixthis-mcp/src/main/resources/console/index.html:65` — details/pre
- `fixthis-mcp/src/main/resources/console/styles.css:159` — `.connection-actions`
- `fixthis-mcp/src/main/resources/console/styles.css:185` — `.connection-details pre`
- `fixthis-mcp/src/main/console/connection.js:160` — pre layout dependency comment
- `fixthis-mcp/src/main/resources/console/index.html:10` — staleness banner
- `fixthis-mcp/src/main/resources/console/styles.css:1339` — staleness banner flex

### Desired Behavior

Connection details와 staleness detail은 "읽을 수 있되 레이아웃을 망가뜨리지 않는" secondary diagnostics여야 한다.

### Requirements

- `.connection-actions`는 899px 이하에서 full-width wrapping을 허용한다.
- `.connection-details`와 `pre`는 `min-width:0`, `max-width:100%`, `overflow-wrap:anywhere`를 가져야 한다.
- Staleness banner는 좁은 폭에서 headline/detail/button이 vertical stack 또는 wrapping row로 전환되어야 한다.
- `<code data-detail>`은 긴 path/sha/package name을 줄바꿈할 수 있어야 한다.
- `connection.js`의 `<pre>` 의존 주석은 새 layout contract에 맞춰 갱신한다.

### Acceptance Criteria

- 390px에서 긴 `connectionDetailsBody`가 viewport를 밀어내지 않는다.
- Staleness critical banner에 120자 package/build detail을 넣어도 dismiss button이 화면 안에 남는다.
- Details를 열고 닫는 과정에서 connection primary action 위치가 불안정하게 뛰지 않는다.

---

## 13. RUX-8 — Browser Regression Coverage

### Current Behavior

현재 `npm run console:smoke`는 connection ready 대기에서 timeout이 발생했다. 또한 기존 smoke는 실제 사용자가 문제를 느끼는 error/agent feedback/narrow layout state를 충분히 stress하지 않는다.

Relevant files:

- `package.json:7` — `console:smoke`
- `scripts/console-browser-smoke.mjs:421` — ready wait

### Desired Behavior

콘솔 UI의 responsive/error/agent-state 회귀는 code review 눈검사에 의존하지 않고 자동으로 실패해야 한다.

### Requirements

- 먼저 현재 smoke timeout의 원인을 분리한다: fixture가 ready state를 못 만드는지, selector/state contract가 바뀌었는지, test wait가 과도하게 엄격한지 확인한다.
- `scripts/console-browser-smoke.mjs` 또는 별도 `scripts/console-responsive-stress.mjs`에 다음 fixtures를 추가한다:
  - connection paused/raw error open
  - activity drift warning
  - in-progress with `agentNote`
  - resolved with long `agentSummary`
  - needs clarification
  - won't fix
  - stale banner with long detail
- Viewport matrix는 최소 `390x844`, `900x900`, `1024x768`, `1280x800`이다.
- 각 viewport에서 `scrollWidth <= innerWidth + 1`를 assertion한다.
- Key containers의 `clientWidth` 대비 `scrollWidth` overflow를 검사한다: `#error`, `#connectionDetailsBody`, `.annotation-summary`, `#stalenessBanner`, `.studio-topbar`.

### Acceptance Criteria

- `npm run console:smoke`가 stable하게 통과한다.
- 새 responsive stress command가 local에서 통과하고, 실패 시 screenshot을 `output/playwright/`에 남긴다.
- CI 또는 required local checklist에 smoke/stress command가 포함된다.

---

## 14. RUX-9 — Sample Compose Adaptive Rows

### Current Behavior

샘플 앱은 FixThis가 실제로 잡아야 할 UI feedback 대상이므로 좁은 화면/큰 font scale에서 일부러 깨지는 영역도 필요하지만, baseline 자체가 너무 쉽게 row 압착을 만들면 제품 데모와 QA 신뢰도가 떨어진다. 현재 여러 컴포넌트가 `Row(SpaceBetween)` 또는 trailing action row를 사용하면서 wrap/adaptive behavior를 갖지 않는다.

Relevant files:

- `sample/src/main/java/io/github/beyondwin/fixthis/sample/FixThisStudioApp.kt:37` — 5개 tab NavigationBar
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt:25` — title/subtitle + status row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/FeedbackCard.kt:38` — title + severity row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/FeedbackCard.kt:68` — action row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/InfoRow.kt:32` — content + trailing row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProjectScreen.kt:53` — chip row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProjectScreen.kt:96` — action row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt:108` — checkbox row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt:122` — switch row
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt:62` — intentional long label + disabled action stress case

### Desired Behavior

샘플 앱은 FixThis의 selection/annotation을 검증하기 좋은 복잡도를 유지하되, 시스템 font scale과 작은 width에서 기본 navigation/action이 완전히 깨지지 않아야 한다.

### Requirements

- `StudioHeader` status chip은 text column을 압착하지 않도록 width cap 또는 second-line wrapping을 허용한다.
- `FeedbackCard` header/action row는 작은 폭에서 vertical stack으로 전환한다.
- `InfoRow` trailing content는 큰 font scale에서 아래 줄로 내려갈 수 있어야 한다.
- Review checkbox/switch row는 label에 `weight(1f)`와 wrapping을 보장한다.
- Diagnostics의 long-label case는 의도된 stress fixture로 유지하되, disabled action이 화면 밖으로 나가지 않아야 한다.

### Acceptance Criteria

- Emulator 또는 Compose screenshot test에서 width 360dp, fontScale 1.3 이상으로 주요 tab을 열었을 때 text/action overlap이 없다.
- Diagnostics long row는 여전히 FixThis target selection stress case로 의미가 있지만, disabled button은 viewport 안에 남는다.
- NavigationBar 5개 tab label이 잘리더라도 tap target은 유지된다.

---

## 15. RUX-10 — Sidekick Overlay Pill Constraints

### Current Behavior

Sidekick overlay pill은 `WRAP_CONTENT` LinearLayout을 top/end에 붙인다. 앱 UI 위에 올라가는 debug-only overlay라 간단한 구현이 맞지만, split-screen, landscape, foldable, 큰 글꼴에서는 status text가 앱 action bar나 상단 affordance를 덮거나 잘릴 수 있다.

Relevant files:

- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt:51` — horizontal pill
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt:63` — text layout params wrap content
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt:153` — top/end placement

### Desired Behavior

Overlay pill은 debug state를 보여주되, 앱의 핵심 UI를 불필요하게 가리거나 화면 밖으로 나가면 안 된다.

### Requirements

- Pill max width를 window width의 일정 비율로 제한한다.
- Status text는 single-line ellipsis 또는 compact label policy를 가져야 한다.
- Top/end margin은 status bar뿐 아니라 display cutout/window inset을 고려한다.
- 작은 화면에서 pill 위치를 top/end 고정 대신 top/start 또는 bottom/end로 바꾸는 옵션은 후속 검토 대상으로 남긴다.

### Acceptance Criteria

- 360dp portrait, landscape, split-screen에서 pill이 화면 밖으로 나가지 않는다.
- Pill이 앱 root의 primary navigation/action 영역을 1초 이상 불필요하게 가리는 경우가 없도록 screenshot으로 확인한다.

---

## 16. Implementation Sequence

1. Smoke fixture timeout을 먼저 조사하고 `npm run console:smoke`를 녹색으로 되돌린다.
2. RUX-1 responsive shell을 수정하고 viewport overflow assertion을 추가한다.
3. RUX-2 status surface를 추가한 뒤 기존 `showError()`, `showSuccess()`, heartbeat handling을 이 surface로 이동한다.
4. RUX-3/RUX-4 agent lifecycle rendering을 추가한다.
5. RUX-5/RUX-6/RUX-7 경고와 diagnostics 세부 overflow를 처리한다.
6. RUX-8 Playwright stress fixture를 CI/local checklist에 연결한다.
7. RUX-9/RUX-10 Compose/overlay는 별도 Android verification pass로 진행한다.

## 17. Verification Checklist

Console verification:

- `npm run console:smoke`
- `node scripts/build-console-assets.mjs --check`
- Responsive stress command after implementation
- Manual screenshot review for 390/900/1024/1280 widths

Android/sample verification:

- `./gradlew :app:assembleDebug`
- Small-width emulator pass for Home, Queue, Project, Review, Diagnostics
- Font scale 1.3 or higher pass for row overlap

Regression assertions:

- No horizontal document overflow at 390px.
- `needs_clarification` and `wont_fix` have distinct labels/classes/banners.
- `agentNote` appears during in-progress state.
- Long `agentSummary`, connection details, and staleness details do not widen the page.
- Copy Prompt partial mark failure displays a warning.

## 18. Open Decisions

- Whether the global status surface should live under the topbar or above the inspector. Recommendation: under topbar, full-width, because bridge/MCP errors affect the whole workspace.
- Whether `needs_clarification` should unlock user editing automatically. Recommendation: yes, but require a visible "Re-save / Copy updated prompt" action after edits.
- Whether `wont_fix` should be grouped with resolved in history counts. Recommendation: count as terminal/done, but keep a distinct label and summary.
- Whether sidekick overlay should support user-configurable placement. Recommendation: not in this pass; max-width and inset safety are enough.
