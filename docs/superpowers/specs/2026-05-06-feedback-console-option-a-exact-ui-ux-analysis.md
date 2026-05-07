# Feedback Console Option A Exact UI/UX Analysis

Date: 2026-05-06

Source prototype:

```text
/Users/kws/Downloads/FixThis Console _standalone_.html
```

Related FixThis docs:

```text
docs/superpowers/specs/2026-05-06-feedback-console-option-a-parity-redesign-design.md
docs/superpowers/specs/2026-05-06-feedback-console-option-a-parity-redesign-implementation-details.md
```

## 목적

이 문서는 standalone HTML의 `Option A - Studio` 동작과 시각 규칙을 현재
FixThis 콘솔에 그대로 옮기기 위한 정밀 기준이다. 구현자는 기존
pending-item 중심 UI를 기준으로 보정하지 말고, 아래의 Option A 구조와
상태 전이를 먼저 맞춘 뒤 FixThis 데이터/API를 연결해야 한다.

요청 범위는 다음 섹션이다.

- `History` 섹션
- `Select` / `Annotate`
- `2 open` / `1 resolved` 카운트
- 확대/축소 컨트롤 `− 100% +`
- preview 클릭 선택 / 드래그 선택
- `Annotation` / `Annotations` 섹션
- 색상, 치수, hover/active/focus 상태

## 소스 기준

standalone HTML은 번들러 매니페스트 안에 압축된 React/CSS를 포함한다.
Option A의 실제 구현은 다음 리소스에서 확인된다.

```text
49c2e072-a29e-4a32-a47a-da4e7666a83c  StudioOption
8086d170-b58f-4117-95f8-6ad7d56e9ab5  MobilePreview / PhoneFrame
template style block                       shared CSS + Option A CSS
```

Option A 컴포넌트의 핵심 상태는 다음과 같다.

```javascript
snapshots
activeSnapId
annotations
draftTitle
selectedId
draggingRect
tool // "select" | "annotate"
dragStart
dragMoved
```

현재 프로젝트에서 이름은 달라도, UI의 visible state는 이 모델과 동일해야
한다. 특히 사용자는 `pendingFeedbackItems`를 보는 것이 아니라 `History`,
preview pin, `Annotations` list/detail을 본다.

## 전체 레이아웃

Option A Studio는 어두운 3패널 작업 공간이다.

```text
.studio
  .studio-topbar   height 56px (grid-template-rows: 56px 1fr 로 강제, topbar 자체에 height 선언 없음)
  .studio-body     columns 280px / 1fr / 340px
    .studio-history
    .studio-canvas
    .studio-inspector
```

기본 레이아웃 값:

| 항목 | 값 |
| --- | --- |
| shell | `width: 100%; height: 100%; overflow: hidden` |
| topbar | `grid-template-columns: 220px 1fr auto`, `padding: 0 16px`, `gap: 16px` |
| body | `grid-template-columns: 280px 1fr 340px`, `min-height: 0` |
| compact body | `240px 1fr 300px` |
| spacious body | `320px 1fr 380px` |
| left/right panel | `background: #131418`, internal flex column |
| center canvas | `background: #0d0e10`, flex column |

패널 경계 border:

```css
.studio-history   { border-right: 1px solid #2a2d35; }  /* --line */
.studio-inspector { border-left:  1px solid #2a2d35; }  /* --line */
```

canvas는 별도 border 없이 `background: #0d0e10`으로 시각 구분한다.

스크롤은 페이지 전체가 아니라 각 패널 내부에서만 발생해야 한다.
커스텀 스크롤바 CSS는 prototype에 정의되어 있지 않다. Compose의 기본 스크롤바를 그대로 사용한다.

## 색상 토큰

Option A Studio의 모든 색상은 아래 토큰을 우선 사용한다.

| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--bg-0` | `#0d0e10` | 전체 배경, canvas 배경, primary text on accent |
| `--bg-1` | `#131418` | topbar, history, inspector, toolbar |
| `--bg-2` | `#1a1c21` | hover row/card, segmented group, inputs |
| `--bg-3` | `#21242b` | active tool, count pill, zoom hover |
| `--line` | `#2a2d35` | 주요 border (panel 경계, toolbar bottom, card active border) |
| `--line-soft` | `#1f2228` | panel-head bottom border, detail-actions top border |
| `--txt-0` | `#e8e9eb` | primary text |
| `--txt-1` | `#b6b8be` | secondary text |
| `--txt-2` | `#7d8089` | muted text, labels, bc-sep |
| `--accent` | `#b8d36a` | active tool text, primary button, brand-mark bg, hint |
| `--accent-deep` | `#8eaa49` | reserved accent depth |
| `--warn` | `#e6b45a` | warning/medium severity family |
| `--danger` | `#f26d6d` | danger/high severity family |

`--line`과 `--line-soft`는 사용 위치가 다르다. 혼용 금지:

| 위치 | 토큰 |
| --- | --- |
| `.panel-head` bottom border | `--line-soft` (`#1f2228`) |
| `.canvas-toolbar` bottom border | `--line` (`#2a2d35`) |
| `.studio-history` right border | `--line` (`#2a2d35`) |
| `.studio-inspector` left border | `--line` (`#2a2d35`) |
| `.detail-actions` top border | `--line-soft` (`#1f2228`) |
| History card active border | `--line` (`#2a2d35`) |

