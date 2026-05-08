package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
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
        val highest = SourceCandidateRiskPrecedence.highest(top.riskFlags)
        if (highest != null) {
            return when (highest) {
                SourceCandidateRisk.AMBIGUOUS ->
                    "Verify this source candidate before editing; top candidates are close."
                SourceCandidateRisk.AREA_SELECTION ->
                    "Visual-area selection; use screenshot and bounds before editing."
                SourceCandidateRisk.TEXT_ONLY ->
                    "Text-only match; confirm against screenshot and code."
                SourceCandidateRisk.NEARBY_ONLY ->
                    "Nearby-only match; confirm against screenshot and code."
                SourceCandidateRisk.ARBITRARY_LITERAL ->
                    "Match relied on a generic string literal; confirm against screenshot and code."
                SourceCandidateRisk.ACTIVITY_ONLY ->
                    "Activity-only match; confirm against screenshot and code."
                SourceCandidateRisk.LEGACY_FALLBACK ->
                    "Legacy-fallback match; confirm against screenshot and code."
            }
        }
        return when (top.confidence) {
            SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
            SelectionConfidence.NONE -> "No source candidate was available from current evidence."
            else -> null
        }
    }
}
