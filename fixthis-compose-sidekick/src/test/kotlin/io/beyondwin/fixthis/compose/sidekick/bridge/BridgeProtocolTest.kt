package io.beyondwin.fixthis.compose.sidekick.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun writesFourByteBigEndianLengthPrefixedUtf8JsonFrame() {
        val output = ByteArrayOutputStream()

        BridgeProtocol.writeFrame(output, """{"id":"req_1","method":"status"}""")

        val bytes = output.toByteArray()
        assertArrayEquals(byteArrayOf(0, 0, 0, 32), bytes.copyOfRange(0, 4))
        assertEquals("""{"id":"req_1","method":"status"}""", bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8))
    }

    @Test
    fun readsFourByteBigEndianLengthPrefixedUtf8JsonFrame() {
        val input = ByteArrayInputStream(
            byteArrayOf(0, 0, 0, 19) + """{"method":"status"}""".toByteArray(Charsets.UTF_8),
        )

        assertEquals("""{"method":"status"}""", BridgeProtocol.readFrame(input))
        assertNull(BridgeProtocol.readFrame(input))
    }
}
