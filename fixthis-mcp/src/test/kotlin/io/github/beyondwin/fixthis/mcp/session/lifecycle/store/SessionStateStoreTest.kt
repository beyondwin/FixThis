package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for the lock-free [SessionStateStore] extracted from
 * FeedbackSessionStoreDelegate. The store owns the in-memory session map plus
 * persistence read/write; the delegate keeps the lock and calls the store from
 * inside synchronized(lock). These tests exercise the store directly (no lock)
 * to lock in the migration-on-read, re-cache, and persistence-precedence
 * semantics for both persistence == null and persistence != null.
 */
class SessionStateStoreTest {
    private fun session(
        id: String,
        nextSeq: Int = 1,
        itemSeq: Int? = null,
        status: SessionStatusDto = SessionStatusDto.ACTIVE,
    ): SessionDto = SessionDto(
        sessionId = id,
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 20L,
        items = itemSeq?.let {
            listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "comment",
                    status = AnnotationStatusDto.READY,
                    sequenceNumber = it,
                ),
            )
        } ?: emptyList(),
        status = status,
        nextItemSequenceNumber = nextSeq,
    )

    private fun persistence(): FeedbackSessionPersistence {
        val root = createTempDirectory(prefix = "fixthis-store-test-").toFile().also { it.deleteOnExit() }
        return FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
    }

    @Test
    fun putThenFindReturnsCachedSessionWithoutPersistence() {
        val store = SessionStateStore(persistence = null)
        val s = session("a")
        store.put(s)
        assertSame(s, store.find("a"))
    }

    @Test
    fun findReturnsNullForUnknownSession() {
        val store = SessionStateStore(persistence = null)
        assertNull(store.find("missing"))
    }

    @Test
    fun getThrowsForUnknownSessionWithoutPersistence() {
        val store = SessionStateStore(persistence = null)
        assertFailsWith<FeedbackSessionException> { store.get("missing") }
    }

    @Test
    fun getAppliesSequenceMigrationAndReCachesMigratedSession() {
        val store = SessionStateStore(persistence = null)
        // item sequenceNumber 5 -> nextFromItems 6, but stored counter is 1, so migration bumps to 6.
        val stale = session("a", nextSeq = 1, itemSeq = 5)
        store.put(stale)

        val migrated = store.get("a")

        assertEquals(6, migrated.nextItemSequenceNumber)
        // Re-cache side effect: the migrated session replaces the stale one in the map.
        assertEquals(6, store.find("a")?.nextItemSequenceNumber)
        assertSame(migrated, store.find("a"))
    }

    @Test
    fun loadIfPersistedReturnsNullWhenPersistenceIsNull() {
        val store = SessionStateStore(persistence = null)
        assertNull(store.loadIfPersisted("a"))
    }

    @Test
    fun loadIfPersistedReturnsNullForMissingPersistedSession() {
        val store = SessionStateStore(persistence = persistence())
        assertNull(store.loadIfPersisted("never-saved"))
    }

    @Test
    fun loadIfPersistedLoadsAndCachesLoadedSession() {
        val p = persistence()
        val saved = session("a", nextSeq = 1, itemSeq = 5)
        p.save(saved)
        val store = SessionStateStore(persistence = p)

        val loaded = store.loadIfPersisted("a")

        assertEquals(saved, loaded)
        // The loaded (un-migrated) session is cached.
        assertEquals(1, store.find("a")?.nextItemSequenceNumber)
    }

    @Test
    fun loadIfPersistedTakesPrecedenceOverCachedCopy() {
        val p = persistence()
        val persisted = session("a", nextSeq = 7)
        p.save(persisted)
        val store = SessionStateStore(persistence = p)
        // Pre-seed the cache with a different copy.
        store.put(session("a", nextSeq = 99))

        val loaded = store.loadIfPersisted("a")

        assertEquals(7, loaded?.nextItemSequenceNumber)
        assertEquals(7, store.find("a")?.nextItemSequenceNumber)
    }

    @Test
    fun getPrefersPersistedSessionThenMigrates() {
        val p = persistence()
        // Persisted copy has stale counter 1 but item seq 5 -> migrates to 6.
        p.save(session("a", nextSeq = 1, itemSeq = 5))
        val store = SessionStateStore(persistence = p)
        // A different cached copy must be ignored in favour of the persisted one.
        store.put(session("a", nextSeq = 50))

        val migrated = store.get("a")

        assertEquals(6, migrated.nextItemSequenceNumber)
        assertSame(migrated, store.find("a"))
    }

    @Test
    fun saveWritesThroughToPersistence() {
        val p = persistence()
        val store = SessionStateStore(persistence = p)
        val s = session("a", nextSeq = 3)

        store.save(s)

        assertEquals(s, p.load("a"))
    }

    @Test
    fun saveIsNoOpWhenPersistenceIsNull() {
        val store = SessionStateStore(persistence = null)
        // Must not throw; nothing cached either.
        store.save(session("a"))
        assertNull(store.find("a"))
    }

    @Test
    fun commitSavesAndCachesByPreviousSessionId() {
        val p = persistence()
        val store = SessionStateStore(persistence = p)
        val previous = session("a", nextSeq = 1)
        val updated = session("a", nextSeq = 9)

        val result = store.commit(previous, updated)

        assertSame(updated, result)
        assertEquals(updated, p.load("a"))
        assertSame(updated, store.find("a"))
    }

    @Test
    fun commitDoesNotTouchCurrentSessionPointer() {
        // The store has no concept of currentSessionId; committing a CLOSED session
        // must remain a pure save + cache with no lifecycle side effects.
        val store = SessionStateStore(persistence = null)
        val previous = session("a")
        val updated = session("a", status = SessionStatusDto.CLOSED)

        store.commit(previous, updated)

        assertSame(updated, store.find("a"))
    }

    @Test
    fun allReturnsCachedSessionsInInsertionOrder() {
        val store = SessionStateStore(persistence = null)
        val a = session("a")
        val b = session("b")
        store.put(a)
        store.put(b)

        assertEquals(listOf(a, b), store.all())
    }

    @Test
    fun idsReturnsCachedSessionIds() {
        val store = SessionStateStore(persistence = null)
        store.put(session("a"))
        store.put(session("b"))

        assertEquals(listOf("a", "b"), store.ids())
    }

    @Test
    fun saveAndPutWritesThroughAndCaches() {
        val p = persistence()
        val store = SessionStateStore(persistence = p)
        val s = session("a", nextSeq = 4)

        store.saveAndPut(s)

        assertEquals(s, p.load("a"))
        assertSame(s, store.find("a"))
    }
}
