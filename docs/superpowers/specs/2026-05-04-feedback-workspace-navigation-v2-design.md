# Feedback Workspace And Limited Navigation V2 Design

Date: 2026-05-04

## Summary

FixThis V2 should turn the V1 in-memory feedback console into a resumable
review workspace and add a narrow set of app navigation actions. The MCP server
continues to own feedback state, but that state is durably saved under the
project's `.fixthis/` directory so a review can survive MCP restarts, browser
refreshes, and interrupted agent sessions.

This design intentionally includes both V2A and V2B in one implementation
scope:

- V2A: persistent feedback workspaces with session listing and resume
- V2B: limited single-step navigation actions: `back`, `tap`, and `swipe`

The two pieces belong together because navigation creates additional captured
screens, and those screens need a stable workspace identity, artifact ownership,
and stale-state semantics.

## Product Decision

The better V2 is not a broad console redesign. It is a workflow reliability
upgrade:

- keep the V1 MCP-first queue as the product center
- make the queue durable and resumable
- let users move through the app without constantly switching back to the
  emulator
- avoid becoming a general mobile automation framework

A polished visual redesign can follow after workspace and navigation behavior
are stable. V2 includes only the UI polish required to make session resume and
navigation understandable.

## Goals

- Persist feedback sessions to disk and reload them when MCP or CLI console
  processes restart.
- Let users and agents list previous feedback sessions for the current project.
- Let a console reopen a previous session by `sessionId`.
- Keep screenshot artifacts owned by their feedback session and safely served
  only from the project-local FixThis artifact directory.
- Add single-step navigation through the debug sidekick bridge:
  - `back`
  - coordinate `tap`
  - directional `swipe`
- Let navigation optionally capture the resulting screen into the same session.
- Preserve V1 queue tools and `fixthis_get_ui_feedback` compatibility.
- Keep the Android app debug-only and local-first.

## Non-Goals

- Do not add arbitrary remote control, script execution, text entry, or app
  exploration loops.
- Do not add cloud sync, team accounts, sharing URLs, or network permissions in
  the Android app.
- Do not require accessibility services or test tags.
- Do not stream screenshot binaries through MCP tools.
- Do not redesign the console into a high-polish product surface beyond the
  controls needed for workspaces and navigation.

## User Flow

### Resume A Review

1. The user opens the feedback console through MCP or `fixthis console`.
2. FixThis loads persisted sessions from
   `.fixthis/feedback-sessions/index.json`.
3. The console opens the active session, or the most recent open session for
   the same package and project.
4. The user can switch to a previous session from the session picker.
5. Screens, feedback items, statuses, agent summaries, and screenshot paths are
   visible without requiring a new capture.

### Navigate While Reviewing

1. The user captures a screen.
2. The user clicks `Back`, `Tap`, or `Swipe` in the console.
3. The MCP server sends one explicit navigation request to the sidekick bridge.
4. The sidekick dispatches the action against the current Activity window.
5. The console captures the resulting screen into the same session when
   `captureAfter` is enabled.
6. The previous screen remains in the workspace and items tied to older screens
   continue to show their original snapshot.

### Agent Workflow

1. The agent calls `fixthis_list_feedback_sessions` to find resumable work.
2. The agent calls `fixthis_open_feedback_console` with a `sessionId`, or
   opens the latest active session.
3. The agent reads feedback with `fixthis_list_feedback` and
   `fixthis_read_feedback`.
4. If a requested view is not visible, the agent may call
   `fixthis_navigate_app` with one action and `captureAfter=true`.
5. The agent resolves feedback with `fixthis_resolve_feedback`.

## Architecture

```text
AI client
  <-> fixthis mcp
        <-> feedback session store
              <-> .fixthis/feedback-sessions/
        <-> local web console
        <-> ADB/local bridge
              <-> debug app sidekick
                    <-> current Activity window
```

The MCP process remains the only owner of feedback session state. Persistence is
an implementation detail of the MCP-side store. The web console reads and writes
state through local HTTP APIs backed by the same service used by MCP tools.

The Android sidekick remains a runtime data source and single-action executor.
It does not host the console and does not accept network traffic.

## Storage Model

All persisted data lives under the project root:

```text
.fixthis/
  feedback-sessions/
    index.json
    <sessionId>/
      session.json
      artifacts/
        screens/
          <screenId>/
            <screenId>-full.png
```

`index.json` stores session summaries for fast listing:

- `schemaVersion`
- `updatedAtEpochMillis`
- `sessions`

Each session summary includes:

- `sessionId`
- `packageName`
- `projectRoot`
- `createdAtEpochMillis`
- `updatedAtEpochMillis`
- `status`
- `screensCount`
- `itemsCount`
- `unresolvedItemsCount`

`session.json` stores the full `FeedbackSession` payload. The existing V1
schema is extended only where required for persistence and navigation metadata.
Unknown fields remain ignored by the shared JSON parser.

## Session Lifecycle

Session lifecycle rules:

- `openSession(packageName)` reuses the latest non-closed session for the same
  package and project unless `newSession=true` is passed.
- `openSession(sessionId)` loads that exact session and makes it current.
- `listSessions(packageName)` returns summaries sorted by most recently updated.
- `captureScreen(sessionId)` appends a screen and immediately saves the session.
- `addAreaFeedback(sessionId, screenId, ...)` appends an item and immediately
  saves the session.
- `resolveFeedback(sessionId, itemId, ...)` updates item status and immediately
  saves the session.
- `closeSession(sessionId)` marks the session `closed`; closed sessions remain
  readable and resumable only by explicit `sessionId`.

If a session file cannot be decoded, listing should include a skipped-session
diagnostic in stderr or local console diagnostics, not fail all sessions. If the
current session cannot be loaded, FixThis opens a new session and reports the
failed session path in the console.

## Artifact Ownership

V1 screen artifacts are desktop-readable paths under `.fixthis/artifacts/`.
V2 moves new feedback workspace artifacts under the owning session directory.
The console screenshot route must serve only PNG files that are:

- present on disk
- inside the current project's `.fixthis/feedback-sessions/`
- referenced by the loaded session
- tied to the requested `screenId`

Legacy V1 artifact paths can still be displayed when reading older sessions, but
new captures should use the V2 session-owned artifact path.

## MCP Tools

### Existing Tools Kept

- `fixthis_open_feedback_console`
- `fixthis_capture_screen`
- `fixthis_list_feedback`
- `fixthis_read_feedback`
- `fixthis_resolve_feedback`
- `fixthis_get_ui_feedback`
- `fixthis_status`
- `fixthis_get_current_screen`
- `fixthis_verify_ui_change`

### `fixthis_open_feedback_console`

Extend arguments:

- `packageName`: optional package override
- `sessionId`: optional exact session to open
- `newSession`: optional boolean; when true, create a new session instead of
  resuming the latest active session

Return:

- `sessionId`
- `packageName`
- `projectRoot`
- `consoleUrl`
- `resumed`
- `session`

### `fixthis_list_feedback_sessions`

List persisted feedback sessions for the project.

Arguments:

- `packageName`: optional package filter
- `includeClosed`: optional boolean, default `false`

Return:

- `projectRoot`
- `sessions`
- `skippedSessions`

### `fixthis_navigate_app`

Perform one explicit navigation action against the current debug app.

Arguments:

- `sessionId`: optional; default current session
- `action`: `back`, `tap`, or `swipe`
- `x`: required for `tap`
- `y`: required for `tap`
- `direction`: required for `swipe`; one of `up`, `down`, `left`, `right`
- `distance`: optional for `swipe`; defaults to 60 percent of the current
  window's shorter side
- `captureAfter`: optional boolean; default `true`

Return:

- `sessionId`
- `action`
- `performed`
- `message`
- `screen`: present when `captureAfter=true` and capture succeeds
- `captureError`: present when navigation succeeds but capture fails

## Console HTTP APIs

Add APIs alongside V1 endpoints:

- `GET /api/sessions`
- `POST /api/session/open`
- `POST /api/session/close`
- `POST /api/navigation`

`POST /api/navigation` accepts the same action shape as
`fixthis_navigate_app`. The console defaults `captureAfter` to `true`.

The existing routes remain:

- `GET /`
- `GET /api/session`
- `POST /api/capture`
- `POST /api/items`
- `GET /api/export/markdown`
- `GET /api/screens/{screenId}/screenshot/full`

## Console UI

The V2 console remains a practical work surface:

- header: package, session status, current session id
- left pane: session picker and captured screen list
- center pane: selected snapshot and navigation controls
- right pane: feedback queue and selected item details

Navigation controls:

- Back button
- Tap mode: clicking the snapshot sends one `tap` action at that coordinate
- Swipe buttons: up, down, left, right
- Capture after navigation toggle, default on

Stale-state display:

- Items show the screen they belong to.
- When selected screen is not the latest captured screen, show a stale screen
  warning.
- Navigation actions always target the live app, not the selected historical
  screenshot.

## Sidekick Navigation

The sidekick bridge adds one method:

```text
performNavigation
```

Request fields:

- `action`
- `x`
- `y`
- `direction`
- `distance`

Response fields:

- `performed`
- `action`
- `activity`
- `message`

Execution rules:

- All Android UI dispatch runs on the main thread.
- `tap` dispatches down/up `MotionEvent`s to the current Activity decor view.
- `swipe` dispatches down/move/up `MotionEvent`s to the current Activity decor
  view.
- `back` dispatches a back key event or Activity back action for the current
  Activity.
- Coordinates must be finite and inside the current window bounds.
- Swipe directions must be one of `up`, `down`, `left`, or `right`.

## Error Handling

New error states:

- `SESSION_NOT_FOUND`: requested persisted session does not exist
- `SESSION_LOAD_FAILED`: session file exists but cannot be decoded
- `SESSION_SAVE_FAILED`: session mutation succeeded in memory but could not be
  saved
- `NAVIGATION_UNSUPPORTED`: action is outside the V2 allowlist
- `NAVIGATION_FAILED`: sidekick could not dispatch the action
- `NAVIGATION_CAPTURE_FAILED`: navigation succeeded but follow-up capture failed
- `INVALID_NAVIGATION_TARGET`: tap or swipe coordinates are outside the current
  window

Partial success is allowed for navigation plus capture. If navigation succeeds
and capture fails, the tool and console should report both facts.

## Privacy And Security

- Keep all persisted session data under project-local `.fixthis/`.
- Keep generated feedback session files and screenshots out of git.
- Preserve localhost-only console binding by default.
- Do not add Android network permissions.
- Do not expose arbitrary filesystem paths through screenshot routes.
- Keep redaction behavior for editable and password semantics.
- Treat navigation actions as local debug-only actions and document that they
  dispatch touch/key events inside the debug app process.

## Testing

Unit tests:

- session persistence read/write/list behavior
- corrupt session skip behavior
- path safety for session-owned artifacts
- session resume behavior in `FeedbackSessionService`
- MCP tool schemas and result shapes
- console HTTP APIs for sessions and navigation
- navigation request validation
- sidekick bridge request/response serialization

Android-side tests:

- bridge method dispatches `performNavigation`
- fake navigation performer receives `back`
- fake navigation performer receives tap coordinates
- fake navigation performer receives swipe direction and distance

Manual smoke:

1. Install and launch the sample debug app.
2. Open console and capture a screen.
3. Add two feedback items.
4. Stop and restart the console.
5. Reopen the same session and verify items and screenshots remain.
6. Use Back, Tap, and Swipe from the console.
7. Verify each action can capture a new screen into the same session.
8. Read the session through MCP and resolve one feedback item.

## Rollout

1. Introduce persistence without changing V1 tool behavior.
2. Add session listing and exact session resume.
3. Move new screen artifacts under session-owned directories.
4. Add console session picker and close/open APIs.
5. Add sidekick navigation protocol with fake-performer tests.
6. Wire CLI and MCP navigation tool.
7. Add console navigation controls with capture-after behavior.
8. Update docs and run full JVM, sidekick, distribution, and emulator smoke
   checks.

## Acceptance Criteria

- Restarting MCP or `fixthis console` does not lose an open feedback queue.
- Users can list previous sessions and reopen one by `sessionId`.
- New captures write screenshot artifacts under the owning session directory.
- Screenshot routes reject paths outside FixThis feedback session storage.
- MCP can list sessions, open a specific session, navigate once, and read the
  resulting queue.
- The console can send Back, Tap, and Swipe actions.
- Navigation can capture the resulting screen into the same session.
- V1 queue tools and the legacy single-feedback wrapper still work.
- The Android app remains debug-only and local-first.
