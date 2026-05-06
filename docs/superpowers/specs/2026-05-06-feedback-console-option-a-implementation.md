# Feedback Console Option A — Compose 구현 가이드

Date: 2026-05-06

원본 UI/UX 스펙: `docs/superpowers/specs/2026-05-06-feedback-console-option-a-exact-ui-ux-analysis.md`
교체 대상: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
의 pending-item 중심 UI 트리.

이 문서는 standalone HTML 프로토타입의 Option A — Studio 동작을 Jetpack
Compose로 그대로 옮기기 위한 구현 가이드다. 스펙 문서가 시각/상호작용의
기준이고, 본 문서는 그 기준을 Compose의 어떤 함수, 어떤 Modifier 체인,
어떤 state holder로 표현할지를 결정한다.

본 가이드는 Compose 코드를 새로 작성한다는 전제이며, 실제 패키지는
`pointpatch-compose-core` 모듈의 `io.github.pointpatch.compose.console.studio`
하위 패키지에 둔다. (기존 `FeedbackConsoleAssets.kt`의 HTML 자산은
스펙 단계에 따라 후속 단계에서 제거하거나, in-app Compose 콘솔로 호출되도록
교체한다 — 본 문서는 Compose 트리 구조에만 집중한다.)

---

## 1. 데이터 모델

모든 모델은 `io.github.pointpatch.compose.console.studio.model` 패키지에 둔다.

### 1.1 Severity / Status enum

```kotlin
enum class Severity { HIGH, MED, LOW }
enum class AnnotationStatus { OPEN, IN_PROGRESS, RESOLVED }
enum class StudioTool { SELECT, ANNOTATE }
```

라벨, 색상은 enum 자체에 두지 않고 `StudioColors` (3장) 함수로 매핑한다.
HTML 프로토타입에서 severity 값 문자열은 `"high" | "med" | "low"`이고
status 값 문자열은 `"open" | "in-progress" | "resolved"`이다. 직렬화 시
같은 형식을 보존해야 한다 (서버 호환). enum 확장 함수로 매핑한다:

```kotlin
fun Severity.wireValue(): String = when (this) {
    Severity.HIGH -> "high"; Severity.MED -> "med"; Severity.LOW -> "low"
}
fun AnnotationStatus.wireValue(): String = when (this) {
    AnnotationStatus.OPEN -> "open"
    AnnotationStatus.IN_PROGRESS -> "in-progress"
    AnnotationStatus.RESOLVED -> "resolved"
}
```

### 1.2 Annotation

`Annotation`은 percent 좌표를 사용한다 (HTML 프로토타입과 동일). 픽셀
좌표로 변환하는 책임은 렌더링 단계에서 진다.

```kotlin
import androidx.compose.runtime.Immutable

@Immutable
data class Annotation(
    val id: String,                  // UUID, 생성 시점 고정
    val label: String,               // "Region 3", "Login Button" 등 (생성 시 frozen)
    val severity: Severity,
    val status: AnnotationStatus,
    val comment: String,             // 빈 문자열 허용
    val rectPercent: RectPercent,    // 0..100 범위
)

@Immutable
data class RectPercent(
    val x: Float,    // 0..100
    val y: Float,    // 0..100
    val w: Float,    // 0..100
    val h: Float,    // 0..100
)
```

`label` 생성 규칙은 7장 / 10장에서 다룬다.

### 1.3 Snapshot

History 패널이 보여주는 immutable record다. `Save snapshot`이 만든 시점의
annotations 깊은 복사본을 보유한다.

```kotlin
@Immutable
data class Snapshot(
    val id: String,
    val title: String,
    val author: String,
    val createdAtEpochMillis: Long,
    val annotations: List<Annotation>,   // immutable copy
)
```

### 1.4 StudioState

전체 상태 컨테이너. ViewModel 내부에 `mutableStateOf` / `mutableStateListOf`
조합으로 풀어쓴다 (5장).

```kotlin
@Immutable
data class StudioState(
    val snapshots: List<Snapshot>,
    val activeSnapId: String?,
    val annotations: List<Annotation>,
    val draftTitle: String,
    val selectedId: String?,
    val tool: StudioTool,
    val draggingRect: RectPercent?,
    val dragMoved: Boolean,
)
```

`StudioState`는 외부 노출용 read-only 스냅샷 (예: 테스트, 직렬화)으로만
쓴다. 실제 Compose 트리에서는 `StudioViewModel`의 개별 state property를
직접 읽는다 (recomposition 범위를 좁히기 위함).

### 1.5 PointerPercent

drag 중간 좌표 계산에 쓰는 작은 값 클래스.

```kotlin
@JvmInline
value class PointerPercent(val packed: Long) {
    val x: Float get() = Float.fromBits((packed ushr 32).toInt())
    val y: Float get() = Float.fromBits(packed.toInt())
    companion object {
        fun of(x: Float, y: Float) =
            PointerPercent(((x.toRawBits().toLong() and 0xFFFFFFFFL) shl 32) or
                           (y.toRawBits().toLong() and 0xFFFFFFFFL))
    }
}
```

선택사항이다. 단순함을 우선시한다면 `Offset` 또는 `Pair<Float, Float>`로
대체해도 된다. 본 문서 이하 예시에서는 `Offset`을 사용한다.

---

## 2. State 관리

`StudioViewModel`은 `androidx.lifecycle.ViewModel`을 상속하고, 내부 상태는
Compose의 `SnapshotStateList`, `mutableStateOf`로 노출한다. `StateFlow`
대신 Compose state를 직접 노출하는 이유는 본 화면의 모든 소비자가 Compose
트리이고, 연속 입력 (drag 좌표)이 매 프레임 발생하기 때문이다.

### 2.1 클래스 골격

```kotlin
class StudioViewModel(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val author: String = "you",
) : ViewModel() {

    val snapshots: SnapshotStateList<Snapshot> = mutableStateListOf()
    var activeSnapId: String? by mutableStateOf(null); private set

    val annotations: SnapshotStateList<Annotation> = mutableStateListOf()
    var draftTitle: String by mutableStateOf("New session"); private set
    var selectedId: String? by mutableStateOf(null); private set

    var tool: StudioTool by mutableStateOf(StudioTool.SELECT); private set
    var draggingRect: RectPercent? by mutableStateOf(null); private set
    var dragMoved: Boolean by mutableStateOf(false); private set

    private var dragStart: DragStart? = null
    private data class DragStart(val percent: Offset, val widgetTag: String?)
}
```

`SnapshotStateList`는 Compose가 element-level mutation도 추적하므로,
`add`/`removeAt`/`set`을 그대로 써도 recomposition이 정확히 발생한다.

### 2.2 Computed values

```kotlin
val openCount: Int get() = annotations.count { it.status == AnnotationStatus.OPEN }
val resolvedCount: Int get() = annotations.count { it.status == AnnotationStatus.RESOLVED }
val inProgressCount: Int get() = annotations.count { it.status == AnnotationStatus.IN_PROGRESS }
val canSaveSnapshot: Boolean get() = annotations.isNotEmpty()
val selectedAnnotation: Annotation? get() = annotations.firstOrNull { it.id == selectedId }
val historyCount: Int get() = snapshots.size
val annotationsCount: Int get() = annotations.size
```

`get()` 형태로 두면 매 read 시 stable list를 다시 훑지만, 패널 헤더 카운트
정도는 비용이 미미하고 별도 캐시 invalidation을 신경 쓸 필요가 없다. 더
큰 화면에서 비용이 문제가 되면 `derivedStateOf`로 감싼다.

### 2.3 Action 함수

스펙 5장 / 7장 / 9장에서 정의한 모든 분기를 1:1로 매핑한다.

