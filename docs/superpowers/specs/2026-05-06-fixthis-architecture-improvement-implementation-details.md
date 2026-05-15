# FixThis Architecture Improvement Implementation Details

- 작성일: 2026-05-06
- 대상: FixThis Android 프로젝트 전체
- 원본 제안서: `docs/superpowers/specs/2026-05-06-architecture-improvement-proposal.md`
- 실행 계획서: `docs/superpowers/plans/2026-05-06-fixthis-architecture-improvement-implementation.md`
- 성격: 상세 구현 명세. 제안서의 권고를 현재 코드 구조에 맞춰 실행 가능한 아키텍처 스펙으로 재구성한다.

---

## 1. 목적

FixThis는 현재 기능 단위로는 잘 작동하지만, feedback console, MCP session, Compose Studio UI, sidekick overlay가 같은 도메인 개념을 다른 이름과 다른 모델로 다루고 있다. 이 문서는 다음 결과를 목표로 한다.

1. 사용자 피드백 단위를 `Annotation`, 화면 캡처 단위를 `Snapshot`, 작업 묶음을 `Session`으로 통일한다.
2. `compose-core`를 순수 도메인 및 use case의 중심으로 고정한다.
3. MCP JSON, 파일 영속화, 브라우저 콘솔 asset, Compose UI state를 각각 외부 레이어로 분리한다.
4. `FeedbackConsoleAssets.kt`, `FeedbackSessionService.kt`, `PreviewSurface.kt`, `CanvasToolbar.kt`의 책임을 작고 테스트 가능한 단위로 나눈다.
5. 기존 persisted session과 MCP JSON wire contract를 깨지 않는다.

## 2. 현재 코드 기준 관찰

검토 기준 커밋 근처의 실제 코드 수치는 다음과 같다.

| 파일 | 현재 역할 | 문제 신호 |
|---|---|---|
| `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt` | HTML, CSS, JavaScript 전체를 Kotlin raw string으로 보관 | 3,198 lines, diff와 lint가 거의 불가능 |
| `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt` | session lifecycle, preview cache, source index, screenshot artifact, item construction | 581 lines, 동시성 lock과 I/O 책임 혼재 |
| `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewSurface.kt` | screenshot rendering, gesture, drag, overlay, bitmap decode | 595 lines, UI state와 gesture 책임 혼재 |
| `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/CanvasToolbar.kt` | tool switch, status, zoom, layout | 358 lines, 하위 컨트롤 분리 부족 |
| `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt` | console HTML, API, session flow 테스트 | 2,184 lines, 테스트 fixture와 assertion이 한 파일에 집중 |

추가로 `compose-core`에는 이미 `FixThisAnnotation`이 존재한다. 이 모델은 sidekick capture payload이며, 새 feedback-domain `Annotation`과 의미가 다르다. 따라서 새 도메인 모델은 `io.github.beyondwin.fixthis.compose.core.domain.annotation.Annotation`으로 두고, 기존 `FixThisAnnotation`은 첫 번째 도메인 PR에서 건드리지 않는다.

## 3. 범위

### 포함

- console asset resource 분리
- Studio theme token 완성
- feedback-domain model 추가
- MCP DTO와 domain model 사이 mapper 추가
- repository interface와 use case 추가
- session service 내부 책임 분해
- overlay mode 명사화와 state transition 검증
- 대형 composable 분리
- ADR 및 PR template guardrail 추가

### 제외

- Hilt, Koin 같은 DI framework 도입
- KMP 전환
- GraphQL, Protobuf 전환
- Detekt custom rule 즉시 도입
- Paparazzi, Roborazzi 즉시 도입
- MCP JSON field name migration
- 기존 `FixThisAnnotation` capture payload migration

제외 항목은 아키텍처 경계가 안정화된 뒤 별도 ADR로 결정한다.

## 4. 설계 원칙

1. **도메인 안쪽으로만 의존한다.** `compose-core`는 MCP, CLI, Android UI, `.fixthis` 파일 구조를 알지 않는다.
2. **wire DTO는 외부 계약이다.** JSON 필드명과 `@SerialName`은 MCP 모듈에서만 관리한다.
3. **UI state는 domain model이 아니다.** Compose 화면은 `AnnotationUiState`, `SnapshotUiState`, toolbar state처럼 화면 전용 모델을 사용한다.
4. **rename과 behavior change를 분리한다.** 특히 `READY` status 제거는 persisted data migration이므로 mapper compatibility 이후 진행한다.
5. **resource extraction은 behavior preserving이어야 한다.** console HTML의 DOM contract와 API call flow는 asset 분리 PR에서 바뀌지 않는다.
6. **동시성 개선은 책임 분해 이후 적용한다.** lock primitive만 바꾸는 작업은 유지보수성을 충분히 개선하지 못한다.

