# Feedback Console Live Preview And Batched Items Detailed Design

Date: 2026-05-05

## Summary

This document specifies the implementation details for the next feedback console
iteration. The console should behave like a local review tool:

- live preview is temporary and does not create session history
- the default screenshot interaction is navigation
- `Add` freezes the latest preview for target selection
- users can add multiple pending comments on one frozen preview
- `Save` promotes that frozen preview into one persisted evidence snapshot and
  links all pending feedback items to it
- `Copy` and `Send` are top-bar agent actions, not comment-composer actions
- agent Markdown is concise and source-hinted

The implementation must preserve the existing PointPatch constraints:

- MCP process owns feedback session state
- `.pointpatch/feedback-sessions/` remains the durable persistence root
- Android remains debug-only and local-first
- navigation remains one-step `back`, `tap`, or `swipe`
- no arbitrary typing, scripted automation, network mocking, cloud sync, or
  external AI API call is added

## Terminology

`Live preview`
: A transient screenshot+semantics capture used only to show the current device
  screen in the console. It is not a `CapturedScreen` in session history.

`Frozen preview`
: The live preview selected by `Add`. Polling stops and all target selection
  happens against this stable image and semantics tree.

`Pending item`
: A client-side target/comment pair queued on the frozen preview before `Save`.
  Pending items are not persisted individually.

`Evidence snapshot`
: The persisted `CapturedScreen` created when `Save` promotes a frozen preview.
  Multiple saved feedback items can share the same evidence snapshot.

`Saved evidence group`
: UI grouping for draft items that share one persisted `screenId`.

## User Workflow

Top bar actions are short and session-level:

```text
Device | Preview interval | Refresh | Add | Save | Copy | Send | New | Close
```

Expected flow:

1. User selects a device.
2. Console shows live preview and polls using the configured interval.
3. While idle, clicking the preview navigates the app with a one-step tap.
4. User clicks `Add`.
5. Console freezes the latest preview and stops preview polling.
6. User clicks a UI component or drags an area.
7. User writes a comment and clicks `Add to Pending`.
8. Console shows a numbered pending row and a matching numbered overlay.
9. User repeats selection/comment as needed on the same frozen preview.
10. If a pending item is wrong, user deletes that row and adds a corrected item.
11. User clicks `Save`.
12. Console persists one evidence snapshot and all pending items.
13. User can expand the saved evidence group to review the persisted screenshot,
    numbered overlays, and saved comments.
14. User clicks `Copy` to copy Markdown or `Send` to create a handoff batch.

## State Machine

```text
Idle
  preview polling enabled when device selected and tab visible
  preview click -> navigate tap
  Refresh -> capture transient preview
  Add -> Adding(frozenPreview)

Adding(frozenPreview)
  preview polling disabled
  click -> select semantics node
  drag -> select visual area
  Add to Pending -> append pending item
  pending Focus -> highlight matching overlay
  pending Delete -> remove row and renumber
  Cancel -> discard pending items and frozen preview reference, return Idle
  Save -> persist one evidence snapshot + N feedback items, return Idle

Idle with saved draft items
  saved evidence groups are expandable
  Copy -> copy current agent Markdown
  Send -> persist handoff batch from current draft items
```

Stop rules:

- Do not refresh or replace the frozen preview while `Adding`.
- Do not perform a new ADB capture during `Save`; saved evidence must match the
  frozen preview the user selected.
- Do not persist pending items until `Save`.
- Do not add pending item editing. Correction is delete-and-add.

## Preview Polling

Default polling:

- default interval: `2_000ms`
- minimum interval: `1_000ms`
- options: `Manual`, `1s`, `2s`, `5s`
- setting storage: browser `localStorage`
- pause when `document.hidden`
- pause while `Adding`
- manual `Refresh` fetches once

Using `1s` is allowed. It may feel slower over Wi-Fi debugging because each
preview needs local ADB/bridge work, but it does not consume AI tokens. Sub-second
polling is intentionally disallowed.

## Persistence Contract

Live preview:

- may use `.pointpatch/preview-cache/<sessionId>/<previewId>/`
- may be held only in memory if serving the screenshot remains possible
- must not be appended to `FeedbackSession.screens`
- must not appear in `FeedbackSessionSummary.screensCount`
- must not create `.pointpatch/feedback-sessions/<session>/artifacts/screens/`
  entries until `Save`

Save:

- takes `previewId` and pending item payloads
- promotes the preview screenshot into
  `.pointpatch/feedback-sessions/<sessionId>/artifacts/screens/<screenId>/`
- creates exactly one `CapturedScreen`
- creates N `FeedbackItem`s
- all N items share the promoted `screenId`
- item sequence numbers follow existing session order
- the operation is atomic from the session store perspective

JSON remains the complete tool contract and keeps internal IDs and artifact
paths. Human-visible console rows and Markdown should not expose IDs by default.

## Server API Contract

`GET /api/preview`

Returns a transient preview.

```json
{
  "previewId": "preview-1",
  "screen": {
    "screenId": "screen-1",
    "displayName": "MainActivity",
    "activityName": "io.github.pointpatch.sample.MainActivity",
    "screenshot": {
      "desktopFullPath": "/repo/.pointpatch/preview-cache/session-1/preview-1/screen-1-full.png",
      "width": 720,
      "height": 1600
    },
    "roots": []
  }
}
```

Contract:

- does not mutate the persisted session
- replaces or bounds the number of retained transient previews
- returns screenshot and semantics from the same capture

`GET /api/preview/screenshot/full`

Returns the latest preview PNG for rendering. The server must only serve files
under PointPatch-owned preview cache or persisted artifact roots.

`POST /api/items/batch`

Request:

```json
{
  "previewId": "preview-1",
  "items": [
    {
      "targetType": "node",
      "nodeUid": "compose:0:merged:42",
      "bounds": {"left": 28.0, "top": 77.0, "right": 692.0, "bottom": 186.0},
      "comment": "Give this field 20 more px of left margin"
    },
    {
      "targetType": "area",
      "bounds": {"left": 112.0, "top": 426.0, "right": 351.0, "bottom": 588.0},
      "comment": "Change this text to hao"
    }
  ]
}
```

Response: updated `FeedbackSession`.

Errors:

- `400` for empty item list
- `400` for blank comment
- `400` for missing `nodeUid` on node target
- `404` or domain-specific session error for unknown `previewId`
- `400` for invalid or out-of-screen bounds

`POST /api/agent-handoffs`

Existing behavior remains: creates a persisted handoff batch from draft items.
The top-bar label is `Send`.

`GET /api/export/markdown`

Existing endpoint remains. The top-bar label is `Copy`.

## Service Responsibilities

`FeedbackSessionService` owns transient preview state because it already owns
session-level workflow state around capture/navigation.

Required methods:

```kotlin
suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot

fun savePreviewFeedbackItems(
    sessionId: String,
    previewId: String,
    items: List<PendingDraftFeedbackItem>,
): FeedbackSession
```

Implementation notes:

- `capturePreview` uses the bridge capture snapshot path but writes to preview
  cache, not session artifact storage.
- the captured screen should use the eventual `screenId` that will be promoted
  on save, so semantics node references and screenshot artifact naming stay
  consistent.
- keep only a small bounded number of previews, such as 3 per service process.
- `savePreviewFeedbackItems` removes the preview from transient state once it is
  promoted.
- if saving fails before store mutation, do not create a partial session update.
- if artifact promotion fails, return an error and keep the session unchanged.

## Store Responsibilities

`FeedbackSessionStore` needs one atomic mutation:

```kotlin
fun addScreenWithItems(
    sessionId: String,
    screen: CapturedScreen,
    items: List<FeedbackItem>,
): FeedbackSession
```

Contract:

- requires at least one item
- assigns IDs for pending screen/items
- assigns one timestamp for screen/items/update
- assigns contiguous sequence numbers
- appends one screen and all items together
- persists once through existing persistence flow

This avoids a state where the evidence snapshot exists without its feedback
items, or items exist without their snapshot.

## Selection Semantics

Node selection:

- hit-test merged semantics nodes first
- fall back to unmerged nodes
- choose smallest containing bounds, with stable tie-break by node order
- store `FeedbackTarget.Node(nodeUid, bounds)`
- store the full selected `PointPatchNode`
- collect nearby meaningful nodes from the same root

Area selection:

- normalize drag bounds
- enforce minimum dimensions
- store `FeedbackTarget.Area(bounds)`
- collect meaningful nodes overlapping the area first
- if no node overlaps, collect nearest meaningful nodes around area center
- source hint confidence should be presented as lower than a direct node target

Pending overlay:

- each pending item renders a numbered badge
- node targets render node bounds
- area targets render selected area bounds
- numbers are derived from current pending list order
- deleting an item renumbers the list and overlay
- no inline edit support

