# Feedback Console Selection And Agent Handoff Design

Date: 2026-05-05

## Summary

This design extends the V2 feedback workspace and limited navigation work with a
more usable console review workflow:

- choose the active Android device from the browser console
- switch explicitly between `Select` and `Navigate` modes
- show visible screenshot selection overlays
- let users click a UI component or drag any visual area
- attach comments to selected targets
- group unsent feedback into a draft queue
- send the current draft as a read-only agent handoff batch
- keep sent batches visible as history

This is a console UX and feedback data-flow improvement, not a general mobile
automation framework.

## Source Inputs

This design builds on the implemented V2 documents:

- `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- `docs/superpowers/plans/2026-05-04-feedback-workspace-navigation-v2-implementation.md`

The design also takes inspiration from the DTA project:

- DTA GitHub repository: <https://github.com/yamsergey/dta>
- User-provided DTA overview video: <https://www.youtube.com/watch?v=y_XaG8E1QuU>

Useful DTA patterns for FixThis are screenshot highlighting, click-to-select,
layout/screenshot inspection, and visible device context. FixThis should not
copy DTA's broader network inspector, mocking, websocket platform, arbitrary
input, or automation scope.

## Product Decision

FixThis should make the feedback console feel like a clear review workspace.
The user should always know:

- which device is active
- whether clicks will select feedback targets or navigate the app
- what part of the screenshot is selected
- which comments are still draft
- which comments have already been sent to the agent
- where earlier sent batches can be reviewed

The existing V2 principles still apply:

- the MCP process owns feedback session state
- session data persists under `.fixthis/feedback-sessions/`
- Android remains debug-only and local-first
- navigation remains limited to one explicit `back`, `tap`, or `swipe`
- no text entry automation, script execution, exploration loops, cloud sync, or
  Android network service is added

## Goals

- Make screenshot clicks understandable by rendering persistent selection
  overlays.
- Separate selection from navigation so clicking the screenshot is never
  ambiguous.
- Support both component selection and custom visual area selection.
- Store selected targets with comments as feedback items.
- Show session, screen, item, and handoff history with human-readable labels
  instead of UUID-first rows.
- Let the user send the current draft batch to the agent, then clear the active
  draft workspace.
- Preserve sent history as read-only session history.
- Let users select and disconnect the active device in the browser console.
- Keep existing MCP tools compatible, especially `fixthis_read_feedback` and
  `fixthis_resolve_feedback`.

## Non-Goals

- Do not add arbitrary typing or text input into the Android app.
- Do not add multi-step scripted app automation.
- Do not add cloud sync, remote sharing, team accounts, or Android network
  permissions.
- Do not add DTA-style network inspection or mocking.
- Do not push directly into a specific AI product API from the console. "Send to
  Agent" means FixThis records an agent-readable handoff batch that MCP tools
  can read.
- Do not delete sent handoff history through casual clear actions.

## Console Layout

The console should become a task-oriented workspace:

```text
Top bar
  Device picker | connection status | Capture | Refresh

Left pane
  Sessions
  Screens
  Sent History

Center pane
  Select / Navigate mode control
  Snapshot with overlay selection layer
  Navigation controls shown for Navigate mode

Right pane
  Current Selection
  Comment composer
  Draft items
  Send Draft to Agent
  Clear Selection / Clear Comment / Clear Draft
