# FixThis MCP

FixThis MCP is the primary agent workflow for the feedback console. The Android app only shows MCP browser connection status; selection, comments, `Copy Prompt`, `Send Agent`, and persistence happen in the desktop browser console.

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

The app status pill is heartbeat-driven. It shows `MCP connected` only while an
authorized browser-console heartbeat is recent; ordinary status, inspect, or
capture calls do not by themselves mark the browser connected.

## Feedback Console

The feedback console is an MCP-owned local web UI. The MCP server owns the session queue and exposes it to agents through queue tools. The browser UI is the human review surface: a dark Studio workspace with persisted sessions on the left, live or frozen preview canvas in the center, and a mode-aware Inspector on the right. Its compact device control shows the active short device label plus `No device`, `Connecting`, `Connected`, or `Unavailable`.

Feedback console sessions are resumable. Workspace metadata and session-owned screenshot artifacts are stored under `.fixthis/feedback-sessions/` in the project root.

The console UI and local API can list, reopen, and close persisted sessions. Closing a session marks it `closed` without deleting its workspace files; closed sessions are skipped by default and included when callers pass `includeClosed`.

The local console serves a per-server browser token and requires `X-FixThis-Console-Token` on mutating `/api/*` requests. Mutating requests with a non-localhost `Origin` are rejected. This protects local mutation endpoints such as app launch, navigation, capture, draft writes, and handoff creation from ordinary cross-origin web pages while keeping the console localhost-only.

The current console contract is documented in [`docs/feedback-console-contract.md`](feedback-console-contract.md); the shipped workflow uses `Annotate`, `Add annotation`, `Copy Prompt`, and `Send Agent`.

Typical flow:

1. Call `fixthis_open_feedback_console`.
2. Start from the connection card. Click `Start`, choose a device when asked, or use `Open app`, `Reconnect`, or `Try again` until the card reaches `Ready`.
3. Use the live preview to navigate the app.
4. Click `Annotate` to freeze the latest preview.
5. Select targets or visual areas and click `Add annotation` to create one or more pending annotations.
6. Click `Copy Prompt` to persist written pending annotations when needed and copy compact prompt text, or click `Send Agent` to persist them and create a local handoff batch.
7. Call `fixthis_list_feedback`.
8. Call `fixthis_read_feedback`.
9. Make code changes and call `fixthis_resolve_feedback`.

The CLI command `fixthis console --package <applicationId>` opens the same local console for copy/export workflows.

Console workflow:

1. Open the connection card. `Start` finds the selected or only ready Android device, launches the debug app when appropriate, and checks the sidekick bridge.
2. If the card shows `Choose a device`, select one connected device from the compact device control. Offline, unauthorized, and otherwise unavailable devices are visible but not selectable.
3. If the card shows `Open the app`, `Reconnect`, or `Check your phone`, use the card action before taking new live preview or navigation actions.
4. When the card shows `Ready`, use the app normally from the console preview.
5. Click `Annotate` when ready to leave feedback on the current screen.
6. Select a UI target or drag a visual area and write a comment.
7. Click `Add annotation`; numbered overlay markers and pending rows stay in sync.
8. Review the draft evidence group in the Inspector Draft view, including the frozen screenshot, numbered overlay, and comments.
9. Click `Copy Prompt` for compact Markdown or `Send Agent` when ready to create a local handoff batch.

The console defaults to `Select` mode. Preview clicks navigate the app until `Annotate` freezes the latest preview for feedback targeting. Navigation remains debug-only and limited to one-step `back`, `tap`, and `swipe` actions.

Top bar actions are short session-level controls: device selection, connection state, `Refresh devices`, `Clear selection`, `Copy Prompt`, and `Send Agent`. Canvas controls include `Select`, `Annotate`, `Add annotation`, and `Exit Annotate`. Live preview interval options are Manual, 1s, 2s, and 5s; the default is 1s. Preview polling pauses while the browser tab is hidden and while the `Annotate` frozen-preview flow is active.

`Annotate` freezes the latest preview only; it does not write a session item by itself. Multiple pending annotations can be added to one frozen preview with `Add annotation`. Pending items support Focus and Delete before they are persisted; deleting renumbers pending items so the pending list numbers and overlay numbers match.

`Copy Prompt` and `Send Agent` persist written pending annotations when needed, promote the frozen preview into one persisted evidence snapshot, and connect those items to the same `screenId`. The item's `screenId` field points to the evidence snapshot saved with that item batch, so multiple saved items can share one `screenId`. During persistence, FixThis derives optional `targetEvidence` for each item from the frozen preview's captured merged semantics nodes and source-index candidates. Later `Annotate` work on the same visible app screen can create another evidence snapshot when pending annotations are persisted. Live preview frames are not session history: `FeedbackSession.screens` contains persisted evidence snapshots, not every preview frame.

Saved evidence groups can be expanded to review the persisted screenshot, numbered overlay, and saved comments. `Send Agent` is local persistence, not an external AI API call. FixThis records a handoff batch in the feedback session so an MCP client can read the batch and decide what to do next.

### Connection Recovery API

The browser console owns connection recovery through local HTTP endpoints on the MCP process:

- `GET /api/connection`: returns the current console connection status.
- `POST /api/app/launch`: launches the selected or only ready debug app when the current status is `WELCOME` or `OPEN_APP`, then returns the next connection status.

`/api/connection` returns a `ConsoleConnectionStatus` object:

- `state`: `WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`, `CHOOSE_DEVICE`, `CHECK_PHONE`, or `UNSUPPORTED_BUILD`.
- `headline` and `message`: user-facing recovery copy.
- `primaryAction`: `START`, `OPEN_APP`, `RECONNECT`, `TRY_AGAIN`, `CHOOSE_DEVICE`, or `CAPTURE` when one action is available.
- `selectedDevice`, `devices`, and `packageName`: local device/package context.
- `canCapture` and `canNavigate`: whether live bridge actions are currently allowed.
- `canUseCachedWork`: whether saved/draft console work can remain visible.
- `details`: raw `deviceState`, `bridgeState`, and `rawError` for troubleshooting.

When the bridge or device drops, the console keeps pending browser draft work and the last preview visible, marks the preview stale, and disables new bridge actions until the status returns to `READY`.

## Setup Output

Run:

```bash
fixthis setup --package <applicationId>
```

By default, the command prints MCP client config JSON:

```json
{
  "command": "fixthis",
  "args": ["mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", "/path/to/project"],
  "packageName": "io.beyondwin.fixthis.sample",
  "projectRoot": "/path/to/project"
}
```

Use the `command` and `args` fields as separate values. This avoids shell quoting issues for project paths with spaces.

To let FixThis merge the MCP server config into supported agent files, run:

```bash
fixthis setup --package <applicationId> --write --target all
```

Supported targets are `codex`, `claude`, and `all`. Codex writes `~/.codex/config.toml`; Claude writes the project-local `.claude/settings.json`. Use `--dry-run` with `--write` to print the target path and rendered merged config without writing:

```bash
fixthis setup --package <applicationId> --write --target codex --dry-run
```

When an Android SDK is detected, the written config includes `ANDROID_HOME` so MCP clients do not need a shell wrapper to load profile environment variables.

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

Checks whether the debug app sidekick bridge is reachable. Returns package, activity, root count, source-index availability, and bridge status. Bridge status includes sidekick and bridge protocol versions plus `capabilities`, currently `targetEvidence`, supported `detailModes`, and whether experimental composable identity is enabled.

`fixthis_get_current_screen`

Inspects the current Compose screen and returns bridge screen data. It may include a latest screenshot resource URI when a screenshot artifact is already available in the MCP session.

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

Arguments:

- `sessionId`: optional feedback session id. If omitted, the active session is used.
- `itemId`: optional feedback item id to focus the returned payload.
- `detailMode`: optional Markdown detail level. Supported values are `compact`, `precise`, and `full`; the default is `precise`.

`detailMode` affects only the Markdown content. The JSON content remains complete and includes all persisted session evidence, including optional `targetEvidence`.

The JSON output preserves the full feedback session schema for tools that need exact IDs, paths, and tool contracts. The Markdown output is the compact agent-facing handoff view: it focuses on request, target evidence, and likely source, and intentionally omits internal IDs plus repeated storage metadata such as raw session, screen, item, batch, and screenshot artifact IDs.

`fixthis_resolve_feedback`

Marks a feedback item as resolved, needing clarification, or not fixed and stores the agent summary.

### Optional SourceCandidate fields

`SourceCandidate` objects appear in the JSON payload returned by `fixthis_read_feedback` under each feedback item's `targetEvidence.sourceCandidates` list. The following fields are optional and were added to carry confidence and risk metadata. Older persisted sessions (written before this feature was introduced) deserialize correctly because all new fields are optional; the formatter emits them only when they are present.

| Field | Type | Description |
| --- | --- | --- |
| `ranking` | `Int` | 1-based position in the sorted candidate list. The top candidate has `ranking=1`. |
| `scoreMargin` | `Double` | `(topScore - nextScore) / max(topScore, 0.001)`. A value of `1.0` indicates a single candidate with no competition. Lower values indicate a closer race between candidates. |
| `evidenceStrength` | `String` | One of `STRONG`, `MODERATE`, or `WEAK`. Internal metadata about how semantically reliable the matching evidence is. This is not the same as the displayed confidence (`high`/`medium`/`low`) shown in the compact handoff line; `evidenceStrength` describes signal quality while displayed confidence is the final classification derived from the full candidate ranking. |
| `riskFlags` | `List<String>` | Empty list for confident matches. May include tokens such as `AREA_SELECTION` (match derived from a drawn visual area rather than a precise node selection) or `AMBIGUOUS` (multiple candidates scored similarly). |
| `caution` | `String?` | Human-readable explanation present when `riskFlags` is non-empty. Absent (`null` or missing) for clean matches. |

These fields inform the compact `src?` handoff line: `why=` tokens come from the candidate's reason list, and the `risk=` token summarises the dominant risk flag when `riskFlags` is non-empty. The `evidenceStrength` field is internal metadata and is not the same as the displayed confidence (`high`/`medium`/`low`) in the handoff line. The full JSON remains available for tools that need the raw values.

## Resources

Available resources:

- `fixthis://session/current`
- `fixthis://screen/current`
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
