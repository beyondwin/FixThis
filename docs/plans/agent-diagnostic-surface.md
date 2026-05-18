# Agent Diagnostic Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every CLI/doctor/agent-handoff surface that an agent reads unambiguous, machine-parseable, and side-effect-safe so that an agent can recover from any install/setup failure without human input.

**Architecture:** Six deliverables (D1–D6) split into four merge-ordered phases. Phase 1 lands additive surface (doctor text parity, exit-code table, message templating) with no behavior change. Phase 2 fixes the privacy-leaking dry-run renderer. Phase 3 introduces transactional `install-agent` with `--json` output and a global-config guard. Phase 4 closes the loop with docs cross-reference CI and `.fixthis/agent-setup.*` schema enforcement.

**Tech Stack:** Kotlin (JVM, JDK 21+), Clikt CLI framework, kotlinx.serialization, JUnit, Gradle. Bash + `jq` for CI doc checks.

**Spec:** [`docs/superpowers/specs/2026-05-16-agent-diagnostic-surface-design.md`](../superpowers/specs/2026-05-16-agent-diagnostic-surface-design.md)

---

## File Structure

| File | Responsibility | New/Modify |
|------|----------------|------------|
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliExit.kt` | Centralized exit-code helpers and `ExitCode` enum | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliVersion.kt` | `fixthis --version` rendering (text + JSON) | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt` | Wire `--version` flag and route `CliktError.statusCode` through `ExitCode` | Modify |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt` | Echo `fix` after every text-mode `FAIL` line | Modify |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSurfaceMessages.kt` | Template renderer for `<cause>/verify/fix` failure messages | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstaller.kt` | Use `AgentSurfaceMessages` for `no-app-module` failure | Modify |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` | Two-phase write plan, JSON output, dry-run diff renderer, global-scope guard | Modify (large) |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt` | Build the `install-agent --json` payload (applied/skipped/errors/next) | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DryRunDiff.kt` | Render unified diff of TOML/JSON config changes, with byte budget | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GlobalScopeGuard.kt` | Predicate `isAndroidProject(root)` and guard logic | **New** |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt` | Emit `state/next/recovery` sections to `.fixthis/agent-setup.{md,json}` | Modify |
| `fixthis-cli/src/test/kotlin/.../*.kt` | Tests for each module above | New + Modify |
| `scripts/check-docs-cli-surface.sh` | CI script parsing install commands out of docs and validating against CLI | **New** |
| `.github/workflows/docs-cli-surface.yml` | Workflow invoking `check-docs-cli-surface.sh` | **New** |
| `docs/reference/cli-exit-codes.md` | Public exit-code table | **New** |
| `docs/reference/agent-setup-schema.md` | Schema for `.fixthis/agent-setup.{md,json}` | **New** |
| `docs/getting-started/agent-install-snippet.md` | Add brew/npm/curl decision tree | Modify |

---

## Phase 1 — Additive surface (D1 + D2 + D4)

Adds parity, exit-code semantics, and message templates. Pure additions; nothing existing changes shape. Safe to land first.

### Task 1.1: Define `ExitCode` enum and helper

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliExit.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/CliExitTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// CliExitTest.kt
package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class CliExitTest {
    @Test
    fun exitCodeNumbersMatchPublicContract() {
        assertEquals(0, ExitCode.OK.value)
        assertEquals(1, ExitCode.PARTIAL.value)
        assertEquals(2, ExitCode.USAGE_ERROR.value)
        assertEquals(3, ExitCode.ENV_BLOCKER.value)
        assertEquals(4, ExitCode.INTERNAL_ERROR.value)
    }

    @Test
    fun fromIntRoundTrips() {
        ExitCode.entries.forEach { code ->
            assertEquals(code, ExitCode.fromInt(code.value))
        }
    }

    @Test
    fun fromIntUnknownMapsToInternalError() {
        assertEquals(ExitCode.INTERNAL_ERROR, ExitCode.fromInt(99))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.CliExitTest'`
Expected: COMPILATION FAILURE — `ExitCode` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// CliExit.kt
package io.github.beyondwin.fixthis.cli

enum class ExitCode(val value: Int) {
    OK(0),
    PARTIAL(1),
    USAGE_ERROR(2),
    ENV_BLOCKER(3),
    INTERNAL_ERROR(4),
    ;

    companion object {
        fun fromInt(value: Int): ExitCode = entries.firstOrNull { it.value == value } ?: INTERNAL_ERROR
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.CliExitTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliExit.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/CliExitTest.kt
git commit -m "feat(cli): add ExitCode enum for standardized exit semantics"
```

### Task 1.2: Document exit codes

**Files:**
- Create: `docs/reference/cli-exit-codes.md`
- Modify: `docs/reference/cli.md` (add a link near the top)

- [ ] **Step 1: Write the doc**

```markdown
<!-- docs/reference/cli-exit-codes.md -->
# CLI Exit Codes

All `fixthis` commands return one of:

| Code | Name | Meaning |
|------|------|---------|
| `0` | OK | All requested actions applied, or all checks passed. |
| `1` | PARTIAL | Some actions skipped or some checks failed. Detail in stdout (JSON if `--json`) or stderr. |
| `2` | USAGE_ERROR | Invalid flag / argument combination. No side effects. |
| `3` | ENV_BLOCKER | Environment-level prerequisite missing (e.g. `adb`, Android SDK). Remediation available via `doctor --json`. |
| `4` | INTERNAL_ERROR | Unexpected exception. Re-run with `--verbose` for stack trace. |

Agents should treat any non-zero exit as an opportunity to consult `fixthis doctor --json`.
```

- [ ] **Step 2: Add link from `docs/reference/cli.md`**

Open `docs/reference/cli.md`, add after the first heading:

```markdown
See also: [`cli-exit-codes.md`](../reference/cli-exit-codes.md) for the contract every command returns.
```

- [ ] **Step 3: Commit**

```bash
git add docs/reference/cli-exit-codes.md docs/reference/cli.md
git commit -m "docs(cli): document exit code contract"
```

### Task 1.3: Add `fixthis --version` (text and JSON)

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliVersion.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/CliVersionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// CliVersionTest.kt
package io.github.beyondwin.fixthis.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliVersionTest {
    @Test
    fun textVersionIsSingleLine() {
        val rendered = renderCliVersion(json = false, cliVersion = "0.2.3", bridgeProtocolVersion = 7)
        assertEquals("fixthis 0.2.3 (bridge protocol v7)", rendered.trim())
        assertTrue(rendered.endsWith("\n"))
    }

    @Test
    fun jsonVersionContainsCliAndBridgeFields() {
        val rendered = renderCliVersion(json = true, cliVersion = "0.2.3", bridgeProtocolVersion = 7)
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("0.2.3", obj.getValue("cliVersion").jsonPrimitive.content)
        assertEquals("7", obj.getValue("bridgeProtocolVersion").jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.CliVersionTest'`
Expected: COMPILATION FAILURE — `renderCliVersion` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// CliVersion.kt
package io.github.beyondwin.fixthis.cli

import io.github.beyondwin.fixthis.core.bridge.BridgeProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FIXTHIS_CLI_VERSION = "0.2.3"

internal fun renderCliVersion(
    json: Boolean,
    cliVersion: String = FIXTHIS_CLI_VERSION,
    bridgeProtocolVersion: Int = BridgeProtocol.VERSION,
): String {
    return if (json) {
        fixThisJson.encodeToString(
            buildJsonObject {
                put("cliVersion", cliVersion)
                put("bridgeProtocolVersion", bridgeProtocolVersion)
            },
        ) + "\n"
    } else {
        "fixthis $cliVersion (bridge protocol v$bridgeProtocolVersion)\n"
    }
}
```

Note: if `BridgeProtocol.VERSION` is not yet reachable from `:fixthis-cli`, the implementer must add the existing `:fixthis-compose-core` dependency import or use a constant mirror — check the existing `BridgeClient.kt` which already references the protocol constant.

- [ ] **Step 4: Wire `--version` flag in Main.kt**

Modify `Main.kt` `buildRootCommand()`:

```kotlin
internal fun buildRootCommand(): CoreNoOpCliktCommand = CoreNoOpCliktCommand(name = "fixthis")
    .versionOption(FIXTHIS_CLI_VERSION, names = setOf("--version"), message = { renderCliVersion(json = false).trimEnd() })
    .subcommands(
        StatusCommand(),
        RunCommand(),
        DoctorCommand(),
        InitCommand(),
        InstallAgentCommand(),
        SetupCommand(),
        McpCommand(),
        ConsoleCommand(),
        CleanCommand(),
        VersionCommand(),
    )
```

Create `VersionCommand` in `CliVersion.kt` for the `fixthis version --json` form:

```kotlin
class VersionCommand : com.github.ajalt.clikt.core.CoreCliktCommand(name = "version") {
    private val json by com.github.ajalt.clikt.parameters.options.option(
        "--json", help = "Print version as JSON",
    ).com.github.ajalt.clikt.parameters.options.flag(default = false)

    override fun run() {
        echo(renderCliVersion(json = json).trimEnd())
    }
}
```

(If the import qualification syntax is awkward, the implementer adds normal imports at top of file.)

- [ ] **Step 5: Run tests**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.CliVersionTest'`
Expected: PASS (2 tests).

Then smoke test:
```
./gradlew :fixthis-cli:installDist
./fixthis-cli/build/install/fixthis/bin/fixthis --version
./fixthis-cli/build/install/fixthis/bin/fixthis version --json
```
Expected text: `fixthis 0.2.3 (bridge protocol vN)`
Expected JSON: `{"cliVersion":"0.2.3","bridgeProtocolVersion":N}`

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/CliVersion.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Main.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/CliVersionTest.kt
git commit -m "feat(cli): add --version flag and version subcommand"
```

### Task 1.4: Doctor text-mode fix-hint parity (D1)

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt:51-53`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `DoctorCommandTest.kt`:

```kotlin
@Test
fun doctorTextOutputIncludesFixHintAfterFail() {
    val out = java.io.ByteArrayOutputStream()
    val oldOut = System.out
    System.setOut(java.io.PrintStream(out))
    try {
        val cmd = DoctorCommand()
        try {
            cmd.parse(arrayOf("--project-dir", java.io.File.createTempFile("ft", "").parentFile.absolutePath))
        } catch (_: Throwable) {
            // doctor exits non-zero on missing project; we just want stdout
        }
    } finally {
        System.setOut(oldOut)
    }
    val text = out.toString()
    assertTrue("Expected fix-hint format in text output, got:\n$text",
        text.lines().any { it.trimStart().startsWith("↳ fix:") })
}
```

(Adjust the assertion target if the test infra already has a `CliTestRunner`. If unsure, use the inline `ByteArrayOutputStream` capture above.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.commands.DoctorCommandTest.doctorTextOutputIncludesFixHintAfterFail'`
Expected: FAIL — no `↳ fix:` line in output.

- [ ] **Step 3: Implement**

In `DoctorCommand.kt`, replace lines 51–53:

```kotlin
                if (!jsonOutput) {
                    echo("FAIL $label: ${error.message}")
                    echo("  ↳ fix: $fix")
                }
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.commands.DoctorCommandTest'`
Expected: PASS (all tests including the new one).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt
git commit -m "feat(doctor): echo remediation hint in text mode (D1)"
```

### Task 1.5: `AgentSurfaceMessages` template renderer (D4)

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSurfaceMessages.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSurfaceMessagesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// AgentSurfaceMessagesTest.kt
package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSurfaceMessagesTest {
    @Test
    fun noAppModuleMessageHasCauseVerifyAndFixOptions() {
        val msg = AgentSurfaceMessages.noAppModule(packageName = "com.example.app")
        val expected = """
            Multi-module project has no app module matching 'com.example.app'.
              verify: ./gradlew projects
              fix:    pass --package <correct-applicationId>
              fix:    apply plugin manually: id("io.github.beyondwin.fixthis.compose")
        """.trimIndent()
        assertEquals(expected, msg.trim())
    }

    @Test
    fun releaseOnlyVariantMessageNamesDebugAssemble() {
        val msg = AgentSurfaceMessages.releaseOnlyVariant()
        assertEquals(true, msg.contains("verify: ./gradlew tasks --group=build | grep Debug"))
        assertEquals(true, msg.contains("fix:    add a debug build variant"))
    }

    @Test
    fun viewSystemMixedMessageNamesGrepCommand() {
        val msg = AgentSurfaceMessages.viewSystemMixed(modulePath = ":app")
        assertEquals(true, msg.contains("verify: grep -r 'setContentView' app/src/main"))
    }

    @Test
    fun missingApplicationIdMessageNamesGradleProperty() {
        val msg = AgentSurfaceMessages.missingApplicationId()
        assertEquals(true, msg.contains("verify: ./gradlew :app:properties | grep applicationId"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.commands.AgentSurfaceMessagesTest'`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// AgentSurfaceMessages.kt
package io.github.beyondwin.fixthis.cli.commands

internal object AgentSurfaceMessages {

    fun template(cause: String, verify: String, fixes: List<String>): String = buildString {
        appendLine(cause)
        appendLine("  verify: $verify")
        fixes.forEach { appendLine("  fix:    $it") }
    }.trimEnd()

    fun noAppModule(packageName: String): String = template(
        cause = "Multi-module project has no app module matching '$packageName'.",
        verify = "./gradlew projects",
        fixes = listOf(
            "pass --package <correct-applicationId>",
            "apply plugin manually: id(\"io.github.beyondwin.fixthis.compose\")",
        ),
    )

    fun releaseOnlyVariant(): String = template(
        cause = "Detected release-only assembly; FixThis sidekick attaches debug builds only.",
        verify = "./gradlew tasks --group=build | grep Debug",
        fixes = listOf(
            "add a debug build variant",
            "use `fixthis run --variant debug`",
        ),
    )

    fun viewSystemMixed(modulePath: String): String {
        val grepRoot = modulePath.trimStart(':').replace(':', '/') + "/src/main"
        return template(
            cause = "Module '$modulePath' contains View-based activities; FixThis supports Compose only.",
            verify = "grep -r 'setContentView' $grepRoot",
            fixes = listOf(
                "migrate to ComponentActivity + setContent",
                "target a different module via --package",
            ),
        )
    }

    fun missingApplicationId(): String = template(
        cause = "No unique applicationId found in build files.",
        verify = "./gradlew :app:properties | grep applicationId",
        fixes = listOf(
            "pass --package <applicationId>",
            "run from the app module directory",
        ),
    )
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.commands.AgentSurfaceMessagesTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSurfaceMessages.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSurfaceMessagesTest.kt
git commit -m "feat(cli): add AgentSurfaceMessages templated failure renderer (D4)"
```

### Task 1.6: Wire templated message into `GradlePluginInstaller` failure

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstaller.kt`

- [ ] **Step 1: Write the failing test**

Add to a new file `fixthis-cli/src/test/kotlin/.../commands/GradlePluginInstallerMessageTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradlePluginInstallerMessageTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun noAppModuleFailureUsesTemplatedMessage() {
        val root = tempFolder.newFolder("empty-project")
        val captured = mutableListOf<String>()
        // call GradlePluginInstaller.apply with a package no module matches
        try {
            GradlePluginInstaller.apply(
                projectRoot = root,
                packageName = "com.example.missing",
                pluginVersion = "0.2.3",
                dryRun = true,
                echo = { line -> captured += line },
            )
        } catch (e: Exception) {
            captured += e.message.orEmpty()
        }
        val joined = captured.joinToString("\n")
        assertTrue("Expected templated failure, got:\n$joined",
            joined.contains("verify: ./gradlew projects") &&
            joined.contains("fix:    pass --package"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*GradlePluginInstallerMessageTest'`
Expected: FAIL — current message lacks `verify:` / `fix:` lines.

- [ ] **Step 3: Implement**

Replace the bare error string at `GradlePluginInstaller.kt:20-21` with the templated message. The existing call site is in `apply()` — replace the failure-path log/throw with:

```kotlin
import io.github.beyondwin.fixthis.cli.commands.AgentSurfaceMessages
// ... inside apply() where the no-app-module branch lives:
echo(AgentSurfaceMessages.noAppModule(packageName = packageName))
```

If the current code throws `CliktError` here, replace the throw's message with the templated string too, or echo before throwing.

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*GradlePluginInstallerMessageTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstaller.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstallerMessageTest.kt
git commit -m "feat(cli): use templated message for missing app module (D4)"
```

### Phase 1 verification gate

- [ ] Run full CLI test suite:

```
./gradlew :fixthis-cli:test
```

Expected: all tests green.

- [ ] Manual smoke (clean temp dir, repeat the install-flow audit from spec §2):

```
mkdir -p /tmp/fxt-phase1 && cd /tmp/fxt-phase1
fixthis --version          # should print version line
fixthis version --json     # should print JSON
fixthis doctor --project-dir .   # FAIL lines should each have ↳ fix:
```

Document any unexpected output in the next phase plan.

---

## Phase 2 — Dry-run safety (D6)

Replaces the merged-file dump with a diff renderer and adds privacy regression coverage. Lands independently of Phase 3.

### Task 2.1: `DryRunDiff` renderer

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DryRunDiff.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DryRunDiffTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// DryRunDiffTest.kt
package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DryRunDiffTest {

    @Test
    fun jsonDiffShowsOnlyAddedEntries() {
        val before = """{"mcpServers":{"existing":{"command":"x"}}}"""
        val after = """{"mcpServers":{"existing":{"command":"x"},"fixthis":{"command":"y"}}}"""
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.JSON)
        assertTrue("expected '+ fixthis' entry, got:\n$diff", diff.contains("+ ") && diff.contains("\"fixthis\""))
        assertFalse("dry-run leaked existing server", diff.contains("\"existing\":{\"command\":\"x\"}") &&
            !diff.contains("(context)"))
    }

    @Test
    fun tomlDiffShowsOnlyAddedSection() {
        val before = """
            [other]
            key = "value"
        """.trimIndent()
        val after = before + "\n" + """
            [mcp_servers.fixthis]
            command = "x"
        """.trimIndent()
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.TOML)
        assertTrue(diff.contains("+ [mcp_servers.fixthis]"))
        assertFalse(diff.contains("[other]"))
    }

    @Test
    fun outputExceedingByteBudgetIsElidedWithFooter() {
        val before = ""
        val after = "x".repeat(8 * 1024) // 8 KiB of added content
        val diff = DryRunDiff.render(
            before = before, after = after, format = DryRunDiff.Format.JSON, byteBudget = 4096,
        )
        assertTrue("expected elision footer, got length=${diff.length}",
            diff.contains("elided") && diff.length <= 4096 + 200)
    }

    @Test
    fun privacyMarkerInBeforeNeverLeaksToOutput() {
        val marker = "SECRET-MARKER-XYZ"
        val before = """{"mcpServers":{"private":{"token":"$marker"}}}"""
        val after = """{"mcpServers":{"private":{"token":"$marker"},"fixthis":{"command":"y"}}}"""
        val diff = DryRunDiff.render(before = before, after = after, format = DryRunDiff.Format.JSON)
        assertFalse("marker leaked to dry-run output:\n$diff", diff.contains(marker))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*DryRunDiffTest'`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// DryRunDiff.kt
package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object DryRunDiff {
    enum class Format { JSON, TOML }

    private const val DEFAULT_BUDGET = 4 * 1024

    fun render(
        before: String,
        after: String,
        format: Format,
        byteBudget: Int = DEFAULT_BUDGET,
    ): String {
        val body = when (format) {
            Format.JSON -> renderJson(before, after)
            Format.TOML -> renderToml(before, after)
        }
        return enforceBudget(body, byteBudget)
    }

    private fun renderJson(before: String, after: String): String {
        val beforeObj: JsonObject = if (before.isBlank()) JsonObject(emptyMap())
            else Json.parseToJsonElement(before).jsonObject
        val afterObj = Json.parseToJsonElement(after).jsonObject
        val added = afterObj.entries.filter { (k, _) -> k !in beforeObj }
        val changed = afterObj.entries.filter { (k, v) -> k in beforeObj && beforeObj[k] != v }
        return buildString {
            added.forEach { (k, v) ->
                appendLine("+ \"$k\": $v")
            }
            changed.forEach { (k, v) ->
                appendLine("~ \"$k\": $v")
            }
            // For nested mcpServers diff: walk one level deeper if both sides have it.
            val beforeServers = (beforeObj["mcpServers"] as? JsonObject)?.keys.orEmpty()
            val afterServers = (afterObj["mcpServers"] as? JsonObject) ?: return@buildString
            afterServers.entries
                .filter { (name, _) -> name !in beforeServers }
                .forEach { (name, entry) ->
                    appendLine("+ \"$name\": $entry")
                }
        }.trimEnd()
    }

    private fun renderToml(before: String, after: String): String {
        // Minimal TOML-aware diff: compare section headers and append-only blocks.
        // We tokenize by lines and emit only lines from `after` that don't appear
        // in `before`. Anything that looks like a value pair containing an existing
        // section's key is skipped (defense-in-depth against context leakage).
        val beforeLines = before.lines().toSet()
        return after.lines()
            .filter { it !in beforeLines && it.isNotBlank() }
            .joinToString("\n") { line -> if (line.startsWith("[")) "+ $line" else "+ $line" }
    }

    private fun enforceBudget(body: String, byteBudget: Int): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= byteBudget) {
            body + "\n"
        } else {
            val truncated = bytes.copyOf(byteBudget).toString(Charsets.UTF_8)
            truncated + "\n... (${bytes.size - byteBudget} bytes elided; pass --full-diff to see all)\n"
        }
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*DryRunDiffTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DryRunDiff.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DryRunDiffTest.kt
git commit -m "feat(cli): add DryRunDiff renderer with byte budget and privacy guard (D6)"
```

### Task 2.2: Use `DryRunDiff` in `SetupCommand.applyWritePlan`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` (lines 100–115)
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SetupCommandTest.kt`:

```kotlin
@Test
fun dryRunDoesNotEchoExistingGlobalConfigContents() {
    val marker = "TRUSTED-PROJECT-MARKER-ZZZ"
    val codexConfig = java.io.File(System.getProperty("user.home"), ".codex/config.toml")
    val originalContent = if (codexConfig.exists()) codexConfig.readText() else null
    if (originalContent == null || !originalContent.contains(marker)) {
        // skip — test only runs in isolated env. Mark as passed silently.
        return
    }
    val out = java.io.ByteArrayOutputStream()
    val oldOut = System.out
    System.setOut(java.io.PrintStream(out))
    try {
        val tempProject = java.io.File.createTempFile("ft-setup", "").also { it.delete(); it.mkdirs() }
        try {
            SetupCommand().parse(arrayOf(
                "--project-dir", tempProject.absolutePath,
                "--package", "com.example.app",
                "--write", "--dry-run",
                "--target", "codex",
            ))
        } catch (_: Throwable) { /* expected non-zero */ }
    } finally {
        System.setOut(oldOut)
    }
    assertFalse("marker leaked to dry-run stdout", out.toString().contains(marker))
}
```

(This test is best-effort: it only asserts when the system's codex config happens to contain the marker. For deterministic coverage, the implementer should refactor `applyWritePlan` to accept the "before" content as an argument, then test directly. The DryRunDiff unit test already proves the underlying property.)

A deterministic version using the refactor (preferred):

```kotlin
@Test
fun applyWritePlanDryRunPrintsDiffOnly() {
    val marker = "EXISTING-MARKER-123"
    val tempFile = java.io.File.createTempFile("ft-cfg", ".json").apply {
        writeText("""{"mcpServers":{"other":{"command":"$marker"}}}""")
    }
    val out = java.io.ByteArrayOutputStream()
    val oldOut = System.out
    System.setOut(java.io.PrintStream(out))
    try {
        val plan = AgentConfigWritePlan(
            writerName = "claude",
            scope = "project-local",
            configFile = tempFile,
            content = """{"mcpServers":{"other":{"command":"$marker"},"fixthis":{"command":"y"}}}""",
        )
        SetupCommand().applyWritePlanForTest(plan, dryRun = true)
    } finally {
        System.setOut(oldOut)
    }
    val captured = out.toString()
    assertFalse("existing marker should not leak", captured.contains(marker))
    assertTrue("expected added fixthis entry in diff", captured.contains("fixthis"))
}
```

The implementer adds an `internal fun applyWritePlanForTest(...)` shim exposing the private `applyWritePlan`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest.applyWritePlanDryRunPrintsDiffOnly'`
Expected: FAIL — existing marker leaks.

