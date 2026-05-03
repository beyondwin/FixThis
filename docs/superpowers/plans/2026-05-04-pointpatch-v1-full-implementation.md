# PointPatch V1 Full Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build PointPatch V1 Full: a debug-only Android Compose UI selection tool with Smart Select, annotation export, source candidates, CLI, and MCP integration.

**Architecture:** The repo becomes a root Gradle multi-project build. Android runtime code lives in sidekick/overlay modules, reusable models and selection logic live in pure Kotlin core, Gradle plugin generates debug-only assets and dependencies, and CLI/MCP communicate with the running debug app through an ADB-backed local bridge.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose Material3, kotlinx.serialization, kotlinx.coroutines, Clikt or picocli for CLI, Gradle plugin APIs, AndroidX Startup, LocalServerSocket, MCP stdio JSON-RPC.

---

## File Structure

Create or replace these main areas:

- `settings.gradle.kts`: root multi-project settings.
- `build.gradle.kts`: root plugin and repository configuration.
- `gradle/libs.versions.toml`: shared versions for Android, Kotlin, Compose, serialization, coroutines, tests, CLI.
- `sample/`: Compose-only Android app used for manual QA and instrumentation tests.
- `pointpatch-compose-core/`: pure Kotlin models, selection, redaction, formatting, source matching.
- `pointpatch-compose-overlay/`: Android Compose overlay UI and Smart Select UI state.
- `pointpatch-compose-sidekick/`: debug app runtime, AndroidX Startup, Activity hooks, root discovery, screenshot, bridge.
- `pointpatch-gradle-plugin/`: plugin id `io.github.pointpatch.compose`, debug runtime injection, source index generation.
- `pointpatch-cli/`: desktop CLI commands and ADB bridge client.
- `pointpatch-mcp/`: stdio MCP server wrapping the CLI bridge client.
- `docs/`: README, privacy, troubleshooting, MCP guide, output schema.

Keep each module focused. Do not put MCP protocol code in Android modules. Do not put Android APIs in `pointpatch-compose-core`.

## Task 1: Root Multi-Project Build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Delete: `sample/settings.gradle.kts` after migrating sample into the root build
- Test: Gradle project loading

- [ ] **Step 1: Create root settings**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PointPatch"

include(":sample")
include(":pointpatch-compose-core")
include(":pointpatch-compose-overlay")
include(":pointpatch-compose-sidekick")
include(":pointpatch-gradle-plugin")
include(":pointpatch-cli")
include(":pointpatch-mcp")
```

- [ ] **Step 2: Create root build file**

Create `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gradle.plugin.publish) apply false
}
```

- [ ] **Step 3: Create shared version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.1.1"
kotlin = "2.2.21"
composeBom = "2026.04.00"
androidxCore = "1.18.0"
activityCompose = "1.12.0"
startup = "1.2.0"
serialization = "1.9.0"
coroutines = "1.10.2"
clikt = "5.0.3"
junit = "4.13.2"
androidxJunit = "1.3.0"
espresso = "3.7.0"
composeUiTest = "1.10.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "androidxCore" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-startup = { group = "androidx.startup", name = "startup-runtime", version.ref = "startup" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
clikt = { group = "com.github.ajalt.clikt", name = "clikt", version.ref = "clikt" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
gradle-plugin-publish = { id = "com.gradle.plugin-publish", version = "1.3.1" }
```

- [ ] **Step 4: Verify project discovery**

Run:

```bash
./gradlew projects
```

Expected: Gradle lists `:sample`, `:pointpatch-compose-core`, `:pointpatch-compose-overlay`, `:pointpatch-compose-sidekick`, `:pointpatch-gradle-plugin`, `:pointpatch-cli`, and `:pointpatch-mcp`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml
git commit -m "build: create PointPatch multi-project build"
```

## Task 2: Compose Sample App

**Files:**
- Delete or replace: `sample/app/`
- Create: `sample/build.gradle.kts`
- Create: `sample/src/main/AndroidManifest.xml`
- Create: `sample/src/main/java/io/github/pointpatch/sample/MainActivity.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/SampleApp.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/FeedScreen.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/DialogScreen.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/CanvasScreen.kt`
- Create: `sample/src/main/java/io/github/pointpatch/sample/screens/EdgeCasesScreen.kt`
- Test: `sample/src/androidTest/java/io/github/pointpatch/sample/SampleAppSmokeTest.kt`

- [ ] **Step 1: Replace the sample module build**

Create `sample/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.pointpatch.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pointpatch.sample"
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

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

- [ ] **Step 2: Create Android manifest**

Create `sample/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="PointPatch Sample"
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

- [ ] **Step 3: Create minimal theme resources**

Create `sample/src/main/res/values/styles.xml`:

```xml
<resources>
    <style name="Theme.PointPatchSample" parent="android:style/Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 4: Create Compose entry activity**

