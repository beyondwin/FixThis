# Feedback Console Option A Studio Redesign Implementation Details

Date: 2026-05-06

Related documents:

- Task plan: `docs/superpowers/plans/2026-05-06-feedback-console-option-a-studio-redesign-implementation.md`
- Current live-preview design: `docs/superpowers/specs/2026-05-05-feedback-console-live-preview-batched-items-detailed-design.md`
- Selection handoff design: `docs/superpowers/specs/2026-05-05-feedback-console-selection-handoff-design.md`
- Prior V2 design: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- Executable visual prototype: `/Users/kws/Downloads/PointPatch Console _standalone_.html`
- Detailed Studio spec prototype: `/Users/kws/Downloads/PointPatch Studio Spec _standalone_.html`

## Purpose

This document is the detailed implementation guide for rebuilding the
PointPatch feedback console around the supplied Option A Studio UI. The task
plan is the executable checklist. This document explains the intended
architecture, state ownership, event mapping, rendering boundaries, and
acceptance contracts behind those tasks.

The redesign is complete when the console visually and interactively feels like
Option A Studio while preserving PointPatch's live-preview and batched evidence
workflow:

- idle preview interaction navigates the connected Android app
- `Add` freezes the latest preview and enters feedback collection
- one frozen preview can accumulate many pending items
- pending items can only be focused or deleted
- `Save` persists one evidence snapshot and links every pending item to it
- `Copy` and `Send` remain session-level top-bar actions
- live preview polling does not disturb the draft inspector

## Scope

In scope:

- Replace the embedded console HTML/CSS/JavaScript in
  `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`.
- Update console HTML contract tests in
  `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`.
- Update user/operator documentation that describes the current console UI.
- Preserve existing server endpoints, persistence schema, CLI behavior, bridge
  contract, and MCP tool contract.

Out of scope:

- No new server API shape.
- No new persistence schema or migration.
- No external AI API call.
- No network inspector, mocking, websocket platform, cloud sync, scripted app
  automation, arbitrary text input, or Android network permission.
- No multi-user collaboration.
- No pending-item edit UI.
- No Select/Navigate toggle.

## Source Prototype Contract

`/Users/kws/Downloads/PointPatch Console _standalone_.html` is an executable
prototype. Use it as the source of truth for Studio interaction feel, not as a
literal data model to copy.

Observed Option A behavior:

- `StudioOption` owns `snapshots`, `activeSnapId`, `annotations`, `draftTitle`,
  `selectedId`, `draggingRect`, and `tool`.
- `New session` clears the working set.
- `Save snapshot` deep-copies current annotations into immutable history.
- History cards reopen snapshots; delete does not accidentally open the card.
- `Select` inspects existing annotations.
- `Annotate` creates annotations by widget click or drag, then returns to
  `Select`.
- Widget targeting uses closest `[data-w]` metadata.
- Prototype annotation bounds are percent-based.
- Annotation list rows select the matching overlay and open a detail inspector.
- The detail inspector can edit label, severity, comment, and status.
- Keyboard shortcuts cover tool switching, escape, save, new session, delete,
  and severity shortcuts.

PointPatch must adapt this behavior:

- Keep the Studio shell, history, canvas, inspector, token system, spacing,
  hover/focus behavior, and modal-free workflow.
- Replace prototype tool mode with PointPatch's `Add` flow. Idle preview clicks
  navigate; feedback selection only exists while the preview is frozen.
- Use PointPatch natural screenshot coordinates for bridge requests and saved
  evidence overlays. Do not convert persisted selection bounds to prototype
  percent coordinates.
- Do not copy prototype label/severity/status editing into pending items.
- Keep pending correction as delete-and-add.

## Current Implementation Surface

Primary file:

```text
pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt
```

Important existing JavaScript state and functions:

```javascript
let addItemsFlow = null;
let pendingFeedbackItems = [];
let focusedPendingItemIndex = null;
let currentSelection = null;
let dragStart = null;
let dragPreview = null;
let previewRequestGeneration = 0;
let previewRequestInFlight = null;
let previewRequestContextGeneration = 0;
let previewRequestInFlightContextGeneration = null;

async function refreshPreview() {}
async function startAddItemsFlow() {}
function queuePendingFeedbackItem() {}
async function savePendingFeedbackItems() {}
function renderPendingItems() {}
function renderSavedEvidenceGroups() {}
function renderNumberedFeedbackOverlay(overlay, image) {}
function renderSelectionOverlay() {}
function selectNodeAtPoint(event, image) {}
function finishAreaSelection(bounds) {}
async function navigate(action, extras = {}) {}
```

