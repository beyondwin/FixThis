package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.SourceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintSummary
import io.beyondwin.fixthis.compose.core.domain.evidence.EvidenceQuality as DomainEvidenceQuality

internal fun SourceEvidence.toSourceInterpretation(): SourceInterpretation = SourceInterpretation(
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

internal fun SourceInterpretation.toSourceEvidence(): SourceEvidence = SourceEvidence(
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

internal fun DomainEvidenceQuality.toContractEvidenceQuality(): EvidenceQuality = when (this) {
    DomainEvidenceQuality.BASIC -> EvidenceQuality.BASIC
    DomainEvidenceQuality.STRUCTURED -> EvidenceQuality.STRUCTURED
}

internal fun EvidenceQuality.toDomainEvidenceQuality(): DomainEvidenceQuality = when (this) {
    EvidenceQuality.BASIC -> DomainEvidenceQuality.BASIC
    EvidenceQuality.STRUCTURED -> DomainEvidenceQuality.STRUCTURED
}

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