```kotlin
fun setTool(next: StudioTool) {
    tool = next
}

fun setDraftTitle(next: String) {
    draftTitle = next
}

fun selectAnnotation(id: String?) {
    selectedId = id
}

fun openSnapshot(id: String) {
    val snap = snapshots.firstOrNull { it.id == id } ?: return
    activeSnapId = snap.id
    annotations.clear()
    annotations.addAll(snap.annotations)        // deep copy: data class는 immutable
    draftTitle = snap.title
    selectedId = null
}

fun deleteSnapshot(id: String) {
    val index = snapshots.indexOfFirst { it.id == id }
    if (index < 0) return
    val wasActive = (activeSnapId == id)
    snapshots.removeAt(index)
    if (wasActive) {
        val next = snapshots.firstOrNull()
        if (next != null) openSnapshot(next.id) else newSession()
    }
}

fun saveSnapshot() {
    if (annotations.isEmpty()) return
    val snap = Snapshot(
        id = idFactory(),
        title = draftTitle,
        author = author,
        createdAtEpochMillis = clock(),
        annotations = annotations.toList(),     // copy
    )
    snapshots.add(0, snap)
    activeSnapId = snap.id
}

fun newSession() {
    activeSnapId = null
    annotations.clear()
    draftTitle = "New session"
    selectedId = null
    tool = StudioTool.SELECT
    resetDrag()
}

fun updateAnnotation(id: String, transform: (Annotation) -> Annotation) {
    val index = annotations.indexOfFirst { it.id == id }
    if (index < 0) return
    annotations[index] = transform(annotations[index])
}

fun deleteAnnotation(id: String) {
    annotations.removeAll { it.id == id }
    if (selectedId == id) selectedId = null
}
```

### 2.4 Drag/widget 액션

```kotlin
fun beginDrag(percent: Offset, widgetTag: String?) {
    if (tool != StudioTool.ANNOTATE) return
    dragStart = DragStart(percent, widgetTag)
    dragMoved = false
    draggingRect = RectPercent(percent.x, percent.y, 0f, 0f)
    selectedId = null
}

fun updateDrag(percent: Offset) {
    val start = dragStart ?: return
    val dx = abs(percent.x - start.percent.x)
    val dy = abs(percent.y - start.percent.y)
    if (dx > 0.6f || dy > 0.6f) dragMoved = true
    val left = min(start.percent.x, percent.x)
    val top = min(start.percent.y, percent.y)
    draggingRect = RectPercent(left, top, abs(percent.x - start.percent.x),
                                          abs(percent.y - start.percent.y))
}

fun endDrag() {
    val start = dragStart ?: return
    val rect = draggingRect
    val createdRegion = dragMoved && rect != null && rect.w > 1.5f && rect.h > 1.5f
    val newAnnotation: Annotation? = when {
        createdRegion -> Annotation(
            id = idFactory(),
            label = "Region ${annotations.size + 1}",
            severity = Severity.MED,
            status = AnnotationStatus.OPEN,
            comment = "",
            rectPercent = rect!!,
        )
        else -> start.widgetTag?.let { tag ->
            Annotation(
                id = idFactory(),
                label = humanizeWidgetTag(tag),
                severity = Severity.MED,
                status = AnnotationStatus.OPEN,
                comment = "",
                rectPercent = rect ?: RectPercent(start.percent.x, start.percent.y, 6f, 6f),
            )
        }
    }
    if (newAnnotation != null) {
        annotations.add(newAnnotation)
        selectedId = newAnnotation.id
        tool = StudioTool.SELECT
    }
    resetDrag()
}

fun cancelDrag() = resetDrag()

private fun resetDrag() {
    dragStart = null
    draggingRect = null
    dragMoved = false
}

private fun humanizeWidgetTag(tag: String): String =
    tag.replace('-', ' ').replaceFirstChar { it.uppercase() }
```

`humanizeWidgetTag`는 HTML 프로토타입의 `data-w="login-button"` →
`"Login button"` 변환과 동일하다.

### 2.5 ViewModel 노출 규칙

- 모든 mutating 함수는 `StudioViewModel`에만 둔다. Composable은 함수
  레퍼런스 (`vm::setTool`, `vm::saveSnapshot`)를 파라미터로 받는다.
- 콜백 파라미터 스타일을 일관되게 유지하기 위해 lambda를 직접 만들기보단
  `vm::deleteAnnotation` 같은 method reference를 권장한다 — 안정적
  identity로 불필요한 recomposition을 줄인다.

---

## 3. Color 시스템

`MaterialTheme`에 의존하지 않는다. Studio 콘솔은 dark 단일 테마이고, 정확한
hex 토큰이 스펙에 명시되어 있다. `io.github.pointpatch.compose.console.studio.theme`
패키지에 `StudioColors` object를 둔다.

```kotlin
import androidx.compose.ui.graphics.Color

object StudioColors {
    val Bg0 = Color(0xFF0D0E10)
    val Bg1 = Color(0xFF131418)
    val Bg2 = Color(0xFF1A1C21)
    val Bg3 = Color(0xFF21242B)
    val Line = Color(0xFF2A2D35)
    val LineSoft = Color(0xFF1F2228)
    val Txt0 = Color(0xFFE8E9EB)
    val Txt1 = Color(0xFFB6B8BE)
    val Txt2 = Color(0xFF7D8089)
    val Accent = Color(0xFFB8D36A)
    val AccentHover = Color(0xFFC8E07C)
    val Danger = Color(0xFFF26D6D)

    // severity
    val SeverityHigh = Color(0xFFF26D6D)
    val SeverityMed = Color(0xFFE6B45A)
    val SeverityLow = Color(0xFF5AB1E6)

    // status dot
    val StatusOpen = Color(0xFFF2C94C)
    val StatusResolved = Color(0xFF6FCF97)
    val StatusInProgress = Color(0xFF5BB1E6)

    // status pill bg (alpha 적용된 결과를 미리 둠 — 매 frame 계산 회피)
    val StatusOpenPillBg = StatusOpen.copy(alpha = 0.15f)
    val StatusResolvedPillBg = StatusResolved.copy(alpha = 0.15f)
    val StatusInProgressPillBg = StatusInProgress.copy(alpha = 0.15f)

    // annotate hint pill
    val AnnotateHintBg = Accent.copy(alpha = 0.08f)
    val AnnotateHintBorder = Accent.copy(alpha = 0.25f)

    // drag rect
    val DragRectFill = Color(0xFF78B4FF).copy(alpha = 0.12f)
    val DragRectBorder = Color(0xFF0D0E10).copy(alpha = 0.6f)

    // widget hover outline (annotate mode)
    val WidgetHoverOutline = Color(0xFF6B4EFF).copy(alpha = 0.7f)
}

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.HIGH -> StudioColors.SeverityHigh
    Severity.MED -> StudioColors.SeverityMed
    Severity.LOW -> StudioColors.SeverityLow
}

fun statusDotColor(status: AnnotationStatus): Color = when (status) {
    AnnotationStatus.OPEN -> StudioColors.StatusOpen
    AnnotationStatus.IN_PROGRESS -> StudioColors.StatusInProgress
    AnnotationStatus.RESOLVED -> StudioColors.StatusResolved
}

fun statusPillBg(status: AnnotationStatus): Color = when (status) {
    AnnotationStatus.OPEN -> StudioColors.StatusOpenPillBg
    AnnotationStatus.IN_PROGRESS -> StudioColors.StatusInProgressPillBg
    AnnotationStatus.RESOLVED -> StudioColors.StatusResolvedPillBg
}
```

`severityColor.copy(alpha = 0.12f)` 패턴은 PinRect 호출부에서 직접 쓴다
(8장). 매 호출마다 새 `Color` 인스턴스를 만들지만, `Color`는 inline value
class이므로 부담이 없다.

### 3.1 Typography

`Inter` 폰트는 `pointpatch-compose-core/src/main/res/font` (또는 동급)에
번들로 포함시킨다. 단일 `FontFamily`와 사이즈 토큰을 둔다:

```kotlin
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object StudioType {
    val Base = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = StudioColors.Txt0)
    val PanelTitle = TextStyle(
        fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.14.em, color = StudioColors.Txt2,
    )                                                        // uppercase: Text 컴포저블에서 .uppercase()
    val PanelCount = TextStyle(
        fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum", color = StudioColors.Txt1,
    )
    val FieldLabel = TextStyle(
        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.14.em, color = StudioColors.Txt2,
    )
    val HiTitle = TextStyle(
        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        lineHeight = 16.9.sp, color = StudioColors.Txt0,
    )
    val AnnRowTitle = TextStyle(
        fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = StudioColors.Txt0,
    )
    val AnnRowComment = TextStyle(
        fontFamily = InterFamily, fontSize = 11.sp, lineHeight = 15.4.sp,
        color = StudioColors.Txt1,
    )
}
```

