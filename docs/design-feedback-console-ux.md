# Feedback Console UX Status

**Status:** Current Studio UI, based on the Option A redesign.
**Current version:** V1.2
**Related module:** `pointpatch-mcp`

The shipped feedback console is a local-first, MCP-owned Studio UI for Android preview feedback. The browser UI uses a dark three-panel workspace: Sessions on the left, live/frozen preview canvas in the center, and a mode-aware Inspector on the right.

## Current Workflow

1. Select a connected ADB device.
2. Use the live preview normally; preview clicks navigate the app.
3. Click `Add` to freeze the latest preview for feedback.
4. Select a Compose target or drag a visual area and write a comment.
5. Click `Add to Pending`; numbered pending rows and preview overlays stay in sync.
6. Repeat on the same frozen preview as needed.
7. Click `Save` once to persist one evidence snapshot and all pending items.
8. Review saved evidence groups in the Inspector Draft view.
9. Click `Copy` for compact Markdown or `Send` to create a local handoff batch for MCP tools.

## Current Constraints

- There is no Select/Navigate toggle. Idle preview clicks navigate; `Add` enters frozen feedback mode.
- Pending items support `Focus` and `Delete` only.
- Live preview frames are transient and are not added to `FeedbackSession.screens`.
- `Save` promotes exactly one frozen preview into one evidence snapshot for all pending items in that frozen work set.
- `Copy` and `Send` are session-level actions.
