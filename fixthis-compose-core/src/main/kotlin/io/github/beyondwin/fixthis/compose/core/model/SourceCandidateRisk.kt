package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceCandidateRisk {
    AMBIGUOUS,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,

    @SerialName("LEGACY_FALLBACK")
    UNTYPED_FALLBACK,
}