uppercase 적용은 `Text(text = "History".uppercase(), ...)` 형태로 표현한다.
CSS의 `text-transform: uppercase`와 시각적으로 동일하다.

---

## 4. 레이아웃 구조

전체 트리는 다음과 같다. 모든 함수는 `io.github.pointpatch.compose.console.studio`
패키지에 둔다.

```
StudioShell(vm)
├── StudioTopbar(...)
└── StudioBody(...)
    ├── StudioHistory(...)
    ├── StudioCanvas(...)
    │   ├── CanvasToolbar(...)
    │   └── CanvasStage(...)
    │       └── PhoneFrame(...)
    │           └── PreviewSurface(...)         // pin overlay 포함
    └── StudioInspector(...)                    // list ↔ detail 분기
        ├── AnnotationsPanel(...)
        └── AnnotationDetail(...)
```

### 4.1 StudioShell

루트. `topbar 56dp + body fill` grid를 `Column { Topbar; Row { ... } }`로
표현한다.

```kotlin
@Composable
fun StudioShell(vm: StudioViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.Bg0)
    ) {
        StudioTopbar(
            draftTitle = vm.draftTitle,
            onDraftTitleChange = vm::setDraftTitle,
            canSave = vm.canSaveSnapshot,
            onSave = vm::saveSnapshot,
            onNewSession = vm::newSession,
        )
        StudioBody(vm = vm, modifier = Modifier.weight(1f))
    }
}
```

### 4.2 StudioBody

3-pane (`280dp / 1f / 340dp`)를 `Row` + `Modifier.width(...)` /
`Modifier.weight(1f)`로 표현한다. 가운데 영역 외곽에 명시적 `min-width`가
필요하므로 `Modifier.widthIn(min = 480.dp)`까지 둔다.

```kotlin
@Composable
fun StudioBody(vm: StudioViewModel, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxSize()) {
        StudioHistory(
            snapshots = vm.snapshots,
            activeSnapId = vm.activeSnapId,
            onOpen = vm::openSnapshot,
            onDelete = vm::deleteSnapshot,
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .border(width = 1.dp, color = StudioColors.Line,
                        shape = RectangleShape)        // 우측 1px만 필요 → drawBehind 권장
        )
        StudioCanvas(
            vm = vm,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        StudioInspector(
            vm = vm,
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
        )
    }
}
```

좌/우 1px 경계선은 `Modifier.drawBehind { drawLine(...) }`로 구현하는 것이
정확하다 (border modifier는 4면을 모두 그린다):

```kotlin
fun Modifier.rightBorder(color: Color, thickness: Dp = 1.dp) = this.drawBehind {
    val px = thickness.toPx()
    drawLine(color, Offset(size.width - px / 2, 0f),
                    Offset(size.width - px / 2, size.height), strokeWidth = px)
}
```

`leftBorder`, `bottomBorder`, `topBorder`도 같은 패턴으로 작성.
`StudioHistory`는 `rightBorder(StudioColors.Line)`,
`StudioInspector`는 `leftBorder(StudioColors.Line)`,
`PanelHead`는 `bottomBorder(StudioColors.LineSoft)`,
`CanvasToolbar`는 `bottomBorder(StudioColors.Line)`로 구분한다.

---

## 5. Topbar 구현

레이아웃: `Row` + 3구간 (`220dp / weight(1f) / wrapContent`), `padding 0.dp 16.dp`,
`height 56.dp`.

```kotlin
@Composable
fun StudioTopbar(
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(StudioColors.Bg1)
            .bottomBorder(StudioColors.Line)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark(modifier = Modifier.width(220.dp))
        Breadcrumb(
            draftTitle = draftTitle,
            onDraftTitleChange = onDraftTitleChange,
            modifier = Modifier.weight(1f)
        )
        TopbarActions(
            canSave = canSave,
            onSave = onSave,
            onNewSession = onNewSession,
        )
    }
}
```

### 5.1 BrandMark

```kotlin
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(StudioColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = StudioColors.Bg0, fontWeight = FontWeight.Bold,
                 fontSize = 16.sp, fontFamily = InterFamily)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PointPatch",
                 fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 letterSpacing = (-0.01).em, color = StudioColors.Txt0,
                 fontFamily = InterFamily)
            Text("STUDIO",
                 style = StudioType.PanelTitle, color = StudioColors.Txt2)
        }
    }
}
```

### 5.2 Breadcrumb / draftTitle 입력

CSS의 `bc-input`은 transparent 배경, hover/focus 시에만 `#1A1C21` 배경.
Compose에서는 `BasicTextField`로 transparent 배경을 만들고, focus
상태/hover 상태에 따라 background를 `animateColorAsState`로 변경한다.

```kotlin
@Composable
fun Breadcrumb(
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val target = if (isFocused || isHovered) StudioColors.Bg2 else Color.Transparent
    val bg by animateColorAsState(target, animationSpec = tween(120), label = "bcInputBg")

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Console", color = StudioColors.Txt1,
             fontSize = 12.sp, fontFamily = InterFamily, maxLines = 1)
        Text("/", color = StudioColors.Txt2, fontSize = 12.sp)
        BasicTextField(
            value = draftTitle,
            onValueChange = onDraftTitleChange,
            singleLine = true,
            textStyle = StudioType.Base.copy(fontSize = 12.sp, color = StudioColors.Txt0),
            cursorBrush = SolidColor(StudioColors.Accent),
            interactionSource = interaction,
            modifier = Modifier
                .widthIn(min = 200.dp)
                .background(bg, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .hoverable(interaction),
        )
    }
}
```

### 5.3 TopbarActions

```kotlin
@Composable
fun TopbarActions(
    canSave: Boolean,
    onSave: () -> Unit,
    onNewSession: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GhostButton(label = "New session", onClick = onNewSession)
        PrimaryButton(label = "Save snapshot", glyph = "⌘", enabled = canSave, onClick = onSave)
    }
}
```

### 5.4 공통 버튼

`GhostButton`, `PrimaryButton`, `DangerButton`을 공통 위젯으로 둔다.

```kotlin
@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) StudioColors.Bg2 else Color.Transparent, tween(120), label = "ghostBg")
    val border by animateColorAsState(
        if (hovered) StudioColors.Line else Color.Transparent, tween(120), label = "ghostBorder")
    val color by animateColorAsState(
        if (hovered) StudioColors.Txt0 else StudioColors.Txt1, tween(120), label = "ghostFg")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 12.sp,
             fontWeight = FontWeight.Medium, fontFamily = InterFamily)
    }
}

@Composable
fun PrimaryButton(
    label: String,
    glyph: String? = null,
    enabled: Boolean = true,
    mini: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (!enabled) StudioColors.Accent
        else if (hovered) StudioColors.AccentHover else StudioColors.Accent,
        tween(120), label = "primaryBg")
    val translate by animateDpAsState(
        if (hovered && enabled) (-1).dp else 0.dp, tween(120), label = "primaryTranslate")
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .offset(y = translate)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .alpha(alpha)
            .hoverable(interaction)
            .clickable(enabled = enabled,
                       interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (mini) 10.dp else 14.dp,
                     vertical = if (mini) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (glyph != null) {
            Text(glyph, color = StudioColors.Bg0, fontSize = 11.sp,
                 modifier = Modifier.alpha(0.7f), fontFamily = InterFamily)
        }
        Text(label, color = StudioColors.Bg0,
             fontSize = if (mini) 11.sp else 12.sp,
             fontWeight = FontWeight.SemiBold, fontFamily = InterFamily)
    }
}
```

`hoverable`은 데스크톱/태블릿 환경에서 마우스 hover를 감지한다. 안드로이드
폰에서는 hover 이벤트가 발생하지 않으므로 ripple-less press 효과를
대체로 두려면 `collectIsPressedAsState`를 OR 결합한다.

---

## 6. History 패널 구현

### 6.1 PanelHead

