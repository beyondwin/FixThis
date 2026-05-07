package io.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIndexTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesV1JsonWithDefaultV2Fields() {
        val index = json.decodeFromString<SourceIndex>(
            """
            {
              "schemaVersion": "1.0",
              "entries": [
                {
                  "file": "src/main/java/Sample.kt",
                  "line": 7,
                  "text": ["Pay now"],
                  "testTags": ["pay_button"]
                }
              ]
            }
            """.trimIndent(),
        )

        val entry = index.entries.single()
        assertTrue(entry.signals.isEmpty())
        assertNull(entry.packageName)
        assertNull(entry.className)
    }

    @Test
    fun decodesV2JsonWithTypedSignals() {
        val index = json.decodeFromString<SourceIndex>(
            """
            {
              "schemaVersion": "1.0",
              "entries": [
                {
                  "file": "src/main/java/Sample.kt",
                  "line": 12,
                  "packageName": "io.beyondwin.fixthis.sample",
                  "className": "CheckoutFeature",
                  "signals": [
                    {
                      "kind": "UI_TEXT",
                      "value": "Pay now",
                      "confidenceWeight": 1.0
                    },
                    {
                      "kind": "STRICT_COMP_TEST_TAG",
                      "value": "comp:CheckoutScreen:primary",
                      "confidenceWeight": 1.15
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val entry = index.entries.single()
        assertEquals("io.beyondwin.fixthis.sample", entry.packageName)
        assertEquals("CheckoutFeature", entry.className)
        assertEquals(
            listOf(SourceSignalKind.UI_TEXT, SourceSignalKind.STRICT_COMP_TEST_TAG),
            entry.signals.map { it.kind },
        )
        assertEquals("Pay now", entry.signals.first().value)
        assertEquals(1.0, entry.signals.first().confidenceWeight, 0.0)
    }

    @Test
    fun decodesV2SignalWithoutConfidenceWeightWithDefaultWeight() {
        val index = json.decodeFromString<SourceIndex>(
            """
            {
              "schemaVersion": "2.0",
              "entries": [
                {
                  "file": "src/main/java/Sample.kt",
                  "signals": [
                    {
                      "kind": "UI_TEXT",
                      "value": "Pay now"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1.0, index.entries.single().signals.single().confidenceWeight, 0.0)
    }
}
