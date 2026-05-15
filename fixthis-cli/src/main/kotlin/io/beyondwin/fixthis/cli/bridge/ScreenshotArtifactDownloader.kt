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
        packageName: String,
        kind: String,
        androidPath: String?,
        destination: File,
        source: String = "annotation",
    ): String? {
        androidPath?.takeIf { it.isNotBlank() } ?: return null
        val result = request(
            scope,
            packageName,
            "readScreenshot",
            buildJsonObject {
                put("kind", kind)
                if (source != "annotation") {
                    put("source", source)
                }
            },
        )
        val mimeType = result["mimeType"]?.jsonPrimitive?.contentOrNull
        require(mimeType == "image/png") { "Bridge returned unsupported screenshot MIME type for $kind: $mimeType" }
        val base64 = result["base64"]?.jsonPrimitive?.contentOrNull
            ?: throw BridgeProtocolException("Bridge readScreenshot response omitted base64 for $kind")
        destination.writeBytes(Base64.getDecoder().decode(base64))
        return destination.absolutePath
    }
}

internal fun String.sanitizedPathSegment(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
