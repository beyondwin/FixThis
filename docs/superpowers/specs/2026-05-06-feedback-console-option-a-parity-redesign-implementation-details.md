# Feedback Console Option A Parity Redesign Implementation Details

Date: 2026-05-06

Related documents:

- Product/UI design: `docs/superpowers/specs/2026-05-06-feedback-console-option-a-parity-redesign-design.md`
- Prior Studio implementation details, superseded where conflicting:
  `docs/superpowers/specs/2026-05-06-feedback-console-option-a-studio-redesign-implementation-details.md`
- Current live-preview/batched design, superseded where conflicting:
  `docs/superpowers/specs/2026-05-05-feedback-console-live-preview-batched-items-detailed-design.md`
- Executable Option A prototype:
  `/Users/kws/Downloads/PointPatch Console _standalone_.html`

## Purpose

This document explains how to implement the Option A parity redesign in the
PointPatch feedback console. It translates the Option A prototype into concrete
PointPatch server, persistence, API, and browser-client changes.

The implementation should make Option A the visible workflow:

- History is a snapshot/screen history inside the active feedback session.
- The canvas has `Select` and `Annotate` tools.
- Annotating the preview immediately registers numbered severity-colored
  annotations.
- The inspector is `Annotations` list mode or `Annotation` detail mode.
- Annotation detail supports label, severity, comment, status, delete, and done.
- `Save snapshot` creates the local AI-agent handoff from registered
  annotations.

The old V1.2 pending-item flow remains useful as a compatibility reference, but
it must not shape the visible UI.

## Implementation Boundary

In scope:

- `pointpatch-mcp` server/session/data-model changes.
- Embedded browser console in `FeedbackConsoleAssets.kt`.
- Console API request/response models under `pointpatch-mcp/.../console`.
- Markdown handoff formatting.
- Persistence compatibility for existing session JSON.
- Targeted tests for model, service, server, HTML contract, and handoff output.
- Current docs that describe the console.

Out of scope:

- External AI API calls.
- Network inspector, mocking, websocket platform, cloud sync, arbitrary typing,
  scripted Android automation, or Android network permissions.
- Reintroducing visible `Back`, `Up`, `Down`, `Left`, `Right` navigation
  controls in this UI.
- Reintroducing `New Session` in the top-right Option A action group.

## Current Code Surface

Primary implementation files:

```text
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsolePreviewModels.kt
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleItemModels.kt
```

Recommended new file:

```text
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAnnotationModels.kt
```

Primary test files:

```text
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
```

## Data Model

Use existing `FeedbackItem` as the persisted annotation record. It already owns:

- `itemId`
- `screenId`
- target bounds and selected node evidence
- source candidates
- comment
- sequence number
- delivery and handoff batch metadata
- status

Extend it with Option A annotation fields:

```kotlin
@Serializable
enum class FeedbackAnnotationSeverity {
    @SerialName("high")
    HIGH,

    @SerialName("med")
    MED,

    @SerialName("low")
    LOW,
}

@Serializable
data class FeedbackItem(
    // existing fields...
    val label: String? = null,
    val severity: FeedbackAnnotationSeverity = FeedbackAnnotationSeverity.MED,
)
```

Rationale:

- This keeps screenshot evidence, source candidates, item IDs, and handoff
  batches in one existing persisted model.
- Kotlin serialization can read old JSON because the new fields have defaults.
- Existing tool contracts that read `FeedbackItem` still work.

Visible annotation status uses existing `FeedbackItemStatus` values:

```text
open        -> FeedbackItemStatus.OPEN
in-progress -> FeedbackItemStatus.IN_PROGRESS
resolved    -> FeedbackItemStatus.RESOLVED
```

Keep the other enum values for existing MCP agent resolution flows:

```text
ready
needs_clarification
wont_fix
```

Console display should normalize unsupported legacy statuses:

- `READY` renders as `open` in the Option A UI.
- `NEEDS_CLARIFICATION` renders as `in-progress` unless a later product design
  gives it its own visible state.
- `WONT_FIX` renders as `resolved` with reduced opacity.

Do not delete the existing enum values. They are part of current MCP behavior.

