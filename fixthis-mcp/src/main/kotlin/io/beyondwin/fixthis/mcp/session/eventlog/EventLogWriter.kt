package io.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val eventLogJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

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
    // ThrowsCount: write, sync, and rename are three distinct durable-write failure modes,
    // each surfacing as a unique EventLogException — suppression is intentional.
    // TooGenericExceptionCaught: the generic catch intentionally wraps any IO/runtime error
    // from RandomAccessFile (e.g. IOException, OutOfMemoryError) as EventLogException.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
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
        } catch (e: EventLogException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw EventLogException("Failed to append event ${event.eventId}: ${e.message}", e)
        }
        if (!tmp.renameTo(finalFile)) {
            tmp.delete()
            throw EventLogException("Atomic rename failed for ${tmp.name}")
        }
    }

    @Synchronized
    fun writeCheckpoint(checkpoint: EventLogCheckpoint) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val tmp = File.createTempFile("checkpoint-", ".json.tmp", directory)
        val finalFile = File(directory, "checkpoint.json")
        try {
            tmp.writeText(eventLogJson.encodeToString(EventLogCheckpoint.serializer(), checkpoint))
            replaceFile(tmp, finalFile)
        } catch (e: Exception) {
            tmp.delete()
            throw EventLogException("Failed to write event log checkpoint for ${checkpoint.sessionId}: ${e.message}", e)
        }
    }
}

class EventLogReader(private val directory: File) {
    val checkpointFile: File
        get() = File(directory, "checkpoint.json")

    fun readAll(): List<SessionEvent> {
        val files = directory.listFiles { f -> f.extension == "jsonl" } ?: return emptyList()
        return files
            .sortedBy { it.name }
            .map { file ->
                val line = file.readText(Charsets.UTF_8).trimEnd()
                eventLogJson.decodeFromString(SessionEvent.serializer(), line)
            }
    }

    fun readCheckpointOrNull(): EventLogCheckpoint? {
        val file = checkpointFile
        if (!file.isFile) return null
        return eventLogJson.decodeFromString(EventLogCheckpoint.serializer(), file.readText(Charsets.UTF_8))
    }

    fun maxActiveSequenceNumberOrNull(): Long? {
        val files = directory.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: return null
        return files.mapNotNull { file -> sequenceNumberFromFileName(file.name) }.maxOrNull()
    }

    private fun sequenceNumberFromFileName(name: String): Long? =
        name.substringAfter("-", missingDelimiterValue = "")
            .substringBefore(".jsonl")
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
}

class EventLogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun replaceFile(source: File, target: File) {
    runCatching {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.getOrElse { error ->
        if (error !is AtomicMoveNotSupportedException) throw error
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
