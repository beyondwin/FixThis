# Agent-First Setup Contract Design

Date: 2026-06-25

## Goal

Make FixThis setup something an AI coding agent can complete and verify in one
contracted flow from an external Android repository.

The user should be able to ask an agent to "set up FixThis and verify it" and
the agent should not need to stitch together human docs, shell output, and
implicit recovery rules. The CLI should return a stable setup-and-verification
report that separates:

- actions the agent can perform now;
- actions that require the user, such as unlocking a device or restarting the
  MCP client;
- the exact readiness state that blocks opening FixThis Studio.

## Current Context

FixThis already has the pieces:

- `fixthis install-agent --project-dir . --target all --json` applies the
  Gradle plugin, writes MCP config, writes `.fixthis/agent-setup.*`, and emits
  `readiness`, `nextAction`, and `restartRequired`.
- `fixthis doctor --project-dir . --json` diagnoses the project, ADB, device,
  package resolution, and sidekick session, with a top-level readiness result.
- `.fixthis/agent-setup.json` stores a local agent handoff.
- Docs already tell agents to run `install-agent`, then `doctor`, then restart
  Claude Code or Codex before `fixthis_open_feedback_console`.

The gap is that this is still a multi-command protocol. Agents must infer how
to merge setup results, doctor results, restart requirements, and user-only
blockers. That makes the flow fragile even when each individual command is
correct.

## Approved Direction

Do not add a beginner-oriented tutorial command as the primary solution.
Modern users increasingly ask an AI agent to perform setup, so the product
surface should optimize for agent execution.

Primary command:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

`--verify` turns `install-agent` into a one-shot agent setup contract:

1. perform the existing install-agent setup;
2. run the existing doctor checks through a reusable service, not a shell-out;
3. emit one machine-readable report that includes setup, verification,
   readiness, next actions, restart requirements, and user-action requirements.

This keeps the existing agent-first command while avoiding a second, overlapping
`fixthis onboard` mental model.

## Stage Review

### Stage 1: Desktop Tool Availability

Current docs make agents choose Homebrew, npm, or the release installer before
running `install-agent`. This remains outside the CLI command because the CLI
cannot install itself reliably.

Improvement:

- Keep install-method snippets in `agent-install-snippet.md`.
- Require the one-shot contract to start after `fixthis version --json` works.
- Document that package-manager installation is the agent's preflight, while
  `install-agent --verify --json` is the repo setup contract.

Defect to avoid:

- Do not make `install-agent --verify` attempt Homebrew/npm/curl installation.
  That would add network side effects and platform-specific failure modes to a
  command whose current responsibility is Android project setup.

### Stage 2: Project and Package Detection

Current state:

- `BridgeClient.resolvePackageName` can read `.fixthis/project.json` or Gradle
  files.
- Ambiguous `applicationId` values map to `CONFIG_RECOVERABLE`.
- `install-agent` preflights Gradle plugin application before writing agent
  config, preventing config writes for the wrong package.

Improvement:

- Preserve the preflight ordering.
- In the unified report, expose a structured package resolution section:
  `packageName`, `projectRoot`, `packageResolution`, and optional candidates
  when ambiguity is available.
- Classify ambiguous package detection as `agentAction` because the agent can
  inspect Gradle files and rerun with `--package <id>`.

Defect to avoid:

- Do not guess among multiple installable application IDs. Agent automation
  must fail clearly rather than silently wiring the wrong app.

### Stage 3: Gradle Patch and Agent Config Writes

Current state:

- `SetupService.installAgent` applies the Gradle plugin, writes MCP configs,
  and writes `.fixthis/agent-setup.*`.
- `install-agent --json` already tracks `applied`, `skipped`, `errors`,
  `readiness`, `next`, and `restartRequired`.
- Codex global config can be skipped outside Android context and reported as
  partial.

Improvement:

- Keep setup results as a nested `setup` object in the unified report.
- Add `mcpConfigChanged` as a normalized boolean derived from applied Claude,
  Codex, or Cursor writes.
- Keep `restartRequired` for compatibility, but attach a structured user
  action when the current agent cannot continue with newly written MCP config.

Defect to avoid:

- Do not let JSON mode write human warnings to stdout. The current code already
  routes warnings to stderr; the unified report must preserve that contract.

### Stage 4: Verification

Current state:

- `DoctorCommand.run()` owns both check execution and rendering.
- Tests already validate the JSON shape and readiness mapping.

Improvement:

- Extract doctor execution into a reusable `DoctorService` that returns
  `DoctorReport`.
- `DoctorCommand` becomes a renderer/adapter over the service.
- `install-agent --verify --json` calls the service directly after setup and
  embeds the rendered data as a `verification` object.
