package io.beyondwin.fixthis.mcp.session

private const val SEMANTIC_KEY_SEPARATOR = "\u0000"

internal fun AnnotationDto.legacySemanticDraftKey(): String? = semanticDraftKey().takeIf { clientDraftKey() == null }

internal fun AnnotationDto.incomingSemanticDraftKey(): String? = semanticDraftKey().takeIf { clientDraftKey() != null }

private fun AnnotationDto.semanticDraftKey(): String? {
    val commentKey = comment.trim().takeIf { it.isNotEmpty() }
    return commentKey?.let { key ->
        listOf(
            target.semanticTypeKey(),
            target.semanticNodeKey(),
            target.semanticBoundsKey(),
            key,
        ).joinToString(SEMANTIC_KEY_SEPARATOR)
    }
}

private fun AnnotationTargetDto.semanticTypeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> "node"
    is AnnotationTargetDto.Area -> "area"
}

private fun AnnotationTargetDto.semanticNodeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> nodeUid
    is AnnotationTargetDto.Area -> ""
}

private fun AnnotationTargetDto.semanticBoundsKey(): String {
    val bounds = when (this) {
        is AnnotationTargetDto.Node -> boundsInWindow
        is AnnotationTargetDto.Area -> boundsInWindow
    }
    return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        .joinToString(",") { kotlin.math.round(it).toInt().toString() }
}
