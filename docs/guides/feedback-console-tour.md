# Feedback Console Tour

Walkthrough of FixThis Studio: connect a device, annotate, hand off.

The console is loopback-only. See [Privacy](../reference/privacy.md) and
[Security](../../SECURITY.md).

## Open it

From a configured agent:

```
fixthis_open_feedback_console
```

Or:

```bash
fixthis console --package <applicationId>
```

It opens at `http://127.0.0.1:<port>`.

Layout: left = sessions, center = preview, right = Inspector. Top bar:
device, connection, Copy Prompt, Save to MCP. Progress row:
Connect → Preview → Annotate → Handoff.

## Connect a device

1. `adb devices` shows the target as `device`.
2. Pick it in the top-bar selector.
3. Click **Start**. FixThis launches the debug app and attaches the bridge.

If it fails, the recovery card offers **Choose device**, **Open app**,
**Reconnect**, or **Try again**. Drafts and the last preview stay visible.

If the device is blocked (screen off, locked, backgrounded, PiP, no Compose
UI), the canvas shows a per-cause overlay and input waits until the cause
clears.

## Select mode

Default after connect. Clicks on the preview navigate the app. Live preview
refreshes at Manual / 1s / 2s / 5s (default 1s). It pauses when the tab is
hidden. Navigation is debug-only: one-step `back`, `tap`, `swipe`.

## Annotate mode

Click **Annotate**. The preview freezes. The frame badge names the state:
`Live preview`, `Frozen for annotation`, `Saved screen`, `Stale frame`,
`No screenshot`, or `Interaction blocked`.

If the app rotates, changes window mode, or leaves the screen before save,
FixThis asks to re-capture, force-save, or cancel.

## Select a target

**Click** pins the closest Compose node. Inspector shows composable name,
labels, bounds, `instance i/N`, and top-3 source candidates.

**Drag** draws a visual area for empty space, gaps, or AndroidView pixels.
Area selections keep activity / screen metadata, not source candidates.

## Write a comment

A numbered marker appears, the detail editor opens, and a pending row is
added. Type the change. Multiple annotations on one freeze share one
screenshot. Draft numbers stay in sync until save; persisted numbers stay
stable.

## Hand off

The readiness summary sits near **Copy Prompt** and **Save to MCP**. It
explains empty, draft-only, ready-to-copy, and ready-to-save. Only written
comments persist. Copy Prompt keeps leftover pins in the browser draft. Save
to MCP drops them.

New sessions use **Auto** diagnostics on Save to MCP. Switch to Manual or Off
if you do not want that. Copy Prompt never starts collection.

After Save to MCP, the session stays in History with a working pip while the
agent claims and resolves items. Click **Annotate** again for another screen.

## Next

- [Connect your agent](../getting-started/connect-your-agent.md)
- [Working with agents](agents.md)
- [Troubleshooting](troubleshooting.md)
- [Console contract](../reference/feedback-console-contract.md)
