# Agent-First Setup Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-shot agent setup contract through `fixthis install-agent --project-dir . --target all --verify --json`.

**Architecture:** Keep `install-agent` as the single agent-first entry point and add `--verify` as an orchestration layer over existing setup and doctor logic. Extract doctor execution into a reusable service, add a small report/action model for merged setup verification, then wire JSON-only structured output without changing existing `install-agent --json` and `doctor --json` contracts.

**Tech Stack:** Kotlin/JVM CLI with Clikt, kotlinx.serialization JSON builders, Gradle `:fixthis-cli:test`, existing FixThis CLI command tests, Markdown docs.

## Global Constraints

- FixThis setup remains debug-only and Jetpack Compose-only.
- The CLI must not install or upgrade itself through Homebrew, npm, or curl.
- Do not support release builds.
- Do not add cloud setup, remote MCP hosting, or ChatGPT connector automation.
- Do not rename persisted MCP feedback fields.
- Do not change bridge protocol semantics.
- Existing `install-agent --json` consumers without `--verify` remain compatible.
- Existing `doctor --json` behavior remains compatible.
- `--dry-run --verify --json` never implies planned setup writes were actually verified.
- JSON mode emits one parseable stdout document before any non-zero exit whenever a report exists.

---

## File Structure

- Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorService.kt`: reusable doctor check execution, extracted from `DoctorCommand`.
- Modify `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`: delegate to `DoctorService` and keep existing rendering.
- Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt`: unified JSON report builder and action model.
- Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt`: merge setup report, optional doctor report, dry-run state, restart state, and structured actions.
- Modify `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`: add `--verify` to `InstallAgentCommand`, run doctor service only when `--verify` is set, `--dry-run` is false, and setup has no recorded errors, then render unified JSON when both `--json` and `--verify` are set.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`: add regression tests for service extraction.
- Create `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt`: unit tests for merge precedence and actions.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`: JSON shape tests for the verify report.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`: command-level dry-run verify JSON stdout test.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainCommandTest.kt`: assert help exposes `--verify`.
- Modify `README.md`, `docs/getting-started/connect-your-agent.md`, `docs/getting-started/add-to-your-app.md`, `docs/getting-started/agent-install-snippet.md`, and `docs/reference/cli.md`: make the one-shot agent contract the external-app setup path.

## Task 1: Extract Doctor Execution Into `DoctorService`

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorService.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`

**Interfaces:**
- Consumes: existing `DoctorReport`, `DoctorCheckResult`, `readinessForDoctorCheck(...)`, `Adb.forProject(root)`, and `BridgeClient`.
- Produces:
  - `internal class DoctorService(private val environment: DoctorEnvironment = RealDoctorEnvironment())`
  - `internal interface DoctorEnvironment`
  - `fun DoctorService.run(packageName: String?, projectRoot: File): DoctorReport`

- [ ] **Step 1: Write the failing service extraction test**

Append this test to `DoctorCommandTest`:

```kotlin
@Test
fun doctorServiceReturnsSameReportShapeAsDoctorJsonRenderer() {
    val service = DoctorService(
        environment = object : DoctorEnvironment {
            override fun requireAndroidProject(root: java.io.File) = Unit
            override fun resolvePackageName(root: java.io.File, packageName: String?) = "com.example.agent"
            override fun listDevices(root: java.io.File) = listOf(AdbDevice("emulator-5554", "device"))
            override fun requestStatus(root: java.io.File, packageName: String) = Unit
        },
    )

    val report = service.run(
        packageName = null,
        projectRoot = java.nio.file.Files.createTempDirectory("fixthis-doctor-service").toFile(),
    )
    val json = Json.parseToJsonElement(renderDoctorJsonReport(report)).jsonObject

    assertEquals("true", json.getValue("ok").jsonPrimitive.content)
    assertEquals("com.example.agent", json.getValue("packageName").jsonPrimitive.content)
    assertEquals("READY", json.getValue("readiness").jsonObject.getValue("state").jsonPrimitive.content)
    assertEquals(5, json.getValue("checks").jsonArray.size)
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*DoctorCommandTest.doctorServiceReturnsSameReportShapeAsDoctorJsonRenderer' --no-daemon
```

Expected: FAIL with unresolved references to `DoctorService` and `DoctorEnvironment`.

- [ ] **Step 3: Add `DoctorService.kt`**

Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorService.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.Adb
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.BridgeClient
import kotlinx.coroutines.runBlocking
import java.io.File

