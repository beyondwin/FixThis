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
    ): Result {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence

        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isUntypedFallbackOnly -> {
                flags.add(SourceCandidateRisk.UNTYPED_FALLBACK)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.hasArbitraryLiteral && !profile.hasSelectedTestTag && !profile.hasStrictCompTag -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
            }
        }

        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
        }

        return Result(confidence, flags)
    }

    private const val NONE_CONFIDENCE_RANK = 0
    private const val LOW_CONFIDENCE_RANK = 1
    private const val MEDIUM_CONFIDENCE_RANK = 2
    private const val HIGH_CONFIDENCE_RANK = 3

    private val confidenceRank = mapOf(
        SelectionConfidence.NONE to NONE_CONFIDENCE_RANK,
        SelectionConfidence.LOW to LOW_CONFIDENCE_RANK,
        SelectionConfidence.MEDIUM to MEDIUM_CONFIDENCE_RANK,
        SelectionConfidence.HIGH to HIGH_CONFIDENCE_RANK,
    )

    private fun ceiling(
        current: SelectionConfidence,
        max: SelectionConfidence,
    ): SelectionConfidence {
        val currentRank = confidenceRank.getValue(current)
        val maxRank = confidenceRank.getValue(max)
        return if (currentRank <= maxRank) current else max
    }
}
