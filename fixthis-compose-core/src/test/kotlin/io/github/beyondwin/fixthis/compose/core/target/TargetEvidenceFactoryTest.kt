package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetEvidenceFactoryTest {
    @Test
    fun nodeTargetIncludesStrictIdentityAndOccurrence() {
        val selected = node(uid = "primary", text = listOf("Pay"), testTag = "comp:CheckoutButton:primary")

        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.NODE,
                selectedNode = selected,
                mergedNodes = listOf(selected),
                sourceCandidates = emptyList(),
                screenshotKinds = listOf("full"),
            ),
        )

        assertEquals("CheckoutButton", evidence.identityHint?.composableNameHint)
        assertEquals("primary", evidence.identityHint?.variantHint)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, evidence.identityHint?.source)
        assertEquals(IdentityHintConfidence.HIGH, evidence.identityHint?.confidence)
        assertEquals(EvidenceQuality.STRUCTURED, evidence.evidenceQuality)
        assertEquals(listOf("full"), evidence.screenshotKinds)
        assertEquals(1, evidence.occurrence?.count)
    }

    @Test
    fun areaTargetAddsVisualAreaWarningAndBasicQualityWithoutStructure() {
        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.AREA,
                selectedNode = null,
                mergedNodes = emptyList(),
                sourceCandidates = emptyList(),
            ),
        )

        assertEquals(EvidenceQuality.BASIC, evidence.evidenceQuality)
        assertTrue(evidence.warnings.contains("Occurrence is not applicable for visual area selections."))
        assertEquals("No source candidate was available from current evidence.", evidence.sourceInterpretation?.caution)
    }

    @Test
    fun sourceInterpretationIsBuiltFromCandidates() {
        val selected = node(uid = "title", text = listOf("Title"))
        val evidence = TargetEvidenceFactory.build(
            TargetEvidenceInput(
                targetKind = TargetKind.NODE,
                selectedNode = selected,
                mergedNodes = listOf(selected),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/Title.kt",
                        line = 12,
                        score = 0.2,
                        matchedTerms = listOf("Title"),
                        matchReasons = listOf("selected text"),
                        confidence = SelectionConfidence.LOW,
                    ),
                ),
            ),
        )

        assertEquals(listOf("selected text"), evidence.sourceInterpretation?.reasonSummary)
        assertEquals(SelectionConfidence.LOW, evidence.sourceInterpretation?.topCandidate?.confidence)
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 120f, 80f),
        text = text,
        testTag = testTag,
    )
}
