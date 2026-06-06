package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: agent resolve/claim status must survive an MCP restart when the
 * event log is wired. Boot replay rebuilds items from events only, so a status
 * mutation that bypasses the event log is lost on restart.
 */
class ResolveClaimReplayTest {
    @Test
    fun resolveStatusSurvivesRestartReplay() = runBlocking {
        withRestart { service, sessionId, itemId ->
            service.resolveFeedback(sessionId, itemId, AnnotationStatusDto.RESOLVED, summary = "done")
        }.let { replayed ->
            assertEquals(AnnotationStatusDto.RESOLVED, replayed.status)
            assertEquals("done", replayed.agentSummary)
        }
    }

    @Test
    fun claimStatusSurvivesRestartReplay() = runBlocking {
        withRestart { service, sessionId, itemId ->
            service.claimFeedback(sessionId, itemId, agentNote = "working on it")
        }.let { replayed ->
            assertEquals(AnnotationStatusDto.IN_PROGRESS, replayed.status)
            assertEquals("working on it", replayed.agentSummary)
        }
    }

    private fun withRestart(
        mutate: (FeedbackSessionService, sessionId: String, itemId: String) -> Unit,
    ): AnnotationDto {
        val root = createTempDirectory("fixthis-resolve-claim-replay-").toFile().also { it.deleteOnExit() }
        val eventRoot = File(root, "events")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root))
        var tick = 0L
        var idSeq = 0
        val clock: () -> Long = { ++tick }
        val writerProvider: (String) -> EventLogWriter = { sid -> EventLogWriter(File(eventRoot, "$sid/events")) }
        val readerProvider: (String) -> EventLogReader = { sid -> EventLogReader(File(eventRoot, "$sid/events")) }

        val store1 = FeedbackSessionStore(
            clock = clock,
            idGenerator = { "id-${++idSeq}" },
            persistence = persistence,
            eventLogWriterProvider = writerProvider,
            eventLogReaderProvider = readerProvider,
        )
        val service1 = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store1,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service1.openSession(packageNameOverride = null, newSession = true)
        val screen = runBlocking { service1.captureScreen(session.sessionId) }
        val item = service1.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "fix this",
        )

        mutate(service1, session.sessionId, item.itemId)

        // Simulate SIGKILL/restart: new store from the same persistence + event log paths.
        val store2 = FeedbackSessionStore(
            clock = clock,
            idGenerator = { "replay-${++idSeq}" },
            persistence = persistence,
            eventLogWriterProvider = writerProvider,
            eventLogReaderProvider = readerProvider,
        )
        val service2 = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store2,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        return service2.getSession(session.sessionId).items.first { it.itemId == item.itemId }
    }
}
