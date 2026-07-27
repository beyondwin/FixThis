# Feedback Verification Receipts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add item-scoped, persisted verification receipts that prove a claimed feedback item against its original target, survive replay, can be linked atomically to resolution, and render in the existing console.

**Architecture:** Keep the feature inside `fixthis-mcp`: a verification coordinator captures live status and a bounded screen snapshot, delegates deterministic target/assertion/verdict decisions to pure evaluators, stages one local screenshot, and commits a `feedbackVerified` event only after optimistic context revalidation. The existing resolve mutation accepts an optional latest `PASS` or `WARN` receipt, while the console derives badges and receipt cards from the session DTO delivered by the existing SSE stream.

**Tech Stack:** Kotlin, kotlinx.serialization, existing FixThis bridge protocol 1.3, append-only session event log, JavaScript console modules, Node test runner, Gradle, ADB-connected Android proof scripts.

## Global Constraints

- Debug builds and Jetpack Compose only; do not add Views, Flutter, WebView DOM, or release-runtime behavior.
- Keep `fixthis-compose-core` free of MCP, CLI, Android UI, browser DTO, and local artifact-path dependencies.
- Preserve Bridge protocol `1.3`; do not add an app-side bridge method.
- Keep `fixthis_verify_ui_change` name, request, response, and behavior unchanged.
- Keep receipt-free `fixthis_resolve_feedback` calls valid.
- Verification never changes feedback status and never resolves an item automatically.
- `PASS` requires a reachable expected package, confirmed non-stale install, compatible screen, `HIGH` or `MEDIUM` target correspondence, at least one explicit assertion, all assertions passed, and no warnings.
- Visual-area feedback can produce only `WARN` or `FAIL`, never `PASS`.
- Do not use whole-screen fingerprint equality or pixel-diff thresholds as pass/fail criteria.
- Accept at most 8 assertions; trim assertion values to at most 256 characters and roles to at most 64 characters.
- Persist at most 16 checks, cap each message at 512 characters, and store at most one after screenshot.
- Store screenshots only below `.fixthis/feedback-sessions/<session-id>/verification/<receipt-id>/after.png`; never upload them.
- Persist only bounded target summaries, never a live root or full current semantics tree.
- Additive persisted fields must decode old session JSON with empty/null defaults.
- Use the existing `HostSourceFreshnessProbe`; do not create another source timestamp scanner.
- Use the existing session SSE path; do not add verification polling or a new top-level console workflow.
- Generate console assets with `node scripts/build-console-assets.mjs`; never hand-edit generated `app.js`, source maps, or build metadata.
- Preserve unrelated worktrees and changes. Do not commit `.fixthis`, screenshots, reports, fixture workspaces, or personal agent state.

---

## File Structure

### New production files

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationModels.kt`
  - Serializable receipt, assertion, check, verdict, correspondence, and request/context types.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationRequestValidator.kt`
  - Input budgets and item/session/baseline preconditions with stable error prefixes.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackTargetCorrespondenceEvaluator.kt`
  - Pure node matching and bounded matched-target summaries.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackAssertionEvaluator.kt`
  - Pure target-scoped evaluation for `text_present`, `text_absent`, and `target_present`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationVerdictPolicy.kt`
  - Deterministic `PASS`/`WARN`/`FAIL` reduction and bounded check construction.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationArtifactStore.kt`
  - Guarded staging, atomic promotion, rollback, and orphan cleanup for `after.png`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationCoordinator.kt`
  - Live collection, freshness evaluation, optimistic context fencing, receipt assembly, and durable commit orchestration.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/VerificationReceiptStoreMutations.kt`
  - Locked context validation and event-backed receipt attachment.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/VerificationReceiptEventPayloadFactory.kt`
  - Stable `feedbackVerified` payload serialization.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/VerificationReceiptEventReplayer.kt`
  - Replay of persisted verification receipts through `SessionReducer`.
- `fixthis-mcp/src/main/console/verificationReceipt.js`
  - Pure receipt selection, badge derivation, escaped receipt-card HTML, and screenshot URLs.
- `scripts/verificationReceiptConsole-test.mjs`
  - Console badge/card/escaping/SSE contract coverage.
- `scripts/verification-receipt-smoke.mjs`
  - Isolated external-fixture stale/install/replay/resolve/console connected proof and report writer.
- `scripts/verification-receipt-smoke-test.mjs`
  - Offline unit coverage for strict smoke orchestration and report semantics.

### New test files

- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSerializationTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationRequestValidatorTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetCorrespondenceEvaluatorTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackAssertionEvaluatorTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationVerdictPolicyTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationArtifactStoreTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSessionEventTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationCoordinatorTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationResolutionTest.kt`

### Existing files to modify

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt:18`
  - Add `verificationReceipts` and `resolutionVerificationReceiptId`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/preview/PreviewCaptureService.kt:27`
  - Expose one internal capture primitive used by preview and verification without duplicating bridge decoding.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutation.kt:10`
  - Add `AttachVerificationReceipt`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReducer.kt:8`
  - Reduce receipt append mutations.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutationService.kt:94`
  - Validate and link optional receipt during the existing atomic item-status update.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReplayEngine.kt:150`
  - Route `feedbackVerified` to the new replayer and reset receipts during full-log replay.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt:105`
  - Expose context capture, receipt attachment, and receipt-aware status update methods.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt:340`
  - Wire the new locked mutation service.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/SessionArtifactJanitor.kt:12`
  - Remove incomplete and unreferenced verification directories on boot.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/draft/AnnotationWorkflow.kt:134`
  - Pass the optional receipt id through the existing resolve workflow.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt:77`
  - Construct the coordinator and expose `verifyFeedback`, receipt-aware resolution, and artifact lookup.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt:125`
  - Parse typed assertions and optional resolve receipt ids and normalize tool output.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt:175`
  - Register the nested assertion schema and extend resolve schema.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt:22`
  - Dispatch `fixthis_verify_feedback`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt:10`
  - Serve the contained after screenshot by receipt id and explicit session id.
- `fixthis-mcp/src/main/console/presentation/annotationDetailView.js:360`
  - Add one badge to saved item rows and one receipt card to saved-item detail.
- `fixthis-mcp/src/main/resources/console/styles.css`
  - Style four compact badges and the receipt card without adding navigation.
- `scripts/run-console-tests.mjs:7`
  - Add source-level receipt rendering guards.
- `scripts/console-tests.json`
  - Include the receipt console test in the canonical group.
- `scripts/build-console-assets.mjs:34`
  - Add receipt rendering identifiers to the preserved contract symbol list only if esbuild would otherwise rename them out of route contract tests.
- `scripts/android-proof-runner.mjs:63`
  - Add the strict verification-receipt proof step and failure catalog entry.
- `scripts/android-proof-runner-test.mjs`
  - Lock the step order, command, and strict failure behavior.
- `package.json:25`
  - Add `verification-receipt:smoke` and `verification-receipt:smoke:test`.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt:84`
  - Cover discovery, nested schema, output, guards, and compatibility.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt:12`
  - Cover explicit-session after-screenshot serving and containment rejection.
- `docs/reference/mcp-tools.md`
- `docs/reference/output-schema.md`
- `docs/reference/feedback-console-contract.md`
- `docs/contributing/release-readiness.md`
- `docs/product/roadmap.md`
- `docs/releases/unreleased.md`
  - Synchronize maintained contracts, proof requirements, roadmap status, and release notes.