```kotlin
@Composable
fun PanelHead(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bottomBorder(StudioColors.LineSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), style = StudioType.PanelTitle)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountPill(count)
            trailing?.invoke()
        }
    }
}

@Composable
fun CountPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.Bg3)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(count.toString(), style = StudioType.PanelCount)
    }
}
```

### 6.2 StudioHistory

```kotlin
@Composable
fun StudioHistory(
    snapshots: List<Snapshot>,
    activeSnapId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(StudioColors.Bg0)) {
        PanelHead(title = "History", count = snapshots.size)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(snapshots, key = { it.id }) { snap ->
                HistoryItem(
                    snapshot = snap,
                    isActive = snap.id == activeSnapId,
                    onClick = { onOpen(snap.id) },
                    onDelete = { onDelete(snap.id) },
                )
            }
        }
    }
}
```

### 6.3 HistoryItem (active state, hover)

```kotlin
@Composable
fun HistoryItem(
    snapshot: Snapshot,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bgTarget = if (isActive || hovered) StudioColors.Bg2 else Color.Transparent
    val bg by animateColorAsState(bgTarget, tween(120), label = "hiBg")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (isActive) Modifier.border(1.dp, StudioColors.Line,
                                                RoundedCornerShape(8.dp)) else Modifier)
            .then(if (isActive) Modifier.drawBehind {
                drawRect(StudioColors.Accent,
                         topLeft = Offset.Zero,
                         size = Size(2f.dp.toPx(), size.height))
            } else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.title, style = StudioType.HiTitle,
                     modifier = Modifier.weight(1f))
                AnimatedVisibility(visible = hovered) {
                    DeleteIconButton(onClick = onDelete)
                }
            }
            Text(formatHistoryMeta(snapshot),
                 fontSize = 11.sp, color = StudioColors.Txt2, fontFamily = InterFamily)
            HistoryStats(snapshot.annotations)
            HistoryStrip(snapshot.annotations)
        }
    }
}

private fun formatHistoryMeta(snapshot: Snapshot): String {
    val date = java.time.Instant.ofEpochMilli(snapshot.createdAtEpochMillis)
        .atZone(java.time.ZoneId.systemDefault())
    val mon = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    val day = date.dayOfMonth
    val time = "%02d:%02d".format(date.hour, date.minute)
    return "${snapshot.author} · $mon $day · $time"
}
```

`onDelete`는 click을 가로채야 한다 — `clickable`을 부모와 같이 두면
이벤트가 부모로 버블된다. Compose에서는 자식 `clickable`이 먼저 처리하면
부모로 전파되지 않으므로 `DeleteIconButton`에 별도 `clickable`을 두면
HTML의 `stopPropagation`과 동일 효과를 자동으로 얻는다.

```kotlin
@Composable
private fun DeleteIconButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) StudioColors.Bg3 else Color.Transparent, tween(120), label = "delBg")
    val fg by animateColorAsState(
        if (hovered) StudioColors.Danger else StudioColors.Txt2, tween(120), label = "delFg")
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("×", color = fg, fontSize = 16.sp, fontFamily = InterFamily)
    }
}
```

### 6.4 HistoryStats

```kotlin
@Composable
fun HistoryStats(annotations: List<Annotation>, modifier: Modifier = Modifier) {
    val open = annotations.count { it.status == AnnotationStatus.OPEN }
    val resolved = annotations.count { it.status == AnnotationStatus.RESOLVED }
    Row(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Pip("$open open")
        Pip("$resolved resolved")
    }
}

@Composable
private fun Pip(text: String) {
    Text(text, fontSize = 11.sp, color = StudioColors.Txt1,
         fontFamily = InterFamily, fontFeatureSettings = "tnum")
}
```

### 6.5 HistoryStrip

각 annotation 1셀, severity color, resolved일 때 alpha 0.35.

```kotlin
@Composable
fun HistoryStrip(annotations: List<Annotation>, modifier: Modifier = Modifier) {
    if (annotations.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        annotations.forEach { ann ->
            val baseColor = severityColor(ann.severity)
            val tint = if (ann.status == AnnotationStatus.RESOLVED)
                baseColor.copy(alpha = 0.35f) else baseColor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }
    }
}
```

---

## 7. Canvas 구현

`StudioCanvas`는 `Column { CanvasToolbar(...); CanvasStage(...) }`. Toolbar는
높이가 wrap (≈ 44dp)이고, stage는 `weight(1f)`.

```kotlin
@Composable
fun StudioCanvas(vm: StudioViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxHeight().background(StudioColors.Bg0)) {
        CanvasToolbar(
            tool = vm.tool,
            onToolChange = vm::setTool,
            openCount = vm.openCount,
            resolvedCount = vm.resolvedCount,
        )
        CanvasStage(vm = vm, modifier = Modifier.weight(1f))
    }
}
```

### 7.1 CanvasToolbar

```kotlin
@Composable
fun CanvasToolbar(
    tool: StudioTool,
    onToolChange: (StudioTool) -> Unit,
    openCount: Int,
    resolvedCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioColors.Bg1)
            .bottomBorder(StudioColors.Line)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToolGroup(tool = tool, onToolChange = onToolChange)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (tool) {
                StudioTool.SELECT -> ToolStatusMeta(open = openCount, resolved = resolvedCount)
                StudioTool.ANNOTATE -> ToolStatusHint()
            }
        }
        ToolZoom()
    }
}
```

### 7.2 ToolGroup (segmented Select / Annotate)

```kotlin
@Composable
fun ToolGroup(
    tool: StudioTool,
    onToolChange: (StudioTool) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StudioColors.Bg2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(
            label = "Select",
            icon = { ToolPointerIcon() },
            active = tool == StudioTool.SELECT,
            onClick = { onToolChange(StudioTool.SELECT) },
        )
        ToolButton(
            label = "Annotate",
            icon = { ToolDashedRectIcon() },
            active = tool == StudioTool.ANNOTATE,
            onClick = { onToolChange(StudioTool.ANNOTATE) },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: @Composable () -> Unit,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        active -> StudioColors.Bg3
        hovered -> StudioColors.Bg2.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val color = if (active) StudioColors.Accent else StudioColors.Txt1
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(if (active) Modifier.shadow(1.dp, RoundedCornerShape(6.dp))
                  else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(label, color = color, fontSize = 12.sp,
             fontWeight = FontWeight.Medium, fontFamily = InterFamily)
    }
}
```

`ToolPointerIcon`, `ToolDashedRectIcon`는 16dp `Canvas`로 직접 그린다
(svg/icon 리소스 의존을 피한다):

```kotlin
@Composable
private fun ToolPointerIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        // 단순 화살표 — 실제 픽셀 모양은 디자인 시 보정.
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.1f)
            lineTo(w * 0.85f, h * 0.55f)
            lineTo(w * 0.5f, h * 0.6f)
            lineTo(w * 0.45f, h * 0.95f)
            close()
        }
        drawPath(path, StudioColors.Txt1)
    }
}

@Composable
private fun ToolDashedRectIcon() {
    Canvas(modifier = Modifier.size(16.dp)) {
        drawRoundRect(
            color = StudioColors.Txt1,
            style = Stroke(width = 1.5f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
    }
}
```

### 7.3 ToolStatusMeta / ToolStatusHint

```kotlin
@Composable
fun ToolStatusMeta(open: Int, resolved: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatusDotLabel(text = "$open open",
            dotColor = StudioColors.StatusOpen,
            glow = StudioColors.StatusOpen.copy(alpha = 0.18f))
        StatusDotLabel(text = "$resolved resolved",
            dotColor = StudioColors.StatusResolved,
            glow = StudioColors.StatusResolved.copy(alpha = 0.18f))
    }
}

@Composable
private fun StatusDotLabel(text: String, dotColor: Color, glow: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = glow, radius = 4.dp.toPx())
            drawCircle(color = dotColor, radius = 2.5f.dp.toPx())
        }
        Text(text, fontSize = 11.sp, color = StudioColors.Txt1,
             fontFamily = InterFamily, fontFeatureSettings = "tnum")
    }
}

@Composable
fun ToolStatusHint() {
    val infinite = rememberInfiniteTransition(label = "pulseA")
    val opacity by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "opacity",
    )
    val scale by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(StudioColors.AnnotateHintBg)
            .border(1.dp, StudioColors.AnnotateHintBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer { alpha = opacity; scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(StudioColors.Accent)
        )
        Text("Click a widget — or drag to draw a region",
             color = StudioColors.Accent, fontSize = 11.sp, fontFamily = InterFamily)
    }
}
```