상태/심각도 색상:

| 의미 | 값 | 사용처 |
| --- | --- | --- |
| severity high | `#F26D6D` | pin, row number, history strip, active severity segment (inline) |
| severity med | `#E6B45A` | pin, row number, history strip, active severity segment (inline) |
| severity low | `#5AB1E6` | pin, row number, history strip, active severity segment (inline) |
| open dot/status | `#f2c94c` | open dot, open pill |
| resolved dot/status | `#6fcf97` | resolved dot, resolved pill |
| in-progress status | `#5bb1e6` | in-progress pill |
| widget hover outline | `rgba(107, 78, 255, 0.7)` | annotate mode widget hover |
| primary hover | `#c8e07c` | Save snapshot hover |
| muted placeholder | `#999` | `No comment` italic |

상태 pill 배경:

```css
.st-open { background: rgba(242,201,76,0.15); color: #f2c94c; }
.st-resolved { background: rgba(111,207,151,0.15); color: #6fcf97; }
.st-in-progress { background: rgba(91,177,230,0.15); color: #5bb1e6; }
```

## Typography

- 기본 폰트는 `Inter`.
- prototype은 `Fraunces`도 로드하지만 Option A shell 자체는 대부분
  `Inter`를 사용한다.
- shell 기본 크기: `13px`.
- density-compact: `12px` (`.studio.density-compact { font-size: 12px; }`).
- density-spacious: `14px` (`.studio.density-spacious { font-size: 14px; }`).
- FixThis에서 density toggle을 지원하지 않는다면 단일 기본값 `13px`으로
  고정한다.
- letter spacing은 panel title, field label, status pill에서만 강하게
  사용한다.

주요 텍스트 스타일:

| 클래스/역할 | 값 |
| --- | --- |
| `.panel-title` | `11px`, uppercase, `letter-spacing: 0.14em`, `font-weight: 600`, `#7d8089` |
| `.panel-count` | `11px`, `font-weight: 600`, tabular nums, bg `#21242b`, color `#b6b8be`, `padding: 2px 8px`, `border-radius: 999px` |
| `.hi-title` | `13px`, `font-weight: 500`, line-height `1.3` |
| `.ann-row-title` | `12px`, `font-weight: 600` |
| `.ann-row-comment` | `11px`, line-height `1.4`, 2-line clamp, `margin-top: 3px` |
| field label | `10px`, uppercase, `letter-spacing: 0.14em`, `font-weight: 600` |

## Topbar

### 구조

```text
.studio-topbar
  .studio-brand
    .brand-mark     (30×30 accent 박스, 아이콘 글리프)
    div
      .brand-name   "FixThis"
      .brand-sub    "Studio"
  .topbar-breadcrumb
    .bc-item        "Lumen"
    .bc-sep         "/"
    .bc-item        "Wallet iOS"
    .bc-sep         "/"
    .bc-input       [draftTitle 입력]
  .topbar-actions
    button.btn-ghost  "+ New session"   (strict parity 시)
    button.btn-primary
      .btn-icon     "⌘"
      span          "Save snapshot"
```

### Brand mark

`◐` 문자 그대로 표기하지 않는다. 실제 구조는 30×30 accent 배경 박스에
brand 글리프가 들어가는 형태다.

```css
.studio-brand { display: flex; align-items: center; gap: 10px; }
.brand-mark {
  width: 30px; height: 30px;
  background: #b8d36a;
  color: #0d0e10;
  border-radius: 8px;
  display: grid; place-items: center;
  font-size: 16px; font-weight: 700;
}
.brand-name { font-weight: 600; font-size: 13px; letter-spacing: -0.01em; }
.brand-sub  { font-size: 10px; color: #7d8089;
              text-transform: uppercase; letter-spacing: 0.14em; }
```

### Breadcrumb / draftTitle 입력

```css
.topbar-breadcrumb { display: flex; align-items: center; gap: 8px; min-width: 0; }
.bc-item  { color: #b6b8be; font-size: 12px; white-space: nowrap; }
.bc-sep   { color: #7d8089; }
.bc-input {
  background: transparent;
  border: none; outline: none;
  color: #e8e9eb; font-size: 13px; font-weight: 500;
  padding: 4px 8px; border-radius: 6px;
  min-width: 200px;
  transition: background 120ms;
}
.bc-input:hover,
.bc-input:focus { background: #1a1c21; }
```

draftTitle 입력은 **기본 상태에서 border/배경이 없다(투명)**. hover 또는
focus 시에만 `#1a1c21` 배경이 생긴다. focus ring 색상 변화는 없다.

### Topbar height

`.studio-topbar`에 height 선언이 없다. `grid-template-rows: 56px 1fr`이
topbar row 높이를 56px로 강제한다. padding은 `0 16px`(좌우만, 상하 0).

### Button styles

현재 프로젝트의 기존 parity 문서는 `+ New session` 제거를 요구한다. 구현
결정은 다음 중 하나로 고정해야 한다.

- strict prototype parity: `+ New session`과 `⌘ Save snapshot` 둘 다 표시.
- current FixThis parity: `+ New session`은 제거하고 `Save snapshot`만
  유지하되, 버튼 스타일은 prototype과 동일하게 사용.

