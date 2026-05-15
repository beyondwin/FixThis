# FixThis MCP

FixThis MCP is the primary agent workflow for the feedback console. The Android app only shows MCP browser connection status; selection, comments, `Copy Prompt`, `Save to MCP`, and persistence happen in the desktop browser console.

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

Saved annotations, preview screenshots, screen artifacts, pending recovery
drafts, and SSE session/preview updates are scoped to the feedback session that
created them. Console routes use explicit session ids for artifact lookup and
item mutation, event payloads carry top-level `sessionId` where they can update
detail or preview state, and saved item numbers remain monotonic rather than
being renumbered after deletes or session reopens.

The current console contract is documented in [`docs/reference/feedback-console-contract.md`](feedback-console-contract.md); the shipped workflow uses `Annotate`, `Add annotation`, `Copy Prompt`, and `Save to MCP`.

Typical flow:

1. Call `fixthis_open_feedback_console`.
2. Start from the connection card. Click `Start`, choose a device when asked, or use `Open app`, `Reconnect`, or `Try again` until the card reaches `Ready`.
3. Use the live preview to navigate the app.
4. Click `Annotate` to freeze the latest preview.
5. Select targets or visual areas and click `Add annotation` to create one or more pending annotations.
6. Click `Copy Prompt` to persist written pending annotations when needed and copy compact prompt text, or click `Save to MCP` to persist them and mark the items as ready for an agent to claim.
7. Call `fixthis_list_feedback` (defaults to SENT and unfinished items).
8. Call `fixthis_read_feedback({itemId})` for the item to work on.
9. Call `fixthis_claim_feedback({itemId})` before editing code.
10. Make code changes and call `fixthis_resolve_feedback({itemId, status, summary})`.

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
9. Click `Copy Prompt` for compact Markdown or `Save to MCP` when ready to mark items as sent so an agent can claim them through MCP.

The console defaults to `Select` mode. Preview clicks navigate the app until `Annotate` freezes the latest preview for feedback targeting. Navigation remains debug-only and limited to one-step `back`, `tap`, and `swipe` actions.

Top bar actions are short session-level controls: device selection, connection state, `Refresh devices`, the device `x` clear icon, `Copy Prompt`, and `Save to MCP`. Canvas controls include `Select`, `Annotate`, `Add annotation`, and `Exit Annotate`. Live preview interval options are Manual, 1s, 2s, and 5s; the default is 1s. Preview polling pauses while the browser tab is hidden and while the `Annotate` frozen-preview flow is active.

`Annotate` freezes the latest preview only; it does not write a session item by itself. Multiple pending annotations can be added to one frozen preview with `Add annotation`. Pending items support Focus and Delete before they are persisted; deleting renumbers pending items so the pending list numbers and overlay numbers match.

`Copy Prompt` and `Save to MCP` persist written pending annotations when needed, promote the frozen preview into one persisted evidence snapshot, and connect those items to the same `screenId`. If a draft contains written comments plus pin-only residual targets, only the written subset is persisted; Copy Prompt keeps residual pins browser-local, while Save to MCP discards them as part of completing the handoff. The item's `screenId` field points to the evidence snapshot saved with that item batch, so multiple saved items can share one `screenId`. During persistence, FixThis derives optional `targetEvidence` and `targetReliability` for each item from the frozen preview's captured merged semantics nodes, source-index candidates, and save-time screen integrity checks. Later `Annotate` work on the same visible app screen can create another evidence snapshot when pending annotations are persisted. Live preview frames are not session history: `FeedbackSession.screens` contains persisted evidence snapshots, not every preview frame.

Before persistence, the server compares the frozen preview fingerprint with a
lightweight live capture when both fingerprints exist. If they differ,
`/api/items/batch` returns HTTP 409 with `screen_fingerprint_mismatch`, and the
browser prompts the user to re-capture, force-save, or cancel. If either
fingerprint is unavailable, saving continues and the response includes
`X-FixThis-Fingerprint-Unavailable-Reason` for diagnostics.

Pending annotations also have a browser-local recovery mirror. The console
stores schema-v2 DraftWorkspace envelopes under
`localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`, with a
per-session index at `localStorage["fixthis.workspace.index.<sessionId>"]`.
Each envelope carries the immutable frozen annotation context, preview id,
screen metadata, screenshot URL, frozen timestamp, pending items, revision,
lifecycle, and undo/redo history. Legacy schema-v1
`localStorage["fixthis.pending.<sessionId>"]` envelopes are still readable and
migrated into schema-v2 recovery workspaces. On reload or reattach, the console
asks the user to Recover, Recapture, or Discard before exposing pending rows
again. Deleting a session clears both its schema-v2 workspace entries and the
legacy `fixthis.pending.<sessionId>` mirror from browser storage.

