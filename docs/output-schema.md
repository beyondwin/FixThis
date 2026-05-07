# FixThis Output Schema

FixThis exports one annotation model for clipboard JSON, Markdown, CLI, and MCP. The current schema version is `1.0`.

Current MCP feedback-console sessions are the primary output surface. Legacy
single-annotation fields remain documented for compatibility because session
items map through the same core annotation model.

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

FixThis JSON is encoded with `explicitNulls=false` and `encodeDefaults=true`. Nullable optional fields are omitted when their value is null. Collections with default empty values are emitted as empty arrays.

These fields can be absent or empty depending on runtime context:

- `app.versionName`, `app.versionCode`
- `selection.selectedUid`
- `selection.areaBoundsInWindow`
- `selectedNode`
- `candidatesAtPoint`
- `scopeCandidates`
- `nearbyNodes`
- `sourceCandidates`
- `targetEvidence`
- `searchHints`
- `screenshot`

### `targetEvidence`

`targetEvidence` is optional additive evidence for agent handoff. In the current MCP console flow, it is generated when `Copy Prompt` or `Send Agent` persists written pending annotations and promotes a frozen preview into persisted feedback items. It may be absent when the captured screen, selected target, or source index does not provide enough structured evidence.

- `identityHint`: optional target identity derived from strict `comp:<ComposableName>:<variant>` test tags or stable semantics labels.
- `occurrence`: optional ordinal/count for the selected target, based on captured merged semantics nodes.
- `sourceInterpretation`: optional summary of the top source candidate, source-match reasons, and confidence caution.
- `evidenceQuality`: `BASIC` or `STRUCTURED`.
- `screenshotKinds`: screenshot artifact kinds available for the annotation, such as `full` and `crop`.
- `warnings`: human-readable caveats. Agents must treat these as confidence constraints.

## Feedback Session Schema

Feedback console sessions are returned by `fixthis_open_feedback_console` and served by the local console API. Top-level fields:

- `schemaVersion`: schema version string.
- `sessionId`: active feedback session id.
- `packageName`: Android application id.
- `projectRoot`: desktop project root.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: session timestamps.
- `screens`: persisted evidence snapshots saved from frozen previews.
- `items`: feedback queue items.
- `handoffBatches`: persisted sent handoff batches.
- `status`: `active`, `ready_for_agent`, or `closed`.

`FeedbackSession.screens` contains persisted evidence snapshots, not every preview frame.

## Feedback Session Summary

Feedback session summaries are returned by `fixthis_list_feedback_sessions` and the feedback session index. Fields:

- `sessionId`: persisted feedback session id.
- `packageName`: Android application id.
- `projectRoot`: desktop project root.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: session timestamps.
- `status`: `active`, `ready_for_agent`, or `closed`.
- `screensCount`: number of persisted evidence snapshots in the session.
- `itemsCount`: number of feedback items in the session.
- `unresolvedItemsCount`: number of feedback items not resolved or marked won't fix.
- `draftItemsCount`: number of feedback items whose delivery is `draft`.
- `sentBatchesCount`: number of persisted handoff batches.

`fixthis_list_feedback` returns the same session context plus `unresolvedSentItemsCount`, the number of sent feedback items not resolved or marked won't fix.

## Feedback Console Connection Status

Connection status is console-local API data returned by `GET /api/connection` and `POST /api/app/launch`. It is not persisted into `FeedbackSession` JSON.

Fields:

- `state`: `WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`, `CHOOSE_DEVICE`, `CHECK_PHONE`, or `UNSUPPORTED_BUILD`.
- `headline`: short user-facing state label.
- `message`: user-facing recovery guidance.
- `primaryAction`: optional next action. Values are `START`, `OPEN_APP`, `RECONNECT`, `TRY_AGAIN`, `CHOOSE_DEVICE`, or `CAPTURE`.
- `selectedDevice`: selected device summary when available.
- `devices`: available device summaries.
- `packageName`: Android application id for the console session.
- `canCapture`: whether live capture/preview actions are currently allowed.
- `canNavigate`: whether debug navigation actions are currently allowed.
- `canUseCachedWork`: whether the console can keep saved/draft work visible while disconnected.
- `details`: optional diagnostic object with `deviceState`, `bridgeState`, and `rawError`.

