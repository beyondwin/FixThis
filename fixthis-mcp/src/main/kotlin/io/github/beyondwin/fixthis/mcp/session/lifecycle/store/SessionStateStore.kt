@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.withMigratedItemSequenceCounter

/** Lock-free persistence/cache facade; the delegate owns synchronization and current-session selection. */
internal class SessionStateStore(
    private val persistence: FeedbackSessionPersistence?,
) {
    private val cache = DurableEventSessionCache()

    /**
     * Load-or-cache + migrate-on-read + re-cache. Persistence load (when wired)
     * takes precedence over the cached copy. Throws when the session is unknown.
     *
     * Was `getSessionLocked` on the delegate.
     */
    fun get(sessionId: String): SessionDto {
        val session = cache.preferred(sessionId) { loadIfPersisted(sessionId) }
            ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
        val migrated = session.withMigratedItemSequenceCounter()
        cache.put(migrated)
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
        cache.put(loaded)
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
        cache.commit(previous.sessionId, updated)
        return updated
    }

    /**
     * Materializes a mutation whose event has already been durably appended.
     *
     * The event is the commit record. A snapshot save failure therefore cannot roll the
     * mutation back; retain the replay-equivalent state in memory and prefer it over the
     * stale snapshot until a later successful snapshot write clears the override.
     */
    fun commitAfterDurableEvent(previous: SessionDto, updated: SessionDto): SessionDto = cache.commitAfterDurableEvent(previous.sessionId, updated, ::save)

    /** Write-through to persistence only (no in-memory caching). Was `save` on the delegate. */
    fun save(session: SessionDto) {
        persistence?.save(session)
    }

    /** In-memory cache-only write: `sessions[id] = session`. */
    fun put(session: SessionDto) {
        cache.put(session)
    }

    /** Mirrors the delegate's `save(x); sessions[x] = x` pattern at a single call site. */
    fun saveAndPut(session: SessionDto): SessionDto = session.also {
        save(it)
        cache.commit(it.sessionId, it)
    }

    /** Cached sessions in insertion order. Was `sessions.values.toList()`. */
    fun all(): List<SessionDto> = cache.all()

    /** Event-committed sessions whose durable snapshot has not caught up yet. */
    val durableEventSessions: List<SessionDto> get() = cache.durableEventSessions()

    /** Cached session ids in insertion order. Was `sessions.keys.toList()`. */
    fun ids(): List<String> = cache.ids()

    /** Cache-only lookup with no load/migrate. Was `sessions[sessionId]`. */
    fun find(sessionId: String): SessionDto? = cache.find(sessionId)
}
