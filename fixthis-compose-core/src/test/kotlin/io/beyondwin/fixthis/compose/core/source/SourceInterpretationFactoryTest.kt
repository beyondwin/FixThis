package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceInterpretationFactoryTest {
    @Test
    fun summarizesTopCandidate() {
        val interpretation = SourceInterpretationFactory.from(
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                    line = 42,
                    score = 1.0,
                    matchedTerms = listOf("AppPrimaryButton"),
                    matchReasons = listOf("selected testTag convention composable"),
                    confidence = SelectionConfidence.HIGH,
                ),
            ),
        )

        assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", interpretation.topCandidate?.file)
        assertEquals(42, interpretation.topCandidate?.line)
        assertEquals(SelectionConfidence.HIGH, interpretation.topCandidate?.confidence)
        assertEquals(listOf("selected testTag convention composable"), interpretation.reasonSummary)
        assertNull(interpretation.caution)
    }

    @Test
    fun returnsCautionWhenNoCandidateExists() {
        val interpretation = SourceInterpretationFactory.from(emptyList())

        assertNull(interpretation.topCandidate)
        assertEquals("No source candidate was available from current evidence.", interpretation.caution)
    }

    @Test
    fun cautionsForLowAndNoConfidenceCandidates() {
        val expectedCaution = "Top source candidate has low confidence; verify before editing."

        val lowInterpretation = SourceInterpretationFactory.from(
            sourceCandidates = listOf(candidate(confidence = SelectionConfidence.LOW)),
        )
        val noneInterpretation = SourceInterpretationFactory.from(
            sourceCandidates = listOf(candidate(confidence = SelectionConfidence.NONE)),
        )

        assertEquals(expectedCaution, lowInterpretation.caution)
        assertEquals(expectedCaution, noneInterpretation.caution)
    }

    @Test
    fun limitsReasonSummaryToFiveReasons() {
        val interpretation = SourceInterpretationFactory.from(
            sourceCandidates = listOf(
                candidate(
                    matchReasons = listOf(
                        "selected text",
                        "selected testTag",
                        "selected testTag convention composable",
                        "selected role",
                        "nearby text",
                        "activity",
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "selected text",
                "selected testTag",
                "selected testTag convention composable",
                "selected role",
                "nearby text",
            ),
            interpretation.reasonSummary,
        )
    }

    private fun candidate(
        confidence: SelectionConfidence = SelectionConfidence.HIGH,
        matchReasons: List<String> = listOf("selected text"),
    ): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
        line = 42,
        score = 1.0,
        matchedTerms = listOf("AppPrimaryButton"),
        matchReasons = matchReasons,
        confidence = confidence,
    )
}
