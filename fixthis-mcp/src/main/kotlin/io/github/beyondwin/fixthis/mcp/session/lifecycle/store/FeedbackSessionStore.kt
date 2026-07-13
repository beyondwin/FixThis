@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogCompactionTask
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Suppress("LongParameterList")
class FeedbackSessionStore(
    clock: () -> Long = { System.currentTimeMillis() },
    idGenerator: () -> String = { UUID.randomUUID().toString() },
    persistence: FeedbackSessionPersistence? = null,
    eventLogWriterProvider: ((sessionId: String) -> EventLogWriter)? = null,
    eventLogReaderProvider: ((sessionId: String) -> EventLogReader)? = null,
    eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactionTask)? = null,
    eventLogCompactionThreshold: Int = 1000,
    compactionFailureSink: ((sessionId: String, cause: Throwable) -> Unit)? = null,
) {
    private val delegate = FeedbackSessionStoreDelegate(
        clock = clock,
        idGenerator = idGenerator,
        persistence = persistence,
        eventLogWriterProvider = eventLogWriterProvider,
        eventLogReaderProvider = eventLogReaderProvider,
        eventLogCompactorProvider = eventLogCompactorProvider,
        eventLogCompactionThreshold = eventLogCompactionThreshold,
        compactionFailureSink = compactionFailureSink,
    )

    fun openSession(packageName: String, projectRoot: String): SessionDto = delegate.openSession(packageName, projectRoot)

    fun currentSession(): SessionDto? = delegate.currentSession()

    fun getSession(sessionId: String): SessionDto = delegate.getSession(sessionId)

    fun replaceSessionForDomain(session: SessionDto): SessionDto = delegate.replaceSessionForDomain(session)

    fun addOrReplaceScreenForDomain(sessionId: String, screen: SnapshotDto): SessionDto = delegate.addOrReplaceScreenForDomain(sessionId, screen)

    fun addOrReplaceAnnotationForDomain(sessionId: String, annotation: AnnotationDto): SessionDto = delegate.addOrReplaceAnnotationForDomain(sessionId, annotation)

    fun nextId(): String = delegate.nextId()

    fun listSessions(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList = delegate.listSessions(packageName, includeClosed)

    fun openExistingSession(sessionId: String): SessionDto = delegate.openExistingSession(sessionId)

    fun closeSession(sessionId: String): SessionDto = delegate.closeSession(sessionId)

    fun addScreen(sessionId: String, screen: SnapshotDto): SnapshotDto = delegate.addScreen(sessionId, screen)

    fun addScreenWithItems(
        sessionId: String,
        screen: SnapshotDto,
        items: List<AnnotationDto>,
        eventMetadata: JsonObject = JsonObject(emptyMap()),
    ): SessionDto = delegate.addScreenWithItems(sessionId, screen, items, eventMetadata)

    fun deleteScreen(sessionId: String, screenId: String): SessionDto = delegate.deleteScreen(sessionId, screenId)

    fun addItem(sessionId: String, item: AnnotationDto): AnnotationDto = delegate.addItem(sessionId, item)

    fun clearDraftItems(sessionId: String): SessionDto = delegate.clearDraftItems(sessionId)

    fun sendDraftToAgent(
        sessionId: String,
        markdownSnapshot: String?,
        targetItemIds: List<String>? = null,
    ): SessionDto = delegate.sendDraftToAgent(sessionId, markdownSnapshot, targetItemIds)

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto = delegate.markItemsHandedOff(sessionId, itemIds)

    fun markReadyForAgent(sessionId: String): SessionDto = delegate.markReadyForAgent(sessionId)

    fun updateRuntimeEvidencePolicy(
        sessionId: String,
        policy: RuntimeEvidencePolicy,
    ): SessionDto = delegate.updateRuntimeEvidencePolicy(sessionId, policy)

    fun attachRuntimeEvidence(
        sessionId: String,
        expectedScreenId: String,
        itemIds: List<String>,
        attachments: List<RuntimeEvidenceAttachment>,
        aggregateStatus: RuntimeEvidenceStatus,
    ): SessionDto = delegate.attachRuntimeEvidence(
        sessionId,
        expectedScreenId,
        itemIds,
        attachments,
        aggregateStatus,
    )

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        agentSummary: String?,
    ): AnnotationDto = delegate.updateItemStatus(sessionId, itemId, status, agentSummary)

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto = delegate.claimFeedback(sessionId, itemId, agentNote)

    fun updateDraftItem(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto = delegate.updateDraftItem(sessionId, itemId, label, severity, comment, status)

    fun deleteDraftItem(sessionId: String, itemId: String): SessionDto = delegate.deleteDraftItem(sessionId, itemId)
}
