package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeEvidenceSerializationTest {
    @Test
    fun runtimeEvidenceAttachmentSerializesWithStableWireNames() {
        val json = fixThisJson.encodeToString(
            RuntimeEvidenceAttachment.serializer(),
            RuntimeEvidenceAttachment(
                evidenceId = "e-1",
                type = RuntimeEvidenceType.LOGCAT_WINDOW,
                capturedAtEpochMillis = 1L,
                packageName = "io.example",
                summary = "summary",
                artifactPath = ".fixthis/runtime-evidence/e-1/logcat.txt",
            ),
        )

        val payload = fixThisJson.parseToJsonElement(json).jsonObject

        assertEquals("logcat_window", payload.getValue("type").jsonPrimitive.content)
        assertEquals(
            ".fixthis/runtime-evidence/e-1/logcat.txt",
            payload.getValue("artifactPath").jsonPrimitive.content,
        )
    }
}
