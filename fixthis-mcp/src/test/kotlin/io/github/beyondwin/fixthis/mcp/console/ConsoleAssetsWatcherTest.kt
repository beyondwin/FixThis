package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConsoleAssetsWatcherTest {
    private val tempDir: File = Files.createTempDirectory("console-assets-watcher").toFile()

    @AfterTest fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun writeMeta(hash: String) {
        File(tempDir, "console-build-meta.json")
            .writeText("""{"buildEpochMs":0,"gitSha":"$hash"}""" + "\n")
    }

    @Test
    fun emitsConsoleAssetsChangedWhenMetaFileMtimeAdvances() {
        writeMeta("aaaaaa1")
        val bus = ConsoleEventBus()
        val seen = mutableListOf<ConsoleEvent>()
        val latch = CountDownLatch(1)
        val subscription = bus.subscribe { ev ->
            if (ev.name == "console-assets-changed") {
                seen += ev
                latch.countDown()
            }
        }
        val watcher = ConsoleAssetsWatcher(tempDir, bus, pollIntervalMillis = 50)
        try {
            watcher.start()
            Thread.sleep(1100)
            writeMeta("bbbbbb2")
            assertEquals(true, latch.await(3, TimeUnit.SECONDS), "watcher must emit within 3s")
            val event = seen.single()
            assertEquals("bbbbbb2", event.data["buildHash"]!!.jsonPrimitive.content)
            assertNotNull(event.data["at"])
        } finally {
            subscription.close()
            watcher.stop()
        }
    }

    @Test
    fun doesNotEmitWhenHashUnchanged() {
        writeMeta("samehash")
        val bus = ConsoleEventBus()
        val seen = mutableListOf<ConsoleEvent>()
        val subscription = bus.subscribe { ev ->
            if (ev.name == "console-assets-changed") seen += ev
        }
        val watcher = ConsoleAssetsWatcher(tempDir, bus, pollIntervalMillis = 50)
        try {
            watcher.start()
            Thread.sleep(1100)
            writeMeta("samehash")
            Thread.sleep(500)
            assertEquals(0, seen.size, "watcher must dedup on identical hash")
        } finally {
            subscription.close()
            watcher.stop()
        }
    }
}
