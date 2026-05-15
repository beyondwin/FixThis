# Duplicate Draft Feedback Hardening Implementation Details

## Problem Statement

The current duplicate-save protection added client draft identity to console batch saves and prevents exact full duplicate appends. It still leaves several correctness gaps:

1. Partial duplicate saves can split one frozen draft workspace across two persisted screens.
2. Fully duplicate no-op saves still append `addScreenWithItems` events to the session event log.
3. Browser history dedupe falls back to semantic matching too broadly, which can hide a new draft that happens to target the same element with the same comment.
4. Recovery dedupe applies one workspace id after selecting the newest recovery envelope, instead of deduping each recovery candidate with its own workspace id.
5. Server-side legacy dedupe is absent for pre-`clientWorkspaceId/clientDraftItemId` saved items.

The goal is to make duplicate protection precise, bounded, and observable without broad refactors.

## Current Relevant Flow

### Console save request

`fixthis-mcp/src/main/console/draftApiAdapter.js` builds `/api/items/batch` payloads:

```js
{
  sessionId,
  previewId,
  workspaceId,
  screen,
  items: [
    {
      draftItemId,
      targetType,
      bounds,
      nodeUid,
      label,
      severity,
      status,
      comment
    }
  ],
  frozenFingerprint,
  forceMismatchOverride
}
```

### Server DTO and persistence

`SaveSnapshotRequest.workspaceId` and `AnnotationDraftDto.draftItemId` are routed through:

- `FeedbackItemRoutes`
- `FeedbackSessionService`
- `AnnotationWorkflow`
- `FeedbackDraftService`
- `FeedbackSessionStoreDelegate.addScreenWithItems`

`FeedbackDraftService.feedbackItemsForReservation` copies the browser ids onto `AnnotationDto.clientWorkspaceId` and `AnnotationDto.clientDraftItemId`.

### Current duplicate decision

`FeedbackSessionStoreDelegate.addScreenWithItems` does:

```kotlin
val existingClientKeys = session.items.mapNotNull { it.clientDraftKey() }.toSet()
val newItems = items.filterNot { item ->
    item.clientDraftKey()?.let { it in existingClientKeys } == true
}
```

If `newItems.isEmpty()`, it returns the existing session. If `newItems` is non-empty, it currently mints a new `captured` screen from the request screen and attaches all `newItems` to that screen.

That is correct for brand-new saves and full duplicates, but wrong for partial duplicates.

## Required Behavior

### B1: Partial duplicate saves reuse the existing screen

Given a session with:

- persisted screen `screen-1`
- persisted items `draft-1`, `draft-2`, both having `clientWorkspaceId = "workspace-a"`
- incoming batch for the same `workspace-a` containing `draft-1`, `draft-2`, `draft-3`
- request screen has `screenId = "pending"` or a preview/fallback screen id

Then:

- only `draft-3` is appended
- no second screen is appended
- `draft-3.screenId == "screen-1"`
- sequence numbers continue from the existing session counter
- response contains the session with one screen and three items
- event log contains an `addScreenWithItems` event for only the newly created item and the reused screen

### B2: Full duplicate saves do not write session events

Given a session where every incoming item has an existing `clientWorkspaceId/clientDraftItemId` match:

- the HTTP request still returns 200 with the current session
- no new screen or item is persisted
- no `addScreenWithItems` event is appended
- the event log does not grow for repeated identical retry/no-op requests

This is intentionally not an audit trail. It is a retry/idempotency suppression path.

### B3: Browser semantic dedupe is legacy-only

`historyPendingDedupe.js` must use this order:

1. If a pending item has both `workspaceId` and `draftItemId`, only client-key matching can remove it.
2. If a pending item has no client key, semantic matching may remove it, but only when the comment is nonblank.
3. Empty-comment/pin-only recovery items must not be removed by semantic matching.

This prevents a user from adding a new legitimate draft with the same bounds and same common comment, while still suppressing old `fixthis.pending.<sessionId>` legacy recovery mirrors.

### B4: Recovery dedupe is envelope-aware

`historyRecoveryItemsForSession` must dedupe each recovery envelope with that envelope's own `workspaceId`.

The selection should be:

1. Load v2 workspace envelopes from `draftStorageAdapter`.
2. Load legacy v1 mirror from `restorePendingState`.
3. For each candidate, compute `dedupedItems = dedupePendingHistoryItemsForSession(session, candidate.items, candidate.workspaceId || null)`.
4. Drop candidates with zero deduped items.
5. Select the newest remaining candidate by `updatedAtEpochMillis`.
6. Return that candidate's deduped items.

This matters when the newest local recovery is now fully represented on the server but an older recovery envelope still contains real unsaved pin-only work.

### B5: Server legacy semantic dedupe is narrow and non-destructive

