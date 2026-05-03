package io.github.pointpatch.compose.sidekick.export

import android.content.Context
import io.github.pointpatch.compose.core.format.PointPatchJsonFormatter
import io.github.pointpatch.compose.core.format.PointPatchMarkdownFormatter
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFileExporter(
    private val context: Context,
    private val dateProvider: () -> String = { currentDateString() },
) {
    fun exportMarkdown(annotation: PointPatchAnnotation): File =
        export(
            annotation = annotation,
            extension = "md",
            content = PointPatchMarkdownFormatter.format(annotation),
        )

    fun exportJson(annotation: PointPatchAnnotation): File =
        export(
            annotation = annotation,
            extension = "json",
            content = PointPatchJsonFormatter.format(annotation),
        )

    private fun export(
        annotation: PointPatchAnnotation,
        extension: String,
        content: String,
    ): File {
        val directory = File(context.cacheDir, "pointpatch/${dateProvider()}").also { directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Could not create export directory: ${directory.absolutePath}"
            }
        }
        return File(directory, "${annotation.id.safeFileStem()}.$extension").also { file ->
            file.writeText(content)
        }
    }
}

private fun String.safeFileStem(): String =
    replace(Regex("""[^\w.-]"""), "_").ifBlank { "annotation" }

private fun currentDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
