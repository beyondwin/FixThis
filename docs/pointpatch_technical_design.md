# PointPatch for Android Compose — 상세 기술 설계서

**문서 목적:** 이 문서는 `PointPatch for Android Compose`를 실제로 구현하기 위한 상세 기술 설계서다. PRD가 “무엇을 만들 것인가”를 설명한다면, 이 문서는 “어떤 방식으로 구현할 것인가”를 설명한다.  
**대상 독자:** Codex, Android 개발자, Gradle plugin 개발자, MCP/CLI 구현자  
**제품명:** PointPatch for Android Compose  
**짧은 이름:** PointPatch Compose  
**CLI:** `pointpatch`  
**문서 버전:** v1.0  
**작성일:** 2026-05-03

---

## 0. 구현 방향 요약

PointPatch는 다음 구조로 구현한다.

```text
Android debug app
  └─ pointpatch-compose-sidekick
       ├─ AndroidX Startup autoinit
       ├─ ActivityLifecycleCallbacks
       ├─ DecorView overlay
       ├─ Compose RootForTest discovery
       ├─ SemanticsOwner tree read
       ├─ Node selection
       ├─ Screenshot capture
       ├─ Annotation export
       └─ Local bridge for MCP

Desktop
  └─ pointpatch CLI
       ├─ status
       ├─ setup
       ├─ run
       ├─ doctor
       └─ mcp stdio server

Gradle
  └─ pointpatch-gradle-plugin
       ├─ debug runtime dependency injection
       ├─ source index generation
       ├─ project metadata generation
       └─ release safety
```

핵심 구현 원칙은 다음이다.

```text
1. Android Jetpack Compose only
2. debug-only
3. no required testTags
4. no AccessibilityService
5. no core compiler plugin
6. in-app annotation UX first
7. MCP optional
8. local-first export
9. failure-safe fallback
```

---

## 1. 전체 시스템 아키텍처

### 1.1 Runtime architecture

```text
User taps PointPatch button
        ↓
Selection layer enabled
        ↓
User taps Compose UI
        ↓
PointPatch sidekick reads current Activity decorView
        ↓
Find Compose roots implementing RootForTest
        ↓
Read SemanticsOwner from each root
        ↓
Collect merged + unmerged SemanticsNode lists
        ↓
Map SemanticsNode → PointPatchNode
        ↓
Select best node by tap coordinate and scoring
        ↓
Collect candidatesAtPoint and nearbyNodes
        ↓
Capture screenshot full + crop
        ↓
Load source index if available
        ↓
Match sourceCandidates
        ↓
Build PointPatchAnnotation
        ↓
User enters comment
        ↓
Export Markdown/JSON or return through MCP
```

### 1.2 MCP architecture

MCP server는 Android 앱 안에서 직접 실행하지 않는다. Desktop process로 실행한다.

```text
Codex / Claude Code / Cursor / VS Code
        │
        │ stdio MCP
        ▼
pointpatch mcp
        │
        │ adb bridge
        ▼
debug app sidekick
        │
        │ Compose runtime APIs
        ▼
running Android Compose app
```

이 구조를 선택하는 이유:

- Android 앱에 HTTP server를 기본으로 열지 않는다.
- core artifact에 `INTERNET` permission을 넣지 않는다.
- MCP client가 desktop process를 실행하는 표준 흐름에 맞다.
- Android sidekick은 runtime UI data 수집에만 집중한다.
- 보안과 privacy 설명이 단순해진다.

### 1.3 Data flow

```text
Semantics snapshot
    + Tap coordinate
    + Screenshot
    + Source index
    + User comment
        ↓
PointPatchAnnotation
        ↓
MarkdownFormatter / JsonFormatter / MpcToolResultFormatter
```

---

## 2. Repository 구조

권장 repository 이름:

```text
pointpatch-compose
```

권장 구조:

```text
pointpatch-compose/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  README.md
  LICENSE
  docs/
    prd.md
    decisions.md
    technical-design.md
    output-schema.md
    privacy.md
    troubleshooting.md
    mcp.md

  pointpatch-compose-core/
    build.gradle.kts
    src/main/java/io/github/pointpatch/compose/core/...

  pointpatch-compose-overlay/
    build.gradle.kts
    src/main/java/io/github/pointpatch/compose/overlay/...

  pointpatch-compose-sidekick/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/io/github/pointpatch/compose/sidekick/...

  pointpatch-gradle-plugin/
    build.gradle.kts
    src/main/kotlin/io/github/pointpatch/gradle/...

  pointpatch-cli/
    build.gradle.kts
    src/main/kotlin/io/github/pointpatch/cli/...

  pointpatch-mcp/
    build.gradle.kts
    src/main/kotlin/io/github/pointpatch/mcp/...

  sample/
    build.gradle.kts
    src/main/java/io/github/pointpatch/sample/...
```

---

## 3. Module 설계

### 3.1 `pointpatch-compose-core`

역할:

- 공통 데이터 모델
- semantics node mapping
- node selection algorithm
- nearby node collection
- annotation model
- Markdown/JSON formatter
- source candidate matching
- redaction policy

Android dependency는 최소화한다. 가능한 pure Kotlin으로 유지한다.

주요 package:

```text
io.github.pointpatch.compose.core.model
io.github.pointpatch.compose.core.selection
io.github.pointpatch.compose.core.format
io.github.pointpatch.compose.core.source
io.github.pointpatch.compose.core.redaction
```

### 3.2 `pointpatch-compose-overlay`

역할:

- floating toolbar
- selection layer
- highlight overlay
- comment sheet
- copy/share buttons
- settings/connect UI

Compose UI module이다.

주요 package:

```text
io.github.pointpatch.compose.overlay.ui
io.github.pointpatch.compose.overlay.state
io.github.pointpatch.compose.overlay.theme
```

### 3.3 `pointpatch-compose-sidekick`

역할:

- AndroidX Startup
- Application lifecycle hook
- overlay install
- Compose root discovery
- semantics inspection
- screenshot capture
- clipboard/local file export
- bridge server for MCP

주요 package:

```text
io.github.pointpatch.compose.sidekick.init
io.github.pointpatch.compose.sidekick.lifecycle
io.github.pointpatch.compose.sidekick.overlay
io.github.pointpatch.compose.sidekick.inspect
io.github.pointpatch.compose.sidekick.screenshot
io.github.pointpatch.compose.sidekick.export
io.github.pointpatch.compose.sidekick.bridge
```

### 3.4 `pointpatch-gradle-plugin`

역할:

- Gradle plugin id: `io.github.pointpatch.compose`
- debug runtime dependency injection
- source index generation
- generated assets registration
- `.pointpatch/project.json` metadata generation

주요 package:

```text
io.github.pointpatch.gradle
io.github.pointpatch.gradle.task
io.github.pointpatch.gradle.source
```

### 3.5 `pointpatch-cli`

역할:

- `pointpatch status`
- `pointpatch setup`
- `pointpatch run`
- `pointpatch doctor`
- `pointpatch mcp`

Kotlin/JVM CLI로 구현한다. `pointpatch-mcp` module을 포함하거나 dependency로 둔다.

### 3.6 `pointpatch-mcp`

역할:

- stdio MCP JSON-RPC server
- tools/list, tools/call
- resources/list, resources/read
- Android sidekick bridge client

초기 버전은 MCP SDK에 강하게 의존하지 않고 JSON-RPC를 직접 구현할 수 있다. 단, protocol compatibility test를 둔다. MCP prompts endpoints와 prompts capability는 V1 surface가 아니며 future extension으로 둔다.

---

## 4. Gradle 설정 설계

### 4.1 사용자 설치 방식

Published artifact example:

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

Fallback after artifacts are published:

```kotlin
dependencies {
    debugImplementation("io.github.pointpatch:pointpatch-compose-sidekick:0.1.0")
}
```

이 fallback coordinate는 artifact가 publish된 이후의 외부 설치 예시다. 현재 repo sample은 composite build/project dependency wiring을 사용한다.

### 4.2 Gradle plugin extension

```kotlin
abstract class PointPatchExtension @Inject constructor(
    objects: ObjectFactory
) {
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val runtimeVersion: Property<String> =
        objects.property(String::class.java).convention("0.1.0")

    val addDebugRuntime: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val generateSourceIndex: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val generateProjectMetadata: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val includeScreenshots: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    val redactEditableText: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)
}
```

### 4.3 Plugin apply flow

```kotlin
class PointPatchGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "pointpatch",
            PointPatchExtension::class.java
        )

        project.plugins.withId("com.android.application") {
            configureAndroidApplication(project, extension)
        }
    }
}
```

### 4.4 Android Components API 사용

AGP variant-aware 작업은 `androidComponents`를 사용한다.

의도:

```kotlin
androidComponents.onVariants { variant ->
    if (!variant.debuggable) return@onVariants

    if (extension.addDebugRuntime.get()) {
        dependencies.add(
            "${variant.name}Implementation",
            "io.github.pointpatch:pointpatch-compose-sidekick:${extension.runtimeVersion.get()}"
        )
    }

    if (extension.generateSourceIndex.get()) {
        val task = tasks.register(
            "generate${variant.name.capitalized()}PointPatchSourceIndex",
            GeneratePointPatchSourceIndexTask::class.java
        ) {
            // source dirs, res dirs, output file 설정
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            task,
            GeneratePointPatchSourceIndexTask::outputDir
        )
    }
}
```

실제 AGP API 이름은 사용 버전에 맞게 조정한다.

### 4.5 Generated asset

Debug APK에 포함할 파일:

```text
assets/pointpatch/pointpatch-source-index.json
assets/pointpatch/pointpatch-build-info.json
```

### 4.6 Project metadata

CLI와 MCP server가 읽을 metadata:

```text
.pointpatch/project.json
```

예시:

```json
{
  "schemaVersion": "1.0",
  "projectRoot": "/Users/me/project",
  "appModule": "app",
  "applicationId": "com.example.app",
  "debugVariant": "debug",
  "sourceIndexAsset": "pointpatch/pointpatch-source-index.json",
  "generatedAtEpochMillis": 1777786212000
}
```

---

## 5. Android sidekick 초기화 설계

### 5.1 Manifest

`pointpatch-compose-sidekick/src/main/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application>
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">

            <meta-data
                android:name="io.github.pointpatch.compose.sidekick.init.PointPatchInitializer"
                android:value="androidx.startup" />
        </provider>
    </application>
</manifest>
```

원칙:

- core sidekick에는 permission을 추가하지 않는다.
- `INTERNET` permission은 별도 optional artifact가 생길 때만 고려한다.

### 5.2 Initializer

```kotlin
class PointPatchInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as? Application ?: return

        val isDebuggable =
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        if (!isDebuggable) return

        PointPatch.install(app)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

### 5.3 Global install object

```kotlin
object PointPatch {
    private val installed = AtomicBoolean(false)

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return

        val config = PointPatchConfig.load(application)
        val runtime = PointPatchRuntime(application, config)

        application.registerActivityLifecycleCallbacks(
            PointPatchActivityLifecycleCallbacks(runtime)
        )