Device summaries include:

- `serial`: ADB serial.
- `state`: raw ADB state, such as `device`, `offline`, or `unauthorized`.
- `label`: short display label derived from model, device name, product, or serial.
- `selected`: whether this is the active console device.

`POST /api/app/launch` returns the same shape. It only attempts app launch from `WELCOME` or `OPEN_APP`; `CHECK_PHONE`, `CHOOSE_DEVICE`, and `UNSUPPORTED_BUILD` are returned without hiding their underlying cause.

## Captured Screen Schema

Captured screens represent persisted evidence snapshots in a feedback session. The feedback console creates them when `Copy Prompt` or `Send Agent` persists pending annotations and promotes a frozen preview; MCP tools can also create them through explicit capture or navigation with `captureAfter`. Live preview frames are not captured screens:

- `screenId`: persisted evidence snapshot id.
- `capturedAtEpochMillis`: capture timestamp.
- `activityName`: current Activity when available.
- `displayName`: console display label.
- `screenshot`: local screenshot artifact metadata when available.
- `roots`: Compose root snapshots with merged and unmerged nodes.
- `sourceIndexAvailable`: whether source matching data was available.
- `errors`: non-fatal capture or inspection errors.

## Feedback Navigation Result

Navigation results are returned by `fixthis_navigate_app`. Fields:

- `performed`: whether the sidekick performed the requested action.
- `action`: `back`, `tap`, or `swipe`.
- `activityName`: current Activity name when available.
- `message`: optional bridge status or failure message.
- `screen`: captured screen when `captureAfter` is true and capture succeeds.
- `captureError`: capture failure message when navigation performed but follow-up capture failed.

## Feedback Item Schema

Feedback items represent human comments on a persisted evidence snapshot. When a saved item targets a semantics node, `targetEvidence` is derived from that snapshot's captured merged semantics nodes and source-index candidates. Visual-area items keep occurrence unavailable and report that caveat in `targetEvidence.warnings`.

- `itemId`: feedback item id.
- `screenId`: evidence snapshot saved with this item batch. Multiple items can share one `screenId` when they were saved together from one frozen preview.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: item timestamps.
- `target`: selected `semantics_node` or `visual_area`.
- `selectedNode`: selected Compose node when available.
- `nearbyNodes`: nearby context nodes.
- `sourceCandidates`: best-effort source hints.
- `screenshotCrop`: crop artifact metadata when available.
- `comment`: human feedback text.
- `sequenceNumber`: stable human-readable item number within the session.
- `delivery`: `draft` before handoff or `sent` after a handoff batch records the item for agent reading.
- `handoffBatchId`: batch id that sent the item, present for sent items when available.
- `sentAtEpochMillis`: time the item was sent to a handoff batch, present for sent items.
- `status`: `open`, `ready`, `in_progress`, `resolved`, `needs_clarification`, or `wont_fix`.
- `agentSummary`: optional agent resolution summary.
- `targetEvidence`: optional additive evidence for stable agent handoff. When present, it follows the annotation `targetEvidence` shape above.

`ready` is retained for persisted/session JSON compatibility. Domain mappers normalize legacy `ready` values to `AnnotationStatus.OPEN`; this is not a JSON field migration.

## Feedback Delivery

The feedback console defaults to navigation. `Annotate` freezes the latest preview so the user can select a target or drag a visual area, write a comment, and create one or more pending UI-only items with `Add annotation`. Pending items are numbered in the Studio UI and support Focus and Delete until `Copy Prompt` or `Send Agent` persists written pending annotations when needed. That persistence promotes the frozen preview once into one persisted evidence snapshot, stores all pending items, and connects them to the same `screenId`. Later `Annotate` work on the same visible app screen creates a new evidence snapshot when pending annotations are persisted.

`Send Agent` creates a persisted handoff batch, changes saved items to `delivery: "sent"`, sets `handoffBatchId` and `sentAtEpochMillis`, and records those items in `handoffBatches`. It does not create a new external AI API payload; MCP tools read the persisted session data.

Connection loss does not change feedback delivery fields. Browser-only pending items and the last preview stay visible as cached work, the preview is marked stale, and new bridge actions resume only after the connection status returns to `READY`.

Delivery values:

- `draft`: item is still in the current draft queue.
- `sent`: item is part of a persisted handoff batch.