---

### Task 1: Persisted receipt contract and backward-compatible decoding

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt:18`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSerializationTest.kt`

**Interfaces:**
- Consumes: Existing `SnapshotScreenshotDto`, `FixThisRect`, `SessionDto`, and `AnnotationDto`.
- Produces: `FeedbackVerificationRequest`, `FeedbackVerificationReceiptDto`, `FeedbackVerificationAssertionDto`, `FeedbackVerificationCheckDto`, `MatchedTargetSummaryDto`, and their enums; `SessionDto.verificationReceipts`; `AnnotationDto.resolutionVerificationReceiptId`.

- [ ] **Step 1: Write failing serialization tests**

```kotlin
@Test
fun oldSessionJsonDefaultsVerificationFields() {
    val decoded = fixThisJson.decodeFromString<SessionDto>(legacySessionJson)
    assertTrue(decoded.verificationReceipts.isEmpty())
    assertNull(decoded.items.single().resolutionVerificationReceiptId)
}

@Test
fun receiptRoundTripKeepsBoundedEvidenceShape() {
    val receipt = receiptFixture(
        verdict = FeedbackVerificationVerdict.PASS,
        assertions = listOf(
            FeedbackVerificationAssertionDto(
                kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
                value = "Pay now",
                role = "Button",
            ),
        ),
    )
    val decoded = fixThisJson.decodeFromString<FeedbackVerificationReceiptDto>(
        fixThisJson.encodeToString(receipt),
    )
    assertEquals(receipt, decoded)
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackVerificationSerializationTest' --no-daemon
```

Expected: compilation fails because the verification DTOs and additive fields do not exist.

- [ ] **Step 3: Add the serializable contract**

```kotlin
@Serializable
enum class FeedbackVerificationVerdict {
    @SerialName("pass") PASS,
    @SerialName("warn") WARN,
    @SerialName("fail") FAIL,
}

@Serializable
enum class FeedbackVerificationAssertionKind {
    @SerialName("text_present") TEXT_PRESENT,
    @SerialName("text_absent") TEXT_ABSENT,
    @SerialName("target_present") TARGET_PRESENT,
}

@Serializable
enum class FeedbackVerificationCheckOutcome {
    @SerialName("passed") PASSED,
    @SerialName("warning") WARNING,
    @SerialName("failed") FAILED,
}

@Serializable
enum class FeedbackTargetCorrespondence {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
    @SerialName("none") NONE,
}

@Serializable
data class FeedbackVerificationAssertionDto(
    val kind: FeedbackVerificationAssertionKind,
    val value: String? = null,
    val role: String? = null,
)

internal data class FeedbackAssertionEvaluation(
    val assertion: FeedbackVerificationAssertionDto,
    val outcome: FeedbackVerificationCheckOutcome,
    val message: String,
)

@Serializable
data class FeedbackVerificationCheckDto(
    val kind: String,
    val outcome: FeedbackVerificationCheckOutcome,
    val message: String,
)

@Serializable
data class MatchedTargetSummaryDto(
    val confidence: FeedbackTargetCorrespondence,
    val nodeUid: String? = null,
    val role: String? = null,
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val boundsInWindow: FixThisRect? = null,
)

@Serializable
data class FeedbackVerificationReceiptDto(
    val receiptId: String,
    val itemId: String,
    val baselineScreenId: String,
    val createdAtEpochMillis: Long,
    val verdict: FeedbackVerificationVerdict,
    val checks: List<FeedbackVerificationCheckDto>,
    val assertions: List<FeedbackVerificationAssertionDto>,
    val currentActivity: String? = null,
    val currentScreenFingerprint: String? = null,
    val installedAtEpochMillis: Long? = null,
    val afterScreenshot: SnapshotScreenshotDto? = null,
    val matchedTargetSummary: MatchedTargetSummaryDto? = null,
)

data class FeedbackVerificationRequest(
    val sessionId: String,
    val itemId: String,
    val assertions: List<FeedbackVerificationAssertionDto>,
)
```

Add these exact defaults:

```kotlin
// SessionDto
val verificationReceipts: List<FeedbackVerificationReceiptDto> = emptyList(),

// AnnotationDto
val resolutionVerificationReceiptId: String? = null,
```

- [ ] **Step 4: Run focused serialization tests and existing persistence coverage**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationSerializationTest' \
  --tests '*FeedbackSessionPersistenceTest' \
  --tests '*SnapshotDtoSerializationTest' \
  --no-daemon
```

Expected: PASS, including legacy JSON decoding with empty/null verification defaults.

- [ ] **Step 5: Commit the contract**

```bash
git add \
  docs/superpowers/plans/2026-07-27-feedback-verification-receipts.md \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationModels.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSerializationTest.kt
git commit -m "feat: add feedback verification receipt contract"
```

### Task 2: Request guards, target correspondence, assertions, and verdict policy

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationRequestValidator.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackTargetCorrespondenceEvaluator.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackAssertionEvaluator.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationVerdictPolicy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationRequestValidatorTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetCorrespondenceEvaluatorTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackAssertionEvaluatorTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationVerdictPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1 DTOs plus `SessionDto`, `AnnotationDto`, `AnnotationTargetDto`, `SnapshotDto`, `FixThisNode`, and `FeedbackDelivery`.
- Produces:
  - `FeedbackVerificationRequestValidator.validate(session, itemId, assertions): FeedbackVerificationStartContext`
  - `FeedbackTargetCorrespondenceEvaluator.evaluate(item, currentScreen): FeedbackTargetCorrespondenceResult`
  - `FeedbackAssertionEvaluator.evaluate(assertions, correspondence): List<FeedbackAssertionEvaluation>`
  - `FeedbackVerificationVerdictPolicy.decide(checks, assertions): FeedbackVerificationVerdict`

- [ ] **Step 1: Write the request-guard decision tests**

```kotlin
@Test
fun rejectsUnsentAndUnclaimedItemsWithStablePrefixes() {
    assertFailsWithMessage("VERIFICATION_ITEM_NOT_SENT:") {
        validator.validate(draftSession(), "item-1", emptyList())
    }
    assertFailsWithMessage("VERIFICATION_ITEM_NOT_IN_PROGRESS:") {
        validator.validate(sentOpenSession(), "item-1", emptyList())
    }
}

@Test
fun rejectsAssertionBudgetsWithoutCreatingContext() {
    val oversized = FeedbackVerificationAssertionDto(
        kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
        value = "x".repeat(257),
    )
    assertFailsWithMessage("VERIFICATION_ASSERTIONS_INVALID:") {
        validator.validate(claimedSession(), "item-1", listOf(oversized))
    }
}
```

- [ ] **Step 2: Write the pure evaluator decision tables**

```kotlin
@Test
fun matchingStableTagIsHighAndRoleTextFallbackIsMedium() {
    assertEquals(HIGH, evaluator.evaluate(itemWithTag("pay"), screenWithTag("pay")).confidence)
    assertEquals(
        MEDIUM,
        evaluator.evaluate(itemWithRoleText("Button", "Pay now"), screenWithRoleText("Button", "Pay now")).confidence,
    )
}

