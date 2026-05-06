package io.beyondwin.fixthis.compose.sidekick.capture

import io.beyondwin.fixthis.compose.core.model.ActivityInfo
import io.beyondwin.fixthis.compose.core.model.AppInfo
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.beyondwin.fixthis.compose.core.model.SelectionKind
import io.beyondwin.fixthis.compose.core.model.SelectionSource
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TapPoint
import io.beyondwin.fixthis.compose.core.selection.NodeSelector
import io.beyondwin.fixthis.compose.core.selection.SelectionOptions
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceMatcher

data class AnnotationCaptureInput(
    val app: AppInfo,
    val activity: ActivityInfo,
    val tap: TapPoint,
    val nodes: List<FixThisNode>,
    val sourceIndex: SourceIndex = SourceIndex(),
    val userComment: String,
    val scopeNodeUid: String? = null,
    val areaBoundsInWindow: FixThisRect? = null,
    val screenshot: ScreenshotInfo? = null,
    val errors: List<FixThisError> = emptyList(),
    val selectionOptions: SelectionOptions = SelectionOptions(),
)

class AnnotationCaptureController(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { "fixthis-${System.currentTimeMillis()}" },
) {
    fun capture(input: AnnotationCaptureInput): FixThisAnnotation {
        val tapSelection = NodeSelector.select(
            nodes = input.nodes,
            tap = input.tap,
            options = input.selectionOptions,
        )

        val errors = input.errors.toMutableList()
        val captureSelection = when {
            input.areaBoundsInWindow != null -> CaptureSelection(
                selectedNode = null,
                selection = SelectionInfo(
                    kind = SelectionKind.VISUAL_AREA,
                    confidence = SelectionConfidence.MEDIUM,
                    areaBoundsInWindow = input.areaBoundsInWindow,
                    source = SelectionSource.AREA_SELECT,
                ),
                nearbyNodes = nearbyForArea(input.nodes, input.areaBoundsInWindow, input.selectionOptions.maxNearbyNodes),
            )

            input.scopeNodeUid != null -> {
                val scopedNode = input.nodes.firstOrNull { it.uid == input.scopeNodeUid }
                if (scopedNode == null) {
                    errors += FixThisError(
                        code = "SCOPE_NODE_NOT_FOUND",
                        message = "Scope chip node was not found",
                        details = mapOf("scopeNodeUid" to input.scopeNodeUid),
                    )
                    CaptureSelection(
                        selectedNode = tapSelection.selectedNode,
                        selection = tapSelection.selection,
                        nearbyNodes = fallbackNearby(input.nodes, tapSelection.selectedNode, input.tap, input.selectionOptions),
                    )
                } else {
                    CaptureSelection(
                        selectedNode = scopedNode,
                        selection = SelectionInfo(
                            kind = SelectionKind.SEMANTICS_NODE,
                            confidence = SelectionConfidence.HIGH,
                            selectedUid = scopedNode.uid,
                            source = SelectionSource.SCOPE_CHIP,
                        ),
                        nearbyNodes = fallbackNearby(input.nodes, scopedNode, input.tap, input.selectionOptions),
                    )
                }
            }

            else -> CaptureSelection(
                selectedNode = tapSelection.selectedNode,
                selection = tapSelection.selection,
                nearbyNodes = fallbackNearby(input.nodes, tapSelection.selectedNode, input.tap, input.selectionOptions)
            )
        }

        if (captureSelection.selectedNode == null && input.areaBoundsInWindow == null) {
            errors += FixThisError(
                code = "NO_NODE_AT_TAP",
                message = "No semantics node contains the tap point",
                details = mapOf(
                    "xInWindow" to input.tap.xInWindow.toString(),
                    "yInWindow" to input.tap.yInWindow.toString(),
                ),
            )
        }

        val sourceCandidates = sourceCandidatesFor(
            sourceIndex = input.sourceIndex,
            captureSelection = captureSelection,
            areaBoundsInWindow = input.areaBoundsInWindow,
            activityName = input.activity.className,
        )

        return FixThisAnnotation(
            id = idGenerator(),
            createdAtEpochMillis = clock(),
            app = input.app,
            activity = input.activity,
            tap = input.tap,
            selection = captureSelection.selection,
            selectedNode = captureSelection.selectedNode,
            candidatesAtPoint = tapSelection.candidatesAtPoint,
            scopeCandidates = tapSelection.scopeCandidates,
            nearbyNodes = captureSelection.nearbyNodes,
            sourceCandidates = sourceCandidates,
            searchHints = buildSearchHints(
                selectedNode = captureSelection.selectedNode,
                nearbyNodes = captureSelection.nearbyNodes,
                sourceCandidates = sourceCandidates,
                activityName = input.activity.className,
            ),
            screenshot = input.screenshot,
            userComment = input.userComment,
            errors = errors,
        )
    }

    private fun sourceCandidatesFor(
        sourceIndex: SourceIndex,
        captureSelection: CaptureSelection,
        areaBoundsInWindow: FixThisRect?,
        activityName: String,
    ): List<SourceCandidate> {
        val matcher = SourceMatcher(sourceIndex)
        captureSelection.selectedNode?.let { selectedNode ->
            return matcher.match(
                selectedNode = selectedNode,
                nearbyNodes = captureSelection.nearbyNodes,
                activityName = activityName,
            )
        }

        if (areaBoundsInWindow == null) return emptyList()

        return captureSelection.nearbyNodes
            .mapIndexed { index, node ->
                AreaSourceMatch(
                    sourceCandidates = matcher.match(
                        selectedNode = node,
                        nearbyNodes = captureSelection.nearbyNodes.filterNot { it.uid == node.uid },
                        activityName = activityName,
                    ),
                    index = index,
                )
            }
            .filter { it.sourceCandidates.isNotEmpty() }
            .sortedWith(
                compareByDescending<AreaSourceMatch> { it.sourceCandidates.first().score }
                    .thenBy { it.index }
            )
            .firstOrNull()
            ?.sourceCandidates
            .orEmpty()
    }

    private data class CaptureSelection(
        val selectedNode: FixThisNode?,
        val selection: SelectionInfo,
        val nearbyNodes: List<FixThisNode>,
    )
}

