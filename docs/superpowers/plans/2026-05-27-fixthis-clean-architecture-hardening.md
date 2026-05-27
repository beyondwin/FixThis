# FixThis Clean Architecture Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor FixThis's MCP session and source/target trust internals toward clean architecture boundaries while preserving every external MCP, console, CLI, Gradle, bridge, and persisted JSON contract.

**Architecture:** Keep `FeedbackSessionService` source-compatible as the facade. Extract small pure policies and workflow seams first, then move target evidence rules into `:fixthis-compose-core`, then add narrow architecture drift checks and documentation updates. The sequence avoids a big-bang rewrite and proves behavior at contract boundaries after each extraction.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, kotlin.test/JUnit, Gradle, Node.js fixture tests, Graphify local graph.

---

## Scope Check

The approved spec intentionally covers three connected architecture tracks:

- MCP session application boundary cleanup.
- Source/target trust policy extraction.
- Module governance and drift prevention.

These are not three independent products. The implementation should ship as one architecture hardening branch with small commits. The first commits must preserve behavior and reduce session package pressure; later commits can strengthen core policy extraction and architecture checks after the code shape exists.

This plan does not change external contracts, bridge protocol, CLI commands, Gradle plugin behavior, persisted JSON field names, source matching fixture semantics, or runtime product scope.

## File Structure

### Preview Save Boundary

- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicy.kt`
  - Pure policy for frozen/current fingerprint comparison, unavailable reason, event metadata, and target reliability warning additions.
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicyTest.kt`
  - Unit tests for mismatch, null-fingerprint reason, event metadata, and warning output.
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTracker.kt`
  - Owns preview save in-flight keys and preview cache reservation/release.
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTrackerTest.kt`
  - Unit tests for reservation conflicts, missing preview, cache removal, and release after failure.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
  - Delegate fingerprint policy and reservation tracking to the new collaborators.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt`
  - Add one characterization test that invalid preview-save requests release the reservation and allow retry.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceMismatchTest.kt`
  - Keep existing mismatch behavior green after delegation.

### Target Evidence Boundary

- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactory.kt`
  - Pure factory for identity, occurrence, evidence quality, source interpretation, screenshot kinds, and target-evidence warnings.
- Create: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactoryTest.kt`
  - Unit tests for strict identity, visual-area warnings, and low-confidence source interpretation.
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidator.kt`
  - Validates target request data and derives selected/nearby evidence nodes from a `SnapshotDto`.
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidatorTest.kt`
  - Unit tests for blank comment rejection, missing node context, invalid bounds, area overlap ordering, and nearest-node fallback.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
  - Delegate target evidence construction to core and target validation to the MCP validator.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`
  - Keep integration behavior pinned after extraction.

### Architecture Governance And Docs

- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
  - Add a narrow check that core target policy files do not import MCP or session DTOs.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
  - Ratchet budgets for `FeedbackDraftService.kt` and `TargetEvidenceService.kt` after they shrink.
- Modify: `docs/architecture/overview.md`
  - Update the MCP/session and core policy descriptions after the refactor lands.
- Modify: `docs/superpowers/specs/2026-05-27-fixthis-clean-architecture-hardening-design.md`
  - Change status from `Ready for user review` to `Implemented` after all tasks are complete.

## Task 1: Extract Preview Fingerprint Policy

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicyTest.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicy.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`

