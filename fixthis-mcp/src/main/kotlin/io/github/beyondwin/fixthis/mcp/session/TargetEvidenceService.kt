package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceMatcher
import io.github.beyondwin.fixthis.compose.core.target.TargetEvidenceFactory
import io.github.beyondwin.fixthis.compose.core.target.TargetEvidenceInput
import io.github.beyondwin.fixthis.compose.core.target.TargetReliabilityCalculator
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
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
    private val targetValidator: FeedbackTargetValidator = FeedbackTargetValidator(),
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
    ): ValidatedFeedbackTarget = targetValidator.validate(
        screen = screen,
        targetType = targetType,
        bounds = bounds,
        nodeUid = nodeUid,
        comment = comment,
        allowBlankComment = allowBlankComment,
        missingNodeContext = missingNodeContext,
    )

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
    ): TargetEvidence = TargetEvidenceFactory.build(
        TargetEvidenceInput(
            targetKind = when (targetType) {
                FeedbackTargetType.AREA -> TargetKind.AREA
                FeedbackTargetType.NODE -> TargetKind.NODE
            },
            selectedNode = selectedNode,
            mergedNodes = screen.roots.flatMap { root -> root.mergedNodes },
            sourceCandidates = sourceCandidates,
            screenshotKinds = screen.screenshot.availableKinds(),
        ),
    )

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
}
