# FixThis for Android Compose — 상세 기술 설계서

**문서 목적:** 이 문서는 `FixThis for Android Compose`를 실제로 구현하기 위한 상세 기술 설계서다. PRD가 “무엇을 만들 것인가”를 설명한다면, 이 문서는 “어떤 방식으로 구현할 것인가”를 설명한다.
**대상 독자:** Codex, Android 개발자, Gradle plugin 개발자, MCP/CLI 구현자  
**제품명:** FixThis for Android Compose
**짧은 이름:** FixThis Compose
**CLI:** `fixthis`
**문서 버전:** v1.0  
**작성일:** 2026-05-03

> Current implementation note: this document started as the detailed V1 design.
> The current mainline product path is MCP feedback console first. The Android
> sidekick provides debug runtime evidence, screenshots, navigation, and a
> heartbeat-driven MCP status host; it no longer owns an in-app annotation
> overlay. Lower historical sections that mention selection overlays or
> clipboard-first annotation export are retained as design background unless a
> nearby current-status note says otherwise.

---

## 0. 구현 방향 요약

FixThis는 다음 구조로 구현한다.

```text
Android debug app
  └─ fixthis-compose-sidekick
       ├─ AndroidX Startup autoinit
       ├─ ActivityLifecycleCallbacks
       ├─ MCP connection status host
       ├─ Compose RootForTest discovery
       ├─ SemanticsOwner tree read
       ├─ Screenshot capture
       └─ Local bridge for MCP

Desktop
  └─ fixthis CLI
       ├─ status
       ├─ setup
       ├─ run
       ├─ doctor
       ├─ console
       └─ mcp stdio server

Gradle
  └─ fixthis-gradle-plugin
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
6. MCP feedback console as the primary agent workflow
7. app UI limited to MCP browser connection status
8. local-first export and handoff
9. failure-safe fallback
```

---

## 1. 전체 시스템 아키텍처

### 1.1 Runtime architecture

```text
User opens MCP feedback console
        ↓
Console diagnoses ADB device and sidekick bridge state
        ↓
Connection card guides Start / Open app / Reconnect / Try again
        ↓
Console reaches Ready and captures preview
        ↓
User clicks Annotate to freeze the latest preview
        ↓
User selects a Compose target or visual area in the browser
        ↓
MCP service asks sidekick to read current Activity decorView
        ↓
Find Compose roots implementing RootForTest
        ↓
Read SemanticsOwner from each root
        ↓
Collect merged + unmerged SemanticsNode lists
        ↓
Map SemanticsNode → FixThisNode
        ↓
Match browser selection to captured semantics node or visual area
        ↓
Collect candidatesAtPoint and nearbyNodes
        ↓
Capture screenshot full + crop
        ↓
Load source index if available
        ↓
Match sourceCandidates
        ↓
Derive optional targetEvidence from merged nodes, strict comp tags,
occurrence, source candidates, and screenshot availability
        ↓
Copy Prompt or Send Agent persists feedback items when needed and exposes complete JSON plus detailMode Markdown
```

### 1.2 MCP architecture

MCP server는 Android 앱 안에서 직접 실행하지 않는다. Desktop process로 실행한다.

```text
Codex / Claude Code / Cursor / VS Code
        │
        │ stdio MCP
        ▼
fixthis mcp
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
FixThisAnnotation
        ↓
MarkdownFormatter / JsonFormatter / MpcToolResultFormatter
```

MCP feedback console flow:

```text
Selected ADB device
    + Console connection status
    + Live preview navigation
    + Frozen preview after Annotate
        ↓
Pending component/custom-area comments in browser state
        ↓
Copy Prompt or Send Agent promotes one frozen preview into one evidence snapshot
when needed and stores all pending items with the same screenId
        ↓
FeedbackSession in MCP process
        ↓
.fixthis/feedback-sessions/<session-id> persistence
        ↓
FeedbackHandoffBatch after Send Agent
        ↓
fixthis_read_feedback complete JSON + compact Markdown for agent work
```

