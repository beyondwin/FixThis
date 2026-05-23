package io.github.beyondwin.fixthis.compose.core.source

internal object SourceScoringPolicy {
    const val maxCandidates: Int = 5
    const val highConfidenceScore: Double = 100.0
    const val minPartialMatchLength: Int = 3
    const val selectedTestTagRankingTier: Int = 50
    const val selectedTextRankingTier: Int = 40
    const val nearbyContextRankingTier: Int = 20
    const val activityRankingTier: Int = 10

    fun rankingTier(matchReasons: List<SourceMatchReason>): Int {
        val reasons = matchReasons.toSet()
        return when {
            reasons.hasAny(
                SourceMatchReason.SELECTED_TEST_TAG,
                SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE,
                SourceMatchReason.SELECTED_OWNER_FUNCTION,
            ) -> selectedTestTagRankingTier
            reasons.hasAny(
                SourceMatchReason.SELECTED_TEXT,
                SourceMatchReason.SELECTED_CONTENT_DESCRIPTION,
                SourceMatchReason.SELECTED_STATE_DESCRIPTION,
                SourceMatchReason.SELECTED_STRING_RESOURCE,
                SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE,
                SourceMatchReason.SELECTED_ROLE,
            ) -> selectedTextRankingTier
            reasons.hasAny(
                SourceMatchReason.NEARBY_TEXT,
                SourceMatchReason.NEARBY_CONTENT_DESCRIPTION,
                SourceMatchReason.NEARBY_TEST_TAG,
                SourceMatchReason.NEARBY_ROLE,
            ) -> nearbyContextRankingTier
            SourceMatchReason.ACTIVITY in reasons -> activityRankingTier
            else -> 0
        }
    }

    fun bucketScore(reason: SourceMatchReason): Double = when (reason) {
        SourceMatchReason.SELECTED_TEXT -> SELECTED_TEXT_SCORE
        SourceMatchReason.SELECTED_CONTENT_DESCRIPTION -> SELECTED_CONTENT_DESCRIPTION_SCORE
        SourceMatchReason.SELECTED_STATE_DESCRIPTION -> SELECTED_STATE_DESCRIPTION_SCORE
        SourceMatchReason.SELECTED_TEST_TAG -> SELECTED_TEST_TAG_SCORE
        SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE -> SELECTED_TEST_TAG_CONVENTION_SCORE
        SourceMatchReason.SELECTED_OWNER_FUNCTION -> SELECTED_OWNER_FUNCTION_SCORE
        SourceMatchReason.SELECTED_ROLE -> SELECTED_ROLE_SCORE
        SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE -> SELECTED_RESOLVED_STRING_RESOURCE_SCORE
        SourceMatchReason.NEARBY_TEXT -> NEARBY_TEXT_SCORE
        SourceMatchReason.NEARBY_CONTENT_DESCRIPTION -> NEARBY_CONTENT_DESCRIPTION_SCORE
        SourceMatchReason.NEARBY_TEST_TAG -> NEARBY_TEST_TAG_SCORE
        SourceMatchReason.NEARBY_ROLE -> NEARBY_ROLE_SCORE
        SourceMatchReason.ACTIVITY -> ACTIVITY_SCORE
        SourceMatchReason.SELECTED_STRING_RESOURCE,
        SourceMatchReason.ARBITRARY_LITERAL,
        SourceMatchReason.UNTYPED_FALLBACK,
        -> 0.0
    }

    private const val SELECTED_TEXT_SCORE: Double = 45.0
    private const val SELECTED_CONTENT_DESCRIPTION_SCORE: Double = 40.0
    private const val SELECTED_STATE_DESCRIPTION_SCORE: Double = 38.0
    private const val SELECTED_TEST_TAG_SCORE: Double = 55.0
    private const val SELECTED_TEST_TAG_CONVENTION_SCORE: Double = 65.0
    private const val SELECTED_OWNER_FUNCTION_SCORE: Double = 64.0
    private const val SELECTED_ROLE_SCORE: Double = 25.0
    private const val SELECTED_RESOLVED_STRING_RESOURCE_SCORE: Double = 48.0
    private const val NEARBY_TEXT_SCORE: Double = 24.0
    private const val NEARBY_CONTENT_DESCRIPTION_SCORE: Double = 22.0
    private const val NEARBY_TEST_TAG_SCORE: Double = 18.0
    private const val NEARBY_ROLE_SCORE: Double = 8.0
    private const val ACTIVITY_SCORE: Double = 15.0

    private fun Set<SourceMatchReason>.hasAny(vararg reasons: SourceMatchReason): Boolean = reasons.any { it in this }
}
