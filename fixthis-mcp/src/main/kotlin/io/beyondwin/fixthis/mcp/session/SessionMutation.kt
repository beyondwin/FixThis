package io.beyondwin.fixthis.mcp.session

sealed interface SessionMutation {
    data class AddScreen(val screen: SnapshotDto, val now: Long) : SessionMutation
    data class AddScreenWithItems(
        val screen: SnapshotDto,
        val items: List<AnnotationDto>,
        val now: Long,
    ) : SessionMutation
    data class ReplaceItems(val items: List<AnnotationDto>, val now: Long) : SessionMutation
    data class DeleteScreen(val screenId: String, val now: Long) : SessionMutation
    data class DeleteItem(val itemId: String, val now: Long) : SessionMutation
    data class AddHandoff(
        val batch: FeedbackHandoffBatch,
        val items: List<AnnotationDto>,
        val now: Long,
    ) : SessionMutation
    data class Close(val now: Long) : SessionMutation
    data class MarkReadyForAgent(val now: Long) : SessionMutation
}
