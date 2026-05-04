package io.github.pointpatch.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    @Test
    fun consoleStartupResultMarksToolErrors() {
        val result = consoleStartupResult(
            toolResult(
                isError = true,
                content = listOf(textContent("Package name could not be resolved")),
            ),
        )

        assertTrue(result.isError)
        assertEquals("Package name could not be resolved", result.text)
    }

    @Test
    fun consoleStartupResultReturnsSuccessText() {
        val result = consoleStartupResult(
            toolResult(
                content = listOf(textContent("""{"consoleUrl":"http://127.0.0.1:1234/"}""")),
            ),
        )

        assertFalse(result.isError)
        assertEquals("""{"consoleUrl":"http://127.0.0.1:1234/"}""", result.text)
    }
}
