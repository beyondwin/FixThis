# FixThis Architecture Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor FixThis toward clearer domain boundaries, smaller testable modules, resource-backed console assets, a complete Studio design system, and explicit overlay/session state transitions without breaking the existing MCP, CLI, or persisted session contracts.

**Architecture:** Keep `compose-core` as the pure Kotlin center for capture models, feedback domain models, repository contracts, and use cases. Keep MCP JSON wire DTOs and file persistence in `fixthis-mcp`, and keep Compose-only UI state and Studio theme tokens in `fixthis-compose-overlay`. Sequence the work so each PR either preserves behavior exactly or changes one domain contract with compatibility tests.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Android Gradle Plugin 9.1.1, kotlinx.serialization 1.9.0, kotlinx.coroutines 1.10.2, Compose BOM 2026.04.01, JUnit 4, Kotlin test, existing Gradle modules.

---

## Review Notes Applied

The source proposal is directionally right, but the implementation order needs tightening for this repository:

- `FeedbackConsoleAssets.kt` should be split before broad renames. It is a 3,198-line Kotlin raw string and can be made reviewable with behavior-preserving resource loading before touching domain names.
- `FixThisAnnotation` already exists in `compose-core/src/main/kotlin/io/github/fixthis/compose/core/model/Models.kt` as a capture payload. The new feedback-domain `Annotation` must live under `io.beyondwin.fixthis.compose.core.domain.annotation` and must not replace `FixThisAnnotation` in the first domain PR.
- `FeedbackItemStatus.READY` removal is a data migration, not a mechanical rename. Decode compatibility for persisted `"ready"` values must be locked before writing new normalized statuses.
- Repository interfaces can live in `compose-core`, but file-backed implementations and JSON DTOs stay in `fixthis-mcp`; `compose-core` must not know about MCP wire names, `.fixthis` paths, or HTTP.
- `synchronized` to `Mutex` should happen after service decomposition creates small lock scopes. Replacing all locks first would make the code async-shaped without reducing responsibility.
- Detekt, screenshot regression, and dependency graph tooling are valuable but should land after the main architecture paths are explicit. Before then, lightweight tests and PR checklist guards provide enough friction.

## Current Baseline

- Proposal file: `docs/superpowers/specs/2026-05-06-architecture-improvement-proposal.md`
- Existing untracked proposal state: `?? docs/superpowers/specs/2026-05-06-architecture-improvement-proposal.md`
- Verified high-risk file sizes:
  - `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`: 3,198 lines
  - `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt`: 581 lines
  - `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewSurface.kt`: 595 lines
  - `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/CanvasToolbar.kt`: 358 lines
  - `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt`: 2,184 lines
- Verified Gradle commands:
  - `./gradlew :fixthis-mcp:test --dry-run`
  - `./gradlew :fixthis-compose-core:test --dry-run`
  - `./gradlew :fixthis-compose-overlay:testDebugUnitTest --dry-run`
  - `./gradlew test --dry-run`

## Target File Structure

- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/common/DomainIds.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/annotation/Annotation.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/annotation/AnnotationRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/session/Session.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/session/SessionRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/snapshot/Snapshot.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/snapshot/SnapshotRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase/annotation/CreateAnnotationUseCase.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase/snapshot/SaveSnapshotUseCase.kt`
- Create: `fixthis-compose-core/src/test/kotlin/io/github/fixthis/compose/core/domain/annotation/AnnotationStatusTest.kt`
- Create: `fixthis-mcp/src/main/resources/console/index.html`
- Create: `fixthis-mcp/src/main/resources/console/styles.css`
- Create: `fixthis-mcp/src/main/resources/console/app.js`
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SessionDomainMappers.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/PreviewSnapshotCache.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SourceIndexRegistry.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/ScreenshotArtifactPromoter.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ArchitectureCompatibilityTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/PreviewSnapshotCacheTest.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioTheme.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioSpacing.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioShapes.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioElevation.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioSemanticColors.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/overlay/OverlayStateMachine.kt`
- Create: `fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/overlay/OverlayStateMachineTest.kt`
- Create: `docs/adr/README.md`
- Create: `.github/pull_request_template.md`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/overlay/OverlayMode.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/github/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt`
- Modify: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewSurface.kt`
- Modify: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/CanvasToolbar.kt`
- Modify: related tests under `fixthis-mcp/src/test`, `fixthis-compose-core/src/test`, and `fixthis-compose-overlay/src/test`

## Task 1: Lock Compatibility Before Refactoring

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ArchitectureCompatibilityTest.kt`

- [x] **Step 1: Write JSON and status compatibility tests**

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureCompatibilityTest {
    @Test
    fun legacyReadyFeedbackItemStatusStillDecodes() {
        val item = fixThisJson.decodeFromString(
            FeedbackItem.serializer(),
            """
            {
              "itemId": "item-1",
              "screenId": "screen-1",
              "createdAtEpochMillis": 10,
              "updatedAtEpochMillis": 20,
              "target": {
                "type": "visual_area",
                "boundsInWindow": {
                  "left": 1.0,
                  "top": 2.0,
                  "right": 3.0,
                  "bottom": 4.0
                }
              },
              "comment": "Ready for agent",
              "status": "ready"
            }
            """.trimIndent(),
        )

        assertEquals(FeedbackItemStatus.READY, item.status)
    }

    @Test
    fun sessionWireFormatKeepsExistingFieldNames() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    displayName = "MainActivity",
                    screenshot = FeedbackScreenshot(width = 720, height = 1600),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = FeedbackTarget.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Fix spacing",
                    status = FeedbackItemStatus.READY,
                ),
            ),
            status = FeedbackSessionStatus.READY_FOR_AGENT,
        )

        val encoded = fixThisJson.encodeToString(FeedbackSession.serializer(), session)

        assertTrue(encoded.contains("\"screens\""))
        assertTrue(encoded.contains("\"items\""))
        assertTrue(encoded.contains("\"screenId\""))
        assertTrue(encoded.contains("\"itemId\""))
        assertTrue(encoded.contains("\"ready\""))
        assertTrue(encoded.contains("\"ready_for_agent\""))
    }
}
```

- [x] **Step 2: Run the compatibility tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.ArchitectureCompatibilityTest
```

Expected: PASS before any refactor.

