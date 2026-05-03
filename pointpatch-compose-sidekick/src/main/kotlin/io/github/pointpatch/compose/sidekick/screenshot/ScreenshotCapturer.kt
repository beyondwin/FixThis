package io.github.pointpatch.compose.sidekick.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenshotCapturer(
    private val store: ScreenshotStore,
    private val pixelCopyTimeoutMillis: Long = 500L,
) {
    suspend fun capture(
        activity: Activity,
        annotationId: String,
        selectedBounds: PointPatchRect? = null,
    ): ScreenshotInfo {
        val decorView = activity.window?.decorView
            ?: return ScreenshotInfo(captureFailedReason = "Window decorView is unavailable")
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) {
            return ScreenshotInfo(captureFailedReason = "DecorView has no size")
        }

        val failures = mutableListOf<String>()
        val fullBitmap = tryPixelCopy(activity = activity, width = width, height = height)
            .getOrElse { error ->
                failures += "PixelCopy failed: ${error.message ?: error::class.java.simpleName}"
                null
            }
            ?: tryDrawDecorView(decorView)
                .getOrElse { error ->
                    failures += "Canvas fallback failed: ${error.message ?: error::class.java.simpleName}"
                    null
                }

        if (fullBitmap == null) {
            return ScreenshotInfo(
                captureFailedReason = failures.ifEmpty {
                    listOf("PixelCopy and Canvas fallback failed")
                }.joinToString(separator = "; "),
            )
        }

        return try {
            store.save(
                annotationId = annotationId,
                fullBitmap = fullBitmap,
                selectedBounds = selectedBounds,
            )
        } catch (error: Throwable) {
            ScreenshotInfo(captureFailedReason = "Screenshot storage failed: ${error.message ?: error::class.java.simpleName}")
        } finally {
            fullBitmap.recycle()
        }
    }

    private suspend fun tryPixelCopy(
        activity: Activity,
        width: Int,
        height: Int,
    ): Result<Bitmap> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Result.failure(UnsupportedOperationException("PixelCopy requires API 26"))
        }

        return runCatching {
            withContext(Dispatchers.Main.immediate) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val result = withTimeoutOrNull(pixelCopyTimeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        try {
                            PixelCopy.request(
                                activity.window,
                                bitmap,
                                { copyResult ->
                                    if (continuation.isActive) {
                                        continuation.resume(copyResult)
                                    }
                                },
                                Handler(Looper.getMainLooper()),
                            )
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                } ?: PixelCopy.ERROR_TIMEOUT

                if (result == PixelCopy.SUCCESS) {
                    bitmap
                } else {
                    bitmap.recycle()
                    error("PixelCopy result code $result")
                }
            }
        }
    }

    private suspend fun tryDrawDecorView(decorView: View): Result<Bitmap> =
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    decorView.draw(Canvas(bitmap))
                }
            }
        }
}
