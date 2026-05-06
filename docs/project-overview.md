# PointPatch Project Overview

이 문서는 현재 저장소의 실제 코드 기준으로 PointPatch의 역할, 모듈 경계, 실행 흐름, 개발 명령을 빠르게 파악하기 위한 온보딩 문서다. 제품 요구사항과 장기 설계 배경은 [Product requirements](pointpatch_prd.md)와 [Technical design](pointpatch_technical_design.md)를 함께 본다.

## 한 줄 요약

PointPatch는 Jetpack Compose debug 앱에 sidekick 런타임을 붙여 현재 UI의 semantics, 스크린샷, 선택 위치, source 후보, 사용자 피드백을 로컬에서 수집하고, CLI/MCP/feedback console을 통해 AI 코딩 에이전트가 바로 읽을 수 있는 작업 큐로 넘기는 도구다.

## 현재 범위

- Android Jetpack Compose debug build 전용.
- AndroidX Startup으로 debug 앱에 sidekick 자동 설치.
- AccessibilityService 없이 현재 앱 프로세스의 Compose semantics만 읽음.
- ADB와 app-local socket bridge를 사용한 로컬 desktop 연동.
- MCP feedback console이 주 워크플로이고, legacy 단건 in-app capture도 유지.
- source candidate는 Gradle source index 기반 best-effort 힌트.
- screenshot pixel은 자동 PII redaction 대상이 아니므로 공유 전 검토 필요.

## 모듈 지도

```text
:app                         sample/ validation app
:pointpatch-compose-core     pure Kotlin domain contracts, use cases, models, selection, formatter, source matching
:pointpatch-compose-overlay  Compose overlay UI and public Studio shell
:pointpatch-compose-sidekick debug runtime installed into target app
:pointpatch-gradle-plugin    debug dependency injection and source-index asset generation
:pointpatch-cli              desktop CLI and ADB bridge client
:pointpatch-mcp              stdio MCP server, feedback session store, local console server
```

### `:pointpatch-compose-core`

Pure Kotlin 모듈이다. Android 런타임에 직접 묶이지 않는 공통 계약을 둔다.

- `domain/annotation`, `domain/snapshot`, `domain/session`: `Annotation`, `Snapshot`, `Session`, typed IDs, repository contracts, delivery/status/target concepts.
- `usecase/annotation/CreateAnnotationUseCase.kt`, `usecase/snapshot/SaveSnapshotUseCase.kt`: pure application use cases over the domain repository contracts.
- `model/Models.kt`: `PointPatchAnnotation`, `PointPatchNode`, `SelectionInfo`, `SourceCandidate`, `ScreenshotInfo` 등 export schema의 중심 모델.
- `selection/NodeSelector.kt`: tap 좌표에 들어온 semantics node를 점수화한다. click action, 의미 있는 text/contentDescription/role/testTag, merged tree 여부, center proximity, root-like penalty를 반영한다.
- `selection/NearbyNodeCollector.kt`: 선택 node 주변의 의미 있는 node를 중복 제거해 context로 모은다.
- `source/SourceIndex.kt`, `source/SourceMatcher.kt`: Gradle plugin이 만든 source index와 semantics 증거를 매칭한다.
- `format/PointPatchMarkdownFormatter.kt`, `format/PointPatchJsonFormatter.kt`: annotation을 agent-facing Markdown 또는 JSON으로 변환한다.
- `redaction/RedactionPolicy.kt`: editable/password semantics text redaction 기본 정책.

Boundary invariant: `:pointpatch-compose-core` does not know about MCP, CLI, Android UI surfaces, or `.pointpatch` file layout. Outer modules translate their DTOs, persistence, bridge, and presentation state into core domain contracts explicitly.

### `:pointpatch-compose-overlay`

Compose UI 모듈이다. 두 갈래 UI가 들어 있다.