@Test
fun assertionsAreScopedToTheCorrespondingNode() {
    val current = screenWithNodes(
        target = node(text = listOf("Pay now"), role = "Button"),
        unrelated = node(text = listOf("Delete account"), role = "Button"),
    )
    val correspondence = evaluator.evaluate(itemWithRoleText("Button", "Pay now"), current)
    val result = assertionEvaluator.evaluate(
        listOf(textAbsent("Delete account", role = "Button")),
        correspondence,
    )
    assertEquals(FeedbackVerificationCheckOutcome.PASSED, result.single().outcome)
}

@Test
fun verdictTablePreventsFalsePasses() {
    assertEquals(FAIL, policy.decide(listOf(failed("SOURCE_INSTALL_STALE")), passedAssertions()))
    assertEquals(WARN, policy.decide(listOf(warning("INSTALL_FRESHNESS_UNKNOWN")), passedAssertions()))
    assertEquals(WARN, policy.decide(listOf(passed("TARGET_MEDIUM")), emptyList()))
    assertEquals(PASS, policy.decide(passChecks(), passedAssertions()))
}
```

- [ ] **Step 3: Run the four test classes and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackVerificationRequestValidatorTest' \
  --tests '*FeedbackTargetCorrespondenceEvaluatorTest' \
  --tests '*FeedbackAssertionEvaluatorTest' \
  --tests '*FeedbackVerificationVerdictPolicyTest' \
  --no-daemon
```

Expected: compilation fails because the validators and evaluators are absent.

- [ ] **Step 4: Implement exact validation and matching rules**

Create a start context that captures every optimistic fence:

```kotlin
data class FeedbackVerificationStartContext(
    val sessionId: String,
    val sessionUpdatedAtEpochMillis: Long,
    val item: AnnotationDto,
    val itemUpdatedAtEpochMillis: Long,
    val baselineScreen: SnapshotDto,
    val receiptCount: Int,
    val assertions: List<FeedbackVerificationAssertionDto>,
)
```

Validation must:

```kotlin
requireVerification(session.status != SessionStatusDto.CLOSED, "VERIFICATION_CONTEXT_CHANGED:", "Session is closed")
requireVerification(item.delivery == FeedbackDelivery.SENT, "VERIFICATION_ITEM_NOT_SENT:", itemId)
requireVerification(item.status == AnnotationStatusDto.IN_PROGRESS, "VERIFICATION_ITEM_NOT_IN_PROGRESS:", itemId)
requireVerification(baseline != null, "VERIFICATION_BASELINE_NOT_FOUND:", item.screenId)
requireVerification(assertions.size <= 8, "VERIFICATION_ASSERTIONS_INVALID:", "At most 8 assertions are allowed")
```

Normalize each assertion by trimming `value` and `role`. Require nonblank `value` only for `TEXT_PRESENT` and `TEXT_ABSENT`; reject any value for `TARGET_PRESENT`; reject values over 256 characters and roles over 64 characters.

Correspondence must flatten merged and unmerged current nodes, deduplicate by `(rootIndex, treeKind, uid)`, and rank candidates deterministically:

```kotlin
when {
    stableTagMatches && roleCompatible -> HIGH
    identityHintMatches && roleCompatible -> HIGH
    roleCompatible && semanticOverlap && spatiallyCompatible -> MEDIUM
    roleCompatible && spatiallyCompatible && nearbyContextOverlap -> MEDIUM
    roleCompatible && (semanticOverlap || spatiallyCompatible) -> LOW
    else -> NONE
}
```

Tie-break in this order: confidence, exact semantic overlap count, intersection-over-union of bounds, then lexical `uid`. For `AnnotationTargetDto.Area`, return `LOW` with no matched node and a `MANUAL_VISUAL_REVIEW_REQUIRED` reason. Bound summary `text` and `contentDescriptions` to 4 entries each, trim each entry to 256 characters, and omit sensitive/password node text.

Assertion evaluation must inspect only `correspondence.matchedNode`:

```kotlin
TEXT_PRESENT -> matchesRole && targetStrings.any { it.contains(value, ignoreCase = false) }
TEXT_ABSENT -> matchesRole && targetStrings.none { it.contains(value, ignoreCase = false) }
TARGET_PRESENT -> correspondence.confidence in setOf(HIGH, MEDIUM, LOW)
```

If the optional role does not match the corresponding node role, the assertion fails. `NONE` correspondence fails every assertion. Verdict precedence is `FAILED check/assertion -> FAIL`, then any warning or zero assertions -> `WARN`, otherwise `PASS`. Truncate the final check list to 16 and every message to 512 characters at construction time.

- [ ] **Step 5: Run the evaluator tests and mutation-free core regression**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackVerification*Test' \
  --tests '*FeedbackTargetStrategyTest' \
  --tests '*TargetEvidenceServiceTest' \
  --no-daemon
```

Expected: PASS; global `fixthis_verify_ui_change` code remains untouched.

- [ ] **Step 6: Commit the pure verification policy**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationRequestValidatorTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetCorrespondenceEvaluatorTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackAssertionEvaluatorTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationVerdictPolicyTest.kt
git commit -m "feat: evaluate feedback verification evidence"
```

### Task 3: Guarded after-screenshot artifact lifecycle

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationArtifactStore.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/SessionArtifactJanitor.kt:12`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationArtifactStoreTest.kt`
- Modify test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionArtifactJanitorTest.kt`

**Interfaces:**
- Consumes: `SessionDto`, `SnapshotScreenshotDto`, and canonical project/session roots.
- Produces:
  - `prepare(session, receiptId, source): PreparedVerificationArtifact`
  - `promote(prepared): SnapshotScreenshotDto?`
  - `discard(prepared)`
  - `deleteReceiptArtifact(session, receiptId)`
  - `cleanupIncomplete()` and `cleanupOrphans(referencesBySession)`.

- [ ] **Step 1: Write containment, promotion, rollback, and janitor tests**

```kotlin
@Test
fun promotesExactlyOnePngIntoReceiptDirectory() {
    val prepared = store.prepare(session, "receipt-1", screenshotFixture(sourcePng))
    val promoted = store.promote(prepared)
    assertEquals(
        root.resolve(".fixthis/feedback-sessions/session-1/verification/receipt-1/after.png").canonicalPath,
        promoted?.desktopFullPath,
    )
    assertFalse(prepared.temporaryDirectory.exists())
}

@Test
fun rejectsSymlinkAndOutsideSessionRoot() {
    assertFailsWith<FeedbackSessionException> {
        store.prepare(session, "../escape", screenshotFixture(outsidePng))
    }
}

@Test
fun janitorRemovesIncompleteAndUnreferencedReceiptDirectories() {
    janitor.cleanupVerificationArtifacts(listOf(sessionWithReceipt("receipt-kept")), referencesComplete = true)
    assertTrue(receiptDirectory("receipt-kept").isDirectory)
    assertFalse(receiptDirectory("receipt-orphan").exists())
    assertFalse(incompleteDirectory.exists())
}
```

- [ ] **Step 2: Run artifact tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationArtifactStoreTest' \
  --tests '*SessionArtifactJanitorTest' \
  --no-daemon
```

Expected: compilation fails because verification artifact APIs are absent.

- [ ] **Step 3: Implement guarded staging and atomic promotion**

Use a canonical receipt root and an adjacent hidden temporary directory:

```kotlin
data class PreparedVerificationArtifact(
    val sessionId: String,
    val receiptId: String,
    internal val temporaryDirectory: File,
    internal val finalDirectory: File,
    internal val stagedFile: File?,
    internal val sourceMetadata: SnapshotScreenshotDto?,
)
```