- [ ] **Step 1: Write the failing policy tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicyTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewFingerprintPolicyTest {
    @Test
    fun enforceThrowsWhenFingerprintsDifferWithoutOverride() {
        val error = assertFailsWith<ScreenFingerprintMismatch> {
            PreviewFingerprintPolicy.enforce(
                PreviewSaveFingerprintCheck(
                    frozenFingerprint = "frozen",
                    currentFingerprint = "current",
                ),
            )
        }

        assertEquals("frozen", error.frozenFingerprint)
        assertEquals("current", error.currentFingerprint)
    }

    @Test
    fun enforceSkipsMismatchWhenOverrideOrUnavailableFingerprintIsPresent() {
        PreviewFingerprintPolicy.enforce(
            PreviewSaveFingerprintCheck(
                frozenFingerprint = "frozen",
                currentFingerprint = "current",
                forceMismatchOverride = true,
            ),
        )
        PreviewFingerprintPolicy.enforce(PreviewSaveFingerprintCheck(frozenFingerprint = null, currentFingerprint = "current"))
        PreviewFingerprintPolicy.enforce(PreviewSaveFingerprintCheck(frozenFingerprint = "frozen", currentFingerprint = null))
    }

    @Test
    fun unavailableReasonDistinguishesMissingSides() {
        assertEquals(
            "frozen_and_current_fingerprint_unavailable",
            PreviewFingerprintPolicy.unavailableReason(PreviewSaveFingerprintCheck()),
        )
        assertEquals(
            "frozen_fingerprint_unavailable",
            PreviewFingerprintPolicy.unavailableReason(PreviewSaveFingerprintCheck(currentFingerprint = "current")),
        )
        assertEquals(
            "current_fingerprint_unavailable",
            PreviewFingerprintPolicy.unavailableReason(PreviewSaveFingerprintCheck(frozenFingerprint = "frozen")),
        )
        assertEquals(
            null,
            PreviewFingerprintPolicy.unavailableReason(
                PreviewSaveFingerprintCheck(frozenFingerprint = "same", currentFingerprint = "same"),
            ),
        )
    }

    @Test
    fun eventMetadataKeepsExistingKeys() {
        val metadata = PreviewFingerprintPolicy.eventMetadata(
            fingerprintCheck = PreviewSaveFingerprintCheck(
                frozenFingerprint = null,
                currentFingerprint = null,
                forceMismatchOverride = true,
                frozenFingerprintSource = "previewCache",
                clientFrozenFingerprintMismatched = true,
            ),
            fingerprintUnavailableReason = "frozen_and_current_fingerprint_unavailable",
        )

        assertEquals(true, metadata["forceMismatchOverride"]?.jsonPrimitive?.boolean)
        assertEquals(
            "frozen_and_current_fingerprint_unavailable",
            metadata["fingerprintUnavailableReason"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("previewCache", metadata["frozenFingerprintSource"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, metadata["clientFrozenFingerprintMismatched"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun reliabilityWarningsPreserveExistingWarningSemantics() {
        val warnings = PreviewFingerprintPolicy.reliabilityWarnings(
            fingerprintCheck = PreviewSaveFingerprintCheck(forceMismatchOverride = true),
            fingerprintUnavailableReason = "current_fingerprint_unavailable",
        )

        assertEquals(
            listOf(
                TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
                TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
            ),
            warnings,
        )
    }
}
```

- [ ] **Step 2: Run the policy test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*PreviewFingerprintPolicyTest" --no-daemon
```

Expected: FAIL with unresolved `PreviewFingerprintPolicy`.

- [ ] **Step 3: Add the policy implementation**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicy.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PreviewFingerprintPolicy {
    fun enforce(fingerprintCheck: PreviewSaveFingerprintCheck) {
        val frozen = fingerprintCheck.frozenFingerprint
        val current = fingerprintCheck.currentFingerprint
        if (fingerprintCheck.forceMismatchOverride || frozen == null || current == null) return
        if (frozen != current) {
            throw ScreenFingerprintMismatch(frozen, current)
        }
    }

    fun unavailableReason(fingerprintCheck: PreviewSaveFingerprintCheck): String? = when {
        fingerprintCheck.frozenFingerprint == null && fingerprintCheck.currentFingerprint == null ->
            "frozen_and_current_fingerprint_unavailable"
        fingerprintCheck.frozenFingerprint == null -> "frozen_fingerprint_unavailable"
        fingerprintCheck.currentFingerprint == null -> "current_fingerprint_unavailable"
        else -> null
    }

    fun eventMetadata(
        fingerprintCheck: PreviewSaveFingerprintCheck,
        fingerprintUnavailableReason: String?,
    ): JsonObject = buildJsonObject {
        if (fingerprintCheck.forceMismatchOverride) put("forceMismatchOverride", true)
        if (fingerprintUnavailableReason != null) {
            put("fingerprintUnavailableReason", fingerprintUnavailableReason)
        }
        put("frozenFingerprintSource", fingerprintCheck.frozenFingerprintSource)
        if (fingerprintCheck.clientFrozenFingerprintMismatched) {
            put("clientFrozenFingerprintMismatched", true)
        }
    }

    fun reliabilityWarnings(
        fingerprintCheck: PreviewSaveFingerprintCheck,
        fingerprintUnavailableReason: String?,
    ): List<TargetReliabilityWarning> = buildList {
        if (fingerprintCheck.forceMismatchOverride) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED)
        }
        if (fingerprintUnavailableReason != null) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE)
        }
    }
}
```

- [ ] **Step 4: Delegate from `FeedbackDraftService`**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`, replace the block inside `commitPreviewFeedbackSaveWithMetadata` that calls `enforceFingerprintMatch`, `fingerprintUnavailableReason`, and `previewSaveEventMetadata` with:

```kotlin
        PreviewFingerprintPolicy.enforce(fingerprintCheck)
        val fingerprintUnavailableReason = PreviewFingerprintPolicy.unavailableReason(fingerprintCheck)
        val eventMetadata = PreviewFingerprintPolicy.eventMetadata(
            fingerprintCheck = fingerprintCheck,
            fingerprintUnavailableReason = fingerprintUnavailableReason,
        )
```

Then replace the private `previewReliabilityWarnings` body with:

```kotlin
    private fun previewReliabilityWarnings(
        fingerprintCheck: PreviewSaveFingerprintCheck,
        fingerprintUnavailableReason: String?,
    ): List<TargetReliabilityWarning> = PreviewFingerprintPolicy.reliabilityWarnings(
        fingerprintCheck = fingerprintCheck,
        fingerprintUnavailableReason = fingerprintUnavailableReason,
    )
```

Delete the private functions `enforceFingerprintMatch`, `fingerprintUnavailableReason`, and `previewSaveEventMetadata` from `FeedbackDraftService.kt`. Remove the now-unused imports `kotlinx.serialization.json.buildJsonObject` and `kotlinx.serialization.json.put`.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*PreviewFingerprintPolicyTest" --tests "*FeedbackDraftServiceMismatchTest" --no-daemon
```

Expected: PASS. Existing mismatch behavior and event metadata keys are unchanged.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicy.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewFingerprintPolicyTest.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt
git commit -m "refactor(session): extract preview fingerprint policy"
```

## Task 2: Extract Preview Save Reservation Tracking

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTrackerTest.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTracker.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt`

- [ ] **Step 1: Write tracker tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTrackerTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PreviewSaveReservationTrackerTest {
    @Test
    fun reserveRejectsMissingPreviewWhenFallbackIsAbsent() {
        val tracker = PreviewSaveReservationTracker(PreviewSnapshotCache(2))

        val error = assertFailsWith<FeedbackSessionException> {
            tracker.reserve(sessionId = "session-1", previewId = "preview-missing", fallbackScreen = null)
        }

        assertEquals("PREVIEW_NOT_FOUND: Unknown preview: preview-missing", error.message)
    }

    @Test
    fun reserveRejectsSecondSaveForSamePreviewUntilReleased() {
        val cache = PreviewSnapshotCache(2)
        cache.put(record("session-1", "preview-1"))
        val tracker = PreviewSaveReservationTracker(cache)

        val slot = tracker.reserve("session-1", "preview-1", fallbackScreen = null)
        val error = assertFailsWith<FeedbackSessionException> {
            tracker.reserve("session-1", "preview-1", fallbackScreen = null)
        }

        assertEquals("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: preview-1", error.message)
        tracker.release(slot.inFlightKey)
        tracker.reserve("session-1", "preview-1", fallbackScreen = null)
    }

    @Test
    fun completeRemovesCachedPreviewAndReleasesInflightKey() {
        val cache = PreviewSnapshotCache(2)
        cache.put(record("session-1", "preview-1"))
        val tracker = PreviewSaveReservationTracker(cache)
        val slot = tracker.reserve("session-1", "preview-1", fallbackScreen = null)

        val removed = tracker.complete(sessionId = "session-1", previewId = "preview-1", inFlightKey = slot.inFlightKey)

        assertEquals("preview-1", removed?.snapshot?.previewId)
        assertNull(cache.get("session-1", "preview-1"))
        tracker.reserve("session-1", "preview-1", fallbackScreen = fallbackScreen())
    }

    private fun record(sessionId: String, previewId: String): PreviewRecord = PreviewRecord(
        sessionId = sessionId,
        projectRoot = "/repo",
        snapshot = FeedbackPreviewSnapshot(previewId = previewId, screen = fallbackScreen()),
        sourceIndex = null,
    )

    private fun fallbackScreen(): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "MainActivity",
    )
}
```

- [ ] **Step 2: Run the tracker test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*PreviewSaveReservationTrackerTest" --no-daemon
```

Expected: FAIL with unresolved `PreviewSaveReservationTracker`.

- [ ] **Step 3: Add tracker implementation**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTracker.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

internal data class PreviewSaveSlot(
    val inFlightKey: String,
    val cachedPreview: PreviewRecord?,
)

internal class PreviewSaveReservationTracker(
    private val previewCache: PreviewSnapshotCache,
) {
    private val lock = Any()
    private val previewSavesInFlight = mutableSetOf<String>()

    fun reserve(
        sessionId: String,
        previewId: String,
        fallbackScreen: SnapshotDto?,
    ): PreviewSaveSlot {
        val inFlightKey = "$sessionId:$previewId"
        return synchronized(lock) {
            val record = previewCache.get(sessionId, previewId)
            if (record == null && fallbackScreen == null) {
                throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
            }
            if (!previewSavesInFlight.add(inFlightKey)) {
                throw FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: $previewId")
            }
            PreviewSaveSlot(inFlightKey = inFlightKey, cachedPreview = record)
        }
    }

    fun release(inFlightKey: String) {
        synchronized(lock) {
            previewSavesInFlight.remove(inFlightKey)
        }
    }

    fun complete(sessionId: String, previewId: String, inFlightKey: String): PreviewRecord? = synchronized(lock) {
        previewSavesInFlight.remove(inFlightKey)
        previewCache.remove(sessionId, previewId)
    }
}
```

- [ ] **Step 4: Wire `FeedbackDraftService` through the tracker**

In `FeedbackDraftService`, replace:

```kotlin
    private val lock = Any()
    private val previewSavesInFlight = mutableSetOf<String>()
```

with:

```kotlin
    private val previewSaveReservations = PreviewSaveReservationTracker(previewCache)
```

Delete the private `PreviewSaveSlot` data class from `FeedbackDraftService.kt`; it now lives in `PreviewSaveReservationTracker.kt`.

Replace `reservePreviewSave(...)` body with:

```kotlin
    private fun reservePreviewSave(
        sessionId: String,
        previewId: String,
        fallbackScreen: SnapshotDto?,
    ): PreviewSaveSlot = previewSaveReservations.reserve(sessionId, previewId, fallbackScreen)
```

In `commitPreviewFeedbackSaveWithMetadata`, replace the synchronized removal block:

```kotlin
        val removedPreview = synchronized(lock) {
            previewSavesInFlight.remove(reservation.inFlightKey)
            previewCache.remove(reservation.sessionId, reservation.previewId)
        }
```

with:

```kotlin
        val removedPreview = previewSaveReservations.complete(
            sessionId = reservation.sessionId,
            previewId = reservation.previewId,
            inFlightKey = reservation.inFlightKey,
        )
```

Replace `releasePreviewSaveReservation` with:

```kotlin
    private fun releasePreviewSaveReservation(inFlightKey: String) {
        previewSaveReservations.release(inFlightKey)
    }
```

- [ ] **Step 5: Add reservation-release characterization test**

Append this test to `FeedbackDraftServiceTest`:

```kotlin
    @Test
    fun invalidPreviewSaveReleasesReservationSoRetryCanSucceed() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-reservation-release-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        assertFailsWith<IllegalArgumentException> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.NODE,
                        nodeUid = "missing-node",
                        bounds = FixThisRect(1f, 1f, 10f, 10f),
                        comment = "Invalid node",
                    ),
                ),
                allowBlankComments = false,
            )
        }

        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Retry succeeds",
                ),
            ),
            allowBlankComments = false,
        )

        assertEquals(listOf("Retry succeeds"), updated.items.map { it.comment })
    }
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*PreviewSaveReservationTrackerTest" --tests "*FeedbackDraftServiceTest.invalidPreviewSaveReleasesReservationSoRetryCanSucceed" --tests "*FeedbackDraftServiceTest.previewPromotionHappensOncePerFrozenPreviewSave" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTracker.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/PreviewSaveReservationTrackerTest.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt
git commit -m "refactor(session): extract preview save reservations"
```

## Task 3: Move Target Evidence Assembly Into Core

**Files:**
- Create: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactoryTest.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactory.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`

- [ ] **Step 1: Write core factory tests**

Create `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactoryTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetEvidenceFactoryTest {
    @Test
    fun nodeTargetIncludesStrictIdentityAndOccurrence() {
        val selected = node(uid = "primary", text = listOf("Pay"), testTag = "comp:CheckoutButton:primary")

        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.NODE,
                selectedNode = selected,
                mergedNodes = listOf(selected),
                sourceCandidates = emptyList(),
                screenshotKinds = listOf("full"),
            ),
        )

        assertEquals("CheckoutButton", evidence.identityHint?.composableNameHint)
        assertEquals("primary", evidence.identityHint?.variantHint)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, evidence.identityHint?.source)
        assertEquals(IdentityHintConfidence.HIGH, evidence.identityHint?.confidence)
        assertEquals(EvidenceQuality.STRUCTURED, evidence.evidenceQuality)
        assertEquals(listOf("full"), evidence.screenshotKinds)
        assertEquals(1, evidence.occurrence?.count)
    }

    @Test
    fun areaTargetAddsVisualAreaWarningAndBasicQualityWithoutStructure() {
        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.AREA,
                selectedNode = null,
                mergedNodes = emptyList(),
                sourceCandidates = emptyList(),
            ),
        )

        assertEquals(EvidenceQuality.BASIC, evidence.evidenceQuality)
        assertTrue(evidence.warnings.contains("Occurrence is not applicable for visual area selections."))
        assertEquals("No source candidate was available from current evidence.", evidence.sourceInterpretation?.caution)
    }

    @Test
    fun sourceInterpretationIsBuiltFromCandidates() {
        val selected = node(uid = "title", text = listOf("Title"))
        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.NODE,
                selectedNode = selected,
                mergedNodes = listOf(selected),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/Title.kt",
                        line = 12,
                        score = 0.2,
                        matchedTerms = listOf("Title"),
                        matchReasons = listOf("selected text"),
                        confidence = SelectionConfidence.LOW,
                    ),
                ),
            ),
        )

        assertEquals(listOf("selected text"), evidence.sourceInterpretation?.reasonSummary)
        assertEquals(SelectionConfidence.LOW, evidence.sourceInterpretation?.topCandidate?.confidence)
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 120f, 80f),
        text = text,
        testTag = testTag,
    )
}
```

- [ ] **Step 2: Run core test and verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetEvidenceFactoryTest" --no-daemon
```

