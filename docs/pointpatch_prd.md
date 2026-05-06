# PointPatch for Android Compose — 상세 PRD

**제품명:** PointPatch for Android Compose  
**짧은 이름:** PointPatch Compose  
**CLI 이름:** `pointpatch`  
**대상 플랫폼:** Android Jetpack Compose  
**제품 유형:** Debug-only UI feedback + AI context bridge  
**핵심 문장:** Android Jetpack Compose debug 앱에서 수정할 UI를 지목하고 원하는 변경사항을 입력하면, AI 코딩 에이전트가 코드를 수정할 수 있는 정확한 runtime context를 생성한다.  
**문서 버전:** v1.0  
**작성일:** 2026-05-03

---

## 1. 제품 개요

PointPatch for Android Compose는 실행 중인 Android Jetpack Compose debug 앱에서 사용자가 UI 요소를 직접 선택하고, 원하는 변경사항을 입력하면, 해당 UI에 대한 runtime context를 AI 코딩 에이전트에게 전달하는 개발용 도구다.

사용자는 “이 버튼”, “이 카드”, “세 번째 리스트 아이템”, “여기 여백”처럼 애매한 UI 수정 요청을 앱 화면에서 직접 지목할 수 있다. PointPatch는 선택된 UI의 Compose semantics, bounds, 주변 텍스트, screenshot crop, source search hint, source candidate를 수집해 Codex, Claude Code, Cursor 같은 AI coding agent가 이해할 수 있는 Markdown/JSON context로 변환한다.

제품은 두 가지 사용 방식을 제공한다.

1. **기본 UX:** 앱 안에서 UI 선택 → 코멘트 입력 → Copy for AI.
2. **고급 UX:** MCP feedback console → desktop browser에서 device 선택,
   live preview로 앱 탐색, Add로 preview freeze, 여러 pending feedback 작성,
   Save로 evidence snapshot 1개와 item N개 저장, Send로 agent-readable
   handoff batch 생성.

MCP는 기본 UX를 대체하지 않는다. MCP는 AI coding agent와의 연결을 자동화하는 advanced integration layer다.

---

## 2. 핵심 가치

### 2.1 사용자가 말하는 “여기”를 AI가 정확히 이해하게 한다

일반적인 AI coding agent는 repository의 코드만 보고 수정 위치를 추정한다. 사용자가 “이 버튼을 바꿔줘”라고 말해도, agent는 어떤 화면의 어떤 버튼인지 알기 어렵다.

PointPatch는 실행 중인 앱에서 선택한 UI 요소를 기준으로 다음 정보를 제공한다.

- 선택된 Compose semantics node
- UI bounds
- text, contentDescription, role, action
- 주변 노드와 주변 텍스트
- screenshot crop
- source search hints
- source candidates
- 사용자 코멘트

이를 통해 AI는 사용자가 말한 UI와 코드 수정 위치를 훨씬 정확히 연결할 수 있다.

### 2.2 모든 Composable에 tag를 붙이지 않아도 된다

기존 UI test나 automation 방식은 `testTag`, accessibility id, resource id 같은 안정적 selector가 중요하다. 하지만 실제 프로젝트에서 모든 Composable에 tag를 붙이는 것은 비현실적이다.

PointPatch는 기본적으로 Compose semantics tree를 사용한다. `testTag`는 있으면 활용하지만 필수는 아니다.

### 2.3 앱 코드 수정 없이 시작한다

artifact가 publish된 뒤의 외부 프로젝트 UX는 다음 중 하나다. 현재 repo-local V1 검증은 composite build/settings wiring으로 plugin build를 포함한다.

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

또는:

```kotlin
dependencies {
    debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
}
```

현재 repo 밖의 외부 프로젝트는 published artifact가 없다면 이 coordinate에 의존할 수 없고, composite build/project dependency wiring을 명시해야 한다.

사용자는 Activity마다 install을 호출하거나, `setContent`를 `PointPatchRoot`로 감싸거나, AndroidManifest를 직접 수정하지 않아도 된다.

### 2.4 Debug-only, privacy-first

PointPatch는 개발과 디버그 용도다. production feature가 아니다.

기본 원칙:

- debug build에서만 동작
- release build에는 포함되지 않음
- AccessibilityService 사용 안 함
- 네트워크 전송 기본 비활성화
- clipboard/file export 중심
- password/editable text redaction 기본 활성화
- screenshot은 기본적으로 로컬에만 저장

---

## 3. 사용자와 페르소나

### 3.1 Android Compose 개발자

목표:

- UI 수정 요청을 AI coding agent에게 정확히 전달하고 싶다.
- “이 버튼”, “여기 여백”처럼 애매한 요청을 줄이고 싶다.
- AI가 잘못된 Composable을 수정하는 문제를 줄이고 싶다.

주요 흐름:

```text
앱 실행
→ PointPatch 버튼 클릭
→ UI 선택
→ 원하는 변경사항 입력
→ Copy for Codex/Claude/Cursor
→ AI가 코드 수정
```