## 5. 목표 레이어

```text
Presentation
  fixthis-compose-overlay
  sample app
  StudioViewModel, Composable, UI state

Application
  fixthis-compose-core/usecase
  CreateAnnotationUseCase, SaveSnapshotUseCase, LoadSessionUseCase

Domain
  fixthis-compose-core/domain
  Annotation, Snapshot, Session, Repository interfaces

Data and Integration
  fixthis-mcp/session
  fixthis-mcp/console
  fixthis-compose-sidekick
  DTO, persistence, bridge, screenshot artifacts, browser console
```

의존 방향:

```text
compose-overlay -> compose-core
compose-sidekick -> compose-core, compose-overlay
fixthis-mcp -> compose-core, fixthis-cli
fixthis-cli -> compose-core
compose-core -> no project module dependency
```

## 6. 용어와 모델 taxonomy

| 개념 | Domain name | MCP DTO name | 기존 이름 | UI name |
|---|---|---|---|---|
| 사용자 피드백 단위 | `Annotation` | `AnnotationDto` | `FeedbackItem` | annotation row, pin |
| 저장된 화면 | `Snapshot` | `SnapshotDto` | `CapturedScreen` | snapshot, evidence |
| 작업 묶음 | `Session` | `SessionDto` | `FeedbackSession` | session |
| 임시 작성 항목 | `AnnotationDraft` | `AnnotationDraftDto` | `PendingDraftFeedbackItem` | pending annotation |
| 상태 | `AnnotationStatus` | `AnnotationStatusDto` | `FeedbackItemStatus` | grouped status |
| 전달 묶음 | `SessionHandoffBatch` | `SessionHandoffBatchDto` | `FeedbackHandoffBatch` | handoff history |

### 6.1 기존 `FixThisAnnotation` 유지

`FixThisAnnotation`은 Android sidekick에서 Compose semantics, tap point, screenshot crop, source hints를 캡처한 payload다. 이 모델은 feedback console session의 annotation과 다르다.

규칙:

- `FixThisAnnotation`은 `compose-core/model`에 남긴다.
- feedback-domain `Annotation`은 `compose-core/domain/annotation`에 추가한다.
- 두 모델 간 직접 rename은 하지 않는다.
- sidekick capture flow가 MCP session으로 저장되는 별도 boundary가 필요해질 때 mapper를 추가한다.

## 7. Domain 상세

### 7.1 ID value class

```kotlin
package io.github.beyondwin.fixthis.compose.core.domain.common

@JvmInline
value class AnnotationId(val value: String) {
    init {
        require(value.isNotBlank()) { "AnnotationId must not be blank" }
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

@JvmInline
value class SnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "SnapshotId must not be blank" }
    }
}
```

### 7.2 Annotation status

도메인 상태는 5개로 정리한다.

```kotlin
enum class AnnotationStatus(val group: Group) {
    OPEN(Group.OPEN),
    IN_PROGRESS(Group.IN_PROGRESS),
    RESOLVED(Group.RESOLVED),
    WONT_FIX(Group.RESOLVED),
    NEEDS_CLARIFICATION(Group.OPEN);

    enum class Group {
        OPEN,
        IN_PROGRESS,
        RESOLVED,
    }
}
```

`READY`는 domain status가 아니다. 기존 JSON `"ready"`는 `AnnotationStatusDto.READY`로 decode한 뒤 mapper에서 `AnnotationStatus.OPEN`으로 normalize한다. 새 저장 경로는 `"ready"`를 쓰지 않는다.

### 7.3 Annotation

```kotlin
data class Annotation(
    val id: AnnotationId,
    val sessionId: SessionId,
    val snapshotId: SnapshotId,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: AnnotationTarget,
    val selectedNode: FixThisNode? = null,
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: SnapshotScreenshot? = null,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: AnnotationDelivery = AnnotationDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: AnnotationStatus = AnnotationStatus.OPEN,
    val agentSummary: String? = null,
)

sealed interface AnnotationTarget {
    data class Node(val nodeUid: String, val boundsInWindow: FixThisRect) : AnnotationTarget
    data class Area(val boundsInWindow: FixThisRect) : AnnotationTarget
}

enum class AnnotationDelivery {
    DRAFT,
    SENT,
}
```

