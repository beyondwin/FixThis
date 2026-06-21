package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.handoffMessage
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceCandidateDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceKindDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.target.TargetBoundaryContextFormatter
import io.github.beyondwin.fixthis.mcp.session.target.TargetBoundaryGuidance
import io.github.beyondwin.fixthis.mcp.session.target.TargetOwnerResolver
import io.github.beyondwin.fixthis.mcp.session.target.TargetSummaryFormatter

object CompactHandoffRenderer {
    private const val MAX_CANDIDATES_RENDERED = 3
    fun render(session: SessionDto, itemIds: List<String>? = null): String = buildString {
        val effectiveSession = if (itemIds == null) {
            session
        } else {
            session.copy(items = session.items.filter { it.itemId in itemIds })
        }
        appendLine("# FixThis Feedback Handoff")
        appendLine()
        appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
        appendLine()
        appendLine("- Package: `${effectiveSession.packageName}`")
        val sourceRoot = computeSourceRoot(effectiveSession)
        if (sourceRoot != null) {
            appendLine("- Source root: `$sourceRoot`")
        }

        val orderedItems = effectiveSession.items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<AnnotationDto>> { it.value.sequenceNumber ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
        if (orderedItems.isEmpty()) {
            appendLine("No feedback items.")
            appendLine()
            return@buildString
        }

        val itemsByScreen = orderedItems.groupBy { it.value.screenId }
        var precomputedMarkerCounter = 0
        val analysesByScreen = itemsByScreen.mapValues { (_, indexedItems) ->
            analyzeScreen(indexedItems, precomputedMarkerCounter).also { analysis ->
                precomputedMarkerCounter += analysis.groups.sumOf { it.size }
            }
        }
        val allItems = orderedItems.map { it.value }
        val allOverlapGroups = analysesByScreen.values.flatMap { it.groups }
        val allDuplicateMap = analysesByScreen.values.flatMap { it.duplicateMap.entries }
            .associate { it.key to it.value }
        HandoffQualitySummary.render(allItems, allOverlapGroups, allDuplicateMap)?.let {
            appendLine(it)
        }
        appendLine()

        var globalCounter = 0
        itemsByScreen.forEach { (screenId, indexedItems) ->
            val screen = effectiveSession.screens.firstOrNull { it.screenId == screenId }
            val displayName = screen?.displayName ?: "Screen"
            appendLine("Screen ${screenId.take(8)}: ${displayName.inlineSafe()}")
            (screen?.screenshot?.desktopFullPath ?: screen?.screenshot?.fullPath)?.let {
                appendLine("screenshot: ${it.inlineSafe()}")
            }
            val w = screen?.screenshot?.width
            val h = screen?.screenshot?.height
            if (w != null && h != null) {
                appendLine("viewport: $w×$h")
            }
            val activityName = screen?.activityName
            if (activityName != null && activityName != displayName) {
                appendLine("activity: $activityName")
            }
            appendLine()

            val analysis = analysesByScreen.getValue(screenId)
            val itemById = analysis.indexedItems.associate { it.value.itemId to it.value }
            val grouping = analysis.grouping
            val groups = analysis.groups
            val duplicateMap = analysis.duplicateMap

            var overlapGroupCounter = 0
            groups.forEach { group ->
                val isOverlapGroup = group.size > 1
                if (isOverlapGroup) {
                    overlapGroupCounter += 1
                    appendLine("Overlap group $overlapGroupCounter (resolve one marker at a time):")
                }
                group.forEach { detectorItem ->
                    val annotation = itemById[detectorItem.id] ?: return@forEach
                    globalCounter += 1
                    val dupRefMarker = duplicateMap[annotation.itemId]
                    val label = if (dupRefMarker == null) grouping.labels[annotation.itemId] else null
                    val isLeader = annotation.itemId in grouping.leaderItemIds
                    val groupSize = label?.total ?: 0
                    appendCompactItem(
                        CompactItemRenderContext(
                            number = globalCounter,
                            item = annotation,
                            screen = screen,
                            isOverlap = isOverlapGroup,
                            instanceLabel = label,
                            isInstanceLeader = isLeader,
                            groupSize = groupSize,
                            dupRefMarker = dupRefMarker,
                            sourceRoot = sourceRoot,
                        ),
                    )
                }
            }
        }
        appendLine("---")
        appendLine("agent_protocol:")
        appendLine("  before_work: fixthis_claim_feedback({sessionId, itemId})")
        appendLine("  on_complete: fixthis_resolve_feedback({sessionId, itemId, status: resolved|wont_fix|needs_clarification, summary})")
        appendLine("  user_console_reflects_within: 2s")
        appendLine("session_id: ${effectiveSession.sessionId}")
    }

    private data class ScreenHandoffAnalysis(
        val indexedItems: List<IndexedValue<AnnotationDto>>,
        val grouping: InstanceGrouping,
        val groups: List<List<AnnotationOverlapDetector.Item>>,
        val markerByItemId: Map<String, Int>,
        val duplicateMap: Map<String, Int>,
    )

    private data class CompactItemRenderContext(
        val number: Int,
        val item: AnnotationDto,
        val screen: SnapshotDto?,
        val isOverlap: Boolean,
        val instanceLabel: InstanceLabel? = null,
        val isInstanceLeader: Boolean = false,
        val groupSize: Int = 0,
        val dupRefMarker: Int? = null,
        val sourceRoot: String? = null,
    )

    private fun analyzeScreen(
        indexedItems: List<IndexedValue<AnnotationDto>>,
        startingMarker: Int,
    ): ScreenHandoffAnalysis {
        val itemsForScreen = indexedItems.map { it.value }
        val grouping = InstanceGroupingHelper.compute(itemsForScreen)
        val detectorItems = indexedItems.map { entry ->
            val isArea = entry.value.target is AnnotationTargetDto.Area
            val hasWeakLabels = entry.value.selectedNode?.text?.isEmpty() ?: true
            AnnotationOverlapDetector.Item(
                id = entry.value.itemId,
                bounds = when (val target = entry.value.target) {
                    is AnnotationTargetDto.Area -> target.boundsInWindow
                    is AnnotationTargetDto.Node -> target.boundsInWindow
                },
                isAreaSelection = isArea,
                hasWeakLabels = hasWeakLabels,
            )
        }
        val groups = AnnotationOverlapDetector.detect(detectorItems)
        val itemById = indexedItems.associate { it.value.itemId to it.value }
        var preCounter = startingMarker
        val markerByItemId = mutableMapOf<String, Int>()
        groups.forEach { group ->
            group.forEach { detectorItem ->
                if (itemById.containsKey(detectorItem.id)) {
                    preCounter += 1
                    markerByItemId[detectorItem.id] = preCounter
                }
            }
        }
        val dupDetectorItems = indexedItems.mapNotNull { entry ->
            val annotation = entry.value
            val marker = markerByItemId[annotation.itemId] ?: return@mapNotNull null
            val fileLine = annotation.sourceCandidates.firstOrNull()?.fileWithLine()
            val pathLeaves = annotation.selectedNode?.path ?: emptyList()
            val bounds = when (val target = annotation.target) {
                is AnnotationTargetDto.Area -> target.boundsInWindow
                is AnnotationTargetDto.Node -> target.boundsInWindow
            }
            DuplicateMarkerDetector.Item(
                itemId = annotation.itemId,
                markerNumber = marker,
                key = DuplicateMarkerDetector.Key(
                    fileLine = fileLine,
                    testTag = annotation.selectedNode?.testTag,
                    pathLeaves = pathLeaves,
                    bounds = bounds,
                ),
            )
        }
        return ScreenHandoffAnalysis(
            indexedItems = indexedItems,
            grouping = grouping,
            groups = groups,
            markerByItemId = markerByItemId,
            duplicateMap = DuplicateMarkerDetector.detect(dupDetectorItems),
        )
    }

    private fun StringBuilder.appendCompactItem(context: CompactItemRenderContext) {
        val item = context.item
        val title = item.comment.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(No request provided)"
        val prefix = if (item.severity == AnnotationSeverityDto.HIGH) "[!] " else ""
        appendLine("[${context.number}] ${prefix}${title.inlineSafe()}")
        appendLine("  id: ${item.itemId}")
        val owner = TargetOwnerResolver.resolve(item, context.screen)
        appendLine("  ${TargetSummaryFormatter.render(item, owner)}")
        TargetBoundaryGuidance.from(item).compactToken?.let { token ->
            appendLine("  targetBoundary=$token")
        }
        TargetBoundaryContextFormatter.compactLine(item)?.let { line ->
            appendLine("  $line")
        }
        appendLine(compactUiLine(item, context.isOverlap, context.instanceLabel, context.dupRefMarker))
        item.screenshotCrop?.desktopCropPath?.let { appendLine("crop: ${it.inlineSafe()}") }
        appendEditSurfaceBlock(item)
        appendCandidatesBlock(item, context.sourceRoot)
        appendReliabilityBlock(item.targetReliability)
        appendVerificationGuidanceBlock(
            AgentVerificationGuidanceClassifier.classify(
                item = item,
                isOverlap = context.isOverlap,
                hasDuplicateReference = context.dupRefMarker != null,
            ),
        )
        if (context.isInstanceLeader && context.groupSize >= 2 && !context.isOverlap) {
            appendLine(
                "  note: ${context.groupSize} markers map to same call site — " +
                    "likely list-rendered; disambiguate by instance index",
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendEditSurfaceBlock(item: AnnotationDto) {
        item.editSurfaceCandidates.take(2).forEach { candidate ->
            appendLine("  ${candidate.formatEditSurfaceLine()}")
            candidate.note?.takeIf { it.isNotBlank() }?.let { note ->
                appendLine("  note: ${note.inlineSafe()}")
            }
        }
    }

    private fun EditSurfaceCandidateDto.formatEditSurfaceLine(): String {
        val kindToken = when (kind) {
            EditSurfaceKindDto.CONTAINER_COLOR -> "containerColor"
            EditSurfaceKindDto.TEXT_COLOR -> "textColor"
            EditSurfaceKindDto.TYPOGRAPHY -> "typography"
            EditSurfaceKindDto.SPACING -> "spacing"
            EditSurfaceKindDto.CHIP_COLOR -> "chipColor"
            EditSurfaceKindDto.COMPONENT_RENDERER -> "componentRenderer"
            EditSurfaceKindDto.UNKNOWN -> "unknown"
        }
        val fileLine = if (line != null) "$file:$line" else file
        val reasonTokens = reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
        val roleToken = role?.let { "  role=${it.name.lowercase().replace("_", "-")}" }.orEmpty()
        val basisToken = confidenceBasis?.takeIf { it.isNotBlank() }?.let { "  basis=${it.inlineSafe()}" }.orEmpty()
        return "editSurface: $kindToken$roleToken -> ${fileLine.inlineSafe()}  " +
            "conf=${confidence.name.lowercase()}  why=[$reasonTokens]$basisToken"
    }

    private fun StringBuilder.appendReliabilityBlock(reliability: TargetReliability?) {
        if (reliability == null) return
        val confidence = reliability.confidence.name.lowercase()
        appendLine("  targetConfidence=$confidence")
        appendLine("  targetAction=${reliability.compactActionToken()}")
        reliability.warnings.forEach { warning ->
            appendLine("  warning: ${warning.handoffMessage().inlineSafe()}")
        }
    }

    private fun StringBuilder.appendVerificationGuidanceBlock(guidance: AgentVerificationGuidance) {
        appendLine(
            "  verify: ${guidance.mode.token()}  because=${guidance.reasons.joinToString(",")}",
        )
        appendLine("  verifyBeforeEdit: ${guidance.beforeEdit.joinToString(",")}")
    }

    private fun AgentVerificationMode.token(): String = name.lowercase().replace("_", "-")

    private fun TargetReliability.compactActionToken(): String = when (confidence) {
        io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.HIGH -> "inspect-source-first"
        io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.MEDIUM -> "inspect-and-corroborate"
        io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW -> "treat-source-paths-as-hints"
        io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.UNKNOWN -> "verify-manually"
    }

    private fun StringBuilder.appendCandidatesBlock(item: AnnotationDto, sourceRoot: String?) {
        if (item.sourceCandidates.isEmpty()) {
            appendLine("  unknown")
            return
        }
        val rank1 = item.sourceCandidates.firstOrNull()
        val rank2 = item.sourceCandidates.getOrNull(1)
        val computedMargin = if (rank1 != null && rank2 != null) {
            (rank1.score - rank2.score).takeIf { it > 0 }
        } else {
            null
        }
        item.sourceCandidates.take(MAX_CANDIDATES_RENDERED).forEachIndexed { idx, candidate ->
            appendLine("  ${formatCandidateLine(candidate, idx + 1, computedMargin, sourceRoot)}")
        }
        val rank1Caution = item.sourceCandidates.firstOrNull()?.caution
        if (!rank1Caution.isNullOrBlank()) {
            appendLine("  note: ${rank1Caution.inlineSafe()}")
        }
    }

    private fun formatCandidateLine(
        candidate: io.github.beyondwin.fixthis.compose.core.model.SourceCandidate,
        rank: Int,
        computedMargin: Double? = null,
        sourceRoot: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("${candidate.relativeFileWithLine(sourceRoot)}  conf=${candidate.confidence.name.lowercase()}")
        if (rank == 1) {
            candidate.ownerComposable?.takeIf { it.isNotBlank() }?.let { owner ->
                sb.append("  owner=$owner")
            }
            val effectiveMargin = candidate.scoreMargin ?: computedMargin
            effectiveMargin?.let { margin ->
                sb.append("  margin=${"%.2f".format(margin)}")
            }
            val tokens = candidate.matchReasons.mapNotNull(REASON_TOKENS::get).distinct().take(4)
            if (tokens.isNotEmpty()) {
                sb.append("  matched=[${tokens.joinToString(", ")}]")
            }
        }
        sb.append(candidate.staleMarkerSuffix())
        return sb.toString()
    }

    private val REASON_TOKENS: Map<String, String> = mapOf(
        "selected text" to "text",
        "selected contentDescription" to "contentDescription",
        "selected testTag" to "tag",
        "selected testTag convention composable" to "compTag",
        "selected role" to "role",
        "selected resolved stringResource" to "resolvedStringRes",
        "nearby text" to "nearbyText",
        "nearby contentDescription" to "nearbyContentDescription",
        "nearby testTag" to "nearbyTag",
        "nearby role" to "nearbyRole",
        "activity" to "activity",
        "selected stringResource" to "stringRes",
        "arbitrary literal" to "literal",
        "legacy fallback" to "legacy",
    )

    private fun compactUiLine(
        item: AnnotationDto,
        isOverlap: Boolean,
        instanceLabel: InstanceLabel? = null,
        dupRefMarker: Int? = null,
    ): String {
        val node = item.selectedNode
        val explicitRole = node?.role?.takeIf { it.isNotBlank() }
        val explicitTag = node?.testTag?.takeIf { it.isNotBlank() }
        val rect = when (val target = item.target) {
            is AnnotationTargetDto.Area -> target.boundsInWindow
            is AnnotationTargetDto.Node -> target.boundsInWindow
        }
        val sb = StringBuilder("  ")
        if (explicitRole != null) sb.append("role=$explicitRole  ")
        if (explicitTag != null) sb.append("tag=$explicitTag  ")
        sb.append("box=${rect.formatBox()}")
        if (instanceLabel != null) {
            sb.append("  instance ${instanceLabel.index}/${instanceLabel.total}")
        }
        return when {
            dupRefMarker != null -> "$sb; targetRisk=duplicate-of-marker-$dupRefMarker"
            isOverlap -> "$sb; targetRisk=overlap"
            else -> sb.toString()
        }
    }
}
