# FixThis Output Schema

One annotation model for clipboard JSON, Markdown, CLI, and MCP.
`schemaVersion` is `1.0`.

MCP feedback-console sessions are the primary surface. Legacy
single-annotation fields remain because session items map through the same
core model.

JSON encoding: `explicitNulls=false`, `encodeDefaults=true`. Null optionals
are omitted. Empty collections are emitted as `[]`.

Protected field names: `items`, `screens`, `itemId`, `screenId`,
`targetEvidence`, `targetReliability`, `sourceCandidates`.

## Required Fields

Always present on an annotation:

- `schemaVersion`, `id`, `createdAtEpochMillis`
- `platform` (`android-compose`)
- `app.packageName`, `app.debuggable`
- `activity.className`
- `tap.xInWindow`, `tap.yInWindow`
- `selection.kind`, `selection.confidence`, `selection.source`
- `userComment`
- `errors` (empty means none known)

## Optional Fields

May be absent: `app.versionName`, `app.versionCode`, `selection.selectedUid`,
`selection.areaBoundsInWindow`, `selectedNode`, `candidatesAtPoint`,
`scopeCandidates`, `nearbyNodes`, `sourceCandidates`,
`editSurfaceCandidates`, `targetEvidence`, `targetReliability`,
`searchHints`, `screenshot`.

### `targetEvidence`

Generated when Copy Prompt or Save to MCP persists written pending
annotations. May be absent if the frozen screen is too thin.

- `identityHint` — strict test tags (`comp:<ComposableName>:<variant>`,
  `screen:<ComposableName>:<id>`, or `comp.<ComposableName>.<id>`) or stable
  labels
- `occurrence` — ordinal/count over captured merged nodes
- `sourceInterpretation` — top candidate summary
- `evidenceQuality` — `BASIC` or `STRUCTURED`
- `screenshotKinds` — e.g. `full`, `crop`
- `warnings` — confidence constraints, not task priority

### `targetReliability`

How much to trust the selected target. Not a priority. Does not replace
`targetEvidence` or `sourceCandidates`.

- `confidence`: `HIGH`, `MEDIUM`, `LOW`, `UNKNOWN`
- `reasons`: e.g. `STRICT_COMPOSABLE_IDENTITY`, `MEANINGFUL_COMPOSE_NODE`,
  `STRONG_SOURCE_CANDIDATE`, `MEDIUM_SOURCE_CANDIDATE`
- `warnings`: `VISUAL_AREA_ONLY`, `NO_MEANINGFUL_COMPOSE_TARGET`,
  `POSSIBLE_VIEW_INTEROP`, `LOW_SOURCE_CANDIDATE_MARGIN`,
  `SOURCE_INDEX_STALE`, `SCREEN_FINGERPRINT_MISMATCH_FORCED`,
  `SCREEN_FINGERPRINT_UNAVAILABLE`, `SENSITIVE_TEXT_REDACTED`

Compact Markdown renders `targetConfidence=<enum>` and `targetAction=`
(`inspect-source-first`, `inspect-and-corroborate`,
`treat-source-paths-as-hints`, `verify-manually`). Those tokens are renderer
output, not persisted JSON fields.

## Feedback Session Schema

Returned by `fixthis_open_feedback_console` and the local console API.

- `schemaVersion`, `sessionId`, `packageName`, `projectRoot`
- `createdAtEpochMillis`, `updatedAtEpochMillis`
- `screens` — persisted evidence snapshots, not live preview frames
- `items` — feedback queue
- `verificationReceipts` — missing field decodes as `[]`
- `runtimeEvidence` — local attachments; do not commit
- `runtimeEvidencePolicy` — `auto_on_handoff`, `manual`, `off` (legacy
  missing field = `manual`; new sessions store `auto_on_handoff`)
- `handoffBatches`
- `nextItemSequenceNumber`
- `status` — `active`, `ready_for_agent`, `closed`

## Feedback Session Summary

