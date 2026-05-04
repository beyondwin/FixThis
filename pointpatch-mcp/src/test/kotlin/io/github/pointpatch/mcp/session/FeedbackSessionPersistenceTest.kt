package io.github.pointpatch.mcp.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackSessionPersistenceTest {
    @Test
    fun pathsStayUnderProjectFeedbackSessionsDirectory() {
        val root = createTempDir(prefix = "pointpatch-v2-paths-")
        val paths = FeedbackSessionPaths(root)

        val sessionDir = paths.sessionDirectory("session-1")
        val screenDir = paths.screenArtifactDirectory("session-1", "screen-1")

        assertEquals(File(root, ".pointpatch/feedback-sessions/session-1").canonicalFile, sessionDir)
        assertEquals(File(root, ".pointpatch/feedback-sessions/session-1/artifacts/screens/screen-1").canonicalFile, screenDir)
        assertTrue(screenDir.toPath().startsWith(paths.rootDirectory.toPath()))
    }

    @Test
    fun pathHelpersRejectUnsafeIds() {
        val paths = FeedbackSessionPaths(createTempDir(prefix = "pointpatch-v2-unsafe-"))

        assertFailsWith<IllegalArgumentException> {
            paths.sessionDirectory("../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            paths.screenArtifactDirectory("session-1", "screen/1")
        }
    }

    @Test
    fun sessionSummaryCountsUnresolvedItems() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(CapturedScreen(screenId = "screen-1", capturedAtEpochMillis = 2L, displayName = "Main")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = FeedbackTarget.Area(PointPatchRectForTest.bounds),
                    comment = "Fix spacing",
                    status = FeedbackItemStatus.READY,
                ),
                FeedbackItem(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = FeedbackTarget.Area(PointPatchRectForTest.bounds),
                    comment = "Done",
                    status = FeedbackItemStatus.RESOLVED,
                ),
            ),
        )

        val summary = FeedbackSessionSummary.from(session)

        assertEquals("session-1", summary.sessionId)
        assertEquals(1, summary.screensCount)
        assertEquals(2, summary.itemsCount)
        assertEquals(1, summary.unresolvedItemsCount)
    }
}

private object PointPatchRectForTest {
    val bounds = io.github.pointpatch.compose.core.model.PointPatchRect(1f, 2f, 3f, 4f)
}
