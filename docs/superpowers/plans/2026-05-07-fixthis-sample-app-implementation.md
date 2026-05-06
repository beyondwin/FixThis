# FixThis Sample App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current basic Compose validation app with the branded FixThis Studio sample app at package `io.beyondwin.fixthis.sample`.

**Architecture:** Keep the work scoped to the Android sample app and package-specific sample references. First migrate the sample package/app identity with a minimal compiling app, then add deterministic demo data, shared Compose components, five product-like screens, and updated tests/docs.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Material 3, Compose UI tests, existing PointPatch Gradle plugin.

---

## Source Spec

Implement the approved design in:

```text
docs/superpowers/specs/2026-05-07-fixthis-sample-app-design.md
```

Do not rename PointPatch library, CLI, MCP, Gradle plugin modules, plugin ids, or packages in this plan.

## File Structure

Create this sample app structure:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/
  MainActivity.kt
  FixThisStudioApp.kt
  FixThisTheme.kt
  model/FixThisDemoData.kt
  components/
    FeedbackCard.kt
    MetricCard.kt
    SectionHeader.kt
    StatusChip.kt
  screens/
    HomeScreen.kt
    QueueScreen.kt
    ProjectScreen.kt
    ReviewScreen.kt
    DiagnosticsScreen.kt
```

Move android tests to:

```text
sample/src/androidTest/java/io/beyondwin/fixthis/sample/
  SampleAppSmokeTest.kt
  SemanticsInspectorSampleAppTest.kt
```

Delete the old sample app source package after the new package builds:

```text
sample/src/main/java/io/github/pointpatch/sample/
sample/src/androidTest/java/io/github/pointpatch/sample/
```

## Task 1: Package Identity And Minimal App Shell

**Files:**

- Modify: `sample/build.gradle.kts`
- Modify: `sample/src/main/AndroidManifest.xml`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/MainActivity.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt`
- Delete in Step 6: `sample/src/main/java/io/github/pointpatch/sample/MainActivity.kt`
- Delete in Step 6: `sample/src/main/java/io/github/pointpatch/sample/SampleApp.kt`

- [x] **Step 1: Update Gradle package identity**

Change `sample/build.gradle.kts`:

```kotlin
android {
    namespace = "io.beyondwin.fixthis.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.beyondwin.fixthis.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}
```

- [x] **Step 2: Update launcher label**

Change `sample/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="FixThis"
        android:supportsRtl="true"
        android:theme="@style/Theme.PointPatchSample">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [x] **Step 3: Create the new MainActivity**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/MainActivity.kt`:

```kotlin
package io.beyondwin.fixthis.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FixThisStudioApp()
        }
    }
}
```

- [x] **Step 4: Create the sample theme**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt`:

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object FixThisColors {
    val Accent = Color(0xff087f6f)
    val AccentSoft = Color(0xffd7f3ee)
    val Surface = Color(0xffffffff)
    val Background = Color(0xfff6f8f7)
    val TextPrimary = Color(0xff17201d)
    val TextSecondary = Color(0xff5f6f69)
    val Warning = Color(0xffb76e00)
    val WarningSoft = Color(0xffffefd1)
    val Critical = Color(0xffb42318)
    val CriticalSoft = Color(0xffffe3df)
    val Success = Color(0xff1b7f3a)
    val SuccessSoft = Color(0xffdff6e7)
    val Blocked = Color(0xff626970)
    val BlockedSoft = Color(0xffeceff1)
}

private val FixThisLightColorScheme: ColorScheme = lightColorScheme(
    primary = FixThisColors.Accent,
    onPrimary = Color.White,
    primaryContainer = FixThisColors.AccentSoft,
    onPrimaryContainer = FixThisColors.TextPrimary,
    secondary = Color(0xff47645e),
    onSecondary = Color.White,
    background = FixThisColors.Background,
    onBackground = FixThisColors.TextPrimary,
    surface = FixThisColors.Surface,
    onSurface = FixThisColors.TextPrimary,
    surfaceVariant = Color(0xffe3ebe7),
    onSurfaceVariant = FixThisColors.TextSecondary,
    error = FixThisColors.Critical,
    onError = Color.White,
)

@Composable
fun FixThisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FixThisLightColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
```