```

The UI should remain dense and practical. It should feel like developer tooling,
not a marketing surface. Cards should be used only for repeated sessions,
screens, draft items, and sent batches.

## Device Workflow

### Device List

The console shows connected ADB devices in the top bar. Each row includes:

- serial
- model when available
- product/device name when available
- state: `device`, `offline`, or `unauthorized`
- selected state

Only devices in `device` state can be selected for capture and navigation.

### Select Device

When the user selects a device, the console records it as the active device for
this console process. Capture, status, navigation, and screenshot pulls use that
device until the user disconnects or chooses another device.

If no device is selected, the console may show sessions and saved screenshots,
but live capture and navigation controls are disabled.

### Disconnect

Disconnect means:

- clear the console's active device selection
- clean up ADB forwards created by this console process when any are still
  registered
- disable live capture and navigation until another device is selected

Disconnect must not call `adb disconnect`, detach USB, or affect the user's ADB
server outside FixThis-owned resources.

## Modes

The snapshot has an explicit segmented control:

- `Select`
- `Navigate`

### Select Mode

Select mode is for creating feedback items.

- Click on the screenshot: select a semantics component.
- Drag on the screenshot: select a custom visual area.
- Selection renders as an overlay box and summary in the right pane.
- No Android input event is sent in Select mode.

### Navigate Mode

Navigate mode is for operating the live app.

- Click on the screenshot: send a one-step `tap` navigation request.
- `Back`, `Swipe Up`, `Swipe Down`, `Swipe Left`, and `Swipe Right` use the
  existing limited navigation API.
- `captureAfter` remains available and defaults on.

If a historical screen is selected, Navigate mode must show that navigation
targets the live app, not the historical screenshot. The user can still use
navigation, but the UI should make this state obvious.

## Selection Model

All overlay math uses screenshot natural pixel coordinates, not rendered CSS
coordinates. The browser maps rendered image positions back to natural image
coordinates before sending bounds to the server.

### Component Selection

On click in Select mode:

1. Convert click position to natural screenshot coordinates.
2. Hit-test captured semantics nodes for the selected screen.
3. Prefer merged semantics nodes.
4. If multiple nodes contain the point, select the smallest visible bounds.
5. Store the chosen node as the current selection.
6. Render the node bounds as a selection overlay.

The selection summary should show useful available metadata:

- component type or role when known
- text or content description when available
- bounds
- screen label

If multiple plausible nodes exist, the right pane may show a compact candidate
list so the user can switch to a parent or child target.

### Custom Area Selection

On drag in Select mode:

1. Start a transient rectangle on pointer down.
2. Update the rectangle while dragging.
3. On pointer up, normalize bounds and require a minimum size.
4. Store the bounds as a custom visual area selection.
5. Render the selection as `Custom area`.

Custom areas are first-class feedback targets. They are useful when the problem
is spacing, alignment, visual grouping, whitespace, or any region that does not
map cleanly to one semantics component.

### Clear Selection

`Clear Selection` removes only the active overlay and selection summary. It does
not delete draft items or sent history.

Selection is also cleared when:

- the active screen changes
- a new capture replaces the viewed live screen
- the user sends the current draft to the agent
- the user closes or switches sessions

## Feedback Composer

The right pane contains the active selection and comment composer.

`Add Item` is enabled only when:

- a screen is selected
- a component or custom area is selected
- the comment has non-blank text

When the user adds an item:

- the item is appended to the current draft list
- the composer comment is cleared
- the selection remains visible by default until the user selects another target
  or clears it
- the item card receives a human-readable number in the current session

Item cards show:

- `#<number>`
- first line of the comment
- target type: `Component` or `Custom area`
- screen label, such as `Screen 3 - MainActivity`
- delivery state: `Not sent` or sent batch label
- resolution status when available

UUIDs appear only as secondary shortened metadata.

## Agent Handoff

### Draft Items

Draft items are feedback items that have not been included in a handoff batch.
They remain editable/removable until sent.

The UI labels this section `Draft`.

### Send Draft To Agent

`Send Draft to Agent` creates one handoff batch from all current draft items.
It does not keep adding to the same visual draft after send.

On success:

- draft items are marked as sent in a new batch
- the session status becomes `ready_for_agent`
- a handoff history entry is appended
- current selection is cleared
- comment is cleared
- the visible draft list is cleared
- the sent batch appears under `Sent History`

The currently viewed screenshot may remain visible, but the active feedback
workspace is a clean slate. To add more feedback, the user must select or
capture again and create a new draft batch.

