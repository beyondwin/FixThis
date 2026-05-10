# Feedback Console Option A Parity Redesign Design

Date: 2026-05-06

## Purpose

This design supersedes the earlier Feedback Console V1.2 implementation
direction where it conflicts with the executable Option A prototype. The next
console iteration should follow Option A's annotation workspace first, then
adapt FixThis's local Android data sources to that interaction model.

The reference prototype is:

```text
/Users/kws/Downloads/FixThis Console _standalone_.html
```

The target is not another incremental polish pass on the current console. It is
an Option A parity pass: history cards, Select/Annotate tools, annotation pins,
annotation detail editing, counts, and click behavior should work like the
prototype unless this document explicitly removes a prototype control.

## Precedence Decision

Option A behavior now wins over the prior FixThis console contract.

This means the following previous restrictions are superseded for this redesign:

- Pending items are no longer limited to `Focus` and `Delete`.
- The right panel is no longer a `Composer`/`Draft` split.
- Saved feedback is no longer modeled primarily as a batched pending list.
- Annotation labels, severity, comments, and status are editable in the UI.
- Preview interaction is annotation-first when the user is in the Studio
  workspace; visible one-step navigation controls are removed from this UI.

FixThis still provides the live Android screenshot, Compose node metadata,
local persistence, and local handoff/export plumbing. Those implementation
details should serve the Option A workspace rather than dictate the visible UI.

## Required Deviations From The Prototype

The prototype is the behavioral source, with these explicit visual deviations:

- Remove the top-right prototype action button `+ New session`.
- Keep the Option A primary `Save snapshot` action, but change its FixThis
  behavior: clicking it sends the currently registered annotations to the AI
  agent handoff path.
- Remove the visible navigation cluster `Back`, `Up`, `Down`, `Left`, `Right`.
- Do not replace those controls with another old-style top-bar action rail.
- Keep the canvas toolbar focused on `Select`, `Annotate`, annotation stats,
  and zoom/status treatment.

Creating, editing, deleting, and marking annotations should persist through the
annotation APIs. `Save snapshot` is not a local draft-save button; it is the
Option A primary handoff action for sending the registered annotations to the AI
agent.

## Target Layout

Use the Option A dark Studio shell:

```text
56px top bar
  left: FixThis Studio brand
  middle: project/session/device context styled as an Option A breadcrumb
  right: Save snapshot primary action, with no New Session action

body
  left: History panel
  center: Canvas panel
  right: Annotation inspector
```

Stable dimensions and overflow rules are required because the current console
has height problems around `Select`, `Annotate`, and `Sessions`:

- The shell owns `height: 100vh` and `overflow: hidden`.
- The body uses `min-height: 0`.
- The left history, center canvas, and right inspector each use `min-height: 0`.
- The history list and annotation list scroll inside their panel, not the page.
- `Select`/`Annotate` tools must not resize the canvas toolbar when active,
  hovered, or focused.

## Canvas Toolbar

The center toolbar should match Option A's structure:

```text
[ Select ] [ Annotate ]        status/counts        zoom/status controls
```

Tool switching UI/UX:

- `Select` and `Annotate` render as the Option A segmented tool group, not as
  the old Select/Navigate toggle.
- The active tool gets the Option A active style: darker raised segment,
  accent-colored text/icon, and stable dimensions.
- Hover/focus changes color only; it must not resize the toolbar or push the
  status/zoom controls.
- Clicking `Select` sets `tool = select`, clears any in-progress drag rectangle,
  and keeps the currently selected annotation selected unless the user clicks
  empty preview space.
- Clicking `Annotate` sets `tool = annotate`, clears the current selected
  annotation, and shows the annotate hint in the toolbar.
- Tool state changes are immediate and local to the canvas/inspector; they do
  not create, save, send, or delete annotations by themselves.
- `Start annotating` in the empty annotation state is equivalent to clicking
  `Annotate`.

`Select` mode:

- The toolbar status shows annotation stats such as open/resolved counts rather
  than the annotate instruction pill.
- The preview cursor/interaction should feel inspectable: pins are clickable,
  empty space clears selection, and no drag rectangle is started.
- Clicking an existing annotation pin selects it.
- Clicking an annotation row in the inspector selects the same pin.
- Clicking empty preview space clears the selection.
- The right inspector title is `Annotations` when no annotation is selected and
  `Annotation` when a detail is open.

