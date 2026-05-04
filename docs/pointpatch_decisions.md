# PointPatch for Android Compose — 주요 결정사항 및 근거

**문서 목적:** 이 문서는 PointPatch for Android Compose의 주요 제품/기술 의사결정을 기록한다. 각 결정이 왜 내려졌는지, 어떤 대안을 검토했는지, 무엇을 포기했는지를 명확히 남긴다.  
**문서 버전:** v1.0  
**작성일:** 2026-05-03

---

## 1. 최종 방향 요약

PointPatch는 Android Jetpack Compose debug 앱에서 사용자가 수정할 UI를 직접 지목하고, 원하는 변경사항을 입력하면, AI 코딩 에이전트에게 정확한 runtime context를 전달하는 도구다.

최종 방향:

```text
PointPatch for Android Compose
  = in-app UI annotation first
  + AI context export
  + optional MCP connection
```

핵심 결정:

- Android Jetpack Compose만 지원한다.
- debug build 전용으로 만든다.
- `testTag`를 필수로 요구하지 않는다.
- AccessibilityService를 사용하지 않는다.
- Kotlin compiler plugin을 core에 넣지 않는다.
- Compose semantics tree를 core runtime signal로 사용한다.
- AndroidX Startup과 ActivityLifecycleCallbacks로 자동 설치한다.
- 앱 안 annotation UX를 기본 UX로 둔다.
- MCP는 optional advanced workflow로 둔다.
- source mapping은 Gradle source index부터 시작한다.
- privacy-first, local-first로 설계한다.

---

## 2. 결정 1: 이름을 “PointPatch for Android Compose”로 정한다

### 결정

제품명은 다음으로 정한다.

```text
PointPatch for Android Compose
```

짧은 이름:

```text
PointPatch Compose
```

CLI:

```bash
pointpatch
```

### 구현 순서 이유

제품의 본질은 다음 흐름이다.

```text
수정할 UI를 지목한다.
원하는 변경사항을 말한다.
AI가 해당 context를 바탕으로 코드를 patch한다.
```

`PointPatch`는 이 흐름을 잘 담는다.

- `Point`: 사용자가 UI를 지목한다.
- `Patch`: AI가 코드 수정으로 이어진다.
- `for Android Compose`: 지원 범위가 Android Jetpack Compose임을 명확히 한다.

### 검토한 대안

| 이름 | 장점 | 탈락/후순위 이유 |
|---|---|---|
| Agentation Compose | 기존 웹 Agentation과 연결감 있음 | 공식 확장처럼 보일 수 있어 혼동 가능 |
| TapCue | 짧고 직관적 | “원하는 걸 말한다/수정한다” 뉘앙스가 약함 |
| TapTell | 탭하고 말한다는 UX가 좋음 | 개발자 도구/patch 느낌이 약함 |
| PointPrompt | AI prompt 생성 느낌이 강함 | 코드 수정/patch 의미가 약함 |
| TapToFix | 매우 직관적 | 너무 일반적이고 자동 수정 제품처럼 들릴 수 있음 |
| ComposePoint | Compose-only가 명확함 | patch/change action이 약함 |

### 결과

PointPatch는 사용자 행동과 제품 결과를 모두 담는다.

```text
Point at Compose UI. Tell AI what to patch.
```

---

## 3. 결정 2: Android Jetpack Compose 전용으로 범위를 제한한다

### 결정

PointPatch는 Android Jetpack Compose만 지원한다.

지원하지 않는 것:

- XML/View UI
- WebView DOM
- React Native
- Flutter
- iOS
- 타 앱 inspection

### 이유

처음에는 Android 전체 또는 웹 Agentation의 Android 버전을 생각할 수 있었다. 하지만 Android UI에는 View/XML, Compose, WebView 등 여러 계층이 있고, 각각 inspection 방식이 다르다.

Compose-only로 제한하면 다음 장점이 있다.

1. Compose semantics tree라는 명확한 기반을 사용할 수 있다.
2. 제품 메시지가 단순해진다.
3. 설치와 UX를 단순하게 유지할 수 있다.
4. 오픈소스 초기 버전의 안정성이 올라간다.
5. XML/View id, WebView JS injection, AccessibilityService 같은 분기를 피할 수 있다.
6. “Android Compose UI 수정 요청용 도구”라는 포지션이 선명해진다.

### 포기한 것

- 더 넓은 Android 앱 호환성
- XML/View 프로젝트 사용성
- WebView 내부 DOM selection

### 근거

제품의 핵심은 “Compose UI를 지목해 AI에게 수정 context를 전달”하는 것이다. Android 전체 inspector가 되려 하면 DTA, Appium MCP, mobile-mcp 같은 큰 자동화 플랫폼과 겹치고 제품 범위가 흐려진다.

---

## 4. 결정 3: Debug-only 도구로 만든다

### 결정

PointPatch는 debug/dev utility다. production feature가 아니다.

### 이유