## Snapshot History Model

Option A `snapshots` map to persisted `CapturedScreen` records in the active
`FeedbackSession`.

The left History panel should not list feedback sessions. It should list
snapshot cards for screens that have annotations in the current session.

Create console response models:

```kotlin
@Serializable
data class FeedbackSnapshotList(
    val snapshots: List<FeedbackSnapshotSummary> = emptyList(),
)

@Serializable
data class FeedbackSnapshotSummary(
    val screenId: String,
    val title: String,
    val activityName: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val annotationCount: Int,
    val openCount: Int,
    val resolvedCount: Int,
    val severityStrip: List<FeedbackAnnotationSeverity>,
)
```

Summary rules:

- `screenId` is the snapshot id used by the browser as `activeSnapshotId`.
- `title` uses `CapturedScreen.displayName`.
- `createdAtEpochMillis` uses `CapturedScreen.capturedAtEpochMillis`.
- `updatedAtEpochMillis` is the max of the screen capture time and annotation
  update times for that screen.
- `annotationCount` counts all items for that `screenId`.
- `openCount` counts UI-normalized open annotations.
- `resolvedCount` counts UI-normalized resolved annotations.
- `severityStrip` is the annotations for that screen sorted by sequence/order.

For old sessions with screens but no items, do not show those screens in the
Option A History panel. The visible history is annotation-focused.

## Console API Shape

Keep existing endpoints for compatibility. Add annotation-focused endpoints for
the Option A UI.

### List Snapshot History

```text
GET /api/snapshots
```

Response:

```json
{
  "snapshots": [
    {
      "screenId": "screen-1",
      "title": "MainActivity",
      "activityName": "io.github.pointpatch.MainActivity",
      "createdAtEpochMillis": 1715000000000,
      "updatedAtEpochMillis": 1715000001000,
      "annotationCount": 3,
      "openCount": 2,
      "resolvedCount": 1,
      "severityStrip": ["med", "high", "low"]
    }
  ]
}
```

### Create Annotation

```text
POST /api/annotations
```

Request:

```json
{
  "previewId": "preview-1",
  "screenId": null,
  "targetType": "node",
  "nodeUid": "compose:0:merged:42",
  "bounds": {"left": 20.0, "top": 80.0, "right": 360.0, "bottom": 140.0},
  "label": "Balance card",
  "severity": "med",
  "comment": "",
  "status": "open"
}
```

Rules:

- Exactly one of `previewId` or `screenId` must be provided.
- `previewId` means the annotation is being created from the current live
  preview. The server promotes that preview to a persisted `CapturedScreen`
  before storing the first annotation.
- `screenId` means the annotation is added to an already persisted snapshot.
- `targetType = node` requires `nodeUid`.
- `targetType = area` ignores `nodeUid`.
- Bounds must be finite, positive, and inside the screenshot when screenshot
  dimensions are known.
- `label` may be blank in the raw request, but the service should derive a
  display label before persisting:
  - node target: best semantic label from text/contentDescription/testTag/role
  - area target: `Region N`
- `comment` may be blank. Option A creates an annotation first and lets the
  user fill the comment in the detail inspector.
- `severity` defaults to `med`.
- `status` defaults to `open`.

Response should return the updated `FeedbackSession`. The browser can derive
the active snapshot and annotations from it without another round trip.

### Update Annotation

```text
PATCH /api/annotations/{itemId}
```

Request:

```json
{
  "label": "Balance card",
  "severity": "high",
  "comment": "Cents need to step down further.",
  "status": "in_progress"
}
```

Rules:

- At least one field must be present.
- Empty `label` is allowed only if the service derives a fallback label for
  display.
- `severity` accepts only `high`, `med`, `low`.
- `status` accepts only `open`, `in_progress`, `resolved` from this console
  endpoint.
- Update `updatedAtEpochMillis`.
- Keep `screenId`, target evidence, source candidates, sequence number, and
  created timestamp unchanged.

Response: updated `FeedbackSession`.

### Delete Annotation

```text
DELETE /api/annotations/{itemId}
```

Rules:

- Delete only that `FeedbackItem`.
- Remove the item id from handoff batch `itemIds`.
- Drop empty handoff batches after removal.
- Do not delete the `CapturedScreen` unless no annotations remain for that
  screen and the implementation explicitly chooses to clean up empty snapshots.
  The preferred first implementation keeps the screen so history transitions are
  predictable during the session.

Response: updated `FeedbackSession`.

### Send Snapshot Annotations To Agent

```text
POST /api/agent-handoffs
```

Request for Option A UI:

```json
{
  "screenId": "screen-1"
}
```

Rules:

- If `screenId` is present, create a handoff batch from all annotations for that
  snapshot sorted by sequence/order.
- If `screenId` is absent, keep the old draft-send behavior for compatibility.
- Reject an unknown `screenId` with `400`.
- Reject a snapshot with no annotations with `409`.
- Preserve annotation statuses. Do not force `OPEN`, `IN_PROGRESS`, or
  `RESOLVED` annotations to `READY`.
- Mark included annotations as `delivery = SENT`, set `handoffBatchId`, and set
  `sentAtEpochMillis`.
- If already sent annotations are edited later, the implementation should set
  `delivery = DRAFT` and clear stale handoff metadata so `Save snapshot` can
  send the changed annotation again.

Response: updated `FeedbackSession`.

## Request Models

Add `FeedbackConsoleAnnotationModels.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.FeedbackAnnotationSeverity
import io.github.pointpatch.mcp.session.FeedbackItemStatus
import kotlinx.serialization.Serializable

@Serializable
data class CreateFeedbackAnnotationRequest(
    val previewId: String? = null,
    val screenId: String? = null,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
    val label: String? = null,
    val severity: FeedbackAnnotationSeverity = FeedbackAnnotationSeverity.MED,
    val comment: String = "",
    val status: FeedbackItemStatus = FeedbackItemStatus.OPEN,
)

@Serializable
data class UpdateFeedbackAnnotationRequest(
    val label: String? = null,
    val severity: FeedbackAnnotationSeverity? = null,
    val comment: String? = null,
    val status: FeedbackItemStatus? = null,
)

@Serializable
data class SendAnnotationHandoffRequest(
    val screenId: String? = null,
)
```

Do not expose `READY`, `NEEDS_CLARIFICATION`, or `WONT_FIX` through the browser
status segmented control. Validate console update requests before converting
them into store mutations.

## Store Changes

Add methods to `FeedbackSessionStore`:

```kotlin
fun addAnnotation(sessionId: String, screen: CapturedScreen?, item: FeedbackItem): FeedbackSession

fun updateAnnotation(
    sessionId: String,
    itemId: String,
    label: String?,
    severity: FeedbackAnnotationSeverity?,
    comment: String?,
    status: FeedbackItemStatus?,
): FeedbackSession

fun deleteAnnotation(sessionId: String, itemId: String): FeedbackSession

fun sendSnapshotAnnotationsToAgent(
    sessionId: String,
    screenId: String,
    markdownSnapshot: String?,
): FeedbackSession
```

Implementation details:

- `addAnnotation` should append the screen if the annotation is created from a
  preview promotion, otherwise use the existing screen.
- `sequenceNumber` remains the persisted annotation order.
- `updateAnnotation` should turn edited sent annotations back into draft:
  `delivery = DRAFT`, `handoffBatchId = null`, `sentAtEpochMillis = null`.
- `deleteAnnotation` removes the item from batches and removes empty batches.
- `sendSnapshotAnnotationsToAgent` creates a new `FeedbackHandoffBatch` for all
  items with the given `screenId`, preserving each item status.
- Existing `sendDraftToAgent` remains for compatibility.

Do not reuse `updateItemStatus` for browser annotation edits. That method is
currently agent-resolution oriented and rejects `OPEN`/`IN_PROGRESS`.

## Service Changes

Add service methods to `FeedbackSessionService`:

```kotlin
fun createAnnotation(sessionId: String, request: CreateFeedbackAnnotationRequest): FeedbackSession

fun updateAnnotation(sessionId: String, itemId: String, request: UpdateFeedbackAnnotationRequest): FeedbackSession

fun deleteAnnotation(sessionId: String, itemId: String): FeedbackSession

fun snapshotHistory(sessionId: String): FeedbackSnapshotList

fun sendSnapshotAnnotationsToAgent(sessionId: String, screenId: String): FeedbackSession
```

