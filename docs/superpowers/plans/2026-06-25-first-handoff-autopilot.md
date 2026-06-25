# First Handoff Autopilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `fixthis install-agent --verify --json` a strict action contract that leads safely to one MCP-readable sent feedback item.

**Architecture:** Keep `AgentSetupVerificationService` as the single readiness/action decision point. Add a small validation contract around action actor/kind values, prove the contract in CLI unit tests, connect the same semantics to agent-loop smoke and release evidence, then document setup handoff files separately from verify stdout reports.

**Tech Stack:** Kotlin/JVM CLI with kotlinx.serialization JSON, JUnit tests, Node.js `node:test` smoke/report scripts, Markdown reference docs, existing Gradle and npm verification commands.

## Global Constraints

- FixThis remains debug-only and Jetpack Compose-only.
- Do not add automatic Homebrew, npm, or curl installation from inside the CLI.
- Do not support release builds.
- Do not add cloud MCP hosting or remote storage.
- Do not add ChatGPT connector automation.
- Do not rename persisted feedback JSON fields.
- Do not change bridge protocol semantics unless required by the first handoff contract.
- `actions[]` is the source of truth for agents; `next[]` and `nextAction` stay for compatibility.
- The current agent must not call `fixthis_open_feedback_console` unless `readyForMcpTooling=true`.
- `--dry-run --verify --json` must never claim runtime readiness.
- Strict connected proof must fail deferred Android evidence in strict mode.

---

## File Structure

- Modify `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt`
  - Own the closed `actions[]` contract and JSON rendering validation.
- Modify `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt`
  - Keep all action construction in one place and use contract constants.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt`
  - Prove action ordering, allowed actor/kind values, restart gating, dry-run gating, and unsupported-build blocking.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
  - Pin schema `1.1` JSON shape for command/tool/manual actions.
- Modify `scripts/agent-loop-smoke.mjs`
  - Add reusable verify-report autopilot assertions and include the result in first-handoff reports.
- Modify `scripts/agent-loop-smoke-test.mjs`
  - Test verify-report action semantics independent of Android runtime.
- Modify `scripts/external-fixture-matrix.mjs`
  - Make external fixture setup evidence use `install-agent --verify --json`.
- Modify `scripts/external-fixture-matrix-test.mjs`
  - Pin the external matrix command surface.
- Modify `docs/reference/agent-setup-schema.md`
  - Split setup handoff file schema `1.0` from verify stdout report schema `1.1`.
- Modify `README.md`, `docs/getting-started/agent-install-snippet.md`, and `docs/reference/cli.md`
  - Keep the agent-facing command and gating rules aligned.
- Modify `scripts/agent-bootstrap-contract-test.mjs`
  - Require `--verify --json`, `readyForMcpTooling`, and `actions[]` in agent-facing docs.
- Modify `docs/contributing/release-readiness.md` and `scripts/check-release-readiness.mjs`
  - Require first-handoff autopilot contract evidence in release readiness.

---

### Task 1: Harden The CLI Action Contract

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`

**Interfaces:**
- Consumes: `AgentSetupVerificationService.buildReport(request: AgentSetupVerificationRequest): AgentSetupVerificationReport`
- Produces:
  - `internal object AgentSetupActionContract`
  - `AgentSetupActionContract.AGENT`, `USER`, `AGENT_AFTER_RESTART`
  - `AgentSetupActionContract.COMMAND`, `MCP_TOOL`, `MANUAL`
  - `AgentSetupActionContract.validate(action: AgentSetupAction): List<String>`
  - `AgentSetupActionContract.requireValid(action: AgentSetupAction)`

- [ ] **Step 1: Add failing contract tests**

Append these helpers and tests to `AgentSetupVerificationServiceTest`.

