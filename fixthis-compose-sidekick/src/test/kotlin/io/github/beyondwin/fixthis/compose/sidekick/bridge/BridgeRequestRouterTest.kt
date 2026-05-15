package io.github.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeRequestRouterTest {
    @Test
    fun routeReturnsNullForUnknownMethod() = runBlocking {
        val router = BridgeRequestRouter(listOf(TestHandler(method = "heartbeat")))

        assertNull(router.route(method = "status", params = JsonObject(emptyMap())))
    }

    @Test
    fun routeDelegatesToMatchingHandlerWithParams() = runBlocking {
        val params = JsonObject(mapOf("expectedText" to JsonPrimitive("Pay now")))
        val handler = TestHandler(method = "verifyUiChange", response = JsonPrimitive("handled"))
        val router = BridgeRequestRouter(listOf(handler))

        val result = router.route(method = "verifyUiChange", params = params)

        assertEquals(JsonPrimitive("handled"), result)
        assertEquals(params, handler.seenParams)
    }

    private class TestHandler(
        override val method: String,
        private val response: JsonElement = JsonPrimitive(true),
    ) : BridgeMethodHandler {
        var seenParams: JsonObject? = null

        override suspend fun handle(params: JsonObject): JsonElement {
            seenParams = params
            return response
        }
    }
}