        runtime.startBridgeIfEnabled()
    }
}
```

### 5.4 Runtime config

```kotlin
data class PointPatchConfig(
    val enabled: Boolean = true,
    val captureScreenshots: Boolean = true,
    val redactPassword: Boolean = true,
    val redactEditableText: Boolean = true,
    val includeRawProperties: Boolean = false,
    val maxCandidates: Int = 5,
    val maxNearbyNodes: Int = 12,
    val bridgeEnabled: Boolean = true
)
```

Config loading order:

```text
1. hardcoded safe defaults
2. assets/pointpatch/pointpatch-build-info.json
3. optional manifest metadata
4. runtime overrides, if any
```

---

## 6. Activity lifecycle와 overlay attach

### 6.1 Lifecycle callbacks

```kotlin
class PointPatchActivityLifecycleCallbacks(
    private val runtime: PointPatchRuntime
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        runtime.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        runtime.onActivityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        runtime.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
```

### 6.2 Runtime activity state

```kotlin
class PointPatchRuntime(
    private val application: Application,
    private val config: PointPatchConfig
) {
    private val currentActivityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val sessions = ConcurrentHashMap<Int, PointPatchActivitySession>()

    fun onActivityResumed(activity: Activity) {
        currentActivityRef.set(WeakReference(activity))
        val session = sessions.getOrPut(System.identityHashCode(activity)) {
            PointPatchActivitySession(activity, config)
        }
        session.attachOverlayIfNeeded()
    }

    fun onActivityPaused(activity: Activity) {
        // Keep overlay; no-op by default.
    }

    fun onActivityDestroyed(activity: Activity) {
        sessions.remove(System.identityHashCode(activity))?.detach()
    }

    fun currentActivity(): Activity? =
        currentActivityRef.get()?.get()
}
```

### 6.3 Overlay host

```kotlin
class PointPatchOverlayHostLayout(
    context: Context
) : FrameLayout(context) {
    init {
        tag = TAG
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    companion object {
        const val TAG = "io.github.pointpatch.compose.overlay.HOST"
    }
}
```

### 6.4 Attach logic

```kotlin
class PointPatchActivitySession(
    private val activity: Activity,
    private val config: PointPatchConfig
) {
    private var host: PointPatchOverlayHostLayout? = null
    private lateinit var controller: PointPatchOverlayController

    fun attachOverlayIfNeeded() {
        val decor = activity.window.decorView as? ViewGroup ?: return

        val existing = decor.findViewWithTag<View>(PointPatchOverlayHostLayout.TAG)
        if (existing != null) return

        val newHost = PointPatchOverlayHostLayout(activity)
        controller = PointPatchOverlayController(activity, newHost, config)

        newHost.addView(controller.createToolbarView())

        decor.addView(
            newHost,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        host = newHost
    }

    fun detach() {
        val decor = activity.window.decorView as? ViewGroup ?: return
        host?.let { decor.removeView(it) }
        host = null
    }
}
```

---

## 7. Overlay UI 설계

### 7.1 Overlay state machine

```kotlin
sealed interface OverlayMode {
    data object Idle : OverlayMode
    data object MenuOpen : OverlayMode
    data class Selecting(val requestId: String?) : OverlayMode
    data class ReviewingSelection(val draft: PointPatchDraft) : OverlayMode
    data class Commenting(val draft: PointPatchDraft) : OverlayMode
    data class Exported(val annotation: PointPatchAnnotation) : OverlayMode
}
```

### 7.2 Toolbar

위치:

- bottom-end
- system bars padding 고려
- size는 48dp~56dp

UI:

```text
[PointPatch]
```

터치 시 메뉴:

```text
Select UI
Recent
Connect AI Agent
```

### 7.3 Selection layer

Selection mode일 때만 full-screen `ComposeView`를 추가한다.

```kotlin
fun createSelectionLayerView(): ComposeView =
    ComposeView(activity).apply {
        setContent {
            PointPatchSelectionLayer(
                message = "수정할 UI를 탭하세요",
                onTap = { x, y -> controller.onSelectionTap(x, y) },
                onCancel = { controller.cancelSelection() }
            )
        }
    }
```

주의:

- selection layer는 tap을 consume한다.
- selection layer는 root discovery에서 제외되어야 한다.
- tap 좌표는 window coordinate로 변환한다.

### 7.4 Highlight layer

선택 후 selected node bounds를 표시한다.

```kotlin
@Composable
fun PointPatchHighlightLayer(
    selected: PointPatchNode?,
    candidates: List<ScoredPointPatchNode>
)
```

표시:

- selected bounds: 명확한 rectangle
- candidates: 약한 outline
- selected 없음: tap point marker

색상은 기본 theme에 맡기거나 사용자가 지정 가능하게 한다.

### 7.5 Comment sheet

```kotlin
@Composable
fun PointPatchCommentSheet(
    draft: PointPatchDraft,
    onCopyMarkdown: (String) -> Unit,
    onCopyJson: (String) -> Unit,
    onShare: (PointPatchAnnotation) -> Unit,
    onDismiss: () -> Unit
)
```

필수 정보:

- selected node summary
- screenshot crop preview
- comment text field
- source candidates summary
- Copy for AI
- Copy Markdown
- Copy JSON

---

## 8. Compose root discovery

### 8.1 목적

현재 Activity 안의 Compose root를 찾아 `RootForTest.semanticsOwner`에 접근한다.

### 8.2 Root handle model

```kotlin
data class ComposeRootHandle(
    val rootIndex: Int,
    val view: View,
    val rootForTest: RootForTest,
    val boundsInWindow: PointPatchRect,
    val zOrder: Int
)
```

### 8.3 Finder algorithm

```kotlin
object ComposeRootFinder {
    fun findRoots(decorView: View): List<ComposeRootHandle> {
        val result = mutableListOf<ComposeRootHandle>()
        var z = 0

        fun visit(view: View, skip: Boolean) {
            val isPointPatchOverlay =
                view.tag == PointPatchOverlayHostLayout.TAG

            if (skip || isPointPatchOverlay) return

            if (view is RootForTest) {
                result += ComposeRootHandle(
                    rootIndex = result.size,
                    view = view,
                    rootForTest = view,
                    boundsInWindow = view.boundsInWindow().toPointPatchRect(),
                    zOrder = z++
                )
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i), skip = false)
                }
            }
        }

        visit(decorView, skip = false)
        return result
    }
}
```

### 8.4 Root 우선순위

tap coordinate가 있는 경우:

```text
1. tap 좌표를 포함하는 root
2. 더 작은 bounds root
3. 더 높은 zOrder root
```

Dialog/Popup root가 있으면 main root보다 작은 bounds 또는 높은 z-order로 우선될 수 있다.

---

## 9. Semantics inspection 설계

### 9.1 Snapshot request

```kotlin
data class SnapshotRequest(
    val tap: TapPoint? = null,
    val includeMergedTree: Boolean = true,
    val includeUnmergedTree: Boolean = true,
    val maxNodes: Int = 500,
    val includeRawProperties: Boolean = false
)
```

### 9.2 Snapshot result

```kotlin
data class ScreenSnapshot(
    val createdAtEpochMillis: Long,
    val app: AppInfo,
    val activity: ActivityInfo,
    val roots: List<RootSnapshot>,
    val sourceIndexAvailable: Boolean,
    val errors: List<String>
)