- `install-agent --dry-run --verify --json` does not run doctor as if setup had
  changed the project. It emits the planned setup writes and marks
  verification as skipped with reason `dry_run_no_side_effects`.

Defect to avoid:

- Do not shell out to `fixthis doctor --json`. Shelling out would make tests
  slower, reintroduce stdout/stderr contamination risk, and hide typed
  readiness data behind string parsing.

### Stage 5: Readiness Merge

Current state:

- `install-agent` can return setup-level recoverable readiness.
- `doctor` returns the first failed check readiness, or `READY`.

Improvement:

- Define precedence explicitly:
  1. setup errors block verification and become top-level readiness;
  2. setup skipped targets become top-level readiness unless verification is a
     harder blocker;
  3. doctor readiness wins when setup completed and verification ran;
  4. all-green doctor becomes top-level `READY`.
- Include `readinessSource` on top-level readiness: `setup`, `verification`, or
  `merged`.
- Treat app readiness and agent progress as separate signals. `readiness.state`
  can be `READY` while `ok=false` if a blocking action such as MCP client
  restart remains.

Defect to avoid:

- Do not report `READY` just because setup writes succeeded. Ready means the
  debug app and FixThis sidekick are connected enough for the console/handoff
  path to proceed.

### Stage 6: Next Actions for Agents

Current state:

- `next` is a list of strings.
- `nextAction` is the preferred first command/action.
- `restartRequired` is a boolean.

Improvement:

- Keep `next` and `nextAction` for compatibility.
- Add structured `actions[]` with:
  - `id`
  - `actor`: `agent`, `user`, or `agent_after_restart`
  - `kind`: `command`, `mcp_tool`, `manual`, or `inspect`
  - `command` or `tool`
  - `reason`
  - `blocksProgress`
- Add `requiresUserAction` boolean and optional `userActionReason`.
- Add `readyForMcpTooling` boolean. It is true only when app readiness is
  `READY` and no current-client restart blocks MCP tool calls.

Examples:

- `DEVICE_BLOCKED` becomes user action: start emulator, connect device, or
  unlock/authorize.
- `CONFIG_RECOVERABLE` from ambiguous package becomes agent action: inspect
  Gradle and rerun with `--package`.
- `restartRequired` after MCP config writes becomes user action for the
  currently running Claude Code/Codex process, with follow-up
  `agent_after_restart` action `fixthis_open_feedback_console`.
- If multiple actions block progress, order them by what unblocks the next
  agent step: restart current MCP client first when the agent cannot load the
  new server, then device/app actions, then rerun verification.

Defect to avoid:

- Do not tell the current agent to call `fixthis_open_feedback_console` before
  restart when the MCP config was just written for that same agent.

### Stage 7: Exit Codes

Current exit code contract:

- `0` OK
- `1` PARTIAL
- `2` USAGE_ERROR
- `3` ENV_BLOCKER
- `4` INTERNAL_ERROR

Design:

- Exit `0` only when setup succeeded, verification readiness is `READY`, and no
  blocking action remains for the current agent.
- Exit `1` when setup applied but readiness requires a recoverable agent or
  user action, including MCP client restart.
- Exit `3` only for environment-level blockers surfaced before a useful JSON
  setup report can be produced, such as missing Android SDK in run-like flows.
- Exit `4` for unexpected setup or write failures.
- In `--json` mode, print the JSON report to stdout before throwing a
  non-zero `CliktError` whenever the report exists.

Defect to avoid:

- Do not make agents infer readiness from exit code alone. The JSON readiness
  state is authoritative.

## Proposed JSON Shape

The report extends the existing install-agent JSON rather than replacing it.
Existing top-level fields remain valid.

```json
{
  "schemaVersion": "1.1",
  "ok": false,
  "readiness": {
    "state": "DEVICE_BLOCKED",
    "cause": "No connected Android device or emulator found",
    "verify": "fixthis doctor --project-dir /repo --json",
    "fix": "Start an emulator or connect a device, then run `adb devices`.",
    "nextAction": "Start an emulator or connect a device, then run `adb devices`."
  },
  "readinessSource": "verification",
  "nextAction": "Start an emulator or connect a device, then run `adb devices`.",
  "next": [
    "Start an emulator or connect a device, then run `adb devices`.",
    "fixthis install-agent --project-dir /repo --target all --verify --json"
  ],
  "restartRequired": true,
  "requiresUserAction": true,
  "userActionReason": "DEVICE_BLOCKED",
  "readyForMcpTooling": false,
  "actions": [
    {
      "id": "start-device",
      "actor": "user",
      "kind": "manual",
      "reason": "A connected unlocked Android device or emulator is required.",
      "blocksProgress": true
    },
    {
      "id": "rerun-verify",
      "actor": "agent",
      "kind": "command",
      "command": "fixthis install-agent --project-dir /repo --target all --verify --json",
      "reason": "Verify setup after the device is ready.",
      "blocksProgress": false
    }
  ],
  "setup": {
    "applied": [],
    "skipped": [],
    "errors": []
  },
  "verification": {
    "ok": false,
    "packageName": "com.example.app",
    "checks": []
  }
}
```

