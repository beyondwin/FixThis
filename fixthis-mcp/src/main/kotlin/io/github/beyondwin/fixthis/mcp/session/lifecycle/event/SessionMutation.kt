package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackHandoffBatch
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus

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
    data class AttachRuntimeEvidence(
        val attachments: List<RuntimeEvidenceAttachment>,
        val itemIds: List<String>,
        val expectedScreenId: String,
        val aggregateStatus: RuntimeEvidenceStatus,
        val now: Long,
    ) : SessionMutation
    data class UpdateRuntimeEvidencePolicy(
        val policy: RuntimeEvidencePolicy,
        val now: Long,
    ) : SessionMutation
}
