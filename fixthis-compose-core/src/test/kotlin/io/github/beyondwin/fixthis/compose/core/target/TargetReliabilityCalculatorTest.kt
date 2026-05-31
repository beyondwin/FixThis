package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHint
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReliabilityCalculatorTest {
    @Test
    fun highConfidenceForMeaningfulNodeWithStrictIdentityAndClearSource() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/HomeScreen.kt",
                        line = 42,
                        score = 0.92,
                        matchReasons = listOf("selected testTag convention composable"),
                        confidence = SelectionConfidence.HIGH,
                        scoreMargin = 0.41,
                    ),
                ),
                targetEvidence = TargetEvidence(
                    identityHint = IdentityHint(
                        composableNameHint = "HomeScreen",
                        source = IdentityHintSource.TEST_TAG_CONVENTION,
                        confidence = IdentityHintConfidence.HIGH,
                    ),
                    evidenceQuality = EvidenceQuality.STRUCTURED,
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.HIGH, result.confidence)
        assertTrue(result.reasons.contains(TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY))
        assertTrue(result.reasons.contains(TargetReliabilityReason.STRONG_SOURCE_CANDIDATE))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun lowConfidenceForVisualAreaWithNoMeaningfulComposeCoverage() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.AREA,
                selectedNode = null,
                nearbyNodes = emptyList(),
                sourceCandidates = emptyList(),
                semanticCoverage = TargetReliabilityCalculator.coverageFor(
                    roots = listOf(FixThisRect(0f, 0f, 400f, 800f)),
                    meaningfulNodes = emptyList(),
                    targetBounds = FixThisRect(32f, 120f, 260f, 220f),
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.VISUAL_AREA_ONLY))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))
    }

    @Test
    fun visualAreaWithNoSourceCandidatesWarnsPossibleViewInteropEvenWithComposeCoverage() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.AREA,
                selectedNode = null,
                nearbyNodes = emptyList(),
                sourceCandidates = emptyList(),
                semanticCoverage = TargetReliabilityCalculator.coverageFor(
                    roots = listOf(FixThisRect(0f, 0f, 400f, 800f)),
                    meaningfulNodes = listOf(
                        meaningfulNode().copy(boundsInWindow = FixThisRect(20f, 120f, 260f, 220f)),
                    ),
                    targetBounds = FixThisRect(32f, 140f, 240f, 200f),
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.VISUAL_AREA_ONLY))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))
    }

    @Test
    fun lowConfidenceWhenSourceCandidateIsStaleOrAmbiguous() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/QueueScreen.kt",
                        score = 0.51,
                        confidence = SelectionConfidence.MEDIUM,
                        scoreMargin = 0.03,
                        riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                        stale = true,
                        staleReason = "excerpt mismatch",
                    ),
                ),
                screenFingerprintAvailable = false,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.SOURCE_INDEX_STALE))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE))
    }

    @Test
    fun sensitiveNodeAddsWarningWithoutLeakingSensitiveText() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(
                    text = listOf("<redacted-password>"),
                    editableText = "<redacted-password>",
                    isSensitive = true,
                ),
                sourceCandidates = emptyList(),
            ),
        )

        assertTrue(result.warnings.contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED))
        assertTrue(result.reasons.none { it.name.contains("PASSWORD", ignoreCase = true) })
    }

    @Test
    fun mediumSourceCandidateWithWeakRiskDoesNotRaiseTargetAboveMedium() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/AdaptiveGrid.kt",
                        line = 38,
                        score = 0.72,
                        matchReasons = listOf("selected testTag convention composable"),
                        confidence = SelectionConfidence.MEDIUM,
                        scoreMargin = 0.18,
                        riskFlags = listOf(SourceCandidateRisk.ARBITRARY_LITERAL),
                    ),
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.MEDIUM, result.confidence)
        assertTrue(result.reasons.contains(TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE))
    }

    @Test
    fun highRiskSourceCandidateCannotCreateHighTargetReliability() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/ReplyListContent.kt",
                        line = 95,
                        score = 0.95,
                        confidence = SelectionConfidence.HIGH,
                        scoreMargin = 0.02,
                        riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                    ),
                ),
                targetEvidence = TargetEvidence(
                    identityHint = IdentityHint(
                        composableNameHint = "ReplyInboxScreen",
                        source = IdentityHintSource.TEST_TAG_CONVENTION,
                        confidence = IdentityHintConfidence.HIGH,
                    ),
                    evidenceQuality = EvidenceQuality.STRUCTURED,
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN))
    }

    private fun meaningfulNode(
        text: List<String> = listOf("Pay now"),
        editableText: String? = null,
        isSensitive: Boolean = false,
    ): FixThisNode = FixThisNode(
        uid = "compose:0:merged:10",
        composeNodeId = 10,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 20f, 140f, 80f),
        text = text,
        editableText = editableText,
        role = "Button",
        testTag = "comp:HomeScreen:primary",
        isSensitive = isSensitive,
    )
}