The redesign should keep these names where practical. New helper names should
be explicit and region-oriented, for example:

```javascript
function renderSessionRegions() {}
function renderPreviewRegion() {}
function renderInspectorRegion() {}
function renderPreviewOnly() {}
function renderComposerInspector() {}
function renderDraftInspector() {}
function ensurePreviewFrame() {}
function hydrateSavedEvidencePreviews() {}
```

## Target UI Information Architecture

The console becomes a three-panel Studio workspace under one top bar.

```text
56px top bar
  left: PointPatch / Studio brand
  middle: session/device context and preview interval
  right: Refresh | Add | Save | Copy | Send | New | Close

body
  left 280px: Sessions plus Sent History drawer
  center flexible: Canvas toolbar plus live/frozen Android preview
  right 340px: mode-aware Inspector
```

Top-bar actions remain short and session-level:

```text
Refresh | Add | Save | Copy | Send | New | Close
```

`Copy` and `Send` must never move into the comment composer. They operate on
the current draft/session state.

## DOM Contract

The new shell should expose stable classes for layout and stable IDs for
existing behavior.

Required layout classes:

```text
studio-shell
studio-topbar
studio-body
studio-history
studio-canvas
studio-inspector
studio-brand
studio-context
studio-actions
canvas-toolbar
history-list
inspector-body
inspector-footer
```

Required action/control IDs:

```text
devicePicker
previewIntervalSelect
refreshDevicesButton
disconnectDeviceButton
refreshButton
addFlowButton
saveButton
copyMarkdownButton
sendDraftButton
newSessionButton
closeSessionButton
backButton
swipeUpButton
swipeDownButton
swipeLeftButton
swipeRightButton
captureAfterNavigation
```

Required region IDs:

```text
sessionMeta
deviceStatus
sessionCount
sessions
sentHistory
canvasToolbar
previewModeBadge
snapshotTitle
navigationControls
snapshot
snapshotFrame
snapshotImage
selectionOverlay
inspectorTitle
inspectorCount
inspectorBody
inspectorFooter
selectionSummary
comment
pendingItems
draftItems
clearSelectionButton
cancelAddFlowButton
addItemButton
clearDraftButton
error
```

Forbidden UI IDs/classes:

```text
modeSelect
modeNavigate
queue-pane
```

The absence of `modeSelect` and `modeNavigate` is intentional. The final UI may
have a canvas badge (`Live`, `Frozen`, `Idle`) but not a user-facing
Select/Navigate toggle.

## Visual System Contract

Use Option A Studio tokens as the primary palette:

```css
:root {
  color-scheme: dark;
  --bg-0: #0d0e10;
  --bg-1: #131418;
  --bg-2: #1a1c21;
  --bg-3: #21242b;
  --line: #2a2d35;
  --line-soft: rgba(42, 45, 53, .72);
  --txt-0: #e8e9eb;
  --txt-1: #b6b8be;
  --txt-2: #7d8089;
  --accent: #b8d36a;
  --danger: #f26d6d;
  --warning: #e6b45a;
}
```

Design rules:

- Use a restrained dark workspace, not a marketing layout.
- Keep cards and buttons at 8px radius or below.
- Use compact top-bar controls.
- Use the lime accent for active/frozen/primary state.
- Use the warning color for focused pending overlays.
- Add 120ms transitions for hover/focus polish.
- Respect `prefers-reduced-motion`.
- Avoid decorative blobs, gradients as content, oversized hero typography, or
  nested cards.

The center preview may use a subtle radial or frame shadow to make the phone
preview read as the main workspace object, but the phone screenshot must remain
clear and inspectable.

## State Model

### Global State

The browser state remains a UI cache around MCP-owned session state.

```javascript
const state = {
  session: null,
  preview: null,
  sessions: [],
  devices: [],
  selectedDeviceSerial: null
};
```

The exact current object shape may differ. Do not introduce a second durable
session model in the browser. `state.session` mirrors server state returned by
existing APIs.

### Live Preview

`state.preview` is transient:

```javascript
state.preview = {
  previewId: "preview-1",
  screen: {
    screenId: "preview-screen-1",
    displayName: "MainActivity",
    screenshot: {
      desktopFullPath: "...",
      width: 720,
      height: 1600
    },
    roots: []
  }
};
```

Rules:

- It must not be pushed into `state.session.screens`.
- It must not create a session history card.
- It must be served by preview-scoped screenshot URLs when available:

```javascript
function previewScreenshotUrl(previewId) {
  return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full';
}
```

### Frozen Add Flow

`addItemsFlow` is the only state that means "feedback selection mode".

