# Source Index Root Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis source candidates resolve correctly in repository-root MCP sessions, including multi-module Android projects such as the bundled `sample/` app.

**Architecture:** Add additive source-root metadata to the source index, copy the host-resolvable path into persisted source candidates, and centralize MCP host path resolution in one resolver. Staleness checks, freshness checks, and compact handoff rendering will all use the same resolved path semantics.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Gradle TestKit-style project fixtures via `ProjectBuilder`, JUnit 4/kotlin-test, FixThis MCP session models.

---

## File Structure

- Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
  - Owns source-index wire models. Add `SourceRoot` and `repoFile`.
- Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
  - Owns persisted `SourceCandidate`. Add nullable `repoFile` so Copy Prompt can render host paths without re-reading the source index.
- Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
  - Owns domain source hints. Add nullable `repoFile` so mapping does not drop the path.
- Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
  - Copies `SourceIndexEntry.repoFile` into `SourceCandidate.repoFile`.
- Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
  - Preserves `repoFile` through domain/session mapping.
- Modify `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndexTest.kt`
  - Covers v1 compatibility and v1.1 root metadata decoding.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
  - Mirrors core source-index fields for generated assets.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
  - Builds entries with both module-relative `file` and repo-relative `repoFile`.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt`
  - Same path handling for XML resources.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
  - Accepts module/root directories and emits `sourceRoot`.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
  - Adds `rootProjectDirectory` input and passes it to the generator.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt`
  - Wires `rootProjectDirectory`.
- Modify `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`
  - Covers module and root-project path generation.
- Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolver.kt`
  - Central source path resolver for MCP host files.
- Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolverTest.kt`
  - Covers resolver order, escapes, and ambiguous suffix fallback.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt`
  - Uses resolver instead of `File(projectRoot, candidate.file)`.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`
  - Covers module-relative candidate resolution.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`
  - Uses resolver for exists/newer checks and sample paths.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`
  - Covers no false project-root misconfiguration for module paths.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`
  - Uses candidate display paths for compact source-root shortening.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Covers Copy Prompt rendering of `sample/src/main/...`.
- Modify `docs/reference/output-schema.md`
  - Documents `sourceRoot`, `repoFile`, and updated stale diagnostics.
- Modify `docs/guides/troubleshooting.md`
  - Updates the `projectRoot may be misconfigured` guidance.

## Task 1: Core Source Models

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndexTest.kt`

- [ ] **Step 1: Add failing source-index model test**

Append this test to `SourceIndexTest`:

```kotlin
@Test
fun decodesV11RootMetadataAndRepoFile() {
    val index = json.decodeFromString<SourceIndex>(
        """
        {
          "schemaVersion": "1.1",
          "sourceRoot": {
            "kind": "gradle-project",
            "gradlePath": ":app",
            "projectDir": "sample"
          },
          "entries": [
            {
              "file": "src/main/java/Sample.kt",
              "repoFile": "sample/src/main/java/Sample.kt",
              "line": 7
            }
          ]
        }
        """.trimIndent(),
    )

    assertEquals("1.1", index.schemaVersion)
    assertEquals("gradle-project", index.sourceRoot?.kind)
    assertEquals(":app", index.sourceRoot?.gradlePath)
    assertEquals("sample", index.sourceRoot?.projectDir)
    assertEquals("sample/src/main/java/Sample.kt", index.entries.single().repoFile)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "io.beyondwin.fixthis.compose.core.source.SourceIndexTest.decodesV11RootMetadataAndRepoFile"
```

Expected: compilation fails because `sourceRoot` and `repoFile` do not exist.

- [ ] **Step 3: Add source-index fields**

Update `SourceIndex.kt`:

```kotlin
@Serializable
data class SourceIndex(
    val schemaVersion: String = "1.0",
    val sourceRoot: SourceRoot? = null,
    val entries: List<SourceIndexEntry> = emptyList(),
)

@Serializable
data class SourceRoot(
    val kind: String = "gradle-project",
    val gradlePath: String? = null,
    val projectDir: String? = null,
)

@Serializable
data class SourceIndexEntry(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null,
    val signals: List<SourceSignal> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)
```