`Annotate` mode:

- The toolbar shows the Option A hint treatment: "Click a widget - or drag to
  draw a region".
- The preview cursor/interaction should feel drawable: pointer down starts a
  possible annotation target, pointer move shows the drag rectangle, and pointer
  up creates either a widget-snapped annotation or custom region.
- Clicking a Compose-backed widget creates an annotation snapped to that node's
  bounds.
- Dragging creates a custom region annotation.
- A new annotation defaults to `severity = med` and `status = open`.
- After creation, the UI selects the new annotation and returns to `Select`.

The visible Android navigation buttons are removed from this surface. If Android
navigation remains needed for operator workflows, it must be handled by a later
Option A-native affordance or by existing non-visual tooling, not by restoring
the old `Back/Up/Down/Left/Right` strip.

## History Panel

The left panel is Option A history, not a basic session picker.

History UI must follow the prototype's card anatomy:

```text
panel-head
  panel-title = History
  panel-count = snapshots.length

history-list
  history-item
    hi-head
      hi-title
      optional hi-del
    hi-meta
    hi-stats
      N open
      N resolved
      N pts
    hi-strip
      hi-strip-cell per annotation
```

Required visual behavior:

- The history panel uses the same dark `studio-history` surface, fixed panel
  width, internal scrolling, and no page-level overflow.
- `history-list` is vertically scrollable with compact gaps like the prototype.
- `history-item` uses rounded 8px rows with transparent default border.
- Hover changes the card background to the Option A secondary surface.
- The active item uses `is-active` treatment: secondary background, border, and
  the left accent inset.
- `hi-title` is the primary label, `hi-meta` is muted timestamp/author or
  session metadata, and internal IDs must not be visible in normal labels.
- `hi-stats` renders compact pips for open, resolved, and total point counts.
- `hi-strip` renders one severity-colored segment per annotation; resolved
  segments remain visible but lower opacity.
- If `cardStyle = minimal` is implemented, it may hide stats/strip only when the
  product deliberately exposes a density/card-style setting. The default target
  is the rich Option A card.

Each history item should behave like the prototype card:

- Click opens that snapshot/session and makes it active.
- Active history item gets the Option A active treatment.
- Rich card mode shows counts such as `2 open`, `1 resolved`, and total points.
- The annotation color strip reflects annotation severity and resolved opacity.
- If a delete control is shown on a history card, clicking it must not also open
  the card.

The panel title can remain `History` or `Sessions` only if the final UI still
looks and behaves like Option A. The user-facing behavior is more important than
the exact label.

## Annotation Inspector

The right panel replaces the current composer with Option A annotation UI.

Annotations UI must follow the prototype's inspector anatomy:

```text
studio-inspector
  panel-head
    panel-title = Annotations | Annotation
    panel-count = annotations.length

  ann-list
    empty
      empty-mark
      empty-title
      empty-body
      Start annotating
    ann-row
      ann-row-num
      ann-row-body
        ann-row-title
        ann-row-comment
      ann-row-status

  ann-detail
    back-btn
    field Label
    field Severity segmented control
    field Comment textarea
    field Status segmented control
    detail-actions Delete / Done
```

Required list-state visual behavior:

- `studio-inspector` uses the same dark right-panel surface, left border,
  internal scrolling, and no nested card shell.
- `ann-list` fills the remaining panel height and scrolls internally.
- Empty state is vertically centered within the annotation list area, not
  pushed below the fold.
- `empty-mark`, `empty-title`, `empty-body`, and primary mini button match the
  Option A empty-state hierarchy.
- `ann-row` uses the prototype grid: number badge, text body, status pill.
- `ann-row` hover uses the secondary surface and does not shift layout.
- `ann-row-num` uses the annotation severity color.
- `ann-row-comment` clamps visually like the prototype and shows muted
  `No comment` when comment is empty.
- `ann-row-status` uses status-specific pill styling for `open`,
  `in-progress`, and `resolved`.

Required detail-state visual behavior:

- `ann-detail` replaces the list area and scrolls internally.
- The back affordance uses the Option A muted text button style.
- `Label` input, `Comment` textarea, and segmented controls use the Option A
  field spacing, labels, dark inputs, and accent focus borders.
- `Severity` and `Status` segmented controls must keep stable height and width
  between states.
- Active severity segment uses the severity color as its fill.
- `Delete` uses the Option A danger treatment; `Done` uses the ghost treatment.
- Detail actions stay below the fields with the top divider treatment.

