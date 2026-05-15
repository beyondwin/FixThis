package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.resourceText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class FixThisResourceDispatcher(
    private val packageResolver: PackageResolver,
    private val screenBridge: ScreenBridge,
    private val defaultPackageName: String?,
    private val cache: BridgeResultCache,
) {
    suspend fun read(uri: String): JsonObject = when (uri) {
        "fixthis://session/current" -> bridgeResource(uri) {
            val packageName = resolveDefaultPackageName()
            val status = cache.latestStatus(packageName) ?: screenBridge.status(packageName)
                .also { cache.cacheStatus(packageName, it) }
            buildJsonObject {
                put("packageName", packageName)
                put("status", status)
            }
        }
        "fixthis://screen/current" -> bridgeResource(uri) {
            val packageName = resolveDefaultPackageName()
            cache.latestScreen(packageName) ?: screenBridge.inspectCurrentScreen(packageName)
                .also { cache.cacheScreen(packageName, it) }
        }
        "fixthis://screenshot/latest/full.png" -> screenshotResource(uri, "desktopFullPath", "fullPath")
        "fixthis://screenshot/latest/crop.png" -> screenshotResource(uri, "desktopCropPath", "cropPath")
        "fixthis://source-index" -> bridgeResource(uri) {
            val packageName = resolveDefaultPackageName()
            val status = cache.latestStatus(packageName) ?: screenBridge.status(packageName)
                .also { cache.cacheStatus(packageName, it) }
            buildJsonObject {
                put("available", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
                put("source", "bridge-status")
            }
        }
        else -> throw FixThisToolException("Unknown FixThis resource: $uri")
    }

    private suspend fun bridgeResource(
        uri: String,
        block: suspend () -> JsonObject,
    ): JsonObject = resourceText(
        uri,
        fixThisJson.encodeToString(JsonObject.serializer(), block()),
    )

    private fun screenshotResource(uri: String, vararg pathKeys: String): JsonObject {
        val packageName = resolveDefaultPackageName()
        val path = cache.latestScreen(packageName).screenshotArtifactPath(*pathKeys)
        return resourceText(
            uri,
            fixThisJson.encodeToString(
                JsonObject.serializer(),
                if (path == null) {
                    unavailable("No screenshot artifact is available for $uri")
                } else {
                    buildJsonObject {
                        put("path", path)
                        put("note", "FixThis exposes screenshot artifacts as desktop-readable paths.")
                    }
                },
            ),
        )
    }

    private fun resolveDefaultPackageName(): String = packageResolver.resolvePackageName(defaultPackageName)
        .also { cache.rememberDefaultPackage(it) }

    private fun JsonObject?.screenshotArtifactPath(vararg pathKeys: String): String? {
        val screenshot = this?.get("screenshot") as? JsonObject
        return pathKeys.firstNotNullOfOrNull { key -> (screenshot?.get(key) as? JsonPrimitive)?.contentOrNull }
    }

    private fun unavailable(message: String): JsonObject = buildJsonObject {
        put("available", false)
        put("message", message)
    }
}