Saved evidence groups can be expanded to review the persisted screenshot, numbered overlay, and saved comments. `Save to MCP` is local persistence, not an external AI API call. FixThis marks the affected items with `delivery: sent` so MCP clients can list them through `fixthis_list_feedback`, claim one with `fixthis_claim_feedback`, and resolve it with `fixthis_resolve_feedback`. Sessions that contain sent items remain in the main History list; while an agent is actively working on an item the row shows a `working` pip that is driven by the item's `in_progress` status.

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

> **Behavior change (May 2026)**: `fixthis_list_feedback` and
> `fixthis_read_feedback` now default to returning only items that were
> sent to the agent (`delivery: sent`) and are not yet resolved. Pass
> `includeAll: true` to restore the previous "all items" behavior. The
> Sent History drawer in the browser console has been removed; sessions
> remain in the main History list with a `working` pip while an agent is
> active.

`fixthis_status`

Checks whether the debug app sidekick bridge is reachable. Returns package, activity, root count, source-index availability, and bridge status. Bridge status includes sidekick and bridge protocol versions plus `capabilities`, currently `targetEvidence`, supported `detailModes`, and whether experimental composable identity is enabled. `targetReliability` is derived by the MCP/session layer from saved evidence; it is not a separate bridge capability.

Responses also include an `installStale` boolean and `installStaleReason` string. When `true`, the host has source files modified after the device APK's last install time, so source coordinates returned by other tools may not match what you can edit; reinstall the debug APK before trusting them. `newerSourceFiles` lists up to three example paths. When `installEpochMillis` is unavailable (older sidekick), the check is skipped and `installStale` is `false` with an explanatory reason.

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

Lists feedback queue summaries for the active feedback session, including draft item count, sent handoff batch count, unresolved sent item count, and the count of items currently `in_progress` (claimed by an agent).

By default this tool returns only items with `delivery: sent` whose `status` is not `resolved`/`wont_fix`. This is the focused agent work queue. Pass `includeAll: true` to receive every item, including drafts and finished items. Older callers that want the previous behavior should set `includeAll: true` explicitly.

Arguments:

- `sessionId`: optional feedback session id. If omitted, the active session is used.
- `includeAll`: optional boolean. When `true`, return every item regardless of `delivery` and `status`. Default `false`.

`fixthis_read_feedback`

Reads the feedback queue as annotation JSON and Markdown, optionally focused on one item. The JSON output preserves saved items, draft/sent delivery state, screens, and handoff batches. The Markdown output is a compact work queue for agents rather than a storage dump.

Arguments:

- `sessionId`: optional feedback session id. If omitted, the active session is used.
- `itemId`: optional feedback item id to focus the returned payload. When supplied, the requested item is always returned regardless of the default filter — this lets agents act on a specific id pasted from `Copy Prompt` even if it is still in `delivery: draft`.
- `includeAll`: optional boolean. When `true`, the queue listing in JSON and Markdown includes drafts and resolved items. Default `false` (matches the focused list described above).
- `detailMode`: optional Markdown detail level. Supported values are `compact`, `precise`, and `full`; the default is `precise`.

`detailMode` affects only the Markdown content. The JSON content remains complete and includes all persisted session evidence, including optional `targetEvidence` and `targetReliability`.

The JSON output preserves the full feedback session schema for tools that need exact IDs, paths, and tool contracts. The Markdown output is the compact agent-facing handoff view: it focuses on request, target evidence, and likely source, and intentionally omits internal IDs plus repeated storage metadata such as raw session, screen, item, batch, and screenshot artifact IDs.

`compactMarkdown` includes target confidence lines when reliability metadata is
present. Low-confidence or warning items remain actionable, but agents should
verify them before editing. JSON output includes the complete optional
`targetReliability` object on each item.

Compact Markdown may include a `Handoff quality:` line near the top of the
prompt. This is an aggregate warning summary for the rendered item set; it is
not a blocker. Agents should use it to decide how much screenshot/code
verification is needed before editing.

Each compact item includes a `target:` line before the coordinate `box=` line.
The target line is a redaction-safe semantic summary of what the user selected.
Source candidates remain hints; the target line and screenshot are the primary
evidence for what the user meant.

