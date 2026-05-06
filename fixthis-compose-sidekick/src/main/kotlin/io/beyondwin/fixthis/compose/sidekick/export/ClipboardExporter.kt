package io.beyondwin.fixthis.compose.sidekick.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.beyondwin.fixthis.compose.core.format.FixThisJsonFormatter
import io.beyondwin.fixthis.compose.core.format.FixThisMarkdownFormatter
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation

class ClipboardExporter(private val context: Context) {
    fun copyMarkdown(annotation: FixThisAnnotation): ClipboardExportResult =
        copy(
            label = MARKDOWN_LABEL,
            content = FixThisMarkdownFormatter.format(annotation),
        )

    fun copyJson(annotation: FixThisAnnotation): ClipboardExportResult =
        copy(
            label = JSON_LABEL,
            content = FixThisJsonFormatter.format(annotation),
        )

    private fun copy(label: String, content: String): ClipboardExportResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
        return ClipboardExportResult(
            label = label,
            content = content,
            warning = SCREENSHOT_WARNING,
        )
    }

    companion object {
        const val MARKDOWN_LABEL = "FixThis Markdown"
        const val JSON_LABEL = "FixThis JSON"
        const val SCREENSHOT_WARNING = "Screenshots are saved locally. They may contain sensitive information."
    }
}

data class ClipboardExportResult(
    val label: String,
    val content: String,
    val warning: String,
)
