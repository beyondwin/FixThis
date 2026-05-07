# MCP-First Feedback Console Design

Date: 2026-05-04

## Summary

FixThis should move from an app-first single annotation flow to an
MCP-first feedback session. The MCP server owns a multi-screen feedback queue,
and a local desktop web console becomes the human-facing UI for capturing and
editing feedback.

The Android device or emulator remains the source of truth for the running app.
The desktop console becomes the comfortable review surface: view Android screen
snapshots, click targets, write feedback with a real keyboard, collect feedback
across screens, and let the AI agent read the queue through MCP.

## Product Decision

Previous FixThis V1 docs treated MCP as an optional advanced workflow. This
design intentionally changes that product center:

- MCP is the primary agent workflow.
- The local web console is the primary human workflow.
- Copy Markdown and Copy JSON remain as fallback/export paths.
- CLI remains useful as a non-MCP entrypoint, but it is not the product center.

## Goals

- Provide an Agentation-like desktop workflow for Android Compose feedback.
- Remove the need to type comments on a device or emulator.
- Support multi-screen feedback queues, not just one annotation at a time.
- Let agents list, read, and resolve feedback through MCP tools.
- Keep FixThis local-first and debug-only.
- Reuse the existing sidekick bridge, screenshot, semantics, source candidate,
  CLI, and MCP foundations where possible.

## Non-Goals

- Do not become a general mobile automation framework.
- Do not add full remote-control behavior such as arbitrary typing, navigation,
  or scripted exploration in this design.
- Do not require Android app network permissions.
- Do not stream binary screenshots through MCP in the first version.
- Do not require test tags or accessibility services.

## User Flow

1. The user or agent opens a FixThis feedback session.
2. FixThis returns or opens a local web console URL.
3. The console captures the current Android screen through the MCP server and
   sidekick bridge.
4. The user clicks or drags on the desktop snapshot to create feedback targets.
5. The user writes comments in the desktop console.
6. The user navigates the app on the device or emulator, then captures additional
   screens into the same queue.
7. The agent lists and reads the queue through MCP.
8. The agent makes code changes, verifies the UI, and marks feedback items as
   resolved or needing clarification.
9. The console reflects feedback status changes.

## Architecture

```text
AI client
  <-> fixthis mcp
        <-> local web console
        <-> ADB/local bridge
              <-> debug app sidekick
```

The MCP server owns the feedback session. It exposes both an MCP tool API for
agents and a localhost web UI for humans. The web console does not own separate
state; it reads and writes the MCP session queue.

The Android debug app remains focused on runtime data capture:

- screenshot capture
- Compose semantics inspection
- source candidate hints
- current activity/status
- last annotation compatibility

The Android app does not host the web UI or MCP server.

## Components

### MCP Feedback Session

The session is an in-memory queue owned by the MCP server process. A later
version can persist sessions, but the first version should keep state scoped to
the MCP server lifetime.

Responsibilities:

- resolve package/project/device
- open and serve the local web console
- store captured screens
- store feedback items
- expose queue state through MCP tools
- write screenshot artifacts under `.fixthis/artifacts/`
- track item status and agent resolution summaries

### Local Web Console

The console uses a three-pane layout:

- left: captured screen list
- center: selected Android screen snapshot
- right: feedback queue and selected item editor

Primary actions:

- capture current screen
- refresh selected screen snapshot
- click or drag target on a snapshot
- edit feedback comment
- send queue to agent
- copy Markdown
- copy JSON

The console should use a localhost URL. It should show connection and error
states plainly instead of failing silently.

### Android Sidekick Bridge

The bridge continues to provide runtime data to the desktop process. It should
not become a web server and should not gain network permissions.

The existing `startFeedbackCapture` path can remain for compatibility, but the
new console flow should prefer explicit screen capture and queue item creation
through the MCP session.

## Queue Model

### Feedback Session

Fields:

- `sessionId`
- `packageName`
- `projectRoot`
- `createdAt`
- `updatedAt`
- `screens`
- `items`
- `status`

Session statuses:

- `active`
- `ready_for_agent`
- `closed`

### Captured Screen

Fields:

- `screenId`
- `capturedAt`
- `activityName`
- `displayName`
- `screenshot`
- `roots`
- `sourceIndexAvailable`
- `errors`

The screenshot should include desktop-readable artifact paths when available.

### Feedback Item

Fields:

- `itemId`
- `screenId`
- `createdAt`
- `updatedAt`
- `target`
- `selectedNode`
- `nearbyNodes`
- `sourceCandidates`
- `screenshotCrop`
- `comment`
- `status`
- `agentSummary`

