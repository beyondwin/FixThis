@file:Suppress("MaxLineLength")

package io.beyondwin.fixthis.compose.sidekick.bridge.handlers

import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeConnectionState
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeEnvironment
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeHeartbeatResult
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeMethodHandler
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeNavigationRequest
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeProtocol
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeScreenshotReader
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeUiVerificationResult
import io.beyondwin.fixthis.compose.sidekick.bridge.stringParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

private inline fun <reified T> T.toBridgeJsonElement(): JsonElement = BridgeProtocol.json.encodeToJsonElement(this)

internal fun defaultBridgeMethodHandlers(
    environment: BridgeEnvironment,
    connectionState: BridgeConnectionState,
    screenshotReader: BridgeScreenshotReader,
    socketNameProvider: () -> String,
): List<BridgeMethodHandler> = listOf(
    HeartbeatBridgeHandler(connectionState),
    StatusBridgeHandler(environment, socketNameProvider),
    InspectCurrentScreenBridgeHandler(environment),
    CaptureScreenSnapshotBridgeHandler(environment),
    ReadSourceIndexBridgeHandler(environment),
    VerifyUiChangeBridgeHandler(environment),
    ReadScreenshotBridgeHandler(screenshotReader),
    PerformNavigationBridgeHandler(environment),
)

private class HeartbeatBridgeHandler(
    private val connectionState: BridgeConnectionState,
) : BridgeMethodHandler {
    override val method: String = "heartbeat"

    override suspend fun handle(params: JsonObject): JsonElement {
        connectionState.markAuthorizedRequest()
        return BridgeProtocol.json.encodeToJsonElement(BridgeHeartbeatResult())
    }
}

private class StatusBridgeHandler(
    private val environment: BridgeEnvironment,
    private val socketNameProvider: () -> String,
) : BridgeMethodHandler {
    override val method: String = "status"

    override suspend fun handle(params: JsonObject): JsonElement = environment.status()
        .copy(socketName = socketNameProvider())
        .toBridgeJsonElement()
}

private class InspectCurrentScreenBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "inspectCurrentScreen"

    override suspend fun handle(params: JsonObject): JsonElement = environment.inspectCurrentScreen().toBridgeJsonElement()
}

private class CaptureScreenSnapshotBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "captureScreenSnapshot"

    override suspend fun handle(params: JsonObject): JsonElement = environment.captureScreenSnapshot(
        currentFocusOutput = params.stringParam("currentFocusOutput"),
    ).toBridgeJsonElement()
}

private class ReadSourceIndexBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "readSourceIndex"

    override suspend fun handle(params: JsonObject): JsonElement = environment.readSourceIndex().toBridgeJsonElement()
}

private class VerifyUiChangeBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "verifyUiChange"

    override suspend fun handle(params: JsonObject): JsonElement = verifyUiChange(params).toBridgeJsonElement()

    private suspend fun verifyUiChange(params: JsonObject): BridgeUiVerificationResult {
        val expectedText = params.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: return BridgeUiVerificationResult(
                verified = false,
                message = "No expectedText parameter was provided",
            )
        val inspection = environment.inspectCurrentScreen()
        val matched = inspection.roots
            .flatMap { it.mergedNodes + it.unmergedNodes }
            .flatMap { node -> node.text + node.contentDescription }
            .firstOrNull { value -> value.contains(expectedText, ignoreCase = true) }
        return BridgeUiVerificationResult(
            verified = matched != null,
            expectedText = expectedText,
            matchedText = matched,
            message = if (matched == null) "Expected text was not found on the current screen" else null,
        )
    }
}

private class ReadScreenshotBridgeHandler(
    private val screenshotReader: BridgeScreenshotReader,
) : BridgeMethodHandler {
    override val method: String = "readScreenshot"

    override suspend fun handle(params: JsonObject): JsonElement = screenshotReader.read(params).toBridgeJsonElement()
}

private class PerformNavigationBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "performNavigation"

    override suspend fun handle(params: JsonObject): JsonElement = BridgeProtocol.json.encodeToJsonElement(
        environment.performNavigation(
            BridgeProtocol.json.decodeFromJsonElement(BridgeNavigationRequest.serializer(), params),
        ),
    )
}
