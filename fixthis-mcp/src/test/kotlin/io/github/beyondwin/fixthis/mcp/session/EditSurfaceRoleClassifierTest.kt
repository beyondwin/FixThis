@file:Suppress("LongParameterList")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class EditSurfaceRoleClassifierTest {
    @Test
    fun classifiesCopyIntentAsCopyOrData() {
        val item = item(
            comment = "Rename this button to Checkout",
            selectedNode = node(text = listOf("Continue"), role = "Button"),
            candidates = listOf(candidate("CheckoutScreen.kt", reasons = listOf("selected text"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, role.role)
        assertEquals(SelectionConfidence.MEDIUM, role.confidenceCap)
    }

    @Test
    fun classifiesSpacingAsLayoutOrStyle() {
        val item = item(
            comment = "Reduce the bottom spacing by 8dp",
            selectedNode = node(testTag = "comp:FeedbackCard:priority"),
            candidates = listOf(candidate("QueueScreen.kt", reasons = listOf("nearby text"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.LAYOUT_OR_STYLE, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    @Test
    fun classifiesTaggedComponentStyleAsComponentDefinition() {
        val item = item(
            comment = "Make this card background green",
            selectedNode = node(text = listOf("Resolved this week"), testTag = "comp:MetricCard:summary"),
            candidates = listOf(candidate("MetricCard.kt", owner = "MetricCard", terms = listOf("MetricCard"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.COMPONENT_DEFINITION, role.role)
        assertEquals(SelectionConfidence.MEDIUM, role.confidenceCap)
    }

    @Test
    fun classifiesVisualAreaWithoutSourceAsVisualArea() {
        val item = areaItem(comment = "Tighten this empty gap", warnings = emptyList())

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith()))

        assertEquals(EditSurfaceRoleDto.VISUAL_AREA, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    @Test
    fun classifiesInteropWarningAsInteropRisk() {
        val item = areaItem(comment = "Fix the native chart spacing", warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith()))

        assertEquals(EditSurfaceRoleDto.INTEROP_RISK, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    private fun item(comment: String, selectedNode: FixThisNode, candidates: List<SourceCandidate>): AnnotationDto =
        AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
            selectedNode = selectedNode,
            sourceCandidates = candidates,
            comment = comment,
        )

    private fun areaItem(comment: String, warnings: List<TargetReliabilityWarning>): AnnotationDto =
        AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 200f, 120f)),
            sourceCandidates = emptyList(),
            comment = comment,
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = warnings,
            ),
        )

    private fun node(
        text: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
    ): FixThisNode = FixThisNode(
        uid = "node",
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 200f, 120f),
        text = text,
        role = role,
        testTag = testTag,
        path = listOf("root", "node"),
    )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun candidate(
        file: String,
        reasons: List<String> = listOf("selected testTag convention composable"),
        terms: List<String> = emptyList(),
        owner: String? = null,
    ): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/$file",
        line = 12,
        score = 80.0,
        confidence = SelectionConfidence.MEDIUM,
        matchReasons = reasons,
        matchedTerms = terms,
        ownerComposable = owner,
    )
}