---

## 2. Repository 구조

권장 repository 이름:

```text
fixthis-compose
```

권장 구조:

```text
fixthis-compose/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  README.md
  LICENSE
  docs/
    fixthis_prd.md
    fixthis_decisions.md
    fixthis_technical_design.md
    output-schema.md
    privacy.md
    troubleshooting.md
    mcp.md

  fixthis-compose-core/
    build.gradle.kts
    src/main/kotlin/io/beyondwin/fixthis/compose/core/...

  fixthis-compose-sidekick/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/...

  fixthis-gradle-plugin/
    build.gradle.kts
    src/main/kotlin/io/beyondwin/fixthis/gradle/...

  fixthis-cli/
    build.gradle.kts
    src/main/kotlin/io/beyondwin/fixthis/cli/...

  fixthis-mcp/
    build.gradle.kts
    src/main/kotlin/io/beyondwin/fixthis/mcp/...

  sample/
    build.gradle.kts
    src/main/java/io/beyondwin/fixthis/sample/...
```

---

## 3. Module 설계

### 3.1 `fixthis-compose-core`

역할:

- 공통 데이터 모델
- pure domain models and repository contracts
- annotation/snapshot/session use cases
- node selection algorithm
- nearby node collection
- legacy annotation export model
- Markdown/JSON formatter
- source candidate matching
- stable target evidence models, identity hints, occurrence calculation, and source interpretation
- redaction policy

Pure Kotlin module로 유지한다. MCP, CLI, Android UI, `.fixthis` persistence layout을 알지 않는다.

주요 package:

```text
io.beyondwin.fixthis.compose.core.domain.annotation
io.beyondwin.fixthis.compose.core.domain.snapshot
io.beyondwin.fixthis.compose.core.domain.session
io.beyondwin.fixthis.compose.core.usecase.annotation
io.beyondwin.fixthis.compose.core.usecase.snapshot
io.beyondwin.fixthis.compose.core.model
io.beyondwin.fixthis.compose.core.identity
io.beyondwin.fixthis.compose.core.selection
io.beyondwin.fixthis.compose.core.format
io.beyondwin.fixthis.compose.core.source
io.beyondwin.fixthis.compose.core.redaction
```

MCP/session DTOs translate into these domain models through explicit mapper code in `fixthis-mcp`; persisted JSON field names are not owned by this module.

### 3.1.1 Current repository layout

현재 repository에서는 Android Studio 관례에 맞춰 sample app을 Gradle project `:app`으로 노출한다. 실제 source directory는 계속 `sample/`이다.

```text
include(":app")
project(":app").projectDir = file("sample")
```

따라서 local build/install 문서와 CLI 기본 install task는 `:app:installDebug`를 사용한다. `:sample`은 더 이상 현재 Gradle project path가 아니다. 현재 repository sample application id는 `io.beyondwin.fixthis.sample`이고 launcher label은 `FixThis`다.

`gradle/gradle-daemon-jvm.properties`는 Gradle daemon JVM toolchain을 Java 21로 고정하는 repository 파일이다. 반대로 `local.properties`, `.fixthis/artifacts/`, `.fixthis/feedback-sessions/`는 developer-local 파일이므로 git에서 무시한다.

### 3.2 `fixthis-compose-sidekick`

역할:

- AndroidX Startup
- Application lifecycle hook
- MCP browser connection status indicator
- Compose root discovery
- semantics inspection
- screenshot capture
- bridge server for MCP

주요 package:

```text
io.beyondwin.fixthis.compose.sidekick.init
io.beyondwin.fixthis.compose.sidekick.lifecycle
io.beyondwin.fixthis.compose.sidekick.overlay
io.beyondwin.fixthis.compose.sidekick.inspect
io.beyondwin.fixthis.compose.sidekick.screenshot
io.beyondwin.fixthis.compose.sidekick.bridge
```