No selected annotation:

- Header: `Annotations`
- Count: total annotations for the active snapshot/session
- Body: annotation rows with number, label, comment preview, and status pill
- Empty state uses the exact Option A copy and action:

```text
No annotations yet
Switch to Annotate, then click a widget or drag a region on the preview.
Start annotating
```

The `Start annotating` action switches the canvas tool to `Annotate`.

Selected annotation:

- Header: `Annotation`
- Detail body contains:
  - back affordance to all annotations
  - `Label` text input
  - `Severity` segmented control: `high`, `med`, `low`
  - `Comment` textarea
  - `Status` segmented control: `open`, `in-progress`, `resolved`
  - `Delete`
  - `Done`

Click behavior must match Option A:

- Clicking a row opens the same annotation detail as clicking its preview pin.
- `Done` closes detail and returns to the annotation list.
- `Delete` removes the selected annotation, closes detail, updates counts, and
  renumbers pins and rows.
- Editing label, severity, comment, or status updates the selected annotation
  and the corresponding row/pin without requiring a separate visible snapshot
  save button.

## Annotation Pin And Selection Behavior

Pins use Option A rectangular overlays:

- Every registered annotation renders immediately on the preview as an Option A
  pin rectangle.
- Rect bounds align to the selected Compose node or dragged region.
- Pin number appears in a circular tag at the top-left of the rectangle.
- Pin numbers are the 1-based order of the current annotation array, so the
  preview shows `1`, `2`, `3`, and so on exactly like the sample prototype.
- The number shown on the preview pin must always match the annotation row
  number in the right panel.
- Pin color derives from severity:
  - `high`: `#F26D6D`
  - `med`: `#E6B45A`
  - `low`: `#5AB1E6`
- The rectangle border, translucent fill, and number tag all use the same
  severity color via the Option A `--pin-color` treatment.
- Selected pins get the Option A stronger border and glow treatment.
- Resolved annotations remain visible but use reduced opacity.
- Multiple annotations can coexist on the same preview; each remains visible as
  its own numbered, severity-colored rectangle.
- Creating the first, second, and third annotations should visibly produce the
  same UI pattern as the prototype sample: three numbered overlays with colors
  determined independently by each annotation's severity.

Rows and pins use the same ordered annotation array, so deleting an annotation
renumbers both immediately with no gaps.

## Option A Event Contract

This section is the implementation-level interaction contract extracted from
the Option A prototype. The implementation should preserve these event outcomes
even if FixThis uses different internal API names.

### History Events

The history panel state is driven by the active snapshot/session and its
annotation set:

```text
panel count = snapshots.length
active card = activeSnapshotId === card.id
card open count = annotations where status === open
card resolved count = annotations where status === resolved
card total points = annotations.length
```

History item click:

- User clicks a history card.
- UI sets that card as the active snapshot/session.
- The canvas reloads the selected snapshot screenshot.
- The annotation array is replaced with a copy of that history item's
  annotations.
- The title/breadcrumb context is updated from the selected history item.
- `selectedAnnotationId` is cleared, so the inspector returns to the
  `Annotations` list state.

History delete click, if the implementation keeps the Option A card delete
affordance:

- User clicks the delete control inside a history card.
- The event must stop propagation and must not also open that card.
- The target card is removed from the history list.
- If the deleted card was active, activate the next available card.
- If no card remains, clear the active snapshot/session and show the empty
  annotation state.

History card rendering:

- Active card uses the Option A `is-active` treatment.
- Rich card stats show `N open`, `N resolved`, and total points.
- The severity strip renders one segment per annotation, using severity color.
- Resolved annotation segments stay visible with reduced opacity.

### Annotation List And Detail Events

Empty list state:

- If the active snapshot/session has zero annotations and no annotation is
  selected, the right panel renders the exact empty state copy:

```text
No annotations yet
Switch to Annotate, then click a widget or drag a region on the preview.
Start annotating
```

- Clicking `Start annotating` sets `tool = annotate`.
- The canvas toolbar immediately shows the annotate hint state.

Annotation row click:

- User clicks a row in the `Annotations` list.
- UI sets `selectedAnnotationId` to that annotation id.
- The matching preview pin receives the selected visual treatment.
- The inspector switches from list mode to detail mode with title
  `Annotation`.

Annotation detail back / Done:

- The back affordance and `Done` both clear `selectedAnnotationId`.
- The inspector returns to the `Annotations` list state.
- The annotation remains in the list with any edited fields applied.

Field edit events:

- `Label` input updates the selected annotation label immediately.
- `Severity` segmented control updates severity immediately and recolors:
  - the detail selected value
  - the annotation row number badge
  - the preview pin rectangle/tag
  - the history severity strip
- `Comment` textarea updates the selected annotation comment immediately.
- `Status` segmented control updates status immediately and updates:
  - the row status pill
  - the open/resolved counts in the canvas toolbar
  - the open/resolved counts in the active history card
  - resolved opacity on row/pin/strip where applicable

Delete selected annotation:

- User clicks `Delete` in annotation detail.
- The selected annotation is removed.
- `selectedAnnotationId` is cleared.
- Rows and pins renumber from the remaining ordered annotation array.
- Counts and history card stats update immediately.

### Preview Events

Preview pin click in Select mode:

- User clicks an annotation rectangle or number tag.
- The pin click stops propagation, so the canvas empty-space handler does not
  clear selection.
- UI sets `selectedAnnotationId` to that annotation id.
- The inspector opens the same detail view as annotation row click.

Preview empty click in Select mode:

- User clicks the preview outside an annotation pin.
- UI clears `selectedAnnotationId`.
- The inspector returns to the `Annotations` list state.

Preview pointer down in Annotate mode:

- If the current tool is not `Annotate`, ignore the pointer down for annotation
  creation.
- In `Annotate`, record the pointer's preview-relative start position.
- Clear the current selected annotation.
- Initialize a zero-size drag rectangle.

Preview pointer move in Annotate mode:

- If no drag start exists, ignore the move.
- Convert pointer position to preview-relative coordinates.
- Mark the gesture as moved after the Option A threshold is crossed.
- Render a live drag rectangle from the normalized start/end positions.

Preview pointer up in Annotate mode:

- If the gesture moved enough, create a region annotation from the drag
  rectangle and label it `Region N`.
- If the gesture did not move enough, resolve the clicked widget target and snap
  the annotation to that widget's bounds.
- The new annotation defaults to:

```text
severity = med
status = open
comment = empty
```

- The new annotation is appended to the ordered annotations array.
- UI selects the new annotation.
- The preview immediately renders the new numbered overlay:
  - first annotation renders tag `1`
  - second annotation renders tag `2`
  - third annotation renders tag `3`
  - each tag and rectangle uses that annotation's current severity color
- Tool returns to `Select`.
- Drag state is cleared.

Widget click targeting:

- Option A targets the closest widget metadata marker under the pointer.
- FixThis should adapt this to Compose semantics nodes: select the smallest
  containing semantics node when available, and use dragged region selection
  when no node is found or when the user drags.

Save snapshot click:

- The visible action remains `Save snapshot`.
- Unlike the prototype's local-only snapshot cloning, FixThis maps this
  action to agent handoff: it sends the active snapshot/session's currently
  registered annotations to the persisted local handoff path.
- The action is disabled when there are no registered annotations.

## State Model

The browser state should follow the prototype's concepts:

```javascript
snapshots
activeSnapshotId
annotations
selectedAnnotationId
draggingRect
tool // select | annotate
```

FixThis can map these to existing session concepts internally, but the UI
state should not be shaped around `pendingFeedbackItems` as the primary user
model. If existing names remain during implementation, they should be internal
compatibility details only.

## Persistence And API Implications

The existing model already has `FeedbackItemStatus.OPEN`,
`FeedbackItemStatus.IN_PROGRESS`, and `FeedbackItemStatus.RESOLVED`. The
redesign should expose those through the annotation `Status` segmented control.

Severity is a new persisted annotation field. Add a small enum with the Option A
values:

```text
high
med
low
```

Annotation data must persist at least:

```text
id
snapshot/screen id
target type
node uid when available
bounds
label
severity
comment
status
sequence/order
created/updated timestamps
```

The implementation can extend `FeedbackItem` or introduce a
`FeedbackAnnotation` wrapper, but the persisted JSON and MCP export path must
include the new label/severity/status/comment values so history reopen, Copy,
and Send do not lose Option A annotation state.

Required API capabilities:

- create annotation from node click or dragged region
- update annotation label/severity/comment/status
- delete annotation
- list annotations for the active history item
- open a history item and restore its annotations