```css
.topbar-actions { display: flex; gap: 8px; }

.btn-ghost {
  padding: 7px 12px; border-radius: 7px;
  color: #b6b8be; font-size: 12px; font-weight: 500;
  border: 1px solid transparent;
  transition: all 120ms;
}
.btn-ghost:hover {
  background: #1a1c21; color: #e8e9eb;
  border-color: #2a2d35;
}

.btn-primary {
  padding: 7px 14px; border-radius: 7px;
  background: #b8d36a; color: #0d0e10;
  font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 6px;
  transition: all 120ms;
}
.btn-primary:hover:not(:disabled) {
  background: #c8e07c;
  transform: translateY(-1px);
}
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }
.btn-primary.mini { padding: 5px 10px; font-size: 11px; }

.btn-icon { font-size: 11px; opacity: 0.7; }
```

`Save snapshot` 버튼은 annotation이 0개이면 disabled다.
`⌘`는 `.btn-icon` span 안에 들어가는 별도 글리프이며 font-size 11px,
opacity 0.7이다. `⌘ Save snapshot` 전체를 단일 텍스트로 쓰면 비율이 틀어진다.
hover 시 `transform: translateY(-1px)` 마이크로 인터랙션이 있다.

## Panel Head

모든 패널(`studio-history`, `studio-inspector`)의 헤더는 동일한 `.panel-head`
구조를 사용한다.

```css
.panel-head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid #1f2228;  /* --line-soft */
}
```

panel-head의 bottom border는 `--line-soft`(`#1f2228`)이다.
canvas toolbar의 bottom border인 `--line`(`#2a2d35`)과 혼동하지 않는다.

## History Section

### 구조

왼쪽 패널은 `History`이며, snapshot 수를 count pill로 표시한다.

```text
.studio-history
  .panel-head
    .panel-title "History"
    .panel-count snapshots.length
  .history-list
    .history-item[.is-active]
      .hi-head
        .hi-title
        .hi-del ×
      .hi-meta
      .hi-stats
        .hi-pip N open
        .hi-pip N done
        .hi-pip N pts
      .hi-strip
        .hi-strip-cell per annotation
```

prototype의 History card는 `resolved` 대신 `done` 텍스트를 사용한다.
반면 canvas toolbar는 `resolved`를 사용한다. 현재 사용자가 요청한 문구는
`1 resolved`이므로, 제품 parity에서는 History도 `resolved`로 통일할 수
있다. 단, pixel/label parity를 정말 엄격히 맞출 때는 History card의 두
번째 pip는 `N done`이다.

### 기본 데이터 예시

초기 snapshot은 2개다.

```text
snap-1: Wallet home v3, You · May 5 · 14:22, annotations 3
  a1 med  open      Greeting hierarchy
  a2 high open      Balance card
  a3 low  resolved  Recent activity row

snap-2: Wallet home v2, You · May 3 · 09:10, annotations 1
  a4 med resolved   Quick actions grid
```

`.hi-meta` 날짜 포맷은 `"{author} · {Mon D} · {HH:mm}"` 형식으로 중간점(·)을
구분자로 통일한다. 콤마/중간점 혼용 금지.

초기 화면에서 `snap-1`이 active이므로 canvas toolbar는 `2 open`,
`1 resolved`를 보여야 한다.

### 스타일

| 요소 | 값 |
| --- | --- |
| `.history-list` | `flex: 1`, `overflow-y: auto`, `padding: 8px`, `gap: 4px` |
| `.history-item` | `padding: 12px`, `border-radius: 8px`, transparent border, `transition: background 120ms` |
| hover | `background: #1a1c21` |
| active | `background: #1a1c21`, `border-color: #2a2d35`, `box-shadow: inset 2px 0 0 #b8d36a` |
| `.hi-meta` | `font-size: 11px`, color `#7d8089`, `margin-top: 2px` |
| stats | `display: flex`, `flex-wrap: wrap`, `gap: 10px`, `margin-top: 8px` |
| `.hi-pip` | `display: inline-flex`, `align-items: center`, `font-size: 11px`, color `#b6b8be`, tabular nums (배경/border 없는 텍스트) |
| strip | `display: flex`, `gap: 2px`, `margin-top: 8px` |
| strip cell | `flex: 1`, `height: 4px`, `border-radius: 2px` |

Resolved annotation의 strip opacity는 `0.35`다.

`.hi-pip`은 별도 배경이나 border가 없는 인라인 텍스트 노드다. pip 앞에
색상 dot을 붙일지는 FixThis 결정 사항이며 prototype CSS에는 강제 없다.

히스토리가 0개일 때의 empty state는 prototype에 정의되어 있지 않다.
FixThis에서 `.empty` 컴포넌트 재사용 여부를 별도 결정한다.

### delete button

```css
.hi-del {
  width: 20px; height: 20px; border-radius: 4px;
  color: #7d8089; font-size: 16px; line-height: 1;
  opacity: 0; transition: all 120ms;
}
.history-item:hover .hi-del { opacity: 1; }
.hi-del:hover { background: #21242b; color: #f26d6d; }
```

hover 시 배경 `#21242b`, 글자 `#f26d6d`(danger)로 변한다.

### card-minimal

```css
.studio.card-minimal .hi-stats,
.studio.card-minimal .hi-strip { display: none; }
```

