package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleHttpErrorBodyTest {
    @Test
    fun errorBodyCarriesDetails() {
        val encoded = fixThisJson.encodeToString(
            ConsoleErrorBody.serializer(),
            ConsoleErrorBody(
                error = "screen_fingerprint_mismatch",
                message = "The screen changed.",
                action = "choose_recapture_force_or_cancel",
                details = mapOf("sessionId" to "session-1"),
            ),
        )
        val obj = fixThisJson.parseToJsonElement(encoded).jsonObject

        assertEquals("screen_fingerprint_mismatch", obj.getValue("error").jsonPrimitive.content)
        assertEquals("choose_recapture_force_or_cancel", obj.getValue("action").jsonPrimitive.content)
        assertEquals(
            "session-1",
            obj.getValue("details").jsonObject.getValue("sessionId").jsonPrimitive.content,
        )
    }
}