## Source Hints

Source hints should prioritize what an AI coding agent can search:

- likely file and line
- confidence
- matched terms
- match reasons
- selected text
- editable text when safe
- content description
- test tag
- role
- nearby UI labels for area targets

Node target matching:

```text
selectedNode = selected semantics node
nearbyNodes = meaningful nodes near selected node
sourceCandidates = SourceMatcher.match(sourceIndex, selectedNode, nearbyNodes, activityName)
```

Area target matching:

```text
evidenceNodes = overlapping meaningful nodes, else nearest meaningful nodes
selectedNodeForMatching = first evidence node, if present
nearbyNodes = remaining evidence nodes
sourceCandidates = SourceMatcher.match(sourceIndex, selectedNodeForMatching, nearbyNodes, activityName)
```

If no source index is available, Markdown should still include target evidence
and search terms. It should not pretend a source file is known.

## Console Layout

The console should stay dense and utilitarian.

Top bar:

```text
Device picker | Interval | Refresh | Add | Save | Copy | Send | New | Close
```

Left pane:

- sessions only
- no primary `Screens` history list

Center pane:

- live preview when idle
- frozen preview while adding
- numbered overlay markers
- navigation controls visible only when idle

Right pane:

- selection summary
- comment composer
- `Add to Pending`
- pending item list while adding
- saved evidence groups

Button label rules:

- `Refresh`, not `Refresh Preview`
- `Add`, not `Add Items`
- `Save`, not `Save Items`
- `Copy`, not `Copy Agent Context`
- `Send`, not `Send Draft to Agent`

`Copy` and `Send` must be top-bar actions next to each other.

## Visible Metadata

Session rows should be short:

```text
Active | 2 draft | 1 sent | updated 06:52 PM
```

Do not show:

- session ID
- package name in each row
- screen count in the default row
- batch ID

Saved evidence group rows:

```text
MainActivity | 3 items | screenshot attached
```

Draft item rows inside a group:

```text
#2 Add left margin
Email address | Source hint available
```

Do not show by default:

- item ID
- screen ID
- delivery
- status
- screenshot size
- captured time
- raw artifact paths

## Agent Markdown Shape

Markdown should be compact and actionable.

```markdown
# PointPatch Feedback Handoff

- Package: `io.github.pointpatch.sample`
- Feedback Items: `2`
- Screenshots: local debug artifacts available through PointPatch tooling

## Item 1

Request:
Give this field 20 more px of left margin

Target:
- Type: Compose semantics node
- Text: `Email address`
- Test Tag: `emailField`
- Bounds: `28.0,77.0,692.0,186.0`

Likely Source:
1. `sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt:37` high confidence
   - matched: `Email address`, `emailField`
   - reasons: selected text, selected testTag
```

For area targets:

```markdown
Target:
- Type: Visual area
- Bounds: `112.0,426.0,351.0,588.0`
- Nearby UI: `Email address`, `Submit`
- Note: area selection only; verify screenshot and source candidates.
```

Markdown must not include:

- session ID
- screen ID
- item ID
- batch ID
- raw screenshot file paths
- repeated delivery/status/captured-time metadata
- screenshot dimensions unless a future user-facing need appears

## Compatibility

Existing JSON session schema remains the durable tool contract. This design adds
new console APIs and service methods, but existing MCP reads should continue to
work with persisted sessions.

Existing sessions with many historical screens remain readable. The redesigned
UI should simply avoid making old unreferenced screens a primary workflow
surface. Deleting old screens remains a separate existing operation.

## Verification Requirements

Targeted tests:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Broader tests before completion:

```bash
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
git diff --check
```

Connected smoke, when `SM_G986N` is available:

- preview appears without appending to `session.screens`
- `Add` freezes current preview
- two pending items show numbered overlays
- deleting one pending item renumbers remaining overlays
- `Save` creates one persisted screen and N items
- expanding the saved evidence group shows screenshot and comments
- `Copy` produces compact source-hinted Markdown
- `Send` creates a handoff batch

## Open Risks

- `1s` preview may feel slow over Wi-Fi debugging; the interval setting and
  `Manual` mode mitigate this.
- Preview cache cleanup must be bounded so repeated preview polling does not
  leave large local artifacts.
- Source matching is best-effort. Area selections can only provide evidence and
  likely files, not guaranteed exact component ownership.
- The UI must keep overlay coordinate mapping in natural screenshot pixels; CSS
  scaling must not affect saved bounds.
