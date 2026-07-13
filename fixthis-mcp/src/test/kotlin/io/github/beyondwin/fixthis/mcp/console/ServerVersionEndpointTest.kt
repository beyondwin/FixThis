package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.BuildInfo
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerVersionEndpointTest {
    @Test
    fun serverVersionReturnsExpectedBuildInfo() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = java.net.URI(server.originUrl + "/api/server-version").toURL()
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"

            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))

            val body = connection.inputStream.bufferedReader().readText()
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals(BuildInfo.BUILD_EPOCH_MS, json.getValue("serverBuildEpochMs").jsonPrimitive.long)
            assertEquals(BuildInfo.GIT_SHA, json.getValue("serverGitSha").jsonPrimitive.content)
            assertEquals("1.3", json.getValue("bridgeProtocolVersion").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun serverVersionRejectNonGetMethod() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            // HEAD is a safe non-GET method that bypasses the mutation guard (not in ConsoleMutatingMethods),
            // so the request reaches routing and the requireMethod("GET") check returns 405.
            val connection = java.net.URI(server.originUrl + "/api/server-version").toURL()
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"

            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }
}