- [x] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ArchitectureCompatibilityTest.kt
git commit -m "test: lock architecture compatibility contracts"
```

## Task 2: Split FeedbackConsole Assets Into Resources

**Files:**
- Create: `fixthis-mcp/src/main/resources/console/index.html`
- Create: `fixthis-mcp/src/main/resources/console/styles.css`
- Create: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing resource-loader contract tests**

Add these tests near the existing HTML contract tests:

```kotlin
@Test
fun consoleAssetsAreLoadedFromClasspathResources() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("<style>"))
    assertTrue(html.contains("</style>"))
    assertTrue(html.contains("<script>"))
    assertTrue(html.contains("</script>"))
    assertTrue(html.contains("class=\"studio-shell\""))
    assertTrue(html.contains("function renderPreviewRegion"))
}

@Test
fun consoleAssetsRejectTraversalPaths() {
    val error = assertFailsWith<IllegalArgumentException> {
        FeedbackConsoleAssets.resource("../FeedbackConsoleAssets.kt")
    }

    assertTrue(error.message!!.contains("path traversal"))
}
```

If `assertFailsWith` is not imported in the file, add:

```kotlin
import kotlin.test.assertFailsWith
```

- [x] **Step 2: Run the tests and confirm the new contract is red**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: the traversal test fails because `FeedbackConsoleAssets.resource` does not exist yet.

- [x] **Step 3: Extract the raw string without changing rendered HTML**

Move the current HTML document from `FeedbackConsoleAssets.indexHtml` into three resources:

- `index.html` keeps document structure and uses `<!-- FIXTHIS_STYLES -->` inside `<head>`.
- `index.html` uses `<!-- FIXTHIS_SCRIPT -->` before `</body>`.
- `styles.css` receives only the CSS currently inside `<style>`.
- `app.js` receives only the JavaScript currently inside `<script>`.

The resulting `index.html` must still contain the same DOM nodes and IDs after `FeedbackConsoleAssets.indexHtml` injects styles and script.

- [x] **Step 4: Replace the Kotlin asset object with a loader**

```kotlin
package io.beyondwin.fixthis.mcp.console

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

- [x] **Step 5: Run the focused test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS. Existing HTML contract tests still see `FeedbackConsoleAssets.indexHtml` with inline CSS and JavaScript.

- [x] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleAssets.kt fixthis-mcp/src/main/resources/console fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "refactor: load feedback console assets from resources"
```

## Task 3: Add Studio Theme Entry Point And Tokens

**Files:**
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioSpacing.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioShapes.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioElevation.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioSemanticColors.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme/StudioTheme.kt`
- Modify: `fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/console/studio/StudioModelAndThemeTest.kt`

- [x] **Step 1: Write token tests**

Add tests to `StudioModelAndThemeTest`:

```kotlin
@Test
fun studioSpacingTokensExposeStableDpValues() {
    val spacing = StudioSpacing()

    assertEquals(4.dp, spacing.xs)
    assertEquals(8.dp, spacing.sm)
    assertEquals(12.dp, spacing.md)
    assertEquals(16.dp, spacing.lg)
    assertEquals(24.dp, spacing.xl)
    assertEquals(32.dp, spacing.xxl)
}

@Test
fun studioSemanticColorsMapExistingPalette() {
    val colors = darkStudioSemanticColors()

    assertColor("#0D0E10", colors.surface)
    assertColor("#131418", colors.surfaceRaised)
    assertColor("#E8E9EB", colors.onSurface)
    assertColor("#B6B8BE", colors.onSurfaceMuted)
    assertColor("#2A2D35", colors.border)
    assertColor("#B8D36A", colors.accent)
    assertColor("#F26D6D", colors.danger)
}
```

Add imports:

```kotlin
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.compose.console.studio.theme.StudioSpacing
import io.beyondwin.fixthis.compose.console.studio.theme.darkStudioSemanticColors
```

- [x] **Step 2: Run the tests and confirm they are red**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest --tests io.beyondwin.fixthis.compose.console.studio.StudioModelAndThemeTest
```

Expected: compile failure because `StudioSpacing` and `darkStudioSemanticColors` do not exist.

- [x] **Step 3: Add spacing, shapes, and elevation**

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class StudioSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)
```

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
internal data class StudioShapes(
    val xs: Shape = RoundedCornerShape(4.dp),
    val sm: Shape = RoundedCornerShape(6.dp),
    val md: Shape = RoundedCornerShape(7.dp),
    val lg: Shape = RoundedCornerShape(8.dp),
    val xl: Shape = RoundedCornerShape(12.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
)
```

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class StudioElevation(
    val none: Dp = 0.dp,
    val flat: Dp = 1.dp,
    val raised: Dp = 4.dp,
    val overlay: Dp = 12.dp,
)
```

- [x] **Step 4: Add semantic colors and theme object**

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
internal data class StudioSemanticColors(
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigher: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceSubtle: Color,
    val border: Color,
    val borderSubtle: Color,
    val accent: Color,
    val accentPressed: Color,
    val danger: Color,
    val warning: Color,
    val statusOpen: Color,
    val statusInProgress: Color,
    val statusResolved: Color,
)

internal fun darkStudioSemanticColors(): StudioSemanticColors =
    StudioSemanticColors(
        surface = StudioColors.Bg0,
        surfaceRaised = StudioColors.Bg1,
        surfaceHigher = StudioColors.Bg2,
        onSurface = StudioColors.Txt0,
        onSurfaceMuted = StudioColors.Txt1,
        onSurfaceSubtle = StudioColors.Txt2,
        border = StudioColors.Line,
        borderSubtle = StudioColors.LineSoft,
        accent = StudioColors.Accent,
        accentPressed = StudioColors.AccentDeep,
        danger = StudioColors.Danger,
        warning = StudioColors.Warn,
        statusOpen = StudioColors.StatusOpen,
        statusInProgress = StudioColors.StatusInProgress,
        statusResolved = StudioColors.StatusResolved,
    )
```

```kotlin
package io.beyondwin.fixthis.compose.console.studio.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalStudioSemanticColors = staticCompositionLocalOf { darkStudioSemanticColors() }
private val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacing() }
private val LocalStudioShapes = staticCompositionLocalOf { StudioShapes() }
private val LocalStudioElevation = staticCompositionLocalOf { StudioElevation() }

@Composable
internal fun StudioTheme(
    colors: StudioSemanticColors = darkStudioSemanticColors(),
    spacing: StudioSpacing = StudioSpacing(),
    shapes: StudioShapes = StudioShapes(),
    elevation: StudioElevation = StudioElevation(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStudioSemanticColors provides colors,
        LocalStudioSpacing provides spacing,
        LocalStudioShapes provides shapes,
        LocalStudioElevation provides elevation,
    ) {
        content()
    }
}

