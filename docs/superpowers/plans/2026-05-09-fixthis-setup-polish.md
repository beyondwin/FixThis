# FixThis Setup Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix four quality gaps in `fixthis setup --write`: swallowed exception details, silent `mcpServers` type mismatch, under-actionable executable-not-found warning, and invisible Codex-global/Claude-project-local scope asymmetry.

**Architecture:** All changes are in `:fixthis-cli`. Tasks 1→2 are ordered (Change 2 relies on Change 1's improved catch). Tasks 3 and 4 are independent. No other module is touched.

**Tech Stack:** Kotlin, JUnit 4 (`org.junit.*`), Clikt (`com.github.ajalt.clikt.*`), kotlinx-serialization-json.

**Spec:** `docs/superpowers/specs/2026-05-09-fixthis-setup-polish-spec.md`

---

## File Map

| Action | Path |
|--------|------|
| Modify | `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt` |
| Modify | `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt` |
| Modify | `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt` |
| Modify | `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt` |
| Modify | `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt` |
| Modify | `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommandTest.kt` |

Test command (run after every task): `./gradlew :fixthis-cli:test`

---

### Task 1: Preserve Exception Cause in SetupCommand Error Message

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommandTest.kt`

- [ ] **Step 1: Write the failing test first**

Open `SetupCommandTest.kt`. The existing test `targetAllPreflightsMergesBeforeWritingAnyConfig` currently asserts:

```kotlin
assertEquals("Could not merge claude MCP config at ${claudeSettings.absolutePath}.", error.message)
```

This is the test that will break when we change the message format. We need a new test that asserts the cause is included, and we'll update the existing one at the same time. Add the following new test at the end of `SetupCommandTest`:

```kotlin
@Test
fun mergeErrorIncludesCauseMessageForDebugging() {
    val projectRoot = temporaryFolder.newFolder("project").canonicalFile
    val userHome = temporaryFolder.newFolder("home")
    val claudeSettings = projectRoot.resolve(".claude/settings.json")
    claudeSettings.parentFile.mkdirs()
    claudeSettings.writeText("""{"mcpServers":""")   // truncated JSON

    val error = withUserHome(userHome) {
        assertThrows(CliktError::class.java) {
            SetupCommand().parse(
                listOf(
                    "--package", "io.beyondwin.fixthis.sample",
                    "--project-dir", projectRoot.absolutePath,
                    "--write",
                    "--target", "claude",
                ),
            )
        }
    }

    assertTrue(
        "Expected message to contain file path",
        error.message!!.contains(claudeSettings.absolutePath),
    )
    assertTrue(
        "Expected message to contain cause detail",
        error.message!!.length > "Could not merge claude MCP config at ${claudeSettings.absolutePath}:".length,
    )
    assertNotNull("Expected cause to be set", error.cause)
}
```

- [ ] **Step 2: Run new test to verify it fails**

```bash
./gradlew :fixthis-cli:test --tests "io.beyondwin.fixthis.cli.commands.SetupCommandTest.mergeErrorIncludesCauseMessageForDebugging"
```

Expected: FAILED — message does not contain cause detail (current code uses `catch (_: Exception)` which discards cause).

- [ ] **Step 3: Apply the fix in SetupCommand.kt**

Find the `buildWritePlans` method (around line 75). Change the catch block from:

```kotlin
} catch (_: Exception) {
    throw CliktError("Could not merge ${writer.name} MCP config at ${configFile.absolutePath}.")
}
```

to:

```kotlin
} catch (e: Exception) {
    throw CliktError(
        "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
        cause = e,
    )
}
```

- [ ] **Step 4: Update the existing broken assertion in SetupCommandTest**

The test `targetAllPreflightsMergesBeforeWritingAnyConfig` currently has:

```kotlin
assertEquals("Could not merge claude MCP config at ${claudeSettings.absolutePath}.", error.message)
```

Change it to:

```kotlin
assertTrue(
    "Expected message to start with merge error prefix",
    error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}:"),
)
```

- [ ] **Step 5: Run all CLI tests**

```bash
./gradlew :fixthis-cli:test
```

Expected: BUILD SUCCESSFUL, all tests pass including `mergeErrorIncludesCauseMessageForDebugging`.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "fix(setup): preserve exception cause in merge error message"
```

---

### Task 2: Explicit mcpServers Type Validation in ClaudeConfigWriter