density 외에 `card-minimal` 변종이 존재한다. FixThis에서 사용하지 않더라도
인지해 둔다.

### 이벤트

History card click:

1. `activeSnapId`를 해당 snapshot id로 변경한다.
2. active snapshot의 `annotations`를 deep copy해서 현재 annotations로
   교체한다.
3. `draftTitle`을 snapshot title로 변경한다.
4. `selectedId`를 `null`로 바꿔 inspector를 list 상태로 되돌린다.

History delete click:

1. delete button은 `event.stopPropagation()`을 호출해야 한다.
2. 삭제 클릭이 card open click으로 전파되면 안 된다.
3. active snapshot 삭제 시 다음 snapshot을 active로 잡는다.
4. 남은 snapshot이 없으면 new-session 상태로 간다.

## Canvas Toolbar

### 구조

중앙 toolbar는 세 구역이다.

```text
left:   [Select] [Annotate]
center: tool status (flex: 1, justify-content: center)
right:  − 100% +
```

HTML 구조:

```text
.canvas-toolbar
  .tool-group
    button.tool Select
    button.tool Annotate
  .tool-status
    .ts-meta or .ts-hint
  .tool-zoom
    button.zb −
    span 100%
    button.zb +
```

`.canvas-toolbar`에 명시적 height 선언이 없다. 컨텐츠 + 상하 padding `10px`
으로 높이가 결정된다(대략 44px).

### Select / Annotate 스타일

| 요소 | 값 |
| --- | --- |
| `.canvas-toolbar` | `padding: 10px 16px`, `gap: 16px`, bg `#131418`, `border-bottom: 1px solid #2a2d35` |
| `.tool-group` | `display: flex`, `gap: 4px`, `padding: 3px`, bg `#1a1c21`, radius `8px` |
| `.tool` | `padding: 6px 12px`, radius `6px`, `gap: 6px`, `font-size: 12px`, `font-weight: 500`, `transition: all 120ms` |
| `.tool:hover` | text `#e8e9eb`, no size change |
| `.tool.is-active` | bg `#21242b`, color `#b8d36a`, shadow `0 1px 2px rgba(0,0,0,0.3)` |
| `.tool-status` | `flex: 1`, `display: flex`, `justify-content: center` |

Select icon은 16x16 pointer shape, Annotate icon은 16x16 dashed rectangle이다.
현재 프로젝트에서 lucide를 쓰더라도 stroke weight와 크기는 16px 기준으로
맞춰야 한다.

### Tool Status

Select 상태:

```text
2 open    1 resolved
```

스타일:

```css
.ts-meta {
  display: flex;
  gap: 16px;
  font-size: 11px;
  color: #b6b8be;
  font-variant-numeric: tabular-nums;
}
.dot-open { background: #f2c94c; box-shadow: 0 0 0 3px rgba(242,201,76,0.18); }
.dot-done { background: #6fcf97; box-shadow: 0 0 0 3px rgba(111,207,151,0.18); }
```

Annotate 상태:

```text
Click a widget — or drag to draw a region
```

스타일:

| 요소 | 값 |
| --- | --- |
| `.ts-hint` | inline-flex, `gap: 8px`, bg `rgba(184,211,106,0.08)`, border `rgba(184,211,106,0.25)`, color `#b8d36a`, `padding: 5px 12px`, pill radius |
| `.ts-dot` | `6px x 6px`, bg `#b8d36a`, `animation: pulse-a 1.4s infinite` |

pulse-a 키프레임:

```css
@keyframes pulse-a {
  0%, 100% { opacity: 1;   transform: scale(1);   }
  50%       { opacity: 0.5; transform: scale(1.3); }
}
```

### Zoom

prototype의 zoom UI는 시각 컨트롤만 있고 React handler가 없다. disabled
state CSS도 없으며 percent 텍스트는 항상 `100%`로 고정이다.

```text
− 100% +
```

스타일:

| 요소 | 값 |
| --- | --- |
| `.tool-zoom` | bg `#1a1c21`, radius `6px`, `padding: 2px`, `gap: 6px`, `font-size: 11px`, tabular nums |
| `.zb` | `24px x 24px`, radius `4px`, `font-size: 14px`, color `#b6b8be` |
| `.zb:hover` | bg `#21242b`, color `#e8e9eb` (transition 없음, 즉시 변경) |
| percent span | `padding: 0 6px` |

FixThis 결정 사항: zoom 범위, step 값, disabled 처리(최소/최대 시 버튼
비활성화 여부)는 prototype 기반으로 결정할 수 없으므로 별도 정의한다.
표시 배율은 기본 `100%`이며, preview frame 중심 기준으로 확대/축소되어야 한다.
버튼 hover/active로 toolbar 높이가 변하면 안 된다.

## Preview / Selection / Drag

### Preview 구조

Option A는 중앙 stage 안에 phone frame을 배치하고, 그 안의 preview surface에
annotation pin과 drag rectangle을 absolute overlay로 얹는다.

```text
.canvas-stage
  PhoneFrame(theme="dark")
    .phone-shell.phone-dark
      .phone-bezel
        .phone-screen
          .phone-notch
          .preview-frame.phone-canvas[.tool-annotate]
            MobilePreview
            .pin-rect*
            .drag-rect?
```

Phone shell 주요 값:

| 요소 | 값 |
| --- | --- |
| `.phone-bezel` | `320px x 660px`, radius `44px`, padding `8px` |
| dark bezel bg | `linear-gradient(180deg, #2a2a2e 0%, #1a1a1d 100%)` |
| bezel box-shadow | 아래 참조 (4레이어) |
| screen | radius `36px`, bg `#fff`, overflow hidden |
| notch | `86px x 22px`, top `10px`, bg `#000`, pill radius |
| `.phone-canvas` | absolute inset 0, no border/shadow/radius override |

phone-bezel box-shadow (전부 적용해야 prototype 깊이감 재현):

```css
.phone-bezel {
  box-shadow:
    0 0 0 2px #3a3a40,
    0 30px 60px -20px rgba(0,0,0,0.6),
    0 12px 24px -8px rgba(0,0,0,0.4),
    inset 0 1px 0 rgba(255,255,255,0.06);
}
```

Stage 배경:

```css
.canvas-stage {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 50% 50%, #131418 0%, #0d0e10 70%),
    #0d0e10;
  overflow: hidden;
  min-height: 0;
}
```

### Select 모드

Select 모드는 기존 annotation을 inspect하는 모드다.

- pin click: `event.stopPropagation()` 후 `selectedId = ann.id`.
- preview empty click: `selectedId = null`.
- drag rectangle은 시작하지 않는다.
- Inspector는 선택 여부에 따라 `Annotations` list 또는 `Annotation` detail로
  전환된다.

### Annotate 모드

Annotate 모드는 widget click 또는 drag로 annotation을 만든다.

Pointer down:

1. `tool !== "annotate"`이면 무시.
2. preview frame 기준 pointer 위치를 percent 좌표로 계산.
3. `dragStart = { x, y, target }`.
4. `dragMoved = false`.
5. `draggingRect = { x, y, w: 0, h: 0 }`.
6. `selectedId = null`.

Pointer move:

1. `dragStart`가 없으면 무시.
2. 현재 pointer 위치를 percent 좌표로 계산.
3. `dx > 0.6` 또는 `dy > 0.6`이면 `dragMoved = true`.
4. 시작/현재 위치를 normalize해서 `draggingRect = { x, y, w, h }`.

Pointer up:

1. `dragMoved === true`이고 `draggingRect.w > 1.5`, `draggingRect.h > 1.5`
   이면 custom region annotation을 생성한다.
2. region label은 `Region ${annotations.length + 1}`. 이 라벨은 생성 시점의
   length+1로 동결된다. 이후 다른 annotation 삭제로 인한 재번호화가 일어나도
   라벨 문자열 자체는 변경되지 않는다.
3. drag가 아니면 최초 pointer down target에서 `closest("[data-w]")`를 찾는다.
4. widget이 있으면 widget bounds로 snap하고, label은 `data-w`의 hyphen을
   space로 바꾼 뒤 첫 글자를 대문자로 만든다.
5. annotation 생성 기본값:

```javascript
severity: "med"
comment: ""
status: "open"
```

6. 새 annotation을 배열 뒤에 append한다.
7. `selectedId`를 새 annotation id로 설정한다.
8. `tool = "select"`로 되돌린다.
9. `dragStart`, `dragMoved`, `draggingRect`를 초기화한다.

### Drag Rectangle

Drag rectangle은 live preview 중에만 보이며, `dragMoved.current`가 true일 때
렌더링된다.

```css
.drag-rect {
  position: absolute;
  border: 1.5px dashed currentColor;
  background: rgba(120, 180, 255, 0.12);
  pointer-events: none;
  border-radius: 4px;
}
```

`drag-rect`는 흰 배경의 `phone-screen` 안에 렌더된다. `currentColor`는 해당
컨텍스트에서 어두운 색이 상속되어 **어두운 dashed border**로 보인다. 구현 시
currentColor 상속이 불확실하다면 `border-color: rgba(13,14,16,0.6)` 등으로
명시 토큰을 사용하는 편이 안전하다.

### Widget Hover

Annotate 모드에서만 `[data-w]` 요소에 hover outline이 생긴다.

```css
.preview-frame.tool-annotate [data-w]:hover {
  outline: 2px solid rgba(107, 78, 255, 0.7);
  outline-offset: -1px;
  border-radius: 4px;
  cursor: pointer;
}
```

`outline-offset: -1px`로 outline이 요소 안쪽으로 들어와 inner glow처럼
보인다.

FixThis에서는 `[data-w]`를 Compose semantics node hit target으로
대체한다. hover outline과 click snap 결과는 가장 작은 containing node에
맞춰야 한다.

## Annotation Pins

Pin은 point marker가 아니라 rectangle overlay다.

```text
.pin-rect
  .pin-tag
```

`--pin-color`는 severity에 따라 JSX에서 inline style로 주입된다.

```jsx
style={{ "--pin-color": severityColor }}
```

Compose에서는 `Modifier` 또는 `drawBehind`로 직접 severity 색을 전달한다.

Pin style:

```css
.pin-rect {
  position: absolute;
  border: 1.5px solid var(--pin-color);
  background: color-mix(in srgb, var(--pin-color) 12%, transparent);
  border-radius: 4px;
  transition: all 120ms;
}
.pin-rect:hover {
  background: color-mix(in srgb, var(--pin-color) 20%, transparent);
}
.pin-rect.is-selected {
  border-width: 2px;
  box-shadow:
    0 0 0 2px color-mix(in srgb, var(--pin-color) 25%, transparent),
    0 4px 16px rgba(0,0,0,0.3);
}
```

