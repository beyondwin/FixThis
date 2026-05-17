# v0.5 Trustworthy Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the README-first Claude Code / Codex onboarding path executable, recoverable, and release-verifiable from `fixthis install-agent` through the first saved MCP handoff.

**Architecture:** Keep the existing CLI, Gradle plugin, MCP console, and docs surfaces. Add a small docs contract test, tighten setup/doctor/readiness outputs, map console first-run blockers to the shared readiness vocabulary, and extend the first-run smoke so the release claim is backed by executable evidence.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Clikt, Gradle Kotlin DSL, Node.js `node:test`, Playwright, shell scripts, Markdown docs.

---

## Scope Check

The approved spec spans docs, CLI, console, smoke tests, and release gates. This is one implementation plan because the tracks share one release claim: README-first onboarding must get Claude Code and Codex from setup to first handoff. Each task below is independently testable and ends with a commit.

No task adds production runtime support, non-Compose targeting, cloud behavior, automatic code edits, large source-matching changes, or persisted MCP JSON field renames.

## File Structure

### Create

- `scripts/agent-bootstrap-contract-test.mjs`
  - Owns README/snippet command-order assertions for the Claude/Codex bootstrap path.
- `scripts/first-run-smoke-contract-test.mjs`
  - Owns low-cost script contract assertions for `scripts/first-run-smoke.mjs`.

### Modify

- `README.md`
  - Add the README-first Claude/Codex agent bootstrap block.
- `docs/getting-started/agent-install-snippet.md`
  - Align pasteable repo instructions with README command order.
- `docs/getting-started/connect-your-agent.md`
  - Align Claude/Codex setup guidance with `install-agent`, `doctor --json`, restart, console.
- `MCP.md`
  - Keep MCP bootstrap summary aligned with the README-first path.
- `AGENTS.md`
  - Keep agent notes aligned with the README-first path.
- `package.json`
  - Add script entries for the new contract tests.
- `scripts/verify-ci-local.sh`
  - Add the docs/bootstrap contract test to fast gates.
- `scripts/check-release-readiness.mjs`
  - Add release-readiness rules for the v0.5 onboarding claim and release-time smoke.
- `docs/contributing/release-readiness.md`
  - Add the v0.5 onboarding release claim and supporting gates.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt`
  - Write `.fixthis/project.json` from `install-agent` / `init --agent`, align next steps, and keep readiness recovery in JSON.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
  - Add top-level readiness and restart metadata to `--json` output.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
  - Pass top-level report readiness and restart metadata from `install-agent`.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
  - Map first-run failures to the shared readiness states more precisely.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt`
  - Cover `project.json`, command order, readiness, and recovery output.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
  - Cover report-level readiness and restart metadata.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`
  - Cover readiness classification changes.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`
  - Update generated setup guide expectations and assert `project.json`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt`
  - Map no-device, unauthorized/offline, selected-invalid, and multi-device states to readiness states that match first-run recovery.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt`
  - Cover console readiness mapping.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionRoutesTest.kt`
  - Cover `/api/connection` readiness JSON.
- `scripts/first-run-smoke.mjs`
  - Assert saved handoff leaves sent feedback readable from the session API.

---

## Task 1: Lock The README/Snippet Bootstrap Contract

**Files:**
- Create: `scripts/agent-bootstrap-contract-test.mjs`
- Modify: `package.json`
- Modify: `scripts/verify-ci-local.sh`

- [ ] **Step 1: Write the failing docs contract test**

Create `scripts/agent-bootstrap-contract-test.mjs`:

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);

function read(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function indexOfCommand(text, command) {
  return text.indexOf(command);
}

const canonicalInstall = 'fixthis install-agent --project-dir . --target all';
const canonicalDoctor = 'fixthis doctor --project-dir . --json';
const restartLine = 'Restart Claude Code or Codex';
const consoleTool = 'fixthis_open_feedback_console';

function assertCanonicalOrder(file, text) {
  const installIndex = indexOfCommand(text, canonicalInstall);
  const doctorIndex = indexOfCommand(text, canonicalDoctor);
  assert.notEqual(installIndex, -1, `${file} must contain ${canonicalInstall}`);
  assert.notEqual(doctorIndex, -1, `${file} must contain ${canonicalDoctor}`);
  assert.ok(
    installIndex < doctorIndex,
    `${file} must put install-agent before doctor --json`,
  );
}

test('README exposes the canonical Claude/Codex bootstrap path', () => {
  const text = read('README.md');
  assert.match(text, /Claude Code[\s\S]*Codex/);
  assertCanonicalOrder('README.md', text);
  assert.match(text, new RegExp(restartLine));
  assert.match(text, new RegExp(consoleTool));
  assert.match(text, /Do not commit `?\.fixthis\/?`?/);
  assert.match(text, /debug-only/i);
});

test('agent install snippet matches README command order', () => {
  const readme = read('README.md');
  const snippet = read('docs/getting-started/agent-install-snippet.md');
  assertCanonicalOrder('docs/getting-started/agent-install-snippet.md', snippet);
  for (const command of [canonicalInstall, canonicalDoctor]) {
    assert.ok(readme.includes(command), `README missing ${command}`);
    assert.ok(snippet.includes(command), `snippet missing ${command}`);
  }
  assert.match(snippet, new RegExp(restartLine));
  assert.match(snippet, new RegExp(consoleTool));
});

test('agent-facing docs keep release and artifact constraints visible', () => {
  const combined = [
    read('README.md'),
    read('docs/getting-started/agent-install-snippet.md'),
  ].join('\n');
  assert.match(combined, /Never add FixThis to release builds|Do not configure release builds/);
  assert.match(combined, /Do not commit `?\.fixthis\/?`?/);
});
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
node --test scripts/agent-bootstrap-contract-test.mjs
```

