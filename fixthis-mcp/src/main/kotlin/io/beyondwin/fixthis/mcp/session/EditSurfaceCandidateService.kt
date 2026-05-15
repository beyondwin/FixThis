package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) return emptyList()

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
                    kind = intent.primaryKind,
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
                    kind = intent.primaryKind,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
                )
            }
        }

        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            item.sourceCandidates.firstOrNull()?.let { source ->
                candidates += source.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
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
        confidence: SelectionConfidence,
        reasons: List<EditSurfaceReasonDto>,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        file = file,
        repoFile = repoFile,
        line = line,
        confidence = confidence,
        reasons = reasons.distinct(),
        note = "source candidate identifies data text; editSurface identifies likely rendering code".takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
    )
}