internal interface DoctorEnvironment {
    fun requireAndroidProject(root: File)
    fun resolvePackageName(root: File, packageName: String?): String
    fun listDevices(root: File): List<AdbDevice>
    fun requestStatus(root: File, packageName: String)
}

internal class RealDoctorEnvironment : DoctorEnvironment {
    override fun requireAndroidProject(root: File) {
        require(root.resolve("settings.gradle.kts").exists() || root.resolve("settings.gradle").exists()) {
            "settings.gradle(.kts) was not found"
        }
    }

    override fun resolvePackageName(root: File, packageName: String?): String =
        BridgeClient(adb = Adb.forProject(root), projectRoot = root).resolvePackageName(packageName)

    override fun listDevices(root: File): List<AdbDevice> = Adb.forProject(root).devices()

    override fun requestStatus(root: File, packageName: String) {
        val adb = Adb.forProject(root)
        val client = BridgeClient(adb = adb, projectRoot = root)
        runBlocking {
            client.request(packageName, "status")
        }
    }
}

internal class DoctorService(
    private val environment: DoctorEnvironment = RealDoctorEnvironment(),
) {
    fun run(packageName: String?, projectRoot: File): DoctorReport {
        val root = projectRoot.canonicalFile
        var resolvedPackage: String? = null
        val checks = mutableListOf<DoctorCheckResult>()

        fun check(name: String, label: String, fix: String, block: () -> Unit) {
            try {
                block()
                checks += DoctorCheckResult(name = name, label = label, ok = true)
            } catch (error: Throwable) {
                checks += DoctorCheckResult(
                    name = name,
                    label = label,
                    ok = false,
                    message = error.message,
                    fix = fix,
                    readiness = readinessForDoctorCheck(name, error.message, fix),
                )
            }
        }

        check(
            name = "android_project_found",
            label = "Android project found",
            fix = "Run from an Android repository root or pass --project-dir.",
        ) {
            environment.requireAndroidProject(root)
        }
        check(
            name = "fixthis_project_metadata_found",
            label = "FixThis project metadata found",
            fix = "Run `./gradlew fixthisSetup` or pass --package <applicationId>.",
        ) {
            resolvedPackage = environment.resolvePackageName(root, packageName)
        }
        check(
            name = "adb_found",
            label = "ADB found",
            fix = "Install Android SDK platform-tools or set ANDROID_HOME.",
        ) {
            environment.listDevices(root)
        }
        check(
            name = "device_connected",
            label = "device connected",
            fix = "Start an emulator or connect a device, then run `adb devices`.",
        ) {
            require(hasConnectedAndroidDevice(environment.listDevices(root))) {
                "No connected Android device or emulator found"
            }
        }
        check(
            name = "sidekick_session_found",
            label = "sidekick session found",
            fix = "Build and run the debug app with FixThis sidekick installed.",
        ) {
            val pkg = resolvedPackage ?: environment.resolvePackageName(root, packageName)
            environment.requestStatus(root, pkg)
        }

        return DoctorReport(packageName = resolvedPackage, checks = checks)
    }
}
```

- [ ] **Step 4: Make `DoctorCommand` delegate to the service**

Replace the body of `DoctorCommand.run()` in `DoctorCommand.kt` with:

```kotlin
override fun run() {
    val root = File(projectDir).canonicalFile
    val report = DoctorService().run(packageName = packageName, projectRoot = root)

    if (jsonOutput) {
        echo(renderDoctorJsonReport(report))
    } else {
        report.checks.forEach { check ->
            if (check.ok) {
                echo("OK   ${check.label}")
            } else {
                echo("FAIL ${check.label}: ${check.message}")
                echo("  ↳ fix: ${check.fix}")
            }
        }
    }

    if (!report.ok) {
        throw CliktError("${report.checks.count { !it.ok }} doctor check(s) failed")
    }
}
```

Remove now-unused `Adb`, `BridgeClient`, and `runBlocking` imports from `DoctorCommand.kt`.

- [ ] **Step 5: Run doctor tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*DoctorCommandTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit task 1**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorService.kt \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt
git commit -m "refactor(cli): extract doctor service"
```