### 3.2 AI coding agent power user

목표:

- Codex/Claude Code/Cursor가 실행 중인 앱 화면을 직접 inspect하게 하고 싶다.
- UI 수정 후 실제 화면에 반영됐는지 검증하고 싶다.
- MCP workflow를 사용하고 싶다.

주요 흐름:

```text
pointpatch setup --package <applicationId>
→ pointpatch run
→ AI agent에서 PointPatch MCP tool 사용
→ 앱에서 UI 선택
→ agent가 source candidate를 받고 코드 수정
→ agent가 verify tool로 검증
```

### 3.3 디자이너 / PM / QA

목표:

- 앱 화면에서 문제 있는 부분을 직접 지목하고 피드백을 남기고 싶다.
- 개발자나 AI에게 넘길 수 있는 구조화된 피드백을 만들고 싶다.

주요 흐름:

```text
앱에서 PointPatch 버튼
→ 문제 UI 선택
→ "이 여백 줄여주세요" 입력
→ 공유 또는 복사
```

주의:

- 이 사용자는 MCP, ADB, package name, source index 같은 용어를 몰라도 되어야 한다.

---

## 4. 문제 정의

### 4.1 일반 AI agent 요청의 한계

사용자가 AI agent에게 아래처럼 요청한다고 가정한다.

```text
이 화면에서 결제 버튼 문구를 바로 결제하기로 바꿔줘.
```

AI agent가 repository만 보고 판단하면 다음 문제가 생긴다.

- 현재 화면이 어떤 route/activity인지 모름
- 같은 문구가 여러 곳에 있을 수 있음
- 반복 리스트의 특정 item인지 모름
- screenshot이 없으면 시각적 위치를 모름
- screenshot이 있어도 코드 위치는 추정해야 함
- 수정 후 실제 UI 반영 여부를 검증하기 어려움

### 4.2 Android Compose 특유의 문제

Compose에서는 웹 DOM처럼 selector를 얻기 어렵다.

- Composable 함수가 runtime UI node와 1:1로 대응하지 않음
- 모든 Composable이 `View`로 남아있지 않음
- source file/line을 runtime node에서 항상 직접 얻을 수 없음
- custom Canvas나 purely visual UI는 semantics가 없을 수 있음
- `testTag`는 수동으로 붙여야 하므로 기본 요구사항으로 삼기 어려움

### 4.3 기존 모바일 MCP/automation 도구와의 차이

DTA, mobile-mcp, appium-mcp 같은 도구는 모바일 자동화, 테스트, 디버깅, network inspection에 강하다. PointPatch는 더 좁고 명확한 문제에 집중한다.

```text
실행 중인 Compose UI에서 사용자가 수정할 부분을 지목하고,
AI coding agent가 해당 UI를 수정할 수 있도록 context를 전달한다.
```

---

## 5. 제품 목표

### 5.1 Primary goals

1. 사용자가 Compose UI를 탭해서 수정 대상을 선택할 수 있다.
2. 사용자가 원하는 변경사항을 앱 안에서 입력할 수 있다.
3. 선택된 UI에 대한 정확한 runtime context를 생성한다.
4. 생성된 context를 Markdown/JSON으로 복사하거나 공유할 수 있다.
5. `testTag` 없이도 대부분의 Material/Compose UI에서 동작한다.
6. Gradle plugin 또는 debug dependency 하나로 시작할 수 있다.
7. MCP 연결 시 AI agent가 현재 화면 context를 직접 조회할 수 있다.
8. MCP feedback console에서 사용자가 Studio UI의 Sessions / preview canvas /
   Inspector 구조 안에서 live preview를 탐색하고, Add로 freeze한 화면에 component
   또는 custom area feedback을 여러 개 쌓은 뒤, Save 한 번으로 evidence snapshot과
   item들을 저장하고 Copy 또는 Send로 handoff context를 만들 수 있다.
9. source index/source candidates를 통해 AI가 수정할 파일 후보를 얻을 수 있다.
10. Debug-only와 privacy-first 원칙을 지킨다.

### 5.2 Non-goals

다음은 v1/v2 core 범위가 아니다.

- XML/View 기반 Android UI inspector
- WebView DOM inspector
- 타 앱 UI inspector
- AccessibilityService 기반 device-wide control
- Network traffic recording
- HTTP/WebSocket mocking
- Android Studio plugin full IDE inspector
- Kotlin custom compiler plugin 기반 자동 source mapping
- AI가 코드를 직접 수정하는 file write tool
- feedback console에서 외부 AI API를 직접 호출하는 기능
- production build 사용
- screenshot 자동 PII redaction 보장

---

## 6. 제품 포지셔닝

### 6.1 짧은 소개

```text
PointPatch lets you point at Jetpack Compose UI in a debug Android app, describe what you want changed, and send precise runtime context to AI coding agents.
```

한국어:

```text
PointPatch는 Android Jetpack Compose debug 앱에서 수정할 UI를 지목하고 원하는 변경사항을 입력하면, AI 코딩 에이전트가 코드를 수정할 수 있는 정확한 runtime context를 생성합니다.
```

### 6.2 제품 원칙

- **Point first:** 사용자가 수정할 UI를 직접 지목한다.
- **Tell second:** 사용자가 원하는 변경사항을 직접 입력한다.
- **Context always:** AI에게 단순 screenshot이 아니라 구조화된 context를 준다.
- **No required tags:** `testTag`는 필수가 아니다.
- **Compose-only:** Android Jetpack Compose에 집중한다.
- **Debug-only:** production tool이 아니다.
- **Local-first:** 네트워크 전송은 기본적으로 하지 않는다.
- **MCP optional:** MCP는 고급 연결 방식이지 기본 UX를 대체하지 않는다.

---

## 7. 핵심 사용자 시나리오

### 7.1 기본 시나리오: 앱에서 선택하고 복사

```text
1. 사용자가 debug 앱을 실행한다.
2. 화면 우하단 PointPatch 버튼이 보인다.
3. 사용자가 PointPatch 버튼을 누른다.
4. "수정할 UI를 탭하세요" 안내가 표시된다.
5. 사용자가 결제 버튼을 탭한다.
6. 선택된 영역이 highlight된다.
7. comment sheet가 열린다.
8. 사용자가 "문구를 바로 결제하기로 바꿔줘"라고 입력한다.
9. 사용자가 "Copy for Codex"를 누른다.
10. Markdown context가 clipboard에 복사된다.
11. 사용자가 Codex/Claude/Cursor에 붙여넣는다.
12. AI agent가 관련 파일을 수정한다.
```

### 7.2 MCP feedback console 시나리오

```text
1. 사용자가 AI agent에서 pointpatch_open_feedback_console을 호출하거나
   pointpatch console --package <applicationId>를 실행한다.

2. browser feedback console의 Studio workspace에서 연결된 ADB device를 선택한다.

3. live preview에서 앱을 평소처럼 탐색한다. preview click은 기본적으로
   debug-only navigation이다.

4. 피드백을 남길 화면에서 Add를 눌러 최신 preview를 freeze한다.

5. component를 클릭하거나 custom area를 drag하고 comment를 입력한 뒤
   Add to Pending을 누른다. 같은 frozen preview에 여러 pending item을 추가할 수
   있고, pending item은 Focus/Delete만 지원한다.

6. Save를 한 번 누르면 console은 frozen preview를 evidence snapshot 하나로
   저장하고 모든 pending item을 같은 `screenId`에 연결한다.

7. Send를 누르면 console은 외부 AI API를 호출하지 않고,
   `.pointpatch/feedback-sessions/` 아래에 local handoff batch를 기록한다.

8. AI agent가 `pointpatch_read_feedback`으로 compact Markdown과 complete JSON을
   읽는다. Markdown은 request, target evidence, likely source 중심이고 JSON은
   batch, item, screen, selected node, source candidate, screenshot path를 보존한다.

9. AI agent가 source candidate를 참고해 코드 수정하고,
   `pointpatch_verify_ui_change` 또는 console capture로 결과를 확인한다.
```

### 7.3 반복 UI 시나리오

```text
1. 사용자가 LazyColumn의 세 번째 카드에 있는 "신청" 버튼만 수정하고 싶다.
2. PointPatch에서 해당 버튼을 직접 선택한다.
3. output에는 selectedNode bounds, nearby text, parent/sibling context가 포함된다.
4. AI agent는 공통 "신청" 문구 전체가 아니라 해당 item renderer/context를 고려한다.
```

### 7.4 시각적 수정 시나리오

```text
1. 사용자가 카드 아래 여백이 너무 크다고 느낀다.
2. 해당 카드나 여백 근처를 선택한다.
3. PointPatch는 선택 좌표, 후보 nodes, screenshot crop, 주변 nodes를 export한다.
4. AI agent는 source candidates와 screenshot을 보고 padding/Spacer/Arrangement 후보를 찾는다.
```

### 7.5 아이콘 버튼 시나리오

```text
1. 사용자가 X 아이콘의 클릭 영역을 키우고 싶다.
2. 아이콘 버튼을 탭한다.
3. contentDescription이 있으면 "닫기" 같은 의미가 export된다.
4. contentDescription이 없어도 bounds, screenshot crop, coordinate가 export된다.
5. AI agent는 IconButton, Modifier.size, touch target 관련 코드를 찾는다.
```

---

## 8. UX 요구사항

### 8.1 설치 UX

#### 권장 설치 after artifacts are published

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

위 versioned plugin coordinate는 artifacts가 publish된 뒤의 외부 프로젝트 설치 예시다. 현재 repo-local V1 검증은 composite build/settings wiring으로 plugin build를 포함한다.

Gradle plugin은 다음을 자동으로 처리한다.