Create `sample/src/main/java/io/github/pointpatch/sample/MainActivity.kt`:

```kotlin
package io.github.pointpatch.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SampleApp()
        }
    }
}
```

- [ ] **Step 5: Create tabbed sample app shell**

Create `sample/src/main/java/io/github/pointpatch/sample/SampleApp.kt`:

```kotlin
package io.github.pointpatch.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.pointpatch.sample.screens.CanvasScreen
import io.github.pointpatch.sample.screens.CheckoutScreen
import io.github.pointpatch.sample.screens.DialogScreen
import io.github.pointpatch.sample.screens.EdgeCasesScreen
import io.github.pointpatch.sample.screens.FeedScreen
import io.github.pointpatch.sample.screens.FormScreen

private enum class SampleTab(val label: String) {
    Checkout("Checkout"),
    Feed("Feed"),
    Form("Form"),
    Dialog("Dialog"),
    Canvas("Canvas"),
    Edge("Edge")
}

@Composable
fun SampleApp() {
    MaterialTheme {
        var selected by remember { mutableStateOf(SampleTab.Checkout) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    SampleTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            label = { Text(tab.label) },
                            icon = {}
                        )
                    }
                }
            }
        ) { padding ->
            Column {
                when (selected) {
                    SampleTab.Checkout -> CheckoutScreen(padding)
                    SampleTab.Feed -> FeedScreen(padding)
                    SampleTab.Form -> FormScreen(padding)
                    SampleTab.Dialog -> DialogScreen(padding)
                    SampleTab.Canvas -> CanvasScreen(padding)
                    SampleTab.Edge -> EdgeCasesScreen(padding)
                }
            }
        }
    }
}
```

- [ ] **Step 6: Add Checkout screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CheckoutScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Checkout") }
        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Total due") },
                    supportingContent = { Text("\$32.00") }
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Coupon applied") },
                    supportingContent = { Text("Spring discount") }
                )
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {}
            ) {
                Text("Pay now")
            }
        }
    }
}
```

- [ ] **Step 7: Add Feed screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/FeedScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun FeedScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) { index ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (index == 0) "Monthly plan" else "Plan ${index + 1}")
                    Row {
                        Button(onClick = {}) { Text("Apply") }
                        IconButton(
                            modifier = Modifier.semantics { contentDescription = "Save" },
                            onClick = {}
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8: Add Form screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FormScreen(padding: PaddingValues) {
    var email by remember { mutableStateOf("person@example.com") }
    var password by remember { mutableStateOf("secret-password") }
    var agreed by remember { mutableStateOf(true) }
    var marketing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = email,
            onValueChange = { email = it },
            label = { Text("Email address") }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation()
        )
        Row {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I agree")
        }
        Row {
            Switch(checked = marketing, onCheckedChange = { marketing = it })
            Text("Marketing updates")
        }
    }
}
```

- [ ] **Step 9: Add Dialog screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/DialogScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DialogScreen(padding: PaddingValues) {
    var dialogOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = { dialogOpen = true }) { Text("Open dialog") }
        Button(onClick = { menuOpen = true }) { Text("More options") }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Confirm") }, onClick = { menuOpen = false })
        }
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Confirm") },
            text = { Text("Dialog body") },
            confirmButton = {
                Button(onClick = { dialogOpen = false }) { Text("Confirm") }
            }
        )
    }
}
```

- [ ] **Step 10: Add Canvas screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/CanvasScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun CanvasScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Canvas only")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            drawRect(Color(0xff6f8cff))
        }
        Text("Semantic chart")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = "Semantic chart" }
        ) {
            drawCircle(Color(0xff18a999"))
        }
    }
}
```

- [ ] **Step 11: Add Edge Cases screen**

Create `sample/src/main/java/io/github/pointpatch/sample/screens/EdgeCasesScreen.kt`:

```kotlin
package io.github.pointpatch.sample.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EdgeCasesScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {}
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Very long product title that should wrap without breaking selection")
                Button(enabled = false, onClick = {}) { Text("Disabled action") }
            }
        }
    }
}
```

- [ ] **Step 12: Add sample smoke test**

Create `sample/src/androidTest/java/io/github/pointpatch/sample/SampleAppSmokeTest.kt`:

```kotlin
package io.github.pointpatch.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SampleAppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sampleShowsCheckoutAndNavigatesTabs() {
        rule.onNodeWithText("Pay now").assertExists()
        rule.onNodeWithText("Feed").performClick()
        rule.onNodeWithText("Monthly plan").assertExists()
        rule.onNodeWithText("Form").performClick()
        rule.onNodeWithText("Email address").assertExists()
    }
}
```

