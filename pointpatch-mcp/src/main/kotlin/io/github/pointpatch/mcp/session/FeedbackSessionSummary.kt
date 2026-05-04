package io.github.pointpatch.mcp.session

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
    val status: FeedbackSessionStatus,
    val screensCount: Int,
    val itemsCount: Int,
    val unresolvedItemsCount: Int,
) {
    companion object {
        fun from(session: FeedbackSession): FeedbackSessionSummary =
            FeedbackSessionSummary(
                sessionId = session.sessionId,
                packageName = session.packageName,
                projectRoot = session.projectRoot,
                createdAtEpochMillis = session.createdAtEpochMillis,
                updatedAtEpochMillis = session.updatedAtEpochMillis,
                status = session.status,
                screensCount = session.screens.size,
                itemsCount = session.items.size,
                unresolvedItemsCount = session.items.count { item ->
                    item.status !in setOf(FeedbackItemStatus.RESOLVED, FeedbackItemStatus.WONT_FIX)
                },
            )
    }
}