Expected: FAIL because README and the agent snippet do not yet share the exact README-first `install-agent` then `doctor --json` bootstrap contract.

- [ ] **Step 3: Add npm script**

Modify `package.json` under `scripts`:

```json
"docs:agent-bootstrap:test": "node --test scripts/agent-bootstrap-contract-test.mjs",
```

Place it near the other docs/check scripts, after `checks:observation:test`.

- [ ] **Step 4: Add the contract test to fast local verification**

Modify `scripts/verify-ci-local.sh` after `node scripts/check-release-readiness.mjs`:

```bash
run_step "npm run docs:agent-bootstrap:test"
```

- [ ] **Step 5: Run focused verification**

Run:

```bash
npm run docs:agent-bootstrap:test
```

Expected: FAIL for the same doc-contract reason as Step 2.

- [ ] **Step 6: Commit the failing contract test**

```bash
git add scripts/agent-bootstrap-contract-test.mjs package.json scripts/verify-ci-local.sh
git commit -m "test: lock README-first agent bootstrap contract"
```

---

## Task 2: Update README And Agent Docs To The Canonical Bootstrap Path

**Files:**
- Modify: `README.md`
- Modify: `docs/getting-started/agent-install-snippet.md`
- Modify: `docs/getting-started/connect-your-agent.md`
- Modify: `MCP.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Add README agent bootstrap block**

In `README.md`, update the external app Quick Start so it contains this block before the detailed shell commands:

````markdown
### Claude Code / Codex Bootstrap Prompt

Paste this into Claude Code or Codex from the root of a Jetpack Compose Android app:

```text
Install FixThis in this project and configure it for this agent.

Use this order:
1. Run `fixthis install-agent --project-dir . --target all`.
2. Run `fixthis doctor --project-dir . --json`.
3. Use the doctor JSON readiness result as the source of truth.
4. If MCP config was written, tell me to restart Claude Code or Codex before calling `fixthis_open_feedback_console`.

Do not configure release builds. Do not commit `.fixthis/`.
```
````

Keep the existing install commands below it. Do not remove Homebrew, npm, or GitHub Release installer options.

- [ ] **Step 2: Replace the pasteable agent snippet command order**

In `docs/getting-started/agent-install-snippet.md`, replace the target repo snippet steps 3-6 with:

```markdown
3. Run `fixthis install-agent --project-dir . --target all`. This patches the
   detected app module with Gradle plugin `io.github.beyondwin.fixthis.compose`,
   writes MCP config for Claude Code / Codex, writes `.fixthis/project.json`,
   and writes `.fixthis/agent-setup.*` handoff files. Pass
   `--package <applicationId>` if detection is ambiguous. Pass `--dry-run`
   before writing if the repo has unusual Gradle wiring.