PointPatch는 화면 텍스트, screenshot, UI tree, source hints를 다룬다. 이는 민감한 정보를 포함할 수 있다. 따라서 production 앱에 들어가거나 일반 사용자에게 노출되어서는 안 된다.

### 안전장치

1. Gradle plugin은 debug variant에만 runtime dependency를 넣는다.
2. 직접 설치도 `debugImplementation`만 문서화한다.
3. sidekick initializer에서 `ApplicationInfo.FLAG_DEBUGGABLE`을 확인한다.
4. release build에서는 overlay와 bridge가 실행되지 않는다.
5. 네트워크 export는 기본 비활성화다.

### 대안

| 대안 | 문제 |
|---|---|
| release에서도 optional enable | privacy/security 부담 증가 |
| product analytics/feedback tool로 확장 | 제품 목적이 흐려짐 |
| feature flag로 production 사용 허용 | 오픈소스 신뢰성 저하 가능 |

### 결론

Debug-only는 제한이 아니라 제품 정체성이다.

---

## 5. 결정 4: 모든 Composable에 `testTag`를 요구하지 않는다

### 결정

`testTag`는 optional signal로만 사용한다. 필수 요구사항으로 삼지 않는다.

### 이유

처음에는 Compose에서 UI를 식별하려면 `Modifier.testTag()`를 붙이는 방식이 떠오를 수 있다.

예:

```kotlin
Button(
    modifier = Modifier.testTag("checkout/payButton"),
    onClick = onPayClick
) {
    Text("결제하기")
}
```

이 방식은 정확하지만 현실성이 낮다.

문제:

- 기존 프로젝트 전체에 tag를 붙이는 것은 비현실적이다.
- adoption을 크게 떨어뜨린다.
- “기본 프로젝트에 tag를 다 붙여야 한다”는 불만이 즉시 생긴다.
- 디자이너/PM/QA가 사용할 수 있는 가벼운 tool이 되기 어렵다.

### 선택한 방식

기본 signal:

- Compose semantics text
- contentDescription
- role
- click/setText action
- bounds
- nearby context
- screenshot crop
- source hints

Optional signal:

- testTag
- custom semantics
- sourceInfo
- source index

### 결과

PointPatch는 tag 없는 기본 Compose UI에서도 동작해야 한다.

---

## 6. 결정 5: AccessibilityService를 사용하지 않는다

### 결정

AccessibilityService는 core에서 사용하지 않는다.

### 이유

AccessibilityService는 앱 코드 수정 없이 UI tree를 얻을 수 있는 강력한 방법이다. 하지만 PointPatch의 목적에는 과하다.

문제:

1. 사용자가 Android 설정에서 접근성 권한을 켜야 한다.
2. 타 앱 화면에도 접근할 수 있어 privacy 부담이 크다.
3. 오픈소스 debug utility의 신뢰도를 떨어뜨릴 수 있다.
4. “권한 없는 zero-config” 목표와 충돌한다.
5. Compose-only 앱 내부 도구라는 범위를 벗어난다.

### 대안

| 대안 | 장점 | 문제 |
|---|---|---|
| AccessibilityService | 앱 코드 수정 없이 UI tree 가능 | 권한, privacy, 타 앱 접근 |
| App 내부 sidekick | 권한 없음, debug-only 적합 | debug APK에 dependency 필요 |
| ADB/UIAutomator | 외부 자동화 가능 | setup과 device control 부담 |

### 결론

PointPatch는 앱 내부 debug sidekick으로 동작한다. 타 앱 inspector가 아니다.

---

## 7. 결정 6: Compose semantics tree를 core signal로 사용한다

### 결정

PointPatch는 Compose `SemanticsOwner`의 merged/unmerged semantics tree를 읽어 UI node context를 만든다.

### 이유

Compose semantics는 UI 요소의 의미를 표현한다. AI에게 필요한 정보도 대부분 의미 정보다.

수집 가능한 정보:

- text
- contentDescription
- role
- stateDescription
- selected/checked state
- enabled/disabled
- click/longClick/setText/scroll action
- password/editable 여부
- boundsInWindow
- testTag가 있으면 testTag

이 정보는 “사용자가 지목한 UI가 무엇인지” 설명하는 데 충분히 유용하다.

### 대안

| 대안 | 문제 |
|---|---|
| screenshot-only | 코드 위치를 찾기 어렵고 모호함 |
| layout tree internal reflection only | Compose version compatibility risk |
| compiler plugin metadata | 구현/유지보수 부담 큼 |
| testTag only | 수동 태깅 부담 큼 |

### 결론

Semantics tree는 안정성과 유용성의 균형이 가장 좋다. 단, semantics 없는 custom UI에는 screenshot/coordinate fallback을 제공한다.

---

## 8. 결정 7: merged tree와 unmerged tree를 모두 읽는다

### 결정

PointPatch는 merged semantics tree와 unmerged semantics tree를 모두 수집한다.

### 이유

두 tree는 서로 다른 장점이 있다.

#### Merged tree

