package io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import kotlinx.serialization.json.Json
import java.io.File

private val compactorJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val DEFAULT_COMPACTION_THRESHOLD = 1000

private data class EventLogFile(val file: File, val event: SessionEvent)

fun interface EventLogCompactionTask {
    fun runOnce(threshold: Int)
}

class EventLogCompactor(
    private val directory: File,
    private val snapshotProvider: () -> SessionDto,
    private val snapshotWriter: (SessionDto) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EventLogCompactionTask {
    fun runOnce() {
        runOnce(DEFAULT_COMPACTION_THRESHOLD)
    }

    override fun runOnce(threshold: Int) {
        val files = directory.listFiles { f -> f.isFile && f.extension == "jsonl" }
            ?.map { file -> EventLogFile(file, readEvent(file)) }
            ?.sortedBy { it.event.sequenceNumber }
            .orEmpty()
        if (files.size <= threshold) return
        val toArchive = files.dropLast(threshold)
        val compactedThroughSequenceNumber = toArchive.last().event.sequenceNumber
        val snapshot = snapshotProvider()
        snapshotWriter(snapshot)
        EventLogWriter(directory).writeCheckpoint(
            EventLogCheckpoint(
                sessionId = snapshot.sessionId,
                compactedThroughSequenceNumber = compactedThroughSequenceNumber,
                snapshotUpdatedAtEpochMillis = snapshot.updatedAtEpochMillis,
                createdAtEpochMillis = clock(),
            ),
        )
        val archive = File(directory, "archive").apply { mkdirs() }
        toArchive.forEach { entry ->
            entry.file.renameTo(File(archive, entry.file.name))
        }
    }

    private fun readEvent(file: File): SessionEvent {
        val line = file.readText(Charsets.UTF_8).trimEnd()
        return compactorJson.decodeFromString(SessionEvent.serializer(), line)
    }
}