Compose 변환 (color-mix → alpha copy):

| CSS | Compose |
| --- | --- |
| `color-mix(... 12%, transparent)` | `severityColor.copy(alpha = 0.12f)` |
| `color-mix(... 20%, transparent)` | `severityColor.copy(alpha = 0.20f)` |
| `color-mix(... 25%, transparent)` | `severityColor.copy(alpha = 0.25f)` |

selected 상태는 (1) border 2px, (2) outer glow 2px, (3) drop shadow 16px
세 레이어 동시 적용이다.

`.pin-tag` style:

```css
.pin-tag {
  position: absolute;
  top: -10px; left: -10px;
  width: 22px; height: 22px;
  border-radius: 50%;
  display: grid; place-items: center;
  font-size: 11px; font-weight: 700;
  color: #0d0e10;
  background: var(--pin-color);
  box-shadow: 0 2px 6px rgba(0,0,0,0.3);
}
```

Pin color mapping:

```javascript
high -> #F26D6D
med  -> #E6B45A
low  -> #5AB1E6
```

Numbering:

- display number is render index + 1.
- row number and pin tag must always match.
- delete 후 남은 annotation은 즉시 1부터 재번호화된다.

Resolved pin opacity:

- source prototype의 `.pin-rect`에는 resolved opacity class가 없다.
- History strip만 resolved opacity `0.35`를 적용한다.
- 현재 FixThis parity 문서는 resolved pin opacity도 낮추라고 되어 있다.
  strict standalone parity를 원하면 pin은 opacity 변화 없이 유지한다.

## Annotation / Annotations Section

오른쪽 패널은 선택 여부에 따라 list/detail 두 상태를 가진다.

```text
no selected annotation -> title "Annotations"
selected annotation    -> title "Annotation"
```

Count pill은 항상 현재 annotations length다.

### Empty State

annotation이 0개이고 선택된 annotation이 없으면 다음 copy를 표시한다.

```text
⌘
No annotations yet
Switch to Annotate, then click a widget or drag a region on the preview.
Start annotating
```

Empty state style:

| 요소 | 값 |
| --- | --- |
| `.empty` | `margin: auto`, flex column, `padding: 32px 24px`, `gap: 8px` (부모 flex 안에서 스스로 가운데 정렬) |
| `.empty-mark` | `44px x 44px`, radius `12px`, bg `#1a1c21`, dashed border `#2a2d35`, color `#7d8089`, `margin-bottom: 4px` |
| `.empty-title` | `13px`, `font-weight: 600` |
| `.empty-body` | `12px`, color `#b6b8be`, `max-width: 220px`, line-height `1.5` |
| `.empty-body b` | color `#e8e9eb` |
| action | `.btn-primary.mini`, `padding: 5px 10px`, `font-size: 11px` |

`Start annotating` click은 `setTool("annotate")`와 동일하다.

### Annotation List

구조:

```text
.ann-list
  .ann-row
    .ann-row-num
    .ann-row-body
      .ann-row-title
      .ann-row-comment
    .ann-row-status.st-open|st-in-progress|st-resolved
```

Style:

| 요소 | 값 |
| --- | --- |
| `.ann-list` | `flex: 1`, `overflow-y: auto`, `padding: 8px`, `gap: 4px` |
| `.ann-row` | grid `28px 1fr auto`, `gap: 10px`, `padding: 10px`, radius `8px`, `transition: background 120ms` |
| hover | bg `#1a1c21` |
| `.ann-row-num` | `24px x 24px`, radius `6px`, `font-size: 11px`, `font-weight: 700`, color `#0d0e10`, bg = severity color (inline style) |
| `.ann-row-comment` | `11px`, color `#b6b8be`, 2-line clamp, `margin-top: 3px` |
| empty comment | `No comment`, color `#999`, italic |
| `.ann-row-status` | `10px`, `padding: 2px 7px`, uppercase, `letter-spacing: 0.08em`, pill |

`.ann-row-num` 배경색은 severity color를 JSX inline style로 직접 적용한다.
CSS 클래스로는 결정되지 않는다.

Row click:

1. `selectedId = ann.id`.
2. matching pin에 `.is-selected`.
3. inspector title이 `Annotation`으로 바뀌고 detail view가 열린다.

### Annotation Detail

구조:

```text
.ann-detail
  .back-btn "← All annotations"
  .field Label input
  .field Severity segmented high / med / low
  .field Comment textarea
  .field Status segmented open / in-progress / resolved
  .detail-actions
    .btn-danger Delete
    .btn-ghost Done
```

Detail style:

