package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto

internal object HandoffQualitySummary {
    fun render(
        items: List<AnnotationDto>,
        overlapGroups: List<List<AnnotationOverlapDetector.Item>>,
        duplicateMap: Map<String, Int>,
    ): String? {
        val summary = Counts(
            lowConfidenceTargets = items.count { it.targetReliability?.confidence == TargetConfidence.LOW },
            warningTargets = items.count { it.targetReliability?.warnings.orEmpty().isNotEmpty() },
            overlapGroups = overlapGroups.count { it.size > 1 },
            duplicateMarkers = duplicateMap.size,
            visualAreas = items.count { it.target is AnnotationTargetDto.Area },
            redactedTargets = items.count { TargetSummaryFormatter.isRedacted(it) },
            staleSourceCandidateItems = items.count { item -> item.sourceCandidates.any { it.stale == true } },
            itemsWithoutSourceCandidates = items.count { it.sourceCandidates.isEmpty() },
        )
        return summary.render()
    }

    private data class Counts(
        val lowConfidenceTargets: Int,
        val warningTargets: Int,
        val overlapGroups: Int,
        val duplicateMarkers: Int,
        val visualAreas: Int,
        val redactedTargets: Int,
        val staleSourceCandidateItems: Int,
        val itemsWithoutSourceCandidates: Int,
    ) {
        fun render(): String? {
            val parts = listOfNotNull(
                lowConfidenceTargets.label("low-confidence target"),
                warningTargets.label("warning target"),
                overlapGroups.label("overlap group"),
                duplicateMarkers.label("duplicate marker"),
                visualAreas.label("visual area"),
                redactedTargets.label("redacted target"),
                staleSourceCandidateItems.label("stale source candidate"),
                itemsWithoutSourceCandidates.label(
                    singular = "item without source candidates",
                    plural = "items without source candidates",
                ),
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Handoff quality: ", separator = ", ")
        }
    }

    private fun Int.label(singular: String, plural: String = "${singular}s"): String? = when (this) {
        0 -> null
        1 -> "1 $singular"
        else -> "$this $plural"
    }
}
