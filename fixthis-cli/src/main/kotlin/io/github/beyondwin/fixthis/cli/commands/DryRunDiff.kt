package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object DryRunDiff {
    enum class Format { JSON, TOML }

    private const val DEFAULT_BUDGET = 4 * 1024
    private const val UNPARSEABLE_JSON_NOTE =
        "<unparseable JSON config; diff suppressed for privacy>"

    fun render(
        before: String,
        after: String,
        format: Format,
        byteBudget: Int = DEFAULT_BUDGET,
    ): String {
        val body = when (format) {
            Format.JSON -> renderJsonSafely(before, after)
            Format.TOML -> renderToml(before, after)
        }
        return enforceBudget(body, byteBudget)
    }

    private fun renderJsonSafely(before: String, after: String): String {
        return try {
            renderJsonStructured(before, after)
        } catch (_: Exception) {
            // Privacy invariant: never leak raw content through the JSON path on parse failure.
            // Emit a fixed placeholder; the byte-budget enforcer adds the trailing newline.
            UNPARSEABLE_JSON_NOTE
        }
    }

    private fun renderJsonStructured(before: String, after: String): String {
        val beforeObj: JsonObject = if (before.isBlank()) JsonObject(emptyMap())
            else Json.parseToJsonElement(before).jsonObject
        val afterObj = Json.parseToJsonElement(after).jsonObject
        return buildString {
            // Top-level non-mcpServers keys: emit added (+) or changed (~).
            afterObj.entries
                .filter { (k, _) -> k != "mcpServers" }
                .forEach { (k, v) ->
                    when {
                        k !in beforeObj -> appendLine("+ \"$k\": $v")
                        beforeObj[k] != v -> appendLine("~ \"$k\": $v")
                    }
                }
            // mcpServers: walk one level deeper. ONLY new/changed names.
            val beforeServers: Map<String, kotlinx.serialization.json.JsonElement> =
                (beforeObj["mcpServers"] as? JsonObject) ?: emptyMap()
            val afterServers = (afterObj["mcpServers"] as? JsonObject) ?: return@buildString
            afterServers.entries.forEach { (name, entry) ->
                when {
                    name !in beforeServers -> appendLine("+ \"$name\": $entry")
                    beforeServers[name] != entry -> appendLine("~ \"$name\": $entry")
                }
            }
        }.trimEnd()
    }

    private fun renderToml(before: String, after: String): String {
        val beforeLines = before.lines().toSet()
        return after.lines()
            .filter { it !in beforeLines && it.isNotBlank() }
            .joinToString("\n") { line -> "+ $line" }
    }

    private fun enforceBudget(body: String, byteBudget: Int): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= byteBudget) {
            body + "\n"
        } else {
            val truncated = bytes.copyOf(byteBudget).toString(Charsets.UTF_8)
            truncated + "\n... (${bytes.size - byteBudget} bytes elided; pass --full-diff to see all)\n"
        }
    }
}
