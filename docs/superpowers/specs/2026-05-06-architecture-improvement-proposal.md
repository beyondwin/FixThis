# FixThis 아키텍처 개선 제안서

- 작성일: 2026-05-06
- 대상: FixThis Android 프로젝트 전체 (compose-core / compose-overlay / compose-sidekick / cli / mcp / gradle-plugin)
- 성격: 권고안(Recommendation Document). 옵션 비교가 아니라 단일 권장 경로 제시.

---

## 0. 요약 (TL;DR)

FixThis는 모듈 경계, sealed class 기반 상태 머신, 인터페이스 기반 포트, 순수 JVM 코어 분리 등 좋은 기반이 이미 갖춰져 있다.
그러나 다음 7가지 문제가 누적되며 유지보수 비용을 빠르게 끌어올리고 있다.

1. **용어 불일치**: `FeedbackItem`/`Annotation`, `CapturedScreen`/`Snapshot`이 동일 개념을 다른 이름으로 부른다.
2. **3,198 라인의 임베디드 HTML**: `FeedbackConsoleAssets.kt`는 린트도 테스트도 IDE 지원도 받지 못한다.
3. **데이터 레이어 추상화 부재**: `FeedbackSessionService`(581라인)가 라이프사이클·영속화·캐시·소스 인덱스를 모두 떠맡는다.
4. **디자인 시스템 절반만 구현**: 컬러·타이포는 있으나 spacing·shape·elevation·`StudioTheme` 진입점이 없다.
5. **Composable 비대화**: `PreviewSurface`(595), `CanvasToolbar`(358) 등.
6. **상태 머신 누락 상태**: `OverlayMode`에 Error/Loading 없음, 전이 검증도 암묵적.
7. **테스트 거대화**: `FeedbackConsoleServerTest` 2,184라인 — 프로덕션 코드의 테스트 가능성 부족 신호.

본 문서는 이 7가지를 **3 Phase, 7~9 Sprint**에 걸쳐 해소하는 단일 경로를 제시한다.
**Phase 1은 행동 변경 없이(rename + 디자인 토큰 + asset 분리)** 안전하게 시작하고,
**Phase 2에서 도메인/데이터 레이어**를 분리한 뒤,
**Phase 3에서 코루틴 동시성·Composable 분리·테스트 인프라**를 정비한다.

---

## 1. 용어 통일 (Terminology Unification) — 가장 먼저

이 작업이 모든 후속 제안의 전제조건이다. 용어가 흔들리면 도메인 모델 이름·패키지 이름·DTO 매퍼·테스트 명명이 모두 흔들린다.
**Phase 1의 첫 작업은 행동 변경 없는 일괄 rename PR이다.**

### 1.1 사용자 관점에서 본 권장 용어

UI에 노출된 용어가 사실상의 외부 계약이다. 코드를 UI에 맞춘다 (반대가 아니다).

| 카테고리 | 현재 코드 용어 | UI 표시 용어 | 권장 통합 용어 | 비고 |
|---|---|---|---|---|
| 사용자 의견 단위 | `FeedbackItem` | "Annotation" | **`Annotation`** | UI·코드·MCP 도구명 모두 일치 |
| 저장된 화면 캡처 | `CapturedScreen` | "Snapshot" | **`Snapshot`** | History 패널 라벨과 일치 |
| Annotation 상태 | `FeedbackItemStatus` | open/in-progress/resolved | **`AnnotationStatus`** | 6개 → 5개로 정리(아래 1.3 참조) |
| 작성 중 임시 객체 | `PendingDraftFeedbackItem` | (UI 비노출) | **`AnnotationDraft`** | "Pending"+"Draft" 중복 제거 |
| 세션 | `FeedbackSession` | "Session" | **`Session`** | "Feedback" prefix 제거 |
| 세션 상태 | `FeedbackSessionStatus` | active/ready/closed | **`SessionStatus`** | 동일 |
| 저장 요청 DTO | `SavePreviewFeedbackItemsRequest` | "Save snapshot" 버튼 | **`SaveSnapshotRequest`** | preview→snapshot, items→snapshot |
| 추가 요청 DTO | `AddFeedbackItemRequest` | "Add annotation" | **`AddAnnotationRequest`** | 동일 |
| 핀(시각 표시) | `Pin` / `PinRect` | "pin" | **`Pin`** (유지) | 이미 일치 |
| 오버레이 모드 | `OverlayMode.Selecting` | (간접) | **`OverlayMode.Select`** | 명사 일관성(아래 9장) |
| 큐 | `FeedbackQueue` | (내부) | **`AnnotationQueue`** | Feedback prefix 제거 |
| 핸드오프 | `FeedbackHandoff` | "Handoff" | **`SessionHandoff`** | 단위가 세션이지 feedback이 아님 |

### 1.2 두 개의 `Annotation` 모델 통합

현재 `Annotation` 개념이 **두 곳에 중복 존재**한다.

- `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/model/StudioModels.kt` — UI용 `Annotation`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleItemModels.kt` — `FeedbackItem` (와이어 포맷)

**권장 구조**:

```
fixthis-compose-core
└── domain/
    ├── Annotation.kt            ← 단일 도메인 모델 (Source of Truth)
    ├── AnnotationStatus.kt
    ├── AnnotationDraft.kt
    ├── Snapshot.kt
    ├── Session.kt
    └── SessionStatus.kt

fixthis-mcp
└── console/dto/
    ├── AnnotationDto.kt         ← JSON 와이어 DTO + @Serializable
    └── AnnotationMapper.kt      ← Annotation ↔ AnnotationDto

fixthis-compose-overlay
└── studio/ui/
    └── AnnotationUiState.kt     ← UI 표시용 (selected, hover 등 UI-only 필드 포함)
```

도메인 모델은 **항상 `compose-core`에만 존재**한다. UI/MCP 모듈은 자기 컨텍스트(UI state, wire DTO)로 매핑한다.

### 1.3 Status 정리 (6 → 5)

현재 `FeedbackItemStatus`는 6개:
`OPEN`, `READY`, `IN_PROGRESS`, `RESOLVED`, `NEEDS_CLARIFICATION`, `WONT_FIX`

UI는 3개만 노출: open / in-progress / resolved.
`READY`는 사실상 OPEN의 하위 상태이고, `NEEDS_CLARIFICATION`·`WONT_FIX`는 종결 상태 분류다.

**권장 5개 상태** (UI에는 그룹핑된 3개를 그대로 보여주되, 도메인은 분리 보존):

```kotlin
enum class AnnotationStatus(val group: Group) {
    OPEN(Group.OPEN),                    // 작성 직후
    IN_PROGRESS(Group.IN_PROGRESS),      // 에이전트 작업 중
    RESOLVED(Group.RESOLVED),            // 정상 종결
    WONT_FIX(Group.RESOLVED),            // 의도적 미해결 종결
    NEEDS_CLARIFICATION(Group.OPEN);     // 사용자에게 재질의 — UI에서는 OPEN으로 표시

