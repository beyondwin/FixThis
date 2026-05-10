# Add FixThis to Your Own App

> ⚠️ **Pre-publication.** FixThis is not yet published to Maven Central or the
> Gradle Plugin Portal. External projects must wire this repository explicitly
> via Gradle composite build (`includeBuild`) or project-dependency, _not_ via
> the placeholder coordinates below. See
> [Release readiness](../contributing/release-readiness.md) for the publishing
> checklist.

## Requirements

Same as the sample: JDK 21 toolchain, AGP 9.1.1, Kotlin 2.2.21, Compose BOM
2026.04.01, `minSdk` 24, ADB on PATH. See
[try-the-sample.md](try-the-sample.md#prerequisites) for the full table.

## 1. Apply the Gradle plugin

In your `app/build.gradle.kts`:

```kotlin
plugins {
    id("io.beyondwin.fixthis.compose")
}
```

In this repository the plugin is wired through composite build and
`pluginManagement` in `settings.gradle.kts`, so the bundled sample applies it
directly. **External projects must reproduce that wiring** (composite build or
project dependency) until a published plugin coordinate exists.

The plugin handles source-index generation and adds the sidekick as a
`debugImplementation` automatically. Once published, manual sidekick wiring
will look like (placeholder, do not use yet):

```kotlin
// Future, once published. Until then, use composite build.
dependencies {
    debugImplementation("io.beyondwin.fixthis:fixthis-compose-sidekick:0.1.0")
}
```

Release builds are not a supported target — the sidekick is debug-only by
design.

## 2. Configure your AI agent

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

## What's next

- [Feedback console tour](../guides/feedback-console-tour.md) — visual walkthrough
- [Working with AI agents](../guides/agents.md) — Claude Code, Codex, Cursor,
  and chat-style agents
- [MCP tools reference](../reference/mcp-tools.md) — every MCP tool the server
  exposes
- [Troubleshooting](../guides/troubleshooting.md) — common failures and fixes
