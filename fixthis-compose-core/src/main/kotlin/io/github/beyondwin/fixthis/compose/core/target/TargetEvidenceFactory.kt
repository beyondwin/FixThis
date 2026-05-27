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