From `fixthis_list_feedback_sessions`: `sessionId`, `packageName`,
`projectRoot`, timestamps, `status`, `screensCount`, `itemsCount`,
`unresolvedItemsCount`, `draftItemsCount`, `inProgressItemsCount`,
`sentBatchesCount`. `fixthis_list_feedback` also returns
`unresolvedSentItemsCount`.

## Feedback Console Connection Status

`GET /api/connection` / `POST /api/app/launch`. Not persisted in session JSON.

- `state`: `WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`,
  `CHOOSE_DEVICE`, `CHECK_PHONE`, `UNSUPPORTED_BUILD`
- `headline`, `message`
- `primaryAction`: `START`, `OPEN_APP`, `RECONNECT`, `TRY_AGAIN`,
  `CHOOSE_DEVICE`, `CAPTURE`
- `selectedDevice`, `devices`, `packageName`
- `canCapture`, `canNavigate`, `canUseCachedWork`
- `details.deviceState`, `details.bridgeState`, `details.rawError`

Device summary: `serial`, `state`, `label`, `selected`. Launch is attempted
only from `WELCOME` or `OPEN_APP`.

## Captured Screen Schema

Created when Copy Prompt / Save to MCP persists a freeze, or by capture /
navigate with `captureAfter`.

- `screenId`, `capturedAtEpochMillis`, `activityName`, `displayName`
- `screenshot`, `roots`, `sourceIndexAvailable`, `errors`
- optional: `orientation`, `widthPx`, `heightPx`, `densityDpi`,
  `windowMode`, `systemUiVisible`, `systemUiKind`, `fingerprint`
  (16-char, additive; skip mismatch checks if missing)

## Feedback Navigation Result

`fixthis_navigate_app`: `performed`, `action` (`back`/`tap`/`swipe`),
`activityName`, `message`, `screen`, `captureError`.

## Feedback Item Schema

- `itemId`, `screenId` (shared by items saved from one freeze)
- timestamps, `target` (`semantics_node` or `visual_area`)
- `selectedNode`, `nearbyNodes`, `sourceCandidates`,
  `editSurfaceCandidates`
- `screenshotCrop`, `comment`, `sequenceNumber` (stable after save)
- `delivery`: `draft` or `sent`
- `clientWorkspaceId`, `clientDraftItemId` — retry idempotency
- `handoffBatchId`, `sentAtEpochMillis`
- `status`: `open`, `ready`, `in_progress`, `resolved`,
  `needs_clarification`, `wont_fix`
- `agentSummary`, `resolutionVerificationReceiptId`
- `targetEvidence`, `targetReliability`, `runtimeEvidenceIds`

`ready` is a compatibility value. Domain mappers normalize it to
`AnnotationStatus.OPEN`.

## Feedback Verification Receipt Schema

Append-only. Verification never changes item status.

- `receiptId`, `itemId`, `baselineScreenId`, `createdAtEpochMillis`
- `verdict`: `pass`, `warn`, `fail`
- `checks`: up to 16 `{kind, outcome, message}` (message ≤ 512 chars)
- `assertions`: `text_present`, `text_absent`, `target_present`
- optional: `currentActivity`, `currentScreenFingerprint`,
  `installedAtEpochMillis`, `afterScreenshot`, `matchedTargetSummary`

Old JSON: `verificationReceipts: []`,
`resolutionVerificationReceiptId: null`.

### `runtimeEvidence`

Copy Prompt never starts collection. Save to MCP collects `baseline` only
when policy is `auto_on_handoff`.

- `evidenceId`, `type` (`logcat_window`, `frame_summary`,
  `memory_summary`, `trace_artifact`)
- `capturedAtEpochMillis`, `deviceSerial`, `packageName`,
  `timeRangeEpochMillis`
- `summary` (bounded), `artifactPath`, `captureCommand`, `warnings`
- `captureId`, `status` (`complete`/`partial`/`failed`/`unsupported`)
- `trigger`: `handoff_auto`, `console_manual`, `mcp_manual`,
  `manual_attachment`
- correlation timestamps, `proximity` (`near` ≤ 3s, `delayed` ≤ 15s,
  `stale` after that), `failureReason`