- debug runtime dependency 추가
- source index task 등록
- debug APK에 source index asset 포함
- release build에는 runtime 미포함
- project metadata 생성

#### 직접 dependency 설치

```kotlin
dependencies {
    debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
}
```

이 coordinate는 publish된 artifact가 있을 때의 예시다. 현재 repo sample은 composite build/project dependency wiring을 사용하고, 외부 프로젝트는 artifact publish 전에는 동일하게 명시적 wiring이 필요하다.

이 방식은 source index가 없을 수 있지만, runtime inspection, screenshot, clipboard export는 동작해야 한다.

### 8.2 앱 안 기본 UX

앱 화면에 작은 floating button이 표시된다.

버튼 이름:

```text
PointPatch
```

메뉴:

```text
- Select UI
- Recent
- Connect AI Agent
```

v1에서는 `Select UI`가 핵심이다. `Recent`, `Connect AI Agent`는 v1.1 또는 v2에서 확장 가능하다.

### 8.3 Selection UX

선택 모드 진입 시:

```text
- 전체 화면 selection layer 표시
- 안내 문구: "수정할 UI를 탭하세요"
- 탭하면 overlay가 좌표를 수집
- Semantics tree에서 후보 nodes 추출
- selected node highlight
- top candidates optional 표시
- comment sheet 표시
```

### 8.4 Comment UX

comment sheet 필드:

- 변경 요청 입력창
- selected node 요약
- screenshot crop preview
- source candidates 요약
- Copy for Codex
- Copy for Claude Code
- Copy for Cursor
- Copy Markdown
- Copy JSON
- Share

기본 버튼은 `Copy for AI` 또는 `Copy for Codex`로 한다.

### 8.5 MCP 연결 UX

앱 메뉴의 `Connect AI Agent`에서 연결 상태를 보여준다.

```text
PointPatch AI Connection

App sidekick: Ready
Device: Pixel 8
Package: com.example.app
MCP: Not connected

Run:
pointpatch setup --package <applicationId>
```

버튼:

- Copy setup command
- Copy MCP config
- Run doctor guide
- Open docs

MCP가 연결되면:

```text
MCP: Connected
Client: Cursor
Last request: 12 seconds ago
```

### 8.6 CLI UX

V1에서 사용자가 쓰는 CLI 명령은 구현된 surface로 제한한다.

```bash
pointpatch status
pointpatch setup --package <applicationId>
pointpatch run
pointpatch doctor
pointpatch console --package <applicationId>
```

내부 MCP server 명령은 client config에서 사용된다.

```bash
pointpatch mcp
```

V1은 설치/수정용 init 명령을 제공하지 않는다. 이 repo에서는 Gradle plugin이 composite build/settings wiring으로 포함되어 있고, 외부 프로젝트는 publish된 plugin coordinate 또는 명시적인 composite build/pluginManagement 설정을 사용해야 한다.

현재 repo의 sample app은 Gradle project `:app`으로 노출되고 source는 `sample/` 아래에 둔다. 따라서 local smoke flow와 `pointpatch run`의 기본 install task는 `:app:installDebug`다.

#### `pointpatch setup`

AI client에 붙여 넣을 MCP 설정 JSON을 출력한다.

```text
- --package 값 또는 .pointpatch/project.json의 package/project metadata 확인
- pointpatch mcp command와 args를 포함한 JSON config 출력
```

#### `pointpatch run`

debug 앱을 빌드/설치/실행한다.

```text
- device/emulator 확인
- repo sample Gradle task 실행 (`:app:installDebug`)
- debug build
- install
- launch
- sidekick handshake 확인
```

#### `pointpatch doctor`

문제 진단.

```text
- Android project 여부
- PointPatch project metadata 여부
- ADB 여부
- device 연결 여부
- sidekick session/bridge status 여부
```

#### `pointpatch console`

MCP client 없이도 같은 local feedback console을 연다.

```text
- package/project metadata 확인
- MCP-owned feedback session open 또는 resume
- localhost console URL 출력
- Studio UI에서 live preview navigation, Add freeze, Save evidence batch,
  Copy/Send handoff workflow 제공
```

---

## 9. 기능 요구사항

### 9.1 Runtime sidekick

`pointpatch-compose-sidekick`은 debug APK 안에 포함되는 Android runtime이다.

책임:

- AndroidX Startup으로 자동 초기화
- debuggable build guard
- ActivityLifecycleCallbacks 등록
- 현재 Activity 감지
- decorView에 overlay host attach
- Compose root 탐색
- Semantics tree dump
- node selection
- screenshot capture
- clipboard/file export
- local bridge for MCP

### 9.2 Compose root discovery

Activity `decorView`를 traversal하여 `RootForTest` 구현체를 찾는다.

요구사항:

- overlay 자신의 Compose root는 제외
- 여러 Compose root 지원
- Dialog/Popup 고려
- selected tap coordinate가 포함되는 root 우선
- root가 없으면 screenshot/coordinate fallback

