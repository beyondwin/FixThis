# Source Index Agent-Handoff Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis's MCP handoff payload meaningfully more useful to coding agents by (a) cross-linking `stringResource(R.string.X)` calls to their resolved default-locale text, and (b) recording the enclosing `@Composable fun` for each indexed call site and surfacing it as `ownerComposable` on `SourceCandidate`.

**Architecture:** Build-time enrichment only. The Gradle source-index task already scans both Kotlin and XML; we add a default-locale resolver step that runs once per build, threads a `Map<resId, String>` into the Kotlin scanner, and emits two new `SourceSignal` kinds (`STRING_RESOURCE_RESOLVED`, `LAMBDA_OWNER_FUNCTION`). The runtime matcher learns one new `SourceMatchReason`; `SourceCandidate` gains one optional field. Pre-1.0 project — backwards compatibility with older assets/sessions is **not** a goal; tests pin only the current shape.

**Tech Stack:** Kotlin, Gradle (custom `@CacheableTask`), `kotlinx.serialization`, JUnit, existing FixThis modules (`fixthis-gradle-plugin`, `fixthis-compose-core`, `fixthis-mcp`).

**Spec:** [`../specs/source-index-agent-handoff.md`](../specs/source-index-agent-handoff.md)

---

## File Structure

**New files:** none. All changes extend existing files.

**Modified files (responsibility map):**

| Path | Change |
|---|---|
| `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt` | Add `STRING_RESOURCE_RESOLVED`, `LAMBDA_OWNER_FUNCTION` to `SourceSignalKindAsset`; bump `schemaVersion` to `"1.2"`. |
| `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt` | Add `resolveDefaults(files: List<File>): Map<String, String>` returning resId → default-locale value. |
| `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt` | Build resolver from `xmlFiles`; pass into Kotlin scanner. |
| `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt` | Accept `StringResourceResolver`; emit `STRING_RESOURCE_RESOLVED` signal + `text` enrichment in `collectStringResourceSignals`; track enclosing `@Composable fun` via brace-depth scope and emit `LAMBDA_OWNER_FUNCTION` on every entry inside it. |
| `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt` (extend) and `XmlStringResourceScannerTest.kt` (extend) | New unit cases. |
| `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt` (extend) | Asset-shape golden: schemaVersion 1.2, new signals present. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt` | Add the two new enum values to `SourceSignalKind`; bump `schemaVersion` default to `"1.2"`. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt` | Add `SELECTED_RESOLVED_STRING_RESOURCE`. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt` | Add `SELECTED_RESOLVED_STRING_RESOURCE -> 48.0` to `bucketScore`; extend `rankingTier` SELECTED_TEXT clause to include the new reason. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` | Match selected element text against `STRING_RESOURCE_RESOLVED` signal values; emit `SELECTED_RESOLVED_STRING_RESOURCE`; populate `ownerComposable` from `LAMBDA_OWNER_FUNCTION` while building `SourceCandidate`. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt` | Treat `SELECTED_RESOLVED_STRING_RESOURCE` as selected medium evidence. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` (extend) | Golden case: resolved-only call site matches over `strings.xml` line. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` | Add `ownerComposable: String? = null` to `SourceCandidate` (line ~103). |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt` | Add `ownerComposable` so persisted/domain session round-trips keep the field. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt` | Map `ownerComposable` in both `SourceCandidate` ↔ `SourceHint` directions. |
| `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/DomainContractMappersTest.kt` (extend) | Owner survives source-candidate/domain-source-hint round-trip. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt` | Add owner-aware source-location helper for Markdown renderers. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt` | Inject `inside fun <owner>` segment in precise/full feedback Markdown source-candidate lines. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` | Include compact owner token (`owner=<name>`) for the top candidate when present. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` | Include `inside fun <owner>` in annotation Markdown source-candidate locations. |
| Formatter tests under `fixthis-mcp/src/test/...` and `fixthis-compose-core/src/test/...` | Assert owner text is rendered in each handoff format. |
| `docs/reference/source-matching.md` (create if absent, otherwise extend) | Document the two new signal kinds and the resolver behavior. |
| `docs/reference/output-schema.md` and `docs/reference/feedback-console-contract.md` | Document `ownerComposable`, the compact owner token, and the new signal kinds. |

---

## Tasks

### Task 1: Mirror the new `SourceSignalKind` values across both modules

The compose-core runtime deserializes the asset; `kotlinx.serialization` rejects unknown enum values by default. Add the enum values to both modules in the same commit so the writer and reader stay in sync at every checkpoint.

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt:45-55`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt:46-56`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt` (create)

