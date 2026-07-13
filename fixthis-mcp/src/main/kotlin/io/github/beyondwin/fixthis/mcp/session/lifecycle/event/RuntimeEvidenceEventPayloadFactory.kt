package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val runtimeEvidenceEventJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun SessionEventPayloadFactory.runtimeEvidence(
    sessionId: String,
    expectedScreenId: String,
    itemIds: List<String>,
    attachments: List<RuntimeEvidenceAttachment>,
    aggregateStatus: RuntimeEvidenceStatus,
): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    put("expectedScreenId", expectedScreenId)
    put("itemIds", runtimeEvidenceEventJson.encodeToJsonElement(ListSerializer(String.serializer()), itemIds))
    put(
        "attachments",
        runtimeEvidenceEventJson.encodeToJsonElement(
            ListSerializer(RuntimeEvidenceAttachment.serializer()),
            attachments,
        ),
    )
    put(
        "aggregateStatus",
        runtimeEvidenceEventJson.encodeToJsonElement(RuntimeEvidenceStatus.serializer(), aggregateStatus),
    )
}

internal fun SessionEventPayloadFactory.runtimeEvidencePolicy(
    sessionId: String,
    policy: RuntimeEvidencePolicy,
): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    put("policy", runtimeEvidenceEventJson.encodeToJsonElement(RuntimeEvidencePolicy.serializer(), policy))
}