- 사용자가 실제로 보는 의미 단위에 가깝다.
- Button 내부 Text가 Button node에 병합될 수 있다.
- click action이 있는 상위 의미 node를 찾기 좋다.

#### Unmerged tree

- 내부 Text나 세부 semantics를 더 잘 볼 수 있다.
- source search hint 수집에 유리하다.
- 후보 node 분석에 도움이 된다.

### 결과

선택 결과는 다음을 포함한다.

- selectedNode
- candidatesAtPoint
- nearbyNodes
- treeKind: MERGED or UNMERGED

### 이유

단일 selector에 의존하지 않고 후보 정보를 같이 제공하면, node 선택이 완벽하지 않아도 AI가 context를 이해할 수 있다.

---

## 9. 결정 8: `RootForTest`와 `SemanticsOwner`를 사용한다

### 결정

Activity decorView를 traversal해 `RootForTest` 구현체를 찾고, 그 안의 `semanticsOwner`를 읽는다.

### 이유

이 방식은 앱 코드 변경 없이 실행 중인 Compose root를 찾는 현실적인 방법이다.

흐름:

```text
Activity decorView traversal
  ↓
RootForTest 구현 View 찾기
  ↓
rootForTest.semanticsOwner
  ↓
merged/unmerged semantics nodes
```

장점:

- `setContent`를 wrapper로 감싸지 않아도 된다.
- 앱 코드 수정 없이 runtime inspection이 가능하다.
- Compose semantics 구조와 자연스럽게 맞는다.
- debug-only tool 목적과 맞다.

### 위험

`RootForTest`는 testing/introspection 성격이 강하다. Compose version에 따라 변화 가능성이 있다.

### 완화

- debug-only로 제한
- compatibility tests
- screenshot/coordinate fallback
- no hard failure
- README에 limitation 명시

---

## 10. 결정 9: `PointPatchRoot {}` wrapper는 기본 UX에서 제외한다

### 결정

`PointPatchRoot` 같은 wrapper는 fallback API로만 제공한다. 기본 UX는 autoinit이다.

### 이유

초기 구현으로는 다음 방식이 쉽다.

```kotlin
setContent {
    PointPatchRoot {
        App()
    }
}
```

하지만 이 방식은 앱 코드 수정이 필요하다. 사용자는 설정할 게 많다고 느낀다.

최종 UX는 다음이어야 한다.

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

또는, artifact가 publish된 이후:

```kotlin
debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
```

현재 V1 repo sample은 composite build/project dependency wiring을 사용한다. 외부 프로젝트는 artifact publish 전에는 이 coordinate를 바로 사용할 수 없고 명시적 wiring이 필요하다.

### 결론

Primary path:

- Gradle plugin
- debug dependency
- AndroidX Startup autoinit

Fallback path:

- `PointPatch.install(application)`
- `PointPatchRoot {}`

---

## 11. 결정 10: AndroidX Startup으로 자동 초기화한다

### 결정

`pointpatch-compose-sidekick`은 AndroidX Startup initializer를 포함한다.

### 이유

사용자가 앱 코드에 `PointPatch.install()`을 직접 넣지 않아도 되게 하려면 auto-init이 필요하다.

AndroidX Startup은 library component 초기화에 적합하다.

흐름:

```text
Manifest metadata
  ↓
InitializationProvider
  ↓
PointPatchInitializer
  ↓
debuggable guard
  ↓
PointPatch.install(application)
```

### 대안

| 대안 | 문제 |
|---|---|
| Application에서 수동 install | 앱 코드 수정 필요 |
| 직접 ContentProvider 구현 | boilerplate 증가 |
| bytecode injection | 복잡하고 위험 |
| Gradle transform | 유지보수 부담 |

### 결론

AndroidX Startup은 zero-code install에 가장 적합하다.

---

## 12. 결정 11: ActivityLifecycleCallbacks로 overlay를 자동 attach한다

### 결정

`Application.ActivityLifecycleCallbacks`를 등록하고, Activity resume 시 decorView에 overlay를 attach한다.

### 이유

Activity마다 사용자가 install을 호출하지 않아도 된다.

흐름:

```text
PointPatch.install(application)
  ↓
registerActivityLifecycleCallbacks
  ↓
onActivityResumed(activity)
  ↓
attach overlay
```

장점:

- Activity별 수동 작업 없음
- single-activity Compose 앱과 잘 맞음
- multi-activity도 대응 가능
- detach 관리 가능

### 주의점

- overlay 자기 자신이 selection target으로 잡히지 않아야 한다.
- Dialog/Popup root 처리 필요
- full-screen overlay가 평상시 앱 터치를 막지 않아야 한다.

---

## 13. 결정 12: overlay는 평소 full-screen 터치를 먹지 않는다

### 결정

overlay host는 평소 toolbar만 터치 영역을 갖고, selection mode/comment mode에서만 full-screen layer를 표시한다.

### 이유

항상 full-screen ComposeView를 decorView 위에 올리면 앱 본문 터치를 방해할 수 있다.