- [ ] **Step 1: Write a failing serialization round-trip test**

Create `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndexSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes asset with new signal kinds`() {
        val payload = """
            {
              "schemaVersion": "1.2",
              "entries": [{
                "file": "Foo.kt",
                "signals": [
                  {"kind": "STRING_RESOURCE_RESOLVED", "value": "Log in"},
                  {"kind": "LAMBDA_OWNER_FUNCTION", "value": "LoginScreen"}
                ]
              }]
            }
        """.trimIndent()
        val index = json.decodeFromString(SourceIndex.serializer(), payload)
        assertEquals("1.2", index.schemaVersion)
        val signals = index.entries.single().signals
        assertEquals(SourceSignalKind.STRING_RESOURCE_RESOLVED, signals[0].kind)
        assertEquals("Log in", signals[0].value)
        assertEquals(SourceSignalKind.LAMBDA_OWNER_FUNCTION, signals[1].kind)
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./gradlew :fixthis-compose-core:test --tests SourceIndexSerializationTest
```

Expected: FAIL — `STRING_RESOURCE_RESOLVED` / `LAMBDA_OWNER_FUNCTION` are unknown enum values.

- [ ] **Step 3: Add the enum values to compose-core**

In `SourceIndex.kt`, change the enum body to (order is free — pre-1.0 means no wire-stability concern):

```kotlin
@Serializable
enum class SourceSignalKind {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
    STRING_RESOURCE_RESOLVED,
    LAMBDA_OWNER_FUNCTION,
}
```

Also change `data class SourceIndex(val schemaVersion: String = "1.0", ...)` to default `"1.2"`.

- [ ] **Step 4: Add the enum values to the gradle asset**

In `SourceIndexAssets.kt`, mirror the same two values into `SourceSignalKindAsset` (same order), and change the `SourceIndexAsset` default `schemaVersion` from `"1.1"` to `"1.2"`.

- [ ] **Step 5: Run the test to confirm it passes**

```bash
./gradlew :fixthis-compose-core:test --tests SourceIndexSerializationTest
```

Expected: PASS, both cases.

- [ ] **Step 6: Run the full module tests to confirm no regression**

```bash
./gradlew :fixthis-compose-core:test :fixthis-gradle-plugin:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndexSerializationTest.kt \
        fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt
git commit -m "feat(source-index): add resolved-string and lambda-owner signal kinds (schema 1.2)"
```

---

### Task 2: Default-locale string resolver

Add a method to `XmlStringResourceScanner` that, given the list of XML inputs the task already passes in, returns a `Map<String, String>` from resId to default-locale value. Only `**/values/strings.xml` (no qualifier suffix) contributes.

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/XmlStringResourceScannerTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

In `XmlStringResourceScannerTest.kt`, add:

```kotlin
@Test
fun `resolveDefaults returns default-locale strings only`() {
    val projectDir = tempDir.newFolder("project")
    val resDir = projectDir.resolve("src/main/res").apply { mkdirs() }
    resDir.resolve("values").mkdirs()
    resDir.resolve("values").resolve("strings.xml").writeText(
        """
        <resources>
          <string name="login_button">Log in</string>
          <string name="cancel">Cancel</string>
        </resources>
        """.trimIndent()
    )
    resDir.resolve("values-en").mkdirs()
    resDir.resolve("values-en").resolve("strings.xml").writeText(
        """
        <resources>
          <string name="login_button">Sign in</string>
        </resources>
        """.trimIndent()
    )
    val scanner = XmlStringResourceScanner(projectDir, projectDir)
    val allXml = projectDir.walkTopDown().filter { it.name == "strings.xml" }.toList()
    val resolved = scanner.resolveDefaults(allXml)
    assertEquals(mapOf("login_button" to "Log in", "cancel" to "Cancel"), resolved)
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :fixthis-gradle-plugin:test --tests XmlStringResourceScannerTest.resolveDefaults*
```

Expected: FAIL — `resolveDefaults` does not exist.

- [ ] **Step 3: Implement `resolveDefaults`**

Add to `XmlStringResourceScanner.kt`:

```kotlin
fun resolveDefaults(files: List<File>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (file in files) {
        val sourceFile = file.canonicalFile
        if (sourceFile.parentFile?.name != "values") continue
        val document = runCatching {
            newDocumentBuilderFactory().newDocumentBuilder().parse(sourceFile)
        }.getOrNull() ?: continue
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val element = strings.item(index) as? Element ?: continue
            val name = element.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val value = element.textContent?.trim().orEmpty()
            if (value.isEmpty()) continue
            out.putIfAbsent(name, value)
        }
    }
    return out
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :fixthis-gradle-plugin:test --tests XmlStringResourceScannerTest.resolveDefaults*
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt \
        fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/XmlStringResourceScannerTest.kt
git commit -m "feat(source-index): expose default-locale string resolver from XML scanner"
```

---

### Task 3: Emit `STRING_RESOURCE_RESOLVED` from `KotlinSourceScanner`

Thread the resolver into the Kotlin scanner; in `collectStringResourceSignals`, when a resId is in the resolver map, append the resolved value to the entry's `text` list and add a `STRING_RESOURCE_RESOLVED` signal.

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt:148-172`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `emits STRING_RESOURCE_RESOLVED when resolver has the resId`() {
    val kotlinFile = tempDir.newFile("LoginScreen.kt").apply {
        writeText(
            """
            package com.example
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.res.stringResource

            @Composable
            fun LoginScreen() {
                Text(stringResource(R.string.login_button))
            }
            """.trimIndent()
        )
    }
    val scanner = KotlinSourceScanner(tempDir.root, tempDir.root, json)
    val resolver = mapOf("login_button" to "Log in")
    val entries = scanner.scan(kotlinFile, resolver)
    val signals = entries.flatMap { it.signals }
    assertTrue(signals.any { it.kind == SourceSignalKindAsset.STRING_RESOURCE_RESOLVED && it.value == "Log in" })
    assertTrue(entries.any { "Log in" in it.text })
}
```

(If `scan(File)` is currently the only signature, this test will fail to compile until the new overload exists — that's intentional, drives the API change.)

- [ ] **Step 2: Run the test to verify it fails to compile**

```bash
./gradlew :fixthis-gradle-plugin:compileTestKotlin
```

Expected: FAIL — `scan(File, Map<String, String>)` does not exist.

- [ ] **Step 3: Add resolver parameter to scanner**

Change `KotlinSourceScanner.scan(file: File)` to `scan(file: File, resolver: Map<String, String> = emptyMap())`. Thread `resolver` into `collectStringResourceSignals` and update that function:

```kotlin
private fun collectStringResourceSignals(
    file: File,
    source: String,
    lines: List<String>,
    lineStartOffsets: IntArray,
    entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
    packageName: String?,
    classDeclarations: List<Pair<Int, String>>,
    resolver: Map<String, String>,
) {
    stringResourceRegex.findAll(source).forEach { match ->
        val resourceName = match.groupValues[1]
        val builder = entriesByLine.entryFor(
            file = file,
            lineNumber = match.startLine(lineStartOffsets),
            lines = lines,
            packageName = packageName,
            className = classNameAt(match.range.first, classDeclarations),
        )
        builder.stringResources += resourceName
        builder.addSignal(SourceSignalKindAsset.STRING_RESOURCE, resourceName)
        resolver[resourceName]?.let { resolved ->
            builder.text += resolved
            builder.addSignal(SourceSignalKindAsset.STRING_RESOURCE_RESOLVED, resolved)
        }
    }
}
```

Update the call site in `scanKotlinFile` to pass `resolver`.

- [ ] **Step 4: Wire the resolver into `SourceIndexGenerator`**

```kotlin
fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset {
    val resolver = xmlScanner.resolveDefaults(xmlFiles)
    return SourceIndexAsset(
        sourceRoot = SourceRootAsset(...),
        entries = buildList {
            kotlinFiles.sortedBy { ... }.forEach { addAll(kotlinScanner.scan(it, resolver)) }
            xmlFiles.sortedBy { ... }.forEach { addAll(xmlScanner.scan(it)) }
        },
    )
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :fixthis-gradle-plugin:test --tests KotlinSourceScannerTest
```

Expected: PASS, including the new case.

- [ ] **Step 6: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt \
        fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt \
        fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt
git commit -m "feat(source-index): cross-link Kotlin stringResource calls to resolved default text"
```

---

### Task 4: Emit `LAMBDA_OWNER_FUNCTION` for entries inside `@Composable fun`

Track a stack of enclosing `@Composable fun` declarations by walking braces. The scanner already iterates `lines.forEachIndexed`; extend that pass to maintain `(name, openBraceDepth, closeAtDepth)` and tag every entry created on a line inside the active function.

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `attaches LAMBDA_OWNER_FUNCTION signal to entries inside Composable`() {
    val file = tempDir.newFile("HomeScreen.kt").apply {
        writeText(
            """
            package com.example
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun HomeScreen() {
                Text("Hello")
            }

            fun helperThatIsNotComposable() {
                println("not in scope")
            }
            """.trimIndent()
        )
    }
    val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
    val helloEntry = entries.single { "Hello" in it.text }
    assertTrue(
        helloEntry.signals.any {
            it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "HomeScreen"
        }
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :fixthis-gradle-plugin:test --tests KotlinSourceScannerTest.attachesLambdaOwner*
```

Expected: FAIL — no `LAMBDA_OWNER_FUNCTION` signal emitted yet.

- [ ] **Step 3: Implement the owner-tracking pass**

Add a private helper that computes, for each line index, the name of the innermost enclosing `@Composable fun` (or `null`):

```kotlin
private fun composableOwnerByLine(
    source: String,
    lines: List<String>,
): Array<String?> {
    val owners = arrayOfNulls<String>(lines.size)
    val stack = ArrayDeque<Pair<String, Int>>() // name, brace depth at entry
    var depth = 0
    var pendingComposable = false
    var pendingFunName: String? = null
    var pendingFunOpenDepth = -1
    lines.forEachIndexed { idx, raw ->
        val line = raw
        if (line.contains("@Composable")) pendingComposable = true
        functionRegex.find(line)?.let { m ->
            if (pendingComposable || line.contains("@Composable")) {
                pendingFunName = m.groupValues[1]
                pendingFunOpenDepth = depth
            }
            pendingComposable = false
        }
        for (ch in line) {
            when (ch) {
                '{' -> {
                    depth++
                    if (pendingFunName != null && depth == pendingFunOpenDepth + 1) {
                        stack.addLast(pendingFunName!! to depth)
                        pendingFunName = null
                        pendingFunOpenDepth = -1
                    }
                }
                '}' -> {
                    if (stack.isNotEmpty() && stack.last().second == depth) stack.removeLast()
                    depth--
                }
            }
        }
        owners[idx] = stack.lastOrNull()?.first
    }
    return owners
}
```

After all `collectXxxSignals` passes have populated `entriesByLine`, walk it once and attach the owner:

```kotlin
val owners = composableOwnerByLine(source, lines)
entriesByLine.forEach { (lineNumber, builder) ->
    owners.getOrNull(lineNumber - 1)?.let { ownerName ->
        builder.addSignal(SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION, ownerName)
    }
}
```

(Caveat to capture in a `// ` comment-free design note: brace-depth counting ignores braces inside string literals. For the regex-based scanner already in use this is acceptable; the function name regex already operates at the same fidelity level.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :fixthis-gradle-plugin:test --tests KotlinSourceScannerTest
```

Expected: PASS.

- [ ] **Step 5: Add a negative-case test**

```kotlin
@Test
fun `does not attach owner for entries outside any Composable`() {
    val file = tempDir.newFile("TopLevel.kt").apply {
        writeText(
            """
            package com.example
            const val LABEL = "outside"
            """.trimIndent()
        )
    }
    val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
    entries.forEach { entry ->
        assertTrue(entry.signals.none { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION })
    }
}
```

Run and confirm PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt \
        fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt
git commit -m "feat(source-index): record enclosing @Composable fun for indexed call sites"
```

---

### Task 5: Matcher honors `STRING_RESOURCE_RESOLVED`

Add a new `SourceMatchReason`, score it, and have `SourceMatcher` emit it when the selected element's text matches a `STRING_RESOURCE_RESOLVED` signal on an entry.

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` (extend)

- [ ] **Step 1: Write the failing matcher golden case**

In `SourceMatcherTest.kt`, add:

```kotlin
@Test
fun `selects Kotlin call site over strings_xml when only resolved text matches`() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "src/main/kotlin/com/example/LoginScreen.kt",
                line = 42,
                stringResources = listOf("login_button"),
                signals = listOf(
                    SourceSignal(SourceSignalKind.STRING_RESOURCE, "login_button"),
                    SourceSignal(SourceSignalKind.STRING_RESOURCE_RESOLVED, "Log in"),
                ),
            ),
            SourceIndexEntry(
                file = "src/main/res/values/strings.xml",
                line = 5,
                text = listOf("Log in"),
                stringResources = listOf("login_button"),
                signals = listOf(
                    SourceSignal(SourceSignalKind.UI_TEXT, "Log in"),
                    SourceSignal(SourceSignalKind.STRING_RESOURCE, "login_button"),
                ),
            ),
        ),
    )
    val candidates = SourceMatcher(index).match(
        selectedNode = node(uid = "login-button", text = listOf("Log in")),
        nearbyNodes = emptyList(),
        activityName = null,
    )
    assertEquals("src/main/kotlin/com/example/LoginScreen.kt", candidates.first().file)
    assertTrue(
        candidates.first().matchReasons.any {
            it == "selected resolved stringResource"
        }
    )
}
```

(Use the existing `node(...)` helper in `SourceMatcherTest`.)

- [ ] **Step 2: Run to confirm it fails**

```bash
./gradlew :fixthis-compose-core:test --tests SourceMatcherTest.selectsKotlinCallSite*
```

Expected: FAIL.

- [ ] **Step 3: Add the enum value**

In `SourceMatchReason.kt`, add `SELECTED_RESOLVED_STRING_RESOURCE` to the
enum (place it after `SELECTED_STRING_RESOURCE`):

```kotlin
SELECTED_RESOLVED_STRING_RESOURCE("selected resolved stringResource"),
```

- [ ] **Step 4: Score it in `SourceScoringPolicy`**

Add to `bucketScore` `when`:

```kotlin
SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE -> SELECTED_RESOLVED_STRING_RESOURCE_SCORE
```

Add the constant:

```kotlin
private const val SELECTED_RESOLVED_STRING_RESOURCE_SCORE: Double = 48.0
```

Extend `rankingTier` to include the new reason in the `selectedTextRankingTier` bucket:

```kotlin
reasons.hasAny(
    SourceMatchReason.SELECTED_TEXT,
    SourceMatchReason.SELECTED_CONTENT_DESCRIPTION,
    SourceMatchReason.SELECTED_STRING_RESOURCE,
    SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE,
    SourceMatchReason.SELECTED_ROLE,
) -> selectedTextRankingTier
```

- [ ] **Step 5: Emit the reason from `SourceMatcher`**

Inside the matcher's per-entry scoring, extend the selected text-like signal
bag so selected node text can match `STRING_RESOURCE_RESOLVED` signal values.
Locate the existing block by:

```bash
git grep -n "SELECTED_STRING_RESOURCE\|SELECTED_TEXT" fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt
```

Add the new signal kind to the selected text-like evidence path:

```kotlin
private fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(
        SourceSignalKind.UI_TEXT,
        SourceSignalKind.STRING_RESOURCE_RESOLVED,
        SourceSignalKind.STRING_RESOURCE,
        SourceSignalKind.ARBITRARY_STRING_LITERAL,
    ),
    legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt),
)
```

Then update `recordMatch` so resolved-string hits use the new scored reason.
Keep `SELECTED_STRING_RESOURCE` as the resId-origin marker only for raw
`STRING_RESOURCE` hits:

```kotlin
val effectiveReason = if (
    hit.signalKind == SourceSignalKind.STRING_RESOURCE_RESOLVED &&
    reason == SourceMatchReason.SELECTED_TEXT
) {
    SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE
} else {
    reason
}

