package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHint
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto

private const val MAX_SUMMARY_ENTRIES = 4
private const val MAX_ENTRY_LENGTH = 256
private const val NEARBY_DISTANCE_MULTIPLIER = 2f

private fun FixThisNode.semanticStrings(): Set<String> {
    if (isSensitive || isPassword) return emptySet()
    return buildList {
        addAll(text)
        editableText?.let(::add)
        addAll(contentDescription)
        stateDescription?.let(::add)
    }
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
}

private fun FixThisNode.nodeKey(): Triple<Int, TreeKind, String> = Triple(rootIndex, treeKind, uid)

private fun FixThisNode.toBoundedSummary(
    confidence: FeedbackTargetCorrespondence,
): MatchedTargetSummaryDto {
    val redactText = isSensitive || isPassword
    return MatchedTargetSummaryDto(
        confidence = confidence,
        nodeUid = uid,
        role = role?.trim()?.take(MAX_ENTRY_LENGTH),
        text = if (redactText) emptyList() else text.boundedEntries(),
        contentDescriptions = if (redactText) emptyList() else contentDescription.boundedEntries(),
        boundsInWindow = boundsInWindow,
    )
}

private fun List<String>.boundedEntries(): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .take(MAX_SUMMARY_ENTRIES)
    .map { it.take(MAX_ENTRY_LENGTH) }
    .toList()

private fun FixThisRect.intersectionOverUnion(other: FixThisRect): Double {
    val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    val intersectionArea = intersectionWidth * intersectionHeight
    val unionArea = area + other.area - intersectionArea
    return if (unionArea > 0f) (intersectionArea / unionArea).toDouble() else 0.0
}

private fun FixThisRect.isNear(other: FixThisRect): Boolean {
    val horizontalGap = maxOf(0f, maxOf(left, other.left) - minOf(right, other.right))
    val verticalGap = maxOf(0f, maxOf(top, other.top) - minOf(bottom, other.bottom))
    val referenceSize = maxOf(width, height, other.width, other.height, 1f)
    return horizontalGap <= referenceSize * NEARBY_DISTANCE_MULTIPLIER &&
        verticalGap <= referenceSize * NEARBY_DISTANCE_MULTIPLIER
}

internal data class FeedbackTargetCorrespondenceResult(
    val confidence: FeedbackTargetCorrespondence,
    val matchedNode: FixThisNode?,
    val reasons: List<String>,
    val summary: MatchedTargetSummaryDto?,
)

internal class FeedbackTargetCorrespondenceEvaluator {
    fun evaluate(
        item: AnnotationDto,
        currentScreen: SnapshotDto,
    ): FeedbackTargetCorrespondenceResult = when (item.target) {
        is AnnotationTargetDto.Area ->
            FeedbackTargetCorrespondenceResult(
                confidence = FeedbackTargetCorrespondence.LOW,
                matchedNode = null,
                reasons = listOf(MANUAL_VISUAL_REVIEW_REQUIRED),
                summary = MatchedTargetSummaryDto(
                    confidence = FeedbackTargetCorrespondence.LOW,
                    boundsInWindow = item.target.boundsInWindow,
                ),
            )

        is AnnotationTargetDto.Node -> evaluateNodeTarget(item, currentScreen)
    }

    private fun evaluateNodeTarget(
        item: AnnotationDto,
        currentScreen: SnapshotDto,
    ): FeedbackTargetCorrespondenceResult {
        val candidates = currentScreen.flattenedNodes()
        val context = CorrespondenceContext(
            baselineNode = item.selectedNode,
            targetBounds = item.target.boundsInWindow,
            identityHint = item.targetEvidence?.identityHint,
            baselineNearbyNodes = item.nearbyNodes,
            currentNodes = candidates,
        )
        val scored = candidates.map { candidate ->
            scoreCandidate(context, candidate)
        }
        val best = scored.sortedWith(CANDIDATE_COMPARATOR).firstOrNull()
            ?.takeUnless { it.match.confidence == FeedbackTargetCorrespondence.NONE }
        return best?.let(::matchedResult)
            ?: FeedbackTargetCorrespondenceResult(
                confidence = FeedbackTargetCorrespondence.NONE,
                matchedNode = null,
                reasons = listOf(TARGET_NOT_FOUND),
                summary = null,
            )
    }

    private fun matchedResult(best: ScoredCandidate): FeedbackTargetCorrespondenceResult = FeedbackTargetCorrespondenceResult(
        confidence = best.match.confidence,
        matchedNode = best.node,
        reasons = listOf(best.match.reason),
        summary = best.node.toBoundedSummary(best.match.confidence),
    )

    private fun scoreCandidate(
        context: CorrespondenceContext,
        candidate: FixThisNode,
    ): ScoredCandidate {
        val baselineRole = context.baselineNode?.role
        val roleCompatible = baselineRole == null || baselineRole == candidate.role
        val stableTagMatches = context.baselineNode?.testTag
            ?.takeUnless(String::isBlank)
            ?.let { it == candidate.testTag } == true
        val identityHintMatches = context.identityHint.matches(candidate)
        val baselineSemantics = context.baselineNode?.semanticStrings().orEmpty()
        val candidateSemantics = candidate.semanticStrings()
        val exactSemanticOverlapCount = baselineSemantics.intersect(candidateSemantics).size
        val semanticOverlap = exactSemanticOverlapCount > 0
        val intersectionOverUnion = context.targetBounds.intersectionOverUnion(candidate.boundsInWindow)
        val spatiallyCompatible = intersectionOverUnion > 0.0
        val nearbyContextOverlap = context.baselineNearbyNodes
            .flatMapTo(linkedSetOf()) { it.semanticStrings() }
            .intersect(
                context.currentNodes
                    .asSequence()
                    .filterNot { it.nodeKey() == candidate.nodeKey() }
                    .filter { it.boundsInWindow.isNear(candidate.boundsInWindow) }
                    .flatMap { it.semanticStrings().asSequence() }
                    .toSet(),
            )
            .isNotEmpty()

        val match = MatchSignals(
            roleCompatible = roleCompatible,
            stableTagMatches = stableTagMatches,
            identityHintMatches = identityHintMatches,
            semanticOverlap = semanticOverlap,
            spatiallyCompatible = spatiallyCompatible,
            nearbyContextOverlap = nearbyContextOverlap,
        ).classify()
        return ScoredCandidate(
            node = candidate,
            match = match,
            exactSemanticOverlapCount = exactSemanticOverlapCount,
            intersectionOverUnion = intersectionOverUnion,
        )
    }

