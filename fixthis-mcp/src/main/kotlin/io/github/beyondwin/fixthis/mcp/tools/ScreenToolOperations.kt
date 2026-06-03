package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbe
import io.github.beyondwin.fixthis.mcp.session.HostSourceFreshnessResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val INSTALL_STALE_HINT = "Run ./gradlew :app:installDebug then cold-launch the app"

/**
 * Device/screen MCP tools: live ADB-bridge inspection and source-index freshness.
 * Depends only on the screen/source ports plus the result cache, freshness probe,
 * and configured default package — not on feedback-session state.
 */
internal class ScreenToolOperations(
    private val ports: FixThisToolBridgePorts,
    private val cache: BridgeResultCache,
    private val freshnessProbe: HostSourceFreshnessProbe,
    private val defaultPackageName: String?,
) {
    internal suspend fun status(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val status = ports.screenBridge.status(packageName)
        cache.cacheStatus(packageName, status)
        val freshness = evaluateFreshness(packageName, status)
        jsonToolResult(statusPayload(packageName, status, freshness))
    }

    internal suspend fun getCurrentScreen(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val screen = ports.screenBridge.inspectCurrentScreen(packageName)
        cache.cacheScreen(packageName, screen)
        jsonToolResult(
            buildJsonObject {
                put("screen", screen)
            },
        )
    }

    internal suspend fun verifyUiChange(arguments: JsonObject): JsonObject = bridgeToolResult {
        val packageName = resolvePackageName(arguments)
        val expectedText = arguments.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: throw FixThisToolException("fixthis_verify_ui_change requires expectedText")
        val role = arguments.stringParam("role")?.takeIf { it.isNotBlank() }
        val bridgeResult = ports.screenBridge.verifyUiChange(packageName, expectedText, role)
        jsonToolResult(normalizeVerifyUiChangeResult(bridgeResult, role))
    }

    private fun statusPayload(
        packageName: String,
        status: JsonObject,
        freshness: HostSourceFreshnessResult,
    ): JsonObject = buildJsonObject {
        put("deviceConnected", true)
        put("packageName", packageName)
        put("appRunning", status["activity"] != null)
        put("sidekickConnected", true)
        put("currentActivity", status["activity"] ?: JsonPrimitive(""))
        put("composeRoots", status["rootsCount"] ?: JsonPrimitive(0))
        put("sourceIndexAvailable", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
        put("installStale", JsonPrimitive(freshness.installStale))
        freshness.reason?.let { put("installStaleReason", JsonPrimitive(it)) }
        if (freshness.installStale) {
            put("installStaleHint", JsonPrimitive(INSTALL_STALE_HINT))
        }
        freshness.installedAtEpochMillis?.let { put("installedAtEpochMillis", JsonPrimitive(it)) }
        put(
            "newerSourceFiles",
            buildJsonArray {
                freshness.sampleNewerFiles.forEach { add(JsonPrimitive(it)) }
            },
        )
        put("bridge", status)
    }

    private fun normalizeVerifyUiChangeResult(bridgeResult: JsonObject, role: String?): JsonObject {
        val bridgeMatchingNodes = bridgeResult["matchingNodes"] as? JsonArray
        val matchedText = bridgeResult.stringParam("matchedText")
        val found = bridgeResult.booleanParam("found")
            ?: bridgeResult.booleanParam("verified")
            ?: bridgeMatchingNodes?.isNotEmpty()
            ?: (matchedText != null)
        val matchingNodes = bridgeMatchingNodes ?: buildJsonArray {
            if (found && matchedText != null) {
                add(
                    buildJsonObject {
                        put("text", matchedText)
                        role?.let { put("role", it) }
                    },
                )
            }
        }

        return buildJsonObject {
            put("found", found)
            put("matchingNodes", matchingNodes)
            if (bridgeResult.requiresNestedBridgeDetails(bridgeMatchingNodes)) {
                put("bridge", bridgeResult)
            }
        }
    }

    private fun JsonObject.requiresNestedBridgeDetails(normalizedMatchingNodes: JsonArray?): Boolean {
        val normalizedKeys = setOf("found", "matchingNodes")
        return keys.any { it !in normalizedKeys } ||
            booleanParam("found") == null ||
            normalizedMatchingNodes == null
    }

    private fun resolvePackageName(arguments: JsonObject): String {
        val packageOverride = arguments.stringParam("packageName")?.takeIf { it.isNotBlank() }
        val packageName = ports.packageResolver.resolvePackageName(packageOverride ?: defaultPackageName)
        if (packageOverride == null) cache.rememberDefaultPackage(packageName)
        return packageName
    }

    private suspend fun evaluateFreshness(
        packageName: String,
        status: JsonObject,
    ): HostSourceFreshnessResult {
        val sourceIndexAvailable = status["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull == true
        val installEpoch = status["installEpochMillis"]?.jsonPrimitive?.longOrNull
        return if (!sourceIndexAvailable) {
            unavailableFreshness(installEpoch, "source index not available")
        } else {
            val raw = runCatching { ports.sourceIndexBridge.readSourceIndex(packageName) }.getOrNull()
            val indexElement = raw?.get("sourceIndex")
            val sourceIndex = indexElement?.let {
                runCatching { McpProtocol.json.decodeFromJsonElement<SourceIndex>(it) }.getOrNull()
            }
            if (sourceIndex == null) {
                unavailableFreshness(installEpoch, "source index could not be read")
            } else {
                freshnessProbe.evaluate(sourceIndex, installEpoch)
            }
        }
    }

    private fun unavailableFreshness(
        installEpoch: Long?,
        reason: String,
    ): HostSourceFreshnessResult = HostSourceFreshnessResult(
        installStale = false,
        newerFileCount = 0,
        totalIndexedFiles = 0,
        installedAtEpochMillis = installEpoch,
        sampleNewerFiles = emptyList(),
        reason = reason,
    )
}