## Task 2: Add Unified Verify Report JSON Model

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`

**Interfaces:**
- Consumes: `InstallAgentJsonReport.Applied`, `InstallAgentJsonReport.Skipped`, `InstallAgentJsonReport.ErrorEntry`, `DoctorReport`, `FirstRunReadiness`.
- Produces:
  - `internal data class AgentSetupAction(...)`
  - `internal data class AgentSetupVerificationReport(...)`
  - `internal object AgentSetupVerificationJsonReport { fun render(report: AgentSetupVerificationReport): String }`

- [ ] **Step 1: Write the failing JSON shape test**

Append this test to `InstallAgentJsonReportTest`:

```kotlin
@Test
fun verifyReportCarriesSetupVerificationActionsAndMcpReadiness() {
    val readiness = FirstRunReadinessCatalog.deviceBlocked(
        cause = "No connected Android device or emulator found",
        fix = "Start an emulator or connect a device, then run `adb devices`.",
    )
    val rendered = AgentSetupVerificationJsonReport.render(
        AgentSetupVerificationReport(
            ok = false,
            readiness = readiness,
            readinessSource = "verification",
            next = listOf("Start an emulator or connect a device, then run `adb devices`."),
            restartRequired = true,
            requiresUserAction = true,
            userActionReason = "DEVICE_BLOCKED",
            readyForMcpTooling = false,
            actions = listOf(
                AgentSetupAction(
                    id = "start-device",
                    actor = "user",
                    kind = "manual",
                    reason = "A connected unlocked Android device or emulator is required.",
                    blocksProgress = true,
                ),
            ),
            setup = AgentSetupSnapshot(
                applied = listOf(InstallAgentJsonReport.Applied("claude", "/repo/.claude/settings.json", "project-local")),
                skipped = emptyList(),
                errors = emptyList(),
                mcpConfigChanged = true,
            ),
            verification = AgentVerificationSnapshot(
                ok = false,
                packageName = "com.example.app",
                checks = emptyList(),
            ),
        ),
    )

    val obj = Json.parseToJsonElement(rendered).jsonObject
    assertEquals("1.1", obj.getValue("schemaVersion").jsonPrimitive.content)
    assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
    assertEquals("verification", obj.getValue("readinessSource").jsonPrimitive.content)
    assertEquals("false", obj.getValue("readyForMcpTooling").jsonPrimitive.content)
    assertEquals("true", obj.getValue("setup").jsonObject.getValue("mcpConfigChanged").jsonPrimitive.content)
    assertEquals("start-device", obj.getValue("actions").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals("com.example.app", obj.getValue("verification").jsonObject.getValue("packageName").jsonPrimitive.content)
}
```

- [ ] **Step 2: Run the failing JSON shape test**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*InstallAgentJsonReportTest.verifyReportCarriesSetupVerificationActionsAndMcpReadiness' --no-daemon
```

Expected: FAIL with unresolved references to the new report classes.

- [ ] **Step 3: Add `AgentSetupVerificationReport.kt`**

Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val agentSetupJson = Json {
    prettyPrint = false
    explicitNulls = false
    encodeDefaults = true
}

internal data class AgentSetupAction(
    val id: String,
    val actor: String,
    val kind: String,
    val reason: String,
    val blocksProgress: Boolean,
    val command: String? = null,
    val tool: String? = null,
)

internal data class AgentSetupSnapshot(
    val applied: List<InstallAgentJsonReport.Applied>,
    val skipped: List<InstallAgentJsonReport.Skipped>,
    val errors: List<InstallAgentJsonReport.ErrorEntry>,
    val mcpConfigChanged: Boolean,
)

internal data class AgentVerificationSnapshot(
    val ok: Boolean,
    val packageName: String?,
    val checks: List<DoctorCheckResult>,
    val skippedReason: String? = null,
)

internal data class AgentSetupVerificationReport(
    val ok: Boolean,
    val readiness: FirstRunReadiness,
    val readinessSource: String,
    val next: List<String>,
    val restartRequired: Boolean,
    val requiresUserAction: Boolean,
    val userActionReason: String?,
    val readyForMcpTooling: Boolean,
    val actions: List<AgentSetupAction>,
    val setup: AgentSetupSnapshot,
    val verification: AgentVerificationSnapshot,
)

internal object AgentSetupVerificationJsonReport {
    fun render(report: AgentSetupVerificationReport): String {
        val preferredNextAction = report.readiness.nextAction.ifBlank { report.next.firstOrNull().orEmpty() }
        return agentSetupJson.encodeToString(
            buildJsonObject {
                put("schemaVersion", "1.1")
                put("ok", report.ok)
                put(
                    "readiness",
                    agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), report.readiness).jsonObject,
                )
                put("readinessSource", report.readinessSource)
                if (preferredNextAction.isNotBlank()) put("nextAction", preferredNextAction)
                put("next", buildJsonArray { report.next.forEach { add(it) } })
                put("restartRequired", report.restartRequired)
                put("requiresUserAction", report.requiresUserAction)
                report.userActionReason?.let { put("userActionReason", it) }
                put("readyForMcpTooling", report.readyForMcpTooling)
                put(
                    "actions",
                    buildJsonArray {
                        report.actions.forEach { action ->
                            add(
                                buildJsonObject {
                                    put("id", action.id)
                                    put("actor", action.actor)
                                    put("kind", action.kind)
                                    put("reason", action.reason)
                                    put("blocksProgress", action.blocksProgress)
                                    action.command?.let { put("command", it) }
                                    action.tool?.let { put("tool", it) }
                                },
                            )
                        }
                    },
                )
                put(
                    "setup",
                    buildJsonObject {
                        put("mcpConfigChanged", report.setup.mcpConfigChanged)
                        put("applied", renderApplied(report.setup.applied))
                        put("skipped", renderSkipped(report.setup.skipped))
                        put("errors", renderErrors(report.setup.errors))
                    },
                )
                put(
                    "verification",
                    buildJsonObject {
                        put("ok", report.verification.ok)
                        report.verification.packageName?.let { put("packageName", it) }
                        report.verification.skippedReason?.let { put("skippedReason", it) }
                        put("checks", renderDoctorChecks(report.verification.checks))
                    },
                )
            },
        ) + "\n"
    }

    private fun renderApplied(applied: List<InstallAgentJsonReport.Applied>) = buildJsonArray {
        applied.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("path", entry.path)
                    put("scope", entry.scope)
                },
            )
        }
    }

    private fun renderSkipped(skipped: List<InstallAgentJsonReport.Skipped>) = buildJsonArray {
        skipped.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("reason", entry.reason)
                    put("fix", entry.fix)
                    entry.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }

    private fun renderErrors(errors: List<InstallAgentJsonReport.ErrorEntry>) = buildJsonArray {
        errors.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("message", entry.message)
                    entry.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }

    private fun renderDoctorChecks(checks: List<DoctorCheckResult>) = buildJsonArray {
        checks.forEach { check ->
            add(
                buildJsonObject {
                    put("name", check.name)
                    put("label", check.label)
                    put("status", if (check.ok) "ok" else "fail")
                    check.message?.let { put("message", it) }
                    check.fix?.let { put("fix", it) }
                    check.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }
}
```

- [ ] **Step 4: Run JSON report tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*InstallAgentJsonReportTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit task 2**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt
git commit -m "feat(cli): add agent setup verification report"
```

## Task 3: Implement Readiness Merge And Structured Actions

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt`

**Interfaces:**
- Consumes: `AgentSetupSnapshot`, `DoctorReport`, `FirstRunReadinessCatalog`, `FirstRunReadinessState`.
- Produces:
  - `internal data class AgentSetupVerificationRequest(...)`
  - `internal class AgentSetupVerificationService`
  - `fun buildReport(request: AgentSetupVerificationRequest): AgentSetupVerificationReport`
  - `fun exitCodeFor(report: AgentSetupVerificationReport): ExitCode`

- [ ] **Step 1: Write failing service tests**

Create `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentSetupVerificationServiceTest {
    private val root = File("/repo").absoluteFile

    @Test
    fun readyDoctorWithNoRestartIsReadyForMcpTooling() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "claude",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )

        assertTrue(report.ok)
        assertEquals("READY", report.readiness.state.name)
        assertTrue(report.readyForMcpTooling)
        assertEquals("fixthis_open_feedback_console", report.actions.single().tool)
        assertEquals(ExitCode.OK, AgentSetupVerificationService().exitCodeFor(report))
    }

    @Test
    fun readyDoctorWithMcpConfigChangeBlocksUntilRestart() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(
                    applied = listOf(InstallAgentJsonReport.Applied("claude", "/repo/.claude/settings.json", "project-local")),
                    skipped = emptyList(),
                    errors = emptyList(),
                    mcpConfigChanged = true,
                ),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )

        assertFalse(report.ok)
        assertEquals("READY", report.readiness.state.name)
        assertFalse(report.readyForMcpTooling)
        assertTrue(report.requiresUserAction)
        assertEquals("restart_mcp_client", report.userActionReason)
        assertEquals("restart-agent", report.actions.first().id)
        assertEquals("user", report.actions.first().actor)
        assertEquals("fixthis_open_feedback_console", report.actions.last().tool)
        assertEquals(ExitCode.PARTIAL, AgentSetupVerificationService().exitCodeFor(report))
    }

    @Test
    fun deviceBlockedDoctorCreatesUserActionAndRerunVerifyAction() {
        val doctor = DoctorReport(
            packageName = "com.example",
            checks = listOf(
                DoctorCheckResult(
                    name = "device_connected",
                    label = "device connected",
                    ok = false,
                    message = "No connected Android device or emulator found",
                    fix = "Start an emulator or connect a device, then run `adb devices`.",
                    readiness = FirstRunReadinessCatalog.deviceBlocked(
                        cause = "No connected Android device or emulator found",
                        fix = "Start an emulator or connect a device, then run `adb devices`.",
                    ),
                ),
            ),
        )

        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = doctor,
                dryRun = false,
            ),
        )

        assertEquals("DEVICE_BLOCKED", report.readiness.state.name)
        assertTrue(report.requiresUserAction)
        assertEquals("DEVICE_BLOCKED", report.userActionReason)
        assertEquals(listOf("start-device", "rerun-verify"), report.actions.map { it.id })
    }

    @Test
    fun dryRunVerifySkipsDoctorAndDoesNotClaimRuntimeReadiness() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "claude",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = null,
                dryRun = true,
            ),
        )

        assertFalse(report.ok)
        assertEquals("CONFIG_RECOVERABLE", report.readiness.state.name)
        assertEquals("dry_run_no_side_effects", report.verification.skippedReason)
        assertFalse(report.readyForMcpTooling)
    }
}
```

- [ ] **Step 2: Run the focused service tests and confirm they fail**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentSetupVerificationServiceTest' --no-daemon
```