### 7.4 Snapshot

```kotlin
data class Snapshot(
    val id: SnapshotId,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshot? = null,
    val roots: List<SnapshotRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<FixThisError> = emptyList(),
)

data class SnapshotRoot(
    val rootIndex: Int,
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode> = emptyList(),
    val unmergedNodes: List<FixThisNode> = emptyList(),
)

data class SnapshotScreenshot(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null,
)
```

### 7.5 Session

```kotlin
data class Session(
    val id: SessionId,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val snapshots: List<Snapshot> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val handoffBatches: List<SessionHandoffBatch> = emptyList(),
    val status: SessionStatus = SessionStatus.ACTIVE,
)

enum class SessionStatus {
    ACTIVE,
    READY_FOR_AGENT,
    CLOSED,
}
```

## 8. Repository와 use case

Repository interface는 domain package에 둔다.

```kotlin
interface SessionRepository {
    suspend fun find(id: SessionId): Session?
    suspend fun save(session: Session): Session
}

interface AnnotationRepository {
    suspend fun save(annotation: Annotation): Annotation
}

interface SnapshotRepository {
    suspend fun find(id: SnapshotId): Snapshot?
    suspend fun save(snapshot: Snapshot): Snapshot
}
```

Use case는 `compose-core/usecase`에 두며, 생성자 주입과 단일 `invoke`를 사용한다.

```kotlin
class CreateAnnotationUseCase(
    private val sessions: SessionRepository,
    private val annotations: AnnotationRepository,
    private val clock: () -> Long,
    private val idGenerator: () -> AnnotationId,
) {
    suspend operator fun invoke(
        sessionId: SessionId,
        snapshotId: SnapshotId,
        target: AnnotationTarget,
        comment: String,
    ): Annotation {
        require(comment.isNotBlank()) { "Annotation comment must not be blank" }
        val session = sessions.find(sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${sessionId.value}")
        val now = clock()
        val annotation = Annotation(
            id = idGenerator(),
            sessionId = session.id,
            snapshotId = snapshotId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            target = target,
            comment = comment,
        )
        return annotations.save(annotation)
    }
}
```

## 9. MCP DTO와 compatibility

MCP DTO는 기존 serialized field name을 유지한다. class name만 domain 용어와 맞춘다.

| Existing class | Target DTO class | JSON field compatibility |
|---|---|---|
| `FeedbackSession` | `SessionDto` | `screens`, `items`, `sessionId` 유지 |
| `CapturedScreen` | `SnapshotDto` | `screenId`, `capturedAtEpochMillis` 유지 |
| `FeedbackItem` | `AnnotationDto` | `itemId`, `screenId`, `comment` 유지 |
| `FeedbackTarget` | `AnnotationTargetDto` | `"semantics_node"`, `"visual_area"` 유지 |
| `FeedbackItemStatus` | `AnnotationStatusDto` | `"ready"` decode 유지 |

### 9.1 Status mapper

```kotlin
private fun AnnotationStatusDto.toDomainStatus(): AnnotationStatus =
    when (this) {
        AnnotationStatusDto.OPEN,
        AnnotationStatusDto.READY -> AnnotationStatus.OPEN
        AnnotationStatusDto.IN_PROGRESS -> AnnotationStatus.IN_PROGRESS
        AnnotationStatusDto.RESOLVED -> AnnotationStatus.RESOLVED
        AnnotationStatusDto.NEEDS_CLARIFICATION -> AnnotationStatus.NEEDS_CLARIFICATION
        AnnotationStatusDto.WONT_FIX -> AnnotationStatus.WONT_FIX
    }

private fun AnnotationStatus.toDto(): AnnotationStatusDto =
    when (this) {
        AnnotationStatus.OPEN -> AnnotationStatusDto.OPEN
        AnnotationStatus.IN_PROGRESS -> AnnotationStatusDto.IN_PROGRESS
        AnnotationStatus.RESOLVED -> AnnotationStatusDto.RESOLVED
        AnnotationStatus.NEEDS_CLARIFICATION -> AnnotationStatusDto.NEEDS_CLARIFICATION
        AnnotationStatus.WONT_FIX -> AnnotationStatusDto.WONT_FIX
    }
```