4. Run `fixthis doctor --project-dir . --json`. Treat the JSON readiness
   result as the source of truth.
5. If MCP config was written, restart Claude Code or Codex so the client reloads it.
6. Open the console with MCP tool `fixthis_open_feedback_console`.
```

Keep the manual Gradle plugin snippet as a recovery branch after these steps.

- [ ] **Step 3: Align connect-your-agent guidance**

In `docs/getting-started/connect-your-agent.md`, replace the external app agent-first sequence with:

```markdown
For a new Android app integration, prefer the single agent command:

```bash
fixthis install-agent --project-dir . --target all
fixthis doctor --project-dir . --json
```

Restart Claude Code or Codex after MCP config is written. Then open the
console with `fixthis_open_feedback_console`.
```

Keep `./scripts/bootstrap-mcp.sh --sample` as the sample-repo shortcut.

- [ ] **Step 4: Align concise MCP and agent notes**

In `MCP.md` and `AGENTS.md`, keep the external Android repo sequence as:

```bash
fixthis install-agent --project-dir . --target all
fixthis doctor --project-dir . --json
```

Mention that `./gradlew fixthisSetup` is a recovery or manual verification command when doctor reports missing generated metadata, not the primary README-first sequence.

- [ ] **Step 5: Run docs contract tests**

Run:

```bash
npm run docs:agent-bootstrap:test
bash scripts/check-docs-cli-surface.sh
node scripts/check-doc-consistency.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit docs alignment**

```bash
git add README.md docs/getting-started/agent-install-snippet.md docs/getting-started/connect-your-agent.md MCP.md AGENTS.md
git commit -m "docs: add README-first Claude Codex bootstrap"
```

---

## Task 3: Make Agent Setup Artifacts Support `install-agent -> doctor`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`

- [ ] **Step 1: Add failing setup artifact tests**

Append these tests to `AgentSetupFilesTest`:

```kotlin
@Test
fun agentSetupWritesProjectJsonForReadmeFirstDoctorPath() {
    val root = tempFolder.newFolder("proj")
    AgentSetupFiles.write(
        projectRoot = root,
        packageName = "com.example.app",
        serverName = "fixthis",
        dryRun = false,
        echo = {},
    )

    val projectJson = java.io.File(root, ".fixthis/project.json")
    assertTrue("project.json missing", projectJson.isFile)
    val obj = Json.parseToJsonElement(projectJson.readText()).jsonObject
    assertTrue("missing applicationId", obj.getValue("applicationId").jsonPrimitive.content == "com.example.app")
}

@Test
fun setupGuideUsesDoctorJsonBeforeConsole() {
    val root = tempFolder.newFolder("proj")
    AgentSetupFiles.write(
        projectRoot = root,
        packageName = "com.example.app",
        serverName = "fixthis",
        dryRun = false,
        echo = {},
    )

    val text = java.io.File(root, ".fixthis/agent-setup.md").readText()
    val doctorIndex = text.indexOf("fixthis doctor --project-dir . --json")
    val consoleIndex = text.indexOf("fixthis_open_feedback_console")
    assertTrue("doctor command missing", doctorIndex >= 0)
    assertTrue("console tool missing", consoleIndex >= 0)
    assertTrue("doctor should come before console", doctorIndex < consoleIndex)
    assertTrue(text.contains("Restart Claude Code or Codex"))
}
```

Update imports in the same file:

```kotlin
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Add failing install-agent integration expectation**

In `InitAgentCommandTest.installAgentAliasesAgentInit`, add:

```kotlin
assertTrue(projectRoot.resolve(".fixthis/project.json").isFile)
```

In `InitAgentCommandTest.initAgentWritesProjectScopedAgentFiles`, replace the old setup guide assertions with:

```kotlin
assertTrue(setupText.contains("fixthis doctor --project-dir . --json"))
assertTrue(setupText.contains("Restart Claude Code or Codex"))
assertTrue(setupText.contains("fixthis_open_feedback_console"))
assertTrue(setupText.contains("If doctor reports `NEEDS_INSTALL`"))
assertFalse(setupText.contains("fixthis init --agent --project-dir ."))
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --tests "*InitAgentCommandTest" --no-daemon
```

Expected: FAIL because `AgentSetupFiles.write` does not write `.fixthis/project.json` and the guide still points agents through `fixthis init`.

- [ ] **Step 4: Write project metadata from AgentSetupFiles**

In `AgentSetupFiles.kt`, add this write plan before `agent-setup.md`:

```kotlin
WritePlan(
    fixThisDirectory.resolve("project.json"),
    fixThisJson.encodeToString(projectMetadata(packageName, projectRoot)) + "\n",
),
```

Add this helper near `projectScopedMcpTemplate`:

```kotlin
private fun projectMetadata(packageName: String, projectRoot: File) = buildJsonObject {
    put("schemaVersion", "1.0")
    put("applicationId", packageName)
    put("projectRoot", projectRoot.absolutePath)
    put("createdBy", "fixthis agent setup")
}
```

`ProjectConfig.resolvePackageName` uses `ignoreUnknownKeys`, so the extra fields do not break existing decoding.

- [ ] **Step 5: Replace the generated setup guide**

Replace `agentSetupGuide` body in `AgentSetupFiles.kt` with:

```kotlin
private fun agentSetupGuide(packageName: String): String = """
    # FixThis Agent Setup

    This Android project is configured for FixThis package `$packageName`.

    Agent sequence from the project root:

    1. Run `fixthis doctor --project-dir . --json`.
    2. Use the doctor JSON readiness result as the source of truth.
    3. Restart Claude Code or Codex if MCP config was written.
    4. Use MCP tool `fixthis_open_feedback_console`.

    If doctor reports `NEEDS_INSTALL` or generated metadata is missing, run
    `./gradlew fixthisSetup` and then rerun
    `fixthis doctor --project-dir . --json`.

    Never add FixThis to release builds. Do not commit `.fixthis/`.
""".trimIndent() + "\n"
```

- [ ] **Step 6: Update agent setup manifest `next` order**

In `agentSetupManifest`, replace the `next` array with:

```kotlin
put(
    "next",
    buildJsonArray {
        add(JsonPrimitive("fixthis doctor --project-dir ${projectRoot.absolutePath} --json"))
        add(JsonPrimitive("# Restart Claude Code / Codex to reload MCP config"))
        add(JsonPrimitive("fixthis_open_feedback_console"))
    },
)
```

Keep `readinessRecovery.NEEDS_INSTALL` pointing at `fixthis install-agent --project-dir . --target all`.

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --tests "*InitAgentCommandTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit setup artifact changes**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt
git commit -m "feat(cli): write README-first agent setup artifacts"
```

---

## Task 4: Add Report-Level Readiness And Restart Metadata To `install-agent --json`

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt`

- [ ] **Step 1: Add failing JSON report tests**

Append this test to `InstallAgentJsonReportTest`:

```kotlin
@Test
fun reportCanCarryTopLevelReadinessAndRestartMetadata() {
    val rendered = InstallAgentJsonReport.render(
        applied = listOf(
            InstallAgentJsonReport.Applied("claude", "/tmp/.claude/settings.json", "project-local"),
        ),
        skipped = emptyList(),
        errors = emptyList(),
        next = listOf("fixthis doctor --project-dir /repo --json"),
        readiness = FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis setup completed; verify the debug app before opening the console.",
        ).copy(
            nextAction = "fixthis doctor --project-dir /repo --json",
        ),
        restartRequired = true,
    )

    val obj = Json.parseToJsonElement(rendered).jsonObject
    val readiness = obj.getValue("readiness").jsonObject
    assertEquals("CONFIG_RECOVERABLE", readiness.getValue("state").jsonPrimitive.content)
    assertEquals("fixthis doctor --project-dir /repo --json", readiness.getValue("nextAction").jsonPrimitive.content)
    assertEquals("true", obj.getValue("restartRequired").jsonPrimitive.content)
}
```

- [ ] **Step 2: Run focused test and verify failure**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --no-daemon
```

Expected: FAIL because `InstallAgentJsonReport.render` has no report-level readiness or restart fields.

- [ ] **Step 3: Extend JSON report renderer**

Change `InstallAgentJsonReport.render` signature:

```kotlin
fun render(
    applied: List<Applied>,
    skipped: List<Skipped>,
    errors: List<ErrorEntry>,
    next: List<String>,
    readiness: FirstRunReadiness? = null,
    restartRequired: Boolean = false,
): String {
```

Inside the JSON object, after `nextAction`, add:

```kotlin
putReadiness(readiness)
put("restartRequired", restartRequired)
```

- [ ] **Step 4: Add install-agent top-level readiness helper**

In `SetupCommand.kt`, add this helper near `InstallAgentCommand`:

```kotlin
private fun installAgentTopLevelReadiness(
    root: File,
    skipped: List<InstallAgentJsonReport.Skipped>,
    errors: List<InstallAgentJsonReport.ErrorEntry>,
) = when {
    errors.isNotEmpty() -> errors.first().readiness ?: FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup hit an error; inspect the error entries and rerun setup.",
    ).copy(
        verify = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
        fix = "Inspect the dry-run output, fix the reported setup error, then rerun install-agent.",
        nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
    )
    skipped.isNotEmpty() -> skipped.first().readiness ?: FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup completed with skipped targets.",
    ).copy(
        verify = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
        fix = "Review skipped targets and rerun setup with an explicit --target or --allow-global when safe.",
        nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
    )
    else -> FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup completed; verify the debug app before opening the console.",
    ).copy(
        verify = "fixthis doctor --project-dir ${root.absolutePath} --json",
        fix = "Run doctor, restart Claude Code or Codex if MCP config changed, then open the feedback console.",
        nextAction = "fixthis doctor --project-dir ${root.absolutePath} --json",
    )
}
```

- [ ] **Step 5: Pass readiness from InstallAgentCommand JSON mode**

In `InstallAgentCommand.run`, inside `if (json)`, compute readiness and restart:

```kotlin
val reportReadiness = installAgentTopLevelReadiness(root, skippedAll, errors)
val restartRequired = applied.any { it.target == "claude" || it.target == "codex" }
```

Pass both into `InstallAgentJsonReport.render`:

```kotlin
readiness = reportReadiness,
restartRequired = restartRequired,
```

- [ ] **Step 6: Update install-agent JSON integration test**

In `InitAgentCommandTest.installAgentJsonModeEmitsSchemaAndAppliedTargets`, add:

```kotlin
assertEquals("true", obj.getValue("restartRequired").jsonPrimitive.content)
assertEquals(
    "CONFIG_RECOVERABLE",
    obj.getValue("readiness").jsonObject.getValue("state").jsonPrimitive.content,
)
assertTrue(
    obj.getValue("readiness").jsonObject
        .getValue("nextAction").jsonPrimitive.content
        .contains("fixthis doctor"),
)
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --tests "*InitAgentCommandTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit report readiness**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InitAgentCommandTest.kt
git commit -m "feat(cli): report install-agent readiness"
```

---

## Task 5: Tighten Doctor Readiness Classification

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`

- [ ] **Step 1: Add failing doctor classification tests**

Add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
```

Append these tests to `DoctorCommandTest`:

```kotlin
@Test
fun doctorMapsNoDeviceToDeviceBlockedReadiness() {
    val readiness = readinessForDoctorCheck(
        name = "device_connected",
        message = "No connected Android device or emulator found",
        fix = "Start an emulator or connect a device, then run `adb devices`.",
    )

    assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, readiness.state)
    assertTrue(readiness.nextAction.contains("emulator"))
}

@Test
fun doctorMapsAmbiguousPackageToConfigRecoverable() {
    val readiness = readinessForDoctorCheck(
        name = "fixthis_project_metadata_found",
        message = "Multiple Android applicationId values found in Gradle build files: a, b. Pass --package explicitly.",
        fix = "Run `./gradlew fixthisSetup` or pass --package <applicationId>.",
    )

    assertEquals(FirstRunReadinessState.CONFIG_RECOVERABLE, readiness.state)
    assertTrue(readiness.nextAction.contains("--dry-run"))
}

@Test
fun doctorClassifiesSidekickRunAsDeniedAsUnsupportedBuild() {
    val readiness = readinessForDoctorCheck(
        name = "sidekick_session_found",
        message = "run-as: package not debuggable: permission denied",
        fix = "Build and run the debug app with FixThis sidekick installed.",
    )

    assertEquals(FirstRunReadinessState.UNSUPPORTED_BUILD, readiness.state)
}
```

