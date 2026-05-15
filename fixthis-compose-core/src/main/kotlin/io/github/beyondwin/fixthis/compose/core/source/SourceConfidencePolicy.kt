package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

internal object SourceConfidencePolicy {
    fun cautionFor(
        confidence: SelectionConfidence,
        flags: List<SourceCandidateRisk>,
    ): String? {
        val highest = SourceCandidateRiskPrecedence.highest(flags)
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
        return when (confidence) {
            SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
            SelectionConfidence.NONE -> "No source candidate was available from current evidence."
            else -> null
        }
    }
}
