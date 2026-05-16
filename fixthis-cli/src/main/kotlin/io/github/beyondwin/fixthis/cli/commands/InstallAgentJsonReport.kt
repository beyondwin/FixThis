package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object InstallAgentJsonReport {
    data class Applied(val target: String, val path: String, val scope: String)
    data class Skipped(val target: String, val reason: String, val fix: String)
    data class ErrorEntry(val target: String, val message: String)

    fun render(
        applied: List<Applied>,
        skipped: List<Skipped>,
        errors: List<ErrorEntry>,
        next: List<String>,
    ): String {
        val ok = skipped.isEmpty() && errors.isEmpty()
        val obj = buildJsonObject {
            put("schemaVersion", "1.0")
            put("ok", ok)
            put("applied", buildJsonArray {
                applied.forEach { a ->
                    add(buildJsonObject {
                        put("target", a.target)
                        put("path", a.path)
                        put("scope", a.scope)
                    })
                }
            })
            put("skipped", buildJsonArray {
                skipped.forEach { sk ->
                    add(buildJsonObject {
                        put("target", sk.target)
                        put("reason", sk.reason)
                        put("fix", sk.fix)
                    })
                }
            })
            put("errors", buildJsonArray {
                errors.forEach { e ->
                    add(buildJsonObject {
                        put("target", e.target)
                        put("message", e.message)
                    })
                }
            })
            put("next", buildJsonArray { next.forEach { add(it) } })
        }
        return fixThisJson.encodeToString(obj) + "\n"
    }
}
