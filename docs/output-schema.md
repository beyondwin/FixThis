# PointPatch Output Schema

PointPatch exports one annotation model for clipboard JSON, Markdown, CLI, and MCP. The current schema version is `1.0`.

## Required Fields

These fields are always present in an annotation:

- `schemaVersion`: schema version string.
- `id`: annotation id.
- `createdAtEpochMillis`: capture time.
- `platform`: currently `android-compose`.
- `app.packageName`: Android application id.
- `app.debuggable`: whether the app is debuggable.
- `activity.className`: current Activity class.
- `tap.xInWindow` and `tap.yInWindow`: tap coordinate in window pixels.
- `selection.kind`: selection result kind.
- `selection.confidence`: selection confidence.
- `selection.source`: selection source.
- `userComment`: user-entered request text.
- `errors`: list of structured errors. Empty means no known error.

## Optional Fields

PointPatch JSON is encoded with `explicitNulls=false` and `encodeDefaults=true`. Nullable optional fields are omitted when their value is null. Collections with default empty values are emitted as empty arrays.

These fields can be absent or empty depending on runtime context:

- `app.versionName`, `app.versionCode`
- `selection.selectedUid`
- `selection.areaBoundsInWindow`
- `selectedNode`
- `candidatesAtPoint`
- `scopeCandidates`
- `nearbyNodes`
- `sourceCandidates`
- `searchHints`
- `screenshot`

## Feedback Session Schema

Feedback console sessions are returned by `pointpatch_open_feedback_console` and served by the local console API. Top-level fields:

- `schemaVersion`: schema version string.
- `sessionId`: active feedback session id.
- `packageName`: Android application id.
- `projectRoot`: desktop project root.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: session timestamps.
- `screens`: captured screen entries.
- `items`: feedback queue items.
- `status`: `active`, `ready_for_agent`, or `closed`.

## Captured Screen Schema

Captured screens represent one Android screen snapshot in a feedback session:

- `screenId`: captured screen id.
- `capturedAtEpochMillis`: capture timestamp.
- `activityName`: current Activity when available.
- `displayName`: console display label.
- `screenshot`: local screenshot artifact metadata when available.
- `roots`: Compose root snapshots with merged and unmerged nodes.
- `sourceIndexAvailable`: whether source matching data was available.
- `errors`: non-fatal capture or inspection errors.

## Feedback Item Schema

Feedback items represent human comments on a captured screen:

- `itemId`: feedback item id.
- `screenId`: captured screen this item belongs to.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: item timestamps.
- `target`: selected `semantics_node` or `visual_area`.
- `selectedNode`: selected Compose node when available.
- `nearbyNodes`: nearby context nodes.
- `sourceCandidates`: best-effort source hints.
- `screenshotCrop`: crop artifact metadata when available.
- `comment`: human feedback text.
- `status`: `open`, `ready`, `in_progress`, `resolved`, `needs_clarification`, or `wont_fix`.
- `agentSummary`: optional agent resolution summary.

## Selection

`selection.kind` values:

- `SEMANTICS_NODE`: a Compose semantics node was selected.
- `VISUAL_AREA`: the user selected a rectangle or PointPatch used an area fallback.
- `TAP_POINT`: no node or area was selected; the tap coordinate is the primary signal.

`selection.confidence` values:

- `HIGH`: strong semantic match, usually direct tap or scope-chip selection.
- `MEDIUM`: usable match with weaker evidence, often area selection with nearby context.
- `LOW`: weak semantic evidence.
- `NONE`: no semantic target.

`selection.source` values:

- `TAP_SELECT`
- `SCOPE_CHIP`
- `AREA_SELECT`
- `FALLBACK`

## Source Candidates

`sourceCandidates` are best-effort ranked hints, not guaranteed file/line mappings. Each candidate can include:

- `file`: source file candidate.
- `line`: optional line from the source index.
- `score`: normalized score from `0.0` to `1.0`.
- `matchedTerms`: selected or nearby terms found in the source index.
- `matchReasons`: why the entry matched.
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, or `NONE`.

Implemented match reasons include:

- `selected text`
- `selected contentDescription`
- `selected testTag`
- `selected role`
- `nearby text`
- `nearby contentDescription`
- `nearby testTag`
- `nearby role`
- `activity`

## Screenshot Paths

The Android sidekick stores screenshots under the app cache directory:

```text
context.cacheDir/pointpatch/<yyyy-MM-dd>/<annotation-id>-full.png
context.cacheDir/pointpatch/<yyyy-MM-dd>/<annotation-id>-crop.png
```

App-only clipboard exports may include Android-local `fullPath` and `cropPath`. Desktop agents cannot usually read those paths directly.

CLI and MCP flows pull screenshots through the bridge and write desktop-readable artifacts. Annotation captures use the annotation id, while console screen snapshots use a generated screen id:

```text
.pointpatch/artifacts/<annotation-id>/<annotation-id>-full.png
.pointpatch/artifacts/<annotation-id>/<annotation-id>-crop.png
.pointpatch/artifacts/<screen-id>/<screen-id>-full.png
```

When available, those paths appear as `desktopFullPath` and `desktopCropPath`. `.pointpatch/artifacts/` is ignored by git because these files are local debug screenshots. If capture or storage fails, `screenshot.captureFailedReason` records the failure and the annotation remains valid.

## Error Codes

Errors are structured as:

```json
{
  "code": "NO_NODE_AT_TAP",
  "message": "No semantics node contains the tap point",
  "details": {}
}
```

Annotation and inspection codes currently include:

- `NO_NODE_AT_TAP`: no semantics node contained the tap point.
- `SCOPE_NODE_NOT_FOUND`: requested scope chip node was not found.
- `ROOT_DISCOVERY_FAILED`: Compose root discovery threw an error.
- `SEMANTICS_MERGED_INSPECTION_FAILED`: merged tree inspection failed.
- `SEMANTICS_UNMERGED_INSPECTION_FAILED`: unmerged tree inspection failed.
- `NO_ACTIVITY`: no resumed Activity is available.
- `NO_DECOR_VIEW`: current Activity has no decor view.
- `NO_OVERLAY_CONTROLLER`: overlay controller is unavailable.
- `CAPTURE_IN_FLIGHT`: another feedback capture is already active.

Bridge and MCP failures may be returned as tool errors or JSON-RPC errors instead of annotation `errors`. See [Troubleshooting](troubleshooting.md).