matchedTerms.add(cleaned)
matchReasons.add(effectiveReason)
trackOrigin(hit, effectiveReason, isNearby)
if (hit.signalKind == SourceSignalKind.STRING_RESOURCE &&
    (
        reason == SourceMatchReason.SELECTED_TEXT ||
            reason == SourceMatchReason.SELECTED_CONTENT_DESCRIPTION
        )
) {
    matchReasons.add(SourceMatchReason.SELECTED_STRING_RESOURCE)
}
val evidenceKey = "${effectiveReason.wireLabel}\u001F${cleaned.normalizedForMatch()}"
return if (scoredEvidence.add(evidenceKey)) {
    SourceScoringPolicy.bucketScore(effectiveReason) * hit.weight
} else {
    0.0
}
```

Finally, update `SourceSignalKind.baseMatchWeight` for the exhaustive enum
`when`:

```kotlin
SourceSignalKind.STRING_RESOURCE_RESOLVED,
SourceSignalKind.UI_TEXT,
SourceSignalKind.TEST_TAG,
SourceSignalKind.CONTENT_DESCRIPTION,
SourceSignalKind.COMPOSABLE_SYMBOL,
-> 1.0
```

Update `EvidenceProfile.kt` so the new reason contributes the same selected
medium evidence as selected UI text/stringResource:

```kotlin
val hasSelectedResolvedStringResource: Boolean =
    SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE in reasons
