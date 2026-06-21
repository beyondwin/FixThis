# FixThis MCP Bootstrap

This repository includes a local MCP server for Claude Code and Codex. For the
bundled sample app, run:

```bash
./scripts/bootstrap-mcp.sh --sample
```

That command builds the local CLI/MCP distributions and registers the FixThis
MCP server with both supported agents:

- Claude Code: project-local `.claude/settings.json`
- Codex: user-global `~/.codex/config.toml`

Restart Claude Code or Codex after the command finishes. Then call:

```text
fixthis_open_feedback_console
```

For a real app, replace the sample shortcut with the app's Android
`applicationId`:

```bash
./scripts/bootstrap-mcp.sh --package <applicationId>
```

For installed CLI usage inside an Android app repo, agents should prefer:

```bash
fixthis init
```

`fixthis init` writes Claude Code / Codex MCP config by default and can infer a
unique Android `applicationId` from Gradle build files when `.fixthis/project.json`
does not exist. Use `fixthis init --dry-run` to preview the writes.

An agent can install the CLI/MCP package before running `fixthis init`.
On macOS, prefer the Homebrew tap:

```bash
brew install beyondwin/tools/fixthis
fixthis init --agent --project-dir . --target codex
```

If FixThis is already installed through Homebrew, refresh the tap first:
`brew update && brew upgrade beyondwin/tools/fixthis`, then confirm with
`fixthis --version`.

With npm:

```bash
npm install -g @beyondwin/fixthis
fixthis init --agent --project-dir . --target codex
```

On macOS/Linux without a package manager, use the GitHub Release installer:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v1.3.0 --init --target codex --project-dir .
```

Use `--target claude` or `--target all` for other MCP clients. For a new
Android app integration in an external repo, the canonical sequence is:

```bash
fixthis install-agent --project-dir . --target all
fixthis doctor --project-dir . --json
```

`fixthis install-agent` handles Gradle wiring and MCP config together;
`fixthis doctor --json` is the source of truth for readiness. `./gradlew
fixthisSetup` is a recovery or manual verification command when doctor
reports missing generated metadata, not the primary path.

Useful variants:

```bash
./scripts/bootstrap-mcp.sh --sample --target claude
./scripts/bootstrap-mcp.sh --sample --target codex
./scripts/bootstrap-mcp.sh --sample --dry-run
```

Keep generated `.claude/settings.json`, `~/.codex/config.toml`, and `.fixthis/`
artifacts local unless you intentionally review and share them.

More detail: [Connect your AI agent](docs/getting-started/connect-your-agent.md)
and [MCP tools reference](docs/reference/mcp-tools.md).
