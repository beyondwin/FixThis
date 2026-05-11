package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PreviewCaptureService(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore,
    private val previewCache: PreviewSnapshotCache,
    private val targetEvidenceService: TargetEvidenceService,
) {
    suspend fun capturePreview(session: SessionDto): FeedbackPreviewSnapshot {
        val previewId = store.nextId()
        val screenId = store.nextId()
        val artifactDirectory = File(
            session.projectRoot,
            ".fixthis/preview-cache/${session.sessionId}/$previewId",
        )
        val payload = bridge.captureScreenSnapshot(
            packageName = session.packageName,
            sessionId = session.sessionId,
            screenId = screenId,
            destinationDirectory = artifactDirectory,
        )
        val preview = FeedbackPreviewSnapshot(
            previewId = previewId,
            screen = payload.toCapturedScreen(screenId = screenId, fallbackDisplayName = "Draft screen"),
        )
        val sourceIndex = targetEvidenceService.readSourceIndexOrNull(session.packageName, preview.screen)
        previewCache.put(
            PreviewRecord(
                sessionId = session.sessionId,
                projectRoot = session.projectRoot,
                snapshot = preview,
                sourceIndex = sourceIndex,
            ),
        )
        return preview
    }

    fun previewScreenshotFile(sessionId: String, previewId: String): File {
        previewCache.get(sessionId, previewId)?.let { record ->
            return previewScreenshotFile(record)
        }
        return previewScreenshotArtifactFile(sessionId, previewId)
            ?: throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
    }

    private fun previewScreenshotFile(record: PreviewRecord): File {
        val previewId = record.snapshot.previewId
        val screenshotPath = record.snapshot.screen.screenshot?.desktopFullPath
            ?: throw FeedbackSessionException("PREVIEW_SCREENSHOT_NOT_FOUND: Screenshot not found for preview: $previewId")
        val screenshotFile = File(screenshotPath).canonicalFile
        val previewDirectory = File(
            File(record.projectRoot).canonicalFile,
            ".fixthis/preview-cache/${record.sessionId}/$previewId",
        ).canonicalFile
        if (
            !screenshotFile.isFile ||
            screenshotFile.extension.lowercase() != "png" ||
            !screenshotFile.toPath().startsWith(previewDirectory.toPath())
        ) {
            throw FeedbackSessionException("PREVIEW_SCREENSHOT_NOT_FOUND: Screenshot not found for preview: $previewId")
        }
        return screenshotFile
    }

    private fun previewScreenshotArtifactFile(sessionId: String, previewId: String): File? {
        val session = store.getSession(sessionId)
        val previewRoot = File(session.projectRoot, ".fixthis/preview-cache/$sessionId").canonicalFile
        val previewDirectory = File(previewRoot, previewId).canonicalFile
        if (!previewDirectory.toPath().startsWith(previewRoot.toPath()) || !previewDirectory.isDirectory) {
            return null
        }
        return previewDirectory
            .walkTopDown()
            .filter { file -> file.isFile && file.extension.lowercase() == "png" && file.name.endsWith("-full.png") }
            .map { file -> file.canonicalFile }
            .firstOrNull { file -> file.toPath().startsWith(previewDirectory.toPath()) }
    }
}

internal fun JsonObject.toCapturedScreen(screenId: String, fallbackDisplayName: String): SnapshotDto {
    val inspection = this["inspection"]?.jsonObject
    val activityName = this["activity"]?.jsonPrimitive?.contentOrNull
        ?: inspection?.get("activity")?.jsonPrimitive?.contentOrNull
    return SnapshotDto(
        screenId = screenId,
        capturedAtEpochMillis = 0L,
        activityName = activityName,
        displayName = activityName?.substringAfterLast('.') ?: fallbackDisplayName,
        screenshot = this["screenshot"]?.jsonObject?.let {
            McpProtocol.json.decodeFromJsonElement<SnapshotScreenshotDto>(it)
        },
        roots = (inspection?.get("roots") ?: this["roots"])?.jsonArray?.map { element ->
            McpProtocol.json.decodeFromJsonElement<SnapshotRootDto>(element)
        }.orEmpty(),
        sourceIndexAvailable = this["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull
            ?: inspection?.get("sourceIndexAvailable")?.jsonPrimitive?.booleanOrNull
            ?: false,
        errors = (inspection?.get("errors") ?: this["errors"])?.jsonArray?.map { element ->
            McpProtocol.json.decodeFromJsonElement<FixThisError>(element)
        }.orEmpty(),
    )
}