- [ ] **Step 4: Add candidate repoFile and matcher propagation**

Update `SourceCandidate` in `Models.kt`:

```kotlin
@Serializable
data class SourceCandidate(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SelectionConfidence,
    val ranking: Int? = null,
    val scoreMargin: Double? = null,
    val evidenceStrength: SourceEvidenceStrength? = null,
    val riskFlags: List<SourceCandidateRisk> = emptyList(),
    val caution: String? = null,
    val stale: Boolean? = null,
    val staleReason: String? = null,
)
```

Update `SourceHint` in `SourceHint.kt`:

```kotlin
data class SourceHint(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SourceHintConfidence,
    val ranking: Int? = null,
    val scoreMargin: Double? = null,
    val evidenceStrength: SourceHintStrength? = null,
    val riskFlags: List<SourceHintRisk> = emptyList(),
    val caution: String? = null,
    val stale: Boolean? = null,
    val staleReason: String? = null,
)
```

Update the `SourceCandidate(` construction in `SourceMatcher.kt`:

```kotlin
return SourceCandidate(
    file = entry.file,
    repoFile = entry.repoFile,
    line = entry.line,
    score = (rawScore / SourceScoringPolicy.highConfidenceScore).coerceIn(0.0, 1.0),
    matchedTerms = matchedTerms,
    matchReasons = wireReasons,
    confidence = afterAmbiguity,
    ranking = margin.ranking,
    scoreMargin = scoreMargin,
    evidenceStrength = profile.strength(),
    riskFlags = flags,
    caution = caution,
)
```

Update both conversion functions in `SourceCandidateMappers.kt` so `repoFile` is copied in both directions:

```kotlin
repoFile = repoFile,
```

- [ ] **Step 5: Run core tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "io.beyondwin.fixthis.compose.core.source.SourceIndexTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndex.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndexTest.kt
git commit -m "feat: add source index root metadata models"
```

## Task 2: Gradle Source Index Generation

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

- [ ] **Step 1: Add failing Gradle generation test**

Add a test that uses a fake repository root with a `sample/` module:

```kotlin
@Test
fun `writes source root and repo relative source paths for module project`() {
    val rootDir = temporaryFolder.newFolder("repo")
    val projectDir = rootDir.resolve("sample")
    val sourceFile = projectDir.resolve("src/main/java/io/example/Sample.kt")
    sourceFile.parentFile.mkdirs()
    sourceFile.writeText(
        """
        package io.example

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        @Composable
        fun Sample() {
            Text("Hello")
        }
        """.trimIndent(),
    )
    val outputDir = projectDir.resolve("build/generated/fixthis/debug/assets")

    runTask(
        projectDir = projectDir,
        rootProjectDir = rootDir,
        kotlinSources = listOf(sourceFile),
        resourceXmlFiles = emptyList(),
        outputDir = outputDir,
    )

    val index = Json.parseToJsonElement(
        outputDir.resolve("fixthis/fixthis-source-index.json").readText(),
    ).jsonObject
    val sourceRoot = index.getValue("sourceRoot").jsonObject
    val entry = index.getValue("entries").jsonArray.single().jsonObject

    assertEquals("1.1", index.getValue("schemaVersion").jsonPrimitive.content)
    assertEquals("gradle-project", sourceRoot.getValue("kind").jsonPrimitive.content)
    assertEquals(":app", sourceRoot.getValue("gradlePath").jsonPrimitive.content)
    assertEquals("sample", sourceRoot.getValue("projectDir").jsonPrimitive.content)
    assertEquals("src/main/java/io/example/Sample.kt", entry.getValue("file").jsonPrimitive.content)
    assertEquals("sample/src/main/java/io/example/Sample.kt", entry.getValue("repoFile").jsonPrimitive.content)
}
```

Update the local `runTask` helper signature to accept:

```kotlin
rootProjectDir: File = projectDir,
projectPath: String = ":app",
```

and set `task.rootProjectDirectory.set(rootProjectDir)`.

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest.writes source root and repo relative source paths for module project"
```

