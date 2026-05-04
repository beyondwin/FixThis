# Feedback Workspace And Limited Navigation V2 Implementation Details

Date: 2026-05-04

Related documents:

- Design: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- Task plan: `docs/superpowers/plans/2026-05-04-feedback-workspace-navigation-v2-implementation.md`
- V1 design: `docs/superpowers/specs/2026-05-04-mcp-first-feedback-console-design.md`
- V1 plan: `docs/superpowers/plans/2026-05-04-mcp-first-feedback-console-implementation.md`

## Purpose

This document explains how the V2 implementation should fit together. The task
plan is the step-by-step execution checklist. This document is the architectural
and behavioral guide an implementer should use when deciding between plausible
implementation choices inside those tasks.

V2 is done when PointPatch can:

- reopen a feedback session after MCP or console restart
- preserve screens, feedback items, resolution state, and screenshot paths
- list feedback workspaces for the current project
- open a specific workspace by `sessionId`
- perform one local debug-only `back`, `tap`, or `swipe`
- optionally capture the resulting screen into the same workspace
- keep V1 tools and legacy single-feedback behavior working

## Implementation Strategy

Implement persistence before navigation.

Navigation creates more screens and stale-screen relationships. If sessions are
still process-local, navigation adds complexity without solving the core user
problem: interrupted reviews lose context. Persistence gives the system stable
session identity, artifact ownership, and recovery behavior before app actions
start adding new screens.

The intended order is:

1. Storage paths and summary models.
2. Disk persistence.
3. Store and service resume behavior.
4. Session-owned screenshot artifacts.
5. MCP and console session management APIs.
6. Sidekick navigation protocol.
7. MCP and console navigation workflow.
8. Docs and emulator smoke verification.

Do not start by redesigning the console UI. The UI should grow only enough to
expose sessions, navigation controls, and stale-state warnings.

## Core Contracts

### MCP Owns Session State

The MCP-side `FeedbackSessionService` remains the only writer of
`FeedbackSession` state. The console must not keep independent session state.
The Android sidekick must not know about feedback sessions, item IDs, or
workspace storage.

The allowed data flow is:

```text
Console or MCP tool
  -> FeedbackSessionService
     -> FeedbackSessionStore
        -> FeedbackSessionPersistence
     -> PointPatchBridge
        -> BridgeClient
           -> sidekick BridgeServer
```

Do not add reverse writes from the sidekick into `.pointpatch/`.

### Persistence Is Project-Local

All V2 session files and new screenshot artifacts live under:

```text
<projectRoot>/.pointpatch/feedback-sessions/
```

The implementation may keep reading old V1 artifact paths for compatibility,
but new captures should be written under the owning session:

```text
.pointpatch/feedback-sessions/<sessionId>/artifacts/screens/<screenId>/<screenId>-full.png
```

The path helper must reject path traversal and unsafe IDs. Treat `sessionId` and
`screenId` as untrusted when constructing filesystem paths, even though they are
normally generated internally.

### Navigation Is Single-Step

`pointpatch_navigate_app` and `POST /api/navigation` perform exactly one action.
They must not accept a sequence of actions, scripts, arbitrary shell commands,
text input, or exploration instructions.

Allowed actions:

- `back`
- `tap`
- `swipe`

Allowed swipe directions:

- `up`
- `down`
- `left`
- `right`

Navigation targets the live app, not a historical screenshot. If the selected
screen in the console is stale, the UI should make that clear before or after
navigation.

### Capture After Navigation Is Best-Effort

Navigation and follow-up capture are two distinct operations.

If navigation succeeds but capture fails, return:

- `performed=true`
- the action that was performed
- `captureError`
- no new `screen`

Do not report the whole operation as failed when the action was actually
performed. This distinction matters because repeating a tap or back action may
change the app again.

## Storage Design

### Files

```text
.pointpatch/
  feedback-sessions/
    index.json
    <sessionId>/
      session.json
      artifacts/
        screens/
          <screenId>/
            <screenId>-full.png
```

`session.json` is the source of truth. `index.json` is an optimization for fast
listing and should be rebuildable from session files.

### Index Shape

The index should use summary records, not full sessions:

```json
{
  "schemaVersion": "2.0",
  "updatedAtEpochMillis": 1777900000000,
  "sessions": [
    {
      "sessionId": "session-1",
      "packageName": "io.github.pointpatch.sample",
      "projectRoot": "/repo",
      "createdAtEpochMillis": 1777899900000,
      "updatedAtEpochMillis": 1777900000000,
      "status": "active",
      "screensCount": 2,
      "itemsCount": 3,
      "unresolvedItemsCount": 2
    }
  ]
}
```

The index should be updated after every successful session save. If writing the
index fails, surface a `SESSION_SAVE_FAILED` error instead of silently
discarding the failure.

### Corrupt Session Files

Listing sessions should skip corrupt `session.json` files and report
`skippedSessions`. Loading an exact corrupt session by `sessionId` should fail
with a clear `SESSION_LOAD_FAILED` style message.

