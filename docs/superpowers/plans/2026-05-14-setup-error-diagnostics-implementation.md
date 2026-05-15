# Setup Error Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `fixthis setup --write` failures self-explanatory by surfacing the full cause chain, classifying the error category, recommending a fix, and offering a `--verbose` opt-in for full stack traces.

**Architecture:** All changes live in `:fixthis-cli`. New internal renderer (`SetupErrorRendering.kt`) and diagnostic-flag holder (`DiagnosticContext.kt`); `SetupCommand` and `Main` wire them up. Spec: `docs/superpowers/specs/2026-05-14-setup-error-diagnostics-design.md`.

**Tech Stack:** Kotlin, Clikt 5.0.3, kotlinx-serialization-json, JUnit 4.

---

## File Map

| Action | Path |
|---|---|
| Create | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/DiagnosticContext.kt` |
| Create | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactor.kt` |
| Create | `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactorTest.kt` |
| Create | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRendering.kt` |
| Create | `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt` |
| Create | `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainPrintTest.kt` |
| Modify | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` |
| Modify | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt` |
| Modify | `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt` |
| Modify | `docs/reference/cli.md` |
| Modify | `docs/guides/troubleshooting.md` |
| Modify | `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md` (one-line supersession note) |

**Test command after every Task:** `./gradlew :fixthis-cli:test --no-daemon`

---

### Task 1: Introduce DiagnosticContext + write the first failing renderer test

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/DiagnosticContext.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt`

- [ ] **Step 1: Create DiagnosticContext.kt**

Write the file with exactly:

```kotlin
package io.github.beyondwin.fixthis.cli

/**
 * Per-thread diagnostic flag. Wraps a [ThreadLocal] so Gradle's parallel test
 * workers do not contaminate one another. The production CLI is single-shot
 * and single-threaded, so the ThreadLocal collapses to one slot in practice.
 * Tests MUST call [reset] in `@After` to free the value on the worker thread.
 */
internal object DiagnosticContext {
    private val verboseFlag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    var verbose: Boolean
        get() = verboseFlag.get()
        set(value) {
            verboseFlag.set(value)
        }

    fun reset() {
        verboseFlag.remove()
    }
}
```

- [ ] **Step 2: Create SetupErrorRenderingTest.kt with the first failing test**

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.DiagnosticContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class SetupErrorRenderingTest {

    @Before
    fun resetFlag() {
        DiagnosticContext.reset()
    }

    @After
    fun clearFlag() {
        DiagnosticContext.reset()
    }

    @Test
    fun rendersMalformedJsonCategoryForJsonDecodingException() {
        val configFile = File("/tmp/proj/.claude/settings.json")
        val cause = runCatching { Json.parseToJsonElement("{") }.exceptionOrNull()!!
        val rendered = renderMergeFailure("claude", configFile, cause)

        assertTrue(
            "Expected prefix line",
            rendered.startsWith("Could not merge claude MCP config at ${configFile.absolutePath}."),
        )
        assertTrue("Expected MALFORMED_JSON category", rendered.contains("Category: MALFORMED_JSON"))
        assertTrue("Expected caused by line", rendered.contains("caused by"))
        assertTrue("Expected Fix line", rendered.contains("Fix:"))
        assertTrue(
            "Expected verbose hint when DiagnosticContext.verbose is false",
            rendered.contains("Re-run with --verbose"),
        )
    }
}
```

- [ ] **Step 3: Run the test and verify it fails to compile**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupErrorRenderingTest.rendersMalformedJsonCategoryForJsonDecodingException" --no-daemon
```

Expected: BUILD FAILED with `Unresolved reference: renderMergeFailure`. This is the intended failing state.

- [ ] **Step 4: Commit the failing test + DiagnosticContext**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/DiagnosticContext.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt
git commit -m "test(setup): failing test for renderMergeFailure + DiagnosticContext skeleton"
```

---

### Task 1B: Introduce SetupErrorRedactor (secret-leakage mitigation, spec §6.3)

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactor.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactorTest.kt`

- [ ] **Step 1: Write the redactor**

