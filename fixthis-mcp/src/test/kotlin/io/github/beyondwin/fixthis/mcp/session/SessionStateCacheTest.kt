package io.github.beyondwin.fixthis.mcp.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStateCacheTest {
    @Test
    fun tracksCurrentOpenSessionAndClearsClosedCurrent() {
        val cache = SessionStateCache()
        val active = session("active", status = SessionStatusDto.ACTIVE)
        val closed = session("active", status = SessionStatusDto.CLOSED)

        cache.put(active)
        assertEquals(active, cache.current())

        cache.put(closed)
        assertNull(cache.current())
    }

    @Test
    fun allReturnsInsertionOrderSnapshot() {
        val cache = SessionStateCache()
        val first = session("first")
        val second = session("second")

        cache.put(first)
        cache.put(second)

        assertEquals(listOf(first, second), cache.all())
    }

    private fun session(id: String, status: SessionStatusDto = SessionStatusDto.ACTIVE): SessionDto = SessionDto(
        sessionId = id,
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        status = status,
    )
}
