package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFallbackRiskSerializationTest {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Test
    fun `untyped fallback risk keeps current wire value`() {
        val candidate = SourceCandidate(
            file = "Foo.kt",
            score = 0.25,
            confidence = SelectionConfidence.LOW,
            riskFlags = listOf(SourceCandidateRisk.UNTYPED_FALLBACK),
        )

        val encoded = json.encodeToString(SourceCandidate.serializer(), candidate)
        val decoded = json.decodeFromString(SourceCandidate.serializer(), encoded)

        assertTrue(encoded.contains("\"LEGACY_FALLBACK\""))
        assertEquals(listOf(SourceCandidateRisk.UNTYPED_FALLBACK), decoded.riskFlags)
    }

    @Test
    fun `current wire value decodes into untyped fallback risk`() {
        val decoded = json.decodeFromString(
            SourceCandidate.serializer(),
            """
            {
              "file": "Foo.kt",
              "score": 0.25,
              "confidence": "LOW",
              "riskFlags": ["LEGACY_FALLBACK"]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(SourceCandidateRisk.UNTYPED_FALLBACK), decoded.riskFlags)
    }
}
