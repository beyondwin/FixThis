package io.beyondwin.fixthis.cli.bridge

import io.beyondwin.fixthis.cli.BridgeConnectionException
import io.beyondwin.fixthis.cli.BridgeProtocolException
import io.beyondwin.fixthis.cli.BridgeRequestException
import io.beyondwin.fixthis.cli.BridgeSocket
import io.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

internal class BridgeProtocolClient(
    private val expectedProtocolVersion: String,
    private val requestIds: AtomicInteger = AtomicInteger(0),
) {
    fun request(
        socket: BridgeSocket,
        session: SidekickSession,
        method: String,
        params: JsonObject,
        readTimeoutMillis: Long,
    ): JsonObject {
        socket.readTimeoutMillis = readTimeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val request = BridgeRequest(
            id = "req_${requestIds.incrementAndGet()}",
            token = session.token,
            method = method,
            params = params,
        )
        BridgeFrames.writeFrame(socket.output, fixThisJson.encodeToString(BridgeRequest.serializer(), request))
        val responsePayload = BridgeFrames.readFrame(socket.input)
            ?: throw BridgeConnectionException("Bridge closed before sending a response")
        val response = fixThisJson.decodeFromString(BridgeResponse.serializer(), responsePayload)
        if (!response.ok) {
            val error = response.error
            throw BridgeRequestException(
                code = error?.code ?: "BRIDGE_ERROR",
                bridgeMessage = error?.message ?: "Bridge request failed",
            )
        }
        val result = response.result?.jsonObject
            ?: throw BridgeProtocolException("Bridge response did not include an object result")
        validateProtocol(result["bridgeProtocolVersion"]?.jsonPrimitive?.contentOrNull ?: expectedProtocolVersion)
        return result
    }

    private fun validateProtocol(protocolVersion: String) {
        if (protocolVersion != expectedProtocolVersion) {
            throw BridgeProtocolException(
                "FixThis bridge protocol $protocolVersion is incompatible with CLI protocol $expectedProtocolVersion",
            )
        }
    }
}
