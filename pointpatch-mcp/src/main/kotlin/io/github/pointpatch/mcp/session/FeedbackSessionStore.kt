package io.github.pointpatch.mcp.session

import java.util.UUID

class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val persistence: FeedbackSessionPersistence? = null,
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, FeedbackSession>()
    private var currentSessionId: String? = null

    init {
        persistence?.let { persistence ->
            persistence.list(includeClosed = true).sessions
                .sortedBy { it.updatedAtEpochMillis }
                .forEach { summary ->
                    runCatching { persistence.load(summary.sessionId) }
                        .getOrNull()
                        ?.let { session ->
                            sessions[session.sessionId] = session
                            if (session.status != FeedbackSessionStatus.CLOSED) currentSessionId = session.sessionId
                        }
                }
        }
    }

    fun openSession(packageName: String, projectRoot: String): FeedbackSession =
        synchronized(lock) {
            val now = clock()
            val session = FeedbackSession(
                sessionId = idGenerator(),
                packageName = packageName,
                projectRoot = projectRoot,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            save(session)
            sessions[session.sessionId] = session
            currentSessionId = session.sessionId
            session
        }

    fun currentSession(): FeedbackSession? =
        synchronized(lock) { currentSessionId?.let { sessions[it] } }

    fun getSession(sessionId: String): FeedbackSession =
        synchronized(lock) {
            getSessionLocked(sessionId)
        }

    fun nextId(): String = synchronized(lock) { idGenerator() }

    fun listSessions(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList =
        synchronized(lock) {
            persistence?.list(packageName, includeClosed)
                ?: FeedbackSessionList(
                    sessions = sessions.values
                        .filter { packageName == null || it.packageName == packageName }
                        .filter { includeClosed || it.status != FeedbackSessionStatus.CLOSED }
                        .map(FeedbackSessionSummary.Companion::from)
                        .sortedByDescending { it.updatedAtEpochMillis },
                )
        }

    fun openExistingSession(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = sessions[sessionId]
                ?: persistence?.load(sessionId)?.also { sessions[it.sessionId] = it }
                ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
            currentSessionId = session.sessionId
            session
        }

    fun closeSession(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val closed = session.copy(
                status = FeedbackSessionStatus.CLOSED,
                updatedAtEpochMillis = now,
            )
            save(closed)
            sessions[sessionId] = closed
            if (currentSessionId == sessionId) currentSessionId = null
            closed
        }

    fun addScreen(sessionId: String, screen: CapturedScreen): CapturedScreen =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val captured = screen.copy(
                screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
                capturedAtEpochMillis = now,
            )
            val updated = session.copy(
                screens = session.screens + captured,
                updatedAtEpochMillis = now,
            )
            save(updated)
            sessions[sessionId] = updated
            captured
        }

    fun deleteScreen(sessionId: String, screenId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            if (session.screens.none { it.screenId == screenId }) {
                throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
            }
            val removedItemIds = session.items
                .filter { it.screenId == screenId }
                .map { it.itemId }
                .toSet()
            val updatedBatches = session.handoffBatches
                .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
                .filter { it.itemIds.isNotEmpty() }
            val updated = session.copy(
                screens = session.screens.filterNot { it.screenId == screenId },
                items = session.items.filterNot { it.screenId == screenId },
                handoffBatches = updatedBatches,
                updatedAtEpochMillis = clock(),
            )
            commitSessionMutation(session, updated).also {
                persistence?.artifactPaths()
                    ?.screenArtifactDirectory(sessionId, screenId)
                    ?.deleteRecursively()
            }
        }

    fun addItem(sessionId: String, item: FeedbackItem): FeedbackItem =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            require(session.screens.any { it.screenId == item.screenId }) {
                "Cannot add feedback for unknown screen: ${item.screenId}"
            }
            val now = clock()
            val created = item.copy(
                itemId = idGenerator(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                sequenceNumber = item.sequenceNumber ?: nextItemSequenceNumber(session),
                delivery = item.delivery,
            )
            val updated = session.copy(
                items = session.items + created,
                updatedAtEpochMillis = now,
            )
            save(updated)
            sessions[sessionId] = updated
            created
        }

    fun clearDraftItems(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val updated = session.copy(
                items = session.items.filter { it.delivery != FeedbackDelivery.DRAFT },
                updatedAtEpochMillis = clock(),
            )
            commitSessionMutation(session, updated)
        }

    fun sendDraftToAgent(sessionId: String, markdownSnapshot: String?): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val draftItems = session.items.filter { it.delivery == FeedbackDelivery.DRAFT }
            if (draftItems.isEmpty()) {
                throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
            }
            val now = clock()
            val batch = FeedbackHandoffBatch(
                batchId = idGenerator(),
                sequenceNumber = session.handoffBatches.size + 1,
                createdAtEpochMillis = now,
                itemIds = draftItems.map { it.itemId },
                markdownSnapshot = markdownSnapshot,
            )
            val updatedItems = session.items.map { item ->
                if (item.delivery == FeedbackDelivery.DRAFT) {
                    item.copy(
                        delivery = FeedbackDelivery.SENT,
                        handoffBatchId = batch.batchId,
                        sentAtEpochMillis = now,
                        status = FeedbackItemStatus.READY,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    item
                }
            }
            val updated = session.copy(
                items = updatedItems,
                handoffBatches = session.handoffBatches + batch,
                status = FeedbackSessionStatus.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
            commitSessionMutation(session, updated)
        }

    fun markReadyForAgent(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val updated = session.copy(
                status = FeedbackSessionStatus.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
            save(updated)
            sessions[sessionId] = updated
            updated
        }

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: FeedbackItemStatus,
        agentSummary: String?,
    ): FeedbackItem =
        synchronized(lock) {
            require(status in setOf(FeedbackItemStatus.RESOLVED, FeedbackItemStatus.NEEDS_CLARIFICATION, FeedbackItemStatus.WONT_FIX)) {
                "Agent resolution status is not allowed: $status"
            }
            val session = getSessionLocked(sessionId)
            val now = clock()
            var updatedItem: FeedbackItem? = null
            val updatedItems = session.items.map { item ->
                if (item.itemId == itemId) {
                    item.copy(
                        status = status,
                        agentSummary = agentSummary,
                        updatedAtEpochMillis = now,
                    ).also { updatedItem = it }
                } else {
                    item
                }
            }
            val item = updatedItem ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
            val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
            save(updated)
            sessions[sessionId] = updated
            item
        }

    private fun getSessionLocked(sessionId: String): FeedbackSession =
        sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")

    private fun nextItemSequenceNumber(session: FeedbackSession): Int =
        session.items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1)
            ?: session.items.size + 1

    private fun commitSessionMutation(previous: FeedbackSession, updated: FeedbackSession): FeedbackSession {
        save(updated)
        sessions[previous.sessionId] = updated
        return updated
    }

    private fun save(session: FeedbackSession) {
        persistence?.save(session)
    }
}

class FeedbackSessionException(message: String) : RuntimeException(message)
