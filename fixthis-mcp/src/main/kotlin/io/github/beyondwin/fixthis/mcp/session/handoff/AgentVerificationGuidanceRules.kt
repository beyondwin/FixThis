package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto

internal data class GuidanceContext(
    val targetConfidence: TargetConfidence,
    val warnings: List<TargetReliabilityWarning>,
    val topSource: SourceCandidate?,
    val margin: Double?,
    val isOverlap: Boolean,
    val hasDuplicateReference: Boolean,
)

internal object AgentVerificationGuidanceRules {
    fun classify(
        item: AnnotationDto,
        isOverlap: Boolean,
        hasDuplicateReference: Boolean,
    ): AgentVerificationGuidance {
        val context = contextFrom(item, isOverlap, hasDuplicateReference)
        return manualGuidance(item, context)
            ?: hintOnlyGuidance(context)
            ?: sourceFirstGuidance(context)
            ?: fallbackGuidance(item, context)
    }

    private fun contextFrom(
        item: AnnotationDto,
        isOverlap: Boolean,
        hasDuplicateReference: Boolean,
    ): GuidanceContext {
        val topSource = item.sourceCandidates.firstOrNull()
        return GuidanceContext(
            targetConfidence = item.targetReliability?.confidence ?: TargetConfidence.UNKNOWN,
            warnings = item.targetReliability?.warnings.orEmpty(),
            topSource = topSource,
            margin = topSource?.scoreMargin ?: computedSourceMargin(item.sourceCandidates),
            isOverlap = isOverlap,
            hasDuplicateReference = hasDuplicateReference,
        )
    }

    private fun manualGuidance(item: AnnotationDto, context: GuidanceContext): AgentVerificationGuidance? {
        val reasons = AgentVerificationGuidanceReasons.manualReasons(item, context.warnings)
        return reasons.takeIf { it.isNotEmpty() }?.let {
            AgentVerificationGuidance(
                mode = AgentVerificationMode.MANUAL,
                reasons = it,
                beforeEdit = AgentVerificationGuidanceReasons.manualActions(it),
            )
        }
    }

    private fun hintOnlyGuidance(context: GuidanceContext): AgentVerificationGuidance? {
        val reasons = AgentVerificationGuidanceReasons.hintOnlyReasons(context)
        return reasons.takeIf { it.isNotEmpty() }?.let {
            AgentVerificationGuidance(
                mode = AgentVerificationMode.HINT_ONLY,
                reasons = it,
                beforeEdit = listOf("claim-feedback", "compare-screenshot", "review-edit-surface", "verify-manually"),
            )
        }
    }

    private fun sourceFirstGuidance(context: GuidanceContext): AgentVerificationGuidance? = if (context.hasStrongTargetAndSource()) {
        AgentVerificationGuidance(
            mode = AgentVerificationMode.SOURCE_FIRST,
            reasons = listOf("strong-target", "strong-source", "clear-margin"),
            beforeEdit = listOf("claim-feedback", "inspect-source", "compare-screenshot"),
        )
    } else {
        null
    }

    private fun fallbackGuidance(item: AnnotationDto, context: GuidanceContext): AgentVerificationGuidance {
        val mode = if (context.requiresHintOnlyMode()) {
            AgentVerificationMode.HINT_ONLY
        } else {
            AgentVerificationMode.CORROBORATE
        }
        return AgentVerificationGuidance(
            mode = mode,
            reasons = AgentVerificationGuidanceReasons.fallbackReasons(context),
            beforeEdit = fallbackActions(mode, item),
        )
    }

    private fun fallbackActions(mode: AgentVerificationMode, item: AnnotationDto): List<String> = when (mode) {
        AgentVerificationMode.CORROBORATE -> buildList {
            add("claim-feedback")
            add("inspect-source")
            add("compare-screenshot")
            add("check-target-summary")
            if (item.editSurfaceCandidates.isNotEmpty()) add("review-edit-surface")
        }
        AgentVerificationMode.HINT_ONLY -> listOf(
            "claim-feedback",
            "inspect-source",
            "check-target-summary",
            "verify-manually",
        )
        AgentVerificationMode.SOURCE_FIRST,
        AgentVerificationMode.MANUAL,
        -> error("Mode handled before this branch")
    }

    private fun GuidanceContext.hasStrongTargetAndSource(): Boolean = targetConfidence == TargetConfidence.HIGH &&
        topSource?.confidence == SelectionConfidence.HIGH &&
        AgentVerificationGuidanceReasons.hasSourceRisk(topSource).not() &&
        margin != null &&
        margin >= CLEAR_MARGIN_THRESHOLD

    private fun GuidanceContext.requiresHintOnlyMode(): Boolean = targetConfidence in setOf(TargetConfidence.LOW, TargetConfidence.UNKNOWN) ||
        topSource == null ||
        topSource.confidence in setOf(SelectionConfidence.LOW, SelectionConfidence.NONE)

    private fun computedSourceMargin(candidates: List<SourceCandidate>): Double? = candidates.take(2).takeIf { it.size == 2 }?.let { (rank1, rank2) ->
        (rank1.score - rank2.score).takeIf { it > 0 }
    }

    private const val CLEAR_MARGIN_THRESHOLD = 0.50
}
