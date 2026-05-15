package io.github.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndexSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes asset with new signal kinds`() {
        val payload = """
            {
              "schemaVersion": "1.2",
              "entries": [{
                "file": "Foo.kt",
                "signals": [
                  {"kind": "STRING_RESOURCE_RESOLVED", "value": "로그인"},
                  {"kind": "LAMBDA_OWNER_FUNCTION", "value": "LoginScreen"}
                ]
              }]
            }
        """.trimIndent()

        val index = json.decodeFromString(SourceIndex.serializer(), payload)

        assertEquals("1.2", index.schemaVersion)
        val signals = index.entries.single().signals
        assertEquals(SourceSignalKind.STRING_RESOURCE_RESOLVED, signals[0].kind)
        assertEquals("로그인", signals[0].value)
        assertEquals(SourceSignalKind.LAMBDA_OWNER_FUNCTION, signals[1].kind)
    }
}
