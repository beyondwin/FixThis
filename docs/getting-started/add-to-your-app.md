# Add FixThis to Your Own App

## Requirements

Same as the sample: JDK 21 toolchain, AGP 9.1.1, Kotlin 2.2.21, Compose BOM
2026.04.01, `minSdk` 24, ADB on PATH. See
[try-the-sample.md](try-the-sample.md#prerequisites) for the full table, and
[`docs/reference/compatibility.md`](../reference/compatibility.md) for the
full supported / minimum-that-compiles version axes.

## 1. Apply the Gradle plugin

The agent-first path should do this automatically:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v0.2.1

fixthis install-agent --project-dir . --target all
```

`fixthis install-agent` detects the Android app module by `applicationId`,
writes Claude Code / Codex MCP config, creates `.fixthis/agent-setup.*`
handoff files, and applies the published Gradle plugin.

Manual equivalent in your app module `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "0.2.1"
}
```

The plugin handles source-index generation and adds the sidekick as a
`debugImplementation` automatically. You usually do not need to add this
dependency yourself, but the resolved Maven artifact is:

```kotlin
dependencies {
    debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:0.2.1")
}
```

Release builds are not a supported target — the sidekick is debug-only by
design.

## 2. Configure your AI agent

After the plugin is applied, refresh project metadata and verify:

```bash
./gradlew fixthisSetup
fixthis doctor --project-dir . --json
```

Use `fixthis install-agent --dry-run` to preview the Gradle patch and config
writes, or `--skip-gradle-plugin` if the plugin is already applied.

`fixthisSetup` writes `.fixthis/project.json` with the detected application id
and refreshes project metadata after Gradle sync. If the project has flavored
debug variants, use the variant-specific task, for example
`./gradlew :app:fixthisSetupStagingDebug`.

```bash
# Bootstrap MCP integration (build + register with Claude Code / Codex)
./scripts/bootstrap-mcp.sh --package <applicationId>
```

`--package` is the Android applicationId of the app you are running FixThis
against. The script writes:

- **Claude Code** → project-local `.claude/settings.json`
- **Codex** → user-global `~/.codex/config.toml`

Pass `--target claude` or `--target codex` to limit the targets, or
`--dry-run` to preview without writing. After it completes, restart Claude
Code or Codex so the new MCP server is picked up.

For Cursor, ChatGPT, or any chat-style agent without first-class MCP support,
use **Copy Prompt** in the console — no setup required.

Agents may combine desktop install and MCP registration:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v0.2.1 --init --target codex --project-dir . --package <applicationId>
```

If the Gradle plugin is not applied yet, prefer `fixthis install-agent` because
it handles both Gradle wiring and MCP config.

### Manual setup (Windows or no shell script)

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> \
  --write \
  --target all
```

See the [CLI reference](../reference/cli.md) for the full set of `fixthis setup`
options.

## 3. Open the console

From any configured agent:

```
fixthis_open_feedback_console
```

Or directly from the CLI:

```bash
fixthis console --package <applicationId>
```

## Done State

Your app integration is working when:

- `fixthis doctor --package <applicationId>` reports a reachable debug app and sidekick bridge;
- `fixthis_open_feedback_console` or `fixthis console --package <applicationId>` opens FixThis Studio;
- one written annotation can be saved through **Copy Prompt** or **Save to MCP**;
- release builds do not include the sidekick.

## What's next

- [Connect your AI agent](connect-your-agent.md) — Claude Code, Codex, Cursor,
  and chat-style agents
- [Agent install snippet](agent-install-snippet.md) — pasteable AGENTS.md /
  CLAUDE.md instructions
- [Feedback console tour](../guides/feedback-console-tour.md) — visual walkthrough
- [Working with AI agents](../guides/agents.md) — Claude Code, Codex, Cursor,
  and chat-style agents
- [MCP tools reference](../reference/mcp-tools.md) — every MCP tool the server
  exposes
- [Troubleshooting](../guides/troubleshooting.md) — common failures and fixes
