# PointPatch MCP

PointPatch MCP is the primary agent workflow for the feedback console. The in-app Copy Markdown and Copy JSON workflow still works without MCP.

## Repository Sample

In this repository the sample Android app is exposed as Gradle project `:app`, with sources under `sample/`. The local smoke flow is:

```bash
./gradlew :app:installDebug
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
```

The `pointpatch run` default install task is `:app:installDebug`.

## Architecture

`pointpatch mcp` runs as a desktop stdio JSON-RPC server. It connects to the running debug app through the CLI bridge:

```text
MCP client
  -> pointpatch mcp over stdio
  -> ADB forward
  -> localabstract:pointpatch_<packageName>
  -> debug app sidekick
```

The Android app does not host the MCP server. The sidekick only exposes a local bridge inside the debug app process.

## Feedback Console

The feedback console is an MCP-owned local web UI. The MCP server owns the session queue and exposes it to agents through queue tools. The browser UI is the human review surface.

Feedback console sessions are resumable. Workspace metadata and session-owned screenshot artifacts are stored under `.pointpatch/feedback-sessions/` in the project root.

The console UI and local API can list, reopen, and close persisted sessions. Closing a session marks it `closed` without deleting its workspace files; closed sessions are skipped by default and included when callers pass `includeClosed`.

Typical flow:

1. Call `pointpatch_open_feedback_console`.
2. Capture one or more screens in the console.
3. Add feedback items on desktop snapshots.
4. Call `pointpatch_list_feedback`.
5. Call `pointpatch_read_feedback`.
6. Make code changes and call `pointpatch_resolve_feedback`.

The CLI command `pointpatch console --package <applicationId>` opens the same local console for copy/export workflows.

## Setup Output

Run:

```bash
pointpatch setup --package <applicationId>
```

The command prints MCP client config JSON:

```json
{
  "command": "pointpatch",
  "args": ["mcp", "--package", "io.github.pointpatch.sample", "--project-dir", "/path/to/project"],
  "packageName": "io.github.pointpatch.sample",
  "projectRoot": "/path/to/project"
}
```

Use the `command` and `args` fields as separate values. This avoids shell quoting issues for project paths with spaces.

## Stdio Rules

The MCP server reads newline-delimited JSON-RPC messages from stdin.

In normal stdio mode, it writes only JSON-RPC responses to stdout. All diagnostics and logs go to stderr. Any wrapper script must preserve that rule or the MCP client can fail to parse server output.

`pointpatch-mcp --console` is an explicit non-stdio mode. It prints the console startup JSON once, then keeps the local console server alive.

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

`pointpatch_status`

Checks whether the debug app sidekick bridge is reachable. Returns package, activity, root count, source-index availability, and bridge status.

`pointpatch_get_current_screen`

Inspects the current Compose screen and returns bridge screen data. It may include a latest screenshot resource URI when a screenshot artifact is already available in the MCP session.

`pointpatch_get_ui_feedback`

Compatibility legacy single-feedback tool that starts in-app capture, waits for one submitted comment, and returns annotation JSON plus Markdown.

The `.pointpatch/artifacts/` directory is a local, ignored screenshot cache for desktop-readable artifacts. It is not required for the in-app clipboard workflow.

`pointpatch_verify_ui_change`

Checks whether expected text is present on the current screen. `expectedText` is required; `role` is an optional semantic hint.

`pointpatch_open_feedback_console`

Opens or returns the local feedback console URL for the active MCP session.

Arguments:

- `packageName`: optional package override.
- `sessionId`: optional persisted feedback session to reopen.
- `newSession`: optional boolean. When true, create a new session instead of resuming the latest active one.

`pointpatch_list_feedback_sessions`

Lists resumable feedback workspaces for the project. Pass `packageName` to filter by Android application id or `includeClosed` to include closed sessions.

`pointpatch_capture_screen`

Captures the current Android screen into the active feedback session.

`pointpatch_navigate_app`

Performs one debug-only `back`, `tap`, or `swipe` action and optionally captures the resulting screen. Arguments are `sessionId`, `action`, `x`, `y`, `direction`, `distance`, and `captureAfter`; `captureAfter` defaults to true.

`action` is required. `tap` requires finite `x` and `y` coordinates. `swipe` requires `direction` (`up`, `down`, `left`, or `right`); `distance`, when provided, must be finite and greater than zero. Unsupported arguments are rejected.

`pointpatch_list_feedback`

Lists feedback queue summaries for the active feedback session.

`pointpatch_read_feedback`

Reads the feedback queue as annotation JSON and Markdown, optionally focused on one item.

`pointpatch_resolve_feedback`

Marks a feedback item as resolved, needing clarification, or not fixed and stores the agent summary.

## Resources

Available resources:

- `pointpatch://session/current`
- `pointpatch://screen/current`
- `pointpatch://annotation/latest`
- `pointpatch://screenshot/latest/full.png`
- `pointpatch://screenshot/latest/crop.png`
- `pointpatch://source-index`

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
