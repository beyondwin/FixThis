# PointPatch MCP

PointPatch MCP is optional. The in-app Copy Markdown and Copy JSON workflow works without MCP.

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

It writes only JSON-RPC responses to stdout. All diagnostics and logs go to stderr. Any wrapper script must preserve that rule or the MCP client can fail to parse server output.

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

Starts feedback capture in the app, waits for the user to select UI and submit a comment, pulls screenshots into `.pointpatch/artifacts/` when present, and returns annotation JSON plus Markdown.

The `.pointpatch/artifacts/` directory is a local, ignored screenshot cache for desktop-readable artifacts. It is not required for the in-app clipboard workflow.

`pointpatch_verify_ui_change`

Checks whether expected text is present on the current screen. `expectedText` is required; `role` is an optional semantic hint.

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
- It rejects explicit screenshot paths; screenshot reads use the current annotation and `kind=full` or `kind=crop`.
- It rejects missing or mismatched session tokens.

Common bridge failure cases are covered in [Troubleshooting](troubleshooting.md).