val hasAnySelected: Boolean =
    hasSelectedTestTag ||
        hasStrictCompTag ||
        hasSelectedUiText ||
        hasSelectedContentDescription ||
        hasSelectedRole ||
        hasSelectedStringResource ||
        hasSelectedResolvedStringResource

val distinctSelectedMediumKinds: Int =
    (if (hasSelectedUiText) 1 else 0) +
        (if (hasSelectedContentDescription) 1 else 0) +
        (if (hasSelectedStringResource || hasSelectedResolvedStringResource) 1 else 0) +
        (if (hasSelectedRole && hasAnySelected && reasons != setOf(SourceMatchReason.SELECTED_ROLE)) 1 else 0)
```

- [ ] **Step 6: Run the new test and the full matcher suite**

```bash
./gradlew :fixthis-compose-core:test --tests SourceMatcherTest
```

Expected: PASS. Inspect any goldens that shifted; if one is unintended, file a follow-up note in the PR description rather than rolling back the scoring change.

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source-match): score resolved string-resource matches at 48"
```

---

### Task 6: Add and preserve `ownerComposable` on source candidates

`SourceMatcher` already has the matched `SourceIndexEntry` while building the
`SourceCandidate`, so this task populates the owner there rather than
re-looking up entries later in MCP session code.

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt:103-118`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` (extend)
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/DomainContractMappersTest.kt` (extend)

- [ ] **Step 1: Write the failing matcher owner test**

In `SourceMatcherTest.kt`, add:

```kotlin
@Test
fun `source candidate carries enclosing Composable owner`() {
    val index = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "sample/src/main/kotlin/LoginScreen.kt",
                line = 42,
                signals = listOf(
                    SourceSignal(SourceSignalKind.UI_TEXT, "Log in"),
                    SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "LoginScreen"),
                ),
            ),
        ),
    )

    val candidates = SourceMatcher(index).match(
        selectedNode = node(uid = "login-button", text = listOf("Log in")),
        nearbyNodes = emptyList(),
        activityName = null,
    )

    assertEquals("LoginScreen", candidates.single().ownerComposable)
}
```

- [ ] **Step 2: Run to confirm compile failure**

```bash
./gradlew :fixthis-compose-core:compileTestKotlin
```

Expected: FAIL — `ownerComposable` does not exist.

- [ ] **Step 3: Add the field to `SourceCandidate` and domain `SourceHint`**

In `Models.kt`, add the nullable field at the end of `SourceCandidate`:

```kotlin
val ownerComposable: String? = null,
```

In `SourceHint.kt`, add the same field at the end of `SourceHint`:

```kotlin
val ownerComposable: String? = null,
```

- [ ] **Step 4: Populate it from the matched entry in `SourceMatcher`**

In `SourceMatcher.kt`, add a helper near the other `SourceIndexEntry`
extensions:

```kotlin
private val SourceIndexEntry.ownerComposable: String?
    get() = signals.firstOrNull {
        it.kind == SourceSignalKind.LAMBDA_OWNER_FUNCTION
    }?.value