## Feedback Handoff Batch

Handoff batches are stored on the feedback session in `handoffBatches`:

- `batchId`: persisted batch id.
- `sequenceNumber`: stable human-readable batch number within the session.
- `createdAtEpochMillis`: time the batch was created.
- `itemIds`: feedback item ids included in the batch.
- `markdownSnapshot`: Markdown handoff snapshot captured when `Send Agent` created the batch, when available.

The item's `screenId` field points to the evidence snapshot saved with the item batch. Multiple items can share one `screenId` when saved together from one frozen preview.

## Feedback Handoff Formats

`fixthis_read_feedback` returns both JSON and Markdown. JSON remains complete and preserves session, screen, item, batch, screenshot, and path fields for MCP tool contracts. Markdown is compact and agent-facing: it focuses on request, target evidence, and likely source, and intentionally omits internal IDs and repeated storage metadata.

## Selection

`selection.kind` values:

- `SEMANTICS_NODE`: a Compose semantics node was selected.
- `VISUAL_AREA`: the user selected a rectangle or FixThis used an area fallback.
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

Generated source-index entries now include additive v2 typed `signals` while preserving the v1 fields (`symbols`, `text`, `contentDescriptions`, `testTags`, `stringResources`, `roles`, and `activityNames`). Current signal kinds are `COMPOSABLE_SYMBOL`, `UI_TEXT`, `STRING_RESOURCE`, `TEST_TAG`, `STRICT_COMP_TEST_TAG`, `CONTENT_DESCRIPTION`, `ROLE`, `ACTIVITY_NAME`, and `ARBITRARY_STRING_LITERAL`; each signal has a `value` and optional `confidenceWeight` defaulting to `1.0`. Readers that do not understand `signals` can continue using v1 fields, and FixThis matching falls back to those v1 fields when no typed signal matches.

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
context.cacheDir/fixthis/<yyyy-MM-dd>/<annotation-id>-full.png
context.cacheDir/fixthis/<yyyy-MM-dd>/<annotation-id>-crop.png
```

App-only clipboard exports may include Android-local `fullPath` and `cropPath`. Desktop agents cannot usually read those paths directly.

CLI and MCP flows pull screenshots through the bridge and write desktop-readable artifacts. Annotation captures use the annotation id in the legacy artifact cache:

```text
.fixthis/artifacts/<annotation-id>/<annotation-id>-full.png
.fixthis/artifacts/<annotation-id>/<annotation-id>-crop.png
```

Feedback console evidence snapshots are session-owned workspace artifacts and use a generated screen id:

```text
.fixthis/feedback-sessions/<session-id>/artifacts/screens/<screen-id>/<screen-id>-full.png
```

When available, annotation paths appear as `desktopFullPath` and `desktopCropPath`. Feedback console screen paths appear on the screen entry. `.fixthis/artifacts/` and `.fixthis/feedback-sessions/` are ignored by git because these files are local debug screenshots and session metadata. If capture or storage fails, `screenshot.captureFailedReason` or `captureError` records the failure and the annotation or navigation result remains valid.

## Error Codes

Errors are structured as:

```json
{
  "code": "NO_NODE_AT_TAP",
  "message": "No semantics node contains the tap point",
  "details": {}
}
```

Annotation and inspection codes include current bridge/inspection failures plus
legacy single-annotation capture failures:

- `NO_NODE_AT_TAP`: no semantics node contained the tap point.
- `SCOPE_NODE_NOT_FOUND`: requested scope chip node was not found.
- `ROOT_DISCOVERY_FAILED`: Compose root discovery threw an error.
- `SEMANTICS_MERGED_INSPECTION_FAILED`: merged tree inspection failed.
- `SEMANTICS_UNMERGED_INSPECTION_FAILED`: unmerged tree inspection failed.
- `NO_ACTIVITY`: no resumed Activity is available.
- `NO_DECOR_VIEW`: current Activity has no decor view.
- `NO_OVERLAY_CONTROLLER`: legacy overlay controller is unavailable.
- `CAPTURE_IN_FLIGHT`: legacy feedback capture is already active.

Bridge and MCP failures may be returned as tool errors or JSON-RPC errors instead of annotation `errors`. See [Troubleshooting](troubleshooting.md).