- [ ] **Step 2: Run focused test and verify failure**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --no-daemon
```

Expected: FAIL because `device_connected` maps to `ENV_BLOCKER`, ambiguous package maps to `NEEDS_INSTALL`, and sidekick errors are not classified through bridge failure rules.

- [ ] **Step 3: Update readinessForDoctorCheck**

Replace `readinessForDoctorCheck` with:

```kotlin
internal fun readinessForDoctorCheck(name: String, message: String?, fix: String): FirstRunReadiness = when (name) {
    "android_project_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "Android project root was not found.",
        fix = fix,
    )
    "fixthis_project_metadata_found" -> {
        val raw = message.orEmpty()
        if (raw.contains("Multiple Android applicationId", ignoreCase = true) ||
            raw.contains("Pass --package", ignoreCase = true)
        ) {
            FirstRunReadinessCatalog.configRecoverable(
                cause = raw.ifBlank { "FixThis could not choose a unique Android applicationId." },
                details = mapOf("check" to name),
            )
        } else {
            FirstRunReadinessCatalog.needsInstall(
                cause = message ?: "FixThis project metadata was not found.",
            )
        }
    }
    "adb_found" -> FirstRunReadinessCatalog.envBlocker(
        cause = message ?: "ADB is unavailable.",
        fix = fix,
    )
    "device_connected" -> FirstRunReadinessCatalog.deviceBlocked(
        cause = message ?: "No ready Android device or emulator is connected.",
        fix = fix,
    )
    "sidekick_session_found" -> {
        val classified = classifyBridgeFailure(message)
        if (classified.state == io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState.UNKNOWN_ERROR) {
            FirstRunReadinessCatalog.needsAppLaunch(
                cause = message ?: "FixThis sidekick session was not found.",
            )
        } else {
            classified
        }
    }
    else -> FirstRunReadinessFailureCatalog.unknown(
        cause = "Doctor check failed: $name",
        details = mapOf("check" to name),
    )
}
```

Add imports:

```kotlin
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import io.github.beyondwin.fixthis.cli.readiness.classifyBridgeFailure
```

Then replace the fully-qualified enum reference in the snippet with `FirstRunReadinessState.UNKNOWN_ERROR`.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit doctor readiness classification**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt
git commit -m "feat(cli): classify doctor first-run readiness"
```

---

## Task 6: Align Console Connection Readiness With First-Run States

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionRoutesTest.kt`

- [ ] **Step 1: Add failing service tests**

In `ConsoleConnectionServiceTest.noReadyDevicesMapsToCheckPhone`, change the readiness assertion:

```kotlin
assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, status.readiness?.state)
```

Add this test:

```kotlin
@Test
fun multipleReadyDevicesMapsToDeviceBlockedChooseDeviceReadiness() = runBlocking {
    val service = connectionService(
        devices = listOf(
            AdbDevice("device-1", "device"),
            AdbDevice("device-2", "device"),
        ),
    )

    val status = service.connectionStatus(session())

    assertEquals(ConsoleConnectionState.CHOOSE_DEVICE, status.state)
    assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, status.readiness?.state)
    assertTrue(status.readiness?.nextAction.orEmpty().contains("Choose"))
}
```

- [ ] **Step 2: Add failing route readiness test**

Append this test to `ConsoleConnectionRoutesTest`:

```kotlin
@Test
fun connectionApiMapsUnauthorizedDeviceToDeviceBlockedReadiness() {
    val bridge = FakeFixThisBridge(
        devicesOverride = listOf(AdbDevice("unauthorized-device", "unauthorized")),
    )
    val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
    val server = FeedbackConsoleServer(service).also { it.start() }
    try {
        val body = ConsoleHttpTestClient(server.url).get("/api/connection")
        val json = fixThisJson.parseToJsonElement(body).jsonObject
        val readiness = json.getValue("readiness").jsonObject

        assertEquals("CHECK_PHONE", json.getValue("state").jsonPrimitive.content)
        assertEquals("DEVICE_BLOCKED", readiness.getValue("state").jsonPrimitive.content)
    } finally {
        server.stop()
    }
}
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionServiceTest" --tests "*ConsoleConnectionRoutesTest" --no-daemon
```

Expected: FAIL because console device blockers still use `ENV_BLOCKER`.

- [ ] **Step 4: Update ConsoleConnectionService mappings**

In `ConsoleConnectionService.connectionStatus`, replace the no-ready-device readiness block with:

```kotlin
readiness = FirstRunReadinessCatalog.deviceBlocked(
    cause = "No ready Android device or emulator is connected.",
    fix = "Start an emulator or connect and authorize a device.",
    details = mapOf("deviceState" to (unavailable?.state ?: "none")),
),
```

Replace the multi-device readiness block with:

```kotlin
readiness = FirstRunReadinessCatalog.deviceBlocked(
    cause = "More than one ready Android device is connected.",
    fix = "Choose a device in the feedback console.",
    details = mapOf("readyDeviceCount" to readyDevices.size.toString()),
),
```

Replace the selected-invalid readiness block with:

```kotlin
readiness = FirstRunReadinessCatalog.deviceBlocked(
    cause = "The selected Android device is not ready.",
    fix = "Select a connected device or fix `adb devices`.",
    details = mapOf("deviceState" to (selectedDevice?.state ?: "missing")),
),
```

Leave device enumeration exceptions as `ENV_BLOCKER`, because ADB itself failed.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionServiceTest" --tests "*ConsoleConnectionRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit console readiness mapping**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleConnectionRoutesTest.kt
git commit -m "feat(console): align connection readiness states"
```