### 9.2 Compatibility tests

필수 테스트:

- legacy `"ready"` status decode
- `SessionDto` encode 결과에 `screens`, `items`, `screenId`, `itemId`가 남아 있는지
- DTO -> domain -> DTO round trip이 기존 session id와 screen id를 유지하는지
- domain `AnnotationStatus.OPEN`을 저장할 때 새 DTO가 `"ready"`를 쓰지 않는지

## 10. Console asset 분리

현재 `FeedbackConsoleAssets.kt`는 Kotlin raw string 하나가 browser console 전체를 가진다. 목표 구조:

```text
fixthis-mcp/src/main/
  kotlin/io/github/fixthis/mcp/console/
    FeedbackConsoleAssets.kt
    FeedbackConsoleServer.kt
  resources/console/
    index.html
    styles.css
    app.js
```

### 10.1 Loader contract

`FeedbackConsoleAssets.indexHtml`는 기존 테스트 호환을 위해 CSS와 JavaScript를 inline으로 조립해 반환한다.

```kotlin
internal object FeedbackConsoleAssets {
    private const val BasePath = "/console"
    private const val StylesPlaceholder = "<!-- FIXTHIS_STYLES -->"
    private const val ScriptPlaceholder = "<!-- FIXTHIS_SCRIPT -->"

    val indexHtml: String by lazy {
        readText("index.html")
            .replace(StylesPlaceholder, "<style>\n${readText("styles.css")}\n</style>")
            .replace(ScriptPlaceholder, "<script>\n${readText("app.js")}\n</script>")
    }

    fun resource(path: String): ByteArray {
        val safePath = path.removePrefix("/").also(::validateResourcePath)
        return checkNotNull(javaClass.getResourceAsStream("$BasePath/$safePath")) {
            "console asset not found: $safePath"
        }.use { input -> input.readAllBytes() }
    }

    private fun readText(path: String): String =
        resource(path).toString(Charsets.UTF_8)

    private fun validateResourcePath(path: String) {
        require(path.isNotBlank()) { "console asset path must not be blank" }
        require(!path.contains("..")) { "path traversal not allowed: $path" }
        require(!path.startsWith("/")) { "absolute asset paths are not allowed: $path" }
    }
}
```

Asset split PR은 DOM, styles, JavaScript behavior를 바꾸지 않는다.

## 11. Studio design system

현재 `StudioColors`와 `StudioType`은 존재하지만 spacing, shape, elevation, semantic color entry point가 없다.

목표 파일:

```text
fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/
  StudioColors.kt
  StudioSemanticColors.kt
  StudioSpacing.kt
  StudioShapes.kt
  StudioElevation.kt
  StudioTheme.kt
```

### 11.1 Tokens

```kotlin
@Immutable
internal data class StudioSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
internal data class StudioShapes(
    val xs: Shape = RoundedCornerShape(4.dp),
    val sm: Shape = RoundedCornerShape(6.dp),
    val md: Shape = RoundedCornerShape(7.dp),
    val lg: Shape = RoundedCornerShape(8.dp),
    val xl: Shape = RoundedCornerShape(12.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
)

@Immutable
internal data class StudioElevation(
    val none: Dp = 0.dp,
    val flat: Dp = 1.dp,
    val raised: Dp = 4.dp,
    val overlay: Dp = 12.dp,
)
```

### 11.2 Theme entry point

Expose `StudioTheme` composable plus a token accessor object. Use a name that does not collide with the composable function.

```kotlin
@Composable
internal fun StudioTheme(
    colors: StudioSemanticColors = darkStudioSemanticColors(),
    spacing: StudioSpacing = StudioSpacing(),
    shapes: StudioShapes = StudioShapes(),
    elevation: StudioElevation = StudioElevation(),
    content: @Composable () -> Unit,
)

internal object StudioThemeTokens {
    val colors: StudioSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSemanticColors.current

    val spacing: StudioSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSpacing.current
}
```

First migration targets:

- `studio/canvas/CanvasToolbar.kt`
- `studio/inspector/AnnotationRow.kt`
- `studio/common/Buttons.kt`
- `studio/common/PanelHead.kt`

## 12. Session service decomposition

### 12.1 Target components