Expected: FAIL with unresolved references to `AgentSetupVerificationService` and `AgentSetupVerificationRequest`.

- [ ] **Step 3: Add `AgentSetupVerificationService.kt`**

Create `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt`:

```kotlin
package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import java.io.File

internal data class AgentSetupVerificationRequest(
    val projectRoot: File,
    val target: String,
    val setup: AgentSetupSnapshot,
    val doctorReport: DoctorReport?,
    val dryRun: Boolean,
)

internal class AgentSetupVerificationService {
    fun buildReport(request: AgentSetupVerificationRequest): AgentSetupVerificationReport {
        val root = request.projectRoot.absoluteFile
        val readiness = selectReadiness(request, root)
        val readinessSource = readinessSource(request)
        val restartRequired = request.setup.mcpConfigChanged
        val readyForMcpTooling = readiness.state == FirstRunReadinessState.READY && !restartRequired
        val actions = actionsFor(request, readiness, restartRequired, readyForMcpTooling, root)
        val requiresUserAction = actions.any { it.actor == "user" && it.blocksProgress }
        val ok = readiness.state == FirstRunReadinessState.READY && readyForMcpTooling && request.setup.skipped.isEmpty() && request.setup.errors.isEmpty()

        return AgentSetupVerificationReport(
            ok = ok,
            readiness = readiness,
            readinessSource = readinessSource,
            next = actions.mapNotNull { it.command ?: it.tool ?: it.reason },
            restartRequired = restartRequired,
            requiresUserAction = requiresUserAction,
            userActionReason = actions.firstOrNull { it.actor == "user" && it.blocksProgress }?.let { action ->
                if (action.id == "restart-agent") "restart_mcp_client" else readiness.state.name
            },
            readyForMcpTooling = readyForMcpTooling,
            actions = actions,
            setup = request.setup,
            verification = verificationSnapshot(request),
        )
    }

    fun exitCodeFor(report: AgentSetupVerificationReport): ExitCode = if (report.ok) {
        ExitCode.OK
    } else if (report.setup.errors.isNotEmpty()) {
        ExitCode.INTERNAL_ERROR
    } else {
        ExitCode.PARTIAL
    }

    private fun selectReadiness(request: AgentSetupVerificationRequest, root: File): FirstRunReadiness {
        request.setup.errors.firstOrNull()?.readiness?.let { return it }
        if (request.dryRun) {
            return FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup was previewed with --dry-run; verification was not executed.",
            ).copy(
                verify = "fixthis install-agent --project-dir ${root.absolutePath} --target ${request.target} --verify --json",
                fix = "Rerun without --dry-run to apply setup, then verify.",
                nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target ${request.target} --verify --json",
            )
        }
        val doctorReadiness = request.doctorReport?.readiness
        if (doctorReadiness != null && doctorReadiness.state != FirstRunReadinessState.READY) {
            return doctorReadiness
        }
        request.setup.skipped.firstOrNull()?.readiness?.let { return it }
        return doctorReadiness ?: FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis setup completed; run verification before opening the console.",
        ).copy(
            verify = "fixthis doctor --project-dir ${root.absolutePath} --json",
            fix = "Run doctor, restart the MCP client if config changed, then open FixThis Studio.",
            nextAction = "fixthis doctor --project-dir ${root.absolutePath} --json",
        )
    }

    private fun readinessSource(request: AgentSetupVerificationRequest): String = when {
        request.setup.errors.isNotEmpty() || request.setup.skipped.isNotEmpty() || request.dryRun -> "setup"
        request.doctorReport != null -> "verification"
        else -> "merged"
    }

    private fun verificationSnapshot(request: AgentSetupVerificationRequest): AgentVerificationSnapshot =
        if (request.dryRun) {
            AgentVerificationSnapshot(ok = false, packageName = null, checks = emptyList(), skippedReason = "dry_run_no_side_effects")
        } else {
            AgentVerificationSnapshot(
                ok = request.doctorReport?.ok ?: false,
                packageName = request.doctorReport?.packageName,
                checks = request.doctorReport?.checks ?: emptyList(),
            )
        }

    private fun actionsFor(
        request: AgentSetupVerificationRequest,
        readiness: FirstRunReadiness,
        restartRequired: Boolean,
        readyForMcpTooling: Boolean,
        root: File,
    ): List<AgentSetupAction> = buildList {
        if (restartRequired) {
            add(
                AgentSetupAction(
                    id = "restart-agent",
                    actor = "user",
                    kind = "manual",
                    reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
                    blocksProgress = true,
                ),
            )
        }
        when (readiness.state) {
            FirstRunReadinessState.READY -> {
                add(
                    AgentSetupAction(
                        id = "open-feedback-console",
                        actor = if (readyForMcpTooling) "agent" else "agent_after_restart",
                        kind = "mcp_tool",
                        tool = "fixthis_open_feedback_console",
                        reason = "Open FixThis Studio after setup verification succeeds.",
                        blocksProgress = false,
                    ),
                )
            }
            FirstRunReadinessState.DEVICE_BLOCKED -> {
                add(
                    AgentSetupAction(
                        id = "start-device",
                        actor = "user",
                        kind = "manual",
                        reason = readiness.fix,
                        blocksProgress = true,
                    ),
                )
                add(rerunVerify(root, request.target))
            }
            FirstRunReadinessState.CONFIG_RECOVERABLE,
            FirstRunReadinessState.NEEDS_INSTALL,
            FirstRunReadinessState.NEEDS_APP_LAUNCH,
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            FirstRunReadinessState.ENV_BLOCKER,
            FirstRunReadinessState.STALE_PREVIEW,
            FirstRunReadinessState.SESSION_MISMATCH,
            FirstRunReadinessState.CAPTURE_UNAVAILABLE,
            FirstRunReadinessState.UNKNOWN_ERROR -> {
                add(
                    AgentSetupAction(
                        id = "recover-setup",
                        actor = if (readiness.state == FirstRunReadinessState.DEVICE_BLOCKED) "user" else "agent",
                        kind = "command",
                        command = readiness.nextAction,
                        reason = readiness.cause,
                        blocksProgress = true,
                    ),
                )
            }
        }
    }

    private fun rerunVerify(root: File, target: String) = AgentSetupAction(
        id = "rerun-verify",
        actor = "agent",
        kind = "command",
        command = "fixthis install-agent --project-dir ${root.absolutePath} --target $target --verify --json",
        reason = "Verify setup after the blocking condition is resolved.",
        blocksProgress = false,
    )
}
```

