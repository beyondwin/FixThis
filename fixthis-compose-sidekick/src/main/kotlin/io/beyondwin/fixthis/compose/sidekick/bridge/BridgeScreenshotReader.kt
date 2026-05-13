package io.beyondwin.fixthis.compose.sidekick.bridge

import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

internal class BridgeScreenshotReader(
    private val environment: BridgeEnvironment,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(params: JsonObject): BridgeScreenshotReadResult {
        require(params.stringParam("path") == null) {
            "Explicit screenshot paths are not supported; use kind=full or kind=crop for the latest screen snapshot"
        }
        val kind = params.stringParam("kind") ?: "full"
        require(kind == "full" || kind == "crop") { "Unsupported screenshot kind: $kind" }
        val source = params.stringParam("source") ?: "screenSnapshot"
        require(source == "screenSnapshot") { "Unsupported screenshot source: $source" }
        val screenshot = environment.getLastScreenSnapshot()?.screenshot
        val path = when (kind) {
            "crop" -> screenshot?.cropPath
            else -> screenshot?.fullPath
        }
        require(!path.isNullOrBlank()) { "No screenshot path is available" }
        return withContext(ioDispatcher) {
            val file = File(path).canonicalFile
            val cacheDirectory = environment.screenshotCacheDirectory().canonicalFile
            require(PathSafety.isUnder(file, cacheDirectory)) {
                "Screenshot path is outside the FixThis screenshot cache"
            }
            require(file.extension.equals("png", ignoreCase = true)) { "Screenshot must be a PNG file" }
            require(file.exists() && file.isFile) { "Screenshot does not exist: $path" }
            require(file.length() <= MAX_SCREENSHOT_READ_BYTES) { "Screenshot is too large to read" }
            require(file.hasPngHeader()) { "Screenshot file is not PNG data" }
            BridgeScreenshotReadResult(
                path = file.absolutePath,
                kind = kind,
                mimeType = "image/png",
                base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
            )
        }
    }

    private companion object {
        const val MAX_SCREENSHOT_READ_BYTES = 16L * 1024L * 1024L
    }
}

private val BRIDGE_PNG_HEADER: ByteArray = byteArrayOf(
    PNG_SIGNATURE_FIRST_BYTE.toByte(),
    'P'.code.toByte(),
    'N'.code.toByte(),
    'G'.code.toByte(),
    PNG_CARRIAGE_RETURN_BYTE.toByte(),
    PNG_LINE_FEED_BYTE.toByte(),
    PNG_END_OF_FILE_BYTE.toByte(),
    PNG_LINE_FEED_BYTE.toByte(),
)

private fun File.hasPngHeader(): Boolean {
    if (length() < BRIDGE_PNG_HEADER.size) return false
    return inputStream().use { input ->
        val header = ByteArray(BRIDGE_PNG_HEADER.size)
        input.read(header) == BRIDGE_PNG_HEADER.size && header.contentEquals(BRIDGE_PNG_HEADER)
    }
}

private const val PNG_SIGNATURE_FIRST_BYTE = -119
private const val PNG_CARRIAGE_RETURN_BYTE = 13
private const val PNG_LINE_FEED_BYTE = 10
private const val PNG_END_OF_FILE_BYTE = 26
