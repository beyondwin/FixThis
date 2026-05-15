package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackSessionServiceClaimTest {
    @Test
    fun serviceClaimDelegatesToStoreClaim() = runBlocking {
        val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { ids.removeFirst() })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(packageNameOverride = null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "fix this",
        )

        val claimed = service.claimFeedback(session.sessionId, "item-1", agentNote = "via service")

        assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
        assertEquals("via service", claimed.agentSummary)
    }
}
