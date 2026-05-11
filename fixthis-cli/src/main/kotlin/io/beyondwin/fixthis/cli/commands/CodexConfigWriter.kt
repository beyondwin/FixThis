package io.beyondwin.fixthis.cli.commands

import java.io.File

internal class CodexConfigWriter : AgentConfigWriter {
    override val name: String = "codex"
    override val scope: String = "global"

    override fun configFile(projectRoot: File, userHome: File): File = userHome.resolve(".codex/config.toml")

    override fun merge(current: String?, entry: McpConfigEntry): String {
        val rendered = render(entry)
        val lines = current.orEmpty().lineSequence().toList()
        val merged = replaceTargetSections(lines, entry.serverName, rendered.trimEnd().lines())
        if (merged == null) {
            return buildString {
                val existing = current.orEmpty().trimEnd()
                if (existing.isNotBlank()) {
                    appendLine(existing)
                    appendLine()
                }
                append(rendered)
            }
        }
        return merged.joinToString("\n").trimEnd() + "\n"
    }

    private fun replaceTargetSections(
        lines: List<String>,
        serverName: String,
        replacement: List<String>,
    ): List<String>? {
        val mainTable = "mcp_servers.$serverName"
        val nestedPrefix = "$mainTable."
        val updated = mutableListOf<String>()
        var index = 0
        var foundTarget = false
        var inserted = false
        while (index < lines.size) {
            val table = tableName(lines[index])
            if (table == null) {
                updated += lines[index]
                index += 1
                continue
            }

            var next = index + 1
            while (next < lines.size && tableName(lines[next]) == null) {
                next += 1
            }

            val isTarget = table == mainTable || table.startsWith(nestedPrefix)
            if (isTarget) {
                foundTarget = true
                if (!inserted && table == mainTable) {
                    updated += replacement
                    inserted = true
                }
            } else {
                updated += lines.subList(index, next)
            }
            index = next
        }

        if (!foundTarget) return null
        if (!inserted) {
            if (updated.any { it.isNotBlank() }) {
                updated += ""
            }
            updated += replacement
        }
        return updated
    }

    private fun tableName(line: String): String? = Regex("""\s*\[([^\]]+)]\s*(?:#.*)?""")
        .matchEntire(line)
        ?.groupValues
        ?.get(1)
        ?.let(::normalizeDottedKey)

    private fun normalizeDottedKey(key: String): String = key.split(".")
        .joinToString(".") { segment -> normalizeDottedKeySegment(segment) }

    private fun normalizeDottedKeySegment(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) {
            return trimmed.substring(1, trimmed.lastIndex)
        }
        return trimmed
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun render(entry: McpConfigEntry): String = buildString {
        appendLine("[mcp_servers.${entry.serverName}]")
        appendLine("command = ${entry.command.tomlString()}")
        appendLine("args = [${entry.args.joinToString(", ") { it.tomlString() }}]")
        if (entry.env.isNotEmpty()) {
            appendLine()
            appendLine("[mcp_servers.${entry.serverName}.env]")
            entry.env.toSortedMap().forEach { (key, value) ->
                appendLine("$key = ${value.tomlString()}")
            }
        }
    }.trimEnd() + "\n"

    private fun String.tomlString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
