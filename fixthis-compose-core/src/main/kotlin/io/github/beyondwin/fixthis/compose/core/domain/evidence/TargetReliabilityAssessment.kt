package io.github.beyondwin.fixthis.compose.core.domain.evidence

data class TargetReliabilityAssessment(
    val confidence: TargetConfidence = TargetConfidence.UNKNOWN,
    val reasons: List<TargetReliabilityReason> = emptyList(),
    val warnings: List<TargetReliabilityWarning> = emptyList(),
)

enum class TargetConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

enum class TargetReliabilityReason {
    STRICT_COMPOSABLE_IDENTITY,
    MEANINGFUL_COMPOSE_NODE,
    STRONG_SOURCE_CANDIDATE,
    MEDIUM_SOURCE_CANDIDATE,
    VISUAL_AREA_SELECTION,
    LEGACY_OR_MISSING_EVIDENCE,
    REDACTED_TEXT_REDUCED_EVIDENCE,
}

enum class TargetReliabilityWarning {
    VISUAL_AREA_ONLY,
    NO_MEANINGFUL_COMPOSE_TARGET,
    POSSIBLE_VIEW_INTEROP,
    LOW_SOURCE_CANDIDATE_MARGIN,
    SOURCE_INDEX_STALE,
    SCREEN_FINGERPRINT_MISMATCH_FORCED,
    SCREEN_FINGERPRINT_UNAVAILABLE,
    SENSITIVE_TEXT_REDACTED,
}