Item statuses:

- `open`
- `ready`
- `in_progress`
- `resolved`
- `needs_clarification`
- `wont_fix`

Targets can be node-backed or area-backed. Area-backed targets are valid because
visual spacing, canvas regions, and low-semantics UI still need feedback.

## MCP Tools

### `fixthis_open_feedback_console`

Opens or returns the local web console URL for the active feedback session.
Returns package, project root, session id, URL, and bridge status.

### `fixthis_capture_screen`

Captures the current Android screen into the active session. Returns a screen
summary and any non-fatal capture errors.

### `fixthis_list_feedback`

Returns queue summaries for the current session. This is the agent's fast path
for understanding available feedback.

### `fixthis_read_feedback`

Returns the full queue or a selected item as annotation JSON and Markdown,
including screenshot artifact paths when available.

### `fixthis_resolve_feedback`

Updates feedback item status and stores the agent's resolution summary.

Allowed statuses are `resolved`, `needs_clarification`, and `wont_fix`.

### Existing Tools

Keep:

- `fixthis_status`
- `fixthis_get_current_screen`
- `fixthis_verify_ui_change`

Deprecate or wrap:

- `fixthis_get_ui_feedback`

The wrapper can create a single-item feedback session and return the same JSON
and Markdown shape expected by existing clients.

## CLI Entry Points

Add this CLI entrypoint:

```bash
fixthis console --package <applicationId>
```

The command should open or print the same local console URL used by MCP. MCP
users can ask the agent to open the console; non-MCP users can run the CLI
directly and use Copy Markdown or Copy JSON.

## Error Handling

The console and tools should return explicit states:

- `NO_DEVICE`: no connected device or emulator
- `MULTIPLE_DEVICES`: target device selection is required
- `APP_NOT_RUNNING`: package is known but app is not running
- `SIDEKICK_UNREACHABLE`: debug sidekick bridge connection failed
- `SCREEN_CAPTURE_FAILED`: screenshot or semantics capture failed
- `STALE_SCREEN`: item is tied to an older snapshot
- `MCP_SESSION_CLOSED`: console is open but the MCP session has ended

Partial success is acceptable. For example, a screen capture may include a
screenshot but report semantics errors, or include semantics but no screenshot.
Those errors should travel with the screen or feedback item.

## Privacy And Security

FixThis remains local-first:

- serve the console on localhost
- do not add Android app network permissions
- keep screenshots under `.fixthis/artifacts/`
- keep generated brainstorming files under `.superpowers/brainstorm`
- keep screenshot artifacts and brainstorming files out of git
- continue redacting editable and password text from semantics
- include explicit screenshot warnings in exported content

The MCP server must continue writing only JSON-RPC responses to stdout. Console
logs and diagnostics belong on stderr or the local web UI.

## Testing

Unit tests:

- queue store behavior
- screen and feedback item serialization
- MCP tool schemas and results
- Markdown and JSON queue formatting
- stale screen status handling
- resolution status transitions

Integration tests:

- MCP server with fake bridge
- local web session creation
- screen capture into queue
- feedback list/read/resolve flow
- compatibility wrapper for `fixthis_get_ui_feedback`

CLI tests:

- `fixthis console` resolves package/project
- command prints a local console URL
- no-device and multiple-device failures are clear

Android-side tests:

- keep existing screenshot, semantics, bridge, and overlay tests
- add bridge tests only if new bridge methods are introduced

Manual smoke:

1. Launch the sample debug app.
2. Open feedback console through MCP or CLI.
3. Capture two screens.
4. Add at least three feedback items.
5. Read the queue through MCP.
6. Copy Markdown and JSON from the web console.
7. Verify one UI change and resolve its feedback item.

## Rollout

1. Introduce the MCP session queue and tool API behind tests.
2. Add the local web console against fake queue data.
3. Wire console actions to MCP session state.
4. Wire screen capture to the existing bridge.
5. Add CLI entrypoint.
6. Add compatibility wrapper for the old single feedback tool.
7. Update README, MCP docs, troubleshooting, privacy, and output schema.

## Acceptance Criteria

- A user can open a desktop console for a running debug app.
- The console can capture the current Android screen.
- The user can create multiple feedback items on a screen snapshot.
- The user can capture another screen into the same queue.
- MCP can list, read, and resolve queue items.
- Copy Markdown and Copy JSON export the same queue data.
- Device keyboard entry is not required for feedback comments.
- The Android app remains debug-only and local-first.
