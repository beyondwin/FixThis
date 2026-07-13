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
- `editSurfaceCandidates`
- `targetEvidence`
- `targetReliability`
- `searchHints`
- `screenshot`

### `targetEvidence`

`targetEvidence` is optional additive evidence for agent handoff. In the current MCP console flow, it is generated when `Copy Prompt` or `Save to MCP` persists written pending annotations and promotes a frozen preview into persisted feedback items. It may be absent when the captured screen, selected target, or source index does not provide enough structured evidence.

- `identityHint`: optional target identity derived from strict test tags (`comp:<ComposableName>:<variant>`, `screen:<ComposableName>:<id>`, or dot-delimited `comp.<ComposableName>.<id>`) or stable semantics labels.
- `occurrence`: optional ordinal/count for the selected target, based on captured merged semantics nodes.
- `sourceInterpretation`: optional summary of the top source candidate, source-match reasons, and confidence caution.
- `evidenceQuality`: `BASIC` or `STRUCTURED`.
- `screenshotKinds`: screenshot artifact kinds available for the annotation, such as `full` and `crop`.
- `warnings`: human-readable caveats. Agents must treat these as confidence constraints.

### `targetReliability`

`targetReliability` is optional additive metadata that tells agents how much to
trust the selected target before editing. It is not a task priority and does
not replace `targetEvidence` or `sourceCandidates`.

- `confidence`: `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN`.
- `reasons`: machine-readable positive evidence tokens used to explain higher
  confidence, such as `STRICT_COMPOSABLE_IDENTITY`,
  `MEANINGFUL_COMPOSE_NODE`, `STRONG_SOURCE_CANDIDATE`, and
  `MEDIUM_SOURCE_CANDIDATE`.
- `warnings`: machine-readable caveat tokens. Current values include
  `VISUAL_AREA_ONLY`, `NO_MEANINGFUL_COMPOSE_TARGET`,
  `POSSIBLE_VIEW_INTEROP`, `LOW_SOURCE_CANDIDATE_MARGIN`,
  `SOURCE_INDEX_STALE`, `SCREEN_FINGERPRINT_MISMATCH_FORCED`,
  `SCREEN_FINGERPRINT_UNAVAILABLE`, and `SENSITIVE_TEXT_REDACTED`.

Agent-facing Markdown may render this confidence with action guidance. Precise
and full Markdown use sentence guidance such as inspect the source candidate
first, inspect and corroborate, treat source paths as hints only, or verify
manually. Compact Markdown preserves `targetConfidence=<enum>` and emits a
separate `targetAction=<token>` such as `inspect-source-first`,
`inspect-and-corroborate`, `treat-source-paths-as-hints`, or
`verify-manually`. These phrases and tokens are renderer output, not persisted
JSON fields.

## Feedback Session Schema

Feedback console sessions are returned by `fixthis_open_feedback_console` and served by the local console API. Top-level fields:

- `schemaVersion`: schema version string.
- `sessionId`: active feedback session id.
- `packageName`: Android application id.
- `projectRoot`: desktop project root.
- `createdAtEpochMillis`, `updatedAtEpochMillis`: session timestamps.
- `screens`: persisted evidence snapshots saved from frozen previews.
- `items`: feedback queue items.
- `runtimeEvidence`: additive list of bounded local runtime evidence summaries
  and artifact paths. It defaults to an empty list. Runtime evidence artifacts
  are local files and must not be committed.
- `runtimeEvidencePolicy`: persisted session policy. Values are
  `auto_on_handoff`, `manual`, and `off`. Newly created sessions explicitly
  store `auto_on_handoff`; legacy JSON with no field decodes as `manual`.
- `handoffBatches`: persisted sent handoff batches.
- `nextItemSequenceNumber`: next monotonic human-readable feedback item
  number for this session. Legacy sessions that do not contain this field are
  migrated from the highest existing item `sequenceNumber`.
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
- `inProgressItemsCount`: number of feedback items currently claimed by an agent.
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

