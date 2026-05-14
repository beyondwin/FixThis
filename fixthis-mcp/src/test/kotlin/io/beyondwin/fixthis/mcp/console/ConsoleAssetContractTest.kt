package io.beyondwin.fixthis.mcp.console

import kotlin.test.Test
import kotlin.test.assertNull

class ConsoleAssetContractTest {
    @Test
    fun `JAR resources do not include source maps`() {
        val cl = javaClass.classLoader
        val mapUrl = cl.getResource("console/app.js.map")
        assertNull(mapUrl, "app.js.map leaked into the packaged resources")
    }
}