### 9.3 Semantics inspection

각 root에서 다음을 수집한다.

- merged semantics tree
- unmerged semantics tree

수집할 node property:

- node id
- tree kind
- boundsInWindow
- text
- editableText
- contentDescription
- role
- testTag
- stateDescription
- selected/checked state
- enabled/disabled
- click/longClick/setText/scroll action
- password 여부
- path
- safe raw properties

### 9.4 Node selection

입력:

- tap coordinate
- semantics nodes
- options

출력:

- selectedNode
- candidatesAtPoint
- nearbyNodes
- score breakdown

기본 점수 기준:

- tap 좌표를 포함하는 node만 후보
- click action 있으면 가산
- text/contentDescription/role/testTag 있으면 가산
- 너무 큰 root container는 감점
- semantic 정보 없는 node는 감점
- 작은 bounds 선호
- merged tree action node 선호
- 동률 시 작은 area, deeper node 순

### 9.5 Nearby context

선택 node 주변의 의미 있는 nodes를 수집한다.

조건:

- 같은 root
- text/contentDescription/role/action이 있음
- selected node와 가까운 위치
- 중복 제거
- 최대 12개 기본값

### 9.6 Screenshot capture

요구사항:

- full screenshot 저장
- selected bounds 기준 crop 저장
- 실패해도 annotation export 계속 진행
- API 26 이상에서는 PixelCopy 우선
- fallback으로 decorView.draw(canvas)
- 파일은 cache directory에 저장

권장 저장 경로:

```text
context.cacheDir/pointpatch/YYYY-MM-DD/<annotation-id>-full.png
context.cacheDir/pointpatch/YYYY-MM-DD/<annotation-id>-crop.png
```

### 9.7 Source index

Gradle plugin이 debug variant 기준 source index를 생성한다.

Source index 대상:

- Kotlin source
- Java source는 후순위
- strings.xml
- string-array/plurals basic support
- Compose 관련 literal
- `Text("...")`
- `stringResource(R.string.xxx)`
- `Modifier.testTag("...")`
- `@Composable` function names
- file path, line number, excerpt

Runtime matching 기준:

- selected text exact match
- nearby text match
- contentDescription match
- testTag match
- screen/activity name match
- role/composable name match
- fuzzy partial match

Source index가 없어도 search hints는 export한다.

### 9.8 Source info provider

DTA류 도구에서 참고할 수 있는 `CompositionData`/sourceInfo 기반 source mapping은 optional enhancement로 둔다.

Provider 구조:

```text
SourceInfoProvider
  - NoopSourceInfoProvider
  - SourceIndexProvider
  - ComposeInspectionSourceInfoProvider
```

v1 core는 source index 중심이다. Compose internal reflection/sourceInfo는 실패해도 전체 기능에 영향이 없어야 한다.

### 9.9 Export formats

지원 format:

- Markdown
- JSON
- MCP tool response

Markdown에는 반드시 포함한다.

- user request
- selected UI node
- candidates at point
- nearby context
- source candidates
- search hints
- screenshots
- raw JSON optional

### 9.10 MCP server

MCP는 desktop process로 실행한다.

구조:

```text
AI client
  ↔ pointpatch mcp
  ↔ ADB/local bridge
  ↔ debug app sidekick
  ↔ Compose runtime
```

MCP server는 앱 안에 직접 넣지 않는다.

#### MCP tools

기존 single-capture tool은 compatibility 용도로 유지하고, 새 workflow는
feedback console session queue를 중심으로 한다.

1. `pointpatch_get_ui_feedback`
2. `pointpatch_get_current_screen`
3. `pointpatch_verify_ui_change`
4. `pointpatch_status`
5. `pointpatch_open_feedback_console`
6. `pointpatch_list_feedback_sessions`
7. `pointpatch_capture_screen`
8. `pointpatch_navigate_app`
9. `pointpatch_list_feedback`
10. `pointpatch_read_feedback`
11. `pointpatch_resolve_feedback`

#### `pointpatch_get_ui_feedback`

Compatibility single-feedback wrapper다. 새 agent workflow에서는
`pointpatch_open_feedback_console`과 feedback queue tools를 우선한다.

동작:

```text
1. 앱 selection overlay 활성화
2. 사용자가 UI 탭
3. 앱 comment sheet 표시
4. 사용자가 원하는 변경사항 입력
5. selected node + context + source candidates 반환
```

#### `pointpatch_open_feedback_console`

동작:

```text
1. active 또는 persisted feedback session open/resume
2. local browser console URL 반환
3. console이 session state와 screenshot artifacts를 MCP process 안에서 소유
```

Console workflow:

```text
Studio workspace에서 active ADB device 선택
→ live preview canvas로 앱 탐색
→ Add로 최신 preview freeze
→ component click 또는 custom drag area 선택
→ comment 입력 후 Add to Pending으로 여러 pending item 추가
→ Save 한 번으로 evidence snapshot 1개와 item N개 저장
→ Copy로 compact Markdown 복사 또는 Send로 persisted handoff batch 생성
→ saved evidence group과 pointpatch_read_feedback에서 batch 확인
```