private data class AreaSourceMatch(
    val sourceCandidates: List<SourceCandidate>,
    val index: Int,
)

private fun fallbackNearby(
    nodes: List<FixThisNode>,
    selectedNode: FixThisNode?,
    tap: TapPoint,
    options: SelectionOptions,
): List<FixThisNode> {
    if (options.maxNearbyNodes <= 0) return emptyList()
    val anchorRootIndex = selectedNode?.rootIndex
    return nodes.asSequence()
        .filter { node -> selectedNode == null || node.uid != selectedNode.uid }
        .filter { node -> anchorRootIndex == null || node.rootIndex == anchorRootIndex }
        .filter { it.hasMeaningfulSemantic() }
        .map { it to it.boundsInWindow.centerDistanceTo(tap) }
        .filter { (_, distance) -> selectedNode == null || distance <= options.nearbyRadiusPx }
        .sortedWith(
            compareBy<Pair<FixThisNode, Float>> { it.second }
                .thenBy { it.first.boundsInWindow.area }
                .thenBy { it.first.uid }
        )
        .distinctBy { it.first.semanticIdentity() }
        .take(options.maxNearbyNodes)
        .map { it.first }
        .toList()
}

private fun nearbyForArea(
    nodes: List<FixThisNode>,
    area: FixThisRect,
    maxNodes: Int,
): List<FixThisNode> {
    if (maxNodes <= 0) return emptyList()
    return nodes.asSequence()
        .filter { it.hasMeaningfulSemantic() }
        .filter { it.boundsInWindow.intersects(area) }
        .sortedWith(
            compareBy<FixThisNode> { it.boundsInWindow.centerDistanceTo(area) }
                .thenBy { it.boundsInWindow.area }
                .thenBy { it.uid }
        )
        .distinctBy { it.semanticIdentity() }
        .take(maxNodes)
        .toList()
}

private fun buildSearchHints(
    selectedNode: FixThisNode?,
    nearbyNodes: List<FixThisNode>,
    sourceCandidates: List<SourceCandidate>,
    activityName: String,
): List<String> =
    sequence {
        selectedNode?.let { node ->
            yieldAll(node.text)
            node.editableText?.let { yield(it) }
            yieldAll(node.contentDescription)
            node.testTag?.let { yield(it) }
            node.role?.let { yield(it) }
            node.stateDescription?.let { yield(it) }
        }

        nearbyNodes.forEach { node ->
            yieldAll(node.text)
            node.editableText?.let { yield(it) }
            yieldAll(node.contentDescription)
            node.testTag?.let { yield(it) }
            node.role?.let { yield(it) }
            node.stateDescription?.let { yield(it) }
        }

        sourceCandidates.forEach { yieldAll(it.matchedTerms) }
        yield(activityName.substringAfterLast('.'))
    }
        .map { it.trim() }
        .filter { it.length in 2..120 }
        .distinct()
        .take(30)
        .toList()

private fun FixThisRect.intersects(other: FixThisRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun FixThisRect.centerDistanceTo(tap: TapPoint): Float {
    val dx = centerX - tap.xInWindow
    val dy = centerY - tap.yInWindow
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun FixThisRect.centerDistanceTo(other: FixThisRect): Float {
    val dx = centerX - other.centerX
    val dy = centerY - other.centerY
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private val FixThisRect.centerX: Float
    get() = (left + right) / 2f

private val FixThisRect.centerY: Float
    get() = (top + bottom) / 2f

private fun FixThisNode.semanticIdentity(): String =
    listOf(
        role.orEmpty(),
        text.joinToString("\u001f"),
        contentDescription.joinToString("\u001f"),
        testTag.orEmpty(),
    ).joinToString("\u001e").lowercase()
