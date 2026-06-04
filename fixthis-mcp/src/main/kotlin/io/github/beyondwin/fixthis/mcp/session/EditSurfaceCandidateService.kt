package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    private const val TEXT_SOURCE_NOTE =
        "source candidate identifies data text; editSurface identifies likely rendering code"

    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
        conventions: TestTagConventionSet = TestTagConventionSet.Default,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        val roleDecision = EditSurfaceRoleClassifier.classify(item, intent)
        val candidates = when {
            item.sourceCandidates.isEmpty() -> listOf(emptySourceCandidate(intent, roleDecision))
            intent.primaryKind == EditSurfaceKindDto.UNKNOWN &&
                roleDecision.role != EditSurfaceRoleDto.COPY_OR_DATA &&
                roleDecision.role != EditSurfaceRoleDto.LAYOUT_OR_STYLE -> emptyList()
            else -> sourceCandidates(item, screen, intent, roleDecision, conventions)
        }
        return candidates.distinctBy { it.file to it.line }.take(2)
    }

    private fun sourceCandidates(
        item: AnnotationDto,
        screen: SnapshotDto?,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
        conventions: TestTagConventionSet,
    ): List<EditSurfaceCandidateDto> {
        val candidates = mutableListOf<EditSurfaceCandidateDto>()
        if (roleDecision.role == EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            spacingCandidate(item, intent, roleDecision)?.let { candidates += it }
        }
        ownerCandidate(item, screen, intent, roleDecision, conventions)?.let { candidates += it }
        selectedTextCandidate(item, intent, roleDecision).takeIf { candidates.isEmpty() }?.let { candidates += it }
        if (roleDecision.role != EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            spacingCandidate(item, intent, roleDecision)?.let { candidates += it }
        }
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
        val scored = EditSurfaceConfidencePolicy.score(roleDecision.role, null)
        return EditSurfaceCandidateDto(
            kind = kind,
            role = roleDecision.role,
            file = "(visual area)",
            confidence = scored.confidence,
            confidenceBasis = scored.basis,
            reasons = intent.reasons,
            note = roleDecision.note,
        )
    }

    private fun ownerCandidate(
        item: AnnotationDto,
        screen: SnapshotDto?,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
        conventions: TestTagConventionSet,
    ): EditSurfaceCandidateDto? {
        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerComposable = conventions.parse(item.selectedNode?.testTag)?.composableName
            ?: conventions.parse(owner?.node?.testTag)?.composableName
            ?: return null
        return item.sourceCandidates.firstOrNull { it.matchesComposable(ownerComposable) }
            ?.toEditSurface(
                kind = normalizedKind(intent),
                roleDecision = roleDecision,
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
        .firstOrNull { candidate ->
            candidate.matchReasons.any { reason ->
                reason == "selected text" ||
                    reason == "selected stringResource" ||
                    reason == "selected resolved stringResource"
            }
        }
        ?.toEditSurface(
            kind = normalizedKind(intent),
            roleDecision = roleDecision,
            reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
        )

    private fun spacingCandidate(
        item: AnnotationDto,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? {
        if (intent.primaryKind == EditSurfaceKindDto.SPACING || roleDecision.role == EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            val source = item.sourceCandidates.firstOrNull { candidate ->
                candidate.matchReasons.contains("layout renderer context")
            } ?: item.sourceCandidates.firstOrNull()
            return source
                ?.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
                    roleDecision = roleDecision,
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
        reasons: List<EditSurfaceReasonDto>,
    ): EditSurfaceCandidateDto {
        val scored = EditSurfaceConfidencePolicy.score(roleDecision.role, this)
        return EditSurfaceCandidateDto(
            kind = kind,
            role = roleDecision.role,
            file = file,
            repoFile = repoFile,
            line = line,
            confidence = scored.confidence,
            confidenceBasis = scored.basis,
            reasons = reasons.distinct(),
            note = roleDecision.note ?: TEXT_SOURCE_NOTE.takeIf {
                kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
            },
        )
    }
}
