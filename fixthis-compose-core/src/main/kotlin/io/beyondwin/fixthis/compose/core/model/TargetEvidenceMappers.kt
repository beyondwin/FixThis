package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.AnnotationEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.EvidenceQuality as DomainEvidenceQuality
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceSource
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceSignatureType as DomainOccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintSummary
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetConfidence as DomainTargetConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityAssessment
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityReason as DomainTargetReliabilityReason
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityWarning as DomainTargetReliabilityWarning

fun AnnotationEvidence.toTargetEvidence(): TargetEvidence = TargetEvidence(
    identityHint = identity?.toIdentityHint(),
    occurrence = occurrence?.toOccurrence(),
    sourceInterpretation = source?.toSourceInterpretation(),
    evidenceQuality = quality.toContractEvidenceQuality(),
    screenshotKinds = screenshotKinds,
    warnings = warnings,
)

fun TargetEvidence.toAnnotationEvidence(): AnnotationEvidence = AnnotationEvidence(
    identity = identityHint?.toIdentityEvidence(),
    occurrence = occurrence?.toOccurrenceEvidence(),
    source = sourceInterpretation?.toSourceEvidence(),
    quality = evidenceQuality.toDomainEvidenceQuality(),
    screenshotKinds = screenshotKinds,
    warnings = warnings,
)

fun TargetReliabilityAssessment.toTargetReliability(): TargetReliability = TargetReliability(
    confidence = confidence.toContractTargetConfidence(),
    reasons = reasons.map(DomainTargetReliabilityReason::toContractTargetReliabilityReason),
    warnings = warnings.map(DomainTargetReliabilityWarning::toContractTargetReliabilityWarning),
)

fun TargetReliability.toTargetReliabilityAssessment(): TargetReliabilityAssessment = TargetReliabilityAssessment(
    confidence = confidence.toDomainTargetConfidence(),
    reasons = reasons.map(TargetReliabilityReason::toDomainTargetReliabilityReason),
    warnings = warnings.map(TargetReliabilityWarning::toDomainTargetReliabilityWarning),
)

private fun IdentityEvidence.toIdentityHint(): IdentityHint = IdentityHint(
    composableNameHint = composableNameHint,
    variantHint = variantHint,
    stableLabel = stableLabel,
    source = when (source) {
        IdentityEvidenceSource.TEST_TAG_CONVENTION -> IdentityHintSource.TEST_TAG_CONVENTION
        IdentityEvidenceSource.SEMANTICS -> IdentityHintSource.SEMANTICS
        IdentityEvidenceSource.NONE -> IdentityHintSource.NONE
    },
    confidence = when (confidence) {
        IdentityEvidenceConfidence.HIGH -> IdentityHintConfidence.HIGH
        IdentityEvidenceConfidence.MEDIUM -> IdentityHintConfidence.MEDIUM
        IdentityEvidenceConfidence.LOW -> IdentityHintConfidence.LOW
    },
)

private fun IdentityHint.toIdentityEvidence(): IdentityEvidence = IdentityEvidence(
    composableNameHint = composableNameHint,
    variantHint = variantHint,
    stableLabel = stableLabel,
    source = when (source) {
        IdentityHintSource.TEST_TAG_CONVENTION -> IdentityEvidenceSource.TEST_TAG_CONVENTION
        IdentityHintSource.SEMANTICS -> IdentityEvidenceSource.SEMANTICS
        IdentityHintSource.NONE -> IdentityEvidenceSource.NONE
    },
    confidence = when (confidence) {
        IdentityHintConfidence.HIGH -> IdentityEvidenceConfidence.HIGH
        IdentityHintConfidence.MEDIUM -> IdentityEvidenceConfidence.MEDIUM
        IdentityHintConfidence.LOW -> IdentityEvidenceConfidence.LOW
    },
)

private fun OccurrenceEvidence.toOccurrence(): Occurrence = Occurrence(
    basis = basis,
    signature = OccurrenceSignature(
        type = when (signatureType) {
            DomainOccurrenceSignatureType.IDENTITY_HINT -> OccurrenceSignatureType.IDENTITY_HINT
            DomainOccurrenceSignatureType.TEST_TAG -> OccurrenceSignatureType.TEST_TAG
            DomainOccurrenceSignatureType.ROLE_PLUS_TEXT -> OccurrenceSignatureType.ROLE_PLUS_TEXT
            DomainOccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION ->
                OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION
        },
        value = signatureValue,
    ),
    count = count,
    selectedOrdinal = selectedOrdinal,
)

private fun Occurrence.toOccurrenceEvidence(): OccurrenceEvidence = OccurrenceEvidence(
    basis = basis,
    signatureType = when (signature.type) {
        OccurrenceSignatureType.IDENTITY_HINT -> DomainOccurrenceSignatureType.IDENTITY_HINT
        OccurrenceSignatureType.TEST_TAG -> DomainOccurrenceSignatureType.TEST_TAG
        OccurrenceSignatureType.ROLE_PLUS_TEXT -> DomainOccurrenceSignatureType.ROLE_PLUS_TEXT
        OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION ->
            DomainOccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION
    },
    signatureValue = signature.value,
    count = count,
    selectedOrdinal = selectedOrdinal,
)

private fun SourceEvidence.toSourceInterpretation(): SourceInterpretation = SourceInterpretation(
    topCandidate = topCandidate?.let {
        SourceCandidateSummary(
            file = it.file,
            line = it.line,
            confidence = it.confidence.toSelectionConfidence(),
        )
    },
    reasonSummary = reasonSummary,
    caution = caution,
)