Expected: compilation fails because `rootProjectDirectory`, `sourceRoot`, and `repoFile` do not exist.

- [ ] **Step 3: Add asset models**

Update `SourceIndexAssets.kt`:

```kotlin
@Serializable
internal data class SourceIndexAsset(
    val schemaVersion: String = "1.1",
    val sourceRoot: SourceRootAsset? = null,
    val entries: List<SourceIndexEntryAsset> = emptyList(),
)

@Serializable
internal data class SourceRootAsset(
    val kind: String = "gradle-project",
    val gradlePath: String? = null,
    val projectDir: String? = null,
)

@Serializable
internal data class SourceIndexEntryAsset(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null,
    val signals: List<SourceSignalAsset> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)
```

Add `repoFile: String? = null` to `SourceIndexEntryBuilder` and pass it to `SourceIndexEntryAsset`.

- [ ] **Step 4: Generate repoFile in scanners**

Change scanner constructors to accept both roots:

```kotlin
private val projectDirectory: File,
private val rootProjectDirectory: File,
```

When creating `SourceIndexEntryBuilder`, compute:

```kotlin
val moduleFile = file.relativeToOrSelf(projectDirectory).invariantSeparatorsPath
val repoFile = file.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
```

and set:

```kotlin
file = moduleFile,
repoFile = repoFile,
```

Apply the same path calculation in `KotlinSourceScanner` and `XmlStringResourceScanner`.

- [ ] **Step 5: Emit sourceRoot from generator**

Update `SourceIndexGenerator`:

```kotlin
internal class SourceIndexGenerator(
    private val projectDirectory: File,
    private val rootProjectDirectory: File,
    private val projectPath: String,
    json: Json,
) {
    private val kotlinScanner = KotlinSourceScanner(projectDirectory, rootProjectDirectory, json)
    private val xmlScanner = XmlStringResourceScanner(projectDirectory, rootProjectDirectory)

    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset = SourceIndexAsset(
        sourceRoot = SourceRootAsset(
            gradlePath = projectPath,
            projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                .takeUnless { it == "." },
        ),
        entries = buildList {
            kotlinFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(kotlinScanner.scan(it)) }
            xmlFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(xmlScanner.scan(it)) }
        },
    )
}
```

- [ ] **Step 6: Wire rootProjectDirectory through task and plugin**

In `GenerateFixThisSourceIndexTask`, add:

```kotlin
@get:Internal
abstract val rootProjectDirectory: DirectoryProperty
```

and construct the generator with:

```kotlin
val generator = SourceIndexGenerator(
    projectDirectory = projectDirectory.get().asFile,
    rootProjectDirectory = rootProjectDirectory.get().asFile,
    projectPath = projectPath.get(),
    json = json,
)
```

In `FixThisGradlePlugin`, set:

```kotlin
task.rootProjectDirectory.set(project.rootProject.layout.projectDirectory)
```

- [ ] **Step 7: Run Gradle plugin tests**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt \
  fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt
git commit -m "feat: generate repository relative source paths"
```

## Task 3: MCP Host Source Path Resolver

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolver.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolverTest.kt`

- [ ] **Step 1: Add resolver tests**