Expected: FAIL with unresolved `TargetEvidenceFactory` and `TargetEvidenceInput`.

- [ ] **Step 3: Add core factory**

Create `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactory.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.github.beyondwin.fixthis.compose.core.identity.OccurrenceCalculator
import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.source.SourceInterpretationFactory

data class TargetEvidenceInput(
    val targetKind: TargetKind,
    val selectedNode: FixThisNode?,
    val mergedNodes: List<FixThisNode>,
    val sourceCandidates: List<SourceCandidate>,
    val screenshotKinds: List<String> = emptyList(),
)

object TargetEvidenceFactory {
    fun build(input: TargetEvidenceInput): TargetEvidence {
        val identityHint = IdentityHintFactory.from(input.selectedNode)
        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = input.selectedNode,
            nodes = input.mergedNodes,
            identityHint = identityHint,
        )
        return TargetEvidence(
            identityHint = identityHint,
            occurrence = occurrence,
            sourceInterpretation = SourceInterpretationFactory.from(input.sourceCandidates),
            evidenceQuality = if (identityHint != null || occurrence != null || input.sourceCandidates.isNotEmpty()) {
                EvidenceQuality.STRUCTURED
            } else {
                EvidenceQuality.BASIC
            },
            screenshotKinds = input.screenshotKinds,
            warnings = buildWarnings(input),
        )
    }

    private fun buildWarnings(input: TargetEvidenceInput): List<String> = buildList {
        if (input.targetKind == TargetKind.AREA) {
            add("Occurrence is not applicable for visual area selections.")
        }
        if (input.targetKind == TargetKind.NODE && input.selectedNode == null) {
            add("No selected semantics node was available for target evidence.")
        }
    }
}
```

