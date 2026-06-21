package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto

internal enum class AgentVerificationMode {
    SOURCE_FIRST,
    CORROBORATE,
    HINT_ONLY,
    MANUAL,
}

internal data class AgentVerificationGuidance(
    val mode: AgentVerificationMode,
    val reasons: List<String>,
    val beforeEdit: List<String>,
)

internal object AgentVerificationGuidanceClassifier {
    fun classify(
        item: AnnotationDto,
        isOverlap: Boolean,
        hasDuplicateReference: Boolean,
    ): AgentVerificationGuidance {
        val targetReliability = item.targetReliability
        val targetConfidence = targetReliability?.confidence ?: TargetConfidence.UNKNOWN
        val warnings = targetReliability?.warnings.orEmpty()
        val topSource = item.sourceCandidates.firstOrNull()
        val margin = topSource?.scoreMargin ?: computedSourceMargin(item.sourceCandidates)

        val manualReasons = manualReasons(item, warnings)
        if (manualReasons.isNotEmpty()) {
            return AgentVerificationGuidance(
                mode = AgentVerificationMode.MANUAL,
                reasons = manualReasons,
                beforeEdit = manualActions(manualReasons),
            )
        }

        val hintOnlyReasons = hintOnlyReasons(warnings, topSource, isOverlap, hasDuplicateReference)
        if (hintOnlyReasons.isNotEmpty()) {
            return AgentVerificationGuidance(
                mode = AgentVerificationMode.HINT_ONLY,
                reasons = hintOnlyReasons,
                beforeEdit = listOf(
                    "claim-feedback",
                    "compare-screenshot",
                    "review-edit-surface",
                    "verify-manually",
                ),
            )
        }

        val strongTarget = targetConfidence == TargetConfidence.HIGH
        val strongSource = topSource?.confidence == SelectionConfidence.HIGH && topSource.hasSourceRisk().not()
        val clearMargin = margin != null && margin >= CLEAR_MARGIN_THRESHOLD
        if (strongTarget && strongSource && clearMargin) {
            return AgentVerificationGuidance(
                mode = AgentVerificationMode.SOURCE_FIRST,
                reasons = listOf("strong-target", "strong-source", "clear-margin"),
                beforeEdit = listOf("claim-feedback", "inspect-source", "compare-screenshot"),
            )
        }

        val reasons = buildList {
            add(targetConfidence.reasonToken())
            when (topSource?.confidence) {
                SelectionConfidence.HIGH -> add("strong-source")
                SelectionConfidence.MEDIUM -> add("useful-source")
                SelectionConfidence.LOW -> add("weak-source")
                SelectionConfidence.NONE -> add("source-hint-only")
                null -> add("no-source")
            }
            if (margin != null && margin < CLEAR_MARGIN_THRESHOLD) add("low-margin")
            if (topSource?.riskFlags?.isNotEmpty() == true) add("source-risk")
        }.distinct()

        val mode = if (
            targetConfidence == TargetConfidence.LOW ||
            targetConfidence == TargetConfidence.UNKNOWN ||
            topSource == null ||
            topSource.confidence == SelectionConfidence.LOW ||
            topSource.confidence == SelectionConfidence.NONE
        ) {
            AgentVerificationMode.HINT_ONLY
        } else {
            AgentVerificationMode.CORROBORATE
        }

        val actions = when (mode) {
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

        return AgentVerificationGuidance(mode = mode, reasons = reasons, beforeEdit = actions)
    }

    private fun manualReasons(
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

    private fun hintOnlyReasons(
        warnings: List<TargetReliabilityWarning>,
        topSource: SourceCandidate?,
        isOverlap: Boolean,
        hasDuplicateReference: Boolean,
    ): List<String> = buildList {
        if (TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings) {
            add("interop-risk")
        }
        if (TargetReliabilityWarning.SOURCE_INDEX_STALE in warnings || topSource?.stale == true) {
            add("stale-source")
        }
        if (topSource?.hasSourceRisk() == true) add("source-risk")
        if (isOverlap) add("overlap-risk")
        if (hasDuplicateReference) add("duplicate-marker")
        if (TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings) {
            add("low-target-confidence")
        }
    }.distinct()

    private fun manualActions(reasons: List<String>): List<String> = buildList {
        add("claim-feedback")
        if ("visual-area" in reasons || "forced-mismatch" in reasons || "overlap-risk" in reasons) {
            add("compare-screenshot")
        }
        add("verify-manually")
    }

    private fun computedSourceMargin(candidates: List<SourceCandidate>): Double? {
        val rank1 = candidates.getOrNull(0) ?: return null
        val rank2 = candidates.getOrNull(1) ?: return null
        return (rank1.score - rank2.score).takeIf { it > 0 }
    }

    private fun TargetConfidence.reasonToken(): String = when (this) {
        TargetConfidence.HIGH -> "strong-target"
        TargetConfidence.MEDIUM -> "medium-target"
        TargetConfidence.LOW -> "weak-target"
        TargetConfidence.UNKNOWN -> "unknown-target"
    }

    private fun SourceCandidate?.hasSourceRisk(): Boolean = this?.riskFlags?.isNotEmpty() == true

    private const val CLEAR_MARGIN_THRESHOLD = 0.50
}
