package io.beyondwin.fixthis.compose.core.domain.session

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot

data class Session(
    val id: SessionId,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val snapshots: List<Snapshot> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val handoffBatches: List<SessionHandoffBatch> = emptyList(),
    val status: SessionStatus = SessionStatus.ACTIVE,
)

data class SessionHandoffBatch(
    val id: String,
    val sequenceNumber: Int,
    val createdAtEpochMillis: Long,
    val annotationIds: List<String>,
    val markdownSnapshot: String? = null,
)

enum class SessionStatus {
    ACTIVE,
    READY_FOR_AGENT,
    CLOSED,
}
