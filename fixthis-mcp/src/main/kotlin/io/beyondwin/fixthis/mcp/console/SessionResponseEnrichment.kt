package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Encodes [session] to a [JsonObject] and adds the derived `staleAfterHandoff` boolean to every
 * item. The flag is true when the item was modified after its last handoff timestamp:
 * `updatedAtEpochMillis > lastHandedOffAtEpochMillis`. It is `false` when the item has never
 * been handed off (`lastHandedOffAtEpochMillis` is null/missing).
 *
 * The flag is derived only — it is never persisted on disk (see spec §4.3 / §10 Q4).
 */
internal fun enrichSessionWithStaleness(session: SessionDto): JsonObject =
    enrichSessionJson(fixThisJson.encodeToJsonElement(SessionDto.serializer(), session).jsonObject)

/**
 * Mutates an already-encoded [SessionDto] JSON object in place by attaching `staleAfterHandoff`
 * to every entry inside its `items` array. Use this when the encoded session is nested inside
 * another payload (for example [AgentHandoffResponse]).
 */
internal fun enrichSessionJson(base: JsonObject): JsonObject {
    val items = base["items"]?.jsonArray ?: return base
    val enrichedItems = items.map { itemEl ->
        val obj = itemEl.jsonObject
        val updatedAt = obj["updatedAtEpochMillis"]?.jsonPrimitive?.long ?: 0L
        val handedOff = obj["lastHandedOffAtEpochMillis"]?.jsonPrimitive?.longOrNull
        val stale = handedOff != null && updatedAt > handedOff
        JsonObject(obj + ("staleAfterHandoff" to JsonPrimitive(stale)))
    }
    return JsonObject(base + ("items" to JsonArray(enrichedItems)))
}