Captured screens represent persisted evidence snapshots in a feedback session. The feedback console creates them when `Copy Prompt` or `Save to MCP` persists pending annotations and promotes a frozen preview; MCP tools can also create them through explicit capture or navigation with `captureAfter`. Live preview frames are not captured screens:

- `screenId`: persisted evidence snapshot id.
- `capturedAtEpochMillis`: capture timestamp.
- `activityName`: current Activity when available.
- `displayName`: console display label.
- `screenshot`: local screenshot artifact metadata when available.
- `roots`: Compose root snapshots with merged and unmerged nodes.
- `sourceIndexAvailable`: whether source matching data was available.
- `errors`: non-fatal capture or inspection errors.
- `orientation`: optional orientation string captured by the bridge.
- `widthPx`, `heightPx`, `densityDpi`: optional display metrics used for
  screen-integrity fingerprinting.
- `windowMode`: optional window mode such as fullscreen, split-screen, or
  picture-in-picture.
- `systemUiVisible`, `systemUiKind`: optional system UI state observed during
  capture.
- `fingerprint`: optional 16-character screen fingerprint derived from
  activity, orientation, dimensions, density, window mode, and system UI kind.

The fingerprint is additive. Legacy captures and old sidekick builds may omit
it; in that case save-time mismatch checks are skipped and the feedback item
can still be persisted.

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
- `editSurfaceCandidates`: optional list of likely rendering/edit surfaces for
  style, typography, spacing, or component-renderer feedback. Legacy sessions
  omit it.
- `screenshotCrop`: crop artifact metadata when available.
- `comment`: human feedback text.
- `sequenceNumber`: monotonic, stable human-readable item number within the
  session. Saved items are not renumbered after deletes, session switches, or
  MCP/console restarts.
- `delivery`: `draft` before handoff or `sent` after a handoff batch records the item for agent reading.
- `clientWorkspaceId`: optional browser DraftWorkspace id that created the
  item. New console saves populate it so retry deduplication can survive
  browser/server round trips.
- `clientDraftItemId`: optional browser-local draft item id. Together with
  `clientWorkspaceId`, it forms the primary idempotency key for
  `/api/items/batch`.
- `handoffBatchId`: batch id that sent the item, present for sent items when available.
- `sentAtEpochMillis`: time the item was sent to a handoff batch, present for sent items.
- `status`: `open`, `ready`, `in_progress`, `resolved`, `needs_clarification`, or `wont_fix`.
- `agentSummary`: optional agent resolution summary.
- `targetEvidence`: optional additive evidence for stable agent handoff. When present, it follows the annotation `targetEvidence` shape above.
- `targetReliability`: optional target confidence and warning metadata. When present, it follows the annotation `targetReliability` shape above.
- `runtimeEvidenceIds`: optional additive list of ids referencing
  session-level `runtimeEvidence` attachments. Items reference evidence by id
  so large local artifacts are not duplicated across feedback items.

`ready` is retained for persisted/session JSON compatibility. Domain mappers normalize legacy `ready` values to `AnnotationStatus.OPEN`; this is not a JSON field migration.

### `runtimeEvidence`

Runtime evidence attachments are optional, local-first summaries that can help
an agent verify a feedback item. `Copy Prompt` never starts collection.
`Save to MCP` runs the `baseline` preset only when the session policy is
`auto_on_handoff`; Manual and Off record a skipped attempt. A typed collection
failure does not prevent otherwise valid feedback from becoming sent.

- `evidenceId`: stable id referenced by item-level `runtimeEvidenceIds`.
- `type`: `logcat_window`, `frame_summary`, `memory_summary`, or
  `trace_artifact`.
- `capturedAtEpochMillis`: local capture timestamp.
- `deviceSerial`: optional ADB serial.
- `packageName`: Android application id used for capture.
- `timeRangeEpochMillis`: optional `{startEpochMillis,endEpochMillis}` window.
- `summary`: bounded text summary. Raw logs and traces are not embedded in
  compact handoff output.
- `artifactPath`: optional local path, usually under ignored `.fixthis/`
  storage.
- `captureCommand`: optional command description used for the capture.
- `warnings`: optional warning tokens from the taxonomy below.
- `captureId`: optional id shared by all attachments created by one bounded
  capture. Items in one automatic same-screen handoff share this id.
