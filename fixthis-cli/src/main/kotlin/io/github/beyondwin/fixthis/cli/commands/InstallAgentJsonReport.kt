package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val compactJson: Json = Json {
    prettyPrint = false
    explicitNulls = false
    encodeDefaults = true
}

private fun JsonObjectBuilder.putReadiness(readiness: FirstRunReadiness?) {
    readiness?.let {
        put(
            "readiness",
            compactJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject,
        )
    }
}

internal object InstallAgentJsonReport {
    data class Applied(val target: String, val path: String, val scope: String)
    data class Skipped(
        val target: String,
        val reason: String,
        val fix: String,
        val readiness: FirstRunReadiness? = null,
    )

    data class ErrorEntry(
        val target: String,
        val message: String,
        val readiness: FirstRunReadiness? = null,
    )

    @Suppress("LongParameterList")
    fun render(
        applied: List<Applied>,
        skipped: List<Skipped>,
        errors: List<ErrorEntry>,
        next: List<String>,
        readiness: FirstRunReadiness? = null,
        restartRequired: Boolean = false,
    ): String {
        val ok = skipped.isEmpty() && errors.isEmpty()
        val obj = buildJsonObject {
            put("schemaVersion", "1.0")
            put("ok", ok)
            put(
                "applied",
                buildJsonArray {
                    applied.forEach { a ->
                        add(
                            buildJsonObject {
                                put("target", a.target)
                                put("path", a.path)
                                put("scope", a.scope)
                            },
                        )
                    }
                },
            )
            put(
                "skipped",
                buildJsonArray {
                    skipped.forEach { sk ->
                        add(
                            buildJsonObject {
                                put("target", sk.target)
                                put("reason", sk.reason)
                                put("fix", sk.fix)
                                putReadiness(sk.readiness)
                            },
                        )
                    }
                },
            )
            put(
                "errors",
                buildJsonArray {
                    errors.forEach { e ->
                        add(
                            buildJsonObject {
                                put("target", e.target)
                                put("message", e.message)
                                putReadiness(e.readiness)
                            },
                        )
                    }
                },
            )
            put("next", buildJsonArray { next.forEach { add(it) } })
            next.firstOrNull()?.let { put("nextAction", it) }
            putReadiness(readiness)
            put("restartRequired", restartRequired)
        }
        return compactJson.encodeToString(obj) + "\n"
    }
}
