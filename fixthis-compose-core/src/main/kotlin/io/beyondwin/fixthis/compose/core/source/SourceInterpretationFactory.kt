package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.SourceInterpretation

object SourceInterpretationFactory {
    fun from(sourceCandidates: List<SourceCandidate>): SourceInterpretation {
        val top = sourceCandidates.firstOrNull()
            ?: return SourceInterpretation(caution = "No source candidate was available from current evidence.")

        return SourceInterpretation(
            topCandidate = SourceCandidateSummary(
                file = top.file,
                line = top.line,
                confidence = top.confidence
            ),
            reasonSummary = top.matchReasons.take(5),
            caution = when (top.confidence) {
                SelectionConfidence.LOW,
                SelectionConfidence.NONE -> "Top source candidate has low confidence; verify before editing."
                else -> null
            }
        )
    }
}
