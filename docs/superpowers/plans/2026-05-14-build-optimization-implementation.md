# FixThis Build Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local Gradle builds faster and CI signals more accurate by enabling safe caching, cache-enabling the FixThis source-index task, reducing sidekick metadata recompilation, and making the compatibility matrix real.

**Architecture:** Keep low-risk Gradle defaults separate from configuration-cache rollout. Cache custom tasks only after declaring all inputs and adding stale-output tests. Move frequently changing sidekick build metadata from generated Kotlin into generated Android resources so metadata updates do not force Kotlin recompilation.

**Tech Stack:** Gradle 9.3.1, Android Gradle Plugin 9.1.1, Kotlin 2.2.21, JDK 21, Gradle TestKit / JUnit 4, Android resources, GitHub Actions, Node built-in test runner.

---

## File Structure

**Create:**
- `docs/superpowers/specs/2026-05-14-build-optimization-detailed-spec.md` - design and rationale for this work.
- `docs/superpowers/plans/2026-05-14-build-optimization-implementation.md` - this execution plan.
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfo.kt` - runtime provider that reads generated Android resources.
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfoTest.kt` - unit tests for provider fallback behavior.

**Modify:**
- `gradle.properties` - enable local build cache.
- `scripts/bootstrap-mcp.sh` - pass verified cache flags to the bootstrap Gradle command.
- `CONTRIBUTING.md` - document cache behavior and the reason configuration cache is still opt-in globally.
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt` - add cacheability and stale-output cleanup.
- `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt` - add cacheability and stale-output tests.
- `fixthis-compose-sidekick/build.gradle.kts` - generate build metadata resources instead of Kotlin source.
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt` - read sidekick build epoch from `SidekickBuildInfoProvider`.
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt` - stop importing generated `BuildInfo` directly in tests.
- `settings.gradle.kts` - wire root build version override properties.
- `fixthis-gradle-plugin/settings.gradle.kts` - wire included-build version override properties.
- `.github/workflows/nightly-compat.yml` - remove stale note and add Compose UI test override if needed.
- `docs/reference/compatibility.md` - document that override plumbing is active.
- `.github/workflows/ci.yml` - move cheap console checks before the long Gradle baseline.

---

## Task 1: Enable Safe Build Cache Defaults

**Files:**
- Modify: `gradle.properties`
- Modify: `scripts/bootstrap-mcp.sh`
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add local build cache to root Gradle properties**

Edit `gradle.properties` to this exact content:

```properties
# Project-wide Gradle settings.
# Specifies the JVM arguments used for the daemon process.
# The root multi-project build needs this file when invoked from the repository root.
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true

# Kotlin code style for this project: "official" or "obsolete":
kotlin.code.style=official
```

- [ ] **Step 2: Keep configuration cache opt-in in the properties file**

Run:

```bash
grep -n "configuration-cache" gradle.properties
```

Expected: no output. Do not add `org.gradle.configuration-cache=true` in this task.

- [ ] **Step 3: Add cache flags to bootstrap Gradle install**

In `scripts/bootstrap-mcp.sh`, replace:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
```

with:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist --build-cache --configuration-cache
```

- [ ] **Step 4: Document cache behavior in CONTRIBUTING**

Under `## Required Local Checks`, add this paragraph before the command block:

```markdown
The root build enables the local Gradle build cache by default. Configuration
cache is intentionally still opt-in because `spotlessCheck` does not reliably
reuse it yet; use `--configuration-cache` on focused loops such as
`:app:assembleDebug` or `:fixthis-mcp:installDist` after verifying the command
stores and reuses a cache entry.
```

- [ ] **Step 5: Verify bootstrap syntax**

Run:

```bash
bash -n scripts/bootstrap-mcp.sh
```

Expected: exit code 0 and no output.

- [ ] **Step 6: Verify focused cache reuse**

Run twice:

```bash
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
```

Expected second run includes:

```text
Configuration cache entry reused.
```

- [ ] **Step 7: Commit Task 1**

