package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceSource
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceSignatureType as DomainOccurrenceSignatureType

internal fun IdentityEvidence.toIdentityHint(): IdentityHint = IdentityHint(
    composableNameHint = composableNameHint,
    variantHint = variantHint,
    stableLabel = stableLabel,
    source = when (source) {
        IdentityEvidenceSource.TEST_TAG_CONVENTION -> IdentityHintSource.TEST_TAG_CONVENTION
        IdentityEvidenceSource.SEMANTICS -> IdentityHintSource.SEMANTICS
        IdentityEvidenceSource.NONE -> IdentityHintSource.NONE
    },
    confidence = when (confidence) {
        IdentityEvidenceConfidence.HIGH -> IdentityHintConfidence.HIGH
        IdentityEvidenceConfidence.MEDIUM -> IdentityHintConfidence.MEDIUM
        IdentityEvidenceConfidence.LOW -> IdentityHintConfidence.LOW
    },
)

internal fun IdentityHint.toIdentityEvidence(): IdentityEvidence = IdentityEvidence(
    composableNameHint = composableNameHint,
    variantHint = variantHint,
    stableLabel = stableLabel,
    source = when (source) {
        IdentityHintSource.TEST_TAG_CONVENTION -> IdentityEvidenceSource.TEST_TAG_CONVENTION
        IdentityHintSource.SEMANTICS -> IdentityEvidenceSource.SEMANTICS
        IdentityHintSource.NONE -> IdentityEvidenceSource.NONE
    },
    confidence = when (confidence) {
        IdentityHintConfidence.HIGH -> IdentityEvidenceConfidence.HIGH
        IdentityHintConfidence.MEDIUM -> IdentityEvidenceConfidence.MEDIUM
        IdentityHintConfidence.LOW -> IdentityEvidenceConfidence.LOW
    },
)

internal fun OccurrenceEvidence.toOccurrence(): Occurrence = Occurrence(
    basis = basis,
    signature = OccurrenceSignature(
        type = when (signatureType) {
            DomainOccurrenceSignatureType.IDENTITY_HINT -> OccurrenceSignatureType.IDENTITY_HINT
            DomainOccurrenceSignatureType.TEST_TAG -> OccurrenceSignatureType.TEST_TAG
            DomainOccurrenceSignatureType.ROLE_PLUS_TEXT -> OccurrenceSignatureType.ROLE_PLUS_TEXT
            DomainOccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION ->
                OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION
        },
        value = signatureValue,
    ),
    count = count,
    selectedOrdinal = selectedOrdinal,
)

internal fun Occurrence.toOccurrenceEvidence(): OccurrenceEvidence = OccurrenceEvidence(
    basis = basis,
    signatureType = when (signature.type) {
        OccurrenceSignatureType.IDENTITY_HINT -> DomainOccurrenceSignatureType.IDENTITY_HINT
        OccurrenceSignatureType.TEST_TAG -> DomainOccurrenceSignatureType.TEST_TAG
        OccurrenceSignatureType.ROLE_PLUS_TEXT -> DomainOccurrenceSignatureType.ROLE_PLUS_TEXT
        OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION ->
            DomainOccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION
    },
    signatureValue = signature.value,
    count = count,
    selectedOrdinal = selectedOrdinal,
)