Create `SetupErrorRedactor.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

/**
 * Best-effort scrubbing of secrets and home paths out of error messages and
 * stack traces before they are printed to stderr. Defense-in-depth alongside
 * the documented "redact before pasting" guidance in
 * docs/guides/troubleshooting.md.
 */
internal object SetupErrorRedactor {

    private val SENSITIVE_KEY = Regex(
        "(?i)\"((?:api[-_]?key|token|secret|credential|password|bearer)[\\w-]*)\"\\s*[:=]\\s*\"[^\"]*\""
    )
    private val SENSITIVE_LONG_VALUE_AFTER_KEY = Regex(
        "(?i)((?:api[-_]?key|token|secret|credential|password|bearer)[\\w-]*\\s*[:=]\\s*)([^\\s,;}\\]]{20,})"
    )
    private val BEARER_HEADER = Regex("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._\\-/+=]+")
    private val USERS_HOME = Regex("/Users/[^/\\s\"]+")
    private val LINUX_HOME = Regex("/home/[^/\\s\"]+")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var out = text
        out = SENSITIVE_KEY.replace(out) { match ->
            "\"${match.groupValues[1]}\": \"***REDACTED***\""
        }
        out = SENSITIVE_LONG_VALUE_AFTER_KEY.replace(out, "$1***REDACTED***")
        out = BEARER_HEADER.replace(out, "$1 ***REDACTED***")
        out = USERS_HOME.replace(out, "/Users/<redacted>")
        out = LINUX_HOME.replace(out, "/home/<redacted>")
        return out
    }
}
```

- [ ] **Step 2: Write the redactor tests**

Create `SetupErrorRedactorTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupErrorRedactorTest {

    @Test
    fun masksJsonApiKeyValue() {
        val input = """{"api_key":"sk-live-abcdefghij","other":"ok"}"""
        val out = SetupErrorRedactor.redact(input)
        assertFalse("must not leak literal", out.contains("sk-live-abcdefghij"))
        assertTrue(out.contains("***REDACTED***"))
        assertTrue("non-secret key preserved", out.contains("\"other\":\"ok\""))
    }

    @Test
    fun masksTokenAndSecretAndPassword() {
        val out = SetupErrorRedactor.redact(
            """{"token":"abc","secret":"xyz","password":"hunter2"}""",
        )
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun masksHomePathOnMacOS() {
        val out = SetupErrorRedactor.redact("/Users/wooseung/proj/.claude/settings.json")
        assertTrue(out.contains("/Users/<redacted>/proj/.claude/settings.json"))
    }

    @Test
    fun masksHomePathOnLinux() {
        val out = SetupErrorRedactor.redact("/home/ci/.claude/settings.json")
        assertTrue(out.contains("/home/<redacted>/.claude/settings.json"))
    }

    @Test
    fun masksBearerHeader() {
        val out = SetupErrorRedactor.redact("Authorization: Bearer eyJhbGciOi.payload.sig")
        assertFalse(out.contains("eyJhbGciOi.payload.sig"))
        assertTrue(out.contains("Bearer ***REDACTED***"))
    }

    @Test
    fun masksLongValueAfterKeyAsFallback() {
        val out = SetupErrorRedactor.redact("api_key=sk-live-abcdefghijklmnopqrstuvwxyz")
        assertFalse("must not leak long value", out.contains("sk-live-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(out.contains("***REDACTED***"))
    }

    @Test
    fun leavesNonSensitiveTextAlone() {
        val out = SetupErrorRedactor.redact("Unexpected JSON token at offset 14")
        assertTrue(out == "Unexpected JSON token at offset 14")
    }
}
```

- [ ] **Step 3: Run the redactor tests**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupErrorRedactorTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 7 tests pass.

- [ ] **Step 4: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactor.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRedactorTest.kt
git commit -m "feat(setup): SetupErrorRedactor masks secrets, tokens, and home paths"
```

Note: Task 2's `renderMergeFailure` and Task 5's `printCliktError` must both
pipe outputs through `SetupErrorRedactor.redact(...)` (cause messages and
stack-trace lines). The wire-up is folded into the relevant Task 2 / Task 5
steps below.

---

### Task 2: Implement renderMergeFailure to make Task 1's test pass

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRendering.kt`

- [ ] **Step 1: Create SetupErrorRendering.kt**

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.DiagnosticContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

internal enum class SetupFailureCategory {
    MALFORMED_JSON,
    MALFORMED_MCPSERVERS_SHAPE,
    MALFORMED_TOML,
    FILESYSTEM_ERROR,
    UNKNOWN,
}

internal fun renderMergeFailure(writerName: String, configFile: File, error: Throwable): String {
    val category = classify(configFile, error)
    val builder = StringBuilder()
    builder.appendLine("Could not merge $writerName MCP config at ${SetupErrorRedactor.redact(configFile.absolutePath)}.")
    builder.appendLine("  Category: ${category.name}")
    builder.appendLine("  Cause:")
    renderCauseChain(error).forEach { builder.appendLine("    ${SetupErrorRedactor.redact(it)}") }
    builder.appendLine("  Fix: ${SetupErrorRedactor.redact(recommendation(category, configFile))}")
    if (!DiagnosticContext.verbose) {
        builder.append("  Re-run with --verbose for a full stack trace.")
    } else {
        // Trim trailing newline so callers can append the stack-trace block cleanly.
        if (builder.endsWith("\n")) builder.deleteCharAt(builder.length - 1)
    }
    return builder.toString()
}