Create-from-preview flow:

1. Look up `previewSnapshots[previewId]`.
2. Build the `FeedbackItem` using the existing source-candidate logic from
   `buildFeedbackItemForDraft`.
3. Promote preview artifacts into
   `.pointpatch/feedback-sessions/<sessionId>/artifacts/screens/<screenId>/`.
4. Add the persisted `CapturedScreen` and annotation in one store mutation.
5. Remove preview cache only after the mutation succeeds.

Create-from-screen flow:

1. Look up the persisted `CapturedScreen`.
2. Validate the target against that screen's nodes/screenshot bounds.
3. Build the `FeedbackItem`.
4. Append it through `addAnnotation`.

To avoid duplicating existing evidence code, split `buildFeedbackItemForDraft`
into a helper that accepts:

```kotlin
private fun buildFeedbackItem(
    screen: CapturedScreen,
    sourceIndex: SourceIndex?,
    targetType: FeedbackTargetType,
    bounds: PointPatchRect,
    nodeUid: String?,
    label: String?,
    severity: FeedbackAnnotationSeverity,
    comment: String,
    status: FeedbackItemStatus,
): FeedbackItem
```

The helper should:

- validate the target
- allow blank comments for annotation creation and updates
- derive stored bounds from the selected node when target type is node
- collect nearby evidence nodes
- attach source candidates
- derive a non-empty label if request label is blank
- persist severity and status

Status validation for browser edits:

```text
allowed browser statuses = OPEN, IN_PROGRESS, RESOLVED
```

## Server Changes

Add routes in `FeedbackConsoleServer.handle`:

```text
GET    /api/snapshots
POST   /api/annotations
PATCH  /api/annotations/{itemId}
DELETE /api/annotations/{itemId}
POST   /api/agent-handoffs  // decode optional screenId
```

Path helpers:

```kotlin
private fun String.isAnnotationPath(): Boolean =
    split('/').size == 4 && startsWith("/api/annotations/")

private fun String.itemIdFromAnnotationPath(): String =
    URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())
```

Request-body guardrails:

- Parse request bodies as JSON objects before decoding.
- Reject unsupported fields for create/update/handoff requests.
- Preserve the existing unsupported-field behavior used by navigation and item
  requests.

HTTP status mapping:

- `400` invalid body, invalid status, invalid severity, invalid bounds, unknown
  screen for an annotation request.
- `404` unknown annotation item id.
- `409` no annotations to send.
- `500` unexpected persistence or bridge failures.

## Browser State

Replace visible pending-item state with Option A state:

```javascript
const state = {
  session: null,
  preview: null,
  snapshots: [],
  activeSnapshotId: null,
  selectedDeviceSerial: null,
};

let tool = 'select';
let annotations = [];
let selectedAnnotationId = null;
let draggingRect = null;
let dragStart = null;
let dragMoved = false;
```

Derived state:

```javascript
const selectedAnnotation = annotations.find(item => item.itemId === selectedAnnotationId);
const openCount = annotations.filter(item => uiStatus(item.status) === 'open').length;
const resolvedCount = annotations.filter(item => uiStatus(item.status) === 'resolved').length;
```

Keep preview polling if a live device is selected, but do not allow polling to
replace the active persisted snapshot while the user is editing annotations.

## Browser Rendering Regions

Keep region rendering split to avoid flicker:

```javascript
function renderTopbar() {}
function renderHistoryPanel() {}
function renderCanvasToolbar() {}
function renderPreviewSurface() {}
function renderAnnotationOverlay() {}
function renderInspector() {}
function renderAnnotationList() {}
function renderAnnotationDetail() {}
function renderAll() {}
```

Live preview refresh should call only the preview/canvas region unless the
active snapshot changes.

History item click should call:

```javascript
setActiveSnapshot(screenId)
renderHistoryPanel()
renderPreviewSurface()
renderAnnotationOverlay()
renderInspector()
```

Annotation field edits should call:

```javascript
updateAnnotation(...)
renderHistoryPanel()
renderAnnotationOverlay()
renderInspector()
```

## Option A UI CSS

Keep or introduce these Option A classes:

```text
studio
studio-topbar
studio-body
studio-history
studio-canvas
studio-inspector
topbar-breadcrumb
topbar-actions
btn-primary
btn-ghost
panel-head
panel-title
panel-count
history-list
history-item
hi-head
hi-title
hi-meta
hi-stats
hi-pip
hi-strip
hi-strip-cell
canvas-toolbar
tool-group
tool
tool-status
ts-hint
ts-meta
tool-zoom
canvas-stage
pin-rect
pin-tag
drag-rect
ann-list
ann-row
ann-row-num
ann-row-body
ann-row-title
ann-row-comment
ann-row-status
ann-detail
back-btn
field
seg
seg-btn
detail-actions
```

Do not render these visible controls:

```text
New Session
Back
Up
Down
Left
Right
Composer
Draft
Focus pending item
Delete pending item
```

`Save snapshot` remains visible and disabled when `annotations.length === 0`.

## Tool Switching Behavior

`Select` click:

- set `tool = 'select'`
- clear any drag state
- keep current selection unless the user clicks empty preview space
- show stats in the toolbar
- do not create an annotation

`Annotate` click:

- set `tool = 'annotate'`
- clear selected annotation
- show the hint pill: `Click a widget - or drag to draw a region`
- preview pointer down starts a possible annotation target
- preview pointer move shows `drag-rect`
- preview pointer up creates an annotation

After an annotation is created:

- append it to the ordered annotation list
- select the new annotation
- switch back to `Select`
- render the new numbered preview pin immediately

## Preview Overlay Behavior

Use the Option A pin model:

```javascript
function severityColor(severity) {
  if (severity === 'high') return '#F26D6D';
  if (severity === 'med') return '#E6B45A';
  return '#5AB1E6';
}
```

For each annotation, render:

```html
<div class="pin-rect" style="--pin-color: ...">
  <div class="pin-tag">1</div>
</div>
```

Rules:

- Pin number is annotation index + 1.
- Pin number must match the right-panel row number.
- Rectangle border, fill, and tag all use severity color.
- Selected pin adds `is-selected`.
- Resolved pins remain visible with reduced opacity.
- Creating three annotations through `Annotate` visibly produces `1`, `2`, and
  `3` on the preview, colored independently by severity.
- Deleting an annotation renumbers rows and pins with no gaps.

PointPatch stores bounds in natural screenshot coordinates. The browser should
convert natural bounds to percent CSS values when rendering overlays:

```javascript
left = bounds.left * 100 / image.naturalWidth
top = bounds.top * 100 / image.naturalHeight
width = (bounds.right - bounds.left) * 100 / image.naturalWidth
height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight
```

## Inspector Behavior

Empty annotation state:

```text
No annotations yet
Switch to Annotate, then click a widget or drag a region on the preview.
Start annotating
```

`Start annotating` is equivalent to clicking `Annotate`.

List state:

- title: `Annotations`
- count: total annotations for active snapshot
- rows: number badge, label, comment preview, status pill
- row click opens detail and selects matching pin

Detail state:

- title: `Annotation`
- fields: label, severity, comment, status
- actions: delete, done
- `Done` clears selection and returns to list
- `Delete` deletes the annotation, clears selection, updates history, updates
  overlay, and returns to list
- edits persist through `PATCH /api/annotations/{itemId}`

## Handoff Markdown

Update `FeedbackQueueFormatter` so Option A annotations include:

```text
## Annotation 1

Label:
Balance card

Severity:
high

Status:
open

Request:
Cents need to step down further.

Target:
...

Likely Source:
...
```

Keep the existing compact request/target/source emphasis. Do not dump raw
storage JSON into Markdown.

When formatting old items with no label or severity:

- derive label from selected node evidence or `Annotation N`
- severity defaults to `med`

## Persistence Compatibility

Existing session JSON should remain readable because:

- `FeedbackItem.label` defaults to `null`.
- `FeedbackItem.severity` defaults to `MED`.
- `FeedbackItemStatus` keeps all existing enum values.
- `FeedbackDelivery` remains unchanged.

Add persistence tests that decode old JSON without `label` or `severity` and
assert:

```text
label == null
severity == MED
```

No migration file is required for the first implementation because the new
fields have serializer defaults.

## Test Plan

Targeted model/persistence tests:

```text
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
```

Required assertions:

- old persisted item JSON without `severity` decodes as `MED`
- label/severity/status/comment updates persist and reload
- delete removes one annotation and removes item ids from handoff batches

Service tests:

```text
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Required assertions:

- creating first annotation from preview promotes one screen
- creating second annotation on the same screen does not promote another screen
- node click uses selected node bounds
- area drag uses dragged bounds
- severity defaults to `MED`
- status defaults to `OPEN`
- blank comment is allowed when creating an annotation
- update supports `OPEN`, `IN_PROGRESS`, `RESOLVED`
- update rejects `READY`, `NEEDS_CLARIFICATION`, `WONT_FIX` from browser API
- save snapshot handoff preserves annotation statuses

Console server/API tests:

```text
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Required assertions:

- `GET /api/snapshots` returns snapshot counts and severity strip
- `POST /api/annotations` validates unsupported fields
- `POST /api/annotations` accepts a blank comment
- `PATCH /api/annotations/{itemId}` updates label/severity/comment/status
- `DELETE /api/annotations/{itemId}` deletes exactly one annotation
- `POST /api/agent-handoffs` with `screenId` sends snapshot annotations
- HTML contains Option A history classes
- HTML contains `Select`, `Annotate`, and `Save snapshot`
- HTML does not contain visible `New Session`, `Back`, `Up`, `Down`, `Left`,
  `Right`, `Composer`, or pending `Focus`
- HTML contains `Severity`, `high`, `med`, `low`
- HTML contains `Status`, `open`, `in-progress`, `resolved`
- HTML contains `Delete` and `Done`
- HTML contains pin rectangle rendering and severity colors

Formatter tests:

```text
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Required assertions:

- Markdown includes annotation label
- Markdown includes severity
- Markdown includes status
- Markdown includes comment
- Markdown keeps target evidence and likely source

Final verification:

```text
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
git diff --check
```

## Manual Browser Smoke

Use the executable prototype as the visual reference:

```text
file:///Users/kws/Downloads/PointPatch%20Console%20_standalone_.html
```

Smoke the implemented console against the same behavior:

1. Load the console.
2. Confirm the dark Option A Studio shell renders.
3. Confirm History panel height is fixed and scrolls internally.
4. Confirm `Select` and `Annotate` do not change toolbar height.
5. Click `Annotate`.
6. Confirm the annotate hint pill appears.
7. Click a widget on the preview.
8. Confirm annotation `1` appears as a severity-colored pin rectangle.
9. Confirm the inspector opens `Annotation` detail for the new annotation.
10. Change severity to `high`.
11. Confirm the preview pin, row badge, and history strip turn red.
12. Click `Done`.
13. Confirm inspector returns to `Annotations` list.
14. Click `Annotate` and drag a region.
15. Confirm annotation `2` appears on the preview.
16. Create a third annotation and confirm `1`, `2`, `3` all render.
17. Delete annotation `2`.
18. Confirm remaining pins and rows renumber to `1`, `2`.
19. Change status to `resolved`.
20. Confirm open/resolved counts update in toolbar/history.
21. Click a history card.
22. Confirm canvas screenshot, pins, and annotation list restore.
23. Click `Save snapshot`.
24. Confirm a local handoff batch is persisted and contains current
    annotations.

## Rollout Notes

This is a breaking visible-console redesign. Keep compatibility APIs where they
reduce risk, but do not keep the old visible workflow in the Option A surface.

If implementation has to be split into phases, use this order:

1. Data model and persistence compatibility.
2. Store/service annotation API.
3. Server routes and request validation.
4. Formatter/handoff changes.
5. HTML contract tests.
6. Option A shell/history/canvas/inspector rendering.
7. Browser smoke and docs update.

Each phase should keep tests passing before moving to the next phase.