- [ ] **Step 13: Run sample tests**

Run:

```bash
./gradlew :sample:assembleDebug :sample:connectedDebugAndroidTest
```

Expected: debug APK builds and smoke test passes on a connected device/emulator.

- [ ] **Step 14: Commit**

```bash
git add sample
git commit -m "testapp: replace sample with Compose coverage app"
```

## Task 3: Core Annotation Models and Formatters

**Files:**
- Create: `pointpatch-compose-core/build.gradle.kts`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/model/Models.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/redaction/RedactionPolicy.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/format/PointPatchJsonFormatter.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/format/PointPatchMarkdownFormatter.kt`
- Test: `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/format/PointPatchMarkdownFormatterTest.kt`
- Test: `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/redaction/RedactionPolicyTest.kt`

- [ ] **Step 1: Configure core module**

Create `pointpatch-compose-core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

- [ ] **Step 2: Define stable schema models**

Create `Models.kt` with:

```kotlin
package io.github.pointpatch.compose.core.model

import kotlinx.serialization.Serializable

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

@Serializable
data class AppInfo(val packageName: String, val versionName: String? = null, val versionCode: Long? = null, val debuggable: Boolean)

@Serializable
data class ActivityInfo(val className: String)

@Serializable
data class TapPoint(val xInWindow: Float, val yInWindow: Float)

@Serializable
data class PointPatchRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

@Serializable
enum class TreeKind { MERGED, UNMERGED }

@Serializable
enum class SelectionKind { SEMANTICS_NODE, VISUAL_AREA, TAP_POINT }

@Serializable
enum class SelectionConfidence { HIGH, MEDIUM, LOW, NONE }

@Serializable
enum class SelectionSource { TAP_SELECT, SCOPE_CHIP, AREA_SELECT, FALLBACK }

@Serializable
data class SelectionInfo(
    val kind: SelectionKind,
    val confidence: SelectionConfidence,
    val selectedUid: String? = null,
    val areaBoundsInWindow: PointPatchRect? = null,
    val source: SelectionSource
)

@Serializable
data class PointPatchNode(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: TreeKind,
    val boundsInWindow: PointPatchRect,
    val text: List<String> = emptyList(),
    val editableText: String? = null,
    val contentDescription: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
    val stateDescription: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val actions: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val path: List<String> = emptyList(),
    val rawProperties: Map<String, String> = emptyMap()
) {
    fun hasMeaningfulSemantic(): Boolean =
        text.isNotEmpty() || editableText != null || contentDescription.isNotEmpty() ||
            role != null || testTag != null || actions.isNotEmpty() || stateDescription != null
}

@Serializable
data class ScoredPointPatchNode(val node: PointPatchNode, val score: Double, val breakdown: Map<String, Double>)

@Serializable
data class ScopeCandidate(val label: String, val nodeUid: String, val boundsInWindow: PointPatchRect, val score: Double)

@Serializable
data class SourceCandidate(
    val file: String,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SelectionConfidence
)

@Serializable
data class ScreenshotInfo(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null
)

@Serializable
data class PointPatchError(val code: String, val message: String, val details: Map<String, String> = emptyMap())
```

- [ ] **Step 3: Add redaction policy**

Create `RedactionPolicy.kt`:

```kotlin
package io.github.pointpatch.compose.core.redaction

data class RedactedText(
    val text: List<String>,
    val editableText: String?,
    val redacted: Boolean
)

object RedactionPolicy {
    fun apply(
        isPassword: Boolean,
        editableText: String?,
        text: List<String>,
        redactEditableText: Boolean = true
    ): RedactedText {
        if (isPassword) {
            return RedactedText(listOf("<redacted-password>"), "<redacted-password>", true)
        }
        if (editableText != null && redactEditableText) {
            return RedactedText(text, "<redacted-editable-text>", true)
        }
        return RedactedText(text, editableText, false)
    }
}
```

- [ ] **Step 4: Add JSON and Markdown formatters**

Create `PointPatchJsonFormatter.kt`:

```kotlin
package io.github.pointpatch.compose.core.format

import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PointPatchJsonFormatter {
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun format(annotation: PointPatchAnnotation): String = json.encodeToString(annotation)
}
```

Create `PointPatchMarkdownFormatter.kt`:

