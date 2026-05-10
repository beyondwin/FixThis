# Feedback Console Tour

A visual walkthrough of the FixThis Studio console — from launching it against
a connected device to handing a batch of annotations off to your AI agent.

> 📸 **Screenshots / GIFs are placeholders** in this revision. Captures will be
> added as we cut a release. If you'd like to contribute captures, see the
> ![TODO: capture] markers and the [contribution guide](../../CONTRIBUTING.md).

## Open the console

From a configured agent:

```
fixthis_open_feedback_console
```

Or directly from the CLI:

```bash
fixthis console --package <applicationId>
```

The console opens in your default browser at `http://127.0.0.1:<port>`. The
URL is loopback-only — see [Privacy](../reference/privacy.md) and
[Security](../../SECURITY.md).

![TODO: capture — full console window on first open, "Start" connection card visible](../assets/console-tour-01-open.png)

*Caption:* Three-pane layout. Left: persisted feedback sessions. Center: live
or frozen Android preview. Right: mode-aware Inspector. Top bar: device
selector + connection state + session-level actions (Refresh devices, Clear
selection, Copy Prompt, Save to MCP).

## Connect a device

1. Make sure your device or emulator is reachable (`adb devices` from a shell
   shows it as `device`).
2. Pick the target device in the top-bar device selector. Multiple connected
   devices are visible but only ready ones are selectable.
3. Click **Start** on the connection card. FixThis launches your debug app and
   attaches the sidekick bridge.

![TODO: capture — top bar with device selector showing "Connected", Inspector showing "Ready"](../assets/console-tour-02-connect.png)

If the connection fails, the recovery card surfaces actionable options:
**Choose device** (multiple ready devices), **Open app** (debug app is not in
foreground), **Reconnect** (bridge dropped), **Try again** (transient ADB
error). Drafts and the last preview remain visible while reconnecting.

When the device is *blocked* — screen off, locked, app in background, in
Picture-in-Picture, sample app unresponsive, or no Compose UI on the current
screen — the canvas shows a per-cause overlay and suppresses input until the
cause clears, then auto-resumes the prior tool mode.

## Navigate in Select mode

After connection, the console defaults to **Select** mode. Clicks on the
preview navigate the app the same way they would on the device. Live preview
refreshes at the chosen interval (Manual, 1s, 2s, 5s; default 1s; auto-paused
when the tab is hidden or the device becomes unavailable).

Navigation is debug-only and limited to one-step `back`, `tap`, and `swipe`.

![TODO: capture — preview showing the sample app's Home screen, Select mode chip highlighted](../assets/console-tour-03-select.png)

## Switch to Annotate mode

Once you're on the screen you want to leave feedback on, click **Annotate**.
The current preview freezes — subsequent navigation in the running app does
not change what you're annotating, so you can take your time.

![TODO: capture — top bar mode toggle showing Annotate active, frozen preview with subtle "frozen" indicator](../assets/console-tour-04-annotate-mode.png)

## Make a selection

Two selection styles, picked automatically from how you click:

### Smart Select (single click on a UI element)

A single click pins the closest Compose semantics node under the cursor. The
overlay shows the merged-tree bounds; the Inspector shows:

- the matched composable name (e.g. `MetricCard`)
- semantic labels and `contentDescription` if any
- bounds, instance index (`instance i/N` for repeated cards)
- top-3 source-file candidates with line numbers, match reasons, and a margin
  score

![TODO: capture — preview with one Smart Select pin on a card, Inspector right pane showing semantic info + source candidates](../assets/console-tour-05-smart-select.png)

### Area selection (drag a rectangle)

Drag to draw a visual area when there's no clean target — typical for empty
margins, gaps between elements, or `AndroidView` interop where Compose
semantics are not detected.

Area selections still attach activity / screen metadata, just no source
candidates.

![TODO: capture — preview with area-rectangle around a margin, Inspector showing area mode (no source candidates)](../assets/console-tour-06-area-select.png)

## Write a comment and pin it

Type a comment in the Inspector, then click **Add annotation**. A numbered
overlay marker appears on the preview, and a row appears in the pending list.
Pending annotations support **Focus** (re-pan to the marker) and **Delete**
(renumber the rest so pin numbers and list numbers stay in sync).

![TODO: capture — frozen preview with two numbered pins, pending list on the right showing comments 1 and 2](../assets/console-tour-07-pin.png)

You can pin **multiple annotations on the same frozen preview** — they share
one screenshot and one source-candidate context.

## Save the batch

You have two ways to hand off the pending batch:

### Copy Prompt — for any chat-style agent

Click **Copy Prompt**. A compact Markdown prompt lands in the clipboard. Paste
into Claude, Codex, Cursor, ChatGPT, or any other agent. The Markdown contains
your comments + target evidence + top-3 source candidates + severity / status
— enough for the agent to start editing.

### Save to MCP — for Claude Code or Codex

Click **Save to MCP**. The batch is persisted as a local handoff under
`.fixthis/feedback-sessions/<session-id>/`. Then in your agent:

> Read the latest FixThis handoff and start fixing.

The agent calls `fixthis_read_feedback`, gets the same JSON + Markdown, and
edits.

Both paths share the same compact prompt format and the same JSON evidence;
**Save to MCP** just removes the manual paste step. See
[Working with AI agents](agents.md) for per-agent specifics.

![TODO: capture — Inspector "Draft view" showing screenshot + numbered overlay + comments, with Copy Prompt and Save to MCP buttons highlighted](../assets/console-tour-08-handoff.png)

## After the agent makes the change

Resolve the items so they don't appear in the next queue:

> Mark all FixThis items in that batch as resolved.

The agent calls `fixthis_resolve_feedback` per item, with a status of
`resolved`, `needs-clarification`, or `not-fixed`.

Resolved annotations move out of the active queue but stay browsable in the
session list under "Resolved", so you can audit history.

## Resuming after a restart

Feedback console sessions are resumable. FixThis saves workspace metadata and
screenshot artifacts under `.fixthis/feedback-sessions/`, so an MCP or console
restart does not discard queued feedback.

![TODO: capture — left pane session list with two prior sessions, one expanded showing saved evidence groups](../assets/console-tour-09-resume.png)

## What's next

- [Working with AI agents](agents.md) — Claude Code, Codex, Cursor specifics
- [MCP tools reference](../reference/mcp-tools.md) — exact tool signatures
- [Output schema](../reference/output-schema.md) — JSON shape of what the
  agent reads
- [Feedback console contract](../reference/feedback-console-contract.md) —
  console-side semantics and persisted shape
- [Troubleshooting](troubleshooting.md) — when the bridge / device / preview
  misbehaves
