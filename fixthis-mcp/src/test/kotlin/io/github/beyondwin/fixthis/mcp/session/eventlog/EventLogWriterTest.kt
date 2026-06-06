package io.github.beyondwin.fixthis.mcp.session.eventlog

import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogDurability
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogWriterTest {

    @Test
    fun appendedEventsAreReadableInInsertionOrder() {
        val dir = Files.createTempDirectory("evtlog").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir)
            writer.append(makeEvent(1L, "x"))
            writer.append(makeEvent(2L, "y"))
            val replayed = EventLogReader(eventsDir).readAll()
            assertEquals(listOf(1L, 2L), replayed.map { it.sequenceNumber })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appendCreatesOneFilePerEvent() {
        val dir = Files.createTempDirectory("evtlog").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir)
            repeat(100) { i -> writer.append(makeEvent((i + 1).toLong(), "item-$i")) }
            val files = eventsDir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()
            assertEquals(100, files.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileNamesUseZeroPaddedFormat() {
        val dir = Files.createTempDirectory("evtlog").toFile()
        try {
            val eventsDir = File(dir, "events")
            val writer = EventLogWriter(eventsDir)
            val event = makeEvent(1L, "x")
            writer.append(event)
            val file = eventsDir.listFiles()!!.single()
            val expected = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
            assertEquals(expected, file.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fastDurabilityWritesReadableEvents() {
        val dir = Files.createTempDirectory("evtlog-fast").toFile()
        try {
            val eventsDir = File(dir, "events")
            val touched = mutableListOf<String>()
            val writer = EventLogWriter(
                directory = eventsDir,
                onWriteHook = { touched += it.fileName.toString() },
                durability = EventLogDurability.Fast,
            )

            writer.append(makeEvent(1L, "fast"))

            val replayed = EventLogReader(eventsDir).readAll()
            assertEquals(listOf(1L), replayed.map { it.sequenceNumber })
            assertEquals(listOf("1715500000001-0000000001.jsonl.tmp"), touched)
            assertEquals(EventLogDurability.Fast, writer.durability)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultConstructorUsesDurableWrites() {
        val dir = Files.createTempDirectory("evtlog-default-durable").toFile()
        try {
            val writer = EventLogWriter(directory = File(dir, "events"))

            assertEquals(EventLogDurability.Durable, writer.durability)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun makeEvent(seq: Long, itemId: String) = SessionEvent(
        eventId = "evt-$seq",
        sequenceNumber = seq,
        epochMillis = 1_715_500_000_000L + seq,
        actor = "console",
        type = "addItem",
        payload = buildJsonObject { put("itemId", itemId) },
    )
}
