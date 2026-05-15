package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.beyondwin.fixthis.compose.core.identity.OccurrenceCalculator
import io.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetKind
import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactory
import io.beyondwin.fixthis.compose.core.source.SourceMatcher
import io.beyondwin.fixthis.compose.core.target.TargetReliabilityCalculator
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class TargetEvidenceService(
    private val bridge: FixThisBridge,
    private val sourceIndexRegistry: SourceIndexRegistry,
    private val projectRoot: File = File(".").canonicalFile,
    private val stalenessChecker: SourceCandidateStalenessChecker = SourceCandidateStalenessChecker(projectRoot),
    private val reliabilityCalculator: TargetReliabilityCalculator = TargetReliabilityCalculator,
) {
    suspend fun readSourceIndexOrNull(packageName: String, screen: SnapshotDto): SourceIndex? {
        if (!screen.sourceIndexAvailable) return null
        if (sourceIndexRegistry.contains(packageName)) return sourceIndexRegistry.cached(packageName)
        val result = runCatching { bridge.readSourceIndex(packageName) }.getOrElse { return null }
        val available = result["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull ?: false
        val sourceIndexElement = result["sourceIndex"]
        val sourceIndex = if (available && sourceIndexElement != null) {
            runCatching {
                McpProtocol.json.decodeFromJsonElement<SourceIndex>(sourceIndexElement)
                    .takeIf { it.entries.isNotEmpty() }
            }.getOrNull()
        } else {
            null
        }
        sourceIndexRegistry.put(packageName, sourceIndex)
        return sourceIndex
    }

    fun buildFeedbackItem(
        screen: SnapshotDto,
        sourceIndex: SourceIndex?,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
        allowBlankComment: Boolean,
        writtenStatus: AnnotationStatusDto,
        missingNodeContext: String = "screen",
    ): AnnotationDto {
        val validatedTarget = validateFeedbackTarget(
            screen = screen,
            targetType = targetType,
            bounds = bounds,
            nodeUid = nodeUid,
            comment = comment,
            allowBlankComment = allowBlankComment,
            missingNodeContext = missingNodeContext,
        )
        return buildFeedbackItem(
            screen = screen,
            sourceIndex = sourceIndex,
            validatedTarget = validatedTarget,
            comment = comment,
            writtenStatus = writtenStatus,
        )
    }

    fun validateFeedbackTarget(
        screen: SnapshotDto,
        targetType: FeedbackTargetType,
        bounds: FixThisRect,
        nodeUid: String?,
        comment: String,
        allowBlankComment: Boolean,
        missingNodeContext: String = "screen",
    ): ValidatedFeedbackTarget {
        if (!allowBlankComment) {
            require(comment.isNotBlank()) { "Feedback comment must not be blank" }
        }
        val selectedNode = selectedNodeFor(screen, targetType, nodeUid, missingNodeContext)
        val storedBounds = selectedNode?.boundsInWindow ?: bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(screen, storedBounds)
        val evidenceNodes = evidenceNodesFor(screen, targetType, storedBounds, selectedNode)
        return ValidatedFeedbackTarget(
            targetType = targetType,
            selectedNode = selectedNode,
            storedBounds = storedBounds,
            evidenceNodes = evidenceNodes,
        )
    }

    fun buildFeedbackItem(
        screen: SnapshotDto,
        sourceIndex: SourceIndex?,
        validatedTarget: ValidatedFeedbackTarget,
        comment: String,
        writtenStatus: AnnotationStatusDto,
    ): AnnotationDto {
        val sourceSelectedNode = when (validatedTarget.targetType) {
            FeedbackTargetType.AREA -> validatedTarget.evidenceNodes.firstOrNull()
            FeedbackTargetType.NODE -> validatedTarget.selectedNode
        }
        val sourceNearbyNodes = when (validatedTarget.targetType) {
            FeedbackTargetType.AREA -> validatedTarget.evidenceNodes.drop(1)
            FeedbackTargetType.NODE -> validatedTarget.evidenceNodes
        }
        val sourceCandidates = sourceCandidatesFor(sourceIndex, sourceSelectedNode, sourceNearbyNodes, screen.activityName)
        val targetEvidence = targetEvidenceFor(
            targetType = validatedTarget.targetType,
            selectedNode = validatedTarget.selectedNode,
            screen = screen,
            sourceCandidates = sourceCandidates,
        )
        val targetReliability = targetReliabilityFor(
            screen = screen,
            validatedTarget = validatedTarget,
            sourceCandidates = sourceCandidates,
            targetEvidence = targetEvidence,
        )
        val target = when (validatedTarget.targetType) {
            FeedbackTargetType.AREA -> AnnotationTargetDto.Area(validatedTarget.storedBounds)
            FeedbackTargetType.NODE -> {
                val nodeForTarget = requireNotNull(validatedTarget.selectedNode) {
                    "ValidatedFeedbackTarget(targetType=NODE) must carry a non-null selectedNode"
                }
                AnnotationTargetDto.Node(
                    nodeUid = nodeForTarget.uid,
                    boundsInWindow = validatedTarget.storedBounds,
                )
            }
        }
        val item = AnnotationDto(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = target,
            selectedNode = validatedTarget.selectedNode,
            nearbyNodes = validatedTarget.evidenceNodes,
            sourceCandidates = sourceCandidates,
            comment = comment,
            status = if (comment.isBlank()) AnnotationStatusDto.OPEN else writtenStatus,
            targetEvidence = targetEvidence,
            targetReliability = targetReliability,
        )
        return item.copy(editSurfaceCandidates = EditSurfaceCandidateService.build(item, screen))
    }

    fun targetEvidenceFor(
        targetType: FeedbackTargetType,
        selectedNode: FixThisNode?,
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
    ): TargetEvidence {
        val identityHint = IdentityHintFactory.from(selectedNode)
        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = selectedNode,
            nodes = screen.roots.flatMap { root -> root.mergedNodes },
            identityHint = identityHint,
        )
        return TargetEvidence(
            identityHint = identityHint,
            occurrence = occurrence,
            sourceInterpretation = SourceInterpretationFactory.from(sourceCandidates),
            evidenceQuality = if (identityHint != null || occurrence != null || sourceCandidates.isNotEmpty()) {
                EvidenceQuality.STRUCTURED
            } else {
                EvidenceQuality.BASIC
            },
            screenshotKinds = screen.screenshot.availableKinds(),
            warnings = buildList {
                if (targetType == FeedbackTargetType.AREA) {
                    add("Occurrence is not applicable for visual area selections.")
                }
                if (targetType == FeedbackTargetType.NODE && selectedNode == null) {
                    add("No selected semantics node was available for target evidence.")
                }
            },
        )
    }

    fun targetReliabilityFor(
        screen: SnapshotDto,
        validatedTarget: ValidatedFeedbackTarget,
        sourceCandidates: List<SourceCandidate>,
        targetEvidence: TargetEvidence?,
    ): TargetReliability {
        val roots = screen.roots.map { root -> root.boundsInWindow }
        val meaningfulNodes = screen.allNodes().filter { node -> node.hasMeaningfulSemantic() }
        return reliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = when (validatedTarget.targetType) {
                    FeedbackTargetType.AREA -> TargetKind.AREA
                    FeedbackTargetType.NODE -> TargetKind.NODE
                },
                selectedNode = validatedTarget.selectedNode,
                nearbyNodes = validatedTarget.evidenceNodes,
                sourceCandidates = sourceCandidates,
                targetEvidence = targetEvidence,
                semanticCoverage = TargetReliabilityCalculator.coverageFor(
                    roots = roots,
                    meaningfulNodes = meaningfulNodes,
                    targetBounds = validatedTarget.storedBounds,
                ),
                screenFingerprintAvailable = screen.fingerprint != null,
            ),
        )
    }

    fun refreshSourceEvidence(item: AnnotationDto, screen: SnapshotDto, sourceIndex: SourceIndex): AnnotationDto {
        var refreshed = item
        if (item.sourceCandidates.isNotEmpty()) {
            val sourceCandidates = stalenessChecker.annotate(item.sourceCandidates, sourceIndex)
            if (sourceCandidates != item.sourceCandidates) {
                refreshed = item.withRefreshedSourceEvidence(screen, sourceCandidates)
            }
        }
        return refreshed
    }

    private fun AnnotationDto.withRefreshedSourceEvidence(
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
    ): AnnotationDto {
        val targetType = when (target) {
            is AnnotationTargetDto.Area -> FeedbackTargetType.AREA
            is AnnotationTargetDto.Node -> FeedbackTargetType.NODE
        }
        val bounds = when (val annotationTarget = target) {
            is AnnotationTargetDto.Area -> annotationTarget.boundsInWindow
            is AnnotationTargetDto.Node -> annotationTarget.boundsInWindow
        }
        val evidence = targetEvidenceFor(
            targetType = targetType,
            selectedNode = selectedNode,
            screen = screen,
            sourceCandidates = sourceCandidates,
        )
        val reliability = targetReliabilityFor(
            screen = screen,
            validatedTarget = ValidatedFeedbackTarget(
                targetType = targetType,
                selectedNode = selectedNode,
                storedBounds = bounds,
                evidenceNodes = nearbyNodes,
            ),
            sourceCandidates = sourceCandidates,
            targetEvidence = evidence,
        )
        val refreshed = copy(
            sourceCandidates = sourceCandidates,
            targetEvidence = evidence,
            targetReliability = reliability,
        )
        return refreshed.copy(editSurfaceCandidates = EditSurfaceCandidateService.build(refreshed, screen))
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
        FeedbackTargetType.NODE -> {
            val node = requireNotNull(selectedNode) {
                "evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"
            }
            nodeEvidenceNodes(screen, node)
        }
    }

    private fun validateFinitePositiveBounds(bounds: FixThisRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) { "Selection bounds must have positive size" }
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
            .filter { evidence ->
                if (hasOverlappingEvidence) {
                    evidence.overlaps
                } else {
                    true
                }
            }
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

    private fun sourceCandidatesFor(
        sourceIndex: SourceIndex?,
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
        activityName: String?,
    ): List<SourceCandidate> {
        val raw = sourceIndex
            ?.takeIf { it.entries.isNotEmpty() }
            ?.let { SourceMatcher.match(it, selectedNode, nearbyNodes, activityName) }
            .orEmpty()
        if (raw.isEmpty() || sourceIndex == null) return raw
        return stalenessChecker.annotate(raw, sourceIndex)
    }

    private fun SnapshotScreenshotDto?.availableKinds(): List<String> {
        val screenshot = this ?: return emptyList()
        return buildList {
            if (!screenshot.fullPath.isNullOrBlank() || !screenshot.desktopFullPath.isNullOrBlank()) add("full")
            if (!screenshot.cropPath.isNullOrBlank() || !screenshot.desktopCropPath.isNullOrBlank()) add("crop")
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

    private data class AreaEvidenceNode(
        val node: FixThisNode,
        val overlaps: Boolean,
        val overlapArea: Float,
        val centerDistance: Float,
    )

    data class ValidatedFeedbackTarget(
        val targetType: FeedbackTargetType,
        val selectedNode: FixThisNode?,
        val storedBounds: FixThisRect,
        val evidenceNodes: List<FixThisNode>,
    )

    private companion object {
        const val MaxEvidenceNodes = 8
    }
}
