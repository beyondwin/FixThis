@file:Suppress("MatchingDeclarationName")

package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.DiagnosticContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

internal enum class SetupFailureCategory {
    MALFORMED_JSON,
    MALFORMED_MCPSERVERS_SHAPE,
    MALFORMED_TOML,
    FILESYSTEM_ERROR,
    UNKNOWN,
}

internal fun renderMergeFailure(writerName: String, configFile: File, error: Throwable): String {
    val category = classify(configFile, error)
    val builder = StringBuilder()
    val redactedPath = SetupErrorRedactor.redact(configFile.absolutePath)
    builder.appendLine("Could not merge $writerName MCP config at $redactedPath.")
    builder.appendLine("  Category: ${category.name}")
    builder.appendLine("  Cause:")
    renderCauseChain(error).forEach { builder.appendLine("    ${SetupErrorRedactor.redact(it)}") }
    builder.appendLine("  Fix: ${SetupErrorRedactor.redact(recommendation(category, configFile))}")
    if (!DiagnosticContext.verbose) {
        builder.append("  Re-run with --verbose for a full stack trace.")
    } else {
        if (builder.endsWith("\n")) builder.deleteCharAt(builder.length - 1)
    }
    return builder.toString()
}

internal fun classify(configFile: File, error: Throwable): SetupFailureCategory = when {
    isJsonDecodingFailure(error) -> SetupFailureCategory.MALFORMED_JSON
    isMcpServersShapeFailure(error) -> SetupFailureCategory.MALFORMED_MCPSERVERS_SHAPE
    isFilesystemFailure(error) -> SetupFailureCategory.FILESYSTEM_ERROR
    configFile.isTomlFile() && isTomlParseFailure(error) -> SetupFailureCategory.MALFORMED_TOML
    configFile.isTomlFile() && error !is SerializationException -> SetupFailureCategory.MALFORMED_TOML
    else -> SetupFailureCategory.UNKNOWN
}

private fun isJsonDecodingFailure(error: Throwable): Boolean {
    val chain = causeChain(error)
    return chain.any { it::class.simpleName == "JsonDecodingException" } ||
        chain.any { it is SerializationException }
}

private fun isMcpServersShapeFailure(error: Throwable): Boolean = error is IllegalArgumentException &&
    error.message?.contains("\"mcpServers\"") == true &&
    error.message?.contains("not a JSON object") == true

private fun isFilesystemFailure(error: Throwable): Boolean = causeChain(error).any { it is IOException }

private fun File.isTomlFile(): Boolean = name.endsWith(".toml")

private fun isTomlParseFailure(error: Throwable): Boolean = error !is SerializationException &&
    causeChain(error).any { it is IllegalStateException || it is IllegalArgumentException }

private fun recommendation(category: SetupFailureCategory, configFile: File): String = when (category) {
    SetupFailureCategory.MALFORMED_JSON ->
        "Open ${configFile.absolutePath}, fix the JSON syntax " +
            "(the parse error above points at the offending position), " +
            "and re-run `fixthis setup --write`. If you cannot recover the file, back it up and delete it; " +
            "`fixthis setup --write` will create a fresh one."
    SetupFailureCategory.MALFORMED_MCPSERVERS_SHAPE ->
        "The `mcpServers` key already exists in ${configFile.absolutePath} but is not a JSON object. " +
            "Open the file and replace it with `{}` (an empty object), or remove the key entirely; " +
            "`fixthis setup --write` will add the FixThis entry."
    SetupFailureCategory.MALFORMED_TOML ->
        "Open ${configFile.absolutePath}, repair the TOML (errors above name the failure), and re-run. " +
            "If unsure, back up the file and delete the `[mcp_servers.fixthis]` section; " +
            "`fixthis setup --write` will rewrite it."
    SetupFailureCategory.FILESYSTEM_ERROR ->
        "Filesystem error reading ${configFile.absolutePath}. " +
            "Check permissions with `ls -l ${configFile.absolutePath}`. " +
            "If the file is owned by another user, `chmod +r` or `chown` so your shell can read it."
    SetupFailureCategory.UNKNOWN ->
        "Please file an issue with the output of `fixthis setup --write --verbose` and the contents of " +
            "${configFile.absolutePath} (redact secrets first)."
}

internal fun renderCauseChain(top: Throwable, maxDepth: Int = 8): List<String> {
    val seen = IdentityHashMap<Throwable, Boolean>()
    val out = mutableListOf<String>()
    var current: Throwable? = top
    var depth = 0
    while (current != null) {
        val throwable = current
        if (seen.containsKey(throwable)) {
            out += "${"  ".repeat(depth)}(cause chain truncated: cycle detected)"
            current = null
        } else if (depth >= maxDepth) {
            out += "${"  ".repeat(depth)}(cause chain truncated)"
            current = null
        } else {
            seen[throwable] = true
            val indent = "  ".repeat(depth)
            val name = throwable::class.simpleName ?: throwable.javaClass.name
            val msg = throwable.message ?: "(no message)"
            out += "${indent}caused by $name: $msg"
            val next = throwable.cause
            if (next === throwable) {
                out += "${"  ".repeat(depth + 1)}(cause chain truncated: self-reference)"
                current = null
            } else {
                current = next
                depth += 1
            }
        }
    }
    return out
}

private fun causeChain(top: Throwable): Sequence<Throwable> {
    val seen = IdentityHashMap<Throwable, Boolean>()
    return generateSequence(top) { it.cause }
        .takeWhile { seen.put(it, true) == null }
}