| 요소 | 값 |
| --- | --- |
| `.ann-detail` | `flex: 1`, `overflow-y: auto`, `padding: 16px`, `gap: 14px` |
| `.back-btn` | `font-size: 11px`, color `#7d8089`, `padding: 4px 0` |
| `.back-btn:hover` | color `#e8e9eb` |
| `.field` | flex column, `gap: 6px` |
| input/textarea | bg `#1a1c21`, border `1px solid #2a2d35`, color `#e8e9eb`, `padding: 8px 10px`, radius `6px`, `font-size: 13px`, `outline: none`, `transition: border 120ms` |
| focus | `border-color: #b8d36a` (border-color만 transition) |
| textarea | line-height `1.5`, min-height `80px`, `resize: vertical` |
| `.seg` | flex, `gap: 2px`, `padding: 2px`, bg `#1a1c21`, radius `7px` |
| `.seg-btn` | flex 1, `padding: 6px 10px`, `font-size: 11px`, uppercase, `letter-spacing: 0.06em`, `transition: all 120ms` |
| active status segment | bg `#21242b`, text `#e8e9eb` (CSS 클래스로 처리) |
| active severity segment | bg = severity color, text `#0d0e10` (**inline style로 강제**) |
| `.detail-actions` | flex space-between, `border-top: 1px solid #1f2228`, `padding-top: 12px`, `margin-top: 4px` |

`.seg-btn.is-active`의 CSS는 항상 `background: #21242b; color: #e8e9eb`다.
severity segmented control에서 활성 색상이 severity color가 되는 것은
**JSX inline style이 CSS를 override하는 방식**이다. CSS 클래스만으로는
severity 배경색이 나오지 않는다.

```css
.btn-danger {
  padding: 7px 12px; border-radius: 7px;
  color: #f26d6d; font-size: 12px; font-weight: 500;
  border: 1px solid transparent; transition: all 120ms;
}
.btn-danger:hover {
  background: rgba(242,109,109,0.1);
  border-color: rgba(242,109,109,0.3);
}
```

`Done` 버튼은 Topbar와 동일한 `.btn-ghost` 스타일을 사용한다 (`padding: 7px 12px`).

Detail event behavior:

- `← All annotations`: `selectedId = null`.
- `Done`: `selectedId = null`.
- Label edit: 즉시 annotation label 변경.
- Severity edit: 즉시 row number, pin color, history strip, active segment 색상 변경.
- Comment edit: 즉시 row comment preview 변경.
- Status edit: 즉시 status pill, toolbar count, history count/strip opacity 변경.
- Delete: annotation 제거, `selectedId = null`, row/pin 번호 재정렬.

별도의 "Save changes"는 없다. 모든 field edit은 즉시 local state에 반영된다.
FixThis 구현에서는 debounce 또는 optimistic API update를 사용할 수 있지만,
사용자에게 보이는 동작은 즉시 반영이어야 한다.

## Transitions

prototype이 보장하는 transition 목록. 모두 `120ms` 일관 사용한다.

| selector | transition |
| --- | --- |
| `.history-item` | `background 120ms` |
| `.hi-del` | `all 120ms` (opacity + color + background 포함) |
| `.tool` | `all 120ms` |
| `.btn-primary` | `all 120ms` (hover 시 `translateY(-1px)` 포함) |
| `.btn-ghost` | `all 120ms` |
| `.btn-danger` | `all 120ms` |
| `.ann-row` | `background 120ms` |
| `.pin-rect` | `all 120ms` |
| `.field input/textarea` | `border 120ms` (border-color만) |
| `.seg-btn` | `all 120ms` |
| `.bc-input` | `background 120ms` |
| `.ts-dot` | `pulse-a 1.4s infinite` |

tool 전환(Select ↔ Annotate), 패널 전환(list ↔ detail)은 transition 없이
즉시 swap한다.

키보드 단축키는 prototype CSS 및 JS에 정의되어 있지 않다.
FixThis에서 지원하지 않는다면 "단축키 미지원"으로 명시한다.

## Mobile Preview Widget Targets

prototype의 widget snap target은 `data-w` attribute다.

주요 target:

```text
logo
header
greeting
avatar
balance-card
action-send
action-receive
action-swap
section-quick
quick-bills
quick-transfer
quick-invest
quick-cards
section-tx
tx-1
tx-2
tx-3
tabbar
tab-home
tab-cards
tab-stats
tab-me
```

FixThis에서는 이 개념을 Compose semantics node에 매핑한다.

- click: pointer 아래의 가장 작은 containing node를 선택한다.
- drag: semantics node snap이 아니라 drag rectangle bounds를 사용한다.
- label: node text/contentDescription/testTag/role을 우선 사용하고, 없으면
  `Region N` 또는 `Annotation N` fallback을 사용한다.
- bounds: screenshot 좌표를 preview percent 좌표로 변환해서 pin과 row가 같은
  annotation record를 바라보게 한다.

## Current Project 적용 차이

현재 `FeedbackConsoleAssets.kt`는 아직 다음 요소가 남아 있다.

- `pendingFeedbackItems`
- `Add` / `Save` 중심 flow
- `Focus` / `Delete` pending list
- live/frozen preview mode badge
- old selection overlay와 comment composer

Option A parity 구현 시 visible UI 기준으로는 다음을 교체한다.

| 현재 개념 | Option A 개념 |
| --- | --- |
| sessions/pending items | snapshot `History` + annotations |
| selection overlay only | persistent numbered pin rectangles |
| comment composer | annotation detail `Comment` textarea |
| Add to pending | Annotate click/drag creates annotation immediately |
| Focus pending | row/pin click selects annotation |
| Save draft | `Save snapshot` handoff |
| selection mode/navigate mode | `Select` / `Annotate` |

