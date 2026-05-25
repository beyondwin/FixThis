package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ConsoleAssetsWatcher(
    private val assetsDir: File,
    private val eventBus: ConsoleEventBus,
    private val pollIntervalMillis: Long = 500L,
    private val now: () -> Instant = Instant::now,
    private val diagnosticsSink: (String) -> Unit = { System.err.print(it) },
) {
    private val metaFile: File = File(assetsDir, "console-build-meta.json")
    private val lastEmittedHash = AtomicReference<String?>(null)
    private val lastSeenMtime = AtomicReference(0L)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        readCurrentHash()?.let { lastEmittedHash.set(it) }
        lastSeenMtime.set(if (metaFile.exists()) metaFile.lastModified() else 0L)
        val service = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "fixthis-console-assets-watcher").apply { isDaemon = true }
        }
        service.scheduleWithFixedDelay(
            ::tick,
            pollIntervalMillis,
            pollIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
        executor = service
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun tick() {
        runCatching { pollOnce() }.onFailure { error ->
            diagnosticsSink("ConsoleAssetsWatcher: ${error::class.java.name}: ${error.message}\n")
        }
    }

    private fun pollOnce() {
        val hash = nextHashOrNull() ?: return
        lastEmittedHash.set(hash)
        eventBus.emit(
            "console-assets-changed",
            buildJsonObject {
                put("buildHash", hash)
                put("at", now().toString())
            },
        )
    }

    private fun nextHashOrNull(): String? {
        if (!metaFile.exists()) return null
        val mtime = metaFile.lastModified()
        val previousMtime = lastSeenMtime.get()
        return if (mtime == previousMtime) {
            null
        } else {
            lastSeenMtime.set(mtime)
            readCurrentHash()?.takeIf { it != lastEmittedHash.get() }
        }
    }

    private fun readCurrentHash(): String? {
        if (!metaFile.exists()) return null
        return runCatching {
            val parsed = Json.parseToJsonElement(metaFile.readText()) as? JsonObject
            parsed?.get("gitSha")?.jsonPrimitive?.content
        }.getOrNull()
    }
}