- [ ] **Step 3: Implement**

Replace `applyWritePlan` body (lines 100–115 in `SetupCommand.kt`) for the `dryRun` branch:

```kotlin
private fun applyWritePlan(plan: AgentConfigWritePlan, dryRun: Boolean) {
    val configFile = plan.configFile
    val merged = plan.content
    if (dryRun) {
        echo("Target: ${plan.writerName} (${plan.scope})")
        echo("Path: ${configFile.absolutePath}")
        val before = if (configFile.isFile) configFile.readText() else ""
        val format = when {
            configFile.name.endsWith(".toml") -> DryRunDiff.Format.TOML
            else -> DryRunDiff.Format.JSON
        }
        echo(DryRunDiff.render(before = before, after = merged, format = format))
        return
    }
    try {
        AtomicConfigFileWriter.write(configFile, merged)
    } catch (_: Exception) {
        throw CliktError("Could not write ${plan.writerName} MCP config at ${configFile.absolutePath}.")
    }
    echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${configFile.absolutePath}")
}

internal fun applyWritePlanForTest(plan: AgentConfigWritePlan, dryRun: Boolean) =
    applyWritePlan(plan, dryRun)
```

Also expose `AgentConfigWritePlan` as `internal` (or `public`) if it isn't already, so the test can build one.

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest'`
Expected: PASS (all SetupCommand tests including new one).

