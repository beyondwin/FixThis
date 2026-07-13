@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.withMigratedItemSequenceCounter

/**
 * Owns the in-memory feedback-session map plus the persistence read/write seam.
 *
 * This store is deliberately **lock-free**: every method must be called by
 * [FeedbackSessionStoreDelegate] from inside its shared store-lock blocks
 * (and once, without the lock, from the delegate's `init` block at construction
 * time). The observable concurrency/locking semantics therefore live entirely
 * in the delegate and are unchanged by this extraction. The store introduces no
 * internal locking of its own.
 *
 * [currentSessionId] intentionally does NOT live here — it is a session-lifecycle
 * concern that remains a field of the delegate, so that pure mutations (e.g.
 * [commit]) never hijack the current-session pointer.
 */
internal class SessionStateStore(
    private val persistence: FeedbackSessionPersistence?,
) {
    private val sessions = linkedMapOf<String, SessionDto>()

    /**
     * Load-or-cache + migrate-on-read + re-cache. Persistence load (when wired)
     * takes precedence over the cached copy. Throws when the session is unknown.
     *
     * Was `getSessionLocked` on the delegate.
     */
    fun get(sessionId: String): SessionDto {
        val session = loadIfPersisted(sessionId)
            ?: sessions[sessionId]
            ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
        val migrated = session.withMigratedItemSequenceCounter()
        sessions[migrated.sessionId] = migrated
        return migrated
    }

    /**
     * Loads [sessionId] from persistence when available, caching the loaded
     * (un-migrated) session. Persistence load takes precedence over any cached
     * copy. Returns null when persistence is absent or the load fails.
     *
     * Was `loadPersistedSessionIfAvailable` on the delegate.
     */
    fun loadIfPersisted(sessionId: String): SessionDto? {
        val loaded = persistence?.let { p ->
            runCatching { p.load(sessionId) }.getOrNull()
        } ?: return null
        sessions[loaded.sessionId] = loaded
        return loaded
    }

    /**
     * Persists [updated] then caches it under [previous]'s session id. Returns
     * [updated]. Must NOT touch any current-session pointer — that is the
     * delegate's responsibility.
     *
     * Was `commitSessionMutation` on the delegate.
     */
    fun commit(previous: SessionDto, updated: SessionDto): SessionDto {
        save(updated)
        sessions[previous.sessionId] = updated
        return updated
    }

    /** Write-through to persistence only (no in-memory caching). Was `save` on the delegate. */
    fun save(session: SessionDto) {
        persistence?.save(session)
    }

    /** In-memory cache-only write: `sessions[id] = session`. */
    fun put(session: SessionDto) {
        sessions[session.sessionId] = session
    }

    /** Mirrors the delegate's `save(x); sessions[x] = x` pattern at a single call site. */
    fun saveAndPut(session: SessionDto): SessionDto = session.also {
        save(it)
        put(it)
    }

    /** Cached sessions in insertion order. Was `sessions.values.toList()`. */
    fun all(): List<SessionDto> = sessions.values.toList()

    /** Cached session ids in insertion order. Was `sessions.keys.toList()`. */
    fun ids(): List<String> = sessions.keys.toList()

    /** Cache-only lookup with no load/migrate. Was `sessions[sessionId]`. */
    fun find(sessionId: String): SessionDto? = sessions[sessionId]
}
