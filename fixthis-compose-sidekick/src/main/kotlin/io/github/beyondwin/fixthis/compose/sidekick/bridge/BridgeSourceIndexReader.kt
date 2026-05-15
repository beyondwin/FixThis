package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.content.Context
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import java.io.ByteArrayOutputStream

private fun Context.hasAsset(path: String): Boolean = runCatching {
    assets.open(path).use { true }
}.getOrDefault(false)

internal fun Context.readSourceIndexResult(path: String): BridgeSourceIndexResult = if (!hasAsset(path)) {
    BridgeSourceIndexResult(sourceIndexAvailable = false)
} else {
    readSourceIndexAsset(path).fold(
        onSuccess = ::decodeSourceIndex,
        onFailure = ::sourceIndexUnavailable,
    )
}

private fun decodeSourceIndex(json: String): BridgeSourceIndexResult = runCatching {
    BridgeSourceIndexResult(
        sourceIndexAvailable = true,
        sourceIndex = BridgeProtocol.json.decodeFromString(SourceIndex.serializer(), json),
    )
}.getOrElse { error ->
    sourceIndexUnavailable(error)
}

private fun sourceIndexUnavailable(error: Throwable): BridgeSourceIndexResult = BridgeSourceIndexResult(
    sourceIndexAvailable = false,
    sourceIndexError = error.message ?: error::class.java.simpleName,
)

private fun Context.readSourceIndexAsset(path: String): Result<String> = runCatching {
    assets.open(path).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SOURCE_INDEX_ASSET_BYTES) throw SourceIndexAssetTooLargeException()
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

private class SourceIndexAssetTooLargeException :
    IllegalStateException(
        "Source index asset exceeds $MAX_SOURCE_INDEX_ASSET_BYTES bytes",
    )

private const val MAX_SOURCE_INDEX_ASSET_BYTES = 4 * 1024 * 1024