`fixthis-compose-sidekick/src/androidTest/AndroidManifest.xml` removes the AndroidX Startup metadata for `FixThisInitializer` in sidekick instrumentation tests. That keeps tests focused on the inspected UI/runtime component under test instead of auto-starting the full bridge/status host from the test APK.

### 3.4 `fixthis-gradle-plugin`

역할:

- Gradle plugin id: `io.beyondwin.fixthis.compose`
- debug runtime dependency injection
- source index generation
- generated assets registration
- `.fixthis/project.json` metadata generation

주요 package:

```text
io.beyondwin.fixthis.gradle
io.beyondwin.fixthis.gradle.task
io.beyondwin.fixthis.gradle.source
```

### 3.5 `fixthis-cli`

역할:

- `fixthis status`
- `fixthis setup`
- `fixthis run`
- `fixthis doctor`
- `fixthis mcp`

Kotlin/JVM CLI로 구현한다. `fixthis mcp`와 `fixthis console`은 sibling distribution 또는 `PATH`에서 `fixthis-mcp` executable을 찾아 실행한다.

### 3.6 `fixthis-mcp`

역할:

- stdio MCP JSON-RPC server
- tools/list, tools/call
- resources/list, resources/read
- Android sidekick bridge client
- local feedback console server
- browser connection status and app-launch recovery contract
- feedback session store and `.fixthis/feedback-sessions/` persistence
- target evidence derivation when frozen previews are saved
- draft/sent handoff queue formatting for agents
- session DTO/domain mappers that preserve existing JSON field names while mapping to `compose-core` domain models
- transient preview cache, source-index registry, and screenshot artifact promotion helpers
- browser console assets loaded from `src/main/resources/console`

초기 버전은 MCP SDK에 강하게 의존하지 않고 JSON-RPC를 직접 구현할 수 있다. 단, protocol compatibility test를 둔다. MCP prompts endpoints와 prompts capability는 V1 surface가 아니며 future extension으로 둔다. Existing MCP JSON field names are compatibility contracts; domain naming changes must stay behind mapper boundaries.

---

## 4. Gradle 설정 설계

### 4.1 사용자 설치 방식

Published artifact example:

```kotlin
plugins {
    id("io.beyondwin.fixthis.compose") version "0.1.0"
}
```

Fallback after artifacts are published:

```kotlin
dependencies {
    debugImplementation("io.beyondwin.fixthis:fixthis-compose-sidekick:0.1.0")
}
```

이 fallback coordinate는 artifact가 publish된 이후의 외부 설치 예시다. 현재 repo sample은 composite build/project dependency wiring을 사용한다.

### 4.2 Gradle plugin extension

```kotlin
abstract class FixThisExtension @Inject constructor(
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
class FixThisGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "fixthis",
            FixThisExtension::class.java
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
            "io.beyondwin.fixthis:fixthis-compose-sidekick:${extension.runtimeVersion.get()}"
        )
    }

    if (extension.generateSourceIndex.get()) {
        val task = tasks.register(
            "generate${variant.name.capitalized()}FixThisSourceIndex",
            GenerateFixThisSourceIndexTask::class.java
        ) {
            // source dirs, res dirs, output file 설정
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
            task,
            GenerateFixThisSourceIndexTask::outputDir
        )
    }
}
```

실제 AGP API 이름은 사용 버전에 맞게 조정한다.

### 4.5 Generated asset

Debug APK에 포함할 파일:

```text
assets/fixthis/fixthis-source-index.json
assets/fixthis/fixthis-build-info.json
```

### 4.6 Project metadata

CLI와 MCP server가 읽을 metadata:

```text
.fixthis/project.json
```

예시:

```json
{
  "schemaVersion": "1.0",
  "projectRoot": "/Users/me/project",
  "appModule": "app",
  "applicationId": "com.example.app",
  "debugVariant": "debug",
  "sourceIndexAsset": "fixthis/fixthis-source-index.json",
  "generatedAtEpochMillis": 1777786212000
}
```

