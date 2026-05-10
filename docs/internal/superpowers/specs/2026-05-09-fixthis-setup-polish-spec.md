# FixThis Setup Polish — Code Spec

## Goal

Fix four quality gaps in `fixthis setup --write` identified during code review: swallowed exception details, silent `mcpServers` type mismatch, under-actionable executable-not-found warning, and invisible scope asymmetry (Codex global vs Claude project-local) in the output message.

## Scope

All changes are in `:fixthis-cli`. No other module is touched.

## Out of Scope

- Documentation changes (see `2026-05-09-fixthis-ai-discoverability-docs-spec.md`)
- `McpExecutableLocator` search algorithm changes — the classpath-scan approach stays; only the warning text improves
- Codex vs Claude scope asymmetry in behavior — this is intentional design; only the output message improves

---

## Change 1: SetupCommand — Preserve Exception Cause in Error Message

### File

`fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`

### Problem

Lines 83–88 catch any `Exception` during config merge and throw a generic `CliktError` that discards the original exception message. A malformed `settings.json` or TOML parse failure surfaces as:

```
Could not merge claude MCP config at /project/.claude/settings.json.
```

With no indication of what was wrong. The user has no idea whether to fix JSON syntax, delete the file, or something else.

### Change

```kotlin
// BEFORE (lines 83-88):
val merged = try {
    val current = configFile.takeIf { it.isFile }?.readText()
    writer.merge(current, entry)
} catch (_: Exception) {
    throw CliktError("Could not merge ${writer.name} MCP config at ${configFile.absolutePath}.")
}

// AFTER:
val merged = try {
    val current = configFile.takeIf { it.isFile }?.readText()
    writer.merge(current, entry)
} catch (e: Exception) {
    throw CliktError(
        "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
        cause = e,
    )
}
```

### Affected Test

`SetupCommandTest.targetAllPreflightsMergesBeforeWritingAnyConfig` asserts the exact error message. The assertion must change from `assertEquals` to `startsWith` because the cause message is now appended:

```kotlin
// BEFORE:
assertEquals("Could not merge claude MCP config at ${claudeSettings.absolutePath}.", error.message)

// AFTER:
assertTrue(
    "Expected message to start with merge error prefix",
    error.message!!.startsWith("Could not merge claude MCP config at ${claudeSettings.absolutePath}:"),
)
```

### Acceptance Criteria

- Running `fixthis setup --write --target claude` on a project whose `.claude/settings.json` contains `{"mcpServers":` (truncated JSON) now prints a message containing both the file path and the JSON parse error detail.
- All existing `SetupCommandTest` and `AgentConfigWriterTest` tests pass.

---

## Change 2: ClaudeConfigWriter — Explicit mcpServers Type Validation

### File

`fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`

### Problem

Line 24:
```kotlin
val existingServers = root["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
```

If a user has manually edited `.claude/settings.json` and set `"mcpServers"` to a non-object value (e.g. `null`, `[]`, or `"string"`), the `.jsonObject` property throws `IllegalArgumentException: JsonElement is not a JsonObject`. This reaches the catch in Change 1 and surfaces as:

```
Could not merge claude MCP config at /project/.claude/settings.json: JsonElement is not a JsonObject
```

This is technically better after Change 1, but still cryptic. An explicit check produces an actionable message.

### Change

```kotlin
// BEFORE (lines 22-26):
override fun merge(current: String?, entry: McpConfigEntry): String {
    val root = current
        ?.takeIf { it.isNotBlank() }
        ?.let { fixThisJson.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val existingServers = root["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())

// AFTER:
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

### New Test Cases (in AgentConfigWriterTest)

```kotlin
@Test
fun claudeMergeThrowsWhenMcpServersIsArray() {
    val current = """{"mcpServers":[]}"""
    val entry = McpConfigEntry(
        serverName = "fixthis",
        command = "/bin/fixthis-mcp",
        args = listOf("--package", "io.test", "--project-dir", "/repo"),
    )
    val ex = assertThrows(IllegalArgumentException::class.java) {
        ClaudeConfigWriter().merge(current, entry)
    }
    assertTrue(ex.message!!.contains("\"mcpServers\""))
    assertTrue(ex.message!!.contains("not a JSON object"))
    assertTrue(ex.message!!.contains("Fix the file manually"))
}

@Test
fun claudeMergeThrowsWhenMcpServersIsString() {
    val current = """{"mcpServers":"wrong"}"""
    val entry = McpConfigEntry(
        serverName = "fixthis",
        command = "/bin/fixthis-mcp",
        args = listOf("--package", "io.test", "--project-dir", "/repo"),
    )
    val ex = assertThrows(IllegalArgumentException::class.java) {
        ClaudeConfigWriter().merge(current, entry)
    }
    assertTrue(ex.message!!.contains("not a JSON object"))
}

