package io.beyondwin.fixthis.mcp.session

object CompactHandoffRenderer {
    fun render(session: SessionDto): String = buildString {
        appendLine("# FixThis Feedback Handoff")
        appendLine()
        appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
        appendLine()
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Feedback Items: `${session.items.size}`")
        appendLine()

        val orderedItems = session.items.withIndex()
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
            val screen = session.screens.firstOrNull { it.screenId == screenId }
            val displayName = screen?.displayName ?: "Screen"
            appendLine("Screen ${screenId}: ${displayName.inlineSafe()}")
            screen?.screenshot?.desktopFullPath?.let {
                appendLine("screenshot: ${it.inlineSafe()}")
            }
            val w = screen?.screenshot?.width
            val h = screen?.screenshot?.height
            if (w != null && h != null) {
                appendLine("viewport: ${w}×${h}")
            }
            appendLine()

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

            // Build a map from itemId -> AnnotationDto for quick lookup
            val itemById = indexedItems.associate { it.value.itemId to it.value }

            var overlapGroupCounter = 0
            groups.forEach { group ->
                val isOverlapGroup = group.size > 1
                if (isOverlapGroup) {
                    overlapGroupCounter += 1
                    appendLine("Overlap group $overlapGroupCounter (resolve one marker at a time):")
                    appendLine()
                }
                group.forEach { detectorItem ->
                    val annotation = itemById[detectorItem.id] ?: return@forEach
                    globalCounter += 1
                    appendCompactItem(globalCounter, annotation, isOverlapGroup)
                }
            }
        }
    }

    private fun StringBuilder.appendCompactItem(number: Int, item: AnnotationDto, isOverlap: Boolean) {
        val title = item.comment.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(No request provided)"
        appendLine("${number}. [marker $number] ${title.inlineSafe()}")
        val targetSummary = compactTargetSummary(item, isOverlap)
        appendLine("target: $targetSummary")
        item.screenshotCrop?.desktopCropPath?.let { appendLine("crop: ${it.inlineSafe()}") }
        appendLine(compactSourceLine(item))
        appendLine()
    }

    private fun compactTargetSummary(item: AnnotationDto, isOverlap: Boolean): String {
        val node = item.selectedNode
        val role = node?.role?.takeIf { it.isNotBlank() } ?: when (item.target) {
            is AnnotationTargetDto.Area -> "Area"
            is AnnotationTargetDto.Node -> "Node"
        }
        val label = node?.text?.firstOrNull()
            ?: node?.contentDescription?.firstOrNull()
            ?: node?.testTag
            ?: "(unlabelled)"
        val bounds = when (val target = item.target) {
            is AnnotationTargetDto.Area -> target.boundsInWindow.formatBounds()
            is AnnotationTargetDto.Node -> target.boundsInWindow.formatBounds()
        }
        val base = "${role.inlineSafe()} \"${label.inlineSafe()}\" bounds=$bounds"
        return if (isOverlap) "$base; targetRisk=overlap" else base
    }

    private fun compactSourceLine(item: AnnotationDto): String {
        val candidate = item.sourceCandidates.firstOrNull() ?: return "src? unknown"
        val confidence = candidate.confidence.name.lowercase()
        val why = candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().joinToString("+")
        val risk = candidate.riskFlags.firstOrNull()?.name?.lowercase()?.replace('_', '-')
        val parts = mutableListOf("src? ${candidate.fileWithLine()} $confidence")
        if (why.isNotBlank()) parts.add("why=$why")
        if (risk != null) parts.add("risk=$risk")
        return parts.joinToString("; ")
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
}