```kotlin
package io.github.pointpatch.compose.core.format

import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchNode

object PointPatchMarkdownFormatter {
    fun format(annotation: PointPatchAnnotation): String = buildString {
        appendLine("# PointPatch Compose Feedback")
        appendLine()
        appendLine("## User request")
        appendLine(annotation.userComment.ifBlank { "(No comment)" })
        appendLine()
        appendLine("## Selection")
        appendLine("- Kind: ${annotation.selection.kind}")
        appendLine("- Confidence: ${annotation.selection.confidence}")
        appendLine("- Source: ${annotation.selection.source}")
        annotation.selection.areaBoundsInWindow?.let { appendLine("- Area: ${it.left},${it.top},${it.right},${it.bottom}") }
        appendLine()
        appendLine("## Selected UI")
        appendNode(annotation.selectedNode)
        appendLine()
        appendLine("## Nearby context")
        if (annotation.nearbyNodes.isEmpty()) appendLine("- none")
        annotation.nearbyNodes.forEach { appendLine("- ${it.summary()}") }
        appendLine()
        appendLine("## Source candidates")
        if (annotation.sourceCandidates.isEmpty()) appendLine("- none")
        annotation.sourceCandidates.forEachIndexed { index, candidate ->
            appendLine("${index + 1}. `${candidate.file}${candidate.line?.let { ":$it" } ?: ""}`")
            appendLine("   - score: ${candidate.score}")
            appendLine("   - reasons: ${candidate.matchReasons.joinToString()}")
        }
        appendLine()
        appendLine("## Search hints")
        if (annotation.searchHints.isEmpty()) appendLine("- none")
        annotation.searchHints.forEach { appendLine("- \"$it\"") }
        appendLine()
        appendLine("## Screenshot")
        val screenshot = annotation.screenshot
        if (screenshot == null) {
            appendLine("- none")
        } else {
            appendLine("- full: ${screenshot.desktopFullPath ?: screenshot.fullPath ?: "none"}")
            appendLine("- crop: ${screenshot.desktopCropPath ?: screenshot.cropPath ?: "none"}")
            screenshot.captureFailedReason?.let { appendLine("- capture failed: $it") }
        }
        if (annotation.errors.isNotEmpty()) {
            appendLine()
            appendLine("## Capture notes")
            annotation.errors.forEach { appendLine("- ${it.code}: ${it.message}") }
        }
    }

    private fun StringBuilder.appendNode(node: PointPatchNode?) {
        if (node == null) {
            appendLine("- none")
            return
        }
        appendLine("- UID: ${node.uid}")
        appendLine("- Tree: ${node.treeKind}")
        appendLine("- Role: ${node.role ?: "none"}")
        appendLine("- Text: ${node.text.joinToString().ifBlank { "none" }}")
        appendLine("- Content description: ${node.contentDescription.joinToString().ifBlank { "none" }}")
        appendLine("- Bounds: ${node.boundsInWindow.left},${node.boundsInWindow.top},${node.boundsInWindow.right},${node.boundsInWindow.bottom}")
        appendLine("- Actions: ${node.actions.joinToString().ifBlank { "none" }}")
    }

    private fun PointPatchNode.summary(): String {
        val label = text.firstOrNull() ?: contentDescription.firstOrNull() ?: role ?: testTag ?: uid
        return "${role ?: "Node"} \"$label\""
    }
}
```

- [ ] **Step 5: Add core tests**

Create formatter and redaction tests asserting:

```kotlin
assertTrue(markdown.contains("PointPatch Compose Feedback"))
assertTrue(markdown.contains("Pay now"))
assertEquals("<redacted-password>", RedactionPolicy.apply(true, "secret", emptyList()).editableText)
```

- [ ] **Step 6: Run core tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test
```

Expected: all core tests pass.

- [ ] **Step 7: Commit**

```bash
git add pointpatch-compose-core
git commit -m "core: add annotation schema and formatters"
```

## Task 4: Core Selection and Source Matching

**Files:**
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/selection/NodeSelector.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/selection/NearbyNodeCollector.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/source/SourceIndex.kt`
- Create: `pointpatch-compose-core/src/main/kotlin/io/github/pointpatch/compose/core/source/SourceMatcher.kt`
- Test: `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/selection/NodeSelectorTest.kt`
- Test: `pointpatch-compose-core/src/test/kotlin/io/github/pointpatch/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Add failing selector tests**

Tests must cover clickable button over text, root penalty, no-node fallback, and scope candidate labels.

Run:

```bash
./gradlew :pointpatch-compose-core:test --tests '*NodeSelectorTest'
```

Expected: fails because `NodeSelector` does not exist.

- [ ] **Step 2: Implement NodeSelector**

Create `NodeSelector.kt` with `SelectionResult`, `SelectionOptions`, and `NodeSelector.select(nodes, tap, options)`. Scoring must add high weight for `OnClick`, text/contentDescription/role/testTag, merged meaningful nodes, and lower score for huge empty containers.

Required public API:

```kotlin
data class SelectionOptions(val maxCandidates: Int = 5, val maxNearbyNodes: Int = 12, val nearbyRadiusPx: Float = 480f)