internal object StudioThemeTokens {
    val colors: StudioSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSemanticColors.current

    val spacing: StudioSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSpacing.current

    val shapes: StudioShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioShapes.current

    val elevation: StudioElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioElevation.current
}
```

- [x] **Step 5: Run the focused overlay tests**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest --tests io.beyondwin.fixthis.compose.console.studio.StudioModelAndThemeTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/theme fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/console/studio/StudioModelAndThemeTest.kt
git commit -m "feat: add studio theme tokens"
```

## Task 4: Introduce Feedback Domain Models In Compose Core

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/common/DomainIds.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/annotation/Annotation.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/session/Session.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/snapshot/Snapshot.kt`
- Create: `fixthis-compose-core/src/test/kotlin/io/github/fixthis/compose/core/domain/annotation/AnnotationStatusTest.kt`

- [x] **Step 1: Write a domain status test**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.annotation

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationStatusTest {
    @Test
    fun statusGroupsMatchProductBuckets() {
        assertEquals(AnnotationStatus.Group.OPEN, AnnotationStatus.OPEN.group)
        assertEquals(AnnotationStatus.Group.OPEN, AnnotationStatus.NEEDS_CLARIFICATION.group)
        assertEquals(AnnotationStatus.Group.IN_PROGRESS, AnnotationStatus.IN_PROGRESS.group)
        assertEquals(AnnotationStatus.Group.RESOLVED, AnnotationStatus.RESOLVED.group)
        assertEquals(AnnotationStatus.Group.RESOLVED, AnnotationStatus.WONT_FIX.group)
    }
}
```

- [x] **Step 2: Run the test and confirm it is red**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatusTest
```

Expected: compile failure because the domain package does not exist.

- [x] **Step 3: Add stable value IDs**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.common

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

- [x] **Step 4: Add annotation domain model**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.annotation

import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

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

- [x] **Step 5: Add session and snapshot domain models**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.session

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot

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

data class SessionHandoffBatch(
    val id: String,
    val sequenceNumber: Int,
    val createdAtEpochMillis: Long,
    val annotationIds: List<String>,
    val markdownSnapshot: String? = null,
)

enum class SessionStatus {
    ACTIVE,
    READY_FOR_AGENT,
    CLOSED,
}
```

```kotlin
package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.annotation.SnapshotScreenshot
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect

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
```

- [x] **Step 6: Run core tests**

Run:

```bash
./gradlew :fixthis-compose-core:test
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain fixthis-compose-core/src/test/kotlin/io/github/fixthis/compose/core/domain
git commit -m "feat: add feedback domain models"
```

## Task 5: Add MCP Domain Mappers Before Renaming DTOs

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SessionDomainMappers.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ArchitectureCompatibilityTest.kt`

- [x] **Step 1: Add mapper compatibility tests**

Add these tests:

```kotlin
@Test
fun legacyReadyStatusMapsToOpenDomainStatus() {
    val dto = FeedbackItem(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 12L,
        updatedAtEpochMillis = 13L,
        target = FeedbackTarget.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Ready from old session",
        status = FeedbackItemStatus.READY,
    )

    val domain = dto.toDomainAnnotation(sessionId = "session-1")

    assertEquals(io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus.OPEN, domain.status)
}

@Test
fun domainSessionMapsBackToExistingWireFieldNames() {
    val dto = FeedbackSession(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 20L,
        screens = listOf(CapturedScreen("screen-1", 11L, displayName = "MainActivity")),
    )

    val roundTrip = dto.toDomainSession().toFeedbackSessionDto()

    assertEquals(dto.sessionId, roundTrip.sessionId)
    assertEquals(dto.screens.single().screenId, roundTrip.screens.single().screenId)
    assertEquals(dto.items, roundTrip.items)
}
```

- [x] **Step 2: Run the test and confirm it is red**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.ArchitectureCompatibilityTest
```

Expected: compile failure because the mapper functions do not exist.

- [x] **Step 3: Add the mapper file**

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationDelivery
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.annotation.SnapshotScreenshot
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionHandoffBatch
import io.beyondwin.fixthis.compose.core.domain.session.SessionStatus
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.beyondwin.fixthis.compose.core.domain.snapshot.SnapshotRoot

fun FeedbackSession.toDomainSession(): Session =
    Session(
        id = SessionId(sessionId),
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        snapshots = screens.map(CapturedScreen::toDomainSnapshot),
        annotations = items.map { item -> item.toDomainAnnotation(sessionId) },
        handoffBatches = handoffBatches.map { batch ->
            SessionHandoffBatch(
                id = batch.batchId,
                sequenceNumber = batch.sequenceNumber,
                createdAtEpochMillis = batch.createdAtEpochMillis,
                annotationIds = batch.itemIds,
                markdownSnapshot = batch.markdownSnapshot,
            )
        },
        status = status.toDomainStatus(),
    )

fun Session.toFeedbackSessionDto(): FeedbackSession =
    FeedbackSession(
        sessionId = id.value,
        packageName = packageName,
        projectRoot = projectRoot,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        screens = snapshots.map(Snapshot::toCapturedScreenDto),
        items = annotations.map(Annotation::toFeedbackItemDto),
        handoffBatches = handoffBatches.map { batch ->
            FeedbackHandoffBatch(
                batchId = batch.id,
                sequenceNumber = batch.sequenceNumber,
                createdAtEpochMillis = batch.createdAtEpochMillis,
                itemIds = batch.annotationIds,
                markdownSnapshot = batch.markdownSnapshot,
            )
        },
        status = status.toFeedbackSessionStatusDto(),
    )

fun CapturedScreen.toDomainSnapshot(): Snapshot =
    Snapshot(
        id = SnapshotId(screenId),
        capturedAtEpochMillis = capturedAtEpochMillis,
        activityName = activityName,
        displayName = displayName,
        screenshot = screenshot?.toDomainScreenshot(),
        roots = roots.map { root ->
            SnapshotRoot(
                rootIndex = root.rootIndex,
                boundsInWindow = root.boundsInWindow,
                mergedNodes = root.mergedNodes,
                unmergedNodes = root.unmergedNodes,
            )
        },
        sourceIndexAvailable = sourceIndexAvailable,
        errors = errors,
    )

