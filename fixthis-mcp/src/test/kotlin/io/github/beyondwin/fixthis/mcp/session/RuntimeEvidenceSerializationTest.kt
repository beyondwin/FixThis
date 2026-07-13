package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceFailureReason
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceProximity
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceTrigger
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceWarning
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeEvidenceSerializationTest {
    @Test
    fun legacySessionWithoutPolicyDecodesAsManual() {
        val session = fixThisJson.decodeFromString(
            SessionDto.serializer(),
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "legacy-session",
              "packageName": "io.example",
              "projectRoot": "/repo",
              "createdAtEpochMillis": 1,
              "updatedAtEpochMillis": 1
            }
            """.trimIndent(),
        )

        assertEquals(RuntimeEvidencePolicy.MANUAL, session.runtimeEvidencePolicy)
    }

    @Test
    fun legacyRuntimeEvidenceAttachmentWithoutLifecycleFieldsUsesCompatibilityDefaults() {
        val attachment = fixThisJson.decodeFromString(
            RuntimeEvidenceAttachment.serializer(),
            """
            {
              "evidenceId": "legacy-evidence",
              "type": "logcat_window",
              "capturedAtEpochMillis": 1,
              "packageName": "io.example",
              "summary": "legacy summary"
            }
            """.trimIndent(),
        )

        assertEquals(RuntimeEvidenceStatus.COMPLETE, attachment.status)
        assertEquals(RuntimeEvidenceTrigger.MANUAL_ATTACHMENT, attachment.trigger)
        assertNull(attachment.captureId)
        assertNull(attachment.screenCapturedAtEpochMillis)
        assertNull(attachment.captureStartedAtEpochMillis)
        assertNull(attachment.captureCompletedAtEpochMillis)
        assertNull(attachment.proximity)
        assertNull(attachment.failureReason)
        assertEquals(emptyList(), attachment.warnings)
    }

    @Test
    fun runtimeEvidencePoliciesRoundTripWithStableWireNames() {
        val expectedWireNames = mapOf(
            RuntimeEvidencePolicy.AUTO_ON_HANDOFF to "auto_on_handoff",
            RuntimeEvidencePolicy.MANUAL to "manual",
            RuntimeEvidencePolicy.OFF to "off",
        )

        expectedWireNames.forEach { (policy, wireName) ->
            val encoded = fixThisJson.encodeToString(RuntimeEvidencePolicy.serializer(), policy)
            assertEquals("\"$wireName\"", encoded)
            assertEquals(policy, fixThisJson.decodeFromString(RuntimeEvidencePolicy.serializer(), encoded))
        }
    }

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
                captureId = "capture-1",
                status = RuntimeEvidenceStatus.PARTIAL,
                trigger = RuntimeEvidenceTrigger.HANDOFF_AUTO,
                screenCapturedAtEpochMillis = 2L,
                captureStartedAtEpochMillis = 3L,
                captureCompletedAtEpochMillis = 4L,
                proximity = RuntimeEvidenceProximity.DELAYED,
                failureReason = RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT,
                warnings = listOf(
                    RuntimeEvidenceWarning.OUTPUT_TRUNCATED,
                    RuntimeEvidenceWarning.REDACTION_APPLIED,
                    RuntimeEvidenceWarning.PROCESS_RESTARTED,
                    RuntimeEvidenceWarning.CONTEXT_CHANGED,
                    RuntimeEvidenceWarning.STALE_WINDOW,
                    RuntimeEvidenceWarning.CUMULATIVE_NOT_WINDOWED,
                    RuntimeEvidenceWarning.TIMESTAMP_FILTER_UNSUPPORTED,
                    RuntimeEvidenceWarning.PID_FILTER_UNSUPPORTED,
                ),
            ),
        )

        val payload = fixThisJson.parseToJsonElement(json).jsonObject

        assertEquals("logcat_window", payload.getValue("type").jsonPrimitive.content)
        assertEquals(
            ".fixthis/runtime-evidence/e-1/logcat.txt",
            payload.getValue("artifactPath").jsonPrimitive.content,
        )
        assertEquals("capture-1", payload.getValue("captureId").jsonPrimitive.content)
        assertEquals("partial", payload.getValue("status").jsonPrimitive.content)
        assertEquals("handoff_auto", payload.getValue("trigger").jsonPrimitive.content)
        assertEquals("2", payload.getValue("screenCapturedAtEpochMillis").jsonPrimitive.content)
        assertEquals("3", payload.getValue("captureStartedAtEpochMillis").jsonPrimitive.content)
        assertEquals("4", payload.getValue("captureCompletedAtEpochMillis").jsonPrimitive.content)
        assertEquals("delayed", payload.getValue("proximity").jsonPrimitive.content)
        assertEquals("capture_timeout", payload.getValue("failureReason").jsonPrimitive.content)
        assertEquals(
            listOf(
                "output_truncated",
                "redaction_applied",
                "process_restarted",
                "context_changed",
                "stale_window",
                "cumulative_not_windowed",
                "timestamp_filter_unsupported",
                "pid_filter_unsupported",
            ),
            payload.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
        )

        val decoded = fixThisJson.decodeFromString(RuntimeEvidenceAttachment.serializer(), json)
        assertEquals(RuntimeEvidenceStatus.PARTIAL, decoded.status)
        assertEquals(RuntimeEvidenceTrigger.HANDOFF_AUTO, decoded.trigger)
        assertEquals(RuntimeEvidenceProximity.DELAYED, decoded.proximity)
        assertEquals(RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT, decoded.failureReason)
    }
}
