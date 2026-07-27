# Feedback Verification Receipts Design

## Summary

FixThis currently closes the agent loop with two separate capabilities:

- `fixthis_verify_ui_change` checks whether text appears anywhere on the current
  Compose screen.
- `fixthis_resolve_feedback` stores a terminal status and an optional agent
  summary.

Neither capability ties the original feedback target to post-change runtime
evidence. An agent can resolve an item without recording which app build,
screen, target, or assertion it verified.

This design adds item-scoped verification receipts. A new
`fixthis_verify_feedback` MCP tool reads the original feedback and frozen
screen, inspects the current debug app through existing bridge capabilities,
evaluates explicit typed assertions, captures one bounded post-change
screenshot, and persists a `PASS`, `WARN`, or `FAIL` receipt. A later
`fixthis_resolve_feedback` call may link the latest compatible receipt to the
resolved item.

The feature is additive. It keeps receipt-free resolution compatible, leaves
`fixthis_verify_ui_change` unchanged, and does not change Bridge protocol
`1.3`.

## Goals

- Verify a feedback item against its original screen and target instead of
  matching text globally.
- Make stale source/install state, wrong-screen verification, target mismatch,
  and assertion failures explicit.
- Persist bounded post-change evidence that survives MCP restart and event-log
  replay.
- Let the console show whether a resolved item is verified, reviewed with a
  warning, or unverified.
- Preserve a strict separation between verification and resolution. Verification
  must never resolve an item automatically.
- Provide connected product-path proof that a stale APK cannot produce a
  passing receipt.

## Non-Goals

- Pixel-perfect visual regression scoring or automatic pass/fail thresholds.
- Natural-language parsing of the feedback comment into assertions.
- Automatic resolution after a passing verification.
- Cloud storage, external AI calls, or remote review behavior.
- Raw semantics dumps in persisted receipts.
- A new app-side bridge method or Bridge protocol change.
- XML/View source targeting, WebView DOM inspection, or non-Compose runtimes.
- A new top-level console workflow or dedicated verification page.

## Current State

`fixthis_verify_ui_change` delegates to the sidekick `verifyUiChange` bridge
method. The bridge inspects merged and unmerged semantics and reports whether
any text or content description contains `expectedText`. Its optional `role`
input is normalized into the MCP response but is not used to scope the
app-side match.

`fixthis_resolve_feedback` accepts `itemId`, terminal `status`, and optional
`summary`. `SessionMutationService.updateItemStatus` stores only the status and
`agentSummary`.

Persisted sessions already contain the inputs needed for an item-scoped
baseline:

- `AnnotationDto.screenId`
- `AnnotationDto.target`
- `AnnotationDto.selectedNode`
- `AnnotationDto.nearbyNodes`
- `AnnotationDto.targetEvidence`
- `AnnotationDto.targetReliability`
- the owning `SnapshotDto`, including activity, roots, screenshot, dimensions,
  and fingerprint

The MCP host also already owns the live inspection, preview screenshot,
session event log, SSE notification, and source freshness primitives required
for the post-change side of the comparison.

## User And Agent Workflow

The normal MCP lifecycle becomes:

```text
fixthis_read_feedback
  -> fixthis_claim_feedback
  -> edit code
  -> rebuild and install the debug app
  -> fixthis_verify_feedback
  -> inspect the receipt
  -> fixthis_resolve_feedback with verificationReceiptId
```

The user continues to create feedback in the browser console. The console does
not add a verification action that competes with the MCP agent workflow. It
reflects receipts produced by the agent and lets the user inspect the original
and post-change evidence.

## Architecture

The new flow remains MCP-hosted:

```text
fixthis_verify_feedback
  -> FeedbackVerificationCoordinator
       -> FeedbackSessionStore: original item and screen
       -> existing bridge status and screen inspection
       -> existing preview/screenshot capture
       -> HostSourceFreshnessProbe
       -> FeedbackTargetCorrespondenceEvaluator
       -> FeedbackAssertionEvaluator
       -> FeedbackVerificationArtifactStore
       -> session mutation and feedbackVerified event
  -> session-updated / sessions-updated SSE
  -> console badge and receipt detail
```

The implementation belongs under
`fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/`.
It may depend on the existing session DTOs and pure Compose models. It must not
move MCP session, artifact-path, browser, or tool concerns into
`:fixthis-compose-core`.

### `FeedbackVerificationCoordinator`

The coordinator owns the workflow boundary:

1. Resolve the session and item.
2. Validate that the session is active and the item is sent and in progress.
3. Capture a comparison context from the item, baseline screen, session update
   timestamp, and current receipt count.
