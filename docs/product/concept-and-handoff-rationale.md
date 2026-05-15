# FixThis Product Concept And Handoff Rationale

이 문서는 FixThis를 처음 이해하는 사람이 제품 의도, 핵심 선택, 그리고
agent handoff prompt가 왜 지금 형태인지 한 번에 파악할 수 있도록 정리한
정식 설명서다.

## 한 줄 정의

FixThis는 Jetpack Compose debug 앱에서 사용자가 수정할 UI를 직접 지목하고,
원하는 변경사항을 적으면, AI 코딩 에이전트가 올바른 코드를 찾고 수정할 수
있도록 실행 중인 화면의 근거를 로컬에서 묶어 전달하는 도구다.

```text
running Compose debug UI
-> human-selected target
-> local runtime evidence
-> compact agent handoff
-> code change and feedback resolution
```

## 해결하려는 문제

AI agent에게 "이 버튼 바꿔줘", "세 번째 카드 색상 바꿔줘", "여기 여백을
줄여줘"라고만 말하면 agent는 보통 다음을 모른다.

- 현재 화면이 어떤 Activity, route, 또는 상태인지
- 같은 텍스트가 여러 화면이나 파일에 있는지
- 반복 리스트의 몇 번째 item인지
- screenshot의 픽셀이 어떤 composable과 연결되는지
- 수정할 곳이 call site인지, reusable component 정의인지, data/copy source인지
- 현재 source index가 설치된 debug APK와 맞는지

FixThis는 이 빈틈을 "사용자가 직접 지목한 UI target"과 "실행 중인 앱에서
수집한 evidence"로 채운다.

## 현재 제품 흐름

FixThis의 현재 main workflow는 Android 앱 내부가 아니라 desktop browser
console에서 진행된다.

```text
debug 앱 실행
-> FixThis Studio console 열기
-> connection card에서 Start / Open app / Reconnect 처리
-> live preview에서 앱 탐색
-> Annotate로 현재 preview freeze
-> component 클릭 또는 visual area drag
-> comment 작성
-> Add annotation
-> Copy Prompt 또는 Save to MCP
-> agent가 item을 읽고 claim/resolve
```

Android 앱 내부에는 `MCP waiting` / `MCP connected` 상태 pill만 표시된다.
선택, annotation, prompt 생성, feedback queue, handoff 저장은 desktop
console이 소유한다.

이 구조는 앱 sidekick을 작게 유지하고, agent가 실제로 읽을 local artifact와
queue state를 desktop process가 일관되게 관리하게 만든다.

## 왜 Compose 전용인가

FixThis의 핵심은 "실행 중인 UI target을 source 후보와 연결하는 것"이다.
Android 전체를 대상으로 하면 XML/View, Compose, WebView, Flutter, React
Native가 모두 다른 inspection 방식을 요구한다.

V1은 Jetpack Compose로 좁힌다.

- Compose semantics tree라는 안정적인 runtime signal을 사용할 수 있다.
- 제품 메시지가 단순해진다.
- 초기 설치와 검증 범위가 현실적이다.
- AccessibilityService, WebView injection, View hierarchy source mapping 같은
  큰 분기를 피할 수 있다.

대신 View/XML/WebView는 정확한 source target으로 보장하지 않는다. interop
가능성이 있으면 prompt에 warning으로 드러내고 agent가 검증하도록 한다.

## 왜 debug-only인가

FixThis는 screenshot, UI text, semantics, source hint, user comment를 다룬다.
이 데이터는 민감할 수 있다. 따라서 production feature가 아니라 debug/dev
utility로 제한한다.

기본 원칙은 다음과 같다.

- release build에는 포함하지 않는다.
- sidekick은 debuggable 앱인지 확인한다.
- 설치 문서는 `debugImplementation` 흐름을 기준으로 한다.
- `.fixthis/` 아래 local artifact는 commit하지 않는다.
- FixThis 자체는 외부 AI API로 screenshot이나 prompt를 업로드하지 않는다.

## 왜 앱 안 annotation이 아니라 desktop console인가

초기 아이디어는 앱 내부에서 UI를 탭하고 comment를 입력하는 방식이었다.
하지만 실제 agent workflow에서는 다음 상태가 모두 desktop 쪽에 있다.