- [ ] **Step 4: Delegate `TargetEvidenceService.targetEvidenceFor` to core**

In `TargetEvidenceService.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.target.TargetEvidenceFactory
import io.github.beyondwin.fixthis.compose.core.target.TargetEvidenceInput
```

Remove imports that are no longer used after delegation:

```kotlin
import io.github.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.github.beyondwin.fixthis.compose.core.identity.OccurrenceCalculator
import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.source.SourceInterpretationFactory
```

Replace the body of `targetEvidenceFor(...)` with:

```kotlin
        return TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = when (targetType) {
                    FeedbackTargetType.AREA -> TargetKind.AREA
                    FeedbackTargetType.NODE -> TargetKind.NODE
                },
                selectedNode = selectedNode,
                mergedNodes = screen.roots.flatMap { root -> root.mergedNodes },
                sourceCandidates = sourceCandidates,
                screenshotKinds = screen.screenshot.availableKinds(),
            ),
        )
```

- [ ] **Step 5: Run core and MCP target evidence tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetEvidenceFactoryTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --no-daemon
```

Expected: PASS. Existing `TargetEvidenceServiceTest` assertions still hold because external target evidence shape is unchanged.

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactory.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetEvidenceFactoryTest.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt
git commit -m "refactor(core): move target evidence assembly policy"
```

