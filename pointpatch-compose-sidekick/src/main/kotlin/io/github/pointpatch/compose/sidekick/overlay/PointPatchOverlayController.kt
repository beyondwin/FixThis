package io.github.pointpatch.compose.sidekick.overlay

import android.app.Activity
import android.os.Build
import android.content.pm.ApplicationInfo
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pointpatch.compose.core.model.ActivityInfo
import io.github.pointpatch.compose.core.model.AppInfo
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScopeCandidate
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import io.github.pointpatch.compose.core.model.TapPoint
import io.github.pointpatch.compose.core.source.SourceIndex
import io.github.pointpatch.compose.overlay.OverlayMode
import io.github.pointpatch.compose.overlay.OverlayStateMachine
import io.github.pointpatch.compose.overlay.PointPatchDraft
import io.github.pointpatch.compose.sidekick.capture.AnnotationCaptureController
import io.github.pointpatch.compose.sidekick.capture.AnnotationCaptureInput
import io.github.pointpatch.compose.sidekick.export.ClipboardExportResult
import io.github.pointpatch.compose.sidekick.export.ClipboardExporter
import io.github.pointpatch.compose.sidekick.export.LocalFileExporter
import io.github.pointpatch.compose.sidekick.inspect.SemanticsInspectionResult
import io.github.pointpatch.compose.sidekick.inspect.SemanticsInspector
import io.github.pointpatch.compose.sidekick.screenshot.ScreenshotCapturer
import io.github.pointpatch.compose.sidekick.screenshot.ScreenshotStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

internal interface SemanticsInspectorPort {
    fun inspect(decorView: View): SemanticsInspectionResult
}

internal interface ScreenshotCapturerPort {
    suspend fun capture(
        activity: Activity,
        annotationId: String,
        selectedBounds: PointPatchRect?,
    ): ScreenshotInfo
}

internal interface ClipboardExporterPort {
    fun copyMarkdown(annotation: PointPatchAnnotation): ClipboardExportResult
    fun copyJson(annotation: PointPatchAnnotation): ClipboardExportResult
}

internal interface LocalFileExporterPort {
    suspend fun exportMarkdown(annotation: PointPatchAnnotation): File
}

internal class PointPatchOverlayController(
    private val activity: Activity,
    private val inspector: SemanticsInspectorPort = AndroidSemanticsInspectorPort(),
    private val annotationController: AnnotationCaptureController = AnnotationCaptureController(),
    private val screenshotCapturer: ScreenshotCapturerPort = AndroidScreenshotCapturerPort(
        ScreenshotCapturer(ScreenshotStore(activity)),
    ),
    private val clipboardExporter: ClipboardExporterPort = AndroidClipboardExporterPort(
        ClipboardExporter(activity),
    ),
    private val localFileExporter: LocalFileExporterPort = AndroidLocalFileExporterPort(
        LocalFileExporter(activity),
    ),
    private val appInfoProvider: () -> AppInfo = { activity.toAppInfo() },
    private val activityInfoProvider: () -> ActivityInfo = { ActivityInfo(activity::class.java.name) },
) {
    private val stateMachine = OverlayStateMachine()

    var mode: OverlayMode by mutableStateOf(stateMachine.state.value)
        private set

    val shouldHandleOverlayTouch: Boolean
        get() = mode is OverlayMode.Select ||
            mode is OverlayMode.ReviewingSelection ||
            mode is OverlayMode.Commenting

    private var activeCapture: ActiveCapture? = null
    private var currentAnnotation: PointPatchAnnotation? = null
    private var lastSubmittedAnnotation: PointPatchAnnotation? = null
    private var completionSequence: Long = 0L
    private var lastCompletionSubmitted: Boolean = false
    private var feedbackCaptureInFlight: Boolean = false

    internal val lastAnnotation: PointPatchAnnotation?
        get() = currentAnnotation ?: lastSubmittedAnnotation

    fun startSelection() {
        transitionMode(OverlayMode.Select(requestId = UUID.randomUUID().toString()))
        activeCapture = null
        currentAnnotation = null
    }

    suspend fun captureTap(xInWindow: Float, yInWindow: Float) {
        captureSelection(
            tap = TapPoint(xInWindow = xInWindow, yInWindow = yInWindow),
            areaBoundsInWindow = null,
            scopeNodeUid = null,
            userComment = "",
        )
    }

    suspend fun captureArea(left: Float, top: Float, right: Float, bottom: Float) {
        val area = PointPatchRect(
            left = min(left, right),
            top = min(top, bottom),
            right = max(left, right),
            bottom = max(top, bottom),
        )
        captureSelection(
            tap = TapPoint(xInWindow = (area.left + area.right) / 2f, yInWindow = (area.top + area.bottom) / 2f),
            areaBoundsInWindow = area,
            scopeNodeUid = null,
            userComment = "",
        )
    }

    suspend fun selectScope(candidate: ScopeCandidate) {
        val capture = activeCapture ?: return
        captureSelection(
            tap = capture.tap,
            areaBoundsInWindow = capture.areaBoundsInWindow,
            scopeNodeUid = candidate.nodeUid,
            userComment = currentAnnotation?.userComment.orEmpty(),
        )
    }

    fun updateComment(comment: String) {
        val updated = currentAnnotation?.copy(userComment = comment) ?: return
        transitionMode(OverlayMode.Commenting(updated.toDraft()))
        currentAnnotation = updated
    }

    fun copyMarkdown(): ClipboardExportResult? {
        val annotation = currentAnnotation ?: return null
        return clipboardExporter.copyMarkdown(annotation).also {
            markSubmitted(annotation)
        }
    }

    fun copyJson(): ClipboardExportResult? {
        val annotation = currentAnnotation ?: return null
        return clipboardExporter.copyJson(annotation).also {
            markSubmitted(annotation)
        }
    }

    suspend fun share(): File? {
        val annotation = currentAnnotation ?: return null
        val file = localFileExporter.exportMarkdown(annotation)
        transitionMode(OverlayMode.Exported(annotation.id))
        markSubmitted(annotation)
        return file
    }

    fun cancel() {
        if (stateMachine.state.value !is OverlayMode.Idle) {
            transitionMode(OverlayMode.Idle)
        }
        activeCapture = null
        currentAnnotation = null
        lastCompletionSubmitted = false
        completionSequence++
    }

    internal suspend fun startFeedbackCapture(
        timeoutMillis: Long,
        pollMillis: Long = 100L,
    ): FeedbackCaptureWaitResult {
        if (feedbackCaptureInFlight) {
            return FeedbackCaptureWaitResult(submitted = false, annotation = null, rejected = true)
        }
        feedbackCaptureInFlight = true
        return try {
            val startSequence = completionSequence
            startSelection()
            val submitted = withTimeoutOrNull(timeoutMillis) {
                while (completionSequence == startSequence) {
                    delay(pollMillis)
                }
                lastCompletionSubmitted && lastSubmittedAnnotation != null
            } ?: false
            FeedbackCaptureWaitResult(
                submitted = submitted,
                annotation = if (submitted) lastSubmittedAnnotation else null,
            )
        } finally {
            feedbackCaptureInFlight = false
        }
    }

    private suspend fun captureSelection(
        tap: TapPoint,
        areaBoundsInWindow: PointPatchRect?,
        scopeNodeUid: String?,
        userComment: String,
    ) {
        val decorView = activity.window?.decorView ?: return
        val inspection = inspector.inspect(decorView)
        val nodes = inspection.mergedNodes
        val baseInput = AnnotationCaptureInput(
            app = appInfoProvider(),
            activity = activityInfoProvider(),
            tap = tap,
            nodes = nodes,
            sourceIndex = SourceIndex(),
            userComment = userComment,
            scopeNodeUid = scopeNodeUid,
            areaBoundsInWindow = areaBoundsInWindow,
            screenshot = null,
            errors = inspection.errors,
        )
        val annotationWithoutScreenshot = annotationController.capture(baseInput)
        val selectedBounds = annotationWithoutScreenshot.selectedNode?.boundsInWindow
            ?: annotationWithoutScreenshot.selection.areaBoundsInWindow
        val screenshot = screenshotCapturer.capture(
            activity = activity,
            annotationId = annotationWithoutScreenshot.id,
            selectedBounds = selectedBounds,
        )
        val annotation = annotationWithoutScreenshot.copy(screenshot = screenshot)
        transitionMode(OverlayMode.Commenting(annotation.toDraft()))
        activeCapture = ActiveCapture(
            tap = tap,
            areaBoundsInWindow = areaBoundsInWindow,
        )
        currentAnnotation = annotation
    }

    private data class ActiveCapture(
        val tap: TapPoint,
        val areaBoundsInWindow: PointPatchRect?,
    )

    private fun markSubmitted(annotation: PointPatchAnnotation) {
        lastSubmittedAnnotation = annotation
        lastCompletionSubmitted = true
        completionSequence++
    }

    private fun transitionMode(next: OverlayMode) {
        stateMachine.transition(next)
        mode = stateMachine.state.value
    }
}

