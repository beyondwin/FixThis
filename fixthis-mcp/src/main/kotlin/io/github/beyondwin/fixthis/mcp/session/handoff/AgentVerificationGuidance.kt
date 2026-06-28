package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto

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
    ): AgentVerificationGuidance = AgentVerificationGuidanceRules.classify(item, isOverlap, hasDuplicateReference)
}