Create `HostSourcePathResolverTest.kt` with tests for `repoFile`, `sourceRoot`, legacy root, escapes, and ambiguous suffixes. Use this skeleton:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.compose.core.source.SourceRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostSourcePathResolverTest {
    @Test
    fun `resolves repoFile before module file`() {
        val root = tempDir()
        root.resolve("sample/src/main/java/Sample.kt").writeSource("fun sample() {}")
        val index = SourceIndex(
            sourceRoot = SourceRoot(gradlePath = ":app", projectDir = "sample"),
            entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", repoFile = "sample/src/main/java/Sample.kt", line = 1)),
        )

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("sample/src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.REPO_FILE, result.reason)
    }

    @Test
    fun `resolves sourceRoot plus module file when repoFile is absent`() {
        val root = tempDir()
        root.resolve("sample/src/main/java/Sample.kt").writeSource("fun sample() {}")
        val index = SourceIndex(
            sourceRoot = SourceRoot(gradlePath = ":app", projectDir = "sample"),
            entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)),
        )

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("sample/src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.SOURCE_ROOT, result.reason)
    }

    @Test
    fun `rejects path escape`() {
        val root = tempDir()
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "../Escape.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertFalse(result.found)
        assertTrue(result.failureReason!!.startsWith("path escapes project root"))
    }

    @Test
    fun `reports ambiguous suffix fallback`() {
        val root = tempDir()
        root.resolve("a/src/main/java/Sample.kt").writeSource("fun a() {}")
        root.resolve("b/src/main/java/Sample.kt").writeSource("fun b() {}")
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertFalse(result.found)
        assertEquals("file not found on host; multiple suffix matches", result.failureReason)
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory(prefix = "fixthis-resolver-").toFile().also { it.deleteOnExit() }
    private fun File.writeSource(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.HostSourcePathResolverTest"
```

Expected: compilation fails because the resolver does not exist.

- [ ] **Step 3: Implement resolver**

Create `HostSourcePathResolver.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import java.io.File

data class HostSourcePathResolution(
    val file: File?,
    val displayPath: String?,
    val reason: HostSourcePathResolutionReason?,
    val failureReason: String?,
) {
    val found: Boolean get() = file != null
}

enum class HostSourcePathResolutionReason {
    REPO_FILE,
    SOURCE_ROOT,
    LEGACY_ROOT,
    UNIQUE_SUFFIX,
}

class HostSourcePathResolver(private val projectRoot: File) {
    private val canonicalRoot: File by lazy { projectRoot.canonicalFile }

    fun resolve(entry: SourceIndexEntry, sourceIndex: SourceIndex): HostSourcePathResolution {
        val candidates = buildList {
            entry.repoFile?.takeIf { it.isNotBlank() }?.let { add(it to HostSourcePathResolutionReason.REPO_FILE) }
            sourceIndex.sourceRoot?.projectDir?.let { dir ->
                val prefix = dir.trim('/').takeIf { it.isNotBlank() }
                val combined = if (prefix == null) entry.file else "$prefix/${entry.file}"
                add(combined to HostSourcePathResolutionReason.SOURCE_ROOT)
            }
            add(entry.file to HostSourcePathResolutionReason.LEGACY_ROOT)
        }

        for ((relative, reason) in candidates.distinctBy { it.first }) {
            val checked = resolveRelative(relative, reason)
            if (checked.found || checked.failureReason?.startsWith("path escapes project root") == true) return checked
        }

        return uniqueSuffix(entry.file)
    }

    private fun resolveRelative(relative: String, reason: HostSourcePathResolutionReason): HostSourcePathResolution {
        val resolved = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            ?: return missing("file not found on host")
        if (!resolved.isInsideRoot()) {
            return missing("path escapes project root: $relative")
        }
        if (!resolved.isFile) return missing("file not found on host")
        return HostSourcePathResolution(
            file = resolved,
            displayPath = resolved.relativeTo(canonicalRoot).invariantSeparatorsPath,
            reason = reason,
            failureReason = null,
        )
    }

    private fun uniqueSuffix(relative: String): HostSourcePathResolution {
        val suffix = relative.trimStart('/')
        val matches = canonicalRoot.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.relativeTo(canonicalRoot).invariantSeparatorsPath.endsWith(suffix) }
            .take(2)
            .toList()
        return when (matches.size) {
            1 -> HostSourcePathResolution(
                file = matches.single().canonicalFile,
                displayPath = matches.single().canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath,
                reason = HostSourcePathResolutionReason.UNIQUE_SUFFIX,
                failureReason = null,
            )
            0 -> missing("file not found on host")
            else -> missing("file not found on host; multiple suffix matches")
        }
    }

    private fun File.isInsideRoot(): Boolean =
        this == canonicalRoot || path.startsWith(canonicalRoot.path + File.separator)

    private fun missing(reason: String): HostSourcePathResolution =
        HostSourcePathResolution(file = null, displayPath = null, reason = null, failureReason = reason)
}
```

- [ ] **Step 4: Run resolver tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.HostSourcePathResolverTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolver.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourcePathResolverTest.kt
git commit -m "feat: add host source path resolver"
```

## Task 4: Resolver Consumers

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`

- [ ] **Step 1: Add staleness checker module path test**

Append:

```kotlin
@Test
fun `marks candidate fresh when sourceRoot resolves module relative file`() {
    val tmp = tempDir()
    val file = File(tmp, "sample/src/main/java/Sample.kt")
    file.parentFile.mkdirs()
    file.writeText("package x\n\nfun greet() = \"hi\"\n")
    val index = SourceIndex(
        sourceRoot = io.beyondwin.fixthis.compose.core.source.SourceRoot(
            gradlePath = ":app",
            projectDir = "sample",
        ),
        entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 3, excerpt = "fun greet() = \"hi\"")),
    )
    val candidate = candidate(file = "src/main/java/Sample.kt", line = 3)
    val checker = SourceCandidateStalenessChecker(tmp)

    val result = checker.annotate(listOf(candidate), index).single()

    assertEquals(false, result.stale)
    assertNull(result.staleReason)
}
```

- [ ] **Step 2: Update staleness checker implementation**

Replace direct file construction with resolver lookup:

```kotlin
private val resolver = HostSourcePathResolver(projectRoot)
```

Inside `annotate(candidate, entry, canonicalRoot)` use:

```kotlin
val resolvedEntry = entry ?: return candidate
val resolution = resolver.resolve(resolvedEntry, sourceIndex)
val resolved = resolution.file ?: return candidate.flagStale(resolution.failureReason ?: "file not found on host")
if (resolved.length() > MaxBytesToRead) return candidate.flagStale("file too large to verify")
```

Adjust helper signatures so `sourceIndex` is available to the private `annotate` function.

- [ ] **Step 3: Add freshness probe module path test**

Append:

```kotlin
@Test
fun `does not flag projectRoot misconfiguration when indexed files exist under sourceRoot`() {
    val tmp = tempDir()
    val installed = 1_700_000_000_000L
    val file = File(tmp, "sample/src/main/java/Exists.kt").also {
        it.parentFile.mkdirs()
        it.writeText("a")
    }
    file.setLastModified(installed - 60_000)
    val index = SourceIndex(
        sourceRoot = io.beyondwin.fixthis.compose.core.source.SourceRoot(
            gradlePath = ":app",
            projectDir = "sample",
        ),
        entries = listOf(SourceIndexEntry(file = "src/main/java/Exists.kt", line = 1, excerpt = "a")),
    )
    val probe = HostSourceFreshnessProbe(tmp)

    val result = probe.evaluate(index, installEpochMillis = installed)

    assertFalse(result.installStale)
    assertEquals(0, result.newerFileCount)
    assertEquals(null, result.reason)
}
```

- [ ] **Step 4: Update freshness probe implementation**

Use `HostSourcePathResolver(projectRoot)` for every indexed entry. Deduplicate by the resolved display path when found, else by raw `entry.file`.

The newer-file map should use:

```kotlin
val resolver = HostSourcePathResolver(canonicalRoot)
val resolvedFiles = sourceIndex.entries.map { entry ->
    entry to resolver.resolve(entry, sourceIndex)
}
```

For `existsCount`, count `resolution.found`. For `newer`, use `resolution.file?.lastModified()`. For `sampleNewerFiles`, emit `resolution.displayPath ?: entry.file`.

- [ ] **Step 5: Run MCP resolver consumer tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.SourceCandidateStalenessCheckerTest" \
  --tests "io.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbeTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt
git commit -m "fix: resolve module source paths during freshness checks"
```

