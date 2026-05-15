package io.github.beyondwin.fixthis.compose.core.selection

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.ScopeCandidate
import io.github.beyondwin.fixthis.compose.core.model.ScoredFixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.github.beyondwin.fixthis.compose.core.model.SelectionKind
import io.github.beyondwin.fixthis.compose.core.model.SelectionSource
import io.github.beyondwin.fixthis.compose.core.model.TapPoint
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.math.roundToInt

data class SelectionOptions(
    val maxCandidates: Int = 5,
    val maxNearbyNodes: Int = 12,
    val nearbyRadiusPx: Float = 480f,
)

data class SelectionResult(
    val selectedNode: FixThisNode?,
    val candidatesAtPoint: List<ScoredFixThisNode>,
    val scopeCandidates: List<ScopeCandidate>,
    val nearbyNodes: List<FixThisNode>,
    val selection: SelectionInfo,
)

object NodeSelector {
    fun select(
        nodes: List<FixThisNode>,
        tap: TapPoint,
        options: SelectionOptions = SelectionOptions(),
    ): SelectionResult {
        val atPoint = nodes.filter {
            it.boundsInWindow.area > 0f && it.boundsInWindow.contains(tap.xInWindow, tap.yInWindow)
        }
        if (atPoint.isEmpty()) {
            return SelectionResult(
                selectedNode = null,
                candidatesAtPoint = emptyList(),
                scopeCandidates = emptyList(),
                nearbyNodes = emptyList(),
                selection = SelectionInfo(
                    kind = SelectionKind.TAP_POINT,
                    confidence = SelectionConfidence.NONE,
                    source = SelectionSource.FALLBACK,
                ),
            )
        }

        val largestAreaAtPoint = atPoint.maxOf { it.boundsInWindow.area }
        val scored = atPoint
            .map { node -> score(node, tap, largestAreaAtPoint) }
            .sortedWith(
                compareByDescending<ScoredFixThisNode> { it.score }
                    .thenBy { it.node.boundsInWindow.area }
                    .thenBy { it.node.uid },
            )
        val candidates = scored.take(options.maxCandidates.coerceAtLeast(0))

        val selected = scored.firstOrNull()?.node
        val scopes = candidates
            .filter { it.node.hasMeaningfulSemantic() }
            .map { ScopeCandidate(label = labelFor(it.node), nodeUid = it.node.uid, boundsInWindow = it.node.boundsInWindow, score = it.score) }
            .sortedWith(compareByDescending<ScopeCandidate> { it.score }.thenBy { it.nodeUid })

        val nearby = selected?.let {
            NearbyNodeCollector.collect(
                nodes = nodes,
                anchor = it,
                maxNodes = options.maxNearbyNodes,
                radiusPx = options.nearbyRadiusPx,
            )
        }.orEmpty()

        return SelectionResult(
            selectedNode = selected,
            candidatesAtPoint = candidates,
            scopeCandidates = scopes,
            nearbyNodes = nearby,
            selection = SelectionInfo(
                kind = SelectionKind.SEMANTICS_NODE,
                confidence = confidenceFor(scored.firstOrNull()?.score ?: 0.0),
                selectedUid = selected?.uid,
                source = SelectionSource.TAP_SELECT,
            ),
        )
    }

    private fun score(node: FixThisNode, tap: TapPoint, largestAreaAtPoint: Float): ScoredFixThisNode {
        val breakdown = linkedMapOf<String, Double>()
        breakdown["containsTap"] = 20.0

        val actionScore = actionScore(node)
        if (actionScore != 0.0) breakdown["action"] = actionScore

        val semanticScore = semanticScore(node)
        if (semanticScore != 0.0) breakdown["semantic"] = semanticScore

        if (node.treeKind == TreeKind.MERGED && node.hasMeaningfulSemantic()) {
            breakdown["mergedMeaningful"] = 14.0
        }

        if (node.enabled) {
            breakdown["enabled"] = 4.0
        } else {
            breakdown["disabledPenalty"] = -85.0
        }

        breakdown["centerProximity"] = centerProximityScore(node.boundsInWindow, tap)

        if (isRootLike(node, largestAreaAtPoint)) {
            breakdown["rootPenalty"] = -45.0
        }

        if (!node.hasMeaningfulSemantic() && node.boundsInWindow.area >= HUGE_EMPTY_AREA_PX) {
            breakdown["largeEmptyPenalty"] = -28.0
        }

        return ScoredFixThisNode(
            node = node,
            score = breakdown.values.sum(),
            breakdown = breakdown,
        )
    }

    private fun actionScore(node: FixThisNode): Double {
        if (node.actions.any { it.equals("OnClick", ignoreCase = true) }) return 70.0
        return when {
            node.actions.isNotEmpty() -> 24.0
            else -> 0.0
        }
    }

    private fun semanticScore(node: FixThisNode): Double {
        var score = 0.0
        if (node.text.isNotEmpty() || node.editableText != null) score += 18.0
        if (node.contentDescription.isNotEmpty()) score += 16.0
        if (node.role != null) score += 14.0
        if (node.testTag != null) score += 10.0
        if (node.stateDescription != null) score += 6.0
        return score
    }

    private fun centerProximityScore(bounds: FixThisRect, tap: TapPoint): Double {
        val dx = bounds.centerX - tap.xInWindow
        val dy = bounds.centerY - tap.yInWindow
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return (12.0 - (distance / 24.0)).coerceAtLeast(0.0).toDouble()
    }

    private fun isRootLike(node: FixThisNode, largestAreaAtPoint: Float): Boolean {
        val hasRootUid = node.uid.equals("root", ignoreCase = true) || node.uid.endsWith(":root", ignoreCase = true)
        val largestMeaningless = node.boundsInWindow.area == largestAreaAtPoint && !node.hasMeaningfulSemantic()
        return hasRootUid || largestMeaningless
    }

    private fun confidenceFor(score: Double): SelectionConfidence = when {
        score >= 100.0 -> SelectionConfidence.HIGH
        score >= 55.0 -> SelectionConfidence.MEDIUM
        score > 0.0 -> SelectionConfidence.LOW
        else -> SelectionConfidence.NONE
    }

    private fun labelFor(node: FixThisNode): String {
        val parts = buildList {
            node.role?.takeUnlessBlank()?.let { add(it) }
            node.primaryText()?.let { add(it) }
            node.contentDescription.firstOrNull()?.takeUnlessBlank()?.let { add(it) }
            node.testTag?.takeUnlessBlank()?.let { add("#$it") }
            if (isEmpty()) add(node.uid)
            add("${node.boundsInWindow.width.roundToInt()}x${node.boundsInWindow.height.roundToInt()}")
        }
        return parts.distinct().joinToString(" | ")
    }

    private fun FixThisNode.primaryText(): String? = text.firstOrNull()?.takeUnlessBlank() ?: editableText?.takeUnlessBlank()

    private fun String.takeUnlessBlank(): String? = takeUnless { it.isBlank() }?.trim()

    private const val HUGE_EMPTY_AREA_PX = 180_000f
}
