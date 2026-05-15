package io.github.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

interface BridgeEnvironment {
    suspend fun status(): BridgeStatus
    suspend fun inspectCurrentScreen(): BridgeScreenInspection
    suspend fun captureScreenSnapshot(currentFocusOutput: String? = null): BridgeScreenSnapshot
    suspend fun readSourceIndex(): BridgeSourceIndexResult
    suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot?
    suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult
    fun screenshotCacheDirectory(): File
}

internal fun JsonObject.stringParam(name: String): String? = get(name)?.jsonPrimitive?.content