**Files:**
- Modify: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`

- [ ] **Step 1: Write three failing tests**

Open `AgentConfigWriterTest.kt`. The existing `entry` field can be reused. Add a local entry for the new tests or reuse the class-level one. Add the following three tests after the existing tests:

```kotlin
@Test
fun claudeMergeThrowsWhenMcpServersIsArray() {
    val current = """{"mcpServers":[]}"""
    val ex = assertThrows(IllegalArgumentException::class.java) {
        ClaudeConfigWriter().merge(current, entry)
    }
    assertTrue(
        "Expected message to mention mcpServers",
        ex.message!!.contains("\"mcpServers\""),
    )
    assertTrue(
        "Expected message to say not a JSON object",
        ex.message!!.contains("not a JSON object"),
    )
    assertTrue(
        "Expected message to say fix manually",
        ex.message!!.contains("Fix the file manually"),
    )
}

@Test
fun claudeMergeThrowsWhenMcpServersIsString() {
    val current = """{"mcpServers":"wrong"}"""
    val ex = assertThrows(IllegalArgumentException::class.java) {
        ClaudeConfigWriter().merge(current, entry)
    }
    assertTrue(ex.message!!.contains("not a JSON object"))
}

@Test
fun claudeMergeAcceptsAbsentMcpServers() {
    val current = """{"otherKey":"value"}"""
    val merged = ClaudeConfigWriter().merge(current, entry)
    assertTrue("Expected fixthis server to appear", merged.contains("\"fixthis\""))
    assertTrue("Expected other keys preserved", merged.contains("\"otherKey\""))
}
```

Note: `entry` is already defined at the class level in `AgentConfigWriterTest`.

- [ ] **Step 2: Run new tests to verify they fail correctly**

```bash
./gradlew :fixthis-cli:test --tests "io.beyondwin.fixthis.cli.commands.AgentConfigWriterTest.claudeMergeThrowsWhenMcpServersIsArray" \
  --tests "io.beyondwin.fixthis.cli.commands.AgentConfigWriterTest.claudeMergeThrowsWhenMcpServersIsString" \
  --tests "io.beyondwin.fixthis.cli.commands.AgentConfigWriterTest.claudeMergeAcceptsAbsentMcpServers"