- [x] **Step 5: Create a temporary five-tab app shell**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`:

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class FixThisTab(val label: String) {
    Home("Home"),
    Queue("Queue"),
    Project("Project"),
    Review("Review"),
    Diagnostics("Diagnostics"),
}

@Composable
fun FixThisStudioApp() {
    FixThisTheme {
        var selectedTabName by rememberSaveable { mutableStateOf(FixThisTab.Home.name) }
        val selected = FixThisTab.entries.firstOrNull { it.name == selectedTabName } ?: FixThisTab.Home

        Scaffold(
            bottomBar = {
                NavigationBar {
                    FixThisTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selectedTabName = tab.name },
                            icon = { Text(tab.label.take(1), style = MaterialTheme.typography.labelMedium) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            TemporaryTabContent(selected, padding)
        }
    }
}

@Composable
private fun TemporaryTabContent(tab: FixThisTab, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Text(
            text = "FixThis Studio",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = tab.label)
    }
}
```

- [x] **Step 6: Delete old main app files after the new shell exists**

Run:

```bash
rm sample/src/main/java/io/github/pointpatch/sample/MainActivity.kt
rm sample/src/main/java/io/github/pointpatch/sample/SampleApp.kt
```

Expected: old files are removed; old screen files remain until replacement screens are added.

- [x] **Step 7: Build the minimal renamed app**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 8: Commit package identity shell**

Run:

```bash
git add sample/build.gradle.kts sample/src/main/AndroidManifest.xml sample/src/main/java/io/beyondwin/fixthis/sample sample/src/main/java/io/github/pointpatch/sample/MainActivity.kt sample/src/main/java/io/github/pointpatch/sample/SampleApp.kt
git commit -m "feat: rename sample app to FixThis"
```

## Task 2: Demo Data Model

**Files:**

- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt`

- [x] **Step 1: Create deterministic sample data**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt`:

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
)

data class DiagnosticSignal(
    val label: String,
    val value: String,
    val state: FeedbackState,
)

object FixThisDemoData {
    val metrics = listOf(
        ProjectMetric("Open feedback", "28", "+6 today", FeedbackState.New),
        ProjectMetric("High priority", "7", "3 need owner", FeedbackState.Blocked),
        ProjectMetric("Resolved this week", "19", "+12%", FeedbackState.Resolved),
        ProjectMetric("Queued agent drafts", "11", "ready to send", FeedbackState.InReview),
    )

    val feedbackItems = listOf(
        FeedbackItem(
            id = "FX-1042",
            title = "Primary checkout action blends into the summary panel",
            screenName = "Checkout",
            severity = FeedbackSeverity.Critical,
            state = FeedbackState.New,
            assignee = "Mina",
            ageLabel = "12 min",
            summary = "Increase contrast and make the pay action easier to target from the bottom bar.",
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
        ),
        FeedbackItem(
            id = "FX-1029",
            title = "Dialog copy is too terse for destructive close action",
            screenName = "Project detail",
            severity = FeedbackSeverity.Medium,
            state = FeedbackState.InReview,
            assignee = "Sam",
            ageLabel = "2 hr",
            summary = "Clarify what closes, what remains in history, and how reviewers can reopen it.",
        ),
        FeedbackItem(
            id = "FX-1017",
            title = "Canvas health chart has no meaningful target for visual feedback",
            screenName = "Diagnostics",
            severity = FeedbackSeverity.Low,
            state = FeedbackState.Blocked,
            assignee = "No owner",
            ageLabel = "1 day",
            summary = "Keep the visual chart selectable with area selection while labeling the timeline.",
        ),
    )

    val activity = listOf(
        ActivityEvent("Mina assigned FX-1042", "Checkout contrast feedback moved to priority queue.", "Now"),
        ActivityEvent("Agent draft prepared", "Review request includes screenshot context and source hints.", "18 min"),
        ActivityEvent("Sam reopened FX-1029", "Dialog confirmation copy needs one more pass.", "1 hr"),
    )