```kotlin
private fun assertActionContract(report: AgentSetupVerificationReport) {
    assertTrue("report should contain at least one action", report.actions.isNotEmpty())
    report.actions.forEach { action ->
        assertTrue(
            "invalid action $action",
            AgentSetupActionContract.validate(action).isEmpty(),
        )
    }
    val firstBlocked = report.actions.indexOfFirst { it.blocksProgress }
    if (firstBlocked >= 0) {
        val blockedAction = report.actions[firstBlocked]
        assertTrue(
            "blocking action must be user/manual or agent command: $blockedAction",
            (blockedAction.actor == AgentSetupActionContract.USER && blockedAction.kind == AgentSetupActionContract.MANUAL) ||
                (blockedAction.actor == AgentSetupActionContract.AGENT && blockedAction.kind == AgentSetupActionContract.COMMAND),
        )
    }
}

@Test
fun allServiceReportsUseClosedActionContract() {
    val ready = AgentSetupVerificationService().buildReport(
        AgentSetupVerificationRequest(
            projectRoot = root,
            target = "all",
            setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
            doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
            dryRun = false,
        ),
    )
    assertActionContract(ready)

    val restart = AgentSetupVerificationService().buildReport(
        AgentSetupVerificationRequest(
            projectRoot = root,
            target = "all",
            setup = AgentSetupSnapshot(
                applied = listOf(InstallAgentJsonReport.Applied("codex", "/Users/test/.codex/config.toml", "user-global")),
                skipped = emptyList(),
                errors = emptyList(),
                mcpConfigChanged = true,
            ),
            doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
            dryRun = false,
        ),
    )
    assertActionContract(restart)
    assertEquals("restart-agent", restart.actions.first().id)
    assertEquals(AgentSetupActionContract.USER, restart.actions.first().actor)
    assertEquals(AgentSetupActionContract.MANUAL, restart.actions.first().kind)
    assertEquals(AgentSetupActionContract.AGENT_AFTER_RESTART, restart.actions.last().actor)

    val dryRun = AgentSetupVerificationService().buildReport(
        AgentSetupVerificationRequest(
            projectRoot = root,
            target = "all",
            setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
            doctorReport = null,
            dryRun = true,
        ),
    )
    assertActionContract(dryRun)
    assertEquals("recover-setup", dryRun.actions.single().id)
    assertEquals(AgentSetupActionContract.AGENT, dryRun.actions.single().actor)
    assertEquals(AgentSetupActionContract.COMMAND, dryRun.actions.single().kind)
}

@Test
fun unsupportedBuildDoesNotOpenConsole() {
    val readiness = FirstRunReadinessCatalog.unsupportedBuild(
        cause = "Release builds cannot expose the FixThis sidekick.",
    )
    val report = AgentSetupVerificationService().buildReport(
        AgentSetupVerificationRequest(
            projectRoot = root,
            target = "all",
            setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
            doctorReport = DoctorReport(
                packageName = "com.example",
                checks = listOf(
                    DoctorCheckResult(
                        name = "sidekick_session_found",
                        label = "sidekick session found",
                        ok = false,
                        message = "Release builds cannot expose the FixThis sidekick.",
                        fix = "Install a debuggable app with FixThis enabled.",
                        readiness = readiness,
                    ),
                ),
            ),
            dryRun = false,
        ),
    )

    assertActionContract(report)
    assertFalse(report.readyForMcpTooling)
    assertTrue(report.actions.none { it.tool == "fixthis_open_feedback_console" })
    assertEquals("recover-setup", report.actions.single().id)
    assertEquals(AgentSetupActionContract.COMMAND, report.actions.single().kind)
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentSetupVerificationServiceTest.allServiceReportsUseClosedActionContract' --tests '*AgentSetupVerificationServiceTest.unsupportedBuildDoesNotOpenConsole' --no-daemon
```

Expected: FAIL with unresolved reference `AgentSetupActionContract`.

- [ ] **Step 3: Add the action contract object and JSON render validation**

In `AgentSetupVerificationReport.kt`, add this object after `AgentSetupAction`.

```kotlin
internal object AgentSetupActionContract {
    const val AGENT = "agent"
    const val USER = "user"
    const val AGENT_AFTER_RESTART = "agent_after_restart"

    const val COMMAND = "command"
    const val MCP_TOOL = "mcp_tool"
    const val MANUAL = "manual"

    private val allowedActors = setOf(AGENT, USER, AGENT_AFTER_RESTART)
    private val allowedKinds = setOf(COMMAND, MCP_TOOL, MANUAL)

    fun validate(action: AgentSetupAction): List<String> = buildList {
        if (action.actor !in allowedActors) add("Unsupported action actor: ${action.actor}")
        if (action.kind !in allowedKinds) add("Unsupported action kind: ${action.kind}")
        if (action.kind == COMMAND && action.command.isNullOrBlank()) add("Command action requires command")
        if (action.kind == MCP_TOOL && action.tool.isNullOrBlank()) add("MCP tool action requires tool")
        if (action.kind == MANUAL && action.reason.isBlank()) add("Manual action requires reason")
        if (action.kind == COMMAND && !action.tool.isNullOrBlank()) add("Command action must not include tool")
        if (action.kind == MCP_TOOL && !action.command.isNullOrBlank()) add("MCP tool action must not include command")
    }

    fun requireValid(action: AgentSetupAction) {
        val errors = validate(action)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
```

Then, in `AgentSetupVerificationJsonReport.render`, add this validation before building JSON:

```kotlin
report.actions.forEach(AgentSetupActionContract::requireValid)
```

Place it as the first line inside `fun render(report: AgentSetupVerificationReport): String`.

- [ ] **Step 4: Use contract constants in service action construction**

In `AgentSetupVerificationService.kt`, replace action string literals with contract constants.

