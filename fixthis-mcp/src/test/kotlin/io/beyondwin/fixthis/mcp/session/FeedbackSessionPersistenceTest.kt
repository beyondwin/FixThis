package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackSessionPersistenceTest {
    @Test
    fun pathsStayUnderProjectFeedbackSessionsDirectory() {
        val root = tempDir(prefix = "fixthis-v2-paths-")
        val paths = FeedbackSessionPaths(root)

        val sessionDir = paths.sessionDirectory("session-1")
        val screenDir = paths.screenArtifactDirectory("session-1", "screen-1")

        assertEquals(File(root, ".fixthis/feedback-sessions/session-1").canonicalFile, sessionDir)
        assertEquals(File(root, ".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1").canonicalFile, screenDir)
        assertTrue(screenDir.toPath().startsWith(paths.rootDirectory.toPath()))
    }

    @Test
    fun pathHelpersRejectUnsafeIds() {
        val paths = FeedbackSessionPaths(tempDir(prefix = "fixthis-v2-unsafe-"))

        assertFailsWith<IllegalArgumentException> {
            paths.sessionDirectory("../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            paths.screenArtifactDirectory("session-1", "screen/1")
        }
    }

    @Test
    fun sessionSummaryCountsUnresolvedItems() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 2L, displayName = "Main")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Area(FixThisRectForTest.bounds),
                    comment = "Fix spacing",
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
                    status = AnnotationStatusDto.READY,
                ),
                AnnotationDto(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Area(FixThisRectForTest.bounds),
                    comment = "Done",
                    status = AnnotationStatusDto.RESOLVED,
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
        val root = tempDir(prefix = "fixthis-v2-persist-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )

        persistence.save(session)

        assertTrue(File(root, ".fixthis/feedback-sessions/session-1/session.json").isFile)
        assertTrue(File(root, ".fixthis/feedback-sessions/index.json").isFile)
        assertEquals(session, persistence.load("session-1"))
        assertEquals(listOf("session-1"), persistence.list().sessions.map { it.sessionId })
        val index = fixThisJson.decodeFromString(FeedbackSessionIndex.serializer(), paths.indexFile.readText())
        assertEquals(listOf("session-1"), index.sessions.map { it.sessionId })
    }

    @Test
    fun loadSessionWithoutTargetEvidenceStillWorks() {
        val root = tempDir(prefix = "fixthis-v2-legacy-target-evidence-")
        val paths = FeedbackSessionPaths(root)
        paths.sessionDirectory("session-1").mkdirs()
        paths.sessionFile("session-1").writeText(
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "session-1",
              "packageName": "io.beyondwin.fixthis.sample",
              "projectRoot": "${root.absolutePath}",
              "createdAtEpochMillis": 100,
              "updatedAtEpochMillis": 200,
              "screens": [],
              "items": [],
              "handoffBatches": [],
              "status": "active"
            }
            """.trimIndent(),
        )

        val loaded = FeedbackSessionPersistence(paths).load("session-1")

        assertEquals("session-1", loaded.sessionId)
        assertEquals(0, loaded.items.size)
    }

    @Test
    fun failedIndexWriteLeavesExistingSessionFileUnchanged() {
        val root = tempDir(prefix = "fixthis-v2-partial-save-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val initial = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        persistence.save(initial)
        paths.indexFile.delete()
        assertTrue(paths.indexFile.mkdirs())

        val updated = initial.copy(
            updatedAtEpochMillis = 300L,
            screens = listOf(SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 250L, displayName = "Main")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 260L,
                    updatedAtEpochMillis = 270L,
                    target = AnnotationTargetDto.Area(FixThisRectForTest.bounds),
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
        val root = tempDir(prefix = "fixthis-v2-session-replace-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val existing = SessionDto(
            sessionId = "session-old",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = root.absolutePath,
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        persistence.save(existing)
        val oldIndex = paths.indexFile.readText()
        paths.sessionDirectory("session-new").mkdirs()
        assertTrue(paths.sessionFile("session-new").mkdirs())

        val candidate = SessionDto(
            sessionId = "session-new",
            packageName = "io.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-index-replace-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        val initial = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-corrupt-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
        paths.sessionDirectory("session-bad").mkdirs()
        paths.sessionFile("session-bad").writeText("{not json")

        val listed = persistence.list()

        assertEquals(emptyList(), listed.sessions)
        assertEquals(listOf(paths.sessionFile("session-bad").absolutePath), listed.skippedSessions.map { it.path })
    }
}

private fun tempDir(prefix: String): File =
    kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

private object FixThisRectForTest {
    val bounds = io.beyondwin.fixthis.compose.core.model.FixThisRect(1f, 2f, 3f, 4f)
}
