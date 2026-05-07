# FixThis MCP

FixThis MCP is the primary agent workflow for the feedback console. The in-app Copy Markdown and Copy JSON workflow still works without MCP.

## Repository Sample

In this repository the FixThis Studio sample Android app is exposed as Gradle project `:app`, with sources under `sample/` and application id `io.beyondwin.fixthis.sample`. The local smoke flow is:

```bash
./gradlew :app:installDebug
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

The `fixthis run` default install task is `:app:installDebug`.

## Architecture

`fixthis mcp` runs as a desktop stdio JSON-RPC server. It connects to the running debug app through the CLI bridge:

```text
MCP client
  -> fixthis mcp over stdio
  -> ADB forward
  -> localabstract:fixthis_<packageName>
  -> debug app sidekick
```

The Android app does not host the MCP server. The sidekick only exposes a local bridge inside the debug app process.

## Feedback Console

The feedback console is an MCP-owned local web UI. The MCP server owns the session queue and exposes it to agents through queue tools. The browser UI is the human review surface: a dark Studio workspace with persisted sessions on the left, live or frozen preview canvas in the center, and a mode-aware Inspector on the right. Its compact device control shows the active short device label plus `No device`, `Connecting`, `Connected`, or `Unavailable`.

Feedback console sessions are resumable. Workspace metadata and session-owned screenshot artifacts are stored under `.fixthis/feedback-sessions/` in the project root.

The console UI and local API can list, reopen, and close persisted sessions. Closing a session marks it `closed` without deleting its workspace files; closed sessions are skipped by default and included when callers pass `includeClosed`.

Typical flow:

1. Call `fixthis_open_feedback_console`.
2. Use the live preview to navigate the app.
3. Click Add to freeze the latest preview.
4. Select targets or visual areas and create one or more pending items.
5. Click Save once to persist one evidence snapshot for those pending items.
6. Call `fixthis_list_feedback`.
7. Call `fixthis_read_feedback`.
8. Make code changes and call `fixthis_resolve_feedback`.

The CLI command `fixthis console --package <applicationId>` opens the same local console for copy/export workflows.

Console workflow:

1. Select a connected ADB device from the compact device control. Offline, unauthorized, and otherwise unavailable devices are visible but not selectable.
2. Use the app normally from the console preview.
3. Click Add when ready to leave feedback on the current screen.
4. Select a UI target or drag a visual area and write a comment.
5. Click Add to Pending; numbered overlay markers and pending rows stay in sync.
6. Click Save once to store one evidence snapshot and all pending items.
7. Review the saved evidence group in the Inspector Draft view, including the persisted screenshot, numbered overlay, and comments.
8. Click Copy for compact Markdown or Send when ready to create a local handoff batch.

The console defaults to navigation and has no Select/Navigate toggle. Preview clicks navigate the app until Add freezes the latest preview for feedback targeting. Navigation remains debug-only and limited to one-step `back`, `tap`, and `swipe` actions.

Top bar actions are short session-level controls: Refresh, Add, Save, Copy, Send, New, and Close. Live preview interval options are Manual, 1s, 2s, and 5s; the default is 1s. Preview polling pauses while the browser tab is hidden and while the Add/frozen-preview flow is active.

Add freezes the latest preview only; it does not save. Multiple pending feedback items can be added to one frozen preview. Pending items support Focus and Delete before Save; deleting renumbers pending items so the pending list numbers and overlay numbers match.

Save promotes the frozen preview once into one persisted evidence snapshot and connects all pending items to the same `screenId`. The item's `screenId` field points to the evidence snapshot saved with that item batch, so multiple saved items can share one `screenId`. Later Add on the same visible app screen creates a new evidence snapshot after Save. Live preview frames are not session history: `FeedbackSession.screens` contains persisted evidence snapshots, not every preview frame.

Saved evidence groups can be expanded to review the persisted screenshot, numbered overlay, and saved comments. Send is local persistence, not an external AI API call. FixThis records a handoff batch in the feedback session so an MCP client can read the batch and decide what to do next.

## Setup Output

Run:

```bash
fixthis setup --package <applicationId>
```

The command prints MCP client config JSON:

```json
{
  "command": "fixthis",
  "args": ["mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", "/path/to/project"],
  "packageName": "io.beyondwin.fixthis.sample",
  "projectRoot": "/path/to/project"
}
```

Use the `command` and `args` fields as separate values. This avoids shell quoting issues for project paths with spaces.

## Stdio Rules

The MCP server reads newline-delimited JSON-RPC messages from stdin.

In normal stdio mode, it writes only JSON-RPC responses to stdout. All diagnostics and logs go to stderr. Any wrapper script must preserve that rule or the MCP client can fail to parse server output.

`fixthis-mcp --console` is an explicit non-stdio mode. It prints the console startup JSON once, then keeps the local console server alive.

Supported JSON-RPC methods:

- `initialize`
- `notifications/initialized`
- `notifications/cancelled`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`
- `ping`