data class RootSnapshot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode>,
    val unmergedNodes: List<PointPatchNode>
)
```

### 9.3 SemanticsInspector

```kotlin
class SemanticsInspector(
    private val config: PointPatchConfig
) {
    fun inspect(activity: Activity, request: SnapshotRequest): ScreenSnapshot {
        val decor = activity.window.decorView
        val roots = ComposeRootFinder.findRoots(decor)

        val rootSnapshots = roots.mapIndexed { index, root ->
            inspectRoot(index, root, request)
        }

        return ScreenSnapshot(
            createdAtEpochMillis = System.currentTimeMillis(),
            app = AppInfo.from(activity),
            activity = ActivityInfo.from(activity),
            roots = rootSnapshots,
            sourceIndexAvailable = SourceIndexRepository.hasIndex(activity),
            errors = emptyList()
        )
    }

    private fun inspectRoot(
        index: Int,
        root: ComposeRootHandle,
        request: SnapshotRequest
    ): RootSnapshot {
        val owner = root.rootForTest.semanticsOwner

        val merged = if (request.includeMergedTree) {
            owner.getAllSemanticsNodes(
                mergingEnabled = true,
                skipDeactivatedNodes = true
            )
        } else emptyList()

        val unmerged = if (request.includeUnmergedTree) {
            owner.getAllSemanticsNodes(
                mergingEnabled = false,
                skipDeactivatedNodes = true
            )
        } else emptyList()

        return RootSnapshot(
            rootIndex = index,
            boundsInWindow = root.boundsInWindow,
            mergedNodes = merged.map { SemanticsNodeMapper.map(it, index, TreeKind.MERGED, config) },
            unmergedNodes = unmerged.map { SemanticsNodeMapper.map(it, index, TreeKind.UNMERGED, config) }
        )
    }
}
```

### 9.4 Mapping property

```kotlin
object SemanticsNodeMapper {
    fun map(
        node: SemanticsNode,
        rootIndex: Int,
        treeKind: TreeKind,
        config: PointPatchConfig
    ): PointPatchNode {
        val cfg = node.config

        val isPassword = cfg.getOrNull(SemanticsProperties.Password) == true

        val text = cfg.getOrNull(SemanticsProperties.Text)
            ?.map { it.text }
            .orEmpty()

        val editableText = cfg.getOrNull(SemanticsProperties.EditableText)
            ?.text

        val contentDescription = cfg.getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()

        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString()
        val testTag = cfg.getOrNull(SemanticsProperties.TestTag)
        val stateDescription = cfg.getOrNull(SemanticsProperties.StateDescription)
        val selected = cfg.getOrNull(SemanticsProperties.Selected)
        val disabled = cfg.contains(SemanticsProperties.Disabled)

        val actions = mutableListOf<String>()
        if (cfg.contains(SemanticsActions.OnClick)) actions += "OnClick"
        if (cfg.contains(SemanticsActions.OnLongClick)) actions += "OnLongClick"
        if (cfg.contains(SemanticsActions.SetText)) actions += "SetText"
        if (cfg.contains(SemanticsActions.ScrollBy)) actions += "ScrollBy"
        if (cfg.contains(SemanticsActions.ScrollToIndex)) actions += "ScrollToIndex"

        val redaction = RedactionPolicy.apply(
            isPassword = isPassword,
            editableText = editableText,
            text = text,
            config = config
        )

        return PointPatchNode(
            uid = "$rootIndex:$treeKind:${node.id}",
            composeNodeId = node.id,
            rootIndex = rootIndex,
            treeKind = treeKind,
            boundsInWindow = node.boundsInWindow.toPointPatchRect(),
            text = redaction.text,
            editableText = redaction.editableText,
            contentDescription = contentDescription,
            role = role,
            testTag = testTag,
            stateDescription = stateDescription,
            selected = selected,
            enabled = !disabled,
            actions = actions,
            isPassword = isPassword,
            isSensitive = redaction.redacted,
            path = buildPath(node),
            rawProperties = if (config.includeRawProperties) safeProperties(cfg) else emptyMap()
        )
    }
}
```

주의:

- Compose API 버전에 따라 property 접근 코드가 달라질 수 있다.
- 구현 시 compile 가능한 API signature에 맞게 조정한다.
- redaction이 먼저 적용되어야 한다.

---

## 10. Node selection algorithm

### 10.1 Models

```kotlin
data class SelectionResult(
    val selectedNode: PointPatchNode?,
    val candidatesAtPoint: List<ScoredPointPatchNode>,
    val nearbyNodes: List<PointPatchNode>,
    val tap: TapPoint,
    val reason: String
)

data class ScoredPointPatchNode(
    val node: PointPatchNode,
    val score: Double,
    val breakdown: Map<String, Double>
)
```

### 10.2 Candidate filter

```text
1. boundsInWindow contains tap
2. bounds area > 0
3. not PointPatch overlay
4. not invisible/deactivated, if known
```

### 10.3 Scoring

```kotlin
object NodeSelector {
    fun select(
        snapshot: ScreenSnapshot,
        tap: TapPoint,
        options: SelectionOptions
    ): SelectionResult {
        val allNodes = snapshot.roots.flatMap { it.mergedNodes + it.unmergedNodes }

        val candidates = allNodes
            .filter { it.boundsInWindow.contains(tap.xInWindow, tap.yInWindow) }
            .filter { it.boundsInWindow.area > 0f }
            .map { scoreNode(it, snapshot) }
            .sortedWith(candidateComparator())

        val selected = candidates.firstOrNull()?.node
        val nearby = NearbyNodeCollector.collect(allNodes, selected, tap, options)

        return SelectionResult(
            selectedNode = selected,
            candidatesAtPoint = candidates.take(options.maxCandidates),
            nearbyNodes = nearby,
            tap = tap,
            reason = if (selected == null) "No semantics node found at tap point" else "Selected by coordinate scoring"
        )
    }

    private fun scoreNode(
        node: PointPatchNode,
        snapshot: ScreenSnapshot
    ): ScoredPointPatchNode {
        val b = linkedMapOf<String, Double>()

        if ("OnClick" in node.actions) b["action.click"] = 1000.0
        if ("SetText" in node.actions) b["action.setText"] = 600.0
        if ("OnLongClick" in node.actions) b["action.longClick"] = 300.0

        if (node.role != null) b["role"] = 250.0
        if (node.text.isNotEmpty()) b["text"] = 250.0
        if (node.contentDescription.isNotEmpty()) b["contentDescription"] = 250.0
        if (node.testTag != null) b["testTag"] = 400.0
        if (node.stateDescription != null) b["stateDescription"] = 150.0
        if (node.selected != null) b["selected"] = 100.0

        if (node.treeKind == TreeKind.MERGED && node.hasMeaningfulSemantic()) {
            b["tree.merged"] = 150.0
        }

        val area = node.boundsInWindow.area.coerceAtLeast(1f)
        b["size"] = min(300.0, 100000.0 / area)

        if (node.isLikelyRootContainer(snapshot)) {
            b["penalty.rootContainer"] = -500.0
        }

        if (!node.hasMeaningfulSemantic()) {
            b["penalty.emptySemantic"] = -300.0
        }

        if (!node.enabled) {
            b["penalty.disabled"] = -100.0
        }

        return ScoredPointPatchNode(
            node = node,
            score = b.values.sum(),
            breakdown = b
        )
    }
}
```

### 10.4 Comparator

```kotlin
fun candidateComparator(): Comparator<ScoredPointPatchNode> =
    compareByDescending<ScoredPointPatchNode> { it.score }
        .thenBy { it.node.boundsInWindow.area }
        .thenByDescending { it.node.treeKind == TreeKind.MERGED }
        .thenByDescending { it.node.path.size }
        .thenBy { it.node.uid }