data class SelectionResult(
    val selectedNode: PointPatchNode?,
    val candidatesAtPoint: List<ScoredPointPatchNode>,
    val scopeCandidates: List<ScopeCandidate>,
    val nearbyNodes: List<PointPatchNode>,
    val selection: SelectionInfo
)
```

- [ ] **Step 3: Implement NearbyNodeCollector**

Collect meaningful nodes from the same root, sorted by center distance, deduped by role/text/contentDescription/testTag.

- [ ] **Step 4: Add source index models and matcher**

Create a serializable `SourceIndex`, `SourceIndexEntry`, and `SourceMatcher.match(selectedNode, nearbyNodes, activityName)` that returns up to five candidates with `matchedTerms`, `matchReasons`, and confidence.

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test
```

Expected: selector and matcher tests pass.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-compose-core
git commit -m "core: add selection and source matching"
```

## Task 5: Overlay UI and Smart Select State

**Files:**
- Create: `pointpatch-compose-overlay/build.gradle.kts`
- Create: `pointpatch-compose-overlay/src/main/kotlin/io/github/pointpatch/compose/overlay/OverlayMode.kt`
- Create: `pointpatch-compose-overlay/src/main/kotlin/io/github/pointpatch/compose/overlay/PointPatchToolbar.kt`
- Create: `pointpatch-compose-overlay/src/main/kotlin/io/github/pointpatch/compose/overlay/PointPatchSelectionLayer.kt`
- Create: `pointpatch-compose-overlay/src/main/kotlin/io/github/pointpatch/compose/overlay/PointPatchCommentSheet.kt`
- Create: `pointpatch-compose-overlay/src/main/kotlin/io/github/pointpatch/compose/overlay/PointPatchHighlightLayer.kt`

- [ ] **Step 1: Configure overlay module**

Create Android library module with Compose enabled and dependency on `:pointpatch-compose-core`.

- [ ] **Step 2: Add overlay state model**

Create sealed state:

```kotlin
sealed interface OverlayMode {
    data object Idle : OverlayMode
    data object MenuOpen : OverlayMode
    data class Selecting(val requestId: String?) : OverlayMode
    data class ReviewingSelection(val draft: PointPatchDraft) : OverlayMode
    data class Commenting(val draft: PointPatchDraft) : OverlayMode
    data class Exported(val annotationId: String) : OverlayMode
}
```

- [ ] **Step 3: Add toolbar composable**

`PointPatchToolbar` shows a 56dp button and menu entries: `Select UI`, `Recent`, `Connect AI Agent`. Only `Select UI` needs to be active in the first implementation.

- [ ] **Step 4: Add selection layer composable**

`PointPatchSelectionLayer` consumes taps only in selecting mode and supports long press / drag to emit visual area bounds.

Public callbacks:

```kotlin
onTap(xInWindow: Float, yInWindow: Float)
onAreaSelected(left: Float, top: Float, right: Float, bottom: Float)
onCancel()
```

- [ ] **Step 5: Add highlight and comment sheet**

Highlight selected bounds and candidate scopes. Comment sheet must show selected summary, scope chips, text field, screenshot crop when available, `No screenshot crop` when capture has not run, and actions: `Copy for AI`, `Copy JSON`, `Share`, or `Send to AI Agent` when MCP is waiting.

- [ ] **Step 6: Run overlay build**

Run:

```bash
./gradlew :pointpatch-compose-overlay:assembleDebug
```

Expected: overlay module builds.

- [ ] **Step 7: Commit**

```bash
git add pointpatch-compose-overlay
git commit -m "overlay: add Smart Select UI components"
```

## Task 6: Sidekick Autoinit, Activity Hook, and Root Discovery

**Files:**
- Create: `pointpatch-compose-sidekick/build.gradle.kts`
- Create: `pointpatch-compose-sidekick/src/main/AndroidManifest.xml`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/init/PointPatchInitializer.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/PointPatch.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/lifecycle/PointPatchActivityLifecycleCallbacks.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/overlay/PointPatchOverlayHostLayout.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/inspect/ComposeRootFinder.kt`
- Test: sample debug/release manual checks

- [ ] **Step 1: Configure sidekick module**

Create Android library module with dependencies on core, overlay, AndroidX Startup, Compose UI, and coroutines Android.

- [ ] **Step 2: Add AndroidX Startup manifest**

Use `InitializationProvider` and metadata for `PointPatchInitializer`. Do not add `INTERNET` permission.

- [ ] **Step 3: Implement debuggable guard**

`PointPatchInitializer.create(context)` must return immediately unless `ApplicationInfo.FLAG_DEBUGGABLE` is set.

- [ ] **Step 4: Implement lifecycle install**