For the restart action:

```kotlin
AgentSetupAction(
    id = "restart-agent",
    actor = AgentSetupActionContract.USER,
    kind = AgentSetupActionContract.MANUAL,
    reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
    blocksProgress = true,
)
```

For the console action:

```kotlin
AgentSetupAction(
    id = "open-feedback-console",
    actor = if (readyForMcpTooling) {
        AgentSetupActionContract.AGENT
    } else {
        AgentSetupActionContract.AGENT_AFTER_RESTART
    },
    kind = AgentSetupActionContract.MCP_TOOL,
    tool = "fixthis_open_feedback_console",
    reason = "Open FixThis Studio after setup verification succeeds.",
    blocksProgress = false,
)
```

For the device action:

```kotlin
AgentSetupAction(
    id = "start-device",
    actor = AgentSetupActionContract.USER,
    kind = AgentSetupActionContract.MANUAL,
    reason = readiness.fix,
    blocksProgress = true,
)
```

For the recovery action:

```kotlin
AgentSetupAction(
    id = "recover-setup",
    actor = AgentSetupActionContract.AGENT,
    kind = AgentSetupActionContract.COMMAND,
    command = readiness.nextAction,
    reason = readiness.cause,
    blocksProgress = true,
)
```

For `rerunVerify`:

```kotlin
private fun rerunVerify(root: File, target: String) = AgentSetupAction(
    id = "rerun-verify",
    actor = AgentSetupActionContract.AGENT,
    kind = AgentSetupActionContract.COMMAND,
    command = "fixthis install-agent --project-dir ${root.absolutePath} --target $target --verify --json",
    reason = "Verify setup after the blocking condition is resolved.",
    blocksProgress = false,
)
```

- [ ] **Step 5: Pin command/tool/manual JSON rendering**

Append this test to `InstallAgentJsonReportTest`.

```kotlin
@Test
fun verifyReportRendersCommandToolAndManualActions() {
    val rendered = AgentSetupVerificationJsonReport.render(
        AgentSetupVerificationReport(
            ok = false,
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup needs another command.",
            ).copy(
                nextAction = "fixthis install-agent --project-dir /repo --target all --verify --json",
            ),
            readinessSource = "setup",
            next = listOf(
                "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
                "fixthis install-agent --project-dir /repo --target all --verify --json",
                "fixthis_open_feedback_console",
            ),
            restartRequired = true,
            requiresUserAction = true,
            userActionReason = "restart_mcp_client",
            readyForMcpTooling = false,
            actions = listOf(
                AgentSetupAction(
                    id = "restart-agent",
                    actor = AgentSetupActionContract.USER,
                    kind = AgentSetupActionContract.MANUAL,
                    reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
                    blocksProgress = true,
                ),
                AgentSetupAction(
                    id = "rerun-verify",
                    actor = AgentSetupActionContract.AGENT,
                    kind = AgentSetupActionContract.COMMAND,
                    command = "fixthis install-agent --project-dir /repo --target all --verify --json",
                    reason = "Verify setup after restart.",
                    blocksProgress = false,
                ),
                AgentSetupAction(
                    id = "open-feedback-console",
                    actor = AgentSetupActionContract.AGENT_AFTER_RESTART,
                    kind = AgentSetupActionContract.MCP_TOOL,
                    tool = "fixthis_open_feedback_console",
                    reason = "Open FixThis Studio after setup verification succeeds.",
                    blocksProgress = false,
                ),
            ),
            setup = AgentSetupSnapshot(
                applied = emptyList(),
                skipped = emptyList(),
                errors = emptyList(),
                mcpConfigChanged = true,
            ),
            verification = AgentVerificationSnapshot(
                ok = false,
                packageName = null,
                checks = emptyList(),
            ),
        ),
    )

    val obj = Json.parseToJsonElement(rendered).jsonObject
    val actions = obj.getValue("actions").jsonArray.map { it.jsonObject }
    assertEquals("manual", actions[0].getValue("kind").jsonPrimitive.content)
    assertEquals("command", actions[1].getValue("kind").jsonPrimitive.content)
    assertEquals("mcp_tool", actions[2].getValue("kind").jsonPrimitive.content)
    assertEquals(
        "fixthis install-agent --project-dir /repo --target all --verify --json",
        actions[1].getValue("command").jsonPrimitive.content,
    )
    assertEquals(
        "fixthis_open_feedback_console",
        actions[2].getValue("tool").jsonPrimitive.content,
    )
}
```

- [ ] **Step 6: Run focused CLI tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentSetupVerificationServiceTest' --tests '*InstallAgentJsonReportTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit task 1**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationReport.kt \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationService.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupVerificationServiceTest.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt
git commit -m "feat(cli): harden setup action contract"
```

---

### Task 2: Connect Autopilot Semantics To First-Handoff Evidence

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`
- Modify: `scripts/external-fixture-matrix.mjs`
- Modify: `scripts/external-fixture-matrix-test.mjs`

