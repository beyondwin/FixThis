# Agent Setup Handoff Schema

`fixthis install-agent` writes `.fixthis/project.json`,
`.fixthis/agent-setup.json`, `.fixthis/agent-setup.md`, and
`.fixthis/mcp.json.template`. `fixthis init --agent` writes the same handoff
files when the Gradle plugin is already applied. The JSON file is the canonical
contract; the Markdown file is a human-readable rendering of the same data.

## Schema (v1.0)

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | Currently `"1.0"`. Bumped only on incompatible changes. |
| `state.packageName` | string | Detected Android application id. |
| `state.projectRoot` | string | Absolute path to the Android project root. |
| `state.detectedAt` | RFC 3339 timestamp | When the file was generated. |
| `readiness` | object | First-run readiness object using the same shape as `fixthis doctor --json` and the feedback console. |
| `next` | string[] | Ordered list of runnable commands the agent should attempt next. Each entry MUST be a valid shell command (lines starting with `#` are comments). |
| `restartGuidance` | string | Human-readable reminder to restart Claude Code / Codex after MCP config changes. |
| `recovery` | object | Keyed by failure-mode id; values are one-line remediation strings plus optional nested groups. |
| `recovery.readinessRecovery` | map<string,string> | Optional readiness-state-specific remediation strings. |

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

## Stability

`next[0]` is guaranteed runnable as a shell command. Additional `next` entries may include comments prefixed with `#`.
