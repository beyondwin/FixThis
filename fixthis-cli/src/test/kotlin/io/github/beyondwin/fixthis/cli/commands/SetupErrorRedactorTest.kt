package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupErrorRedactorTest {

    @Test
    fun masksJsonApiKeyValue() {
        val input = """{"api_key":"sk-live-abcdefghij","other":"ok"}"""
        val out = SetupErrorRedactor.redact(input)
        assertFalse("must not leak literal", out.contains("sk-live-abcdefghij"))
        assertTrue(out.contains("***REDACTED***"))
        assertTrue("non-secret key preserved", out.contains("\"other\":\"ok\""))
    }

    @Test
    fun masksTokenAndSecretAndPassword() {
        val out = SetupErrorRedactor.redact(
            """{"token":"abc","secret":"xyz","password":"hunter2"}""",
        )
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun masksHomePathOnMacOS() {
        val out = SetupErrorRedactor.redact("/Users/wooseung/proj/.claude/settings.json")
        assertTrue(out.contains("/Users/<redacted>/proj/.claude/settings.json"))
    }

    @Test
    fun masksHomePathOnLinux() {
        val out = SetupErrorRedactor.redact("/home/ci/.claude/settings.json")
        assertTrue(out.contains("/home/<redacted>/.claude/settings.json"))
    }

    @Test
    fun masksBearerHeader() {
        val out = SetupErrorRedactor.redact("Authorization: Bearer eyJhbGciOi.payload.sig")
        assertFalse(out.contains("eyJhbGciOi.payload.sig"))
        assertTrue(out.contains("Bearer ***REDACTED***"))
    }

    @Test
    fun masksLongValueAfterKeyAsFallback() {
        val out = SetupErrorRedactor.redact("api_key=sk-live-abcdefghijklmnopqrstuvwxyz")
        assertFalse("must not leak long value", out.contains("sk-live-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(out.contains("***REDACTED***"))
    }

    @Test
    fun leavesNonSensitiveTextAlone() {
        val out = SetupErrorRedactor.redact("Unexpected JSON token at offset 14")
        assertTrue(out == "Unexpected JSON token at offset 14")
    }
}
