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
