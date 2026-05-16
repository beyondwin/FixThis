# v0.3 First-Run Trust Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make first-run install, diagnosis, console recovery, notification surfaces, and handoff failures use one recoverable readiness contract.

**Architecture:** Add `FirstRunReadiness` in `:fixthis-cli` because `:fixthis-mcp` already depends on CLI bridge utilities. Thread that contract into doctor, install-agent reports, agent setup files, console connection status, browser structured errors, and console notification policy without changing the Android sidekick protocol. Keep `statusSurfaceRegistry` as the low-level surface coordinator and add a focused `notificationCenter.js` policy layer above it.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization JSON, Clikt, JUnit 4, Node 20 `node:test`, Playwright fixture smoke harness, existing console `// @requires` bundler.

**Spec:** [`../specs/2026-05-16-v03-first-run-trust-program-design.md`](../specs/2026-05-16-v03-first-run-trust-program-design.md)

---

## Scope Check

The spec spans CLI, MCP console routes, browser console JavaScript, and smoke
tests. Keep it as one plan because the shared readiness contract is the core
unit of behavior; each task below remains independently testable and
committable.

## File Structure

| Path | Responsibility |
| --- | --- |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt` | Shared readiness enum, DTO, canned state catalog, and mapping helpers. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt` | Contract tests for state names, canned actions, and message classification. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt` | Emit readiness details in doctor JSON and text failures. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt` | Pin doctor readiness fields. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt` | Add readiness to skipped/error entries and top-level next action. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt` | Pin install-agent JSON readiness output. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt` | Write readiness recovery contract into `.fixthis/agent-setup.*`. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt` | Pin agent setup readiness sections. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt` | Add readiness to `/api/connection` payload. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt` | Map device, launch, unsupported build, and ready states to readiness. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt` | Pin readiness on connection states. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt` | Preserve structured HTTP error fields for browser clients. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpErrorBodyTest.kt` | Pin error JSON shape. |
| `fixthis-mcp/src/main/console/api.js` | Throw a structured `ConsoleRequestError` from failed HTTP responses. |
| `scripts/requestJson-test.mjs` | Pin structured browser HTTP errors. |
| `fixthis-mcp/src/main/console/notificationCenter.js` | Policy layer for toast, banner, inline, and modal notifications. |
| `scripts/notificationCenter-test.mjs` | Pin dedupe, TTL, surface routing, stack behavior, and error policy. |
| `fixthis-mcp/src/main/console/state.js` | Route `showStatus`, `showError`, `showWarning`, `showSuccess` through `NotificationCenter`. |
| `fixthis-mcp/src/main/console/boundaryDialogVariants.js` | Add variants for clear local draft, clear server drafts, fingerprint mismatch, and recovery recapture. |
| `fixthis-mcp/src/main/console/main.js` | Replace pending boundary and recapture native confirms with in-app prompts. |
| `fixthis-mcp/src/main/console/devices.js` | Replace forget-device native confirm with in-app prompt. |
| `fixthis-mcp/src/main/console/history.js` | Replace clear local draft and clear server drafts native confirms with in-app prompts. |
| `fixthis-mcp/src/main/console/annotations.js` | Replace fingerprint mismatch native confirm with in-app prompt. |
| `scripts/nativeConfirmAudit-test.mjs` | Pin that production console paths no longer call `window.confirm`. |
| `scripts/first-run-smoke.mjs` | Deterministic Playwright smoke for welcome to ready to annotation to handoff. |
| `scripts/console-fixture/fakeBridgeServer.mjs` | Add fixture scenarios and endpoints needed by first-run smoke. |
| `scripts/console-fixture/scenarios-test.mjs` | Pin new fixture scenarios. |
| `package.json` | Add `first-run:smoke` script. |
| `scripts/console-tests.json` | Add `notification` and first-run-related unit tests to the grouped runner. |
| `docs/guides/troubleshooting.md` | Document readiness states and notification recovery behavior. |
| `docs/releases/unreleased.md` | Add v0.3 first-run trust note. |

## Task 1: Shared FirstRunReadiness Contract

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt`

- [ ] **Step 1: Write the failing test**

Create `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunReadinessTest {
    @Test
    fun stateNamesMatchV03Contract() {
        assertEquals(
            listOf(
                "READY",
                "NEEDS_INSTALL",
                "NEEDS_APP_LAUNCH",
                "DEVICE_BLOCKED",
                "UNSUPPORTED_BUILD",
                "CONFIG_RECOVERABLE",
                "ENV_BLOCKER",
                "STALE_PREVIEW",
                "SESSION_MISMATCH",
                "UNKNOWN_ERROR",
            ),
            FirstRunReadinessState.entries.map { it.name },
        )
    }

    @Test
    fun catalogProvidesOnePrimaryNextAction() {
        val unsupported = FirstRunReadinessCatalog.unsupportedBuild(
            cause = "run-as denied",
            details = mapOf("rawError" to "run-as: package not debuggable"),
        )

        assertEquals(FirstRunReadinessState.UNSUPPORTED_BUILD, unsupported.state)
        assertEquals("Install a debuggable build with FixThis enabled.", unsupported.nextAction)
        assertTrue(unsupported.fix.contains("debuggable"))
        assertEquals("run-as: package not debuggable", unsupported.details.getValue("rawError"))
    }

    @Test
    fun classifyBridgeErrorMapsKnownErrors() {
        assertEquals(
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            classifyBridgeFailure("run-as: package not debuggable").state,
        )
        assertEquals(
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            classifyBridgeFailure("run-as: permission denied").state,
        )
        assertEquals(
            FirstRunReadinessState.NEEDS_APP_LAUNCH,
            classifyBridgeFailure("Could not connect to FixThis bridge").state,
        )
        assertEquals(
            FirstRunReadinessState.UNKNOWN_ERROR,
            classifyBridgeFailure("Socket failed for unexpected reason").state,
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessTest'
```

Expected: compilation fails with unresolved `FirstRunReadinessState`.

- [ ] **Step 3: Implement the readiness contract**

Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.readiness

import kotlinx.serialization.Serializable

@Serializable
enum class FirstRunReadinessState {
    READY,
    NEEDS_INSTALL,
    NEEDS_APP_LAUNCH,
    DEVICE_BLOCKED,
    UNSUPPORTED_BUILD,
    CONFIG_RECOVERABLE,
    ENV_BLOCKER,
    STALE_PREVIEW,
    SESSION_MISMATCH,
    UNKNOWN_ERROR,
}

@Serializable
data class FirstRunReadiness(
    val state: FirstRunReadinessState,
    val cause: String,
    val verify: String,
    val fix: String,
    val nextAction: String,
    val details: Map<String, String> = emptyMap(),
)

object FirstRunReadinessCatalog {
    fun ready(details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.READY,
        cause = "Debug app and FixThis sidekick are connected.",
        verify = "Capture a screen from the feedback console.",
        fix = "No recovery action required.",
        nextAction = "Capture screen",
        details = details,
    )

    fun needsInstall(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.NEEDS_INSTALL,
        cause = cause,
        verify = "./gradlew fixthisSetup",
        fix = "Run `fixthis install-agent --project-dir . --target all` from the Android project root.",
        nextAction = "Run `fixthis install-agent --project-dir . --target all`",
        details = details,
    )

    fun needsAppLaunch(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.NEEDS_APP_LAUNCH,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = "Launch the debug app so the FixThis sidekick can write its bridge session.",
        nextAction = "Open app",
        details = details,
    )