    val diagnostics = listOf(
        DiagnosticSignal("Selection confidence", "92%", FeedbackState.Resolved),
        DiagnosticSignal("Weak semantic regions", "3", FeedbackState.InReview),
        DiagnosticSignal("Waiting for device preview", "2s", FeedbackState.Blocked),
    )
}
```

- [x] **Step 2: Build after adding model**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Commit demo data**

Run:

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt
git commit -m "feat: add FixThis demo data"
```

## Task 3: Shared UI Components

**Files:**

- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt`

- [x] **Step 1: Create status chip**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt`:

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.FixThisColors
import io.beyondwin.fixthis.sample.model.FeedbackSeverity
import io.beyondwin.fixthis.sample.model.FeedbackState

@Composable
fun StatusChip(
    label: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun SeverityChip(severity: FeedbackSeverity, modifier: Modifier = Modifier) {
    val colors = when (severity) {
        FeedbackSeverity.Critical -> FixThisColors.CriticalSoft to FixThisColors.Critical
        FeedbackSeverity.High -> FixThisColors.WarningSoft to FixThisColors.Warning
        FeedbackSeverity.Medium -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackSeverity.Low -> FixThisColors.BlockedSoft to FixThisColors.Blocked
    }
    StatusChip(severity.label, colors.first, colors.second, modifier)
}

@Composable
fun StateChip(state: FeedbackState, modifier: Modifier = Modifier) {
    val colors = when (state) {
        FeedbackState.New -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackState.Triaged -> FixThisColors.WarningSoft to FixThisColors.Warning
        FeedbackState.InReview -> FixThisColors.AccentSoft to FixThisColors.Accent
        FeedbackState.Blocked -> FixThisColors.BlockedSoft to FixThisColors.Blocked
        FeedbackState.Resolved -> FixThisColors.SuccessSoft to FixThisColors.Success
    }
    StatusChip(state.label, colors.first, colors.second, modifier)
}
```

- [x] **Step 2: Create section header**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt`:

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    modifier: Modifier = Modifier,
) {
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
}
```

- [x] **Step 3: Create metric card**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt`:

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.model.ProjectMetric

@Composable
fun MetricCard(metric: ProjectMetric, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
}
```

- [x] **Step 4: Create feedback card**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt`:

```kotlin
package io.beyondwin.fixthis.sample.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.model.FeedbackItem

@Composable
fun FeedbackCard(
    item: FeedbackItem,
    modifier: Modifier = Modifier,
    showDisabledAction: Boolean = false,
) {
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
                Spacer(Modifier.width(8.dp))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.assignee} - ${item.ageLabel}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    OutlinedButton(onClick = {}) { Text("Assign") }
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = "Save ${item.id}"
                        },
                        onClick = {},
                    ) {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
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
    }
}
```

- [x] **Step 5: Build shared components**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit shared components**

Run:

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/components
git commit -m "feat: add FixThis sample components"
```

## Task 4: Product Screens

**Files:**

- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ProjectScreen.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt`
- Create: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
- Delete after replacement: `sample/src/main/java/io/github/pointpatch/sample/screens/*.kt`

