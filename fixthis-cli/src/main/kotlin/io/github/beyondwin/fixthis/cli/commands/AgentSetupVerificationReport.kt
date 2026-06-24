package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val agentSetupJson = Json {
    prettyPrint = false
    explicitNulls = false
    encodeDefaults = true
}

internal data class AgentSetupAction(
    val id: String,
    val actor: String,
    val kind: String,
    val reason: String,
    val blocksProgress: Boolean,
    val command: String? = null,
    val tool: String? = null,
)

internal object AgentSetupActionContract {
    const val AGENT = "agent"
    const val USER = "user"
    const val AGENT_AFTER_RESTART = "agent_after_restart"

    const val COMMAND = "command"
    const val MCP_TOOL = "mcp_tool"
    const val MANUAL = "manual"

    private val allowedActors = setOf(AGENT, USER, AGENT_AFTER_RESTART)
    private val allowedKinds = setOf(COMMAND, MCP_TOOL, MANUAL)

    fun validate(action: AgentSetupAction): List<String> = buildList {
        if (action.actor !in allowedActors) add("Unsupported action actor: ${action.actor}")
        if (action.kind !in allowedKinds) add("Unsupported action kind: ${action.kind}")
        if (action.kind == COMMAND && action.command.isNullOrBlank()) add("Command action requires command")
        if (action.kind == MCP_TOOL && action.tool.isNullOrBlank()) add("MCP tool action requires tool")
        if (action.kind == MANUAL && action.reason.isBlank()) add("Manual action requires reason")
        if (action.kind == COMMAND && !action.tool.isNullOrBlank()) add("Command action must not include tool")
        if (action.kind == MCP_TOOL && !action.command.isNullOrBlank()) add("MCP tool action must not include command")
        if (action.kind == MANUAL && !action.command.isNullOrBlank()) add("Manual action must not include command")
        if (action.kind == MANUAL && !action.tool.isNullOrBlank()) add("Manual action must not include tool")
    }

    fun requireValid(action: AgentSetupAction) {
        val errors = validate(action)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}

internal data class AgentSetupSnapshot(
    val applied: List<InstallAgentJsonReport.Applied>,
    val skipped: List<InstallAgentJsonReport.Skipped>,
    val errors: List<InstallAgentJsonReport.ErrorEntry>,
    val mcpConfigChanged: Boolean,
)

internal data class AgentVerificationSnapshot(
    val ok: Boolean,
    val packageName: String?,
    val checks: List<DoctorCheckResult>,
    val skippedReason: String? = null,
)

internal data class AgentSetupVerificationReport(
    val ok: Boolean,
    val readiness: FirstRunReadiness,
    val readinessSource: String,
    val next: List<String>,
    val restartRequired: Boolean,
    val requiresUserAction: Boolean,
    val userActionReason: String?,
    val readyForMcpTooling: Boolean,
    val actions: List<AgentSetupAction>,
    val setup: AgentSetupSnapshot,
    val verification: AgentVerificationSnapshot,
)

internal object AgentSetupVerificationJsonReport {
    fun render(report: AgentSetupVerificationReport): String {
        report.actions.forEach(AgentSetupActionContract::requireValid)
        val preferredNextAction = report.readiness.nextAction.ifBlank { report.next.firstOrNull().orEmpty() }
        return agentSetupJson.encodeToString(
            buildJsonObject {
                put("schemaVersion", "1.1")
                put("ok", report.ok)
                put(
                    "readiness",
                    agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), report.readiness).jsonObject,
                )
                put("readinessSource", report.readinessSource)
                if (preferredNextAction.isNotBlank()) put("nextAction", preferredNextAction)
                put("next", buildJsonArray { report.next.forEach { add(it) } })
                put("restartRequired", report.restartRequired)
                put("requiresUserAction", report.requiresUserAction)
                report.userActionReason?.let { put("userActionReason", it) }
                put("readyForMcpTooling", report.readyForMcpTooling)
                put(
                    "actions",
                    buildJsonArray {
                        report.actions.forEach { action ->
                            add(
                                buildJsonObject {
                                    put("id", action.id)
                                    put("actor", action.actor)
                                    put("kind", action.kind)
                                    put("reason", action.reason)
                                    put("blocksProgress", action.blocksProgress)
                                    action.command?.let { put("command", it) }
                                    action.tool?.let { put("tool", it) }
                                },
                            )
                        }
                    },
                )
                put(
                    "setup",
                    buildJsonObject {
                        put("mcpConfigChanged", report.setup.mcpConfigChanged)
                        put("applied", renderApplied(report.setup.applied))
                        put("skipped", renderSkipped(report.setup.skipped))
                        put("errors", renderErrors(report.setup.errors))
                    },
                )
                put(
                    "verification",
                    buildJsonObject {
                        put("ok", report.verification.ok)
                        report.verification.packageName?.let { put("packageName", it) }
                        report.verification.skippedReason?.let { put("skippedReason", it) }
                        put("checks", renderDoctorChecks(report.verification.checks))
                    },
                )
            },
        ) + "\n"
    }

    private fun renderApplied(applied: List<InstallAgentJsonReport.Applied>) = buildJsonArray {
        applied.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("path", entry.path)
                    put("scope", entry.scope)
                },
            )
        }
    }

    private fun renderSkipped(skipped: List<InstallAgentJsonReport.Skipped>) = buildJsonArray {
        skipped.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("reason", entry.reason)
                    put("fix", entry.fix)
                    entry.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }

    private fun renderErrors(errors: List<InstallAgentJsonReport.ErrorEntry>) = buildJsonArray {
        errors.forEach { entry ->
            add(
                buildJsonObject {
                    put("target", entry.target)
                    put("message", entry.message)
                    entry.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }

    private fun renderDoctorChecks(checks: List<DoctorCheckResult>) = buildJsonArray {
        checks.forEach { check ->
            add(
                buildJsonObject {
                    put("name", check.name)
                    put("label", check.label)
                    put("status", if (check.ok) "ok" else "fail")
                    check.message?.let { put("message", it) }
                    check.fix?.let { put("fix", it) }
                    check.readiness?.let {
                        put("readiness", agentSetupJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject)
                    }
                },
            )
        }
    }
}