@Test
fun claudeMergeAcceptsNullMcpServers() {
    val current = """{"otherKey":"value"}"""
    val entry = McpConfigEntry(
        serverName = "fixthis",
        command = "/bin/fixthis-mcp",
        args = listOf("--package", "io.test", "--project-dir", "/repo"),
    )
    val merged = ClaudeConfigWriter().merge(current, entry)
    assertTrue(merged.contains("\"fixthis\""))
    assertTrue(merged.contains("\"otherKey\""))
}
```

### Acceptance Criteria

- `ClaudeConfigWriter.merge` throws `IllegalArgumentException` with an actionable message when `mcpServers` is not a JSON object.
- `ClaudeConfigWriter.merge` succeeds when `mcpServers` key is absent.
- All three new tests pass. All existing `AgentConfigWriterTest` tests pass.

---

## Change 3: SetupCommand — Actionable fixthis-mcp Not-Found Warning

### File

`fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`

### Problem

Lines 47–52 print a warning when `fixthis-mcp` is not found:

```
Warning: fixthis-mcp executable not found; writing MCP config with `fixthis mcp`.
Ensure `fixthis` is on PATH or run `./gradlew :fixthis-mcp:installDist`.
```

The warning is easy to miss (one line on stderr) and does not explain that the written config will actively fail at MCP client startup. Users may run `fixthis setup --write`, think it succeeded, and only discover the problem when their AI agent fails to connect.

### Change

```kotlin
// BEFORE (lines 47-52):
if (executable == null) {
    echo(
        "Warning: fixthis-mcp executable not found; writing MCP config with `fixthis mcp`. " +
            "Ensure `fixthis` is on PATH or run `./gradlew :fixthis-mcp:installDist`.",
        err = true,
    )
}

// AFTER:
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

### No New Tests Required

The warning text is a plain string change. Existing behavior (write proceeds with fallback command) is unchanged. The existing integration test `SetupCommandTest` does not assert warning text content, so no test updates are needed.

### Acceptance Criteria

- Running `fixthis setup --write --target claude` when `fixthis-mcp` is not on PATH and not in the build output prints a multi-line warning on stderr explaining the failure consequence and the fix command.
- The written config file is still created (behavior unchanged).

---

## Change 4: AgentConfigWriter — Scope Annotation in Output

### Files

- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt`
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt`
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`

### Problem

`--target all` writes:
- Claude → project-local `.claude/settings.json` (only this project's AI sessions)
- Codex → user-global `~/.codex/config.toml` (ALL Codex sessions on this machine)

The output is:
```
Wrote claude MCP config: /project/.claude/settings.json
Wrote codex MCP config: /Users/kws/.codex/config.toml
```

The Codex entry is global and affects unrelated projects. This is intentional design but invisible to the user. A user who later wonders why FixThis MCP shows up in every Codex project cannot easily trace it back to this command.

### Change

Add a `scope` property to `AgentConfigWriter`:

```kotlin
// AgentConfigWriter.kt — add property:
internal interface AgentConfigWriter {
    val name: String
    val scope: String   // "project-local" or "global"
    fun configFile(projectRoot: File, userHome: File = File(System.getProperty("user.home"))): File
    fun merge(current: String?, entry: McpConfigEntry): String
}
```

Implement in each writer:

```kotlin
// ClaudeConfigWriter.kt:
internal class ClaudeConfigWriter : AgentConfigWriter {
    override val name: String = "claude"
    override val scope: String = "project-local"
    // ...
}

// CodexConfigWriter.kt:
internal class CodexConfigWriter : AgentConfigWriter {
    override val name: String = "codex"
    override val scope: String = "global"
    // ...
}
```

Surface scope in `AgentConfigWritePlan` and `applyWritePlan` output:

```kotlin
// SetupCommand.kt — AgentConfigWritePlan:
private data class AgentConfigWritePlan(
    val writerName: String,
    val scope: String,
    val configFile: File,
    val content: String,
)

// SetupCommand.kt — buildWritePlans:
writers.map { writer ->
    val configFile = writer.configFile(projectRoot)
    val merged = try {
        val current = configFile.takeIf { it.isFile }?.readText()
        writer.merge(current, entry)
    } catch (e: Exception) {
        throw CliktError(
            "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
            cause = e,
        )
    }
    AgentConfigWritePlan(writer.name, writer.scope, configFile, merged)
}

// SetupCommand.kt — applyWritePlan:
private fun applyWritePlan(plan: AgentConfigWritePlan, dryRun: Boolean) {
    val configFile = plan.configFile
    val merged = plan.content
    if (dryRun) {
        echo("Target: ${plan.writerName} (${plan.scope})")
        echo("Path: ${configFile.absolutePath}")
        echo(merged.trimEnd())
        return
    }
    try {
        AtomicConfigFileWriter.write(configFile, merged)
    } catch (_: Exception) {
        throw CliktError("Could not write ${plan.writerName} MCP config at ${configFile.absolutePath}.")
    }
    echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${configFile.absolutePath}")
}
```

### Expected Output After Change

```
Wrote claude MCP config (project-local): /project/.claude/settings.json
Wrote codex MCP config (global): /Users/kws/.codex/config.toml
```

### New Test Cases (in AgentConfigWriterTest or SetupCommandTest)

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

### Acceptance Criteria

- `ClaudeConfigWriter.scope == "project-local"`
- `CodexConfigWriter.scope == "global"`
- `fixthis setup --write --target all` output includes `(project-local)` and `(global)` labels.
- `fixthis setup --write --dry-run --target all` output includes scope labels on the "Target:" line.
- All existing tests pass. Two new scope tests pass.

---

## Test Command

All changes are in `:fixthis-cli`. Run after each task:

```bash
./gradlew :fixthis-cli:test
```

Expected: all tests pass with no new failures.

## Rollout Order

Changes 1 and 2 are coupled (Change 2 relies on Change 1's improved catch). Implement in order: 1 → 2 → 3 → 4. Changes 3 and 4 are independent of each other and of 1/2.