internal fun classify(configFile: File, error: Throwable): SetupFailureCategory {
    if (isJsonDecodingFailure(error)) return SetupFailureCategory.MALFORMED_JSON
    if (isMcpServersShapeFailure(error)) return SetupFailureCategory.MALFORMED_MCPSERVERS_SHAPE
    if (isFilesystemFailure(error)) return SetupFailureCategory.FILESYSTEM_ERROR
    // CodexConfigWriter throws IllegalStateException from regex/section parsing
    // and IllegalArgumentException from key normalization. Both indicate TOML
    // shape problems and MUST be classified as MALFORMED_TOML, not UNKNOWN.
    if (configFile.name.endsWith(".toml") &&
        error !is SerializationException &&
        (error is IllegalStateException || error is IllegalArgumentException || causeChain(error).any { it is IllegalStateException || it is IllegalArgumentException })
    ) {
        return SetupFailureCategory.MALFORMED_TOML
    }
    if (configFile.name.endsWith(".toml") && error !is SerializationException) {
        return SetupFailureCategory.MALFORMED_TOML
    }
    return SetupFailureCategory.UNKNOWN
}

private fun isJsonDecodingFailure(error: Throwable): Boolean {
    // JsonDecodingException is internal to kotlinx-serialization-json. Match by
    // simple class name to stay decoupled, with SerializationException as the
    // public-supertype fallback.
    val chain = causeChain(error)
    return chain.any { it::class.simpleName == "JsonDecodingException" } ||
        chain.any { it is SerializationException }
}

private fun isMcpServersShapeFailure(error: Throwable): Boolean {
    if (error !is IllegalArgumentException) return false
    val msg = error.message ?: return false
    return msg.contains("\"mcpServers\"") && msg.contains("not a JSON object")
}

private fun isFilesystemFailure(error: Throwable): Boolean =
    causeChain(error).any { it is IOException && it !is SerializationException }

private fun recommendation(category: SetupFailureCategory, configFile: File): String = when (category) {
    SetupFailureCategory.MALFORMED_JSON ->
        "Open ${configFile.absolutePath}, fix the JSON syntax (the parse error above points at the offending position), " +
            "and re-run `fixthis setup --write`. If you cannot recover the file, back it up and delete it; " +
            "`fixthis setup --write` will create a fresh one."
    SetupFailureCategory.MALFORMED_MCPSERVERS_SHAPE ->
        "The `mcpServers` key already exists in ${configFile.absolutePath} but is not a JSON object. " +
            "Open the file and replace it with `{}` (an empty object), or remove the key entirely; " +
            "`fixthis setup --write` will add the FixThis entry."
    SetupFailureCategory.MALFORMED_TOML ->
        "Open ${configFile.absolutePath}, repair the TOML (errors above name the failure), and re-run. " +
            "If unsure, back up the file and delete the `[mcp_servers.fixthis]` section; " +
            "`fixthis setup --write` will rewrite it."
    SetupFailureCategory.FILESYSTEM_ERROR ->
        "Filesystem error reading ${configFile.absolutePath}. Check permissions with `ls -l ${configFile.absolutePath}`. " +
            "If the file is owned by another user, `chmod +r` or `chown` so your shell can read it."
    SetupFailureCategory.UNKNOWN ->
        "Please file an issue with the output of `fixthis setup --write --verbose` and the contents of " +
            "${configFile.absolutePath} (redact secrets first)."
}

internal fun renderCauseChain(top: Throwable, maxDepth: Int = 8): List<String> {
    val seen = IdentityHashMap<Throwable, Boolean>()
    val out = mutableListOf<String>()
    var current: Throwable? = top
    var depth = 0
    while (current != null) {
        if (seen.containsKey(current)) {
            out += "${"  ".repeat(depth)}(cause chain truncated: cycle detected)"
            return out
        }
        seen[current] = true
        if (depth >= maxDepth) {
            out += "${"  ".repeat(depth)}(cause chain truncated)"
            return out
        }
        val indent = "  ".repeat(depth)
        val name = current::class.simpleName ?: current.javaClass.name
        val msg = current.message ?: "(no message)"
        out += "${indent}caused by $name: $msg"
        val next = current.cause
        if (next === current) {
            out += "${"  ".repeat(depth + 1)}(cause chain truncated: self-reference)"
            return out
        }
        current = next
        depth += 1
    }
    return out
}

