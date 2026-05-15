@file:Suppress("MaxLineLength")

package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.TargetConfidence as DomainTargetConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityReason as DomainTargetReliabilityReason
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityWarning as DomainTargetReliabilityWarning

internal fun DomainTargetConfidence.toContractTargetConfidence(): TargetConfidence = when (this) {
    DomainTargetConfidence.HIGH -> TargetConfidence.HIGH
    DomainTargetConfidence.MEDIUM -> TargetConfidence.MEDIUM
    DomainTargetConfidence.LOW -> TargetConfidence.LOW
    DomainTargetConfidence.UNKNOWN -> TargetConfidence.UNKNOWN
}

internal fun TargetConfidence.toDomainTargetConfidence(): DomainTargetConfidence = when (this) {
    TargetConfidence.HIGH -> DomainTargetConfidence.HIGH
    TargetConfidence.MEDIUM -> DomainTargetConfidence.MEDIUM
    TargetConfidence.LOW -> DomainTargetConfidence.LOW
    TargetConfidence.UNKNOWN -> DomainTargetConfidence.UNKNOWN
}

internal fun DomainTargetReliabilityReason.toContractTargetReliabilityReason(): TargetReliabilityReason = when (this) {
    DomainTargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY -> TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY
    DomainTargetReliabilityReason.MEANINGFUL_COMPOSE_NODE -> TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE
    DomainTargetReliabilityReason.STRONG_SOURCE_CANDIDATE -> TargetReliabilityReason.STRONG_SOURCE_CANDIDATE
    DomainTargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE -> TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE
    DomainTargetReliabilityReason.VISUAL_AREA_SELECTION -> TargetReliabilityReason.VISUAL_AREA_SELECTION
    DomainTargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE -> TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE
    DomainTargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE ->
        TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE
}

internal fun TargetReliabilityReason.toDomainTargetReliabilityReason(): DomainTargetReliabilityReason = when (this) {
    TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY -> DomainTargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY
    TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE -> DomainTargetReliabilityReason.MEANINGFUL_COMPOSE_NODE
    TargetReliabilityReason.STRONG_SOURCE_CANDIDATE -> DomainTargetReliabilityReason.STRONG_SOURCE_CANDIDATE
    TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE -> DomainTargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE
    TargetReliabilityReason.VISUAL_AREA_SELECTION -> DomainTargetReliabilityReason.VISUAL_AREA_SELECTION
    TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE -> DomainTargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE
    TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE ->
        DomainTargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE
}

internal fun DomainTargetReliabilityWarning.toContractTargetReliabilityWarning(): TargetReliabilityWarning = when (this) {
    DomainTargetReliabilityWarning.VISUAL_AREA_ONLY -> TargetReliabilityWarning.VISUAL_AREA_ONLY
    DomainTargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET ->
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET
    DomainTargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP
    DomainTargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN ->
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN
    DomainTargetReliabilityWarning.SOURCE_INDEX_STALE -> TargetReliabilityWarning.SOURCE_INDEX_STALE
    DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED ->
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED
    DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE ->
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE
    DomainTargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED
}

internal fun TargetReliabilityWarning.toDomainTargetReliabilityWarning(): DomainTargetReliabilityWarning = when (this) {
    TargetReliabilityWarning.VISUAL_AREA_ONLY -> DomainTargetReliabilityWarning.VISUAL_AREA_ONLY
    TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET ->
        DomainTargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET
    TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> DomainTargetReliabilityWarning.POSSIBLE_VIEW_INTEROP
    TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN ->
        DomainTargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN
    TargetReliabilityWarning.SOURCE_INDEX_STALE -> DomainTargetReliabilityWarning.SOURCE_INDEX_STALE
    TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED ->
        DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED
    TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE ->
        DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE
    TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> DomainTargetReliabilityWarning.SENSITIVE_TEXT_REDACTED
}
