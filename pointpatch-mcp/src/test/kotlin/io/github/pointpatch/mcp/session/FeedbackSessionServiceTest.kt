package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackSessionServiceTest {
    @Test
    fun openSessionReusesCurrentSessionForSamePackageAndProject() {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )

        val first = service.openSession(null)
        val second = service.openSession(null)

        assertEquals("session-1", second.sessionId)
        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun openSessionTreatsBlankPackageOverrideAsDefaultPackage() {
        val bridge = FakePointPatchBridge()
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )

        val session = service.openSession("  ")

        assertEquals("io.github.pointpatch.sample", session.packageName)
        assertEquals(listOf<String?>("io.github.pointpatch.sample"), bridge.resolvedOverrides)
    }

    @Test
    fun captureScreenAddsScreenToCurrentSession() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )

        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        assertEquals("screen-1", screen.screenId)
        assertEquals("MainActivity", screen.displayName)
        assertEquals(1, store.getSession(session.sessionId).screens.size)
    }

    @Test
    fun addAreaFeedbackStoresItemForScreen() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next)
        val service = FeedbackSessionService(FakePointPatchBridge(), store, "/repo", "io.github.pointpatch.sample")
        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        val item = service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = PointPatchRect(1f, 2f, 3f, 4f),
            comment = "Fix spacing",
        )

        assertEquals("item-1", item.itemId)
        assertEquals("Fix spacing", item.comment)
    }

    @Test
    fun addAreaFeedbackWithBlankCommentStaysOpen() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next)
        val service = FeedbackSessionService(FakePointPatchBridge(), store, "/repo", "io.github.pointpatch.sample")
        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        val item = service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = PointPatchRect(1f, 2f, 3f, 4f),
            comment = " ",
        )

        assertEquals(FeedbackItemStatus.OPEN, item.status)
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }
}
