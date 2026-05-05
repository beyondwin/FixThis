package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
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
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
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
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-1",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 2L,
                    itemIds = listOf("item-1"),
                    markdownSnapshot = "Batch markdown",
                ),
            ),
        )

        val summary = FeedbackSessionSummary.from(session)

        assertEquals("session-1", summary.sessionId)
        assertEquals(1, summary.screensCount)
        assertEquals(2, summary.itemsCount)
        assertEquals(1, summary.unresolvedItemsCount)
        assertEquals(1, summary.draftItemsCount)
        assertEquals(1, summary.sentBatchesCount)
    }

    @Test
    fun persistenceSavesSessionAndIndex() {
        val root = createTempDir(prefix = "pointpatch-v2-persist-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )

        persistence.save(session)

        assertTrue(File(root, ".pointpatch/feedback-sessions/session-1/session.json").isFile)
        assertTrue(File(root, ".pointpatch/feedback-sessions/index.json").isFile)
        assertEquals(session, persistence.load("session-1"))
        assertEquals(listOf("session-1"), persistence.list().sessions.map { it.sessionId })
        val index = pointPatchJson.decodeFromString(FeedbackSessionIndex.serializer(), paths.indexFile.readText())
        assertEquals(listOf("session-1"), index.sessions.map { it.sessionId })
    }

    @Test
    fun failedIndexWriteLeavesExistingSessionFileUnchanged() {
        val root = createTempDir(prefix = "pointpatch-v2-partial-save-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val initial = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        persistence.save(initial)
        paths.indexFile.delete()
        assertTrue(paths.indexFile.mkdirs())

        val updated = initial.copy(
            updatedAtEpochMillis = 300L,
            screens = listOf(CapturedScreen(screenId = "screen-1", capturedAtEpochMillis = 250L, displayName = "Main")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 260L,
                    updatedAtEpochMillis = 270L,
                    target = FeedbackTarget.Area(PointPatchRectForTest.bounds),
                    comment = "Fix spacing",
                ),
            ),
        )

        assertFailsWith<FeedbackSessionException> {
            persistence.save(updated)
        }

        val loaded = persistence.load("session-1")
        assertEquals(initial, loaded)
        assertEquals(100L, loaded.updatedAtEpochMillis)
        assertEquals(emptyList(), loaded.screens)
        assertEquals(emptyList(), loaded.items)
    }

    @Test
    fun failedSessionReplaceLeavesExistingIndexUnchanged() {
        val root = createTempDir(prefix = "pointpatch-v2-session-replace-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val existing = FeedbackSession(
            sessionId = "session-old",
            packageName = "io.github.pointpatch.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        persistence.save(existing)
        val oldIndex = paths.indexFile.readText()
        paths.sessionDirectory("session-new").mkdirs()
        assertTrue(paths.sessionFile("session-new").mkdirs())

        val candidate = FeedbackSession(
            sessionId = "session-new",
            packageName = "io.github.pointpatch.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 300L,
            updatedAtEpochMillis = 300L,
        )

        assertFailsWith<FeedbackSessionException> {
            persistence.save(candidate)
        }

        assertEquals(oldIndex, paths.indexFile.readText())
        assertEquals(existing, persistence.load("session-old"))
        assertFailsWith<FeedbackSessionException> {
            persistence.load("session-new")
        }
        assertEquals(listOf("session-old"), persistence.list(includeClosed = true).sessions.map { it.sessionId })
    }

    @Test
    fun failedIndexReplaceRestoresCommittedSessionFile() {
        val root = createTempDir(prefix = "pointpatch-v2-index-replace-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val initial = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        persistence.save(initial)
        paths.indexFile.delete()
        assertTrue(paths.indexFile.mkdirs())

        val updated = initial.copy(updatedAtEpochMillis = 300L)

        assertFailsWith<FeedbackSessionException> {
            persistence.save(updated)
        }

        assertEquals(initial, persistence.load("session-1"))
        assertEquals(100L, persistence.list(includeClosed = true).sessions.single().updatedAtEpochMillis)
        assertTrue(paths.indexFile.isDirectory)
    }

    @Test
    fun persistenceSkipsCorruptSessionFilesDuringList() {
        val root = createTempDir(prefix = "pointpatch-v2-corrupt-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        paths.sessionDirectory("session-bad").mkdirs()
        paths.sessionFile("session-bad").writeText("{not json")

        val listed = persistence.list()

        assertEquals(emptyList(), listed.sessions)
        assertEquals(listOf(paths.sessionFile("session-bad").absolutePath), listed.skippedSessions.map { it.path })
    }
}

private object PointPatchRectForTest {
    val bounds = io.github.pointpatch.compose.core.model.PointPatchRect(1f, 2f, 3f, 4f)
}