**Interfaces:**
- Consumes: verify stdout JSON with `schemaVersion`, `readiness`, `actions`, `requiresUserAction`, `readyForMcpTooling`
- Produces:
  - `assertVerifyReportAutopilotContract(report: object): object`
  - `autopilotEvidenceForVerifyReport(report: object): object`
  - `report.firstHandoff.autopilot` in agent-loop smoke reports

- [ ] **Step 1: Add failing agent-loop contract tests**

Update the import list in `scripts/agent-loop-smoke-test.mjs` to include:

```javascript
  assertVerifyReportAutopilotContract,
  autopilotEvidenceForVerifyReport,
```

Append these tests.

```javascript
test("verify report autopilot contract allows MCP only when ready", () => {
  const report = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: false,
    readyForMcpTooling: true,
    actions: [
      {
        id: "open-feedback-console",
        actor: "agent",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  assert.deepEqual(assertVerifyReportAutopilotContract(report), {
    readyForMcpTooling: true,
    blockedByUser: false,
    openConsoleActor: "agent",
    runnableCommandCount: 0,
  });
  assert.deepEqual(autopilotEvidenceForVerifyReport(report), {
    status: "pass",
    readyForMcpTooling: true,
    requiresUserAction: false,
    openConsoleActor: "agent",
    actionCount: 1,
  });
});

test("verify report autopilot contract blocks current MCP call after restart-required setup", () => {
  const report = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: true,
    readyForMcpTooling: false,
    actions: [
      {
        id: "restart-agent",
        actor: "user",
        kind: "manual",
        reason: "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
        blocksProgress: true,
      },
      {
        id: "open-feedback-console",
        actor: "agent_after_restart",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  const summary = assertVerifyReportAutopilotContract(report);
  assert.equal(summary.readyForMcpTooling, false);
  assert.equal(summary.blockedByUser, true);
  assert.equal(summary.openConsoleActor, "agent_after_restart");
});

test("verify report autopilot contract rejects unsafe console action", () => {
  assert.throws(
    () => assertVerifyReportAutopilotContract({
      schemaVersion: "1.1",
      readiness: { state: "READY" },
      requiresUserAction: false,
      readyForMcpTooling: false,
      actions: [
        {
          id: "open-feedback-console",
          actor: "agent",
          kind: "mcp_tool",
          tool: "fixthis_open_feedback_console",
          reason: "Open FixThis Studio.",
          blocksProgress: false,
        },
      ],
    }),
    /readyForMcpTooling=false.*agent console action/,
  );
});
```

- [ ] **Step 2: Run agent-loop tests and confirm they fail**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: FAIL with missing exports `assertVerifyReportAutopilotContract` and `autopilotEvidenceForVerifyReport`.

- [ ] **Step 3: Implement verify-report autopilot helpers**

In `scripts/agent-loop-smoke.mjs`, add these exports after `statusForEnvironment`.

```javascript
const allowedAutopilotActors = new Set(["agent", "user", "agent_after_restart"]);
const allowedAutopilotKinds = new Set(["command", "mcp_tool", "manual"]);

function assertAutopilotAction(action, index) {
  if (!action || typeof action !== "object") throw new Error(`actions[${index}] must be an object`);
  if (!allowedAutopilotActors.has(action.actor)) throw new Error(`actions[${index}] unsupported actor: ${action.actor}`);
  if (!allowedAutopilotKinds.has(action.kind)) throw new Error(`actions[${index}] unsupported kind: ${action.kind}`);
  if (action.kind === "command" && typeof action.command !== "string") throw new Error(`actions[${index}] command action requires command`);
  if (action.kind === "mcp_tool" && typeof action.tool !== "string") throw new Error(`actions[${index}] mcp_tool action requires tool`);
  if (action.kind === "manual" && typeof action.reason !== "string") throw new Error(`actions[${index}] manual action requires reason`);
}

export function assertVerifyReportAutopilotContract(report) {
  if (!report || typeof report !== "object") throw new Error("verify report must be an object");
  if (report.schemaVersion !== "1.1") throw new Error(`verify report schemaVersion must be 1.1, got ${report.schemaVersion}`);
  if (!report.readiness || typeof report.readiness.state !== "string") throw new Error("verify report readiness.state is required");
  if (!Array.isArray(report.actions) || report.actions.length === 0) throw new Error("verify report actions[] is required");
  report.actions.forEach(assertAutopilotAction);

  const openConsoleActions = report.actions.filter((action) => action.tool === "fixthis_open_feedback_console");
  const unsafeCurrentConsole = openConsoleActions.find((action) => action.actor === "agent" && report.readyForMcpTooling !== true);
  if (unsafeCurrentConsole) {
    throw new Error("readyForMcpTooling=false cannot include current-agent console action");
  }
  const blockedByUser = report.actions.some((action) => action.actor === "user" && action.blocksProgress === true);
  if (report.requiresUserAction === true && !blockedByUser) {
    throw new Error("requiresUserAction=true requires a blocking user action");
  }
  return {
    readyForMcpTooling: report.readyForMcpTooling === true,
    blockedByUser,
    openConsoleActor: openConsoleActions[0]?.actor || null,
    runnableCommandCount: report.actions.filter((action) => action.actor === "agent" && action.kind === "command").length,
  };
}

export function autopilotEvidenceForVerifyReport(report) {
  const summary = assertVerifyReportAutopilotContract(report);
  return {
    status: summary.readyForMcpTooling || summary.openConsoleActor === "agent_after_restart" || summary.runnableCommandCount > 0 ? "pass" : "deferred",
    readyForMcpTooling: summary.readyForMcpTooling,
    requiresUserAction: report.requiresUserAction === true,
    openConsoleActor: summary.openConsoleActor,
    actionCount: report.actions.length,
  };
}
```

