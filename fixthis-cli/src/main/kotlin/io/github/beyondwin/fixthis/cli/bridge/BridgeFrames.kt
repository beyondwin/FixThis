package io.github.beyondwin.fixthis.cli.bridge

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
private const val FRAME_BYTE_MASK = 0xff
private const val FIRST_BYTE_SHIFT = 24
private const val SECOND_BYTE_SHIFT = 16
private const val THIRD_BYTE_SHIFT = 8

internal object BridgeFrames {
    fun writeFrame(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FRAME_BYTES) { "Bridge frame exceeds $MAX_FRAME_BYTES bytes" }
        output.writeFrameLength(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun readFrame(input: InputStream): String? {
        val first = input.read()
        if (first == -1) return null
        val length = (first shl FIRST_BYTE_SHIFT) or
            (input.readRequiredByte() shl SECOND_BYTE_SHIFT) or
            (input.readRequiredByte() shl THIRD_BYTE_SHIFT) or
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

    private fun OutputStream.writeFrameLength(size: Int) {
        write((size ushr FIRST_BYTE_SHIFT) and FRAME_BYTE_MASK)
        write((size ushr SECOND_BYTE_SHIFT) and FRAME_BYTE_MASK)
        write((size ushr THIRD_BYTE_SHIFT) and FRAME_BYTE_MASK)
        write(size and FRAME_BYTE_MASK)
    }

    private fun InputStream.readRequiredByte(): Int {
        val value = read()
        if (value == -1) throw EOFException("Unexpected EOF while reading bridge frame length")
        return value
    }
}