## Task 4: Extract Feedback Target Validation From Target Evidence Service

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidatorTest.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidator.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`

- [ ] **Step 1: Write validator tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidatorTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeedbackTargetValidatorTest {
    private val validator = FeedbackTargetValidator()

    @Test
    fun rejectsBlankCommentWhenBlankCommentsAreNotAllowed() {
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validate(
                screen = screenWith(node("title", bounds = FixThisRect(10f, 10f, 100f, 60f), text = listOf("Title"))),
                targetType = FeedbackTargetType.AREA,
                bounds = FixThisRect(10f, 10f, 100f, 60f),
                nodeUid = null,
                comment = "",
                allowBlankComment = false,
            )
        }

        assertEquals("Feedback comment must not be blank", error.message)
    }

    @Test
    fun reportsMissingNodeWithProvidedContext() {
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validate(
                screen = screenWith(),
                targetType = FeedbackTargetType.NODE,
                bounds = FixThisRect(10f, 10f, 100f, 60f),
                nodeUid = "missing",
                comment = "Fix label",
                allowBlankComment = false,
                missingNodeContext = "preview",
            )
        }

        assertEquals("Selected node does not exist on preview: missing", error.message)
    }

    @Test
    fun nodeTargetUsesSelectedNodeBoundsAndNearbyEvidence() {
        val selected = node("selected", bounds = FixThisRect(10f, 10f, 100f, 60f), text = listOf("Save"))
        val nearby = node("nearby", bounds = FixThisRect(120f, 10f, 220f, 60f), text = listOf("Settings"))

        val target = validator.validate(
            screen = screenWith(selected, nearby),
            targetType = FeedbackTargetType.NODE,
            bounds = FixThisRect(0f, 0f, 1f, 1f),
            nodeUid = "selected",
            comment = "Fix save",
            allowBlankComment = false,
        )

        assertEquals(selected, target.selectedNode)
        assertEquals(selected.boundsInWindow, target.storedBounds)
        assertEquals(listOf(nearby), target.evidenceNodes)
    }

    @Test
    fun areaTargetPrefersOverlappingEvidenceThenNearestFallback() {
        val overlap = node("overlap", bounds = FixThisRect(20f, 20f, 120f, 120f), text = listOf("Overlap"))
        val near = node("near", bounds = FixThisRect(230f, 230f, 280f, 280f), text = listOf("Near"))

        val overlappingTarget = validator.validate(
            screen = screenWith(near, overlap),
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(30f, 30f, 90f, 90f),
            nodeUid = null,
            comment = "Fix area",
            allowBlankComment = false,
        )

        assertEquals(listOf(overlap), overlappingTarget.evidenceNodes)

        val fallbackTarget = validator.validate(
            screen = screenWith(near, overlap),
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(180f, 180f, 220f, 220f),
            nodeUid = null,
            comment = "Fix empty area",
            allowBlankComment = false,
        )

        assertEquals(listOf(near, overlap), fallbackTarget.evidenceNodes.take(2))
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "Checkout",
        screenshot = SnapshotScreenshotDto(width = 400, height = 400, desktopFullPath = "/tmp/screen.png"),
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 400f), mergedNodes = nodes.toList())),
    )

    private fun node(
        uid: String,
        bounds: FixThisRect,
        text: List<String> = emptyList(),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
    )
}
```