## Architecture

Add small, testable components inside `:fixthis-cli`:

- `DoctorService`: executes current doctor checks and returns `DoctorReport`.
- `AgentSetupVerificationService`: coordinates setup result, optional doctor
  verification, readiness merge, and action derivation.
- `AgentSetupVerificationReport`: serializable unified report.
- `AgentSetupAction`: structured next action model.

Adapt existing commands:

- `DoctorCommand` delegates check execution to `DoctorService`.
- `InstallAgentCommand` gains `--verify`.
- `InstallAgentCommand --json --verify` renders the unified report.
- Non-JSON `--verify` may render a concise summary, but the primary contract is
  JSON for agents.

## Non-Goals

- Do not support release builds.
- Do not add cloud setup, remote MCP hosting, or ChatGPT connector automation.
- Do not rename persisted MCP feedback fields.
- Do not change bridge protocol semantics.
- Do not make the CLI install or upgrade itself.
- Do not replace `fixthis doctor`; keep it as the focused diagnostic command.
- Do not add a separate `fixthis onboard` command in this phase.

## Testing

Focused tests:

- `DoctorServiceTest`
  - preserves current doctor success and failure classification.
  - returns the same `DoctorReport` shape that `DoctorCommand` renders.
- `AgentSetupVerificationServiceTest`
  - setup success plus doctor ready returns `READY`, exit `0`, and
    `fixthis_open_feedback_console` as an immediate MCP action only when no
    restart is required.
  - setup success plus doctor ready plus newly written MCP config returns
    `READY`, `ok=false`, exit `1`, `readyForMcpTooling=false`, and a blocking
    restart action before the after-restart MCP tool action.
  - setup success plus device blocker returns `DEVICE_BLOCKED`,
    `requiresUserAction=true`, and no premature MCP tool action.
  - ambiguous application ID returns an agent-rerunnable action with
    `--package <applicationId>` guidance.
  - setup error blocks verification and becomes top-level readiness.
  - skipped Codex global write stays partial and explains `--allow-global` or
    local targets.
  - dry-run plus verify skips doctor and reports
    `verification.skippedReason=dry_run_no_side_effects`.
- `InstallAgentJsonReportTest`
  - existing shape remains valid.
  - `--verify` shape includes `setup`, `verification`, `actions`,
    `readinessSource`, and `requiresUserAction`.
- `MainCommandTest`
  - help includes `install-agent` and its `--verify` option.
- Documentation checks
  - README and getting-started docs use
    `fixthis install-agent --project-dir . --target all --verify --json` as the
    agent-first verification path.
  - `docs/reference/cli.md` documents the new flag and JSON fields.
  - `docs/getting-started/agent-install-snippet.md` tells agents to rely on
    structured actions rather than guessing from prose.

Verification commands for implementation:

```bash
./gradlew :fixthis-cli:test --no-daemon
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

Android runtime proof is not required for this design because the feature
orchestrates existing CLI setup and doctor checks without changing sidekick,
bridge protocol, MCP feedback persistence, or console runtime behavior.

## Acceptance Criteria

- An agent can run one command after installing the FixThis CLI:
  `fixthis install-agent --project-dir . --target all --verify --json`.
- The command applies the existing setup path and verifies through doctor
  checks in the same invocation when setup reaches a verifiable state.
- JSON mode emits one parseable stdout document before any non-zero exit.
- The report makes the authoritative readiness state explicit.
- The report distinguishes agent actions from user actions.
- The report prevents agents from trying MCP tools before a required MCP client
  restart.
- `--dry-run --verify --json` never implies that planned setup writes were
  actually verified.
- Existing `install-agent --json` consumers without `--verify` remain
  compatible.
- Existing `doctor --json` behavior remains compatible.
- Docs teach the agent-first one-shot contract as the primary external-app
  setup path.

## Risks

- `DoctorCommand` extraction could subtly change current doctor behavior.
  Mitigation: move logic behind tests first and compare rendered JSON.
- Structured actions could grow into a second workflow language.
  Mitigation: keep actions small and derived directly from readiness states.
- Restart handling may be agent-specific.
  Mitigation: model restart as user action for Claude Code/Codex MCP reload and
  keep Copy Prompt paths outside this setup contract.
- Partial setup plus failed verification can be ambiguous.
  Mitigation: document readiness precedence and include both nested setup and
  verification results.