```bash
git add gradle.properties scripts/bootstrap-mcp.sh CONTRIBUTING.md
git commit -m "build: enable local Gradle build cache"
```

---

## Task 2: Make the Source Index Task Cacheable and Stale-Output Safe

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

- [ ] **Step 1: Add failing tests for cacheability and stale outputs**

In `GenerateFixThisSourceIndexTaskTest.kt`, add imports:

```kotlin
import org.gradle.api.tasks.CacheableTask
import org.junit.Assert.assertFalse
```

Add these tests above the existing `private fun runTask` helper:

```kotlin
@Test
fun `task is explicitly cacheable`() {
    assertTrue(
        "GenerateFixThisSourceIndexTask must be annotated with @CacheableTask so Gradle can reuse output bytes",
        GenerateFixThisSourceIndexTask::class.java.isAnnotationPresent(CacheableTask::class.java),
    )
}

@Test
fun `removes stale source index output when source index generation is disabled`() {
    val projectDir = temporaryFolder.newFolder("project")
    val sourceFile = projectDir.resolve("src/main/java/io/github/fixthis/sample/SampleApp.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(
        """
        package io.beyondwin.fixthis.sample

        import androidx.compose.material3.Text

        fun SampleApp() {
            Text("First")
        }
        """.trimIndent(),
    )
    val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

    runTask(
        projectDir = projectDir,
        kotlinSources = listOf(sourceFile),
        resourceXmlFiles = emptyList(),
        outputDir = outputDir,
    )
    val sourceIndex = outputDir.resolve("fixthis/fixthis-source-index.json")
    assertTrue(sourceIndex.isFile)

    runTask(
        projectDir = projectDir,
        kotlinSources = listOf(sourceFile),
        resourceXmlFiles = emptyList(),
        outputDir = outputDir,
        generateSourceIndex = false,
        generateProjectMetadata = true,
    )

    assertFalse("stale source index JSON must not remain after generation is disabled", sourceIndex.exists())
    assertTrue(outputDir.resolve("fixthis/fixthis-build-info.json").isFile)
}

@Test
fun `removes stale build info output when metadata generation is disabled`() {
    val projectDir = temporaryFolder.newFolder("project")
    val stringsFile = projectDir.resolve("src/main/res/values/strings.xml")
    stringsFile.parentFile.mkdirs()
    stringsFile.writeText(
        """
        <resources>
            <string name="app_name">FixThis</string>
        </resources>
        """.trimIndent(),
    )
    val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

    runTask(
        projectDir = projectDir,
        kotlinSources = emptyList(),
        resourceXmlFiles = listOf(stringsFile),
        outputDir = outputDir,
    )
    val buildInfo = outputDir.resolve("fixthis/fixthis-build-info.json")
    assertTrue(buildInfo.isFile)

    runTask(
        projectDir = projectDir,
        kotlinSources = emptyList(),
        resourceXmlFiles = listOf(stringsFile),
        outputDir = outputDir,
        generateSourceIndex = true,
        generateProjectMetadata = false,
    )

    assertTrue(outputDir.resolve("fixthis/fixthis-source-index.json").isFile)
    assertFalse("stale build info JSON must not remain after metadata generation is disabled", buildInfo.exists())
}
```

Update the helper signature and property wiring:

```kotlin
private fun runTask(
    projectDir: File,
    kotlinSources: List<File>,
    resourceXmlFiles: List<File>,
    outputDir: File,
    generateSourceIndex: Boolean = true,
    generateProjectMetadata: Boolean = true,
) {
    val project = ProjectBuilder.builder()
        .withProjectDir(projectDir)
        .build()
    val task = project.tasks.register(
        "generateFixThisSourceIndex",
        GenerateFixThisSourceIndexTask::class.java,
    ).get()
    task.projectDirectory.set(project.layout.projectDirectory)
    task.kotlinSourceFiles.from(kotlinSources)
    task.resourceXmlFiles.from(resourceXmlFiles)
    task.outputDirectory.set(outputDir)
    task.projectPath.set(":app")
    task.variantName.set("debug")
    task.runtimeVersion.set("0.1.0-test")
    task.includeScreenshots.set(true)
    task.redactEditableText.set(true)
    task.generateSourceIndex.set(generateSourceIndex)
    task.generateProjectMetadata.set(generateProjectMetadata)

    task.generate()
}
```