## Task 5: Compact Handoff Display Paths

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add compact renderer test**

Add a test session whose candidate has `file = "src/main/java/.../HomeScreen.kt"` and `repoFile = "sample/src/main/java/.../HomeScreen.kt"`. Assert compact output includes:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt:44
```

and does not include:

```text
src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt:44
```

- [ ] **Step 2: Add display helpers**

In `FormatterExtensions.kt`, update helpers:

```kotlin
internal fun SourceCandidate.displayFile(): String = repoFile?.takeIf { it.isNotBlank() } ?: file

internal fun SourceCandidate.fileWithLine(): String =
    line?.let { "${displayFile()}:$it" } ?: displayFile()

internal fun SourceCandidate.relativeFileWithLine(prefix: String?): String {
    val display = displayFile()
    val relativeFile = if (prefix != null && display.startsWith(prefix)) display.removePrefix(prefix) else display
    return line?.let { "$relativeFile:$it" } ?: relativeFile
}
```

Update `computeSourceRoot` to use `displayFile()` instead of `file`.

- [ ] **Step 3: Run compact renderer tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.CompactHandoffRendererTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "fix: render repository relative source paths in handoff"
```

## Task 6: Documentation And End-To-End Verification

**Files:**
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/guides/troubleshooting.md`

- [ ] **Step 1: Update output schema**

In `docs/reference/output-schema.md`, add:

```markdown
- `sourceRoot`: optional source-index root metadata. For Gradle projects, `kind` is `"gradle-project"`, `gradlePath` is the Gradle project path, and `projectDir` is the repository-root-relative project directory.
```

In the Source Candidates section, add:

```markdown
- `repoFile`: optional repository-root-relative source path. Agents should prefer this path when present because it can be opened directly from the MCP project root.
```

- [ ] **Step 2: Update troubleshooting**

Replace the absolute guidance that `projectRoot may be misconfigured` means the MCP CLI was launched from the wrong directory with:

```markdown
If `fixthis_status` reports `installStaleReason: "projectRoot may be misconfigured: 0 of <N> indexed files exist on host"`, first confirm the MCP server was launched from the repository root. On older APKs that do not include source-root metadata, this can also happen when the Android app lives under a Gradle subproject such as `sample/`; rebuild and reinstall the debug app so the source index includes repository-relative paths.
```

- [ ] **Step 3: Run targeted test suite**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-gradle-plugin:test :fixthis-mcp:test --tests "*SourceIndexTest*" --tests "*GenerateFixThisSourceIndexTaskTest*" --tests "*HostSourcePathResolverTest*" --tests "*SourceCandidateStalenessCheckerTest*" --tests "*HostSourceFreshnessProbeTest*" --tests "*CompactHandoffRendererTest*"
```