선택한 구조:

```text
PointPatchOverlayHostLayout
  ├─ Toolbar ComposeView
  ├─ SelectionLayer ComposeView, select mode only
  └─ CommentSheet ComposeView, comment mode only
```

### 결과

- 평소 앱 사용에 영향이 적다.
- selection mode에서는 tap을 명확히 capture한다.
- comment mode에서는 입력 UI가 정상 동작한다.

---

## 14. 결정 13: 앱 안 annotation UX를 기본으로 둔다

### 결정

기본 UX는 MCP가 아니라 앱 안 annotation이다.

```text
앱에서 UI 선택
→ comment 입력
→ Copy for AI
```

### 이유

MCP는 강력하지만 첫 사용 마찰이 크다.

MCP 사용자가 알아야 할 수 있는 것:

- MCP client 설정
- ADB/device 상태
- package name
- project path
- sidekick 설치 여부
- server process
- tool call

반면 앱 안 annotation은 즉시 이해된다.

```text
Point at UI. Describe the change. Copy context.
```

### 결론

MCP는 제품의 advanced integration이다. 기본 경험을 대체하지 않는다.

---

## 15. 결정 14: MCP는 optional advanced workflow로 제공한다

### 결정

MCP는 제공하되, 기본 UX가 아니다.

MCP 구조:

```text
AI client
  ↔ pointpatch mcp
  ↔ ADB/local bridge
  ↔ debug app sidekick
```

### 이유

MCP를 사용하면 AI agent가 실행 중인 앱 화면을 직접 조회하고, 사용자가 선택한 UI context를 바로 받아 코드 수정/검증까지 이어갈 수 있다.

하지만 MCP-only로 가면 UX가 복잡해진다. 따라서 다음 전략을 선택한다.

```text
Default:
앱 안 annotation + clipboard/export

Advanced:
MCP 연결 + AI tool call + runtime verification
```

### MCP의 역할

- current screen inspect
- human UI selection
- annotation creation
- source candidates
- verification

### MCP가 하지 않는 것

- 기본적으로 앱을 마음대로 조작하지 않음
- 기본적으로 code write 하지 않음
- 기본적으로 network/server를 app 안에 열지 않음

---

## 16. 결정 15: MCP server는 Android 앱 안이 아니라 desktop process로 실행한다

### 결정

MCP server는 `pointpatch mcp`라는 desktop process로 실행한다. Android debug app에는 sidekick만 들어간다.

### 이유

MCP의 표준 local integration은 client가 server process를 실행하는 방식과 잘 맞는다. Android 앱 안에 MCP server를 직접 넣으면 다음 문제가 생긴다.

- app 안 HTTP server 필요 가능성
- permission 문제
- network exposure
- authentication/origin 문제
- Android lifecycle과 MCP server lifecycle 결합

선택한 구조는 더 안전하다.

```text
AI client
  ↔ stdio MCP server on desktop
  ↔ ADB/local bridge
  ↔ Android sidekick
```

### 결과

- app core에 network permission 불필요
- MCP 설정은 desktop CLI가 관리
- sidekick은 Compose runtime data 수집에 집중
- 보안/프라이버시 부담 감소

---

## 17. 결정 16: MCP tool 수를 줄이고 macro tool 중심으로 설계한다

### 결정

v1 MCP는 tool을 많이 노출하지 않고 macro tool 중심으로 설계한다.

필수 tools:

- `pointpatch_get_ui_feedback`
- `pointpatch_get_current_screen`
- `pointpatch_verify_ui_change`
- `pointpatch_status`

### 이유

도구가 너무 많으면 AI agent도 헷갈리고, 사용자가 이해하기 어렵다.

나쁜 tool list:

```text
inspect_current_screen
capture_screenshot
select_at_coordinates
start_user_selection
create_annotation
get_source_candidates
read_source_index
highlight_node
clear_highlight
```

좋은 v1 tool list:

```text
get_ui_feedback
get_current_screen
verify_ui_change
status
```

### 핵심 tool

`pointpatch_get_ui_feedback` 하나가 다음을 수행한다.

```text
앱 selection overlay 표시
→ 사용자가 UI 선택
→ comment 입력
→ selected context 반환
```

이 tool 하나로 대부분의 AI 수정 workflow가 가능하다.

---

## 18. 결정 17: CLI로 MCP setup friction을 줄인다

### 결정

`pointpatch` CLI를 제공한다.

명령:

```bash
pointpatch status
pointpatch setup --package <applicationId>
pointpatch run
pointpatch doctor
pointpatch mcp
```

### 이유

MCP는 강력하지만 설정이 어렵다. 사용자에게 MCP config JSON, package name, ADB forward를 직접 다루게 하면 adoption이 낮아진다.

CLI가 해야 할 일:

- Android project 탐색
- app module 탐색
- `--package` 또는 `.pointpatch/project.json`에서 applicationId 확인
- device/emulator 확인
- MCP client config JSON 출력
- debug app build/install/launch
- sidekick 연결 확인
- 문제 진단

