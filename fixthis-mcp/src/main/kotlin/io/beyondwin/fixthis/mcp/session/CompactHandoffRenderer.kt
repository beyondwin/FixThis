package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

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
        appendLine()

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

            val itemsForScreen = indexedItems.map { it.value }
            val grouping = InstanceGroupingHelper.compute(itemsForScreen)

            val detectorItems = indexedItems.map { entry ->
                val isArea = entry.value.target is AnnotationTargetDto.Area
                val hasWeakLabels = entry.value.selectedNode?.text?.isEmpty() ?: true
                AnnotationOverlapDetector.Item(
                    id = entry.value.itemId,
                    bounds = when (val t = entry.value.target) {
                        is AnnotationTargetDto.Area -> t.boundsInWindow
                        is AnnotationTargetDto.Node -> t.boundsInWindow
                    },
                    isAreaSelection = isArea,
                    hasWeakLabels = hasWeakLabels,
                )
            }
            val groups = AnnotationOverlapDetector.detect(detectorItems)

            val itemById = indexedItems.associate { it.value.itemId to it.value }

            var preCounter = globalCounter
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
                val bounds = when (val t = annotation.target) {
                    is AnnotationTargetDto.Area -> t.boundsInWindow
                    is AnnotationTargetDto.Node -> t.boundsInWindow
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
            val duplicateMap = DuplicateMarkerDetector.detect(dupDetectorItems)

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
                    appendCompactItem(globalCounter, annotation, isOverlapGroup, label, isLeader, groupSize, dupRefMarker, sourceRoot)
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

    private fun StringBuilder.appendCompactItem(
        number: Int,
        item: AnnotationDto,
        isOverlap: Boolean,
        instanceLabel: InstanceLabel? = null,
        isInstanceLeader: Boolean = false,
        groupSize: Int = 0,
        dupRefMarker: Int? = null,
        sourceRoot: String? = null,
    ) {
        val title = item.comment.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(No request provided)"
        val prefix = if (item.severity == AnnotationSeverityDto.HIGH) "[!] " else ""
        appendLine("[$number] ${prefix}${title.inlineSafe()}")
        appendLine("  id: ${item.itemId}")
        appendLine(compactUiLine(item, isOverlap, instanceLabel, dupRefMarker))
        item.screenshotCrop?.desktopCropPath?.let { appendLine("crop: ${it.inlineSafe()}") }
        appendCandidatesBlock(item, sourceRoot)
        appendReliabilityBlock(item.targetReliability)
        if (isInstanceLeader && groupSize >= 2 && !isOverlap) {
            appendLine("  note: $groupSize markers map to same call site — likely list-rendered; disambiguate by instance index")
        }
        appendLine()
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

    private fun StringBuilder.appendReliabilityBlock(reliability: TargetReliability?) {
        if (reliability == null) return
        val confidence = reliability.confidence.name.lowercase()
        if (confidence == "unknown" && reliability.warnings.isEmpty()) return
        appendLine("  targetConfidence=$confidence")
        reliability.warnings.forEach { warning ->
            appendLine("  warning: ${warning.message()}")
        }
    }

    private fun TargetReliabilityWarning.message(): String = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY -> "visual area only; verify screenshot and bounds"
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET ->
            "no meaningful Compose semantics node covered this target"
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP ->
            "possible AndroidView/WebView area; source candidates may not explain rendered pixels"
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> "source candidates are close; verify before editing"
        TargetReliabilityWarning.SOURCE_INDEX_STALE -> "source index may be stale"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED ->
            "screen changed after capture; user force-saved this item"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE ->
            "screen fingerprint unavailable; mismatch check was skipped"
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> "sensitive text was redacted from target evidence"
    }

    private fun formatCandidateLine(
        candidate: io.beyondwin.fixthis.compose.core.model.SourceCandidate,
        rank: Int,
        computedMargin: Double? = null,
        sourceRoot: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("${candidate.relativeFileWithLine(sourceRoot)}  conf=${candidate.confidence.name.lowercase()}")
        if (rank == 1) {
            val effectiveMargin = candidate.scoreMargin ?: computedMargin
            effectiveMargin?.let { margin ->
                sb.append("  margin=${"%.2f".format(margin)}")
            }
            val tokens = candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().take(4)
            if (tokens.isNotEmpty()) {
                sb.append("  matched=[${tokens.joinToString(", ")}]")
            }
        }
        if (candidate.stale == true) {
            sb.append(" ⚠ stale: ${candidate.staleReason ?: "unspecified"}")
        }
        return sb.toString()
    }

    private fun reasonTokenFor(reason: String): String? = when (reason) {
        "selected text" -> "text"
        "selected contentDescription" -> "contentDescription"
        "selected testTag" -> "tag"
        "selected testTag convention composable" -> "compTag"
        "selected role" -> "role"
        "nearby text" -> "nearbyText"
        "nearby contentDescription" -> "nearbyContentDescription"
        "nearby testTag" -> "nearbyTag"
        "nearby role" -> "nearbyRole"
        "activity" -> "activity"
        "selected stringResource" -> "stringRes"
        "arbitrary literal" -> "literal"
        "legacy fallback" -> "legacy"
        else -> null
    }

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
