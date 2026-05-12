package io.beyondwin.fixthis.mcp.session.eventlog

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

    @Test
    fun compactionMovesOldestFilesWhenAboveThreshold() {
        val dir = Files.createTempDirectory("compactor-primary").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir)
            // Append 1100 events so each event becomes one .jsonl file
            repeat(1100) { i -> writer.append(makeEvent((i + 1).toLong())) }

            val snapshotJson = "{\"items\":[]}"
            EventLogCompactor(eventsDir, snapshotProvider = { snapshotJson }).runOnce(threshold = 1000)

            // Archive dir must exist and contain at least 100 files
            val archiveDir = File(eventsDir, "archive")
            assertTrue(archiveDir.exists(), "archive/ directory should exist after compaction")
            val archiveFiles = archiveDir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
            assertTrue(archiveFiles.size >= 100, "archive/ should contain at least 100 files, found ${archiveFiles.size}")

            // Main events dir (excluding archive/) should have at most 1000 jsonl files
            val remainingFiles = eventsDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()
            assertTrue(remainingFiles.size <= 1000, "events/ should have at most 1000 files after compaction, found ${remainingFiles.size}")

            // state.json must exist with snapshot content
            val stateJson = File(dir, "state.json")
            assertTrue(stateJson.exists(), "state.json should be written after compaction")
            assertEquals(snapshotJson, stateJson.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noOpWhenBelowThreshold() {
        val dir = Files.createTempDirectory("compactor-noop").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir)
            // Append only 50 events — well below threshold of 1000
            repeat(50) { i -> writer.append(makeEvent((i + 1).toLong())) }

            var snapshotCalled = false
            EventLogCompactor(eventsDir, snapshotProvider = {
                snapshotCalled = true
                "{}"
            }).runOnce(threshold = 1000)

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

            // All 50 original files still present
            val remaining = eventsDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()
            assertEquals(50, remaining.size, "All 50 original files should remain")

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
            val writer = EventLogWriter(eventsDir)
            // Append 5 events with sequence numbers 1..5
            repeat(5) { i -> writer.append(makeEvent((i + 1).toLong())) }

            EventLogCompactor(eventsDir, snapshotProvider = { "{}" }).runOnce(threshold = 2)

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
            val archiveSeqs = archiveFiles.map { it.substringAfter("-").trimStart('0').substringBefore(".").toLongOrNull() ?: 0L }.sorted()
            val mainSeqs = mainFiles.map { it.substringAfter("-").trimStart('0').substringBefore(".").toLongOrNull() ?: 0L }.sorted()

            assertTrue(archiveSeqs.max() < mainSeqs.min(), "All archived files should be older than all retained files")
        } finally {
            dir.deleteRecursively()
        }
    }
}
