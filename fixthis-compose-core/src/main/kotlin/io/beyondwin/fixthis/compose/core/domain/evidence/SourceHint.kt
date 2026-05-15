package io.beyondwin.fixthis.compose.core.domain.evidence

enum class SourceHintConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE,
}

enum class SourceHintStrength {
    STRONG,
    MEDIUM,
    WEAK,
}

enum class SourceHintRisk {
    AMBIGUOUS,
    TEXT_ONLY,
    NEARBY_ONLY,
    ACTIVITY_ONLY,
    ARBITRARY_LITERAL,
    AREA_SELECTION,
    LEGACY_FALLBACK,
}

data class SourceHint(
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SourceHintConfidence,
    val ranking: Int? = null,
    val scoreMargin: Double? = null,
    val evidenceStrength: SourceHintStrength? = null,
    val riskFlags: List<SourceHintRisk> = emptyList(),
    val caution: String? = null,
    val stale: Boolean? = null,
    val staleReason: String? = null,
)