- [x] **Step 1: Create Home screen**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt`:

```kotlin
package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.FeedbackCard
import io.beyondwin.fixthis.sample.components.MetricCard
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun HomeScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("FixThis Studio", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Capture UI feedback, prepare fix requests, and keep agent handoffs reviewable.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { SectionHeader("Project health", "Refresh") }
        items(FixThisDemoData.metrics) { metric ->
            MetricCard(metric)
        }
        item { SectionHeader("Priority feedback", "Open queue") }
        items(FixThisDemoData.feedbackItems.take(2)) { item ->
            FeedbackCard(item)
        }
        item { SectionHeader("Recent activity") }
        items(FixThisDemoData.activity) { event ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = event.title,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${event.detail} - ${event.timeLabel}",
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

- [x] **Step 2: Create Queue screen**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/screens/QueueScreen.kt`:

```kotlin
package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.FeedbackCard
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun QueueScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Feedback queue", "28 open") }
        item {
            OutlinedTextField(
                value = "checkout contrast",
                onValueChange = {},
                label = { Text("Search feedback") },
            )
        }
        item {
            FilterChip(selected = true, onClick = {}, label = { Text("High priority") })
        }
        item {
            FilterChip(selected = false, onClick = {}, label = { Text("Assigned to me") })
        }
        item {
            FilterChip(selected = false, onClick = {}, label = { Text("Needs screenshot") })
        }
        itemsIndexed(FixThisDemoData.feedbackItems) { index, item ->
            FeedbackCard(
                item = item,
                showDisabledAction = index == FixThisDemoData.feedbackItems.lastIndex,
            )
        }
    }
}
```

- [x] **Step 3: Create Project screen**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ProjectScreen.kt`:

```kotlin
package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.components.SeverityChip
import io.beyondwin.fixthis.sample.components.StateChip
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun ProjectScreen(padding: PaddingValues) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var closeDialogOpen by rememberSaveable { mutableStateOf(false) }
    val item = FixThisDemoData.feedbackItems.first()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader("Project detail", item.id)
            Text(item.title, style = MaterialTheme.typography.headlineSmall)
            SeverityChip(item.severity)
            StateChip(item.state)
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Affected preview", style = MaterialTheme.typography.titleMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text("Area selection should remain useful on this visual preview surface.")
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Reproduction note", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "On compact widths the primary checkout action visually competes with the coupon summary. The requested change should improve contrast, keep the total visible, and preserve the bottom action target.",
                    )
                    Text("Agent handoff: adjust checkout CTA contrast and verify source candidates.")
                }
            }
        }
        item {
            Button(onClick = { menuOpen = true }) { Text("More actions") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Assign reviewer") }, onClick = { menuOpen = false })
                DropdownMenuItem(
                    text = { Text("Close issue") },
                    onClick = {
                        menuOpen = false
                        closeDialogOpen = true
                    },
                )
            }
        }
        item { SectionHeader("Timeline") }
        items(FixThisDemoData.activity) { event ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall)
                    Text(event.detail)
                    Text(event.timeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (closeDialogOpen) {
        AlertDialog(
            onDismissRequest = { closeDialogOpen = false },
            title = { Text("Close issue") },
            text = { Text("Closing keeps the feedback in history and removes it from the active queue.") },
            confirmButton = {
                Button(onClick = { closeDialogOpen = false }) { Text("Close issue") }
            },
            dismissButton = {
                TextButton(onClick = { closeDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}
```

- [x] **Step 4: Create Review screen**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt`:

```kotlin
package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ReviewScreen(padding: PaddingValues) {
    var title by rememberSaveable { mutableStateOf("Increase checkout CTA contrast") }
    var target by rememberSaveable { mutableStateOf("Checkout / Bottom bar") }
    var token by rememberSaveable { mutableStateOf("agent-context-token") }
    var screenshot by rememberSaveable { mutableStateOf(true) }
    var sendToAgent by rememberSaveable { mutableStateOf(true) }
    var severity by rememberSaveable { mutableStateOf("High") }
    var severityOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Review request")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = title,
            onValueChange = { title = it },
            label = { Text("Request title") },
            supportingText = { Text("Make the requested UI change specific enough for an agent.") },
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = target,
            onValueChange = { target = it },
            label = { Text("Target screen") },
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = token,
            onValueChange = { token = it },
            label = { Text("Agent token") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = { severityOpen = true }) { Text("Severity: $severity") }
        DropdownMenu(expanded = severityOpen, onDismissRequest = { severityOpen = false }) {
            listOf("Critical", "High", "Medium", "Low").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        severity = option
                        severityOpen = false
                    },
                )
            }
        }
        Row {
            Checkbox(checked = screenshot, onCheckedChange = { screenshot = it })
            Text("Include screenshot context")
        }
        Row {
            Switch(checked = sendToAgent, onCheckedChange = { sendToAgent = it })
            Text(if (sendToAgent) "Send to agent queue" else "Keep as draft")
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {}) {
            Text("Submit request")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { title = "" }) {
            Text("Clear draft")
        }
    }
}
```

- [x] **Step 5: Create Diagnostics screen**

Create `sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt`:

```kotlin
package io.beyondwin.fixthis.sample.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.beyondwin.fixthis.sample.components.SectionHeader
import io.beyondwin.fixthis.sample.model.FixThisDemoData