4. Collect current bridge status, screen inspection, source freshness, and one
   post-change screenshot.
5. Evaluate context, target correspondence, and assertions.
6. Prepare a bounded receipt and optional screenshot artifact.
7. Re-read the session and reject a changed context.
8. Atomically promote the artifact and append the receipt event.
9. Emit session update events.

### `FeedbackTargetCorrespondenceEvaluator`

Target correspondence is distinct from assertion evaluation. It identifies
whether a current semantics node is sufficiently likely to be the node selected
in the original feedback.

The evaluator uses the strongest available baseline signals in this order:

1. Stable strict test-tag or target-evidence identity.
2. Compatible role plus semantic text/content description and spatial context.
3. Compatible role plus bounds and nearby-node context.

It returns `HIGH`, `MEDIUM`, `LOW`, or `NONE` with bounded reason codes.
`PASS` requires at least `MEDIUM` correspondence. `LOW` correspondence produces
`WARN`; `NONE` produces `FAIL` when an assertion needs a target.

Visual-area targets have no semantics-node identity. They capture post-change
evidence but require manual review and therefore cannot produce `PASS` in this
version.

### `FeedbackAssertionEvaluator`

Assertions are explicit, typed, and evaluated against the corresponding target,
not against arbitrary matching text elsewhere on the screen.

Supported assertion kinds are:

- `text_present`
- `text_absent`
- `target_present`

`text_present` and `text_absent` accept a required trimmed `value` and an
optional semantic `role`. `target_present` has no value.

The evaluator does not infer assertions from `AnnotationDto.comment`. An agent
that omits assertions receives a captured receipt with `WARN`, not an
unsubstantiated `PASS`.

### `FeedbackVerificationArtifactStore`

The store writes at most one post-change screenshot per receipt under:

```text
.fixthis/feedback-sessions/<session-id>/verification/<receipt-id>/after.png
```

It follows the session store's containment and durable-write conventions:

- create inside a guarded temporary sibling,
- reject symlinks and paths outside the session root,
- write the bounded artifact,
- revalidate context before commit,
- atomically move to the final receipt directory,
- remove the temporary directory on every failure.

The receipt stores an artifact reference and summary metadata, not image bytes.

## Persisted Data Contract

`SessionDto` gains one additive field:

```kotlin
val verificationReceipts: List<FeedbackVerificationReceiptDto> = emptyList()
```

`AnnotationDto` gains one additive nullable field:

```kotlin
val resolutionVerificationReceiptId: String? = null
```

Old session JSON decodes with an empty receipt list and a null resolution
receipt.

The receipt shape is:

```text
FeedbackVerificationReceiptDto
  receiptId
  itemId
  baselineScreenId
  createdAtEpochMillis
  verdict                  pass | warn | fail
  checks[]
    kind
    outcome                passed | warning | failed
    message
  assertions[]
    kind                    text_present | text_absent | target_present
    value?
    role?
  currentActivity?
  currentScreenFingerprint?
  installedAtEpochMillis?
  afterScreenshot?
  matchedTargetSummary?
    confidence              high | medium | low | none
    nodeUid?
    role?
    text[]
    contentDescriptions[]
    boundsInWindow?
```

The matched-target summary contains only the bounded, redaction-safe fields
needed to explain the result. It does not persist the live root or full current
semantics tree.

### Budgets

- At most 8 assertions per call.
- Assertion text is trimmed and limited to 256 characters.
- Assertion role text is limited to 64 characters.
- At most 16 persisted checks.
- Each check message is limited to 512 characters.
- Matched target text and content-description lists use the existing bounded
  semantic redaction rules.
- At most one post-change screenshot is stored per receipt.

Exceeding an input budget is a request error and creates no receipt.

## MCP Contracts

### `fixthis_verify_feedback`

Input:

```json
{
  "sessionId": "optional-active-session-default",
  "itemId": "required",
  "assertions": [
    {
      "kind": "text_present",
      "value": "Pay now",
      "role": "Button"
    },
    {
      "kind": "target_present"
    }
  ]
}
```

The tool requires:

- an active, non-closed session,
- an item with `delivery: sent`,
- item status `in_progress`,
- an owning baseline screen.

The output contains the complete persisted receipt and a concise text summary.
The tool does not return raw current semantics or image bytes.

### `fixthis_resolve_feedback`

The existing tool gains an optional `verificationReceiptId`.

When `status` is `resolved` and a receipt id is supplied, the server verifies:

- the receipt exists,
- it belongs to the same item,
- it is the latest receipt for that item,
- its verdict is `PASS` or `WARN`.