### 7.4 ToolZoom

100% 고정. `Row { ZoomButton("−"); Text("100%"); ZoomButton("+") }`.

```kotlin
@Composable
fun ToolZoom() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioColors.Bg2)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZoomButton("−")
        Text("100%", fontSize = 11.sp, color = StudioColors.Txt1,
             fontFamily = InterFamily, fontFeatureSettings = "tnum",
             modifier = Modifier.padding(horizontal = 6.dp))
        ZoomButton("+")
    }
}

@Composable
private fun ZoomButton(symbol: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) StudioColors.Bg3 else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 14.sp,
             color = if (hovered) StudioColors.Txt0 else StudioColors.Txt1,
             fontFamily = InterFamily)
    }
}
```

### 7.5 CanvasStage / PhoneFrame / PreviewSurface

`CanvasStage`는 `Box` + `radial gradient` 배경 + `place-items: center` 효과.

```kotlin
@Composable
fun CanvasStage(vm: StudioViewModel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stageBackground())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        PhoneFrame {
            PreviewSurface(vm = vm)
        }
    }
}

@Composable
private fun stageBackground(): Brush =
    Brush.radialGradient(
        colors = listOf(StudioColors.Bg1, StudioColors.Bg0),
        center = Offset.Unspecified,            // 컴포넌트 중심 — Compose 기본
        radius = 1500f,
    )

@Composable
fun PhoneFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 320.dp, height = 660.dp)
            .clip(RoundedCornerShape(44.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2A2A2E), Color(0xFF1A1A1D))
                )
            )
            .border(2.dp, Color(0xFF3A3A40), RoundedCornerShape(44.dp))
            // 외곽 그림자 — Compose는 다중 outer shadow가 자연스럽지 않다.
            // 가장 가까운 근사: Modifier.shadow(elevation = 30.dp, shape = RoundedCornerShape(44.dp))
            // 아래에 같은 크기의 Box를 두어 두 번째 shadow를 추가하는 방법도 있다.
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(36.dp))
                .background(Color.White),
        ) {
            content()
            // notch
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .size(width = 86.dp, height = 22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black)
            )
        }
    }
}
```

`PreviewSurface`는 본 가이드에서 핵심이다. 라이브 프리뷰 비트맵 또는
mock UI 위에 pin overlay를 그린다. Annotate 모드 일 때 drag gesture를
받는다.

```kotlin
@Composable
fun PreviewSurface(vm: StudioViewModel) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            .annotateDragGestures(
                tool = vm.tool,
                onBegin = { offsetPx, widget -> vm.beginDrag(toPercent(offsetPx, sizePx), widget) },
                onUpdate = { offsetPx -> vm.updateDrag(toPercent(offsetPx, sizePx)) },
                onEnd = { vm.endDrag() },
                onCancel = { vm.cancelDrag() },
                onSelectEmpty = { vm.selectAnnotation(null) },
            )
    ) {
        // 1) 실제 디바이스 mock 또는 live preview bitmap
        DevicePreviewContent(modifier = Modifier.fillMaxSize())

        // 2) annotations
        vm.annotations.forEachIndexed { index, ann ->
            PinRect(
                annotation = ann,
                index = index,
                isSelected = ann.id == vm.selectedId,
                onClick = { vm.selectAnnotation(ann.id) },
            )
        }

        // 3) drag preview
        if (vm.dragMoved) {
            vm.draggingRect?.let { DragRect(rect = it) }
        }
    }
}

private fun toPercent(offsetPx: Offset, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0) return Offset.Zero
    return Offset(
        x = (offsetPx.x / size.width) * 100f,
        y = (offsetPx.y / size.height) * 100f,
    )
}
```

`DevicePreviewContent`는 본 가이드 범위 밖이며, 실제 PointPatch live
preview의 비트맵을 fill로 그리는 것이 책임이다.

---

## 8. PinRect / PinTag 구현

`PinRect`는 percent 좌표를 부모 픽셀 크기로 변환해 absolute 배치한다.
Compose에서는 `Modifier.offset` + `Modifier.size`를 사용하지만, percent
기반이므로 `BoxWithConstraints`로 부모 크기를 받아 `dp`로 변환하는 것이
자연스럽다. 그러나 `forEachIndexed` 안에서 매번 `BoxWithConstraints`를
쓰면 비용이 크다. 대신 `PreviewSurface`의 `Box`가 이미 `onSizeChanged`로
`sizePx`를 가지고 있으므로, 이를 internal `CompositionLocal`로 노출한다:

```kotlin
val LocalPreviewSizePx = compositionLocalOf<IntSize> { IntSize.Zero }
```

`PreviewSurface`에서 `CompositionLocalProvider(LocalPreviewSizePx provides sizePx) { ... }`
로 감싼다.

```kotlin
@Composable
fun PinRect(
    annotation: Annotation,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val size = LocalPreviewSizePx.current
    if (size.width == 0 || size.height == 0) return
    val density = LocalDensity.current
    val color = severityColor(annotation.severity)
    val rect = annotation.rectPercent

    val leftDp = with(density) { (rect.x / 100f * size.width).toDp() }
    val topDp  = with(density) { (rect.y / 100f * size.height).toDp() }
    val widthDp  = with(density) { (rect.w / 100f * size.width).toDp() }
    val heightDp = with(density) { (rect.h / 100f * size.height).toDp() }

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val fillAlpha = when {
        isSelected -> 0.20f
        hovered -> 0.20f
        else -> 0.12f
    }
    val borderWidth = if (isSelected) 2.dp else 1.5.dp
    val animatedFill by animateColorAsState(
        color.copy(alpha = fillAlpha), tween(120), label = "pinFill")

    Box(
        modifier = Modifier
            .offset(x = leftDp, y = topDp)
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(4.dp))
            .background(animatedFill)
            .border(borderWidth, color, RoundedCornerShape(4.dp))
            .then(if (isSelected) Modifier.drawBehind {
                drawSelectedGlow(color)
            } else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null,
                       onClick = onClick),
    ) {
        PinTag(label = (index + 1).toString(), background = color)
    }
}

private fun DrawScope.drawSelectedGlow(color: Color) {
    // 0 0 0 2px severityColor.copy(alpha=0.25) — outer ring
    val ringPx = 2.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.25f),
        topLeft = Offset(-ringPx, -ringPx),
        size = Size(size.width + ringPx * 2, size.height + ringPx * 2),
        cornerRadius = CornerRadius(4.dp.toPx() + ringPx),
        style = Stroke(width = ringPx),
    )
    // 추가 어두운 그림자 — 0 4px 16px rgba(0,0,0,0.3)
    // Compose에서 정확히 표현하기 어렵다. Modifier.shadow(elevation = 8.dp)로 대체하거나
    // 별도 Box를 부모에 두고 alpha 0.3 검정 blur를 그리는 방법이 있다. 본 가이드에서는
    // shadow modifier를 PinRect 외부에 한 번만 적용한다 (drawBehind 단계 이전):
    // 위쪽 .clip(...) 직전에 .shadow(if (isSelected) 8.dp else 0.dp, RoundedCornerShape(4.dp))를 둔다.
}
```

`PinTag`는 좌상단 외부 (-10, -10) 위치, 22dp 원형, 인덱스 텍스트.

```kotlin
@Composable
private fun PinTag(label: String, background: Color) {
    Box(
        modifier = Modifier
            .offset(x = (-10).dp, y = (-10).dp)
            .size(22.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = StudioColors.Bg0,
             fontSize = 11.sp, fontWeight = FontWeight.Bold,
             fontFamily = InterFamily)
    }
}
```

`DragRect`는 비슷한 구조이지만 dashed border + 파란 fill, click 비반응.