```text
fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/
  FeedbackSessionService.kt
  FeedbackSessionStore.kt
  SessionDomainMappers.kt
  PreviewSnapshotCache.kt
  SourceIndexRegistry.kt
  ScreenshotArtifactPromoter.kt
```

| Component | Responsibility |
|---|---|
| `FeedbackSessionService` | Public orchestration facade for existing MCP callers |
| `FeedbackSessionStore` | Existing in-memory and persistence-backed session mutation |
| `SessionDomainMappers` | DTO-domain conversion |
| `PreviewSnapshotCache` | LRU preview record lookup and eviction |
| `SourceIndexRegistry` | per-package source index cache |
| `ScreenshotArtifactPromoter` | preview cache image files to persisted session artifact directory |

### 12.2 Preview cache

Keep this component synchronous in the first extraction because current `FeedbackSessionService` public methods are mostly synchronous. Convert to `Mutex` after the facade has suspend boundaries for all I/O paths.

```kotlin
class PreviewSnapshotCache(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, PreviewRecord>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreviewRecord>): Boolean =
            size > maxEntries
    }

    fun put(record: PreviewRecord) {
        synchronized(lock) {
            entries[record.snapshot.previewId] = record
        }
    }

    fun get(sessionId: String, previewId: String): PreviewRecord? =
        synchronized(lock) {
            entries[previewId]?.takeIf { record -> record.sessionId == sessionId }
        }

    fun remove(sessionId: String, previewId: String): PreviewRecord? =
        synchronized(lock) {
            entries[previewId]?.takeIf { record -> record.sessionId == sessionId }?.also {
                entries.remove(previewId)
            }
        }
}
```

### 12.3 Artifact promotion

`ScreenshotArtifactPromoter` owns path calculation and file copying. `FeedbackSessionService.savePreviewFeedbackItems` should call it rather than carrying private file-copy helpers.

Acceptance:

- missing source file throws a clear exception
- copied files land under `.fixthis/feedback-sessions/<session>/artifacts/screens/<screen>`
- destination path replaces preview-cache path in the returned `SnapshotDto`

## 13. Overlay state machine

`OverlayMode.Selecting` is renamed to `OverlayMode.Select`. Loading and error states become explicit.

```kotlin
sealed interface OverlayMode {
    data object Idle : OverlayMode
    data object MenuOpen : OverlayMode
    data class Select(val requestId: String?) : OverlayMode
    data class Loading(val reason: LoadingReason) : OverlayMode
    data class ReviewingSelection(val draft: FixThisDraft) : OverlayMode
    data class Commenting(val draft: FixThisDraft) : OverlayMode
    data class Exported(val annotationId: String) : OverlayMode
    data class Error(val cause: OverlayError, val recoverable: Boolean) : OverlayMode

    enum class LoadingReason {
        SCREENSHOT_CAPTURING,
        INSPECTOR_QUERYING,
        BRIDGE_CONNECTING,
    }

    sealed interface OverlayError {
        data object PermissionDenied : OverlayError
        data class ScreenshotFailed(val reason: String) : OverlayError
        data class BridgeUnreachable(val reason: String) : OverlayError
        data class Timeout(val operation: String, val timeoutMillis: Long) : OverlayError
    }
}
```

Transition validation lives in `OverlayStateMachine`.

```kotlin
fun OverlayMode.canTransitionTo(next: OverlayMode): Boolean =
    when (this) {
        OverlayMode.Idle -> next is OverlayMode.MenuOpen || next is OverlayMode.Select
        OverlayMode.MenuOpen -> next is OverlayMode.Idle || next is OverlayMode.Select
        is OverlayMode.Select -> next is OverlayMode.Idle ||
            next is OverlayMode.Loading ||
            next is OverlayMode.ReviewingSelection ||
            next is OverlayMode.Commenting ||
            next is OverlayMode.Error
        is OverlayMode.Loading -> next is OverlayMode.ReviewingSelection ||
            next is OverlayMode.Commenting ||
            next is OverlayMode.Error
        is OverlayMode.ReviewingSelection -> next is OverlayMode.Commenting ||
            next is OverlayMode.Select ||
            next is OverlayMode.Idle ||
            next is OverlayMode.Error
        is OverlayMode.Commenting -> next is OverlayMode.Idle ||
            next is OverlayMode.Select ||
            next is OverlayMode.Exported ||
            next is OverlayMode.Error
        is OverlayMode.Exported -> next is OverlayMode.Idle || next is OverlayMode.Select
        is OverlayMode.Error -> next is OverlayMode.Idle || (recoverable && next !is OverlayMode.Error)
    }
```