별도 Select/Navigate toggle은 없다. idle preview click은 navigation이고,
navigation은 debug-only `back`, `tap`, `swipe` one-step action만 수행한다.

#### `pointpatch_get_current_screen`

현재 화면 summary 반환.

- activity
- compose roots
- semantics nodes
- screenshot optional
- source index availability

#### `pointpatch_verify_ui_change`

수정 후 화면 검증.

입력 예:

```json
{
  "expectedText": "바로 결제하기"
}
```

출력:

```json
{
  "found": true,
  "matchingNodes": []
}
```

#### `pointpatch_status`

연결 상태 확인.

- deviceConnected
- packageName
- appRunning
- sidekickConnected
- currentActivity
- composeRoots
- sourceIndexAvailable

---

## 10. Output schema

### 10.1 Annotation model

```json
{
  "schemaVersion": "1.0",
  "id": "pp_20260503_143012_8f4b",
  "platform": "android-compose",
  "createdAtEpochMillis": 1777786212000,
  "app": {
    "packageName": "com.example.app",
    "versionName": "1.0-debug",
    "versionCode": 1,
    "debuggable": true
  },
  "activity": {
    "className": "com.example.MainActivity"
  },
  "tap": {
    "xInWindow": 512,
    "yInWindow": 1430
  },
  "selection": {
    "kind": "SEMANTICS_NODE",
    "confidence": "HIGH",
    "selectedUid": "0:MERGED:142",
    "source": "TAP_SELECT"
  },
  "selectedNode": {
    "uid": "0:MERGED:142",
    "treeKind": "MERGED",
    "role": "Button",
    "text": ["결제하기"],
    "contentDescription": [],
    "boundsInWindow": {
      "left": 48,
      "top": 1360,
      "right": 1032,
      "bottom": 1496
    },
    "actions": ["OnClick"],
    "enabled": true
  },
  "candidatesAtPoint": [],
  "nearbyNodes": [],
  "sourceCandidates": [],
  "searchHints": [],
  "screenshot": {
    "fullPath": "...",
    "cropPath": "...",
    "desktopFullPath": ".pointpatch/artifacts/pp_20260503_143012_8f4b/pp_20260503_143012_8f4b-full.png",
    "desktopCropPath": ".pointpatch/artifacts/pp_20260503_143012_8f4b/pp_20260503_143012_8f4b-crop.png"
  },
  "userComment": "문구를 바로 결제하기로 바꿔줘.",
  "errors": []
}
```

필수 필드는 `schemaVersion`, `id`, `createdAtEpochMillis`, `platform`, `app.packageName`, `activity.className`, `tap`, `selection.kind`, `selection.confidence`, `selection.source`, `userComment`, `errors`다. `selectedNode`, `candidatesAtPoint`, `scopeCandidates`, `nearbyNodes`, `sourceCandidates`, `searchHints`, `screenshot`, `selection.selectedUid`, `selection.areaBoundsInWindow`는 runtime 상황에 따라 비어 있을 수 있다.

### 10.2 Markdown format

```md
# PointPatch Compose Feedback

## User request

문구를 "Send to agent"로 바꿔줘.

## Selected UI

- Platform: Android Compose
- Activity: io.beyondwin.fixthis.sample.MainActivity
- Role: Button
- Text: Submit request
- Bounds: left=48, top=1360, right=1032, bottom=1496
- Actions: OnClick
- Tree: MERGED

## Nearby context

- Agent handoff
- Include screenshot context

## Source candidates

1. `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt:143`
   - matched: Submit request, Agent handoff

## Search hints

- "Submit request"
- "Agent handoff"
- "Button"
- "MainActivity"

## Screenshot

- full: `/data/user/0/.../pointpatch/...-full.png`
- crop: `/data/user/0/.../pointpatch/...-crop.png`
```

---

## 11. Privacy and security requirements

### 11.1 기본 정책

- no permissions by default
- no AccessibilityService
- no network by default
- no default HTTP server in app
- local clipboard/file export
- MCP는 desktop process에서 stdio 기반으로 실행
- app sidekick과 desktop MCP server는 ADB/local bridge로 연결
- screenshot은 cache에 저장
- sensitive text redaction

### 11.2 Redaction

기본값:

```text
redactPassword = true
redactEditableText = true
includeRawProperties = false
```

규칙:

- password semantics는 항상 redacted
- editable text는 기본 redacted
- screenshot은 자동 redaction을 보장하지 않음
- README와 privacy 문서에 screenshot 주의사항 명시

### 11.3 Release safety

세 가지 레벨에서 방어한다.

1. Gradle plugin은 debug variant에만 runtime dependency 추가
2. sidekick initializer는 `ApplicationInfo.FLAG_DEBUGGABLE` 검사
3. release build에서 overlay/MCP bridge가 절대 실행되지 않음