Warnings: `capture_deferred`, `sensitive_logs_possible`, `artifact_missing`,
`output_truncated`, `redaction_applied`, `process_restarted`,
`context_changed`, `stale_window`, `cumulative_not_windowed`,
`timestamp_filter_unsupported`, `pid_filter_unsupported`.

Failure reasons: `device_unavailable`, `device_changed`,
`package_unavailable`, `process_not_running`, `collector_unsupported`,
`permission_denied`, `capture_timeout`, `context_changed`,
`artifact_write_failed`, `quota_exceeded`, `artifact_missing`.

Device/install/package/session/item/`screenId` drift fails closed. PID or
fingerprint drift is partial evidence with warnings.

Capture result (`fixthis_collect_runtime_evidence` / Save to MCP
`runtimeEvidence`): `attempted`, `captureId`, `status`, `attachmentIds`,
`linkedItemIds`, `artifactDirectory`, `warnings`, `failureReason`,
`skippedReason` (`manual`/`off`).

Handoff Markdown `runtimeEvidenceAttempt` includes attempted, status, mapped
reason, ≤8 warnings. No ids, paths, commands, or raw bodies.

Storage:

```text
.fixthis/runtime-evidence/<session-id>/<capture-id>/
├── logcat.txt
├── memory-summary.txt
├── frame-summary.txt
└── manifest.json
```

Limits: logcat 512 KiB, memory/frame 128 KiB each, bundle 2 MiB, project
root 250 MiB. POSIX dirs `0700`, files `0600`. Summaries 240 chars.
Handoff renders ≤3 attachments / 180 summary chars each. Raw collector
output never enters session JSON, MCP results, or Markdown.

### `editSurfaceCandidates`

Additive MCP/session field. Inspection hints, not a replacement for
`sourceCandidates`.

- `kind`: `CONTAINER_COLOR`, `TEXT_COLOR`, `TYPOGRAPHY`, `SPACING`,
  `CHIP_COLOR`, `COMPONENT_RENDERER`, `UNKNOWN`
- `file`, `repoFile`, `line`
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, `NONE`
- `reasons`: `STYLE_INTENT`, `LAYOUT_INTENT`, `TYPOGRAPHY_INTENT`,
  `TARGET_OWNER`, `SELECTED_TEXT_RENDERER`, `COMPONENT_DEFINITION`,
  `CALL_SITE`, `LIST_ITEM_SPACING`, `COMPONENT_CONTAINER`
- `role`: `CALL_SITE`, `COMPONENT_DEFINITION`, `COPY_OR_DATA`,
  `LAYOUT_OR_STYLE`, `VISUAL_AREA`, `INTEROP_RISK`
- `confidenceBasis`, `note` (compact Markdown `action:`)

## Feedback Delivery

Annotate freezes. Selection creates a pending item. Copy Prompt / Save to
MCP persists written comments onto one `screenId`. Pin-only leftovers stay
browser-local for Copy Prompt and are dropped for Save to MCP.

Save to MCP applies runtime-evidence policy while items are still draft,
then writes `delivery: "sent"`, `handoffBatchId`, `sentAtEpochMillis`, and
`handoffBatches`. No external AI API.

Browser recovery: `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`.
Not part of persisted session JSON.

Retries with `workspaceId` + `draftItemId` do not duplicate items. Keyless
pre-v0.4 local data is unsupported.

## Feedback Handoff Batch

`handoffBatches[]`: `batchId`, `sequenceNumber`, `createdAtEpochMillis`,
`itemIds`, optional `markdownSnapshot`.

## Feedback Handoff Formats

`fixthis_read_feedback` returns complete JSON and compact Markdown. Markdown
omits internal storage IDs.

## Selection

`kind`: `SEMANTICS_NODE`, `VISUAL_AREA`, `TAP_POINT`.
`confidence`: `HIGH`, `MEDIUM`, `LOW`, `NONE`.
`source`: `TAP_SELECT`, `SCOPE_CHIP`, `AREA_SELECT`, `FALLBACK`.

## Source Candidates

Best-effort ranked hints.