```

### 10.5 Nearby nodes

```kotlin
object NearbyNodeCollector {
    fun collect(
        allNodes: List<PointPatchNode>,
        selected: PointPatchNode?,
        tap: TapPoint,
        options: SelectionOptions
    ): List<PointPatchNode> {
        if (selected == null) {
            return allNodes
                .filter { it.hasMeaningfulSemantic() }
                .sortedBy { it.boundsInWindow.centerDistanceTo(tap) }
                .distinctBy { it.semanticDedupKey() }
                .take(options.maxNearbyNodes)
        }

        val selectedCenter = selected.boundsInWindow.center

        return allNodes
            .asSequence()
            .filter { it.uid != selected.uid }
            .filter { it.rootIndex == selected.rootIndex }
            .filter { it.hasMeaningfulSemantic() }
            .filter { it.boundsInWindow.centerDistanceTo(selectedCenter) <= options.nearbyRadiusPx }
            .sortedBy { it.boundsInWindow.centerDistanceTo(selectedCenter) }
            .distinctBy { it.semanticDedupKey() }
            .take(options.maxNearbyNodes)
            .toList()
    }
}
```

---

## 11. Screenshot capture 설계

### 11.1 Result model

```kotlin
sealed interface ScreenshotResult {
    data class Success(
        val fullPath: String?,
        val cropPath: String?,
        val width: Int,
        val height: Int
    ) : ScreenshotResult