## Tools

`fixthis_status`

Checks whether the debug app sidekick bridge is reachable. Returns package, activity, root count, source-index availability, and bridge status.

`fixthis_get_current_screen`

Inspects the current Compose screen and returns bridge screen data. It may include a latest screenshot resource URI when a screenshot artifact is already available in the MCP session.

`fixthis_get_ui_feedback`

Compatibility legacy single-feedback tool that starts in-app capture, waits for one submitted comment, and returns annotation JSON plus Markdown.

The `.fixthis/artifacts/` directory is a local, ignored screenshot cache for desktop-readable artifacts. It is not required for the in-app clipboard workflow.

`fixthis_verify_ui_change`

Checks whether expected text is present on the current screen. `expectedText` is required; `role` is an optional semantic hint.

`fixthis_open_feedback_console`

Opens or returns the local feedback console URL for the active MCP session.

Arguments:

- `packageName`: optional package override.
- `sessionId`: optional persisted feedback session to reopen.
- `newSession`: optional boolean. When true, create a new session instead of resuming the latest active one.

`fixthis_list_feedback_sessions`

Lists resumable feedback workspaces for the project. Pass `packageName` to filter by Android application id or `includeClosed` to include closed sessions.

`fixthis_capture_screen`

Captures the current Android screen into the active feedback session.

`fixthis_navigate_app`

Performs one debug-only `back`, `tap`, or `swipe` action and optionally captures the resulting screen. Arguments are `sessionId`, `action`, `x`, `y`, `direction`, `distance`, and `captureAfter`; `captureAfter` defaults to true.

`action` is required. `tap` requires finite `x` and `y` coordinates. `swipe` requires `direction` (`up`, `down`, `left`, or `right`); `distance`, when provided, must be finite and greater than zero. Unsupported arguments are rejected.

`fixthis_list_feedback`

Lists feedback queue summaries for the active feedback session, including draft item count, sent handoff batch count, and unresolved sent item count.

`fixthis_read_feedback`

Reads the feedback queue as annotation JSON and Markdown, optionally focused on one item. The JSON output preserves saved items, draft/sent delivery state, screens, and handoff batches. The Markdown output is a compact work queue for agents rather than a storage dump.

The JSON output preserves the full feedback session schema for tools that need exact IDs, paths, and tool contracts. The Markdown output is the compact agent-facing handoff view: it focuses on request, target evidence, and likely source, and intentionally omits internal IDs plus repeated storage metadata such as raw session, screen, item, batch, and screenshot artifact IDs.

`fixthis_resolve_feedback`

Marks a feedback item as resolved, needing clarification, or not fixed and stores the agent summary.

## Resources

Available resources:

- `fixthis://session/current`
- `fixthis://screen/current`
- `fixthis://annotation/latest`
- `fixthis://screenshot/latest/full.png`
- `fixthis://screenshot/latest/crop.png`
- `fixthis://source-index`

Screenshot resources return JSON containing a desktop-readable artifact path when available. They do not stream binary image data through MCP in V1.

## Cancellation And EOF

Long-running `tools/call` and `resources/read` requests can be cancelled with `notifications/cancelled`. Cancellation closes the active bridge request, removes the ADB forward when established, and does not emit a stale success response.

When stdin reaches EOF, the server cancels in-flight requests and exits the run loop.

## Local Bridge Limits

The bridge is a local debug-app bridge, not a remote service:

- It requires ADB access to a debuggable app.
- It reads the session token with `adb shell run-as`.
- It uses length-prefixed JSON frames over the forwarded local socket.
- It caps bridge frames and screenshot reads at 16 MiB.
- It rejects explicit screenshot paths; screenshot reads use the current annotation or current screen snapshot source with `kind=full` or `kind=crop`.
- It rejects missing or mismatched session tokens.

Common bridge failure cases are covered in [Troubleshooting](troubleshooting.md).