A `FAIL` receipt cannot be linked as the basis of `resolved`. A receipt id is
not accepted for `needs_clarification` or `wont_fix`, where it would imply a
successful resolution proof.

Calls that omit `verificationReceiptId` remain valid for compatibility. The
resulting resolved item is explicitly rendered as unverified.

### Existing Tool Compatibility

`fixthis_verify_ui_change` remains a lightweight, global current-screen text
assertion. Its name, required `expectedText`, optional `role`, response shape,
and app-side bridge method remain unchanged.

## Verdict Rules

The verdict is deterministic:

### `PASS`

All of the following are true:

- the current app and expected package are reachable,
- host source freshness confirms the installed APK is not stale,
- current screen context is compatible with the baseline,
- target correspondence is `HIGH` or `MEDIUM`,
- at least one explicit assertion was supplied,
- every required assertion passed,
- no check emitted a warning.

### `WARN`

Comparison ran, but proof is incomplete. Examples:

- no explicit assertion was supplied,
- the feedback target is a visual area,
- target correspondence is only `LOW`,
- install freshness or baseline screen identity is unavailable,
- the change requires human visual judgment.

A `WARN` receipt can be linked to a resolved item so a human-reviewed visual
change is not forced into an unverified state. The console labels it
`REVIEWED WITH WARNING`, not `VERIFIED`.

### `FAIL`

At least one required comparison failed. Examples:

- app or expected package unavailable,
- host source is newer than the installed APK,
- known baseline and current screen contexts conflict,
- no corresponding target can be found,
- a required assertion fails.

Failures that occur after a valid item verification attempt begins are
persisted as `FAIL` receipts so the recovery reason survives.

## Screen And Install Integrity

Whole-screen fingerprint equality is not required because a successful UI
change may legitimately alter the fingerprint.

Instead, the comparison uses:

- package equality,
- known baseline and current activity compatibility,
- current source/install freshness,
- target correspondence,
- current fingerprint as recorded diagnostic evidence.

If either baseline or current activity identity is unavailable, the receipt is
`WARN` and cannot pass. If both are known and differ, the receipt is `FAIL`.
If source freshness cannot be established, the receipt is `WARN`. If source
files are newer than the installed APK, the receipt is `FAIL`.

The implementation reuses `HostSourceFreshnessProbe`; it must not duplicate a
second source timestamp scanner.

## Lifecycle, Concurrency, And Replay

Before live collection, the coordinator records:

- session id and current session state,
- `session.updatedAtEpochMillis`,
- item id, status, and update timestamp,
- baseline screen id,
- current verification receipt count.

Immediately before commit, it re-reads the session. Any changed item status,
removed item/screen, closed session, or competing receipt mutation aborts with
`VERIFICATION_CONTEXT_CHANGED` and removes temporary artifacts.

A successful or product-level failed comparison appends a
`feedbackVerified` event. Replay restores the top-level receipt list. Event-log
compaction must preserve receipts and `resolutionVerificationReceiptId` in the
checkpoint snapshot.

Resolving with a receipt continues through the existing item-status mutation
path and stores the link in the same durable item update. Receipt linkage and
terminal status must not be persisted as separate non-atomic mutations.

## Error Handling

Request and state errors create no receipt. Stable prefixes include:

- `VERIFICATION_ITEM_NOT_SENT:`
- `VERIFICATION_ITEM_NOT_IN_PROGRESS:`
- `VERIFICATION_BASELINE_NOT_FOUND:`
- `VERIFICATION_ASSERTIONS_INVALID:`
- `VERIFICATION_CONTEXT_CHANGED:`
- `VERIFICATION_RECEIPT_NOT_FOUND:`
- `VERIFICATION_RECEIPT_MISMATCH:`
- `VERIFICATION_RECEIPT_NOT_LATEST:`
- `VERIFICATION_RECEIPT_FAILED:`

Once a valid attempt begins, product comparison failures create a receipt with
bounded check codes such as:

- `APP_UNAVAILABLE`
- `PACKAGE_MISMATCH`
- `SOURCE_INSTALL_STALE`
- `INSTALL_FRESHNESS_UNKNOWN`
- `SCREEN_CONTEXT_UNKNOWN`
- `SCREEN_CONTEXT_MISMATCH`
- `TARGET_NOT_FOUND`
- `TARGET_LOW_CONFIDENCE`
- `ASSERTION_NOT_PROVIDED`
- `ASSERTION_FAILED`
- `MANUAL_VISUAL_REVIEW_REQUIRED`

