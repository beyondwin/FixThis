package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintRisk
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintStrength
import io.beyondwin.fixthis.compose.core.domain.snapshot.DomainError
import io.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsTreeKind

fun DomainRect.toFixThisRect(): FixThisRect = FixThisRect(left, top, right, bottom)

fun FixThisRect.toDomainRect(): DomainRect = DomainRect(left, top, right, bottom)

fun SemanticsNodeSnapshot.toFixThisNode(): FixThisNode = FixThisNode(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        SemanticsTreeKind.MERGED -> TreeKind.MERGED
        SemanticsTreeKind.UNMERGED -> TreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toFixThisRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun FixThisNode.toDomainSemanticsNode(): SemanticsNodeSnapshot = SemanticsNodeSnapshot(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        TreeKind.MERGED -> SemanticsTreeKind.MERGED
        TreeKind.UNMERGED -> SemanticsTreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toDomainRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun SourceHint.toSourceCandidate(): SourceCandidate = SourceCandidate(
    file = file,
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
)

fun SourceCandidate.toSourceHint(): SourceHint = SourceHint(
    file = file,
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
)

fun DomainError.toFixThisError(): FixThisError = FixThisError(code, message, details)

fun FixThisError.toDomainError(): DomainError = DomainError(code, message, details)

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
    SourceHintRisk.TEXT_ONLY -> SourceCandidateRisk.TEXT_ONLY
    SourceHintRisk.NEARBY_ONLY -> SourceCandidateRisk.NEARBY_ONLY
    SourceHintRisk.ACTIVITY_ONLY -> SourceCandidateRisk.ACTIVITY_ONLY
    SourceHintRisk.ARBITRARY_LITERAL -> SourceCandidateRisk.ARBITRARY_LITERAL
    SourceHintRisk.AREA_SELECTION -> SourceCandidateRisk.AREA_SELECTION
    SourceHintRisk.LEGACY_FALLBACK -> SourceCandidateRisk.LEGACY_FALLBACK
}

private fun SourceCandidateRisk.toSourceHintRisk(): SourceHintRisk = when (this) {
    SourceCandidateRisk.AMBIGUOUS -> SourceHintRisk.AMBIGUOUS
    SourceCandidateRisk.TEXT_ONLY -> SourceHintRisk.TEXT_ONLY
    SourceCandidateRisk.NEARBY_ONLY -> SourceHintRisk.NEARBY_ONLY
    SourceCandidateRisk.ACTIVITY_ONLY -> SourceHintRisk.ACTIVITY_ONLY
    SourceCandidateRisk.ARBITRARY_LITERAL -> SourceHintRisk.ARBITRARY_LITERAL
    SourceCandidateRisk.AREA_SELECTION -> SourceHintRisk.AREA_SELECTION
    SourceCandidateRisk.LEGACY_FALLBACK -> SourceHintRisk.LEGACY_FALLBACK
}
