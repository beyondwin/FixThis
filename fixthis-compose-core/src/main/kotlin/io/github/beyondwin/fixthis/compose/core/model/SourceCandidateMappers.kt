package io.github.beyondwin.fixthis.compose.core.model

import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHintLocation
import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHintRisk
import io.github.beyondwin.fixthis.compose.core.domain.evidence.SourceHintStrength

fun SourceHint.toSourceCandidate(): SourceCandidate = SourceCandidate(
    file = file,
    repoFile = repoFile,
    line = line,
    score = score,
    matchedTerms = matchedTerms,
    matchReasons = matchReasons,
    confidence = confidence.toSelectionConfidence(),
    ranking = ranking,
    scoreMargin = scoreMargin,
    evidenceStrength = evidenceStrength?.toSourceEvidenceStrength(),
    riskFlags = riskFlags.map(SourceHintRisk::toSourceCandidateRisk),
    caution = caution,
    stale = stale,
    staleReason = staleReason,
    ownerComposable = ownerComposable,
    callSites = callSites.map(SourceHintLocation::toSourceLocationRef),
)

fun SourceCandidate.toSourceHint(): SourceHint = SourceHint(
    file = file,
    repoFile = repoFile,
    line = line,
    score = score,
    matchedTerms = matchedTerms,
    matchReasons = matchReasons,
    confidence = confidence.toSourceHintConfidence(),
    ranking = ranking,
    scoreMargin = scoreMargin,
    evidenceStrength = evidenceStrength?.toSourceHintStrength(),
    riskFlags = riskFlags.map(SourceCandidateRisk::toSourceHintRisk),
    caution = caution,
    stale = stale,
    staleReason = staleReason,
    ownerComposable = ownerComposable,
    callSites = callSites.map(SourceLocationRef::toSourceHintLocation),
)

private fun SourceLocationRef.toSourceHintLocation(): SourceHintLocation = SourceHintLocation(file = file, line = line)

private fun SourceHintLocation.toSourceLocationRef(): SourceLocationRef = SourceLocationRef(file = file, line = line)

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

private fun SourceHintStrength.toSourceEvidenceStrength(): SourceEvidenceStrength = when (this) {
    SourceHintStrength.STRONG -> SourceEvidenceStrength.STRONG
    SourceHintStrength.MEDIUM -> SourceEvidenceStrength.MEDIUM
    SourceHintStrength.WEAK -> SourceEvidenceStrength.WEAK
}

private fun SourceEvidenceStrength.toSourceHintStrength(): SourceHintStrength = when (this) {
    SourceEvidenceStrength.STRONG -> SourceHintStrength.STRONG
    SourceEvidenceStrength.MEDIUM -> SourceHintStrength.MEDIUM
    SourceEvidenceStrength.WEAK -> SourceHintStrength.WEAK
}

private fun SourceHintRisk.toSourceCandidateRisk(): SourceCandidateRisk = when (this) {
    SourceHintRisk.AMBIGUOUS -> SourceCandidateRisk.AMBIGUOUS
    SourceHintRisk.SHARED_COMPONENT -> SourceCandidateRisk.SHARED_COMPONENT
    SourceHintRisk.TEXT_ONLY -> SourceCandidateRisk.TEXT_ONLY
    SourceHintRisk.NEARBY_ONLY -> SourceCandidateRisk.NEARBY_ONLY
    SourceHintRisk.ACTIVITY_ONLY -> SourceCandidateRisk.ACTIVITY_ONLY
    SourceHintRisk.ARBITRARY_LITERAL -> SourceCandidateRisk.ARBITRARY_LITERAL
    SourceHintRisk.AREA_SELECTION -> SourceCandidateRisk.AREA_SELECTION
    SourceHintRisk.UNTYPED_FALLBACK -> SourceCandidateRisk.UNTYPED_FALLBACK
}

private fun SourceCandidateRisk.toSourceHintRisk(): SourceHintRisk = when (this) {
    SourceCandidateRisk.AMBIGUOUS -> SourceHintRisk.AMBIGUOUS
    SourceCandidateRisk.SHARED_COMPONENT -> SourceHintRisk.SHARED_COMPONENT
    SourceCandidateRisk.TEXT_ONLY -> SourceHintRisk.TEXT_ONLY
    SourceCandidateRisk.NEARBY_ONLY -> SourceHintRisk.NEARBY_ONLY
    SourceCandidateRisk.ACTIVITY_ONLY -> SourceHintRisk.ACTIVITY_ONLY
    SourceCandidateRisk.ARBITRARY_LITERAL -> SourceHintRisk.ARBITRARY_LITERAL
    SourceCandidateRisk.AREA_SELECTION -> SourceHintRisk.AREA_SELECTION
    SourceCandidateRisk.UNTYPED_FALLBACK -> SourceHintRisk.UNTYPED_FALLBACK
}