```

Then pass it in `MatchScore.toCandidate(...)`:

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
    ownerComposable = entry.ownerComposable,
)
```

- [ ] **Step 5: Preserve it through source-candidate/domain-source-hint mappers**

In `SourceCandidateMappers.kt`, add the field in both directions:

```kotlin
fun SourceHint.toSourceCandidate(): SourceCandidate = SourceCandidate(
    file = file,
    repoFile = repoFile,
    line = line,
    score = score,
    matchedTerms = matchedTerms,
    matchReasons = matchReasons,
    confidence = confidence.toSelectionConfidence(),
    ranking = ranking,
    scoreMargin = scoreMargin,
    evidenceStrength = evidenceStrength?.toSourceEvidenceStrength(),
    riskFlags = riskFlags.map(SourceHintRisk::toSourceCandidateRisk),
    caution = caution,
    stale = stale,
    staleReason = staleReason,
    ownerComposable = ownerComposable,
)

fun SourceCandidate.toSourceHint(): SourceHint = SourceHint(
    file = file,
    repoFile = repoFile,
    line = line,
    score = score,
    matchedTerms = matchedTerms,
    matchReasons = matchReasons,
    confidence = confidence.toSourceHintConfidence(),
    ranking = ranking,
    scoreMargin = scoreMargin,
    evidenceStrength = evidenceStrength?.toSourceHintStrength(),
    riskFlags = riskFlags.map(SourceCandidateRisk::toSourceHintRisk),
    caution = caution,
    stale = stale,
    staleReason = staleReason,
    ownerComposable = ownerComposable,
)
```