    enum class Group { OPEN, IN_PROGRESS, RESOLVED }
}
```

`READY`는 제거 — 실제 의미는 "에이전트에게 전달 준비 완료"이며, 이는 `Session.status == READY_FOR_AGENT`로 충분히 표현된다.

### 1.4 Rename 영향 파일 (변경 없는 rename PR로 한 번에)

`FeedbackItem` → `Annotation` 영향 파일 (대표):

- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleItemModels.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionModels.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionStore.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServer.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/tools/FixThisTools.kt`
- `fixthis-cli/src/main/kotlin/io/github/fixthis/cli/BridgeClient.kt`
- 위 모든 파일에 대응하는 `*Test.kt`

`CapturedScreen` → `Snapshot` 영향 파일:

- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionModels.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionStore.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionPersistence.kt`
- `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsolePreviewModels.kt`
- `fixthis-compose-overlay/.../studio/history/StudioHistory.kt`
- `fixthis-compose-overlay/.../studio/model/StudioModels.kt`

`FeedbackSession*` → `Session*` 영향 파일은 위 mcp/session 디렉토리 전체 + 클래스명이 prefix를 갖는 모든 곳.

### 1.5 Rename 실행 방법

1. **단일 PR**, 단일 커밋, 행동 변경 0줄.
2. JSON wire format은 **유지**(`type: "feedback_item"` 같은 필드는 `@SerialName("feedback_item")`으로 호환 유지). 뒤이어 별도 PR에서 와이어 포맷 마이그레이션.
3. `git mv` 사용해 파일명도 동시 변경 (이력 보존).
4. CI green 후 즉시 머지 — rename PR은 오래 두면 conflict 폭탄이 된다.

---

## 2. 클린 아키텍처 레이어 구조 제안

### 2.1 목표 레이어

```
+----------------------------------------------------------+
|  Presentation (compose-overlay, sample app)              |
|  - StudioViewModel, OverlayController                    |
|  - Composable, UI state classes                          |
+--------------------+-------------------------------------+
                     | depends on (interfaces only)
                     v
+----------------------------------------------------------+
|  Application / UseCase (compose-core::usecase)           |
|  - CreateAnnotationUseCase                               |
|  - SaveSnapshotUseCase                                   |
|  - LoadSessionUseCase                                    |
|  - PromoteSessionToReadyUseCase                          |
+--------------------+-------------------------------------+
                     | depends on
                     v
+----------------------------------------------------------+
|  Domain (compose-core::domain — pure Kotlin/JVM)         |
|  - Annotation, Snapshot, Session, AnnotationStatus       |
|  - SessionRepository (interface)                         |
|  - AnnotationRepository (interface)                      |
|  - SnapshotRepository (interface)                        |
+--------------------+-------------------------------------+
                     ^
                     | implements (impls in outer modules)
                     |
+----------------------------------------------------------+
|  Data (compose-sidekick, mcp::session)                   |
|  - SessionRepositoryImpl (file-based)                    |
|  - LocalSessionDataSource, ScreenshotDataSource          |
|  - DTO + Mapper (와이어/디스크 포맷)                       |
+----------------------------------------------------------+
```

핵심 원칙:

- **의존 방향은 항상 안쪽으로**. compose-core는 어떤 외부 모듈도 모른다.
- **Repository 인터페이스는 도메인 레이어**에 두고, 구현체는 mcp/sidekick에 둔다 (의존성 역전).
- **DTO는 외부 레이어에만**. 도메인은 DTO를 모른다.

### 2.2 권장 패키지 구조 (compose-core)

```
fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/
├── domain/
│   ├── annotation/
│   │   ├── Annotation.kt
│   │   ├── AnnotationStatus.kt
│   │   ├── AnnotationDraft.kt
│   │   ├── Severity.kt
│   │   └── AnnotationRepository.kt        ← interface
│   ├── session/
│   │   ├── Session.kt
│   │   ├── SessionStatus.kt
│   │   ├── SessionId.kt                   ← value class
│   │   └── SessionRepository.kt           ← interface
│   └── snapshot/
│       ├── Snapshot.kt
│       ├── SnapshotId.kt
│       └── SnapshotRepository.kt          ← interface
├── usecase/
│   ├── annotation/
│   │   ├── CreateAnnotationUseCase.kt
│   │   ├── UpdateAnnotationStatusUseCase.kt
│   │   └── DeleteAnnotationUseCase.kt
│   ├── session/
│   │   ├── ActivateSessionUseCase.kt
│   │   ├── PromoteSessionToReadyUseCase.kt
│   │   ├── CloseSessionUseCase.kt
│   │   └── LoadSessionUseCase.kt
│   └── snapshot/
│       ├── SaveSnapshotUseCase.kt
│       └── LoadSnapshotUseCase.kt
├── source/                                ← 기존 유지
├── selection/                             ← 기존 유지
├── format/                                ← 기존 유지
└── redaction/                             ← 기존 유지
```

### 2.3 UseCase 작성 규칙

UseCase는 **단 하나의 invoke / execute** 함수를 갖는 functional class.
의존성은 생성자 주입. 코루틴은 caller가 결정 (suspending function).

```kotlin
// compose-core/usecase/annotation/CreateAnnotationUseCase.kt
class CreateAnnotationUseCase(
    private val sessions: SessionRepository,
    private val annotations: AnnotationRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(
        sessionId: SessionId,
        draft: AnnotationDraft,
    ): Result<Annotation> = runCatching {
        val session = sessions.requireActive(sessionId)
        val annotation = Annotation(
            id = ids.next(),
            sessionId = session.id,
            createdAt = clock.now(),
            status = AnnotationStatus.OPEN,
            ...
        )
        annotations.save(annotation)
        annotation
    }
}
```

### 2.4 Presentation Layer 규칙

- **ViewModel은 도메인 모델을 그대로 노출하지 않는다**. 항상 `*UiState`로 감싼다.
- **Composable은 ViewModel만 본다**. UseCase·Repository는 Composable에서 직접 호출 금지.
- **UI state는 `@Immutable` data class**로, MVI 스타일의 단일 state flow를 권장.

```kotlin
// compose-overlay/studio/ui/AnnotationUiState.kt
@Immutable
data class AnnotationUiState(
    val id: String,
    val number: Int,
    val title: String,
    val statusGroup: AnnotationStatus.Group,
    val isSelected: Boolean,
    val pin: PinUiModel,
)
```

도메인 → UI 변환은 ViewModel 안의 `private fun Annotation.toUi(): AnnotationUiState`로 격리.

---

## 3. 디자인 시스템 완성

현재는 `StudioColors`/`StudioTypography`만 존재. spacing·shape·elevation·진입점·시맨틱 토큰이 없다.

### 3.1 추가 파일 구조

```
fixthis-compose-overlay/.../studio/theme/
├── StudioColors.kt          ← 기존
├── StudioTypography.kt      ← 기존(common/에서 theme/로 이동)
├── StudioSpacing.kt         ← 신규
├── StudioShapes.kt          ← 신규
├── StudioElevation.kt       ← 신규
├── StudioTheme.kt           ← 신규 (단일 진입점)
└── tokens/
    ├── SemanticColors.kt    ← surface/onSurface/border 시맨틱 토큰
    └── StudioDimens.kt      ← 컴포넌트별 고정 치수(예: pinRectSize)