## 14. Compose UI decomposition

### 14.1 PreviewSurface

Current `PreviewSurface.kt` should become an orchestration file. Extract:

| New file | Responsibility |
|---|---|
| `PreviewScreenshotContent.kt` | screenshot bitmap decode state rendering and fallback preview |
| `PreviewGestureLayer.kt` | pointer input and drag/tap handling |
| `AnnotationOverlay.kt` | pin rectangle loop and selection callback |
| `DragRect.kt` | existing drag rectangle visual, retained |
| `WidgetRegistry.kt` | existing widget hit test, retained |

Acceptance:

- `PreviewSurface.kt` drops below 260 lines after first split.
- public behavior and test tags remain stable.
- pure helper `isPreviewGestureEnabled(tool, interactionMode)` is unit-tested.

### 14.2 CanvasToolbar

Target structure:

```text
studio/canvas/
  CanvasToolbar.kt
  toolbar/
    ToolSwitcher.kt
    ToolStatusBar.kt
    ZoomControl.kt
```

Acceptance:

- `CanvasToolbar.kt` drops below 120 lines.
- child controls are stateless.
- existing button labels, callbacks, enabled state, and zoom behavior remain unchanged.

## 15. ADR and PR guardrails

Create:

```text
docs/adr/
  README.md
  0001-use-clean-architecture-layering.md
  0002-domain-models-live-in-compose-core.md
  0003-feedback-item-to-annotation-naming.md
  0004-feedback-console-assets-as-resources.md
  0005-overlay-mode-state-machine.md

.github/pull_request_template.md
```

The PR template must include:

- impacted layer checklist
- persisted session compatibility checkbox
- MCP JSON field compatibility checkbox
- `compose-core` boundary checkbox
- Studio theme token checkbox
- verification command section

## 16. Rollout order

1. Compatibility tests for persisted JSON and status decode
2. Console asset resource split
3. Studio theme token entry point
4. Domain models in `compose-core`
5. MCP DTO-domain mappers
6. Repository contracts and use cases
7. MCP model rename to DTO names
8. Preview cache and source index registry extraction
9. Screenshot artifact promotion extraction
10. Overlay state machine
11. `PreviewSurface` split
12. `CanvasToolbar` split
13. ADR and PR guardrails
14. Full regression pass

This order keeps the highest-conflict rename work after compatibility tests and before service decomposition.

## 17. Verification matrix

| Surface | Command | Required result |
|---|---|---|
| MCP session and console | `./gradlew :fixthis-mcp:test` | PASS |
| Core domain and use cases | `./gradlew :fixthis-compose-core:test` | PASS |
| Overlay JVM tests | `./gradlew :fixthis-compose-overlay:testDebugUnitTest` | PASS |
| Sidekick JVM tests | `./gradlew :fixthis-compose-sidekick:testDebugUnitTest` | PASS |
| Full JVM suite | `./gradlew test` | PASS |
| Import boundary | `rg -n "io\\.github\\.fixthis\\.mcp" fixthis-compose-core/src/main/kotlin` | no matches |
| Legacy naming cleanup | `rg -n "FeedbackItem|CapturedScreen|FeedbackSession" fixthis-compose-core/src/main/kotlin fixthis-compose-overlay/src/main/kotlin fixthis-mcp/src/main/kotlin` | no matches after DTO rename, except transition PRs |

## 18. Done when

- `FeedbackConsoleAssets.kt` is a small resource loader and browser console files live under `src/main/resources/console`.
- `compose-core/domain` contains `Annotation`, `Snapshot`, `Session`, IDs, repository interfaces, and use cases.
- MCP DTO models map to and from domain models without changing existing JSON field names.
- Legacy `"ready"` status decodes and normalizes to `AnnotationStatus.OPEN`.
- `StudioTheme` tokens are available and first migrated components use them.
- `FeedbackSessionService.kt` no longer owns preview cache, source index registry, or screenshot artifact promotion directly.
- `OverlayMode` has `Select`, `Loading`, `Error`, and transition validation tests.
- `PreviewSurface.kt` and `CanvasToolbar.kt` are reduced to orchestration.
- ADRs and PR template document the new boundary rules.
- Verification matrix commands pass before landing.