`PointPatch.install(application)` registers `ActivityLifecycleCallbacks` once using `AtomicBoolean`.

- [ ] **Step 5: Attach idle overlay host**

On resumed activity, add a `PointPatchOverlayHostLayout` to decorView if one is not already present. Host must be non-clickable while idle except for the toolbar child.

- [ ] **Step 6: Implement ComposeRootFinder**

Traverse decorView, skip any view tagged with `io.github.pointpatch.compose.overlay.HOST`, collect views implementing `androidx.compose.ui.node.RootForTest`, and record bounds in window.

- [ ] **Step 7: Add sample debug dependency**

Temporarily add:

```kotlin
debugImplementation(project(":pointpatch-compose-sidekick"))
```

to `sample/build.gradle.kts` until the Gradle plugin task replaces it.

- [ ] **Step 8: Run debug sample**

Run:

```bash
./gradlew :sample:installDebug
adb shell monkey -p io.github.pointpatch.sample 1
```

Expected: app launches and PointPatch toolbar is visible.

- [ ] **Step 9: Run release sample**

Run:

```bash
./gradlew :sample:assembleRelease
```

Expected: release builds. Manual launch should not show active PointPatch runtime.

- [ ] **Step 10: Commit**

```bash
git add pointpatch-compose-sidekick sample/build.gradle.kts
git commit -m "sidekick: autoinit debug overlay"
```

## Task 7: Semantics Inspection and Annotation Capture

**Files:**
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/inspect/SemanticsInspector.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/inspect/SemanticsNodeMapper.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/capture/AnnotationCaptureController.kt`
- Create: `pointpatch-compose-sidekick/src/androidTest/kotlin/io/github/pointpatch/compose/sidekick/SemanticsInspectorTest.kt`

- [ ] **Step 1: Add inspector instrumentation test**

Test against sample app: inspect current screen, assert at least one root and a node containing `Pay now`.

- [ ] **Step 2: Implement SemanticsInspector**

Use `RootForTest.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true/false, skipDeactivatedNodes = true)`. Capture errors as `PointPatchError` instead of throwing.

- [ ] **Step 3: Implement SemanticsNodeMapper**

Map text, editable text, contentDescription, role, testTag, stateDescription, selected, disabled, actions, password, bounds, and path. Apply `RedactionPolicy` before storing editable text.

- [ ] **Step 4: Implement capture controller**

`AnnotationCaptureController` handles Tap Select, Scope Chip reselection, Area Select fallback, user comment, source matching, and annotation construction.

- [ ] **Step 5: Run instrumentation**

Run:

```bash
./gradlew :pointpatch-compose-sidekick:connectedDebugAndroidTest
```

Expected: inspector sees sample Compose roots and redacts editable/password content.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-compose-sidekick
git commit -m "sidekick: inspect Compose semantics and capture annotations"
```

## Task 8: Screenshot, Clipboard, and Local Export

