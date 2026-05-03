package io.github.pointpatch.compose.sidekick.inspect

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsNodeMapperTest {
    @Test
    fun sensitiveDataRedactsTextDescriptionsAndRawProperties() {
        val config = SemanticsConfiguration().apply {
            set(SemanticsProperties.Text, listOf(AnnotatedString("4111 1111 1111 1111")))
            set(SemanticsProperties.ContentDescription, listOf("Visa ending 1111"))
            set(SemanticsProperties.StateDescription, "Balance $123.45")
            set(SemanticsProperties.IsSensitiveData, true)
        }

        val mapped = config.toPointPatchTextProperties(redactEditableText = true)

        assertTrue(mapped.isSensitive)
        assertEquals(listOf("<redacted>"), mapped.text)
        assertEquals(listOf("<redacted>"), mapped.contentDescription)
        assertEquals("<redacted>", mapped.stateDescription)
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.Text.name))
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.ContentDescription.name))
        assertEquals("<redacted>", mapped.rawProperties.getValue(SemanticsProperties.StateDescription.name))
    }
}
