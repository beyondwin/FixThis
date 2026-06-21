package io.github.beyondwin.fixthis.mcp.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object McpToolRegistry {
    fun listTools(): JsonArray = buildJsonArray {
        ToolDefinitions.forEach { add(it.toJson()) }
    }

    fun listResources(): JsonArray = buildJsonArray {
        ResourceDefinitions.forEach { resource ->
            add(resource.toJson())
        }
    }
}

private const val PACKAGE_NAME_DESCRIPTION =
    "Android application id. If omitted, .fixthis/project.json or server --package is used."
private const val VERIFY_UI_CHANGE_DESCRIPTION =
    "Verify that expected UI text is present on the current screen through the FixThis sidekick bridge."
private const val NAVIGATE_APP_DESCRIPTION =
    "Perform one debug-only navigation action against the app and optionally capture the resulting screen."
private const val LIST_FEEDBACK_DESCRIPTION =
    "List feedback queue summaries for the active FixThis feedback session. " +
        "By default the items array contains only SENT feedback that is not resolved or wont_fix; " +
        "pass includeAll=true to include drafts and finished items."
private const val READ_FEEDBACK_DESCRIPTION =
    "Read the feedback queue as annotation JSON and Markdown. " +
        "By default returns only SENT items that are not yet resolved. " +
        "Pass includeAll=true to receive everything; passing itemId always returns that item regardless of state."
private const val RESOLVE_FEEDBACK_DESCRIPTION =
    "Mark a feedback item as resolved, needing clarification, or not fixed. " +
        "Call this after claiming an item with fixthis_claim_feedback and finishing the work. " +
        "Status must be one of resolved, needs_clarification, wont_fix."
private const val CLAIM_FEEDBACK_DESCRIPTION =
    "Mark a feedback item as in-progress before starting work. " +
        "Call this AFTER reading the item and BEFORE making code changes. Returns the updated item. " +
        "The user's browser console reflects the change within 2 seconds. " +
        "After this call you must eventually call fixthis_resolve_feedback for the same itemId."
private const val CAPTURE_RUNTIME_EVIDENCE_DESCRIPTION =
    "Attach bounded local runtime evidence to a feedback item. " +
        "Stores a summary and optional local artifact path; raw logs and traces are not emitted in compact handoff."

private data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }
}

private data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("mimeType", mimeType)
        put("description", description)
    }
}