```

### 3.2 `StudioSpacing`

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class StudioSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

internal val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacing() }
```

### 3.3 `StudioShapes`

```kotlin
@Immutable
data class StudioShapes(
    val xs: Shape = RoundedCornerShape(4.dp),    // pin-rect, zb button
    val sm: Shape = RoundedCornerShape(6.dp),    // tool button, ann-row-num
    val md: Shape = RoundedCornerShape(7.dp),    // seg control
    val lg: Shape = RoundedCornerShape(8.dp),    // history-item, brand-mark
    val xl: Shape = RoundedCornerShape(12.dp),   // empty-mark
    val pill: Shape = RoundedCornerShape(50),    // panel-count, status pill
)
```

### 3.4 `StudioElevation`

```kotlin
@Immutable
data class StudioElevation(
    val none: Dp = 0.dp,
    val flat: Dp = 1.dp,
    val raised: Dp = 4.dp,
    val overlay: Dp = 12.dp,
)
```

### 3.5 시맨틱 컬러 토큰

`StudioColors`는 raw 팔레트(예: `gray900`, `accent500`). UI는 raw가 아닌 **시맨틱 토큰**만 본다.

```kotlin
@Immutable
data class StudioSemanticColors(
    val surface: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val border: Color,
    val borderSubtle: Color,
    val accent: Color,
    val accentOn: Color,
    val danger: Color,
    val dangerOn: Color,
    val statusOpen: Color,
    val statusInProgress: Color,
    val statusResolved: Color,
)

fun darkSemanticColors(palette: StudioColors) = StudioSemanticColors(
    surface = palette.gray900,
    surfaceElevated = palette.gray800,
    onSurface = palette.gray050,
    onSurfaceMuted = palette.gray400,
    ...
)
```

이렇게 하면 라이트/다크/하이콘트라스트 테마 추가가 토큰 매핑만으로 끝난다.

### 3.6 단일 진입점 `StudioTheme`

```kotlin
@Composable
fun StudioTheme(
    colors: StudioSemanticColors = darkSemanticColors(StudioColors),
    typography: StudioTypography = StudioTypography(),
    spacing: StudioSpacing = StudioSpacing(),
    shapes: StudioShapes = StudioShapes(),
    elevation: StudioElevation = StudioElevation(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioTypography provides typography,
        LocalStudioSpacing provides spacing,
        LocalStudioShapes provides shapes,
        LocalStudioElevation provides elevation,
    ) {
        content()
    }
}

object StudioTheme {
    val colors: StudioSemanticColors @Composable @ReadOnlyComposable get() = LocalStudioColors.current
    val typography: StudioTypography @Composable @ReadOnlyComposable get() = LocalStudioTypography.current
    val spacing: StudioSpacing @Composable @ReadOnlyComposable get() = LocalStudioSpacing.current
    val shapes: StudioShapes @Composable @ReadOnlyComposable get() = LocalStudioShapes.current
    val elevation: StudioElevation @Composable @ReadOnlyComposable get() = LocalStudioElevation.current
}
```

사용처:

```kotlin
@Composable
fun AnnotationRow(state: AnnotationUiState) {
    Row(
        modifier = Modifier
            .padding(horizontal = StudioTheme.spacing.md, vertical = StudioTheme.spacing.sm)
            .clip(StudioTheme.shapes.sm)
            .background(StudioTheme.colors.surfaceElevated)
    ) { ... }
}
```

### 3.7 하드코딩 제거 계획

**Sprint 단위로 디렉토리별 마이그레이션** (한 번에 다 바꾸지 않는다):

1. `studio/canvas/` 전체 → `StudioTheme.spacing`/`shapes`로 치환
2. `studio/inspector/` 전체
3. `studio/history/` 전체
4. `studio/topbar/` 전체
5. 마지막으로 lint rule 추가: `RoundedCornerShape(N.dp)` 직접 사용을 detekt로 차단.

검증: 각 PR마다 **시각 회귀 스크린샷 테스트** 1회 (Paparazzi 또는 Roborazzi 권장 — 별도 도입 결정 필요).

---

## 4. FeedbackConsoleAssets.kt 분리 (CRITICAL)

### 4.1 현재 문제

`fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`는 **3,198 라인 Kotlin 파일** 안에 HTML/CSS/JS 전체를 raw string으로 박아놓았다.

이로 인해:

- HTML/CSS/JS에 대해 IDE 자동완성·린트·포매터·brace matching 모두 비활성.
- prettier/eslint/stylelint 미적용.
- 유닛 테스트 불가 (브라우저에 띄워야만 검증).
- diff가 사람에게 거의 unreadable.
- Kotlin 컴파일러가 매번 3K 라인 문자열을 처리해야 함 (작지만 누적되는 빌드 비용).

### 4.2 권장 구조

```
fixthis-mcp/
├── src/main/
│   ├── kotlin/io/github/fixthis/mcp/console/
│   │   ├── FeedbackConsoleAssets.kt       ← 단순 ClassLoader resource loader (~30라인)
│   │   └── FeedbackConsoleServer.kt
│   └── resources/
│       └── console/
│           ├── index.html
│           ├── styles.css
│           ├── app.js
│           └── assets/
│               ├── icons.svg
│               └── ...
└── src/test/resources/console/...           ← 필요 시 fixture
```

### 4.3 로더 코드 (After)

```kotlin
package io.beyondwin.fixthis.mcp.console

object FeedbackConsoleAssets {
    private const val BASE = "/console"

    fun indexHtml(): String = readResource("$BASE/index.html")
    fun stylesCss(): String = readResource("$BASE/styles.css")
    fun appJs(): String = readResource("$BASE/app.js")

    fun resource(path: String): ByteArray {
        val safe = path.removePrefix("/").also(::validatePath)
        return checkNotNull(javaClass.getResourceAsStream("$BASE/$safe")) {
            "console asset not found: $safe"
        }.use { it.readAllBytes() }
    }

    private fun readResource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }
            .use { it.bufferedReader(Charsets.UTF_8).readText() }

    private fun validatePath(path: String) {
        require(!path.contains("..")) { "path traversal not allowed: $path" }
    }
}
```

