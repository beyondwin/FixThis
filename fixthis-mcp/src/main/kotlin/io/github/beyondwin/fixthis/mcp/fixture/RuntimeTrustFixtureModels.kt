package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.target.TargetBoundaryContextFormatter
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeTrustFixtureInput(
    val projectDir: String,
    val packageName: String,
    val sourceIndexPath: String? = null,
    val cases: List<RuntimeTrustCaseInput>,
    val strict: Boolean = false,
)

@Serializable
data class RuntimeTrustCaseInput(
    val caseId: String,
    val runtimeTarget: RuntimeTargetSelector,
    val navigateBefore: RuntimeTargetSelector? = null,
)

@Serializable
data class RuntimeTargetSelector(
    val text: String? = null,
    val testTag: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val visualArea: RuntimeVisualAreaSelector? = null,
)

@Serializable
data class RuntimeVisualAreaSelector(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun rect(): FixThisRect = FixThisRect(left, top, right, bottom)
}

@Serializable
data class RuntimeTrustFixtureOutput(
    val schemaVersion: Int = 1,
    val status: String,
    val cases: List<RuntimeTrustCaseOutput>,
)

@Serializable
data class RuntimeTrustCaseOutput(
    val caseId: String,
    val observed: RuntimeTrustObserved? = null,
    val failures: List<String> = emptyList(),
    val environment: List<String> = emptyList(),
)

@Serializable
data class RuntimeTrustObserved(
    val candidates: List<RuntimeTrustCandidate> = emptyList(),
    val confidence: String? = null,
    val sourceConfidence: String? = null,
    val riskFlags: List<String>? = null,
    val warnings: List<String>? = null,
    val callSites: List<RuntimeTrustCallSite>? = null,
    val boundaryContext: List<RuntimeTrustBoundaryContext>? = null,
)

@Serializable
data class RuntimeTrustBoundaryContext(
    val kind: String,
    val summary: String,
)

@Serializable
data class RuntimeTrustCallSite(
    val file: String,
    val line: Int? = null,
    val mostLikely: Boolean = false,
    val recommendedEditSite: Boolean = false,
)

@Serializable
data class RuntimeTrustCandidate(
    val path: String,
    val line: Int? = null,
    val confidence: String? = null,
    val riskFlags: List<String> = emptyList(),
)

object RuntimeTrustObservationMapper {
    private const val MAX_OBSERVED_CANDIDATES: Int = 3

    fun fromAnnotation(item: AnnotationDto): RuntimeTrustObserved {
        val top = item.sourceCandidates.firstOrNull()
        return RuntimeTrustObserved(
            candidates = item.sourceCandidates.take(MAX_OBSERVED_CANDIDATES).map { candidate ->
                RuntimeTrustCandidate(
                    path = candidate.repoFile ?: candidate.file,
                    line = candidate.line,
                    confidence = candidate.confidence.name.lowercase(),
                    riskFlags = candidate.riskFlags.map { it.name },
                )
            },
            confidence = item.targetReliability?.confidence?.name?.lowercase(),
            sourceConfidence = top?.confidence?.name?.lowercase(),
            riskFlags = top?.riskFlags?.map { it.name },
            warnings = item.targetReliability?.warnings?.map { it.name },
            callSites = top?.callSites?.map { site ->
                RuntimeTrustCallSite(
                    file = site.file,
                    line = site.line,
                    mostLikely = site.mostLikely,
                    recommendedEditSite = site.recommendedEditSite,
                )
            },
            boundaryContext = TargetBoundaryContextFormatter.structuredRows(item).map { row ->
                RuntimeTrustBoundaryContext(
                    kind = row.kind.name.lowercase(),
                    summary = row.summary,
                )
            }.takeIf { it.isNotEmpty() },
        )
    }
}
