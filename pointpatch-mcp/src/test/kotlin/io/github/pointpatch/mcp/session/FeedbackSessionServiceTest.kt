package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun serviceOpensExactPersistedSession() {
        val root = createTempDir(prefix = "pointpatch-v2-service-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = FakeIds("session-1").next,
            persistence = persistence,
        )
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val created = service.openSession(packageNameOverride = null, newSession = true)
        val freshStore = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
        val freshService = FeedbackSessionService(
            bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.other"),
            store = freshStore,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.pointpatch.other",
        )

        val reopened = freshService.openSession(packageNameOverride = null, sessionId = created.sessionId)

        assertEquals(created.sessionId, reopened.sessionId)
        assertEquals(created.sessionId, freshStore.currentSession()?.sessionId)
    }

    @Test
    fun serviceListsSessionsForPackage() {
        val root = createTempDir(prefix = "pointpatch-v2-list-")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.pointpatch.sample",
        )
        service.openSession(packageNameOverride = null, newSession = true)

        val sessions = service.listSessions(packageNameOverride = "io.github.pointpatch.sample")

        assertEquals(listOf("session-1"), sessions.sessions.map { it.sessionId })
    }

    @Test
    fun serviceAutoResumesLatestNonClosedPersistedSessionForPackageAndProject() {
        val root = createTempDir(prefix = "pointpatch-v2-auto-resume-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 500L })
        persistence.save(
            FeedbackSession(
                sessionId = "sample-old",
                packageName = "io.github.pointpatch.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
            ),
        )
        persistence.save(
            FeedbackSession(
                sessionId = "sample-closed",
                packageName = "io.github.pointpatch.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 200L,
                updatedAtEpochMillis = 400L,
                status = FeedbackSessionStatus.CLOSED,
            ),
        )
        persistence.save(
            FeedbackSession(
                sessionId = "sample-latest",
                packageName = "io.github.pointpatch.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 300L,
                updatedAtEpochMillis = 300L,
            ),
        )
        persistence.save(
            FeedbackSession(
                sessionId = "other-current",
                packageName = "io.github.pointpatch.other",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 400L,
                updatedAtEpochMillis = 450L,
            ),
        )
        val store = FeedbackSessionStore(
            clock = { 600L },
            idGenerator = FakeIds("new-session").next,
            persistence = persistence,
        )
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.pointpatch.sample",
        )

        val session = service.openSession(packageNameOverride = null)

        assertEquals("sample-latest", session.sessionId)
        assertEquals("sample-latest", store.currentSession()?.sessionId)
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
    fun captureUsesSessionOwnedArtifactPath() = runBlocking {
        val root = createTempDir(prefix = "pointpatch-v2-artifacts-")
        val bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)

        service.captureScreen(session.sessionId)

        assertEquals("session-1", bridge.lastCaptureSessionId)
        assertEquals("screen-1", bridge.lastCaptureScreenId)
        assertTrue(
            bridge.lastCaptureDestination!!
                .contains(".pointpatch/feedback-sessions/session-1/artifacts/screens/screen-1"),
        )
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