### 결과

사용자는 다음 정도만 기억하면 된다.

```bash
pointpatch setup --package <applicationId>
pointpatch run
```

---

## 19. 결정 18: Gradle plugin을 primary install path로 둔다

### 결정

가장 권장하는 설치 방식은 Gradle plugin이다.

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

### 이유

Gradle plugin은 단순 dependency보다 더 많은 것을 자동화할 수 있다.

자동화 항목:

- debug sidekick dependency 추가
- source index generation
- project metadata generation
- release variant exclusion
- MCP/CLI가 읽을 metadata 생성

### 대안

| 방식 | 장점 | 한계 |
|---|---|---|
| debugImplementation | 간단 | source index/project metadata 자동화 어려움 |
| Gradle plugin | UX 좋고 기능 자동화 가능 | plugin 구현 필요 |
| CLI init script only | 프로젝트 수정 없음 | 지속 설정/IDE 연동 약함 |

### 결론

Primary:

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

Fallback:

```kotlin
debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
```

이 coordinate는 published artifact가 있을 때의 외부 설치 예시다. artifact publish 전에는 repo sample처럼 composite build/project dependency wiring을 사용한다.

Advanced:

```bash
pointpatch run
```

---

## 20. 결정 19: Source mapping은 compiler plugin이 아니라 source indexer부터 시작한다

### 결정

v1 Full의 source candidates는 Gradle source indexer를 사용한다. Kotlin compiler plugin은 core에 넣지 않는다.

### 이유

AI가 코드를 수정하려면 source candidate가 중요하다. 하지만 runtime semantics node에서 source file/line을 항상 직접 얻을 수 없다.

검토한 방식:

| 방식 | 장점 | 문제 |
|---|---|---|
| search hints only | 쉽고 안정적 | source candidate 품질 제한 |
| Gradle source indexer | 안정적, 충분히 유용 | 정확한 runtime-node mapping은 아님 |
| Compose sourceInfo | 잠재적으로 정확 | internal/tooling 의존 가능 |
| Kotlin compiler plugin | 강력할 수 있음 | API instability, 유지보수 부담 큼 |

Kotlin custom compiler plugin은 버전별 breaking risk가 크고, Compose compiler와의 상호작용도 부담이다. “확실하게 동작”이 우선인 제품의 core로는 부적합하다.

### 선택한 구조

```text
1. Semantics fingerprint 생성
2. Source index로 candidate matching
3. Optional sourceInfo provider
4. Compiler plugin은 future experimental
```

---

## 21. 결정 20: Screenshot은 PixelCopy 우선, Canvas fallback

### 결정

Screenshot capture는 API 26 이상에서 PixelCopy를 우선 사용하고, 실패하면 `decorView.draw(canvas)`로 fallback한다.

### 이유

Compose UI, hardware rendering, Surface 계층이 섞이면 단순 Canvas draw가 충분하지 않을 수 있다. PixelCopy는 window/surface 기반 capture에서 더 적합할 수 있다.

### 요구사항

- full screenshot
- selected bounds crop
- failure-safe
- annotation export는 screenshot 실패와 독립

### 결과

screenshot result는 성공/실패 정보를 annotation에 포함한다.

```json
{
  "screenshot": {
    "fullPath": null,
    "cropPath": null,
    "captureFailedReason": "PixelCopy timeout; Canvas fallback failed"
  }
}
```

---

## 22. 결정 21: 실패해도 항상 context를 만든다

### 결정

PointPatch는 selected node를 정확히 찾지 못해도 annotation을 생성해야 한다.

Fallback 단계:

```text
1. selectedNode 성공
2. selectedNode 애매함 → candidatesAtPoint export
3. semantics node 없음 → tap coordinate + screenshot crop
4. screenshot 실패 → coordinate + text context
5. source candidate 없음 → search hints only
```

### 이유

AI에게 필요한 것은 완벽한 selector 하나가 아니라 충분한 context다. 실패 시 빈 결과를 주는 것보다 partial context를 주는 것이 낫다.

### 결과

Annotation model에는 항상 다음을 포함한다.

- tap coordinate
- candidates if any
- screenshot result if any
- failure reason if any
- user comment

---

## 23. 결정 22: Local-first export를 기본으로 한다

### 결정

기본 export는 clipboard/file이다. network export는 opt-in이다.

### 이유

PointPatch는 screenshot과 UI text를 다룬다. 민감 정보가 포함될 수 있다.

기본 export:

- Markdown clipboard
- JSON clipboard
- local screenshot files
- share intent optional

기본으로 하지 않는 것:

- remote upload
- webhook
- analytics
- HTTP server
- cloud storage

### MCP 예외

MCP는 desktop process와 local/ADB bridge를 통해 동작한다. 앱 안에서 외부 network server를 열지 않는다.

---

## 24. 결정 23: Password/editable text redaction을 기본으로 한다

### 결정