- [ ] **Step 4: Run service tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentSetupVerificationServiceTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit task 3**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt
git commit -m "feat(cli): derive setup verification actions"
```

## Task 4: Wire `install-agent --verify --json`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainCommandTest.kt`

**Interfaces:**
- Consumes: `DoctorService`, `AgentSetupVerificationService`, `AgentSetupVerificationJsonReport`.
- Produces:
  - `InstallAgentCommand` flag `--verify`.
  - JSON verify output with `schemaVersion: "1.1"`.
  - Non-zero partial exit when the unified report has blocking actions.

- [ ] **Step 1: Write failing command-level dry-run verify test**

Append this test to `InitAgentCommandTest`:

```kotlin
@Test
fun installAgentDryRunVerifyJsonPrintsUnifiedReportOnly() {
    val tempProject = androidProject("com.example.verify.dryrun")
    val out = ByteArrayOutputStream()
    val oldOut = System.out
    System.setOut(PrintStream(out))
    try {
        withUserHome(temporaryFolder.newFolder("home")) {
            InstallAgentCommand().parse(
                arrayOf(
                    "--project-dir",
                    tempProject.absolutePath,
                    "--package",
                    "com.example.verify.dryrun",
                    "--target",
                    "claude",
                    "--json",
                    "--dry-run",
                    "--verify",
                ),
            )
        }
    } catch (error: CliktError) {
        assertEquals(ExitCode.PARTIAL.value, error.statusCode)
    } finally {
        System.setOut(oldOut)
    }

    val text = out.toString()
    val obj = Json.parseToJsonElement(text.trim()).jsonObject
    assertEquals("1.1", obj.getValue("schemaVersion").jsonPrimitive.content)
    assertEquals("dry_run_no_side_effects", obj.getValue("verification").jsonObject.getValue("skippedReason").jsonPrimitive.content)
    assertFalse("human dry-run output must not be mixed into JSON stdout:\n$text", text.contains("Target:"))
}
```