- MCP server process
- local feedback session
- screenshot artifact promotion
- source index lookup
- Copy Prompt clipboard flow
- Save to MCP queue flow
- claim / resolve lifecycle
- connection recovery and stale preview handling

따라서 current product는 desktop console first다. 앱은 runtime evidence를
제공하고 연결 상태만 보여준다. 사용자는 desktop console에서 화면을 보고,
선택하고, 저장한다.

## 왜 모든 composable에 `testTag`를 요구하지 않는가

`testTag`는 정확한 signal이지만, 기존 앱 전체에 tag를 붙이라고 요구하면
도입 비용이 너무 커진다. FixThis는 tag가 없어도 시작할 수 있어야 한다.

기본 evidence는 다음을 조합한다.

- selected text
- content description
- role
- click / input action
- bounds
- nearby semantics context
- screenshot and crop
- source-index candidates

`testTag`와 `comp:<ComposableName>:<variant>` convention은 있으면 강한 signal로
쓴다. 없으면 다른 evidence로 best-effort handoff를 만든다.

## 왜 source candidate는 "정답"이 아니라 "후보"인가

Compose runtime node에서 항상 정확한 source file/line을 얻을 수 있는 것은
아니다. compiler plugin이나 Compose tooling internals를 쓰면 더 정확한 정보를
얻을 가능성은 있지만, Kotlin/Compose 버전 호환성과 유지보수 부담이 커진다.

V1은 Gradle source index를 기준으로 시작한다.

```text
semantics evidence
-> source index match
-> ranked source candidates
-> confidence, margin, matched reason tokens
```

이 방식은 안정적이고 설명 가능하지만 완벽한 runtime mapping은 아니다.
그래서 prompt는 항상 "source hints are candidates" 원칙을 포함한다.

## Prompt가 전달해야 하는 것

Agent handoff prompt는 네 가지 질문에 빠르게 답해야 한다.

1. 사용자가 무엇을 요청했나?
2. 사용자가 어떤 UI target을 지목했나?
3. 어떤 source file/line 후보를 먼저 봐야 하나?
4. 이 target/source hint를 얼마나 믿어도 되나?

Markdown prompt는 agent와 사람이 읽기 위한 compact view다. JSON session은
tooling을 위한 full-fidelity record다. Markdown은 빠른 작업 지시서이고, JSON은
IDs, paths, screen, selected node, nearby node, source candidates, target
evidence, screenshot artifact를 보존하는 계약이다.

## Prompt의 주요 필드와 이유

### `Package`

어떤 debug 앱에서 나온 feedback인지 확인한다. 여러 Android app이나 sample을
오가며 작업할 때 잘못된 package를 수정하는 일을 줄인다.

### `Source root`

candidate path가 같은 긴 prefix를 반복하면 prompt token이 낭비된다. 공통 prefix를
한 번 올리고 candidate line은 상대 경로처럼 보이게 만들어 읽기 쉽게 한다.

### `Handoff quality`

낮은 target confidence, visual-area-only, overlap, duplicate marker, stale source
index, source candidate 부재 같은 위험 신호를 prompt 상단에 요약한다. Agent가
처음부터 검증 강도를 조절할 수 있게 하기 위한 필드다.

### `Screen`, `screenshot`, `viewport`, `activity`

여러 annotation이 같은 frozen preview에서 나온 경우 같은 screen block 아래에
묶인다.

- `screenshot`은 agent가 실제 픽셀을 확인할 수 있게 한다.
- `viewport`는 좌표가 화면의 어디쯤인지 계산하게 해준다.
- `activity`는 display name과 실제 Android Activity가 다를 때 route/context 힌트가 된다.

### `[N] title`

`N`은 console overlay marker와 맞는 human-facing 번호다. 사람이 "2번 marker"
라고 말할 수 있게 한다.

### `id`

`id`는 MCP tool용 feedback item id다. Agent는 작업 전에 item을 claim하고,
작업 후 resolve할 때 이 id를 사용한다.

```text
fixthis_claim_feedback({sessionId, itemId})
fixthis_resolve_feedback({sessionId, itemId, status, summary})
```

Marker number는 UI 이해용이고, item id는 tool contract용이다.

### `target`

선택된 UI를 redaction-safe하게 요약한다. tag, text, contentDescription, role
같은 semantics 정보를 넣지만 editable/password 등 민감한 값은 제거한다.