The implementation must validate `sessionId` and `receiptId` as single path segments, reject symbolic links from the verification root through the staged file, copy only a regular `.png`, call `FileDescriptor.sync()`, and first promote with:

```kotlin
Files.move(
    temporaryDirectory.toPath(),
    finalDirectory.toPath(),
    StandardCopyOption.ATOMIC_MOVE,
)
```

Use a same-filesystem `Files.move(temporaryDirectory.toPath(), finalDirectory.toPath())` fallback only when `AtomicMoveNotSupportedException` is thrown. Return:

```kotlin
sourceMetadata?.copy(
    fullPath = null,
    cropPath = null,
    desktopFullPath = finalDirectory.resolve("after.png").canonicalPath,
    desktopCropPath = null,
)
```

If no source screenshot exists, stage no file and return `null`; the receipt can still persist. On every exception, delete the exact temporary and final receipt directories. Extend `SessionArtifactJanitor` so only receipt ids referenced from `SessionDto.verificationReceipts` survive a references-complete boot.

- [ ] **Step 4: Run artifact and persistence tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationArtifactStoreTest' \
  --tests '*SessionArtifactJanitorTest' \
  --tests '*FeedbackSessionPersistenceTest' \
  --no-daemon
```

Expected: PASS, with no files outside the verification subtree.

- [ ] **Step 5: Commit the artifact boundary**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationArtifactStore.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/SessionArtifactJanitor.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationArtifactStoreTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionArtifactJanitorTest.kt
git commit -m "feat: persist verification screenshot artifacts"
```

### Task 4: Event-backed receipt commit, replay, and context fencing

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/VerificationReceiptStoreMutations.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/VerificationReceiptEventPayloadFactory.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/VerificationReceiptEventReplayer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutation.kt:10`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReducer.kt:8`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReplayEngine.kt:150`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt:105`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt:340`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSessionEventTest.kt`
- Modify test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionReducerTest.kt`
- Modify test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt`

**Interfaces:**
- Consumes: `FeedbackVerificationStartContext` and `FeedbackVerificationReceiptDto`.
- Produces:
  - `FeedbackSessionStore.captureVerificationContext(sessionId, itemId, assertions)`
  - `FeedbackSessionStore.validateVerificationContext(context)`
  - `FeedbackSessionStore.attachVerificationReceipt(context, receipt): SessionDto`
  - `SessionMutation.AttachVerificationReceipt(receipt, now)`.

- [ ] **Step 1: Write event, replay, compaction, and race tests**

```kotlin
@Test
fun feedbackVerifiedEventReplaysReceiptExactlyOnce() {
    val receipt = receiptFixture(receiptId = "receipt-1")
    store.attachVerificationReceipt(contextFixture(), receipt)
    restartStore()
    assertEquals(listOf(receipt), store.getSession("session-1").verificationReceipts)
}

@Test
fun competingItemMutationRejectsReceiptWithoutJournalAppend() {
    val context = store.captureVerificationContext("session-1", "item-1", assertions())
    store.claimFeedback("session-1", "item-1", "changed note")
    val error = assertFailsWith<FeedbackSessionException> {
        store.attachVerificationReceipt(context, receiptFixture())
    }
    assertTrue(error.message.orEmpty().startsWith("VERIFICATION_CONTEXT_CHANGED:"))
    assertFalse(eventLogText().contains("feedbackVerified"))
}

@Test
fun checkpointPreservesReceiptsAndResolutionLinks() {
    compactSession(sessionWithReceiptAndLinkedResolution())
    assertEquals("receipt-1", replayed.items.single().resolutionVerificationReceiptId)
    assertEquals("receipt-1", replayed.verificationReceipts.single().receiptId)
}
```

- [ ] **Step 2: Run lifecycle tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationSessionEventTest' \
  --tests '*SessionReducerTest' \
  --tests '*EventLogCompactorTest' \
  --no-daemon
```

Expected: compilation fails because receipt mutations and replay routing do not exist.

- [ ] **Step 3: Add the reducer and event payload**

```kotlin
data class AttachVerificationReceipt(
    val receipt: FeedbackVerificationReceiptDto,
    val now: Long,
) : SessionMutation
```

Reducer behavior:

```kotlin
is SessionMutation.AttachVerificationReceipt -> session.copy(
    verificationReceipts = (
        session.verificationReceipts.filterNot { it.receiptId == mutation.receipt.receiptId } +
            mutation.receipt
        ),
    updatedAtEpochMillis = mutation.now,
)
```

The event payload contains only:

```json
{
  "sessionId": "session-1",
  "receipt": { "receiptId": "receipt-1" }
}
```

where `receipt` is the full serializer output. Append under type `feedbackVerified`; route that type through `VerificationReceiptEventReplayer`. During legacy full-log replay, clear `verificationReceipts` with screens/items/handoff batches so events remain the source of mutable state. Checkpoint replay continues to use the snapshot.

- [ ] **Step 4: Implement locked optimistic-context validation**

Within the store lock, compare all captured values:

```kotlin
private fun requireUnchanged(current: SessionDto, context: FeedbackVerificationStartContext) {
    val item = current.items.singleOrNull { it.itemId == context.item.itemId }
    val unchanged = current.status != SessionStatusDto.CLOSED &&
        current.updatedAtEpochMillis == context.sessionUpdatedAtEpochMillis &&
        item?.status == AnnotationStatusDto.IN_PROGRESS &&
        item.updatedAtEpochMillis == context.itemUpdatedAtEpochMillis &&
        current.screens.any { it.screenId == context.baselineScreen.screenId } &&
        current.verificationReceipts.size == context.receiptCount
    if (!unchanged) {
        throw FeedbackSessionException("VERIFICATION_CONTEXT_CHANGED: Feedback changed during verification")
    }
}
```

`attachVerificationReceipt` must re-run this check, reduce, append the event, commit the session, and emit the existing session/sessions updated notifications through `withEventBackedMutation`. Receipt ids must be unique.

- [ ] **Step 5: Run replay and store regression tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationSessionEventTest' \
  --tests '*FeedbackSessionStoreEventLogTest' \
  --tests '*ResolveClaimReplayTest' \
  --tests '*SessionReducerTest' \
  --tests '*EventLogCompactorTest' \
  --no-daemon
```

Expected: PASS; restart restores receipts and context drift leaves no event.

- [ ] **Step 6: Commit durable receipt history**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationSessionEventTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionReducerTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt
git commit -m "feat: replay feedback verification receipts"
```

### Task 5: Live verification coordinator and failure receipts

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationCoordinator.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/preview/PreviewCaptureService.kt:27`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt:77`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationCoordinatorTest.kt`
- Modify test fixture: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt`

**Interfaces:**
- Consumes: Tasks 1–4, `FixThisBridge.status`, `PreviewCaptureService`, `TargetEvidenceService.readSourceIndexOrNull`, and `HostSourceFreshnessProbe`.
- Produces:
  - `PreviewCaptureService.captureCurrentScreen(session, screenId, destinationDirectory, displayName): SnapshotDto`
  - `FeedbackVerificationCoordinator.verify(request): FeedbackVerificationReceiptDto`
  - `FeedbackSessionService.verifyFeedback(sessionId, itemId, assertions): FeedbackVerificationReceiptDto`.

- [ ] **Step 1: Write coordinator tests for pass, warn, fail, and cleanup**

```kotlin
@Test
fun staleInstallPersistsFailReceipt() = runTest {
    sourceFile.setLastModified(installedAt + 1_000)
    val receipt = coordinator.verify(request(targetPresent()))
    assertEquals(FeedbackVerificationVerdict.FAIL, receipt.verdict)
    assertTrue(receipt.checks.any { it.kind == "SOURCE_INSTALL_STALE" })
    assertEquals(receipt, store.getSession("session-1").verificationReceipts.single())
}