For persisted items that lack `clientWorkspaceId/clientDraftItemId`, server-side fallback dedupe may suppress an incoming browser draft only when all of the following hold:

- incoming item has a client draft key
- existing item has no client draft key
- existing and incoming target type match
- node uid matches, or both are visual-area targets with matching integer-rounded bounds
- trimmed comment is nonblank and exactly equal
- existing and incoming item would attach to the same screen context

If there is any ambiguity, append the incoming item. False positives here drop user feedback, so the server fallback must be conservative.

The fallback exists only for a migration window. The primary idempotency contract is still `clientWorkspaceId + clientDraftItemId`.

## Non-Goals

- Do not introduce an HTTP `Idempotency-Key` cache in this pass.
- Do not change persisted MCP JSON field names.
- Do not change release/debug constraints.
- Do not change `Copy Prompt` residual pin-only behavior.
- Do not dedupe all semantically similar feedback globally.
- Do not refactor the whole session store or event log API beyond the minimal nullable/no-op path needed for duplicate suppression.

## Data Contracts

### Client draft key

```kotlin
internal fun AnnotationDto.clientDraftKey(): String? {
    val workspaceId = clientWorkspaceId?.takeIf { it.isNotBlank() }
    val draftItemId = clientDraftItemId?.takeIf { it.isNotBlank() }
    return if (workspaceId == null || draftItemId == null) null else "$workspaceId\u0000$draftItemId"
}
```

This remains the authoritative duplicate key.

### History semantic key

Browser semantic matching is only for legacy local recovery items. The semantic key may remain:

```js
[
  historyItemTargetType(item),
  String(item?.nodeUid || item?.target?.nodeUid || ''),
  normalizedHistoryBounds(historyItemBounds(item)),
  String(item?.comment || '').trim(),
].join('\u0000')
```

But it must be gated by `!pendingClientDraftKey(...)` and nonblank comment.

### Bounds normalization

Browser history dedupe should use integer pixel rounding for semantic legacy matching:

```js
Math.round(Number(bounds[key] || 0))
```

Server legacy fallback should use the same integer-rounded fields.

## Event Log Policy

`withEventBackedMutation` currently appends a journal event before running the mutation closure. Duplicate no-op handling needs a way to return a value without appending.

Use a new private wrapper type:

```kotlin
private data class EventBackedMutation<T>(
    val payload: JsonObject,
    val mutate: () -> T,
)
```

Keep existing callers readable by preserving:

```kotlin
private fun <T> withEventBackedMutation(
    sessionId: String,
    type: String,
    prepare: () -> Pair<JsonObject, () -> T>,
): T
```

Add a nullable variant for no-op paths:

```kotlin
private fun <T> withOptionalEventBackedMutation(
    sessionId: String,
    type: String,
    prepare: () -> EventBackedMutation<T>?,
    noop: () -> T,
): T
```

Only `addScreenWithItems` should use the optional variant in this pass.

## Test Requirements

### Kotlin route/store tests

Add tests in `ConsoleFeedbackItemRoutesTest.kt`:

1. `batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate`
2. `batchItemsApiDoesNotAppendEventForFullWorkspaceDuplicate`
3. `batchItemsApiDedupeLegacyServerItemWithSameTargetAndComment`

If event-log inspection requires a persistent temp project, instantiate the service with the same fixture style used in existing event-log tests or read the session event directory under `projectRoot`.

### JavaScript tests

Extend `scripts/historyPendingDedupe-test.mjs`:

1. client-key mismatch with same semantic target/comment stays visible
2. legacy semantic match is removed
3. legacy empty comment is not removed
4. recovery selection uses each envelope's workspace id and returns the newest non-empty deduped candidate

### Contract tests

Update `ConsoleAssetContractTest.kt` only if source-level helper names change.

### Verification commands

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
npm run console:draft:test
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test
```

## Risk Notes

- Server semantic fallback can lose feedback if it is too broad. Keep it narrow and covered by tests.
- Event-log no-op suppression changes audit behavior for duplicate retries. This is intentional and should be documented in the test name.
- Partial duplicate screen reuse must not attach unrelated new items to an old screen. Only reuse an existing screen when at least one incoming duplicate item maps to an existing item in the same request.
- If all incoming items are new, current screen creation behavior remains unchanged.

## Acceptance Criteria

- Reposting `[draft-1, draft-2, draft-3]` after `[draft-1, draft-2]` creates exactly one additional item and zero additional screens.
- Reposting the exact same full batch creates zero additional items, zero additional screens, and zero additional event-log entries.
- A new browser draft with same target/comment but a different client key remains visible in history.
- Legacy recovery with nonblank semantic match is still hidden from history.
- Legacy pin-only recovery is not hidden by semantic fallback.
- Full `:fixthis-mcp:test` and console draft tests pass.
