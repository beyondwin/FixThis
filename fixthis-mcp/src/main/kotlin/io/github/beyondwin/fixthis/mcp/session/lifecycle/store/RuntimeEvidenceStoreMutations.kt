package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventJournal
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventPayloadFactory
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutation
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionReducer
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionCompactionCoordinator
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.runtimeEvidence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.runtimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val RUNTIME_EVIDENCE_CONTEXT_CHANGED_PREFIX = "RUNTIME_EVIDENCE_CONTEXT_CHANGED:"

internal class RuntimeEvidenceStoreMutations(
    private val lock: ReentrantLock,
    private val clock: () -> Long,
    private val stateStore: SessionStateStore,
    private val journal: SessionEventJournal,
    private val compactionCoordinator: SessionCompactionCoordinator,
) {
    fun attach(
        sessionId: String,
        expectedScreenId: String,
        itemIds: List<String>,
        attachments: List<RuntimeEvidenceAttachment>,
        aggregateStatus: RuntimeEvidenceStatus,
    ): SessionDto {
        require(itemIds.isNotEmpty()) { "itemIds must not be empty" }
        require(attachments.isNotEmpty()) { "attachments must not be empty" }
        val updated = lock.withInterruptibleLock {
            val session = try {
                stateStore.get(sessionId)
            } catch (error: FeedbackSessionException) {
                if (error.message.orEmpty().startsWith("Unknown feedback session:")) throw contextChanged()
                throw error
            }
            requireStableContext(session, expectedScreenId, itemIds)
            val now = clock()
            val mutation = SessionMutation.AttachRuntimeEvidence(
                attachments = attachments,
                itemIds = itemIds,
                expectedScreenId = expectedScreenId,
                aggregateStatus = aggregateStatus,
                now = now,
            )
            val next = SessionReducer.reduce(session, mutation)
            journal.append(
                sessionId = sessionId,
                type = "runtimeEvidenceCaptured",
                payload = SessionEventPayloadFactory.runtimeEvidence(
                    sessionId,
                    expectedScreenId,
                    itemIds,
                    attachments,
                    aggregateStatus,
                ),
            )
            stateStore.commit(session, next)
        }
        compactionCoordinator.compactAfterMutation(sessionId)
        return updated
    }

    fun updatePolicy(sessionId: String, policy: RuntimeEvidencePolicy): SessionDto {
        val updated = lock.withLock {
            val session = stateStore.get(sessionId)
            if (session.status == SessionStatusDto.CLOSED) {
                throw FeedbackSessionException(
                    "$SESSION_CLOSED_PREFIX Cannot update runtime evidence policy on a closed feedback session.",
                )
            }
            val mutation = SessionMutation.UpdateRuntimeEvidencePolicy(policy, clock())
            val next = SessionReducer.reduce(session, mutation)
            journal.append(
                sessionId = sessionId,
                type = "runtimeEvidencePolicyUpdated",
                payload = SessionEventPayloadFactory.runtimeEvidencePolicy(sessionId, policy),
            )
            stateStore.commit(session, next)
        }
        compactionCoordinator.compactAfterMutation(sessionId)
        return updated
    }

    private fun requireStableContext(
        session: SessionDto,
        expectedScreenId: String,
        itemIds: List<String>,
    ) {
        val targetItemIds = itemIds.toSet()
        val matchingItemIds = session.items
            .filter { it.screenId == expectedScreenId && it.itemId in targetItemIds }
            .map { it.itemId }
            .toSet()
        if (session.status == SessionStatusDto.CLOSED || matchingItemIds != targetItemIds) {
            throw contextChanged()
        }
    }

    private fun contextChanged(): FeedbackSessionException = FeedbackSessionException(
        "$RUNTIME_EVIDENCE_CONTEXT_CHANGED_PREFIX Session, screen, or feedback items changed before evidence linkage.",
    )
}

private inline fun <T> ReentrantLock.withInterruptibleLock(block: () -> T): T {
    lockInterruptibly()
    return try {
        block()
    } finally {
        unlock()
    }
}