- `status`: `complete`, `partial`, `failed`, or `unsupported`. Legacy manual
  attachments default to `complete`.
- `trigger`: `handoff_auto`, `console_manual`, `mcp_manual`, or
  `manual_attachment`.
- `screenCapturedAtEpochMillis`, `captureStartedAtEpochMillis`, and
  `captureCompletedAtEpochMillis`: optional correlation timestamps.
- `proximity`: optional `near`, `delayed`, or `stale` correlation bucket.
- `failureReason`: optional terminal or partial failure reason.

Proximity is computed from capture start minus frozen-screen capture time:
`near` is 0 through 3,000 ms, `delayed` is more than 3,000 through 15,000 ms,
and `stale` is more than 15,000 ms or a negative delta. A screen-fingerprint
change downgrades `near` to `delayed` and adds `context_changed`.

Warning values are:

- `capture_deferred`
- `sensitive_logs_possible`
- `artifact_missing`
- `output_truncated`
- `redaction_applied`
- `process_restarted`
- `context_changed`
- `stale_window`
- `cumulative_not_windowed`
- `timestamp_filter_unsupported`
- `pid_filter_unsupported`

Failure reasons are `device_unavailable`, `device_changed`,
`package_unavailable`, `process_not_running`, `collector_unsupported`,
`permission_denied`, `capture_timeout`, `context_changed`,
`artifact_write_failed`, `quota_exceeded`, and `artifact_missing`.

Device serial, install epoch, package identity/availability, session state,
item identity, and `screenId` are hard linkage boundaries. Drift at one of
those boundaries fails closed and does not append links. PID restart and
screen-fingerprint drift remain usable partial evidence: they add warnings and
downgrade status instead of claiming a causal match.

### Runtime evidence capture result

`fixthis_collect_runtime_evidence` and the additive `runtimeEvidence` field on
the `Save to MCP` response use this result shape:

- `attempted`: whether collection was attempted.
- `captureId`: optional capture id.
- `status`: optional `complete`, `partial`, `failed`, or `unsupported`.
- `attachmentIds`, `linkedItemIds`: bounded id lists.
- `artifactDirectory`: optional relative bundle directory.
- `warnings`: warning taxonomy above.
- `failureReason`: optional failure taxonomy above.
- `skippedReason`: optional policy/availability reason. Handoff policy uses
  `manual` or `off`.

The persisted handoff Markdown adds a `runtimeEvidenceAttempt` block containing
only `attempted`, status, a mapped failure or skip reason, and at most eight
warning tokens. It intentionally omits capture ids, attachment ids, local
paths, commands, and raw bodies.

### Runtime evidence storage and bounds

Automatically collected artifacts use this project-relative layout:

```text
.fixthis/runtime-evidence/<session-id>/<capture-id>/
├── logcat.txt
├── memory-summary.txt
├── frame-summary.txt
└── manifest.json
```

Only files produced by the selected preset are present. The file limits are
512 KiB for logcat and 128 KiB each for memory and frame summaries. One
committed bundle is capped at 2 MiB, and the project runtime-evidence root is
capped at 250 MiB under a process/JVM file lock. Commit is temporary-directory
plus atomic-rename; incomplete and orphan bundles are cleaned on recovery.
On POSIX filesystems, runtime-evidence directories are owner-only (`0700`) and
artifact, manifest, and quota-lock files are owner-readable/writable (`0600`).
Existing runtime-evidence paths are tightened when the store opens them.

Collector output is redacted before durable write. Built-in rules cover
authorization/cookie headers, FixThis tokens, common secret/key/token
assignments and query parameters, JSON secret values, and JWT-like tokens.
Authorization, cookie, and token assignment rules accept colon, equals, quoted,
JSON, and whitespace-delimited forms. Every logcat result includes
`sensitive_logs_possible`, even when no built-in rule reports a substitution.
The redactor accepts at most 32 optional injected patterns of at most 256
characters each and rejects unsafe regular-expression shapes. The current
console and MCP schemas do not accept redaction patterns from callers.
Persisted summaries are capped at 240 characters; compact handoff renders at
most three attachments per item and 180 summary characters per attachment.
Raw collector output is never embedded in feedback-session JSON, MCP tool
results, or handoff Markdown.