- `compose/overlay/*`: in-app toolbar, selection highlight, comment sheet 등 legacy single-capture overlay.
- `compose/console/studio/*`: public `FeedbackConsoleScreen`과 Studio-style 3-column console shell. 현재 MCP browser console은 별도 HTML asset을 사용하지만, public Compose entrypoint도 이 모듈에 있다.
- `StudioViewModel`: Studio shell의 local snapshot/annotation state와 annotation drag/select/save 동작을 관리한다.
- `compose/overlay/OverlayStateMachine.kt`: in-app overlay mode 전이를 검증한다.
- `compose/console/studio/theme/*`, `common/*`, `canvas/*`, `canvas/toolbar/*`: Studio theme tokens, common controls, preview canvas, toolbar subcomponents를 분리해 둔다.

### `:pointpatch-compose-sidekick`

타깃 Android debug 앱 안에서 실행되는 런타임이다.

- `PointPatch.install(application)`: debuggable 앱에서만 bridge runtime을 시작하고 Activity lifecycle callbacks를 등록한다.
- `init/PointPatchInitializer.kt`: AndroidX Startup entrypoint. sidekick dependency만 추가해도 debug 앱 시작 시 자동 설치된다.
- `lifecycle/PointPatchActivityLifecycleCallbacks.kt`: resumed/destroyed Activity를 bridge runtime에 알려준다.
- `inspect/ComposeRootFinder.kt`: current decor view 아래 Compose `RootForTest`를 찾는다.
- `inspect/SemanticsInspector.kt`: merged/unmerged semantics tree를 읽고 `PointPatchNode`로 변환한다.
- `capture/AnnotationCaptureController.kt`: tap/area/scope selection, nearby context, source candidate, screenshot metadata, error를 합쳐 `PointPatchAnnotation`을 만든다.
- `screenshot/*`: app cache 아래 screenshot PNG를 저장한다.
- `bridge/BridgeServer.kt`: Android local socket bridge. `status`, `inspectCurrentScreen`, `captureScreenSnapshot`, `readSourceIndex`, `startFeedbackCapture`, `verifyUiChange`, `readScreenshot`, `performNavigation`을 token 검증 후 실행한다.

### `:pointpatch-gradle-plugin`

Android application project에 적용되는 Gradle plugin이다.

- plugin id: `io.github.pointpatch.compose`
- debug variant에서만 동작한다.
- 같은 multi-project build 안에 `:pointpatch-compose-sidekick`이 있으면 project dependency를 붙이고, 외부 프로젝트에서는 `io.github.pointpatch:pointpatch-compose-sidekick:<runtimeVersion>` 좌표를 붙인다.
- `generate<Variant>PointPatchSourceIndex` task가 Kotlin/XML source를 스캔해 generated asset을 만든다.

Generated asset:

```text
build/generated/pointpatch/<variant>/assets/pointpatch/pointpatch-source-index.json
build/generated/pointpatch/<variant>/assets/pointpatch/pointpatch-build-info.json
```

주요 extension 기본값:

```kotlin
pointpatch {
    enabled.set(true)
    runtimeVersion.set("0.1.0")
    addDebugRuntime.set(true)
    generateSourceIndex.set(true)
    generateProjectMetadata.set(true)
    includeScreenshots.set(true)
    redactEditableText.set(true)
}
```

### `:pointpatch-cli`

Desktop process로 실행되는 CLI다. `pointpatch` application distribution을 만든다.

- `pointpatch run`: 기본 `:app:installDebug`를 실행하고 앱을 launch한 뒤 sidekick status를 기다린다.
- `pointpatch status`: bridge 연결, current activity, root count, protocol/source-index 상태를 출력한다.
- `pointpatch doctor`: project, package metadata, ADB, device, sidekick session을 단계별로 진단한다.
- `pointpatch setup`: MCP client용 command/args JSON을 출력한다.
- `pointpatch mcp`: sibling 또는 PATH의 `pointpatch-mcp` executable로 stdio server를 실행한다.
- `pointpatch console`: `pointpatch-mcp --console`을 실행해 local feedback console을 연다.

package name 해석 순서:

1. CLI/MCP argument의 `--package`.
2. `--project-dir` 기준 `.pointpatch/project.json`의 `applicationId`.

