package io.github.beyondwin.fixthis.compose.sidekick.inspect

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsNodeMapperTest {
    private val inputTextKey = SemanticsPropertyKey<AnnotatedString>("InputText")
    private val sensitiveDataKey = SemanticsPropertyKey<Boolean>("IsSensitiveData")

    @Test
    fun editableTextRedactionPreservesNonSensitiveLabelsAndDescriptions() {
        val config = SemanticsConfiguration().apply {
            set(SemanticsProperties.Text, listOf(AnnotatedString("Search")))
            set(SemanticsProperties.EditableText, AnnotatedString("private draft"))
            set(inputTextKey, AnnotatedString("private raw input"))
            set(SemanticsProperties.ContentDescription, listOf("Search query"))
            set(SemanticsProperties.StateDescription, "Editing")
        }

        val mapped = config.toFixThisTextProperties(redactEditableText = true)

        assertTrue(mapped.isSensitive)
        assertEquals(listOf("Search"), mapped.text)
        assertEquals("<redacted-editable-text>", mapped.editableText)
        assertEquals(listOf("Search query"), mapped.contentDescription)
        assertEquals("Editing", mapped.stateDescription)
        assertEquals(
            "[Search]",
            mapped.rawProperties.getValue(SemanticsProperties.Text.name),
        )
        assertEquals(
            "<redacted-editable-text>",
            mapped.rawProperties.getValue(SemanticsProperties.EditableText.name),
        )
        assertEquals(
            "<redacted-editable-text>",
            mapped.rawProperties.getValue(inputTextKey.name),
        )
        assertEquals(
            "[Search query]",
            mapped.rawProperties.getValue(SemanticsProperties.ContentDescription.name),
        )
        assertEquals(
            "Editing",
            mapped.rawProperties.getValue(SemanticsProperties.StateDescription.name),
        )
    }

    @Test
    fun passwordRedactsTextDescriptionsAndRawProperties() {
        val config = SemanticsConfiguration().apply {
            set(SemanticsProperties.Text, listOf(AnnotatedString("Password")))
            set(SemanticsProperties.EditableText, AnnotatedString("correct horse battery staple"))
            set(inputTextKey, AnnotatedString("raw password"))
            set(SemanticsProperties.ContentDescription, listOf("Account password"))
            set(SemanticsProperties.StateDescription, "Filled")
            set(SemanticsProperties.Password, Unit)
        }

        val mapped = config.toFixThisTextProperties(redactEditableText = true)

        assertTrue(mapped.isPassword)
        assertTrue(mapped.isSensitive)
        assertEquals(listOf("<redacted-password>"), mapped.text)
        assertEquals("<redacted-password>", mapped.editableText)
        assertEquals(listOf("<redacted-password>"), mapped.contentDescription)
        assertEquals("<redacted-password>", mapped.stateDescription)
        assertEquals("<redacted-password>", mapped.rawProperties.getValue(SemanticsProperties.Text.name))
        assertEquals("<redacted-password>", mapped.rawProperties.getValue(SemanticsProperties.EditableText.name))
        assertEquals("<redacted-password>", mapped.rawProperties.getValue(inputTextKey.name))
        assertEquals("<redacted-password>", mapped.rawProperties.getValue(SemanticsProperties.ContentDescription.name))
        assertEquals("<redacted-password>", mapped.rawProperties.getValue(SemanticsProperties.StateDescription.name))
    }

    @Test
    fun sensitiveDataRedactsTextDescriptionsAndRawProperties() {
        val config = SemanticsConfiguration().apply {
            set(SemanticsProperties.Text, listOf(AnnotatedString("4111 1111 1111 1111")))
            set(SemanticsProperties.ContentDescription, listOf("Visa ending 1111"))
            set(SemanticsProperties.StateDescription, "Balance $123.45")
            set(sensitiveDataKey, true)
        }

        val mapped = config.toFixThisTextProperties(redactEditableText = true)

        assertTrue(mapped.isSensitive)
        assertEquals(listOf("<redacted>"), mapped.text)
        assertEquals(listOf("<redacted>"), mapped.contentDescription)
        assertEquals("<redacted>", mapped.stateDescription)
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.Text.name))
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.ContentDescription.name))
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.StateDescription.name))
    }
}
