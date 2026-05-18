package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    private const val TEXT_SOURCE_NOTE =
        "source candidate identifies data text; editSurface identifies likely rendering code"

    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        val roleDecision = EditSurfaceRoleClassifier.classify(item, intent)
        val candidates = when {
            item.sourceCandidates.isEmpty() -> listOf(emptySourceCandidate(intent, roleDecision))
            intent.primaryKind == EditSurfaceKindDto.UNKNOWN &&
                roleDecision.role != EditSurfaceRoleDto.COPY_OR_DATA -> emptyList()
            else -> sourceCandidates(item, screen, intent, roleDecision)
        }
        return candidates.distinctBy { it.file to it.line }.take(2)
    }

    private fun sourceCandidates(
        item: AnnotationDto,
        screen: SnapshotDto?,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): List<EditSurfaceCandidateDto> {
        val candidates = mutableListOf<EditSurfaceCandidateDto>()
        ownerCandidate(item, screen, intent, roleDecision)?.let { candidates += it }
        selectedTextCandidate(item, intent, roleDecision).takeIf { candidates.isEmpty() }?.let { candidates += it }
        spacingCandidate(item, intent, roleDecision)?.let { candidates += it }
        return candidates
    }

    private fun emptySourceCandidate(
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto {
        val kind = when (roleDecision.role) {
            EditSurfaceRoleDto.VISUAL_AREA, EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceKindDto.UNKNOWN
            else -> intent.primaryKind
        }
        return EditSurfaceCandidateDto(
            kind = kind,
            role = roleDecision.role,
            file = "(visual area)",
            confidence = roleDecision.confidenceCap,
            reasons = intent.reasons,
            note = roleDecision.note,
        )
    }

    private fun ownerCandidate(
        item: AnnotationDto,
        screen: SnapshotDto?,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? {
        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerComposable = TestTagConvention.parse(item.selectedNode?.testTag)?.composableName
            ?: TestTagConvention.parse(owner?.node?.testTag)?.composableName
            ?: return null
        return item.sourceCandidates.firstOrNull { it.matchesComposable(ownerComposable) }
            ?.toEditSurface(
                kind = normalizedKind(intent),
                roleDecision = roleDecision,
                confidence = SelectionConfidence.MEDIUM,
                reasons = intent.reasons +
                    EditSurfaceReasonDto.TARGET_OWNER +
                    EditSurfaceReasonDto.COMPONENT_DEFINITION,
            )
    }

    private fun SourceCandidate.matchesComposable(composable: String): Boolean {
        val filenameMatches = file.substringAfterLast('/').removeSuffix(".kt") == composable
        return filenameMatches ||
            matchedTerms.any { it == composable } ||
            ownerComposable == composable
    }

    private fun selectedTextCandidate(
        item: AnnotationDto,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? = item.sourceCandidates
        .firstOrNull { it.matchReasons.contains("selected text") }
        ?.toEditSurface(
            kind = normalizedKind(intent),
            roleDecision = roleDecision,
            confidence = SelectionConfidence.MEDIUM,
            reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
        )

    private fun spacingCandidate(
        item: AnnotationDto,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? {
        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            return item.sourceCandidates.firstOrNull()
                ?.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
                    roleDecision = roleDecision,
                    confidence = SelectionConfidence.LOW,
                    reasons = intent.reasons + EditSurfaceReasonDto.CALL_SITE,
                )
        }
        return null
    }

    private fun normalizedKind(intent: EditIntent): EditSurfaceKindDto = when (intent.primaryKind) {
        EditSurfaceKindDto.UNKNOWN -> EditSurfaceKindDto.COMPONENT_RENDERER
        else -> intent.primaryKind
    }

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
        note = roleDecision.note ?: TEXT_SOURCE_NOTE.takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
    )

    private fun minConfidence(left: SelectionConfidence, right: SelectionConfidence): SelectionConfidence {
        val order = listOf(
            SelectionConfidence.NONE,
            SelectionConfidence.LOW,
            SelectionConfidence.MEDIUM,
            SelectionConfidence.HIGH,
        )
        return if (order.indexOf(left) <= order.indexOf(right)) left else right
    }
}
