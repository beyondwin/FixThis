# FixThis Full Project Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the active PointPatch codebase and current product contracts to FixThis with `io.beyondwin.fixthis` packages and no compatibility aliases for old public names.

**Architecture:** Treat this as one breaking contract rename with checkpoints. First rename the Gradle graph, then move packages and identifiers, then update runtime persistence/bridge contracts, then update CLI/MCP contracts, and finally update current docs and audit historical leftovers.

**Tech Stack:** Android Gradle Plugin, Kotlin/JVM, Kotlin Android, Jetpack Compose, kotlinx.serialization, Clikt, local Android socket bridge, stdio MCP server, JUnit, Robolectric.

---

## Scope Check

The spec touches Gradle, Kotlin packages, runtime paths, CLI, MCP, docs, and tests. These are not independent shippable subsystems because they share compile-time imports and public naming contracts. Keep the implementation in one branch/worktree, but split it into small commits so failures are easy to isolate.

## File Structure Map

The implementation renames these active modules:

```text
pointpatch-compose-core           -> fixthis-compose-core
pointpatch-compose-overlay        -> fixthis-compose-overlay
pointpatch-compose-sidekick       -> fixthis-compose-sidekick
pointpatch-gradle-plugin          -> fixthis-gradle-plugin
pointpatch-cli                    -> fixthis-cli
pointpatch-mcp                    -> fixthis-mcp
```

The implementation moves Kotlin package roots in those modules:

```text
src/main/kotlin/io/github/pointpatch      -> src/main/kotlin/io/beyondwin/fixthis
src/test/kotlin/io/github/pointpatch      -> src/test/kotlin/io/beyondwin/fixthis
src/androidTest/kotlin/io/github/pointpatch -> src/androidTest/kotlin/io/beyondwin/fixthis
```

The sample app package stays:

```text
sample/src/main/java/io/beyondwin/fixthis/sample
sample/src/androidTest/java/io/beyondwin/fixthis/sample
```

Files with brand-bearing names must be renamed, not just edited:

```text
fixthis-compose-core/.../format/PointPatchJsonFormatter.kt                 -> FixThisJsonFormatter.kt
fixthis-compose-core/.../format/PointPatchMarkdownFormatter.kt             -> FixThisMarkdownFormatter.kt
fixthis-compose-core/.../format/PointPatchMarkdownFormatterTest.kt         -> FixThisMarkdownFormatterTest.kt
fixthis-compose-sidekick/.../PointPatch.kt                                 -> FixThis.kt
fixthis-compose-sidekick/.../init/PointPatchInitializer.kt                 -> FixThisInitializer.kt
fixthis-compose-sidekick/.../lifecycle/PointPatchActivityLifecycleCallbacks.kt -> FixThisActivityLifecycleCallbacks.kt
fixthis-compose-sidekick/.../overlay/PointPatchOverlayController.kt        -> FixThisOverlayController.kt
fixthis-compose-sidekick/.../overlay/PointPatchOverlayHostLayout.kt        -> FixThisOverlayHostLayout.kt
fixthis-compose-sidekick/.../PointPatchTest.kt                             -> FixThisTest.kt
fixthis-compose-sidekick/.../overlay/PointPatchOverlayControllerTest.kt    -> FixThisOverlayControllerTest.kt
fixthis-compose-sidekick/.../overlay/PointPatchOverlayHostLayoutTest.kt    -> FixThisOverlayHostLayoutTest.kt
fixthis-compose-overlay/.../overlay/PointPatchSelectionLayer.kt            -> FixThisSelectionLayer.kt
fixthis-compose-overlay/.../overlay/PointPatchHighlightLayer.kt            -> FixThisHighlightLayer.kt
fixthis-compose-overlay/.../overlay/PointPatchCommentSheet.kt              -> FixThisCommentSheet.kt
fixthis-compose-overlay/.../overlay/PointPatchToolbar.kt                   -> FixThisToolbar.kt
fixthis-compose-overlay/.../overlay/PointPatchDraftTest.kt                 -> FixThisDraftTest.kt
fixthis-gradle-plugin/.../PointPatchExtension.kt                           -> FixThisExtension.kt
fixthis-gradle-plugin/.../PointPatchGradlePlugin.kt                        -> FixThisGradlePlugin.kt
fixthis-gradle-plugin/.../task/GeneratePointPatchSourceIndexTask.kt        -> GenerateFixThisSourceIndexTask.kt
fixthis-gradle-plugin/.../GeneratePointPatchSourceIndexTaskTest.kt         -> GenerateFixThisSourceIndexTaskTest.kt
fixthis-gradle-plugin/.../PointPatchGradlePluginTest.kt                    -> FixThisGradlePluginTest.kt
fixthis-mcp/.../tools/PointPatchTools.kt                                   -> FixThisTools.kt
fixthis-mcp/.../session/FakePointPatchBridge.kt                            -> FakeFixThisBridge.kt
```

