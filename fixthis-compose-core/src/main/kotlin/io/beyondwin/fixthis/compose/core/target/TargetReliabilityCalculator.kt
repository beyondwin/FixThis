package io.beyondwin.fixthis.compose.core.target

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.SemanticCoverage
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.beyondwin.fixthis.compose.core.model.TargetKind
import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.beyondwin.fixthis.compose.core.model.withWarnings
import kotlin.math.sqrt

object TargetReliabilityCalculator {
    private const val LOW_MARGIN_THRESHOLD = 0.15

    fun calculate(input: TargetReliabilityInput): TargetReliability {
        val reasons = buildReasons(input)
        val warnings = buildWarnings(input)
        val baseConfidence = confidenceFor(input, reasons, warnings)
        return TargetReliability(
            confidence = baseConfidence,
            reasons = reasons.distinct(),
            warnings = warnings.distinct(),
        )
    }

    fun addWarnings(
        reliability: TargetReliability?,
        warnings: Collection<TargetReliabilityWarning>,
    ): TargetReliability = (reliability ?: TargetReliability()).withWarnings(warnings)

    fun coverageFor(
        roots: List<FixThisRect>,
        meaningfulNodes: List<FixThisNode>,
        targetBounds: FixThisRect,
    ): SemanticCoverage {
        val rootBounds = roots.firstOrNull { root -> root.containsCenterOf(targetBounds) } ?: roots.firstOrNull()
        val overlapping = meaningfulNodes.count { node -> node.boundsInWindow.intersects(targetBounds) }
        val nearest = meaningfulNodes
            .map { node -> node.boundsInWindow.centerDistanceTo(targetBounds) }
            .minOrNull()
            ?.let { sqrt(it).toFloat() }
        return SemanticCoverage(
            rootBounds = rootBounds,
            overlappingMeaningfulNodeCount = overlapping,
            nearestMeaningfulNodeDistancePx = nearest,
        )
    }

    private fun buildReasons(input: TargetReliabilityInput): List<TargetReliabilityReason> = buildList {
        val identity = input.targetEvidence?.identityHint
        if (
            identity?.source == IdentityHintSource.TEST_TAG_CONVENTION &&
            identity.confidence == IdentityHintConfidence.HIGH
        ) {
            add(TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY)
        }
        if (input.selectedNode?.hasMeaningfulSemantic() == true) {
            add(TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE)
        }
        val top = input.sourceCandidates.firstOrNull()
        if (top?.confidence == SelectionConfidence.HIGH && !top.hasLowMargin()) {
            add(TargetReliabilityReason.STRONG_SOURCE_CANDIDATE)
        } else if (top != null && top.confidence != SelectionConfidence.NONE) {
            add(TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE)
        }
        if (input.targetKind == TargetKind.AREA) {
            add(TargetReliabilityReason.VISUAL_AREA_SELECTION)
        }
        if (input.selectedNode?.isSensitive == true) {
            add(TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE)
        }
        if (isEmpty()) {
            add(TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE)
        }
    }

    private fun buildWarnings(input: TargetReliabilityInput): List<TargetReliabilityWarning> = buildList {
        if (input.targetKind == TargetKind.AREA) {
            add(TargetReliabilityWarning.VISUAL_AREA_ONLY)
        }
        val hasMeaningfulTarget = input.selectedNode?.hasMeaningfulSemantic() == true ||
            input.nearbyNodes.any { node -> node.hasMeaningfulSemantic() } ||
            input.semanticCoverage.overlappingMeaningfulNodeCount > 0
        if (!hasMeaningfulTarget) {
            add(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET)
        }
        if (
            input.targetKind == TargetKind.AREA &&
            input.semanticCoverage.rootBounds != null &&
            input.semanticCoverage.overlappingMeaningfulNodeCount == 0
        ) {
            add(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)
        }
        if (input.sourceCandidates.firstOrNull()?.hasLowMargin() == true) {
            add(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN)
        }
        if (input.sourceCandidates.any { candidate -> candidate.stale == true }) {
            add(TargetReliabilityWarning.SOURCE_INDEX_STALE)
        }
        if (!input.screenFingerprintAvailable) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE)
        }
        if (input.forcedFingerprintMismatch) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED)
        }
        if (input.selectedNode?.isSensitive == true) {
            add(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
        }
    }

    private fun confidenceFor(
        input: TargetReliabilityInput,
        reasons: List<TargetReliabilityReason>,
        warnings: List<TargetReliabilityWarning>,
    ): TargetConfidence {
        if (warnings.any { warning -> warning.reducesConfidence() }) return TargetConfidence.LOW
        if (TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY in reasons &&
            TargetReliabilityReason.STRONG_SOURCE_CANDIDATE in reasons
        ) {
            return TargetConfidence.HIGH
        }
        if (TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE in reasons || input.sourceCandidates.isNotEmpty()) {
            return TargetConfidence.MEDIUM
        }
        return TargetConfidence.UNKNOWN
    }

    private fun TargetReliabilityWarning.reducesConfidence(): Boolean = when (this) {
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
        -> false
        TargetReliabilityWarning.VISUAL_AREA_ONLY,
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET,
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
        TargetReliabilityWarning.SOURCE_INDEX_STALE,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
        -> true
    }

    private fun SourceCandidate.hasLowMargin(): Boolean =
        scoreMargin != null && scoreMargin < LOW_MARGIN_THRESHOLD ||
            SourceCandidateRisk.AMBIGUOUS in riskFlags

    private fun FixThisRect.containsCenterOf(other: FixThisRect): Boolean {
        val x = (other.left + other.right) / 2f
        val y = (other.top + other.bottom) / 2f
        return contains(x, y)
    }

    private fun FixThisRect.intersects(other: FixThisRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun FixThisRect.centerDistanceTo(other: FixThisRect): Double {
        val dx = ((left + right) / 2.0) - ((other.left + other.right) / 2.0)
        val dy = ((top + bottom) / 2.0) - ((other.top + other.bottom) / 2.0)
        return dx * dx + dy * dy
    }
}