@Test
fun unavailableFreshnessProducesWarnNotPass() = runTest {
    bridge.installEpochMillis = null
    val receipt = coordinator.verify(request(textPresent("Pay now")))
    assertEquals(FeedbackVerificationVerdict.WARN, receipt.verdict)
    assertTrue(receipt.checks.any { it.kind == "INSTALL_FRESHNESS_UNKNOWN" })
}

@Test
fun successfulNodeVerificationPersistsAfterScreenshot() = runTest {
    val receipt = coordinator.verify(request(targetPresent(), textPresent("Pay now")))
    assertEquals(FeedbackVerificationVerdict.PASS, receipt.verdict)
    assertTrue(File(requireNotNull(receipt.afterScreenshot?.desktopFullPath)).isFile)
}

@Test
fun contextRaceDeletesPromotedArtifactAndCreatesNoReceipt() = runTest {
    bridge.beforeCaptureReturn = { store.claimFeedback("session-1", "item-1", "raced") }
    assertFailsWithMessage("VERIFICATION_CONTEXT_CHANGED:") { coordinator.verify(request(targetPresent())) }
    assertFalse(verificationRoot().exists())
    assertTrue(store.getSession("session-1").verificationReceipts.isEmpty())
}
```

- [ ] **Step 2: Run coordinator tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackVerificationCoordinatorTest' --no-daemon
```

Expected: compilation fails because the coordinator and shared live-capture primitive are absent.

- [ ] **Step 3: Extract the shared capture primitive**

Add this internal method to `PreviewCaptureService` and make existing preview/fingerprint methods delegate to it:

```kotlin
internal suspend fun captureCurrentScreen(
    session: SessionDto,
    screenId: String,
    destinationDirectory: File,
    displayName: String,
): SnapshotDto = bridge.captureScreenSnapshot(
    packageName = session.packageName,
    sessionId = session.sessionId,
    screenId = screenId,
    destinationDirectory = destinationDirectory,
).toCapturedScreen(screenId, displayName)
```

This is reuse of host-side decoding, not a bridge protocol change.

- [ ] **Step 4: Implement coordinator sequencing and product checks**

Use this constructor boundary:

```kotlin
internal class FeedbackVerificationCoordinator(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore,
    private val previewCaptureService: PreviewCaptureService,
    private val targetEvidenceService: TargetEvidenceService,
    private val freshnessProbe: HostSourceFreshnessProbe,
    private val artifactStore: FeedbackVerificationArtifactStore,
    private val requestValidator: FeedbackVerificationRequestValidator = FeedbackVerificationRequestValidator(),
    private val correspondenceEvaluator: FeedbackTargetCorrespondenceEvaluator = FeedbackTargetCorrespondenceEvaluator(),
    private val assertionEvaluator: FeedbackAssertionEvaluator = FeedbackAssertionEvaluator(),
    private val verdictPolicy: FeedbackVerificationVerdictPolicy = FeedbackVerificationVerdictPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String,
)
```

Sequence:

```kotlin
val context = store.captureVerificationContext(request.sessionId, request.itemId, request.assertions)
val receiptId = idGenerator()
val status = runCatching { bridge.status(session.packageName) }
val currentScreen = status.getOrNull()?.let {
    previewCaptureService.captureCurrentScreen(session, receiptId, temporaryCaptureDir, "Verification screen")
}
val sourceIndex = currentScreen?.let { targetEvidenceService.readSourceIndexOrNull(session.packageName, it) }
val freshness = sourceIndex?.let { freshnessProbe.evaluate(it, status.installEpochMillis()) }
val checks = buildChecks(status, currentScreen, freshness, context)
val correspondence = currentScreen?.let { correspondenceEvaluator.evaluate(context.item, it) }
val assertionResults = assertionEvaluator.evaluate(context.assertions, correspondence)
val receiptWithoutArtifact = assembleBoundedReceipt(
    receiptId = receiptId,
    context = context,
    checks = checks + assertionResults.map(FeedbackAssertionEvaluation::toCheck),
    assertions = context.assertions,
    currentScreen = currentScreen,
    freshness = freshness,
    correspondence = correspondence,
)
val prepared = artifactStore.prepare(session, receiptId, currentScreen?.screenshot)
store.validateVerificationContext(context)
val afterScreenshot = artifactStore.promote(prepared)
return try {
    store.attachVerificationReceipt(context, receiptWithoutArtifact.copy(afterScreenshot = afterScreenshot))
        .verificationReceipts.single { it.receiptId == receiptId }
} catch (failure: Throwable) {
    artifactStore.deleteReceiptArtifact(session, receiptId)
    throw failure
} finally {
    artifactStore.discard(prepared)
    temporaryCaptureDir.deleteRecursively()
}
```

Map bridge exceptions or missing activity to `APP_UNAVAILABLE`; a known returned package unequal to `session.packageName` to `PACKAGE_MISMATCH`; stale freshness to `SOURCE_INSTALL_STALE`; missing source index/install epoch to `INSTALL_FRESHNESS_UNKNOWN`; known differing activities to `SCREEN_CONTEXT_MISMATCH`; one missing activity to `SCREEN_CONTEXT_UNKNOWN`. Product comparison failures after context validation persist as `FAIL`; invalid requests, context races, artifact failures, and event failures return errors and create no receipt.

- [ ] **Step 5: Run coordinator, preview, and freshness tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationCoordinatorTest' \
  --tests '*PreviewCaptureServiceTest' \
  --tests '*HostSourceFreshnessProbeTest' \
  --no-daemon
```

Expected: PASS; stale install never returns `PASS`, unknown freshness returns `WARN`, and a clean matching node can return `PASS`.

- [ ] **Step 6: Commit the coordinator**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/verification/FeedbackVerificationCoordinator.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/preview/PreviewCaptureService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationCoordinatorTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt
git commit -m "feat: verify feedback against live runtime"
```

### Task 6: Receipt-aware resolution and MCP tool contracts

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutationService.kt:94`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt:105`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt:340`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/draft/AnnotationWorkflow.kt:134`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt:331`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt:125`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt:175`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt:22`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationResolutionTest.kt`
- Modify test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt:84`

**Interfaces:**
- Consumes: `FeedbackSessionService.verifyFeedback` and persisted receipts.
- Produces:
  - MCP `fixthis_verify_feedback({sessionId?, itemId, assertions[]})`
  - `resolveFeedback(sessionId: String, itemId: String, status: AnnotationStatusDto, summary: String?, verificationReceiptId: String? = null): AnnotationDto`
  - atomic `AnnotationDto.resolutionVerificationReceiptId` linkage.

- [ ] **Step 1: Write resolution validation tests**