`FeedbackConsoleServer`는 변경 거의 없음. `FeedbackConsoleAssets.indexHtml()`이 그대로 동작.

### 4.4 빌드 통합 (Gradle)

리소스는 자동으로 `processResources` 태스크에 포함되므로 별도 작업 불필요.
다만 **개발 중 라이브 리로드**를 원하면 다음 옵션을 권장.

```kotlin
// fixthis-mcp/build.gradle.kts
val devResources by tasks.registering(Sync::class) {
    from("src/main/resources/console")
    into(layout.buildDirectory.dir("dev/console"))
}

tasks.named<JavaExec>("run") {
    systemProperty("fixthis.console.dir", layout.buildDirectory.dir("dev/console").get().asFile.absolutePath)
    dependsOn(devResources)
}
```

`FeedbackConsoleAssets`는 시스템 프로퍼티가 있으면 디스크에서 읽도록 분기:

```kotlin
private fun readResource(path: String): String {
    val devDir = System.getProperty("fixthis.console.dir")
    if (devDir != null) {
        val file = File(devDir, path.removePrefix("$BASE/"))
        if (file.exists()) return file.readText(Charsets.UTF_8)
    }
    return checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }
        .use { it.bufferedReader(Charsets.UTF_8).readText() }
}
```

### 4.5 마이그레이션 절차

1. 현재 3,198라인 문자열을 **그대로** `index.html`/`styles.css`/`app.js`로 분리. 의미 변경 0.
2. `FeedbackConsoleAssets.kt`를 위 30라인 로더로 교체.
3. `FeedbackConsoleServerTest`의 모든 케이스가 통과하는지 확인 (smoke).
4. 별도 후속 PR에서 prettier/eslint 도입, JS를 ES 모듈로 분리 등 점진 개선.

### 4.6 체감 효과

- 3,198라인 → ~30라인 (Kotlin 파일).
- HTML/CSS/JS는 자체 도구 체인으로 lint/format 가능.
- diff·리뷰 비용 대폭 감소.
- Kotlin 컴파일 시간 미세하게 감소.

---

## 5. FeedbackSessionService.kt 분리

### 5.1 현재 문제 (581라인)

`fixthis-mcp/.../session/FeedbackSessionService.kt`가 다음을 모두 담당한다:

- 세션 라이프사이클 (`activateSession`, `closeSession`, `promoteToReady` 등)
- 파일 I/O 영속화 호출 (`FeedbackSessionStore`로 일부 위임되지만 여전히 직접 접근)
- 프리뷰 스냅샷 인메모리 캐시 (`LinkedHashMap` LRU)
- 소스 인덱스 등록/조회
- 스크린샷 경로 관리
- `synchronized(sessionLock)`, `synchronized(sourceIndexLock)` 두 개의 모니터 락

이는 Single Responsibility Principle 위반이며, 락 관리도 코루틴 친화적이지 않다.

### 5.2 권장 분해

```
fixthis-mcp/.../session/
├── SessionLifecycleService.kt     ← create/activate/close/promote 만
├── SessionRepository.kt           ← interface (compose-core로 이동 권장)
├── SessionRepositoryImpl.kt       ← 파일 영속화 (현 FeedbackSessionStore의 역할 흡수)
├── SnapshotCache.kt               ← LRU 인메모리 캐시
├── SourceIndexRegistry.kt         ← 소스 인덱스 전담
└── ScreenshotPathResolver.kt      ← 경로 계산 전담
```

각 컴포넌트의 책임:

| 컴포넌트 | 책임 | 락/동시성 |
|---|---|---|
| `SessionLifecycleService` | 도메인 상태 전이만 (UseCase 호출 조정) | `Mutex` 1개 |
| `SessionRepositoryImpl` | 디스크 직렬화/역직렬화 | suspend, 내부 `Mutex` |
| `SnapshotCache` | LRU 캐시(thread-safe) | `Mutex` 또는 `ConcurrentHashMap` |
| `SourceIndexRegistry` | 소스 인덱스 lookup | `Mutex` 1개 |
| `ScreenshotPathResolver` | 순수 함수 (path 계산만) | 무상태 |

### 5.3 `synchronized` → `Mutex` 패턴

**Before** (현재 패턴):

```kotlin
private val sessionLock = Any()
private var current: FeedbackSession? = null

fun activate(id: String): FeedbackSession = synchronized(sessionLock) {
    val session = store.load(id) ?: error("not found: $id")
    current = session.copy(status = ACTIVE)
    store.save(current!!)
    current!!
}
```

문제: `store.save`가 disk I/O인데 모니터 락을 잡은 채 블로킹된다. 코루틴 내에서 호출 시 dispatcher 점유.

**After**:

```kotlin
class SessionLifecycleService(
    private val repository: SessionRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var current: Session? = null

    suspend fun activate(id: SessionId): Session = mutex.withLock {
        val loaded = repository.find(id) ?: throw SessionNotFoundException(id)
        val activated = loaded.copy(status = SessionStatus.ACTIVE, activatedAt = clock.now())
        repository.save(activated)
        current = activated
        activated
    }

    suspend fun current(): Session? = mutex.withLock { current }
}
```

이점:

- I/O 중에도 dispatcher가 점유되지 않음 (`repository.save`가 suspend).
- 테스트에서 `runTest` + `TestDispatcher`로 결정적 검증 가능.
- 취소 협조(cooperative cancellation)가 자연스럽게 동작.

### 5.4 캐시 분리 예시

```kotlin
class SnapshotCache(
    private val maxEntries: Int = 32,
) {
    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<SnapshotId, Snapshot>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<SnapshotId, Snapshot>) = size > maxEntries
    }

    suspend fun get(id: SnapshotId): Snapshot? = mutex.withLock { entries[id] }
    suspend fun put(snapshot: Snapshot) = mutex.withLock { entries[snapshot.id] = snapshot }
    suspend fun invalidate(id: SnapshotId) = mutex.withLock { entries.remove(id) }
    suspend fun clear() = mutex.withLock { entries.clear() }
}
```

### 5.5 검증

- 분해 전 통합 테스트(`FeedbackSessionServiceTest` 2,184라인 일부)를 그대로 유지한 채 리팩터링.
- 각 분해 컴포넌트 단위 테스트 추가.
- 통합 테스트는 점진적으로 단위 테스트로 분해.

---

## 6. 대형 Composable 분리

### 6.1 `PreviewSurface.kt` (595라인) 분해

현 파일은 phone frame, semantics overlay, drag gesture, pin rendering, hit test, viewport pan/zoom을 한 함수에 담는다.

**권장 분해 (모두 `studio/canvas/` 안으로 이동, 일부 이미 존재)**:

```
studio/canvas/
├── PreviewSurface.kt          ← orchestration only (<100 라인)
├── PhoneFrame.kt              ← 기존 유지 (phone shell 시각)
├── AnnotationOverlay.kt       ← 신규: PinRect 렌더링 + 번호
├── DragOverlay.kt             ← 기존 DragRect.kt를 확장 (드래그 중 프리뷰)
├── SemanticHitTargets.kt      ← 신규: 위젯 hit area + invisible Box
├── PreviewGestureLayer.kt     ← 신규: pointerInput 핸들링 (선택/드래그)
└── PreviewViewport.kt         ← 신규: zoom/pan transform 관리
```

`PreviewSurface`의 새로운 모습:

```kotlin
@Composable
fun PreviewSurface(
    state: PreviewSurfaceState,
    onAnnotationCreate: (PreviewRect) -> Unit,
    onAnnotationSelect: (AnnotationId) -> Unit,
    modifier: Modifier = Modifier,
) {
    PreviewViewport(state.viewport, modifier) {
        PhoneFrame(state.frame) {
            SemanticHitTargets(state.widgets, onSelect = onAnnotationSelect)
            AnnotationOverlay(state.annotations)
            DragOverlay(state.dragState)
        }
        PreviewGestureLayer(
            mode = state.mode,
            onDragComplete = onAnnotationCreate,
            onTap = onAnnotationSelect,
        )
    }
}
```

각 자식 Composable은 자체 입력 state를 받아 stateless로 동작 — 테스트가 쉬워진다.

### 6.2 `CanvasToolbar.kt` (358라인) 분해

```
studio/canvas/toolbar/
├── CanvasToolbar.kt           ← layout만 (<80라인)
├── ToolSwitcher.kt            ← Select/Annotate 토글
├── ToolStatusBar.kt           ← ts-meta / ts-hint 표시
├── ZoomControl.kt             ← +/-/fit
└── ToolbarDivider.kt
```

```kotlin
@Composable
fun CanvasToolbar(state: ToolbarUiState, onAction: (ToolbarAction) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.spacing.sm),
    ) {
        ToolSwitcher(state.tool, onAction)
        ToolbarDivider()
        ToolStatusBar(state.status)
        Spacer(Modifier.weight(1f))
        ZoomControl(state.zoom, onAction)
    }
}
```

### 6.3 분해 시 룰

- 자식 composable은 **stateless**(state hoisting). state 보유는 ViewModel 또는 부모만.
- 입력은 immutable data class, 출력은 단방향 콜백.
- 각 자식은 `@Preview`를 제공해 디자인 회귀 가능.
- 각 자식에 `Modifier` 파라미터(default `Modifier`)를 둔다.

### 6.4 기타 비대 파일

| 파일 | 현재 | 권장 분해 |
|---|---|---|
| `FixThisOverlayController.kt` (317) | 상태 머신 + 부수효과 | `OverlayStateMachine`(순수) + `OverlayEffectExecutor`(I/O) |
| `BridgeServer.kt` (571) | HTTP + 프로토콜 | `BridgeHttpServer` + `BridgeProtocolHandler` |
| `BridgeClient.kt` (536) | CLI client | 명령별 Service 분리 (`SessionApi`, `SnapshotApi`) |
| `FixThisTools.kt` (797) | 모든 MCP tool 한 파일 | tool 카테고리별 분리 (`SessionTools`, `AnnotationTools`, `SnapshotTools`) |

---

## 7. 모듈 의존성 개선

### 7.1 현재 의존성 그래프

```
sample → compose-sidekick
compose-sidekick → compose-core, compose-overlay
compose-overlay → compose-core
mcp → compose-core, cli
cli → compose-core
gradle-plugin (독립)
```

### 7.2 도메인 모델의 정착지: `compose-core`

`Annotation`/`Snapshot`/`Session`/`AnnotationStatus`/`Severity`는 **반드시 `compose-core`에**.

근거:

- 이미 순수 JVM (Android 의존 없음).
- compose-overlay와 mcp 양쪽에서 import 가능 (위 그래프상 문제없음).
- mcp 모듈은 cli를 통해 간접적으로 compose-core를 본다 — 직접 의존 추가 권장 (이미 있음).

### 7.3 와이어 포맷 DTO는 mcp에

JSON wire format은 **mcp 모듈만의 외부 계약**이다. compose-core는 와이어를 모른다.

```
fixthis-mcp/.../console/dto/
├── AnnotationDto.kt           ← @Serializable
├── AnnotationStatusDto.kt     ← @SerialName으로 wire 호환
├── SnapshotDto.kt
├── SessionDto.kt
└── Mappers.kt                 ← Annotation ↔ AnnotationDto 양방향
```

매퍼는 single function:

```kotlin
fun Annotation.toDto(): AnnotationDto = AnnotationDto(
    id = id.value,
    sessionId = sessionId.value,
    status = status.toDto(),
    ...
)

fun AnnotationDto.toDomain(): Annotation = Annotation(
    id = AnnotationId(id),
    sessionId = SessionId(sessionId),
    status = status.toDomain(),
    ...
)
```

### 7.4 UI state는 compose-overlay에

도메인 → UI 변환은 ViewModel 내부 private extension.

```kotlin
private fun Annotation.toUiState(selection: SelectedId?): AnnotationUiState =
    AnnotationUiState(
        id = id.value,
        title = label,
        statusGroup = status.group,
        isSelected = selection?.value == id.value,
        ...
    )
```

### 7.5 결과 그래프 (변경 후)

```
[domain models live here]
compose-core ← (compose-overlay, compose-sidekick, mcp, cli)

[wire DTOs]
mcp/console/dto (mcp 내부)

[UI states]
compose-overlay/.../ui (compose-overlay 내부)
```

`Annotation` 모델이 **단 한 곳**에만 존재 — 중복·매퍼 누락·동기화 버그 제거.

### 7.6 DI

DI 프레임워크 없이도 충분하지만, 다음 두 가지를 권장:

- 모듈마다 `*Module` object 또는 `*Graph` class를 두어 매뉴얼 와이어링을 한 곳에 모은다.
- Composition Local로는 ViewModel/Repository를 전달하지 않는다 (테스트 곤란). 명시적 파라미터 주입 또는 ViewModel 팩토리.

```kotlin
// compose-overlay/.../studio/StudioGraph.kt
class StudioGraph(
    private val sessionRepository: SessionRepository,
    private val annotationRepository: AnnotationRepository,
    private val snapshotRepository: SnapshotRepository,
    private val clock: Clock,
) {
    val createAnnotation = CreateAnnotationUseCase(sessionRepository, annotationRepository, clock, ::randomId)
    val saveSnapshot = SaveSnapshotUseCase(sessionRepository, snapshotRepository, clock, ::randomId)
    fun studioViewModel(scope: CoroutineScope): StudioViewModel =
        StudioViewModel(createAnnotation, saveSnapshot, ..., scope)
}
```