```kotlin
@Composable
fun DragRect(rect: RectPercent) {
    val size = LocalPreviewSizePx.current
    if (size.width == 0 || size.height == 0) return
    val density = LocalDensity.current
    val leftDp = with(density) { (rect.x / 100f * size.width).toDp() }
    val topDp  = with(density) { (rect.y / 100f * size.height).toDp() }
    val widthDp  = with(density) { (rect.w / 100f * size.width).toDp() }
    val heightDp = with(density) { (rect.h / 100f * size.height).toDp() }

    Box(
        modifier = Modifier
            .offset(x = leftDp, y = topDp)
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(4.dp))
            .background(StudioColors.DragRectFill)
            .drawBehind {
                drawRoundRect(
                    color = StudioColors.DragRectBorder,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    ),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
    )
}
```

---

## 9. Annotations 패널 구현

`StudioInspector`는 `vm.selectedId == null`이면 `AnnotationsPanel`,
그렇지 않으면 `AnnotationDetail`을 보여준다. 패널 전환은 transition 없음
(스펙: instant).

```kotlin
@Composable
fun StudioInspector(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val selected = vm.selectedAnnotation
    Box(
        modifier = modifier
            .leftBorder(StudioColors.Line)
            .background(StudioColors.Bg0),
    ) {
        if (selected == null) {
            AnnotationsPanel(
                annotations = vm.annotations,
                onSelect = vm::selectAnnotation,
                onStartAnnotating = { vm.setTool(StudioTool.ANNOTATE) },
            )
        } else {
            AnnotationDetail(
                annotation = selected,
                onBack = { vm.selectAnnotation(null) },
                onUpdate = { transform -> vm.updateAnnotation(selected.id, transform) },
                onDelete = { vm.deleteAnnotation(selected.id) },
            )
        }
    }
}
```

### 9.1 AnnotationsPanel

```kotlin
@Composable
fun AnnotationsPanel(
    annotations: List<Annotation>,
    onSelect: (String) -> Unit,
    onStartAnnotating: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHead(title = "Annotations", count = annotations.size)
        if (annotations.isEmpty()) {
            EmptyState(onStartAnnotating = onStartAnnotating)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(annotations, key = { _, ann -> ann.id }) { index, ann ->
                    AnnotationRow(
                        annotation = ann,
                        index = index,
                        onClick = { onSelect(ann.id) },
                    )
                }
            }
        }
    }
}
```

### 9.2 AnnotationRow

```kotlin
@Composable
fun AnnotationRow(
    annotation: Annotation,
    index: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) StudioColors.Bg2 else Color.Transparent, tween(120),
        label = "annRowBg")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null,
                       onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // num pill
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(severityColor(annotation.severity)),
            contentAlignment = Alignment.Center,
        ) {
            Text((index + 1).toString(),
                 fontSize = 11.sp, fontWeight = FontWeight.Bold,
                 color = StudioColors.Bg0, fontFamily = InterFamily)
        }
        // body
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(annotation.label, style = StudioType.AnnRowTitle, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            val commentText = if (annotation.comment.isBlank()) "No comment"
                              else annotation.comment
            Text(
                text = commentText,
                style = StudioType.AnnRowComment.copy(
                    color = if (annotation.comment.isBlank())
                        Color(0xFF999999) else StudioColors.Txt1,
                    fontStyle = if (annotation.comment.isBlank())
                        FontStyle.Italic else FontStyle.Normal,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // status pill
        StatusPill(annotation.status)
    }
}

@Composable
fun StatusPill(status: AnnotationStatus) {
    val text = when (status) {
        AnnotationStatus.OPEN -> "OPEN"
        AnnotationStatus.IN_PROGRESS -> "IN-PROGRESS"
        AnnotationStatus.RESOLVED -> "RESOLVED"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(statusPillBg(status))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, letterSpacing = 0.08.em,
             color = statusDotColor(status), fontFamily = InterFamily,
             fontWeight = FontWeight.SemiBold)
    }
}
```

### 9.3 EmptyState

```kotlin
@Composable
fun EmptyState(onStartAnnotating: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioColors.Bg2)
                .border(1.dp, StudioColors.Line, RoundedCornerShape(12.dp))
                .padding(bottom = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("◇", color = StudioColors.Txt2, fontSize = 18.sp,
                 fontFamily = InterFamily)
        }
        Spacer(Modifier.height(8.dp))
        Text("No annotations yet",
             fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             color = StudioColors.Txt0, fontFamily = InterFamily)
        Spacer(Modifier.height(8.dp))
        Text(
            buildAnnotatedString {
                append("Switch to ")
                withStyle(SpanStyle(color = StudioColors.Txt0)) { append("Annotate") }
                append(", then click a widget or drag a region on the preview.")
            },
            fontSize = 12.sp, color = StudioColors.Txt1,
            lineHeight = 18.sp, fontFamily = InterFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(label = "Start annotating", mini = true, onClick = onStartAnnotating)
    }
}
```

### 9.4 AnnotationDetail

```kotlin
@Composable
fun AnnotationDetail(
    annotation: Annotation,
    onBack: () -> Unit,
    onUpdate: ((Annotation) -> Annotation) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHead(title = "Annotation", count = 1)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(onClick = onBack)

            Field(label = "Label") {
                StudioTextField(
                    value = annotation.label,
                    onValueChange = { v -> onUpdate { it.copy(label = v) } },
                    singleLine = true,
                )
            }
            Field(label = "Severity") {
                SeveritySegmented(
                    value = annotation.severity,
                    onChange = { v -> onUpdate { it.copy(severity = v) } },
                )
            }
            Field(label = "Comment") {
                StudioTextField(
                    value = annotation.comment,
                    onValueChange = { v -> onUpdate { it.copy(comment = v) } },
                    minLines = 4,
                    multiline = true,
                )
            }
            Field(label = "Status") {
                StatusSegmented(
                    value = annotation.status,
                    onChange = { v -> onUpdate { it.copy(status = v) } },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .topBorder(StudioColors.LineSoft)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DangerButton(label = "Delete", onClick = onDelete)
            GhostButton(label = "Done", onClick = onBack)
        }
    }
}
```

### 9.5 Field, BackButton, StudioTextField

```kotlin
@Composable
fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = StudioType.FieldLabel)
        content()
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered) StudioColors.Txt0 else StudioColors.Txt2, tween(120),
        label = "backFg")
    Text(
        "← All annotations",
        color = color, fontSize = 11.sp, fontFamily = InterFamily,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null,
                       onClick = onClick),
    )
}

@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    multiline: Boolean = false,
    minLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (focused) StudioColors.Accent else StudioColors.Line, tween(120),
        label = "tfBorder")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (multiline) minLines else 1,
        textStyle = StudioType.Base.copy(fontSize = 13.sp,
                                         lineHeight = if (multiline) 19.5.sp else 16.sp),
        cursorBrush = SolidColor(StudioColors.Accent),
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(StudioColors.Bg2)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .let { if (multiline) it.heightIn(min = 80.dp) else it },
    )
}
```

### 9.6 SeveritySegmented / StatusSegmented

severity active 셀은 inline severity color. status active 셀은 단색
`Bg3`. 두 컴포넌트 모두 같은 segmented 베이스를 쓴다.

```kotlin
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    activeBg: (T) -> Color,
    activeFg: (T) -> Color,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(StudioColors.Bg2)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isActive = option == selected
            val bg by animateColorAsState(
                if (isActive) activeBg(option) else Color.Transparent,
                tween(120), label = "segBg")
            val fg by animateColorAsState(
                if (isActive) activeFg(option) else StudioColors.Txt1,
                tween(120), label = "segFg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(bg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option).uppercase(),
                     fontSize = 11.sp, color = fg,
                     letterSpacing = 0.06.em, fontFamily = InterFamily,
                     fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SeveritySegmented(value: Severity, onChange: (Severity) -> Unit) {
    Segmented(
        options = listOf(Severity.HIGH, Severity.MED, Severity.LOW),
        selected = value,
        label = {
            when (it) { Severity.HIGH -> "High"; Severity.MED -> "Med"; Severity.LOW -> "Low" }
        },
        activeBg = { severityColor(it) },          // inline style — severity 색
        activeFg = { StudioColors.Bg0 },
        onSelect = onChange,
    )
}

@Composable
fun StatusSegmented(value: AnnotationStatus, onChange: (AnnotationStatus) -> Unit) {
    Segmented(
        options = listOf(AnnotationStatus.OPEN, AnnotationStatus.IN_PROGRESS,
                         AnnotationStatus.RESOLVED),
        selected = value,
        label = {
            when (it) {
                AnnotationStatus.OPEN -> "Open"
                AnnotationStatus.IN_PROGRESS -> "In progress"
                AnnotationStatus.RESOLVED -> "Resolved"
            }
        },
        activeBg = { StudioColors.Bg3 },           // CSS class — 고정 색
        activeFg = { StudioColors.Txt0 },
        onSelect = onChange,
    )
}
```