internal data class FeedbackCaptureWaitResult(
    val submitted: Boolean,
    val annotation: PointPatchAnnotation?,
    val rejected: Boolean = false,
)

private class AndroidSemanticsInspectorPort(
    private val inspector: SemanticsInspector = SemanticsInspector(),
) : SemanticsInspectorPort {
    override fun inspect(decorView: View): SemanticsInspectionResult =
        inspector.inspect(decorView)
}

private class AndroidScreenshotCapturerPort(
    private val capturer: ScreenshotCapturer,
) : ScreenshotCapturerPort {
    override suspend fun capture(
        activity: Activity,
        annotationId: String,
        selectedBounds: PointPatchRect?,
    ): ScreenshotInfo =
        capturer.capture(
            activity = activity,
            annotationId = annotationId,
            selectedBounds = selectedBounds,
        )
}

private class AndroidClipboardExporterPort(
    private val exporter: ClipboardExporter,
) : ClipboardExporterPort {
    override fun copyMarkdown(annotation: PointPatchAnnotation): ClipboardExportResult =
        exporter.copyMarkdown(annotation)

    override fun copyJson(annotation: PointPatchAnnotation): ClipboardExportResult =
        exporter.copyJson(annotation)
}

private class AndroidLocalFileExporterPort(
    private val exporter: LocalFileExporter,
) : LocalFileExporterPort {
    override suspend fun exportMarkdown(annotation: PointPatchAnnotation): File =
        exporter.exportMarkdown(annotation)
}

private fun PointPatchAnnotation.toDraft(): PointPatchDraft =
    PointPatchDraft(
        selectedNode = selectedNode,
        selection = selection,
        scopeCandidates = scopeCandidates,
        selectedScopeNodeUid = selection.selectedUid,
        screenshot = screenshot,
        userComment = userComment,
    )

@Suppress("DEPRECATION")
private fun Activity.toAppInfo(): AppInfo {
    val packageInfo = runCatching {
        packageManager.getPackageInfo(packageName, 0)
    }.getOrNull()
    val applicationInfo = applicationInfo
    return AppInfo(
        packageName = packageName,
        versionName = packageInfo?.versionName,
        versionCode = packageInfo?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        },
        debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
}