The compact Markdown handoff also emits a per-item `id:` token (the feedback item id) and ends with an `agent_protocol:` footer that documents the claim/resolve contract inline. The same compact text is what the `Copy Prompt` button puts on the clipboard, so an agent that only sees the pasted prompt can still reference items by id and call `fixthis_claim_feedback` / `fixthis_resolve_feedback` over MCP.

`fixthis_claim_feedback`

Marks a feedback item as `in_progress` to signal that an agent has begun working on it. Call this after `fixthis_read_feedback` and before making any code changes. The browser console reflects the state change over the `/api/events` SSE stream when available, with ETag-conditional polling on `/api/sessions` and `/api/session` retained as fallback.

Arguments:

- `sessionId`: optional feedback session id. If omitted, the active session is used.
- `itemId`: required feedback item id to claim.
- `agentNote`: optional short string stored with the claim event for human review.

The server rejects claims on already-resolved items.

`fixthis_resolve_feedback`

Marks a feedback item as resolved, needing clarification, or not fixed and stores the agent summary. Pair this with `fixthis_claim_feedback`: claim before editing code, resolve after the change is complete or the agent has decided not to fix the item.

Arguments:

- `sessionId`: optional feedback session id. If omitted, the active session is used.
- `itemId`: required feedback item id to resolve.
- `status`: required status. Must be one of `resolved`, `needs_clarification`, or `wont_fix`.
- `summary`: optional agent-facing summary or reason. The browser console shows this on the saved annotation detail.

### Optional SourceCandidate fields

`SourceCandidate` objects appear in the JSON payload returned by `fixthis_read_feedback` under each feedback item's `sourceCandidates` list. The following fields are optional and were added to carry confidence and risk metadata. Older persisted sessions (written before this feature was introduced) deserialize correctly because all new fields are optional; the formatter emits them only when they are present.

| Field | Type | Description |
| --- | --- | --- |
| `ranking` | `Int` | 1-based position in the sorted candidate list. The top candidate has `ranking=1`. |
| `scoreMargin` | `Double` | Populated by the matcher when at least one runner-up exists; otherwise null. Computed as `topScore - nextScore`; a higher value indicates a more decisive top candidate. |
| `evidenceStrength` | `String` | One of `STRONG`, `MEDIUM`, or `WEAK`. Internal metadata about how semantically reliable the matching evidence is. This is not the same as the displayed confidence (`high`/`medium`/`low`) shown in the compact handoff line; `evidenceStrength` describes signal quality while displayed confidence is the final classification derived from the full candidate ranking. |
| `riskFlags` | `List<String>` | Empty list for confident matches. May include tokens such as `AREA_SELECTION` (match derived from a drawn visual area rather than a precise node selection) or `AMBIGUOUS` (multiple candidates scored similarly). |
| `caution` | `String?` | Human-readable explanation present when `riskFlags` is non-empty. Absent (`null` or missing) for clean matches. |

Each `SourceCandidate` may also carry `stale: true | false | null` and `staleReason`. `true` means the host source line no longer matches the index excerpt — do not edit by file:line; locate the symbol elsewhere first. `null` means the candidate could not be verified (no excerpt or no line, e.g. an XML resource entry).

These fields inform the compact `candidates:` block in the v2 handoff prompt: `matched=[...]` tokens come from the candidate's reason list, and caution text is emitted as a `note:` line when `riskFlags` is non-empty. The full JSON remains available for tools that need the raw values.

### Agent claim/resolve protocol

After `Save to MCP` is clicked (or after the user pastes a Copy Prompt
output to an agent), the agent calls:

1. `fixthis_list_feedback` (default returns SENT and unfinished items;
   pass `includeAll: true` to receive everything).
2. `fixthis_read_feedback({itemId})` for the item it intends to work on.
3. `fixthis_claim_feedback({itemId, agentNote?})` BEFORE making any code
   change. This sets the item's `status` to `in_progress`. The user's
   browser console reflects the change over `/api/events` when available,
   with ETag polling as fallback.
4. After completing the work, `fixthis_resolve_feedback({itemId, status,
   summary})` with `status` of `resolved`, `wont_fix`, or
   `needs_clarification`.

The compact handoff prompt (returned by `fixthis_read_feedback` and
copied by the `Copy Prompt` button) embeds an `agent_protocol:` footer
that documents this contract inline; an agent that sees only the pasted
text can still address items by `id` and call MCP tools.

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

Common bridge failure cases are covered in [Troubleshooting](../guides/troubleshooting.md).