private fun causeChain(top: Throwable): Sequence<Throwable> {
    val seen = IdentityHashMap<Throwable, Boolean>()
    return generateSequence(top) { it.cause }
        .takeWhile { seen.put(it, true) == null }
}
```

- [ ] **Step 2: Re-run the failing test to confirm it now passes**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupErrorRenderingTest.rendersMalformedJsonCategoryForJsonDecodingException" --no-daemon
```

Expected: BUILD SUCCESSFUL. Exactly one test executed and passed.

- [ ] **Step 3: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRendering.kt
git commit -m "feat(setup): renderMergeFailure with category classification + cause chain"
```

---

### Task 3: Cover the remaining categories + cause-chain edge cases

**Files:**
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt`

- [ ] **Step 1: Append the remaining seven tests to SetupErrorRenderingTest**

Add the following inside the existing `class SetupErrorRenderingTest` (after the first test):

```kotlin
@Test
fun rendersMalformedMcpServersShapeCategoryWhenIllegalArgumentMentionsMcpServers() {
    val configFile = File("/tmp/proj/.claude/settings.json")
    val cause = IllegalArgumentException(
        "\"mcpServers\" in existing .claude/settings.json is not a JSON object (found JsonArray). " +
            "Fix the file manually before running fixthis setup.",
    )
    val rendered = renderMergeFailure("claude", configFile, cause)
    assertTrue(
        "Expected MALFORMED_MCPSERVERS_SHAPE category, got: $rendered",
        rendered.contains("Category: MALFORMED_MCPSERVERS_SHAPE"),
    )
    assertTrue(rendered.contains("replace it with `{}`"))
}

@Test
fun rendersMalformedTomlCategoryForCodexFailure() {
    val configFile = File("/tmp/home/.codex/config.toml")
    val cause = IllegalStateException("bad TOML at line 4")
    val rendered = renderMergeFailure("codex", configFile, cause)
    assertTrue(rendered.contains("Category: MALFORMED_TOML"))
    assertTrue(rendered.contains("[mcp_servers.fixthis]"))
}

@Test
fun rendersMalformedTomlCategoryForCodexIllegalArgument() {
    // CodexConfigWriter raises IllegalArgumentException from key normalization;
    // it must classify as MALFORMED_TOML, not UNKNOWN.
    val configFile = File("/tmp/home/.codex/config.toml")
    val cause = IllegalArgumentException("invalid key name \"!!\"")
    val rendered = renderMergeFailure("codex", configFile, cause)
    assertTrue(
        "Expected MALFORMED_TOML for IllegalArgumentException from CodexConfigWriter, got: $rendered",
        rendered.contains("Category: MALFORMED_TOML"),
    )
}

@Test
fun rendersFilesystemErrorCategoryForIoException() {
    val configFile = File("/tmp/proj/.claude/settings.json")
    val cause = java.io.IOException("Permission denied")
    val rendered = renderMergeFailure("claude", configFile, cause)
    assertTrue(rendered.contains("Category: FILESYSTEM_ERROR"))
    assertTrue(rendered.contains("chmod"))
}

@Test
fun rendersUnknownCategoryForOpaqueException() {
    val configFile = File("/tmp/proj/.claude/settings.json")
    val cause = RuntimeException("something opaque")
    val rendered = renderMergeFailure("claude", configFile, cause)
    assertTrue(rendered.contains("Category: UNKNOWN"))
    assertTrue(rendered.contains("--verbose"))
}

@Test
fun walksCauseChainAndIndentsByDepth() {
    val deepest = RuntimeException("c")
    val middle = RuntimeException("b", deepest)
    val top = RuntimeException("a", middle)
    val lines = renderCauseChain(top)
    assertEquals(3, lines.size)
    assertTrue("first line indent 0", lines[0].startsWith("caused by"))
    assertTrue("second line indent 2", lines[1].startsWith("  caused by"))
    assertTrue("third line indent 4", lines[2].startsWith("    caused by"))
    assertTrue(lines[0].contains("a"))
    assertTrue(lines[2].contains("c"))
}

@Test
fun truncatesPathologicalSelfReferenceCause() {
    val self = object : RuntimeException("self") {
        override fun getCause(): Throwable = this
    }
    val lines = renderCauseChain(self)
    assertTrue(
        "Expected truncation marker, got: $lines",
        lines.last().contains("self-reference") || lines.last().contains("cycle detected"),
    )
}

@Test
fun verboseHintOmittedWhenDiagnosticContextVerboseTrue() {
    val configFile = File("/tmp/proj/.claude/settings.json")
    val cause = RuntimeException("opaque")
    DiagnosticContext.verbose = true
    val rendered = renderMergeFailure("claude", configFile, cause)
    assertTrue(
        "Did not expect --verbose hint when verbose is true, got: $rendered",
        !rendered.contains("Re-run with --verbose"),
    )
}
```