@Composable
fun DiagnosticsScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("Diagnostics", "Live")
        Text("Canvas-only sparkline")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val points = listOf(0.8f, 0.4f, 0.55f, 0.35f, 0.6f, 0.28f)
            points.zipWithNext().forEachIndexed { index, pair ->
                val x1 = size.width * index / (points.lastIndex)
                val x2 = size.width * (index + 1) / (points.lastIndex)
                drawLine(
                    color = Color(0xff087f6f),
                    start = Offset(x1, size.height * pair.first),
                    end = Offset(x2, size.height * pair.second),
                    strokeWidth = 6f,
                )
            }
        }
        Text("Semantic signal timeline")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics { contentDescription = "Semantic signal timeline" },
        ) {
            drawCircle(Color(0xff087f6f), radius = 24f, center = Offset(size.width * 0.2f, size.height / 2))
            drawCircle(Color(0xffb76e00), radius = 24f, center = Offset(size.width * 0.5f, size.height / 2))
            drawCircle(Color(0xff626970), radius = 24f, center = Offset(size.width * 0.8f, size.height / 2))
        }
        FixThisDemoData.diagnostics.forEach { signal ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(signal.label)
                    Text(signal.value)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {},
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {}
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    text = "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
                )
                Button(enabled = false, onClick = {}) { Text("Disabled action") }
            }
        }
    }
}
```

- [x] **Step 6: Wire screens into app shell**

Replace `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt` with:

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.beyondwin.fixthis.sample.screens.DiagnosticsScreen
import io.beyondwin.fixthis.sample.screens.HomeScreen
import io.beyondwin.fixthis.sample.screens.ProjectScreen
import io.beyondwin.fixthis.sample.screens.QueueScreen
import io.beyondwin.fixthis.sample.screens.ReviewScreen

enum class FixThisTab(val label: String) {
    Home("Home"),
    Queue("Queue"),
    Project("Project"),
    Review("Review"),
    Diagnostics("Diagnostics"),
}

@Composable
fun FixThisStudioApp() {
    FixThisTheme {
        var selectedTabName by rememberSaveable { mutableStateOf(FixThisTab.Home.name) }
        val selected = FixThisTab.entries.firstOrNull { it.name == selectedTabName } ?: FixThisTab.Home

        Scaffold(
            bottomBar = {
                NavigationBar {
                    FixThisTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selectedTabName = tab.name },
                            icon = { Text(tab.label.take(1), style = MaterialTheme.typography.labelMedium) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            when (selected) {
                FixThisTab.Home -> HomeScreen(padding)
                FixThisTab.Queue -> QueueScreen(padding)
                FixThisTab.Project -> ProjectScreen(padding)
                FixThisTab.Review -> ReviewScreen(padding)
                FixThisTab.Diagnostics -> DiagnosticsScreen(padding)
            }
        }
    }
}
```

- [x] **Step 7: Delete old screen package**

Run:

```bash
rm -r sample/src/main/java/io/github/pointpatch/sample/screens
rmdir sample/src/main/java/io/github/pointpatch/sample || true
```

Expected: old `io.github.pointpatch.sample` source package is gone from main sample source.

- [x] **Step 8: Build product screens**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 9: Commit product screens**

Run:

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample sample/src/main/java/io/github/pointpatch/sample
git commit -m "feat: build FixThis Studio sample UI"
```

## Task 5: Sample Instrumentation Tests

**Files:**

- Move: `sample/src/androidTest/java/io/github/pointpatch/sample/SampleAppSmokeTest.kt`
- Move: `sample/src/androidTest/java/io/github/pointpatch/sample/SemanticsInspectorSampleAppTest.kt`
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt`

