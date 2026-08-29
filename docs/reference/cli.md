# `fixthis` CLI Reference

Exit codes: [`cli-exit-codes.md`](./cli-exit-codes.md).

After `./gradlew :fixthis-cli:installDist`, the binary is
`fixthis-cli/build/install/fixthis/bin/fixthis`.

```text
fixthis <subcommand> [flags]

Subcommands:
  status         ADB / bridge / source-index status
  run            Install, launch, attach, open the console
  doctor         Diagnose ADB / JDK / device / package
  init           Write Claude Code / Codex MCP config
  install-agent  Patch Gradle, write MCP config, write .fixthis handoff
  setup          Print or write MCP config
  mcp            Run the stdio MCP server
  console        Open the local console
  clean          Remove known .fixthis artifact dirs
  version        CLI and bridge protocol versions
```

`fixthis --version` prints the same line as `fixthis version` and exits 0.

`--package` is the Android applicationId. If omitted: `.fixthis/project.json`
`applicationId`, then a unique Gradle `applicationId`. Flavor suffixes count
as extra candidates. Fail if none or more than one. `--project-dir` defaults
to `.`.

## `fixthis status`

```bash
fixthis status [--package <applicationId>] [--project-dir <path>]
```

Non-zero if the bridge cannot be reached.

## `fixthis run`

Installs the debug APK, launches, attaches, opens Studio.

```bash
fixthis run --package io.github.beyondwin.fixthis.sample
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId |
| `--project-dir` | `.` | Project root |
| `--install-task` | `:app:installDebug` | Gradle install task |
| `--timeout-millis` | `30000` | Bridge wait after launch |

If `.fixthis/project.json` has `projectPath` and `variantName` for the
selected applicationId, `run` can derive the install task. An explicit
`--package` for a different app falls back to `:app:installDebug` unless
`--install-task` is set.

## `fixthis doctor`

```bash
fixthis doctor --package io.github.beyondwin.fixthis.sample
fixthis doctor --project-dir . --json
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId |
| `--project-dir` | `.` | Project root |
| `--json` | off | Structured report |

JSON shape: `schemaVersion`, `ok`, `packageName`, `readiness`, `nextAction`,
`checks[]` (`name`, `label`, `status`, optional `message` / `fix` /
`readiness`).

## `fixthis init`

Writes MCP config (same merge as `setup --write`). Use when the Gradle
plugin is already applied.

```bash
fixthis init
fixthis init --package <applicationId>
fixthis init --target codex --dry-run
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId |
| `--project-dir` | `.` | Android project root |
| `--agent` | off | Also write `.fixthis/agent-setup.*` and `mcp.json.template` |
| `--apply-gradle-plugin` | off | Apply `io.github.beyondwin.fixthis.compose` |
| `--plugin-version` | `1.5.0` | Plugin version when applying |
| `--dry-run` | off | Preview writes |
| `--target` | `all` | `claude`, `codex`, `cursor`, `local`, `all` |
| `--server-name` | `fixthis` | MCP server name |
| `--verbose`, `-v` | off | Full stack trace |

Restart the agent after `init`. GitHub Release install:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v1.5.0
```

`./gradlew fixthisSetup` is recovery when generated metadata is missing.
Flavored debug: e.g. `:app:fixthisSetupStagingDebug`.

## `fixthis install-agent`

Applies the published plugin, writes MCP config, writes `.fixthis/` handoff
files.