    private fun SnapshotDto.flattenedNodes(): List<FixThisNode> = roots
        .asSequence()
        .flatMap { root ->
            sequence {
                root.mergedNodes.forEach { yield(it.copy(rootIndex = root.rootIndex, treeKind = TreeKind.MERGED)) }
                root.unmergedNodes.forEach { yield(it.copy(rootIndex = root.rootIndex, treeKind = TreeKind.UNMERGED)) }
            }
        }
        .distinctBy { it.nodeKey() }
        .toList()

    private fun IdentityHint?.matches(candidate: FixThisNode): Boolean {
        if (this == null) return false
        val candidateHint = IdentityHintFactory.from(candidate)
        val conventionMatches = composableNameHint != null &&
            composableNameHint == candidateHint?.composableNameHint &&
            variantHint == candidateHint?.variantHint
        val stableLabelMatches = stableLabel
            ?.takeUnless(String::isBlank)
            ?.let { it == candidateHint?.stableLabel } == true
        return conventionMatches || stableLabelMatches
    }

    private data class ScoredCandidate(
        val node: FixThisNode,
        val match: MatchResult,
        val exactSemanticOverlapCount: Int,
        val intersectionOverUnion: Double,
    )

    private data class CorrespondenceContext(
        val baselineNode: FixThisNode?,
        val targetBounds: FixThisRect,
        val identityHint: IdentityHint?,
        val baselineNearbyNodes: List<FixThisNode>,
        val currentNodes: List<FixThisNode>,
    )

    private data class MatchSignals(
        val roleCompatible: Boolean,
        val stableTagMatches: Boolean,
        val identityHintMatches: Boolean,
        val semanticOverlap: Boolean,
        val spatiallyCompatible: Boolean,
        val nearbyContextOverlap: Boolean,
    ) {
        fun classify(): MatchResult = when {
            isStableTagMatch() ->
                MatchResult(FeedbackTargetCorrespondence.HIGH, MATCHED_STABLE_TAG)
            isIdentityHintMatch() ->
                MatchResult(FeedbackTargetCorrespondence.HIGH, MATCHED_IDENTITY_HINT)
            isSemanticSpatialMatch() ->
                MatchResult(FeedbackTargetCorrespondence.MEDIUM, MATCHED_ROLE_SEMANTICS_SPATIAL)
            isNearbySpatialMatch() ->
                MatchResult(FeedbackTargetCorrespondence.MEDIUM, MATCHED_ROLE_SPATIAL_CONTEXT)
            isLowConfidenceMatch() ->
                MatchResult(FeedbackTargetCorrespondence.LOW, TARGET_LOW_CONFIDENCE)
            else ->
                MatchResult(FeedbackTargetCorrespondence.NONE, TARGET_NOT_FOUND)
        }

        private fun isStableTagMatch(): Boolean = stableTagMatches && roleCompatible

        private fun isIdentityHintMatch(): Boolean = identityHintMatches && roleCompatible

        private fun isSemanticSpatialMatch(): Boolean = roleCompatible && semanticOverlap && spatiallyCompatible

        private fun isNearbySpatialMatch(): Boolean = roleCompatible && spatiallyCompatible && nearbyContextOverlap

        private fun isLowConfidenceMatch(): Boolean = roleCompatible && (semanticOverlap || spatiallyCompatible)
    }

    private data class MatchResult(
        val confidence: FeedbackTargetCorrespondence,
        val reason: String,
    )

    private companion object {
        const val MANUAL_VISUAL_REVIEW_REQUIRED = "MANUAL_VISUAL_REVIEW_REQUIRED"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val TARGET_LOW_CONFIDENCE = "TARGET_LOW_CONFIDENCE"
        const val MATCHED_STABLE_TAG = "MATCHED_STABLE_TAG"
        const val MATCHED_IDENTITY_HINT = "MATCHED_IDENTITY_HINT"
        const val MATCHED_ROLE_SEMANTICS_SPATIAL = "MATCHED_ROLE_SEMANTICS_SPATIAL"
        const val MATCHED_ROLE_SPATIAL_CONTEXT = "MATCHED_ROLE_SPATIAL_CONTEXT"
        val CONFIDENCE_RANK = mapOf(
            FeedbackTargetCorrespondence.HIGH to 3,
            FeedbackTargetCorrespondence.MEDIUM to 2,
            FeedbackTargetCorrespondence.LOW to 1,
            FeedbackTargetCorrespondence.NONE to 0,
        )

        val CANDIDATE_COMPARATOR =
            compareByDescending<ScoredCandidate> { CONFIDENCE_RANK.getValue(it.match.confidence) }
                .thenByDescending { it.exactSemanticOverlapCount }
                .thenByDescending { it.intersectionOverUnion }
                .thenBy { it.node.uid }
    }
}
