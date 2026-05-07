package io.beyondwin.fixthis.compose.sidekick.screenshot

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import io.beyondwin.fixthis.compose.sidekick.overlay.findFixThisOverlayHosts
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        selectedBounds: FixThisRect? = null,
    ): ScreenshotInfo {
        val decorView = activity.window?.decorView
            ?: return ScreenshotInfo(captureFailedReason = "Window decorView is unavailable")
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) {
            return ScreenshotInfo(captureFailedReason = "DecorView has no size")
        }

        val failures = mutableListOf<String>()
        val fullBitmap = decorView.withHiddenFixThisOverlayHosts(mainDispatcher) { hiddenOverlayHosts ->
            tryPixelCopy(
                activity = activity,
                decorView = decorView,
                width = width,
                height = height,
                waitForCleanFrame = hiddenOverlayHosts,
            )
                .getOrElse { error ->
                    failures += "PixelCopy failed: ${error.message ?: error::class.java.simpleName}"
                    null
                }
                ?: tryDrawDecorView(decorView)
                    .getOrElse { error ->
                        failures += "Canvas fallback failed: ${error.message ?: error::class.java.simpleName}"
                        null
                    }
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
        decorView: View,
        width: Int,
        height: Int,
        waitForCleanFrame: Boolean,
    ): Result<Bitmap> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Result.failure(UnsupportedOperationException("PixelCopy requires API 26"))
        }

        return runCatchingCancellable {
            withContext(mainDispatcher) {
                if (waitForCleanFrame) {
                    val frameRendered = withTimeoutOrNull(pixelCopyTimeoutMillis) {
                        decorView.awaitNextDrawAfterInvalidation()
                        true
                    } ?: false
                    if (!frameRendered) {
                        throw CleanFrameTimedOutException(pixelCopyTimeoutMillis)
                    }
                }

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

private class CleanFrameTimedOutException(timeoutMillis: Long) :
    RuntimeException("Clean frame timed out after ${timeoutMillis}ms")

private suspend fun <T> View.withHiddenFixThisOverlayHosts(
    mainDispatcher: CoroutineDispatcher,
    block: suspend (hiddenOverlayHosts: Boolean) -> T,
): T = withContext(mainDispatcher) {
    val hiddenOverlayHosts = hideFixThisOverlayHosts()
    try {
        block(hiddenOverlayHosts.isNotEmpty())
    } finally {
        withContext(NonCancellable) {
            hiddenOverlayHosts.restore()
        }
    }
}

private suspend fun View.awaitNextDrawAfterInvalidation() {
    suspendCancellableCoroutine { continuation ->
        val initialObserver = viewTreeObserver
        if (!initialObserver.isAlive || !isAttachedToWindow) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }

        lateinit var listener: ViewTreeObserver.OnDrawListener
        fun removeListener() {
            val currentObserver = viewTreeObserver
            val observer = if (currentObserver.isAlive) currentObserver else initialObserver
            if (observer.isAlive) {
                observer.removeOnDrawListener(listener)
            }
        }

        fun postAfterDraw(block: () -> Unit) {
            val posted = post {
                block()
            }
            if (!posted) {
                Handler(Looper.getMainLooper()).post {
                    block()
                }
            }
        }

        var completionPosted = false
        listener = ViewTreeObserver.OnDrawListener {
            if (!completionPosted) {
                completionPosted = true
                postAfterDraw {
                    removeListener()
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
        continuation.invokeOnCancellation {
            postAfterDraw {
                removeListener()
            }
        }

        initialObserver.addOnDrawListener(listener)
        postInvalidateOnAnimation()
    }
}

private fun View.hideFixThisOverlayHosts(): List<HiddenOverlayHost> =
    findFixThisOverlayHosts().map { host ->
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