- [ ] **Step 2: Extend root help test for the new flag**

In `MainCommandTest.rootHelpPrintsFixThisUsage`, after the existing `init` assertion, add a focused subprocess for `install-agent --help`:

```kotlin
val installHelp = ProcessBuilder(
    javaExecutable,
    "-cp",
    System.getProperty("java.class.path"),
    "io.github.beyondwin.fixthis.cli.MainKt",
    "install-agent",
    "--help",
)
    .redirectErrorStream(false)
    .start()
installHelp.waitFor(10, TimeUnit.SECONDS)
val installStdout = installHelp.inputStream.bufferedReader().readText()
assertEquals(0, installHelp.exitValue())
assertTrue(installStdout, installStdout.contains("--verify"))
```

- [ ] **Step 3: Run focused tests and confirm they fail**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest.installAgentDryRunVerifyJsonPrintsUnifiedReportOnly' --tests '*MainCommandTest.rootHelpPrintsFixThisUsage' --no-daemon
```

Expected: FAIL because `--verify` is not a recognized option.

- [ ] **Step 4: Add `--verify` to `InstallAgentCommand`**

In `SetupCommand.kt`, add this property next to `json` inside `InstallAgentCommand`:

```kotlin
private val verify by option(
    "--verify",
    help = "After setup, run doctor checks and emit a unified agent setup report in --json mode",
).flag(default = false)
```

- [ ] **Step 5: Build the unified setup snapshot and render path**

In `InstallAgentCommand.run()`, after `SetupService(...).installAgent(...)` returns and before rendering JSON, add:

```kotlin
val setupSnapshot = AgentSetupSnapshot(
    applied = report.applied,
    skipped = report.skipped,
    errors = report.errors,
    mcpConfigChanged = report.applied.any { it.target == "claude" || it.target == "codex" || it.target == "cursor" },
)
val doctorReport = if (verify && !dryRun && report.errors.isEmpty()) {
    DoctorService().run(packageName = packageName, projectRoot = root)
} else {
    null
}
val verifyReport = if (verify) {
    AgentSetupVerificationService().buildReport(
        AgentSetupVerificationRequest(
            projectRoot = root,
            target = effectiveTarget,
            setup = setupSnapshot,
            doctorReport = doctorReport,
            dryRun = dryRun,
        ),
    )
} else {
    null
}
```

Then change the `if (json)` rendering branch to:

```kotlin
if (json) {
    if (verifyReport != null) {
        echo(AgentSetupVerificationJsonReport.render(verifyReport))
    } else {
        val skippedAll = report.skipped
        val applied = report.applied
        val errors = report.errors
        val reportReadiness = installAgentTopLevelReadiness(root, skippedAll, errors)
        val restartRequired = applied.any { it.target == "claude" || it.target == "codex" }
        echo(
            InstallAgentJsonReport.render(
                applied = applied,
                skipped = skippedAll,
                errors = errors,
                next = listOf(
                    "fixthis doctor --project-dir ${root.absolutePath} --json",
                    "# Restart Claude Code / Codex to reload MCP config",
                    "fixthis_open_feedback_console",
                ),
                readiness = reportReadiness,
                restartRequired = restartRequired,
            ),
        )
    }
}
```

Finally replace the skipped/error exit handling with:

```kotlin
verifyReport?.let { unified ->
    val exitCode = AgentSetupVerificationService().exitCodeFor(unified)
    if (exitCode != ExitCode.OK) {
        throw CliktError("install-agent verification requires follow-up", statusCode = exitCode.value)
    }
    return
}
```

Keep the existing `hasErrors` and `hasSkipped` branches after that block so non-verify behavior remains compatible.

- [ ] **Step 6: Run focused command tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*InitAgentCommandTest.installAgentDryRunVerifyJsonPrintsUnifiedReportOnly' --tests '*MainCommandTest.rootHelpPrintsFixThisUsage' --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Run all CLI command tests**

Run:

```bash
./gradlew :fixthis-cli:test --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit task 4**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/MainCommandTest.kt
git commit -m "feat(cli): verify install-agent setup"
```

## Task 5: Update Agent-First Documentation And Final Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/getting-started/connect-your-agent.md`
- Modify: `docs/getting-started/add-to-your-app.md`
- Modify: `docs/getting-started/agent-install-snippet.md`
- Modify: `docs/reference/cli.md`

