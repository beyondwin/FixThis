package io.github.pointpatch.compose.sidekick.screenshot

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import io.github.pointpatch.compose.sidekick.overlay.findPointPatchOverlayHosts
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface PixelCopyRequester {
    fun request(
        activity: Activity,
        destination: Bitmap,
        onFinished: (Int) -> Unit,
        handler: Handler,
    )
}

class ScreenshotCapturer(
    private val store: ScreenshotStore,
    private val pixelCopyTimeoutMillis: Long = 500L,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val pixelCopyRequester: PixelCopyRequester = DefaultPixelCopyRequester,
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

        val hiddenOverlayHosts = decorView.hidePointPatchOverlayHosts()
        val failures = mutableListOf<String>()
        val fullBitmap = try {
            tryPixelCopy(activity = activity, width = width, height = height)
                .getOrElse { error ->
                    failures += "PixelCopy failed: ${error.message ?: error::class.java.simpleName}"
                    null
                }
                ?: tryDrawDecorView(decorView)
                    .getOrElse { error ->
                        failures += "Canvas fallback failed: ${error.message ?: error::class.java.simpleName}"
                        null
                    }
        } finally {
            hiddenOverlayHosts.restore()
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
        } catch (error: CancellationException) {
            throw error
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

        return runCatchingCancellable {
            withContext(mainDispatcher) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val result = withTimeoutOrNull(pixelCopyTimeoutMillis) {
                        suspendCancellableCoroutine { continuation ->
                            try {
                                pixelCopyRequester.request(
                                    activity,
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
                    }

                    if (result == null) {
                        throw PixelCopyTimedOutException(pixelCopyTimeoutMillis)
                    }

                    if (result == PixelCopy.SUCCESS) {
                        bitmap
                    } else {
                        bitmap.recycle()
                        error("PixelCopy result code $result")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PixelCopyTimedOutException) {
                    throw error
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }

    private suspend fun tryDrawDecorView(decorView: View): Result<Bitmap> =
        runCatchingCancellable {
            withContext(mainDispatcher) {
                Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    decorView.draw(Canvas(bitmap))
                }
            }
        }
}

private val DefaultPixelCopyRequester = PixelCopyRequester { activity, destination, onFinished, handler ->
    requestPixelCopy(activity, destination, onFinished, handler)
}

@TargetApi(Build.VERSION_CODES.O)
private fun requestPixelCopy(
    activity: Activity,
    destination: Bitmap,
    onFinished: (Int) -> Unit,
    handler: Handler,
) {
    PixelCopy.request(activity.window, destination, { result -> onFinished(result) }, handler)
}

private data class HiddenOverlayHost(val view: View, val visibility: Int)

private class PixelCopyTimedOutException(timeoutMillis: Long) :
    RuntimeException("PixelCopy timed out after ${timeoutMillis}ms")

private fun View.hidePointPatchOverlayHosts(): List<HiddenOverlayHost> =
    findPointPatchOverlayHosts().map { host ->
        HiddenOverlayHost(view = host, visibility = host.visibility).also {
            host.visibility = View.INVISIBLE
        }
    }

private fun List<HiddenOverlayHost>.restore() {
    forEach { hidden ->
        hidden.view.visibility = hidden.visibility
    }
}

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
