@file:Suppress("LongParameterList", "MaxLineLength")

package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceCandidateServiceTest {
    @Test
    fun buildsChipColorCandidateFromSelectedComponentTag() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(
            comment = "여기 보라색",
            selectedNode = chip,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt",
                    matchedTerms = listOf("StatusChip"),
                    ownerComposable = "StatusChip",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(chip))

        assertEquals(1, candidates.size)
        assertEquals(EditSurfaceKindDto.CHIP_COLOR, candidates.single().kind)
        assertTrue(candidates.single().file.endsWith("StatusChip.kt"))
        assertTrue(candidates.single().reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
        assertTrue(candidates.single().reasons.contains(EditSurfaceReasonDto.COMPONENT_DEFINITION))
    }

    @Test
    fun doesNotBuildCandidateForContentOnlyFeedback() {
        val button = node("checkout-button", text = listOf("Continue"), testTag = "comp:PrimaryButton:default")
        val item = item(
            comment = "Rename this to Checkout",
            selectedNode = button,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/PrimaryButton.kt",
                    matchedTerms = listOf("PrimaryButton"),
                    ownerComposable = "PrimaryButton",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(emptyList<EditSurfaceCandidateDto>(), candidates)
    }

    @Test
    fun keepsOwnerBasedTextColorCandidateForTextInsideTaggedContainer() {
        val owner = node("metric-card", testTag = "comp:MetricCard:summary", bounds = FixThisRect(0f, 0f, 300f, 200f), path = listOf("root", "card"))
        val label = node("metric-label", text = listOf("Resolved this week"), bounds = FixThisRect(20f, 40f, 220f, 80f), path = listOf("root", "card", "label"))
        val item = item(
            comment = "여기 글자 파란색",
            selectedNode = label,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt",
                    matchedTerms = listOf("MetricCard"),
                    ownerComposable = "MetricCard",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(owner, label))

        assertEquals(1, candidates.size)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, candidates.single().kind)
        assertTrue(candidates.single().file.endsWith("MetricCard.kt"))
    }

    private fun item(
        comment: String,
        selectedNode: FixThisNode,
        sourceCandidates: List<SourceCandidate>,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-${selectedNode.uid}",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = sourceCandidates,
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

    private fun sourceCandidate(
        file: String,
        matchedTerms: List<String>,
        ownerComposable: String?,
    ): SourceCandidate = SourceCandidate(
        file = file,
        line = 20,
        score = 0.8,
        matchedTerms = matchedTerms,
        matchReasons = listOf("selected text"),
        confidence = SelectionConfidence.MEDIUM,
        ownerComposable = ownerComposable,
    )
}
