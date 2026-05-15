package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class MainCommandTest {
    @Test
    fun rootHelpPrintsFixThisUsage() {
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            "io.github.beyondwin.fixthis.cli.MainKt",
            "--help",
        )
            .redirectErrorStream(false)
            .start()

        try {
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val stdout = if (process.isAlive) "" else process.inputStream.bufferedReader().readText()
            val stderr = if (process.isAlive) "" else process.errorStream.bufferedReader().readText()

            assertTrue("CLI help process timed out", finished)
            assertEquals(stderr, 0, process.exitValue())
            assertEquals("", stderr)
            assertTrue(stdout, stdout.contains("Usage: fixthis"))
            assertTrue(stdout, stdout.contains("init"))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