---

## 5. Android sidekick 초기화 설계

### 5.1 Manifest

`fixthis-compose-sidekick/src/main/AndroidManifest.xml`

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
                android:name="io.beyondwin.fixthis.compose.sidekick.init.FixThisInitializer"
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
class FixThisInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as? Application ?: return

        val isDebuggable =
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        if (!isDebuggable) return

        FixThis.install(app)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

### 5.3 Global install object

```kotlin
object FixThis {
    private val installed = AtomicBoolean(false)

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return

        val config = FixThisConfig.load(application)
        val runtime = FixThisRuntime(application, config)

        application.registerActivityLifecycleCallbacks(
            FixThisActivityLifecycleCallbacks(runtime)
        )

        runtime.startBridgeIfEnabled()
    }
}
```

### 5.4 Runtime config

```kotlin
data class FixThisConfig(
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
2. assets/fixthis/fixthis-build-info.json
3. optional manifest metadata
4. runtime overrides, if any
```

---

## 6. Activity lifecycle와 status host attach

### 6.1 Lifecycle callbacks

```kotlin
class FixThisActivityLifecycleCallbacks(
    private val runtime: FixThisRuntime
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
class FixThisRuntime(
    private val application: Application,
    private val config: FixThisConfig
) {
    private val currentActivityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val sessions = ConcurrentHashMap<Int, FixThisActivitySession>()

    fun onActivityResumed(activity: Activity) {
        currentActivityRef.set(WeakReference(activity))
        val session = sessions.getOrPut(System.identityHashCode(activity)) {
            FixThisActivitySession(activity, config)
        }
        session.attachOverlayIfNeeded()
    }

    fun onActivityPaused(activity: Activity) = Unit

    fun onActivityDestroyed(activity: Activity) {
        sessions.remove(System.identityHashCode(activity))?.detach()
    }

    fun currentActivity(): Activity? =
        currentActivityRef.get()?.get()
}
```

### 6.3 MCP status host

```kotlin
class FixThisConnectionStatusHostLayout(
    context: Context,
    connectionState: BridgeConnectionState
) : FrameLayout(context) {
    // Shows either "MCP connected" or "MCP waiting".
}
```

The status host is non-interactive. It exists only to show whether the app has
recently received an authorized MCP browser heartbeat.

### 6.4 Attach logic

```kotlin
fun onActivityResumed(activity: Activity) {
    FixThisConnectionStatusHostLayout.attachTo(activity)
    FixThisBridgeRuntime.onActivityResumed(activity)
}
```

---

## 7. App UI Surface

The app no longer owns feedback selection, comments, copy/share, or submit
actions. Those actions live in the MCP browser console. The debug app only shows
connection state:

```text
MCP waiting
MCP connected
```

## 8. Compose root discovery

### 8.1 목적

현재 Activity 안의 Compose root를 찾아 `RootForTest.semanticsOwner`에 접근한다.

### 8.2 Root handle model

```kotlin
data class ComposeRootHandle(
    val rootIndex: Int,
    val view: View,
    val rootForTest: RootForTest,
    val boundsInWindow: FixThisRect,
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
            val isFixThisStatusHost =
                view.isFixThisOverlayHost()

            if (skip || isFixThisStatusHost) return

            if (view is RootForTest) {
                result += ComposeRootHandle(
                    rootIndex = result.size,
                    view = view,
                    rootForTest = view,
                    boundsInWindow = view.boundsInWindow().toFixThisRect(),
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
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode>,
    val unmergedNodes: List<FixThisNode>
)
```

### 9.3 SemanticsInspector