**Files:**
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/screenshot/ScreenshotCapturer.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/screenshot/ScreenshotStore.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/export/ClipboardExporter.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/export/LocalFileExporter.kt`

- [ ] **Step 1: Implement screenshot result**

Use PixelCopy on API 26+ with timeout; fallback to `decorView.draw(Canvas)`. Return `ScreenshotInfo` with `captureFailedReason` if all capture methods fail.

- [ ] **Step 2: Store screenshots**

Save PNGs under:

```text
context.cacheDir/pointpatch/YYYY-MM-DD/<annotation-id>-full.png
context.cacheDir/pointpatch/YYYY-MM-DD/<annotation-id>-crop.png
```

- [ ] **Step 3: Add clipboard export**

Use `ClipboardManager` to copy Markdown or JSON from core formatters. Include warning text in the UI that screenshots may contain sensitive information.

- [ ] **Step 4: Run manual capture**

On the sample app, select `Pay now`, enter `Change label to Pay immediately`, and copy Markdown.

Expected: clipboard contains selected UI, nearby context, screenshot path, and user request.

- [ ] **Step 5: Commit**

```bash
git add pointpatch-compose-sidekick
git commit -m "sidekick: capture screenshots and export annotations"
```

## Task 9: Gradle Plugin and Source Index

**Files:**
- Create: `pointpatch-gradle-plugin/build.gradle.kts`
- Create: `pointpatch-gradle-plugin/src/main/kotlin/io/github/pointpatch/gradle/PointPatchGradlePlugin.kt`
- Create: `pointpatch-gradle-plugin/src/main/kotlin/io/github/pointpatch/gradle/PointPatchExtension.kt`
- Create: `pointpatch-gradle-plugin/src/main/kotlin/io/github/pointpatch/gradle/task/GeneratePointPatchSourceIndexTask.kt`
- Create: `pointpatch-gradle-plugin/src/test/kotlin/io/github/pointpatch/gradle/GeneratePointPatchSourceIndexTaskTest.kt`

- [ ] **Step 1: Configure plugin module**

Use `java-gradle-plugin`, Kotlin JVM, and serialization. Register plugin id:

```kotlin
gradlePlugin {
    plugins {
        create("pointpatchCompose") {
            id = "io.github.pointpatch.compose"
            implementationClass = "io.github.pointpatch.gradle.PointPatchGradlePlugin"
        }
    }
}
```

- [ ] **Step 2: Add extension**

Create properties: `enabled`, `runtimeVersion`, `addDebugRuntime`, `generateSourceIndex`, `generateProjectMetadata`, `includeScreenshots`, `redactEditableText`.

- [ ] **Step 3: Implement debug runtime injection**

When `com.android.application` is present, add `pointpatch-compose-sidekick` only to debuggable variants. During local development, support project dependency injection when the sidekick project exists.

- [ ] **Step 4: Implement source index task**

Scan Kotlin files and XML string resources. Extract string literals, `Text("...")`, `stringResource(R.string.x)`, `Modifier.testTag("...")`, composable function names, file path, line, and excerpt.

- [ ] **Step 5: Package generated assets**

Add generated directory to debug variant assets:

```text
assets/pointpatch/pointpatch-source-index.json
assets/pointpatch/pointpatch-build-info.json
```

- [ ] **Step 6: Apply plugin to sample**

Replace temporary debug dependency with:

```kotlin
plugins {
    id("io.github.pointpatch.compose")
}
```

through included build or local plugin wiring in the root build.

- [ ] **Step 7: Run plugin tests and sample build**

Run:

```bash
./gradlew :pointpatch-gradle-plugin:test :sample:assembleDebug
```

Expected: source index exists in debug assets and sample app still shows PointPatch runtime.

- [ ] **Step 8: Commit**

```bash
git add pointpatch-gradle-plugin sample/build.gradle.kts
git commit -m "gradle: add PointPatch plugin and source index"
```

## Task 10: Sidekick Bridge

**Files:**
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeProtocol.kt`
- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/SessionTokenStore.kt`

- [ ] **Step 1: Define bridge protocol**

Use length-prefixed UTF-8 JSON frames. Define methods: `status`, `inspectCurrentScreen`, `startFeedbackCapture`, `verifyUiChange`, `getLastAnnotation`, `readScreenshot`.

- [ ] **Step 2: Create session token**

On debug runtime start, write:

```text
context.filesDir/pointpatch/session.json
```

with package name, socket name, random token, sidekick version, bridge protocol version, and process start time.

- [ ] **Step 3: Start LocalServerSocket**

Use `localabstract:pointpatch_<packageName>` naming. Reject requests with missing or mismatched token.

- [ ] **Step 4: Add bridge methods**

`status` returns activity, roots count, sidekick version, protocol version, source index availability. `startFeedbackCapture` activates the same in-app Smart Select flow as clipboard mode and waits until the user submits or timeout expires.

- [ ] **Step 5: Run bridge smoke test manually**

Run sample, then:

```bash
adb shell run-as io.github.pointpatch.sample cat files/pointpatch/session.json
```

Expected: JSON session file with token and socket name.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-compose-sidekick
git commit -m "sidekick: add local bridge protocol"
```

## Task 11: CLI

**Files:**
- Create: `pointpatch-cli/build.gradle.kts`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Main.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Adb.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/StatusCommand.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/RunCommand.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/DoctorCommand.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/SetupCommand.kt`

- [ ] **Step 1: Configure CLI module**

Use Kotlin JVM, application plugin, serialization, coroutines, and Clikt. Main class: `io.github.pointpatch.cli.MainKt`.

- [ ] **Step 2: Implement ADB wrapper**

Expose allowlisted operations only: `devices`, `shell`, `forward`, `install`, `monkey`, `runAsCat`, `pull`.

- [ ] **Step 3: Implement bridge client**

Read `.pointpatch/project.json` or accept `--package`. Read sidekick session via `adb shell run-as <package> cat files/pointpatch/session.json`, forward tcp to localabstract socket, frame JSON requests, validate protocol version.

- [ ] **Step 4: Implement commands**

Commands:

```bash
pointpatch status
pointpatch run
pointpatch doctor
pointpatch setup
pointpatch mcp
```

`pointpatch mcp` can delegate to the MCP module in Task 12.

- [ ] **Step 5: Add artifact pulling**

When bridge returns Android-local screenshot paths, CLI pulls files into:

```text
.pointpatch/artifacts/<annotation-id>/
```

and rewrites `desktopFullPath` / `desktopCropPath`.

- [ ] **Step 6: Run CLI status**

Run:

```bash
./gradlew :pointpatch-cli:installDist
./pointpatch-cli/build/install/pointpatch-cli/bin/pointpatch-cli status --package io.github.pointpatch.sample
```

Expected: reports device, package, app running, sidekick connected, current activity, roots count.

- [ ] **Step 7: Commit**

```bash
git add pointpatch-cli
git commit -m "cli: add adb bridge commands"
```

## Task 12: MCP Server

**Files:**
- Create: `pointpatch-mcp/build.gradle.kts`
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/McpServer.kt`
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/McpProtocol.kt`
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Test: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Configure MCP module**

Use Kotlin JVM, serialization, coroutines, and dependency on `:pointpatch-cli` bridge client APIs.

- [ ] **Step 2: Implement stdio JSON-RPC loop**

Read newline-delimited JSON-RPC messages from stdin. Write only JSON-RPC messages to stdout. Write diagnostics to stderr.

- [ ] **Step 3: Implement MCP lifecycle**

Support `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, and `ping`.

