package io.github.beyondwin.fixthis.cli.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceCommandPlannerTest {
    @Test
    fun plannerUsesOnlyAllowlistedArgumentLists() {
        val plan = RuntimeEvidenceCommandPlanner.baseline(
            packageName = "io.example.app",
            pid = 123,
            logcatSince = "07-12 10:14:58.000",
        )

        assertEquals(listOf("logcat", "-d", "-v", "epoch", "--pid", "123", "-T", "07-12 10:14:58.000", "-t", "2000"), plan.appLog.arguments)
        assertEquals(listOf("logcat", "-b", "crash", "-d", "-v", "epoch", "-t", "200"), plan.crashLog.arguments)
        assertEquals(listOf("shell", "dumpsys", "activity", "exit-info", "io.example.app"), plan.exitInfo.arguments)
        assertEquals(listOf("shell", "dumpsys", "meminfo", "io.example.app"), plan.memory.arguments)
        assertEquals(listOf("shell", "dumpsys", "gfxinfo", "io.example.app"), plan.frame.arguments)
        assertTrue(plan.allCommands().none { "sh" in it.arguments || "-c" in it.arguments })
    }

    @Test
    fun plannerRejectsNonApplicationIdsBeforeBuildingCommands() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEvidenceCommandPlanner.baseline("io.example.app;rm", 123, null)
        }
    }
}