Visual area selection이면 `target: visual area`처럼 semantic node가 아니라는
사실을 명시한다.

### `box=(L,T)-(R,B)`

선택 위치의 window pixel bounds다. Semantic summary가 약하거나 redacted된 경우에도
agent가 screenshot에서 정확한 위치를 찾을 수 있게 한다.

### `editSurface`

`sourceCandidates`는 "선택된 text/tag/nearby evidence가 어느 source와 맞았는가"를
말한다.

`editSurface`는 "visual/style/layout 변경이 실제로 렌더링될 가능성이 높은 곳"을
힌트로 준다.

예를 들어 사용자가 카드 내부 text를 찍었지만 요청이 "카드 여백 줄여줘"라면,
text source보다 container composable이 더 적절한 edit surface일 수 있다.

### Source candidate lines

Candidate는 최대 3개까지 보여준다. 하나만 보여주면 agent가 call site와 component
definition, data/copy source 사이를 판단하기 어렵다.

Rank 1에는 다음을 추가한다.

- `conf`: high / medium / low / none
- `margin`: rank 1과 rank 2의 score 차이
- `matched`: 어떤 evidence가 맞았는지 나타내는 token

`margin`이 작으면 rank 1이 애매하다는 뜻이다. Agent는 runner-up도 봐야 한다.

### `instance i/N`

LazyColumn이나 반복 card처럼 여러 marker가 같은 call site와 tag를 공유할 수 있다.
이때 `instance 2/3` 같은 표시가 없으면 agent는 서로 다른 UI instance인지,
같은 target을 중복 pin한 것인지 알기 어렵다.

`instance`는 반복 렌더링된 target을 구분하기 위한 신호다.

### `targetRisk=duplicate-of-marker-N`

나중 marker가 이전 marker와 같은 source, tag, bounds, runtime path를 가리키면
중복 pin일 가능성이 크다. Agent가 같은 일을 두 번 처리하지 않도록 duplicate risk를
prompt에 드러낸다.

### Overlap group

겹치는 target은 한 번에 처리하면 잘못된 composable을 수정하기 쉽다. Overlap group은
"한 marker씩 해결하라"는 작업 단위 힌트다.

### `targetConfidence`와 `warning`

Source candidate confidence와 target confidence는 다르다.

- Source confidence: source file 후보가 evidence와 얼마나 잘 맞는가
- Target confidence: 사용자가 선택한 UI target 자체를 얼마나 믿을 수 있는가

예를 들어 source 후보가 있어도 target이 visual area only거나 WebView boundary일 수
있으면 target confidence는 낮아야 한다.

Warnings는 agent가 멈춰서 확인해야 하는 상황을 알려준다.

- visual area only
- meaningful Compose target 없음
- possible AndroidView/WebView interop
- stale source index
- forced screen mismatch
- missing fingerprint
- sensitive text redacted

## 왜 prompt renderer는 server 쪽 단일 구현인가

Copy Prompt는 browser에서 쓰고, `fixthis_read_feedback`은 MCP server에서 쓴다.
두 곳이 각각 prompt를 만들면 필드가 쉽게 어긋난다.

실제로 prompt에 item id, session id, claim/resolve protocol, crop path, stale
warning 같은 필드가 추가될수록 browser renderer와 Kotlin renderer가 drift할 위험이
커진다.

현재 방향은 Kotlin `CompactHandoffRenderer`가 handoff Markdown의 단일 source of
truth가 되는 것이다.

```text
Copy Prompt
-> server-rendered compact handoff
-> clipboard

Save to MCP
-> server-rendered compact handoff
-> local handoff batch

fixthis_read_feedback
-> same compact handoff + complete JSON
```

이렇게 하면 prompt format 변경은 한 곳에서 관리되고, Copy Prompt와 MCP read flow가
같은 구조를 공유한다.

## 읽는 순서

FixThis를 처음 보는 사람은 이 문서만 읽어도 제품 의도와 prompt 설계 이유를
이해할 수 있어야 한다. 정확한 field grammar나 JSON contract가 필요할 때만 다음
문서를 추가로 보면 된다.

- `docs/reference/feedback-console-contract.md`
- `docs/reference/output-schema.md`
- `docs/reference/mcp-tools.md`
- `docs/architecture/overview.md`
