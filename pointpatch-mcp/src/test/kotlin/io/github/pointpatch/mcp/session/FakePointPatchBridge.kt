package io.github.pointpatch.mcp.session

import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FakePointPatchBridge(
    private val packageName: String = "io.github.pointpatch.sample",
    private val captureError: Throwable? = null,
) : PointPatchBridge {
    val resolvedOverrides = mutableListOf<String?>()
    val navigationRequests = mutableListOf<FeedbackNavigationRequest>()
    var lastCaptureSessionId: String? = null
        private set
    var lastCaptureScreenId: String? = null
        private set
    var lastCaptureDestination: String? = null
        private set

    override fun resolvePackageName(packageOverride: String?): String {
        resolvedOverrides += packageOverride
        return packageOverride ?: packageName
    }

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
        JsonObject(emptyMap())

    override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
        buildJsonObject {
            navigationRequests += request
            put("performed", true)
            put("activity", "MainActivity")
        }

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        captureError?.let { throw it }
        lastCaptureSessionId = sessionId
        lastCaptureScreenId = screenId
        lastCaptureDestination = destinationDirectory?.absolutePath
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put("inspection", buildJsonObject {
            put("activity", "MainActivity")
            put("roots", JsonArray(emptyList()))
            put("errors", JsonArray(emptyList()))
        })
        put("screenshot", buildJsonObject {
            val fallbackPath = "/repo/.pointpatch/artifacts/screen-1/full.png"
            val capturedPath = destinationDirectory
                ?.resolve("${screenId ?: "screen-1"}-full.png")
                ?.absolutePath
            put("desktopFullPath", capturedPath ?: fallbackPath)
        })
    }
}