- [ ] **Step 6: Extend the domain mapper round-trip test**

In `DomainContractMappersTest.sourceCandidateRoundTripPreservesCompatibilityFields`,
add the field to the fixture:

```kotlin
val hint = SourceHint(
    file = "sample/src/main/kotlin/Foo.kt",
    line = 12,
    score = 0.82,
    matchedTerms = listOf("save", "button"),
    matchReasons = listOf("test tag"),
    confidence = SourceHintConfidence.MEDIUM,
    ranking = 1,
    scoreMargin = 0.08,
    evidenceStrength = SourceHintStrength.STRONG,
    riskFlags = listOf(SourceHintRisk.AMBIGUOUS, SourceHintRisk.TEXT_ONLY),
    caution = "ambiguous source match",
    stale = true,
    staleReason = "index older than source",
    ownerComposable = "FooScreen",
)
```

- [ ] **Step 7: Run the tests to confirm they pass**

```bash
./gradlew :fixthis-compose-core:test --tests SourceMatcherTest --tests DomainContractMappersTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/DomainContractMappersTest.kt
git commit -m "feat(source-match): surface enclosing Composable on SourceCandidate"
```

---

### Task 7: Include `ownerComposable` in agent-facing handoff text

The live `fixthis_get_current_screen` tool returns raw screen JSON. Agent-facing
source-candidate text is rendered by the feedback handoff formatters, so this
task updates those renderers directly.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt` (extend)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` (extend)
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt` (extend)

- [ ] **Step 1: Write failing formatter assertions**

Add or extend existing formatter tests with a `SourceCandidate` fixture that
sets `ownerComposable = "LoginScreen"`. Assert the precise/full Markdown
contains:

```kotlin
assertTrue(markdown.contains("inside fun LoginScreen"))
```

For compact handoff output, assert:

```kotlin
assertTrue(markdown.contains("owner=LoginScreen"))
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :fixthis-mcp:test --tests FeedbackQueueFormatterTest --tests CompactHandoffRendererTest \
  :fixthis-compose-core:test --tests FixThisMarkdownFormatterTest