Add the missing import at the top of the file:

```kotlin
import kotlin.test.assertEquals
```

- [ ] **Step 2: Run all renderer tests**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupErrorRenderingTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 8 tests run, 8 pass.

- [ ] **Step 3: Commit**

```bash
git add fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupErrorRenderingTest.kt
git commit -m "test(setup): cover all SetupFailureCategory branches + cause-chain edges"
```

---

### Task 4: Wire renderMergeFailure into SetupCommand and add --verbose flag

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt`

- [ ] **Step 1: Write failing test in SetupCommandTest**

Add at the end of `class SetupCommandTest`:

```kotlin
@Test
fun mergeErrorIncludesCategoryAndFixForMalformedJson() {
    val projectRoot = temporaryFolder.newFolder("project").canonicalFile
    val userHome = temporaryFolder.newFolder("home")
    val claudeSettings = projectRoot.resolve(".claude/settings.json")
    claudeSettings.parentFile.mkdirs()
    claudeSettings.writeText("""{"mcpServers":""")   // truncated JSON

    val error = withUserHome(userHome) {
        assertThrows(CliktError::class.java) {
            SetupCommand().parse(
                listOf(
                    "--package", "io.github.beyondwin.fixthis.sample",
                    "--project-dir", projectRoot.absolutePath,
                    "--write",
                    "--target", "claude",
                ),
            )
        }
    }

    val message = error.message!!
    assertTrue(
        "Expected Category line, got: $message",
        message.contains("Category: MALFORMED_JSON"),
    )
    assertTrue("Expected Fix line", message.contains("Fix:"))
    assertNotNull("Expected cause attached", error.cause)
}

@Test
fun verboseFlagSetsDiagnosticContext() {
    val projectRoot = temporaryFolder.newFolder("project").canonicalFile
    val userHome = temporaryFolder.newFolder("home")
    val claudeSettings = projectRoot.resolve(".claude/settings.json")
    claudeSettings.parentFile.mkdirs()
    claudeSettings.writeText("""{"mcpServers":""")

    DiagnosticContext.reset()
    withUserHome(userHome) {
        runCatching {
            SetupCommand().parse(
                listOf(
                    "--package", "io.github.beyondwin.fixthis.sample",
                    "--project-dir", projectRoot.absolutePath,
                    "--write",
                    "--target", "claude",
                    "--verbose",
                ),
            )
        }
    }
    assertTrue("Expected DiagnosticContext.verbose = true after --verbose run", DiagnosticContext.verbose)
    DiagnosticContext.reset()
}
```

Add the import at the top of the file (or confirm it is present):

```kotlin
import io.github.beyondwin.fixthis.cli.DiagnosticContext
```

- [ ] **Step 2: Update the existing assertion that pins the old colon-style suffix**

Find the existing assertion in `SetupCommandTest` (introduced by the 2026-05-09 polish plan, Task 1 Step 4):

```kotlin
assertTrue(
    "Expected message to start with merge error prefix",
    error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}:"),
)
```

Change the colon to a period:

```kotlin
assertTrue(
    "Expected message to start with merge error prefix",
    error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}."),
)
```

- [ ] **Step 3: Run the two new tests — they should fail**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupCommandTest.mergeErrorIncludesCategoryAndFixForMalformedJson" \
  --tests "io.github.beyondwin.fixthis.cli.commands.SetupCommandTest.verboseFlagSetsDiagnosticContext" --no-daemon
```

Expected: both FAIL. First fails because the message lacks `Category:` (still uses Polish-era format). Second fails because `--verbose` is not a recognized option yet (Clikt parser will throw `NoSuchOption`).

- [ ] **Step 4: Modify SetupCommand.kt**

In `SetupCommand.kt`, add the `--verbose` option directly under the existing options (after `serverName`):

```kotlin
private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)
```

Add a `DiagnosticContext` import at the top of the file:

```kotlin
import io.github.beyondwin.fixthis.cli.DiagnosticContext
```

At the top of `override fun run()`, before the existing first statement (`val root = File(projectDir).canonicalFile`), insert:

```kotlin
if (verbose) {
    DiagnosticContext.verbose = true
}
```

Replace the existing catch in `buildWritePlans` (currently):

```kotlin
} catch (e: Exception) {
    throw CliktError(
        "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
        cause = e,
    )
}
```

with:

```kotlin
} catch (e: Exception) {
    throw CliktError(
        renderMergeFailure(writer.name, configFile, e),
        cause = e,
    )
}
```

- [ ] **Step 5: Run all SetupCommandTest tests**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.commands.SetupCommandTest" --no-daemon
```

Expected: BUILD SUCCESSFUL. Both new tests pass. The previously-updated assertion (Step 2) passes. All other SetupCommandTest tests pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "feat(setup): --verbose flag + categorized merge-failure output"
```

---

### Task 5: Print cause stack trace at the Main.kt boundary when verbose is on

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainPrintTest.kt`

- [ ] **Step 1: Write the failing test for the new printing helper**

Create `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainPrintTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CliktError
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainPrintTest {

    private val originalErr = System.err
    private val originalOut = System.out
    private lateinit var errBuf: ByteArrayOutputStream
    private lateinit var outBuf: ByteArrayOutputStream

    @Before
    fun captureStreams() {
        errBuf = ByteArrayOutputStream()
        outBuf = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuf))
        System.setOut(PrintStream(outBuf))
        DiagnosticContext.reset()
    }

    @After
    fun restoreStreams() {
        System.setErr(originalErr)
        System.setOut(originalOut)
        DiagnosticContext.reset()
    }

    @Test
    fun printsStackTraceWhenDiagnosticContextVerboseTrue() {
        DiagnosticContext.verbose = true
        val cause = RuntimeException("inner reason")
        val error = CliktError("outer message", cause = cause)

        renderCliktErrorForTest(error)

        val errOut = errBuf.toString()
        assertTrue("Expected outer message", errOut.contains("outer message"))
        // Use a regex that matches an actual JVM stack-frame line (e.g.
        // `at io.github.beyondwin.fixthis.cli.MainPrintTest.foo(MainPrintTest.kt:42)`)
        // instead of the bare substring "at ", which appears in the outer
        // message text and gives a false positive.
        val stackFrameRegex = Regex("""at .+\(.+\.kt:\d+\)""")
        assertTrue(
            "Expected JVM stack-frame line, got: $errOut",
            stackFrameRegex.containsMatchIn(errOut),
        )
        assertTrue("Expected inner class name", errOut.contains("RuntimeException"))
    }

    @Test
    fun omitsStackTraceWhenDiagnosticContextVerboseFalse() {
        DiagnosticContext.verbose = false
        val cause = RuntimeException("inner reason")
        val error = CliktError("outer message", cause = cause)

        renderCliktErrorForTest(error)

        val errOut = errBuf.toString()
        assertTrue("Expected outer message printed", errOut.contains("outer message"))
        assertFalse("Did not expect stack-trace frames", errOut.contains("\tat "))
    }
}
```

- [ ] **Step 2: Run the failing test — it should fail to compile**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.MainPrintTest" --no-daemon
```

Expected: BUILD FAILED with `Unresolved reference: renderCliktErrorForTest`.

- [ ] **Step 3: Extract the print-and-exit path in Main.kt into a testable function**

Current `Main.kt`:

```kotlin
fun main(args: Array<String>) {
    val command = CoreNoOpCliktCommand(name = "fixthis")
        .subcommands(
            StatusCommand(),
            RunCommand(),
            DoctorCommand(),
            SetupCommand(),
            McpCommand(),
            ConsoleCommand(),
            CleanCommand(),
        )
    try {
        command.parse(args)
    } catch (error: CliktError) {
        command.getFormattedHelp(error)
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                if (error.printError) {
                    System.err.println(message)
                } else {
                    System.out.println(message)
                }
            }
        exitProcess(error.statusCode)
    }
}
```

Replace the entire file with:

```kotlin
package io.github.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.github.beyondwin.fixthis.cli.commands.CleanCommand
import io.github.beyondwin.fixthis.cli.commands.ConsoleCommand
import io.github.beyondwin.fixthis.cli.commands.DoctorCommand
import io.github.beyondwin.fixthis.cli.commands.McpCommand
import io.github.beyondwin.fixthis.cli.commands.RunCommand
import io.github.beyondwin.fixthis.cli.commands.SetupCommand
import io.github.beyondwin.fixthis.cli.commands.StatusCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = buildRootCommand()
    try {
        command.parse(args)
    } catch (error: CliktError) {
        printCliktError(command, error)
        exitProcess(error.statusCode)
    }
}

internal fun buildRootCommand(): CoreNoOpCliktCommand = CoreNoOpCliktCommand(name = "fixthis")
    .subcommands(
        StatusCommand(),
        RunCommand(),
        DoctorCommand(),
        SetupCommand(),
        McpCommand(),
        ConsoleCommand(),
        CleanCommand(),
    )

internal fun printCliktError(command: CoreNoOpCliktCommand, error: CliktError) {
    command.getFormattedHelp(error)
        ?.takeIf { it.isNotBlank() }
        ?.let { message ->
            if (error.printError) {
                System.err.println(message)
            } else {
                System.out.println(message)
            }
        }
    if (DiagnosticContext.verbose) {
        error.cause?.let {
            // Defense-in-depth: stack traces can contain home paths or echoed
            // settings.json fragments. Route through SetupErrorRedactor before
            // printing.
            System.err.println(io.github.beyondwin.fixthis.cli.commands.SetupErrorRedactor.redact(it.stackTraceToString()))
        }
    }
}

// Test-only convenience: skips command construction by reusing buildRootCommand.
internal fun renderCliktErrorForTest(error: CliktError) {
    printCliktError(buildRootCommand(), error)
}
```

- [ ] **Step 4: Re-run the test — it should now pass**

```bash
./gradlew :fixthis-cli:test --tests "io.github.beyondwin.fixthis.cli.MainPrintTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 5: Run the full :fixthis-cli:test suite**

```bash
./gradlew :fixthis-cli:test --no-daemon
```

Expected: BUILD SUCCESSFUL. No previously green test regresses.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainPrintTest.kt
git commit -m "feat(setup): print cause stack trace at Main when --verbose set"
```

---

### Task 6: Document the new flag and category taxonomy

**Files:**
- Modify: `docs/reference/cli.md`
- Modify: `docs/guides/troubleshooting.md`
- Modify: `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md`

- [ ] **Step 1: Locate and update the `setup` flag table in docs/reference/cli.md**

Open `docs/reference/cli.md`, find the `## setup` section's flag table. Add a row:

```markdown
| `--verbose`, `-v` | Print the full Java stack trace on failure. Implies the cause chain is rendered too, but skipped by default to keep the terse error readable. | flag (default off) |
```

(If the existing table uses different column order, match it; the keys are flag name, description, type.)

- [ ] **Step 2: Add a "Setup failures" section to docs/guides/troubleshooting.md**

Append to `docs/guides/troubleshooting.md` (before the existing trailing section if any), a new heading:

```markdown
## Setup failures

`fixthis setup --write` fails when an existing agent config file cannot be parsed. The error message classifies the cause and recommends an action.

| Category | Meaning | Recommended action |
|---|---|---|
| `MALFORMED_JSON` | `.claude/settings.json` is not valid JSON. | Fix the JSON syntax shown in the `caused by` line, or back up + delete the file and re-run. |
| `MALFORMED_MCPSERVERS_SHAPE` | The `mcpServers` key exists but is not a JSON object. | Replace its value with `{}` or remove the key; re-run. |
| `MALFORMED_TOML` | `~/.codex/config.toml` has a TOML syntax error in the `[mcp_servers.fixthis]` section. | Back up the file, remove the `[mcp_servers.fixthis]` block, and re-run. |
| `FILESYSTEM_ERROR` | The config file cannot be read (permissions, missing directory). | Check `ls -l <path>`; fix ownership or permissions with `chmod`/`chown`. |
| `UNKNOWN` | Anything else. | Re-run with `--verbose` for a stack trace and file an issue. |

For maximum detail, append `--verbose` to any failing `fixthis setup` invocation. The same categorized message is printed, followed by a full Java stack trace including nested causes.
```

- [ ] **Step 3: Add a supersession note to the 2026-05-09 polish spec**

Open `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md`. Under the `# FixThis Setup Polish — Code Spec` heading (line 1), before `## Goal`, insert:

```markdown
> **Update 2026-05-14:** Change 1 (cause preservation) is extended by
> [`2026-05-14-setup-error-diagnostics-design.md`](2026-05-14-setup-error-diagnostics-design.md),
> which adds full cause-chain printing, category classification, and a `--verbose`
> flag. Changes 2/3/4 are unchanged.
```

- [ ] **Step 4: Verify doc edits did not break any linked-anchor tests**

This repo does not run link-check in unit tests, so the verification is a manual `grep`:

```bash
grep -n "2026-05-14-setup-error-diagnostics-design" docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md
grep -n "MALFORMED_JSON\|Setup failures" docs/guides/troubleshooting.md
grep -n "verbose" docs/reference/cli.md | head
```

Expected: each grep returns ≥1 match.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/cli.md docs/guides/troubleshooting.md \
        docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md
git commit -m "docs(setup): document --verbose flag and category taxonomy"
```

---

### Task 7: End-to-end smoke against the installed CLI

**Files:** (no code changes)

- [ ] **Step 1: Build the CLI**

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Create a scratch project with a truncated settings.json**

```bash
SCRATCH="$(mktemp -d)"
mkdir -p "$SCRATCH/.fixthis" "$SCRATCH/.claude"
printf '{"applicationId":"io.github.beyondwin.fixthis.sample"}' > "$SCRATCH/.fixthis/project.json"
printf '{"mcpServers":' > "$SCRATCH/.claude/settings.json"
echo "$SCRATCH"
```

- [ ] **Step 3: Run setup without --verbose; assert the categorized output**

```bash
./fixthis-cli/build/install/fixthis/bin/fixthis setup \
    --package io.github.beyondwin.fixthis.sample \
    --project-dir "$SCRATCH" \
    --write --target claude 2>&1 | tee "$SCRATCH/out.txt"
```

Expected exit code: non-zero. `$SCRATCH/out.txt` contains lines matching:

```
Could not merge claude MCP config at <SCRATCH>/.claude/settings.json.
  Category: MALFORMED_JSON
  Cause:
    caused by ...
  Fix: Open <SCRATCH>/.claude/settings.json, fix the JSON syntax ...
  Re-run with --verbose for a full stack trace.
```

Verify with:

```bash
grep -q "Category: MALFORMED_JSON" "$SCRATCH/out.txt" && echo PASS || echo FAIL
grep -q "Re-run with --verbose" "$SCRATCH/out.txt" && echo PASS || echo FAIL
```

Both should print `PASS`.

- [ ] **Step 4: Re-run with --verbose; assert stack trace appears and hint is gone**

```bash
./fixthis-cli/build/install/fixthis/bin/fixthis setup \
    --package io.github.beyondwin.fixthis.sample \
    --project-dir "$SCRATCH" \
    --write --target claude --verbose 2>&1 | tee "$SCRATCH/out-verbose.txt"
```

Verify:

```bash
grep -q "Category: MALFORMED_JSON" "$SCRATCH/out-verbose.txt" && echo PASS || echo FAIL
grep -q "Re-run with --verbose" "$SCRATCH/out-verbose.txt" && echo "FAIL (should be absent)" || echo PASS
grep -q "^	at \|^    at " "$SCRATCH/out-verbose.txt" && echo PASS || echo FAIL
```

All three should print `PASS`.

- [ ] **Step 5: Healthy run regression**

```bash
rm -f "$SCRATCH/.claude/settings.json"
./fixthis-cli/build/install/fixthis/bin/fixthis setup \
    --package io.github.beyondwin.fixthis.sample \
    --project-dir "$SCRATCH" \
    --write --target claude 2>&1 | tee "$SCRATCH/out-ok.txt"
echo "exit=$?"
grep -q "Wrote claude MCP config (project-local)" "$SCRATCH/out-ok.txt" && echo PASS || echo FAIL
```

Expected: `exit=0` and `PASS`.

- [ ] **Step 6: Clean up**

```bash
rm -rf "$SCRATCH"
```

- [ ] **Step 7: No commit (verification-only task)**

If any PASS/FAIL above flipped to FAIL, return to the relevant earlier task; do not commit a wrapper or "fix" here.

---

## Self-Review Checklist

- [x] G1 (cause chain) — Task 2 implements `renderCauseChain`, Task 5 prints the trace at Main boundary, Task 3 tests both.
- [x] G2 (categories + fixes) — Tasks 2, 3 implement and test all five categories.
- [x] G3 (`--verbose`) — Task 4 adds the flag, Task 5 wires it through Main.
- [x] G4 (boundary printing) — Task 5 extracts `printCliktError` and reads `DiagnosticContext`.
- [x] G5 (TDD) — Tasks 1, 3, 4, 5 all start with a failing test before the implementation step.
- [x] All file paths are absolute or rooted at the repo. No "TBD", no "similar to".
- [x] Existing assertion update (Task 4 Step 2) documented — colon → period in the prefix line.
- [x] Manual smoke (Task 7) exercises both verbose and non-verbose paths plus a healthy regression.
- [x] Docs updated (Task 6): cli.md flag table, troubleshooting category table, spec supersession note.