**Interfaces:**
- Consumes: final CLI command `fixthis install-agent --project-dir . --target all --verify --json`.
- Produces: docs that identify `--verify --json` as the agent-first external-app setup contract and explain `actions[]`, `requiresUserAction`, `readyForMcpTooling`, and `verification.skippedReason`.

- [ ] **Step 1: Update docs with the new one-shot command**

Replace external-app agent setup snippets that currently show:

```bash
fixthis install-agent --project-dir . --target all
fixthis doctor --project-dir . --json
```

with:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

Apply that replacement in `README.md`, `docs/getting-started/connect-your-agent.md`, `docs/getting-started/add-to-your-app.md`, and `docs/getting-started/agent-install-snippet.md`.

- [ ] **Step 2: Add CLI reference text for `--verify`**

In `docs/reference/cli.md`, under `fixthis install-agent`, add this flag row:

```markdown
| `--verify` | off | After setup, run doctor checks and emit one unified agent setup report in `--json` mode. With `--dry-run`, verification is skipped and reported as `verification.skippedReason=dry_run_no_side_effects`. |
```

Then add this paragraph after the existing JSON description:

```markdown
With `--verify --json`, the report uses `schemaVersion: "1.1"` and keeps the
existing setup arrays under `setup.applied`, `setup.skipped`, and
`setup.errors`. It also includes `verification`, `actions[]`,
`readinessSource`, `requiresUserAction`, and `readyForMcpTooling`. Agents
should treat `readiness.state` as the app readiness source of truth and
`actions[]` as the execution queue. Do not call `fixthis_open_feedback_console`
until `readyForMcpTooling` is true, or until the report's
`agent_after_restart` action is reached after restarting the MCP client.
```