```bash
fixthis install-agent --project-dir . --target all --verify --json
fixthis install-agent --project-dir . --target all --dry-run
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId; unique Gradle scan if omitted |
| `--project-dir` | `.` | Android project root |
| `--dry-run` | off | Preview Gradle patch and config writes |
| `--target` | `all` | `claude`, `codex`, `cursor`, `local`, `all` |
| `--server-name` | `fixthis` | MCP server name |
| `--skip-gradle-plugin` | off | Do not patch the app module |
| `--plugin-version` | `1.5.0` | Plugin version |
| `--allow-global` | off | Allow `~/.codex/config.toml` outside an Android project |
| `--json` | off | JSON report |
| `--verify` | off | Run doctor after setup; with `--dry-run` reports `verification.skippedReason=dry_run_no_side_effects` |
| `--verbose`, `-v` | off | Full stack trace |

`claude` → `.claude/settings.json`. `codex` → `~/.codex/config.toml`.
`cursor` → `.cursor/mcp.json`. `local` = claude+cursor. `all` = all three.
`--target all` outside an Android project skips Codex unless
`--allow-global`. Writes are staged then moved atomically.

Exit codes: [`cli-exit-codes.md`](cli-exit-codes.md). Without `--verify`,
next step is `fixthis doctor --project-dir . --json`.

With `--verify --json`, `schemaVersion` is `"1.1"`. Trust `readiness.state`
and `actions[]`. Do not call `fixthis_open_feedback_console` until
`readyForMcpTooling` is true, or until `agent_after_restart` after restart.
File vs stdout contracts: [agent-setup-schema.md](agent-setup-schema.md).

## `fixthis setup`

Prints MCP config. `--write` merges it into agent files.

```bash
fixthis setup --package <applicationId>
fixthis setup --package <applicationId> --write --target all
fixthis setup --package <applicationId> --write --target codex --dry-run
```

| Flag | Default | Description |
|------|---------|-------------|
| `--package` | — | Android applicationId |
| `--project-dir` | `.` | Project root |
| `--write` | off | Write agent config |
| `--dry-run` | off | Privacy-preserving diff, 4 KiB cap |
| `--full-diff` | off | Disable the 4 KiB cap (may leak surrounding config) |
| `--target` | `all` | `claude`, `codex`, `cursor`, `local`, `all` |
| `--server-name` | `fixthis` | MCP server name |
| `--verbose`, `-v` | off | Full stack trace |

No `chatgpt` target. ChatGPT has no file-based MCP config. Use Copy Prompt.
See [Connect your agent](../getting-started/connect-your-agent.md).

Sample shortcut: `./scripts/bootstrap-mcp.sh --sample`. Restart after
`--write`.

## `fixthis mcp`

Stdio JSON-RPC server. Agents start this; you rarely run it by hand.

```bash
fixthis mcp --package <applicationId>
```

Tools: [mcp-tools.md](mcp-tools.md).

## `fixthis console`

Opens Studio without `fixthis run`.

```bash
fixthis console --package <applicationId>
```

`--console-assets-dir` is for contributors (live HTML/CSS/JS). Kotlin still
runs from the JAR. After Kotlin edits: `bash scripts/restart-console.sh`.
Staleness: [bridge-protocol.md](bridge-protocol.md).

## `fixthis clean`

Removes `feedback-sessions/`, `preview-cache/`, `artifacts/`,
`smoke-reports/`. Keeps `.fixthis/project.json` and unknown entries.
Does not remove `runtime-evidence/`.

```bash
fixthis clean --project-dir . --dry-run
fixthis clean --project-dir . --older-than-days 7
```

## `fixthis version`

```bash
fixthis version
# fixthis 1.5.0 (bridge protocol v1.3)

fixthis version --json
# {"cliVersion":"1.5.0","bridgeProtocolVersion":"1.3"}
```

## Exit codes

Shared contract in [`cli-exit-codes.md`](cli-exit-codes.md): `0` success,
`1` partial, `2` usage, `3` env, `4` internal. `doctor` is non-zero on any
failed check. Prefer `doctor --json` for remediation.

## Where commands look for state

1. `--package`
2. `<projectDir>/.fixthis/project.json` `applicationId`
3. Unique Gradle `applicationId` (including suffix combinations)
4. Fail

Gradle-generated metadata may include `projectPath` and `variantName`.

```json
{
  "schemaVersion": "1.0",
  "applicationId": "io.github.beyondwin.fixthis.sample"
}
```

## See also

- [Sample quick start](../getting-started/try-the-sample.md)
- [Add to your app](../getting-started/add-to-your-app.md)
- [Working with agents](../guides/agents.md)
- [MCP tools](mcp-tools.md)