DI 프레임워크 도입은 모듈 수가 더 늘거나 테스트 그래프가 복잡해질 때 재평가 (현재는 불필요).

---

## 8. 리팩토링 우선순위 및 단계별 계획

### 8.1 Phase 1 — 기반 작업 (2~3 sprint)

**목표**: 행동 변경 없이 기반을 정리. 모든 PR이 "rename / 토큰 도입 / 자산 분리"로만 구성됨.

#### Phase 1.1 — 용어 통일 (1 sprint)

| 작업 | 변경 파일 | 위험 | 검증 |
|---|---|---|---|
| `FeedbackItem` → `Annotation` | mcp/session/*, mcp/console/*, mcp/tools/*, cli/BridgeClient.kt + 대응 테스트 | 중(외부 wire format 호환 유지 필요) | 기존 테스트 전부 통과, MCP 클라이언트 호환 테스트 |
| `CapturedScreen` → `Snapshot` | mcp/session/*, compose-overlay/studio/history/* | 중(파일 시스템 경로 영향 가능) | 마이그레이션 전후 동일 세션 로드 가능 |
| `FeedbackSession*` → `Session*` | mcp/session/* 거의 전체 | 저(파일명만 바뀜) | 컴파일 + 전 테스트 |
| `OverlayMode.Selecting` → `Select` 등 명사화 | compose-overlay, compose-sidekick | 저 | 기존 테스트 |
| Status 6→5 (READY 제거) | mcp/session/*, compose-overlay/studio/* | 중(저장된 세션 호환) | 마이그레이션 코드 + 호환 테스트 |

#### Phase 1.2 — 디자인 시스템 골격 (1 sprint)

| 작업 | 변경 파일 |
|---|---|
| `StudioSpacing`, `StudioShapes`, `StudioElevation` 도입 | studio/theme/* 신규 |
| `StudioTheme` 단일 진입점 | studio/theme/StudioTheme.kt |
| 시맨틱 컬러 토큰 추상화 | studio/theme/tokens/SemanticColors.kt |
| 가장 자주 쓰이는 5~10개 컴포넌트만 토큰 사용으로 마이그레이션 | studio/canvas/CanvasToolbar.kt, studio/inspector/AnnotationRow.kt 등 |

이번 스프린트는 **기존 컴포저블 전부를 마이그레이션하지 않는다**. 토큰 시스템을 검증할 정도만.

#### Phase 1.3 — `FeedbackConsoleAssets` 분리 (0.5~1 sprint)

| 작업 | 변경 파일 | 위험 | 검증 |
|---|---|---|---|
| HTML/CSS/JS를 resources/console/로 이동 | mcp/src/main/resources/console/* 신규 | 저 | `FeedbackConsoleServerTest` 전체 통과 |
| `FeedbackConsoleAssets.kt`를 30라인 로더로 교체 | mcp/console/FeedbackConsoleAssets.kt | 저 | 동일 |
| 개발 라이브 리로드 옵션 (선택) | mcp/build.gradle.kts | 저 | 수동 smoke |

### 8.2 Phase 2 — 아키텍처 (3~4 sprint)

**목표**: 도메인 레이어 정착, Repository 인터페이스 도입, `FeedbackSessionService` 분해.

#### Phase 2.1 — 도메인 모델을 compose-core로 (1 sprint)

- `Annotation`/`Snapshot`/`Session` 도메인 클래스를 compose-core/domain/에 정의.
- compose-overlay의 `Annotation`은 `AnnotationUiState`로 개명.
- mcp의 `Annotation`(구 `FeedbackItem`)은 `AnnotationDto`로 개명, 매퍼 추가.
- 위험: 두 모델 정의가 같이 존재하는 중간 단계가 짧게 발생 — 한 PR에 끝낸다.

#### Phase 2.2 — Repository 인터페이스 도입 (1 sprint)

- `SessionRepository`, `AnnotationRepository`, `SnapshotRepository` 인터페이스 정의.
- 기존 `FeedbackSessionStore`를 `SessionRepositoryImpl`로 리네임 + 인터페이스 구현.
- 호출부에서 인터페이스 타입으로 의존성 주입.

#### Phase 2.3 — `FeedbackSessionService` 분해 (1~2 sprint)

- `SessionLifecycleService`, `SnapshotCache`, `SourceIndexRegistry`, `ScreenshotPathResolver`로 분리.
- UseCase 도입: `CreateAnnotationUseCase`, `SaveSnapshotUseCase`, `LoadSessionUseCase` 등.
- 호출부(MCP tools, CLI BridgeClient) 마이그레이션.

#### Phase 2 검증

- 분해 전 통합 테스트(`FeedbackSessionServiceTest`)를 유지한 채 진행. 마지막에 단위 테스트로 분해.
- MCP wire format 호환 회귀 테스트 추가 (실제 클라이언트 JSON 샘플로).

### 8.3 Phase 3 — 품질 (2 sprint)

#### Phase 3.1 — 동시성/Composable 정리 (1 sprint)

| 작업 | 변경 파일 |
|---|---|
| `synchronized` → `Mutex` 일괄 교체 | mcp/session/*, compose-sidekick/overlay/* |
| `PreviewSurface` 분해 | studio/canvas/* |
| `CanvasToolbar` 분해 | studio/canvas/toolbar/* |
| `OverlayMode` Error/Loading 추가 | compose-overlay/OverlayMode.kt + 관련 컨트롤러 |

#### Phase 3.2 — 테스트 인프라 (1 sprint)

- 공용 테스트 픽스처: `AnnotationFactory`, `SnapshotFactory`, `SessionFactory`, `FakeSessionRepository`.
- `FeedbackConsoleServerTest`(2,184), `McpProtocolTest`(1,225)를 도메인별로 분해.
- `FeedbackSessionStore` 기반 통합 테스트 → `FakeSessionRepository` 기반 단위 테스트로 다수 이관.
- Paparazzi/Roborazzi 도입 결정 (스크린샷 회귀).

### 8.4 절대 동시에 하지 말 것

- "용어 통일 + 도메인 분리"를 한 PR에 묶지 마라. 리뷰어가 길을 잃는다.
- "Composable 분해"와 "Theme 마이그레이션"을 같은 컴포넌트에서 동시 진행하지 마라.
- Phase 1.3(asset 분리) 전에 HTML 내용을 손대지 마라. 분리 후 손대라.

---

## 9. OverlayMode 개선

### 9.1 현재

```kotlin
sealed class OverlayMode {
    object Idle : OverlayMode()
    object Selecting : OverlayMode()
    data class Annotating(val target: Target) : OverlayMode()
    data class Captured(val result: CaptureResult) : OverlayMode()
    ...
}
```

문제:

- 에러 상태(스크린샷 실패, 권한 거부 등)가 없음 → 호출부가 ad-hoc로 처리.
- 로딩 상태(스크린샷 캡처 중)가 없음 → UI가 즉각 반응할 표현 없음.
- 전이 검증 없음 — `Idle`에서 `Captured`로 직접 가도 컴파일은 성공.
- 명사/동명사 혼용 (`Selecting`, `Annotating`).

### 9.2 권장 정의

```kotlin
sealed interface OverlayMode {
    data object Idle : OverlayMode

    data object Select : OverlayMode

    data class Loading(val reason: LoadingReason) : OverlayMode {
        enum class LoadingReason { SCREENSHOT_CAPTURING, INSPECTOR_QUERYING, BRIDGE_CONNECTING }
    }

    data class Annotate(val target: AnnotationTarget) : OverlayMode

    data class Captured(val result: CaptureResult) : OverlayMode

    data class Error(
        val cause: OverlayError,
        val recoverable: Boolean,
    ) : OverlayMode

    sealed interface OverlayError {
        data object PermissionDenied : OverlayError
        data class ScreenshotFailed(val reason: String) : OverlayError
        data class BridgeUnreachable(val reason: String) : OverlayError
        data class Timeout(val operation: String, val timeoutMs: Long) : OverlayError
    }
}
```

### 9.3 전이 다이어그램 (텍스트)

```
                +-----------+
                |   Idle    |
                +-----+-----+
                      |
                start |
                      v
                +-----+-----+    confirm     +-----------+
                |  Select   +--------------->|  Loading  |
                +-----+--+--+                |(SCREENSHOT|
                      |  ^                   | CAPTURING)|
                cancel|  |                   +-----+-----+
                      |  |                         |
                      v  |                  success|     fail
                +-----+--+--+                      v        v
                |   Idle    |<---+        +--------+----+  +------+
                +-----------+    |        |  Captured   |  |Error |
                                 |        +-----+-------+  +--+---+
                                 |              |             |
                                 |     annotate |     retry   |
                                 |              v             |
                                 |        +-----+----+        |
                                 +--------+ Annotate |<-------+
                                cancel    +----------+

                Error 상태에서 recoverable=true 면 직전 상태로 복귀 가능.
                Error 상태에서 recoverable=false 면 Idle 로만 복귀.
```

### 9.4 전이 검증

전이 규칙은 별도 함수에서 검증.

```kotlin
fun OverlayMode.canTransitionTo(next: OverlayMode): Boolean = when (this) {
    is OverlayMode.Idle -> next is OverlayMode.Select
    is OverlayMode.Select -> next is OverlayMode.Loading || next is OverlayMode.Idle
    is OverlayMode.Loading -> next is OverlayMode.Captured || next is OverlayMode.Error
    is OverlayMode.Captured -> next is OverlayMode.Annotate || next is OverlayMode.Idle
    is OverlayMode.Annotate -> next is OverlayMode.Idle
    is OverlayMode.Error -> next is OverlayMode.Idle || (recoverable && next !is OverlayMode.Error)
}

class OverlayStateMachine(private val initial: OverlayMode = OverlayMode.Idle) {
    private val _state = MutableStateFlow<OverlayMode>(initial)
    val state: StateFlow<OverlayMode> = _state.asStateFlow()

    fun transition(next: OverlayMode) {
        val current = _state.value
        check(current.canTransitionTo(next)) {
            "invalid transition: $current -> $next"
        }
        _state.value = next
    }
}
```

테스트가 매우 쉬워진다. 잘못된 전이는 `IllegalStateException`으로 바로 잡힌다.

### 9.5 타임아웃 처리

`Loading` 상태에 진입할 때 마다 코루틴 타임아웃을 걸고, 만료 시 `Error.Timeout`으로 전이.

```kotlin
suspend fun captureWithTimeout(timeoutMs: Long = 5_000): Result<Snapshot> = runCatching {
    withTimeout(timeoutMs) {
        machine.transition(OverlayMode.Loading(SCREENSHOT_CAPTURING))
        val snapshot = screenshotPort.capture()
        machine.transition(OverlayMode.Captured(snapshot.toCaptureResult()))
        snapshot
    }
}.recoverCatching { e ->
    val error = when (e) {
        is TimeoutCancellationException -> OverlayError.Timeout("capture", timeoutMs)
        else -> OverlayError.ScreenshotFailed(e.message ?: "unknown")
    }
    machine.transition(OverlayMode.Error(error, recoverable = true))
    throw e
}
```

---

## 10. 생산성 도구 제안

### 10.1 KDoc 가이드라인

**대상**: 모든 public class·function·property (특히 `compose-core/domain` 전체).

규칙:

- 첫 줄 = 한 줄 요약 (마침표로 끝).
- 빈 줄 후 상세 설명 (필요 시).
- `@param`, `@return`, `@throws`는 의미가 자명하지 않은 경우에만.
- 불변/스레드 안전성 등 **계약은 반드시 명시**.

```kotlin
/**
 * 단일 어노테이션의 도메인 모델. 불변(Immutable).
 *
 * UI에 노출하기 전에 ViewModel에서 [AnnotationUiState]로 변환한다.
 * 와이어 포맷으로 직렬화할 때는 [AnnotationDto]로 변환한다.
 *
 * @property id 전역 유일. 세션 간 재사용 금지.
 * @property status 현재 상태. UI 그룹은 [AnnotationStatus.group]으로 조회.
 */
