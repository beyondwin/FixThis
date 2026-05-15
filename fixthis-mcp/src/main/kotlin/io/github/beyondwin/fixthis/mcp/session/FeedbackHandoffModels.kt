package io.github.beyondwin.fixthis.mcp.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackDelivery {
    @SerialName("draft")
    DRAFT,

    @SerialName("sent")
    SENT,
}

@Serializable
data class FeedbackHandoffBatch(
    val batchId: String,
    val sequenceNumber: Int,
    val createdAtEpochMillis: Long,
    val itemIds: List<String>,
    val markdownSnapshot: String? = null,
)