- `file` (module-relative), `repoFile`, `line`, `score` 0–1
- `matchedTerms`, `matchReasons`
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, `NONE`
- `ranking`, `scoreMargin`, `evidenceStrength` (`STRONG`/`MEDIUM`/`WEAK`)
- `riskFlags` (`AREA_SELECTION`, `TEXT_ONLY`, `NEARBY_ONLY`,
  `ACTIVITY_ONLY`, `ARBITRARY_LITERAL`, `LEGACY_FALLBACK`)
- `caution`, `stale` (`true`/`false`/`null`), `staleReason`,
  `ownerComposable`

`LEGACY_FALLBACK` is the serialized token for untyped fallback evidence. It
does not mean pre-v0.4 browser recovery is supported.

Source-index `schemaVersion` `1.2` adds typed `signals` while keeping v1
lists (`symbols`, `text`, `contentDescriptions`, `testTags`,
`stringResources`, `roles`, `activityNames`). Signal kinds:
`COMPOSABLE_SYMBOL`, `UI_TEXT`, `STRING_RESOURCE`,
`STRING_RESOURCE_RESOLVED`, `TEST_TAG`, `STRICT_COMP_TEST_TAG`,
`CONTENT_DESCRIPTION`, `ROLE`, `ACTIVITY_NAME`,
`ARBITRARY_STRING_LITERAL`, `LAMBDA_OWNER_FUNCTION`, `LAYOUT_RENDERER`.

Root metadata (`schemaVersion` ≥ `1.1`): `sourceRoot.kind`
(`gradle-project`), `sourceRoot.gradlePath`, `sourceRoot.projectDir`,
`entries[].repoFile`. Resolve order: `repoFile`,
`sourceRoot.projectDir + file`, `projectRoot + file`, unique suffix
fallback.

Match reasons: `selected text`, `selected contentDescription`,
`selected testTag`, `selected role`, `selected stringResource`,
`selected resolved stringResource`, `nearby text`,
`nearby contentDescription`, `nearby testTag`, `nearby role`, `activity`.

## Screenshot Paths

App cache:

```text
context.cacheDir/fixthis/<yyyy-MM-dd>/<annotation-id>-full.png
context.cacheDir/fixthis/<yyyy-MM-dd>/<annotation-id>-crop.png
```

Desktop session artifacts:

```text
.fixthis/feedback-sessions/<session-id>/artifacts/screens/<screen-id>/<screen-id>-full.png
.fixthis/feedback-sessions/<session-id>/artifacts/screens/<screen-id>/<screen-id>-crop.png
.fixthis/feedback-sessions/<session-id>/verification/<receipt-id>/after.png
```

HTTP URLs carry originating `sessionId`. Failures land in
`screenshot.captureFailedReason` or `captureError`; the annotation remains
valid.

## Error Codes

```json
{ "code": "NO_NODE_AT_TAP", "message": "...", "details": {} }
```

Inspection: `NO_NODE_AT_TAP`, `SCOPE_NODE_NOT_FOUND`,
`ROOT_DISCOVERY_FAILED`, `SEMANTICS_MERGED_INSPECTION_FAILED`,
`SEMANTICS_UNMERGED_INSPECTION_FAILED`, `NO_ACTIVITY`, `NO_DECOR_VIEW`,
`NO_OVERLAY_CONTROLLER`, `CAPTURE_IN_FLIGHT`.

Save conflict: `screen_fingerprint_mismatch` (HTTP 409) with
`frozenFingerprint` and `currentFingerprint`.

## Event Log And Checkpoints

```text
.fixthis/feedback-sessions/<session-id>/session.json
.fixthis/feedback-sessions/<session-id>/events/*.jsonl
.fixthis/feedback-sessions/<session-id>/events/checkpoint.json
.fixthis/feedback-sessions/<session-id>/events/archive/
```

Events are fsync + rename before in-memory update. Restart replays active
events over the snapshot. Compaction writes `checkpoint.json`
(`schemaVersion`, `sessionId`, `compactedThroughSequenceNumber`,
`snapshotUpdatedAtEpochMillis`, `createdAtEpochMillis`) then archives
covered events. Corrupt checkpoints are skipped, not replayed twice.