```kotlin
@Test
fun resolvedAcceptsLatestPassOrWarnAndStoresLinkAtomically() {
    assertEquals("pass-2", resolveWith(latestPassReceipt()).resolutionVerificationReceiptId)
    assertEquals("warn-2", resolveWith(latestWarnReceipt()).resolutionVerificationReceiptId)
}

@Test
fun resolveRejectsFailedCrossItemOldAndNonResolvedLinks() {
    assertResolveError("VERIFICATION_RECEIPT_FAILED:", failedReceipt())
    assertResolveError("VERIFICATION_RECEIPT_MISMATCH:", otherItemReceipt())
    assertResolveError("VERIFICATION_RECEIPT_NOT_LATEST:", olderReceipt())
    assertResolveError(
        "VERIFICATION_RECEIPT_MISMATCH:",
        latestPassReceipt(),
        status = AnnotationStatusDto.WONT_FIX,
    )
}

@Test
fun receiptFreeResolutionRemainsCompatible() {
    val resolved = service.resolveFeedback("session-1", "item-1", RESOLVED, "done", null)
    assertNull(resolved.resolutionVerificationReceiptId)
}
```

- [ ] **Step 2: Write MCP discovery, nested schema, and result tests**

```kotlin
@Test
fun verifyFeedbackToolPublishesTypedAssertionArray() {
    val tool = listedTool("fixthis_verify_feedback")
    val assertions = tool.inputSchema.properties["assertions"]
    assertEquals("array", assertions["type"]?.jsonPrimitive?.content)
    assertEquals(
        setOf("text_present", "text_absent", "target_present"),
        assertions["items"]!!.jsonObject["properties"]!!.jsonObject["kind"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
    )
}

@Test
fun verifyFeedbackReturnsPersistedReceiptWithoutRawRoots() = runTest {
    val result = callVerifyFeedback(itemId = "item-1", assertions = targetPresentJson())
    assertEquals("receipt-1", result.receiptId())
    assertFalse(result.toString().contains("\"roots\""))
}
```

- [ ] **Step 3: Run resolution and MCP tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationResolutionTest' \
  --tests '*McpProtocolTest' \
  --no-daemon
```

Expected: tests fail because the receipt parameter, tool, nested schema, and handler are absent.

- [ ] **Step 4: Implement atomic receipt linkage**

Change the existing method signature end-to-end:

```kotlin
fun updateItemStatus(
    session: SessionDto,
    itemId: String,
    status: AnnotationStatusDto,
    summary: String?,
    verificationReceiptId: String? = null,
): Pair<SessionDto, AnnotationDto>
```

For a supplied id, find the receipt or throw `VERIFICATION_RECEIPT_NOT_FOUND:`. Require `status == RESOLVED`, same `itemId`, latest receipt by `(createdAtEpochMillis, receiptId)`, and verdict `PASS` or `WARN`; use the stable mismatch/non-latest/failed prefixes from the design. Copy `status`, `agentSummary`, and `resolutionVerificationReceiptId` in the same `AnnotationDto.copy` and persist through the existing `updateItemStatus` replace-items event.

- [ ] **Step 5: Register and implement the MCP contract**

Add a schema helper capable of a nested array of objects:

```kotlin
private fun assertionArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("maxItems", 8)
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                putJsonArray("enum") {
                    add("text_present")
                    add("text_absent")
                    add("target_present")
                }
            }
            putJsonObject("value") { put("type", "string"); put("maxLength", 256) }
            putJsonObject("role") { put("type", "string"); put("maxLength", 64) }
        }
        putJsonArray("required") { add("kind") }
        put("additionalProperties", false)
    }
}
```

Register `fixthis_verify_feedback` with required `itemId`, optional `sessionId`, and optional `assertions` defaulting to an empty list. Parse each assertion explicitly into `FeedbackVerificationAssertionDto`; validator semantics remain authoritative because JSON Schema cannot express the per-kind value rule. Return:

```json
{
  "content": [
    {
      "type": "text",
      "text": "Feedback item item-1 verification: PASS (receipt receipt-1)"
    }
  ],
  "structuredContent": {
    "receipt": {}
  }
}
```

Extend only `fixthis_resolve_feedback` with optional string `verificationReceiptId`. Add `OperationBackedToolHandler("fixthis_verify_feedback", feedbackOps::verifyFeedback)`.

- [ ] **Step 6: Run MCP, replay, and compatibility tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*FeedbackVerificationResolutionTest' \
  --tests '*McpProtocolTest' \
  --tests '*ResolveClaimReplayTest' \
  --tests '*SessionMutationServiceTest' \
  --no-daemon
```

Expected: PASS, including the unchanged discovery/schema assertions for `fixthis_verify_ui_change`.

- [ ] **Step 7: Commit tool and resolution contracts**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutationService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/draft/AnnotationWorkflow.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackVerificationResolutionTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat: expose feedback verification receipts"
```

### Task 7: Contained console route for after screenshots

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt:10`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt:12`

**Interfaces:**
- Consumes: persisted `FeedbackVerificationReceiptDto.afterScreenshot`.
- Produces: `GET /api/verification-receipts/<receiptId>/screenshot/after?sessionId=<sessionId>`.

- [ ] **Step 1: Write route scope and containment tests**

```kotlin
@Test
fun verificationAfterScreenshotUsesExplicitSessionAfterCurrentChanges() {
    val response = client.getResponse(
        "/api/verification-receipts/${encode("receipt-a")}/screenshot/after" +
            "?sessionId=${encode("session-a")}",
    )
    assertEquals(200, response.statusCode)
    assertTrue(response.contentTypeStartsWith("image/png"))
}

@Test
fun verificationAfterScreenshotRejectsWrongSessionAndOutsidePath() {
    assertEquals(404, getAfterScreenshot("receipt-a", sessionId = "session-b").statusCode)
    persistReceipt(afterPath = outsideRoot.absolutePath)
    assertEquals(404, getAfterScreenshot("receipt-a", sessionId = "session-a").statusCode)
}
```

- [ ] **Step 2: Run route tests and confirm RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleArtifactRoutesSessionScopeTest' --no-daemon
```

Expected: 404 because `ArtifactRoutes` does not match receipt screenshot paths.

- [ ] **Step 3: Add the route using the existing artifact guard**

Match exactly six path segments and decode the receipt id. Resolve the explicit query session when present, otherwise current session. Require a receipt in that session, a regular `.png`, and a canonical path under:

```kotlin
FeedbackSessionPaths(File(session.projectRoot))
    .rootDirectory
    .resolve("${session.sessionId}/verification/${receipt.receiptId}")
    .canonicalFile
```

Extract the repeated regular-PNG/containment check into one private helper inside `ArtifactRoutes`; keep existing screen screenshot behavior unchanged.

- [ ] **Step 4: Run all console route artifact tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests '*ConsoleArtifactRoutesSessionScopeTest' \
  --tests '*ConsoleAssetRoutesTest' \
  --no-daemon
```

Expected: PASS for screen, preview, and receipt artifacts.

- [ ] **Step 5: Commit the route**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt
git commit -m "feat: serve verification receipt screenshots"
```

### Task 8: Verification badges and receipt details in the existing console

