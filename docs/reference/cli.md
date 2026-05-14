# `fixthis` CLI Reference

The desktop CLI ships in `fixthis-cli/build/install/fixthis/bin/fixthis` after
`./gradlew :fixthis-cli:installDist`. All commands are exposed as
subcommands of `fixthis`.

```text
fixthis <subcommand> [flags]

Subcommands:
  status      Show ADB bridge / package / source-index status
  run         Install the debug app, launch it, attach the bridge, open the console
  doctor      Diagnose ADB / JDK / device / package wiring
  setup       Generate or write MCP config for Claude Code / Codex
  mcp         Run the FixThis MCP server (stdio JSON-RPC, used by agents)
  console     Open the local feedback console without launching anything else
  clean       Remove local FixThis artifact directories
```

`--package` is the Android applicationId of the debug app you are running
FixThis against. If omitted, every subcommand reads
`<projectDir>/.fixthis/project.json` field `applicationId` to discover the
package, and fails with a clear error if neither is provided.

`--project-dir` defaults to `.` (the current working directory).

## `fixthis status`

Reports ADB connectivity, sidekick bridge attach, and source-index
availability.

```bash
fixthis status [--package <applicationId>] [--project-dir <path>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId to connect to. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |

Exit code is non-zero when the bridge can't be reached.

## `fixthis run`

Installs the debug APK, launches the app, attaches the sidekick bridge, and
opens the FixThis Studio console in your default browser. The all-in-one
quick-start command.

```bash
fixthis run --package io.beyondwin.fixthis.sample
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId to launch. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |
| `--install-task` | `:app:installDebug` | Gradle install task to run before launch. |
| `--timeout-millis` | `30000` | Bridge status timeout after launch (ms). |

Use a different `--install-task` if your Gradle project's debug install task
isn't `:app:installDebug` (e.g. flavored builds: `:app:installAcmeDebug`).

## `fixthis doctor`

Diagnoses the local environment: ADB on PATH, JDK toolchain, device reachable,
package installed, source index up to date.

```bash
fixthis doctor --package io.beyondwin.fixthis.sample
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId to diagnose. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |

## `fixthis setup`

Generates MCP config for AI agents. Without `--write`, prints the JSON / TOML
for manual paste. With `--write`, merges into agent settings files.

Fresh contributors should usually run `./scripts/bootstrap-mcp.sh --package
<applicationId>` first. It builds `:fixthis-cli` and `:fixthis-mcp` installDist
and then calls this command with `--write`. Use `fixthis setup` directly when
the distributions are already built, on Windows, or when you need to inspect
the exact generated config.

```bash
# Print only (default)
fixthis setup --package <applicationId>

# Write into Claude Code project-local + Codex user-global
fixthis setup --package <applicationId> --write --target all

# Preview a write
fixthis setup --package <applicationId> --write --target codex --dry-run
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId for the generated MCP config. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |
| `--write` | off | Write MCP config to agent settings files. |
| `--dry-run` | off | With `--write`, print planned writes without modifying files. |
| `--target` | `all` | Agent target: `claude`, `codex`, or `all`. |
| `--server-name` | `fixthis` | MCP server name to write. |
| `--verbose`, `-v` | off | Print the full Java stack trace on failure. Implies the cause chain is rendered too, but skipped by default to keep the terse error readable. |

Targets:

- **`claude`** → project-local `.claude/settings.json` (only affects this project).
- **`codex`** → user-global `~/.codex/config.toml` (affects all Codex sessions).

After `--write`, restart your agent so the new MCP server is picked up.

## `fixthis mcp`

Runs the FixThis MCP server as a stdio JSON-RPC process. **Agents start this
for you** — you don't typically run it by hand. Useful for debugging the MCP
layer.

```bash
fixthis mcp --package <applicationId>
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |

For the tools the server exposes, see [MCP tools reference](mcp-tools.md).

## `fixthis console`

Opens the local feedback console in your default browser without going through
`fixthis run`. Useful when the app is already installed and running, or when
you only want to browse persisted feedback sessions.

```bash
fixthis console --package <applicationId>
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId. |
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |
| `--console-assets-dir` | — | **Contributors only.** Read HTML/CSS/JS from this directory instead of the packaged JAR for live console-UI iteration. |

`--console-assets-dir` reloads HTML/CSS/JS from source on every request, but
the Kotlin server itself runs from the installed JAR. After editing any
Kotlin code in `:fixthis-mcp` or `:fixthis-compose-sidekick`, run
`bash scripts/restart-console.sh`. See
[Bridge protocol](bridge-protocol.md) for the staleness banner that fires
when versions disagree.

## `fixthis clean`

Removes only known local artifact directories:
`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`,
`.fixthis/artifacts/`, `.fixthis/smoke-reports/`.

```bash
# Inspect what would be removed
fixthis clean --project-dir . --dry-run

# Remove only directories not modified in the last 7 days
fixthis clean --project-dir . --older-than-days 7
```

| Flag | Default | Description |
|------|---------|-------------|
| `--project-dir` | `.` | Project root containing `.fixthis/project.json`. |
| `--dry-run` | off | List artifact directories without deleting them. |
| `--older-than-days` | — | Only clean directories last modified more than this many days ago. Must be non-negative. |

The command is symlink-safe and preserves `.fixthis/project.json` and any
unknown `.fixthis` files or directories.

## Exit codes

All subcommands follow standard CLI convention:

- `0` — success
- `1` — generic error (bad usage, missing prerequisite, bridge unreachable)
- `2` — invalid argument (e.g. unknown `--target` value)

`fixthis doctor` returns non-zero when any check fails, so it's safe to wire
into CI as a smoke test.

## Where commands look for state

Every subcommand resolves the package and project root in this order:

1. `--package` flag, if given.
2. `<projectDir>/.fixthis/project.json` field `applicationId`.
3. Fail with a usage error.

`<projectDir>` is `--project-dir` (default `.`) or whatever the agent invoked
the command with.

Minimal local metadata example:

```json
{
  "schemaVersion": "1.0",
  "applicationId": "io.beyondwin.fixthis.sample"
}
```

## See also

- [`docs/getting-started/try-the-sample.md`](../getting-started/try-the-sample.md) — the 5-min Quick Start.
- [`docs/getting-started/add-to-your-app.md`](../getting-started/add-to-your-app.md) — wiring into your own app.
- [`docs/guides/agents.md`](../guides/agents.md) — agent-specific notes.
- [`docs/reference/mcp-tools.md`](mcp-tools.md) — MCP tools the `mcp` subcommand exposes.
- [`docs/reference/bridge-protocol.md`](bridge-protocol.md) — sidekick ↔ console wire protocol.
