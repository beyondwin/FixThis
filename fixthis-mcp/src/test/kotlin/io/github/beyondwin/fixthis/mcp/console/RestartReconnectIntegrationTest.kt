package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionList
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestartReconnectIntegrationTest {
    @Test
    fun draftPersistedBeforeRestartIsVisibleAfter() {
        val projectRoot = Files.createTempDirectory("fixthis-restart-reconnect").toFile()
        try {
            val before = startServerAddDraftAndStop(projectRoot)
            val after = restartAndReadState(projectRoot)

            val summary = after.sessions.sessions.firstOrNull { it.sessionId == before.sessionId }
            assertNotNull(
                summary,
                "Session ${before.sessionId} missing from /api/sessions after restart. " +
                    "Saw: ${after.sessions.sessions.map { it.sessionId }}",
            )
            assertEquals(1, summary.itemsCount, "Persisted draft item missing from /api/sessions summary")
            assertEquals(1, summary.draftItemsCount, "Persisted item should still be a draft after restart")
            assertEquals(1, summary.screensCount, "Captured screen should survive restart")

            assertEquals(
                before.sessionId,
                after.currentSession.sessionId,
                "Most-recently-active session should be restored as current after restart",
            )
            val item = after.currentSession.items.singleOrNull()
                ?: error("Expected exactly one persisted item after restart, got ${after.currentSession.items.size}")
            assertEquals(before.itemId, item.itemId)
            assertEquals("Persisted draft", item.comment)
            assertEquals(FeedbackDelivery.DRAFT, item.delivery)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private data class PreRestartState(val sessionId: String, val itemId: String)

    private data class PostRestartState(
        val sessions: FeedbackSessionList,
        val currentSession: SessionDto,
    )

    private fun startServerAddDraftAndStop(projectRoot: File): PreRestartState {
        val service = newService(projectRoot)
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val session = service.openSession(packageNameOverride = null, newSession = true)
            val screen = runBlocking { service.captureScreen(session.sessionId) }
            val item = service.addAreaFeedback(
                sessionId = session.sessionId,
                screenId = screen.screenId,
                bounds = FixThisRect(1f, 2f, 30f, 40f),
                comment = "Persisted draft",
            )
            return PreRestartState(sessionId = session.sessionId, itemId = item.itemId)
        } finally {
            server.stop()
        }
    }

    private fun restartAndReadState(projectRoot: File): PostRestartState {
        val service = newService(projectRoot)
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/sessions")
            val wire = fixThisJson.parseToJsonElement(body).jsonObject
            assertTrue(
                wire.containsKey("sessions"),
                "Expected /api/sessions response to contain a 'sessions' field. Body: $body",
            )
            val list = fixThisJson.decodeFromString(FeedbackSessionList.serializer(), body)
            val current = service.currentSessionOrNull()
                ?: error(
                    "currentSessionOrNull() returned null after restart; persistence did not restore the open session.",
                )
            return PostRestartState(sessions = list, currentSession = current)
        } finally {
            server.stop()
        }
    }

    private fun newService(projectRoot: File): FeedbackSessionService {
        val paths = FeedbackSessionPaths(projectRoot)
        val persistence = FeedbackSessionPersistence(paths)
        val store = FeedbackSessionStore(
            persistence = persistence,
            idGenerator = { UUID.randomUUID().toString() },
        )
        return FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
    }
}