### 9.7 DangerButton

```kotlin
@Composable
fun DangerButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) StudioColors.Danger.copy(alpha = 0.10f) else Color.Transparent,
        tween(120), label = "dangerBg")
    val border by animateColorAsState(
        if (hovered) StudioColors.Danger.copy(alpha = 0.30f) else Color.Transparent,
        tween(120), label = "dangerBorder")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = StudioColors.Danger, fontSize = 12.sp,
             fontWeight = FontWeight.Medium, fontFamily = InterFamily)
    }
}
```

---

## 10. Gesture 처리

`PreviewSurface`는 두 개의 책임을 진다:

1. Annotate 모드에서 drag (region 생성).
2. Select 모드에서 빈 영역 click → `selectedId = null`.

PinRect/PinTag는 자식이 own click을 가져간다 (HTML의 `stopPropagation`
대응). drag도 PinRect 위에서 시작되면 PinRect의 click이 우선이다 — Compose의
default pointer handling은 자식이 consume하면 부모 gesture는 이벤트를
받지 않는다. annotate 모드에서는 PinRect의 `clickable`이 무력화되거나
부모로 흘려보내야 한다. 가장 단순한 방안:

- annotate 모드에서는 PinRect를 그리지 않는다. (스펙: annotate 모드에서
  pin click은 정의되지 않는다 — 새 annotation을 만드는 모드.)
- 또는 `Modifier.clickable(enabled = tool == StudioTool.SELECT)`로 비활성화.

본 가이드는 후자를 권장한다 (annotate 모드에서 pin이 보이는 것은
시각적으로 일관됨).

### 10.1 annotateDragGestures

```kotlin
fun Modifier.annotateDragGestures(
    tool: StudioTool,
    onBegin: (Offset, String?) -> Unit,
    onUpdate: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onSelectEmpty: () -> Unit,
): Modifier = this
    .pointerInput(tool) {
        if (tool != StudioTool.ANNOTATE) {
            // Select mode: tap on empty surface clears selection
            detectTapGestures { onSelectEmpty() }
            return@pointerInput
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val widget = hitTestWidget(down.position)
            onBegin(down.position, widget)
            var lastPos = down.position
            try {
                drag(down.id) { change ->
                    lastPos = change.position
                    onUpdate(lastPos)
                    change.consume()
                }
                onEnd()
            } catch (_: CancellationException) {
                onCancel()
                throw CancellationException()
            }
        }
    }
```

`hitTestWidget`은 widget element의 좌표를 알아야 한다. Compose에서는
`Modifier.semantics { testTag = "widget:login-button" }`을 widget 마다
달고, 부모가 rendered semantic node tree를 traversal해서 찾는다. 단순
경로는 widget mock 트리에 직접 좌표를 가진 `Modifier.onGloballyPositioned`
콜백을 둬서 `Map<String, Rect>`를 위로 끌어올린 뒤, drag begin 시 lookup
하는 방식이다.

```kotlin
data class WidgetEntry(val tag: String, val boundsInSurface: Rect)

class WidgetRegistry {
    private val entries = mutableStateListOf<WidgetEntry>()

    fun register(tag: String, bounds: Rect) {
        val idx = entries.indexOfFirst { it.tag == tag }
        if (idx >= 0) entries[idx] = WidgetEntry(tag, bounds)
        else entries.add(WidgetEntry(tag, bounds))
    }

    fun hitTest(point: Offset): String? =
        entries
            .filter { it.boundsInSurface.contains(point) }
            .minByOrNull { it.boundsInSurface.width * it.boundsInSurface.height }
            ?.tag
}

val LocalWidgetRegistry = compositionLocalOf<WidgetRegistry> { WidgetRegistry() }

fun Modifier.studioWidget(tag: String): Modifier = composed {
    val registry = LocalWidgetRegistry.current
    var surfaceCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coords ->
        // surface-relative bounds
        val parent = coords.parentLayoutCoordinates ?: return@onGloballyPositioned
        val rectInParent = parent.localBoundingBoxOf(coords, clipBounds = false)
        registry.register(tag, rectInParent)
    }
}
```

`PreviewSurface`는 `LocalWidgetRegistry`를 provide하고, drag begin 시
`registry.hitTest(downPosition)`을 부른다.

### 10.2 Drag thresholds

`updateDrag` 내부에서 `dx > 0.6f || dy > 0.6f` 검사로 `dragMoved`를
설정. `endDrag`는 region 조건 (`w > 1.5 && h > 1.5`)과 widget 조건을
2.4절 그대로 분기.

### 10.3 Widget hover 표현 (annotate 모드)

각 `studioWidget`은 annotate 모드에서 hovered 상태일 때 outline을 그린다.
`hoverable` + `LocalStudioTool` (또는 prop) 결합:

```kotlin
fun Modifier.studioWidgetHoverOutline(tool: StudioTool): Modifier = composed {
    if (tool != StudioTool.ANNOTATE) return@composed this
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered) StudioColors.WidgetHoverOutline else Color.Transparent,
        tween(120), label = "widgetOutline")
    this
        .hoverable(interaction)
        .border(2.dp, color, RoundedCornerShape(4.dp))
}
```

`Modifier.studioWidget(tag) + studioWidgetHoverOutline(tool)` 두 modifier를
함께 적용한다.

---

## 11. 애니메이션

### 11.1 pulse-a (annotate hint dot)

7.3절 참조. `rememberInfiniteTransition` + `animateFloat` (opacity, scale)
조합. CSS: 1.4s 주기, 0%/100% (1, 1) → 50% (0.5, 1.3). Compose `tween(700,
LinearEasing) + Reverse`로 1.4초 주기 왕복을 만든다.

### 11.2 hover transition 120ms

거의 모든 hover 효과는 `animateColorAsState(target, tween(120), label = ...)`
한 줄로 끝난다. 본 가이드 전반에서 일관되게 사용했다. dp 변환은
`animateDpAsState`.

### 11.3 패널 / 도구 전환

스펙: 즉시 (instant). transition modifier를 두지 않으면 그게 곧 instant이다.
`AnimatedVisibility` 같은 fade를 절대 쓰지 말 것. `if/else`로 직접 분기한다.

### 11.4 PrimaryButton hover lift

`animateDpAsState(if (hovered) -1.dp else 0.dp)` + `Modifier.offset(y = ...)`.
5.4절 코드 그대로.

### 11.5 history-item active inset shadow

CSS: `box-shadow: inset 2px 0 0 #b8d36a`. Compose에서는 `drawBehind`로
좌측 2px 라인을 그린다 (6.3절).

---

## 12. 기존 코드 교체 가이드

`pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`는
HTML/CSS/JS 자산이고, 본 가이드의 Compose 구현은 별도 모듈 (`pointpatch-compose-core`)에
들어가는 in-app 콘솔 화면이다. 본 문서의 권장 단계는 다음과 같다:

### 단계 0 — 목표 정렬

- `FeedbackConsoleAssets.kt`는 MCP 서버가 브라우저에 송출하는 HTML이다.
  in-app 콘솔이 이 HTML을 대체하는 것이 아니라, **별도 화면**으로 추가된다.
- 본 가이드의 `StudioShell`은 `pointpatch-compose-core`의 새 화면이며,
  기존 `FeedbackConsoleAssets`와 공존한다.
