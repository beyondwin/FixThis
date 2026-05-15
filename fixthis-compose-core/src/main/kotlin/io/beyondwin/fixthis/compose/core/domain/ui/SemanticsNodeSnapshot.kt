package io.beyondwin.fixthis.compose.core.domain.ui

enum class SemanticsTreeKind {
    MERGED,
    UNMERGED,
}

data class SemanticsNodeSnapshot(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: SemanticsTreeKind,
    val boundsInWindow: DomainRect,
    val text: List<String> = emptyList(),
    val editableText: String? = null,
    val contentDescription: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
    val stateDescription: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val actions: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val path: List<String> = emptyList(),
    val rawProperties: Map<String, String> = emptyMap(),
) {
    fun hasMeaningfulSemantic(): Boolean = text.isNotEmpty() ||
        editableText != null ||
        contentDescription.isNotEmpty() ||
        role != null ||
        testTag != null ||
        actions.isNotEmpty() ||
        stateDescription != null
}
