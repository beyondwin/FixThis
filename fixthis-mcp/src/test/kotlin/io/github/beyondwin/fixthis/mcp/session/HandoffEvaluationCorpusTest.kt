package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.format.DetailMode
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
                "interop-risk-with-compose-host",
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

    @Test
    fun precise_renderingExposesPairedEditSurfacesForCorpusItems() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val itemsWithEditSurfaces = corpus.cases
            .map { HandoffEvaluationFixtures.annotationFor(it) }
            .filter { it.editSurfaceCandidates.isNotEmpty() }
        if (itemsWithEditSurfaces.isEmpty()) return
        val session = SessionDto(
            sessionId = "phase2-precise",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = itemsWithEditSurfaces.mapIndexed { idx, item -> item.copy(sequenceNumber = idx + 1) },
        )
        val md = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        for (item in itemsWithEditSurfaces) {
            val top = item.editSurfaceCandidates.first()
            assertTrue(
                md.contains(top.file),
                "PRECISE must surface edit-surface file ${top.file} for item ${item.itemId}\n$md",
            )
        }
    }

    @Test
    fun corpusItemsRenderBoundaryGuidanceWhenExpected() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-boundary-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        corpus.cases.filter { it.expectedBoundaryToken != null }.forEach { case ->
            val expectedToken = requireNotNull(case.expectedBoundaryToken)
            assertTrue(
                compact.contains("targetBoundary=$expectedToken"),
                "COMPACT missing boundary token for ${case.id}\n$compact",
            )
            assertTrue(
                precise.contains("- Boundary:"),
                "PRECISE missing boundary guidance for ${case.id}\n$precise",
            )
        }
    }

    @Test
    fun corpusInteropHostCaseRendersBoundaryContext() {
        val case = HandoffEvaluationFixtures.loadCorpus().cases.single { it.id == "interop-risk-with-compose-host" }
        val item = HandoffEvaluationFixtures.annotationFor(case)
        val session = SessionDto(
            sessionId = "handoff-boundary-context-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(HandoffEvaluationFixtures.screenFor(case)),
            items = listOf(item.copy(sequenceNumber = 1)),
        )

        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        assertTrue(compact.contains("boundaryContext: tag=\"comp:NativeChartHost:chart\""), compact)
        assertTrue(
            precise.contains("- Boundary context: nearest Compose context tag=\"comp:NativeChartHost:chart\""),
            precise,
        )
    }

    private fun EditSurfaceRoleDto.renderToken(): String = name.lowercase().replace("_", "-")
}
