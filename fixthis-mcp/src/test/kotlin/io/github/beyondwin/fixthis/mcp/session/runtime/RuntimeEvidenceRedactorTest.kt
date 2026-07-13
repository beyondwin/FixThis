package io.github.beyondwin.fixthis.mcp.session.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceRedactorTest {
    @Test
    fun redactorRemovesAuthorizationAndPasswordsButKeepsOrdinaryEmail() {
        val actual = RuntimeEvidenceRedactor().redact(
            "Authorization: Bearer abc.def.ghi user=dev@example.com password=hunter2",
        )

        assertEquals(
            "Authorization: [REDACTED] user=dev@example.com password=[REDACTED]",
            actual.text,
        )
        assertTrue(actual.redacted)
    }

    @Test
    fun redactorCoversDefaultSecretShapes() {
        val input = listOf(
            "Cookie: sid=secret-cookie",
            "Set-Cookie: session=secret-session; Path=/",
            "Authorization: Basic basic-auth-secret",
            "jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstuvwxyz0123456789",
            "api_key=api-secret",
            "client-secret: client-secret-value",
            "access_token=access-secret",
            "https://example.test/callback?code=oauth-code&safe=value&refresh_token=refresh-secret",
            "X-FixThis-Console-Token: console-secret",
            "X-FixThis-Bridge-Token: bridge-header-secret",
            "fixthis_session_token=bridge-secret",
        ).joinToString("\n")

        val actual = RuntimeEvidenceRedactor().redact(input)

        listOf(
            "secret-cookie",
            "secret-session",
            "basic-auth-secret",
            "eyJhbGciOiJIUzI1NiJ9",
            "api-secret",
            "client-secret-value",
            "access-secret",
            "oauth-code",
            "refresh-secret",
            "console-secret",
            "bridge-header-secret",
            "bridge-secret",
        ).forEach { secret -> assertFalse(secret in actual.text, "secret remained: $secret") }
        assertTrue("safe=value" in actual.text)
        assertTrue(actual.redacted)
    }

    @Test
    fun projectPatternsAreAppliedWithoutRedactingUnrelatedText() {
        val redactor = RuntimeEvidenceRedactor(additionalPatterns = listOf("tenant-[0-9]{4}"))

        val actual = redactor.redact("tenant-4821 dev@example.com")

        assertEquals("[REDACTED] dev@example.com", actual.text)
        assertTrue(actual.redacted)
    }

    @Test
    fun unchangedTextReportsNoRedaction() {
        val actual = RuntimeEvidenceRedactor().redact("ordinary log line for dev@example.com")

        assertEquals("ordinary log line for dev@example.com", actual.text)
        assertFalse(actual.redacted)
    }

    @Test
    fun rejectsMoreThanThirtyTwoProjectPatterns() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeEvidenceRedactor(additionalPatterns = List(33) { "safe-$it" })
        }
    }

    @Test
    fun rejectsOversizedInvalidAndUnsafeProjectPatterns() {
        val rejected = listOf(
            "x".repeat(257),
            "[unterminated",
            "(a+)+$",
            "(a*)*",
            "(a{1,3})+",
            "((a+))+",
            "(secret)\\1",
            "(?<=token=).*",
            "(?<!safe)secret",
        )

        rejected.forEach { pattern ->
            assertFailsWith<IllegalArgumentException>("pattern should be rejected: $pattern") {
                RuntimeEvidenceRedactor(additionalPatterns = listOf(pattern))
            }
        }
    }
}
