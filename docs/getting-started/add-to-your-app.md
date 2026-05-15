# Add FixThis to Your Own App

> ⚠️ **Pre-publication status:** The bundled sample works now. A separate app can
> use FixThis today through Gradle composite-build or local project wiring.
> Maven Central and Gradle Plugin Portal coordinates are not published yet. See
> [Release readiness](../contributing/release-readiness.md) for the publishing
> checklist.

## Requirements

Same as the sample: JDK 21 toolchain, AGP 9.1.1, Kotlin 2.2.21, Compose BOM
2026.04.01, `minSdk` 24, ADB on PATH. See
[try-the-sample.md](try-the-sample.md#prerequisites) for the full table, and
[`docs/reference/compatibility.md`](../reference/compatibility.md) for the
full supported / minimum-that-compiles version axes.

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

Once Gradle Plugin Portal publication is live, the agent-friendly form becomes:

```kotlin
plugins {
    id("io.beyondwin.fixthis.compose") version "0.2.0"
}
```

The plugin handles source-index generation and adds the sidekick as a
`debugImplementation` automatically. Future published sidekick wiring will look
like this, but this snippet is not copyable until artifacts are released:

```kotlin
// Future, once published. Until then, use composite build.
dependencies {
    debugImplementation("io.beyondwin.fixthis:fixthis-compose-sidekick:0.1.0")
}
```

Release builds are not a supported target — the sidekick is debug-only by
design.

## 2. Configure your AI agent

For agent-first setup inside the Android app repository, run:

```bash
./gradlew fixthisSetup
fixthis init --project-dir . --target all
fixthis doctor --project-dir .
```

`fixthisSetup` writes `.fixthis/project.json` with the detected application id,
plus `.fixthis/agent-setup.md` and `.fixthis/mcp.json.template` for agents that
prefer project-scoped MCP config. If the project has flavored debug variants,
use the variant-specific task, for example `./gradlew :app:fixthisSetupStagingDebug`.

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

If the CLI/MCP package is available from a GitHub Release, agents may install
the desktop tools first and then run `fixthis init`:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version vX.Y.Z --init --target codex --project-dir . --package <applicationId>
```

This does not replace the Gradle app wiring above; it only removes the need to
build the desktop CLI/MCP tools from source.

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
