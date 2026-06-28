package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto

internal object AgentVerificationGuidanceReasons {
    fun manualReasons(
        item: AnnotationDto,
        warnings: List<TargetReliabilityWarning>,
    ): List<String> = buildList {
        if (item.target is AnnotationTargetDto.Area ||
            TargetReliabilityWarning.VISUAL_AREA_ONLY in warnings
        ) {
            add("visual-area")
        }
        if (item.sourceCandidates.isEmpty()) {
            add("missing-source")
        }
        if (TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET in warnings) {
            add("missing-compose-target")
        }
        if (TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED in warnings) {
            add("forced-mismatch")
        }
        if (TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED in warnings) {
            add("sensitive-redaction")
        }
    }.distinct()

    fun hintOnlyReasons(context: GuidanceContext): List<String> = buildList {
        val warnings = context.warnings
        if (TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings) {
            add("interop-risk")
        }
        if (TargetReliabilityWarning.SOURCE_INDEX_STALE in warnings || context.topSource?.stale == true) {
            add("stale-source")
        }
        if (hasSourceRisk(context.topSource)) add("source-risk")
        if (context.isOverlap) add("overlap-risk")
        if (context.hasDuplicateReference) add("duplicate-marker")
        if (TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings) {
            add("low-target-confidence")
        }
    }.distinct()

    fun manualActions(reasons: List<String>): List<String> = buildList {
        add("claim-feedback")
        if ("visual-area" in reasons || "forced-mismatch" in reasons || "overlap-risk" in reasons) {
            add("compare-screenshot")
        }
        add("verify-manually")
    }

    fun fallbackReasons(context: GuidanceContext): List<String> = buildList {
        add(context.targetConfidence.reasonToken())
        when (context.topSource?.confidence) {
            SelectionConfidence.HIGH -> add("strong-source")
            SelectionConfidence.MEDIUM -> add("useful-source")
            SelectionConfidence.LOW -> add("weak-source")
            SelectionConfidence.NONE -> add("source-hint-only")
            null -> add("no-source")
        }
        if (context.margin != null && context.margin < CLEAR_MARGIN_THRESHOLD) add("low-margin")
        if (hasSourceRisk(context.topSource)) add("source-risk")
    }.distinct()

    fun hasSourceRisk(sourceCandidate: SourceCandidate?): Boolean = sourceCandidate?.riskFlags?.isNotEmpty() == true

    private fun TargetConfidence.reasonToken(): String = when (this) {
        TargetConfidence.HIGH -> "strong-target"
        TargetConfidence.MEDIUM -> "medium-target"
        TargetConfidence.LOW -> "weak-target"
        TargetConfidence.UNKNOWN -> "unknown-target"
    }

    private const val CLEAR_MARGIN_THRESHOLD = 0.50
}
