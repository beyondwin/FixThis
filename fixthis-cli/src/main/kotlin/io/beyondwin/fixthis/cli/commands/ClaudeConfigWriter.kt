package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.fixThisJson
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class ClaudeConfigWriter : AgentConfigWriter {
    override val name: String = "claude"

    override fun configFile(projectRoot: File, userHome: File): File =
        projectRoot.resolve(".claude/settings.json")

    override fun merge(current: String?, entry: McpConfigEntry): String {
        val root = current
            ?.takeIf { it.isNotBlank() }
            ?.let { fixThisJson.parseToJsonElement(it).jsonObject }
            ?: JsonObject(emptyMap())
        val mcpServersElement = root["mcpServers"]
        if (mcpServersElement != null && mcpServersElement !is JsonObject) {
            throw IllegalArgumentException(
                "\"mcpServers\" in existing .claude/settings.json is not a JSON object " +
                    "(found ${mcpServersElement::class.simpleName}). " +
                    "Fix the file manually before running fixthis setup.",
            )
        }
        val existingServers = mcpServersElement?.jsonObject ?: JsonObject(emptyMap())
        val mergedServers = JsonObject(existingServers + (entry.serverName to entry.toClaudeJson()))
        val mergedRoot = JsonObject(root + ("mcpServers" to mergedServers))
        return fixThisJson.encodeToString(JsonObject.serializer(), mergedRoot) + "\n"
    }

    private fun McpConfigEntry.toClaudeJson(): JsonElement =
        buildJsonObject {
            put("command", JsonPrimitive(command))
            put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
            if (env.isNotEmpty()) {
                put(
                    "env",
                    buildJsonObject {
                        env.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    },
                )
            }
        }
}
