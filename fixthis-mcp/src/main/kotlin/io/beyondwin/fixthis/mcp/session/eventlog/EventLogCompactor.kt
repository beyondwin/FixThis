package io.beyondwin.fixthis.mcp.session.eventlog

import java.io.File

class EventLogCompactor(
    private val directory: File,
    private val snapshotProvider: () -> String,
) {
    fun runOnce(threshold: Int = 1000) {
        val files = directory.listFiles { f -> f.isFile && f.extension == "jsonl" }
            ?.sortedBy { it.name }
            ?: return
        if (files.size <= threshold) return
        val archive = File(directory, "archive").apply { mkdirs() }
        val toArchive = files.dropLast(threshold)
        toArchive.forEach { it.renameTo(File(archive, it.name)) }
        val parent = directory.parentFile ?: return
        File(parent, "state.json").writeText(snapshotProvider())
    }
}