- [ ] **Step 4: Include autopilot evidence in first-handoff success reports**

In `firstHandoffSuccess`, add an optional `autopilot` parameter and include it in the return value only when present.

Replace the function with:

```javascript
export function firstHandoffSuccess({
  savedItemCount = 0,
  readFeedbackItemCount = 0,
  readFeedbackSentCount = 0,
  autopilot = null,
} = {}) {
  return {
    status: "pass",
    savedItemCount,
    readFeedbackItemCount,
    readFeedbackSentCount,
    ...(autopilot ? { autopilot } : {}),
  };
}
```

Then update `renderMarkdownReport` after the sent-item lines:

```javascript
  if (firstHandoff.autopilot) {
    lines.push(`- Autopilot: ${firstHandoff.autopilot.status}`);
    lines.push(`- Autopilot readyForMcpTooling: ${firstHandoff.autopilot.readyForMcpTooling}`);
    lines.push(`- Autopilot open console actor: ${firstHandoff.autopilot.openConsoleActor || "none"}`);
  }
```

- [ ] **Step 5: Pin markdown rendering of autopilot evidence**

In `scripts/agent-loop-smoke-test.mjs`, update the `first handoff success carries saved and readable item counts` expected object to include autopilot coverage.

Use this assertion:

```javascript
assert.deepEqual(firstHandoffSuccess({
  savedItemCount: 3,
  readFeedbackItemCount: 4,
  readFeedbackSentCount: 3,
  autopilot: {
    status: "pass",
    readyForMcpTooling: true,
    requiresUserAction: false,
    openConsoleActor: "agent",
    actionCount: 1,
  },
}), {
  status: "pass",
  savedItemCount: 3,
  readFeedbackItemCount: 4,
  readFeedbackSentCount: 3,
  autopilot: {
    status: "pass",
    readyForMcpTooling: true,
    requiresUserAction: false,
    openConsoleActor: "agent",
    actionCount: 1,
  },
});
```

In `buildReport and markdown summarize first handoff and lifecycle counts`, pass the same `autopilot` object to `firstHandoffSuccess` and add:

```javascript
assert.match(markdown, /- Autopilot: pass/);
assert.match(markdown, /- Autopilot readyForMcpTooling: true/);
```

- [ ] **Step 6: Make external fixture setup use verify JSON**

In `scripts/external-fixture-matrix.mjs`, update `planFixtureCommands` so the `install-agent` command uses `--verify --json`.

Replace the install-agent command entry with:

```javascript
{
  name: 'install-agent-verify-json',
  command: `JAVA_OPTS=${userHomeOpt} ${cli} install-agent --project-dir ${project} --target all --package ${shellQuote(fixture.applicationId)} --verify --json`,
},
```

Keep the `doctor-json` command because it remains the explicit compatibility and recovery agreement check.

- [ ] **Step 7: Accept verify JSON reaching app-launch readiness**

In `scripts/external-fixture-matrix.mjs`, replace `hasOkCheck` with this version so it reads both doctor JSON and verify JSON checks:

```javascript
function hasOkCheck(report, name) {
  const checks = Array.isArray(report?.checks)
    ? report.checks
    : Array.isArray(report?.verification?.checks)
      ? report.verification.checks
      : [];
  return checks.some((check) => check.name === name && check.status === 'ok');
}
```

Then replace `normalizeFixtureCommandResult` with this version so both the new verify command and the existing doctor command accept the expected setup boundary:

