# FixThis MCP Bootstrap

Local MCP server for Claude Code and Codex.

Full flags: [CLI reference](docs/reference/cli.md). Path choice:
[docs index](docs/index.md) or [project map](docs/guides/project-map.md).

## Sample

```bash
./scripts/bootstrap-mcp.sh --sample
```

That builds the local CLI/MCP distributions and registers the server:

- Claude Code: project-local `.claude/settings.json`
- Codex: user-global `~/.codex/config.toml`

Restart Claude Code or Codex, then call:

```text
fixthis_open_feedback_console
```

## Your App

```bash
./scripts/bootstrap-mcp.sh --package <applicationId>
```

If the CLI is already installed inside the Android app repo:

```bash
fixthis init
```

`fixthis init` writes Claude Code / Codex MCP config and can infer a unique
`applicationId` from Gradle files when `.fixthis/project.json` is missing.
Preview with `fixthis init --dry-run`.

Install the CLI first if needed:

```bash
brew install beyondwin/tools/fixthis
fixthis init --agent --project-dir . --target codex
```

If Homebrew already has it: `brew update && brew upgrade beyondwin/tools/fixthis`,
then `fixthis --version`.

```bash
npm install -g @beyondwin/fixthis
fixthis init --agent --project-dir . --target codex
```

Without a package manager:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v1.5.0 --init --target codex --project-dir .
```

Use `--target claude` or `--target all` for other clients. For a new external
app, the canonical sequence is:

```bash
fixthis install-agent --project-dir . --target all --verify --json
fixthis doctor --project-dir . --json
```

`install-agent` does Gradle wiring and MCP config together. Doctor JSON is the
readiness source of truth. `./gradlew fixthisSetup` is recovery when generated
metadata is missing.

More: [Connect your agent](docs/getting-started/connect-your-agent.md).