### `editSurfaceCandidates`

`editSurfaceCandidates` is an additive MCP/session-local field. It separates
source-origin evidence from likely rendering surfaces for visual, style,
typography, spacing, and component-renderer requests.

Each entry can include:

- `kind`: one of `CONTAINER_COLOR`, `TEXT_COLOR`, `TYPOGRAPHY`, `SPACING`,
  `CHIP_COLOR`, `COMPONENT_RENDERER`, or `UNKNOWN`.
- `file`: likely rendering/edit surface file.
- `repoFile`: optional repository-root-relative file path.
- `line`: optional line hint.
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, or `NONE`.
- `reasons`: machine-readable derivation reasons, such as `STYLE_INTENT`,
  `LAYOUT_INTENT`, `TYPOGRAPHY_INTENT`, `TARGET_OWNER`,
  `SELECTED_TEXT_RENDERER`, `COMPONENT_DEFINITION`, `CALL_SITE`,
  `LIST_ITEM_SPACING`, or `COMPONENT_CONTAINER`.
- `role`: optional likely edit-surface role for the candidate; one of
  `CALL_SITE`, `COMPONENT_DEFINITION`, `COPY_OR_DATA`, `LAYOUT_OR_STYLE`,
  `VISUAL_AREA`, or `INTEROP_RISK`. Absent on older persisted sessions and
  on candidates the classifier could not score.
- `confidenceBasis`: optional human-readable basis for the role-specific confidence.
- `note`: optional role-specific action guidance or caveat for compact handoffs. In compact Markdown, edit-surface notes render as `action:` lines; source-candidate cautions still render as `note:` lines.

Agents should treat these as inspection hints. They do not rename or replace
`sourceCandidates`, and they are absent from older persisted sessions.

## Feedback Delivery

The feedback console defaults to navigation. `Annotate` freezes the latest preview so the user can select a target or drag a visual area; that selection creates a pending UI-only item and focuses its detail editor for the comment. Pending items are numbered in the Studio UI and support focus and delete until `Copy Prompt` or `Save to MCP` persists written pending annotations when needed. That persistence promotes the frozen preview once into one persisted evidence snapshot, stores written pending items, and connects them to the same `screenId`. In a mixed draft, pin-only residual items stay browser-local for `Copy Prompt` and are discarded for `Save to MCP`. Later `Annotate` work on the same visible app screen creates a new evidence snapshot when pending annotations are persisted.

`Save to MCP` first resolves the session runtime-evidence policy while the
items remain draft. Auto collects and links one baseline capture for a
same-screen batch; Manual and Off skip collection. The final session is then
re-read, compact Markdown is rendered with the final evidence decision, and a
persisted handoff batch changes the items to `delivery: "sent"`, sets
`handoffBatchId` and `sentAtEpochMillis`, and records those items in
`handoffBatches`. It does not create a new external AI API payload; MCP tools
read the persisted session data.

Connection loss does not change feedback delivery fields. Browser-only pending
items are mirrored separately as DraftWorkspace schema-v2 envelopes under
`localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`, with
`localStorage["fixthis.workspace.index.<sessionId>"]` storing the recoverable
workspace ids for that session. Each envelope carries `workspaceId`,
`revision`, `lifecycle`, immutable frozen `context` (`sessionId`, `previewId`,
`screenId`, `screenFingerprint`, `deviceSerial`, `frozenAtEpochMillis`, and
`activityName`), frozen `screen`, `screenshotUrl`, browser-local `items`, and
undo/redo `history`. The persisted MCP `FeedbackSession` JSON remains
unchanged; workspaces are only a browser recovery mirror until written items are
persisted into `.fixthis/feedback-sessions/`. After `Save to MCP`, residual
pin-only browser recovery is cleared instead of being promoted into persisted
session JSON.

Delivery values:

- `draft`: item is still in the current draft queue.
- `sent`: item is part of a persisted handoff batch.

