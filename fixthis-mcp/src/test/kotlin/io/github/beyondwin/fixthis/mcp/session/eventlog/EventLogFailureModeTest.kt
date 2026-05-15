package io.github.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventLogFailureModeTest {

    private fun makeEvent(seq: Long = 1L, itemId: String = "x") = SessionEvent(
        eventId = "evt-$seq",
        sequenceNumber = seq,
        epochMillis = 1_715_500_000_000L + seq,
        actor = "console",
        type = "addItem",
        payload = buildJsonObject { put("itemId", itemId) },
    )

    @Test
    fun writeFailureThrowsEventLogExceptionAndLeavesNoPartialFiles() {
        val dir = Files.createTempDirectory("evtlog-fail").toFile()
        try {
            val writer = EventLogWriter(
                directory = dir,
                onWriteHook = { throw IOException("disk full") },
            )
            val event = makeEvent(1L)

            assertFailsWith<EventLogException> {
                writer.append(event)
            }

            val allFiles = dir.listFiles() ?: emptyArray()
            val jsonlFiles = allFiles.filter { it.extension == "jsonl" }
            val tmpFiles = allFiles.filter { it.name.endsWith(".tmp") }
            assertTrue(
                jsonlFiles.isEmpty(),
                "No .jsonl files should remain after write failure, found: ${jsonlFiles.map { it.name }}",
            )
            assertTrue(
                tmpFiles.isEmpty(),
                "No .tmp files should remain after write failure, found: ${tmpFiles.map { it.name }}",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renameFailureThrowsEventLogExceptionWithMessageAndLeavesNoOrphanTmp() {
        val dir = Files.createTempDirectory("evtlog-rename-fail").toFile()
        try {
            val event = makeEvent(1L)
            // Pre-create the final file name as a directory so renameTo returns false
            val finalName = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
            val blocker = File(dir, finalName)
            blocker.mkdir()

            val writer = EventLogWriter(directory = dir)

            val ex = assertFailsWith<EventLogException> {
                writer.append(event)
            }

            assertTrue(
                ex.message?.contains("Atomic rename failed") == true,
                "Expected 'Atomic rename failed' in message, got: ${ex.message}",
            )

            val tmpFiles = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
            assertTrue(
                tmpFiles.isEmpty(),
                "No .tmp orphan should remain after rename failure, found: ${tmpFiles.map { it.name }}",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun happyPathWithDefaultConstructorCreatesCorrectlyNamedFile() {
        val dir = Files.createTempDirectory("evtlog-happy").toFile()
        try {
            val writer = EventLogWriter(directory = dir)
            val event = makeEvent(42L, "hello")
            writer.append(event)

            val files = dir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
            assertEquals(1, files.size, "Expected exactly one .jsonl file")
            val expectedName = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
            assertEquals(expectedName, files.single().name)

            val tmpFiles = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
            assertTrue(tmpFiles.isEmpty(), "No .tmp files should remain after successful write")
        } finally {
            dir.deleteRecursively()
        }
    }
}