Artifact-write or event-commit failure returns an MCP error and leaves neither
a partial receipt nor a promoted artifact.

## Console Design

The existing History and saved-annotation detail remain the primary surfaces.
No new navigation destination is added.

History rows add at most one compact verification badge:

- `verified` for a resolved item linked to a `PASS` receipt,
- `warning` for a resolved item linked to a `WARN` receipt,
- `verification failed` for an unresolved item whose latest receipt is `FAIL`,
- `unverified` for a resolved item with no linked receipt.

The saved-annotation detail shows the linked receipt for a resolved item or the
latest receipt for an unresolved item. The receipt card contains:

- verdict,
- bounded check explanations,
- typed assertions,
- original and post-change screenshot thumbnails when present,
- receipt id and capture time.

Strings are escaped through existing console HTML helpers. The same card is
available in the compact History drawer. Receipt events use the existing
session SSE path so a browser does not poll for verification state.

## Testing

### Pure And Session Tests

Focused Kotlin coverage includes:

- verdict decision table,
- assertion validation and evaluation,
- target correspondence levels and reason codes,
- visual-area `WARN`,
- stale install `FAIL`,
- bounded receipt serialization,
- multiple receipt history and latest-receipt selection,
- cross-item and non-latest receipt rejection,
- atomic artifact promotion and cleanup,
- `feedbackVerified` replay and checkpoint recovery,
- old session JSON compatibility.

### MCP Contract Tests

MCP tests cover:

- tool discovery and input schema,
- normalized receipt output,
- stable request error prefixes,
- item state guards,
- `PASS` and `WARN` resolution linkage,
- `FAIL`, cross-item, and old-receipt linkage rejection,
- receipt-free resolution compatibility,
- unchanged `fixthis_verify_ui_change` contract.

### Console Tests

Console tests cover:

- History badge derivation,
- receipt-card rendering,
- original and post-change screenshot references,
- `PASS`, `WARN`, `FAIL`, and `UNVERIFIED` states,
- HTML escaping,
- compact History drawer parity,
- SSE-driven updates without new steady-state polling.

### Connected Product-Path Proof

Add:

```text
npm run verification-receipt:smoke:test
npm run verification-receipt:smoke -- --strict
```

The strict smoke uses an isolated external fixture:

```text
install baseline fixture
  -> create and send feedback
  -> claim the item
  -> change fixture source
  -> verify before reinstall
       => FAIL with SOURCE_INSTALL_STALE
  -> rebuild and install
  -> verify target_present and text_present
       => PASS
  -> restart MCP
  -> prove receipt replay
  -> reject resolution linked to the failed receipt
  -> resolve with the passing receipt
  -> prove verified console rendering
```

The smoke writes ignored JSON and Markdown reports under:

```text
build/reports/fixthis-verification-receipt/
```

`npm run android:proof -- --strict` gains a required verification-receipt
product-path row. A deferred device, fixture, replay, artifact, or console
assertion is not a passing connected claim.

### Final Verification

Expected final gates are:

```bash
./gradlew :fixthis-mcp:test --tests '*Verification*' --no-daemon
npm run verification-receipt:smoke:test
npm run verification-receipt:smoke -- --strict
npm run console:test:fast
npm run agent-loop:smoke:test
npm run ci:local:changed
npm run android:proof -- --strict
git diff --check
```

The final implementation router run determines the complete maintained check
set from the files actually changed; its returned broad gate is required in
addition to the focused commands above.

## Compatibility And Privacy

- Persisted session fields are additive with decoding defaults.
- Existing item ids, screen ids, statuses, and field names remain unchanged.
- Receipt-free resolution remains supported.
- `fixthis_verify_ui_change` remains unchanged.
- Bridge protocol remains `1.3`.
- Post-change screenshots remain local under ignored `.fixthis/` storage.
- Screenshot pixels may contain sensitive data, matching the existing product
  warning. Receipt summaries must remain redacted and bounded.
- No receipt or artifact is uploaded automatically.

## Success Criteria

The feature is complete when:

1. An agent can start from `itemId` and recover the original target and screen
   without re-entering baseline coordinates or text.
2. A stale APK or incompatible screen cannot produce `PASS`.
3. A visual-only or otherwise unprovable change produces `WARN`, not a false
   positive.
4. Receipts survive restart and event replay and render consistently in MCP and
   the console.
5. Resolution can link the latest `PASS` or `WARN` receipt, rejects `FAIL`, and
   remains backward-compatible when no receipt is supplied.
6. Strict connected proof exercises stale failure, post-install success,
   restart replay, resolution linkage, and console reflection through the
   product path.