```

Expected: FAIL — the owner is serialized in JSON but absent from Markdown.

- [ ] **Step 3: Add source-location formatter helpers**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`,
add:

```kotlin
internal fun SourceCandidate.fileWithLineAndOwner(): String {
    val ownerSegment = ownerComposable?.takeIf { it.isNotBlank() }?.let { " inside fun $it" }.orEmpty()
    return "${fileWithLine()}$ownerSegment"
}
```

- [ ] **Step 4: Use the owner-aware helpers in MCP handoff formatters**

In `FeedbackQueueFormatter.appendLikelySource`, replace:

```kotlin
appendLine("${index + 1}. `${candidate.fileWithLine()}` ${candidate.markdownConfidence(target)} confidence$staleSuffix")
```

with:

```kotlin
appendLine("${index + 1}. `${candidate.fileWithLineAndOwner()}` ${candidate.markdownConfidence(target)} confidence$staleSuffix")
```

In `CompactHandoffRenderer.formatCandidateLine`, keep the compact path stable
and add a separate owner token for the top candidate:

```kotlin
sb.append("${candidate.relativeFileWithLine(sourceRoot)}  conf=${candidate.confidence.name.lowercase()}")
if (rank == 1) {
    candidate.ownerComposable?.takeIf { it.isNotBlank() }?.let { owner ->
        sb.append("  owner=$owner")
    }
    val effectiveMargin = candidate.scoreMargin ?: computedMargin
    effectiveMargin?.let { margin ->
        sb.append("  margin=${"%.2f".format(margin)}")
    }
    val tokens = candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().take(4)
    if (tokens.isNotEmpty()) {
        sb.append("  matched=[${tokens.joinToString(", ")}]")
    }
}
```

- [ ] **Step 5: Update compose-core annotation Markdown formatting**