redaction 기본값:

```text
redactPassword = true
redactEditableText = true
includeRawProperties = false
```

### 이유

TextField에는 이메일, 전화번호, 주소, 토큰, 검색어 등 민감 정보가 들어갈 수 있다. Password는 반드시 가려야 한다.

### 주의

Screenshot은 자동 redaction을 보장하지 않는다. 문서에서 명시해야 한다.

```text
Screenshots may contain sensitive information. They are stored locally by default and are not uploaded unless explicitly configured.
```

---

## 25. 결정 24: 기존 MCP/automation 도구와 다르게 포지셔닝한다

### 결정

PointPatch는 DTA, mobile-mcp, appium-mcp와 같은 범용 모바일 자동화 플랫폼이 아니다.

### 이유

이미 다음과 같은 도구가 있다.

- DTA: Android AI debugging platform
- mobile-mcp: iOS/Android automation MCP
- appium-mcp: Appium 기반 mobile testing MCP
- Android Remote Control MCP: device-wide remote control
- Compose Buddy: Compose Preview inspection/MCP

PointPatch가 같은 방향으로 가면 차별화가 약해지고 scope가 커진다.

### 차별화

PointPatch는 다음에 집중한다.

```text
human-selected Compose UI
→ change request
→ runtime context
→ source candidates
→ AI patch
→ optional verification
```

### 가져올 아이디어

- DTA의 sidekick/autoinject/MCP 구조
- mobile-mcp의 structured-data-first 원칙
- appium-mcp의 stable locator first 원칙
- Compose Buddy의 source/hierarchy 방향

### 가져오지 않을 것

- network mocking
- WebSocket capture
- JVMTI/native hooks
- AccessibilityService-based remote control
- default HTTP server/tunnel
- general mobile automation scope

---

## 26. 결정 25: AI가 코드를 직접 수정하는 tool은 제공하지 않는다

### 결정

PointPatch MCP는 기본적으로 file write/code edit tool을 제공하지 않는다.

### 이유

Codex, Claude Code, Cursor 같은 AI coding agent는 이미 repository file edit 기능을 갖고 있다. PointPatch가 코드 쓰기까지 맡으면 역할이 불필요하게 커지고 권한 문제가 생긴다.

PointPatch의 역할:

- runtime UI context provider
- source candidate provider
- screenshot/semantics provider
- verification provider

AI coding agent의 역할:

- repository 탐색
- file 수정
- patch 생성
- test/build 수행

### 결론

역할 분리를 유지한다.

```text
PointPatch tells AI what and where.
AI agent edits the code.
```

---

## 27. 결정 26: MCP에 human selection을 핵심 tool로 둔다

### 결정

MCP에서도 사용자가 앱에서 직접 UI를 선택하는 흐름을 핵심으로 둔다.

핵심 tool:

```text
pointpatch_get_ui_feedback
```

동작:

```text
AI tool call
  ↓
앱 overlay 활성화
  ↓
사용자 UI 선택
  ↓
사용자 comment 입력
  ↓
MCP result 반환
```

### 이유

AI가 screenshot을 보고 좌표를 추정하는 방식은 틀릴 수 있다. 사용자가 직접 찍는 방식이 훨씬 정확하다.

이것이 PointPatch의 차별점이다.

```text
AI가 폰을 대신 조작하는 도구가 아니라,
사용자가 지목한 UI를 AI에게 정확히 전달하는 도구.
```

---

## 28. 결정 27: “MCP-only”가 아니라 “MCP-enabled”로 만든다

### 결정

PointPatch는 MCP-only 제품이 아니다. MCP-enabled 제품이다.

### 이유

MCP-only가 되면 첫 사용 마찰이 크다.

MCP-only 흐름의 문제:

- MCP client 설정 필요
- device 연결 필요
- app package 인식 필요
- sidekick 설치 필요
- server 실행 필요
- tool call 이해 필요

앱 안 annotation 흐름은 더 쉽다.

```text
앱 실행
→ UI 선택
→ comment
→ copy
```

### 결론

제품 첫 경험은 앱 안 annotation이어야 한다. MCP는 power workflow로 자연스럽게 확장한다.

---

## 29. 결정 28: 첫 공개 버전부터 최종 UX 방향을 따른다

### 결정

최초 공개 버전부터 `PointPatchRoot` wrapper 기반 MVP로 가지 않고, autoinit/overlay 방향으로 간다.

### 이유

나중에 UX를 바꾸면 사용자 API와 문서가 크게 바뀐다. 처음부터 최종형 UX를 잡는 것이 좋다.

최소 구현이라도 사용자 경험은 다음이어야 한다.

```text
debug dependency or Gradle plugin
→ 앱 실행
→ floating button
→ select UI
→ comment
→ copy
```

### 단, 구현 순서는 단계적으로

첫 공개 목표는 v1 Full로 잡는다. v1 Full에는 앱 안 annotation, source index, CLI, MCP를 포함한다.

