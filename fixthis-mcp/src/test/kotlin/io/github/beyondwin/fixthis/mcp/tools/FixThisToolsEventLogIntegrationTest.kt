package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixThisToolsEventLogIntegrationTest {

    @Test
    fun defaultToolsWriteEventLogWhenConsoleBatchIsSaved() {
        val projectRoot = Files.createTempDirectory("fixthis-tools-event-log").toFile()
        val tools = FixThisTools(
            bridge = FakeFixThisBridge(),
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = projectRoot,
        )
        try {
            val opened = runBlocking {
                tools.call(
                    "fixthis_open_feedback_console",
                    buildJsonObject {
                        put("newSession", true)
                    },
                )
            }.firstStructuredPayload()
            val client = ConsoleClient(opened.getValue("consoleUrl").jsonPrimitive.content)
            val preview = fixThisJson.parseToJsonElement(client.get("/api/preview")).jsonObject
            val previewId = preview.getValue("previewId").jsonPrimitive.content

            val saveResponse = client.postJson(
                "/api/items/batch",
                """
                {
                  "previewId": "$previewId",
                  "items": [
                    {
                      "targetType": "area",
                      "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                      "comment": "Change headline"
                    }
                  ]
                }
                """.trimIndent(),
            )

            assertEquals(200, saveResponse.responseCode)
            val eventLogs = File(projectRoot, ".fixthis/feedback-sessions")
                .walkTopDown()
                .filter { it.isFile && it.extension == "jsonl" }
                .toList()
            assertTrue(
                eventLogs.isNotEmpty(),
                "Expected at least one event log JSONL file under default feedback session storage",
            )
        } finally {
            tools.close()
            projectRoot.deleteRecursively()
        }
    }

    private fun JsonObject.firstStructuredPayload(): JsonObject = this["structuredContent"]?.jsonObject
        ?: this["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.let {
            Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject
        }
        ?: error("Cannot locate structured payload in $this")

    private class ConsoleClient(private val baseUrl: String) {
        private val baseUri = URI(baseUrl)
        private val originUrl = "${baseUri.scheme}://${baseUri.rawAuthority}"
        private val consoleToken: String? by lazy {
            baseUri.rawFragment
                ?.split('&')
                ?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
                ?.firstOrNull { it[0] == "consoleToken" }
                ?.get(1)
                ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        }

        fun get(path: String): String = URI(originUrl + path).toURL().openConnection().let { connection ->
            (connection as HttpURLConnection).applyToken(path)
            connection.inputStream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        }

        fun postJson(path: String, body: String): HttpURLConnection {
            val connection = URI(originUrl + path).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.applyToken(path)
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            return connection
        }

        private fun HttpURLConnection.applyToken(path: String) {
            if (path.startsWith("/api/")) {
                consoleToken?.let { setRequestProperty("X-FixThis-Console-Token", it) }
            }
        }
    }
}
