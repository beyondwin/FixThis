# FixThis Product Scene Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the FixThis sample app into a polished Calm Product Studio sample while preserving PointPatch validation coverage.

**Architecture:** Keep the work inside the existing `sample` module. Use immutable demo data, a stronger sample theme, a small shared component system, and five screen files that each render one product scene. Do not add ViewModels, repositories, network calls, persistence, or non-sample namespace changes.

**Tech Stack:** Kotlin, Android Compose, Material 3, existing Gradle sample app module `:app`, existing Android instrumentation tests.

---

## File Structure

- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`
  - Responsibility: navigation smoke test and stable redesigned text anchors.
- Leave unchanged unless compile anchors change: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt`
  - Responsibility: verify `Submit request` remains visible to semantics inspection.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt`
  - Responsibility: sample-only colors, typography, and Material scheme.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
  - Responsibility: app shell, tab state, bottom navigation labels.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt`
  - Responsibility: deterministic sample data for all product scenes.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt`
  - Responsibility: compact section title and optional action label.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt`
  - Responsibility: shared severity/state/status chips.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt`
  - Responsibility: compact metric tiles.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt`
  - Responsibility: repeated feedback cards with metadata and card actions.
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StudioHeader.kt`
  - Responsibility: screen-level product header.
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/PreviewPanel.kt`
  - Responsibility: product preview, chart preview, and weak-semantics surfaces.
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/InfoRow.kt`
  - Responsibility: timeline, activity, and diagnostic rows.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt`
  - Responsibility: overview dashboard scene.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt`
  - Responsibility: triage inbox scene.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ProjectScreen.kt`
  - Responsibility: selected feedback detail scene.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt`
  - Responsibility: agent handoff composer scene.
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt`
  - Responsibility: diagnostics and semantics inspection scene.

## Task 1: Lock Test Anchors

**Files:**
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`
- Inspect: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt`

- [x] **Step 1: Replace the smoke test with redesigned anchors**

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SampleAppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fixThisShowsProductScenesAndNavigatesTabs() {
        rule.onNodeWithText("FixThis Studio").assertExists()
        rule.onNodeWithText("Review work, at a glance").assertExists()

        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Feedback queue").assertExists()
        rule.onNodeWithText("Needs screenshot").assertExists()

        rule.onNodeWithText("Project").performClick()
        rule.onNodeWithText("Affected preview").assertExists()
        rule.onNodeWithText("Close issue").assertExists()

        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Compose fix request").assertExists()
        rule.onNodeWithText("Submit request").assertExists()

        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Visual-only sparkline").assertExists()
        rule.onNodeWithText("Semantic signal timeline").assertExists()
    }
}
```

- [x] **Step 2: Confirm the semantics inspector test keeps the `Submit request` anchor**

Run:

```bash
sed -n '1,220p' sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt
```

Expected: output includes `rule.onNodeWithText("Review").performClick()` and `Submit request`.

- [x] **Step 3: Compile Android tests**

Run:

```bash
./gradlew :app:assembleDebugAndroidTest
```

Expected: Android test APK compiles. Connected execution can fail before implementation because new visible text is not present yet.

- [x] **Step 4: Commit**

```bash
git add sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt
git commit -m "test: lock FixThis redesign anchors"
```

## Task 2: Expand Demo Data And Theme

**Files:**
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt`

- [ ] **Step 1: Replace demo data with richer local product data**

Use this exact shape so later screen tasks can rely on stable property names:

```kotlin
package io.beyondwin.fixthis.sample.model

enum class FeedbackSeverity(val label: String) {
    Critical("Critical"),
    High("High"),
    Medium("Medium"),
    Low("Low"),
}

enum class FeedbackState(val label: String) {
    New("New"),
    Triaged("Triaged"),
    InReview("In review"),
    Blocked("Blocked"),
    Resolved("Resolved"),
}

data class FeedbackItem(
    val id: String,
    val title: String,
    val screenName: String,
    val severity: FeedbackSeverity,
    val state: FeedbackState,
    val assignee: String,
    val ageLabel: String,
    val summary: String,
    val captureLabel: String,
    val sourceConfidence: String,
)

data class ProjectMetric(
    val label: String,
    val value: String,
    val trendLabel: String,
    val state: FeedbackState,
)

data class ActivityEvent(
    val title: String,
    val detail: String,
    val timeLabel: String,
    val category: String,
)

data class DiagnosticSignal(
    val label: String,
    val value: String,
    val state: FeedbackState,
    val trendLabel: String,
)

object FixThisDemoData {
    const val productName = "FixThis Studio"
    const val projectSummary = "Mobile checkout polish"

    val metrics = listOf(
        ProjectMetric("Open feedback", "28", "+6 today", FeedbackState.New),
        ProjectMetric("High priority", "7", "3 need owner", FeedbackState.Blocked),
        ProjectMetric("Resolved this week", "19", "+12%", FeedbackState.Resolved),
        ProjectMetric("Queued agent drafts", "11", "ready to send", FeedbackState.InReview),
    )

    val feedbackItems = listOf(
        FeedbackItem(
            id = "FX-1042",
            title = "Primary purchase action blends into the summary panel",
            screenName = "Payment summary",
            severity = FeedbackSeverity.Critical,
            state = FeedbackState.New,
            assignee = "Mina",
            ageLabel = "12 min",
            summary = "Increase contrast and make the pay action easier to target from the bottom bar.",
            captureLabel = "Bottom bar capture",
            sourceConfidence = "92%",
        ),
        FeedbackItem(
            id = "FX-1038",
            title = "Filter chips wrap awkwardly on narrow devices",
            screenName = "Catalog",
            severity = FeedbackSeverity.High,
            state = FeedbackState.Triaged,
            assignee = "Jules",
            ageLabel = "43 min",
            summary = "Keep selected filters visible while preserving tap targets at large font scale.",
            captureLabel = "Filter rail capture",
            sourceConfidence = "87%",
        ),
        FeedbackItem(
            id = "FX-1029",
            title = "Close confirmation copy is too terse for destructive action",
            screenName = "Project detail",
            severity = FeedbackSeverity.Medium,
            state = FeedbackState.InReview,
            assignee = "Sam",
            ageLabel = "2 hr",
            summary = "Clarify what closes, what remains in history, and how reviewers can reopen it.",
            captureLabel = "Dialog copy capture",
            sourceConfidence = "81%",
        ),
        FeedbackItem(
            id = "FX-1017",
            title = "Health chart has no meaningful target for visual feedback",
            screenName = "Diagnostics",
            severity = FeedbackSeverity.Low,
            state = FeedbackState.Blocked,
            assignee = "No owner",
            ageLabel = "1 day",
            summary = "Keep the visual chart selectable with area selection while labeling the timeline.",
            captureLabel = "Canvas region capture",
            sourceConfidence = "64%",
        ),
    )

    val activity = listOf(
        ActivityEvent("Mina assigned FX-1042", "Payment contrast feedback moved to priority queue.", "Now", "Triage"),
        ActivityEvent("Agent draft prepared", "Review request includes screenshot context and source hints.", "18 min", "Handoff"),
        ActivityEvent("Sam reopened FX-1029", "Close confirmation copy needs one more pass.", "1 hr", "Review"),
    )

    val diagnostics = listOf(
        DiagnosticSignal("Selection confidence", "92%", FeedbackState.Resolved, "+4 points"),
        DiagnosticSignal("Weak semantic regions", "3", FeedbackState.InReview, "2 expected"),
        DiagnosticSignal("Waiting for device preview", "2s", FeedbackState.Blocked, "ADB pending"),
    )

    val queueFilters = listOf("Critical", "Assigned to me", "Needs screenshot", "Waiting")
}
```

- [ ] **Step 2: Replace the sample theme**

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FixThisColors {
    val Accent = Color(0xff007c70)
    val AccentStrong = Color(0xff005f55)
    val AccentSoft = Color(0xffdff4ef)
    val Surface = Color(0xffffffff)
    val SurfaceRaised = Color(0xfffbfdfc)
    val Background = Color(0xfff5f8f6)
    val TextPrimary = Color(0xff15231f)
    val TextSecondary = Color(0xff5d7069)
    val Border = Color(0xffdfe8e4)
    val Warning = Color(0xff9a5c00)
    val WarningSoft = Color(0xfffff0d4)
    val Critical = Color(0xffa8291f)
    val CriticalSoft = Color(0xffffe3df)
    val Success = Color(0xff1b7f3a)
    val SuccessSoft = Color(0xffdff6e7)
    val Neutral = Color(0xff626970)
    val NeutralSoft = Color(0xffeceff1)
}

