package io.beyondwin.fixthis.compose.sidekick.export

import android.content.Context
import io.beyondwin.fixthis.compose.core.format.FixThisJsonFormatter
import io.beyondwin.fixthis.compose.core.format.FixThisMarkdownFormatter
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFileExporter(
    private val context: Context,
    private val dateProvider: () -> String = { currentDateString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun exportMarkdown(annotation: FixThisAnnotation): File =
        withContext(ioDispatcher) {
            export(
                annotation = annotation,
                extension = "md",
                content = FixThisMarkdownFormatter.format(annotation),
            )
        }

    suspend fun exportJson(annotation: FixThisAnnotation): File =
        withContext(ioDispatcher) {
            export(
                annotation = annotation,
                extension = "json",
                content = FixThisJsonFormatter.format(annotation),
            )
        }

    private fun export(
        annotation: FixThisAnnotation,
        extension: String,
        content: String,
    ): File {
        val directory = File(context.cacheDir, "fixthis/${dateProvider()}").also { directory ->
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
