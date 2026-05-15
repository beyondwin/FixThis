package io.github.beyondwin.fixthis.cli.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class SidekickSession(
    val schemaVersion: String = "1.0",
    val packageName: String,
    val socketName: String,
    val socketAddress: String,
    val token: String,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val createdAtEpochMillis: Long,
    val processStartEpochMillis: Long,
)

@Serializable
internal data class BridgeRequest(
    val id: String,
    val token: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class BridgeResponse(
    val id: String? = null,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: BridgeError? = null,
)

@Serializable
internal data class BridgeError(
    val code: String,
    val message: String,
)
