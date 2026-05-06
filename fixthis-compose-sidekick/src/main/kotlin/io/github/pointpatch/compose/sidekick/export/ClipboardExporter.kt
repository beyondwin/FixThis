package io.github.pointpatch.compose.sidekick.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.pointpatch.compose.core.format.PointPatchJsonFormatter
import io.github.pointpatch.compose.core.format.PointPatchMarkdownFormatter
import io.github.pointpatch.compose.core.model.PointPatchAnnotation

class ClipboardExporter(private val context: Context) {
    fun copyMarkdown(annotation: PointPatchAnnotation): ClipboardExportResult =
        copy(
            label = MARKDOWN_LABEL,
            content = PointPatchMarkdownFormatter.format(annotation),
        )

    fun copyJson(annotation: PointPatchAnnotation): ClipboardExportResult =
        copy(
            label = JSON_LABEL,
            content = PointPatchJsonFormatter.format(annotation),
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
        const val MARKDOWN_LABEL = "PointPatch Markdown"
        const val JSON_LABEL = "PointPatch JSON"
        const val SCREENSHOT_WARNING = "Screenshots are saved locally. They may contain sensitive information."
    }
}

data class ClipboardExportResult(
    val label: String,
    val content: String,
    val warning: String,
)
