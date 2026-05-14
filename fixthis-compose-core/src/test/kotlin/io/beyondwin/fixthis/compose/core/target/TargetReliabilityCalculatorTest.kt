package io.beyondwin.fixthis.compose.core.target

import io.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetKind
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.beyondwin.fixthis.compose.core.model.TreeKind
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
