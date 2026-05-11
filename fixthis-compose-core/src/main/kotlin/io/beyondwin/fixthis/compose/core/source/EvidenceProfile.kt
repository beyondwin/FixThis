package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength

internal data class EvidenceProfile(
    val rawScore: Double,
    val reasons: Set<String>,
) {
    val hasStrictCompTag: Boolean = "selected testTag convention composable" in reasons
    val hasSelectedTestTag: Boolean = "selected testTag" in reasons
    val hasSelectedUiText: Boolean = "selected text" in reasons
    val hasSelectedContentDescription: Boolean = "selected contentDescription" in reasons
    val hasSelectedRole: Boolean = "selected role" in reasons
    val hasSelectedStringResource: Boolean = "selected stringResource" in reasons
    val hasArbitraryLiteral: Boolean = "arbitrary literal" in reasons
    val hasLegacyFallback: Boolean = "legacy fallback" in reasons
    val hasActivity: Boolean = "activity" in reasons
    val hasAnySelected: Boolean =
        hasSelectedTestTag ||
            hasStrictCompTag ||
            hasSelectedUiText ||
            hasSelectedContentDescription ||
            hasSelectedRole ||
            hasSelectedStringResource
    val hasAnyNearby: Boolean = reasons.any { it.startsWith("nearby ") }

    val isTextOnly: Boolean = reasons == setOf("selected text")
    val isNearbyOnly: Boolean = reasons.isNotEmpty() && reasons.all { it.startsWith("nearby ") }
    val isActivityOnly: Boolean = reasons == setOf("activity")
    val isArbitraryLiteralOnly: Boolean =
        "arbitrary literal" in reasons &&
            reasons.all { it in BUCKET_AND_ORIGIN_REASONS } &&
            !hasSelectedTestTag &&
            !hasStrictCompTag &&
            !hasSelectedStringResource &&
            !hasAnyNearby &&
            !hasActivity
    val isLegacyFallbackOnly: Boolean =
        "legacy fallback" in reasons &&
            reasons.all { it in BUCKET_AND_ORIGIN_REASONS } &&
            !hasSelectedTestTag &&
            !hasStrictCompTag &&
            !hasSelectedStringResource &&
            !hasAnyNearby &&
            !hasActivity

    val selectedStrongCount: Int = if (hasStrictCompTag || hasSelectedTestTag) 1 else 0
    val distinctSelectedMediumKinds: Int =
        (if (hasSelectedUiText) 1 else 0) +
            (if (hasSelectedContentDescription) 1 else 0) +
            (if (hasSelectedStringResource) 1 else 0) +
            (if (hasSelectedRole && hasAnySelected && reasons != setOf("selected role")) 1 else 0)

    fun strength(): SourceEvidenceStrength = when {
        selectedStrongCount > 0 -> SourceEvidenceStrength.STRONG
        distinctSelectedMediumKinds > 0 -> SourceEvidenceStrength.MEDIUM
        else -> SourceEvidenceStrength.WEAK
    }

    companion object {
        private val BUCKET_AND_ORIGIN_REASONS: Set<String> = setOf(
            "selected text",
            "selected contentDescription",
            "selected role",
            "arbitrary literal",
            "legacy fallback",
        )

        fun fromReasons(reasons: Collection<String>, rawScore: Double): EvidenceProfile = EvidenceProfile(rawScore = rawScore, reasons = reasons.toSet())
    }
}