private val FixThisLightColorScheme: ColorScheme = lightColorScheme(
    primary = FixThisColors.Accent,
    onPrimary = Color.White,
    primaryContainer = FixThisColors.AccentSoft,
    onPrimaryContainer = FixThisColors.TextPrimary,
    secondary = Color(0xff48625a),
    onSecondary = Color.White,
    background = FixThisColors.Background,
    onBackground = FixThisColors.TextPrimary,
    surface = FixThisColors.Surface,
    onSurface = FixThisColors.TextPrimary,
    surfaceVariant = Color(0xffedf4f1),
    onSurfaceVariant = FixThisColors.TextSecondary,
    outline = FixThisColors.Border,
    error = FixThisColors.Critical,
    onError = Color.White,
)

private val FixThisTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun FixThisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FixThisLightColorScheme,
        typography = FixThisTypography,
        content = content,
    )
}
```

- [ ] **Step 3: Compile Kotlin**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt
git commit -m "feat: refresh FixThis sample data and theme"
```

## Task 3: Build The Shared Component System

**Files:**
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StudioHeader.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/PreviewPanel.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/InfoRow.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt`

- [ ] **Step 1: Add `StudioHeader.kt`**

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StudioHeader(
    title: String,
    subtitle: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            text = status,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
```

- [ ] **Step 2: Add `PreviewPanel.kt`**

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PreviewPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(semanticsModifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SparklineSurface(height = height)
    }
}

@Composable
fun SparklineSurface(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
    ) {
        val points = listOf(0.72f, 0.45f, 0.58f, 0.32f, 0.5f, 0.25f, 0.38f)
        val step = size.width / points.lastIndex.coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height * value)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawLine(
            color = track,
            start = Offset(0f, size.height * 0.72f),
            end = Offset(size.width, size.height * 0.72f),
            strokeWidth = 1.dp.toPx(),
        )
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
```

- [ ] **Step 3: Add `InfoRow.kt`**

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InfoRow(
    title: String,
    detail: String,
    meta: String,
    modifier: Modifier = Modifier,
    clickable: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowModifier = if (clickable) modifier.clickable(onClick = {}) else modifier
    Card(
        modifier = rowModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                Text(
                    meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            trailing?.invoke()
        }
    }
}
```

- [ ] **Step 4: Replace shared existing components**

Use these function bodies inside the existing component files.

```kotlin
// SectionHeader.kt
Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    if (action != null) {
        Text(
            text = action,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// StatusChip.kt
Box(
    modifier = modifier
        .background(background, RoundedCornerShape(999.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp),
) {
    Text(text = label, color = contentColor, style = MaterialTheme.typography.labelMedium)
}

val severityColors = when (severity) {
    FeedbackSeverity.Critical -> FixThisColors.CriticalSoft to FixThisColors.Critical
    FeedbackSeverity.High -> FixThisColors.WarningSoft to FixThisColors.Warning
    FeedbackSeverity.Medium -> FixThisColors.AccentSoft to FixThisColors.Accent
    FeedbackSeverity.Low -> FixThisColors.NeutralSoft to FixThisColors.Neutral
}

val stateColors = when (state) {
    FeedbackState.New -> FixThisColors.AccentSoft to FixThisColors.Accent
    FeedbackState.Triaged -> FixThisColors.WarningSoft to FixThisColors.Warning
    FeedbackState.InReview -> FixThisColors.AccentSoft to FixThisColors.Accent
    FeedbackState.Blocked -> FixThisColors.NeutralSoft to FixThisColors.Neutral
    FeedbackState.Resolved -> FixThisColors.SuccessSoft to FixThisColors.Success
}

// MetricCard.kt
Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
) {
    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(metric.label, style = MaterialTheme.typography.labelMedium)
        Text(metric.value, style = MaterialTheme.typography.headlineSmall)
        Text(
            metric.trendLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        StateChip(metric.state)
    }
}

// FeedbackCard.kt
Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.id, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SeverityChip(item.severity)
        }
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(item.state)
            StatusChip(
                label = item.screenName,
                background = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${item.assignee} - ${item.ageLabel} - ${item.sourceConfidence}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {}) { Text("Assign") }
            IconButton(
                modifier = Modifier.semantics {
                    contentDescription = "Save ${item.id}"
                },
                onClick = {},
            ) {
                Text("S", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                enabled = !showDisabledAction,
                onClick = {},
            ) {
                Text("Reviewed")
            }
        }
    }
}
```

- [ ] **Step 5: Compile Kotlin**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/components
git commit -m "feat: add FixThis sample component system"
```

## Task 4: Refresh App Shell And Home/Queue Scenes

**Files:**
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt`

- [ ] **Step 1: Replace the app shell**