fun Snapshot.toCapturedScreenDto(): CapturedScreen =
    CapturedScreen(
        screenId = id.value,
        capturedAtEpochMillis = capturedAtEpochMillis,
        activityName = activityName,
        displayName = displayName,
        screenshot = screenshot?.toFeedbackScreenshotDto(),
        roots = roots.map { root ->
            FeedbackScreenRoot(
                rootIndex = root.rootIndex,
                boundsInWindow = root.boundsInWindow,
                mergedNodes = root.mergedNodes,
                unmergedNodes = root.unmergedNodes,
            )
        },
        sourceIndexAvailable = sourceIndexAvailable,
        errors = errors,
    )

fun FeedbackItem.toDomainAnnotation(sessionId: String): Annotation =
    Annotation(
        id = AnnotationId(itemId),
        sessionId = SessionId(sessionId),
        snapshotId = SnapshotId(screenId),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        target = target.toDomainTarget(),
        selectedNode = selectedNode,
        nearbyNodes = nearbyNodes,
        sourceCandidates = sourceCandidates,
        screenshotCrop = screenshotCrop?.toDomainScreenshot(),
        comment = comment,
        sequenceNumber = sequenceNumber,
        delivery = delivery.toDomainDelivery(),
        handoffBatchId = handoffBatchId,
        sentAtEpochMillis = sentAtEpochMillis,
        status = status.toDomainStatus(),
        agentSummary = agentSummary,
    )

fun Annotation.toFeedbackItemDto(): FeedbackItem =
    FeedbackItem(
        itemId = id.value,
        screenId = snapshotId.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        target = target.toFeedbackTargetDto(),
        selectedNode = selectedNode,
        nearbyNodes = nearbyNodes,
        sourceCandidates = sourceCandidates,
        screenshotCrop = screenshotCrop?.toFeedbackScreenshotDto(),
        comment = comment,
        sequenceNumber = sequenceNumber,
        delivery = delivery.toFeedbackDeliveryDto(),
        handoffBatchId = handoffBatchId,
        sentAtEpochMillis = sentAtEpochMillis,
        status = status.toFeedbackItemStatusDto(),
        agentSummary = agentSummary,
    )

private fun FeedbackItemStatus.toDomainStatus(): AnnotationStatus =
    when (this) {
        FeedbackItemStatus.OPEN,
        FeedbackItemStatus.READY -> AnnotationStatus.OPEN
        FeedbackItemStatus.IN_PROGRESS -> AnnotationStatus.IN_PROGRESS
        FeedbackItemStatus.RESOLVED -> AnnotationStatus.RESOLVED
        FeedbackItemStatus.NEEDS_CLARIFICATION -> AnnotationStatus.NEEDS_CLARIFICATION
        FeedbackItemStatus.WONT_FIX -> AnnotationStatus.WONT_FIX
    }

private fun AnnotationStatus.toFeedbackItemStatusDto(): FeedbackItemStatus =
    when (this) {
        AnnotationStatus.OPEN -> FeedbackItemStatus.OPEN
        AnnotationStatus.IN_PROGRESS -> FeedbackItemStatus.IN_PROGRESS
        AnnotationStatus.RESOLVED -> FeedbackItemStatus.RESOLVED
        AnnotationStatus.NEEDS_CLARIFICATION -> FeedbackItemStatus.NEEDS_CLARIFICATION
        AnnotationStatus.WONT_FIX -> FeedbackItemStatus.WONT_FIX
    }

private fun FeedbackDelivery.toDomainDelivery(): AnnotationDelivery =
    when (this) {
        FeedbackDelivery.DRAFT -> AnnotationDelivery.DRAFT
        FeedbackDelivery.SENT -> AnnotationDelivery.SENT
    }

private fun AnnotationDelivery.toFeedbackDeliveryDto(): FeedbackDelivery =
    when (this) {
        AnnotationDelivery.DRAFT -> FeedbackDelivery.DRAFT
        AnnotationDelivery.SENT -> FeedbackDelivery.SENT
    }

private fun FeedbackSessionStatus.toDomainStatus(): SessionStatus =
    when (this) {
        FeedbackSessionStatus.ACTIVE -> SessionStatus.ACTIVE
        FeedbackSessionStatus.READY_FOR_AGENT -> SessionStatus.READY_FOR_AGENT
        FeedbackSessionStatus.CLOSED -> SessionStatus.CLOSED
    }

private fun SessionStatus.toFeedbackSessionStatusDto(): FeedbackSessionStatus =
    when (this) {
        SessionStatus.ACTIVE -> FeedbackSessionStatus.ACTIVE
        SessionStatus.READY_FOR_AGENT -> FeedbackSessionStatus.READY_FOR_AGENT
        SessionStatus.CLOSED -> FeedbackSessionStatus.CLOSED
    }