private fun SourceInterpretation.toSourceEvidence(): SourceEvidence = SourceEvidence(
    topCandidate = topCandidate?.let {
        SourceHintSummary(
            file = it.file,
            line = it.line,
            confidence = it.confidence.toSourceHintConfidence(),
        )
    },
    reasonSummary = reasonSummary,
    caution = caution,
)

private fun SourceHintConfidence.toSelectionConfidence(): SelectionConfidence = when (this) {
    SourceHintConfidence.HIGH -> SelectionConfidence.HIGH
    SourceHintConfidence.MEDIUM -> SelectionConfidence.MEDIUM
    SourceHintConfidence.LOW -> SelectionConfidence.LOW
    SourceHintConfidence.NONE -> SelectionConfidence.NONE
}

private fun SelectionConfidence.toSourceHintConfidence(): SourceHintConfidence = when (this) {
    SelectionConfidence.HIGH -> SourceHintConfidence.HIGH
    SelectionConfidence.MEDIUM -> SourceHintConfidence.MEDIUM
    SelectionConfidence.LOW -> SourceHintConfidence.LOW
    SelectionConfidence.NONE -> SourceHintConfidence.NONE
}

private fun DomainEvidenceQuality.toContractEvidenceQuality(): EvidenceQuality = when (this) {
    DomainEvidenceQuality.BASIC -> EvidenceQuality.BASIC
    DomainEvidenceQuality.STRUCTURED -> EvidenceQuality.STRUCTURED
}

private fun EvidenceQuality.toDomainEvidenceQuality(): DomainEvidenceQuality = when (this) {
    EvidenceQuality.BASIC -> DomainEvidenceQuality.BASIC
    EvidenceQuality.STRUCTURED -> DomainEvidenceQuality.STRUCTURED
}

private fun DomainTargetConfidence.toContractTargetConfidence(): TargetConfidence = when (this) {
    DomainTargetConfidence.HIGH -> TargetConfidence.HIGH
    DomainTargetConfidence.MEDIUM -> TargetConfidence.MEDIUM
    DomainTargetConfidence.LOW -> TargetConfidence.LOW
    DomainTargetConfidence.UNKNOWN -> TargetConfidence.UNKNOWN
}

private fun TargetConfidence.toDomainTargetConfidence(): DomainTargetConfidence = when (this) {
    TargetConfidence.HIGH -> DomainTargetConfidence.HIGH
    TargetConfidence.MEDIUM -> DomainTargetConfidence.MEDIUM
    TargetConfidence.LOW -> DomainTargetConfidence.LOW
    TargetConfidence.UNKNOWN -> DomainTargetConfidence.UNKNOWN
}

private fun DomainTargetReliabilityReason.toContractTargetReliabilityReason(): TargetReliabilityReason = when (this) {
    DomainTargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY -> TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY
    DomainTargetReliabilityReason.MEANINGFUL_COMPOSE_NODE -> TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE
    DomainTargetReliabilityReason.STRONG_SOURCE_CANDIDATE -> TargetReliabilityReason.STRONG_SOURCE_CANDIDATE
    DomainTargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE -> TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE
    DomainTargetReliabilityReason.VISUAL_AREA_SELECTION -> TargetReliabilityReason.VISUAL_AREA_SELECTION
    DomainTargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE -> TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE
    DomainTargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE -> TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE
}

private fun TargetReliabilityReason.toDomainTargetReliabilityReason(): DomainTargetReliabilityReason = when (this) {
    TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY -> DomainTargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY
    TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE -> DomainTargetReliabilityReason.MEANINGFUL_COMPOSE_NODE
    TargetReliabilityReason.STRONG_SOURCE_CANDIDATE -> DomainTargetReliabilityReason.STRONG_SOURCE_CANDIDATE
    TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE -> DomainTargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE
    TargetReliabilityReason.VISUAL_AREA_SELECTION -> DomainTargetReliabilityReason.VISUAL_AREA_SELECTION
    TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE -> DomainTargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE
    TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE -> DomainTargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE
}

private fun DomainTargetReliabilityWarning.toContractTargetReliabilityWarning(): TargetReliabilityWarning = when (this) {
    DomainTargetReliabilityWarning.VISUAL_AREA_ONLY -> TargetReliabilityWarning.VISUAL_AREA_ONLY
    DomainTargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET -> TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET
    DomainTargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP
    DomainTargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN
    DomainTargetReliabilityWarning.SOURCE_INDEX_STALE -> TargetReliabilityWarning.SOURCE_INDEX_STALE
    DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED ->
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED
    DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE
    DomainTargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED
}

private fun TargetReliabilityWarning.toDomainTargetReliabilityWarning(): DomainTargetReliabilityWarning = when (this) {
    TargetReliabilityWarning.VISUAL_AREA_ONLY -> DomainTargetReliabilityWarning.VISUAL_AREA_ONLY
    TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET -> DomainTargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET
    TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> DomainTargetReliabilityWarning.POSSIBLE_VIEW_INTEROP
    TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> DomainTargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN
    TargetReliabilityWarning.SOURCE_INDEX_STALE -> DomainTargetReliabilityWarning.SOURCE_INDEX_STALE
    TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED ->
        DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED
    TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> DomainTargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE
    TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> DomainTargetReliabilityWarning.SENSITIVE_TEXT_REDACTED
}