In `FixThisMarkdownFormatter`, update the `SourceCandidate.location()` helper:

```kotlin
private fun SourceCandidate.location(): String {
    val ownerSegment = ownerComposable?.takeIf { it.isNotBlank() }?.let { " inside fun $it" }.orEmpty()
    return file + (line?.let { ":$it" } ?: "") + ownerSegment
}
```

- [ ] **Step 6: Run formatter tests**

```bash
./gradlew :fixthis-mcp:test --tests FeedbackQueueFormatterTest --tests CompactHandoffRendererTest \
  :fixthis-compose-core:test --tests FixThisMarkdownFormatterTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt
git commit -m "feat(handoff): include enclosing Composable in source candidate text"
```

---

### Task 8: End-to-end smoke on the sample app, plus docs

Verify the new fields land in the on-disk asset for the sample app, and document the new signal kinds.

**Files:**
- Modify or create: `docs/reference/source-matching.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Read: `sample/build/generated/fixthis/.../fixthis-source-index.json` (output of the task)

- [ ] **Step 1: Generate the sample index**

```bash
./gradlew :sample:assembleDebug
```

(Adjust task name if the sample uses a different variant.)

- [ ] **Step 2: Confirm new signals are present**

```bash
find sample/build -name "fixthis-source-index.json" -print0 | xargs -0 grep -l "STRING_RESOURCE_RESOLVED"
find sample/build -name "fixthis-source-index.json" -print0 | xargs -0 grep -l "LAMBDA_OWNER_FUNCTION"
```

Expected: both `grep -l` print at least one path. If not, the sample app doesn't exercise the pattern — add a `stringResource` call to one of the sample screens to provide coverage, then re-run.

- [ ] **Step 3: Document the new signal kinds**

Create or extend `docs/reference/source-matching.md` with a section describing:
- What `STRING_RESOURCE_RESOLVED` is, when it fires, and how scoring treats it.
- What `LAMBDA_OWNER_FUNCTION` is, the scope tracking used to compute it, and the limitation around braces inside string literals.
- The schema bump (`1.1` → `1.2`). Pre-1.0 project — `schemaVersion` is informational only; do not document any forward-compat guarantee.

Update `docs/reference/output-schema.md` so the source-index signal list includes
`STRING_RESOURCE_RESOLVED` and `LAMBDA_OWNER_FUNCTION`, and the
`SourceCandidate` shape includes `ownerComposable`.

Update `docs/reference/feedback-console-contract.md` so the compact handoff
grammar documents the optional `owner=<Composable>` token and the precise/full
Markdown examples document `inside fun <Composable>`.

- [ ] **Step 4: Run the full required-local-checks matrix** (per `CONTRIBUTING.md`)

```bash
./gradlew check
node scripts/build-console-assets.mjs --check
git diff --check
```

Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/source-matching.md docs/reference/output-schema.md docs/reference/feedback-console-contract.md
git commit -m "docs(source-match): document STRING_RESOURCE_RESOLVED and LAMBDA_OWNER_FUNCTION"
```

---

## Self-Review Notes

- **Spec coverage:** Acceptance criteria have backing tasks — schema 1.2 (Task 1), resolver presence in sample asset (Task 8), matcher golden (Task 5), `ownerComposable` in candidate and mapper round-trip (Task 6), bridge protocol unchanged (Task 8 checks), and docs (Task 8). Task 7 covers the user-visible handoff text change implied by the spec's "Handoff Text Format" section.
- **Type consistency:** `SourceSignalKind` (compose-core) and `SourceSignalKindAsset` (gradle plugin) are kept in lockstep by Task 1. `SELECTED_RESOLVED_STRING_RESOURCE` is added once (matcher only). `ownerComposable` is named identically on `SourceCandidate`, `SourceHint`, the mappers, and the handoff text formatters.
- **Legacy:** Pre-1.0; no backwards-compat tests, no fallback decoders, no migration steps. If a wire change feels too invasive partway through, change it.
- **Open risks documented:**
  - Brace-depth scope tracking is regex-fidelity, not lexer-fidelity; string-literal braces could mislead it. The plan acknowledges this rather than fixing it (full lexer = scope creep into Level B/C of the broader source-matching work).
  - Scoring shift (resolved 48.0 over selected XML text 45.0 and nearby 24.0) may re-order existing goldens. Task 5 Step 6 calls this out and asks the executor to log shifts in the PR description rather than revert.