- [ ] **Step 1: Move test package directory**

Run:

```bash
mkdir -p sample/src/androidTest/java/io/beyondwin/fixthis/sample
git mv sample/src/androidTest/java/io/github/pointpatch/sample/SampleAppSmokeTest.kt sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt
git mv sample/src/androidTest/java/io/github/pointpatch/sample/SemanticsInspectorSampleAppTest.kt sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt
rmdir sample/src/androidTest/java/io/github/pointpatch/sample || true
```

- [ ] **Step 2: Update smoke test**

Replace `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt` with:

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
    fun fixThisShowsHomeAndNavigatesTabs() {
        rule.onNodeWithText("FixThis Studio").assertExists()
        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Feedback queue").assertExists()
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Submit request").assertExists()
        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Semantic signal timeline").assertExists()
    }
}
```

- [ ] **Step 3: Update semantics inspector test**

Replace `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SemanticsInspectorSampleAppTest.kt` with:

```kotlin
package io.beyondwin.fixthis.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.pointpatch.compose.sidekick.inspect.SemanticsInspector
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SemanticsInspectorSampleAppTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun inspectorFindsSubmitRequestOnReviewScreen() {
        rule.onNodeWithText("Review").performClick()
        rule.waitForIdle()

        val result = SemanticsInspector().inspect(rule.activity.window.decorView)
        val nodes = result.mergedNodes + result.unmergedNodes

        assertTrue(result.errors.joinToString { it.message }, result.errors.isEmpty())
        assertTrue(result.roots.isNotEmpty())
        assertTrue(nodes.any { node -> node.text.any { it.contains("Submit request") } })
    }
}
```

- [ ] **Step 4: Build android tests**

Run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run connected tests when a device is available**

Check devices:

```bash
adb devices
```

If a device or emulator is listed as `device`, run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

If no device is available, record in the implementation final answer: `connectedDebugAndroidTest not run; no emulator/device available`.

- [ ] **Step 6: Commit sample tests**

Run:

```bash
git add sample/src/androidTest/java/io/beyondwin/fixthis/sample sample/src/androidTest/java/io/github/pointpatch/sample
git commit -m "test: update FixThis sample tests"
```

## Task 6: README And Package-Specific Fixtures

**Files:**

- Modify: `README.md`
- Inspect and selectively modify: `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/source/SourceMatcherTest.kt`
- Inspect and selectively modify: `pointpatch-gradle-plugin/src/test/kotlin/io/github/pointpatch/gradle/GeneratePointPatchSourceIndexTaskTest.kt`

- [ ] **Step 1: Update README sample package command**

Change the smoke command in `README.md` from:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
```

to:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.beyondwin.fixthis.sample
```

Keep the repo/module wording as `PointPatch` and `:app`.

- [ ] **Step 2: Audit package references**

Run:

```bash
rg -n "io\\.github\\.pointpatch\\.sample|sample/src/main/java/io/github/pointpatch/sample|PointPatch Sample|Pay now|Monthly plan|Email address" README.md sample pointpatch-compose-core pointpatch-compose-sidekick pointpatch-gradle-plugin pointpatch-cli pointpatch-mcp -g '!**/build/**'
```

Expected: results fall into two groups:

- update sample app references and source matcher fixtures tied to actual sample paths
- leave generic unit fixtures that intentionally use arbitrary package names

- [ ] **Step 3: Update source matcher fixture paths tied to sample source**

In `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/source/SourceMatcherTest.kt`, update package/path examples that are meant to represent current sample source:

```kotlin
file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/ReviewScreen.kt"
```

Use visible text anchors from the new app, for example:

```kotlin
text = listOf("Submit request")
activityName = "io.beyondwin.fixthis.sample.MainActivity"
```

Do not bulk-rewrite every `io.github.pointpatch.sample` fixture across MCP/sidekick tests unless the test explicitly claims to model the real sample app path.

- [ ] **Step 4: Update Gradle plugin source index test only if it asserts old sample app text**

In `pointpatch-gradle-plugin/src/test/kotlin/io/github/pointpatch/gradle/GeneratePointPatchSourceIndexTaskTest.kt`, keep synthetic package names if the test creates its own fake files. Update only assertions that expect the app resource text `PointPatch Sample`; use:

```xml
<string name="app_name">FixThis</string>
```

and:

```kotlin
assertTrue(textValues.contains("FixThis"))
```

If the test remains synthetic and not tied to the real sample package, do not rename its package declarations just for branding.

- [ ] **Step 5: Run unit tests affected by fixture updates**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-gradle-plugin:test :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit docs and fixture updates**

Run:

```bash
git add README.md pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/source/SourceMatcherTest.kt pointpatch-gradle-plugin/src/test/kotlin/io/github/pointpatch/gradle/GeneratePointPatchSourceIndexTaskTest.kt
git commit -m "docs: update FixThis sample package references"
```

If either test file did not need changes, omit it from `git add`.

## Task 7: Final Verification And Cleanup

**Files:**

- Inspect: all changed files
- No new files expected unless fixes are required

- [ ] **Step 1: Verify no old sample main package remains**

Run:

```bash
rg -n "package io\\.github\\.pointpatch\\.sample|io\\.github\\.pointpatch\\.sample\\.screens" sample/src/main sample/src/androidTest
```

Expected: no output.

- [ ] **Step 2: Verify new package appears in app config**

Run:

```bash
rg -n "io\\.beyondwin\\.fixthis\\.sample|FixThis" sample README.md
```

Expected:

- `sample/build.gradle.kts` contains namespace and applicationId
- `sample/src/main/AndroidManifest.xml` contains label `FixThis`
- new Kotlin and androidTest package declarations are present
- README run command names `io.beyondwin.fixthis.sample`

- [ ] **Step 3: Run broad build verification**

Run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :pointpatch-compose-core:test :pointpatch-gradle-plugin:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

If this fails in unrelated modules, capture the failure and run the targeted command from Step 3 again before finalizing.

- [ ] **Step 5: Review final diff**

Run:

```bash
git diff --stat HEAD
git diff -- sample/build.gradle.kts sample/src/main/AndroidManifest.xml sample/src/main/java sample/src/androidTest/java README.md
```

Expected:

- sample app package is `io.beyondwin.fixthis.sample`
- no implementation touches PointPatch library packages
- five screens are present
- old developer-only screen labels are removed from the visible sample UI

- [ ] **Step 6: Commit final cleanup after verification fixes**

Run:

```bash
git status --short
git add sample/build.gradle.kts sample/src/main/AndroidManifest.xml sample/src/main/java sample/src/androidTest/java README.md pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/source/SourceMatcherTest.kt pointpatch-gradle-plugin/src/test/kotlin/io/github/pointpatch/gradle/GeneratePointPatchSourceIndexTaskTest.kt
git commit -m "fix: stabilize FixThis sample app"
```

Expected: create this commit only when `git status --short` shows verification fixes that were not already committed by previous tasks. When the working tree is clean, skip the `git add` and `git commit` commands.

## Self-Review Checklist

- Spec coverage:
  - Package identity: Task 1
  - App label and README package command: Tasks 1 and 6
  - Five product tabs: Tasks 1 and 4
  - Home, Queue, Project, Review, Diagnostics screen content: Task 4
  - Form/dialog/menu/Canvas/edge semantics coverage: Task 4
  - Test updates: Task 5
  - Package-specific fixture audit: Task 6
  - Final verification: Task 7
- Placeholder scan:
  - No `TBD`, `TODO`, `implement later`, or unspecified validation steps.
  - Each code-changing task includes concrete file paths and code snippets.
- Scope guard:
  - No task renames `io.github.pointpatch.*` library packages.
  - No task adds networking, persistence, auth, ViewModels, or new external dependencies.
