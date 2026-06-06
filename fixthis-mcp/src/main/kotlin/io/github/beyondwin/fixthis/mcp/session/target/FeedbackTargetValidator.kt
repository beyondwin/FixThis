package io.github.beyondwin.fixthis.mcp.session.target

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto

data class ValidatedFeedbackTarget(
    val targetType: FeedbackTargetType,
    val selectedNode: FixThisNode?,
    val storedBounds: FixThisRect,
    val evidenceNodes: List<FixThisNode>,
)

data class FeedbackTargetSelection(
    val targetType: FeedbackTargetType,
    val bounds: FixThisRect,
    val nodeUid: String?,
)

data class FeedbackTargetValidationOptions(
    val comment: String,
    val allowBlankComment: Boolean,
    val missingNodeContext: String = "screen",
)

data class FeedbackTargetValidationRequest(
    val screen: SnapshotDto,
    val selection: FeedbackTargetSelection,
    val options: FeedbackTargetValidationOptions,
)

class FeedbackTargetValidator {
    fun validate(request: FeedbackTargetValidationRequest): ValidatedFeedbackTarget {
        val selection = request.selection
        val options = request.options
        if (!options.allowBlankComment) {
            require(options.comment.isNotBlank()) { "Feedback comment must not be blank" }
        }
        val selectedNode = selectedNodeFor(
            request.screen,
            selection.targetType,
            selection.nodeUid,
            options.missingNodeContext,
        )
        val storedBounds = selectedNode?.boundsInWindow ?: selection.bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(request.screen, storedBounds)
        return ValidatedFeedbackTarget(
            targetType = selection.targetType,
            selectedNode = selectedNode,
            storedBounds = storedBounds,
            evidenceNodes = evidenceNodesFor(request.screen, selection.targetType, storedBounds, selectedNode),
        )
    }

    private fun selectedNodeFor(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        nodeUid: String?,
        missingNodeContext: String,
    ): FixThisNode? = when (targetType) {
        FeedbackTargetType.AREA -> null
        FeedbackTargetType.NODE -> {
            val uid = nodeUid?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Node feedback requires nodeUid")
            screen.allNodes().firstOrNull { node -> node.uid == uid }
                ?: throw IllegalArgumentException("Selected node does not exist on $missingNodeContext: $uid")
        }
    }

    private fun evidenceNodesFor(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        storedBounds: FixThisRect,
        selectedNode: FixThisNode?,
    ): List<FixThisNode> = when (targetType) {
        FeedbackTargetType.AREA -> areaEvidenceNodes(screen, storedBounds)
        FeedbackTargetType.NODE -> nodeEvidenceNodes(
            screen,
            requireNotNull(selectedNode) {
                "evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"
            },
        )
    }

    private fun validateFinitePositiveBounds(bounds: FixThisRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) {
            "Selection bounds must have positive size"
        }
    }

    private fun validateBoundsInsideScreenshot(screen: SnapshotDto, bounds: FixThisRect) {
        val width = screen.screenshot?.width?.toFloat() ?: return
        val height = screen.screenshot?.height?.toFloat() ?: return
        require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
            "Selection bounds must be inside the screenshot"
        }
    }

    private fun areaEvidenceNodes(screen: SnapshotDto, bounds: FixThisRect): List<FixThisNode> {
        val evidenceNodes = screen.allNodes()
            .asSequence()
            .filter { it.hasMeaningfulSemantic() }
            .map { node ->
                AreaEvidenceNode(
                    node = node,
                    overlaps = node.boundsInWindow.intersects(bounds),
                    overlapArea = node.boundsInWindow.intersectionArea(bounds),
                    centerDistance = node.boundsInWindow.centerDistanceTo(bounds),
                )
            }
            .toList()
        val hasOverlappingEvidence = evidenceNodes.any { it.overlaps }
        return evidenceNodes
            .asSequence()
            .filter { evidence -> if (hasOverlappingEvidence) evidence.overlaps else true }
            .sortedWith(
                compareByDescending<AreaEvidenceNode> { it.overlaps }
                    .thenByDescending { it.overlapArea }
                    .thenBy { it.centerDistance }
                    .thenBy { it.node.boundsInWindow.area }
                    .thenBy { it.node.uid },
            )
            .map { it.node }
            .distinctBy { it.uid }
            .take(MaxEvidenceNodes)
            .toList()
    }

    private fun nodeEvidenceNodes(screen: SnapshotDto, selectedNode: FixThisNode): List<FixThisNode> = screen.allNodes()
        .asSequence()
        .filter { it.uid != selectedNode.uid }
        .filter { it.rootIndex == selectedNode.rootIndex }
        .filter { it.hasMeaningfulSemantic() }
        .sortedWith(
            compareBy<FixThisNode> { it.boundsInWindow.centerDistanceTo(selectedNode.boundsInWindow) }
                .thenBy { it.boundsInWindow.area }
                .thenBy { it.uid },
        )
        .distinctBy { it.uid }
        .take(MaxEvidenceNodes)
        .toList()

    private data class AreaEvidenceNode(
        val node: FixThisNode,
        val overlaps: Boolean,
        val overlapArea: Float,
        val centerDistance: Float,
    )

    private companion object {
        const val MaxEvidenceNodes = 8
    }
}

private fun SnapshotDto.allNodes(): List<FixThisNode> = roots.flatMap { root -> root.mergedNodes + root.unmergedNodes }

private fun FixThisRect.intersects(other: FixThisRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun FixThisRect.intersectionArea(other: FixThisRect): Float {
    val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    return width * height
}

private fun FixThisRect.centerDistanceTo(other: FixThisRect): Float {
    val dx = ((left + right) / 2f) - ((other.left + other.right) / 2f)
    val dy = ((top + bottom) / 2f) - ((other.top + other.bottom) / 2f)
    return dx * dx + dy * dy
}
