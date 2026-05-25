package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.mcp.session.AnnotationDto
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeTrustFixtureInput(
    val projectDir: String,
    val packageName: String,
    val cases: List<RuntimeTrustCaseInput>,
    val strict: Boolean = false,
)

@Serializable
data class RuntimeTrustCaseInput(
    val caseId: String,
    val runtimeTarget: RuntimeTargetSelector,
)

@Serializable
data class RuntimeTargetSelector(
    val text: String? = null,
    val testTag: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
)

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
        )
    }
}