    fun deviceBlocked(cause: String, fix: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.DEVICE_BLOCKED,
        cause = cause,
        verify = "Check the device state shown in the feedback console.",
        fix = fix,
        nextAction = fix,
        details = details,
    )

    fun unsupportedBuild(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.UNSUPPORTED_BUILD,
        cause = cause,
        verify = "adb shell run-as <applicationId> ls files/fixthis/session.json",
        fix = "Install a debuggable build with the FixThis sidekick enabled.",
        nextAction = "Install a debuggable build with FixThis enabled.",
        details = details,
    )

    fun configRecoverable(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.CONFIG_RECOVERABLE,
        cause = cause,
        verify = "fixthis install-agent --project-dir . --target all --dry-run",
        fix = "Re-run FixThis setup or inspect the dry-run diff before writing config.",
        nextAction = "Run `fixthis install-agent --project-dir . --target all --dry-run`",
        details = details,
    )

    fun envBlocker(cause: String, fix: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.ENV_BLOCKER,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = fix,
        nextAction = fix,
        details = details,
    )

    fun stalePreview(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.STALE_PREVIEW,
        cause = cause,
        verify = "Compare the frozen preview with the current app screen.",
        fix = "Recapture, force-save the frozen preview, or cancel.",
        nextAction = "Choose Recapture, Force save, or Cancel.",
        details = details,
    )

    fun sessionMismatch(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.SESSION_MISMATCH,
        cause = cause,
        verify = "Compare the response sessionId with the active feedback session.",
        fix = "Refresh the active session or return to the matching history item.",
        nextAction = "Refresh session",
        details = details,
    )

    fun unknown(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.UNKNOWN_ERROR,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = "Open diagnostic details and rerun the failed command with --verbose.",
        nextAction = "Open diagnostic details",
        details = details,
    )
}