This split avoids one bad file breaking every review workspace while still
making exact reopen failures visible.

## Store And Service Responsibilities

### `FeedbackSessionPaths`

Responsibilities:

- canonicalize the project root
- provide session, index, and screen artifact paths
- validate ID path segments
- answer whether a file is under feedback session storage

It should not read or write JSON.

### `FeedbackSessionPersistence`

Responsibilities:

- save a full `FeedbackSession`
- load a full `FeedbackSession`
- list session summaries
- skip corrupt sessions during list
- write `index.json`

It should not call the bridge, inspect Android state, or decide which session is
current.

### `FeedbackSessionStore`

Responsibilities:

- keep an in-memory map for the current process
- load persisted sessions on construction when persistence is configured
- save after every mutation
- choose and expose the current session
- support exact session open and close

Store methods should remain synchronized around state mutation. Disk writes can
be inside the same lock for V2 because session files are small and this keeps
behavior simple. If later performance becomes a problem, persistence can move to
an explicit single-writer queue.

### `FeedbackSessionService`

Responsibilities:

- resolve package names
- apply session open/list/close semantics
- coordinate bridge capture
- reserve screen IDs before capture
- route navigation requests
- implement capture-after-navigation behavior

The service is the right place to connect a `sessionId` with a destination
artifact directory. `BridgeClient` should accept a destination, but it should
not decide which feedback session owns the artifact.

## Session Open Semantics

Use these rules consistently across MCP and console:

| Request | Behavior |
| --- | --- |
| `sessionId` provided | Load that exact session and make it current. |
| `newSession=true` | Create a new active session. |
| neither provided | Reuse current non-closed session for same package/project. |
| no current match | Reopen latest non-closed persisted session for same package/project. |
| no persisted match | Create a new active session. |

Closed sessions remain readable through exact `sessionId`; they should not be
auto-selected as the latest active session.

## Screenshot Artifact Handling

V1 uses `.pointpatch/artifacts/<screenId>/`. V2 should use the session-owned
path for new captures. To do that cleanly:

1. `FeedbackSessionService` asks the store for a new `screenId`.
2. It builds a destination with `FeedbackSessionPaths.screenArtifactDirectory`.
3. It calls `PointPatchBridge.captureScreenSnapshot` with `sessionId`,
   `screenId`, and `destinationDirectory`.
4. `BridgeClient` pulls the sidekick screenshot into that destination.
5. The returned screenshot metadata includes `desktopFullPath`.
6. The store appends the `CapturedScreen` and saves the session.

The console screenshot route should verify all of these before serving bytes:

- the requested screen exists in the current session
- the screen has a `desktopFullPath`
- the file exists
- the file extension is `.png`
- the canonical file path is under `.pointpatch/feedback-sessions/`

Do not serve arbitrary paths from the session JSON.

## MCP Tool Contracts

### `pointpatch_open_feedback_console`

New optional arguments:

- `packageName`
- `sessionId`
- `newSession`

Return fields:

- `sessionId`
- `packageName`
- `projectRoot`
- `consoleUrl`
- `resumed`
- `session`

`resumed` should mean an existing session was opened rather than a new one being
created. An explicit `sessionId` open is always a resume.

### `pointpatch_list_feedback_sessions`

Arguments:

- `packageName`
- `includeClosed`

Return fields:

- `projectRoot`
- `sessions`
- `skippedSessions`

This tool should not require a device connection. It reads project-local
workspace files. If package filtering needs package resolution and no package is
available, return a clear argument error instead of contacting a device.

### `pointpatch_navigate_app`

Arguments:

- `sessionId`
- `action`
- `x`
- `y`
- `direction`
- `distance`
- `captureAfter`

Return fields:

- `sessionId`
- `performed`
- `action`
- `activityName`
- `message`
- `screen`
- `captureError`

The tool requires a live sidekick bridge because navigation acts on the running
app. `captureAfter` defaults to `true`.

## Console HTTP Contracts

Add routes:

| Route | Method | Purpose |
| --- | --- | --- |
| `/api/sessions` | `GET` | List persisted workspace summaries. |
| `/api/session/open` | `POST` | Open exact session or create new session. |
| `/api/session/close` | `POST` | Mark current or specified session closed. |
| `/api/navigation` | `POST` | Perform one navigation action. |

Keep existing V1 routes:

| Route | Method | Purpose |
| --- | --- | --- |
| `/api/session` | `GET` | Return current session. |
| `/api/capture` | `POST` | Capture current screen. |
| `/api/items` | `POST` | Add area feedback. |
| `/api/export/markdown` | `GET` | Export queue markdown. |
| `/api/screens/{screenId}/screenshot/full` | `GET` | Serve current-session screenshot. |

Console route handlers should return clear `4xx` responses for bad input and
`5xx` only for unexpected failures.

## Console UI Details

The UI should remain utilitarian. Add these controls without turning the page
into a broad redesign:

- session list with active session marker
- New Session button
- Close Session button
- Back button
- Swipe Up/Down/Left/Right buttons
- Capture after navigation checkbox, default checked
- tap-on-snapshot behavior
- stale-screen warning when selected screen is not latest

