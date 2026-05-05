# Feedback Console UX Status

**Status:** Superseded by the live preview and batched-items console workflow.
**Current version:** V1.1
**Related module:** `pointpatch-mcp`

This document was originally an issue list for the early feedback console. The shipped console now uses a live-preview workflow instead of a capture-first Select/Navigate mode model.

## Current Workflow

The console is an MCP-owned local browser UI. The MCP process owns feedback session state and persists saved evidence under `.pointpatch/feedback-sessions/`.

1. Select a connected ADB device.
2. Use the live preview normally; preview clicks navigate the app.
3. Click Add to freeze the latest preview for feedback.
4. Select one or more Compose targets or visual areas and add comments.
5. Review numbered pending markers and pending comments.
6. Click Save once to persist one evidence snapshot and all pending items.
7. Expand the saved evidence group to review the persisted screenshot, numbered overlay, and saved comments.
8. Click Send to store a local handoff batch for MCP tools.

## Resolved Issues

- Target selection is no longer hardcoded to a full-screen area. The frozen preview supports component click selection and custom visual area selection.
- There is a top-bar Send action. Send records a local handoff batch and does not call an external AI API.
- Pending items are visible before Save and support Focus and Delete. Edit is intentionally not implemented; delete the item and add a replacement instead.
- Pending item numbers and preview overlay numbers are renumbered together after delete.
- Live preview can poll automatically at Manual, 1s, 2s, or 5s, with 2s as the default. Polling pauses while the tab is hidden and while Add/frozen-preview mode is active.
- Saved evidence groups keep the persisted screenshot, numbered overlay, and comments visible for review.
- Area selections use overlapping or nearby semantics nodes plus source candidates when source-index evidence is available.

## Current Constraints

- There is no Select/Navigate toggle. Navigation is the default idle behavior.
- Navigation remains debug-only and limited to one-step `back`, `tap`, and `swipe`.
- Add freezes the preview only; it does not save.
- Save promotes the frozen preview once and connects every pending item from that frozen preview to the same `screenId`.
- Live preview frames are not feedback session history and are not appended to `FeedbackSession.screens`.
- Markdown handoff is compact and agent-facing: request, target evidence, and likely source. JSON remains the complete tool contract with IDs and paths.

## Remaining Follow-Ups

- Browser-side auto-refresh for agent resolution status can still be improved. Session polling is separate from live-preview polling.
- The console can display agent resolution summaries, but richer filtering by item status would make long sessions easier to scan.
- Connected-device smoke coverage should be run when a usable device is attached, because desktop-only tests cannot verify the full browser/device loop.