```javascript
export function normalizeFixtureCommandResult(entry, fixture, result) {
  if (!['install-agent-verify-json', 'doctor-json'].includes(entry.name) || result.status !== 'fail') return result;
  const report = parseJsonObject(result.stdout);
  const expectedSchema = entry.name === 'doctor-json' ? '1.0' : '1.1';
  const reachesExpectedNextAction = report?.schemaVersion === expectedSchema &&
    report?.readiness?.state === 'NEEDS_APP_LAUNCH' &&
    report?.nextAction === 'Open app' &&
    hasOkCheck(report, 'android_project_found') &&
    hasOkCheck(report, 'fixthis_project_metadata_found') &&
    hasOkCheck(report, 'adb_found') &&
    hasOkCheck(report, 'device_connected');
  if (!reachesExpectedNextAction) return result;
  return {
    ...result,
    status: 'pass',
    acceptedExitCode: result.exitCode,
    acceptedReadinessState: report.readiness.state,
    acceptedExpectedSetup: fixture.expectedSetup,
  };
}
```

- [ ] **Step 8: Pin external matrix command surface**

In `scripts/external-fixture-matrix-test.mjs`, find the test that asserts planned command names include `install-agent`. Update the expected command name to `install-agent-verify-json` and assert that the command includes `--verify --json`.

Use this assertion inside that test:

```javascript
const install = commands.find((entry) => entry.name === 'install-agent-verify-json');
assert.ok(install, 'expected install-agent-verify-json command');
assert.match(install.command, /install-agent --project-dir .* --target all .* --verify --json/);
```

Add this test next to `normalizeFixtureCommandResult accepts doctor reaching app launch readiness`:

```javascript
test('normalizeFixtureCommandResult accepts install-agent verify reaching app launch readiness', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures[0];
  const result = normalizeFixtureCommandResult(
    { name: 'install-agent-verify-json' },
    fixture,
    {
      status: 'fail',
      durationMs: 1,
      exitCode: 1,
      stderr: 'install-agent verification requires follow-up\n',
      stdout: JSON.stringify({
        schemaVersion: '1.1',
        ok: false,
        nextAction: 'Open app',
        readiness: { state: 'NEEDS_APP_LAUNCH' },
        actions: [
          {
            id: 'recover-setup',
            actor: 'agent',
            kind: 'command',
            command: 'Open app',
            reason: 'The debug app is not connected to the FixThis bridge.',
            blocksProgress: true,
          },
        ],
        verification: {
          checks: [
            { name: 'android_project_found', status: 'ok' },
            { name: 'fixthis_project_metadata_found', status: 'ok' },
            { name: 'adb_found', status: 'ok' },
            { name: 'device_connected', status: 'ok' },
            { name: 'sidekick_session_found', status: 'fail' },
          ],
        },
      }),
    },
  );

  assert.equal(result.status, 'pass');
  assert.equal(result.acceptedExitCode, 1);
  assert.equal(result.acceptedReadinessState, 'NEEDS_APP_LAUNCH');
});
```

- [ ] **Step 9: Run focused Node tests**

Run:

```bash
npm run agent-loop:smoke:test
npm run external-fixture:matrix:test
```

Expected: PASS.

- [ ] **Step 10: Commit task 2**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs \
  scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs
git commit -m "test(agent-loop): prove setup autopilot contract"
```

---

### Task 3: Document The Two Setup Schemas And Gate Release Evidence

**Files:**
- Modify: `docs/reference/agent-setup-schema.md`
- Modify: `README.md`
- Modify: `docs/getting-started/agent-install-snippet.md`
- Modify: `docs/reference/cli.md`
- Modify: `scripts/agent-bootstrap-contract-test.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`

**Interfaces:**
- Consumes: CLI schema `1.1` report fields `actions[]`, `readyForMcpTooling`, `requiresUserAction`, `verification`
- Produces: docs and release-readiness rules that require first-handoff autopilot evidence

- [ ] **Step 1: Add failing docs contract assertions**

In `scripts/agent-bootstrap-contract-test.mjs`, update the canonical command constants:

```javascript
const canonicalInstall = 'fixthis install-agent --project-dir . --target all --verify --json';
const canonicalDoctor = 'fixthis doctor --project-dir . --json';
const restartLine = 'Restart Claude Code or Codex';
const consoleTool = 'fixthis_open_feedback_console';
const actionContract = 'actions[]';
const readinessGate = 'readyForMcpTooling';
```

Add these assertions to the README test:

```javascript
assert.match(text, /actions\[\]/);
assert.match(text, /readyForMcpTooling/);
```

Add these assertions to the snippet test:

```javascript
assert.match(snippet, /actions\[\]/);
assert.match(snippet, /readyForMcpTooling/);
```

Append this test:

```javascript
test('agent setup schema separates handoff file and verify stdout report', () => {
  const text = read('docs/reference/agent-setup-schema.md');
  assert.match(text, /Setup handoff files/);
  assert.match(text, /\.fixthis\/agent-setup\.json/);
  assert.match(text, /Verify stdout report/);
  assert.match(text, /schemaVersion.*1\.1/);
  assert.match(text, /readyForMcpTooling/);
  assert.match(text, /actions\[\]/);
});
```

- [ ] **Step 2: Run docs contract tests and confirm they fail**

Run:

```bash
npm run docs:agent-bootstrap:test
```

Expected: FAIL because `docs/reference/agent-setup-schema.md` does not yet split handoff-file and verify-stdout contracts.

- [ ] **Step 3: Rewrite agent setup schema reference**

Replace `docs/reference/agent-setup-schema.md` with:

```markdown
# Agent Setup Contracts

