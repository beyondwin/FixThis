package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private val runtimeEvidenceReplayJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object RuntimeEvidenceEventReplayer {
    fun apply(session: SessionDto, event: SessionEvent): SessionDto = when (event.type) {
        "runtimeEvidenceCaptured" -> applyCaptured(session, event)
        "runtimeEvidencePolicyUpdated" -> applyPolicy(session, event)
        else -> error("Unsupported runtime-evidence event type: ${event.type}")
    }

    private fun applyCaptured(session: SessionDto, event: SessionEvent): SessionDto {
        val payload = runtimeEvidenceReplayJson.decodeFromJsonElement(
            RuntimeEvidenceCapturedPayload.serializer(),
            event.payload,
        )
        return SessionReducer.reduce(
            session,
            SessionMutation.AttachRuntimeEvidence(
                attachments = payload.attachments,
                itemIds = payload.itemIds,
                expectedScreenId = payload.expectedScreenId,
                aggregateStatus = payload.aggregateStatus,
                now = event.epochMillis,
            ),
        )
    }

    private fun applyPolicy(session: SessionDto, event: SessionEvent): SessionDto {
        val payload = runtimeEvidenceReplayJson.decodeFromJsonElement(
            RuntimeEvidencePolicyPayload.serializer(),
            event.payload,
        )
        return SessionReducer.reduce(
            session,
            SessionMutation.UpdateRuntimeEvidencePolicy(payload.policy, event.epochMillis),
        )
    }
}

@Serializable
private data class RuntimeEvidenceCapturedPayload(
    val expectedScreenId: String,
    val itemIds: List<String>,
    val attachments: List<RuntimeEvidenceAttachment>,
    val aggregateStatus: RuntimeEvidenceStatus,
)

@Serializable
private data class RuntimeEvidencePolicyPayload(
    val policy: RuntimeEvidencePolicy,
)