Expected: PASS.

- [ ] **Step 4: Rebuild and install sample**

Run:

```bash
./gradlew :app:installDebug :fixthis-cli:installDist :fixthis-mcp:installDist
```

Expected: Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify FixThis status**

Run:

```bash
fixthis-cli/build/install/fixthis/bin/fixthis status --package io.beyondwin.fixthis.sample --project-dir "$PWD"
```

Expected JSON includes:

```json
"sourceIndexAvailable": true,
"installStale": false
```

and does not include:

```text
projectRoot may be misconfigured
```

- [ ] **Step 6: Verify Copy Prompt behavior**

Capture a sample feedback item on a `MetricCard`, then use Copy Prompt or `fixthis_read_feedback`.

Expected compact source line resembles:

```text
sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt:44  conf=high
```

Expected output does not include false:

```text
stale: file not found on host
```

- [ ] **Step 7: Commit docs**

```bash
git add docs/reference/output-schema.md docs/guides/troubleshooting.md
git commit -m "docs: document source index root metadata"
```

## Self-Review

- Spec coverage: covered additive model fields, Gradle generation, shared MCP resolver, staleness/freshness consumers, compact Copy Prompt rendering, compatibility, diagnostics, tests, docs, and rollout.
- Placeholder scan: no unfinished marker text or unspecified implementation steps remain.
- Type consistency: the plan uses `SourceRoot`, `repoFile`, `HostSourcePathResolver`, `HostSourcePathResolution`, and `HostSourcePathResolutionReason` consistently from definition through use.