- [ ] **Step 2: Run validator tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackTargetValidatorTest" --no-daemon
```

Expected: FAIL with unresolved `FeedbackTargetValidator`.

- [ ] **Step 3: Add validator implementation**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidator.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType

internal data class ValidatedFeedbackTarget(
    val targetType: FeedbackTargetType,
    val selectedNode: FixThisNode?,
    val storedBounds: FixThisRect,
    val evidenceNodes: List<FixThisNode>,
)

internal class FeedbackTargetValidator {
    fun validate(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
        allowBlankComment: Boolean,
        missingNodeContext: String = "screen",
    ): ValidatedFeedbackTarget {
        if (!allowBlankComment) {
            require(comment.isNotBlank()) { "Feedback comment must not be blank" }
        }
        val selectedNode = selectedNodeFor(screen, targetType, nodeUid, missingNodeContext)
        val storedBounds = selectedNode?.boundsInWindow ?: bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(screen, storedBounds)
        return ValidatedFeedbackTarget(
            targetType = targetType,
            selectedNode = selectedNode,
            storedBounds = storedBounds,
            evidenceNodes = evidenceNodesFor(screen, targetType, storedBounds, selectedNode),
        )
    }

    private fun selectedNodeFor(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        nodeUid: String?,
        missingNodeContext: String,
    ): FixThisNode? = when (targetType) {
        FeedbackTargetType.AREA -> null
        FeedbackTargetType.NODE -> {
            val uid = nodeUid?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Node feedback requires nodeUid")
            screen.allNodes().firstOrNull { node -> node.uid == uid }
                ?: throw IllegalArgumentException("Selected node does not exist on $missingNodeContext: $uid")
        }
    }

    private fun evidenceNodesFor(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        storedBounds: FixThisRect,
        selectedNode: FixThisNode?,
    ): List<FixThisNode> = when (targetType) {
        FeedbackTargetType.AREA -> areaEvidenceNodes(screen, storedBounds)
        FeedbackTargetType.NODE -> nodeEvidenceNodes(
            screen,
            requireNotNull(selectedNode) {
                "evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"
            },
        )
    }

    private fun validateFinitePositiveBounds(bounds: FixThisRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) {
            "Selection bounds must have positive size"
        }
    }

    private fun validateBoundsInsideScreenshot(screen: SnapshotDto, bounds: FixThisRect) {
        val width = screen.screenshot?.width?.toFloat() ?: return
        val height = screen.screenshot?.height?.toFloat() ?: return
        require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
            "Selection bounds must be inside the screenshot"
        }
    }

    private fun areaEvidenceNodes(screen: SnapshotDto, bounds: FixThisRect): List<FixThisNode> {
        val evidenceNodes = screen.allNodes()
            .asSequence()
            .filter { it.hasMeaningfulSemantic() }
            .map { node ->
                AreaEvidenceNode(
                    node = node,
                    overlaps = node.boundsInWindow.intersects(bounds),
                    overlapArea = node.boundsInWindow.intersectionArea(bounds),
                    centerDistance = node.boundsInWindow.centerDistanceTo(bounds),
                )
            }
            .toList()
        val hasOverlappingEvidence = evidenceNodes.any { it.overlaps }
        return evidenceNodes
            .asSequence()
            .filter { evidence -> if (hasOverlappingEvidence) evidence.overlaps else true }
            .sortedWith(
                compareByDescending<AreaEvidenceNode> { it.overlaps }
                    .thenByDescending { it.overlapArea }
                    .thenBy { it.centerDistance }
                    .thenBy { it.node.boundsInWindow.area }
                    .thenBy { it.node.uid },
            )
            .map { it.node }
            .distinctBy { it.uid }
            .take(MaxEvidenceNodes)
            .toList()
    }

    private fun nodeEvidenceNodes(screen: SnapshotDto, selectedNode: FixThisNode): List<FixThisNode> = screen.allNodes()
        .asSequence()
        .filter { it.uid != selectedNode.uid }
        .filter { it.rootIndex == selectedNode.rootIndex }
        .filter { it.hasMeaningfulSemantic() }
        .sortedWith(
            compareBy<FixThisNode> { it.boundsInWindow.centerDistanceTo(selectedNode.boundsInWindow) }
                .thenBy { it.boundsInWindow.area }
                .thenBy { it.uid },
        )
        .distinctBy { it.uid }
        .take(MaxEvidenceNodes)
        .toList()

    private data class AreaEvidenceNode(
        val node: FixThisNode,
        val overlaps: Boolean,
        val overlapArea: Float,
        val centerDistance: Float,
    )

    private companion object {
        const val MaxEvidenceNodes = 8
    }
}

private fun SnapshotDto.allNodes(): List<FixThisNode> = roots.flatMap { root -> root.mergedNodes + root.unmergedNodes }

private fun FixThisRect.intersects(other: FixThisRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun FixThisRect.intersectionArea(other: FixThisRect): Float {
    val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    return width * height
}

private fun FixThisRect.centerDistanceTo(other: FixThisRect): Float {
    val dx = ((left + right) / 2f) - ((other.left + other.right) / 2f)
    val dy = ((top + bottom) / 2f) - ((other.top + other.bottom) / 2f)
    return dx * dx + dy * dy
}
```