data class Annotation(
    val id: AnnotationId,
    val sessionId: SessionId,
    val status: AnnotationStatus,
    ...
)
```

### 10.2 Lint 규칙 (detekt 권장)

`detekt.yml`에 다음 사용자 규칙 추가:

| 규칙 | 검출 | 권장 |
|---|---|---|
| `ForbiddenLegacyTermFeedbackItem` | `FeedbackItem` 식별자 사용 | `Annotation` 사용 |
| `ForbiddenLegacyTermCapturedScreen` | `CapturedScreen` 사용 | `Snapshot` 사용 |
| `RawCornerShape` | `RoundedCornerShape(N.dp)` 직접 사용 | `StudioTheme.shapes.*` 사용 |
| `RawSpacingDp` | `.padding(N.dp)` (N != 0) | `StudioTheme.spacing.*` 사용 |
| `SynchronizedBlock` | `synchronized { }` | `Mutex.withLock { }` |
| `DomainModelInComposable` | Composable 시그니처에 `Annotation`/`Session` 등장 | UI state 사용 |
| `WildcardImport` | `import x.*` | 명시적 import |

baseline 파일을 함께 커밋해 점진 적용 (legacy 코드는 baseline에 등록, 신규 코드만 차단).

### 10.3 ADR (Architecture Decision Record)

`docs/adr/` 디렉토리 신설.

```
docs/adr/
├── README.md                                          ← ADR 색인 + 작성 규칙
├── 0001-use-clean-architecture-layering.md
├── 0002-domain-models-live-in-compose-core.md
├── 0003-naming-feedback-item-to-annotation.md
├── 0004-feedback-console-assets-as-resources.md
├── 0005-mutex-over-synchronized.md
└── 0006-overlay-mode-state-machine.md
```

ADR 템플릿:

```markdown
# ADR-XXXX: <Title>