---

## 12. Technical architecture

### 12.1 Modules

```text
pointpatch-compose-core
  - pure domain models and repository contracts
  - annotation/snapshot/session use cases
  - legacy annotation export models
  - node selector
  - formatter
  - source matcher

pointpatch-compose-sidekick
  - AndroidX Startup
  - ActivityLifecycleCallbacks
  - overlay installer
  - screenshot capture
  - local bridge

pointpatch-compose-overlay
  - toolbar
  - selection layer
  - highlight
  - comment sheet
  - public Compose Studio shell
  - Studio theme/canvas/toolbar components

pointpatch-gradle-plugin
  - debug dependency injection
  - source index generation
  - project metadata generation

pointpatch-cli
  - status
  - setup
  - run
  - doctor
  - mcp

pointpatch-mcp
  - stdio MCP server
  - tool/resource definitions
  - bridge to Android sidekick
  - local feedback console server
  - feedback session DTOs, persistence, mapper boundaries

sample
  - sample Android Compose app
```

### 12.2 Runtime flow

```text
AndroidX Startup
  ↓
PointPatchInitializer
  ↓
debuggable guard
  ↓
PointPatch.install(application)
  ↓
ActivityLifecycleCallbacks
  ↓
onActivityResumed
  ↓
attach overlay to decorView
  ↓
user selects UI
  ↓
find RootForTest views
  ↓
read SemanticsOwner
  ↓
collect merged/unmerged nodes
  ↓
select best node by coordinate
  ↓
collect nearby context
  ↓
capture screenshot/crop
  ↓
match source candidates
  ↓
export annotation
```

### 12.3 MCP flow

```text
AI client
  ↓ stdio
pointpatch mcp
  ↓ adb/local bridge
debug app sidekick
  ↓
Compose semantics/screenshot/source index
  ↓
pointpatch mcp result
  ↓
AI client edits repo
```

---

## 13. 경쟁/레퍼런스 분석 요약

### 13.1 DTA

DTA는 Android AI debugging platform에 가깝다. AI가 Android device를 inspect, interact, mock, verify할 수 있게 한다.

가져올 점:

- sidekick auto-inject
- AndroidX Startup
- MCP/CLI 구조
- screenshot + layout tree
- click-to-select
- sourceInfo/CompositionData 아이디어

가져오지 않을 점:

- network recording
- WebSocket/mock
- JVMTI/native hooks
- default server/permission
- 너무 큰 platform scope

### 13.2 mobile-mcp

가져올 점:

- structured data first
- screenshot fallback
- multi-client MCP setup examples

### 13.3 Appium MCP

가져올 점:

- stable locator first
- vision/coordinate fallback last
- tool strategy 문서화

### 13.4 Compose Buddy

가져올 점:

- Compose-specific hierarchy
- source location
- screenshot + metadata
- preview/CI 가능성

PointPatch의 차별점:

```text
Compose UI 수정 요청을 위한 human-selected runtime context MCP
```

---

## 14. Success metrics

### 14.1 Product metrics

- 첫 annotation 생성까지 걸리는 시간
- Gradle plugin 설치 성공률
- sidekick auto-init 성공률
- selected node detection 성공률
- screenshot capture 성공률
- source candidate match rate
- MCP setup 성공률
- MCP tool call 성공률
- doctor로 해결 가능한 failure 비율

### 14.2 User outcome metrics

- AI가 첫 시도에서 올바른 UI 코드 수정한 비율
- 잘못된 UI 수정 감소율
- 반복 UI에서 정확한 item 수정 성공률
- 수정 후 verify 성공률
- 사용자가 수동으로 추가 설명해야 한 횟수

### 14.3 Qualitative metrics

- “testTag를 붙이지 않아도 쓸 수 있다” 체감
- “MCP를 몰라도 사용할 수 있다” 체감
- “AI가 내가 말한 UI를 정확히 이해한다” 체감

---

## 15. Release plan

### Phase 1: In-app annotation MVP

목표:

- debug dependency install
- AndroidX Startup autoinit
- overlay
- UI selection
- semantics inspection
- screenshot capture
- Markdown/JSON clipboard export

### Phase 2: Gradle plugin + source index

목표:

- plugin install
- source index generation
- source candidates
- project metadata
- release safety

### Phase 3: CLI

목표:

- `pointpatch status`
- `pointpatch run`
- `pointpatch setup`
- `pointpatch doctor`
- `pointpatch console`

### Phase 4: MCP

목표:

- stdio MCP server
- `pointpatch_get_ui_feedback` compatibility wrapper
- `pointpatch_get_current_screen`
- `pointpatch_verify_ui_change`
- `pointpatch_status`
- `pointpatch_open_feedback_console`
- `pointpatch_list_feedback_sessions`
- `pointpatch_capture_screen`
- `pointpatch_navigate_app`
- `pointpatch_list_feedback`
- `pointpatch_read_feedback`
- `pointpatch_resolve_feedback`
- MCP client setup JSON output

