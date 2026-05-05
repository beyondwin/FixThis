package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.mcp.console.FeedbackTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun navigatePropagatesCancellationFromFollowUpCapture() {
        runBlocking {
            val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
            val bridge = FakePointPatchBridge(captureError = CancellationException("capture cancelled"))
            val service = FeedbackSessionService(
                bridge = bridge,
                store = store,
                projectRoot = "/repo",
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)

            assertFailsWith<CancellationException> {
                service.navigate(
                    sessionId = session.sessionId,
                    request = FeedbackNavigationRequest(action = FeedbackNavigationAction.BACK),
                )
            }
        }
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

    @Test
    fun addSelectedNodeFeedbackStoresSelectedNode() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = PointPatchNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = PointPatchRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(FeedbackScreenRoot(0, PointPatchRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val item = service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            targetType = FeedbackTargetType.NODE,
            bounds = node.boundsInWindow,
            nodeUid = node.uid,
            comment = "Button copy is unclear",
        )

        assertEquals(FeedbackTarget.Node(node.uid, node.boundsInWindow), item.target)
        assertEquals(node, item.selectedNode)
        assertEquals(FeedbackDelivery.DRAFT, item.delivery)
        assertEquals(1, item.sequenceNumber)
    }

    @Test
    fun addSelectedNodeFeedbackRejectsNodeBoundsOutsideScreenshot() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = PointPatchNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = PointPatchRect(-1f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(FeedbackScreenRoot(0, PointPatchRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = "screen-1",
                targetType = FeedbackTargetType.NODE,
                bounds = PointPatchRect(10f, 20f, 110f, 70f),
                nodeUid = node.uid,
                comment = "Button copy is unclear",
            )
        }

        assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
    }

    @Test
    fun addSelectedNodeFeedbackStoresNodeBoundsWhenRequestBoundsDiffer() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = PointPatchNode(
            uid = "compose:0:unmerged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.UNMERGED,
            boundsInWindow = PointPatchRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(FeedbackScreenRoot(0, PointPatchRect(0f, 0f, 720f, 1600f), unmergedNodes = listOf(node))),
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val item = service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            targetType = FeedbackTargetType.NODE,
            bounds = PointPatchRect(200f, 300f, 260f, 340f),
            nodeUid = node.uid,
            comment = "Button copy is unclear",
        )

        assertEquals(FeedbackTarget.Node(node.uid, node.boundsInWindow), item.target)
        assertEquals(node, item.selectedNode)
    }

    @Test
    fun addCustomAreaFeedbackRejectsBoundsOutsideScreenshot() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = "screen-1",
                targetType = FeedbackTargetType.AREA,
                bounds = PointPatchRect(-1f, 0f, 10f, 10f),
                nodeUid = null,
                comment = "Bad bounds",
            )
        }

        assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
    }

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: CapturedScreen): CapturedScreen =
        javaClass.getDeclaredField("store").let { field ->
            field.isAccessible = true
            (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
        }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }
}
