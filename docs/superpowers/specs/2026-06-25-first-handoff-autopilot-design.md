# First Handoff Autopilot Design

Date: 2026-06-25

## Goal

Make the agent-first setup path continue cleanly from setup verification to
the first MCP-readable feedback item.

The user should be able to ask a coding agent to install FixThis in an external
Jetpack Compose debug app, verify the setup report, stop for real user blockers,
and open FixThis Studio only when MCP tooling is actually ready. The first
handoff proof should then confirm that at least one sent feedback item can be
read through MCP.

## Current Context

Recent work added the one-shot setup command:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

That command now emits a schema `1.1` verification report with top-level
readiness, `actions[]`, `requiresUserAction`, `readyForMcpTooling`, nested
setup results, and nested doctor verification results.

The remaining gap is not another setup command. It is the contract between that
verification report and the first real handoff:

- agents need a strict rule for when to run a command, call an MCP tool, stop
  for a user, or wait until restart;
- docs need to distinguish `.fixthis/agent-setup.json` handoff files from the
  `install-agent --verify --json` stdout verification report;
- smoke and release evidence should prove that report actions lead to one
  MCP-readable sent feedback item instead of only proving that setup artifacts
  were written.

## Scope

In scope:

- tighten the `install-agent --verify --json` action contract;
- document the difference between setup handoff files and verify stdout;
- connect the verify report contract to `agent-loop:smoke`;
- make release evidence fail when the first-handoff contract is not covered;
- preserve existing debug-only, Compose-only, local-first boundaries.

Out of scope:

- automatic Homebrew, npm, or curl installation from inside the CLI;
- release build support;
- cloud MCP hosting or remote storage;
- ChatGPT connector automation;
- persisted feedback JSON field renames;
- bridge protocol changes that are not needed for the first handoff.

## Contract

`AgentSetupVerificationService` remains the single place that turns setup and
doctor state into agent actions. Each action in `actions[]` must be executable
or explain exactly why execution must stop.

Allowed `actor` values:

- `agent`: the current agent may perform this action now.
- `user`: the current agent must stop and report the blocker.
- `agent_after_restart`: a future agent process may perform this action after
  the user restarts Claude Code, Codex, or another MCP client.

Allowed `kind` values:

- `command`: run the exact shell command in `command`.
- `mcp_tool`: call the exact MCP tool in `tool`.
- `manual`: report the `reason` to the user and do not continue automatically.

Action rules:

- `blocksProgress=true` means no later action may be attempted by the current
  agent until this action is resolved.
- A `command` action must include `command`.
- An `mcp_tool` action must include `tool`.
- A `manual` action must include a user-facing `reason`.
- `readyForMcpTooling=true` is required before the current agent may call
  `fixthis_open_feedback_console`.
- If `readiness.state=READY` but MCP config was changed, the report must keep
  `readyForMcpTooling=false` and emit `agent_after_restart` for
  `fixthis_open_feedback_console`.

`next[]` and `nextAction` stay for compatibility, but `actions[]` is the
source of truth for agents.

## Flow

The intended agent flow is:

1. Run `fixthis install-agent --project-dir . --target all --verify --json`.
2. Parse the single stdout JSON document.
3. If `requiresUserAction=true`, report the first blocking user action and
   stop.
4. If the first non-blocked action is `kind=command`, run it and rerun
   `install-agent --verify --json`.
5. If the only console-opening action is `actor=agent_after_restart`, tell the
   user to restart the MCP client before using FixThis MCP tools.
6. If `readyForMcpTooling=true`, call `fixthis_open_feedback_console`.
7. Complete the browser feedback workflow.
8. Confirm that MCP can read at least one sent feedback item.

## Failure Handling

`DEVICE_BLOCKED` is a user blocker. The report should emit a `manual` user
action first, then a rerun-verify command after the user connects, unlocks, or
authorizes a device.

`NEEDS_INSTALL`, `CONFIG_RECOVERABLE`, and `NEEDS_APP_LAUNCH` are recoverable
agent actions when the next action is a concrete command. The current agent may
run the command and rerun verify.

`UNSUPPORTED_BUILD` is blocking. The report should not imply that the console
can be opened.

Dry runs never claim runtime readiness. `--dry-run --verify --json` must report
`verification.skippedReason=dry_run_no_side_effects`,
`readyForMcpTooling=false`, and a rerun command without `--dry-run`.

Restart-required reports separate app readiness from agent progress. The app
can be `READY`, while the current agent still cannot call MCP tools until the
user restarts the MCP client.

## Documentation

`docs/reference/agent-setup-schema.md` should split two related but distinct
contracts:

- `.fixthis/agent-setup.json`: local setup handoff file, schema `1.0`, written
  into the project for later inspection.
- `install-agent --verify --json`: stdout verification report, schema `1.1`,
  used by the current agent to decide the next action.

README and getting-started docs should continue to show
`fixthis install-agent --project-dir . --target all --verify --json` as the
agent-first path and should tell agents to use `readiness.state` and
`actions[]` as the source of truth.

## Evidence And Tests

Focused Kotlin tests:

- `AgentSetupVerificationServiceTest` covers action ordering, closed
  `actor`/`kind` values, restart-required behavior, device blockers, dry-run
  behavior, and ready-for-MCP behavior.
- `InstallAgentJsonReportTest` pins the schema `1.1` report shape.
- `DoctorCommandTest` and `AgentSetupFilesTest` keep doctor and handoff-file
  compatibility intact.

Node and docs tests:

- `npm run docs:agent-bootstrap:test` verifies agent-facing docs reference the
  correct one-shot command and gating rules.
- `bash scripts/check-docs-cli-surface.sh` keeps documented commands and flags
  aligned with CLI help.
- `npm run agent-loop:smoke:test` verifies first-handoff report fields and the
  verify-report action semantics used by the smoke path.
- `npm run release:gate:test` fails when the first-handoff contract evidence is
  missing from release claims.

Connected evidence:

- `npm run agent-loop:smoke -- --strict` remains the strict Android proof that
  setup can continue to one MCP-readable sent feedback item.
- Non-strict runs may defer Android runtime prerequisites, but must include the
  exact deferred reason and recovery-oriented readiness object.

## Acceptance Criteria

- Agents can decide the next action from `install-agent --verify --json`
  without reading prose docs.
- The current agent never calls `fixthis_open_feedback_console` while
  `readyForMcpTooling=false`.
- Restart-required reports clearly produce an `agent_after_restart` console
  action.
- Dry-run verify reports never imply runtime readiness.
- The setup handoff-file schema and verify stdout schema are documented as
  separate contracts.
- `agent-loop:smoke:test` and release-gate tests cover the first-handoff
  autopilot contract.
- Strict connected proof still uses real Android runtime evidence when
  available and fails rather than passing deferred evidence in strict mode.