```kotlin
class SemanticsInspector(
    private val config: FixThisConfig
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
        config: FixThisConfig
    ): FixThisNode {
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

        return FixThisNode(
            uid = "$rootIndex:$treeKind:${node.id}",
            composeNodeId = node.id,
            rootIndex = rootIndex,
            treeKind = treeKind,
            boundsInWindow = node.boundsInWindow.toFixThisRect(),
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
    val selectedNode: FixThisNode?,
    val candidatesAtPoint: List<ScoredFixThisNode>,
    val nearbyNodes: List<FixThisNode>,
    val tap: TapPoint,
    val reason: String
)

data class ScoredFixThisNode(
    val node: FixThisNode,
    val score: Double,
    val breakdown: Map<String, Double>
)
```

### 10.2 Candidate filter

```text
1. boundsInWindow contains tap
2. bounds area > 0
3. not FixThis overlay
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
        node: FixThisNode,
        snapshot: ScreenSnapshot
    ): ScoredFixThisNode {
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

        return ScoredFixThisNode(
            node = node,
            score = b.values.sum(),
            breakdown = b
        )
    }
}
```

### 10.4 Comparator

```kotlin
fun candidateComparator(): Comparator<ScoredFixThisNode> =
    compareByDescending<ScoredFixThisNode> { it.score }
        .thenBy { it.node.boundsInWindow.area }
        .thenByDescending { it.node.treeKind == TreeKind.MERGED }
        .thenByDescending { it.node.path.size }
        .thenBy { it.node.uid }
```

### 10.5 Nearby nodes

```kotlin
object NearbyNodeCollector {
    fun collect(
        allNodes: List<FixThisNode>,
        selected: FixThisNode?,
        tap: TapPoint,
        options: SelectionOptions
    ): List<FixThisNode> {
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
        selectedBounds: FixThisRect?
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
        val dir = File(context.cacheDir, "fixthis/$date")
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
data class FixThisAnnotation(
    val schemaVersion: String = "1.0",
    val id: String,
    val createdAtEpochMillis: Long,
    val platform: String = "android-compose",
    val app: AppInfo,
    val activity: ActivityInfo,
    val tap: TapPoint,
    val selection: SelectionInfo,
    val selectedNode: FixThisNode? = null,
    val candidatesAtPoint: List<ScoredFixThisNode> = emptyList(),
    val scopeCandidates: List<ScopeCandidate> = emptyList(),
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val searchHints: List<String> = emptyList(),
    val screenshot: ScreenshotInfo? = null,
    val userComment: String,
    val errors: List<FixThisError> = emptyList()
)
```

`SelectionInfo.kind` is one of `SEMANTICS_NODE`, `VISUAL_AREA`, or `TAP_POINT`. `SelectionInfo.confidence` is one of `HIGH`, `MEDIUM`, `LOW`, or `NONE`. `SelectionInfo.source` is one of `TAP_SELECT`, `SCOPE_CHIP`, `AREA_SELECT`, or `FALLBACK`.

### 12.2 Node model

```kotlin
@Serializable
data class FixThisNode(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: TreeKind,
    val boundsInWindow: FixThisRect,
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
object FixThisJsonFormatter {
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun format(annotation: FixThisAnnotation): String =
        json.encodeToString(annotation)
}
```

### 13.2 Markdown formatter

```kotlin
class FixThisMarkdownFormatter {
    fun format(annotation: FixThisAnnotation): String = buildString {
        appendLine("# FixThis Compose Feedback")
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
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
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
localabstract:fixthis_<packageName>
```

보안:

- debug build only
- random session token 생성
- desktop CLI는 `adb shell run-as <package> cat files/fixthis/session.json`으로 token 조회
- 모든 bridge request에 token 포함
- token mismatch면 reject

### 15.2 Session token

파일:

```text
context.filesDir/fixthis/session.json
```

예시:

```json
{
  "schemaVersion": "1.0",
  "packageName": "com.example.app",
  "token": "random-256-bit-base64",
  "createdAtEpochMillis": 1777786212000,
  "socketName": "fixthis_com.example.app"
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
captureScreenSnapshot
readSourceIndex
verifyUiChange
readScreenshot
performNavigation
```

