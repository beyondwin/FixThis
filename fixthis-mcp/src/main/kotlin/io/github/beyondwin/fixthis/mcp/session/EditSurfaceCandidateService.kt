package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        val roleDecision = EditSurfaceRoleClassifier.classify(item, intent)
        if (item.sourceCandidates.isEmpty()) {
            // VISUAL_AREA and INTEROP_RISK never identify a precise code surface,
            // so they must not leak intent.primaryKind (e.g., "gap" -> SPACING)
            // into the kind field. Other empty-source paths keep the intent kind.
            val emptySourceKind = when (roleDecision.role) {
                EditSurfaceRoleDto.VISUAL_AREA, EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceKindDto.UNKNOWN
                else -> if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.UNKNOWN else intent.primaryKind
            }
            return listOf(
                EditSurfaceCandidateDto(
                    kind = emptySourceKind,
                    role = roleDecision.role,
                    file = "(visual area)",
                    confidence = roleDecision.confidenceCap,
                    reasons = intent.reasons,
                    note = roleDecision.note,
                ),
            )
        }
        if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN && roleDecision.role != EditSurfaceRoleDto.COPY_OR_DATA) {
            return emptyList()
        }

        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerComposable = componentNameFrom(item.selectedNode?.testTag)
            ?: componentNameFrom(owner?.node?.testTag)
        val candidates = mutableListOf<EditSurfaceCandidateDto>()

        ownerComposable?.let { composable ->
            item.sourceCandidates.firstOrNull { candidate ->
                candidate.file.substringAfterLast('/').removeSuffix(".kt") == composable ||
                    candidate.matchedTerms.any { it == composable } ||
                    candidate.ownerComposable == composable
            }?.let { source ->
                candidates += source.toEditSurface(
                    kind = if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.COMPONENT_RENDERER else intent.primaryKind,
                    roleDecision = roleDecision,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons +
                        EditSurfaceReasonDto.TARGET_OWNER +
                        EditSurfaceReasonDto.COMPONENT_DEFINITION,
                )
            }
        }

        if (candidates.isEmpty()) {
            item.sourceCandidates.firstOrNull { it.matchReasons.contains("selected text") }?.let { source ->
                candidates += source.toEditSurface(
                    kind = if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.COMPONENT_RENDERER else intent.primaryKind,
                    roleDecision = roleDecision,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
                )
            }
        }

        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            item.sourceCandidates.firstOrNull()?.let { source ->
                candidates += source.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
                    roleDecision = roleDecision,
                    confidence = SelectionConfidence.LOW,
                    reasons = intent.reasons + EditSurfaceReasonDto.CALL_SITE,
                )
            }
        }

        return candidates.distinctBy { it.file to it.line }.take(2)
    }

    private fun componentNameFrom(testTag: String?): String? = TestTagConvention.parse(testTag)?.composableName

    private fun SourceCandidate.toEditSurface(
        kind: EditSurfaceKindDto,
        roleDecision: EditSurfaceRoleDecision,
        confidence: SelectionConfidence,
        reasons: List<EditSurfaceReasonDto>,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        role = roleDecision.role,
        file = file,
        repoFile = repoFile,
        line = line,
        confidence = minConfidence(confidence, roleDecision.confidenceCap),
        reasons = reasons.distinct(),
        note = roleDecision.note ?: "source candidate identifies data text; editSurface identifies likely rendering code".takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
    )

    private fun minConfidence(left: SelectionConfidence, right: SelectionConfidence): SelectionConfidence {
        val order = listOf(SelectionConfidence.NONE, SelectionConfidence.LOW, SelectionConfidence.MEDIUM, SelectionConfidence.HIGH)
        return if (order.indexOf(left) <= order.indexOf(right)) left else right
    }
}
