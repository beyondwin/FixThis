package io.beyondwin.fixthis.compose.core.domain.evidence

data class AnnotationEvidence(
    val identity: IdentityEvidence? = null,
    val occurrence: OccurrenceEvidence? = null,
    val source: SourceEvidence? = null,
    val quality: EvidenceQuality = EvidenceQuality.BASIC,
    val screenshotKinds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class IdentityEvidence(
    val composableNameHint: String? = null,
    val variantHint: String? = null,
    val stableLabel: String? = null,
    val source: IdentityEvidenceSource = IdentityEvidenceSource.NONE,
    val confidence: IdentityEvidenceConfidence = IdentityEvidenceConfidence.LOW,
)

enum class IdentityEvidenceSource {
    TEST_TAG_CONVENTION,
    SEMANTICS,
    NONE,
}

enum class IdentityEvidenceConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class EvidenceQuality {
    BASIC,
    STRUCTURED,
}

data class OccurrenceEvidence(
    val basis: String = "captured_merged_semantics_nodes",
    val signatureType: OccurrenceSignatureType,
    val signatureValue: String,
    val count: Int,
    val selectedOrdinal: Int,
)

enum class OccurrenceSignatureType {
    IDENTITY_HINT,
    TEST_TAG,
    ROLE_PLUS_TEXT,
    ROLE_PLUS_CONTENT_DESCRIPTION,
}

data class SourceEvidence(
    val topCandidate: SourceHintSummary? = null,
    val reasonSummary: List<String> = emptyList(),
    val caution: String? = null,
)

data class SourceHintSummary(
    val file: String,
    val line: Int? = null,
    val confidence: SourceHintConfidence,
)