private val ToolDefinitions = listOf(
    ToolDefinition(
        name = "fixthis_status",
        description = "Check whether the FixThis sidekick bridge is reachable for the debug app.",
        inputSchema = objectSchema(
            "packageName" to stringProperty(PACKAGE_NAME_DESCRIPTION),
        ),
    ),
    ToolDefinition(
        name = "fixthis_get_current_screen",
        description = "Inspect the current Compose screen through the FixThis sidekick bridge.",
        inputSchema = objectSchema(
            "packageName" to stringProperty(PACKAGE_NAME_DESCRIPTION),
            "includeScreenshot" to booleanProperty(
                "Whether to include the latest screenshot resource URI when available.",
            ),
            "includeSemantics" to booleanProperty(
                "Whether the caller wants semantics data. V1 bridge inspection returns semantics nodes.",
            ),
            "maxNodes" to integerProperty(
                "Maximum nodes requested by the caller. V1 bridge may return fewer or ignore this hint.",
            ),
        ),
    ),
    ToolDefinition(
        name = "fixthis_verify_ui_change",
        description = VERIFY_UI_CHANGE_DESCRIPTION,
        inputSchema = objectSchema(
            "packageName" to stringProperty(PACKAGE_NAME_DESCRIPTION),
            "expectedText" to stringProperty("Text expected to appear on the current screen."),
            "role" to stringProperty("Optional semantic role hint, such as Button."),
            required = listOf("expectedText"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_open_feedback_console",
        description = "Open or return the local FixThis feedback console for the current MCP session.",
        inputSchema = objectSchema(
            "packageName" to stringProperty(PACKAGE_NAME_DESCRIPTION),
            "sessionId" to stringProperty("Exact feedback session id to reopen."),
            "newSession" to booleanProperty(
                "Create a new session instead of resuming the current or latest persisted session.",
            ),
        ),
    ),
    ToolDefinition(
        name = "fixthis_list_feedback_sessions",
        description = "List persisted FixThis feedback sessions for this project.",
        inputSchema = objectSchema(
            "packageName" to stringProperty("Optional Android application id filter."),
            "includeClosed" to booleanProperty("Whether to include closed feedback sessions."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_capture_screen",
        description = "Capture the current Android screen into the active FixThis feedback session.",
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_navigate_app",
        description = NAVIGATE_APP_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "action" to stringProperty("One of back, tap, or swipe."),
            "x" to numberProperty("Tap x coordinate. Required for tap."),
            "y" to numberProperty("Tap y coordinate. Required for tap."),
            "direction" to stringProperty("Swipe direction: up, down, left, or right. Required for swipe."),
            "distance" to numberProperty("Optional swipe distance."),
            "captureAfter" to booleanProperty("Whether to capture a screen after navigation. Defaults to true."),
            required = listOf("action"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_list_feedback",
        description = LIST_FEEDBACK_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "includeAll" to booleanProperty(
                "When true, return every item in the session instead of only SENT and unfinished items. " +
                    "Defaults to false.",
            ),
        ),
    ),
    ToolDefinition(
        name = "fixthis_read_feedback",
        description = READ_FEEDBACK_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Optional feedback item id to focus the returned payload."),
            "detailMode" to enumStringProperty(
                description = "Markdown detail level. JSON remains complete regardless of this value.",
                values = listOf("compact", "precise", "full"),
            ),
            "includeAll" to booleanProperty("If true, returns DRAFT and resolved items too."),
        ),
    ),
    ToolDefinition(
        name = "fixthis_resolve_feedback",
        description = RESOLVE_FEEDBACK_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to update."),
            "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
            "summary" to stringProperty("Agent summary shown in the console."),
            required = listOf("itemId", "status"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_claim_feedback",
        description = CLAIM_FEEDBACK_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to claim."),
            "agentNote" to stringProperty("Optional short note shown next to the item in the user's console."),
            required = listOf("itemId"),
        ),
    ),
    ToolDefinition(
        name = "fixthis_capture_runtime_evidence",
        description = CAPTURE_RUNTIME_EVIDENCE_DESCRIPTION,
        inputSchema = objectSchema(
            "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
            "itemId" to stringProperty("Feedback item id to attach evidence to."),
            "type" to enumStringProperty(
                description = "Runtime evidence type.",
                values = listOf("logcat_window", "frame_summary", "memory_summary", "trace_artifact"),
            ),
            "summary" to stringProperty("Bounded evidence summary. Do not include raw logs or trace payloads."),
            "artifactPath" to stringProperty("Optional local artifact path under ignored storage."),
            required = listOf("itemId", "type", "summary"),
        ),
    ),
)

private val ResourceDefinitions = listOf(
    ResourceDefinition(
        "fixthis://session/current",
        "Current FixThis session",
        "Current bridge session and sidekick status.",
    ),
    ResourceDefinition("fixthis://screen/current", "Current FixThis screen", "Current inspected Compose screen."),
    ResourceDefinition(
        "fixthis://screenshot/latest/full.png",
        "Latest full screenshot",
        "Desktop-readable latest full screenshot artifact path.",
    ),
    ResourceDefinition(
        "fixthis://screenshot/latest/crop.png",
        "Latest crop screenshot",
        "Desktop-readable latest crop screenshot artifact path.",
    ),
    ResourceDefinition(
        "fixthis://source-index",
        "FixThis source index",
        "Source index availability reported by the sidekick bridge.",
    ),
)

private fun objectSchema(
    vararg properties: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
    )
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
    put("additionalProperties", false)
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun enumStringProperty(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun integerProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun numberProperty(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}
