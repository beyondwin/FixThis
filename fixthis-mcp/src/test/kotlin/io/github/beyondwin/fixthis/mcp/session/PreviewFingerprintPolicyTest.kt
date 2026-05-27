package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
