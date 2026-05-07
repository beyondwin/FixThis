# FixThis Feedback Console — Claude Design System 리디자인 브리프

> **대상 코드**: `fixthis-mcp/src/main/resources/console/{index.html,styles.css,app.js}`와 `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt`
> **문서 버전**: 2026-05-05
> **작성 기준**: Claude Design System (dark/light, amber #D4A843 primary, 4/8/12/16/24/32 spacing)

> **현재 상태:** 구현 전 디자인 비평/참고 문서다. 현재 shipped console 상태와
> 운영자 workflow의 source of truth는 `docs/design-feedback-console-ux.md`,
> `README.md`, `docs/mcp.md`다. 2026-05-06 Studio 구현은 dark 3-column
> workspace, mode-aware Inspector, live-preview 렌더 분리, evidence card 표시를
> 반영했지만, `Refresh | Add | Save | Copy | Send | New | Close`는 제품 계약에
> 따라 top bar의 짧은 session-level action으로 유지한다. 아래 분석에서 "단일
> Kotlin raw string" 또는 `FeedbackConsoleAssets.kt` 내 embedded HTML/CSS/JS를
> 언급하는 대목은 2026-05-06 resource split 전 상태를 설명하는 historical
> observation이다.

---

## 1. 분석 요약

현재 콘솔은 “기능적으로 동작하지만, 시각적/구조적으로는 아직 디버그 페이지” 상태다. 워크플로우의 핵심 전환(idle ↔ add-flow ↔ pending ↔ saved)이 **시각적으로 표현되지 않고**, 한 패널에 너무 많은 정보가 쌓여 있어 “지금 내가 무엇을 해야 하는가”가 즉답되지 않는다. 우선순위순으로 정리하면 다음과 같다.

| #   | 문제                                                                                                                                                                                                  | 워크플로우 영향                                                                                                                                                |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1  | **단일 `render()` 함수가 모든 영역을 통째로 재생성** (`renderSnapshot`, `renderPendingItems`, `renderSavedEvidenceGroups` 모두 호출, DOM 자식들을 매번 교체). 2초 폴링마다 우측 Draft까지 깜박임. | 사용자가 Draft를 검토하는 중에 시야가 흔들림 → 신뢰도 하락. evidence 이미지도 매 폴링마다 `<img>` 노드가 새로 생성되어 캐시가 있어도 깜빡임이 남음.            |
| P2  | **모드 전환의 시각적 부재** — idle 클릭=tap navigation, add-flow 클릭=selection. 동일한 preview 위에서 클릭의 의미가 정반대인데 표시가 약함(타이틀 텍스트 변경뿐).                              | 잘못된 모드에서 클릭해 의도치 않은 navigation이 발생하거나, 반대로 selection 의도였는데 tap이 나가는 사고. 특히 새 사용자에게 치명적.                          |
| P3  | **Right pane 5중 적재** — Selection summary + Comment + Pending list + Draft (evidence groups) + Error가 하나의 세로 스크롤에 쌓임. 어떤 단계인지에 따라 4/5는 dead UI.                              | Add 단계에서는 Comment에 집중해야 하는데 아래 Draft가 시야를 차지. Draft 검토 단계에서는 Selection/Comment가 dead 상태로 남음.                                |
| P4  | **Header의 계층 부재** — 7개 액션 버튼(Refresh/Add/Save/Copy/Send/New/Close)이 한 toolbar에 평등하게 나열. 상태에 따라 primary가 바뀌어야 하는데 항상 Add만 primary.                                | “지금 다음에 눌러야 할 버튼”이 시각적으로 안내되지 않음. 특히 frozen 상태에서 Save가 primary여야 하는데 그렇지 않음.                                          |
| P5  | **Save 버튼이 멀리 있음** — Pending list는 우측 pane에 있는데 Save는 헤더 최상단. Pending에 추가 후 “이제 어디로 가야 하지?” 동선이 끊김.                                                            | Add to Pending 버튼 옆에 Save가 없어서 마우스 이동이 길고, “Pending이 차 있는데 Save가 active인 줄 모름” 사건이 자주 발생.                                    |
| P6  | **Evidence groups의 발견성** — 저장된 결과(=세션의 알맹이)가 우측 pane 최하단 `<details>` collapsed 상태로 묻혀 있음.                                                                                | 사용자가 “지금까지 무엇을 잡아냈는지” 보려면 스크롤 + accordion 두 단계 필요. 세션 핵심 자산인데 가장 안 보이는 위치.                                          |
| P7  | **Navigation controls의 영구 노출** — Back, Swipe×4, Capture 체크박스가 idle 동안 항상 preview 위에 나열. 시각적 노이즈 + 우발 클릭 가능성.                                                          | preview에 집중해야 하는데 5개 버튼이 시야를 가져감. frozen 상태에서는 hidden되지만, idle에서 가장 자주 보는 화면이 가장 어수선.                                |

위 7개 중 **P1(렌더링 깜박임)** 과 **P2(모드 시각화)** 가 사용성의 근본 문제이며, 나머지는 “정보 구조의 분리” 한 번으로 동시에 해결되는 종속 문제다.

---

## 2. 정보 구조(IA) 재설계 제안

### 2.1 3-column이 적합한가?

**부분적으로 유지하되, 우측 pane을 “모드별 단일 작업영역”으로 재정의해야 한다.**

- 좌측(Sessions/Sent History)은 적절한 위치 — 글로벌 navigation으로서의 sidebar.
- 중앙(Preview)은 항상 hero로 유지.
- 우측은 현재 “queue-pane”이라는 이름 그대로 잡화통이 됨. 이를 **mode-aware workspace** 로 바꿔야 한다 (§5 참고).

추가로, **Sent History는 sidebar 하단보다 “세션 메타” 영역의 collapsible로 격하**시킨다. 매번 보지 않는 정보다.

### 2.2 항상 visible vs. contextual

| 정보                                            | 현재             | 권장                                                                  |
| ----------------------------------------------- | ---------------- | --------------------------------------------------------------------- |
| Session header (package, count, updated)        | 항상 visible     | 항상 visible (header)                                                 |
| Device picker / interval / status               | 항상 visible     | 항상 visible — 단 idle/frozen 모두 device-row로 분리 (§3)             |
| Preview                                         | 항상 visible     | 항상 visible (mode badge 추가)                                        |
| Navigation controls                             | idle만 visible   | idle만 visible, **icon-only + overflow** 로 compact (§4)              |
| Sessions list                                   | 항상 visible     | 항상 visible                                                          |
| Sent History                                    | 항상 visible     | **collapsible**, 세션 footer로 이동                                   |
| Selection summary                               | 항상 visible     | **add-flow에서만 visible** (그 외엔 dead UI)                          |
| Comment textarea                                | 항상 visible     | **add-flow에서만 visible**                                            |
| Pending items list                              | 항상 visible     | **add-flow에서만 visible** (Save와 같은 영역)                         |
| Draft (evidence groups)                         | 항상 visible (collapsed) | **idle 모드의 우측 hero**, add-flow 동안엔 좌측 sidebar 끝으로 stub  |
| Error                                           | bottom of right  | **toast / inline 상단 banner**                                        |

### 2.3 5개 상태에 맞는 UI 계층

각 상태는 “preview의 시각적 톤” + “right pane의 콘텐츠” + “primary action” 세 축으로 정의한다.

| 상태                  | Preview 톤                              | Right pane                                  | Primary action          |
| --------------------- | --------------------------------------- | ------------------------------------------- | ----------------------- |
| **idle**              | live (subtle pulsing dot)               | Draft / Evidence summary                    | Add                     |
| **polling**           | live (refresh shimmer on edge only)     | Draft (변동 없음)                           | Add                     |
| **add-flow / frozen** | frozen (amber border + “FROZEN” badge)  | Composer (Selection → Comment → Pending)    | Save (pending ≥ 1일 때) |
| **pending(unsaved)**  | frozen + 번호된 overlay                 | Composer with Pending list filled           | Save (highlighted)      |
| **saved / draft 검토** | live 복귀                              | Draft (evidence groups, hero)               | Send (또는 Copy)        |

> 핵심 원칙: **각 상태에서 다음 액션이 단 하나로 명확해야 한다.**

---

## 3. Header / Toolbar 재설계

### 3.1 두 그룹의 명확한 분리

현재 헤더는 3-column grid이지만, 그룹의 “종류”가 시각적으로 구분되지 않는다. 두 줄 헤더로 분리하자.

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ [FixThis Feedback Console]   sessionMeta · package · 3 items · updated 14:22 │
│  ─── divider ───                                                              │
│ [Device ▾] [Interval: 2s ▾] [↻ Devices] [Disconnect]   ●Selected emul-5554    │
│                                                            [New] [Close]       │
└───────────────────────────────────────────────────────────────────────────────┘
                                                  (session actions은 우측 pane으로)
```

- **Row 1 = identity + session meta** (h1 + meta).
- **Row 2 = device control bar** (좌) + **session lifecycle** (우, New/Close만 — 자주 안 씀).
- **Add / Save / Copy / Send 는 헤더에서 빠진다.** 이들은 “현재 작업 컨텍스트”에 속하므로 우측 pane의 sticky footer로 이동 (§5).

### 3.2 상태별 primary 변화

`button.primary` 가 항상 Add에 고정된 현재 구조 대신, 상태에 따라 클래스가 이동:

| 상태                  | primary       | secondary                          | tertiary           |
| --------------------- | ------------- | ---------------------------------- | ------------------ |
| idle                  | **Add**       | Refresh, Send                      | Copy, New, Close   |
| add-flow + 0 pending  | (none)        | Add to Pending(disabled), Cancel   | -                  |
| add-flow + ≥1 pending | **Save**      | Add to Pending                     | Cancel             |
| saved with draft      | **Send**      | Copy                               | New, Close         |

JS 변경: `updateComposerState()` 가 disabled만 토글하지 말고 `.classList.toggle('primary', ...)` 로 강조도 함께 이동.

### 3.3 7개 → 시각 계층

| Tier        | 버튼                                  | 위치                                       | 스타일                                           |
| ----------- | ------------------------------------- | ------------------------------------------ | ------------------------------------------------ |
| Primary     | Add / Save / Send (mode별)            | Right pane sticky footer                   | filled amber `#D4A843`, text on amber dark       |
| Secondary   | Refresh, Copy                         | Right pane footer 옆 또는 preview header   | outlined, neutral border                         |
| Tertiary    | New, Close                            | Header row 2 우측, **icon + label** 작게   | ghost (no border, 호버시 background)             |
| Contextual  | Cancel, Clear Selection, Clear Draft  | 해당 패널 안                               | text button 또는 ghost-danger                    |

---

## 4. 중심(Preview) 영역 재설계

### 4.1 Live vs. Frozen 시각 구분

현재는 `<h2>` 텍스트가 “Live Preview” ↔ “Frozen Feedback Snapshot”으로 바뀌는 게 전부. 다음을 추가한다.

- **Live**: preview frame `border: 1px solid token(border-subtle)`. 우상단에 pulsing green dot + “LIVE · 2s” pill.
- **Frozen**: preview frame `border: 2px solid #D4A843` (amber, primary). 좌상단 “FROZEN · selecting” badge (amber filled). 배경 살짝 amber tint (`background: rgba(212, 168, 67, 0.04)`).
- **Polling 중**: 프레임 외곽에만 1.2초 shimmer animation. 이미지 자체는 절대 swap하지 않음 (§6의 요구).

이 시각 차이는 “지금 클릭하면 무엇이 일어날지”를 0.5초 안에 알려준다.

### 4.2 Navigation controls 정리

현재: 5개 버튼 + checkbox가 inline. 권장:

- **icon-only 버튼**: Back(←), Swipe Up/Down/Left/Right (↑↓←→). 각 32×32, ghost style.
- **그룹화**: `[←]   [↑] [↓] [←] [→]   [⚙]` — 마지막 ⚙ overflow에 “Capture after navigation” 토글 + 추후 추가 옵션.
- **위치**: preview 좌상단(badge 자리 다음) 또는 우상단. snapshot-header를 **floating control bar** 로 만들어 preview 위에 absolute 띄우면 시각적 분리가 강해진다.
- **frozen 상태에서는 dim/hide** (현재 `hidden = true`와 동일).

### 4.3 Frozen state indicator (구체)

```css
.preview-frame[data-mode="frozen"] {
  border: 2px solid #D4A843;
  border-radius: 12px;
  background: rgba(212, 168, 67, 0.04);
  box-shadow: 0 0 0 4px rgba(212, 168, 67, 0.08);
}
.mode-badge[data-mode="frozen"] {
  background: #D4A843;
  color: #1A1A1A;
  font-weight: 600;
  letter-spacing: 0.02em;
  padding: 4px 8px;
  border-radius: 6px;
}
```

추가로, 우측 pane composer 자체도 `border-left: 2px solid #D4A843` 로 같은 “frozen 영역”임을 시각적으로 묶어준다(이른바 *visual yoke*).

---

## 5. 오른쪽 패널(Right Pane) 재설계

### 5.1 핵심: **모드별 다른 콘텐츠**, 같은 슬롯

탭(tab)으로 강제 전환하는 대신, **상태 기반 conditional render** 가 본 도구에 더 자연스럽다 (사용자가 탭을 옮길 일 없이 시스템 상태가 패널을 정해주므로).

| 모드               | Right pane 구성                                                                                    |
| ------------------ | -------------------------------------------------------------------------------------------------- |
| **idle**           | **Draft (Evidence)** hero — group들이 큰 카드로 펼쳐져 있음. 우상단 “Send / Copy / Clear Draft”.    |
| **add-flow**       | **Composer**: Selection box → Comment textarea → Pending list → sticky footer (Save / Cancel).      |
| **empty session**  | empty state 일러스트 + “Press Add to capture your first feedback”.                                  |

Selection / Comment / Pending 은 add-flow에서만 mount, idle에선 unmount. 결과:

- idle 시 Composer 영역에 dead UI 없음.
- add-flow 시 Draft가 “좌측 sidebar 하단의 작은 stub”으로 축소되어 “이미 저장된 N개 그룹” 만 표시.

### 5.2 Draft / Evidence groups를 hero로 격상

```
┌─────────────── Right pane (idle 모드) ───────────────┐
│  Draft                              [Send] [Copy] ⋯  │
│  ────────────────────────────────────────────────── │
│  Screen: HomeScreen          3 items · screenshot ✓ │
│  ┌───────────────────────────────────────────────┐ │
│  │ [thumbnail with #1 #2 #3 overlays]            │ │
│  │ #1 Login button copy too small               │ │
│  │ #2 Custom area 240×80 — spacing too tight    │ │
│  │ #3 Avatar misaligned · source hint available │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│  Screen: SettingsScreen       1 item · screenshot ✓ │
│  ┌───────────────────────────────────────────────┐ │
│  │ ...                                            │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│  [Clear Draft]                                      │
└─────────────────────────────────────────────────────┘
```

기존 `<details>` 는 collapsed 기본을 expanded 기본으로, 그리고 thumbnail을 줄이지 말고 **카드의 hero 이미지**로 키운다. 이게 “세션의 알맹이”다.

### 5.3 Error 처리

`<p id="error">` 를 우측 pane 하단에서 빼고:

- **Inline error**: 사용자 액션 직후의 에러는 해당 버튼/필드 바로 아래 `aria-live="polite"` 인라인.
- **Toast**: 폴링/네트워크 등 백그라운드 에러는 우상단 toast (4s auto-dismiss, dismissable).
- **Persistent banner**: device 연결 실패처럼 작업이 막히는 에러는 헤더 row 2 아래 dismissable banner.

`error.textContent = '...'` 하나로 다 처리되던 걸 세 채널로 나눈다.

---

## 6. 깜박임 방지를 위한 렌더링 구조 제안

### 6.1 근본 원인 진단 (코드 기준)

```
function render() {
  ...
  renderSnapshot();              // ← snapshot 자식 노드 통째로 교체, <img>가 매번 새로 생성됨
  renderPendingItems();          // ← pendingItems 자식 노드 통째로 교체
  renderSavedEvidenceGroups();   // ← draftItems 자식 노드 통째로 교체 + 그 안의 <img>까지 새로 생성
  ...
  sentHistory의 자식도 통째로 교체
}
```

`refreshPreview()` → `render()` 가 폴링마다 호출되므로 **변경 없는 영역까지 모두 DOM 노드가 교체**된다. 브라우저가 캐시된 이미지라도 `<img>` 가 새로 생성되면 1프레임 placeholder를 그린다 → 깜박임.

### 6.2 분리 전략

각 영역을 “언제 갱신되는가”의 축으로 isolate:

| 영역                     | 갱신 트리거                                                                | 권장 갱신 방식                                          |
| ------------------------ | -------------------------------------------------------------------------- | ------------------------------------------------------- |
| Preview `<img>`          | 매 폴링 (preview ID 갱신)                                                  | **이미지 src만 교체**, 노드 재생성 금지                 |
| Selection overlay        | 매 폴링 + 사용자 입력                                                      | overlay만 자식 갱신 (preview img 노드는 건드리지 않음)   |
| Pending items            | Add to Pending / Delete / Focus 시에만                                     | 사용자 액션 콜백 내에서만 재렌더                        |
| Draft (evidence groups)  | Save / Clear Draft / Send / 세션 전환 시에만                               | session API 응답 시에만 재렌더                          |
| Sent history             | Send / 세션 전환 시에만                                                    | 위와 동일                                               |
| Sessions sidebar         | session 전환 시에만                                                        | refreshSessions() 분리 호출                             |

### 6.3 구체 수정안

**최소 침습 (P0, CSS+JS 30분)**:

1. `refreshPreview()` 에서 `render()` 호출하지 않음. 대신 `updatePreviewImage()` 만 호출:
   - 기존 `<img id="snapshotImage">` 를 찾아 `previewId` 가 동일하면 noop.
   - 새 `previewId` 면 `img.src = previewScreenshotUrl(...)` 만 재할당. 노드는 유지.
   - 최초 mount 시에만 `renderSnapshot()` 실행.
2. `render()` 는 **세션 데이터 변경 시에만** 호출되는 함수로 의미를 좁힌다. 폴링 경로에선 호출 금지.
3. `renderSavedEvidenceGroups()` 안의 `<img>` 도 `data-screen-id` 가 같으면 src 재할당 skip.

**구조적 (P1, 1–2일)**:

- `state` 를 작은 reactive store로: `sessionStore`, `previewStore`, `composerStore`. 각 store에 구독자 등록 → store가 변할 때만 해당 DOM 조각 갱신. 라이브러리 없이 100줄 안에 구현 가능.
- DOM 조각마다 `data-key` 부여, diff 함수로 add/update/remove만 수행 (mini-keyed-list). 이러면 Pending list나 Draft list가 “정렬 유지 + 깜박임 0”.

**이상 (P2)**:

- Preview 이미지를 `<img>` 대신 `<canvas>` 또는 `ImageBitmap` 으로 그리고, 새 frame은 “off-screen buffer → drawImage로 swap” 처리. 그러면 0프레임 swap. (도구 성격상 과한 투자일 수 있음 — 대부분 6.3-1만으로 충분하리라 추정.)

---

## 7. Claude Design System 적용 가이드

### 7.1 색상 토큰

| 역할                      | Light                             | Dark                             | 용도                                    |
| ------------------------- | --------------------------------- | -------------------------------- | --------------------------------------- |
| `bg-canvas`               | `#F7F6F3` (warm off-white)        | `#1A1A1A`                        | body                                    |
| `bg-surface`              | `#FFFFFF`                         | `#222222`                        | section, card                           |
| `bg-surface-raised`       | `#FFFFFF` + shadow                | `#2A2A2A`                        | hover/active card                       |
| `border-subtle`           | `#E6E2DA`                         | `#333333`                        | card borders                            |
| `border-strong`           | `#CFC8BB`                         | `#444444`                        | toolbar dividers                        |
| `text-primary`            | `#1A1A1A`                         | `#F2F0EA`                        | h1, body                                |
| `text-secondary`          | `#5C5751`                         | `#B0ABA2`                        | meta, captions                          |
| `text-tertiary`           | `#8B857B`                         | `#7C766C`                        | placeholder                             |
| `accent-primary`          | `#D4A843`                         | `#D4A843`                        | primary CTA, frozen indicator           |
| `accent-primary-hover`    | `#C29836`                         | `#E0B956`                        |                                         |
| `accent-primary-fg`       | `#1A1A1A`                         | `#1A1A1A`                        | text on amber                           |
| `accent-soft-bg`          | `rgba(212,168,67,0.08)`           | `rgba(212,168,67,0.12)`          | frozen tint, evidence highlight         |
| `live-indicator`          | `#3F8F5C`                         | `#5BB07F`                        | live dot (현재 `#116a5c` 대체)          |
| `error`                   | `#B33A3A`                         | `#E26A6A`                        |                                         |
| `error-bg`                | `#FBEAEA`                         | `rgba(226,106,106,0.12)`         | banner                                  |

> Dark mode는 사용자가 자주 도구를 켜놓는 도구라서 default를 dark로 두는 것을 검토할 가치가 있다. 최소한 `prefers-color-scheme` 자동 대응은 기본 제공.

### 7.2 타이포

| 토큰         | 크기/lh    | weight | 사용처                                          |
| ------------ | ---------- | ------ | ----------------------------------------------- |
| `display`    | 20 / 28    | 700    | h1 (FixThis Feedback Console)                |
| `heading-md` | 14 / 20    | 600    | h2 (현재 700에서 한 단계 낮춤)                  |
| `heading-sm` | 12 / 16    | 600    | section sub-heading, badge label                |
| `body-md`    | 14 / 20    | 400    | textarea, row text                              |
| `body-sm`    | 13 / 18    | 400    | meta, captions                                  |
| `mono-sm`    | 12 / 16    | 500    | bounds, IDs (`'JetBrains Mono', monospace`)     |
| `caption`    | 11 / 14    | 500    | uppercase pill (`LIVE`, `FROZEN`)               |

기본 글꼴은 Inter 그대로 두되, mono를 추가해 bounds(`132,418 - 392,478`) 같은 좌표 표시는 mono로. 가독성 + “기술 도구의 결” 동시 확보.

### 7.3 Spacing

4 / 8 / 12 / 16 / 24 / 32 px 토큰화. 현재 코드의 `padding: 14px 18px`, `gap: 14px` 같은 비표준 값은 16/12로 정렬.

| 영역              | 권장 spacing                             |
| ----------------- | ---------------------------------------- |
| header padding    | `16px 24px`                              |
| section padding   | `24px`                                   |
| 카드 간 gap       | `12px`                                   |
| 카드 내부 padding | `16px`                                   |
| 버튼 padding      | `0 12px`, height 36px (현재 34px)        |
| 토스트 / banner   | `12px 16px`, gap 8px                     |

### 7.4 Radius / Elevation

- 카드/입력: `10px` (현재 6/8 혼재 → 통일).
- 버튼: `8px`.
- 토스트/모달: `12px`.
- elevation은 light에서만 `0 1px 2px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.04)` 정도. dark에서는 border-strong로 대체.

---

## 8. 와이어프레임 (텍스트)

### 8.1 IDLE — live preview, navigation 가능

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ FixThis Feedback Console                       sample.app · 0 items · updated 14:22    │
│ ─────────────────────────────────────────────────────────────────────────────────────────│
│ [Pixel 7 ▾]  [2s ▾]  [↻]  [Disconnect]   ● Selected emul-5554              [+ New] [✕]  │
└───────────────────────────────────────────────────────────────────────────────────────────┘
┌───────────────┬─────────────────────────────────────────────┬───────────────────────────┐
│ Sessions      │  ● LIVE · 2s          [←] [↑] [↓] [←] [→] [⚙]│ Draft           [Send]   │
│ ───────────── │ ┌─────────────────────────────────────────┐ │ ───────────────  [Copy]  │
│ ▸ Active      │ │                                         │ │                          │
│   3 items     │ │       (live screenshot, tap to          │ │ HomeScreen · 3 items     │
│   updated 14:22│ │           navigate)                     │ │ ┌──────────────────────┐ │
│               │ │                                         │ │ │ [thumbnail #1#2#3]   │ │
│ ▸ 2026-05-04  │ │                                         │ │ │ #1 Login button copy │ │
│   sent · 2    │ │                                         │ │ │ #2 Custom area 240×80│ │
│               │ │                                         │ │ │ #3 Avatar misaligned │ │
│ Sent History ▾│ └─────────────────────────────────────────┘ │ └──────────────────────┘ │
│               │                                             │                          │
│               │                                             │ SettingsScreen · 1 item  │
│               │                                             │ ┌──────────────────────┐ │
│               │                                             │ │ ...                  │ │
│               │                                             │ └──────────────────────┘ │
│               │                                             │                          │
│               │                                             │ ─── sticky footer ───    │
│               │                                             │ [+ Add Feedback] ←primary│
│               │                                             │ [Clear Draft]            │
└───────────────┴─────────────────────────────────────────────┴───────────────────────────┘
```

### 8.2 ADD / FROZEN — selection + comment 입력

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ FixThis Feedback Console                       sample.app · 3 items · updated 14:22    │
│ ─── (device row dimmed: device controls inactive while frozen) ──                          │
│ [Pixel 7 ▾]  [2s ▾]  [↻]  [Disconnect]   ● Frozen at preview_8c2f                         │
└───────────────────────────────────────────────────────────────────────────────────────────┘
┌───────────────┬─────────────────────────────────────────────┬───────────────────────────┐
│ Sessions      │  ▣ FROZEN · selecting   (nav controls hidden)│ Composer                 │
│               │ ┌══════════════ amber border ══════════════┐│ ─────────────────────────│
│ Draft stub    │ ║                                          ║│ Selection                │
│ 4 saved groups│ ║   (frozen screenshot, click=selection,   ║│ ┌──────────────────────┐ │
│  →            │ ║    drag=area)                            ║│ │ Component: Login btn │ │
│               │ ║                                          ║│ │ 132,418 — 392,478    │ │
│               │ ║   [#1] [#2] (already pending overlays)  ║│ └──────────────────────┘ │
│               │ ║                                          ║│ [Clear Selection]        │
│               │ ╚══════════════════════════════════════════╝│                          │
│               │                                             │ Comment                  │
│               │                                             │ ┌──────────────────────┐ │
│               │                                             │ │ Make this button     │ │
│               │                                             │ │ taller and use the   │ │
│               │                                             │ │ amber primary color. │ │
│               │                                             │ └──────────────────────┘ │
│               │                                             │ [Add to Pending]         │
│               │                                             │                          │
│               │                                             │ Pending (2)              │
│               │                                             │ ┌──────────────────────┐ │
│               │                                             │ │ #1 Login button copy │ │
│               │                                             │ │   [Focus] [Delete]   │ │
│               │                                             │ ├──────────────────────┤ │
│               │                                             │ │ #2 Custom area 240×80│ │
│               │                                             │ │   [Focus] [Delete]   │ │
│               │                                             │ └──────────────────────┘ │
│               │                                             │ ─── sticky footer ───    │
│               │                                             │ [✓ Save (2)]  ← primary  │
│               │                                             │ [Cancel]                 │
└───────────────┴─────────────────────────────────────────────┴───────────────────────────┘
```

### 8.3 DRAFT 검토 — evidence groups 확인 + Send

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│ FixThis Feedback Console                       sample.app · 4 items · updated 14:25    │
│ [Pixel 7 ▾]  [2s ▾]  [↻]  [Disconnect]   ● LIVE                            [+ New] [✕]   │
└───────────────────────────────────────────────────────────────────────────────────────────┘
┌───────────────┬─────────────────────────────────────────────┬───────────────────────────┐
│ Sessions      │  ● LIVE · 2s          (nav controls compact)│ Draft (4)    [Send] ←pri │
│ ▸ Active      │ ┌─────────────────────────────────────────┐ │              [Copy]      │
│ Sent History ▾│ │ (live preview)                          │ │ ───────────────────────  │
│ ▸ Batch #2    │ │                                         │ │ HomeScreen · 3 items ▼   │
│   2 items     │ │                                         │ │ ┌────────────────────┐  │
│   sent 14:22  │ │                                         │ │ │ [hero thumbnail    │  │
│               │ │                                         │ │ │  with #1#2#3 over] │  │
│               │ │                                         │ │ ├────────────────────┤  │
│               │ │                                         │ │ │ #1 Login button…   │  │
│               │ └─────────────────────────────────────────┘ │ │ #2 Custom area…    │  │
│               │                                             │ │ #3 Avatar…         │  │
│               │                                             │ └────────────────────┘  │
│               │                                             │                          │
│               │                                             │ SettingsScreen · 1 ▼     │
│               │                                             │ ┌────────────────────┐  │
│               │                                             │ │ #4 …               │  │
│               │                                             │ └────────────────────┘  │
│               │                                             │                          │
│               │                                             │ ─── sticky footer ───    │
│               │                                             │ [+ Add Feedback]         │
│               │                                             │ [Clear Draft]            │
└───────────────┴─────────────────────────────────────────────┴───────────────────────────┘
```

---

## 9. 구현 우선순위 로드맵

### P0 — 즉시 (CSS / JS 표면 변경, 1일 내)

각각이 독립적으로 가치를 주며 risk가 낮은 것들.

1. **깜박임 P1 차단**
   - `refreshPreview()` 가 `render()` 가 아니라 신규 `updatePreviewImage()` 만 호출.
   - 동일 `previewId` 면 src 재할당 skip.
   - `renderSavedEvidenceGroups()` 도 idempotent하게 (data-screen-id 캐시 비교).
   - 영향 큼, 변경 작음.
2. **Frozen 시각화 강화**
   - preview frame에 amber border + “FROZEN” pill.
   - `<h2 id="snapshotTitle">` 텍스트 토글 대신 `data-mode` attribute 토글 + CSS.
3. **Save 버튼 이중 노출**
   - 기존 헤더 Save 유지하되, **Pending list 바로 아래에도 Save 버튼 추가** (sticky footer 형태). 동일 액션. 우측 pane에서 동선이 끊기지 않도록.
4. **Error 위치 격상**
   - `<p id="error">` 를 우측 하단에서 우상단 toast로 옮김. 한 함수 변경.
5. **Color/spacing 토큰 적용**
   - `:root` 에 CSS variables 정의, `#116a5c` → `#D4A843` 외 핵심 색 4–5개 토큰화.
   - 라이트/다크 둘 다는 P1으로 미루더라도, 토큰 정의는 지금.

### P1 — 구조 변경 (3–5일)

6. **Right pane 모드 분기**
   - `.queue-pane` 에 `data-mode="idle|composer"` 두고, idle 모드에서는 Composer를 mount 해제.
   - Draft가 idle hero가 되고 evidence thumbnail 확대.
7. **Header 두 줄 분리**
   - row 1: identity + meta. row 2: device controls + lifecycle. 헤더에서 Add/Save/Copy/Send 제거(우측 pane 책임).
8. **Navigation controls compact**
   - icon-only 버튼화, “Capture after navigation”을 ⚙ overflow로.
9. **Sent History를 sidebar에서 collapsible로 격하**
   - default collapsed.
10. **Reactive store 도입 (가벼운)**
    - `sessionStore`, `previewStore`, `composerStore`. 각 영역이 자기 store에만 구독.
    - 이후 모든 `render()` 호출이 “해당 영역만 갱신”으로 자연스럽게 정리됨.

### P2 — 이상적인 완성 (1–2주, 또는 별도 스프린트)

11. **Dark mode default + 토글**
    - `prefers-color-scheme` 감지 + 헤더 ⚙ 메뉴에 수동 override.
12. **Keyed list diff**
    - Pending / Draft / Sessions / Sent History 모두 keyed update. 정렬 유지, 깜박임 0.
13. **Preview canvas swap**
    - `<img>` → `<canvas>` + offscreen buffer. polling shimmer가 0프레임.
14. **Empty / loading / error 일러스트레이션**
    - “No active session”, “No devices connected”, “No draft yet” — 각 상태에 illustrated empty state.
15. **Accessibility 패스**
    - 모든 상호작용에 `aria-live`/`aria-pressed`/keyboard shortcut(예: `A` = Add, `S` = Save, `Esc` = Cancel).
16. **Settings pane**
    - preview interval, capture-after-navigation default, theme — 한 곳에 모음. ⚙ overflow에서 진입.

---

## 부록 A. 코드에서 즉시 바꿀 수 있는 “핫스팟” 목록

| 위치 (line)                                         | 무엇을                                                                                                                                                          | 왜                                                            |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `render()` (~964–995)                               | preview 갱신 경로에서 호출 금지. `updatePreviewImage()` 신설.                                                                                                  | 깜박임 (P1) 즉시 해소.                                        |
| `renderSnapshot()` (~945–962)                       | 동일 `previewId` 면 `<img>.src` 재할당 skip. overlay만 갱신.                                                                                                    | DOM 노드 swap 차단.                                           |
| `renderSavedEvidenceGroups()` (~911–943)            | `<img>` 의 `src` 비교 후 변동 시에만 교체. `<details>` 의 open 상태를 사용자 입력 외에는 보존.                                                                  | Draft가 폴링마다 닫히는 것 방지.                              |
| `snapshot-header` (~111–117)                        | `data-mode` attribute 추가, CSS로 frozen 시 amber border.                                                                                                        | Frozen 시각화.                                                |
| `.queue-pane` (~247)                                | `data-mode` 추가, idle/composer 분기 CSS.                                                                                                                       | Right pane 모드별 콘텐츠.                                     |
| header row (~196–222)                               | 두 줄로 분리, Add/Save/Copy/Send 제거 후 우측 pane sticky footer에 재배치.                                                                                      | 동선/계층 정리.                                               |
| `<p id="error">` (~266)                             | 제거하고 toast 컨테이너로 이동.                                                                                                                                 | 에러 발견성.                                                  |
| `:root` (~12–17)                                    | `--bg-canvas`, `--bg-surface`, `--text-primary`, `--accent-primary`, … 토큰 변수화.                                                                              | 이후 다크 모드/시스템 통일 기반.                              |
| `button.primary` (~57)                              | `background: var(--accent-primary)` 로 변경. `#116a5c` → `#D4A843`.                                                                                              | DS 정합.                                                      |
| `selectionSummary` (~249) + `<textarea id=comment>` (~255) | add-flow에서만 mount. JS 상에서 `addItemsFlow ? mount() : unmount()` 패턴.                                                                                | dead UI 제거.                                                 |

---

## 부록 B. 디자인 결정 메모

- **다크 우선?** 본 도구는 “Compose 디버그 옆에 띄워두는 보조 콘솔” 성격이라, 사용자가 IDE 다크 테마에 익숙하다는 가정이 합리적. 다만 첫 P1 단계는 라이트 유지하면서 토큰만 정리, P2에서 다크 default 전환을 권장.
- **Tab vs. State-aware pane?** Composer ↔ Draft를 탭으로 전환하는 안도 검토했으나, **사용자가 모드를 직접 전환할 일이 거의 없다** (Add 버튼 = 자동 frozen, Save = 자동 idle 복귀). 따라서 시스템이 결정하는 state-aware가 옳다. 탭은 잉여 클릭만 만든다.
- **Sent History의 위치.** 사용자는 거의 보지 않지만 “지난 작업 회고” 시점에는 필요. 따라서 제거 X, 격하 O. sidebar 하단 collapsible이 정답.
- **Frozen 색상으로 amber 적합성.** Claude DS의 amber는 “주의 + 작업 진행 중”의 톤을 동시에 가지므로 frozen state 표현에 잘 맞는다. 별도의 amber-warning 토큰을 따로 만들 필요 없이 primary를 그대로 사용해도 의미가 충돌하지 않음 (frozen 상태 = 사용자 작업 진행 중 = 시스템의 ‘primary 모드’).
- **Spacing 변경의 risk.** 14px → 16px 같은 변화는 미미해 보이지만 device-strip이 wrap되는 임계가 바뀌므로 `@media (max-width: 900px)` 분기와 함께 회귀 확인 필요.

---

## 마무리

이 브리프의 가장 중요한 한 줄: **“현재 코드의 단일 `render()` 가 너무 많은 책임을 진다.”** 그 함수를 분해하는 것 자체가 P1(깜박임), P3(우측 pane 과부하), P6(Evidence 발견성)을 동시에 해결한다. 디자인 토큰 적용은 그 위에 입히는 “옷”이고, 정보 구조 재설계는 그 옷이 잘 맞도록 몸을 다듬는 작업이다.

P0만으로도 사용자가 체감하는 “안정감”은 크게 달라진다. P1까지 가면 도구의 인상이 “디버그 페이지”에서 “Claude 계열 워크벤치”로 바뀐다. P2는 시간이 허락할 때 점진적으로.
