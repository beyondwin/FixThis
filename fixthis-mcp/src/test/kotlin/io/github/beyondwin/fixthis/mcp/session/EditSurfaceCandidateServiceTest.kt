@file:Suppress("LongParameterList", "MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceKindDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceReasonDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceCandidateServiceTest {
    @Test
    fun attachesRoleRubricBasisToComponentDefinitionCandidate() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(
            comment = "여기 보라색",
            selectedNode = chip,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StatusChip.kt",
                    matchedTerms = listOf("StatusChip"),
                    ownerComposable = "StatusChip",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(chip))

        val candidate = candidates.single { it.role == EditSurfaceRoleDto.COMPONENT_DEFINITION }
        assertNotNull(candidate.confidenceBasis)
        assertTrue(candidate.confidenceBasis!!.contains("shared component definition"))
    }

    @Test
    fun buildsChipColorCandidateFromSelectedComponentTag() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(
            comment = "여기 보라색",
            selectedNode = chip,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StatusChip.kt",
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
    fun attachesCopyOrDataRoleForContentOnlyFeedback() {
        val button = node("checkout-button", text = listOf("Continue"), testTag = "comp:PrimaryButton:default")
        val item = item(
            comment = "Rename this to Checkout",
            selectedNode = button,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/PrimaryButton.kt",
                    matchedTerms = listOf("PrimaryButton"),
                    ownerComposable = "PrimaryButton",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, candidates.single().role)
    }

    @Test
    fun keepsOwnerBasedTextColorCandidateForTextInsideTaggedContainer() {
        val owner = node("metric-card", testTag = "comp:MetricCard:summary", bounds = FixThisRect(0f, 0f, 300f, 200f), path = listOf("root", "card"))
        val label = node("metric-label", text = listOf("Resolved this week"), bounds = FixThisRect(20f, 40f, 220f, 80f), path = listOf("root", "card", "label"))
        val item = item(
            comment = "여기 글자 파란색",
            selectedNode = label,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt",
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

    @Test
    fun assignsCopyOrDataRoleForRenameFeedback() {
        val button = node("checkout-button", text = listOf("Continue"), testTag = "comp:PrimaryButton:checkout")
        val item = item(
            comment = "Rename this button to Checkout",
            selectedNode = button,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/CheckoutScreen.kt",
                    matchedTerms = listOf("Continue"),
                    ownerComposable = "CheckoutScreen",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, candidates.single().role)
    }

    @Test
    fun createsVisualAreaRoleCandidateWithoutSourceFile() {
        val item = AnnotationDto(
            itemId = "area",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 80f, 80f)),
            sourceCandidates = emptyList(),
            comment = "Tighten this empty gap",
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith())

        assertEquals(1, candidates.size)
        assertEquals(EditSurfaceRoleDto.VISUAL_AREA, candidates.single().role)
        assertEquals(EditSurfaceKindDto.UNKNOWN, candidates.single().kind)
        assertEquals(SelectionConfidence.LOW, candidates.single().confidence)
    }

    @Test
    fun selectedStringResourceCandidateBuildsCopyOrDataSurface() {
        val button = node(uid = "button", text = listOf("Continue"), role = "Button")
        val item = item(
            comment = "Make it clearer",
            selectedNode = button,
            candidates = listOf(
                candidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/CheckoutStrings.kt",
                    reasons = listOf("selected resolved stringResource"),
                    terms = listOf("Continue"),
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, candidates.single().role)
        assertEquals(EditSurfaceKindDto.COMPONENT_RENDERER, candidates.single().kind)
        assertEquals(EditSurfaceReasonDto.SELECTED_TEXT_RENDERER, candidates.single().reasons.single())
    }

    @Test
    fun layoutRendererContextBuildsLowConfidenceLayoutSurface() {
        val tile = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile")
        val item = item(
            comment = "This grid feels cramped",
            selectedNode = tile,
            candidates = listOf(
                candidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/AdaptiveGrid.kt",
                    reasons = listOf("selected owner composable", "layout renderer context"),
                    owner = "AdaptiveGrid",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(tile))

        assertEquals(EditSurfaceRoleDto.LAYOUT_OR_STYLE, candidates.single().role)
        assertEquals(EditSurfaceKindDto.SPACING, candidates.single().kind)
        assertEquals(SelectionConfidence.LOW, candidates.single().confidence)
    }

    private fun item(
        comment: String,
        selectedNode: FixThisNode,
        candidates: List<SourceCandidate>,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-${selectedNode.uid}",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = candidates,
        comment = comment,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
        role: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
        path: List<String> = listOf("root", uid),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
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

    private fun candidate(
        file: String,
        reasons: List<String>,
        terms: List<String> = emptyList(),
        owner: String? = null,
    ): SourceCandidate = SourceCandidate(
        file = file,
        line = 12,
        score = 0.8,
        confidence = SelectionConfidence.MEDIUM,
        matchReasons = reasons,
        matchedTerms = terms,
        ownerComposable = owner,
    )
}