- [ ] **Step 4: Delegate `TargetEvidenceService.validateFeedbackTarget`**

In `TargetEvidenceService`, add a constructor dependency:

```kotlin
    private val targetValidator: FeedbackTargetValidator = FeedbackTargetValidator(),
```

Replace `validateFeedbackTarget(...)` body with:

```kotlin
        return targetValidator.validate(
            screen = screen,
            targetType = targetType,
            bounds = bounds,
            nodeUid = nodeUid,
            comment = comment,
            allowBlankComment = allowBlankComment,
            missingNodeContext = missingNodeContext,
        )
```

Delete these now-duplicated members from `TargetEvidenceService.kt`:

- `selectedNodeFor`
- `evidenceNodesFor`
- `validateFinitePositiveBounds`
- `validateBoundsInsideScreenshot`
- `areaEvidenceNodes`
- `nodeEvidenceNodes`
- private `FixThisRect.intersects`
- private `FixThisRect.intersectionArea`
- private `FixThisRect.centerDistanceTo`
- private `AreaEvidenceNode`
- nested `TargetEvidenceService.ValidatedFeedbackTarget`
- `MaxEvidenceNodes` if it is no longer used

In `FeedbackDraftService.addAreaFeedback`, replace `TargetEvidenceService.ValidatedFeedbackTarget(...)` with `ValidatedFeedbackTarget(...)`.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackTargetValidatorTest" --tests "*TargetEvidenceServiceTest" --tests "*FeedbackDraftServiceTest" --no-daemon
```

Expected: PASS. Error messages for missing preview/screen nodes and invalid bounds remain unchanged.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidator.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidatorTest.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt
git commit -m "refactor(session): extract feedback target validation"
```

