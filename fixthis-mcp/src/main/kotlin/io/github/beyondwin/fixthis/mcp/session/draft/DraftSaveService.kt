package io.github.beyondwin.fixthis.mcp.session.draft

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory revision tracker for the preview draft save endpoint
 * (`/api/items/batch`). Spec S1.4.5 — older save / newer edits conflict policy.
 *
 * Each successful save bumps a per-session monotonic counter. The HTTP layer
 * surfaces the current value as `ETag: "<rev>"` and enforces `If-Match` on
 * subsequent saves:
 *
 * - First save (no rev yet): If-Match must be absent OR `*`. Rev becomes 1.
 * - Later save: If-Match must equal the current rev (as a quoted string) OR `*`.
 * - Mismatch / missing-when-required → [StaleDraftRevisionException]
 *   (HTTP 412 by the route).
 *
 * The rev is process-scoped and orthogonal to the existing screen fingerprint
 * check (HTTP 409); both can fire on the same request and are evaluated
 * independently. Callers MUST call [bumpRev] only after a successful persist
 * so failed downstream commits do not corrupt the counter.
 */
internal class DraftSaveService {
    private val revs = ConcurrentHashMap<String, Long>()

    /** Returns the current rev as the string used in ETag / If-Match. */
    fun currentRev(sessionId: String): String = (revs[sessionId] ?: 0L).toString()

    /**
     * Validates [ifMatch] against the current rev for [sessionId]. Throws
     * [StaleDraftRevisionException] if the precondition fails. Does NOT bump
     * the rev — call [bumpRev] after the downstream save succeeds.
     */
    fun validateIfMatch(sessionId: String, ifMatch: String?) {
        val previous = revs[sessionId] ?: 0L
        val normalized = ifMatch?.trim()
        val matches = when {
            normalized == "*" -> true
            normalized == null -> previous == 0L
            else -> normalized.trim('"') == previous.toString()
        }
        if (!matches) {
            throw StaleDraftRevisionException(currentRev = previous.toString())
        }
    }

    /** Atomically bump the rev for [sessionId] and return the new value. */
    fun bumpRev(sessionId: String): String {
        val next = revs.compute(sessionId) { _, prev -> (prev ?: 0L) + 1L }!!
        return next.toString()
    }

    /**
     * Convenience that validates + bumps in one step. Production code SHOULD
     * use [validateIfMatch] before the save and [bumpRev] only after the save
     * succeeds; this entry point is kept for the unit tests that exercise
     * the state machine without a real persist.
     */
    fun commitSave(sessionId: String, ifMatch: String?): String {
        validateIfMatch(sessionId, ifMatch)
        return bumpRev(sessionId)
    }

    /** Test/teardown hook — drop tracked revs. */
    fun reset() {
        revs.clear()
    }
}

/** Thrown when a save's `If-Match` doesn't match the current rev. */
internal class StaleDraftRevisionException(
    val currentRev: String,
) : RuntimeException("Stale draft save: current rev is $currentRev")
