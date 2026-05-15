package io.github.beyondwin.fixthis.compose.core.model

import io.github.beyondwin.fixthis.compose.core.domain.evidence.AnnotationEvidence
import io.github.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityAssessment
import io.github.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityReason as DomainTargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityWarning as DomainTargetReliabilityWarning

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
