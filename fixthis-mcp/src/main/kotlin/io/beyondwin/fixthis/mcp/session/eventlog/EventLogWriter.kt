package io.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

private val eventLogJson = Json { encodeDefaults = true }

class EventLogWriter(private val directory: File) {

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    @Synchronized
    fun append(event: SessionEvent) {
        val fileName = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
        val file = File(directory, fileName)
        val line = eventLogJson.encodeToString(SessionEvent.serializer(), event) + "\n"
        RandomAccessFile(file, "rwd").use { raf ->
            raf.write(line.toByteArray(Charsets.UTF_8))
            raf.channel.force(true)
        }
    }
}

class EventLogReader(private val directory: File) {

    fun readAll(): List<SessionEvent> {
        val files = directory.listFiles { f -> f.extension == "jsonl" } ?: return emptyList()
        return files
            .sortedBy { it.name }
            .map { file ->
                val line = file.readText(Charsets.UTF_8).trimEnd()
                eventLogJson.decodeFromString(SessionEvent.serializer(), line)
            }
    }
}