Current documentation files with product filenames should be renamed:

```text
docs/pointpatch_prd.md              -> docs/fixthis_prd.md
docs/pointpatch_technical_design.md -> docs/fixthis_technical_design.md
docs/pointpatch_decisions.md        -> docs/fixthis_decisions.md
```

## Task 1: Baseline and Rename Audit

**Files:**
- Read: `docs/superpowers/specs/2026-05-07-fixthis-full-rename-design.md`
- Read: `settings.gradle.kts`
- Read: `README.md`
- No source edits in this task.

- [x] **Step 1: Confirm the implementation starts clean**

Run:

```bash
git status --short
```

Expected: no output. If there is output, stop and inspect it before continuing.

- [x] **Step 2: Create the implementation branch when the current branch is `main`**

Run:

```bash
current_branch="$(git branch --show-current)"
if [ "$current_branch" = "main" ]; then
  git switch -c codex/fixthis-full-rename
else
  printf 'Using existing branch: %s\n' "$current_branch"
fi
```

Expected if starting from `main`: `Switched to a new branch 'codex/fixthis-full-rename'`.

- [x] **Step 3: Record current PointPatch hit count**

Run:

```bash
rg -n -i "pointpatch" -g '!**/build/**' -g '!**/.gradle/**' | wc -l
```

Expected: a non-zero count. This is the baseline that later tasks reduce to migration notes and historical docs only.

- [x] **Step 4: Run baseline tests that will protect the rename**

Run:

```bash
./gradlew :pointpatch-gradle-plugin:test :pointpatch-cli:test :pointpatch-mcp:test :pointpatch-compose-core:test :pointpatch-compose-sidekick:test
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit the baseline checkpoint only if a branch was created**

Run:

```bash
git status --short
```

Expected: no output. Do not create an empty commit.

## Task 2: Rename the Gradle Graph and Module Directories

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `sample/build.gradle.kts`
- Modify: `fixthis-compose-core/build.gradle.kts`
- Modify: `fixthis-compose-overlay/build.gradle.kts`
- Modify: `fixthis-compose-sidekick/build.gradle.kts`
- Modify: `fixthis-cli/build.gradle.kts`
- Modify: `fixthis-mcp/build.gradle.kts`
- Modify: `fixthis-gradle-plugin/settings.gradle.kts`
- Modify: `fixthis-gradle-plugin/build.gradle.kts`

- [x] **Step 1: Rename module directories**

Run:

```bash
git mv pointpatch-compose-core fixthis-compose-core
git mv pointpatch-compose-overlay fixthis-compose-overlay
git mv pointpatch-compose-sidekick fixthis-compose-sidekick
git mv pointpatch-gradle-plugin fixthis-gradle-plugin
git mv pointpatch-cli fixthis-cli
git mv pointpatch-mcp fixthis-mcp
```

Expected: no command output and `git status --short` shows six rename entries.

- [x] **Step 2: Update `settings.gradle.kts`**

Replace the full file with:

```kotlin
pluginManagement {
    includeBuild("fixthis-gradle-plugin") {
        name = "fixthis-gradle-plugin"
    }

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

rootProject.name = "FixThis"

include(":app")
project(":app").projectDir = file("sample")
include(":fixthis-compose-core")
include(":fixthis-compose-overlay")
include(":fixthis-compose-sidekick")
include(":fixthis-cli")
include(":fixthis-mcp")
```

- [x] **Step 3: Update project dependency paths**

Run:

```bash
rg --files -0 -g '*.gradle.kts' -g 'settings.gradle.kts' |
  xargs -0 perl -pi -e '
    s/pointpatch-compose-core/fixthis-compose-core/g;
    s/pointpatch-compose-overlay/fixthis-compose-overlay/g;
    s/pointpatch-compose-sidekick/fixthis-compose-sidekick/g;
    s/pointpatch-gradle-plugin/fixthis-gradle-plugin/g;
    s/pointpatch-cli/fixthis-cli/g;
    s/pointpatch-mcp/fixthis-mcp/g;
    s/PointPatch/FixThis/g;
  '
```

Expected: no command output.

- [x] **Step 4: Remove the old secondary CLI launcher block**

Edit `fixthis-cli/build.gradle.kts` so the full file is:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.beyondwin.fixthis.cli.MainKt")
    applicationName = "fixthis"
}

dependencies {
    implementation(project(":fixthis-compose-core"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

- [x] **Step 5: Update MCP distribution build file**

Edit `fixthis-mcp/build.gradle.kts` so the `application` and dependencies blocks read:

```kotlin
application {
    mainClass.set("io.beyondwin.fixthis.mcp.McpServerKt")
    applicationName = "fixthis-mcp"
}

dependencies {
    implementation(project(":fixthis-cli"))
    implementation(project(":fixthis-compose-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
```

- [x] **Step 6: Update plugin build identity**

Edit `fixthis-gradle-plugin/settings.gradle.kts` so it contains:

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

rootProject.name = "fixthis-gradle-plugin-build"
```

Then edit `fixthis-gradle-plugin/build.gradle.kts` so the Gradle plugin block reads:

```kotlin
gradlePlugin {
    plugins {
        create("fixThisCompose") {
            id = "io.beyondwin.fixthis.compose"
            implementationClass = "io.beyondwin.fixthis.gradle.FixThisGradlePlugin"
        }
    }
}
```

- [x] **Step 7: Update sample plugin id**

Edit the plugin block in `sample/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("io.beyondwin.fixthis.compose")
}
```

- [x] **Step 8: Verify Gradle sees the new projects**

Run:

```bash
./gradlew projects
```

Expected: output lists `:fixthis-cli`, `:fixthis-compose-core`, `:fixthis-compose-overlay`, `:fixthis-compose-sidekick`, `:fixthis-mcp`, and no `:pointpatch-*` project names.

- [x] **Step 9: Commit Gradle graph rename**

Run:

```bash
git add settings.gradle.kts sample/build.gradle.kts fixthis-* 
git commit -m "chore: rename gradle modules to fixthis"
```

Expected: commit succeeds.

## Task 3: Move Kotlin Packages and Rename Brand-Bearing Files

**Files:**
- Move package directories under all `fixthis-*` modules.
- Rename brand-bearing files listed in the File Structure Map.
- Modify all Kotlin package declarations and imports in `fixthis-*`.

- [x] **Step 1: Move Kotlin package roots**

Run:

```bash
for module in fixthis-compose-core fixthis-compose-overlay fixthis-compose-sidekick fixthis-gradle-plugin fixthis-cli fixthis-mcp; do
  for source_set in src/main/kotlin src/test/kotlin src/androidTest/kotlin; do
    old="$module/$source_set/io/github/pointpatch"
    new_parent="$module/$source_set/io/beyondwin"
    if [ -d "$old" ]; then
      mkdir -p "$new_parent"
      git mv "$old" "$new_parent/fixthis"
      rmdir -p "$module/$source_set/io/github" 2>/dev/null || true
    fi
  done
done
```

Expected: package roots move under `io/beyondwin/fixthis`.

- [x] **Step 2: Apply mechanical package and identifier replacements in active code**

Run:

```bash
rg --files -0 fixthis-* sample -g '*.kt' -g '*.kts' -g '*.xml' -g '*.json' -g '*.properties' |
  xargs -0 perl -pi -e '
    s/io\.github\.pointpatch/io.beyondwin.fixthis/g;
    s/PointPatch/FixThis/g;
    s/pointPatch/fixThis/g;
    s/pointpatch/fixthis/g;
  '
```

Expected: no command output.

- [x] **Step 3: Rename brand-bearing Kotlin files**

Run:

```bash
git mv fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/PointPatchJsonFormatter.kt fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisJsonFormatter.kt
git mv fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/PointPatchMarkdownFormatter.kt fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt
git mv fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/PointPatchMarkdownFormatterTest.kt fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt
git mv fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/PointPatch.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThis.kt
git mv fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/init/PointPatchInitializer.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/init/FixThisInitializer.kt
git mv fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/PointPatchActivityLifecycleCallbacks.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacks.kt
git mv fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/PointPatchOverlayController.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt
git mv fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/PointPatchOverlayHostLayout.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayHostLayout.kt
git mv fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/PointPatchTest.kt fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThisTest.kt
git mv fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/PointPatchOverlayControllerTest.kt fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayControllerTest.kt
git mv fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/PointPatchOverlayHostLayoutTest.kt fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayHostLayoutTest.kt
git mv fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/PointPatchSelectionLayer.kt fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/FixThisSelectionLayer.kt
git mv fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/PointPatchHighlightLayer.kt fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/FixThisHighlightLayer.kt
git mv fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/PointPatchCommentSheet.kt fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/FixThisCommentSheet.kt
git mv fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/PointPatchToolbar.kt fixthis-compose-overlay/src/main/kotlin/io/beyondwin/fixthis/compose/overlay/FixThisToolbar.kt
git mv fixthis-compose-overlay/src/test/kotlin/io/beyondwin/fixthis/compose/overlay/PointPatchDraftTest.kt fixthis-compose-overlay/src/test/kotlin/io/beyondwin/fixthis/compose/overlay/FixThisDraftTest.kt
git mv fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/PointPatchExtension.kt fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisExtension.kt
git mv fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/PointPatchGradlePlugin.kt fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt
git mv fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GeneratePointPatchSourceIndexTask.kt fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt
git mv fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GeneratePointPatchSourceIndexTaskTest.kt fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt
git mv fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/PointPatchGradlePluginTest.kt fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePluginTest.kt
git mv fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/PointPatchTools.kt fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt
git mv fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakePointPatchBridge.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt
```

Expected: no command output.

- [x] **Step 4: Fix any remaining filename misses**

Run:

```bash
find fixthis-* sample \( -name '*PointPatch*' -o -name '*pointpatch*' \) -print
```

Expected: no output. If output appears, rename each active source/test file with `git mv` and rerun this command.

- [x] **Step 5: Run compile-focused tests**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-gradle-plugin:test
```

Expected: compilation may fail only for stale generated class names. Fix stale names by replacing old imports/usages with the names created by Step 3, then rerun until `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit package and identifier rename**

Run:

```bash
git add fixthis-* sample
git commit -m "refactor: move active code to fixthis packages"
```

Expected: commit succeeds.

## Task 4: Update Runtime Persistence, Bridge, and Generated Asset Contracts

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SessionTokenStore.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/screenshot/ScreenshotStore.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SessionTokenStoreTest.kt`
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/screenshot/ScreenshotStoreTest.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

- [x] **Step 1: Verify the mechanical replacements changed runtime names**

Run:

```bash
rg -n -S "files/pointpatch|cache/pointpatch|pointpatch_|pointpatch/pointpatch|generated/pointpatch|PointPatch|pointPatch|pointpatch" \
  fixthis-compose-sidekick fixthis-gradle-plugin \
  -g '!**/build/**' -g '!**/.gradle/**'
```

Expected after Task 3: no active old-name matches. If matches remain in active code or tests, replace them with:

```text
files/fixthis
cache/fixthis
fixthis_
fixthis/fixthis
generated/fixthis
FixThis
fixThis
fixthis
```

- [x] **Step 2: Ensure `SessionTokenStore` final contract is FixThis**

Confirm `SessionTokenStore.kt` contains these exact contract snippets:

```kotlin
val directory = File(context.filesDir, "fixthis")
```

```kotlin
fun socketNameForPackage(packageName: String): String = "fixthis_$packageName"
```

```kotlin
const val FixThisSidekickVersion: String = "0.1.0"
```

Expected: the old `PointPatchSidekickVersion` symbol is gone and callers use `FixThisSidekickVersion`.

- [x] **Step 3: Ensure screenshot cache contract is FixThis**

Confirm `ScreenshotStore.kt` contains:

```kotlin
File(context.cacheDir, "fixthis/${dateProvider()}").also { directory ->
```

Confirm `BridgeServer.kt` contains:

```kotlin
override fun screenshotCacheDirectory(): File = File(context.cacheDir, "fixthis")
```

- [x] **Step 4: Ensure generated asset contract is FixThis**

Confirm `FixThisGradlePlugin.kt` registers:

```kotlin
"generate${variant.name.capitalized()}FixThisSourceIndex"
```

and writes generated assets to:

```kotlin
project.layout.buildDirectory.dir("generated/fixthis/${variant.name}/assets")
```

Confirm `GenerateFixThisSourceIndexTask.kt` writes:

```kotlin
val assetRoot = outputDirectory.get().asFile.resolve("fixthis")
```

and its build-info defaults are:

```kotlin
val sourceIndexAsset: String = "fixthis/fixthis-source-index.json",
val buildInfoAsset: String = "fixthis/fixthis-build-info.json",
```

- [x] **Step 5: Run runtime and Gradle plugin tests**

Run:

```bash
./gradlew :fixthis-compose-sidekick:test --tests io.beyondwin.fixthis.compose.sidekick.bridge.SessionTokenStoreTest --tests io.beyondwin.fixthis.compose.sidekick.screenshot.ScreenshotStoreTest
./gradlew :fixthis-gradle-plugin:test --tests io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit runtime contract rename**

Run:

```bash
git add fixthis-compose-sidekick fixthis-gradle-plugin
git commit -m "refactor: rename runtime contracts to fixthis"
```

Expected: commit succeeds.

## Task 5: Update CLI and MCP Public Contracts

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Main.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ConsoleCommand.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/McpServer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/McpProtocol.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/*`
- Test: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/McpCommandTest.kt`
- Test: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/BridgeClientTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpServerTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/*`

- [x] **Step 1: Confirm CLI main name is FixThis**

Confirm `Main.kt` contains:

```kotlin
val command = CoreNoOpCliktCommand(name = "fixthis")
```

- [x] **Step 2: Confirm setup config emits the new command**

Confirm `SetupCommand.kt` contains:

```kotlin
put("command", "fixthis")
```

and still emits args:

```kotlin
buildJsonArray {
    add(JsonPrimitive("mcp"))
    add(JsonPrimitive("--package"))
    add(JsonPrimitive(resolvedPackage))
    add(JsonPrimitive("--project-dir"))
    add(JsonPrimitive(root.absolutePath))
}
```

- [x] **Step 3: Confirm MCP executable lookup uses `fixthis-mcp`**

Confirm `McpExecutableLocator.executableName()` returns:

```kotlin
if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
    "fixthis-mcp.bat"
} else {
    "fixthis-mcp"
}
```

Confirm sibling and project install candidates resolve `fixthis-mcp`.

- [x] **Step 4: Confirm bridge and local artifact contracts use FixThis**

Confirm `BridgeClient.kt` contains:

```kotlin
private const val SessionPath = "files/fixthis/session.json"
```

and artifact storage:

```kotlin
val artifactDirectory = projectRoot.resolve(".fixthis/artifacts/$artifactId")
```

Confirm user-facing errors say `FixThis bridge`.

- [x] **Step 5: Confirm MCP server name and tools use FixThis**

Confirm `McpProtocolTest` expected server name is:

```kotlin
assertEquals("fixthis-mcp", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
```

Confirm `FixThisTools.kt` dispatches these tool names:

```kotlin
fixthis_status
fixthis_get_current_screen
fixthis_get_ui_feedback
fixthis_verify_ui_change
fixthis_open_feedback_console
fixthis_list_feedback_sessions
fixthis_capture_screen
fixthis_navigate_app
fixthis_list_feedback
fixthis_read_feedback
fixthis_resolve_feedback
```

Confirm resources use:

```text
fixthis://session/current
fixthis://screen/current
fixthis://annotation/latest
fixthis://screenshot/latest/full.png
fixthis://screenshot/latest/crop.png
fixthis://source-index
```

- [x] **Step 6: Run CLI and MCP tests**

Run:

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Build distributions and verify commands**

Run:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis --help
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample
```

Expected:

```text
Usage: fixthis
```

and setup JSON with `"command": "fixthis"` and package `io.beyondwin.fixthis.sample`.

- [x] **Step 8: Commit CLI and MCP rename**

Run:

```bash
git add fixthis-cli fixthis-mcp
git commit -m "refactor: rename cli and mcp contracts to fixthis"
```

Expected: commit succeeds.

## Task 6: Update Current Docs, Ignore Rules, and Migration Note

**Files:**
- Modify: `.gitignore`
- Modify: `README.md`
- Rename: `docs/pointpatch_prd.md` -> `docs/fixthis_prd.md`
- Rename: `docs/pointpatch_technical_design.md` -> `docs/fixthis_technical_design.md`
- Rename: `docs/pointpatch_decisions.md` -> `docs/fixthis_decisions.md`
- Modify: `docs/project-overview.md`
- Modify: `docs/mcp.md`
- Modify: `docs/troubleshooting.md`
- Modify: `docs/privacy.md`
- Modify: `docs/output-schema.md`
- Modify: `docs/adr/*.md`

- [x] **Step 1: Rename current product docs**

Run:

```bash
git mv docs/pointpatch_prd.md docs/fixthis_prd.md
git mv docs/pointpatch_technical_design.md docs/fixthis_technical_design.md
git mv docs/pointpatch_decisions.md docs/fixthis_decisions.md
```

Expected: no command output.

- [x] **Step 2: Apply mechanical documentation rename to current docs only**

Run:

```bash
rg --files -0 README.md docs .gitignore \
  -g '*.md' -g '.gitignore' \
  -g '!docs/superpowers/**' |
  xargs -0 perl -pi -e '
    s/io\.github\.pointpatch/io.beyondwin.fixthis/g;
    s/PointPatch/FixThis/g;
    s/pointPatch/fixThis/g;
    s/pointpatch/fixthis/g;
  '
```

Expected: no command output.

- [x] **Step 3: Ensure `.gitignore` ignores FixThis local artifacts**

Confirm `.gitignore` contains:

```gitignore
# FixThis local capture artifacts
.fixthis/artifacts/
.fixthis/feedback-sessions/

# OS
.DS_Store
.superpowers/brainstorm
.fixthis
.playwright-cli
```

Expected: no `.pointpatch` ignore entries remain in `.gitignore`.

- [x] **Step 4: Add migration note to README**

Add this short note near the top of `README.md`, after the opening paragraph:

```markdown
> Migration note: FixThis was previously named PointPatch. This repository uses
> the new `fixthis` CLI, `io.beyondwin.fixthis.*` packages, `fixthis_*` MCP
> tools, and `.fixthis/` local storage paths. Old PointPatch public contracts are
> not preserved in this breaking rename.
```

- [x] **Step 5: Ensure README commands use FixThis names**

Confirm README examples include:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

and:

```bash
fixthis doctor
fixthis run
fixthis setup --package <applicationId>
fixthis console --package <applicationId>
```

- [x] **Step 6: Ensure README links target renamed docs**

Confirm README links include:

```markdown
- [Product requirements](docs/fixthis_prd.md)
- [Technical design](docs/fixthis_technical_design.md)
- [Decisions](docs/fixthis_decisions.md)
```

- [x] **Step 7: Commit docs rename**

Run:

```bash
git add .gitignore README.md docs
git commit -m "docs: rename current documentation to fixthis"
```

Expected: commit succeeds.

## Task 7: Run Full Verification and Classify Remaining Old Names

**Files:**
- Modify only files with unclassified old-name hits from the audit.
- Historical docs under `docs/superpowers/specs/` and `docs/superpowers/plans/` may retain PointPatch wording if they describe past work.

- [x] **Step 1: Run full JVM/unit verification**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 2: Build the sample app**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Build distributions**

Run:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Verify CLI executable**

Run:

```bash
fixthis-cli/build/install/fixthis/bin/fixthis --help
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample
```

Expected: help says `Usage: fixthis`; setup JSON says `"command": "fixthis"`.

- [x] **Step 5: Run connected Android tests if a device is available**

Run:

```bash
adb devices | sed -n '2,$p'
```

If at least one line has state `device`, run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected when a device is available: `BUILD SUCCESSFUL`. If no device is available, record `connectedDebugAndroidTest not run: no connected Android device/emulator`.

Result recorded for this pass: `adb` was not on `PATH`; the SDK `adb` found a
connected Samsung device, but `./gradlew :app:connectedDebugAndroidTest` did not
pass because the device was locked/keyguarded and the sample app moved from
`RESUMED` to `PAUSED`/`STOPPED` with `lockNow pending`. Treat this as an
environment residual risk, not evidence of a FixThis rename regression.

- [x] **Step 6: Audit remaining PointPatch names**

Run:

```bash
rg -n -i "pointpatch" -g '!**/build/**' -g '!**/.gradle/**'
```

Expected: remaining matches are limited to:

```text
docs/superpowers/specs/...
docs/superpowers/plans/...
docs/superpowers/specs/2026-05-07-fixthis-full-rename-design.md
docs/superpowers/plans/2026-05-07-fixthis-full-rename-implementation.md
```

If any active source, build file, README, current doc, test, fixture, `.gitignore`, or resource file appears, update it to FixThis and rerun the relevant test from the earlier task.

- [x] **Step 7: Commit final audit fixes**

Run:

```bash
git status --short
```

If there are audit fixes:

```bash
git add .
git commit -m "chore: finish fixthis rename audit"
```

Expected: either no output from `git status --short`, or a successful final audit commit.

## Task 8: Final Integration Report

**Files:**
- No source edits unless verification in Task 7 exposed a missed active rename.

- [ ] **Step 1: Summarize commits**

Run:

```bash
git log --oneline --decorate -n 8
```

Expected: recent commits include Gradle graph rename, package rename, runtime contract rename, CLI/MCP rename, docs rename, and any final audit commit.

- [ ] **Step 2: Confirm working tree is clean**

Run:

```bash
git status --short
```

Expected: no output.

- [ ] **Step 3: Prepare final implementation notes**

Include these points in the final response:

```text
- Renamed modules to fixthis-*.
- Moved active packages to io.beyondwin.fixthis.*.
- Renamed CLI/MCP contracts to fixthis.
- Renamed runtime storage to .fixthis, files/fixthis, and cache/fixthis.
- Updated current docs and added the migration note.
- Reported verification commands and whether connected Android tests ran.
- Reported remaining pointpatch audit hits, limited to historical specs/plans and migration notes.
```
