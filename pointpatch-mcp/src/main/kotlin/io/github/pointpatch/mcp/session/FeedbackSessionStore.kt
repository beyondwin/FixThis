package io.github.pointpatch.mcp.session

import java.util.UUID

class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, FeedbackSession>()
    private var currentSessionId: String? = null

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

    fun addScreen(sessionId: String, screen: CapturedScreen): CapturedScreen =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val captured = screen.copy(
                screenId = idGenerator(),
                capturedAtEpochMillis = now,
            )
            sessions[sessionId] = session.copy(
                screens = session.screens + captured,
                updatedAtEpochMillis = now,
            )
            captured
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
            )
            sessions[sessionId] = session.copy(
                items = session.items + created,
                updatedAtEpochMillis = now,
            )
            created
        }

    fun markReadyForAgent(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val updated = session.copy(
                status = FeedbackSessionStatus.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
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
            sessions[sessionId] = session.copy(items = updatedItems, updatedAtEpochMillis = now)
            item
        }

    private fun getSessionLocked(sessionId: String): FeedbackSession =
        sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
}

class FeedbackSessionException(message: String) : RuntimeException(message)