Draft batch persistence is idempotent. When `/api/items/batch` receives
browser `workspaceId` and `draftItemId` values, persisted items retain them as
`clientWorkspaceId` and `clientDraftItemId`. Retrying the same batch does not
append duplicate items or duplicate event-log entries. If a retry contains
already-saved items plus new items from the same workspace, only the new items
are appended and they share the already-persisted evidence screen. Older items
or browser-local recovery entries without client draft ids are not semantically
deduplicated. They are unsupported pre-client-key local data and must be
discarded or recreated with current schema-v2 draft identity.

## Feedback Handoff Batch

Handoff batches are stored on the feedback session in `handoffBatches`:

- `batchId`: persisted batch id.
- `sequenceNumber`: stable human-readable batch number within the session.
- `createdAtEpochMillis`: time the batch was created.
- `itemIds`: feedback item ids included in the batch.
- `markdownSnapshot`: Markdown handoff snapshot captured when `Save to MCP` created the batch, when available.

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

- `file`: source file candidate, preserved as generated by the source index. For Gradle modules this is module-relative.
- `repoFile`: optional repository-root-relative source path. New source indexes populate this so MCP sessions launched from the repository root can render paths agents can open directly.
- `line`: optional line from the source index.
- `score`: normalized score from `0.0` to `1.0`.
- `matchedTerms`: selected or nearby terms found in the source index.
- `matchReasons`: why the entry matched.
- `confidence`: `HIGH`, `MEDIUM`, `LOW`, or `NONE`.
- `ranking`: optional 1-based rank within the item's ordered candidate list.
- `scoreMargin`: optional score gap between this candidate and the next-ranked candidate. Populated for the rank-1 candidate; serialized into compact handoff as `margin=`.
- `evidenceStrength`: optional `STRONG`, `MEDIUM`, or `WEAK` describing how reliable the underlying evidence is. Used to reserve `confidence=HIGH` for strong evidence with a clear top-vs-next margin.
- `riskFlags`: optional list of confidence-capping risk tokens (for example `AREA_SELECTION`, `TEXT_ONLY`, `NEARBY_ONLY`, `ACTIVITY_ONLY`, `ARBITRARY_LITERAL`, `LEGACY_FALLBACK`).
  `LEGACY_FALLBACK` remains the current serialized token for internal
  untyped-fallback source evidence. It is kept for persisted JSON and agent
  compatibility; it does not indicate support for pre-v0.4 browser recovery or
  old local artifact roots.
- `caution`: optional human-readable caveat. Surfaced on the rank-1 candidate as a `note:` line in compact handoff.
- `stale`: optional `true`, `false`, or `null`. `true` means the host source line no longer matches the index excerpt (do not edit by file:line); `false` means the line-accurate match was verified; `null` means the candidate could not be verified (no excerpt, no line, or an XML resource entry).
- `staleReason`: optional string explaining the staleness verdict, e.g. `"excerpt mismatch"`, `"file not found on host"`, `"file not found on host; sourceRoot unresolved"`, `"file not found on host; multiple suffix matches"`, `"line out of range"`, `"path escapes project root"`, or `"file too large to verify"`.
- `ownerComposable`: optional simple name of the enclosing `@Composable fun` for the indexed source entry. Markdown handoffs render this as `inside fun <name>` or compact `owner=<name>` when present.

Generated source-index entries now include additive v2 typed `signals` while preserving the v1 fields (`symbols`, `text`, `contentDescriptions`, `testTags`, `stringResources`, `roles`, and `activityNames`). Current signal kinds are `COMPOSABLE_SYMBOL`, `UI_TEXT`, `STRING_RESOURCE`, `STRING_RESOURCE_RESOLVED`, `TEST_TAG`, `STRICT_COMP_TEST_TAG`, `CONTENT_DESCRIPTION`, `ROLE`, `ACTIVITY_NAME`, `ARBITRARY_STRING_LITERAL`, `LAMBDA_OWNER_FUNCTION`, and `LAYOUT_RENDERER`; each signal has a `value` and optional `confidenceWeight` defaulting to `1.0`. `STRING_RESOURCE_RESOLVED` is emitted on Kotlin `stringResource(R.string.name)` call sites when the default-locale value is available from `res/values/strings.xml`; `LAMBDA_OWNER_FUNCTION` records the enclosing composable function for indexed Kotlin entries.