private fun FeedbackTarget.toDomainTarget(): AnnotationTarget =
    when (this) {
        is FeedbackTarget.Area -> AnnotationTarget.Area(boundsInWindow)
        is FeedbackTarget.Node -> AnnotationTarget.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun AnnotationTarget.toFeedbackTargetDto(): FeedbackTarget =
    when (this) {
        is AnnotationTarget.Area -> FeedbackTarget.Area(boundsInWindow)
        is AnnotationTarget.Node -> FeedbackTarget.Node(nodeUid = nodeUid, boundsInWindow = boundsInWindow)
    }

private fun FeedbackScreenshot.toDomainScreenshot(): SnapshotScreenshot =
    SnapshotScreenshot(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )

private fun SnapshotScreenshot.toFeedbackScreenshotDto(): FeedbackScreenshot =
    FeedbackScreenshot(
        fullPath = fullPath,
        cropPath = cropPath,
        desktopFullPath = desktopFullPath,
        desktopCropPath = desktopCropPath,
        width = width,
        height = height,
        captureFailedReason = captureFailedReason,
    )
```

- [x] **Step 4: Run MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SessionDomainMappers.kt fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ArchitectureCompatibilityTest.kt
git commit -m "feat: map session dto models to domain models"
```

## Task 6: Add Repository Contracts And Use Cases

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/session/SessionRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/annotation/AnnotationRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain/snapshot/SnapshotRepository.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase/annotation/CreateAnnotationUseCase.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase/snapshot/SaveSnapshotUseCase.kt`
- Create: `fixthis-compose-core/src/test/kotlin/io/github/fixthis/compose/core/usecase/annotation/CreateAnnotationUseCaseTest.kt`

- [x] **Step 1: Write the first use case test**

```kotlin
package io.beyondwin.fixthis.compose.core.usecase.annotation

import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateAnnotationUseCaseTest {
    @Test
    fun createsOpenAnnotationInActiveSession() = runBlocking {
        val sessions = FakeSessionRepository(
            Session(
                id = SessionId("session-1"),
                packageName = "io.beyondwin.fixthis.sample",
                projectRoot = "/repo",
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
            ),
        )
        val annotations = FakeAnnotationRepository()
        val useCase = CreateAnnotationUseCase(
            sessions = sessions,
            annotations = annotations,
            clock = { 20L },
            idGenerator = { AnnotationId("annotation-1") },
        )

        val created = useCase(
            sessionId = SessionId("session-1"),
            snapshotId = SnapshotId("screen-1"),
            target = AnnotationTarget.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix spacing",
        )

        assertEquals(AnnotationId("annotation-1"), created.id)
        assertEquals(AnnotationStatus.OPEN, created.status)
        assertEquals(created, annotations.saved.single())
    }
}
```

This test also needs small fake repositories in the same test file:

```kotlin
private class FakeSessionRepository(private val session: Session) : SessionRepository {
    override suspend fun find(id: SessionId): Session? =
        session.takeIf { it.id == id }

    override suspend fun save(session: Session): Session = session
}

private class FakeAnnotationRepository : AnnotationRepository {
    val saved = mutableListOf<Annotation>()

    override suspend fun save(annotation: Annotation): Annotation {
        saved += annotation
        return annotation
    }
}
```

- [x] **Step 2: Run and confirm red**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests io.beyondwin.fixthis.compose.core.usecase.annotation.CreateAnnotationUseCaseTest
```

Expected: compile failure because repositories and use case do not exist.

- [x] **Step 3: Add repository interfaces**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.session

import io.beyondwin.fixthis.compose.core.domain.common.SessionId

interface SessionRepository {
    suspend fun find(id: SessionId): Session?
    suspend fun save(session: Session): Session
}
```

```kotlin
package io.beyondwin.fixthis.compose.core.domain.annotation

interface AnnotationRepository {
    suspend fun save(annotation: Annotation): Annotation
}
```

```kotlin
package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId

interface SnapshotRepository {
    suspend fun find(id: SnapshotId): Snapshot?
    suspend fun save(snapshot: Snapshot): Snapshot
}
```

- [x] **Step 4: Add `CreateAnnotationUseCase`**

```kotlin
package io.beyondwin.fixthis.compose.core.usecase.annotation

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

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
            status = AnnotationStatus.OPEN,
        )
        return annotations.save(annotation)
    }
}
```

- [x] **Step 5: Run core tests**

Run:

```bash
./gradlew :fixthis-compose-core:test
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase fixthis-compose-core/src/test/kotlin/io/github/fixthis/compose/core/usecase
git commit -m "feat: add feedback repositories and create annotation use case"
```

## Task 7: Rename MCP Wire Models To DTO Names

**Files:**
- Move: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionModels.kt` to `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SessionDtoModels.kt`
- Move: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/FeedbackConsoleItemModels.kt` to `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/console/AnnotationRequestModels.kt`
- Modify: all imports and references under `fixthis-mcp/src/main/kotlin`, `fixthis-mcp/src/test/kotlin`, `fixthis-cli/src/main/kotlin`, and `fixthis-cli/src/test/kotlin`

- [x] **Step 1: Run compatibility tests before the rename**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.ArchitectureCompatibilityTest
```

Expected: PASS.

- [x] **Step 2: Mechanically rename classes with wire names preserved**

Apply this mapping:

| Old class | New class |
|---|---|
| `FeedbackSession` | `SessionDto` |
| `FeedbackSessionStatus` | `SessionStatusDto` |
| `CapturedScreen` | `SnapshotDto` |
| `FeedbackScreenRoot` | `SnapshotRootDto` |
| `FeedbackScreenshot` | `SnapshotScreenshotDto` |
| `FeedbackItem` | `AnnotationDto` |
| `FeedbackTarget` | `AnnotationTargetDto` |
| `FeedbackItemStatus` | `AnnotationStatusDto` |
| `AddFeedbackItemRequest` | `AddAnnotationRequest` |
| `PendingDraftFeedbackItem` | `AnnotationDraftDto` |
| `SavePreviewFeedbackItemsRequest` | `SaveSnapshotRequest` |

Do not change serialized field names in this task. Keep JSON property names such as `screens`, `items`, `screenId`, and `itemId` until a separate wire-format migration is designed.

- [x] **Step 3: Keep the old status wire value during decode**

The renamed enum should still accept `"ready"`:

```kotlin
@Serializable
enum class AnnotationStatusDto {
    @SerialName("open")
    OPEN,

    @SerialName("ready")
    READY,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("resolved")
    RESOLVED,

    @SerialName("needs_clarification")
    NEEDS_CLARIFICATION,

    @SerialName("wont_fix")
    WONT_FIX,
}
```

Domain mapping from `READY` remains normalized to `AnnotationStatus.OPEN`.

- [x] **Step 4: Update tests by meaning, not by deleting coverage**

Update `ArchitectureCompatibilityTest` to use the renamed DTO classes while keeping the same assertions about JSON field names and `"ready"` decode.

- [x] **Step 5: Run all JVM tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-mcp fixthis-cli
git commit -m "refactor: rename feedback wire models to annotation dto names"
```

## Task 8: Split Preview Cache And Source Index Registry Out Of Session Service

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/PreviewSnapshotCache.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/SourceIndexRegistry.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/PreviewSnapshotCacheTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt`

- [x] **Step 1: Write cache tests**

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewSnapshotCacheTest {
    @Test
    fun evictsOldestPreviewWhenCapacityIsExceeded() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))
        cache.put(previewRecord("preview-2"))
        cache.put(previewRecord("preview-3"))

        assertNull(cache.get(sessionId = "session-1", previewId = "preview-1"))
        assertEquals("preview-2", cache.get("session-1", "preview-2")!!.snapshot.previewId)
        assertEquals("preview-3", cache.get("session-1", "preview-3")!!.snapshot.previewId)
    }

    @Test
    fun rejectsPreviewFromDifferentSession() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))

        assertNull(cache.get(sessionId = "session-2", previewId = "preview-1"))
    }
}
```

Add this helper to the same test file:

```kotlin
private fun previewRecord(previewId: String): PreviewRecord =
    PreviewRecord(
        sessionId = "session-1",
        projectRoot = "/repo",
        snapshot = FeedbackPreviewSnapshot(
            previewId = previewId,
            screen = CapturedScreen(
                screenId = "screen-$previewId",
                capturedAtEpochMillis = 10L,
                displayName = "Draft screen",
            ),
        ),
        sourceIndex = null,
    )