```javascript
addItemsFlow = {
  previewId: state.preview.previewId,
  screen: state.preview.screen,
  screenshotUrl: previewScreenshotUrl(state.preview.previewId)
};
```

Rules:

- Set only from `startAddItemsFlow()`.
- Clear on cancel, successful save, session boundary, device boundary, or new
  session.
- While non-null, preview polling is stopped.
- While non-null, navigation controls are hidden.

### Pending Items

Pending items are client-side only until `Save`:

```javascript
pendingFeedbackItems.push({
  targetType: currentSelection.targetType,
  nodeUid: currentSelection.nodeUid,
  bounds: currentSelection.bounds,
  comment: feedbackComment
});
```

Rules:

- Pending item display numbers are derived from array order.
- Overlay numbers are derived from the same array order.
- Delete removes one array entry and therefore renumbers rows and overlays.
- Focus sets `focusedPendingItemIndex`; it must not create an extra unnumbered
  selection overlay.
- No edit, status, severity, label, move, or resize operation is provided.

### Current Selection

`currentSelection` represents the in-progress item before it is queued:

```javascript
currentSelection = {
  targetType: "node",
  nodeUid: "compose:0:merged:42",
  bounds: { left: 28, top: 77, right: 692, bottom: 186 }
};
```

Area selections omit `nodeUid`:

```javascript
currentSelection = {
  targetType: "area",
  bounds: { left: 112, top: 426, right: 351, bottom: 588 }
};
```

Bounds are natural screenshot pixels, not viewport CSS pixels and not
prototype percent coordinates.

## State Machine

```text
Idle
  polling: enabled when device selected, interval is not Manual, document visible
  center preview: latest transient preview if available
  preview click: navigate tap
  inspector: Draft
  Add: transition to Adding with frozen latest preview

Adding
  polling: disabled
  center preview: frozen preview image
  preview click: select Compose node
  preview drag: select visual area
  inspector: Composer
  Add to Pending: append pending item, clear current selection/comment
  Focus: highlight matching pending overlay
  Delete: remove pending item and renumber
  Cancel: discard pending items and frozen preview, return Idle
  Save: POST one batch, persist one evidence snapshot, return Idle

Idle with draft items
  polling: enabled by interval
  inspector: Draft with saved evidence groups
  Copy: copy compact Markdown for current draft
  Send: create persisted handoff batch and clear draft items
```

Boundary resets:

- Device change clears preview, add flow, pending items, focused item, and
  current selection.
- Session open/new/close clears preview, add flow, pending items, focused item,
  and current selection.
- `Add` must guard against stale in-flight preview requests with the existing
  preview generation counters.

## Rendering Architecture

The main UX bug to avoid is live preview polling causing the right-side draft
or composer to flicker. This requires region rendering.

Required functions:

```javascript
function renderSessionRegions() {
  // session meta, session cards, sent drawer
}

function renderPreviewRegion() {
  // mode badge, title, stable preview frame/image, overlays
}

function renderInspectorRegion() {
  // Draft or Composer, depending on addItemsFlow
}

function renderPreviewOnly() {
  renderPreviewRegion();
  renderSelectionOverlay();
  updateComposerState();
}

function render() {
  renderSessionRegions();
  renderPreviewRegion();
  renderInspectorRegion();
  updateComposerState();
}
```

`refreshPreview()` must end with `renderPreviewOnly()`, not `render()`.

```javascript
async function refreshPreview() {
  error.textContent = '';
  if (addItemsFlow) return;
  const requestGeneration = ++previewRequestGeneration;
  const preview = await requestLivePreview();
  if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;
  state.preview = preview;
  renderPreviewOnly();
}
```

### Stable Preview Image

Do not rebuild the whole center DOM for every preview refresh. Use a stable
frame and update `img.src` only when the source changes.

```javascript
function ensurePreviewFrame() {
  let frame = document.getElementById('snapshotFrame');
  if (frame) return frame;
  snapshot.innerHTML =
    '<div id="snapshotFrame" class="snapshot-frame">' +
      '<img id="snapshotImage" alt="PointPatch preview" aria-label="PointPatch preview">' +
      '<div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>' +
    '</div>';
  attachSnapshotHandlers();
  return document.getElementById('snapshotFrame');
}
```

If there is no screenshot, an empty-state replacement is acceptable. Once a
screenshot exists, keep the frame stable.

## Preview Polling Contract

Supported preview intervals:

```text
Manual
1s
2s default
5s
```

Rules:

- Minimum automatic interval is 1s.
- Store interval in browser `localStorage`.
- Pause automatic polling while `document.hidden`.
- Pause automatic polling while `addItemsFlow` is active.
- Manual `Refresh` performs one capture even when automatic polling is off.
- `Refresh` is disabled or ignored during frozen add flow.
- In-flight preview reuse and generation guards must be preserved.
- Preview refresh must only update the preview region.

## Canvas Behavior

### Idle Mode

Idle mode means no `addItemsFlow`.

Expected behavior:

- Badge reads `Live` after a preview exists, otherwise `Idle`.
- Navigation controls are visible.
- Preview click translates CSS event coordinates to natural screenshot pixels.
- Preview click calls one-step `navigate('tap', { x, y })`.
- Back/swipe buttons call only existing one-step navigation actions.
- No feedback target is selected.

### Frozen Mode

Frozen mode means `addItemsFlow` is non-null.

Expected behavior:

- Badge reads `Frozen`.
- Navigation controls are hidden.
- Preview image source is `addItemsFlow.screenshotUrl`.
- Live polling is stopped.
- Click selects the nearest Compose node at that point.
- Drag creates an area selection when natural screenshot width and height are at
  least 8 pixels.
- A small drag or click fallback selects a node.
- `suppressNextClick` prevents drag pointerup from also triggering a click.

### Overlay Numbering

Use a single source for pending row numbers and overlay labels:

```javascript
pendingFeedbackItems.map((item, index) => index + 1)
```

The visible overlay should render as `#1`, `#2`, `#3`, matching the pending row
labels. After delete, no gaps remain.

Current selection may render as a non-numbered outline while composing. Focused
pending items should only highlight their numbered overlay.

## Inspector Behavior

The right panel has two modes.

### Draft Inspector

Shown when `addItemsFlow` is null.

Contents:

- Title: `Draft`
- Count: number of draft feedback items
- Saved evidence groups
- Empty state when there are no draft items
- `Clear Draft` when draft items exist

Saved evidence groups should be expanded cards, not collapsed by default. Each
card shows:

- saved screenshot preview
- numbered overlay matching saved items
- saved comments
- target/source hints

### Composer Inspector

Shown when `addItemsFlow` is non-null.

Contents:

- Title: `Composer`
- Count: number of pending items
- current selection summary
- comment textarea
- pending item list
- footer actions: `Clear Selection`, `Cancel`, `Add to Pending`

Allowed pending item row actions:

```text
Focus
Delete
```

Forbidden pending item controls:

```text
Edit
Severity
Status
Label
Move
Resize
```

The comment textarea is only for creating the next pending item. It must not
edit already queued items.

## Session History And Sent Drawer

The left panel should feel like Option A history while using current PointPatch
session data.

Session cards:

- show human-readable session label and summary
- show active state with accent left edge
- avoid exposing internal `sessionId`, `screenId`, or `batchId` by default
- use button/card semantics so the entire row opens the session

Sent History:

- lives in a lower drawer under sessions
- lists handoff batches and any inconsistent sent item fallback rows
- uses human-readable labels
- does not expose internal IDs by default

Rendering should be split:

```javascript
function renderSessionsListFromPayload(sessionSummaries) {}
function renderSentHistory() {}
```

`refreshSessions()` should fetch summaries and update only session cards.

## Server API And Storage Contract

The redesign is client-side only. These endpoints stay unchanged:

```text
GET  /api/session
POST /api/session/new
POST /api/session/open
POST /api/session/close
GET  /api/sessions
GET  /api/devices
POST /api/devices/select
POST /api/devices/disconnect
GET  /api/preview
GET  /api/preview/screenshot/full
GET  /api/preview/{previewId}/screenshot/full
POST /api/navigation
POST /api/items/batch
POST /api/agent-markdown
POST /api/agent-handoffs
```

Persistence remains under:

```text
.pointpatch/feedback-sessions/
```

Live previews remain transient. `Save` is the only UI action in this flow that
promotes a frozen preview into a persisted evidence snapshot.

`Send` creates a persisted handoff batch for MCP tools to read. It does not call
an external AI API.

## Keyboard And Accessibility Contract

Required shortcuts:

```text
Escape: cancel Add flow, or clear current selection
A: start Add flow
Cmd/Ctrl+S: save pending feedback items
Cmd/Ctrl+N: new session
```

Guard shortcuts while text input is focused:

```javascript
function isTextInputFocused() {
  const tag = document.activeElement?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || document.activeElement?.isContentEditable;
}
```

Accessibility requirements:

- `error` uses `role="status" aria-live="polite"`.
- preview image has `alt="PointPatch preview"` and `aria-label`.
- icon-only navigation buttons have `aria-label`.
- pending `Focus` and `Delete` buttons include item numbers in `aria-label`.
- focused state is visible in keyboard and mouse flows.

## Test Strategy

The implementation plan adds string-contract tests because the console is
embedded HTML/CSS/JavaScript. These tests should protect the contracts that are
easy to regress:

- Option A shell classes and tokens exist.
- PointPatch top-bar action IDs and labels remain.
- no `modeSelect` or `modeNavigate` UI exists.
- preview refresh calls `renderPreviewOnly()`, not full `render()`.
- canvas badge and navigation/frozen mode behavior are represented.
- inspector exposes Draft/Composer modes.
- pending items show only `Focus` and `Delete`.
- session history avoids visible internal IDs.
- accessibility and shortcut guards are present.

Server/service tests protect behavior outside the HTML shell:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Final required checks:

```bash
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
git diff --check
```

## Manual Smoke Acceptance

Use the running console after `installDist` and validate the real UI:

1. Studio top bar and three-panel layout render.
2. `Refresh | Add | Save | Copy | Send | New | Close` are visible.
3. Idle preview click navigates the Android app.
4. Live preview refresh does not flicker Draft/evidence cards.
5. `Add` freezes the current preview and hides navigation controls.
6. Badge changes to `Frozen`.
7. Click target selection and drag area selection both work.
8. `Add to Pending` creates matching numbered row and overlay.
9. Multiple pending items share the frozen preview.
10. `Focus` highlights the matching overlay.
11. `Delete` removes one pending item and renumbers rows/overlays.
12. No pending edit/severity/status UI appears.
13. `Save` creates one evidence snapshot for all pending items.
14. Draft inspector shows expanded evidence card with screenshot, overlay, and
    comments.
15. A later `Add` on the same app screen still creates a new frozen work item
    and a new saved snapshot.
16. `Copy` copies compact agent Markdown.
17. `Send` creates a local handoff batch and moves draft items out of Draft.

Connected-device smoke should use the actual device when available:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH /Users/kws/Library/Android/sdk/platform-tools/adb devices -l
```

If no device is connected, or the device is offline/unauthorized, record the raw
output and skip only connected-device smoke.

## Task Mapping

| Plan Task | Implementation Detail Covered Here |
| --- | --- |
| Task 1 | DOM/test contract, forbidden controls, render isolation contract |
| Task 2 | shell layout, visual tokens, top-bar actions, DOM IDs |
| Task 3 | region rendering, stable image, preview polling flicker fix |
| Task 4 | canvas idle/frozen behavior, navigation, selection, overlays |
| Task 5 | Draft/Composer inspector split, pending restrictions, evidence cards |
| Task 6 | session history and sent drawer rendering |
| Task 7 | keyboard shortcuts and accessibility |
| Task 8 | targeted and broad verification |
| Task 9 | manual browser and connected-device smoke |
| Task 10 | docs update contract |

## Risks And Mitigations

`FeedbackConsoleAssets.kt` is a large embedded string.
: Keep changes grouped by CSS, markup, DOM references, region renderers, and
  event handlers. Prefer small commits at task boundaries.

String-contract tests can become brittle.
: Assert stable IDs, function names, and behavioral guards. Avoid testing exact
  large markup blocks unless the structure itself is the contract.

Preview flicker can reappear if full render is called from polling paths.
: Treat `refreshPreview()` as preview-only. Full `render()` is for session,
  device, save, send, and boundary changes.

Frozen preview can accidentally be replaced by a newer live preview.
: Preserve preview generation counters and store `addItemsFlow.screenshotUrl`
  using the preview-scoped screenshot route.

Prototype annotation editing can leak into PointPatch.
: Keep the explicit forbidden list in tests and manual smoke: no pending edit,
  severity, status, label, move, or resize.

Internal IDs can leak through history labels.
: Use human-readable labels and keep short IDs out of visible card text unless a
  future debug-only affordance is explicitly requested.

## Definition Of Done

- The console visually follows Option A Studio.
- No Select/Navigate toggle exists.
- Idle preview click still navigates.
- `Add` freezes the current preview and stops polling.
- Pending items support only `Focus` and `Delete`.
- Pending rows and overlays renumber together.
- `Save` persists one snapshot for all pending items in the frozen work set.
- Saved evidence groups show screenshot, numbered overlay, and comments.
- `Copy` and `Send` remain top-bar session actions.
- Live preview refresh updates only the preview region.
- All targeted and final verification commands pass, except connected-device
  smoke may be skipped only with raw `adb devices -l` output recorded.