### `:pointpatch-mcp`

MCP stdio server와 local feedback console 서버다.

- `McpProtocol`: JSON-RPC initialize/tools/resources/ping/cancellation 처리.
- `tools/PointPatchTools.kt`: MCP tool/resource registry와 CLI bridge adapter.
- `session/FeedbackSessionService.kt`: session workflow orchestration. Session open/resume, preview capture, persisted evidence capture, navigation, annotation 저장, handoff, resolve를 조율한다.
- `session/SessionDtoModels.kt`, `console/AnnotationRequestModels.kt`: MCP/local-console DTO와 persisted JSON field names. Existing field names such as `items`, `screens`, `itemId`, and `screenId` are compatibility contracts.
- `session/SessionDomainMappers.kt`: DTO와 `compose-core` domain model 사이의 명시적 mapper. Legacy `"ready"` item status는 domain에서 `AnnotationStatus.OPEN`으로 normalize된다.
- `session/PreviewSnapshotCache.kt`, `SourceIndexRegistry.kt`, `ScreenshotArtifactPromoter.kt`: transient preview cache, source-index caching, frozen preview screenshot promotion을 service에서 분리한다.
- `session/FeedbackSessionStore.kt`, `FeedbackSessionPersistence.kt`: `.pointpatch/feedback-sessions/<session-id>/session.json` persistence.
- `console/FeedbackConsoleServer.kt`: `127.0.0.1` HTTP console과 `/api/*` endpoints.
- `console/FeedbackConsoleAssets.kt`: `src/main/resources/console/index.html`, `styles.css`, `app.js` classpath resources를 검증하고 조립하는 loader.

MCP tools:

- `pointpatch_status`
- `pointpatch_get_current_screen`
- `pointpatch_get_ui_feedback`
- `pointpatch_verify_ui_change`
- `pointpatch_open_feedback_console`
- `pointpatch_list_feedback_sessions`
- `pointpatch_capture_screen`
- `pointpatch_navigate_app`
- `pointpatch_list_feedback`
- `pointpatch_read_feedback`
- `pointpatch_resolve_feedback`

Resources:

- `pointpatch://session/current`
- `pointpatch://screen/current`
- `pointpatch://annotation/latest`
- `pointpatch://screenshot/latest/full.png`
- `pointpatch://screenshot/latest/crop.png`
- `pointpatch://source-index`

### `:app` (`sample/`)

저장소 검증용 FixThis Studio Compose sample app이다. Android Studio 관례에 맞춰 Gradle project path는 `:app`이고 실제 source directory는 `sample/`이다. Application id는 `io.beyondwin.fixthis.sample`, launcher label은 `FixThis`다. `Home`, `Queue`, `Project`, `Review`, `Diagnostics` 탭이 하나의 compact product scene을 이루며 semantics, screenshot, navigation, source matching, form controls, dropdown/menu, dialog, Canvas, disabled controls, repeated cards, long text, weak-semantics edge case를 검증한다.

## Runtime Flow

```mermaid
flowchart TD
    A["Debug Compose app starts"] --> B["AndroidX Startup runs PointPatchInitializer"]
    B --> C["PointPatch.install registers ActivityLifecycleCallbacks"]
    C --> D["PointPatchBridgeRuntime starts BridgeServer"]
    D --> E["SessionTokenStore writes files/pointpatch/session.json"]
    F["CLI or MCP request"] --> G["BridgeClient reads session token with adb run-as"]
    G --> H["adb forward tcp:<port> to localabstract:pointpatch_<package>"]
    H --> I["BridgeServer validates token and method"]
    I --> J["Inspect semantics / capture screenshot / navigate / read source index"]
    J --> K["Return JSON result to CLI or MCP"]
```

## Feedback Console Flow

