package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Test
import kotlin.test.assertTrue

class CopyPromptEditSurfaceRendererTest {
    @Test
    fun renderTargetSummaryIncludesContainingTaggedOwner() {
        val owner = node("owner-card", bounds = FixThisRect(42f, 1167f, 1038f, 1509f), testTag = "comp:MetricCard:summary", path = listOf("root", "card"))
        val selected = node(
            "metric-label",
            bounds = FixThisRect(79f, 1204f, 348f, 1241f),
            text = listOf("Resolved this week"),
            path = listOf("root", "card", "label"),
        )
        val session = oneItemSession(
            screen = screenWith(owner, selected),
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node(selected.uid, selected.boundsInWindow),
                selectedNode = selected,
                comment = "여기 글자 파란색",
                sequenceNumber = 1,
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("""target: text="Resolved this week"; inside tag="comp:MetricCard:summary""""))
    }

    @Test
    fun renderShowsEditSurfaceBeforeDataSourceCandidateForStyleIntent() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node("label", FixThisRect(79f, 1204f, 348f, 1241f)),
            selectedNode = node("label", bounds = FixThisRect(79f, 1204f, 348f, 1241f), text = listOf("Resolved this week")),
            sourceCandidates = listOf(dataSourceCandidate("Resolved this week", 59)),
            editSurfaceCandidates = listOf(
                EditSurfaceCandidateDto(
                    kind = EditSurfaceKindDto.TEXT_COLOR,
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt",
                    line = 26,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = listOf(EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.TARGET_OWNER),
                    note = "source candidate identifies data text; editSurface identifies likely rendering code",
                ),
            ),
            comment = "여기 글자 파란색",
            sequenceNumber = 1,
        )

        val markdown = CompactHandoffRenderer.render(oneItemSession(item))
        val editIndex = markdown.indexOf("editSurface: textColor ->")
        val sourceIndex = markdown.indexOf("FixThisDemoData.kt:59")

        assertTrue(editIndex >= 0)
        assertTrue(sourceIndex > editIndex)
        assertTrue(markdown.contains("note: source candidate identifies data text; editSurface identifies likely rendering code"))
    }

    @Test
    fun renderSampleStyleHandoffExposesEditSurfacesAndWarnings() {
        val metricCard = node("metric-card", bounds = FixThisRect(42f, 1167f, 1038f, 1509f), testTag = "comp:MetricCard:summary", path = listOf("root", "metric-card"))
        val resolvedThisWeek = node("metric-label", bounds = FixThisRect(79f, 1204f, 348f, 1241f), text = listOf("Resolved this week"), path = listOf("root", "metric-card", "metric-label"))
        val priorityFeedback = node("priority-title", bounds = FixThisRect(42f, 1520f, 420f, 1570f), text = listOf("Priority feedback"), path = listOf("root", "section-header", "priority-title"))
        val openQueue = node("open-queue", bounds = FixThisRect(800f, 1520f, 1038f, 1570f), text = listOf("Open queue"), path = listOf("root", "section-header", "open-queue"))
        val feedbackCard = node("feedback-card", bounds = FixThisRect(42f, 1600f, 1038f, 1820f), text = listOf("FX-1042"), testTag = "comp:FeedbackCard:priority", path = listOf("root", "feedback-card"))
        val resolvedChip = node("resolved-chip", bounds = FixThisRect(820f, 1630f, 1000f, 1680f), text = listOf("Resolved"), testTag = "comp:StatusChip:resolved", path = listOf("root", "feedback-card", "resolved-chip"))
        val session = SessionDto(
            sessionId = "session-style-golden",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(screenWith(metricCard, resolvedThisWeek, priorityFeedback, openQueue, feedbackCard, resolvedChip)),
            items = listOf(
                styleItem("item-metric-background", metricCard, "여기 배경 초록색", 1, EditSurfaceKindDto.CONTAINER_COLOR, "sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt", 26, EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.TARGET_OWNER),
                styleItem("item-resolved-this-week", resolvedThisWeek, "여기 글자 파란색", 2, EditSurfaceKindDto.TEXT_COLOR, "sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt", 26, EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.TARGET_OWNER),
                styleItem("item-priority-feedback", priorityFeedback, "여기 텍스트 더크게", 3, EditSurfaceKindDto.TYPOGRAPHY, "sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt", 24, EditSurfaceReasonDto.TYPOGRAPHY_INTENT, EditSurfaceReasonDto.SELECTED_TEXT_RENDERER),
                styleItem("item-open-queue", openQueue, "여기 글자 빨간색", 4, EditSurfaceKindDto.TEXT_COLOR, "sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt", 29, EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.SELECTED_TEXT_RENDERER),
                styleItem("item-feedback-card-spacing", feedbackCard, "여기 아래 바텀마진 8dp더", 5, EditSurfaceKindDto.SPACING, "sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt", 51, EditSurfaceReasonDto.LAYOUT_INTENT, EditSurfaceReasonDto.LIST_ITEM_SPACING),
                styleItem("item-resolved-chip", resolvedChip, "여기 텍스트컬리 보라색", 6, EditSurfaceKindDto.CHIP_COLOR, "sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt", 45, EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.COMPONENT_DEFINITION),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("""inside tag="comp:MetricCard:summary""""))
        assertTrue(markdown.contains("editSurface: textColor -> sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt:26"))
        assertTrue(markdown.contains("editSurface: typography -> sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt:24"))
        assertTrue(markdown.contains("editSurface: textColor -> sample/src/main/java/io/beyondwin/fixthis/sample/components/SectionHeader.kt:29"))
        assertTrue(markdown.contains("editSurface: spacing -> sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt:51"))
        assertTrue(markdown.contains("editSurface: chipColor -> sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt:45"))
    }

    private fun node(
        uid: String,
        bounds: FixThisRect,
        text: List<String> = emptyList(),
        testTag: String? = null,
        path: List<String> = emptyList(),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        testTag = testTag,
        path = path,
    )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        displayName = "MainActivity",
        roots = listOf(SnapshotRootDto(rootIndex = 0, boundsInWindow = FixThisRect(0f, 0f, 1080f, 1920f), mergedNodes = nodes.toList())),
    )

    private fun oneItemSession(item: AnnotationDto): SessionDto = oneItemSession(
        screen = SnapshotDto("screen-1", 1L, displayName = "Review"),
        item = item,
    )

    private fun oneItemSession(screen: SnapshotDto, item: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-one-item",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(screen),
        items = listOf(item),
    )

    private fun styleItem(
        itemId: String,
        targetNode: FixThisNode,
        comment: String,
        sequence: Int,
        kind: EditSurfaceKindDto,
        file: String,
        line: Int,
        vararg reasons: EditSurfaceReasonDto,
    ): AnnotationDto = AnnotationDto(
        itemId = itemId,
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(targetNode.uid, targetNode.boundsInWindow),
        selectedNode = targetNode,
        comment = comment,
        sequenceNumber = sequence,
        sourceCandidates = listOf(dataSourceCandidate(targetNode.text.firstOrNull().orEmpty(), 50 + sequence)),
        editSurfaceCandidates = listOf(EditSurfaceCandidateDto(kind, file, line = line, confidence = SelectionConfidence.MEDIUM, reasons = reasons.toList())),
    )

    private fun dataSourceCandidate(term: String, line: Int): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt",
        line = line,
        score = 0.55,
        matchedTerms = listOf(term).filter { it.isNotBlank() },
        matchReasons = listOf("selected text"),
        confidence = SelectionConfidence.MEDIUM,
    )
}