- 그렇지 않고 HTML을 Compose UI로 완전 교체하는 시나리오라면, 단계 0은
  `FeedbackConsoleServer`가 더 이상 HTML을 응답하지 않는 결정이 선행돼야
  한다 — 본 가이드 범위 밖이며 별도 ADR이 필요하다.

### 단계 1 — 새 패키지 생성

`pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/console/studio/`
하위에 다음 파일을 만든다.

```
studio/
├── StudioShell.kt              (4장 — 루트)
├── StudioViewModel.kt          (2장)
├── model/
│   ├── Annotation.kt
│   ├── Snapshot.kt
│   ├── Severity.kt
│   ├── AnnotationStatus.kt
│   ├── StudioTool.kt
│   └── RectPercent.kt
├── theme/
│   ├── StudioColors.kt         (3장)
│   └── StudioType.kt
├── topbar/
│   ├── StudioTopbar.kt         (5장)
│   ├── BrandMark.kt
│   └── Breadcrumb.kt
├── history/
│   ├── StudioHistory.kt        (6장)
│   ├── HistoryItem.kt
│   └── HistoryStrip.kt
├── canvas/
│   ├── StudioCanvas.kt         (7장)
│   ├── CanvasToolbar.kt
│   ├── ToolGroup.kt
│   ├── PhoneFrame.kt
│   ├── PreviewSurface.kt
│   ├── PinRect.kt              (8장)
│   ├── DragRect.kt
│   └── WidgetRegistry.kt       (10장)
├── inspector/
│   ├── StudioInspector.kt      (9장)
│   ├── AnnotationsPanel.kt
│   ├── AnnotationRow.kt
│   ├── AnnotationDetail.kt
│   ├── Segmented.kt
│   └── EmptyState.kt
└── common/
    ├── PanelHead.kt
    ├── Buttons.kt              (GhostButton, PrimaryButton, DangerButton)
    └── Borders.kt              (rightBorder, leftBorder, topBorder, bottomBorder)
```

### 단계 2 — 기존 화면 진입점 교체

기존 in-app 콘솔이 있다면 (예: `FeedbackConsoleScreen` Composable),
다음과 같이 `StudioShell`을 호출하도록 바꾼다:

```kotlin
@Composable
fun FeedbackConsoleScreen() {
    val vm: StudioViewModel = viewModel()
    StudioShell(vm = vm, modifier = Modifier.fillMaxSize())
}
```

기존 pending-item-centric Composable (`PendingItemList`,
`SelectionSummary`, `Composer`, `DraftInspector` 등)은 `git rm`으로
제거한다. 변환 매핑:

| 기존 (pending-item) | 신규 (Studio) |
|---|---|
| `PendingItem` 데이터 클래스 | `Annotation` (rectPercent + severity/status 추가) |
| `currentSelection` | `selectedId` (annotation id reference) |
| `addItemsFlow` flag | 항상 활성, `tool == ANNOTATE`이 같은 의미 |
| `Composer` 패널 (선택 시 표시) | `AnnotationDetail` |
| `DraftInspector` 패널 (선택 없음) | `AnnotationsPanel` |
| Save batch 버튼 | `Save snapshot` (Topbar) |
| Cancel add flow / Clear draft | (제거) — `New session` 하나로 합침 |
| Add 버튼 (preview freeze) | (제거) — preview는 항상 freeze된 상태로 가정 또는 별도 freeze 토글 |

### 단계 3 — preview 데이터 연결

`DevicePreviewContent`는 본 문서의 placeholder다. 실제 PointPatch는
`/api/preview/{id}/screenshot/full`을 받아 비트맵으로 렌더한다. Compose
in-app에서는 동일 API를 호출하거나, 디바이스 직접 캡처를 거친다 — 그
부분은 `pointpatch-compose-overlay` 또는 `pointpatch-compose-sidekick`의
기존 preview 캡처 코드를 재사용한다.

`PreviewSurface`의 `DevicePreviewContent` 자리에 비트맵을 그리는
`Image(painter = ...)`를 둔다. `WidgetRegistry`는 비트맵 위에 직접 등록되지
않으므로, semantics tree를 기반으로 hit test하려면 별도 메커니즘 (예:
오버레이 mock semantics tree)을 구성해야 한다. 단순 region 드래그만 필요
하다면 widget hit test를 `null`로 두고 region annotation만 만들어도 된다.

### 단계 4 — 테스트 갱신

- `FeedbackConsoleServerTest.kt`는 HTML 응답 contract를 검증하므로 그대로
  둔다 — Compose UI 추가가 영향을 주지 않는다.
- 새 Compose UI 테스트는 `pointpatch-compose-core/src/test/kotlin/.../studio/`에
  추가한다:
  - `StudioViewModelTest`: 2.3 / 2.4의 모든 액션 동작을 검증.
  - `StudioShellComposeTest` (`androidTest`): tool 전환, 빈 상태,
    annotation row click → detail 진입 등을 robolectric/compose-test로 검증.

### 단계 5 — 색상 / 타이포 검증

`StudioColors`의 hex가 스펙 hex와 글자 단위로 일치하는지 grep으로 비교:

```bash
grep -E "0xFF[A-Fa-f0-9]{6}" .../StudioColors.kt
```

스펙 표 (UI/UX Analysis 문서 색상 토큰 섹션)와 1:1 대응 시켜 누락 없이
사용한다. 누락된 색은 그 자리에 hardcode 하지 말고 모두 `StudioColors`에
추가한다.

---

## 부록 A — 키보드 단축키 (스펙 외 추정)

스펙에는 명시되지 않았으나, 기존 HTML이 `Esc` / `A` / `⌘S` / `⌘N`을 처리한다.
Compose에서는 `Modifier.onPreviewKeyEvent`를 `StudioShell` 루트에 둔다:

```kotlin
.modifier.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when {
        event.key == Key.Escape -> { vm.selectAnnotation(null); true }
        event.key == Key.A && !event.isMetaPressed -> { vm.setTool(StudioTool.ANNOTATE); true }
        event.key == Key.S && event.isMetaPressed -> { vm.saveSnapshot(); true }
        event.key == Key.N && event.isMetaPressed -> { vm.newSession(); true }
        else -> false
    }
}
```

`isMetaPressed`는 macOS에서 ⌘이고, Windows/Linux에서는 `isCtrlPressed`로
대체. 두 조건을 OR로 묶는다.

---

## 부록 B — 화면 비율 / 반응형

스펙은 1100px+ 기준 desktop 레이아웃이며, 폰 폭에서는 의미가 없다. PointPatch
in-app Studio는 태블릿 가로 모드 또는 데스크톱 (Compose Desktop)을
타겟팅한다고 가정한다. `StudioBody`에 `BoxWithConstraints`를 두고
`maxWidth < 900.dp`인 경우 placeholder ("Resize to ≥ 900dp wide")로
대체하면 된다 — HTML의 `@media (max-width: 899px)` 규칙과 동일하다.

```kotlin
@Composable
fun StudioBody(vm: StudioViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth < 900.dp) {
            ResizePlaceholder()
            return@BoxWithConstraints
        }
        Row(modifier = Modifier.fillMaxSize()) { /* 4.2와 동일 */ }
    }
}
```

---

## 부록 C — 구현 우선순위 / 점진적 머지 전략

전체를 한 번에 머지하기보다 다음 순서로 PR을 쪼갠다:

1. `model/` + `theme/` + `StudioViewModel` (UI 없음, 테스트 포함).
2. `common/` (Buttons, PanelHead, Borders) + `topbar/`.
3. `history/` (LazyColumn, HistoryItem, HistoryStrip).
4. `inspector/` (AnnotationsPanel, AnnotationDetail, Segmented).
5. `canvas/`의 PhoneFrame + PreviewSurface (drag gesture 제외).
6. `canvas/`의 PinRect + DragRect + drag gesture.
7. WidgetRegistry, widget hit test, hover outline.
8. 키보드 단축키, 반응형 placeholder.

각 PR은 자체 preview Composable (`@Preview`)와 단위 테스트를 동반한다.
작은 단위로 시각적 검증이 가능해야 hex 색, 패딩, 폰트 weight 같은 정밀
값에서의 회귀를 빠르게 잡을 수 있다.