## Task 5: Tighten Architecture Governance

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`

- [ ] **Step 1: Add focused core target-policy boundary test**

In `ModuleBoundaryTest.kt`, add this test below `composeCoreDoesNotImportOuterModulesOrAndroid()`:

```kotlin
    @Test
    fun composeCoreTargetPoliciesDoNotImportMcpSessionDtos() {
        val forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.""")
        val offenders = kotlinFiles("fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }
```

- [ ] **Step 2: Run boundary test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ModuleBoundaryTest" --no-daemon
```

Expected: PASS. If this fails, remove the forbidden import from core instead of weakening the rule.

- [ ] **Step 3: Ratchet hotspot budgets after extraction**

Run:

```bash
wc -l fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt
```

In `ArchitectureHotspotBudgetTest.kt`, lower only these two budgets:

```kotlin
            "${mcpMain}session/FeedbackDraftService.kt" to 430,
            "${mcpMain}session/TargetEvidenceService.kt" to 320,
```

- [ ] **Step 4: Run architecture tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ModuleBoundaryTest" --tests "*ArchitectureHotspotBudgetTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test(architecture): ratchet clean architecture boundaries"
```

## Task 6: Update Architecture Documentation

**Files:**
- Modify: `docs/architecture/overview.md`
- Modify: `docs/superpowers/specs/2026-05-27-fixthis-clean-architecture-hardening-design.md`

- [ ] **Step 1: Update architecture overview**

In `docs/architecture/overview.md`, update the `:fixthis-compose-core` bullet list to mention `TargetEvidenceFactory` under `target`:

```markdown
- `target/TargetReliabilityCalculator.kt`, `target/TargetEvidenceFactory.kt`:
  pure target confidence, warning, evidence-quality, identity, occurrence, and
  source-interpretation policy used by outer session adapters.
```

In the `:fixthis-mcp` section, update the session bullets to include:

```markdown
- `session/FeedbackTargetValidator.kt` and
  `session/PreviewFingerprintPolicy.kt`: keep target validation and preview
  fingerprint decisions focused and testable while `FeedbackDraftService`
  coordinates persistence.
```

- [ ] **Step 2: Mark the design spec implemented**

In `docs/superpowers/specs/2026-05-27-fixthis-clean-architecture-hardening-design.md`, change:

```markdown
Status: Ready for user review
```

to:

```markdown
Status: Implemented
```

Add this short implementation note after the `Summary` section:

```markdown
Implementation note: the first implementation pass extracted preview
fingerprint policy, preview save reservation tracking, core target evidence
assembly, MCP target validation, and architecture drift checks while preserving
external contracts.
```

- [ ] **Step 3: Run doc diff checks**

Run:

```bash
git diff --check -- docs/architecture/overview.md docs/superpowers/specs/2026-05-27-fixthis-clean-architecture-hardening-design.md
```

Expected: PASS with no output.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/overview.md docs/superpowers/specs/2026-05-27-fixthis-clean-architecture-hardening-design.md
git commit -m "docs: document clean architecture hardening"
```

## Task 7: Final Verification And Graph Refresh

**Files:**
- Generated local graph changes under `graphify-out/` are expected locally but must not be committed.

- [ ] **Step 1: Run focused Kotlin verification**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetEvidenceFactoryTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-mcp:test \
  --tests "*PreviewFingerprintPolicyTest" \
  --tests "*PreviewSaveReservationTrackerTest" \
  --tests "*FeedbackTargetValidatorTest" \
  --tests "*FeedbackDraftServiceTest" \
  --tests "*FeedbackDraftServiceMismatchTest" \
  --tests "*TargetEvidenceServiceTest" \
  --tests "*ModuleBoundaryTest" \
  --tests "*ArchitectureHotspotBudgetTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run source trust fixture contracts**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS. This is required because target evidence and reliability policy seams changed.

- [ ] **Step 3: Run full diff whitespace check**

Run:

```bash
git diff --check
```

Expected: PASS with no output.

- [ ] **Step 4: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: exits 0. Do not stage `graphify-out/` unless the user explicitly changes the repo's artifact policy.

- [ ] **Step 5: Confirm commit hygiene**

Run:

```bash
git status --short
```

Expected: only intentional tracked changes remain. If `graphify-out/` appears dirty, leave it unstaged. If generated build output or `.fixthis/` appears, do not commit it.

## Final Acceptance Checklist

- [ ] `FeedbackSessionService` remains source-compatible for current route and MCP callers.
- [ ] `FeedbackDraftService` no longer owns fingerprint policy or reservation tracking internals.
- [ ] `TargetEvidenceService` delegates pure target evidence assembly to `:fixthis-compose-core`.
- [ ] `FeedbackTargetValidator` owns target validation and evidence-node selection.
- [ ] Core target policy files do not import MCP session DTOs.
- [ ] Persisted JSON field names remain unchanged.
- [ ] Focused Kotlin tests pass.
- [ ] `npm run source-matching:fixtures:test` passes.
- [ ] `git diff --check` passes.
- [ ] `graphify update .` has been run after code changes.