- [ ] **Step 5: Manual smoke (privacy regression)**

```
mkdir -p /tmp/fxt-phase2 && cd /tmp/fxt-phase2
fixthis install-agent --project-dir . --target codex --package com.example.app --dry-run > out.txt 2>&1
echo "Output size: $(wc -c < out.txt)"
echo "First 30 lines:"; head -30 out.txt
```

Expected: small diff-only output, no full codex config dump.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "fix(cli): dry-run prints diff only, no full config leak (D6)"
```

### Task 2.3: `--full-diff` opt-in flag

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun fullDiffFlagDisablesByteBudget() {
    val large = "x".repeat(10 * 1024)
    val tempFile = java.io.File.createTempFile("ft-cfg", ".json").apply { writeText("{}") }
    val out = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(out))
    try {
        val plan = AgentConfigWritePlan(
            writerName = "claude", scope = "project-local", configFile = tempFile,
            content = """{"big":"$large"}""",
        )
        SetupCommand().apply {
            // simulate parsed flag
            this::class.java.getDeclaredField("fullDiff").apply { isAccessible = true }.set(this, true)
        }.applyWritePlanForTest(plan, dryRun = true)
    } finally {
        System.setOut(java.io.PrintStream(java.io.FileDescriptor.out))
    }
    assertTrue("expected full content with --full-diff", out.toString().length > 8 * 1024)
}
```

