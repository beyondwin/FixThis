package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceEvidenceStrength {
    STRONG,
    MEDIUM,
    WEAK,
}
