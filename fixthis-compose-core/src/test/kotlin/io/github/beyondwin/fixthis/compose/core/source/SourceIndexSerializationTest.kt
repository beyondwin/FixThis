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

    @Test
    fun defaultSchemaVersionIs13() {
        assertEquals("1.3", SourceIndex().schemaVersion)
    }

    @Test
    fun roundTripsTestTagConventions() {
        val withConventions = SourceIndex(testTagConventions = listOf("^([A-Za-z]+)_([A-Za-z0-9]+)$"))
        val roundTrip = Json.decodeFromString(SourceIndex.serializer(), Json.encodeToString(SourceIndex.serializer(), withConventions))
        assertEquals(listOf("^([A-Za-z]+)_([A-Za-z0-9]+)$"), roundTrip.testTagConventions)
    }

    @Test
    fun roundTripsSharedComponentSignal() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "app/ui/Button.kt",
                    line = 10,
                    symbols = listOf("PrimaryButton"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "3"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val encoded = Json.encodeToString(SourceIndex.serializer(), index)
        val decoded = Json.decodeFromString(SourceIndex.serializer(), encoded)

        val signal = decoded.entries.single().signals.single { it.kind == SourceSignalKind.SHARED_COMPONENT }
        assertEquals("3", signal.value)
    }

    @Test
    fun roundTripsSharedComponentCallSiteSignal() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "ui/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "3"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42"),
                    ),
                ),
            ),
        )

        val encoded = Json.encodeToString(SourceIndex.serializer(), index)
        val decoded = Json.decodeFromString(SourceIndex.serializer(), encoded)

        val callSite = decoded.entries.single().signals.single {
            it.kind == SourceSignalKind.SHARED_COMPONENT_CALL_SITE
        }
        assertEquals("ui/ScreenA.kt:42", callSite.value)
    }
}
