package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.AnnotationEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.EvidenceQuality
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.IdentityEvidenceSource
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintRisk
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintStrength
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintSummary
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetConfidence
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityAssessment
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityReason
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityWarning
import io.beyondwin.fixthis.compose.core.domain.snapshot.DomainError
import io.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsTreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainContractMappersTest {
    @Test
    fun rectAndSemanticsNodeRoundTripWithoutLoss() {
        val node = SemanticsNodeSnapshot(
            uid = "merged:1",
            composeNodeId = 42,
            rootIndex = 2,
            treeKind = SemanticsTreeKind.MERGED,
            boundsInWindow = DomainRect(1f, 2f, 30f, 44f),
            text = listOf("Save"),
            editableText = "Draft",
            contentDescription = listOf("Save button"),
            role = "Button",
            testTag = "primary-save",
            stateDescription = "Enabled",
            selected = false,
            enabled = true,
            actions = listOf("OnClick"),
            isPassword = false,
            isSensitive = true,
            path = listOf("root", "button"),
            rawProperties = mapOf("custom" to "value"),
        )

        val roundTrip = node.toFixThisNode().toDomainSemanticsNode()

        assertEquals(node, roundTrip)
        assertTrue(roundTrip.hasMeaningfulSemantic())
    }

    @Test
    fun sourceCandidateRoundTripPreservesCompatibilityFields() {
        val hint = SourceHint(
            file = "sample/src/main/kotlin/Foo.kt",
            line = 12,
            score = 0.82,
            matchedTerms = listOf("save", "button"),
            matchReasons = listOf("test tag"),
            confidence = SourceHintConfidence.MEDIUM,
            ranking = 1,
            scoreMargin = 0.08,
            evidenceStrength = SourceHintStrength.STRONG,
            riskFlags = listOf(SourceHintRisk.AMBIGUOUS, SourceHintRisk.TEXT_ONLY),
            caution = "ambiguous source match",
            stale = true,
            staleReason = "index older than source",
            ownerComposable = "FooScreen",
        )

        val roundTrip = hint.toSourceCandidate().toSourceHint()

        assertEquals(hint, roundTrip)
    }

    @Test
    fun targetEvidenceRoundTripUsesDomainNativeNames() {
        val evidence = AnnotationEvidence(
            identity = IdentityEvidence(
                composableNameHint = "PrimaryButton",
                variantHint = "danger",
                stableLabel = "Delete",
                source = IdentityEvidenceSource.TEST_TAG_CONVENTION,
                confidence = IdentityEvidenceConfidence.HIGH,
            ),
            occurrence = OccurrenceEvidence(
                signatureType = OccurrenceSignatureType.TEST_TAG,
                signatureValue = "delete-button",
                count = 3,
                selectedOrdinal = 2,
            ),
            source = SourceEvidence(
                topCandidate = SourceHintSummary(
                    file = "sample/src/main/kotlin/DeleteButton.kt",
                    line = 48,
                    confidence = SourceHintConfidence.HIGH,
                ),
                reasonSummary = listOf("stable test tag"),
                caution = "verify variant",
            ),
            quality = EvidenceQuality.STRUCTURED,
            screenshotKinds = listOf("full", "crop"),
            warnings = listOf("redacted"),
        )

        val roundTrip = evidence.toTargetEvidence().toAnnotationEvidence()

        assertEquals(evidence, roundTrip)
    }

    @Test
    fun targetReliabilityRoundTripUsesDomainNativeNames() {
        val assessment = TargetReliabilityAssessment(
            confidence = TargetConfidence.LOW,
            reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
            warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
        )

        val roundTrip = assessment.toTargetReliability().toTargetReliabilityAssessment()

        assertEquals(assessment, roundTrip)
    }

    @Test
    fun domainErrorRoundTripPreservesDetails() {
        val error = DomainError(
            code = "SCREENSHOT_FAILED",
            message = "Unable to capture screenshot",
            details = mapOf("reason" to "permission"),
        )

        assertEquals(error, error.toFixThisError().toDomainError())
    }
}