- [ ] **Step 4: Implement four tools**

Tools:

- `pointpatch_status`
- `pointpatch_get_current_screen`
- `pointpatch_get_ui_feedback`
- `pointpatch_verify_ui_change`

`pointpatch_get_ui_feedback` calls bridge `startFeedbackCapture`, waits for user selection/comment, pulls screenshots through CLI artifact logic, and returns both JSON annotation and Markdown.

- [ ] **Step 5: Add protocol tests**

Test initialize response, tools/list includes four tools, logs are not written to stdout, invalid params return JSON-RPC error.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :pointpatch-mcp:test
```

Expected: protocol tests pass.

- [ ] **Step 7: Commit**

```bash
git add pointpatch-mcp
git commit -m "mcp: expose PointPatch macro tools"
```

## Task 13: End-to-End QA and Docs

**Files:**
- Create: `README.md`
- Create: `docs/output-schema.md`
- Create: `docs/privacy.md`
- Create: `docs/troubleshooting.md`
- Create: `docs/mcp.md`
- Modify: `docs/pointpatch_prd.md`
- Modify: `docs/pointpatch_technical_design.md`
- Modify: `docs/pointpatch_decisions.md`

- [ ] **Step 1: Update docs with approved V1 Full decisions**

Add Smart Select, Compose sample replacement, required/optional schema fields, screenshot desktop artifact handling, MCP stdio logging rule, and bridge failure cases.

- [ ] **Step 2: Create README**

Include:

```text
Android Jetpack Compose only.
Debug builds only.
No required testTags.
No AccessibilityService.
MCP optional.
Source candidates are best-effort.
Screenshots may contain sensitive information.
```

- [ ] **Step 3: Create output schema doc**

Document required and optional annotation fields, selection kinds, confidence values, source candidate match reasons, screenshot path behavior, and error codes.

- [ ] **Step 4: Create privacy doc**

Document local-first behavior, no network permission in core sidekick, redaction defaults, screenshot limitations, cache storage, MCP/ADB local bridge behavior.

- [ ] **Step 5: Create troubleshooting doc**

Cover `ADB_NOT_FOUND`, `MULTIPLE_DEVICES`, `RUN_AS_FAILED`, `SIDEKICK_SESSION_NOT_FOUND`, `NO_COMPOSE_ROOT`, screenshot failures, MCP stdout log corruption.

- [ ] **Step 6: Run full build**

Run:

```bash
./gradlew clean build
```

Expected: all JVM tests and Android module builds pass.

- [ ] **Step 7: Run sample manual V1 flow**

Manual test:

```text
1. Install sample debug app.
2. Open Checkout.
3. Tap PointPatch.
4. Select Pay now.
5. Switch scope from Text to Button.
6. Enter "Change label to Pay immediately".
7. Copy Markdown.
8. Verify Markdown includes selection, user request, source candidates, screenshot path.
9. Run MCP get_ui_feedback and verify same UI flow returns annotation.
```

- [ ] **Step 8: Commit**

```bash
git add README.md docs
git commit -m "docs: document PointPatch v1 full workflow"
```

## Self-Review

Spec coverage:

- V1 Full scope is covered by Tasks 1 through 13.
- Compose sample conversion is covered by Task 2.
- Smart Select is covered by Tasks 4, 5, and 7.
- Output schema improvements are covered by Task 3 and Task 13.
- Screenshot desktop access is covered by Tasks 8 and 11.
- Bridge and MCP contracts are covered by Tasks 10 and 12.
- Compatibility and release safety are covered by Tasks 1, 6, 9, and 13.

No red-flag plan markers remain. Implementation-specific API adjustments must preserve the public contracts named in this plan.