### 15.5 Desktop bridge client

`fixthis-cli`는 다음 순서로 sidekick에 연결한다.

```text
1. project metadata에서 applicationId 읽기
2. adb devices 확인
3. debug app 실행 여부 확인
4. adb shell run-as <package> cat files/fixthis/session.json
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

Implemented tools:

```text
fixthis_status
fixthis_get_current_screen
fixthis_verify_ui_change
fixthis_open_feedback_console
fixthis_list_feedback_sessions
fixthis_capture_screen
fixthis_navigate_app
fixthis_list_feedback
fixthis_read_feedback
fixthis_resolve_feedback
```

#### `fixthis_status`

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

#### `fixthis_capture_screen`

Captures the current Android screen into the active feedback console session.
Selection and comments happen in the MCP browser console, not in the Android app.

출력:

```json
{
  "sessionId": "fb_s_123",
  "screen": {
    "screenId": "screen_1",
    "activityName": "MainActivity",
    "sourceIndexAvailable": true
  }
}
```

#### `fixthis_get_current_screen`

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
  "screenshotResource": "fixthis://screenshot/latest/full.png"
}
```

#### `fixthis_verify_ui_change`

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

#### `fixthis_open_feedback_console`

입력:

```json
{
  "packageName": "com.example.app",
  "sessionId": "optional-session-id",
  "newSession": false
}
```

출력:

```json
{
  "sessionId": "feedback-session-id",
  "packageName": "com.example.app",
  "projectRoot": "/path/to/project",
  "consoleUrl": "http://127.0.0.1:<port>/",
  "resumed": true,
  "session": {}
}
```

The console browser surface is a Studio workspace: Sessions/history on the left,
live or frozen preview canvas in the center, and a mode-aware Inspector on the
right. Top-bar actions stay short and session-level: device selection,
connection state, `Refresh devices`, `Clear selection`, `Copy Prompt`, and
`Send Agent`. Canvas/Inspector actions include `Select`, `Annotate`,
`Add annotation`, `Exit Annotate`, `Clear Selection`, and `Clear Draft`. Live
preview rendering is separated from session and Inspector rendering so polling
does not repaint saved Draft evidence.

Console-local API owns the browser workflow:

```text
GET /api/connection
POST /api/app/launch
GET /api/devices
POST /api/device/select
POST /api/device/disconnect
GET /api/preview
GET /api/preview/{previewId}/screenshot/full
POST /api/navigation
POST /api/items/batch
DELETE /api/items/draft
POST /api/agent-handoffs
GET /api/export/markdown
```

`GET /api/connection` returns the recovery-card contract: state, headline,
message, primary action, selected/available devices, capture/navigation
capability booleans, package name, and diagnostic details. Supported states are
`WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`, `CHOOSE_DEVICE`,
`CHECK_PHONE`, and `UNSUPPORTED_BUILD`.

`POST /api/app/launch` is intentionally narrow. It launches only the selected or
only ready Android device for the active package, and only when the current
connection status is `WELCOME` or `OPEN_APP`. Other states are returned as-is so
the browser does not hide `CHECK_PHONE`, `CHOOSE_DEVICE`, or unsupported-build
problems behind a launch attempt.

Live preview responses are transient console state. They are not appended to
`FeedbackSession.screens`. `POST /api/items/batch` is the persistence path used
when `Copy Prompt` or `Send Agent` needs to persist written pending annotations:
it promotes one frozen preview to a persisted evidence snapshot and stores all
pending feedback items against that snapshot. Pending items are browser-side
draft work until persistence and support Focus/Delete in the Studio Inspector,
not edit or status mutation.

Device selection is MCP process-local state. Console disconnect clears the
FixThis selected serial; it does not run `adb disconnect`.

Connection drops do not clear browser draft work or persisted session data. The
console marks the last preview stale, disables live bridge actions, keeps
pending items visible, and resumes polling when `/api/connection` returns
`READY`.

