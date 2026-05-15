# FixThis — Agent Notes

> **Agents:** read this file end-to-end. Sub-anchors may be skipped on a
> first pass, but every top-level section is load-bearing for correct
> tool use.

FixThis adds a debug-only sidekick to Jetpack Compose Android apps and exposes
captured UI context to AI coding agents over MCP. The Android app shows only a
connection status pill; all annotation, selection, and handoff happen in a
desktop browser console.

This file is the entry point for Codex / Claude Code / similar agents. It
points to canonical docs rather than restating them — update those, not this.

## Prerequisites

- JDK 21+
- Android SDK with ADB (`adb devices` shows a connected device or emulator)
- Node.js 20.0+ LTS (for console JS harnesses; see `CONTRIBUTING.md` § Prerequisites)
- Target app is a **debug build** (`debugImplementation` only; release is not supported)

## Quick Start

```bash
# Try the bundled sample app first.
./scripts/bootstrap-mcp.sh --sample

# Or bootstrap MCP integration for your own debug app.
./scripts/bootstrap-mcp.sh --package <applicationId>
```

`--sample` uses the bundled sample package (`io.beyondwin.fixthis.sample`).
`--package` is the Android applicationId of the app you are running FixThis
against. The script writes Claude Code config to project-local
`.claude/settings.json` and Codex config to `~/.codex/config.toml`. Pass
`--target claude` / `--target codex` to limit targets, or `--dry-run` to
preview. Restart your agent after the script finishes.

Manual setup, full CLI flags, and dry-run examples:
[`docs/reference/cli.md`](docs/reference/cli.md).
MCP bootstrap summary for agents: [`MCP.md`](MCP.md).

**Trying FixThis from scratch on the sample app?** Start at the README's
[Quick Start](README.md#quick-start-sample-app-to-agent-handoff) for the
human-driven flow (build → doctor → run → console → agent handoff).

## Feedback Workflow

1. Call `fixthis_open_feedback_console` — opens browser at `localhost:<port>`.
2. Click **Start** on the connection card. FixThis launches the app and attaches.
3. Navigate the app from the browser preview.
4. Click **Annotate** to freeze the latest screen preview.
5. Click a UI element or drag a visual area; type a comment.
6. Click **Add annotation** — creates a numbered pin in the overlay.
7. Repeat for additional feedback on this screen.
8. **Save to MCP** persists the batch as a local handoff. (Use **Copy Prompt**
   instead to paste compact Markdown into a chat-style agent.)
9. Call `fixthis_read_feedback` to read the queue, then
   `fixthis_resolve_feedback` after making changes.

First-run agent setup:
[`docs/getting-started/connect-your-agent.md`](docs/getting-started/connect-your-agent.md).
Deeper agent workflow notes:
[`docs/guides/agents.md`](docs/guides/agents.md).

## MCP Tools — Index

| Tool | What it does |
|------|--------------|
| `fixthis_status` | ADB bridge + package + source-index check |
| `fixthis_get_current_screen` | Inspect current Compose semantics and layout |
| `fixthis_verify_ui_change` | Check expected text on screen |
| `fixthis_open_feedback_console` | Open the local browser feedback console |
| `fixthis_list_feedback_sessions` | List resumable feedback workspaces |
| `fixthis_capture_screen` | Capture into the active feedback session |
| `fixthis_navigate_app` | One debug-only tap, swipe, or back |
| `fixthis_list_feedback` | List feedback queue summaries |
| `fixthis_read_feedback` | Read queue as JSON + compact Markdown |
| `fixthis_claim_feedback` | Mark items in-progress (avoids duplicate work) |
| `fixthis_resolve_feedback` | Mark resolved / needs-clarification / not-fixed |

Full signatures and return shapes:
[`docs/reference/mcp-tools.md`](docs/reference/mcp-tools.md).

## Constraints — Read Before Making Changes

- **Debug builds only.** Sidekick installs only via `debugImplementation`. Never target release.
- **Jetpack Compose only.** No View-based or Flutter targets in V1.
- **Local-first.** FixThis makes no external API calls. **Save to MCP** is local file persistence.
- **Do not commit `.fixthis/`** — screenshots, session metadata, local artifacts.
- **Do not rename persisted MCP JSON fields.** `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, `sourceCandidates` are compatibility contracts — see [`docs/reference/output-schema.md`](docs/reference/output-schema.md).
- **`:fixthis-compose-core` has no dependency on MCP, CLI, Android UI, or `.fixthis/` paths.** Outer modules translate at the boundary.
- **Bridge protocol changes are coordinated.** See [`docs/reference/bridge-protocol.md`](docs/reference/bridge-protocol.md).

## Diagnostics

```bash
fixthis doctor --package <applicationId>
```

Common issues: [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md).

## Build / Test

Canonical commands and the local PR checklist live in
[`CONTRIBUTING.md`](CONTRIBUTING.md). CI baseline mirroring them:
`.github/workflows/ci.yml`.

## Module Map

```
:app (sample/)            — validation sample app
:fixthis-compose-core     — pure Kotlin domain
:fixthis-compose-sidekick — debug Android runtime
:fixthis-gradle-plugin    — source-index generation, debug DI
:fixthis-cli              — desktop CLI
:fixthis-mcp              — stdio MCP server + local HTTP feedback console
```

Architecture deep dive: [`docs/architecture/overview.md`](docs/architecture/overview.md).
