package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

internal object SourceRiskClassifier {
    data class Result(
        val confidence: SelectionConfidence,
        val flags: List<SourceCandidateRisk>,
    )

    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
        confidentCallSite: Boolean = false,
    ): Result {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence

        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isUntypedFallbackOnly -> {
                flags.add(SourceCandidateRisk.UNTYPED_FALLBACK)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.hasArbitraryLiteral && !profile.hasSelectedTestTag && !profile.hasStrictCompTag -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            if (!confidentCallSite) {
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        return Result(confidence, flags)
    }

    private fun capAt(
        current: SelectionConfidence,
        ceiling: SelectionConfidence,
    ): SelectionConfidence = if (current.ordinal < ceiling.ordinal) ceiling else current
}
