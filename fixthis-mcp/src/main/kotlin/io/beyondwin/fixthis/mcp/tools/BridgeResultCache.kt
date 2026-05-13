package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal class BridgeResultCache(
    private val maxRecentOverridePackages: Int = 8,
    defaultPackageName: String?,
) {
    private val lock = Any()
    private val latestScreens = mutableMapOf<String, JsonObject>()
    private val latestStatuses = mutableMapOf<String, JsonObject>()
    private val cachedPackageOrder = linkedSetOf<String>()
    private var defaultCachePackage: String? = defaultPackageName?.takeIf { it.isNotBlank() }

    fun cacheScreen(packageName: String, screen: JsonObject) = synchronized(lock) {
        latestScreens[packageName] = screen
        rememberCachedPackage(packageName)
    }

    fun cacheSnapshot(packageName: String, screen: SnapshotDto) {
        cacheScreen(packageName, McpProtocol.json.encodeToJsonElement(SnapshotDto.serializer(), screen).jsonObject)
    }

    fun cacheStatus(packageName: String, status: JsonObject) = synchronized(lock) {
        latestStatuses[packageName] = status
        rememberCachedPackage(packageName)
    }

    fun latestScreen(packageName: String): JsonObject? = synchronized(lock) { latestScreens[packageName] }

    fun latestStatus(packageName: String): JsonObject? = synchronized(lock) { latestStatuses[packageName] }

    fun rememberDefaultPackage(packageName: String) = synchronized(lock) {
        defaultCachePackage = packageName
        rememberCachedPackage(packageName)
    }

    private fun rememberCachedPackage(packageName: String) {
        cachedPackageOrder.remove(packageName)
        cachedPackageOrder.add(packageName)
        while (cachedPackageOrder.count { it != defaultCachePackage } > maxRecentOverridePackages) {
            val evictedPackage = cachedPackageOrder.firstOrNull { it != defaultCachePackage } ?: return
            cachedPackageOrder.remove(evictedPackage)
            latestScreens.remove(evictedPackage)
            latestStatuses.remove(evictedPackage)
        }
    }
}