## Acceptance Checklist

구현 완료 기준:

- 첫 화면에서 dark Studio shell, 56px topbar, 280/1fr/340 body가 보인다.
- brand-mark는 30×30 accent(`#b8d36a`) 배경 박스, 안에 dark text 글리프.
- draftTitle 입력은 기본 상태에서 투명, hover/focus 시 `#1a1c21` 배경.
- 왼쪽 panel title은 `History`, count는 snapshot 수다.
- panel-count pill은 bg `#21242b`, color `#b6b8be`, `padding: 2px 8px`, radius `999px`.
- panel-head는 `padding: 14px 16px`, `border-bottom: 1px solid #1f2228`.
- `.studio-history`에 `border-right: 1px solid #2a2d35`, `.studio-inspector`에 `border-left`.
- active History card는 left accent inset `#b8d36a`가 있다.
- active snapshot의 toolbar count가 `2 open`, `1 resolved`로 보인다.
- `Select`와 `Annotate`는 segmented tool group 안에 있고 active 상태에서
  `#21242b` 배경과 `#b8d36a` text/icon을 쓴다.
- tool-status는 toolbar 중앙 정렬(`flex: 1; justify-content: center`).
- `− 100% +` zoom control은 24px button, 11px percent로 고정 크기다.
- Annotate hint dot은 `pulse-a 1.4s infinite` (opacity 1↔0.5, scale 1↔1.3).
- Select mode에서 preview empty click은 선택을 해제한다.
- Select mode에서 pin click은 detail을 열고, pin은 selected glow를 가진다.
- selected pin은 border 2px + outer glow 2px + drop shadow 16px 세 레이어 동시.
- Annotate mode에서 widget hover outline은 violet `rgba(107,78,255,0.7)`, `outline-offset: -1px`.
- Annotate mode에서 click은 Compose node bounds annotation을 만든다.
- Annotate mode에서 drag는 custom region annotation을 만든다.
- drag threshold는 move detect `0.6%`, creation minimum `1.5% x 1.5%`를 따른다.
- 새 annotation 기본값은 `med`, `open`, empty comment다.
- annotation 생성 후 tool은 `Select`로 돌아오고 새 annotation detail이 열린다.
- pin number, row number, history strip 순서가 항상 동일하다.
- Delete 후 row/pin 번호가 1부터 gap 없이 재정렬된다.
- pin-rect fill은 `severityColor.copy(alpha=0.12)`, hover는 `0.20`, selected glow는 `0.25`.
- `.ann-row-num`은 `font-size: 11px`, `font-weight: 700`, color `#0d0e10`, bg = severity color (inline).
- severity segmented control 활성 버튼 색은 CSS 클래스가 아닌 inline style로 적용.
- `.seg-btn` letter-spacing은 `0.06em` (status pill의 `0.08em`과 다름).
- input/textarea에 `outline: none`, `transition: border 120ms` 적용.
- 오른쪽 panel은 no selection에서 `Annotations`, selection에서 `Annotation`이다.
- empty state는 `margin: auto`로 부모 flex 안에서 스스로 중앙 정렬.
- empty state copy와 `Start annotating` 동작이 prototype과 같다.
- Label/Severity/Comment/Status 편집은 별도 save 없이 즉시 반영된다.
- hi-del hover 시 bg `#21242b`, color `#f26d6d`.
- phone-bezel box-shadow는 4레이어 (outline + 2단 drop shadow + inset highlight).
- 모든 hover transition은 `120ms` 일관 적용.
- high/med/low 색상은 각각 `#F26D6D`, `#E6B45A`, `#5AB1E6`이다.
- status pill 색상은 open `#f2c94c`, in-progress `#5bb1e6`, resolved `#6fcf97`이다.
- History strip의 resolved item opacity는 `0.35`다.
- hover/active/focus에서 toolbar, row, card의 높이와 column layout이 흔들리지 않는다.

## 구현 시 주의할 결정

1. `History` second count label을 `done`으로 둘지 `resolved`로 통일할지
   정해야 한다. standalone source는 History에서 `done`, toolbar/status에서
   `resolved`를 쓴다.
2. zoom button은 standalone에서 동작이 없다. 현재 프로젝트는 실제 zoom을
   붙이되, UI 크기와 label은 prototype과 같게 유지하는 편이 제품적으로 낫다.
   zoom 범위, step, disabled 처리를 별도 정의해야 한다.
3. resolved pin opacity는 standalone에는 없고 기존 FixThis parity 문서에는
   있다. "HTML 그대로"라면 opacity 변화 없이 두고, "상태 가독성"을 우선하면
   기존 문서처럼 opacity를 낮춘다.
4. `+ New session`은 standalone에는 있지만 기존 FixThis parity 문서에서는
   제거 대상이다. 요청 범위 밖이므로 구현 전에 하나로 고정해야 한다.
5. History가 0개일 때의 empty state를 별도 정의해야 한다. prototype에는
   초기 snapshot이 항상 2개이므로 empty state가 정의되어 있지 않다.
6. 키보드 단축키는 prototype에 없다. FixThis에서 지원하지 않는다면
   명시적으로 "미지원"으로 고정한다.
7. density toggle(`density-compact` / `density-spacious`)을 지원하지 않는다면
   기본 font-size `13px` 단일값으로 고정한다.
