package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength

internal data class EvidenceProfile(
    val rawScore: Double,
    val reasons: Set<SourceMatchReason>,
) {
    val hasStrictCompTag: Boolean = SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE in reasons
    val hasSelectedTestTag: Boolean = SourceMatchReason.SELECTED_TEST_TAG in reasons
    val hasSelectedUiText: Boolean = SourceMatchReason.SELECTED_TEXT in reasons
    val hasSelectedContentDescription: Boolean = SourceMatchReason.SELECTED_CONTENT_DESCRIPTION in reasons
    val hasSelectedRole: Boolean = SourceMatchReason.SELECTED_ROLE in reasons
    val hasSelectedStringResource: Boolean = SourceMatchReason.SELECTED_STRING_RESOURCE in reasons
    val hasSelectedResolvedStringResource: Boolean = SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE in reasons
    val hasArbitraryLiteral: Boolean = SourceMatchReason.ARBITRARY_LITERAL in reasons
    val hasLegacyFallback: Boolean = SourceMatchReason.LEGACY_FALLBACK in reasons
    val hasActivity: Boolean = SourceMatchReason.ACTIVITY in reasons
    val hasAnySelected: Boolean =
        hasSelectedTestTag ||
            hasStrictCompTag ||
            hasSelectedUiText ||
            hasSelectedContentDescription ||
            hasSelectedRole ||
            hasSelectedStringResource ||
            hasSelectedResolvedStringResource
    val hasAnyNearby: Boolean = reasons.any { it in NEARBY_REASONS }

    val isTextOnly: Boolean = reasons == setOf(SourceMatchReason.SELECTED_TEXT)
    val isNearbyOnly: Boolean = reasons.isNotEmpty() && reasons.all { it in NEARBY_REASONS }
    val isActivityOnly: Boolean = reasons == setOf(SourceMatchReason.ACTIVITY)
    val isArbitraryLiteralOnly: Boolean =
        SourceMatchReason.ARBITRARY_LITERAL in reasons &&
            reasons.all { it in BUCKET_AND_ORIGIN_REASONS } &&
            !hasSelectedTestTag &&
            !hasStrictCompTag &&
            !hasSelectedStringResource &&
            !hasSelectedResolvedStringResource &&
            !hasAnyNearby &&
            !hasActivity
    val isLegacyFallbackOnly: Boolean =
        SourceMatchReason.LEGACY_FALLBACK in reasons &&
            reasons.all { it in BUCKET_AND_ORIGIN_REASONS } &&
            !hasSelectedTestTag &&
            !hasStrictCompTag &&
            !hasSelectedStringResource &&
            !hasSelectedResolvedStringResource &&
            !hasAnyNearby &&
            !hasActivity

    val selectedStrongCount: Int = if (hasStrictCompTag || hasSelectedTestTag) 1 else 0
    val distinctSelectedMediumKinds: Int =
        (if (hasSelectedUiText) 1 else 0) +
            (if (hasSelectedContentDescription) 1 else 0) +
            (if (hasSelectedStringResource || hasSelectedResolvedStringResource) 1 else 0) +
            (if (hasSelectedRole && hasAnySelected && reasons != setOf(SourceMatchReason.SELECTED_ROLE)) 1 else 0)

    fun strength(): SourceEvidenceStrength = when {
        selectedStrongCount > 0 -> SourceEvidenceStrength.STRONG
        distinctSelectedMediumKinds > 0 -> SourceEvidenceStrength.MEDIUM
        else -> SourceEvidenceStrength.WEAK
    }

    companion object {
        private val NEARBY_REASONS: Set<SourceMatchReason> = setOf(
            SourceMatchReason.NEARBY_TEXT,
            SourceMatchReason.NEARBY_CONTENT_DESCRIPTION,
            SourceMatchReason.NEARBY_TEST_TAG,
            SourceMatchReason.NEARBY_ROLE,
        )

        private val BUCKET_AND_ORIGIN_REASONS: Set<SourceMatchReason> = setOf(
            SourceMatchReason.SELECTED_TEXT,
            SourceMatchReason.SELECTED_CONTENT_DESCRIPTION,
            SourceMatchReason.SELECTED_ROLE,
            SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE,
            SourceMatchReason.ARBITRARY_LITERAL,
            SourceMatchReason.LEGACY_FALLBACK,
        )

        fun fromMatchReasons(
            reasons: Collection<SourceMatchReason>,
            rawScore: Double,
        ): EvidenceProfile = EvidenceProfile(rawScore = rawScore, reasons = reasons.toSet())

        fun fromReasons(reasons: Collection<String>, rawScore: Double): EvidenceProfile {
            val matchReasons = reasons.map(SourceMatchReason::fromWireLabel)
            return fromMatchReasons(matchReasons, rawScore)
        }
    }
}