```

- [x] **Step 2: Run and confirm red**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.PreviewSnapshotCacheTest
```

Expected: compile failure because the cache and top-level `PreviewRecord` do not exist.

- [x] **Step 3: Add cache and top-level record**

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot

data class PreviewRecord(
    val sessionId: String,
    val projectRoot: String,
    val snapshot: FeedbackPreviewSnapshot,
    val sourceIndex: SourceIndex?,
)

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

- [x] **Step 4: Add source index registry**

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SourceIndexRegistry {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, SourceIndex?>()

    suspend fun cached(packageName: String): SourceIndex? =
        mutex.withLock {
            entries[packageName]
        }

    suspend fun contains(packageName: String): Boolean =
        mutex.withLock {
            entries.containsKey(packageName)
        }

    suspend fun put(packageName: String, sourceIndex: SourceIndex?) {
        mutex.withLock {
            entries[packageName] = sourceIndex
        }
    }
}
```

- [x] **Step 5: Wire cache and registry into `FeedbackSessionService`**

Change the constructor defaults:

```kotlin
private val previewCache: PreviewSnapshotCache = PreviewSnapshotCache(MaxRetainedPreviews),
private val sourceIndexRegistry: SourceIndexRegistry = SourceIndexRegistry(),
```

Replace `previewSnapshots` and `previewSavesInFlight` access in small increments. Keep the public `FeedbackSessionService` method signatures unchanged in this task.

- [x] **Step 6: Run MCP session and console tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.FeedbackSessionServiceTest --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/PreviewSnapshotCacheTest.kt
git commit -m "refactor: split preview cache from session service"
```