```mermaid
flowchart TD
    A["pointpatch_open_feedback_console or pointpatch console"] --> B["MCP starts local HTTP console"]
    B --> C["User selects ADB device"]
    C --> D["Console polls preview via captureScreenSnapshot"]
    D --> E["User clicks Add to freeze latest preview"]
    E --> F["User selects semantics node or visual area and writes comments"]
    F --> G["Save persists one screen evidence snapshot"]
    G --> H["Feedback items share that screenId"]
    H --> I["Send creates local handoff batch"]
    I --> J["Agent reads queue with pointpatch_read_feedback"]
    J --> K["Agent resolves items with pointpatch_resolve_feedback"]
```

Important distinction:

- Preview frames are temporary and stored under `.pointpatch/preview-cache/`.
- Saved evidence lives under `.pointpatch/feedback-sessions/<session-id>/`.
- `Send` is local persistence for MCP handoff. It does not call an external AI API.

## Local Files And Artifacts

Android app-private files:

```text
files/pointpatch/session.json
cache/pointpatch/<yyyy-MM-dd>/<annotation-id>-full.png
cache/pointpatch/<yyyy-MM-dd>/<annotation-id>-crop.png
```

Project-local desktop files:

```text
.pointpatch/project.json
.pointpatch/artifacts/<annotation-id>/
.pointpatch/feedback-sessions/<session-id>/
.pointpatch/preview-cache/<session-id>/<preview-id>/
```

현재 `.gitignore`는 `.pointpatch` 전체를 무시한다. package auto-resolution에 `.pointpatch/project.json`을 팀 차원에서 공유하려면 ignore 규칙을 조정해야 한다.

## 개발 명령

Build and install sample:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Build CLI and MCP distributions:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Run sample smoke flow:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.beyondwin.fixthis.sample
```

Open console:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.beyondwin.fixthis.sample
```

Run local unit tests:

```bash
./gradlew test
./gradlew :pointpatch-gradle-plugin:test
./gradlew :pointpatch-compose-overlay:testDebugUnitTest :pointpatch-compose-sidekick:testDebugUnitTest
```

Android instrumentation tests require an unlocked interactive emulator or device. A physical device can still report `device` in ADB while a secure lockscreen prevents Compose hierarchy inspection; see [Troubleshooting](troubleshooting.md#connected-test-says-no-compose-hierarchies-found).

```bash
./gradlew connectedAndroidTest
```

## 문서 읽는 순서

처음 보는 개발자에게 권장하는 순서:

1. [README](../README.md): 제품 요약과 빠른 실행.
2. 이 문서: 현재 코드 구조와 runtime 흐름.
3. [MCP](mcp.md): feedback console과 MCP tool contract.
4. [Output schema](output-schema.md): annotation/session JSON field.
5. [Privacy](privacy.md): local-first, redaction, screenshot 주의사항.
6. [Troubleshooting](troubleshooting.md): ADB/sidekick/MCP 실패 진단.
7. [Technical design](pointpatch_technical_design.md): 더 긴 설계 배경과 module-by-module 설계.
8. [Architecture Decision Records](adr/README.md): 현재 코드에서 지켜야 하는 durable architecture decisions.

`docs/superpowers/plans/`와 `docs/superpowers/specs/`는 implementation history와 작업 지시 기록이다. 현재 architecture source of truth는 위 current-facing 문서와 ADR을 우선한다.

## 자주 헷갈리는 지점

- `:app`은 Gradle project path이고 source directory는 `sample/`이다.
- Android app은 MCP server나 HTTP server를 열지 않는다. MCP/console server는 desktop process다.
- app bridge는 token이 있는 Android local socket이고 ADB forward로만 desktop에서 접근한다.
- `pointpatch_get_ui_feedback`는 legacy 단건 capture 호환 도구다. 새 워크플로는 feedback console session tools를 우선 사용한다.
- source candidates는 정확한 compiler mapping이 아니라 source index text/symbol 기반 ranking이다.
- semantics redaction은 screenshot pixel redaction이 아니다.
- feedback console의 `Add`는 freeze만 하고 저장하지 않는다. `Save`가 persisted evidence snapshot을 만든다.
- persisted MCP JSON field names는 compatibility contract다. Domain model naming과 다를 수 있으므로 mapper boundary에서 확인한다.
