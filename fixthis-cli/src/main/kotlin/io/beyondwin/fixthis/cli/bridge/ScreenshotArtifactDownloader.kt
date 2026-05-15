package io.beyondwin.fixthis.cli.bridge

import io.beyondwin.fixthis.cli.BridgeProtocolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

internal class ScreenshotArtifactDownloader(
    private val request: suspend (BridgeRequestScope, String, String, JsonObject) -> JsonObject,
) {
    suspend fun readScreenshotArtifact(
        scope: BridgeRequestScope,
        artifact: ScreenshotArtifactRequest,
    ): String? {
        artifact.androidPath?.takeIf { it.isNotBlank() } ?: return null
        val result = request(
            scope,
            artifact.packageName,
            "readScreenshot",
            buildJsonObject {
                put("kind", artifact.kind)
                if (artifact.source != "annotation") {
                    put("source", artifact.source)
                }
            },
        )
        val mimeType = result["mimeType"]?.jsonPrimitive?.contentOrNull
        require(mimeType == "image/png") {
            "Bridge returned unsupported screenshot MIME type for ${artifact.kind}: $mimeType"
        }
        val base64 = result["base64"]?.jsonPrimitive?.contentOrNull
            ?: throw BridgeProtocolException("Bridge readScreenshot response omitted base64 for ${artifact.kind}")
        artifact.destination.writeBytes(Base64.getDecoder().decode(base64))
        return artifact.destination.absolutePath
    }
}

internal data class ScreenshotArtifactRequest(
    val packageName: String,
    val kind: String,
    val androidPath: String?,
    val destination: File,
    val source: String = "annotation",
)

internal fun String.sanitizedPathSegment(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
