package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTrustObservationMapperTest {
    @Test
    fun mapsAnnotationTrustFieldsToObservedOutput() {
        val observed = RuntimeTrustObservationMapper.fromAnnotation(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("compose-fab", FixThisRect(0f, 0f, 100f, 100f)),
                comment = "runtime fixture",
                status = AnnotationStatusDto.OPEN,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
                        line = 95,
                        score = 0.42,
                        confidence = SelectionConfidence.MEDIUM,
                        riskFlags = listOf(SourceCandidateRisk.ARBITRARY_LITERAL),
                    ),
                ),
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.MEDIUM,
                    warnings = listOf(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN),
                ),
            ),
        )

        assertEquals("medium", observed.confidence)
        assertEquals("medium", observed.sourceConfidence)
        assertEquals(listOf("ARBITRARY_LITERAL"), observed.riskFlags)
        assertEquals(listOf("LOW_SOURCE_CANDIDATE_MARGIN"), observed.warnings)
    }

    @Test
    fun mapsRecommendedCallSitesToObservedOutput() {
        val observed = RuntimeTrustObservationMapper.fromAnnotation(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("header", FixThisRect(0f, 0f, 100f, 100f)),
                comment = "runtime fixture",
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/components/StudioHeader.kt",
                        score = 0.8,
                        confidence = SelectionConfidence.MEDIUM,
                        callSites = listOf(
                            SourceLocationRef(
                                file = "sample/screens/DiagnosticsScreen.kt",
                                line = 42,
                                mostLikely = true,
                                recommendedEditSite = true,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(RuntimeTrustCallSite("sample/screens/DiagnosticsScreen.kt", 42, mostLikely = true, recommendedEditSite = true)),
            observed.callSites,
        )
    }
}
