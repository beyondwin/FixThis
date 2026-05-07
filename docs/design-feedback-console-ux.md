# Feedback Console UX Status

**Status:** Current Studio UI, based on the Option A redesign.
**Current version:** V1.2
**Related module:** `fixthis-mcp`

The shipped feedback console is a local-first, MCP-owned Studio UI for Android preview feedback. The browser UI uses a dark three-panel workspace: Sessions on the left, live/frozen preview canvas in the center, and a mode-aware Inspector on the right. The top bar includes a compact device control with a short device label, connection state, `Refresh devices`, and `Clear selection`.

## Current Workflow

1. Open the console and start from the connection card.
2. Click `Start`; the console finds the selected or only ready Android device, opens the debug app when possible, and connects to the FixThis sidekick bridge.
3. If more than one ready device is connected, the card shows `Choose a device`; click `Choose device` and select the device from the compact device control.
4. If the app is closed or the bridge drops, use the card action shown for that state: `Open app`, `Reconnect`, or `Try again`. Draft annotations and the last preview stay visible while reconnecting.
5. When the card shows `Ready`, click `Capture screen` to refresh the preview and continue normal preview navigation.
6. Click `Add` to freeze the latest preview for feedback.
7. Select a Compose target or drag a visual area and write a comment.
8. Click `Add to Pending`; numbered pending rows and preview overlays stay in sync.
9. Repeat on the same frozen preview as needed.
10. Click `Save` once to persist one evidence snapshot and all pending items.
11. Review saved evidence groups in the Inspector Draft view.
12. Click `Copy` for compact Markdown or `Send` to create a local handoff batch for MCP tools.

## Current Constraints

- There is no Select/Navigate toggle. Idle preview clicks navigate; `Add` enters frozen feedback mode.
- Device state is summarized as `No device`, `Connecting`, `Connected`, or `Unavailable`; unavailable devices remain visible but are not selectable.
- `Clear selection` clears FixThis's active device selection and does not run `adb disconnect`.
- Pending items support `Focus` and `Delete` only.
- Live preview frames are transient and are not added to `FeedbackSession.screens`.
- `Save` promotes exactly one frozen preview into one evidence snapshot for all pending items in that frozen work set.
- `Copy` and `Send` are session-level actions.

## Connection Recovery UX

The console now treats connection handling as a simple recovery loop for non-developer users. The main UI shows one state and one primary action: `Connect to your app`, `Ready`, `Open the app`, `Reconnect`, `Choose a device`, `Check your phone`, or `This build cannot connect`; card actions include `Start`, `Capture screen`, `Open app`, `Reconnect`, `Choose device`, and `Try again`.

Technical causes are hidden under Details. The app-side UI remains a minimal MCP status pill; all control stays in the browser console.

When connection drops, the console keeps pending feedback items and the last preview visible, marks the preview as stale, disables live bridge actions, and resumes preview polling after reconnect.
