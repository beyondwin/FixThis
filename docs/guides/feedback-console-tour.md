# Feedback Console Tour

A visual walkthrough of the FixThis Studio console — from launching it against
a connected device to handing a batch of annotations off to your AI agent.

> **Screenshot capture notes:** the tour below records the frames that should be
> captured for release screenshots. The image files are intentionally not linked
> until those captures exist in `docs/assets/`.

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

> Capture needed: full console window on first open, with the `Start`
> connection card visible and the workflow progress showing Connect, Preview,
> Annotate, and Handoff.

*Caption:* Three-pane layout. Left: persisted feedback sessions. Center: live
or frozen Android preview. Right: mode-aware Inspector. Top bar: device
selector + connection state + session-level actions (Refresh devices, Clear
selection, Copy Prompt, Save to MCP). The workflow progress row marks the
current Connect → Preview → Annotate → Handoff step, and the prompt readiness
summary explains whether a batch is ready to copy or save.

## Connect a device

1. Make sure your device or emulator is reachable (`adb devices` from a shell
   shows it as `device`).
2. Pick the target device in the top-bar device selector. Multiple connected
   devices are visible but only ready ones are selectable.
3. Click **Start** on the connection card. FixThis launches your debug app and
   attaches the sidekick bridge.

> Capture needed: top bar with the device selector showing `Connected`, and
> the Inspector showing `Ready`.

If the connection fails, the recovery card surfaces actionable options:
**Choose device** (multiple ready devices), **Open app** (debug app is not in
foreground), **Reconnect** (bridge dropped), **Try again** (transient ADB
error). Drafts and the last preview remain visible while reconnecting.

When the device is *blocked* — screen off, locked, app in background, in
Picture-in-Picture, sample app unresponsive, or no Compose UI on the current
screen — the canvas shows a per-cause overlay and suppresses input until the
cause clears, then auto-resumes the prior tool mode. The workflow progress row
keeps the blocked connection visible instead of making the user infer it from
the canvas alone.

## Navigate in Select mode

After connection, the console defaults to **Select** mode. Clicks on the
preview navigate the app the same way they would on the device. Live preview
refreshes at the chosen interval (Manual, 1s, 2s, 5s; default 1s; auto-paused
when the tab is hidden or the device becomes unavailable).

Navigation is debug-only and limited to one-step `back`, `tap`, and `swipe`.

> Capture needed: sample app Home screen in the preview, with the `Select`
> mode chip highlighted.

## Switch to Annotate mode

Once you're on the screen you want to leave feedback on, click **Annotate**.
The current preview freezes — subsequent navigation in the running app does
not change what you're annotating, so you can take your time.

The preview frame status badge names the current frame state: `Live preview`,
`Frozen for annotation`, `Saved screen`, `Stale frame`, `No screenshot`, or
`Interaction blocked`. Stale and blocked states keep their existing overlays,
but the badge stays visible so screenshots and videos still explain why the
preview is not interactive.

The frozen preview carries a screen fingerprint when the bridge can compute
one. If the app rotates, changes window mode, or otherwise moves to a different
screen before you hand off the batch, FixThis asks whether to re-capture,
force-save, or cancel.

> Capture needed: top bar mode toggle with `Annotate` active, plus a frozen
> preview indicator.

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

> Capture needed: one Smart Select pin on a card, with the Inspector showing
> semantic info and source candidates.

### Area selection (drag a rectangle)

Drag to draw a visual area when there's no clean target — typical for empty
margins, gaps between elements, or `AndroidView` interop where Compose
semantics are not detected.

Area selections still attach activity / screen metadata, just no source
candidates.

> Capture needed: area rectangle around a margin, with the Inspector showing
> area mode and no source candidates.

## Write a comment and pin it

Type a comment in the Inspector, then click **Add annotation**. A numbered
overlay marker appears on the preview, and a row appears in the pending list.
Pending annotations support **Focus** (re-pan to the marker) and **Delete**.
Draft pin numbers stay in sync while editing; once persisted, item sequence
numbers remain stable for the session.

> Capture needed: frozen preview with two numbered pins, plus pending comments
> 1 and 2 in the right pane.

You can pin **multiple annotations on the same frozen preview** — they share
one screenshot and one source-candidate context.

## Save the batch

You have two ways to hand off the pending batch:

The prompt readiness summary sits near **Copy Prompt** and **Save to MCP**. It
shows `No annotations ready` before any written comments exist, then counts the
ready annotations and distinguishes clipboard-only copy from the local MCP
queue. Disabled handoff buttons always have a visible reason in this summary.

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
**Save to MCP** just removes the manual paste step and never uploads the
handoff. See
[Working with AI agents](agents.md) for per-agent specifics.

> Capture needed: Inspector Draft view with screenshot, numbered overlay,
> comments, and the `Copy Prompt` / `Save to MCP` buttons visible.

## After the agent makes the change

Resolve the items so they don't appear in the next queue:

> Mark all FixThis items in that batch as resolved.

The agent calls `fixthis_resolve_feedback` per item, with a status of
`resolved`, `needs_clarification`, or `wont_fix`.

The console renders Resolved, Needs Clarification, and Won't Fix as distinct
states. Resolved and Won't Fix items move out of the default agent work queue;
Needs Clarification stays visible and editable so you can update the comment
and re-save.

## Resuming after a restart

Feedback console sessions are resumable. FixThis saves workspace metadata and
screenshot artifacts under `.fixthis/feedback-sessions/`, so an MCP or console
restart does not discard queued feedback.

On compact layouts, the prior-session list moves behind the **History** button
in the top bar. The drawer exposes the same saved evidence groups as the
desktop left pane without covering the current preview until opened.

Unsaved browser-only pending annotations are mirrored locally per session. When
the console reloads and finds a recoverable frozen preview, it shows a
Recover / Recapture / Discard banner before exposing the pending rows again.
Recover keeps the frozen screenshot and pending comments; Recapture maps the
comments onto a fresh preview; Discard clears the local mirror.

> Capture needed: left session list with two prior sessions, one expanded to
> show saved evidence groups.

## What's next

- [Working with AI agents](agents.md) — Claude Code, Codex, Cursor specifics
- [MCP tools reference](../reference/mcp-tools.md) — exact tool signatures
- [Output schema](../reference/output-schema.md) — JSON shape of what the
  agent reads
- [Feedback console contract](../reference/feedback-console-contract.md) —
  console-side semantics and persisted shape
- [Troubleshooting](troubleshooting.md) — when the bridge / device / preview
  misbehaves
