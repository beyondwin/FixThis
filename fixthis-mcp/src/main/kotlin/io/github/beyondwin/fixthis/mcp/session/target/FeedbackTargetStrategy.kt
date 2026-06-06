package io.github.beyondwin.fixthis.mcp.session.target

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto

/**
 * Per-kind behavior for a feedback target. The wire enum [FeedbackTargetType] is
 * mapped to a strategy at exactly one place ([strategy]); the persisted
 * [AnnotationTargetDto] is mapped to the enum at one place ([targetType]). Every
 * other target-kind decision dispatches through this interface, so a new kind is
 * one new implementation, not edits across scattered `when` blocks.
 *
 * Invariant (see docs/superpowers/specs/2026-06-06-target-type-polymorphism-design.md
 * and ADR-0009): no third `when` over target kind may exist in this package.
 */
internal sealed interface FeedbackTargetStrategy {
    val type: FeedbackTargetType
    val targetKind: TargetKind

    fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode?

    fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode>

    fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode?

    fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode>

    fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto
}

internal object AreaTargetStrategy : FeedbackTargetStrategy {
    override val type = FeedbackTargetType.AREA
    override val targetKind = TargetKind.AREA

    override fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode? = null

    override fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode> = areaEvidenceNodes(screen, storedBounds)

    override fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode? = target.evidenceNodes.firstOrNull()

    override fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode> = target.evidenceNodes.drop(1)

    override fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto = AnnotationTargetDto.Area(target.storedBounds)
}

internal object NodeTargetStrategy : FeedbackTargetStrategy {
    override val type = FeedbackTargetType.NODE
    override val targetKind = TargetKind.NODE

    override fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode {
        val uid = nodeUid?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Node feedback requires nodeUid")
        return screen.allNodes().firstOrNull { node -> node.uid == uid }
            ?: throw IllegalArgumentException("Selected node does not exist on $missingNodeContext: $uid")
    }

    override fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode> = nodeEvidenceNodes(
        screen,
        requireNotNull(selectedNode) {
            "evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"
        },
    )

    override fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode? = target.selectedNode

    override fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode> = target.evidenceNodes

    override fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto {
        val nodeForTarget = requireNotNull(target.selectedNode) {
            "ValidatedFeedbackTarget(targetType=NODE) must carry a non-null selectedNode"
        }
        return AnnotationTargetDto.Node(nodeUid = nodeForTarget.uid, boundsInWindow = target.storedBounds)
    }
}

internal fun FeedbackTargetType.strategy(): FeedbackTargetStrategy = when (this) {
    FeedbackTargetType.AREA -> AreaTargetStrategy
    FeedbackTargetType.NODE -> NodeTargetStrategy
}

internal fun AnnotationTargetDto.targetType(): FeedbackTargetType = when (this) {
    is AnnotationTargetDto.Area -> FeedbackTargetType.AREA
    is AnnotationTargetDto.Node -> FeedbackTargetType.NODE
}

private const val MAX_EVIDENCE_NODES = 8

private data class AreaEvidenceNode(
    val node: FixThisNode,
    val overlaps: Boolean,
    val overlapArea: Float,
    val centerDistance: Float,
)

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
        .take(MAX_EVIDENCE_NODES)
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
    .take(MAX_EVIDENCE_NODES)
    .toList()

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
