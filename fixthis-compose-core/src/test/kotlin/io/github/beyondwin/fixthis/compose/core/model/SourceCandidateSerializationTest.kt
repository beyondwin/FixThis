package io.github.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCandidateSerializationTest {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun deserializesLegacyJsonWithoutNewFields() {
        val legacy = """
            {
              "file": "Foo.kt",
              "line": 12,
              "score": 0.5,
              "matchedTerms": ["Pay now"],
              "matchReasons": ["selected text"],
              "confidence": "MEDIUM"
            }
        """.trimIndent()

        val candidate = json.decodeFromString(SourceCandidate.serializer(), legacy)

        assertEquals("Foo.kt", candidate.file)
        assertEquals(12, candidate.line)
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertEquals(null, candidate.ranking)
        assertEquals(null, candidate.scoreMargin)
        assertEquals(null, candidate.evidenceStrength)
        assertTrue(candidate.riskFlags.isEmpty())
        assertEquals(null, candidate.caution)
        assertEquals(null, candidate.stale)
        assertEquals(null, candidate.staleReason)
    }

    @Test
    fun serializesNewFieldsWhenPresentAndOmitsDefaults() {
        val candidate = SourceCandidate(
            file = "Bar.kt",
            line = 7,
            score = 0.8,
            matchedTerms = listOf("Pay now"),
            matchReasons = listOf("selected text"),
            confidence = SelectionConfidence.MEDIUM,
            ranking = 1,
            scoreMargin = 0.42,
            evidenceStrength = SourceEvidenceStrength.MEDIUM,
            riskFlags = listOf(SourceCandidateRisk.TEXT_ONLY),
            caution = "Text-only match; confirm against screenshot and code.",
        )

        val text = json.encodeToString(SourceCandidate.serializer(), candidate)
        val round = json.decodeFromString(SourceCandidate.serializer(), text)

        assertEquals(candidate, round)
        assertTrue(text.contains("\"ranking\""))
        assertTrue(text.contains("\"riskFlags\""))
        // §12: stale=null must not appear in serialized output (encodeDefaults=false)
        assertTrue("stale=null should be omitted from JSON", !text.contains("\"stale\""))
        assertTrue("staleReason=null should be omitted from JSON", !text.contains("\"staleReason\""))
    }

    @Test
    fun roundTripsStaleFields() {
        val candidate = SourceCandidate(
            file = "Foo.kt",
            line = 42,
            score = 0.9,
            confidence = SelectionConfidence.HIGH,
            stale = true,
            staleReason = "excerpt mismatch",
        )

        val text = json.encodeToString(SourceCandidate.serializer(), candidate)
        val round = json.decodeFromString(SourceCandidate.serializer(), text)

        assertEquals(candidate, round)
        assertEquals(true, round.stale)
        assertEquals("excerpt mismatch", round.staleReason)
        assertTrue(text.contains("\"stale\""))
        assertTrue(text.contains("\"staleReason\""))
    }

    @Test
    fun roundTripsLegacyToCurrent() {
        val legacy = """
            {
              "file": "Foo.kt",
              "score": 0.3,
              "matchReasons": ["selected text"],
              "confidence": "LOW"
            }
        """.trimIndent()

        val candidate = json.decodeFromString(SourceCandidate.serializer(), legacy)
        val reSerialized = json.encodeToString(SourceCandidate.serializer(), candidate)
        val round = json.decodeFromString(SourceCandidate.serializer(), reSerialized)

        assertEquals(SelectionConfidence.LOW, round.confidence)
        assertTrue(round.riskFlags.isEmpty())
    }
}