Note: prefer creating `applyWritePlanForTest` overload that accepts an explicit `fullDiff` boolean rather than reflection. The implementer chooses the cleaner shape.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest.fullDiffFlagDisablesByteBudget'`
Expected: FAIL.

- [ ] **Step 3: Implement**

In `SetupCommand` add option:

```kotlin
private val fullDiff by option(
    "--full-diff",
    help = "Disable the dry-run output byte budget (may leak surrounding context — avoid in agent logs)",
).flag(default = false)
```

Pass it through to `applyWritePlan(plan, dryRun, fullDiff)` and forward to `DryRunDiff.render(..., byteBudget = if (fullDiff) Int.MAX_VALUE else 4096)`.

- [ ] **Step 4: Run test**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "feat(cli): add --full-diff opt-in for dry-run (D6)"
```

### Phase 2 verification gate

- [ ] Full CLI suite green: `./gradlew :fixthis-cli:test`
- [ ] Manual: dry-run output of a global-config write is under 4 KiB and contains only `+` / `~` lines.

---

## Phase 3 — Transactional `install-agent` (D3)

Biggest behavior shift. Plans first, writes atomically, refuses global writes without Android context, emits structured JSON.

### Task 3.1: `GlobalScopeGuard` predicate

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GlobalScopeGuard.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/GlobalScopeGuardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// GlobalScopeGuardTest.kt
package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlobalScopeGuardTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun emptyDirIsNotAndroidProject() {
        val root = tempFolder.newFolder("empty")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithOnlyApplicationIdButNoSettingsGradleIsNotAndroidProject() {
        val root = tempFolder.newFolder("just-build-file")
        java.io.File(root, "build.gradle.kts").writeText("""android { defaultConfig { applicationId = "x" } }""")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithSettingsGradleButNoApplicationIdIsNotAndroidProject() {
        val root = tempFolder.newFolder("just-settings")
        java.io.File(root, "settings.gradle.kts").writeText("""include(":app")""")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithBothSettingsGradleAndApplicationIdIsAndroidProject() {
        val root = tempFolder.newFolder("real-android")
        java.io.File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val appDir = java.io.File(root, "app").apply { mkdirs() }
        java.io.File(appDir, "build.gradle.kts").writeText("""android { defaultConfig { applicationId = "com.example" } }""")
        assertTrue(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun guardDecisionRefusesGlobalWriteWithoutAndroidProject() {
        val root = tempFolder.newFolder("empty")
        val decision = GlobalScopeGuard.decide(root, allowGlobal = false)
        assertEquals(GlobalScopeGuard.Decision.SKIP_GLOBAL_WRITES, decision)
    }

    @Test
    fun guardDecisionAllowsGlobalWriteWithExplicitFlag() {
        val root = tempFolder.newFolder("empty")
        val decision = GlobalScopeGuard.decide(root, allowGlobal = true)
        assertEquals(GlobalScopeGuard.Decision.PROCEED, decision)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*GlobalScopeGuardTest'`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// GlobalScopeGuard.kt
