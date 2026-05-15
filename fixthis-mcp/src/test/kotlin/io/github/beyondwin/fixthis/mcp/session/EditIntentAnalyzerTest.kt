@file:Suppress("LongParameterList", "MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditIntentAnalyzerTest {
    @Test
    fun returnsChipColorForColorRequestInsideChipComponent() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(comment = "여기 보라색", selectedNode = chip)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(chip))

        assertEquals(EditSurfaceKindDto.CHIP_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.COMPONENT_DEFINITION))
    }

    @Test
    fun returnsTextColorForTextNodeColorRequest() {
        val owner = node("metric-card", testTag = "comp:MetricCard:summary", bounds = FixThisRect(0f, 0f, 300f, 200f), path = listOf("root", "card"))
        val label = node("metric-label", text = listOf("Resolved this week"), bounds = FixThisRect(20f, 40f, 220f, 80f), path = listOf("root", "card", "label"))
        val item = item(comment = "여기 글자 파란색", selectedNode = label)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(owner, label))

        assertEquals(EditSurfaceKindDto.TEXT_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
    }

    @Test
    fun returnsContainerColorForBackgroundRequest() {
        val card = node("metric-card", testTag = "comp:MetricCard:summary")
        val item = item(comment = "카드 배경 초록색", selectedNode = card)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(card))

        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
    }

    @Test
    fun returnsTypographyForTextSizeRequestOnTextNode() {
        val title = node("section-title", text = listOf("Priority feedback"))
        val item = item(comment = "여기 텍스트 더크게", selectedNode = title)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(title))

        assertEquals(EditSurfaceKindDto.TYPOGRAPHY, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
    }

    @Test
    fun returnsSpacingForMarginOrDpRequest() {
        val card = node("feedback-card", testTag = "comp:FeedbackCard:priority")
        val item = item(comment = "여기 아래 바텀마진 8dp 더", selectedNode = card)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(card))

        assertEquals(EditSurfaceKindDto.SPACING, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.LAYOUT_INTENT))
    }

    @Test
    fun returnsUnknownForContentOnlyFeedback() {
        val button = node("checkout-button", text = listOf("Continue"))
        val item = item(comment = "Rename this to Checkout", selectedNode = button)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(button))

        assertEquals(EditSurfaceKindDto.UNKNOWN, intent.primaryKind)
        assertEquals(emptyList<EditSurfaceReasonDto>(), intent.reasons)
    }

    @Test
    fun commentOnlyCompatibilityKeepsLegacyClassifierBehavior() {
        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, EditIntentClassifier.classify("여기 배경 초록색").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("make this label red").primaryKind)
        assertEquals(EditSurfaceKindDto.UNKNOWN, EditIntentClassifier.classify("Rename this to Checkout").primaryKind)
    }

    private fun item(comment: String, selectedNode: FixThisNode): AnnotationDto = AnnotationDto(
        itemId = "item-${selectedNode.uid}",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = listOf(sourceCandidate(selectedNode.uid)),
        comment = comment,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
        path: List<String> = listOf("root", uid),
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
        roots = listOf(SnapshotRootDto(rootIndex = 0, boundsInWindow = FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun sourceCandidate(owner: String): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/$owner.kt",
        line = 20,
        score = 0.8,
        confidence = SelectionConfidence.MEDIUM,
    )
}
