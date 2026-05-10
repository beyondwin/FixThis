package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixThisToolsStatusTest {

    @Test
    fun `status surfaces installStale when newer source exists`() {
        val tmp = tempDir()
        val installed = 1_700_000_000_000L
        val src = File(tmp, "sample/src/main/kotlin/Foo.kt")
        src.parentFile.mkdirs()
        src.writeText("fun foo() {}")
        src.setLastModified(installed + 120_000)

        val bridge = FakeFixThisBridge(
            sourceIndex = SourceIndex(entries = listOf(
                SourceIndexEntry(file = "sample/src/main/kotlin/Foo.kt", line = 1, excerpt = "fun foo() {}"),
            )),
            sourceIndexAvailable = true,
            statusProvider = {
                buildJsonObject {
                    put("activity", "Foo")
                    put("rootsCount", 1)
                    put("sourceIndexAvailable", true)
                    put("installEpochMillis", installed)
                }
            },
        )
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tmp,
        )

        val response = runBlocking {
            tools.call("fixthis_status", JsonObject(emptyMap()))
        }
        val payload = response.firstStructuredPayload()

        assertEquals(true, payload["installStale"]?.jsonPrimitive?.boolean)
        assertTrue(payload["installStaleReason"]!!.jsonPrimitive.content.contains("indexed source files"))
        assertTrue(payload["newerSourceFiles"]!!.jsonArray.size >= 1)
    }

    @Test
    fun `status reports installStale=false with inconclusive reason when sidekick lacks installEpochMillis`() {
        val tmp = tempDir()
        val bridge = FakeFixThisBridge(
            sourceIndex = SourceIndex(),
            sourceIndexAvailable = true,
            statusProvider = {
                buildJsonObject {
                    put("activity", "Foo")
                    put("rootsCount", 1)
                    put("sourceIndexAvailable", true)
                    // installEpochMillis intentionally absent
                }
            },
        )
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = "io.beyondwin.fixthis.sample",
            projectRoot = tmp,
        )

        val payload = runBlocking { tools.call("fixthis_status", JsonObject(emptyMap())) }
            .firstStructuredPayload()

        assertEquals(false, payload["installStale"]?.jsonPrimitive?.boolean)
        assertTrue(payload["installStaleReason"]!!.jsonPrimitive.content.contains("install epoch"))
    }

    private fun JsonObject.firstStructuredPayload(): JsonObject =
        this["structuredContent"]?.jsonObject
            ?: this["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject
            }
            ?: error("Cannot locate structured payload in $this")

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory(prefix = "fixthis-status-").toFile().also { it.deleteOnExit() }
}