FixThis exposes two related setup contracts:

- setup handoff files written under `.fixthis/`;
- the stdout verification report emitted by `fixthis install-agent --verify --json`.

Agents should use the stdout verification report for immediate control flow.
The `.fixthis/agent-setup.json` file is a local handoff artifact for later
inspection and recovery.

## Setup handoff files

`fixthis install-agent` writes `.fixthis/project.json`,
`.fixthis/agent-setup.json`, `.fixthis/agent-setup.md`, and
`.fixthis/mcp.json.template`. `fixthis init --agent` writes the same handoff
files when the Gradle plugin is already applied. The JSON file is the canonical
file contract; the Markdown file is a human-readable rendering of the same
data.

### Handoff file schema (v1.0)

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | Currently `"1.0"`. Bumped only on incompatible changes. |
| `state.packageName` | string | Detected Android application id. |
| `state.projectRoot` | string | Absolute path to the Android project root. |
| `state.detectedAt` | RFC 3339 timestamp | When the file was generated. |
| `readiness` | object | First-run readiness object using the same shape as `fixthis doctor --json` and the feedback console. |
| `next` | string[] | Ordered list of runnable commands the agent should attempt next. Each entry MUST be a valid shell command; lines starting with `#` are comments. |
| `restartGuidance` | string | Human-readable reminder to restart Claude Code / Codex after MCP config changes. |
| `recovery` | object | Keyed by failure-mode id; values are one-line remediation strings plus optional nested groups. |
| `recovery.readinessRecovery` | map<string,string> | Optional readiness-state-specific remediation strings. |

`next[0]` is guaranteed runnable as a shell command. Additional `next` entries
may include comments prefixed with `#`.

## Verify stdout report

Run:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

The command emits one parseable stdout JSON document. This report is the source
of truth for the current agent.

### Verify report schema (v1.1)

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | Currently `"1.1"` for the verify stdout report. |
| `ok` | boolean | True only when setup and verification are ready and no restart blocks MCP tooling. |
| `readiness` | object | First-run readiness object selected from setup and doctor verification. |
| `readinessSource` | string | `setup`, `verification`, or `merged`. |
| `nextAction` | string | Compatibility summary for older agents. Prefer `actions[]`. |
| `next` | string[] | Compatibility list derived from `actions[]`. Prefer `actions[]`. |
| `restartRequired` | boolean | True when MCP config changed and the current client must restart. |
| `requiresUserAction` | boolean | True when a blocking user/manual action is present. |
| `userActionReason` | string | Optional stable reason for the first blocking user action. |
| `readyForMcpTooling` | boolean | True only when the current agent may call FixThis MCP tools. |
| `actions[]` | array | Ordered action queue for agents. |
| `setup` | object | Nested setup writes, skips, errors, and `mcpConfigChanged`. |
| `verification` | object | Nested doctor verification status, package name, checks, and optional `skippedReason`. |

### Action contract

Allowed `actions[].actor` values:

- `agent`: the current agent may perform this action now.
- `user`: the current agent must stop and report the blocker.
- `agent_after_restart`: a future agent process may perform this action after the user restarts the MCP client.

Allowed `actions[].kind` values:

- `command`: run the exact shell command in `command`.
- `mcp_tool`: call the exact MCP tool in `tool`.
- `manual`: report `reason` to the user and do not continue automatically.

`blocksProgress=true` means no later action may be attempted by the current
agent until that action is resolved. The current agent must not call
`fixthis_open_feedback_console` unless `readyForMcpTooling=true`.

## Recovery Keys

Closed set:

- `no-android-context`
- `no-app-module`
- `release-only-variant`
- `view-system-mixed`
- `missing-application-id`

`recovery.readinessRecovery` currently documents:

- `NEEDS_INSTALL`
- `CONFIG_RECOVERABLE`
- `ENV_BLOCKER`
- `UNSUPPORTED_BUILD`
- `NEEDS_APP_LAUNCH`
```

- [ ] **Step 4: Align README and snippet wording**

In `README.md` and `docs/getting-started/agent-install-snippet.md`, make sure the agent prompt includes these exact lines:

```text
1. Run `fixthis install-agent --project-dir . --target all --verify --json`.
2. Use the JSON `readiness.state` and `actions[]` as the source of truth.
3. If `requiresUserAction` is true, tell me the exact blocking action.
4. Do not call `fixthis_open_feedback_console` until `readyForMcpTooling` is true, or until the report's `agent_after_restart` action is reached after restart.
```

- [ ] **Step 5: Add release-readiness rule**

In `scripts/check-release-readiness.mjs`, add this rule after `R45.external-first-handoff-deferral-rule`:

```javascript
requireRegex(
  'R52.first-handoff-autopilot-contract',
  'docs/contributing/release-readiness.md',
  /## First Handoff Autopilot Evidence[\s\S]*install-agent --project-dir \. --target all --verify --json[\s\S]*actions\[\][\s\S]*readyForMcpTooling[\s\S]*npm run agent-loop:smoke:test[\s\S]*npm run agent-loop:smoke -- --strict/,
  'first handoff autopilot evidence section',
);
```

- [ ] **Step 6: Add release-readiness section**

In `docs/contributing/release-readiness.md`, add this section after `## External App First Handoff Recovery`.

```markdown
## First Handoff Autopilot Evidence

The first-handoff autopilot line may be claimed only when the release commit
has evidence that the agent can use `fixthis install-agent --project-dir .
--target all --verify --json` as the source of truth from setup verification
through opening the feedback console.

| Claim | Required evidence |
| --- | --- |
| Verify stdout report actions are a closed agent contract with `actions[]`, `requiresUserAction`, and `readyForMcpTooling`. | `./gradlew :fixthis-cli:test --tests "*AgentSetupVerificationServiceTest" --tests "*InstallAgentJsonReportTest" --no-daemon` |
| First handoff evidence checks the verify-report action semantics and reaches one MCP-readable sent feedback item. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict` |

The current agent must not call `fixthis_open_feedback_console` while
`readyForMcpTooling=false`. When the report emits `agent_after_restart`, the
user must restart the MCP client before the console-opening tool call.
```

- [ ] **Step 7: Run docs and release readiness checks**

Run:

```bash
npm run docs:agent-bootstrap:test
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
```

Expected: PASS.

- [ ] **Step 8: Commit task 3**

```bash
git add docs/reference/agent-setup-schema.md README.md docs/getting-started/agent-install-snippet.md \
  docs/reference/cli.md scripts/agent-bootstrap-contract-test.mjs \
  docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs: document first handoff autopilot contract"
```

---

### Task 4: Final Verification And Graph Update

**Files:**
- Modify: `CHANGELOG.md`
- Generated/ignored: `graphify-out/`

**Interfaces:**
- Consumes: completed Tasks 1-3
- Produces: final local evidence, graph refresh, and a clean implementation branch

- [ ] **Step 1: Add changelog entry**

Under `## Unreleased` in `CHANGELOG.md`, replace `No unreleased user-visible changes yet.` with:

```markdown
### Added

- Added the First Handoff Autopilot contract: agent setup verification reports now have a stricter `actions[]` contract, docs distinguish setup handoff files from verify stdout reports, and release evidence checks that the setup report semantics lead to one MCP-readable sent feedback item.
```

- [ ] **Step 2: Run full focused verification**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentSetupVerificationServiceTest' --tests '*InstallAgentJsonReportTest' --no-daemon
npm run agent-loop:smoke:test
npm run external-fixture:matrix:test
npm run docs:agent-bootstrap:test
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 3: Run optional strict Android proof when a ready emulator is available**

Check environment:

```bash
adb devices -l
adb shell getprop sys.boot_completed
```

If a connected unlocked device or emulator is ready, run:

```bash
npm run agent-loop:smoke -- --strict
```

Expected: PASS and `build/reports/fixthis-agent-loop/report.md` includes `## First Handoff`, `Status: pass`, and autopilot evidence.

If Android runtime is unavailable, record the exact reason from `adb devices -l` and do not claim strict connected proof.

- [ ] **Step 4: Update graphify output**

Run:

```bash
graphify update .
```

Expected: exits 0. Dirty `graphify-out/` files are expected and must not be committed.

- [ ] **Step 5: Commit final docs and changelog**

```bash
git add CHANGELOG.md
git commit -m "docs: record first handoff autopilot evidence"
```

- [ ] **Step 6: Final status check**

Run:

```bash
git status --short
```

Expected: no tracked source changes remain. Untracked local artifacts under `.fixthis/`, `graphify-out/`, build reports, or pre-existing unrelated plan files may remain uncommitted.
