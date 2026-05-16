# Agent Setup Handoff Schema

`fixthis install-agent --agent` writes `.fixthis/agent-setup.json` and `.fixthis/agent-setup.md`. The JSON file is the canonical contract; the Markdown file is a human-readable rendering of the same data.

## Schema (v1.0)

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | Currently `"1.0"`. Bumped only on incompatible changes. |
| `state.packageName` | string | Detected Android application id. |
| `state.projectRoot` | string | Absolute path to the Android project root. |
| `state.detectedAt` | RFC 3339 timestamp | When the file was generated. |
| `next` | string[] | Ordered list of runnable commands the agent should attempt next. Each entry MUST be a valid shell command (lines starting with `#` are comments). |
| `recovery` | map<string,string> | Keyed by failure-mode id; value is a one-line remediation. |

## Recovery Keys

Closed set:
- `no-android-context`
- `no-app-module`
- `release-only-variant`
- `view-system-mixed`
- `missing-application-id`

## Stability

`next[0]` is guaranteed runnable as a shell command. Additional `next` entries may include comments prefixed with `#`.