`LAYOUT_RENDERER` source-index signals are typed call-site evidence for Compose
`Layout(...)`, `SubcomposeLayout(...)`, and `SubcomposeLayout { ... }` usage, and
for content-slot wrapper composables — a `@Composable fun` exposing a
`content: @Composable (...) -> Unit` slot, which carries the wrapper's own
function name. Agents should interpret them with the owner composable and
confidence warnings: a layout renderer signal alone is not an exact source-line
guarantee.

Generated source indexes also include additive root metadata starting with source-index `schemaVersion: "1.1"`. Current generated source indexes use `schemaVersion: "1.2"` for resolved string-resource, owner-composable, and layout-renderer signals:

- `sourceRoot.kind`: source-root kind. Current value is `"gradle-project"`.
- `sourceRoot.gradlePath`: Gradle project path that generated the index, such as `":app"`.
- `sourceRoot.projectDir`: repository-root-relative Gradle project directory. Root projects use an empty string.
- `entries[].repoFile`: repository-root-relative source path for this entry.

`entries[].file` remains unchanged for compatibility. New MCP readers resolve candidates in this order: `repoFile`, `sourceRoot.projectDir + file`, legacy `projectRoot + file`, then a unique suffix fallback when exactly one host file ends with the indexed path.

Implemented match reasons include:

- `selected text`
- `selected contentDescription`
- `selected testTag`
- `selected role`
- `selected stringResource`
- `selected resolved stringResource`
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

CLI and MCP flows pull screenshots through the bridge and write desktop-readable artifacts. Current persisted screen artifacts live under the feedback-session workspace and use a generated screen id:

```text
.fixthis/feedback-sessions/<session-id>/artifacts/screens/<screen-id>/<screen-id>-full.png
.fixthis/feedback-sessions/<session-id>/artifacts/screens/<screen-id>/<screen-id>-crop.png
```

When available, annotation paths appear as `desktopFullPath` and
`desktopCropPath`. Feedback console screen paths appear on the screen entry.
Console preview and screen artifact HTTP URLs include the originating
`sessionId`; routes must resolve the artifact against that session rather than
whatever session is currently active. `.fixthis/artifacts/` and
`.fixthis/feedback-sessions/` are ignored by git because these files are local
debug screenshots and session metadata. If capture or storage fails,
`screenshot.captureFailedReason` or `captureError` records the failure and the
annotation or navigation result remains valid.

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

Bridge and MCP failures may be returned as tool errors or JSON-RPC errors instead of annotation `errors`. See [Troubleshooting](../guides/troubleshooting.md).

Feedback-console save conflicts can also be returned as local HTTP API
responses before an item is persisted:

- `screen_fingerprint_mismatch`: `POST /api/items/batch` compared the frozen
  preview fingerprint with a current lightweight capture and they differed.
  The response is HTTP 409 and includes `frozenFingerprint` and
  `currentFingerprint`; the browser asks whether to re-capture, force-save, or
  cancel.

## Event Log And Checkpoints

Feedback sessions are stored as a snapshot plus an append-only mutation log:

```text
.fixthis/feedback-sessions/<session-id>/session.json
.fixthis/feedback-sessions/<session-id>/events/*.jsonl
.fixthis/feedback-sessions/<session-id>/events/checkpoint.json
.fixthis/feedback-sessions/<session-id>/events/archive/
```

Each event is fsync'd to a temporary file and renamed to a numbered `.jsonl`
file before the in-memory session is updated. On restart, FixThis replays the
active events over the snapshot. Compaction writes `checkpoint.json` with
`schemaVersion`, `sessionId`, `compactedThroughSequenceNumber`,
`snapshotUpdatedAtEpochMillis`, and `createdAtEpochMillis`, then archives the
events covered by that checkpoint. Corrupt or incompatible checkpoints are
treated as skipped replay input rather than a reason to apply archived events
twice.