---

## Task 7: Extend First-Run Smoke To Prove Sent Feedback Is Readable

**Files:**
- Create: `scripts/first-run-smoke-contract-test.mjs`
- Modify: `scripts/first-run-smoke.mjs`
- Modify: `package.json`
- Modify: `scripts/verify-ci-local.sh`

- [ ] **Step 1: Write a script contract test**

Create `scripts/first-run-smoke-contract-test.mjs`:

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);

test('first-run smoke verifies sent feedback after Save to MCP', () => {
  const source = readFileSync(resolve(root, 'scripts/first-run-smoke.mjs'), 'utf8');
  assert.match(source, /\/api\/agent-handoffs/);
  assert.match(source, /\/api\/session/);
  assert.match(source, /delivery/);
  assert.match(source, /sent/);
  assert.match(source, /Make the primary button label clearer/);
});
```

- [ ] **Step 2: Run contract test and verify failure**

Run:

```bash
node --test scripts/first-run-smoke-contract-test.mjs
```

Expected: FAIL because `first-run-smoke.mjs` does not yet read the session after Save to MCP.

- [ ] **Step 3: Extend the smoke script**

In `scripts/first-run-smoke.mjs`, after `await handoffResponse;`, insert:

```js
    const sessionAfterHandoff = await page.evaluate(async () => {
      const response = await fetch('/api/session');
      if (!response.ok) throw new Error(`session read failed: ${response.status}`);
      return response.json();
    });
    assert.equal(sessionAfterHandoff.items.length, 1);
    assert.equal(sessionAfterHandoff.items[0].delivery, 'sent');
    assert.match(sessionAfterHandoff.items[0].comment, /primary button label/);
```

Keep the existing prompt-readiness and request-log assertions.

- [ ] **Step 4: Verify the fake server already supports session reads**

Run:

```bash
rg -n "url.pathname === '/api/session'" scripts/console-fixture/fakeBridgeServer.mjs
```

Expected: one handler for `GET /api/session` is present.

- [ ] **Step 5: Add npm script**

Modify `package.json`:

```json
"first-run:smoke:test": "node --test scripts/first-run-smoke-contract-test.mjs",
```

Place it near `first-run:smoke`.

- [ ] **Step 6: Add contract test to fast verification**

Modify `scripts/verify-ci-local.sh` after `npm run docs:agent-bootstrap:test`:

```bash
run_step "npm run first-run:smoke:test"
```

Do not add `npm run first-run:smoke` to fast verification; it remains release-time because it launches Playwright.

- [ ] **Step 7: Run focused verification**

Run:

```bash
npm run first-run:smoke:test
npm run first-run:smoke
```

Expected: both PASS.

- [ ] **Step 8: Commit first-run smoke proof**

```bash
git add scripts/first-run-smoke-contract-test.mjs scripts/first-run-smoke.mjs package.json scripts/verify-ci-local.sh
git commit -m "test: verify first-run handoff readability"
```

---

## Task 8: Make Release Readiness Prove The v0.5 Onboarding Claim

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`

- [ ] **Step 1: Add failing release-readiness rules**

In `scripts/check-release-readiness.mjs`, after rule `R2.readiness-release-process`, add:

```js
requireIncludes(
  'R2b.v05-trustworthy-onboarding-claim',
  'docs/contributing/release-readiness.md',
  'v0.5 Trustworthy Onboarding',
);
requireIncludes(
  'R2c.v05-readme-first-agent-bootstrap',
  'docs/contributing/release-readiness.md',
  'README-first Claude Code / Codex bootstrap',
);
requireIncludes(
  'R2d.v05-first-run-smoke',
  'docs/contributing/release-readiness.md',
  'npm run first-run:smoke',
);
```

- [ ] **Step 2: Run release readiness check and verify failure**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: FAIL because the release readiness doc does not yet name the v0.5 onboarding claim or smoke command.

- [ ] **Step 3: Add v0.5 section to release readiness**

In `docs/contributing/release-readiness.md`, after "Supported Install Paths Today", add:

````markdown
## v0.5 Trustworthy Onboarding Claim

v0.5 may claim README-first Claude Code / Codex bootstrap only when the release
issue includes evidence for this path:

```text
README agent block
-> fixthis install-agent --project-dir . --target all
-> fixthis doctor --project-dir . --json
-> restart Claude Code or Codex when MCP config changed
-> fixthis_open_feedback_console
-> Save to MCP
-> agent-readable sent feedback
```

Required evidence before tagging:

- `npm run docs:agent-bootstrap:test`
- `bash scripts/check-docs-cli-surface.sh`
- `npm run first-run:smoke`
- `npm run first-run:smoke:test`
- `./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --no-daemon`
- `./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionServiceTest" --tests "*ConsoleConnectionRoutesTest" --no-daemon`
````

- [ ] **Step 4: Add source release checklist item**

In "Required Before Next Source Release", add:

```markdown
- [ ] v0.5 Trustworthy Onboarding evidence is captured if the release notes
      claim README-first Claude Code / Codex bootstrap.
```

- [ ] **Step 5: Run release readiness check**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit release readiness update**

```bash
git add docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs: gate v0.5 onboarding release claim"
```

---

## Task 9: Final Verification Pass

**Files:**
- Verify all files touched by Tasks 1-8.

- [ ] **Step 1: Run docs and Node fast checks**

Run:

```bash
npm run docs:agent-bootstrap:test
npm run first-run:smoke:test
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
node scripts/check-release-readiness.mjs
node scripts/build-console-assets.mjs --check
npm run console:test:all
```

Expected: PASS.

- [ ] **Step 2: Run focused Gradle checks**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest" --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --tests "*InitAgentCommandTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionServiceTest" --tests "*ConsoleConnectionRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run release-time first-run smoke**

Run:

```bash
npm run first-run:smoke
```

Expected: PASS with `First-run smoke passed.`

- [ ] **Step 4: Run changed-only local CI**

Run:

```bash
npm run ci:local:changed
```

Expected: PASS.

- [ ] **Step 5: Inspect diff and whitespace**

Run:

```bash
git diff --stat HEAD~8..HEAD
git diff --check
git status --short
```

Expected:

- `git diff --check` prints no output.
- `git status --short` is clean after the final commit.

- [ ] **Step 6: Commit final verification note if docs changed during verification**

If verification required release-readiness wording changes, commit them:

```bash
git add docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs: finalize v0.5 onboarding verification"
```

If no files changed, do not create an empty commit.

## Plan Self-Review

- Spec coverage:
  - README-first agent bootstrap: Tasks 1 and 2.
  - Setup artifacts and report output: Tasks 3 and 4.
  - Doctor readiness: Task 5.
  - Console readiness: Task 6.
  - Golden path smoke: Task 7.
  - Release confidence gates: Task 8.
  - Final verification: Task 9.
- Scope control:
  - No task adds release runtime support.
  - No task expands Android UI stack support.
  - No task renames persisted MCP JSON compatibility fields.
  - No task changes bridge protocol.
- Type consistency:
  - Readiness model uses existing `FirstRunReadiness`, `FirstRunReadinessState`, `FirstRunReadinessCatalog`, and `FirstRunReadinessFailureCatalog`.
  - Console readiness remains serialized through existing `ConsoleConnectionStatus.readiness`.
  - Setup artifacts stay under `.fixthis/` and do not alter feedback session schema.
