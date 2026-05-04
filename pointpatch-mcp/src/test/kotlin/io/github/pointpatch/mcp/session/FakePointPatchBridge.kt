package io.github.pointpatch.mcp.session

import io.github.pointpatch.mcp.tools.PointPatchBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FakePointPatchBridge : PointPatchBridge {
    val resolvedOverrides = mutableListOf<String?>()

    override fun resolvePackageName(packageOverride: String?): String {
        resolvedOverrides += packageOverride
        return packageOverride ?: "io.github.pointpatch.sample"
    }

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
        JsonObject(emptyMap())

    override suspend fun captureScreenSnapshot(packageName: String): JsonObject = buildJsonObject {
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put("inspection", buildJsonObject {
            put("activity", "MainActivity")
            put("roots", JsonArray(emptyList()))
            put("errors", JsonArray(emptyList()))
        })
        put("screenshot", buildJsonObject {
            put("desktopFullPath", "/repo/.pointpatch/artifacts/screen-1/full.png")
        })
    }
}