fun classifyBridgeFailure(rawError: String?): FirstRunReadiness {
    val raw = rawError.orEmpty()
    val lower = raw.lowercase()
    return when {
        "not debuggable" in lower ||
            ("run-as" in lower && "permission" in lower) ||
            ("run-as" in lower && "denied" in lower) ->
            FirstRunReadinessCatalog.unsupportedBuild(
                cause = "This build cannot expose the FixThis bridge.",
                details = mapOf("rawError" to raw),
            )
        "could not connect to fixthis bridge" in lower ||
            "bridge closed before sending a response" in lower ||
            ("bridge" in lower && "timed out" in lower) ->
            FirstRunReadinessCatalog.needsAppLaunch(
                cause = "The debug app is not connected to the FixThis bridge.",
                details = mapOf("rawError" to raw),
            )
        else -> FirstRunReadinessCatalog.unknown(
            cause = raw.ifBlank { "FixThis could not classify this first-run failure." },
            details = if (raw.isBlank()) emptyMap() else mapOf("rawError" to raw),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :fixthis-cli:test --tests 'io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessTest'
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt
git commit -m "feat(cli): add first-run readiness contract"
```

## Task 2: Doctor, Install-Agent, and Agent Setup Readiness

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt`

- [ ] **Step 1: Write failing doctor readiness assertions**

In `DoctorCommandTest.kt`, add imports:

```kotlin
import kotlinx.serialization.json.jsonPrimitive
```

Extend `doctorJsonReportIncludesStableStatusAndFixFields` after the existing
`fix` assertion:

```kotlin
        val readiness = failed.getValue("readiness").jsonObject
        assertEquals("ENV_BLOCKER", readiness.getValue("state").jsonPrimitive.content)
        assertTrue(readiness.getValue("nextAction").jsonPrimitive.content.contains("emulator"))
```

- [ ] **Step 2: Write failing install-agent report assertions**

In `InstallAgentJsonReportTest.kt`, add a test:

```kotlin
    @Test
    fun skippedEntriesCarryReadinessAndTopLevelNextAction() {
        val rendered = InstallAgentJsonReport.render(
            applied = emptyList(),
            skipped = listOf(
                InstallAgentJsonReport.Skipped(
                    target = "codex",
                    reason = "no-android-context",
                    fix = "Re-run from an Android project root, or pass --allow-global.",
                    readiness = FirstRunReadinessCatalog.configRecoverable(
                        cause = "Codex global config was skipped outside an Android project.",
                    ),
                ),
            ),
            errors = emptyList(),
            next = listOf("cd <android-project-root>", "fixthis install-agent --project-dir ."),
        )

        val root = Json.parseToJsonElement(rendered).jsonObject
        val skipped = root.getValue("skipped").jsonArray.single().jsonObject
        assertEquals("CONFIG_RECOVERABLE", skipped.getValue("readiness").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("cd <android-project-root>", root.getValue("nextAction").jsonPrimitive.content)
    }
```

Add these imports to that test file:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 3: Write failing agent setup readiness assertions**

In `AgentSetupFilesTest.kt`, extend `agentSetupJsonContainsStateNextAndRecoverySections`:

```kotlin
        assertTrue("missing 'readiness'", "readiness" in obj)
        assertTrue("missing readiness recovery map", "readinessRecovery" in obj.getValue("recovery").jsonObject)
        val readiness = obj.getValue("readiness").jsonObject
        assertTrue(readiness.getValue("nextAction").jsonPrimitive.content.contains("fixthis doctor"))
```

Add this import:

```kotlin
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 4: Run focused tests to verify they fail**

Run:

```bash
./gradlew :fixthis-cli:test \
  --tests 'io.github.beyondwin.fixthis.cli.commands.DoctorCommandTest' \
  --tests 'io.github.beyondwin.fixthis.cli.commands.InstallAgentJsonReportTest' \
  --tests 'io.github.beyondwin.fixthis.cli.commands.AgentSetupFilesTest'
```

Expected: compilation fails because `readiness` constructor arguments and JSON
fields do not exist.

- [ ] **Step 5: Update doctor result model and JSON rendering**

In `DoctorCommand.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
```

Change `DoctorCheckResult` to:

```kotlin
internal data class DoctorCheckResult(
    val name: String,
    val label: String,
    val ok: Boolean,
    val message: String? = null,
    val fix: String? = null,
    val readiness: FirstRunReadiness? = null,
)
```

Inside `check`, replace the failed `DoctorCheckResult` creation with:

```kotlin
                val readiness = readinessForDoctorCheck(name, error.message, fix)
                checks += DoctorCheckResult(
                    name = name,
                    label = label,
                    ok = false,
                    message = error.message,
                    fix = fix,
                    readiness = readiness,
                )
```

Add this helper below `DoctorCheckResult`:

```kotlin
internal fun readinessForDoctorCheck(name: String, message: String?, fix: String): FirstRunReadiness = when (name) {
    "android_project_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "Android project root was not found.",
        fix = fix,
    )
    "fixthis_project_metadata_found" -> FirstRunReadinessCatalog.needsInstall(
        cause = message ?: "FixThis project metadata was not found.",
    )
    "adb_found", "device_connected" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "ADB or a ready Android device is unavailable.",
        fix = fix,
    )
    "sidekick_session_found" -> FirstRunReadinessCatalog.needsAppLaunch(
        cause = message ?: "FixThis sidekick session was not found.",
    )
    else -> FirstRunReadinessCatalog.unknown(
        cause = message ?: "Doctor check failed: $name",
    )
}
```

Inside `renderDoctorJsonReport`, after `check.fix?.let { put("fix", it) }`,
add:

```kotlin
                            check.readiness?.let {
                                put(
                                    "readiness",
                                    fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject,
                                )
                            }
```

- [ ] **Step 6: Update install-agent JSON report types**

In `InstallAgentJsonReport.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
```

Change the data classes to:

```kotlin
    data class Skipped(
        val target: String,
        val reason: String,
        val fix: String,
        val readiness: FirstRunReadiness? = null,
    )

    data class ErrorEntry(
        val target: String,
        val message: String,
        val readiness: FirstRunReadiness? = null,
    )
```

Inside skipped entry rendering, after `put("fix", sk.fix)`, add:

```kotlin
                                sk.readiness?.let {
                                    put("readiness", compactJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                                }
```

Inside error entry rendering, after `put("message", e.message)`, add:

```kotlin
                                e.readiness?.let {
                                    put("readiness", compactJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                                }
```

After rendering the `next` array, add:

```kotlin
            next.firstOrNull()?.let { put("nextAction", it) }
```

- [ ] **Step 7: Add readiness to early skipped install-agent entries**

In `SetupCommand.kt`, import:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
```

In the `earlySkipped` `InstallAgentJsonReport.Skipped` call, add:

```kotlin
                        readiness = FirstRunReadinessCatalog.configRecoverable(
                            cause = "Codex global config was skipped outside an Android project.",
                        ),
```

- [ ] **Step 8: Update agent setup manifest**

In `AgentSetupFiles.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
```

In `agentSetupManifest`, define the current readiness before `buildJsonObject`:

```kotlin
    private fun agentSetupManifest(packageName: String, projectRoot: File): JsonObject {
        val readiness = FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis agent setup files were written; verify the debug app before opening the console.",
            details = mapOf("packageName" to packageName, "projectRoot" to projectRoot.absolutePath),
        ).copy(
            verify = "fixthis doctor --project-dir ${projectRoot.absolutePath} --json",
            fix = "Run doctor, restart the agent MCP client, then open the feedback console.",
            nextAction = "fixthis doctor --project-dir ${projectRoot.absolutePath} --json",
        )
        return buildJsonObject {
            // existing fields stay here
        }
    }
```

Move the existing body into that `return buildJsonObject { ... }`. Inside the
root object, after the `state` object, add:

```kotlin
        put("readiness", fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), readiness).jsonObject)
```

Inside the existing `recovery` object, add:

```kotlin
                put(
                    "readinessRecovery",
                    buildJsonObject {
                        put("NEEDS_INSTALL", JsonPrimitive("Run `fixthis install-agent --project-dir . --target all`."))
                        put("CONFIG_RECOVERABLE", JsonPrimitive("Run `fixthis install-agent --project-dir . --target all --dry-run`, inspect the diff, then rerun without --dry-run."))
                        put("ENV_BLOCKER", JsonPrimitive("Install missing local prerequisites, then run `fixthis doctor --project-dir . --json`."))
                        put("UNSUPPORTED_BUILD", JsonPrimitive("Install a debuggable build with the FixThis sidekick enabled."))
                        put("NEEDS_APP_LAUNCH", JsonPrimitive("Launch the debug app or click Start in the feedback console."))
                    },
                )
```

- [ ] **Step 9: Run focused tests**

Run:

```bash
./gradlew :fixthis-cli:test \
  --tests 'io.github.beyondwin.fixthis.cli.commands.DoctorCommandTest' \
  --tests 'io.github.beyondwin.fixthis.cli.commands.InstallAgentJsonReportTest' \
  --tests 'io.github.beyondwin.fixthis.cli.commands.AgentSetupFilesTest'
```

Expected: pass.

- [ ] **Step 10: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt \
        fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt
git commit -m "feat(cli): surface readiness in first-run reports"
```

## Task 3: Console Connection Readiness

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt`

- [ ] **Step 1: Write failing readiness assertions**

In `ConsoleConnectionServiceTest.kt`, add:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
```

Extend `noReadyDevicesMapsToCheckPhone`:

```kotlin
        assertEquals(FirstRunReadinessState.ENV_BLOCKER, status.readiness?.state)
```

Extend `singleReadyDeviceWithoutSelectionMapsToWelcome`:

```kotlin
        assertEquals(FirstRunReadinessState.NEEDS_APP_LAUNCH, status.readiness?.state)
```

Add this test:

```kotlin
    @Test
    fun unsupportedBridgeFailureMapsToUnsupportedBuildReadiness() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(AdbDevice("device-1", "device")),
            heartbeatError = IllegalStateException("run-as: package not debuggable"),
        )
        bridge.selectDevice("device-1")
        val service = ConsoleConnectionService(bridge)

        val status = service.connectionStatus(session())

        assertEquals(ConsoleConnectionState.UNSUPPORTED_BUILD, status.state)
        assertEquals(FirstRunReadinessState.UNSUPPORTED_BUILD, status.readiness?.state)
        assertEquals("Install a debuggable build with FixThis enabled.", status.readiness?.nextAction)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.session.ConsoleConnectionServiceTest'
```

Expected: compilation fails because `ConsoleConnectionStatus.readiness` and
`FakeFixThisBridge.heartbeatError` do not exist.

- [ ] **Step 3: Add readiness to connection models**

In `ConsoleConnectionModels.kt`, import:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
```

Add this property to `ConsoleConnectionStatus` before `details`:

```kotlin
    val readiness: FirstRunReadiness? = null,
```

- [ ] **Step 4: Map readiness in ConsoleConnectionService**

In `ConsoleConnectionService.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.cli.readiness.classifyBridgeFailure
```

In the ADB devices catch block, add this argument:

```kotlin
                readiness = FirstRunReadinessCatalog.envBlocker(
                    cause = "ADB could not list Android devices.",
                    fix = "Install Android SDK platform-tools or fix `adb devices`.",
                    details = mapOf("rawError" to raw),
                ),
```

In `readyDevices.isEmpty()`, add:

```kotlin
                readiness = FirstRunReadinessCatalog.envBlocker(
                    cause = "No ready Android device or emulator is connected.",
                    fix = "Start an emulator or connect and authorize a device.",
                    details = mapOf("deviceState" to (unavailable?.state ?: "none")),
                ),
```

In the single ready WELCOME response, add:

```kotlin
                    readiness = FirstRunReadinessCatalog.needsAppLaunch(
                        cause = "A device is ready; start the debug app to connect FixThis.",
                    ),
```

In the CHOOSE_DEVICE response, add:

```kotlin
                    readiness = FirstRunReadinessCatalog.envBlocker(
                        cause = "More than one ready Android device is connected.",
                        fix = "Choose a device in the feedback console.",
                        details = mapOf("readyDeviceCount" to readyDevices.size.toString()),
                    ),
```

In the selected device unavailable response, add:

```kotlin
                readiness = FirstRunReadinessCatalog.envBlocker(
                    cause = "The selected Android device is not ready.",
                    fix = "Select a connected device or fix `adb devices`.",
                    details = mapOf("deviceState" to (selectedDevice?.state ?: "missing")),
                ),
```

In the READY response, add:

```kotlin
                readiness = FirstRunReadinessCatalog.ready(
                    details = mapOf("deviceSerial" to selectedDevice.serial),
                ),
```

In the bridge failure catch block, define:

```kotlin
            val readiness = classifyBridgeFailure(raw)
```

Then add this argument to the returned `ConsoleConnectionStatus`:

```kotlin
                readiness = readiness,
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.session.ConsoleConnectionServiceTest'
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt
git commit -m "feat(console): include readiness in connection status"
```

## Task 4: Structured Console HTTP Errors in Browser

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpErrorBodyTest.kt`
- Modify: `fixthis-mcp/src/main/console/api.js`
- Create: `scripts/requestJson-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing Kotlin error-body test**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpErrorBodyTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleHttpErrorBodyTest {
    @Test
    fun errorBodyCarriesDetails() {
        val encoded = fixThisJson.encodeToString(
            ConsoleErrorBody.serializer(),
            ConsoleErrorBody(
                error = "screen_fingerprint_mismatch",
                message = "The screen changed.",
                action = "choose_recapture_force_or_cancel",
                details = mapOf("sessionId" to "session-1"),
            ),
        )
        val obj = fixThisJson.parseToJsonElement(encoded).jsonObject

        assertEquals("screen_fingerprint_mismatch", obj.getValue("error").jsonPrimitive.content)
        assertEquals("choose_recapture_force_or_cancel", obj.getValue("action").jsonPrimitive.content)
        assertEquals(
            "session-1",
            obj.getValue("details").jsonObject.getValue("sessionId").jsonPrimitive.content,
        )
    }
}
```

- [ ] **Step 2: Write failing browser requestJson test**

Create `scripts/requestJson-test.mjs`:

```js
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

test('requestJson preserves structured error body', async () => {
  const responseBody = {
    error: 'forbidden_origin',
    message: 'Forbidden origin',
    action: 'reload_console',
    details: { expectedHost: '127.0.0.1' },
  };
  const fetchImpl = async () => ({
    ok: false,
    status: 403,
    headers: new Map([['content-type', 'application/json']]),
    async text() { return JSON.stringify(responseBody); },
    async json() { return responseBody; },
  });
  const { requestJson, ConsoleRequestError } = loadConsoleSymbols({
    modules: ['api.js'],
    symbols: ['requestJson', 'ConsoleRequestError'],
    args: ['fetch'],
    values: [fetchImpl],
  });

  await assert.rejects(
    () => requestJson('/api/items', { method: 'POST' }),
    (error) => {
      assert.ok(error instanceof ConsoleRequestError);
      assert.equal(error.status, 403);
      assert.equal(error.error, 'forbidden_origin');
      assert.equal(error.action, 'reload_console');
      assert.equal(error.details.expectedHost, '127.0.0.1');
      return true;
    },
  );
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleHttpErrorBodyTest'
node --test scripts/requestJson-test.mjs
```

Expected: Kotlin compilation fails because `details` is not a field; Node test
fails because `ConsoleRequestError` is missing.

- [ ] **Step 4: Extend ConsoleErrorBody**

In `ConsoleHttp.kt`, change `ConsoleErrorBody` to:

```kotlin
@Serializable
internal data class ConsoleErrorBody(
    val error: String,
    val message: String? = null,
    val action: String? = null,
    val frozenFingerprint: String? = null,
    val currentFingerprint: String? = null,
    val details: Map<String, String> = emptyMap(),
)
```

Add a `details` parameter to `sendErrorJson`:

```kotlin
internal fun HttpExchange.sendErrorJson(
    status: Int,
    error: String,
    message: String? = null,
    action: String? = null,
    details: Map<String, String> = emptyMap(),
) {
    sendText(
        status,
        fixThisJson.encodeToString(
            ConsoleErrorBody.serializer(),
            ConsoleErrorBody(error = error, message = message, action = action, details = details),
        ),
        "application/json; charset=utf-8",
    )
}
```

Leave existing callers unchanged because `details` has a default.

- [ ] **Step 5: Implement structured browser errors**

Replace `fixthis-mcp/src/main/console/api.js` with:

```js
// @requires state.js
            class ConsoleRequestError extends Error {
              constructor({ status, error, message, action, details, bodyText }) {
                super(message || error || bodyText || ('HTTP ' + status));
                this.name = 'ConsoleRequestError';
                this.status = status;
                this.error = error || null;
                this.action = action || null;
                this.details = details || {};
                this.bodyText = bodyText || '';
              }
            }

            function responseContentType(response) {
              if (!response?.headers) return '';
              if (typeof response.headers.get === 'function') return response.headers.get('content-type') || '';
              if (typeof response.headers.get === 'undefined' && typeof response.headers[Symbol.iterator] === 'function') {
                for (const [key, value] of response.headers) {
                  if (String(key).toLowerCase() === 'content-type') return String(value);
                }
              }
              return '';
            }

            async function readStructuredError(response) {
              const bodyText = await response.text();
              const contentType = responseContentType(response);
              if (contentType.includes('application/json') || bodyText.trim().startsWith('{')) {
                try {
                  const parsed = JSON.parse(bodyText);
                  return new ConsoleRequestError({
                    status: response.status,
                    error: parsed.error,
                    message: parsed.message,
                    action: parsed.action,
                    details: parsed.details,
                    bodyText,
                  });
                } catch (_) {
                  return new ConsoleRequestError({ status: response.status, bodyText });
                }
              }
              return new ConsoleRequestError({ status: response.status, bodyText });
            }

            async function requestJson(path, options = {}) {
              const method = (options.method || 'GET').toUpperCase();
              const headers = new Headers(options.headers || {});
              if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
                const token = window.FixThisConsoleConfig?.consoleToken;
                if (token) headers.set('X-FixThis-Console-Token', token);
              }
              const response = await fetch(path, { ...options, headers });
              if (!response.ok) {
                throw await readStructuredError(response);
              }
              return response.json();
            }

            async function copyTextToClipboard(text) {
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(text);
                  return;
                }
              } catch (cause) {
                // Fall back below for browser surfaces that deny Clipboard API writes.
              }
              const fallback = document.createElement('textarea');
              fallback.value = text;
              fallback.setAttribute('readonly', '');
              fallback.style.position = 'fixed';
              fallback.style.top = '-9999px';
              fallback.style.left = '-9999px';
              document.body.appendChild(fallback);
              fallback.focus();
              fallback.select();
              const copied = document.execCommand('copy');
              fallback.remove();
              if (!copied) throw new Error('Copy failed. Select the prompt and copy it manually.');
            }
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleHttpErrorBodyTest'
node --test scripts/requestJson-test.mjs
```

Expected: pass.

- [ ] **Step 7: Rebuild and verify console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: rebuild updates `app.js`, `app.js.map`, and
`console-build-meta.json` if the bundle changed; `--check` succeeds (size
budget honored); `node --check` reports no syntax errors. These three
commands are required by `CONTRIBUTING.md` after any
`fixthis-mcp/src/main/console/` change.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpErrorBodyTest.kt \
        fixthis-mcp/src/main/console/api.js \
        scripts/requestJson-test.mjs \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): preserve structured API errors"
```

## Task 5: NotificationCenter Policy Layer

**Files:**
- Create: `fixthis-mcp/src/main/console/notificationCenter.js`
- Create: `scripts/notificationCenter-test.mjs`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing NotificationCenter tests**

Create `scripts/notificationCenter-test.mjs`:

```js
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createRegistry() {
  const calls = [];
  return {
    calls,
    hide(id) {
      calls.push({ kind: 'hide', id });
    },
    show(id, opts) {
      calls.push({ kind: 'show', id, opts });
    },
  };
}

test('NotificationCenter routes recoverable errors to banner, not toast', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({
    severity: 'error',
    surface: 'toast',
    title: 'Save failed',
    message: 'Draft preserved locally.',
    primaryAction: 'Retry save',
    dedupeKey: 'save-failed',
  });

  const show = registry.calls.find((call) => call.kind === 'show');
  assert.equal(show.id, 'save-failed');
  assert.equal(show.opts.surfaceClass, 'banner');
  assert.equal(show.opts.content.headline, 'Save failed');
  assert.equal(show.opts.content.detail, 'Draft preserved locally.');
});

test('NotificationCenter dedupes repeated polling failures', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling' });
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling' });

  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 1);
});

test('NotificationCenter keeps success as ttl toast', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'success', surface: 'toast', message: 'Saved', ttlMs: 1200 });

  const show = registry.calls.find((call) => call.kind === 'show');
  assert.equal(show.opts.surfaceClass, 'toast');
  assert.equal(show.opts.autoDismissMs, 1200);
});

test('NotificationCenter releases dedupe key after ttl so a later notify re-displays', () => {
  const registry = createRegistry();
  const timers = [];
  const fakeSetTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry, setTimeoutImpl: fakeSetTimeout });

  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  // While active, repeated notify is suppressed.
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 1);

  // After ttl, dedupe entry is released; a follow-up notify re-displays.
  assert.equal(timers.length, 1);
  timers[0].fn();
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 2);
});

test('NotificationCenter auto-promotes severity:error off toast unless explicitly allowed', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'error', surface: 'toast', message: 'Network blip', dedupeKey: 'e1' });
  assert.equal(registry.calls.find((c) => c.kind === 'show' && c.id === 'e1').opts.surfaceClass, 'banner');

  // Opt-in keeps the toast surface, e.g. transient retry-succeeded-but-warn case.
  center.notify({ severity: 'error', surface: 'toast', message: 'Retried', dedupeKey: 'e2', allowErrorToast: true });
  assert.equal(registry.calls.find((c) => c.kind === 'show' && c.id === 'e2').opts.surfaceClass, 'toast');
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
node --test scripts/notificationCenter-test.mjs
```

Expected: fails because `notificationCenter.js` does not exist.

- [ ] **Step 3: Implement NotificationCenter**

Create `fixthis-mcp/src/main/console/notificationCenter.js`:

```js
// @requires statusSurfaceRegistry.js
            const SupportedNotificationSurfaces = Object.freeze(new Set(['toast', 'banner', 'inline']));

            function notificationContent(input) {
              return {
                headline: input.title || input.message || '',
                detail: input.message || '',
                retry: Boolean(input.primaryAction),
              };
            }

            function normalizedNotificationSurface(input) {
              const requested = input.surface || (input.severity === 'success' ? 'toast' : 'banner');
              const surface = SupportedNotificationSurfaces.has(requested) ? requested : 'banner';
              // Auto-promote: severity:error never lands on toast unless the caller
              // explicitly opts in via allowErrorToast (e.g. retried-and-recovered).
              if (input.severity === 'error' && surface === 'toast' && !input.allowErrorToast) {
                return 'banner';
              }
              return surface;
            }

            function createNotificationCenter({
              registry = statusSurfaceRegistry,
              setTimeoutImpl = (typeof window !== 'undefined' ? window.setTimeout : setTimeout),
            } = {}) {
              const activeKeys = new Map(); // id -> { releaseTimer }

              function notify(input = {}) {
                const surfaceClass = normalizedNotificationSurface(input);
                const id = input.dedupeKey || [
                  input.severity || 'info',
                  surfaceClass,
                  input.title || '',
                  input.message || '',
                ].join(':');
                if (activeKeys.has(id)) return id;

                const autoDismissMs = input.ttlMs != null
                  ? input.ttlMs
                  : (surfaceClass === 'toast' ? 3000 : 0);
                const opts = {
                  surfaceClass,
                  priority: input.severity === 'error' ? 1 : input.severity === 'warning' ? 2 : 3,
                  content: surfaceClass === 'toast' ? (input.message || input.title || '') : notificationContent(input),
                  autoDismissMs,
                };
                // Track the entry, and release it once the surface auto-dismisses so a
                // later notify with the same dedupeKey can re-display (polling failure
                // recurrence, retried operations, etc.).
                let releaseTimer = null;
                if (autoDismissMs > 0) {
                  releaseTimer = setTimeoutImpl(() => {
                    activeKeys.delete(id);
                  }, autoDismissMs);
                }
                activeKeys.set(id, { releaseTimer });
                registry.show(id, opts);
                return id;
              }

              function hide(id) {
                const entry = activeKeys.get(id);
                if (entry && entry.releaseTimer != null && typeof clearTimeout === 'function') {
                  clearTimeout(entry.releaseTimer);
                }
                activeKeys.delete(id);
                registry.hide(id);
              }

              function clearRecoverable(id) {
                hide(id);
              }

              return { clearRecoverable, hide, notify };
            }
```

Notes:

- `inline` is supported as a surface but the registry is responsible for
  rendering it against the connection card / preview / inspector areas. The
  policy layer only routes the choice.
- `modal` / destructive sheets are intentionally not part of this API; those
  flow through `renderBoundaryDialog` (Task 6).
- Maximum visible toast count is delegated to the existing
  `statusSurfaceRegistry`. If a future audit shows the registry does not cap
  stack depth, add the cap there rather than re-implementing inside
  `NotificationCenter`.

- [ ] **Step 4: Route state.js status helpers through NotificationCenter**

In `fixthis-mcp/src/main/console/state.js`, update the `// @requires` header to
include `notificationCenter.js` before any module that uses status helpers:

```js
// @requires notificationCenter.js, ...
```

Near `const consoleApp = createConsoleApp({state})`, create:

```js
const notificationCenter = createNotificationCenter({ registry: statusSurfaceRegistry });
```

Inside `showStatus`, replace the `statusSurfaceRegistry.show('global-error', ...)`
call with:

```js
                  notificationCenter.notify({
                    severity: variant === 'error' ? 'error' : variant === 'warning' ? 'warning' : variant === 'success' ? 'success' : 'info',
                    // Successes are dismissible toasts; everything else (including
                    // errors with a TTL) stays as a banner so a recoverable error
                    // never reduces to a toast-only surface.
                    surface: variant === 'success' ? 'toast' : 'banner',
                    title: variant === 'error' ? 'FixThis needs attention' : '',
                    message,
                    primaryAction: assertive ? 'Open details' : null,
                    // The dedupeKey MUST stay 'global-error' because three existing
                    // call sites in this same file (`syncStatusVisibility`,
                    // `resetStatusSurface`, and the message-empty branch) call
                    // `statusSurfaceRegistry.hide('global-error')` to clear the
                    // singleton status line. Changing this key strands those hides.
                    dedupeKey: 'global-error',
                    ttlMs: durationMs,
                  });
```

Also migrate the three remaining `statusSurfaceRegistry.hide('global-error')`
call sites in `state.js` (currently at `syncStatusVisibility`,
`resetStatusSurface`, and the empty-message branch inside `showStatus`) to
`notificationCenter.hide('global-error')`. This keeps the `activeKeys` map and
the underlying registry in sync; otherwise the registry hides but the policy
layer still thinks the key is active and silently suppresses a later
`notify({ dedupeKey: 'global-error' })`.

Keep `error.textContent`, role, and class updates in place — the `error`
element is the live DOM target the registry mounts the global status into,
and downstream styling depends on its class.

Audit step: add an assertion to `scripts/notificationCenter-test.mjs` that the
state.js wiring path uses `dedupeKey: 'global-error'`:

```js
test('state.js global-status path keeps stable dedupeKey for hide coordination', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'error', surface: 'banner', message: 'boom', dedupeKey: 'global-error' });
  center.hide('global-error');
  const hide = registry.calls.find((call) => call.kind === 'hide');
  assert.equal(hide.id, 'global-error');
  // Re-notify after hide must succeed (not deduped).
  center.notify({ severity: 'error', surface: 'banner', message: 'boom again', dedupeKey: 'global-error' });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 2);
});
```

- [ ] **Step 5: Add notification group after all files exist**

In `scripts/console-tests.json`, add:

```json
  "notification": [
    "scripts/requestJson-test.mjs",
    "scripts/notificationCenter-test.mjs"
  ],
```

- [ ] **Step 6: Run tests**

Run:

```bash
node --test scripts/notificationCenter-test.mjs
node scripts/run-console-tests.mjs notification
```

Expected: pass.

- [ ] **Step 7: Rebuild and verify console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: bundle updates and both verification commands succeed.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/notificationCenter.js \
        scripts/notificationCenter-test.mjs \
        fixthis-mcp/src/main/console/state.js \
        scripts/console-tests.json \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): add notification policy layer"