The old batch-only `Save` flow can remain as a compatibility layer only if the
visible UX still behaves like immediate Option A annotations.

## Save Snapshot And Agent Handoff Behavior

Option A parity changes the handoff source from "pending items waiting for Save"
to "current registered annotations".

`Save snapshot` is the primary user action for handing registered annotations to
the AI agent. In FixThis terms, it creates the persisted local handoff that
the agent/tooling can read. It must not call an external AI API unless a later
design explicitly adds that integration.

The handoff output should include the same target evidence as before, but now
include:

- annotation number
- label
- severity
- status
- comment
- target evidence and likely source hints

Resolved annotations remain visible in history. `Save snapshot` should send the
currently registered annotation set for the active snapshot/session unless the
implementation plan explicitly narrows the send set. The UI must still display
all statuses.

If `Copy` or `Send` compatibility controls remain internally, they should route
through the same annotation handoff source. The visible Option A primary action
is `Save snapshot`.

## Removed UI

The following visible controls should not appear in the final parity UI:

```text
New Session
Back
Up
Down
Left
Right
Composer
Draft-as-primary-inspector-mode
Focus-only pending list
Delete-only pending list
```

The final UI may still have internal functions for session creation, refresh,
export, or bridge navigation, but those functions must not recreate the old
visible workflow unless a later design explicitly adds an Option A-native
control.

## Test Expectations

HTML/JS contract tests should assert:

- Option A shell classes and tokens remain present.
- `Select` and `Annotate` tools render in the canvas toolbar.
- the exact empty annotation copy appears: `No annotations yet`, `Switch to
  Annotate, then click a widget or drag a region on the preview.`, and
  `Start annotating`
- `Save snapshot` is present as the primary agent handoff action.
- `New Session` is absent.
- The old navigation button labels are absent from the visible canvas toolbar.
- The old `Composer` title is absent.
- `Annotations` and `Annotation` inspector states exist.
- `Severity`, `high`, `med`, `low` exist.
- `Status`, `open`, `in-progress`, `resolved` exist.
- `Delete` and `Done` exist in selected annotation detail.
- History card click handlers open the selected history item.
- Annotation row click and pin click both select the same annotation.
- Preview annotations render as Option A pin rectangles with number tags.
- Preview pin labels use 1-based annotation order: `1`, `2`, `3`.
- Preview pin and annotation row numbers stay in sync after create/delete.
- Preview pin border/fill/tag colors derive from severity: high red, med
  yellow, low blue.
- Open/resolved counts are rendered from annotation status.

Service and persistence tests should assert:

- severity persists and reloads
- status updates persist and reload
- label/comment edits persist and reload
- delete removes only one annotation and renumbers UI order
- `Save snapshot` creates the AI agent handoff from registered annotations
- handoff serialization includes label, severity, status, comment, and target
  evidence

Manual browser smoke should verify:

- left history height remains stable and scrolls internally
- center toolbar height does not jump between Select and Annotate
- right annotation list/detail panel scrolls internally
- creating three annotations through `Annotate` shows `1`, `2`, and `3` on the
  preview, with each overlay colored by severity like the opened prototype
- clicking a history card loads its annotations
- clicking a row selects the preview pin
- clicking a preview pin opens the same detail
- changing severity recolors the pin and row strip
- changing status updates `open/resolved` counts
- clicking `Save snapshot` produces an agent-readable handoff from the current
  annotations

## Risks

This is a breaking redesign relative to the V1.2 batched-pending contract. The
largest implementation risk is trying to keep both workflows visible. The next
implementation plan should choose the Option A annotation workflow as the single
visible model and move any old behavior behind compatibility APIs or remove it
from the console surface.

The second risk is treating severity/status as UI-only state. That would break
history reopen, Copy, and Send, so severity and editable status must be
persistent.

## Acceptance Criteria

The design is implemented when:

- the console visually matches Option A's dark Studio workspace, excluding the
  explicitly removed top-right prototype actions
- `Select`/`Annotate` interaction matches the prototype
- annotation list, annotation detail, preview pins, and history cards stay in
  sync
- severity and status controls are editable and persistent
- open/resolved counts update from annotation state
- `Save snapshot` sends the registered annotations through the AI agent handoff
  path
- old visible navigation buttons and composer/draft workflow are gone
- history item clicks, annotation row clicks, and preview pin clicks reproduce
  the prototype behavior