Keep the five tabs and use text-based icon labels because the project has no Material icon dependency:

```kotlin
enum class FixThisTab(val label: String, val iconLabel: String) {
    Home("Home", "H"),
    Queue("Queue", "Q"),
    Project("Project", "P"),
    Review("Review", "R"),
    Diagnostics("Diagnostics", "D"),
}
```

Use this `NavigationBarItem` body:

```kotlin
NavigationBarItem(
    selected = selected == tab,
    onClick = { selectedTabName = tab.name },
    icon = { Text(tab.iconLabel, style = MaterialTheme.typography.labelMedium) },
    label = { Text(tab.label, maxLines = 1) },
)
```

Use this `Scaffold` body:

```kotlin
Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            FixThisTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selected == tab,
                    onClick = { selectedTabName = tab.name },
                    icon = { Text(tab.iconLabel, style = MaterialTheme.typography.labelMedium) },
                    label = { Text(tab.label, maxLines = 1) },
                )
            }
        }
    },
) { padding ->
    FixThisTabContent(selected, padding)
}
```

- [ ] **Step 2: Replace `HomeScreen.kt`**

The screen must include these visible strings:

```kotlin
StudioHeader(
    title = "FixThis Studio",
    subtitle = "Review work, at a glance",
    status = "Live",
)
SectionHeader("Project health", "Refresh")
SectionHeader("Priority feedback", "Open queue")
SectionHeader("Recent activity")
```

Use `LazyColumn` with `items(FixThisDemoData.metrics)`, `items(FixThisDemoData.feedbackItems.take(2))`, and `items(FixThisDemoData.activity)`. Render activities through `InfoRow(activity.title, activity.detail, "${activity.category} - ${activity.timeLabel}")`.

- [ ] **Step 3: Replace `QueueScreen.kt`**

The screen must include these visible strings:

```kotlin
SectionHeader("Feedback queue", "28 open")
OutlinedTextField(
    modifier = Modifier.fillMaxWidth(),
    value = searchQuery,
    onValueChange = { searchQuery = it },
    label = { Text("Search feedback") },
    singleLine = true,
)
```

Render `FixThisDemoData.queueFilters` as `FilterChip` labels. Render `FixThisDemoData.feedbackItems` through `FeedbackCard`, passing `showDisabledAction = index == FixThisDemoData.feedbackItems.lastIndex`.

- [ ] **Step 4: Compile and run the smoke test compile target**

Run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt
git commit -m "feat: redesign FixThis home and queue"
```

## Task 5: Refresh Project And Review Scenes

**Files:**
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ProjectScreen.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt`

- [ ] **Step 1: Replace `ProjectScreen.kt` structure**

Keep these state variables:

```kotlin
var menuOpen by rememberSaveable { mutableStateOf(false) }
var closeDialogOpen by rememberSaveable { mutableStateOf(false) }
val item = FixThisDemoData.feedbackItems.first()
```

The visible product anchors must include:

```kotlin
StudioHeader(
    title = item.id,
    subtitle = item.title,
    status = item.state.label,
)
PreviewPanel(
    title = "Affected preview",
    subtitle = "${item.screenName} - ${item.captureLabel}",
)
SectionHeader("Timeline")
OutlinedButton(onClick = { menuOpen = true }) { Text("More actions") }
DropdownMenuItem(
    text = { Text("Close issue") },
    onClick = {
        menuOpen = false
        closeDialogOpen = true
    },
)
AlertDialog(
    onDismissRequest = { closeDialogOpen = false },
    title = { Text("Close issue") },
    text = { Text("Close ${item.id} and keep this feedback in the project history?") },
    confirmButton = {
        TextButton(onClick = { closeDialogOpen = false }) {
            Text("Close issue")
        }
    },
    dismissButton = {
        TextButton(onClick = { closeDialogOpen = false }) {
            Text("Cancel")
        }
    },
)
```

Add metadata cards or rows for `Source confidence` using `item.sourceConfidence` and `Owner` using `item.assignee`.

- [ ] **Step 2: Replace `ReviewScreen.kt` structure**

Keep `rememberSaveable` state for title, target, token, screenshot, sendToAgent, severity, and severityOpen. The visible product anchors must include:

```kotlin
StudioHeader(
    title = "Review request",
    subtitle = "Compose fix request",
    status = if (sendToAgent) "Agent queue" else "Draft",
)
OutlinedTextField(
    modifier = Modifier.fillMaxWidth(),
    value = title,
    onValueChange = { title = it },
    label = { Text("Request title") },
    singleLine = true,
)
OutlinedTextField(
    modifier = Modifier.fillMaxWidth(),
    value = target,
    onValueChange = { target = it },
    label = { Text("Target screen") },
    singleLine = true,
)
OutlinedTextField(
    modifier = Modifier.fillMaxWidth(),
    value = token,
    onValueChange = { token = it },
    label = { Text("Agent token") },
    visualTransformation = PasswordVisualTransformation(),
    singleLine = true,
    supportingText = { Text("Token is masked but remains editable for handoff checks.") },
)
OutlinedButton(onClick = { severityOpen = true }) { Text("Severity: $severity") }
Checkbox(
    modifier = Modifier.semantics {
        contentDescription = "Include screenshot context"
    },
    checked = screenshot,
    onCheckedChange = { screenshot = it },
)
Switch(
    modifier = Modifier.semantics {
        contentDescription = "Send to agent queue"
    },
    checked = sendToAgent,
    onCheckedChange = { sendToAgent = it },
)
Button(modifier = Modifier.fillMaxWidth(), onClick = {}) { Text("Submit request") }
OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { title = "" }) { Text("Clear draft") }
```

Preserve the checkbox content description `Include screenshot context` and switch content description `Send to agent queue`.

- [ ] **Step 3: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/screens/ProjectScreen.kt sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt
git commit -m "feat: redesign FixThis project and review"
```

## Task 6: Refresh Diagnostics Scene

**Files:**
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt`

- [ ] **Step 1: Replace diagnostics layout**

The screen must include these visible anchors and semantic regions:

```kotlin
StudioHeader(
    title = "Diagnostics",
    subtitle = "Inspect selection quality and semantic coverage.",
    status = "Live",
)
Text("Visual-only sparkline", style = MaterialTheme.typography.titleSmall)
SparklineSurface()
Text("Semantic signal timeline", style = MaterialTheme.typography.titleSmall)
SparklineSurface(
    modifier = Modifier.semantics {
        contentDescription = "Semantic signal timeline"
    },
)
```

Render `FixThisDemoData.diagnostics` through `InfoRow`, with `StateChip(signal.state)` as trailing content. Add one weak visual preview:

```kotlin
PreviewPanel(
    title = "Weak semantic preview",
    subtitle = "Area selection should remain useful on this visual block.",
    height = 96.dp,
)
```

Add the long nested row with disabled control:

```kotlin
InfoRow(
    title = "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
    detail = "Nested row target remains selectable while the disabled action communicates blocked state.",
    meta = "Blocked control",
    clickable = true,
) {
    Button(enabled = false, onClick = {}) {
        Text("Disabled action")
    }
}
```

- [ ] **Step 2: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt
git commit -m "feat: redesign FixThis diagnostics"
```

## Task 7: Final Verification

**Files:**
- Inspect all files changed by Tasks 1-6.

- [ ] **Step 1: Run full sample build verification**

Run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 2: Run focused JVM tests that are cheap and related to source matching**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-gradle-plugin:test
```

Expected: PASS.

- [ ] **Step 3: Run connected instrumentation tests when a device is available**

Run:

```bash
adb devices
```

If the output lists a device in `device` state, run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS.

If no device is listed, write this in the final implementation summary: `Connected instrumentation tests were not run because no Android device or emulator was available.`

- [ ] **Step 4: Inspect diff for non-sample namespace drift**

Run:

```bash
git diff -- sample README.md pointpatch-compose-core pointpatch-compose-sidekick pointpatch-gradle-plugin pointpatch-cli pointpatch-mcp
```

Expected: only sample UI/test changes unless a test anchor required a targeted update. No package rename under `io.github.pointpatch.*`.

- [ ] **Step 5: Commit final adjustments**

If Task 7 required fixes:

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample sample/src/androidTest/java/io/beyondwin/fixthis/sample
git commit -m "fix: stabilize FixThis product scene sample"
```

If Task 7 required no fixes, do not create an empty commit.

## Completion Summary Template

Use this final summary after implementation:

```text
Implemented the FixThis product scene redesign across the sample app. Home, Queue, Project, Review, and Diagnostics now share the Calm Product Studio visual system while preserving form, dropdown, dialog, Canvas, disabled-control, repeated-card, long-text, and weak-semantics validation coverage.

Verification:
- ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
- ./gradlew :pointpatch-compose-core:test :pointpatch-gradle-plugin:test
- Connected instrumentation: <run result or not run because no device/emulator was available>
```
