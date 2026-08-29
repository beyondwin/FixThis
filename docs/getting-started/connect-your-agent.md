# Connect Your AI Agent

Two handoff modes, same evidence:

| Agent | Use | Setup |
| --- | --- | --- |
| Claude Code | **Save to MCP** | `./scripts/bootstrap-mcp.sh --sample --target claude` |
| Codex | **Save to MCP** | `./scripts/bootstrap-mcp.sh --sample --target codex` |
| Cursor, ChatGPT, other chat | **Copy Prompt** | None. Paste the Markdown. |

**Copy Prompt** puts compact Markdown on the clipboard. **Save to MCP** writes
the same handoff locally so an MCP agent can read, claim, and resolve items.

Agents working in this repository should also read [AGENTS.md](../../AGENTS.md)
and the [project map](../guides/project-map.md).

## Prerequisites

- Sample app or a Compose debug build with FixThis installed.
- The Android `applicationId`, or a Gradle project where FixThis can detect
  a unique one.
- ADB on PATH and an unlocked device or emulator.

Sample package: `io.github.beyondwin.fixthis.sample`.

Shortcut:

```bash
./scripts/bootstrap-mcp.sh --sample
```

## Agent-First Desktop Install

On macOS, prefer Homebrew:

```bash
brew install beyondwin/tools/fixthis
fixthis init --agent --project-dir . --target codex
```

If it is already installed: `brew update && brew upgrade beyondwin/tools/fixthis`,
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

Use `--target claude` or `--target all`. Add `--package <applicationId>` if
detection is ambiguous.

For a new Android app, prefer:

```bash
fixthis install-agent --project-dir . --target all --verify --json
```

Restart Claude Code or Codex after MCP config is written. Then open the
console with `fixthis_open_feedback_console`.

If doctor reports `NEEDS_INSTALL` or generated metadata is missing, run
`./gradlew fixthisSetup` and rerun
`fixthis install-agent --project-dir . --target all --verify --json`.
Pasteable repo instructions: [Agent install snippet](agent-install-snippet.md).

## Claude Code

```bash
./scripts/bootstrap-mcp.sh --sample --target claude
```

Restart Claude Code. Then:

```text
fixthis_open_feedback_console
```

After **Save to MCP**:

```text
Read the latest FixThis handoff, claim the item, make the change, and mark it resolved when done.
```

For your own app, use `--package <applicationId>` instead of `--sample`.

## Codex

```bash
./scripts/bootstrap-mcp.sh --sample --target codex
```

Restart Codex. Same tools as Claude Code:
`fixthis_open_feedback_console`, then read / claim / resolve.

## Cursor, ChatGPT, and chat-style agents

1. Open FixThis Studio.
2. Click **Annotate**.
3. Select a UI element or drag an area.
4. Type the change.
5. Click **Copy Prompt**.
6. Paste into the agent.

No MCP config or restart.

## MCP Queue

1. `fixthis_read_feedback`
2. `fixthis_claim_feedback` before editing
3. `fixthis_resolve_feedback` after editing (`resolved`, `needs_clarification`,
   or `wont_fix`)

Signatures: [MCP tools](../reference/mcp-tools.md). Flags: [CLI](../reference/cli.md).

## Locality

FixThis does not call an external AI API. **Save to MCP** writes
`.fixthis/feedback-sessions/`. Do not commit `.fixthis/`.

## Next

- [Try the sample](try-the-sample.md)
- [Add to your app](add-to-your-app.md)
- [Working with agents](../guides/agents.md)
- [MCP.md](../../MCP.md)
- [Troubleshooting](../guides/troubleshooting.md)
