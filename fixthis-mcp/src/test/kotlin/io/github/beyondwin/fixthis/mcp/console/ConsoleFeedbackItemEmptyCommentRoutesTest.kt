package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleFeedbackItemEmptyCommentRoutesTest {
    @Test
    fun batchItemsApiRejectsBlankCommentsWithTypedValidationError() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            prefix = "fixthis-console-blank-batch",
            clock = { 100L },
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
        )
        fixture.use { context ->
            context.service.openSession(null, newSession = true)
            val preview = context.client.get("/api/preview")
            val previewId = fixThisJson.parseToJsonElement(preview).jsonObject
                .getValue("previewId")
                .jsonPrimitive
                .content

            val connection = context.client.connection(
                "/api/items/batch",
                method = "POST",
                body = """
                {
                  "previewId": "$previewId",
                  "items": [
                    {
                      "targetType": "area",
                      "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                      "comment": ""
                    }
                  ]
                }
                """.trimIndent(),
            )

            assertEquals(422, connection.responseCode)
            val payload = fixThisJson
                .parseToJsonElement(connection.errorStream.bufferedReader().readText())
                .jsonObject
            assertEquals("empty-comment", payload.getValue("error").jsonPrimitive.content)
            assertTrue(payload.getValue("message").jsonPrimitive.content.contains("empty comment"))
        }
    }
}