### Sent History

Sent history is read-only by default.

Each batch shows:

- `Batch #<number>`
- sent time
- item count
- item summaries
- associated screen labels
- whether items are unresolved, in progress, resolved, or need clarification

Sent history must remain visible after browser refresh and MCP restart because
it is part of the persisted session.

### Agent Read Path

The console does not call an external AI API. It records a handoff batch so the
agent can read it through MCP:

- `fixthis_list_feedback`
- `fixthis_read_feedback`
- `fixthis_resolve_feedback`

`fixthis_read_feedback` should make draft vs sent state clear. It should
include handoff history, batch numbers, item comments, target bounds, selected
node metadata when available, screenshot paths, and screen labels.

## Clear Actions

Clear actions must be narrow and safe:

- `Clear Selection`: remove only the active overlay selection.
- `Clear Comment`: clear only the composer text.
- `Clear Draft`: discard current unsent draft items after confirmation.

Clear actions must not remove sent history. Removing sent history requires
closing or starting a new session, not a casual clear button.

## Human-Readable Labels

The console should avoid UUID-first rows.

### Sessions

Example:

```text
Session 2 - active
3 screens - 2 draft - 1 sent batch
Updated 14:32
a23cdd75...
```

### Screens

Example:

```text
Screen 3 - MainActivity
2 feedback items - Captured 14:34
7773bcdc...
```

Screens should be clickable. Selecting a screen displays that screenshot and its
items. The latest screen is not the only view.

### Draft Items

Example:

```text
#4 Checkout total is too low
Screen 3 - Custom area - Not sent
```

### Sent Batches

Example:

```text
Batch #1 - Sent 14:40
3 items - 2 unresolved
```

## Data Model

The existing `FeedbackSession` model should be extended without breaking old
sessions.

Recommended additions:

```kotlin
@Serializable
data class FeedbackSession(
    ...
    val handoffBatches: List<FeedbackHandoffBatch> = emptyList(),
)

@Serializable
data class FeedbackItem(
    ...
    val sequenceNumber: Int? = null,
    val delivery: FeedbackDelivery = FeedbackDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
)

@Serializable
enum class FeedbackDelivery {
    @SerialName("draft")
    DRAFT,

    @SerialName("sent")
    SENT,
}

@Serializable
data class FeedbackHandoffBatch(
    val batchId: String,
    val sequenceNumber: Int,
    val createdAtEpochMillis: Long,
    val itemIds: List<String>,
    val markdownSnapshot: String? = null,
)
```

Old sessions that lack these fields load with empty history and draft delivery.

Node selections use the existing `FeedbackTarget.Node` plus `selectedNode`.
Custom drags use the existing `FeedbackTarget.Area`.

## Console HTTP APIs

New or extended console APIs:

```text
GET /api/devices
POST /api/device/select
POST /api/device/disconnect
POST /api/items
DELETE /api/items/draft
POST /api/agent-handoffs
```

`POST /api/items` accepts:

- `screenId`
- `comment`
- `target`
- `bounds`
- optional `nodeUid`

The server validates that:

- `screenId` belongs to the current session
- bounds are finite and positive
- node selections refer to a node present on that screen
- custom area selections stay inside the screenshot bounds

`POST /api/agent-handoffs` fails with `409` if there are no draft items.

## MCP Tool Behavior

Existing tools remain the primary agent interface.

`fixthis_list_feedback` should summarize:

- draft item count
- sent batch count
- unresolved sent item count
- latest batch summary

`fixthis_read_feedback` should include:

- current draft items
- sent history grouped by batch
- selected target details
- screen labels and screenshot paths
- source candidates and selected node metadata when available

`fixthis_resolve_feedback` keeps updating item resolution state. Resolving a
sent item updates the sent history view because the history references live item
ids, not copied item objects.

