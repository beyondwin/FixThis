# Feedback Console Option A Parity Redesign Design

Date: 2026-05-06

## Purpose

This design supersedes the earlier Feedback Console V1.2 implementation
direction where it conflicts with the executable Option A prototype. The next
console iteration should follow Option A's annotation workspace first, then
adapt PointPatch's local Android data sources to that interaction model.

The reference prototype is:

```text
/Users/kws/Downloads/PointPatch Console _standalone_.html
```

The target is not another incremental polish pass on the current console. It is
an Option A parity pass: history cards, Select/Annotate tools, annotation pins,
annotation detail editing, counts, and click behavior should work like the
prototype unless this document explicitly removes a prototype control.

## Precedence Decision

Option A behavior now wins over the prior PointPatch console contract.

This means the following previous restrictions are superseded for this redesign:

- Pending items are no longer limited to `Focus` and `Delete`.
- The right panel is no longer a `Composer`/`Draft` split.
- Saved feedback is no longer modeled primarily as a batched pending list.
- Annotation labels, severity, comments, and status are editable in the UI.
- Preview interaction is annotation-first when the user is in the Studio
  workspace; visible one-step navigation controls are removed from this UI.

PointPatch still provides the live Android screenshot, Compose node metadata,
local persistence, and local handoff/export plumbing. Those implementation
details should serve the Option A workspace rather than dictate the visible UI.

## Required Deviations From The Prototype

The prototype is the behavioral source, with these explicit visual deviations:

- Remove the top-right prototype action buttons `+ New session` and
  `Save snapshot`.
- Remove the visible navigation cluster `Back`, `Up`, `Down`, `Left`, `Right`.
- Do not replace those controls with another old-style top-bar action rail.
- Keep the canvas toolbar focused on `Select`, `Annotate`, annotation stats,
  and zoom/status treatment.

The save/session lifecycle must therefore become native to the annotation
workspace. Creating, editing, deleting, and marking annotations should persist
through the annotation APIs rather than requiring a visible `Save snapshot`
button.

## Target Layout

Use the Option A dark Studio shell:

```text
56px top bar
  left: PointPatch Studio brand
  middle: project/session/device context styled as an Option A breadcrumb
  right: no New Session / Save Snapshot action group

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

`Select` mode:

- Clicking an existing annotation pin selects it.
- Clicking an annotation row in the inspector selects the same pin.
- Clicking empty preview space clears the selection.
- The right inspector title is `Annotations` when no annotation is selected and
  `Annotation` when a detail is open.

`Annotate` mode:

- The toolbar shows the Option A hint treatment: "Click a widget - or drag to
  draw a region".
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

No selected annotation:

- Header: `Annotations`
- Count: total annotations for the active snapshot/session
- Body: annotation rows with number, label, comment preview, and status pill
- Empty state: Option A "No annotations yet" style with a primary
  `Start annotating` action that switches to `Annotate`

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

- Rect bounds align to the selected Compose node or dragged region.
- Pin number appears in a circular tag at the top-left of the rectangle.
- Pin color derives from severity:
  - `high`: `#F26D6D`
  - `med`: `#E6B45A`
  - `low`: `#5AB1E6`
- Selected pins get the Option A stronger border and glow treatment.
- Resolved annotations remain visible but use reduced opacity.

Rows and pins use the same ordered annotation array, so deleting an annotation
renumbers both immediately with no gaps.

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

PointPatch can map these to existing session concepts internally, but the UI
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

## Copy And Send Behavior

Option A parity changes the handoff source from "pending items waiting for Save"
to "current persisted annotations". Copy/Send output should include the same
target evidence as before, but now include:

- annotation number
- label
- severity
- status
- comment
- target evidence and likely source hints

Resolved annotations may remain visible in history and Copy/Send context, but
the implementation plan should define whether Send includes all annotations or
only non-resolved annotations. The UI must still display all statuses.

## Removed UI

The following visible controls should not appear in the final parity UI:

```text
New Session
Save snapshot
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
- The old navigation button labels are absent from the visible canvas toolbar.
- The old `Composer` title is absent.
- `Annotations` and `Annotation` inspector states exist.
- `Severity`, `high`, `med`, `low` exist.
- `Status`, `open`, `in-progress`, `resolved` exist.
- `Delete` and `Done` exist in selected annotation detail.
- History card click handlers open the selected history item.
- Annotation row click and pin click both select the same annotation.
- Open/resolved counts are rendered from annotation status.

Service and persistence tests should assert:

- severity persists and reloads
- status updates persist and reload
- label/comment edits persist and reload
- delete removes only one annotation and renumbers UI order
- Copy/Send serialization includes label, severity, status, comment, and target
  evidence

Manual browser smoke should verify:

- left history height remains stable and scrolls internally
- center toolbar height does not jump between Select and Annotate
- right annotation list/detail panel scrolls internally
- clicking a history card loads its annotations
- clicking a row selects the preview pin
- clicking a preview pin opens the same detail
- changing severity recolors the pin and row strip
- changing status updates `open/resolved` counts

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
- old visible navigation buttons and composer/draft workflow are gone
- history item clicks, annotation row clicks, and preview pin clicks reproduce
  the prototype behavior
