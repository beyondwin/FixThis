package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.SourceInterpretation

object SourceInterpretationFactory {
    fun from(sourceCandidates: List<SourceCandidate>): SourceInterpretation {
        val top = sourceCandidates.firstOrNull()
            ?: return SourceInterpretation(
                caution = "No source candidate was available from current evidence.",
            )

        return SourceInterpretation(
            topCandidate = SourceCandidateSummary(
                file = top.file,
                line = top.line,
                confidence = top.confidence,
            ),
            reasonSummary = top.matchReasons.take(5),
            caution = top.caution ?: defaultCaution(top),
        )
    }

    private fun defaultCaution(top: SourceCandidate): String? {
        val confidence = top.confidence
        return SourceConfidencePolicy.cautionFor(confidence, top.riskFlags)
    }
}
