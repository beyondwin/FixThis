package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TargetEvidence(
    val identityHint: IdentityHint? = null,
    val occurrence: Occurrence? = null,
    val sourceInterpretation: SourceInterpretation? = null,
    val evidenceQuality: EvidenceQuality = EvidenceQuality.BASIC,
    val screenshotKinds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
enum class EvidenceQuality {
    BASIC,
    STRUCTURED,
}

@Serializable
data class IdentityHint(
    val composableNameHint: String? = null,
    val variantHint: String? = null,
    val stableLabel: String? = null,
    val source: IdentityHintSource = IdentityHintSource.NONE,
    val confidence: IdentityHintConfidence = IdentityHintConfidence.LOW,
)

@Serializable
enum class IdentityHintSource {
    TEST_TAG_CONVENTION,
    SEMANTICS,
    NONE,
}

@Serializable
enum class IdentityHintConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
data class Occurrence(
    val basis: String = "captured_merged_semantics_nodes",
    val signature: OccurrenceSignature,
    val count: Int,
    val selectedOrdinal: Int,
)

@Serializable
data class OccurrenceSignature(
    val type: OccurrenceSignatureType,
    val value: String,
)

@Serializable
enum class OccurrenceSignatureType {
    IDENTITY_HINT,
    TEST_TAG,
    ROLE_PLUS_TEXT,
    ROLE_PLUS_CONTENT_DESCRIPTION,
}

@Serializable
data class SourceInterpretation(
    val topCandidate: SourceCandidateSummary? = null,
    val reasonSummary: List<String> = emptyList(),
    val caution: String? = null,
)

@Serializable
data class SourceCandidateSummary(
    val file: String,
    val line: Int? = null,
    val confidence: SelectionConfidence,
)
