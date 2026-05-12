package io.beyondwin.fixthis.mcp.session.eventlog

import io.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.serialization.json.Json
import java.io.File

private val compactorJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class EventLogCompactor(
    private val directory: File,
    private val snapshotProvider: () -> SessionDto,
    private val snapshotWriter: (SessionDto) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun runOnce(threshold: Int = 1000) {
        val files = directory.listFiles { f -> f.isFile && f.extension == "jsonl" }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.size <= threshold) return
        val toArchive = files.dropLast(threshold)
        val compactedThroughSequenceNumber = readSequenceNumber(toArchive.last())
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
        toArchive.forEach { file ->
            file.renameTo(File(archive, file.name))
        }
    }

    private fun readSequenceNumber(file: File): Long {
        val line = file.readText(Charsets.UTF_8).trimEnd()
        return compactorJson.decodeFromString(SessionEvent.serializer(), line).sequenceNumber
    }
}