**Files:**
- Create: `fixthis-mcp/src/main/console/verificationReceipt.js`
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js:360`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Create: `scripts/verificationReceiptConsole-test.mjs`
- Modify: `scripts/run-console-tests.mjs:7`
- Modify: `scripts/console-tests.json`
- Modify: `scripts/build-console-assets.mjs:34`
- Generated by command: `fixthis-mcp/src/main/resources/console/app.js`
- Generated by command: `fixthis-mcp/src/main/resources/console/app.js.map`
- Generated by command: `fixthis-mcp/src/main/resources/console/console-build-meta.json`

**Interfaces:**
- Consumes: `state.session.verificationReceipts`, item receipt links, existing `escapeHtml`, `formatTime`, and explicit-session screenshot URL conventions.
- Produces:
  - `receiptForItem(session, item)`
  - `verificationBadgeModel(session, item)`
  - `verificationBadgeHtml(session, item)`
  - `verificationReceiptSectionHtml(session, item)`.

- [ ] **Step 1: Write badge, card, escaping, and update-path tests**

```javascript
test("derives all four badge states", () => {
  assert.equal(verificationBadgeModel(passLinkedSession(), resolvedItem()).label, "verified");
  assert.equal(verificationBadgeModel(warnLinkedSession(), resolvedItem()).label, "warning");
  assert.equal(verificationBadgeModel(failLatestSession(), inProgressItem()).label, "verification failed");
  assert.equal(verificationBadgeModel(sessionWithoutReceipts(), resolvedItem()).label, "unverified");
});

test("renders bounded checks and both screenshot references with escaping", () => {
  const html = verificationReceiptSectionHtml(sessionFixture("<script>"), itemFixture());
  assert.match(html, /screens\\/baseline-screen\\/screenshot\\/full/);
  assert.match(html, /verification-receipts\\/receipt-1\\/screenshot\\/after/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;/);
});

test("receipt rendering reads refreshed session state and adds no polling timer", () => {
  assert.match(annotationDetailSource, /verificationReceiptSectionHtml\\(state\\.session, item\\)/);
  assert.doesNotMatch(verificationSource, /setInterval|setTimeout|poll/);
});
```

- [ ] **Step 2: Run the new test directly and confirm RED**

Run:

```bash
node --test scripts/verificationReceiptConsole-test.mjs
```

Expected: FAIL because `verificationReceipt.js` and rendering call sites do not exist.

- [ ] **Step 3: Implement pure selection and presentation**

Use this selection rule:

```javascript
function receiptForItem(session, item) {
  const receipts = (session?.verificationReceipts || [])
    .filter((receipt) => receipt.itemId === item.itemId)
    .sort((a, b) =>
      (b.createdAtEpochMillis - a.createdAtEpochMillis) ||
      String(b.receiptId).localeCompare(String(a.receiptId))
    );
  if (item.status === "resolved" && item.resolutionVerificationReceiptId) {
    return receipts.find((receipt) => receipt.receiptId === item.resolutionVerificationReceiptId) || null;
  }
  return receipts[0] || null;
}
```

Badge mapping:

```javascript
if (item.status === "resolved" && receipt?.verdict === "pass") return { tone: "success", label: "verified" };
if (item.status === "resolved" && receipt?.verdict === "warn") return { tone: "warning", label: "warning" };
if (item.status !== "resolved" && receipt?.verdict === "fail") return { tone: "danger", label: "verification failed" };
if (item.status === "resolved" && !item.resolutionVerificationReceiptId) return { tone: "neutral", label: "unverified" };
return null;
```

The card must render verdict, receipt id, formatted capture time, checks, typed assertions, baseline screenshot URL, and after screenshot URL. Build every text fragment through `escapeHtml`; construct URLs with `encodeURIComponent` for receipt/screen/session ids. Add `// @requires` for dependencies so the source graph orders the module; do not manually order concatenated assets.

- [ ] **Step 4: Wire the saved list and detail without new navigation**

In `renderSavedEvidenceGroups`, append `verificationBadgeHtml(state.session, item)` to each saved item row. In `renderSavedAnnotationDetail`, append:

```javascript
${verificationReceiptSectionHtml(state.session, item)}
```

Keep the existing runtime evidence section and action controls in place. Add compact CSS scoped under `.verification-*`; do not widen the History rail or add a page.

- [ ] **Step 5: Register the test and rebuild canonical assets**

Add `scripts/verificationReceiptConsole-test.mjs` to the `canonical` array in `scripts/console-tests.json`. Add source guard assertions in `run-console-tests.mjs` for the badge/detail calls and receipt screenshot route. Run:

```bash
node scripts/build-console-assets.mjs
node --test scripts/verificationReceiptConsole-test.mjs
node scripts/run-console-tests.mjs canonical
npm run console:test:fast
node scripts/build-console-assets.mjs --check
```

Expected: PASS; generated resources match source and no new polling appears.

- [ ] **Step 6: Commit console reflection**

```bash
git add \
  fixthis-mcp/src/main/console/verificationReceipt.js \
  fixthis-mcp/src/main/console/presentation/annotationDetailView.js \
  fixthis-mcp/src/main/resources/console/styles.css \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json \
  scripts/verificationReceiptConsole-test.mjs \
  scripts/run-console-tests.mjs \
  scripts/console-tests.json \
  scripts/build-console-assets.mjs
git commit -m "feat: render feedback verification receipts"
```

### Task 9: Offline-tested strict connected smoke

**Files:**
- Create: `scripts/verification-receipt-smoke.mjs`
- Create: `scripts/verification-receipt-smoke-test.mjs`
- Modify: `package.json:25`

**Interfaces:**
- Consumes: MCP JSON-RPC stdio, existing external-fixture Gradle conventions, ADB-selected device, console HTTP API, and `fixthis_verify_feedback`.
- Produces:
  - `npm run verification-receipt:smoke:test`
  - `npm run verification-receipt:smoke -- --strict`
  - `build/reports/fixthis-verification-receipt/report.json`
  - `build/reports/fixthis-verification-receipt/report.md`.

- [ ] **Step 1: Write offline orchestration and strictness tests**

```javascript
test("strict result requires every product-path checkpoint", () => {
  const report = buildReport(successfulSteps());
  assert.equal(report.status, "PASS");
  for (const name of [
    "baseline_install",
    "feedback_sent_and_claimed",
    "stale_install_failed_receipt",
    "rebuilt_install_passed_receipt",
    "restart_replayed_receipts",
    "failed_receipt_rejected",
    "passing_receipt_resolved",
    "console_rendered_verified",
  ]) {
    assert.equal(report.steps.find((step) => step.name === name)?.status, "PASS");
  }
});

test("strict mode rejects deferred connected evidence", () => {
  assert.throws(
    () => assertStrictReport(buildReport([{ name: "baseline_install", status: "DEFERRED" }])),
    /verification receipt strict proof failed/,
  );
});
```

- [ ] **Step 2: Run offline smoke tests and confirm RED**

Run:

```bash
node --test scripts/verification-receipt-smoke-test.mjs
```

Expected: module-not-found failure for `verification-receipt-smoke.mjs`.

- [ ] **Step 3: Implement deterministic external-fixture lifecycle**

Export `parseArgs`, `mcpCall`, `buildReport`, `writeReport`, `assertStrictReport`, and `runVerificationReceiptSmoke`. Use a generated fixture below:

```text
build/tmp/fixthis-verification-receipt/fixture/
```

The fixture package must be unique per run, contain one Compose screen with a tagged `Button`, and use the repository's included FixThis debug dependency. The script must:

```text
1. build and install baseline debug APK
2. cold-launch and wait for bridge status
3. open a new session, capture screen, create feedback, send, and claim item
4. edit only the generated fixture button text and advance its mtime beyond installEpochMillis
5. call fixthis_verify_feedback before reinstall and require FAIL + SOURCE_INSTALL_STALE
6. rebuild, install, cold-launch, and wait for the new text
7. call fixthis_verify_feedback with target_present and text_present and require PASS
8. stop only the MCP child spawned by this script, start a fresh MCP child on the same project root, and read the session
9. require both receipts and the after screenshot to survive replay
10. require resolution with the failed receipt to return VERIFICATION_RECEIPT_FAILED
11. resolve with the passing receipt and require the link on the item
12. launch headless Playwright against the spawned console, select the persisted session and item, and require the rendered `verified` badge plus linked receipt id
```

Track every spawned PID and generated path explicitly. In `finally`, stop only those PIDs, uninstall only the unique fixture package, and delete only the generated fixture directory. Never kill existing FixThis servers, emulators, or unrelated ADB processes.

- [ ] **Step 4: Add scripts and verify offline behavior**

Add:

```json
"verification-receipt:smoke": "node scripts/verification-receipt-smoke.mjs",
"verification-receipt:smoke:test": "node --test scripts/verification-receipt-smoke-test.mjs"
```

Run:

```bash
npm run verification-receipt:smoke:test
node scripts/verification-receipt-smoke.mjs --help
```

Expected: offline tests PASS and help exits 0 without requiring a device.

- [ ] **Step 5: Run the strict connected smoke**

Run:

```bash
npm run verification-receipt:smoke -- --strict
```

Expected: PASS with JSON and Markdown reports under `build/reports/fixthis-verification-receipt/`; `DEFERRED` or `SKIPPED` is not accepted.

- [ ] **Step 6: Commit connected proof**

```bash
git add \
  package.json \
  scripts/verification-receipt-smoke.mjs \
  scripts/verification-receipt-smoke-test.mjs
git commit -m "test: prove verification receipt product path"
```

### Task 10: Android proof row and maintained documentation

**Files:**
- Modify: `scripts/android-proof-runner.mjs:63`
- Modify: `scripts/android-proof-runner-test.mjs`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/product/roadmap.md`
- Modify: `docs/releases/unreleased.md`

**Interfaces:**
- Consumes: Task 9 strict smoke command and final MCP/session/console contracts.
- Produces: a required `Verification receipt product path` Android proof row and synchronized maintained docs.

- [ ] **Step 1: Write Android proof runner tests**

```javascript
test("verification receipt proof is required after runtime evidence", () => {
  const steps = buildProofSteps({}, connectedEnvironment());
  assert.deepEqual(
    steps.map((step) => step.name),
    [
      "Sample app proof",
      "Real Copy Prompt proof",
      "Agent loop smoke",
      "Runtime evidence product path",
      "Verification receipt product path",
      "External fixture matrix",
    ],
  );
  const verification = steps.find((step) => step.name === "Verification receipt product path");
  assert.equal(verification.command, "npm run verification-receipt:smoke -- --strict");
  assert.equal(verification.required, true);
});
```

- [ ] **Step 2: Run proof-runner tests and confirm RED**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
```

Expected: ordered step assertion fails because the verification receipt row is absent.

- [ ] **Step 3: Add the strict proof row and failure guidance**

Add failure code:

```javascript
verification_receipt_failed: {
  summary: "Feedback verification receipt product-path proof failed.",
  nextAction: "Inspect build/reports/fixthis-verification-receipt and rerun `npm run verification-receipt:smoke -- --strict`.",
},
```

Insert the required step immediately after runtime evidence:

```javascript
{
  name: "Verification receipt product path",
  command: "npm run verification-receipt:smoke -- --strict",
  failureCode: "verification_receipt_failed",
  required: true,
}
```

- [ ] **Step 4: Update maintained documentation with exact contracts**

Document:

- `fixthis_verify_feedback` request schema, response receipt, budgets, prerequisites, and stable errors in `docs/reference/mcp-tools.md`.
- additive session/item/receipt JSON fields in `docs/reference/output-schema.md`.
- four badge states, linked-versus-latest receipt selection, screenshot route, escaping, and SSE-only refresh in `docs/reference/feedback-console-contract.md`.
- both smoke commands, report paths, and strict connected requirements in `docs/contributing/release-readiness.md`.
- mark the receipt milestone delivered without changing unrelated roadmap ordering in `docs/product/roadmap.md`.
- add an unreleased feature note covering stale-install fail-closed behavior, replay, resolution linking, and console evidence in `docs/releases/unreleased.md`.

- [ ] **Step 5: Run proof-runner, doc, and route-selected checks**

Run:

```bash
node --test scripts/android-proof-runner-test.mjs
node scripts/check-doc-consistency.mjs
npm run agent:route -- --changed --json
```

Expected: tests and docs PASS; save the router JSON and run every returned focused check plus its broad gate before final completion.

- [ ] **Step 6: Commit proof wiring and docs**

```bash
git add \
  scripts/android-proof-runner.mjs \
  scripts/android-proof-runner-test.mjs \
  docs/reference/mcp-tools.md \
  docs/reference/output-schema.md \
  docs/reference/feedback-console-contract.md \
  docs/contributing/release-readiness.md \
  docs/product/roadmap.md \
  docs/releases/unreleased.md
git commit -m "docs: publish verification receipt workflow"
```

### Task 11: Fresh final-diff verification and closeout

**Files:**
- Review: every file changed by Tasks 1–10.
- Do not modify generated reports or `.fixthis` content.

**Interfaces:**
- Consumes: all implementation commits and router-selected gates.
- Produces: fresh evidence at final HEAD, an exact residual-risk statement, and a clean local Git handoff.

- [ ] **Step 1: Run focused Kotlin verification**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*Verification*' --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run offline smoke and console verification**

Run:

```bash
npm run verification-receipt:smoke:test
npm run console:test:fast
node scripts/run-console-tests.mjs canonical
npm run agent-loop:smoke:test
node scripts/build-console-assets.mjs --check
```

Expected: all commands PASS and the console bundle is current.

- [ ] **Step 3: Run connected product proof**

Run:

```bash
npm run verification-receipt:smoke -- --strict
npm run android:proof -- --strict
```

Expected: both commands PASS; reports identify stale failure, post-install pass, restart replay, failed-receipt rejection, linked resolution, and console reflection.

- [ ] **Step 4: Run router-selected broad verification**

Run:

```bash
npm run agent:route -- --changed --json
npm run ci:local:changed
```

Expected: router has no unexplained warnings and the returned broad gate passes. If the router returns additional focused commands, run them verbatim before continuing.

- [ ] **Step 5: Inspect the final diff and repository state**

Run:

```bash
git diff --check
git status --short --branch
git log -12 --oneline --decorate
git worktree list --porcelain
```

Expected: `git diff --check` PASS; only intentionally uncommitted files, if any, are listed; all four pre-existing worktrees remain intact.

- [ ] **Step 6: Return failures to the owning task**

If a final command fails, identify the first failing assertion, return to the task that owns that subsystem, add a regression test there, apply the smallest correction, rerun that task's focused command, and repeat Tasks 11.1–11.5 from the corrected HEAD. Do not create an empty commit when no correction is required.

- [ ] **Step 7: Report completion without unauthorized remote mutation**

Report:

```text
implementation commits
focused test commands and PASS results
strict report paths
router-returned checks and broad gate result
git diff --check result
branch/upstream/ahead-behind/dirty state
residual risks, or "none observed"
```

Do not merge, push, publish, stop unrelated processes, or clean another worktree unless the user separately requests it.
