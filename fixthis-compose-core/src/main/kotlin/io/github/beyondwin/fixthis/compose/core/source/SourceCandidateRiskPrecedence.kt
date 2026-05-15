package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

object SourceCandidateRiskPrecedence {
    val orderedHighestFirst: List<SourceCandidateRisk> = listOf(
        SourceCandidateRisk.AMBIGUOUS,
        SourceCandidateRisk.AREA_SELECTION,
        SourceCandidateRisk.TEXT_ONLY,
        SourceCandidateRisk.NEARBY_ONLY,
        SourceCandidateRisk.ARBITRARY_LITERAL,
        SourceCandidateRisk.ACTIVITY_ONLY,
        SourceCandidateRisk.LEGACY_FALLBACK,
    )

    fun highest(flags: Collection<SourceCandidateRisk>): SourceCandidateRisk? {
        if (flags.isEmpty()) return null
        val present = flags.toSet()
        return orderedHighestFirst.firstOrNull { it in present }
    }

    fun ordered(flags: Collection<SourceCandidateRisk>): List<SourceCandidateRisk> {
        if (flags.isEmpty()) return emptyList()
        val present = flags.toSet()
        return orderedHighestFirst.filter { it in present }
    }
}
