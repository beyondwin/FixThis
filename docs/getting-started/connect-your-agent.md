# Connect Your AI Agent

FixThis supports two handoff modes:

| Agent style | Use | Setup |
| --- | --- | --- |
| Claude Code | **Save to MCP** | `./scripts/bootstrap-mcp.sh --sample --target claude` for the sample, or `--package <applicationId>` for your app |
| Codex | **Save to MCP** | `./scripts/bootstrap-mcp.sh --sample --target codex` for the sample, or `--package <applicationId>` for your app |
| Cursor, ChatGPT, or another chat-style agent | **Copy Prompt** | No MCP setup; paste the copied Markdown |

This page explains how users connect an agent to FixThis. Agents working inside the FixThis repository should also read [AGENTS.md](../../AGENTS.md) and the [Project map](../guides/project-map.md) before changing files.

Both modes use the same evidence. **Copy Prompt** puts compact Markdown on your
clipboard. **Save to MCP** writes the same handoff locally so an MCP-aware
agent can read, claim, and resolve feedback items.

## Prerequisites

- Run the bundled sample or a Compose debug build with FixThis installed.
- Know the Android `applicationId` for the app you want to inspect, or run from
  a Gradle project where FixThis can detect a unique Android `applicationId`.
- Keep ADB on your PATH and use an unlocked device or emulator.

For the sample app, the package is `io.github.beyondwin.fixthis.sample`.

For the fastest sample setup, use the shortcut:

```bash
./scripts/bootstrap-mcp.sh --sample
```

## Agent-First Desktop Install

An agent can install the desktop CLI/MCP package and register MCP from inside
an Android app repository.

On macOS, prefer Homebrew:

```bash
brew install beyondwin/tools/fixthis
fixthis init --agent --project-dir . --target codex
```

If FixThis is already installed through Homebrew, run
`brew update && brew upgrade beyondwin/tools/fixthis` before checking
`fixthis --version`.

With npm:

```bash
npm install -g @beyondwin/fixthis
fixthis init --agent --project-dir . --target codex
```

On macOS/Linux without a package manager, use the GitHub Release installer:

```bash
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v1.5.0 --init --target codex --project-dir .
```

Use `--target claude` for Claude Code or `--target all` for both. If package
detection is ambiguous, add `--package <applicationId>`.

For a new Android app integration, prefer the single agent command:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

## Codex Plugin

The Codex plugin packages the install, feedback-loop, Android evidence, and
release-smoke workflows as skills. It does not replace setup: canonical
bootstrap still starts with
`fixthis install-agent --project-dir . --target all --verify --json` and uses
the unified JSON report as the readiness source of truth.

Restart Claude Code or Codex after MCP config is written. Then open the
console with `fixthis_open_feedback_console`.

`fixthis install-agent` applies the published Gradle plugin, writes Claude
Code / Codex MCP config, writes `.fixthis/project.json`, and leaves
`.fixthis/agent-setup.*` handoff files for follow-up agents. The doctor JSON
readiness result is the source of truth.

If doctor reports `NEEDS_INSTALL` or generated metadata is missing, run
`./gradlew fixthisSetup` as a recovery step and rerun
`fixthis install-agent --project-dir . --target all --verify --json`.
`fixthisSetup` writes
`.fixthis/project.json`, so `init`, `doctor`, `mcp`, and `console` can omit
`--package` unless the Android project has multiple app ids. For pasteable
`AGENTS.md` / `CLAUDE.md` instructions, use
[Agent install snippet](agent-install-snippet.md).

## Claude Code

```bash
./scripts/bootstrap-mcp.sh --sample --target claude
```

The script builds the local CLI/MCP distributions and writes project-local
Claude Code settings under `.claude/settings.json`. Restart Claude Code after
the script finishes.

For your own debug app, use `--package <applicationId>` instead of `--sample`.
If the CLI is already installed and you are running inside the Android app
repository, agents can use `fixthis init --target claude` instead.

In Claude Code, open the console:

```text
fixthis_open_feedback_console
```

After you click **Save to MCP** in the console, ask Claude Code:

```text
Read the latest FixThis handoff, claim the item, make the change, and mark it resolved when done.
```

## Codex

```bash
./scripts/bootstrap-mcp.sh --sample --target codex
```

The script builds the local CLI/MCP distributions and writes Codex MCP config
to `~/.codex/config.toml`. Restart Codex after the script finishes.

For your own debug app, use `--package <applicationId>` instead of `--sample`.
If the CLI is already installed and you are running inside the Android app
repository, agents can use `fixthis init --target codex` instead.

In Codex, open the console:

```text
fixthis_open_feedback_console
```

After **Save to MCP**, ask Codex:

```text
Read the latest FixThis handoff, claim the item, make the change, and mark it resolved when done.
```

## Cursor, ChatGPT, and Chat-Style Agents

Use **Copy Prompt**:

1. Open FixThis Studio.
2. Click **Annotate**.
3. Select a UI element or drag a visual area.
4. Type the change request in the annotation detail.
5. Click **Copy Prompt**.
6. Paste the Markdown into your agent.

No MCP restart or config file is required for this mode.

## MCP Queue Lifecycle

MCP-aware agents should follow this lifecycle:

1. `fixthis_read_feedback` to read the compact Markdown and JSON evidence.
2. `fixthis_claim_feedback` before editing, so the console shows the item as in progress.
3. `fixthis_resolve_feedback` after editing, with `resolved`, `needs_clarification`, or `wont_fix`.

Full signatures live in the [MCP tools reference](../reference/mcp-tools.md).
CLI setup flags live in the [CLI reference](../reference/cli.md).

## Locality and Artifacts

FixThis does not call an external AI API. **Save to MCP** writes local handoff
files under `.fixthis/feedback-sessions/`, and **Copy Prompt** writes compact
Markdown to your clipboard. Do not commit `.fixthis/`.

## Next

- [Try the sample app](try-the-sample.md)
- [Add FixThis to your app](add-to-your-app.md)
- [Working with AI agents](../guides/agents.md)
- [MCP bootstrap summary](../../MCP.md)
- [Troubleshooting](../guides/troubleshooting.md)