### Phase 5: Advanced source mapping

목표:

- Compose sourceInfo provider
- CompositionData/source information exploration
- source candidate accuracy improvements

### Phase 6: Verification and optional interaction

목표:

- verify node/text/state
- optional tap/input/swipe tools
- human confirmation for destructive or state-changing actions

---

## 16. Risks and mitigations

### Risk 1: Compose API compatibility

Semantics and `RootForTest` behavior may vary by Compose version.

Mitigation:

- support matrix 문서화
- sample matrix 테스트
- fallback to screenshot/coordinate
- no hard failure

### Risk 2: Semantics 없는 UI

Canvas-only/custom UI는 node가 부족할 수 있다.

Mitigation:

- screenshot crop fallback
- candidatesAtPoint export
- README에 limitation 명시
- optional semantics recommendation 제공

### Risk 3: Source candidate 부정확

Runtime node와 source code가 직접 연결되지 않을 수 있다.

Mitigation:

- source index + search hints
- sourceInfo provider optional
- top 5 candidates export
- exact line guarantee를 약속하지 않음

### Risk 4: MCP setup friction

MCP 설정은 사용자에게 어렵다.

Mitigation:

- `pointpatch setup`
- `--package` 또는 `.pointpatch/project.json` 기반 setup JSON 출력
- agent workflow 단위 MCP tools
- `doctor` 제공
- 앱 안 connect guide 제공

### Risk 5: Privacy concern

screenshot/runtime text 수집이 민감할 수 있다.

Mitigation:

- debug-only
- local-first
- no network default
- redaction
- privacy docs
- screenshot warning

---

## 17. Acceptance criteria

### 17.1 Basic UX

- debug app에서 floating PointPatch button이 보인다.
- Select UI를 누르면 selection layer가 표시된다.
- Button/Text/TextField/Switch/Checkbox/LazyColumn item을 선택할 수 있다.
- 선택 후 comment sheet가 표시된다.
- Copy Markdown이 정상 동작한다.
- Markdown에는 selected node, nearby context, screenshot path가 포함된다.

### 17.2 Technical

- overlay 자기 자신이 selection target으로 잡히지 않는다.
- selected node가 없더라도 annotation이 생성된다.
- password/editable text redaction이 적용된다.
- screenshot 실패 시에도 annotation export가 실패하지 않는다.
- release build에서 sidekick이 실행되지 않는다.

### 17.3 MCP

- `pointpatch status`가 sidekick 연결 상태를 표시한다.
- `pointpatch setup --package <applicationId>` 또는 기존 `.pointpatch/project.json` 기반 setup이 MCP config를 출력한다.
- `pointpatch_open_feedback_console`이 browser feedback console URL을 반환한다.
- feedback console 기본 상태는 navigation이며 Add로 최신 preview를 freeze할 수 있다.
- 한 frozen preview에서 component click 또는 custom drag area로 pending item 여러 개를 만들 수 있다.
- Save 한 번으로 evidence snapshot 1개와 item N개가 저장되고 같은 `screenId`를 공유한다.
- Send 이후 `pointpatch_read_feedback`이 compact Markdown과 complete JSON handoff batch를 반환한다.
- `pointpatch_verify_ui_change`가 expected text 존재 여부를 검사한다.

---

## 18. README 핵심 문구

```md
# PointPatch for Android Compose

PointPatch lets you point at Jetpack Compose UI in a debug Android app, describe what you want changed, and send precise runtime context to AI coding agents.

Android Jetpack Compose only. Debug builds only. No required testTags. No AccessibilityService.
```

한국어:

```md
# PointPatch for Android Compose

PointPatch는 Android Jetpack Compose debug 앱에서 수정할 UI를 지목하고 원하는 변경사항을 입력하면, AI 코딩 에이전트가 코드를 수정할 수 있는 정확한 runtime context를 생성합니다.

Android Jetpack Compose 전용입니다. Debug build 전용이며, 모든 Composable에 testTag를 붙일 필요가 없고 AccessibilityService를 사용하지 않습니다.
```

---

## 19. 최종 요약

PointPatch for Android Compose는 “모바일 자동화 MCP”가 아니라 “Compose UI 수정 요청용 runtime context 도구”다.

가장 중요한 UX는 다음이다.

```text
Point at UI → Describe the change → AI gets exact context → AI patches code
```

제품은 처음부터 MCP만으로 설계하지 않는다. 앱 안 annotation UX를 기본으로 제공하고, MCP는 고급 AI agent 연결로 제공한다.

최종 제품은 다음 특징을 가져야 한다.

```text
- Android Jetpack Compose only
- debug-only
- no required testTags
- no AccessibilityService
- one-line install
- in-app UI selection
- comment input
- screenshot crop
- semantics context
- source candidates
- clipboard/export
- optional MCP
- easy CLI setup
```
