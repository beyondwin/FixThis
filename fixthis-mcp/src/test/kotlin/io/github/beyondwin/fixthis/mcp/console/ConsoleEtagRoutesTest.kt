package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleEtagRoutesTest {
    @Test
    fun apiSessionsResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            assertEquals(200, first.statusCode)
            val etag = first.header("ETag")
            assertNotNull(etag)
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/sessions", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertEquals(etag, second.header("ETag"))
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsEtagChangesAfterMutation() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions").header("ETag")!!
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val second = client.getResponse("/api/sessions").header("ETag")!!
            assertNotEquals(first, second)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertNotNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/session")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/session", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionWithoutCurrentReturns200NullAndNoEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertEquals("null", response.body.trim())
            assertNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }
}