- [ ] **Step 2: Run tests and verify they fail for the expected reason**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest" --no-daemon
```

Expected: failure from `task is explicitly cacheable` because `@CacheableTask` is not present yet. The stale-output tests may also fail if stale files remain.

- [ ] **Step 3: Annotate the task and clear outputs before writing**

In `GenerateFixThisSourceIndexTask.kt`, add import:

```kotlin
import org.gradle.api.tasks.CacheableTask
```

Annotate the task:

```kotlin
@CacheableTask
abstract class GenerateFixThisSourceIndexTask : DefaultTask() {
```

Replace the first lines of `generate()` with:

```kotlin
@TaskAction
fun generate() {
    val outputRoot = outputDirectory.get().asFile
    if (outputRoot.exists()) {
        outputRoot.deleteRecursively()
    }
    val assetRoot = outputRoot.resolve("fixthis")
    assetRoot.mkdirs()
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest" --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run source-index task with build-cache diagnostics**

Run:

```bash
./gradlew :app:generateDebugFixThisSourceIndex --build-cache --info --no-daemon
./gradlew :app:generateDebugFixThisSourceIndex --build-cache --info --no-daemon
```

Expected: output does not contain:

```text
Caching disabled for task ':app:generateDebugFixThisSourceIndex' because:
  Caching has not been enabled for the task
```

- [ ] **Step 6: Commit Task 2**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt \
  fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt
git commit -m "build: cache FixThis source index generation"
```

---

## Task 3: Move Sidekick BuildInfo to Generated Android Resources

**Files:**
- Modify: `fixthis-compose-sidekick/build.gradle.kts`
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfo.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt`
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfoTest.kt`
- Modify: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt`

- [ ] **Step 1: Add runtime provider tests first**

Create `SidekickBuildInfoTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SidekickBuildInfoTest {
    @Test
    fun `provider returns parsed resource values`() {
        val provider = StaticSidekickBuildInfoProvider(
            buildEpochMs = "123456789",
            gitSha = "abc123",
        )

        assertEquals(
            SidekickBuildInfo(buildEpochMs = 123456789L, gitSha = "abc123"),
            provider.current(),
        )
    }

    @Test
    fun `provider falls back for malformed resource values`() {
        val provider = StaticSidekickBuildInfoProvider(
            buildEpochMs = "not-a-long",
            gitSha = "",
        )

        assertEquals(
            SidekickBuildInfo(buildEpochMs = 0L, gitSha = "unknown"),
            provider.current(),
        )
    }

    private class StaticSidekickBuildInfoProvider(
        private val buildEpochMs: String,
        private val gitSha: String,
    ) : SidekickBuildInfoProvider {
        override fun current(): SidekickBuildInfo = parseSidekickBuildInfo(
            buildEpochMs = buildEpochMs,
            gitSha = gitSha,
        )
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "io.beyondwin.fixthis.compose.sidekick.bridge.SidekickBuildInfoTest" --no-daemon
```

Expected: compile failure because `SidekickBuildInfo`, `SidekickBuildInfoProvider`, and `parseSidekickBuildInfo` do not exist yet.

- [ ] **Step 3: Add the provider implementation**

Create `SidekickBuildInfo.kt`:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

import android.content.Context
import io.beyondwin.fixthis.compose.sidekick.R

internal data class SidekickBuildInfo(
    val buildEpochMs: Long,
    val gitSha: String,
)

internal interface SidekickBuildInfoProvider {
    fun current(): SidekickBuildInfo
}

internal class AndroidResourceSidekickBuildInfoProvider(
    private val context: Context,
) : SidekickBuildInfoProvider {
    override fun current(): SidekickBuildInfo = parseSidekickBuildInfo(
        buildEpochMs = context.getString(R.string.fixthis_sidekick_build_epoch_ms),
        gitSha = context.getString(R.string.fixthis_sidekick_git_sha),
    )
}

internal fun parseSidekickBuildInfo(
    buildEpochMs: String,
    gitSha: String,
): SidekickBuildInfo = SidekickBuildInfo(
    buildEpochMs = buildEpochMs.toLongOrNull() ?: 0L,
    gitSha = gitSha.ifBlank { "unknown" },
)
```

- [ ] **Step 4: Update the sidekick Gradle task to generate resources**

In `fixthis-compose-sidekick/build.gradle.kts`, rename the task class to
`GenerateSidekickBuildInfoResourcesTask` and replace its output body with:

```kotlin
abstract class GenerateSidekickBuildInfoResourcesTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val gitSha: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildEpoch: org.gradle.api.provider.Property<Long>

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val target = outputDir.get().file("values/fixthis_build_info.xml").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="fixthis_sidekick_build_epoch_ms" translatable="false">${buildEpoch.get()}</string>
                <string name="fixthis_sidekick_git_sha" translatable="false">${gitSha.get()}</string>
            </resources>
            """.trimIndent(),
        )
    }
}
```

Update task registration:

```kotlin
val generateBuildInfoResources =
    tasks.register<GenerateSidekickBuildInfoResourcesTask>("generateBuildInfoResources") {
        outputDir.set(layout.buildDirectory.dir("generated/res/buildinfo/main"))
        gitSha.set(
            gitShortShaProvider.zip(gitStatusProvider) { sha, status ->
                if (status.isEmpty()) sha else "$sha-dirty"
            },
        )
        buildEpoch.set(
            gitCommitEpochProvider.zip(gitStatusProvider) { commitEpochSeconds, status ->
                if (status.isEmpty() && commitEpochSeconds.isNotBlank()) {
                    commitEpochSeconds.toLong() * 1000L
                } else {
                    System.currentTimeMillis()
                }
            },
        )
    }
```

Update `androidComponents` registration:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateBuildInfoResources,
            GenerateSidekickBuildInfoResourcesTask::outputDir,
        )
    }
}
```

Remove the old generated Kotlin source registration:

```kotlin
variant.sources.java?.addGeneratedSourceDirectory(
    generateBuildInfo,
    GenerateSidekickBuildInfoTask::outputDir,
)
```

- [ ] **Step 5: Wire AndroidBridgeEnvironment to the provider**

In `AndroidBridgeEnvironment.kt`, remove:

```kotlin
import io.beyondwin.fixthis.compose.sidekick.BuildInfo
```

Add constructor parameter:

```kotlin
private val buildInfoProvider: SidekickBuildInfoProvider = AndroidResourceSidekickBuildInfoProvider(context),
```

At the start of `status()`, after `val sourceIndexResult = readSourceIndex()`, add:

```kotlin
val buildInfo = buildInfoProvider.current()
```

Replace:

```kotlin
sidekickBuildEpochMs = BuildInfo.BUILD_EPOCH_MS,
```

with:

```kotlin
sidekickBuildEpochMs = buildInfo.buildEpochMs,
```

- [ ] **Step 6: Update BridgeServerTest direct BuildInfo references**

In `BridgeServerTest.kt`, remove:

```kotlin
import io.beyondwin.fixthis.compose.sidekick.BuildInfo
```

Replace the expected build epoch at the assertion site with the status value
from the bridge response. Use this pattern:

```kotlin
assertTrue(response.contains("\"sidekickBuildEpochMs\""))
```

Do not assert against generated `BuildInfo.BUILD_EPOCH_MS`; that class no
longer exists in the sidekick module.

- [ ] **Step 7: Run focused sidekick tests**

Run:

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Verify debug assemble avoids Kotlin recompile on repeated build**

Run twice:

```bash
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
```

Expected second run includes:

```text
Configuration cache entry reused.
```

Expected second run does not execute:

```text
:fixthis-compose-sidekick:compileDebugKotlin
:app:compileDebugKotlin
```

- [ ] **Step 9: Commit Task 3**

```bash
git add fixthis-compose-sidekick/build.gradle.kts \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfo.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SidekickBuildInfoTest.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "build: avoid sidekick metadata Kotlin recompilation"
```

---

## Task 4: Wire Compatibility Matrix Version Overrides

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `fixthis-gradle-plugin/settings.gradle.kts`
- Modify: `.github/workflows/nightly-compat.yml`
- Modify: `docs/reference/compatibility.md`

- [ ] **Step 1: Add root plugin version overrides**

At the top of `settings.gradle.kts`, before `pluginManagement`, add:

```kotlin
val overrideAgpVersion = providers.gradleProperty("overrideAgpVersion")
val overrideKotlinVersion = providers.gradleProperty("overrideKotlinVersion")
```

Inside `pluginManagement`, before `repositories`, add:

```kotlin
resolutionStrategy {
    eachPlugin {
        when (requested.id.id) {
            "com.android.application",
            "com.android.library",
            -> overrideAgpVersion.orNull?.let(::useVersion)
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.plugin.compose",
            "org.jetbrains.kotlin.plugin.serialization",
            -> overrideKotlinVersion.orNull?.let(::useVersion)
        }
    }
}
```

- [ ] **Step 2: Replace implicit root version catalog with explicit override-aware catalog**

Inside `dependencyResolutionManagement`, after `repositories`, add:

```kotlin
versionCatalogs {
    create("libs") {
        from(files("gradle/libs.versions.toml"))
        providers.gradleProperty("overrideAgpVersion").orNull?.let { version("agp", it) }
        providers.gradleProperty("overrideKotlinVersion").orNull?.let { version("kotlin", it) }
        providers.gradleProperty("overrideComposeBomVersion").orNull?.let { version("composeBom", it) }
        providers.gradleProperty("overrideComposeUiTestVersion").orNull?.let { version("composeUiTest", it) }
    }
}
```

- [ ] **Step 3: Mirror overrides in the included Gradle plugin build**

At the top of `fixthis-gradle-plugin/settings.gradle.kts`, before
`pluginManagement`, add:

```kotlin
val overrideAgpVersion = providers.gradleProperty("overrideAgpVersion")
val overrideKotlinVersion = providers.gradleProperty("overrideKotlinVersion")
```

Inside its `pluginManagement`, before `repositories`, add:

```kotlin
resolutionStrategy {
    eachPlugin {
        when (requested.id.id) {
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.plugin.serialization",
            -> overrideKotlinVersion.orNull?.let(::useVersion)
        }
    }
}
```

Replace the included build catalog block with:

```kotlin
versionCatalogs {
    create("libs") {
        from(files("../gradle/libs.versions.toml"))
        overrideAgpVersion.orNull?.let { version("agp", it) }
        overrideKotlinVersion.orNull?.let { version("kotlin", it) }
    }
}
```

- [ ] **Step 4: Update nightly compatibility workflow text and commands**

In `.github/workflows/nightly-compat.yml`, delete the stale comment block that
says the override properties are not wired.

Add one scheduled command for Compose UI test artifacts if the documented lower
bound must be exercised:

```yaml
run: ./gradlew -PoverrideComposeBomVersion=2026.01.00 -PoverrideComposeUiTestVersion=1.9.0 :app:assembleDebug --no-daemon
```

Keep `continue-on-error: true` for this informational workflow.

- [ ] **Step 5: Update compatibility docs**

In `docs/reference/compatibility.md`, replace the note under `## Scheduled validation`
with:

```markdown
The property override mechanism is active. `overrideAgpVersion` and
`overrideKotlinVersion` are applied through plugin resolution and the version
catalog. `overrideComposeBomVersion` and `overrideComposeUiTestVersion` are
applied through the root `libs` catalog before project build scripts resolve
dependencies.
```

- [ ] **Step 6: Verify normal pinned build still works**

Run:

```bash
./gradlew :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Verify each lower-bound override**

Run:

```bash
./gradlew -PoverrideAgpVersion=9.0.0 :app:assembleDebug --no-daemon
./gradlew -PoverrideKotlinVersion=2.2.0 :app:assembleDebug --no-daemon
./gradlew -PoverrideComposeBomVersion=2026.01.00 -PoverrideComposeUiTestVersion=1.9.0 :app:assembleDebug --no-daemon
```

Expected: each command reaches `BUILD SUCCESSFUL`. If AGP or Kotlin lower-bound
commands fail because the documented lower bound no longer compiles, update
`docs/reference/compatibility.md` in the same task to raise the lower bound to
the lowest version that passes locally.

- [ ] **Step 8: Commit Task 4**

```bash
git add settings.gradle.kts fixthis-gradle-plugin/settings.gradle.kts \
  .github/workflows/nightly-compat.yml docs/reference/compatibility.md
git commit -m "ci: wire compatibility matrix version overrides"
```

---

## Task 5: Move Cheap Console Checks Earlier in CI

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Reorder CI steps**

Move these two steps so they run immediately after `Make Gradle executable` and
before `Run Gradle verification`:

```yaml
      - name: Check console asset bundle
        run: node scripts/build-console-assets.mjs --check

      - name: Check console JavaScript syntax
        run: node --check fixthis-mcp/src/main/resources/console/app.js
```

Keep `Run console JavaScript tests` after the Gradle verification for now,
because those tests cover behavior and are less cheap than syntax / bundle
freshness.

- [ ] **Step 2: Run local console checks**

Run:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: both commands exit 0.

- [ ] **Step 3: Commit Task 5**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: fail fast on stale console assets"
```

---

## Task 6: Final Verification Sweep

**Files:**
- All files touched by Tasks 1 through 5.

- [ ] **Step 1: Run Gradle warning baseline**

Run:

```bash
./gradlew help --warning-mode all --no-daemon
```

Expected: `BUILD SUCCESSFUL`. If a Gradle 10 deprecation warning remains from
the detekt plugin, record it in the PR body as an upstream/plugin warning and do
not block this cache PR on a toolchain bump.

- [ ] **Step 2: Run required local Gradle checks**

Run:

```bash
./gradlew \
  spotlessCheck \
  detekt \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  :app:assembleDebug \
  :fixthis-cli:installDist \
  :fixthis-mcp:installDist \
  --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run console checks**

Run:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
```

Expected: all Node checks exit 0.

- [ ] **Step 4: Run cache-specific verification**

Run:

```bash
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :app:assembleDebug --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
./gradlew :fixthis-mcp:installDist --configuration-cache --build-cache --no-daemon
```

Expected: the second command in each pair includes:

```text
Configuration cache entry reused.
```

- [ ] **Step 5: Run diff hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0. `git status --short` shows only files
intentionally changed by this plan.

- [ ] **Step 6: Commit final verification note if docs changed during execution**

If the implementation updated this plan with measured timings, commit the doc
change:

```bash
git add docs/superpowers/plans/2026-05-14-build-optimization-implementation.md
git commit -m "docs: record build optimization verification"
```

---

## Self-Review Checklist

- [ ] Spec coverage: every goal in `2026-05-14-build-optimization-detailed-spec.md` maps to Task 1, 2, 3, 4, or 5.
- [ ] Placeholder scan: the plan contains no unfinished placeholder markers, no generic error-handling instruction, and no unowned file path.
- [ ] Type consistency: `SidekickBuildInfo`, `SidekickBuildInfoProvider`, and `parseSidekickBuildInfo` use the same names in tests and implementation.
- [ ] Verification commands: every changed subsystem has a focused command and a full-sweep command.
- [ ] Rollout safety: `org.gradle.configuration-cache=true` is not added until Spotless cache reuse is solved in a separate change.