## Task 9: Extract Screenshot Artifact Promotion

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/ScreenshotArtifactPromoter.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ScreenshotArtifactPromoterTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session/FeedbackSessionService.kt`

- [x] **Step 1: Write artifact promotion test**

```kotlin
package io.beyondwin.fixthis.mcp.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenshotArtifactPromoterTest {
    @Test
    fun promotesPreviewScreenshotsIntoSessionArtifactDirectory() {
        val root = createTempDir(prefix = "fixthis-promoter-")
        val previewDir = File(root, ".fixthis/preview-cache/session-1/preview-1").apply { mkdirs() }
        val full = File(previewDir, "source-full.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val crop = File(previewDir, "source-crop.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val promoter = ScreenshotArtifactPromoter()
        val screen = CapturedScreen(
            screenId = "screen-1",
            capturedAtEpochMillis = 10L,
            displayName = "Draft",
            screenshot = FeedbackScreenshot(
                desktopFullPath = full.absolutePath,
                desktopCropPath = crop.absolutePath,
            ),
        )

        val promoted = promoter.promote(projectRoot = root.absolutePath, sessionId = "session-1", screen = screen)

        val screenshot = promoted.screenshot!!
        assertTrue(screenshot.desktopFullPath!!.contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"))
        assertTrue(screenshot.desktopCropPath!!.contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"))
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(screenshot.desktopFullPath!!).readBytes().toList())
        assertEquals(byteArrayOf(4, 5, 6).toList(), File(screenshot.desktopCropPath!!).readBytes().toList())
    }
}
```

- [x] **Step 2: Run and confirm red**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.ScreenshotArtifactPromoterTest
```

Expected: compile failure because `ScreenshotArtifactPromoter` does not exist.

- [x] **Step 3: Add promoter**

```kotlin
package io.beyondwin.fixthis.mcp.session

import java.io.File

class ScreenshotArtifactPromoter {
    fun promote(projectRoot: String, sessionId: String, screen: CapturedScreen): CapturedScreen {
        val screenshot = screen.screenshot ?: return screen
        val artifactDirectory = FeedbackSessionPaths(File(projectRoot))
            .screenArtifactDirectory(sessionId, screen.screenId)
        if (!artifactDirectory.exists()) {
            check(artifactDirectory.mkdirs()) {
                "Could not create FixThis artifact directory: ${artifactDirectory.absolutePath}"
            }
        }
        val promotedFullPath = promotePath(
            sourcePath = screenshot.desktopFullPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-full.png",
        )
        val promotedCropPath = promotePath(
            sourcePath = screenshot.desktopCropPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-crop.png",
        )
        return screen.copy(
            screenshot = screenshot.copy(
                desktopFullPath = promotedFullPath ?: screenshot.desktopFullPath,
                desktopCropPath = promotedCropPath ?: screenshot.desktopCropPath,
            ),
        )
    }

    private fun promotePath(sourcePath: String?, artifactDirectory: File, fileName: String): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        require(source.exists() && source.isFile) { "Preview screenshot artifact is missing: $sourcePath" }
        val destination = artifactDirectory.resolve(fileName)
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        return destination.absolutePath
    }
}
```

- [x] **Step 4: Replace private promotion functions in service**

Inject:

```kotlin
private val screenshotArtifactPromoter: ScreenshotArtifactPromoter = ScreenshotArtifactPromoter()
```

Replace the call to `promotePreviewArtifacts` with:

```kotlin
val persistedScreen = screenshotArtifactPromoter.promote(
    projectRoot = preview.projectRoot,
    sessionId = sessionId,
    screen = preview.snapshot.screen,
)
```

Remove the old private `promotePreviewArtifacts` and `promoteScreenshotPath` functions from `FeedbackSessionService`.

- [x] **Step 5: Run MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/github/fixthis/mcp/session/ScreenshotArtifactPromoterTest.kt
git commit -m "refactor: extract screenshot artifact promotion"
```

## Task 10: Make OverlayMode Transitions Explicit

**Files:**
- Modify: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/overlay/OverlayMode.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/overlay/OverlayStateMachine.kt`
- Create: `fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/overlay/OverlayStateMachineTest.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/github/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt`
- Modify: related overlay tests

- [x] **Step 1: Write state transition tests**

```kotlin
package io.beyondwin.fixthis.compose.overlay

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverlayStateMachineTest {
    @Test
    fun idleCanEnterSelectMode() {
        val machine = OverlayStateMachine()

        machine.transition(OverlayMode.Select(requestId = "request-1"))

        assertTrue(machine.state.value is OverlayMode.Select)
    }

    @Test
    fun idleCannotJumpToExported() {
        val machine = OverlayStateMachine()

        assertFailsWith<IllegalStateException> {
            machine.transition(OverlayMode.Exported(annotationId = "annotation-1"))
        }
    }

    @Test
    fun loadingCanMoveToRecoverableError() {
        val machine = OverlayStateMachine(OverlayMode.Select(requestId = "request-1"))

        machine.transition(OverlayMode.Loading(OverlayMode.LoadingReason.SCREENSHOT_CAPTURING))
        machine.transition(
            OverlayMode.Error(
                cause = OverlayMode.OverlayError.ScreenshotFailed("capture failed"),
                recoverable = true,
            ),
        )

        assertTrue(machine.state.value is OverlayMode.Error)
    }
}
```

- [x] **Step 2: Run and confirm red**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest --tests io.beyondwin.fixthis.compose.overlay.OverlayStateMachineTest
```

Expected: compile failure because `OverlayStateMachine`, `Select`, `Loading`, and `Error` do not exist.

- [x] **Step 3: Update `OverlayMode`**

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

- [x] **Step 4: Add state machine**

```kotlin
package io.beyondwin.fixthis.compose.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayStateMachine(initial: OverlayMode = OverlayMode.Idle) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<OverlayMode> = mutableState.asStateFlow()

    fun transition(next: OverlayMode) {
        val current = mutableState.value
        check(current.canTransitionTo(next)) {
            "invalid overlay transition: $current -> $next"
        }
        mutableState.value = next
    }
}

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

- [x] **Step 5: Replace `OverlayMode.Selecting` usages**

Replace:

```kotlin
OverlayMode.Selecting(requestId = UUID.randomUUID().toString())
```

with:

```kotlin
OverlayMode.Select(requestId = UUID.randomUUID().toString())
```

Replace checks:

```kotlin
mode is OverlayMode.Selecting
```

with:

```kotlin
mode is OverlayMode.Select
```

- [x] **Step 6: Run overlay and sidekick tests**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest :fixthis-compose-sidekick:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/overlay fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/overlay fixthis-compose-sidekick/src/main/kotlin/io/github/fixthis/compose/sidekick/overlay fixthis-compose-sidekick/src/test/kotlin
git commit -m "refactor: add overlay state machine"
```

## Task 11: Reduce PreviewSurface Responsibility

**Files:**
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewScreenshotContent.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewGestureLayer.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/AnnotationOverlay.kt`
- Modify: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/PreviewSurface.kt`
- Test: `fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/console/studio/canvas/CanvasHelpersTest.kt`

- [x] **Step 1: Add helper tests for gesture enablement**

Add a pure helper test:

```kotlin
@Test
fun previewGestureEnabledOnlyInAnnotateModeWithAvailableAnnotation() {
    assertTrue(isPreviewGestureEnabled(StudioTool.ANNOTATE, CanvasInteractionMode.WidgetSnapAndRegion))
    assertFalse(isPreviewGestureEnabled(StudioTool.SELECT, CanvasInteractionMode.WidgetSnapAndRegion))
    assertFalse(isPreviewGestureEnabled(StudioTool.ANNOTATE, CanvasInteractionMode.AnnotationUnavailable))
}
```

- [x] **Step 2: Add helper implementation**

```kotlin
internal fun isPreviewGestureEnabled(
    tool: StudioTool,
    interactionMode: CanvasInteractionMode,
): Boolean =
    tool == StudioTool.ANNOTATE && interactionMode != CanvasInteractionMode.AnnotationUnavailable
```

Use this helper inside `Modifier.annotateDragGestures`.

- [x] **Step 3: Move screenshot rendering into its own file**

Move `PreviewScreenshotContent`, `PreviewMessage`, `DevicePreviewContent`, and bitmap decoding helpers from `PreviewSurface.kt` into `PreviewScreenshotContent.kt`. Keep function names and visibility unchanged first, so call sites do not change.

- [x] **Step 4: Move overlay loop into `AnnotationOverlay`**

```kotlin
@Composable
internal fun AnnotationOverlay(
    annotations: List<Annotation>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    annotations.forEachIndexed { index, annotation ->
        PinRect(
            annotation = annotation,
            index = index,
            isSelected = annotation.id == selectedId,
            enabled = enabled,
            onClick = { onSelect(annotation.id) },
        )
    }
}
```

- [x] **Step 5: Keep `PreviewSurface` as orchestration**

After extraction, `PreviewSurface.kt` should contain the public `PreviewSurface`, `LocalPreviewSizePx`, `annotateDragGestures`, and direct orchestration only. The line count target for this task is under 260 lines.

- [x] **Step 6: Run overlay tests**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/console/studio/canvas
git commit -m "refactor: split preview surface rendering"
```

## Task 12: Reduce CanvasToolbar Responsibility

**Files:**
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/toolbar/ToolSwitcher.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/toolbar/ToolStatusBar.kt`
- Create: `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/toolbar/ZoomControl.kt`
- Move: toolbar-specific logic from `fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas/CanvasToolbar.kt`

- [x] **Step 1: Move state-free tool switcher first**

Create `ToolSwitcher.kt` with:

```kotlin
@Composable
internal fun ToolSwitcher(
    tool: StudioTool,
    onToolChange: (StudioTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioTool.entries.forEach { option ->
            StudioTextButton(
                text = option.name.lowercase().replaceFirstChar { char -> char.titlecase() },
                selected = tool == option,
                onClick = { onToolChange(option) },
            )
        }
    }
}
```

Use the existing local button primitives from `CanvasToolbar.kt`. If the current primitives are private, move the primitive with `ToolSwitcher` so the extracted file compiles.

- [x] **Step 2: Move status and zoom controls**

Create `ToolStatusBar.kt` for hint text and `ZoomControl.kt` for zoom buttons. Keep the same strings and callback behavior as the current toolbar.

- [x] **Step 3: Update `CanvasToolbar.kt` to layout-only**

`CanvasToolbar.kt` should construct a row and delegate to `ToolSwitcher`, `ToolStatusBar`, and `ZoomControl`. The line count target for this task is under 120 lines.

- [x] **Step 4: Run overlay tests**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-compose-overlay/src/main/kotlin/io/github/fixthis/compose/console/studio/canvas fixthis-compose-overlay/src/test/kotlin/io/github/fixthis/compose/console/studio/canvas
git commit -m "refactor: split canvas toolbar controls"
```

## Task 13: Add ADR And PR Guardrails

**Files:**
- Create: `docs/adr/README.md`
- Create: `docs/adr/0001-use-clean-architecture-layering.md`
- Create: `docs/adr/0002-domain-models-live-in-compose-core.md`
- Create: `docs/adr/0003-feedback-item-to-annotation-naming.md`
- Create: `docs/adr/0004-feedback-console-assets-as-resources.md`
- Create: `docs/adr/0005-overlay-mode-state-machine.md`
- Create: `.github/pull_request_template.md`

- [x] **Step 1: Add ADR README**

```markdown
# Architecture Decision Records

This directory records durable architecture decisions for FixThis.

Each ADR uses a monotonic numeric prefix, a short kebab-case title, and one status:

- Proposed
- Accepted
- Deprecated
- Superseded

Every ADR must include Context, Decision, Consequences, and Alternatives Considered.
```

- [x] **Step 2: Add ADR template content to each ADR**

Use this exact structure for each ADR and fill the title and decision sentence from the implemented task:

```markdown
# ADR-0001: Use Clean Architecture Layering

- Status: Accepted
- Date: 2026-05-06

## Context

FixThis now has UI, MCP, CLI, capture, persistence, and Gradle plugin responsibilities. Domain concepts must not depend on MCP JSON, Android UI, or file-system layout.

## Decision

`compose-core` owns pure domain models, repository contracts, and use cases; outer modules own DTOs, UI state, persistence, bridge, and presentation.

## Consequences

- Domain behavior becomes unit-testable on the JVM.
- MCP JSON changes require explicit mapper changes.
- More mapper code exists at module boundaries.

## Alternatives Considered

- Keep models in MCP and import them from UI code. Rejected because UI state and wire format would remain coupled.
- Move everything into a new shared module. Rejected because `compose-core` is already pure Kotlin and already depended on by the relevant modules.
```

- [x] **Step 3: Add PR template**

```markdown
## Summary

## Impacted Layers
- [ ] Domain (`fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/domain`)
- [ ] Use cases (`fixthis-compose-core/src/main/kotlin/io/github/fixthis/compose/core/usecase`)
- [ ] MCP DTO / persistence
- [ ] CLI / bridge
- [ ] Compose overlay UI
- [ ] Studio theme
- [ ] Tests only
- [ ] Documentation only

## Compatibility
- [ ] Existing persisted sessions still decode
- [ ] MCP JSON field names are unchanged
- [ ] CLI commands keep their current flags and output shape
- [ ] Existing Compose public APIs keep source compatibility or the PR explains the break

## Architecture Checks
- [ ] No MCP DTO imported into `compose-core`
- [ ] No domain model exposed directly from Composable parameters
- [ ] New Studio UI spacing and shapes use theme tokens
- [ ] New coroutine code does not introduce monitor locks around disk or bridge I/O
- [ ] Related ADR is linked or updated

## Verification
- [ ] Commands run are listed below with PASS or FAIL results
- [ ] Relevant checks that were not run are listed as SKIPPED with a reason and residual risk

| Command / check | Result | Notes |
| --- | --- | --- |
|  | PASS / FAIL / SKIPPED |  |
```

- [x] **Step 4: Commit**

```bash
git add docs/adr .github/pull_request_template.md
git commit -m "docs: add architecture guardrails"
```

## Task 14: Final Regression Pass

**Files:**
- No new files

- [x] **Step 1: Run all tests**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [x] **Step 2: Run Android library unit tests**

Run:

```bash
./gradlew :fixthis-compose-overlay:testDebugUnitTest :fixthis-compose-sidekick:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 3: Check architecture import boundaries**

Run:

```bash
rg -n "io\\.github\\.fixthis\\.mcp" fixthis-compose-core/src/main/kotlin
rg -n "FeedbackItem|CapturedScreen|FeedbackSession" fixthis-compose-core/src/main/kotlin fixthis-compose-overlay/src/main/kotlin fixthis-mcp/src/main/kotlin
```

Expected:

- First command prints no matches.
- Second command prints no legacy terminology after Task 7, except historical documentation or intentionally retained test names during the same PR.

Actual:

- `./gradlew test`: PASS, exit code 0.
- `./gradlew :fixthis-gradle-plugin:test`: PASS, exit code 0.
- `./gradlew :fixthis-compose-overlay:testDebugUnitTest :fixthis-compose-sidekick:testDebugUnitTest`: PASS, exit code 0.
- `rg -n "io\\.github\\.fixthis\\.mcp" fixthis-compose-core/src/main/kotlin`: no matches, exit code 1 as expected.
- `rg -n "FeedbackItem|CapturedScreen|FeedbackSession" fixthis-compose-core/src/main/kotlin fixthis-compose-overlay/src/main/kotlin fixthis-mcp/src/main/kotlin`: matches only retained MCP service/API, persistence, console/tool compatibility names, and human-facing feedback item/session strings; no `compose-core` or `compose-overlay` matches, and no unrenamed MCP wire DTO class blockers.

- [x] **Step 4: Commit final cleanup**

```bash
git status --short
git add docs/adr .github fixthis-compose-core fixthis-compose-overlay fixthis-compose-sidekick fixthis-mcp fixthis-cli
git commit -m "chore: complete architecture improvement cleanup"
```

## Self-Review Checklist

- Spec coverage:
  - Terminology unification is covered by Tasks 5 and 7.
  - Console asset extraction is covered by Task 2.
  - Domain and repository boundaries are covered by Tasks 4, 5, and 6.
  - Studio design system is covered by Task 3.
  - Session service decomposition is covered by Tasks 8 and 9.
  - Composable decomposition is covered by Tasks 11 and 12.
  - Overlay state machine is covered by Task 10.
  - ADR and PR guardrails are covered by Task 13.
- Scope adjustment:
  - The plan intentionally does not introduce Hilt, Koin, Detekt, Paparazzi, Roborazzi, GraphQL, Protobuf, or KMP. Those are follow-up decisions after the boundaries are stable.
- Compatibility guard:
  - Existing MCP field names remain locked by `ArchitectureCompatibilityTest`.
  - `READY` status is decoded and normalized through the mapper before it is removed from new domain behavior.
- Type consistency:
  - Domain models use `Annotation`, `Snapshot`, and `Session`.
  - MCP wire models become `AnnotationDto`, `SnapshotDto`, and `SessionDto`.
  - Existing `FixThisAnnotation` remains the sidekick capture payload until a separate capture-domain migration is designed.