The tap coordinate conversion should use rendered image bounds and natural image
size:

```text
x = (clientX - imageLeft) * naturalWidth / renderedWidth
y = (clientY - imageTop) * naturalHeight / renderedHeight
```

If the image has no natural dimensions or no screenshot is loaded, tap mode
should do nothing and show a clear message.

## Sidekick Navigation Details

### Bridge Protocol

Add method:

```text
performNavigation
```

Request:

```json
{
  "action": "tap",
  "x": 120,
  "y": 300
}
```

Response:

```json
{
  "performed": true,
  "action": "tap",
  "activity": "io.github.pointpatch.sample.MainActivity",
  "message": null
}
```

### Android Dispatch

Dispatch must run on the main thread.

`tap`:

- require finite coordinates
- require coordinates inside decor view bounds
- dispatch `ACTION_DOWN`
- dispatch `ACTION_UP`

`swipe`:

- require valid direction
- default distance to 60 percent of the shorter window side
- dispatch `ACTION_DOWN`
- dispatch at least one `ACTION_MOVE`
- dispatch `ACTION_UP`

`back`:

- dispatch a back key event to the decor view or use the Activity back
  dispatcher if the existing app compatibility layer makes that preferable

If the current Activity is missing, fail with a clear message. Do not try to
start the app from the sidekick.

## Error Mapping

Use stable user-facing messages for expected failures:

| Condition | Suggested code or message |
| --- | --- |
| Missing session | `SESSION_NOT_FOUND` |
| Corrupt exact session | `SESSION_LOAD_FAILED` |
| Save failed | `SESSION_SAVE_FAILED` |
| Unsupported navigation action | `NAVIGATION_UNSUPPORTED` |
| Invalid tap or swipe target | `INVALID_NAVIGATION_TARGET` |
| Sidekick action failure | `NAVIGATION_FAILED` |
| Action succeeded but capture failed | `NAVIGATION_CAPTURE_FAILED` or `captureError` |

MCP tool responses may use `isError=true` for invalid requests. Navigation
partial success should be a normal JSON result with `captureError`.

## Compatibility Rules

Keep these V1 behaviors intact:

- `pointpatch_get_ui_feedback` still returns annotation JSON and Markdown.
- `pointpatch_capture_screen` still captures into the active session when no
  `sessionId` is provided.
- `pointpatch_list_feedback`, `pointpatch_read_feedback`, and
  `pointpatch_resolve_feedback` still default to the current session.
- `pointpatch console --package <applicationId>` still prints a localhost URL.

Do not require users to migrate existing `.pointpatch/artifacts/` content.
Existing sessions without V2 storage should remain readable if they are already
loaded in memory, but new persisted sessions should use the V2 path layout.

## Testing Strategy

### Unit Tests

Run targeted tests at each task boundary:

- persistence path safety and JSON round-trip
- corrupt session skip behavior
- store resume semantics
- service open/list/close behavior
- artifact destination selection
- navigation model validation
- MCP tool schemas and result shape
- console route behavior
- bridge method dispatch
- Android navigation performer validation

### Integration Tests

Use fake bridge tests for MCP and console flows:

- open new session
- capture screen
- add item
- save and rebuild service
- reopen same session
- navigate with `captureAfter=true`
- confirm a new screen is appended

### Manual Smoke

Manual smoke is required because touch dispatch and app navigation depend on a
real Activity window.

Minimum connected-device smoke:

1. Install the sample app.
2. Start the app through PointPatch.
3. Open console.
4. Capture a screen.
5. Add feedback.
6. Restart console.
7. Reopen the same `sessionId`.
8. Use Back, Tap, and Swipe.
9. Confirm navigation with capture appends screens.
10. Read and resolve feedback through MCP.

## Stop Rules

Stop and report a blocker if:

- existing V1 tests fail before V2 edits and the cause is unrelated
- repo-local instructions conflict with this document
- persisted session files would need to overwrite unrelated user files
- Android navigation cannot be implemented without adding app network
  permissions, accessibility services, or broad automation
- exact session reopen semantics conflict with current MCP process ownership in
  a way that would make data loss likely

Do not weaken tests to pass around these blockers.

## Verification Commands

Targeted checks during implementation:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.BridgeServerTest
```

Final checks:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-cli:test :pointpatch-mcp:test
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Docs-only checks for this document:

```bash
git diff --check -- docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-implementation-details.md
rg -n "feedback-sessions|pointpatch_list_feedback_sessions|pointpatch_navigate_app|captureAfter|SESSION_SAVE_FAILED" docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-implementation-details.md
```

## Done When

The V2 implementation can be considered complete only when:

- all V2 task-plan checkboxes are complete
- final JVM, sidekick, and installDist checks pass
- connected emulator smoke confirms persistence and all three navigation actions
- docs describe the new tools, storage path, privacy behavior, and recovery
  steps
- no session-owned screenshot route can serve files outside
  `.pointpatch/feedback-sessions/`
- no navigation API accepts multi-step automation or text input