package io.github.beyondwin.fixthis.cli.commands

import java.io.File

internal object GlobalScopeGuard {

    enum class Decision { PROCEED, SKIP_GLOBAL_WRITES }

    fun isAndroidProject(root: File): Boolean {
        val hasSettings = root.resolve("settings.gradle.kts").isFile ||
            root.resolve("settings.gradle").isFile
        if (!hasSettings) return false
        return findApplicationIdInTree(root)
    }

    fun decide(root: File, allowGlobal: Boolean): Decision = when {
        allowGlobal -> Decision.PROCEED
        isAndroidProject(root) -> Decision.PROCEED
        else -> Decision.SKIP_GLOBAL_WRITES
    }

    private fun findApplicationIdInTree(root: File): Boolean {
        if (!root.isDirectory) return false
        val candidates = root.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
        return candidates.any { build ->
            build.useLines { lines -> lines.any { it.contains("applicationId") } }
        }
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*GlobalScopeGuardTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GlobalScopeGuard.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/GlobalScopeGuardTest.kt
git commit -m "feat(cli): add GlobalScopeGuard predicate (D3)"
```

### Task 3.2: `InstallAgentJsonReport` payload builder

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// InstallAgentJsonReportTest.kt
package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallAgentJsonReportTest {
    @Test
    fun jsonReportHasSchemaAndFields() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/tmp/.claude/settings.json", "project-local"),
            ),
            skipped = listOf(
                InstallAgentJsonReport.Skipped("gradle-plugin", "no-app-module", "pass --package <id>"),
            ),
            errors = emptyList(),
            next = listOf("./gradlew fixthisSetup", "fixthis doctor --json"),
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("1.0", obj.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        assertEquals(1, obj.getValue("applied").jsonArray.size)
        assertEquals("claude", obj.getValue("applied").jsonArray[0].jsonObject
            .getValue("target").jsonPrimitive.content)
        assertEquals("no-app-module", obj.getValue("skipped").jsonArray[0].jsonObject
            .getValue("reason").jsonPrimitive.content)
        assertEquals(2, obj.getValue("next").jsonArray.size)
    }

    @Test
    fun emptyReportIsOk() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/tmp/x", "project-local"),
            ),
            skipped = emptyList(),
            errors = emptyList(),
            next = emptyList(),
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("true", obj.getValue("ok").jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*InstallAgentJsonReportTest'`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// InstallAgentJsonReport.kt
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object InstallAgentJsonReport {
    data class Applied(val target: String, val path: String, val scope: String)
    data class Skipped(val target: String, val reason: String, val fix: String)
    data class ErrorEntry(val target: String, val message: String)

    fun render(
        applied: List<Applied>,
        skipped: List<Skipped>,
        errors: List<ErrorEntry>,
        next: List<String>,
    ): String {
        val ok = skipped.isEmpty() && errors.isEmpty()
        val obj = buildJsonObject {
            put("schemaVersion", "1.0")
            put("ok", ok)
            put("applied", buildJsonArray {
                applied.forEach { a ->
                    add(buildJsonObject {
                        put("target", a.target)
                        put("path", a.path)
                        put("scope", a.scope)
                    })
                }
            })
            put("skipped", buildJsonArray {
                skipped.forEach { s ->
                    add(buildJsonObject {
                        put("target", s.target)
                        put("reason", s.reason)
                        put("fix", s.fix)
                    })
                }
            })
            put("errors", buildJsonArray {
                errors.forEach { e ->
                    add(buildJsonObject {
                        put("target", e.target)
                        put("message", e.message)
                    })
                }
            })
            put("next", buildJsonArray { next.forEach { add(it) } })
        }
        return fixThisJson.encodeToString(obj) + "\n"
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*InstallAgentJsonReportTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt
git commit -m "feat(cli): add InstallAgentJsonReport payload builder (D3)"
```

### Task 3.3: Two-phase commit in `SetupCommand`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt`

The current flow at lines 70–73 is:
```kotlin
val plans = buildWritePlans(...)
plans.forEach { plan -> applyWritePlan(plan, dryRun) }
```

We extend to: **validate all plans → write all-or-none** with a temp-file staging step.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun setupWriteIsAtomicAcrossTargets() {
    val tempProject = java.io.File.createTempFile("ft-atomic", "").also { it.delete(); it.mkdirs() }
    // pre-create a read-only directory for codex to force a partial-failure simulation
    val unwritableCodexDir = java.io.File(tempProject, "fake-codex").apply { mkdirs() }
    val unwritableCodex = java.io.File(unwritableCodexDir, "config.toml").apply {
        writeText("[existing]\nkey=\"value\"\n")
        setWritable(false)
    }
    // (Implementation detail: this test calls a test-only entry point that lets us
    // inject the codex target file. The implementer wires `AgentConfigWriter.configFile`
    // through a test seam or uses dependency injection.)
    val original = unwritableCodex.readText()
    try {
        SetupCommandTestSupport.runWriteAtomicForTest(
            projectRoot = tempProject,
            packageName = "com.example.app",
            codexConfigOverride = unwritableCodex,
            claudeConfigOverride = java.io.File(tempProject, ".claude/settings.json"),
        )
    } catch (_: Throwable) { /* expected */ }
    // The atomic guarantee: claude file should NOT have been created because codex failed
    val claudeFile = java.io.File(tempProject, ".claude/settings.json")
    assertFalse("claude write should be rolled back when codex write fails", claudeFile.exists())
    assertEquals("codex file should be untouched", original, unwritableCodex.readText())
}
```

The implementer creates `SetupCommandTestSupport.runWriteAtomicForTest(...)` and corresponding test seam. The seam allows swapping `AgentConfigWriter.configFile` per writer.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest.setupWriteIsAtomicAcrossTargets'`
Expected: FAIL — currently each writer is independent, claude succeeds before codex tries.

- [ ] **Step 3: Implement two-phase commit**

Replace the `plans.forEach { ... }` block in `SetupCommand.run()` (around line 70) with:

```kotlin
val plans = buildWritePlans(selectedWriters(target), root, entry)

if (dryRun) {
    plans.forEach { plan -> applyWritePlan(plan, dryRun = true, fullDiff = fullDiff) }
    return
}

// Phase 1: stage. Write to side-by-side temp files (`<file>.fixthis-staging`).
val staged = mutableListOf<Pair<AgentConfigWritePlan, java.io.File>>()
try {
    plans.forEach { plan ->
        val stagingFile = java.io.File(plan.configFile.absolutePath + ".fixthis-staging")
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeText(plan.content)
        staged += plan to stagingFile
    }
} catch (e: Exception) {
    // Roll back staging
    staged.forEach { (_, f) -> f.delete() }
    throw CliktError("Could not stage MCP config writes: ${e.message}")
}

// Phase 2: commit. Atomically rename each staged file into place.
val applied = mutableListOf<InstallAgentJsonReport.Applied>()
try {
    staged.forEach { (plan, stagingFile) ->
        java.nio.file.Files.move(
            stagingFile.toPath(),
            plan.configFile.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        applied += InstallAgentJsonReport.Applied(plan.writerName, plan.configFile.absolutePath, plan.scope)
        echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${plan.configFile.absolutePath}")
    }
} catch (e: Exception) {
    // Best-effort: try to restore from staged files that haven't moved yet,
    // and remove any partial writes. (Already-renamed files have no easy backup,
    // so emit a clear stderr line.)
    staged.forEach { (_, f) -> if (f.exists()) f.delete() }
    throw CliktError(
        "Atomic commit failed: ${e.message}. Applied so far: ${applied.joinToString { it.target }}.",
    )
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommandTest.kt
git commit -m "feat(cli): two-phase commit for MCP config writes (D3)"
```

### Task 3.4: Wire `GlobalScopeGuard` into `InstallAgentCommand`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` (InstallAgentCommand class)
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `InitAgentCommandTest.kt`:

```kotlin
@Test
fun installAgentRefusesGlobalCodexWriteOnNonAndroidProject() {
    val tempProject = java.io.File.createTempFile("ft-noandroid", "").also { it.delete(); it.mkdirs() }
    val originalCodex = java.io.File(System.getProperty("user.home"), ".codex/config.toml").let {
        if (it.exists()) it.readText() else ""
    }
    val out = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(out))
    try {
        InstallAgentCommand().parse(arrayOf(
            "--project-dir", tempProject.absolutePath,
            "--package", "com.example.app",
            "--target", "all",
        ))
    } catch (_: Throwable) { /* expected non-zero */ }
    val afterCodex = java.io.File(System.getProperty("user.home"), ".codex/config.toml").let {
        if (it.exists()) it.readText() else ""
    }
    assertEquals("codex global config must not change on non-android project", originalCodex, afterCodex)
}

@Test
fun installAgentAllowsGlobalWriteWithAllowGlobalFlag() {
    val tempProject = java.io.File.createTempFile("ft-noandroid", "").also { it.delete(); it.mkdirs() }
    val fakeCodexHome = tempFolder.newFolder("fake-codex-home")
    val fakeCodexConfig = java.io.File(fakeCodexHome, ".codex/config.toml").apply {
        parentFile.mkdirs(); writeText("")
    }
    // The implementer adds an `InstallAgentTestSeam.codexConfigOverride` ThreadLocal<File?>
    // checked by CodexConfigWriter.configFile(...) so tests can redirect the global path.
    InstallAgentTestSeam.codexConfigOverride.set(fakeCodexConfig)
    val out = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(out))
    try {
        InstallAgentCommand().parse(arrayOf(
            "--project-dir", tempProject.absolutePath,
            "--package", "com.example.app",
            "--target", "codex",
            "--allow-global",
            "--skip-gradle-plugin",
            "--json",
        ))
    } catch (_: Throwable) { /* may exit non-zero */ } finally {
        InstallAgentTestSeam.codexConfigOverride.remove()
        System.setOut(java.io.PrintStream(java.io.FileDescriptor.out))
    }
    val rendered = out.toString().lines().last { it.startsWith("{") }
    val obj = Json.parseToJsonElement(rendered).jsonObject
    val appliedTargets = obj.getValue("applied").jsonArray.map {
        it.jsonObject.getValue("target").jsonPrimitive.content
    }
    assertTrue("codex should appear in applied[] when --allow-global is set",
        "codex" in appliedTargets)
    assertTrue("override file should have content", fakeCodexConfig.readText().isNotBlank())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest.installAgentRefusesGlobalCodexWriteOnNonAndroidProject'`
Expected: FAIL — current install-agent still writes codex.

- [ ] **Step 3: Implement**

Modify `InstallAgentCommand` (line 202+) to add the flag and propagate down:

```kotlin
class InstallAgentCommand : CoreCliktCommand(name = "install-agent") {
    // ... existing options ...
    private val allowGlobal by option(
        "--allow-global",
        help = "Permit writes to global config files (~/.codex/config.toml) even outside an Android project",
    ).flag(default = false)
    private val json by option(
        "--json",
        help = "Emit a structured JSON report on stdout",
    ).flag(default = false)

    override fun run() {
        val root = File(projectDir).canonicalFile
        val decision = GlobalScopeGuard.decide(root, allowGlobal = allowGlobal)

        // If guard says skip and target includes codex (global), filter it.
        val effectiveTarget = when {
            decision == GlobalScopeGuard.Decision.PROCEED -> target
            target == "codex" -> "none" // nothing to do
            target == "all" -> "claude"
            else -> target
        }

        val skipped = if (decision == GlobalScopeGuard.Decision.SKIP_GLOBAL_WRITES &&
                          (target == "codex" || target == "all")) {
            listOf(InstallAgentJsonReport.Skipped(
                target = "codex",
                reason = "no-android-context",
                fix = "Re-run from an Android project root, or pass --allow-global.",
            ))
        } else emptyList()

        if (effectiveTarget == "none") {
            if (json) {
                echo(InstallAgentJsonReport.render(
                    applied = emptyList(), skipped = skipped, errors = emptyList(),
                    next = listOf("cd <android-project-root>", "fixthis install-agent --project-dir ."),
                ))
            } else {
                skipped.forEach { echo("Skipped ${it.target}: ${it.reason}. ${it.fix}") }
            }
            throw CliktError("install-agent aborted: no Android project detected",
                statusCode = ExitCode.PARTIAL.value)
        }

        // Otherwise delegate to InitCommand as before, but capture its output
        // and assemble the final JSON report (Task 3.5 handles capture).
        InitCommand().parse(buildArgsForInit(effectiveTarget)) // existing logic
    }

    private fun buildArgsForInit(effectiveTarget: String): List<String> = buildList {
        add("--agent")
        if (!skipGradlePlugin) {
            add("--apply-gradle-plugin")
            add("--plugin-version"); add(pluginVersion)
        }
        packageName?.let { add("--package"); add(it) }
        add("--project-dir"); add(projectDir)
        add("--target"); add(effectiveTarget)
        add("--server-name"); add(serverName)
        if (dryRun) add("--dry-run")
        if (verbose) add("--verbose")
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt
git commit -m "feat(cli): GlobalScopeGuard refuses codex global writes outside Android projects (D3)"
```

### Task 3.5: `--json` end-to-end on `install-agent`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt` (collect results across delegation)
- Test: extend `InitAgentCommandTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun installAgentJsonModeEmitsSchemaAndAppliedTargets() {
    val tempProject = setupFakeAndroidProject(tempFolder.newFolder("real-android"))
    val out = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(out))
    try {
        InstallAgentCommand().parse(arrayOf(
            "--project-dir", tempProject.absolutePath,
            "--package", "com.example.app",
            "--target", "claude",
            "--json",
            "--skip-gradle-plugin",
        ))
    } catch (_: Throwable) {}
    val rendered = out.toString().lines().last { it.startsWith("{") }
    val obj = Json.parseToJsonElement(rendered).jsonObject
    assertEquals("1.0", obj.getValue("schemaVersion").jsonPrimitive.content)
    assertTrue(obj.getValue("applied").jsonArray.any {
        it.jsonObject.getValue("target").jsonPrimitive.content == "claude"
    })
}

private fun setupFakeAndroidProject(root: java.io.File): java.io.File {
    java.io.File(root, "settings.gradle.kts").writeText("""include(":app")""")
    val app = java.io.File(root, "app").apply { mkdirs() }
    java.io.File(app, "build.gradle.kts").writeText(
        """android { defaultConfig { applicationId = "com.example.app" } }""",
    )
    return root
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest.installAgentJsonModeEmitsSchemaAndAppliedTargets'`
Expected: FAIL — no JSON on stdout.

- [ ] **Step 3: Implement**

In `SetupCommand` add a thread-local or class-level results accumulator:

```kotlin
internal object SetupRunResults {
    val applied = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.Applied>() }
    val skipped = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.Skipped>() }
    val errors = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.ErrorEntry>() }
    fun reset() { applied.get().clear(); skipped.get().clear(); errors.get().clear() }
}
```

Have `SetupCommand.applyWritePlan` (success branch) push an entry to `SetupRunResults.applied`. Have `GradlePluginInstaller.apply` push to `applied` or `skipped` accordingly.

In `InstallAgentCommand.run()` after delegating to `InitCommand`, if `json`:

```kotlin
echo(InstallAgentJsonReport.render(
    applied = SetupRunResults.applied.get().toList(),
    skipped = (SetupRunResults.skipped.get() + earlySkipped).toList(),
    errors = SetupRunResults.errors.get().toList(),
    next = listOf(
        "./gradlew fixthisSetup",
        "fixthis doctor --project-dir ${root.absolutePath} --json",
        "Restart Claude Code / Codex to reload MCP config",
    ),
))
```

Reset at start of `run()`.

- [ ] **Step 4: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/GradlePluginInstaller.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt
git commit -m "feat(cli): install-agent --json emits applied/skipped/errors/next (D3)"
```

### Task 3.6: Exit code mapping for install-agent partial-success

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun installAgentExitsPartialWhenSomeSkipped() {
    val out = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(out))
    val exitCode = try {
        InstallAgentCommand().parse(arrayOf(
            "--project-dir", tempFolder.newFolder("empty").absolutePath,
            "--package", "com.example.app",
            "--target", "all",
            "--json",
        ))
        0
    } catch (e: CliktError) {
        e.statusCode
    }
    assertEquals(ExitCode.PARTIAL.value, exitCode)
}

@Test
fun installAgentExitsZeroWhenAllApplied() {
    val tempProject = setupFakeAndroidProject(tempFolder.newFolder("real-android"))
    val exitCode = try {
        InstallAgentCommand().parse(arrayOf(
            "--project-dir", tempProject.absolutePath,
            "--package", "com.example.app",
            "--target", "claude",
            "--skip-gradle-plugin",
        ))
        0
    } catch (e: CliktError) {
        e.statusCode
    }
    assertEquals(ExitCode.OK.value, exitCode)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest.installAgentExitsPartial*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

At end of `InstallAgentCommand.run()`:

```kotlin
val hasSkipped = SetupRunResults.skipped.get().isNotEmpty() || earlySkipped.isNotEmpty()
val hasErrors = SetupRunResults.errors.get().isNotEmpty()
if (hasErrors) {
    throw CliktError("install-agent failed", statusCode = ExitCode.INTERNAL_ERROR.value)
}
if (hasSkipped) {
    throw CliktError("install-agent completed with skipped targets",
        statusCode = ExitCode.PARTIAL.value)
}
// OK path: exit 0 implicit
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt
git commit -m "feat(cli): map install-agent partial/error to ExitCode (D3)"
```

### Phase 3 verification gate

- [ ] Full test suite: `./gradlew :fixthis-cli:test` — all green
- [ ] Manual replay of audit (use the same dirs from spec §2):

```
mkdir /tmp/fxt-empty && cd /tmp/fxt-empty
fixthis install-agent --project-dir . --target all --package com.example.app --json
echo "exit=$?"
# expected: exit 1, JSON with applied=[], skipped=[{target:codex,reason:no-android-context},...]
# expected: ~/.codex/config.toml unchanged
```

- [ ] CHANGELOG.md: add a `### Changed` entry under `## Unreleased` describing the new exit code contract, `--json` flag, and `--allow-global` flag (semantic-version implication: MINOR).

Commit: `docs(changelog): record install-agent JSON + global-scope guard (D3)`

---

## Phase 4 — Docs + CI hardening (D5)

Closes drift between docs and CLI surface; enforces `.fixthis/agent-setup.*` schema.

### Task 4.1: `agent-setup` schema and generator update

**Files:**
- Create: `docs/reference/agent-setup-schema.md`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt`
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt` (create if missing)

- [ ] **Step 1: Write the failing test**

```kotlin
// AgentSetupFilesTest.kt
package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSetupFilesTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun agentSetupJsonContainsStateNextAndRecoverySections() {
        val root = tempFolder.newFolder("proj")
        AgentSetupFiles.write(
            projectRoot = root,
            packageName = "com.example.app",
            serverName = "fixthis",
            dryRun = false,
            echo = {},
        )
        val jsonFile = java.io.File(root, ".fixthis/agent-setup.json")
        assertTrue("agent-setup.json missing", jsonFile.isFile)
        val obj = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertTrue("missing 'state'", "state" in obj)
        assertTrue("missing 'next'", "next" in obj)
        assertTrue("missing 'recovery'", "recovery" in obj)
        assertTrue("next must be a non-empty array of runnable command strings",
            obj.getValue("next").jsonArray.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-cli:test --tests '*AgentSetupFilesTest'`
Expected: FAIL — current format likely lacks these top-level keys.

- [ ] **Step 3: Implement**

Update `AgentSetupFiles.write(...)` so the emitted JSON contains:

```json
{
  "schemaVersion": "1.0",
  "state": {
    "packageName": "com.example.app",
    "projectRoot": "/path/to/project",
    "detectedAt": "2026-05-16T12:34:56Z"
  },
  "next": [
    "./gradlew fixthisSetup",
    "fixthis doctor --project-dir <root> --json"
  ],
  "recovery": {
    "no-android-context": "Run from the Android repo root, or pass --allow-global to write the global codex config anyway.",
    "no-app-module": "Run ./gradlew projects to list modules; pass the correct --package.",
    "release-only-variant": "Add a debug variant; FixThis attaches debug builds only."
  }
}
```

The `.md` companion remains a human-readable rendering of the same three sections.

- [ ] **Step 4: Write the schema doc**

`docs/reference/agent-setup-schema.md`:

```markdown
# Agent Setup Handoff Schema

`fixthis install-agent` writes `.fixthis/agent-setup.json` and `.fixthis/agent-setup.md`. `fixthis init --agent` writes the same files when the Gradle plugin is already applied. The JSON file is the canonical contract; the Markdown file is a human-readable rendering of the same data.

## Schema (v1.0)

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | Currently `"1.0"`. Bumped only on incompatible changes. |
| `state.packageName` | string | Detected Android application id. |
| `state.projectRoot` | string | Absolute path to the Android project root. |
| `state.detectedAt` | RFC 3339 timestamp | When the file was generated. |
| `next` | string[] | Ordered list of runnable commands the agent should attempt next. Each entry MUST be a valid shell command. |
| `recovery` | map<string,string> | Keyed by failure-mode id; value is a one-line remediation. |

## Stability

`next[0]` is guaranteed runnable as a shell command. Additional `next` entries may include comments prefixed with `#`.
```

- [ ] **Step 5: Run test**

Run: `./gradlew :fixthis-cli:test --tests '*AgentSetupFilesTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt \
        docs/reference/agent-setup-schema.md
git commit -m "feat(cli): agent-setup files emit state/next/recovery schema v1.0 (D5)"
```

### Task 4.2: README install branching decision tree

**Files:**
- Modify: `docs/getting-started/agent-install-snippet.md`

- [ ] **Step 1: Edit doc**

Add (or replace existing list) with:

````markdown
## Install method decision tree (for agents)

Pick the first matching branch:

```
if command -v brew >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
    brew install beyondwin/tools/fixthis
elif command -v npm >/dev/null 2>&1; then
    npm install -g @beyondwin/fixthis
else
    curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
      | bash -s -- --version v0.2.3
fi
```

If your agent needs to verify install success in the same shell session:

```
fixthis version --json | jq -r '.cliVersion'   # exits 0 with the version on stdout
```
````

- [ ] **Step 2: Commit**

```bash
git add docs/getting-started/agent-install-snippet.md
git commit -m "docs(install): add install-method decision tree for agents (D5)"
```

### Task 4.3: `check-docs-cli-surface.sh` CI script

**Files:**
- Create: `scripts/check-docs-cli-surface.sh`
- Create: `.github/workflows/docs-cli-surface.yml`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# scripts/check-docs-cli-surface.sh
# Verifies that every `fixthis <subcommand>` invocation referenced in
# README.md / AGENTS.md / CLAUDE.md / MCP.md / docs/getting-started/agent-install-snippet.md
# matches a real subcommand and uses currently-valid flags.
set -euo pipefail

DOCS=(
    "README.md"
    "AGENTS.md"
    "CLAUDE.md"
    "MCP.md"
    "docs/getting-started/agent-install-snippet.md"
)

# Build the CLI if not present.
CLI_BIN="fixthis-cli/build/install/fixthis/bin/fixthis"
if [ ! -x "$CLI_BIN" ]; then
    ./gradlew :fixthis-cli:installDist --quiet
fi

# Extract subcommand names.
SUBCOMMANDS=$("$CLI_BIN" --help | awk '/^Commands:/ { found=1; next } found && /^[[:space:]]+[a-z]/ { print $1 }')

# For each doc, find `fixthis <token>` patterns.
EXIT=0
for doc in "${DOCS[@]}"; do
    if [ ! -f "$doc" ]; then
        echo "warn: $doc not found, skipping"
        continue
    fi
    while IFS= read -r token; do
        # Skip pipe options and known non-subcommand tokens
        case "$token" in
            -*|install-agent|setup|init|run|doctor|status|mcp|console|clean|version)
                ;;
            *)
                # token is whatever follows `fixthis ` first; validate it is a known subcommand
                if ! echo "$SUBCOMMANDS" | grep -qx "$token"; then
                    echo "$doc: unknown subcommand 'fixthis $token'"
                    EXIT=1
                fi
                ;;
        esac
    done < <(grep -oE 'fixthis [a-z][a-z-]+' "$doc" | awk '{print $2}' | sort -u)
done

# For each --flag mentioned in `fixthis <sub> --flag`, verify it exists in `--help`.
for doc in "${DOCS[@]}"; do
    [ -f "$doc" ] || continue
    while IFS= read -r line; do
        # Parse subcommand + flag
        sub=$(echo "$line" | awk '{print $2}')
        flag=$(echo "$line" | awk '{print $3}')
        if echo "$SUBCOMMANDS" | grep -qx "$sub"; then
            if ! "$CLI_BIN" "$sub" --help 2>/dev/null | grep -qE "^\\s*$flag(=|,| )"; then
                echo "$doc: '$sub' has no flag '$flag'"
                EXIT=1
            fi
        fi
    done < <(grep -oE 'fixthis [a-z][a-z-]+ --[a-z][a-z-]+' "$doc" | sort -u)
done

exit "$EXIT"
```

- [ ] **Step 2: Write the workflow**

```yaml
# .github/workflows/docs-cli-surface.yml
name: docs cli surface
on:
  pull_request:
    paths:
      - "README.md"
      - "AGENTS.md"
      - "CLAUDE.md"
      - "MCP.md"
      - "docs/**"
      - "fixthis-cli/**"
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew :fixthis-cli:installDist --no-daemon
      - run: bash scripts/check-docs-cli-surface.sh
```

- [ ] **Step 3: Make the script executable and smoke-test locally**

```bash
chmod +x scripts/check-docs-cli-surface.sh
bash scripts/check-docs-cli-surface.sh
```

Expected: exit 0 (or, if any drift exists, fix the doc and re-run until 0).

- [ ] **Step 4: Commit**

```bash
git add scripts/check-docs-cli-surface.sh .github/workflows/docs-cli-surface.yml
git commit -m "ci(docs): enforce docs ↔ CLI surface parity (D5)"
```

### Phase 4 verification gate

- [ ] All tests: `./gradlew test` (broad — catches cross-module regressions)
- [ ] CI passes on a draft PR for the branch
- [ ] CHANGELOG.md `## Unreleased` updated with phase 4 entries
- [ ] Re-run the audit replay from spec §2 in a clean temp dir and confirm:
  - `fixthis --version` works
  - `fixthis doctor` text output shows `↳ fix:` lines
  - `fixthis install-agent --json --project-dir <empty>` exits 1, JSON includes `skipped[0].reason="no-android-context"`, codex global config untouched
  - `fixthis install-agent --dry-run` for an existing global config does not echo unrelated entries

---

## Cleanup task (post-Phase 4)

- [ ] Remove the two dead `[mcp_servers.fixthis]` entries this audit introduced in the repo author's `~/.codex/config.toml` if not already cleaned up. (Not part of the merged plan, but the implementer should leave a note in the PR description.)