#### `fixthis_capture_screen`

Captures the current app screen into the active feedback session. The captured
screen can include desktop-readable screenshot artifact paths under
`.fixthis/feedback-sessions/<session-id>/`.

#### `fixthis_navigate_app`

Performs one debug-only navigation action:

```json
{
  "action": "tap",
  "x": 120.0,
  "y": 240.0,
  "captureAfter": true
}
```

Supported actions are `back`, `tap`, and `swipe`. Unsupported arguments are
rejected.

#### `fixthis_list_feedback`

Returns session queue counts and item summaries, including draft item count,
sent batch count, and unresolved sent item count.

#### `fixthis_read_feedback`

Returns agent-readable JSON and Markdown for saved feedback items. JSON
preserves IDs, paths, screens, items, and handoff batches for tool
contracts. Markdown is compact and focuses on request, target evidence, and
likely source instead of internal IDs or screenshot storage paths. When focused
on a sent item, the returned JSON handoff batch is scoped to that item.

#### `fixthis_resolve_feedback`

Updates item status to `resolved`, `needs_clarification`, or `wont_fix` and
stores the agent summary.

### 16.4 Resources

```text
fixthis://session/current
fixthis://screen/current
fixthis://screenshot/latest/full.png
fixthis://screenshot/latest/crop.png
fixthis://source-index
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

- `fixthis status`
- `fixthis run`
- `fixthis doctor`
- `fixthis setup`
- `fixthis mcp`

V1은 init 명령 또는 Gradle 파일 자동 수정 흐름을 제공하지 않는다. 이 repo의 plugin은 composite build/settings wiring으로 포함되어 있고, 외부 사용자는 published coordinate가 제공되기 전까지 explicit composite build/pluginManagement wiring을 제공해야 한다.

#### `fixthis setup`

목표:

- MCP client 설정에 붙여 넣을 JSON 출력

동작:

```text
1. `--package` 값 또는 `.fixthis/project.json`의 package/project metadata 읽기
2. fixthis mcp command와 args 생성
3. command, args, packageName, projectRoot를 포함한 JSON 출력
```

#### `fixthis run`

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

#### `fixthis doctor`

목표:

- 실패 원인 진단

체크:

```text
- Android project found
- FixThis project metadata found
- ADB found
- device connected
- sidekick session found
```

#### `fixthis mcp`

목표:

- MCP stdio server 실행

주의:

- interactive log 출력 금지
- stderr로만 diagnostic log 출력
- stdout은 JSON-RPC 전용

#### `fixthis console`

목표:

- MCP client 없이 feedback console을 실행

동작:

```text
1. package/project metadata 읽기
2. FeedbackSession open 또는 resume
3. local feedback console server 시작
4. browser connection card가 /api/connection으로 device/bridge 상태 진단
5. console startup JSON 또는 localhost URL 출력
```

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
        config: FixThisConfig
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
data class FixThisError(
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

`fixthis-compose-core`

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

FixThis Studio sample app scenarios:

- tabbed product navigation across Home, Queue, Project, Review, and Diagnostics
- Material3 Button and text anchors
- TextField and password-like redaction surfaces
- Checkbox and Switch
- repeated card/list items
- Dialog
- Popup/Dropdown
- Canvas-only preview area
- Canvas with explicit semantics
- disabled controls
- long text and nested target rows
- weak-semantics fallback regions
- multiple Compose roots when dialogs or menus are open

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

Connected Compose tests require the target app to be foregrounded on an unlocked interactive emulator or device; a secure physical-device lockscreen can make Compose report no hierarchies even when `adb devices` reports `device`.

### 20.3 CLI tests

Use fake project fixtures.

- read applicationId from `--package` or `.fixthis/project.json`
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
./gradlew :fixthis-compose-core:test
```

### Phase 2: Sidekick autoinit and overlay attach

Deliverables:

- AndroidX Startup initializer
- debuggable guard
- lifecycle callbacks
- overlay host
- toolbar

Acceptance:

- sample debug app shows FixThis button
- release sample does not show button

### Phase 3: Semantics inspection

Deliverables:

- ComposeRootFinder
- SemanticsInspector
- SemanticsNodeMapper
- snapshot logging/debug UI

Acceptance:

- sample button/text nodes appear in snapshot

### Phase 4: MCP browser console selection flow

Deliverables:

- browser preview
- browser target selection
- pending feedback items
- save to session evidence snapshot

Acceptance:

- user can select a target in the MCP browser console and save feedback

### Phase 5: Screenshot and handoff

Deliverables:

- PixelCopy-first capturer
- Canvas fallback
- session-owned screenshot artifacts
- MCP handoff batch

Acceptance:

- MCP can read compact Markdown and full JSON for saved feedback

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
- feedback console server
- feedback session persistence
- agent-readable handoff queue tools

Acceptance:

- AI/MCP client can call `fixthis_open_feedback_console`
- browser console can show the Studio live preview without appending preview frames to session history
- `Annotate` freezes the latest preview, `Add annotation` creates pending items, `Copy Prompt` or `Send Agent` stores one evidence snapshot plus multiple items when needed, and `Send Agent` creates a persisted handoff batch
- `fixthis_read_feedback` returns complete JSON and compact source-hinted Markdown

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
HomeScreen
  - product health summary
  - priority metrics
  - repeated feedback cards

QueueScreen
  - triage list
  - filter controls
  - repeated card rows

ProjectScreen
  - selected feedback detail
  - AlertDialog
  - DropdownMenu
  - visible close-action anchor

ReviewScreen
  - TextField and password-like redaction surface
  - Checkbox
  - Switch
  - agent handoff composer

DiagnosticsScreen
  - Canvas-only preview area
  - Canvas with explicit semantics
  - weak-semantics block
  - disabled control
  - long text and nested target rows
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
    id("io.beyondwin.fixthis.compose") version "0.1.0"
}
```

Current repo-local V1 validation uses composite build/settings wiring instead of a published plugin coordinate.

### 23.2 Manual fallback API

```kotlin
object FixThis {
    fun install(application: Application)
    fun configure(block: FixThisConfigBuilder.() -> Unit)
}
```

### 23.3 Root fallback API

자동 attach가 불가능한 특수 환경용.

```kotlin
@Composable
fun FixThisRoot(
    enabled: Boolean = true,
    config: FixThisConfig = FixThisConfig(),
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
Implement FixThis for Android Compose according to docs/fixthis_technical_design.md.

Build modules:
- fixthis-compose-core
- fixthis-compose-sidekick
- fixthis-gradle-plugin
- fixthis-cli
- fixthis-mcp
- sample

Core requirements:
- Android Jetpack Compose only
- debug-only
- no AccessibilityService
- no required testTags
- AndroidX Startup autoinit
- ActivityLifecycleCallbacks status-host attach
- RootForTest discovery
- SemanticsOwner merged/unmerged tree inspection
- browser-console target selection mapped to captured semantics or visual area
- screenshot capture with PixelCopy-first and Canvas fallback
- complete JSON plus Markdown detail modes
- Gradle source index generation
- MCP feedback console workflow tools
- nullable Stable Target Evidence v1

Do not implement a Kotlin compiler plugin.
Do not add network permission to the core sidekick.
Keep Android app UI minimal and debug-only; the current product workflow is MCP
feedback console first.
Prioritize failure-safe behavior and local-first privacy.
```

---

## 27. Final implementation checklist

```text
[ ] Gradle plugin can be applied with id("io.beyondwin.fixthis.compose")
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
[ ] MCP server exposes feedback console workflow tools
[ ] MCP open_feedback_console returns a local console URL
[ ] MCP read_feedback returns complete JSON and compact source-hinted Markdown
[ ] release build has no active FixThis runtime
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
