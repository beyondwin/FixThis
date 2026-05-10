package io.beyondwin.fixthis.mcp.session

import java.util.UUID

class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val persistence: FeedbackSessionPersistence? = null,
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, SessionDto>()
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
                            if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
                        }
                }
        }
    }

    fun openSession(packageName: String, projectRoot: String): SessionDto =
        synchronized(lock) {
            val now = clock()
            val session = SessionDto(
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

    fun currentSession(): SessionDto? =
        synchronized(lock) { currentSessionId?.let { getSessionLocked(it) } }

    fun getSession(sessionId: String): SessionDto =
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
                        .filter { includeClosed || it.status != SessionStatusDto.CLOSED }
                        .map(FeedbackSessionSummary.Companion::from)
                        .sortedByDescending { it.updatedAtEpochMillis },
                )
        }

    fun openExistingSession(sessionId: String): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            currentSessionId = session.sessionId
            session
        }

    fun closeSession(sessionId: String): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val closed = session.copy(
                status = SessionStatusDto.CLOSED,
                updatedAtEpochMillis = now,
            )
            save(closed)
            sessions[sessionId] = closed
            if (currentSessionId == sessionId) currentSessionId = null
            closed
        }

    fun addScreen(sessionId: String, screen: SnapshotDto): SnapshotDto =
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

    fun addScreenWithItems(sessionId: String, screen: SnapshotDto, items: List<AnnotationDto>): SessionDto =
        synchronized(lock) {
            require(items.isNotEmpty()) { "At least one feedback item is required" }
            val session = getSessionLocked(sessionId)
            val now = clock()
            val captured = screen.copy(
                screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
                capturedAtEpochMillis = now,
            )
            val firstSequence = nextItemSequenceNumber(session)
            val createdItems = items.mapIndexed { index, item ->
                item.copy(
                    itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
                    screenId = captured.screenId,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    sequenceNumber = item.sequenceNumber ?: firstSequence + index,
                    delivery = FeedbackDelivery.DRAFT,
                )
            }
            val updated = session.copy(
                screens = session.screens + captured,
                items = session.items + createdItems,
                updatedAtEpochMillis = now,
            )
            commitSessionMutation(session, updated)
        }

    fun deleteScreen(sessionId: String, screenId: String): SessionDto =
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

    fun addItem(sessionId: String, item: AnnotationDto): AnnotationDto =
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

    fun clearDraftItems(sessionId: String): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val updated = session.copy(
                items = session.items.filter { it.delivery != FeedbackDelivery.DRAFT },
                updatedAtEpochMillis = clock(),
            )
            commitSessionMutation(session, updated)
        }

    fun sendDraftToAgent(
        sessionId: String,
        markdownSnapshot: String?,
        targetItemIds: List<String>? = null,
    ): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val targetSet = targetItemIds?.toSet()
            val candidates = session.items.filter { item ->
                val matchesTarget = targetSet == null || item.itemId in targetSet
                matchesTarget && (
                    item.delivery == FeedbackDelivery.DRAFT ||
                        (item.delivery == FeedbackDelivery.SENT && item.status == AnnotationStatusDto.READY)
                    )
            }
            if (candidates.isEmpty()) {
                throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
            }
            val now = clock()
            val batch = FeedbackHandoffBatch(
                batchId = idGenerator(),
                sequenceNumber = session.handoffBatches.size + 1,
                createdAtEpochMillis = now,
                itemIds = candidates.map { it.itemId },
                markdownSnapshot = markdownSnapshot,
            )
            val updatedItems = session.items.map { item ->
                val matchesTarget = targetSet == null || item.itemId in targetSet
                when {
                    item.delivery == FeedbackDelivery.DRAFT && matchesTarget -> {
                        item.copy(
                            delivery = FeedbackDelivery.SENT,
                            handoffBatchId = batch.batchId,
                            sentAtEpochMillis = now,
                            lastHandedOffAtEpochMillis = now,
                            status = AnnotationStatusDto.READY,
                            updatedAtEpochMillis = now,
                        )
                    }
                    item.delivery == FeedbackDelivery.SENT &&
                        item.status == AnnotationStatusDto.READY &&
                        matchesTarget -> {
                        // Re-save: preserve sentAt; refresh lastHandedOffAt + handoffBatchId.
                        item.copy(
                            handoffBatchId = batch.batchId,
                            lastHandedOffAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                        )
                    }
                    else -> item
                }
            }
            val updated = session.copy(
                items = updatedItems,
                handoffBatches = session.handoffBatches + batch,
                status = SessionStatusDto.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
            commitSessionMutation(session, updated)
        }

    fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto =
        synchronized(lock) {
            if (itemIds.isEmpty()) {
                throw FeedbackSessionException("itemIds must not be empty")
            }
            val session = getSessionLocked(sessionId)
            val targetSet = itemIds.toSet()
            val now = clock()
            val updatedItems = session.items.map { item ->
                if (item.itemId in targetSet) {
                    item.copy(
                        lastHandedOffAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                } else item
            }
            val updated = session.copy(
                items = updatedItems,
                updatedAtEpochMillis = now,
            )
            commitSessionMutation(session, updated)
        }

    fun markReadyForAgent(sessionId: String): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val updated = session.copy(
                status = SessionStatusDto.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
            save(updated)
            sessions[sessionId] = updated
            updated
        }

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        agentSummary: String?,
    ): AnnotationDto =
        synchronized(lock) {
            require(status in setOf(AnnotationStatusDto.RESOLVED, AnnotationStatusDto.NEEDS_CLARIFICATION, AnnotationStatusDto.WONT_FIX)) {
                "Agent resolution status is not allowed: $status"
            }
            val session = getSessionLocked(sessionId)
            val now = clock()
            var updatedItem: AnnotationDto? = null
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

    private val resolvedStatusSet = setOf(
        AnnotationStatusDto.RESOLVED,
        AnnotationStatusDto.WONT_FIX,
    )

    fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            var updatedItem: AnnotationDto? = null
            val updatedItems = session.items.map { item ->
                if (item.itemId != itemId) return@map item
                if (item.status in resolvedStatusSet) {
                    throw FeedbackSessionException(
                        "ITEM_ALREADY_RESOLVED: Cannot claim resolved feedback item: $itemId",
                    )
                }
                item.copy(
                    status = AnnotationStatusDto.IN_PROGRESS,
                    agentSummary = agentNote ?: item.agentSummary,
                    updatedAtEpochMillis = now,
                ).also { updatedItem = it }
            }
            val item = updatedItem
                ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
            val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
            save(updated)
            sessions[sessionId] = updated
            item
        }

    fun updateDraftItem(
        sessionId: String,
        itemId: String,
        label: String?,
        severity: AnnotationSeverityDto?,
        comment: String?,
        status: AnnotationStatusDto?,
    ): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            var found = false
            val updatedItems = session.items.map { item ->
                if (item.itemId != itemId) return@map item
                found = true
                if (isLockedForEdit(item)) {
                    throw FeedbackSessionException("ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId")
                }
                item.copy(
                    label = label ?: item.label,
                    severity = severity ?: item.severity,
                    comment = comment ?: item.comment,
                    status = status ?: item.status,
                    updatedAtEpochMillis = now,
                )
            }
            if (!found) throw FeedbackSessionException("Unknown feedback item: $itemId")
            val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
            save(updated)
            sessions[sessionId] = updated
            updated
        }

    fun deleteDraftItem(sessionId: String, itemId: String): SessionDto =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val item = session.items.find { it.itemId == itemId }
                ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
            if (isLockedForEdit(item)) {
                throw FeedbackSessionException("ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId")
            }
            val updatedBatches = session.handoffBatches
                .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it == itemId }) }
                .filter { it.itemIds.isNotEmpty() }
            val updated = session.copy(
                items = session.items.filterNot { it.itemId == itemId },
                handoffBatches = updatedBatches,
                updatedAtEpochMillis = clock(),
            )
            save(updated)
            sessions[sessionId] = updated
            updated
        }

    private fun isLockedForEdit(item: AnnotationDto): Boolean =
        item.delivery == FeedbackDelivery.SENT &&
            item.status in setOf(AnnotationStatusDto.IN_PROGRESS, AnnotationStatusDto.RESOLVED)

    private fun getSessionLocked(sessionId: String): SessionDto =
        loadPersistedSessionIfAvailable(sessionId)
            ?: sessions[sessionId]
            ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")

    private fun loadPersistedSessionIfAvailable(sessionId: String): SessionDto? {
        val loaded = persistence?.let { persistence ->
            runCatching { persistence.load(sessionId) }.getOrNull()
        } ?: return null
        sessions[loaded.sessionId] = loaded
        return loaded
    }

    private fun nextItemSequenceNumber(session: SessionDto): Int =
        session.items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1)
            ?: session.items.size + 1

    private fun commitSessionMutation(previous: SessionDto, updated: SessionDto): SessionDto {
        save(updated)
        sessions[previous.sessionId] = updated
        return updated
    }

    private fun save(session: SessionDto) {
        persistence?.save(session)
    }
}

class FeedbackSessionException(message: String) : RuntimeException(message)
