package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.draft.DraftSaveService
import io.github.beyondwin.fixthis.mcp.session.draft.StaleDraftRevisionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Spec S1.4.5 — older save / newer edits conflict policy.
 *
 * The save endpoint is `/api/items/batch`. This test exercises the rev/ETag
 * gate via the in-memory [DraftSaveService] state machine, independent of
 * the HTTP boundary. The route wires both together — see
 * [ConsoleFeedbackDraftConflictRoutesTest] for the integration coverage.
 */
class DraftSaveServiceTest {
    @Test
    fun firstSaveReturnsEtagOne() {
        val service = DraftSaveService()
        val rev = service.commitSave(sessionId = "session-1", ifMatch = null)
        assertEquals("1", rev)
        assertEquals("1", service.currentRev("session-1"))
    }

    @Test
    fun secondSaveWithoutIfMatchIsStale() {
        val service = DraftSaveService()
        service.commitSave(sessionId = "session-1", ifMatch = null) // bumps to "1"
        val error = assertFailsWith<StaleDraftRevisionException> {
            service.commitSave(sessionId = "session-1", ifMatch = null)
        }
        assertEquals("1", error.currentRev)
    }

    @Test
    fun secondSaveWithStaleIfMatchIsStale() {
        val service = DraftSaveService()
        service.commitSave(sessionId = "session-1", ifMatch = null) // rev "1"
        service.commitSave(sessionId = "session-1", ifMatch = "\"1\"") // rev "2"
        val error = assertFailsWith<StaleDraftRevisionException> {
            service.commitSave(sessionId = "session-1", ifMatch = "\"1\"")
        }
        assertEquals("2", error.currentRev)
    }

    @Test
    fun ifMatchStarAlwaysOverrides() {
        val service = DraftSaveService()
        service.commitSave(sessionId = "session-1", ifMatch = null) // rev "1"
        service.commitSave(sessionId = "session-1", ifMatch = "\"1\"") // rev "2"
        // Now any stale or absent If-Match would be 412, but `*` overrides.
        val rev = service.commitSave(sessionId = "session-1", ifMatch = "*")
        assertEquals("3", rev)
    }

    @Test
    fun ifMatchMatchingCurrentRevAdvances() {
        val service = DraftSaveService()
        val first = service.commitSave(sessionId = "session-1", ifMatch = null)
        val second = service.commitSave(sessionId = "session-1", ifMatch = "\"" + first + "\"")
        assertEquals("2", second)
    }

    @Test
    fun firstSaveWithStaleIfMatchIsStillStale() {
        // If the client sends If-Match before any save exists, the "0" rev is
        // not the server's "0" (no entry yet). We treat "no entry" as no rev
        // required only when If-Match is null. A non-null, non-`*` If-Match
        // pre-rev is a stale claim.
        val service = DraftSaveService()
        val error = assertFailsWith<StaleDraftRevisionException> {
            service.commitSave(sessionId = "session-1", ifMatch = "\"5\"")
        }
        assertEquals("0", error.currentRev)
    }

    @Test
    fun revsAreScopedPerSession() {
        val service = DraftSaveService()
        service.commitSave(sessionId = "session-a", ifMatch = null) // a: 1
        service.commitSave(sessionId = "session-a", ifMatch = "\"1\"") // a: 2
        val firstB = service.commitSave(sessionId = "session-b", ifMatch = null)
        assertEquals("1", firstB)
        assertEquals("2", service.currentRev("session-a"))
    }
}