```

Expected: `claudeMergeThrowsWhenMcpServersIsArray` and `claudeMergeThrowsWhenMcpServersIsString` FAIL (currently `jsonObject` throws a different exception without the expected message). `claudeMergeAcceptsAbsentMcpServers` may PASS already.

- [ ] **Step 3: Apply the fix in ClaudeConfigWriter.kt**

Open `ClaudeConfigWriter.kt`. The `merge` method currently reads:

```kotlin
override fun merge(current: String?, entry: McpConfigEntry): String {
    val root = current
        ?.takeIf { it.isNotBlank() }
        ?.let { fixThisJson.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val existingServers = root["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
```

Change it to:

```kotlin
override fun merge(current: String?, entry: McpConfigEntry): String {
    val root = current
        ?.takeIf { it.isNotBlank() }
        ?.let { fixThisJson.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val mcpServersElement = root["mcpServers"]
    if (mcpServersElement != null && mcpServersElement !is JsonObject) {
        throw IllegalArgumentException(
            "\"mcpServers\" in existing .claude/settings.json is not a JSON object " +
                "(found ${mcpServersElement::class.simpleName}). " +
                "Fix the file manually before running fixthis setup.",
        )
    }
    val existingServers = mcpServersElement?.jsonObject ?: JsonObject(emptyMap())
```

The closing brace and rest of the method remain unchanged.

- [ ] **Step 4: Run all CLI tests**

```bash
./gradlew :fixthis-cli:test
```

Expected: BUILD SUCCESSFUL, all three new tests pass, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt \
        fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt
git commit -m "fix(setup): explicit type check for mcpServers in ClaudeConfigWriter"
```

---

### Task 3: Actionable fixthis-mcp Not-Found Warning

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`

- [ ] **Step 1: Locate the warning (no test for this — string-only change)**

Open `SetupCommand.kt`. Find the block starting around line 47:

```kotlin
if (executable == null) {
    echo(
        "Warning: fixthis-mcp executable not found; writing MCP config with `fixthis mcp`. " +
            "Ensure `fixthis` is on PATH or run `./gradlew :fixthis-mcp:installDist`.",
        err = true,
    )
}
```

- [ ] **Step 2: Replace with multi-line actionable warning**

```kotlin
if (executable == null) {
    echo(
        "Warning: fixthis-mcp executable not found.\n" +
            "  The written config will use `fixthis mcp` as a command fallback.\n" +
            "  MCP clients will fail to start FixThis unless `fixthis` is on PATH.\n" +
            "  Fix: run `./gradlew :fixthis-mcp:installDist` then re-run `fixthis setup --write`.",
        err = true,
    )
}
```

- [ ] **Step 3: Run all CLI tests to verify no regressions**

```bash
./gradlew :fixthis-cli:test
```

Expected: BUILD SUCCESSFUL, same pass count as before.

- [ ] **Step 4: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt
git commit -m "fix(setup): expand fixthis-mcp not-found warning with actionable steps"
```

---

### Task 4: Scope Annotation in AgentConfigWriter Output

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt`

- [ ] **Step 1: Write failing tests for scope property**

Add the following two tests to the end of `AgentConfigWriterTest.kt`:

```kotlin
@Test
fun claudeWriterScopeIsProjectLocal() {
    assertEquals("project-local", ClaudeConfigWriter().scope)
}

@Test
fun codexWriterScopeIsGlobal() {
    assertEquals("global", CodexConfigWriter().scope)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :fixthis-cli:test --tests "io.beyondwin.fixthis.cli.commands.AgentConfigWriterTest.claudeWriterScopeIsProjectLocal" \
  --tests "io.beyondwin.fixthis.cli.commands.AgentConfigWriterTest.codexWriterScopeIsGlobal"
```

Expected: FAILED — `scope` property does not exist yet (compilation error).

- [ ] **Step 3: Add scope property to AgentConfigWriter interface**

Open `AgentConfigWriter.kt`. The current content is:

```kotlin
internal interface AgentConfigWriter {
    val name: String
    fun configFile(projectRoot: File, userHome: File = File(System.getProperty("user.home"))): File
    fun merge(current: String?, entry: McpConfigEntry): String
}
```

Change it to:

```kotlin
internal interface AgentConfigWriter {
    val name: String
    val scope: String
    fun configFile(projectRoot: File, userHome: File = File(System.getProperty("user.home"))): File
    fun merge(current: String?, entry: McpConfigEntry): String
}
```

- [ ] **Step 4: Implement scope in ClaudeConfigWriter**

Open `ClaudeConfigWriter.kt`. Add `override val scope: String = "project-local"` after the `override val name` line:

```kotlin
internal class ClaudeConfigWriter : AgentConfigWriter {
    override val name: String = "claude"
    override val scope: String = "project-local"
    // rest of class unchanged
```

- [ ] **Step 5: Implement scope in CodexConfigWriter**

Open `CodexConfigWriter.kt`. Add `override val scope: String = "global"` after the `override val name` line:

```kotlin
internal class CodexConfigWriter : AgentConfigWriter {
    override val name: String = "codex"
    override val scope: String = "global"
    // rest of class unchanged
```

- [ ] **Step 6: Add scope to AgentConfigWritePlan and wire through SetupCommand**

Open `SetupCommand.kt`. 

**6a.** Change `AgentConfigWritePlan` data class (near the bottom of `SetupCommand`):

```kotlin
// BEFORE:
private data class AgentConfigWritePlan(
    val writerName: String,
    val configFile: File,
    val content: String,
)

// AFTER:
private data class AgentConfigWritePlan(
    val writerName: String,
    val scope: String,
    val configFile: File,
    val content: String,
)
```

**6b.** Change `buildWritePlans` to pass `writer.scope` into the plan:

```kotlin
// Find: AgentConfigWritePlan(writer.name, configFile, merged)
// Replace with:
AgentConfigWritePlan(writer.name, writer.scope, configFile, merged)
```

**6c.** Change `applyWritePlan` to include scope in output messages:

```kotlin
// BEFORE:
if (dryRun) {
    echo("Target: ${plan.writerName}")
    echo("Path: ${configFile.absolutePath}")
    echo(merged.trimEnd())
    return
}
// ...
echo("Wrote ${plan.writerName} MCP config: ${configFile.absolutePath}")

// AFTER:
if (dryRun) {
    echo("Target: ${plan.writerName} (${plan.scope})")
    echo("Path: ${configFile.absolutePath}")
    echo(merged.trimEnd())
    return
}
// ...
echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${configFile.absolutePath}")
```

- [ ] **Step 7: Run all CLI tests**

```bash
./gradlew :fixthis-cli:test
```

Expected: BUILD SUCCESSFUL. Both new scope tests pass. All existing tests pass (no existing test asserts the output message format of `applyWritePlan`).

- [ ] **Step 8: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt \
        fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt \
        fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt \
        fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt
git commit -m "feat(setup): surface project-local/global scope in setup output"
```

---

## Self-Review Checklist

- [x] Spec coverage: Change 1 (Task 1), Change 2 (Task 2), Change 3 (Task 3), Change 4 (Task 4).
- [x] Placeholder scan: no "TBD", no "similar to", all code is verbatim.
- [x] Type consistency: `AgentConfigWritePlan.scope: String` defined in Task 4 Step 6a, referenced in Steps 6b and 6c.
- [x] Existing breaking test `targetAllPreflightsMergesBeforeWritingAnyConfig` updated in Task 1 Step 4.
- [x] Tasks 3 and 4 are independent — can be swapped in order if needed.
- [x] Tasks 1 and 2 are ordered — Task 2 benefits from Task 1's improved catch.
- [x] Test command included after every task.