다만 구현은 한 번에 모든 subsystem을 만드는 방식이 아니라 milestone별로 진행한다.

v1 Full milestone:

1. Compose sample app과 core annotation model
2. sidekick autoinit와 overlay attach
3. semantics inspection과 Smart Select
4. screenshot capture와 local export
5. Gradle plugin과 source index
6. CLI bridge, run, setup, doctor
7. MCP server와 macro tools
8. docs, compatibility matrix, release readiness

### 이유

MCP까지 포함하는 최종 제품 경험은 초기에 검증해야 한다. 하지만 MCP부터 만들면 runtime selection UX, annotation schema, screenshot/export 같은 핵심 흐름이 아직 불안정한 상태에서 bridge와 protocol complexity가 먼저 커진다.

따라서 v1 Full은 제품 범위이고, milestone은 구현 순서다.

---

## 30. 결정 29: 선택 UX는 Smart Select로 설계한다

### 결정

선택 UX는 단순히 tap-only나 drag-only로 만들지 않는다. v1은 Smart Select를 사용한다.

```text
Tap Select
→ Scope Chips
→ Area Select fallback
```

### 이유

Compose UI는 semantics node 기반으로 의미 있는 target을 얻을 수 있다. 일반적인 Button, Text, Card, Row, LazyColumn item은 tap이 가장 빠르고 자연스럽다.

하지만 실제 수정 요청은 단일 node만으로 충분하지 않은 경우가 많다.

예:

- 텍스트만 바꾸고 싶다.
- 버튼 전체를 바꾸고 싶다.
- 버튼이 들어 있는 카드 전체를 바꾸고 싶다.
- 카드가 들어 있는 리스트 item 전체를 바꾸고 싶다.
- semantics가 없는 여백, Canvas, 이미지 일부를 지목하고 싶다.

드래그를 기본으로 하면 사용자가 매번 영역을 손으로 그려야 해서 느리다. 반대로 tap만 있으면 node 선택이 애매할 때 사용자가 바로잡기 어렵다.

### 선택한 UX

기본 흐름:

```text
Select UI
→ 사용자가 target을 탭
→ PointPatch가 best semantics candidate를 highlight
→ comment sheet 표시
→ Scope Chips로 Text/Button/Card/Screen 같은 후보 scope 전환
→ 필요할 때만 Area Select로 시각 영역 선택
```

Scope Chips 예:

```text
Selected: Button "Pay now"

[Text] [Button] [BottomBar] [Screen]
```

Area Select는 fallback이다.

사용 대상:

- spacing/padding feedback
- Canvas-only UI
- image/visual-only region
- background/surface area
- semantics node 없음
- 후보 scope가 모두 틀림

### 결과

PointPatch는 빠른 기본 선택과 수동 보정 능력을 모두 갖는다.

```text
Tap for speed.
Scope chips for precision.
Area select for visual fallback.
```

---

## 31. 결정 30: sample은 Compose 테스트 앱으로 교체한다

### 결정

현재 `sample/`의 XML/ViewBinding/AppCompat starter app은 PointPatch 검증에 적합하지 않다. v1 구현에서는 `sample/`을 Jetpack Compose 전용 테스트 앱으로 교체한다.

필수 screen:

- CheckoutScreen
- FeedScreen
- FormScreen
- DialogScreen
- CanvasScreen
- EdgeCasesScreen

### 이유

PointPatch의 핵심은 Compose semantics, RootForTest, screenshot crop, source candidates, Smart Select를 검증하는 것이다. XML/View sample은 이 목적을 검증하지 못한다.

sample app은 demo가 아니라 test fixture여야 한다.

검증해야 할 것:

- Material/Compose 기본 semantics
- repeated LazyColumn item
- Dialog/Popup root
- editable/password redaction
- Canvas-only fallback
- Canvas with explicit semantics
- disabled controls
- long text/font scale
- nested clickable target

### 결과

v1 acceptance는 sample app 기준으로 판단한다.

```text
sample debug app
→ PointPatch overlay
→ Smart Select
→ annotation export
→ source candidates
→ CLI/MCP feedback flow
```

---

## 32. 결정 31: Product copy에서 Android Compose 전용성을 계속 강조한다

### 결정

README, docs, UI에서 다음 문구를 반복한다.

```text
Android Jetpack Compose only.
Debug builds only.
No required testTags.
No AccessibilityService.
```

### 이유

제품이 무엇을 하지 않는지 명확해야 오해가 줄어든다.

특히 “Android UI inspector”라고만 하면 XML/View, WebView, 타 앱 inspection까지 기대할 수 있다. 이를 피하기 위해 “for Android Compose”를 이름과 문서에 명시한다.

### 결과

제품명:

```text
PointPatch for Android Compose
```

짧은 이름:

```text
PointPatch Compose
```

---

## 33. 결정 32: 정확한 source line을 보장하지 않는다

### 결정

v1 Full에서는 source candidate를 제공하지만, 모든 UI node에 대해 정확한 source file/line을 보장하지 않는다.

