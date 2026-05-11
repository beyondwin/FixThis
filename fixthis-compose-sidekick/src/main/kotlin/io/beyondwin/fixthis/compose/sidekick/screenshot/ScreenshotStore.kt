package io.beyondwin.fixthis.compose.sidekick.screenshot

import android.content.Context
import android.graphics.Bitmap
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class ScreenshotStore(
    private val context: Context,
    private val dateProvider: () -> String = { currentDateString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun save(
        annotationId: String,
        fullBitmap: Bitmap,
        selectedBounds: FixThisRect?,
    ): ScreenshotInfo = withContext(ioDispatcher) {
        val fileStem = annotationId.safeFileStem()
        val directory = screenshotDirectory()
        val fullFile = File(directory, "$fileStem-full.png")
        writePng(fullBitmap, fullFile)

        val cropFile = selectedBounds
            ?.toCropRect(width = fullBitmap.width, height = fullBitmap.height)
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { cropRect ->
                val cropBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width,
                    cropRect.height,
                )
                File(directory, "$fileStem-crop.png").also { file ->
                    try {
                        writePng(cropBitmap, file)
                    } finally {
                        cropBitmap.recycle()
                    }
                }
            }

        ScreenshotInfo(
            fullPath = fullFile.absolutePath,
            cropPath = cropFile?.absolutePath,
            width = fullBitmap.width,
            height = fullBitmap.height,
        )
    }

    fun screenshotDirectory(): File = File(context.cacheDir, "fixthis/${dateProvider()}").also { directory ->
        check(directory.exists() || directory.mkdirs()) {
            "Could not create screenshot directory: ${directory.absolutePath}"
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode PNG: ${file.absolutePath}"
            }
        }
    }
}

private data class IntCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

private fun FixThisRect.toCropRect(width: Int, height: Int): IntCropRect {
    val left = floor(left).toInt().coerceIn(0, width)
    val top = floor(top).toInt().coerceIn(0, height)
    val right = ceil(right).toInt().coerceIn(0, width)
    val bottom = ceil(bottom).toInt().coerceIn(0, height)
    return IntCropRect(left = left, top = top, right = right, bottom = bottom)
}

private fun String.safeFileStem(): String = replace(Regex("""[^\w.-]"""), "_").ifBlank { "annotation" }

private fun currentDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
