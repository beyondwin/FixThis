package io.github.pointpatch.compose.core.redaction

data class RedactedText(
    val text: List<String>,
    val editableText: String?,
    val redacted: Boolean
)

object RedactionPolicy {
    fun apply(
        isPassword: Boolean,
        editableText: String?,
        text: List<String>,
        redactEditableText: Boolean = true
    ): RedactedText {
        if (isPassword) {
            return RedactedText(listOf("<redacted-password>"), "<redacted-password>", true)
        }
        if (editableText != null && redactEditableText) {
            return RedactedText(text, "<redacted-editable-text>", true)
        }
        return RedactedText(text, editableText, false)
    }
}
