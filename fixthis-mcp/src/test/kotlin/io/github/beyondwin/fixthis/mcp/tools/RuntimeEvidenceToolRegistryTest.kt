package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceStatus
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceToolRegistryTest {
    @Test
    fun toolRegistryExposesRuntimeEvidenceTool() {
        val tools = McpToolRegistry.listTools()
        val names = tools.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertTrue("fixthis_capture_runtime_evidence" in names)
        assertTrue("fixthis_collect_runtime_evidence" in names)
    }

    @Test
    fun collectionToolSchemaPinsAllowlistedPresetsAndRequiredFields() {
        val tool = McpToolRegistry.listTools().jsonArray
            .first { it.jsonObject.getValue("name").jsonPrimitive.content == "fixthis_collect_runtime_evidence" }
            .jsonObject
        val schema = tool.getValue("inputSchema").jsonObject
        val properties = schema.getValue("properties").jsonObject
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        val presets = properties.getValue("preset").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(listOf("itemId", "preset"), required)
        assertEquals(listOf("baseline", "logs", "memory", "performance"), presets)
        assertEquals(setOf("sessionId", "itemId", "preset"), properties.keys)
    }

    @Test
    fun runtimeEvidenceToolSchemaPinsRequiredFieldsAndEvidenceTypes() {
        val tool = McpToolRegistry.listTools().jsonArray
            .first { it.jsonObject.getValue("name").jsonPrimitive.content == "fixthis_capture_runtime_evidence" }
            .jsonObject
        val schema = tool.getValue("inputSchema").jsonObject
        val properties = schema.getValue("properties").jsonObject
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        val typeEnum = properties.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(listOf("itemId", "type", "summary"), required)
        assertEquals(listOf("logcat_window", "frame_summary", "memory_summary", "trace_artifact"), typeEnum)
        assertTrue("artifactPath" in properties.keys)
    }

    @Test
    fun collectionToolCallsCoordinatorAndReturnsNoRawCollectorPayload() = withToolFixture { fixture ->
        val response = runBlocking {
            fixture.tools.call(
                "fixthis_collect_runtime_evidence",
                buildJsonObject {
                    put("sessionId", fixture.sessionId)
                    put("itemId", "item-1")
                    put("preset", "logs")
                },
            )
        }
        val payload = response.getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content

        val result = fixThisJson.parseToJsonElement(payload).jsonObject
        assertEquals(true, result.getValue("attempted").jsonPrimitive.boolean)
        assertEquals("complete", result.getValue("status").jsonPrimitive.content)
        assertFalse(payload.contains("raw-secret"))
        assertFalse(payload.contains("output"))
    }

    @Test
    fun collectionToolRejectsBlankAndAdditionalArguments() = withToolFixture { fixture ->
        runBlocking {
            assertFailsWith<FixThisToolException> {
                fixture.tools.call(
                    "fixthis_collect_runtime_evidence",
                    buildJsonObject {
                        put("sessionId", "")
                        put("itemId", "item-1")
                        put("preset", "logs")
                    },
                )
            }
            assertFailsWith<FixThisToolException> {
                fixture.tools.call(
                    "fixthis_collect_runtime_evidence",
                    buildJsonObject {
                        put("itemId", "item-1")
                        put("preset", "logs")
                        put("command", "shell")
                    },
                )
            }
        }
    }

    @Test
    fun legacyAttachmentToolStillTreatsBlankSessionAsActiveAndIgnoresExtraArguments() = withToolFixture { fixture ->
        runBlocking {
            fixture.tools.call(
                "fixthis_capture_runtime_evidence",
                buildJsonObject {
                    put("sessionId", "")
                    put("itemId", "item-1")
                    put("type", "logcat_window")
                    put("summary", "legacy summary")
                    put("legacyExtra", true)
                },
            )
        }

        assertEquals("legacy summary", fixture.store.getSession(fixture.sessionId).runtimeEvidence.last().summary)
    }

    private fun withToolFixture(block: (ToolFixture) -> Unit) {
        val root = Files.createTempDirectory("fixthis-runtime-tool").toFile()
        val store = FeedbackSessionStore(idGenerator = AtomicToolIds()::next)
        val bridge = ToolRuntimeEvidenceBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
            runtimeEvidenceBridge = bridge,
        )
        val session = service.openSession(null, newSession = true)
        store.replaceSessionForDomain(
            store.getSession(session.sessionId).copy(
                screens = listOf(SnapshotDto("screen-1", 1_000, displayName = "Screen", fingerprint = "frozen")),
                items = listOf(
                    AnnotationDto(
                        itemId = "item-1",
                        screenId = "screen-1",
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 1,
                        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                        comment = "comment",
                    ),
                ),
            ),
        )
        val tools = FixThisTools(bridge = bridge, projectRoot = root, feedbackService = service)
        try {
            block(ToolFixture(tools, store, session.sessionId))
        } finally {
            tools.close()
            root.deleteRecursively()
        }
    }

    private data class ToolFixture(
        val tools: FixThisTools,
        val store: FeedbackSessionStore,
        val sessionId: String,
    )
}

private class AtomicToolIds {
    private var next = 0
    fun next(): String = "tool-${++next}"
}

private class ToolRuntimeEvidenceBridge(
    private val delegate: FakeFixThisBridge = FakeFixThisBridge(),
) : FixThisBridge by delegate,
    RuntimeEvidenceBridge {
    override fun capabilities(packageName: String) = CliRuntimeEvidenceCapabilities(
        baselineAvailable = true,
        supportedCollectors = setOf(CliRuntimeEvidenceKind.LOGCAT_WINDOW),
    )

    override suspend fun context(packageName: String) = CliRuntimeEvidenceContext(
        deviceSerial = "emulator-5554",
        packageName = packageName,
        packageAvailable = true,
        pid = 1,
        installEpochMillis = 10,
        currentActivity = "MainActivity",
        bridgeProtocolVersion = "1.3",
        currentScreenFingerprint = "frozen",
    )

    override suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ) = CliRuntimeEvidenceResult(
        kind = kind,
        status = CliRuntimeEvidenceStatus.COMPLETE,
        startedAtEpochMillis = 1_100,
        completedAtEpochMillis = 1_200,
        command = listOf("/private/adb", "shell", packageName, "api_key=raw-secret"),
        output = "api_key=raw-secret java.lang.IllegalStateException",
    )
}
