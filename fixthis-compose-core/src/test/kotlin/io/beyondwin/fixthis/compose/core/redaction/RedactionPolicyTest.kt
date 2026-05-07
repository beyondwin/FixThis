package io.beyondwin.fixthis.compose.core.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionPolicyTest {
    @Test
    fun redactsPasswordTextAndEditableText() {
        val redacted = RedactionPolicy.apply(
            isPassword = true,
            editableText = "secret",
            text = emptyList()
        )

        assertEquals(listOf("<redacted-password>"), redacted.text)
        assertEquals("<redacted-password>", redacted.editableText)
        assertTrue(redacted.redacted)
    }

    @Test
    fun redactsPasswordTextWhenTextContainsSecret() {
        val redacted = RedactionPolicy.apply(
            isPassword = true,
            editableText = null,
            text = listOf("secret")
        )

        assertEquals(listOf("<redacted-password>"), redacted.text)
        assertEquals("<redacted-password>", redacted.editableText)
        assertTrue(redacted.redacted)
    }

    @Test
    fun redactsEditableTextByDefault() {
        val redacted = RedactionPolicy.apply(
            isPassword = false,
            editableText = "private draft",
            text = listOf("Label")
        )

        assertEquals(listOf("Label"), redacted.text)
        assertEquals("<redacted-editable-text>", redacted.editableText)
        assertTrue(redacted.redacted)
    }

    @Test
    fun preservesEditableTextWhenRedactionDisabled() {
        val redacted = RedactionPolicy.apply(
            isPassword = false,
            editableText = "visible",
            text = listOf("Label"),
            redactEditableText = false
        )

        assertEquals(listOf("Label"), redacted.text)
        assertEquals("visible", redacted.editableText)
        assertFalse(redacted.redacted)
    }
}
