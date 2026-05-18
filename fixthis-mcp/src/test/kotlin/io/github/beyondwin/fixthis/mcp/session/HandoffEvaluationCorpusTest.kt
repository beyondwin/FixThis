package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandoffEvaluationCorpusTest {
    @Test
    fun corpusHasStableV06Coverage() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()

        assertEquals(1, corpus.schemaVersion)
        assertEquals(
            listOf(
                "button-copy-call-site",
                "reusable-card-color",
                "spacing-layout",
                "visual-area-gap",
                "interop-risk",
                "ambiguous-repeated-text",
            ),
            corpus.cases.map { it.id },
        )
    }

    @Test
    fun v06CorpusMeetsEditSurfaceRegressionGate() {
        val failures = HandoffEvaluationFixtures.loadCorpus().cases.mapNotNull { case ->
            val item = HandoffEvaluationFixtures.annotationFor(case)
            val topRole = item.editSurfaceCandidates.firstOrNull()?.role
            val topFiles = item.sourceCandidates.take(3).map { it.file.substringAfterLast('/') }
            val highConfidence = item.editSurfaceCandidates.any { it.confidence == SelectionConfidence.HIGH }
            when {
                topRole != case.expectedRole ->
                    "${case.id}: expected role ${case.expectedRole}, got $topRole"
                case.expectedTop3Contains != null && topFiles.none { it == case.expectedTop3Contains } ->
                    "${case.id}: top-3 candidates did not include ${case.expectedTop3Contains}; got $topFiles"
                !case.allowHighConfidence && highConfidence ->
                    "${case.id}: weak case produced high-confidence edit surface"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    @Test
    fun corpusItemsRenderCompactHandoffWithoutDroppingRoles() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { case ->
                HandoffEvaluationFixtures.screenFor(case)
            },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val markdown = CompactHandoffRenderer.render(session)

        for (case in corpus.cases) {
            assertTrue(markdown.contains("role=${case.expectedRole.renderToken()}"), "Missing role for ${case.id}")
        }
        assertTrue(markdown.length <= 6500, "v0.6 corpus handoff is ${markdown.length} chars; budget is 6500")
    }

    private fun EditSurfaceRoleDto.renderToken(): String = name.lowercase().replace("_", "-")
}
