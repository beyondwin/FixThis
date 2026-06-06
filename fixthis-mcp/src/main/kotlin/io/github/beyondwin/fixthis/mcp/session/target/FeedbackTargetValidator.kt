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
        val strategy = selection.targetType.strategy()
        val selectedNode = strategy.resolveSelectedNode(
            request.screen,
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
            evidenceNodes = strategy.evidenceNodes(request.screen, storedBounds, selectedNode),
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
}
