package io.github.beyondwin.fixthis.mcp.session.preview

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.PreviewSaveFingerprintCheck
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
