package io.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

private val eventLogJson = Json { encodeDefaults = true }

class EventLogWriter(
    private val directory: File,
    private val onWriteHook: (java.nio.file.Path) -> Unit = {},
) {

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    @Synchronized
    fun append(event: SessionEvent) {
        val name = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
        val tmp = File(directory, "$name.tmp")
        val finalFile = File(directory, name)
        try {
            RandomAccessFile(tmp, "rwd").use { raf ->
                onWriteHook(tmp.toPath())
                val line = eventLogJson.encodeToString(SessionEvent.serializer(), event) + "\n"
                raf.write(line.toByteArray(Charsets.UTF_8))
                raf.channel.force(true)
            }
            if (!tmp.renameTo(finalFile)) {
                throw EventLogException("Atomic rename failed for ${tmp.name}")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is EventLogException) throw e
            throw EventLogException("Failed to append event ${event.eventId}: ${e.message}", e)
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

class EventLogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