    data class Failure(
        val reason: String
    ) : ScreenshotResult
}
```

### 11.2 Capture flow

```text
1. decorView size 확인
2. API 26+이면 PixelCopy request(Window, Bitmap, listener, Handler)
3. PixelCopy 성공 시 full bitmap 저장
4. 실패하거나 timeout이면 decorView.draw(Canvas) fallback
5. selected bounds가 있으면 crop 생성
6. 저장 경로 반환
7. 모든 실패는 Failure로 기록하되 annotation 생성을 막지 않음
```

### 11.3 Pseudocode

```kotlin
class ScreenshotCapturer(
    private val store: ScreenshotStore
) {
    suspend fun capture(
        activity: Activity,
        selectedBounds: PointPatchRect?
    ): ScreenshotResult {
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            return ScreenshotResult.Failure("DecorView has no size")
        }

        val fullBitmap = tryPixelCopy(activity)
            ?: tryDrawView(decor)
            ?: return ScreenshotResult.Failure("PixelCopy and Canvas fallback failed")

        val annotationId = IdGenerator.nextAnnotationId()

        val full = store.save(fullBitmap, "$annotationId-full.png")

        val crop = selectedBounds?.let { bounds ->
            val cropRect = bounds.toAndroidRect()
                .coerceInside(fullBitmap.width, fullBitmap.height)

            if (cropRect.width() > 0 && cropRect.height() > 0) {
                val cropBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )
                store.save(cropBitmap, "$annotationId-crop.png")
            } else null
        }

        return ScreenshotResult.Success(
            fullPath = full.absolutePath,
            cropPath = crop?.absolutePath,
            width = fullBitmap.width,
            height = fullBitmap.height
        )
    }
}
```

### 11.4 Store

```kotlin
class ScreenshotStore(private val context: Context) {
    fun save(bitmap: Bitmap, fileName: String): File {
        val date = LocalDate.now().toString()
        val dir = File(context.cacheDir, "pointpatch/$date")
        dir.mkdirs()

        val file = File(dir, fileName)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
```

---

## 12. Annotation model 설계

### 12.1 Core models

```kotlin
@Serializable
data class PointPatchAnnotation(
    val schemaVersion: String = "1.0",
    val id: String,
    val createdAtEpochMillis: Long,
    val platform: String = "android-compose",
    val app: AppInfo,
    val activity: ActivityInfo,
    val tap: TapPoint,
    val selection: SelectionInfo,
    val selectedNode: PointPatchNode? = null,
    val candidatesAtPoint: List<ScoredPointPatchNode> = emptyList(),
    val scopeCandidates: List<ScopeCandidate> = emptyList(),
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val searchHints: List<String> = emptyList(),
    val screenshot: ScreenshotInfo? = null,
    val userComment: String,
    val errors: List<PointPatchError> = emptyList()
)
```

`SelectionInfo.kind` is one of `SEMANTICS_NODE`, `VISUAL_AREA`, or `TAP_POINT`. `SelectionInfo.confidence` is one of `HIGH`, `MEDIUM`, `LOW`, or `NONE`. `SelectionInfo.source` is one of `TAP_SELECT`, `SCOPE_CHIP`, `AREA_SELECT`, or `FALLBACK`.

### 12.2 Node model

```kotlin
@Serializable
data class PointPatchNode(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: TreeKind,
    val boundsInWindow: PointPatchRect,
    val text: List<String>,
    val editableText: String?,
    val contentDescription: List<String>,
    val role: String?,
    val testTag: String?,
    val stateDescription: String?,
    val selected: Boolean?,
    val enabled: Boolean,
    val actions: List<String>,
    val isPassword: Boolean,
    val isSensitive: Boolean,
    val path: List<String>,
    val rawProperties: Map<String, String> = emptyMap()
)
```

### 12.3 Search hints

Search hints 생성 규칙:

```text
1. selected node text
2. selected node contentDescription
3. selected node testTag
4. nearby nodes text
5. nearby nodes contentDescription
6. role
7. activity class short name
8. inferred screen name
9. stateDescription
10. source candidate matched terms
```

중복 제거 및 길이 제한:

```kotlin
fun buildSearchHints(annotationDraft: AnnotationDraft): List<String> {
    return sequenceOf(...)
        .flatten()
        .map { it.trim() }
        .filter { it.length in 2..120 }
        .distinct()
        .take(30)
        .toList()
}
```

---

## 13. Formatter 설계

### 13.1 JSON formatter

`kotlinx.serialization` 사용.

```kotlin
object PointPatchJsonFormatter {
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun format(annotation: PointPatchAnnotation): String =
        json.encodeToString(annotation)
}
```

### 13.2 Markdown formatter

```kotlin
class PointPatchMarkdownFormatter {
    fun format(annotation: PointPatchAnnotation): String = buildString {
        appendLine("# PointPatch Compose Feedback")
        appendLine()
        appendLine("## User request")
        appendLine()
        appendLine(annotation.userComment.ifBlank { "(No comment)" })
        appendLine()

        appendLine("## Selected UI")
        appendSelectedNode(annotation.selectedNode)

        appendLine("## Nearby context")
        appendNearby(annotation.nearbyNodes)

        appendLine("## Source candidates")
        appendSourceCandidates(annotation.sourceCandidates)

        appendLine("## Search hints")
        appendSearchHints(annotation.searchHints)

        appendLine("## Screenshot")
        appendScreenshot(annotation.screenshot)

        if (annotation.errors.isNotEmpty()) {
            appendLine("## Capture notes")
            annotation.errors.forEach { appendLine("- $it") }
        }
    }
}
```

### 13.3 Client-specific templates

```text
Copy for Codex
Copy for Claude Code
Copy for Cursor
Copy generic Markdown
Copy raw JSON
```

초기에는 같은 Markdown을 사용하되 header만 다르게 할 수 있다.

---

## 14. Source indexer 설계

### 14.1 Source index schema

```kotlin
@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.0",
    val generatedAtEpochMillis: Long,
    val projectRoot: String,
    val variant: String,
    val entries: List<SourceIndexEntry>
)

@Serializable
data class SourceIndexEntry(
    val file: String,
    val line: Int?,
    val functionName: String?,
    val kind: SourceEntryKind,
    val tokens: List<String>,
    val stringLiterals: List<String>,
    val resourceRefs: List<String>,
    val testTags: List<String>,
    val excerpt: String?
)
```

### 14.2 Source scan 대상

```text
src/**/java/**/*.kt
src/**/kotlin/**/*.kt
src/**/res/values/**/*.xml
```

### 14.3 제외

```text
build/**
.gradle/**
.idea/**
*.class
```

### 14.4 Kotlin scanner

초기 버전은 가벼운 regex scanner로 시작한다.

추출:

- string literals
- `Text("...")`
- `Button(...)`, `IconButton(...)`, `TextButton(...)`
- `stringResource(R.string.xxx)`
- `Modifier.testTag("...")`
- `@Composable fun FunctionName`
- surrounding excerpt

추후 KSP/PSI 기반 scanner로 개선할 수 있다.

### 14.5 XML string scanner

추출:

- `<string name="pay_now">결제하기</string>`
- `<plurals>`
- `<string-array>`

### 14.6 Source matcher

```kotlin
class SourceMatcher(
    private val index: SourceIndex?
) {
    fun match(
        selectedNode: PointPatchNode?,
        nearbyNodes: List<PointPatchNode>,
        appContext: AppContext
    ): List<SourceCandidate> {
        if (index == null) return emptyList()

        val terms = buildTerms(selectedNode, nearbyNodes, appContext)

        return index.entries
            .map { scoreEntry(it, terms) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(5)
    }
}
```

점수:

```text
+1000 selected text exact match
+800 contentDescription exact match
+700 testTag exact match
+500 nearby text exact match
+300 resource ref match
+250 activity/screen name in file path
+200 role/composable type match
+100 partial text match
```

---

## 15. MCP bridge 설계

### 15.1 Sidekick bridge server

Android sidekick은 debug build에서 local bridge를 연다.

권장 방식:

```text
android.net.LocalServerSocket
localabstract:pointpatch_<packageName>
```

보안:

- debug build only
- random session token 생성
- desktop CLI는 `adb shell run-as <package> cat files/pointpatch/session.json`으로 token 조회
- 모든 bridge request에 token 포함
- token mismatch면 reject

### 15.2 Session token

파일:

```text
context.filesDir/pointpatch/session.json
```

예시:

```json
{
  "schemaVersion": "1.0",
  "packageName": "com.example.app",
  "token": "random-256-bit-base64",
  "createdAtEpochMillis": 1777786212000,
  "socketName": "pointpatch_com.example.app"
}
```

### 15.3 Bridge protocol

Length-prefixed JSON을 권장한다.

Frame:

```text
4-byte big-endian length
UTF-8 JSON payload
```

Request:

```json
{
  "id": "req_1",
  "token": "random",
  "method": "inspectCurrentScreen",
  "params": {}
}
```

Response:

```json
{
  "id": "req_1",
  "ok": true,
  "result": {}
}
```

Error:

```json
{
  "id": "req_1",
  "ok": false,
  "error": {
    "code": "ROOT_DISCOVERY_FAILED",
    "message": "Failed to discover Compose roots"
  }
}
```

### 15.4 Bridge methods

```text
status
inspectCurrentScreen
startFeedbackCapture
verifyUiChange
getLastAnnotation
readScreenshot
```

### 15.5 Desktop bridge client

`pointpatch-cli`는 다음 순서로 sidekick에 연결한다.

```text
1. project metadata에서 applicationId 읽기
2. adb devices 확인
3. debug app 실행 여부 확인
4. adb shell run-as <package> cat files/pointpatch/session.json
5. socketName/token 획득
6. adb forward tcp:<localPort> localabstract:<socketName>
7. local tcp로 bridge 연결
8. status handshake
```

`adb forward`를 쓰면 desktop MCP server는 local tcp로 연결하고 Android sidekick은 localabstract socket으로 받는다.

---

## 16. MCP server 설계

### 16.1 Transport

초기 버전은 stdio transport만 지원한다.

```text
stdin: JSON-RPC request
stdout: JSON-RPC response
stderr: logs only
```

주의:

- stdout에는 protocol message만 출력한다.
- logs는 stderr로만 출력한다.
- CLI progress log와 MCP mode log를 분리한다.

### 16.2 MCP server capabilities

초기 capabilities:

```json
{
  "tools": {},
  "resources": {}
}
```

### 16.3 Tools

#### `pointpatch_status`

입력:

```json
{}
```

출력:

```json
{
  "deviceConnected": true,
  "packageName": "com.example.app",
  "appRunning": true,
  "sidekickConnected": true,
  "currentActivity": "com.example.MainActivity",
  "composeRoots": 1,
  "sourceIndexAvailable": true
}
```

#### `pointpatch_get_ui_feedback`

입력:

```json
{
  "instruction": "수정할 UI를 앱에서 탭하고 원하는 변경사항을 입력하세요.",
  "timeoutMs": 60000
}
```

동작:

```text
1. bridge.startFeedbackCapture
2. Android overlay selection mode 활성화
3. 사용자 UI tap
4. comment sheet 표시
5. 사용자 comment 입력
6. annotation 생성
7. MCP result 반환
```

출력:

```json
{
  "annotation": {},
  "markdown": "# PointPatch Compose Feedback\n..."
}
```

#### `pointpatch_get_current_screen`

입력:

```json
{
  "includeScreenshot": true,
  "includeSemantics": true,
  "maxNodes": 200
}
```

출력:

```json
{
  "screen": {},
  "screenshotResource": "pointpatch://screenshot/latest/full.png"
}
```

#### `pointpatch_verify_ui_change`

입력:

```json
{
  "expectedText": "바로 결제하기",
  "role": "Button"
}
```

출력:

```json
{
  "found": true,
  "matchingNodes": []
}
```

### 16.4 Resources

```text
pointpatch://session/current
pointpatch://screen/current
pointpatch://annotation/latest
pointpatch://screenshot/latest/full.png
pointpatch://screenshot/latest/crop.png
pointpatch://source-index
```

### 16.5 Prompts

V1 MCP는 prompts capability를 expose하지 않는다. prompt templates는 future extension으로 둔다.

---

## 17. CLI 설계

### 17.1 CLI stack

권장:

- Kotlin/JVM
- `kotlinx.serialization`
- `kotlinx.coroutines`
- `picocli` 또는 `clikt`
- Gradle wrapper detection
- ADB command runner abstraction

### 17.2 Commands

V1 commands:

- `pointpatch status`
- `pointpatch run`
- `pointpatch doctor`
- `pointpatch setup`
- `pointpatch mcp`

V1은 init 명령 또는 Gradle 파일 자동 수정 흐름을 제공하지 않는다. 이 repo의 plugin은 composite build/settings wiring으로 포함되어 있고, 외부 사용자는 published coordinate가 제공되기 전까지 explicit composite build/pluginManagement wiring을 제공해야 한다.

#### `pointpatch setup`

목표:

- MCP client 설정에 붙여 넣을 JSON 출력

동작:

```text
1. `--package` 값 또는 `.pointpatch/project.json`의 package/project metadata 읽기
2. pointpatch mcp command와 args 생성
3. command, args, packageName, projectRoot를 포함한 JSON 출력
```

#### `pointpatch run`

목표:

- debug app 실행과 sidekick 연결 확인

동작:

```text
1. project metadata 읽기
2. device 선택
3. ./gradlew :app:installDebug
4. adb shell monkey -p <package> 1
5. sidekick session.json 대기
6. status 출력
```

#### `pointpatch doctor`

목표:

- 실패 원인 진단

체크:

```text
- Android project found
- PointPatch project metadata found
- ADB found
- device connected
- sidekick session found
```

#### `pointpatch mcp`

목표:

- MCP stdio server 실행

주의:

- interactive log 출력 금지
- stderr로만 diagnostic log 출력
- stdout은 JSON-RPC 전용

---

## 18. Privacy와 security 구현

### 18.1 Release guard

```kotlin
fun Context.isDebuggable(): Boolean =
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
```

모든 entry point에서 확인한다.

- Initializer
- bridge server
- overlay attach
- screenshot capture
- MCP request handler

### 18.2 Redaction

```kotlin
object RedactionPolicy {
    fun apply(
        isPassword: Boolean,
        editableText: String?,
        text: List<String>,
        config: PointPatchConfig
    ): RedactedText {
        if (isPassword) {
            return RedactedText(
                text = listOf("<redacted-password>"),
                editableText = "<redacted-password>",
                redacted = true
            )
        }

        if (editableText != null && config.redactEditableText) {
            return RedactedText(
                text = text,
                editableText = "<redacted-editable-text>",
                redacted = true
            )
        }

        return RedactedText(text, editableText, redacted = false)
    }
}
```

### 18.3 Screenshot warning

Screenshot에는 민감정보가 포함될 수 있다. 자동 redaction은 v1에서 보장하지 않는다.

UI에 다음 문구를 넣는다.

```text
Screenshots are saved locally. They may contain sensitive information.
```

### 18.4 Network

Core sidekick:

- no INTERNET permission
- no remote upload
- no analytics

Optional future exporter:

- separate artifact
- explicit opt-in
- separate README/privacy warning

---

## 19. Error handling

### 19.1 Error model

```kotlin
@Serializable
data class PointPatchError(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)
```

### 19.2 Common errors

```text
NO_ACTIVITY
NO_DECOR_VIEW
NO_NODE_AT_TAP
NO_OVERLAY_CONTROLLER
CAPTURE_IN_FLIGHT
SCOPE_NODE_NOT_FOUND
ROOT_DISCOVERY_FAILED
SEMANTICS_MERGED_INSPECTION_FAILED
SEMANTICS_UNMERGED_INSPECTION_FAILED
BAD_REQUEST
UNAUTHORIZED
UNKNOWN_METHOD
METHOD_FAILED
```

### 19.3 Principle

오류가 있어도 가능한 context를 반환한다.

예:

```json
{
  "tap": { "xInWindow": 512, "yInWindow": 1430 },
  "errors": [
    {
      "code": "NO_NODE_AT_TAP",
      "message": "No semantics node contains the tap coordinate"
    }
  ]
}
```

---

## 20. Testing strategy

### 20.1 Unit tests

`pointpatch-compose-core`

- `NodeSelectorTest`
  - clickable button preferred
  - text node vs button node
  - root container penalty
  - no node fallback
  - merged/unmerged tie-break
- `NearbyNodeCollectorTest`
  - distance sorting
  - dedup
  - max count
- `RedactionPolicyTest`
  - password redacted
  - editable redacted by default
  - normal text preserved
- `MarkdownFormatterTest`
  - full annotation
  - no selected node
  - errors included
- `SourceMatcherTest`
  - selected text match
  - nearby text match
  - testTag match
  - no index

### 20.2 Android instrumentation tests

Sample app scenarios:

- Material3 Button
- Text
- TextField
- Password TextField
- Checkbox
- Switch
- LazyColumn item
- Dialog
- Popup/Dropdown
- Canvas-only
- Canvas with semantics
- multiple Compose roots

Tests:

```text
1. overlay appears in debug
2. overlay not selected as target
3. button selection returns role/text/action
4. textfield is redacted
5. screenshot capture returns result or failure without crash
6. LazyColumn nearby context works
7. Dialog root discoverable
```

### 20.3 CLI tests

Use fake project fixtures.

- read applicationId from `--package` or `.pointpatch/project.json`
- run sample Gradle tasks used by V1 CLI
- doctor missing ADB
- doctor missing sidekick
- setup config output

### 20.4 MCP tests

- initialize request
- tools/list
- tools/call status
- tool result JSON schema
- invalid params
- bridge timeout
- stdout/stderr separation

### 20.5 Manual QA matrix

```text
API levels: 23, 26, 30, 35+
Compose versions: supported matrix
Devices: emulator + physical
Orientation: portrait/landscape
Theme: light/dark
Font scale: 1.0, 1.5, 2.0
RTL: enabled
```

---

## 21. Implementation phases

### Phase 1: Core models and formatters

Deliverables:

- data models
- JSON serialization
- Markdown formatter
- redaction policy
- search hint builder

Acceptance:

```bash
./gradlew :pointpatch-compose-core:test
```

### Phase 2: Sidekick autoinit and overlay attach

Deliverables:

- AndroidX Startup initializer
- debuggable guard
- lifecycle callbacks
- overlay host
- toolbar

Acceptance:

- sample debug app shows PointPatch button
- release sample does not show button

### Phase 3: Semantics inspection

Deliverables:

- ComposeRootFinder
- SemanticsInspector
- SemanticsNodeMapper
- snapshot logging/debug UI

Acceptance:

- sample button/text nodes appear in snapshot

### Phase 4: Selection flow

Deliverables:

- selection layer
- tap capture
- node selection algorithm
- highlight
- comment sheet

Acceptance:

- user can select a button and see selected node summary

### Phase 5: Screenshot and export

Deliverables:

- PixelCopy-first capturer
- Canvas fallback
- crop
- clipboard exporter
- local file exporter

Acceptance:

- Markdown copied to clipboard with screenshot path

### Phase 6: Gradle plugin and source index

Deliverables:

- plugin id
- debug dependency injection
- source index task
- generated assets
- source matcher

Acceptance:

- applying plugin auto-adds sidekick
- source candidates appear in annotation

### Phase 7: CLI

Deliverables:

- status
- setup
- run
- doctor
- mcp

Acceptance:

- sample project can be installed/launched through CLI
- doctor produces actionable output

### Phase 8: MCP

Deliverables:

- sidekick bridge server
- desktop bridge client
- stdio MCP server
- macro tools

Acceptance:

- AI/MCP client can call `pointpatch_get_ui_feedback`
- app overlay opens
- user selection returns annotation result

### Phase 9: Docs and release readiness

Deliverables:

- README
- privacy doc
- troubleshooting
- MCP guide
- sample app
- CI
- publish to Maven local

Acceptance:

```bash
./gradlew clean build
./gradlew publishToMavenLocal
```

---

## 22. Sample app 설계

Screens:

```text
CheckoutScreen
  - amount text
  - coupon row
  - pay button
  - bottom payment bar

FeedScreen
  - LazyColumn
  - repeated cards
  - icon button
  - image placeholder

FormScreen
  - TextField
  - Password field
  - Checkbox
  - Switch

DialogScreen
  - AlertDialog
  - DropdownMenu
  - Popup

CanvasScreen
  - Canvas-only component
  - Canvas with semantics
```

목적:

- semantics 있는 UI와 없는 UI의 차이를 문서화
- redaction 검증
- LazyColumn/Popup/Dialog edge case 검증

---

## 23. Public API

### 23.1 Primary user API

사용자는 일반적으로 public API를 호출하지 않는다.

Primary install after artifacts are published:

```kotlin
plugins {
    id("io.github.pointpatch.compose") version "0.1.0"
}
```

Current repo-local V1 validation uses composite build/settings wiring instead of a published plugin coordinate.

### 23.2 Manual fallback API

```kotlin
object PointPatch {
    fun install(application: Application)
    fun configure(block: PointPatchConfigBuilder.() -> Unit)
}
```

### 23.3 Root fallback API

자동 attach가 불가능한 특수 환경용.

```kotlin
@Composable
fun PointPatchRoot(
    enabled: Boolean = true,
    config: PointPatchConfig = PointPatchConfig(),
    content: @Composable () -> Unit
)
```

README의 primary path로 홍보하지 않는다. Troubleshooting에만 안내한다.

---

## 24. Versioning and compatibility

### 24.1 Schema version

Annotation schema:

```text
1.0
```

Source index schema:

```text
1.0
```

Bridge protocol schema:

```text
1.0
```

### 24.2 Version compatibility

- sidekick and CLI/MCP versions should match major/minor version.
- bridge handshake includes protocol version.
- mismatch handling:

```json
{
  "code": "PROTOCOL_VERSION_MISMATCH",
  "message": "Sidekick protocol 1.0 is incompatible with CLI protocol 2.0"
}
```

---

## 25. Reference implementation notes

### 25.1 Compose API compatibility

`RootForTest`, `SemanticsOwner`, `SemanticsNode`, `SemanticsProperties`, `SemanticsActions` API는 Compose version에 따라 compile signature가 달라질 수 있다. 구현 시 supported Compose versions를 명확히 정하고 CI matrix를 둔다.

### 25.2 LayoutNode/sourceInfo

DTA류 구현에서 참고할 수 있는 `CompositionData`, sourceInfo, LayoutNode reflection은 optional provider로만 둔다.

원칙:

```text
- 실패해도 annotation은 동작
- source candidates는 best-effort
- core selection은 semantics 기반
```

### 25.3 MCP security

MCP server가 arbitrary shell command를 실행하지 않도록 한다.

- tool args validation
- no arbitrary command tool
- adb command allowlist
- no user-provided command interpolation
- stdout protocol only
- logs to stderr

---

## 26. Codex implementation prompt

다음 프롬프트를 Codex에 줄 수 있다.

```text
Implement PointPatch for Android Compose according to docs/technical-design.md.

Build modules:
- pointpatch-compose-core
- pointpatch-compose-overlay
- pointpatch-compose-sidekick
- pointpatch-gradle-plugin
- pointpatch-cli
- pointpatch-mcp
- sample

Core requirements:
- Android Jetpack Compose only
- debug-only
- no AccessibilityService
- no required testTags
- AndroidX Startup autoinit
- ActivityLifecycleCallbacks overlay attach
- RootForTest discovery
- SemanticsOwner merged/unmerged tree inspection
- coordinate-based node selection
- screenshot capture with PixelCopy-first and Canvas fallback
- Markdown/JSON annotation export
- Gradle source index generation
- optional MCP with macro tools

Do not implement a Kotlin compiler plugin.
Do not add network permission to the core sidekick.
Do not make MCP the only UX.
Prioritize failure-safe behavior and local-first privacy.
```

---

## 27. Final implementation checklist

```text
[ ] Gradle plugin can be applied with id("io.github.pointpatch.compose")
[ ] debug runtime dependency is added only to debug variants
[ ] sidekick autoinits via AndroidX Startup
[ ] sidekick exits when app is not debuggable
[ ] overlay appears in debug sample app
[ ] overlay does not intercept normal app touches while idle
[ ] selection layer captures tap only in selection mode
[ ] Compose roots are found via RootForTest
[ ] overlay root is excluded from inspection
[ ] merged and unmerged semantics nodes are collected
[ ] node selection returns selected/candidates/nearby
[ ] screenshot full/crop is captured or failure is recorded
[ ] password/editable text redaction works
[ ] Markdown export includes all key sections
[ ] JSON export matches schema
[ ] source index generated and packaged
[ ] source candidates appear when matches exist
[ ] CLI doctor gives actionable diagnostics
[ ] MCP server exposes macro tools
[ ] MCP get_ui_feedback opens app overlay and returns annotation
[ ] release build has no active PointPatch runtime
[ ] docs explain limitations clearly
```

---

## 28. External references

Implementation should be checked against current official documentation while coding.

- AndroidX App Startup: https://developer.android.com/topic/libraries/app-startup
- AndroidX Startup `Initializer`: https://developer.android.com/reference/androidx/startup/Initializer
- `Application.ActivityLifecycleCallbacks`: https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks
- Compose `RootForTest`: https://developer.android.com/reference/kotlin/androidx/compose/ui/node/RootForTest
- Compose `SemanticsOwner`: https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/SemanticsOwner
- Compose semantics guide: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Compose testing semantics guide: https://developer.android.com/develop/ui/compose/testing/semantics
- `PixelCopy`: https://developer.android.com/reference/android/view/PixelCopy
- MCP specification overview: https://modelcontextprotocol.io/specification/2025-06-18/basic
- MCP transports: https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
