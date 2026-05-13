package io.beyondwin.fixthis.mcp.session.eventlog

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SessionDto
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventLogCompactorTest {

    private fun makeEvent(seq: Long, itemId: String = "item-$seq") = SessionEvent(
        eventId = "evt-$seq",
        sequenceNumber = seq,
        epochMillis = 1_715_500_000_000L + seq,
        actor = "console",
        type = "addItem",
        payload = buildJsonObject { put("itemId", itemId) },
    )

    private fun makeSession(
        sessionId: String = "session-1",
        updatedAtEpochMillis: Long = 1_715_500_000_000L,
    ) = SessionDto(
        sessionId = sessionId,
        packageName = "com.test",
        projectRoot = "/project",
        createdAtEpochMillis = 1_715_400_000_000L,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun makeScreen() = SnapshotDto(
        screenId = "pending",
        capturedAtEpochMillis = 0L,
        displayName = "CompactionScreen",
    )

    private fun makeDraftItem(screenId: String, index: Int) = AnnotationDto(
        itemId = "pending",
        screenId = screenId,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
        comment = "item-$index",
        delivery = FeedbackDelivery.DRAFT,
    )

    private fun idGenerator(): () -> String {
        var counter = 0
        return { "id-${++counter}" }
    }

    private fun eventWriterFor(paths: FeedbackSessionPaths): (String) -> EventLogWriter = { sessionId ->
        EventLogWriter(paths.eventLogDirectory(sessionId))
    }

    private fun fastEventWriterFor(paths: FeedbackSessionPaths): (String) -> EventLogWriter = { sessionId ->
        EventLogWriter(
            directory = paths.eventLogDirectory(sessionId),
            durability = EventLogDurability.Fast,
        )
    }

    private fun eventReaderFor(paths: FeedbackSessionPaths): (String) -> EventLogReader = { sessionId ->
        EventLogReader(paths.eventLogDirectory(sessionId))
    }

    @Test
    fun compactedSessionReplaysFromSnapshotPlusActiveEvents() {
        val projectRoot = Files.createTempDirectory("compactor-replay").toFile()
        try {
            val paths = FeedbackSessionPaths(projectRoot)
            val persistence = FeedbackSessionPersistence(paths)
            var now = 1_715_500_000_000L
            val store = FeedbackSessionStore(
                clock = { ++now },
                idGenerator = idGenerator(),
                persistence = persistence,
                eventLogWriterProvider = fastEventWriterFor(paths),
                eventLogReaderProvider = eventReaderFor(paths),
            )
            val session = store.openSession("com.test", projectRoot.absolutePath)
            val screen = store.addScreen(session.sessionId, makeScreen())
            repeat(24) { index ->
                store.addItem(session.sessionId, makeDraftItem(screen.screenId, index + 1))
            }
            val sent = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "handoff")
            val expected = sent

            EventLogCompactor(
                paths.eventLogDirectory(session.sessionId),
                snapshotProvider = { store.getSession(session.sessionId) },
                snapshotWriter = { persistence.save(it) },
                clock = { ++now },
            ).runOnce(threshold = 10)

            val replayedStore = FeedbackSessionStore(
                clock = { ++now },
                idGenerator = idGenerator(),
                persistence = persistence,
                eventLogWriterProvider = eventWriterFor(paths),
                eventLogReaderProvider = eventReaderFor(paths),
            )
            val replayed = replayedStore.getSession(session.sessionId)

            assertEquals(expected.screens, replayed.screens)
            assertEquals(expected.items, replayed.items)
            assertEquals(expected.handoffBatches, replayed.handoffBatches)
            assertEquals(expected.status, replayed.status)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun compactionMovesOldestFilesWhenAboveThreshold() {
        val dir = Files.createTempDirectory("compactor-primary").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
            // Append 12 events so each event becomes one .jsonl file.
            repeat(12) { i -> writer.append(makeEvent((i + 1).toLong())) }

            val snapshot = makeSession(updatedAtEpochMillis = 1_715_500_000_012L)
            var writtenSnapshot: SessionDto? = null
            EventLogCompactor(
                eventsDir,
                snapshotProvider = { snapshot },
                snapshotWriter = { writtenSnapshot = it },
                clock = { 1_715_500_002_000L },
            ).runOnce(threshold = 10)

            val archiveDir = File(eventsDir, "archive")
            assertTrue(archiveDir.exists(), "archive/ directory should exist after compaction")
            val archiveFiles = archiveDir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
            assertEquals(2, archiveFiles.size, "archive/ should contain the 2 oldest files")

            val remainingFiles = eventsDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()
            assertEquals(10, remainingFiles.size, "events/ should retain the newest 10 files")

            assertEquals(snapshot, writtenSnapshot)
            val checkpoint = EventLogReader(eventsDir).readCheckpointOrNull()
            assertEquals(
                EventLogCheckpoint(
                    sessionId = snapshot.sessionId,
                    compactedThroughSequenceNumber = 2L,
                    snapshotUpdatedAtEpochMillis = snapshot.updatedAtEpochMillis,
                    createdAtEpochMillis = 1_715_500_002_000L,
                ),
                checkpoint,
            )
            assertFalse(File(dir, "state.json").exists(), "state.json should not be written after compaction")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noOpWhenBelowThreshold() {
        val dir = Files.createTempDirectory("compactor-noop").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
            // Append only 5 events, below the threshold of 10.
            repeat(5) { i -> writer.append(makeEvent((i + 1).toLong())) }

            var snapshotCalled = false
            EventLogCompactor(eventsDir, snapshotProvider = {
                snapshotCalled = true
                makeSession()
            }, snapshotWriter = {
                snapshotCalled = true
            }).runOnce(threshold = 10)

            // Archive dir should NOT exist (or be empty)
            val archiveDir = File(eventsDir, "archive")
            val archiveFiles = if (archiveDir.exists()) {
                archiveDir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
            } else {
                emptyArray()
            }
            assertEquals(0, archiveFiles.size, "No files should be archived when below threshold")

            // state.json must NOT be written
            assertFalse(File(dir, "state.json").exists(), "state.json should NOT be written when below threshold")
            assertEquals(
                null,
                EventLogReader(eventsDir).readCheckpointOrNull(),
                "checkpoint should NOT be written when below threshold",
            )

            val remaining = eventsDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()
            assertEquals(5, remaining.size, "All 5 original files should remain")

            assertFalse(snapshotCalled, "snapshotProvider should not be called when below threshold")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldestFilesArchivedAndNewestRetained() {
        val dir = Files.createTempDirectory("compactor-order").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
            // Append 5 events with sequence numbers 1..5
            repeat(5) { i -> writer.append(makeEvent((i + 1).toLong())) }

            val snapshot = makeSession(updatedAtEpochMillis = 1_715_500_000_005L)
            EventLogCompactor(
                eventsDir,
                snapshotProvider = { snapshot },
                snapshotWriter = {},
                clock = { 1_715_500_000_006L },
            ).runOnce(threshold = 2)

            val archiveDir = File(eventsDir, "archive")
            assertTrue(archiveDir.exists(), "archive/ dir should exist")

            val archiveFiles = archiveDir.listFiles { f -> f.extension == "jsonl" }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
            val mainFiles = eventsDir.listFiles { f -> f.isFile && f.extension == "jsonl" }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()

            // 3 oldest (seq 1, 2, 3) should be archived
            assertEquals(3, archiveFiles.size, "3 oldest files should be in archive")
            // 2 newest (seq 4, 5) should remain in main dir
            assertEquals(2, mainFiles.size, "2 newest files should remain in events/")

            // Verify the names contain the expected sequence numbers
            val archiveSeqs = archiveFiles
                .map { it.substringAfter("-").trimStart('0').substringBefore(".").toLongOrNull() ?: 0L }
                .sorted()
            val mainSeqs = mainFiles
                .map { it.substringAfter("-").trimStart('0').substringBefore(".").toLongOrNull() ?: 0L }
                .sorted()

            assertTrue(
                archiveSeqs.max() < mainSeqs.min(),
                "All archived files should be older than all retained files",
            )
            assertEquals(3L, EventLogReader(eventsDir).readCheckpointOrNull()?.compactedThroughSequenceNumber)
        } finally {
            dir.deleteRecursively()
        }
    }
}
