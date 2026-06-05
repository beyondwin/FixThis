package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength

internal data class EditSurfaceEvidence(
    val strong: Boolean,
    val exactCopyMatch: Boolean,
    val ambiguous: Boolean,
    val proximityOnly: Boolean,
    val shared: Boolean,
    val confidentCallSite: Boolean,
) {
    companion object {
        private val EXACT_COPY_REASONS = setOf(
            "selected text",
            "selected stringResource",
            "selected resolved stringResource",
            "selected contentDescription",
        )

        fun from(candidate: SourceCandidate?): EditSurfaceEvidence {
            if (candidate == null) {
                return EditSurfaceEvidence(
                    strong = false,
                    exactCopyMatch = false,
                    ambiguous = false,
                    proximityOnly = false,
                    shared = false,
                    confidentCallSite = false,
                )
            }
            val flags = candidate.riskFlags
            val ambiguous = SourceCandidateRisk.AMBIGUOUS in flags
            return EditSurfaceEvidence(
                strong = candidate.evidenceStrength == SourceEvidenceStrength.STRONG,
                exactCopyMatch = candidate.matchReasons.any { it in EXACT_COPY_REASONS },
                ambiguous = ambiguous,
                proximityOnly = SourceCandidateRisk.NEARBY_ONLY in flags || SourceCandidateRisk.TEXT_ONLY in flags,
                shared = SourceCandidateRisk.SHARED_COMPONENT in flags,
                confidentCallSite = candidate.confidence == SelectionConfidence.HIGH &&
                    candidate.ownerComposable != null &&
                    !ambiguous,
            )
        }
    }
}