- [ ] **Step 3: Run documentation consistency checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: both commands exit 0.

- [ ] **Step 4: Run the full CLI test suite**

Run:

```bash
./gradlew :fixthis-cli:test --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command exits 0. Dirty ignored `graphify-out/` files are acceptable and must not be committed.

- [ ] **Step 6: Check final git status**

Run:

```bash
git status --short
```

Expected: only intended tracked source, test, and docs changes are listed. `.fixthis/`, `graphify-out/`, Android build output, and local runtime evidence are not staged.

- [ ] **Step 7: Commit docs and final integration**

```bash
git add README.md \
  docs/getting-started/connect-your-agent.md \
  docs/getting-started/add-to-your-app.md \
  docs/getting-started/agent-install-snippet.md \
  docs/reference/cli.md
git commit -m "docs: document agent setup verification"
```

## Final Verification

After all tasks land, run:

```bash
./gradlew :fixthis-cli:test --no-daemon
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
git status --short
```

Expected:

- CLI tests pass.
- Documentation consistency passes.
- No whitespace errors.
- Graphify update exits 0.
- Git status contains no unintended tracked changes.

## Self-Review Notes

- Spec coverage: the plan covers `--verify --json`, doctor extraction, dry-run skip semantics, readiness merge, structured actions, MCP restart blocking, exit codes, docs, and verification commands.
- Placeholder scan: no task relies on unspecified file names, method names, or commands.
- Type consistency: task interfaces use `DoctorService`, `DoctorEnvironment`, `AgentSetupVerificationRequest`, `AgentSetupVerificationReport`, `AgentSetupAction`, `AgentSetupSnapshot`, and `AgentVerificationSnapshot` consistently across tests and implementation snippets.
