package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditIntentAnalyzer {
    fun analyze(item: AnnotationDto, screen: SnapshotDto?): EditIntent {
        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerTag = owner?.node?.testTag
        return analyze(
            comment = item.comment,
            selectedNode = item.selectedNode,
            ownerTag = ownerTag,
            sourceCandidates = item.sourceCandidates,
        )
    }

    fun analyzeCommentOnly(comment: String): EditIntent = analyze(
        comment = comment,
        selectedNode = null,
        ownerTag = null,
        sourceCandidates = emptyList(),
    )

    private fun analyze(
        comment: String,
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): EditIntent {
        val signals = EditIntentLexicon.classify(comment)
        return if (signals.isEmpty() || signals == setOf(RawEditIntentSignal.CONTENT_ONLY)) {
            unknown()
        } else {
            when {
                RawEditIntentSignal.SPACING in signals ->
                    EditIntent(EditSurfaceKindDto.SPACING, listOf(EditSurfaceReasonDto.LAYOUT_INTENT))
                RawEditIntentSignal.TYPOGRAPHY in signals && (selectedNode == null || selectedNode.isTextLike()) ->
                    EditIntent(EditSurfaceKindDto.TYPOGRAPHY, listOf(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
                RawEditIntentSignal.COLOR_STYLE in signals ->
                    colorIntent(signals, selectedNode, ownerTag, sourceCandidates)
                else -> unknown()
            }
        }
    }

    private fun colorIntent(
        signals: Set<RawEditIntentSignal>,
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): EditIntent = when {
        isChipLike(selectedNode, ownerTag, sourceCandidates) ->
            EditIntent(
                EditSurfaceKindDto.CHIP_COLOR,
                listOf(EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.COMPONENT_DEFINITION),
            )
        RawEditIntentSignal.BACKGROUND_STYLE in signals ->
            EditIntent(EditSurfaceKindDto.CONTAINER_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
        RawEditIntentSignal.TEXT_STYLE in signals || selectedNode.isTextLike() ->
            EditIntent(EditSurfaceKindDto.TEXT_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
        else -> unknown()
    }

    private fun isChipLike(
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): Boolean {
        val terms = buildList {
            selectedNode?.testTag?.let(::add)
            ownerTag?.let(::add)
            sourceCandidates.mapNotNullTo(this) { it.ownerComposable }
        }
        return terms.any { term ->
            term.contains("chip", ignoreCase = true) ||
                term.contains("badge", ignoreCase = true) ||
                term.contains("pill", ignoreCase = true)
        }
    }

    private fun FixThisNode?.isTextLike(): Boolean = this != null &&
        (
            text.isNotEmpty() ||
                editableText?.isNotBlank() == true ||
                role.equals("Text", ignoreCase = true)
            )

    private fun unknown(): EditIntent = EditIntent(EditSurfaceKindDto.UNKNOWN, emptyList())
}
