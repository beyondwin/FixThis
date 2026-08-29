# Add FixThis to Your App

How to wire FixThis into an external Compose debug app.

Repo map: [project map](../guides/project-map.md). Flags: [CLI](../reference/cli.md).

## Requirements

Same as the sample: JDK 21, AGP 9.1.1, Kotlin 2.2.21, Compose BOM 2025.01.01,
`minSdk` 23, ADB on PATH. See [sample prerequisites](try-the-sample.md#prerequisites)
and [compatibility](../reference/compatibility.md).

## 1. Apply the Gradle plugin

Agent-first path:

```bash
brew install beyondwin/tools/fixthis
# or: npm install -g @beyondwin/fixthis
# or: curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh | bash -s -- --version v1.5.0

fixthis install-agent --project-dir . --target all --verify --json
```

If Homebrew already has an older build:
`brew update && brew upgrade beyondwin/tools/fixthis`, then `fixthis --version`.

`install-agent` finds the app module by `applicationId`, writes Claude Code /
Codex MCP config, and creates `.fixthis/project.json` plus
`.fixthis/agent-setup.*`.

Manual equivalent:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "1.5.0"
}
```

The plugin generates the source index and adds the sidekick as
`debugImplementation`. You usually do not add this yourself:

```kotlin
dependencies {
    debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:1.5.0")
}
```

Release builds are not supported. The sidekick is debug-only.

## 2. Configure the agent

Trust the `--verify --json` report. `readiness.state` is app readiness,
`actions[]` is the follow-up queue, and `readyForMcpTooling` must be true
before this agent opens the console.

Preview writes with `fixthis install-agent --dry-run`. Skip the Gradle patch
with `--skip-gradle-plugin` if the plugin is already applied.

Run `./gradlew fixthisSetup` only when doctor reports `NEEDS_INSTALL`,
generated metadata is missing, or you changed variants by hand. Flavored
debug variants use a variant-specific task, for example
`./gradlew :app:fixthisSetupStagingDebug`.

```bash
./scripts/bootstrap-mcp.sh --package <applicationId>
```

`--package` is the Android applicationId. The script writes:

- Claude Code → `.claude/settings.json`
- Codex → `~/.codex/config.toml`

Pass `--target claude` or `--target codex` to limit targets, or `--dry-run`
to preview. Restart Claude Code or Codex after it finishes.

Cursor, ChatGPT, and other chat agents use **Copy Prompt**. No MCP setup.

If the plugin is not applied yet, prefer `install-agent` over `init`.

### Manual setup (Windows or no bootstrap script)

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> \
  --write \
  --target all
```

## 3. Open the console

From a configured agent:

```
fixthis_open_feedback_console
```

Or from the CLI:

```bash
fixthis console --package <applicationId>
```

## Done

- `fixthis doctor --package <applicationId>` reaches a debug app and sidekick.
- The console opens.
- One written annotation can be copied or saved to MCP.
- Release builds do not include the sidekick.

## Next

- [Connect your agent](connect-your-agent.md)
- [Agent install snippet](agent-install-snippet.md)
- [Console tour](../guides/feedback-console-tour.md)
- [Working with agents](../guides/agents.md)
- [MCP tools](../reference/mcp-tools.md)
- [Troubleshooting](../guides/troubleshooting.md)