- 상태: Proposed | Accepted | Deprecated | Superseded by ADR-YYYY
- 작성일: YYYY-MM-DD
- 결정자: <names>

## Context
무엇을 결정해야 했는가, 왜.

## Decision
무엇을 결정했는가 (한 문장).

## Consequences
- 긍정: ...
- 부정: ...
- 후속 작업: ...

## Alternatives Considered
- <대안1> — 기각 사유
- <대안2> — 기각 사유
```

본 제안서가 머지되면 ADR-0001 ~ 0006이 즉시 생성되어야 한다.

### 10.4 PR 템플릿 보강

`.github/pull_request_template.md` 추가 또는 보강:

```markdown
## 변경 요약

## 영향 레이어
- [ ] Domain (compose-core/domain)
- [ ] UseCase (compose-core/usecase)
- [ ] Data (Repository impl)
- [ ] Presentation (composable, ViewModel)
- [ ] Bridge / MCP / CLI

## 체크리스트
- [ ] 기존 용어(`FeedbackItem`, `CapturedScreen`) 도입 없음
- [ ] 도메인 모델을 Composable 시그니처에 노출하지 않음
- [ ] 하드코딩 dp/RoundedCornerShape 도입 없음
- [ ] `synchronized` 도입 없음 (Mutex 사용)
- [ ] 신규 public API에 KDoc 추가
- [ ] 관련 ADR 링크 (필요 시)
```

### 10.5 Gradle 모듈 그래프 검증

`com.github.dependency-graph` 또는 `dependency-analysis-android-gradle-plugin` 도입을 권장.
"compose-core가 외부 모듈을 의존하지 않는다"를 CI에서 강제할 수 있다.

```kotlin
// build.gradle.kts (root)
dependencyAnalysis {
    issues {
        all {
            onAny { severity("fail") }
        }
        project(":fixthis-compose-core") {
            // compose-core는 Android, ktor 등 외부 의존 금지
        }
    }
}
```

---

## 부록 A. 마이그레이션 체크리스트 (한눈에)

- [ ] **Phase 1.1** `FeedbackItem` → `Annotation` 일괄 rename (단일 PR)
- [ ] **Phase 1.1** `CapturedScreen` → `Snapshot` 일괄 rename
- [ ] **Phase 1.1** `FeedbackSession*` → `Session*` 일괄 rename
- [ ] **Phase 1.1** `OverlayMode.Selecting` → `Select` 등 명사화
- [ ] **Phase 1.1** `AnnotationStatus` 6→5 정리
- [ ] **Phase 1.2** `StudioSpacing`/`Shapes`/`Elevation` 추가
- [ ] **Phase 1.2** `StudioTheme` 단일 진입점
- [ ] **Phase 1.2** 시맨틱 컬러 토큰 도입
- [ ] **Phase 1.3** `FeedbackConsoleAssets` 리소스 분리 (3,198 → 30라인)
- [ ] **Phase 2.1** 도메인 모델을 `compose-core/domain`으로 이동
- [ ] **Phase 2.1** `AnnotationDto` + 매퍼 추가
- [ ] **Phase 2.1** `AnnotationUiState` 추가
- [ ] **Phase 2.2** `SessionRepository`/`AnnotationRepository`/`SnapshotRepository` 인터페이스
- [ ] **Phase 2.3** `FeedbackSessionService` 분해
- [ ] **Phase 2.3** UseCase 도입 (`CreateAnnotationUseCase` 등)
- [ ] **Phase 3.1** `synchronized` → `Mutex`
- [ ] **Phase 3.1** `PreviewSurface` 분해
- [ ] **Phase 3.1** `CanvasToolbar` 분해
- [ ] **Phase 3.1** `OverlayMode` Error/Loading + 전이 검증
- [ ] **Phase 3.2** 테스트 픽스처 도입
- [ ] **Phase 3.2** 거대 테스트 분해
- [ ] **Phase 3.2** 스크린샷 회귀(Paparazzi) 도입 결정
- [ ] **상시** detekt 사용자 규칙
- [ ] **상시** ADR 0001~0006 작성
- [ ] **상시** PR 템플릿

---

## 부록 B. 권장하지 않는 것

- **DI 프레임워크(Hilt/Koin) 즉시 도입**: 매뉴얼 와이어링이 아직 깔끔하다. 모듈/유스케이스 수가 두 배 이상 늘 때 재평가.
- **즉각적인 KMP 전환**: compose-core가 KMP 후보지만, 현재는 JVM-only로 충분하고 Android 전용 의존이 없다는 사실만 유지하면 된다.
- **MVI 라이브러리(Orbit, MVIKotlin) 도입**: 단일 `StateFlow<UiState>` + `sealed interface Action` 패턴이면 충분. 라이브러리는 학습 비용이 ROI를 못 따라간다.
- **GraphQL/Protobuf 전환**: 현재 KotlinX Serialization JSON으로 충분. 와이어 효율이 문제될 때 재평가.

---

## 부록 C. 핵심 권장사항 요약 (의사결정자용)

| 순위 | 작업 | 영향 | 비용 |
|---|---|---|---|
| 1 | `FeedbackConsoleAssets.kt` 분리 | 매우 큼 (DX 즉시 개선) | 0.5~1 sprint |
| 2 | 용어 통일 (Annotation/Snapshot) | 큼 (이후 모든 작업의 전제) | 1 sprint |
| 3 | 도메인 모델을 compose-core로 통합 | 큼 (중복/매퍼 제거) | 1 sprint |
| 4 | `StudioTheme` + spacing/shapes | 중 (UI 일관성) | 1 sprint |
| 5 | `FeedbackSessionService` 분해 + Mutex | 중 (확장성/테스트성) | 1~2 sprint |
| 6 | `PreviewSurface`/`CanvasToolbar` 분해 | 중 (UI 변경 안정성) | 1 sprint |
| 7 | `OverlayMode` Error/Loading | 작 (현장 안정성) | 0.5 sprint |
| 8 | 테스트 픽스처 + 거대 테스트 분해 | 작 (장기 유지보수) | 1 sprint |

**총 예상 기간**: 7~9 sprint (약 3.5~4.5개월, 1주 sprint 기준).
**병렬화 가능**: Phase 1.2(테마)와 Phase 1.3(asset)은 다른 사람이 동시 진행 가능.

---

본 제안서는 `docs/adr/0001-use-clean-architecture-layering.md` 등 ADR로 분할 등록되어야 정식 채택된다.
ADR 작성과 Phase 1.1 PR 시작은 동일 sprint에 함께 진행할 것을 권한다.