```

## Task 6: Remove Native Confirms from First-Run Console Paths

**Files:**
- Modify: `fixthis-mcp/src/main/console/boundaryDialogVariants.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/devices.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Create: `scripts/nativeConfirmAudit-test.mjs`
- Modify: `scripts/boundaryDialogVariants-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing native confirm audit**

Create `scripts/nativeConfirmAudit-test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const files = [
  'fixthis-mcp/src/main/console/main.js',
  'fixthis-mcp/src/main/console/devices.js',
  'fixthis-mcp/src/main/console/history.js',
  'fixthis-mcp/src/main/console/annotations.js',
];

test('production first-run console paths do not call native confirm', () => {
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /window\.confirm|\.confirm\(/, `${file} still uses native confirm`);
  }
});
```

- [ ] **Step 2: Add boundary variant assertions**

In `scripts/boundaryDialogVariants-test.mjs`, add:

```js
test('destructive utility dialogs label confirm and cancel explicitly', () => {
  renderBoundaryDialog('clearLocalDraft', { itemCount: 2 });
  assert.match(document.title.textContent, /Clear local draft/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Clear local draft']);

  renderBoundaryDialog('clearServerDrafts', { itemCount: 3 });
  assert.match(document.summary.textContent, /3 saved draft/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Delete drafts']);

  renderBoundaryDialog('fingerprintMismatch', {});
  assert.deepEqual(visibleLabels(), ['Cancel', 'Force save', 'Recapture']);
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
node --test scripts/nativeConfirmAudit-test.mjs scripts/boundaryDialogVariants-test.mjs
```

Expected: native confirm audit fails and boundary variants are missing.

- [ ] **Step 4: Add boundary variants**

In `boundaryDialogVariants.js`, add entries to `BoundaryDialogVariants`:

```js
  clearLocalDraft: Object.freeze({
    title: () => 'Clear local draft?',
    summary: (ctx = {}) => {
      const count = ctx.itemCount ?? 1;
      return 'Removes ' + count + ' unsaved local ' + (count === 1 ? 'annotation' : 'annotations') + ' from this browser.';
    },
    primary: Object.freeze({ label: 'Clear local draft', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  clearServerDrafts: Object.freeze({
    title: () => 'Delete saved draft feedback?',
    summary: (ctx = {}) => {
      const count = ctx.itemCount ?? 0;
      return 'Deletes ' + count + ' saved draft ' + (count === 1 ? 'item' : 'items') + ' from this session.';
    },
    primary: Object.freeze({ label: 'Delete drafts', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  fingerprintMismatch: Object.freeze({
    title: () => 'Screen changed since capture?',
    summary: () => 'Keep the frozen preview only if the annotation still describes that frame.',
    primary: Object.freeze({ label: 'Recapture', action: 'recapture' }),
    secondary: Object.freeze({ label: 'Force save', action: 'force' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  recaptureRecoveredDraft: Object.freeze({
    title: () => 'Recapture recovered draft?',
    summary: () => 'Pins will be remapped onto the latest app screen.',
    primary: Object.freeze({ label: 'Recapture', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
```

- [ ] **Step 5: Add a reusable boundary prompt helper in main.js**

In `main.js`, add this helper near existing boundary prompt functions:

```js
            function promptBoundaryDialogChoice(variant, context = {}) {
              return new Promise((resolve) => {
                renderBoundaryDialog(variant, context);
                const root = document.getElementById('sessionBoundarySheet');
                if (!root) {
                  resolve('cancel');
                  return;
                }
                function onClick(event) {
                  const action = event.target?.dataset?.boundaryAction;
                  if (!action) return;
                  root.removeEventListener('click', onClick);
                  statusSurfaceRegistry.hide('sessionBoundarySheet');
                  resolve(action);
                }
                root.addEventListener('click', onClick);
              });
            }
```

Replace `promptPendingBoundaryChoice` with:

```js
            function promptPendingBoundaryChoice(action, count) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingBoundary === 'function') {
                return Promise.resolve(window.fixThisPromptPendingBoundary({ action, count }));
              }
              if (action === 'delete-session') {
                return promptBoundaryDialogChoice('sessionDelete', { annotationCount: count, screenCount: 0 })
                  .then((choice) => choice === 'discardAndProceed' || choice === 'saveAndProceed' ? 'discard' : 'cancel');
              }
              if (action === 'new-session') {
                return promptBoundaryDialogChoice('sessionCreate', { itemCount: count })
                  .then((choice) => choice === 'saveAndProceed' ? 'save' : choice === 'discardAndProceed' ? 'discard' : 'cancel');
              }
              return promptBoundaryDialogChoice('sessionSwitch', { itemCount: count })
                .then((choice) => choice === 'saveAndProceed' ? 'save' : choice === 'discardAndProceed' ? 'discard' : 'cancel');
            }
```

Replace `promptPendingRecoveryBoundaryChoice` with:

```js
            function promptPendingRecoveryBoundaryChoice(recovery, action) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingRecoveryBoundary === 'function') {
                return Promise.resolve(window.fixThisPromptPendingRecoveryBoundary({ recovery, action }));
              }
              return promptBoundaryDialogChoice('pendingRecovery', {
                canResume: hasRecoverablePreviewContext(recovery),
                itemCount: pendingRecoveryItems(recovery).length,
              }).then((choice) => choice === 'discard' ? 'clear' : 'cancel');
            }
```

In `recapturePendingRecovery`, replace the native confirm block with:

```js
              const accepted = await promptBoundaryDialogChoice('recaptureRecoveredDraft', {});
              if (accepted !== 'confirm') return;
```

- [ ] **Step 6: Replace device/history/annotation confirms**

In `devices.js`, replace the confirm block in `forgetDevice` with:

```js
              const accepted = await promptBoundaryDialogChoice('forgetDevice', { deviceName: deviceNameForPrompt });
              if (accepted !== 'confirm') return;
```

In `history.js`, replace the confirm block in `clearLocalDraft` with:

```js
              const accepted = await promptBoundaryDialogChoice('clearLocalDraft', {
                itemCount: draftWorkspaceItems(draftWorkspace).length || pendingRecoveryItems(pendingRecovery).length,
              });
              if (accepted !== 'confirm') return;
```

In `history.js`, replace the confirm block in `clearServerDrafts` with:

```js
              const accepted = await promptBoundaryDialogChoice('clearServerDrafts', {
                itemCount: savedEvidenceItems().filter((item) => item.delivery !== 'sent').length,
              });
              if (accepted !== 'confirm') return;
```

In `annotations.js`, replace `promptScreenFingerprintMismatch` with:

```js
            function promptScreenFingerprintMismatch(frozenFingerprint, currentFingerprint) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptFingerprintMismatch === 'function') {
                return Promise.resolve(window.fixThisPromptFingerprintMismatch({ frozenFingerprint: frozenFingerprint, currentFingerprint: currentFingerprint }));
              }
              return promptBoundaryDialogChoice('fingerprintMismatch', {
                frozenFingerprint,
                currentFingerprint,
              }).then((choice) => {
                if (choice === 'force') return 'force';
                if (choice === 'recapture') return 'recapture';
                return 'cancel';
              });
            }
```

- [ ] **Step 7: Add native confirm audit to notification group**

In `scripts/console-tests.json`, update the `notification` group to:

```json
  "notification": [
    "scripts/requestJson-test.mjs",
    "scripts/notificationCenter-test.mjs",
    "scripts/nativeConfirmAudit-test.mjs"
  ],
```

- [ ] **Step 8: Run focused tests**

Run:

```bash
node --test scripts/nativeConfirmAudit-test.mjs scripts/boundaryDialogVariants-test.mjs
node scripts/run-console-tests.mjs notification canonical pending session
```

Expected: pass.

- [ ] **Step 9: Rebuild and verify console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: bundle updates and both verification commands succeed.

- [ ] **Step 10: Commit**

```bash
git add fixthis-mcp/src/main/console/boundaryDialogVariants.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/devices.js \
        fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/console/annotations.js \
        scripts/nativeConfirmAudit-test.mjs \
        scripts/boundaryDialogVariants-test.mjs \
        scripts/console-tests.json \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map \
        fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): replace first-run confirms with in-app sheets"
```

## Task 7: First-Run Smoke Harness

**Files:**
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `scripts/console-fixture/scenarios-test.mjs`
- Create: `scripts/first-run-smoke.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing fixture scenario test**

In `scripts/console-fixture/scenarios-test.mjs`, add:

```js
test('first-run unsupported build scenario exposes readiness payload', async () => {
  const fixture = await startFakeBridge({ scenario: 'unsupported-build' });
  try {
    const status = await fetch(`${fixture.url}/api/connection`).then((response) => response.json());
    assert.equal(status.state, 'UNSUPPORTED_BUILD');
    assert.equal(status.readiness.state, 'UNSUPPORTED_BUILD');
    assert.match(status.readiness.nextAction, /debuggable build/);
  } finally {
    await fixture.close();
  }
});
```

- [ ] **Step 2: Write failing smoke script**

Create `scripts/first-run-smoke.mjs`:

```js
#!/usr/bin/env node
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

async function main() {
  const fixture = await startFakeBridge({ scenario: 'first-run-ready' });
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    await page.goto(fixture.url, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-testid="connection-card"][data-connection-state="ready"]', { timeout: 5000 });
    await page.click('#annotateToolButton');
    await page.mouse.move(120, 180);
    await page.mouse.down();
    await page.mouse.move(260, 300);
    await page.mouse.up();
    await page.fill('#comment', 'Make the primary button label clearer');
    await page.click('text=Add annotation');
    // The Save-to-MCP button must be enabled (it starts `disabled` in index.html)
    // and the prompt-readiness element must transition away from its initial
    // "No annotations ready" text before we trust the click landed a handoff.
    await page.waitForSelector('[data-testid="save-to-mcp-button"]:not([disabled])', { timeout: 5000 });
    await page.click('[data-testid="save-to-mcp-button"]');
    await page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="prompt-readiness"]');
      return el && !/no annotations ready/i.test(el.textContent || '');
    }, { timeout: 5000 });

    const handoffs = fixture.getRequestLog().filter((entry) => entry.path === '/api/agent-handoffs');
    assert.equal(handoffs.length, 1);
    assert.deepEqual(consoleErrors, []);
    console.log('First-run smoke passed.');
  } finally {
    await browser.close();
    await fixture.close();
  }
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
node --test scripts/console-fixture/scenarios-test.mjs
node scripts/first-run-smoke.mjs
```

Expected: fixture test fails because scenarios are missing; smoke fails before
ready state or annotation save.

- [ ] **Step 4: Add fixture scenarios and save endpoint support**

In `scripts/console-fixture/fakeBridgeServer.mjs`, add scenarios:

```js
  'first-run-ready': () => ({
    forceState: 'READY',
    readiness: {
      state: 'READY',
      cause: 'Debug app and FixThis sidekick are connected.',
      verify: 'Capture a screen from the feedback console.',
      fix: 'No recovery action required.',
      nextAction: 'Capture screen',
      details: {},
    },
  }),
  'unsupported-build': () => ({
    forceState: 'UNSUPPORTED_BUILD',
    readiness: {
      state: 'UNSUPPORTED_BUILD',
      cause: 'This build cannot expose the FixThis bridge.',
      verify: 'adb shell run-as <applicationId> ls files/fixthis/session.json',
      fix: 'Install a debuggable build with the FixThis sidekick enabled.',
      nextAction: 'Install a debuggable build with FixThis enabled.',
      details: { rawError: 'run-as: package not debuggable' },
    },
  }),
```

In the `/api/connection` response, add:

```js
        readiness: scenarioState.readiness || {
          state: 'READY',
          cause: 'Debug app and FixThis sidekick are connected.',
          verify: 'Capture a screen from the feedback console.',
          fix: 'No recovery action required.',
          nextAction: 'Capture screen',
          details: {},
        },
```

Add an `/api/items/batch` handler before `/api/agent-handoffs`:

```js
    if (url.pathname === '/api/items/batch' && req.method === 'POST') {
      const body = await readJson(req);
      const now = Date.now();
      const screen = {
        ...(body.screen || fakePreview(session.sessionId).screen),
        screenshot: { desktopFullPath: '/tmp/fixthis-first-run-smoke.png' },
      };
      if (!session.screens.some((candidate) => candidate.screenId === screen.screenId)) {
        session.screens.push(screen);
      }
      const items = Array.isArray(body.items) ? body.items : [];
      for (const draft of items) {
        session.items.push({
          itemId: `item-${session.items.length + 1}`,
          screenId: screen.screenId,
          createdAtEpochMillis: now,
          updatedAtEpochMillis: now,
          target: {
            type: draft.targetType || 'visual_area',
            boundsInWindow: draft.bounds || { left: 10, top: 10, right: 80, bottom: 80 },
          },
          label: draft.label || 'First-run annotation',
          severity: draft.severity || 'med',
          comment: draft.comment || 'First-run annotation',
          sequenceNumber: session.items.length + 1,
          delivery: 'draft',
          status: 'open',
        });
      }
      session.updatedAtEpochMillis = now;
      session.nextItemSequenceNumber = session.items.length + 1;
      json(res, session);
      return;
    }
```

- [ ] **Step 5: Add npm script**

In `package.json`, add:

```json
    "first-run:smoke": "node scripts/first-run-smoke.mjs",
```

- [ ] **Step 6: Run fixture and smoke tests**

Run:

```bash
node --test scripts/console-fixture/scenarios-test.mjs
npm run first-run:smoke
```

Expected: both pass and `First-run smoke passed.` prints.

- [ ] **Step 7: Commit**

```bash
git add scripts/console-fixture/fakeBridgeServer.mjs \
        scripts/console-fixture/scenarios-test.mjs \
        scripts/first-run-smoke.mjs \
        package.json
git commit -m "test(console): add first-run smoke harness"
```

## Task 8: Documentation, Release Notes, and Final Verification

**Files:**
- Modify: `docs/guides/troubleshooting.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `docs/superpowers/specs/2026-05-16-v03-first-run-trust-program-design.md`

- [ ] **Step 1: Update troubleshooting readiness section**

In `docs/guides/troubleshooting.md`, add this section after the starting
doctor command block:

```markdown
## First-Run Readiness States

FixThis first-run surfaces use one readiness vocabulary across `doctor --json`,
`.fixthis/agent-setup.json`, and the feedback console.

| State | What it means | First action |
| --- | --- | --- |
| `READY` | Debug app and sidekick are connected. | Capture screen. |
| `NEEDS_INSTALL` | FixThis metadata or setup is missing. | Run `fixthis install-agent --project-dir . --target all`. |
| `NEEDS_APP_LAUNCH` | Device is available but the app bridge is not reachable. | Open the debug app. |
| `DEVICE_BLOCKED` | Device or app is connected but not interactable. | Resolve the specific device overlay. |
| `UNSUPPORTED_BUILD` | Release/non-debug build, missing sidekick, or `run-as` denied. | Install a debuggable build with FixThis enabled. |
| `CONFIG_RECOVERABLE` | MCP or project config can be regenerated. | Run the setup command with `--dry-run`, then rerun without it. |
| `ENV_BLOCKER` | ADB, SDK, device, JDK, Node, or repo root is missing. | Fix the prerequisite and rerun doctor. |
| `STALE_PREVIEW` | Frozen preview no longer matches the live app screen. | Recapture, force-save, or cancel. |
| `SESSION_MISMATCH` | Response or artifact belongs to another feedback session. | Refresh the session or return to the matching history item. |
| `UNKNOWN_ERROR` | FixThis could not classify the failure. | Open details and run doctor with JSON output. |
```

- [ ] **Step 2: Update notification troubleshooting**

In `docs/guides/troubleshooting.md`, add this section near the browser console
sections:

```markdown
## Console Notifications

The console uses different surfaces for different recovery work:

- Toasts are for success, cancel, and undo messages that can disappear.
- Inline status belongs to the connection card, preview, inspector, or save controls.
- Global banners stay visible when the whole workflow is blocked.
- In-app sheets handle destructive choices such as discarding drafts, clearing saved drafts, forgetting a device, or force-saving a stale preview.
- Details panels hold raw errors, JSON, and diagnostic commands.

If a mutating request returns `403`, reload the console page served by the
current MCP process. The console token changes when the server restarts.
```

- [ ] **Step 3: Update release notes**

In `docs/releases/unreleased.md`, add under `## Highlights`:

```markdown
- v0.3 first-run trust work now gives `doctor`, `install-agent`,
  `.fixthis/agent-setup.*`, and the feedback console a shared readiness
  vocabulary, structured browser API errors, consistent notification surfaces,
  and a deterministic first-run console smoke path.
```

- [ ] **Step 4: Mark spec as planned**

In `docs/superpowers/specs/2026-05-16-v03-first-run-trust-program-design.md`,
change:

```markdown
Status: Approved design
```

to:

```markdown
Status: Planned for implementation
```

- [ ] **Step 5: Run final verification**

Run:

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node scripts/run-console-tests.mjs notification canonical pending session harness
npm run first-run:smoke
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: all commands pass. The `build-console-assets.mjs --check` and
`node --check` calls are required by `CONTRIBUTING.md` whenever the console
bundle changes and must be part of the final gate.

- [ ] **Step 6: Commit**

```bash
git add docs/guides/troubleshooting.md \
        docs/releases/unreleased.md \
        docs/superpowers/specs/2026-05-16-v03-first-run-trust-program-design.md
git commit -m "docs: document v0.3 first-run trust recovery"
```

## Edge Case Coverage Map

The table below distinguishes **classification coverage** (the readiness state
is plumbed end-to-end so a future surface change is one wiring step) from
**UX coverage** (the user actually sees the spec-promised guidance). Be
honest: not every cell promises a UX-complete fix in v0.3.

| Edge case group | Classification | UX surface | Covered by |
| --- | --- | --- | --- |
| repo root, app module, applicationId, config write, dry-run, stale setup files | ✅ | ✅ | Task 2 |
| adb missing, no device, unauthorized, offline, multiple devices, selected device missing | ✅ | ✅ | Tasks 2 and 3 |
| screen off, lockscreen, background, PiP, no Compose root | ✅ (existing) | ✅ (existing blocked overlay) | Task 3 plus existing blocked overlay tests |
| `run-as` denied, unknown package, sidekick missing, app not launched, bridge timeout, unsupported build | ✅ | ✅ | Tasks 1 and 3 |
| 403 token/origin error | ✅ (structured `action`) | ⚠️ partial (reload CTA not auto-rendered) | Task 4 (deferred: see below) |
| repeated SSE / polling failures | ✅ | ✅ (dedupe + ttl release) | Task 5 |
| clipboard failure | ✅ (api.js throws) | ⚠️ partial (manual-copy guidance copy not wired through NotificationCenter) | Task 4 (deferred: see below) |
| screenshot 404 / semantics-only capture | ❌ | ❌ | Deferred (see below) |
| corrupt or incomplete draft recovery, session switch, clear local draft, clear server drafts | ✅ | ✅ | Task 6 |
| selected node missing, invalid bounds, empty comment, fingerprint mismatch, duplicate save, mark-handed-off failure | ✅ | ✅ | Tasks 4, 5, and 6 |
| ready to preview to annotation to handoff | n/a | ✅ | Task 7 |

### Deferred to a follow-up plan

The following spec cases are intentionally **not** implemented in this plan
because the readiness contract is the prerequisite. They are listed here so
the table above is not a false guarantee, and so a follow-up plan can pick
them up with one wiring step each:

- **403 reload CTA**: when `requestJson` rejects with
  `ConsoleRequestError.action === 'reload_console'`, the caller should fire
  `notificationCenter.notify({ severity: 'error', surface: 'banner',
  primaryAction: 'Reload console', ... })`. Task 4 plumbs the field; no
  caller consumes it yet.
- **Clipboard fallback final-state message**: `copyTextToClipboard` throws
  with `"Copy failed. Select the prompt and copy it manually."`. The callers
  (`#copyPromptButton` handlers) still surface this through legacy
  `showError`. Migrate to a NotificationCenter call with explicit manual-copy
  detail copy.
- **Screenshot 404 / semantics-only capture**: needs both a server-side
  classification (no readiness state currently covers it without overloading
  `UNKNOWN_ERROR`) and a console copy choice. Defer to a dedicated capture-
  fallback plan.
- **Late SSE / cross-session response → SESSION_MISMATCH ignore path**: the
  state is defined and surfaces can render it, but the actual "ignore stale
  responses when possible" logic in SSE / preview-poll consumers is not
  modified by any task here. Defer.
- **Older save finishes after newer local edits**: the conflict policy is
  cross-cutting (server `If-Match`/etag plus client merge UX) and outside
  the v0.3 trust scope. Defer.

## Execution Notes

- Keep commits per task. Do not combine readiness contract work with console UI migration.
- Rebuild `fixthis-mcp/src/main/resources/console/app.js` whenever any file under `fixthis-mcp/src/main/console/` changes.
- Preserve the public symbols, file paths, test names, JSON field names, and command names shown in this plan.
- Do not change Android sidekick protocol for this plan.
