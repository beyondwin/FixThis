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

For published / installed CLI usage inside an Android app repo, agents should
prefer:

```bash
fixthis init
```

`fixthis init` writes Claude Code / Codex MCP config by default and can infer a
unique Android `applicationId` from Gradle build files when `.fixthis/project.json`
does not exist. Use `fixthis init --dry-run` to preview the writes.

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
