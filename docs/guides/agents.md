# Working with AI Agents

FixThis hands UI context to AI coding agents in two modes. Pick the section
below that matches how you drive your agent.

| Agent style                       | Mode             | Setup                              |
| --------------------------------- | ---------------- | ---------------------------------- |
| Claude Code (CLI)                 | **Save to MCP**  | `./scripts/bootstrap-mcp.sh --package <applicationId> --target claude` |
| Codex CLI                         | **Save to MCP**  | `./scripts/bootstrap-mcp.sh --package <applicationId> --target codex`  |
| Cursor / ChatGPT / any chat agent | **Copy Prompt**  | None — paste from the clipboard    |

Both modes share the same compact Markdown prompt format and the same JSON
evidence; **Save to MCP** just removes the manual paste step. The bootstrap
script builds the local CLI/MCP distributions and then runs
`fixthis setup --write`; use the raw CLI command only for manual setup or
Windows shells.

## Claude Code

Claude Code reads MCP servers from per-project `.claude/settings.json`. Bootstrap:

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target claude
```

After the script writes the config, **restart Claude Code** so it picks up the
new MCP server. Then in any Claude Code session for this project:

```
fixthis_open_feedback_console
```

opens the browser console. After saving feedback, ask the agent to pick it up:

> Read the latest FixThis handoff and start fixing.

The agent calls `fixthis_read_feedback`, gets the compact Markdown prompt and
JSON evidence, and edits the right call sites.

For queue work, the agent should claim the item before editing:

```
fixthis_claim_feedback
```

This marks the item `in_progress` so the console shows a working state and
other agents avoid duplicate work. Pass `agentNote` when useful; the console
shows it in the item detail while the item is locked for editing.

When you've made the change, ask the agent to mark it resolved:

> Mark all FixThis items in that batch as resolved.

The agent calls `fixthis_resolve_feedback` per item with `status` set to
`resolved`, `needs_clarification`, or `wont_fix`. The console renders those as
Resolved, Needs Clarification, or Won't Fix, with the agent summary visible in
the saved annotation detail.

## Codex CLI

Codex reads MCP servers from user-global `~/.codex/config.toml`. Bootstrap:

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target codex
```

Restart Codex so it picks up the new MCP server. After that, the workflow is
identical to Claude Code — `fixthis_open_feedback_console` to open the console,
`fixthis_read_feedback` / `fixthis_claim_feedback` /
`fixthis_resolve_feedback` for the queue.

Codex also reads `AGENTS.md` at the repository root. The shipped `AGENTS.md`
points Codex at the same MCP setup so a fresh clone is wired up out of the box.

## Cursor, ChatGPT, and other chat-style agents

For agents without first-class MCP support, use **Copy Prompt** in the console:

1. Annotate as usual in the FixThis Studio console.
2. Click **Copy Prompt** instead of **Save to MCP**.
3. Paste into the agent's chat input. The pasted Markdown contains:
   - the user comment(s)
   - target evidence (bounds, semantics path, instance index)
   - top-3 source-file candidates with line numbers and match reasons
   - severity / status

The agent can start editing immediately — no MCP setup, no restart, no extra
tools.

### Target reliability warnings

Saved feedback items may include `targetReliability`. Treat it as the
confidence level for the UI target, not as task priority.

- `HIGH`: source candidates are strong starting points, but still verify the
  screenshot and surrounding code before editing.
- `MEDIUM`: inspect the listed candidates before editing; the right call site
  may be nearby rather than the first candidate.
- `LOW`: use the screenshot, bounds, comment, and nearby UI labels first. Treat
  source candidates as hints.
- `POSSIBLE_VIEW_INTEROP`: the selected pixels may come from AndroidView,
  WebView, or another non-Compose boundary. Do not assume a Compose candidate
  rendered those pixels.
- `SCREEN_FINGERPRINT_MISMATCH_FORCED`: the user force-saved after the screen
  changed. Confirm the current UI before applying edits.

## Behavior shared across modes

- **Both modes are local.** No FixThis call goes to an external API. **Save to
  MCP** persists `.fixthis/feedback-sessions/<id>/` files; **Copy Prompt** writes
  to the clipboard.
- **JSON is always complete.** Agent-facing Markdown is intentionally compact
  and omits internal IDs and storage metadata. JSON kept on disk preserves all
  IDs, paths, and MCP contracts — see the
  [Output schema](../reference/output-schema.md).
- **Multiple annotations batch into one handoff.** Whether you Copy Prompt or
  Save to MCP, every pending annotation on the frozen preview lands in the same
  batch.
- **Screen mismatch is guarded.** If the frozen preview fingerprint differs
  from the current app screen when you save, the console asks whether to
  re-capture, force-save, or cancel.
- **Agent outcomes stay visible.** Claimed, clarification-needed, won't-fix,
  and resolved items render as separate states in the browser console instead
  of collapsing into a generic sent row.

## What's next

- [Feedback console tour](feedback-console-tour.md) — annotate / pin / hand off,
  with screenshots
- [MCP tools reference](../reference/mcp-tools.md) — exact tool signatures and
  return shapes
- [CLI reference](../reference/cli.md) — `fixthis setup` and friends
