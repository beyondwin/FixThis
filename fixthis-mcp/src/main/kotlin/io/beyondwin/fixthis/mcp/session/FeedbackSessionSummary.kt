package io.beyondwin.fixthis.mcp.session

import kotlinx.serialization.Serializable

@Serializable
data class FeedbackSessionIndex(
    val schemaVersion: String = "2.0",
    val updatedAtEpochMillis: Long,
    val sessions: List<FeedbackSessionSummary> = emptyList(),
)

@Serializable
data class FeedbackSessionSummary(
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: SessionStatusDto,
    val screensCount: Int,
    val itemsCount: Int,
    val unresolvedItemsCount: Int,
    val inProgressItemsCount: Int = 0,
    val draftItemsCount: Int = 0,
    val sentBatchesCount: Int = 0,
) {
    companion object {
        fun from(session: SessionDto): FeedbackSessionSummary = FeedbackSessionSummary(
            sessionId = session.sessionId,
            packageName = session.packageName,
            projectRoot = session.projectRoot,
            createdAtEpochMillis = session.createdAtEpochMillis,
            updatedAtEpochMillis = session.updatedAtEpochMillis,
            status = session.status,
            screensCount = session.screens.size,
            itemsCount = session.items.size,
            unresolvedItemsCount = session.items.count { item ->
                item.status !in setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.WONT_FIX)
            },
            inProgressItemsCount = session.items.count { item -> item.status == AnnotationStatusDto.IN_PROGRESS },
            draftItemsCount = session.items.count { item -> item.delivery == FeedbackDelivery.DRAFT },
            sentBatchesCount = session.handoffBatches.size,
        )
    }
}