An optional future tool such as `fixthis_send_feedback_to_agent` can mirror
the console's handoff creation, but the first implementation can keep handoff
creation console-only if MCP agents already read the queue.

## Device Architecture

ADB device selection requires the CLI bridge layer to support a selected serial.
The bridge client should pass `adb -s <serial>` for device-specific operations
when a serial is selected.

Affected operations include:

- `devices`
- `run-as cat`
- `forward`
- `forward --remove`
- `pull`
- `shell monkey`

The selected serial should be console-process state, not global project
configuration. It should not be persisted into `.fixthis/project.json`.

## Privacy And Security

- Keep all session data and screenshots under project-local `.fixthis/`.
- Keep `.fixthis/feedback-sessions/` and screenshot artifacts ignored by git.
- Keep the console bound to localhost by default.
- Do not add Android network permissions.
- Do not expose screenshot paths outside the current project and referenced
  session.
- Do not use `adb disconnect` from the console disconnect button.
- Treat screenshots as sensitive pixel captures.
- Preserve text/password redaction in semantics payloads.

## Error Handling

The console should show actionable inline errors:

- no device selected
- device offline or unauthorized
- selected device disappeared
- capture failed
- selection bounds invalid
- selected node no longer exists for the screen
- draft is empty when sending to agent
- session closed
- navigation succeeded but capture failed

Errors should not clear draft work unless the user explicitly clears it.

## Testing

Unit tests:

- device parsing and selected-device command routing
- console device APIs
- selection request validation
- draft item creation for node and custom area targets
- draft clearing preserves sent history
- handoff creation moves draft items to sent delivery state
- old session JSON loads with empty handoff history
- `fixthis_read_feedback` groups draft and sent batches

UI asset smoke tests:

- `Select` / `Navigate` control exists
- selection overlay DOM exists
- `Add Item` disabled without selection
- draft and sent history sections exist
- device picker exists
- UUIDs are secondary metadata, not the primary row labels

Manual smoke:

1. Open the console with at least one device connected.
2. Select the device in the browser.
3. Capture a screen.
4. In Select mode, click a component and verify overlay highlight.
5. Add a comment and create a draft item.
6. Drag a custom area and create another draft item.
7. Send draft to agent and verify the draft clears.
8. Verify `Sent History` shows Batch #1 with both items.
9. Refresh the browser and verify history persists.
10. Use `fixthis_read_feedback` and verify the agent-readable output includes
    the sent batch.
11. Switch to Navigate mode and verify screenshot click performs a tap.
12. Disconnect the device from the console and verify capture/navigation are
    disabled while ADB remains connected.

## Rollout

1. Add data model support for delivery state and handoff batches.
2. Add device selection support in ADB and bridge client layers.
3. Add console APIs for devices, draft clearing, and handoff creation.
4. Update queue formatter and MCP read/list outputs.
5. Redesign console layout and readable session/screen/item cards.
6. Add screenshot overlay selection and Select/Navigate modes.
7. Add tests and docs.
8. Run JVM, sidekick, distribution, and connected-device smoke checks.

## Acceptance Criteria

- The user can select an active ADB device from the browser console.
- Disconnect clears only FixThis console device state and owned forwards.
- The snapshot has clear `Select` and `Navigate` modes.
- Select mode click highlights a component without tapping the app.
- Select mode drag creates a custom visual area.
- Navigate mode click still performs a limited tap navigation.
- Selected targets remain visibly highlighted until cleared or sent.
- `Add Item` requires a selected target and comment.
- Draft items are shown separately from sent history.
- `Send Draft to Agent` creates a batch, clears active draft state, and keeps the
  batch visible in sent history.
- Sent history survives console refresh and MCP restart.
- `fixthis_read_feedback` exposes draft and sent feedback in an
  agent-readable form.
- Session, screen, item, and batch lists use human-readable labels with UUIDs
  only as secondary metadata.
- Existing V2 persistence, session resume, and limited navigation behavior remain
  compatible.