### 이유

Compose runtime node와 source code 위치는 복잡하게 연결된다.

어려운 경우:

- reusable component
- stringResource
- server-driven text
- list item
- conditional UI
- wrapper components
- modifier chain
- custom composable
- library component

### 문서화할 표현

```text
Source candidates are best-effort. PointPatch provides search hints and likely files, but does not guarantee exact source line mapping for every UI node.
```

### 이유

과장하지 않는 것이 오픈소스 신뢰를 높인다.

---

## 34. 결정 33: screenshot은 desktop-readable artifact로도 제공한다

### 결정

Android app-only export에서는 screenshot path가 Android app sandbox 내부 path일 수 있다. CLI/MCP 경로에서는 desktop AI agent가 읽을 수 있도록 screenshot artifact를 `.pointpatch/artifacts/` 아래로 pull하거나 resource로 제공한다.

권장 경로:

```text
.pointpatch/artifacts/<annotation-id>/<annotation-id>-full.png
.pointpatch/artifacts/<annotation-id>/<annotation-id>-crop.png
```

### 이유

Android `cacheDir` path는 desktop process에서 직접 열 수 없다. Markdown에 Android-local path만 넣으면 Codex/Claude/Cursor 같은 desktop agent가 screenshot을 실제로 참고하기 어렵다.

### 결과

Annotation schema는 Android-local path와 desktop-readable path를 구분한다.

```json
{
  "screenshot": {
    "fullPath": "/data/user/0/.../cache/pointpatch/full.png",
    "cropPath": "/data/user/0/.../cache/pointpatch/crop.png",
    "desktopFullPath": ".pointpatch/artifacts/pp_1/pp_1-full.png",
    "desktopCropPath": ".pointpatch/artifacts/pp_1/pp_1-crop.png"
  }
}
```

구현된 CLI/MCP 경로는 Android screenshot 파일을 직접 desktop path로 노출하지 않고, sidekick bridge의 `readScreenshot` method로 현재 annotation의 `full` 또는 `crop` PNG를 읽은 뒤 `.pointpatch/artifacts/<annotation-id>/` 아래에 저장한다. Bridge는 명시적 path 읽기를 허용하지 않고 현재 annotation screenshot만 읽는다.

---

## 35. 최종 architecture decision summary

최종 구조:

```text
Gradle plugin or debugImplementation
  ↓
AndroidX Startup
  ↓
Debuggable guard
  ↓
ActivityLifecycleCallbacks
  ↓
DecorView overlay
  ↓
User selects Compose UI with Smart Select
  ↓
RootForTest discovery
  ↓
SemanticsOwner merged/unmerged nodes
  ↓
Tap Select + Scope Chips + Area Select fallback
  ↓
Nearby context + screenshot crop
  ↓
Source index matching
  ↓
Markdown/JSON export
  ↓
CLI/MCP bridge with desktop-readable artifacts
```

이 구조가 선택된 이유:

1. 사용자 설정이 적다.
2. Compose-only 범위에 맞다.
3. `testTag` 없이도 동작한다.
4. Accessibility 권한이 없다.
5. compiler plugin보다 안정적이다.
6. MCP 없이도 가치가 있다.
7. MCP 연결 시 AI agent workflow로 확장된다.
8. privacy-first 정책을 지키기 쉽다.
9. Smart Select로 빠른 선택과 수동 보정을 모두 지원한다.
10. sample app이 실제 Compose edge case를 검증한다.

---

## 36. 원칙 체크리스트

새 기능을 추가할 때 다음 질문을 통과해야 한다.

1. Android Jetpack Compose debug workflow에 필요한가?
2. 사용자가 설정할 일을 줄이는가?
3. `testTag` 강제 없이 동작하는가?
4. AccessibilityService 없이 가능한가?
5. release safety를 해치지 않는가?
6. privacy 기본값을 해치지 않는가?
7. AI에게 UI 수정 context를 더 잘 전달하는가?
8. 실패해도 fallback output이 가능한가?
9. MCP 없이도 기본 UX가 유지되는가?
10. 기존 모바일 automation platform과 scope가 과하게 겹치지 않는가?
11. Smart Select의 Tap/Scope/Area 모델을 복잡하게 만들지 않는가?
12. sample app에서 검증 가능한가?

---

## 37. 최종 결정문

PointPatch for Android Compose는 다음 원칙으로 만든다.

```text
Compose-only
debug-only
zero-code install
in-app UI selection first
Smart Select: tap + scope chips + area fallback
MCP optional
no required testTags
no AccessibilityService
no core compiler plugin
semantics runtime inspector
source index best-effort
screenshot desktop artifacts for CLI/MCP
local-first export
privacy-first defaults
```

제품의 본질:

```text
Point at Compose UI. Tell AI what to patch.
```

한국어:

```text
Android Compose 화면에서 수정할 UI를 지목하고, 원하는 변경사항을 말하면, AI가 코드를 수정할 수 있는 정확한 context를 생성한다.
```
