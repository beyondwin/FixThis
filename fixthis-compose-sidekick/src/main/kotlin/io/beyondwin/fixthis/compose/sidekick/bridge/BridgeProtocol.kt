package io.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object BridgeProtocol {
    const val VERSION: String = "1.3"
    private const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun writeFrame(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FRAME_BYTES) { "Bridge frame exceeds $MAX_FRAME_BYTES bytes" }
        output.write((bytes.size ushr 24) and 0xff)
        output.write((bytes.size ushr 16) and 0xff)
        output.write((bytes.size ushr 8) and 0xff)
        output.write(bytes.size and 0xff)
        output.write(bytes)
        output.flush()
    }

    fun readFrame(input: InputStream): String? {
        val first = input.read()
        if (first == -1) return null
        val length = (first shl 24) or
            (input.readRequiredByte() shl 16) or
            (input.readRequiredByte() shl 8) or
            input.readRequiredByte()
        require(length in 0..MAX_FRAME_BYTES) { "Invalid bridge frame length: $length" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) throw EOFException("Unexpected EOF while reading bridge frame")
            offset += read
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun success(id: String?, result: kotlinx.serialization.json.JsonElement): String = json.encodeToString(
        BridgeResponse.serializer(),
        BridgeResponse(id = id, ok = true, result = result),
    )

    fun error(id: String?, code: String, message: String): String = json.encodeToString(
        BridgeResponse.serializer(),
        BridgeResponse(
            id = id,
            ok = false,
            error = BridgeError(code = code, message = message),
        ),
    )

    private fun InputStream.readRequiredByte(): Int {
        val value = read()
        if (value == -1) throw EOFException("Unexpected EOF while reading bridge frame length")
        return value
    }
}

@Serializable
data class BridgeRequest(
    val id: String? = null,
    val token: String? = null,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BridgeResponse(
    val id: String? = null,
    val ok: Boolean,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: BridgeError? = null,
)

@Serializable
data class BridgeError(
    val code: String,
    val message: String,
)
