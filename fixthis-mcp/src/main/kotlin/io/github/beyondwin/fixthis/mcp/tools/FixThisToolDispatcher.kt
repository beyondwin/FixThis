package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbe
import io.github.beyondwin.fixthis.mcp.tools.handlers.McpToolHandler
import io.github.beyondwin.fixthis.mcp.tools.handlers.defaultMcpToolHandlers
import kotlinx.serialization.json.JsonObject
import java.io.File

internal data class FixThisToolBridgePorts(
    val packageResolver: PackageResolver,
    val screenBridge: ScreenBridge,
    val sourceIndexBridge: SourceIndexBridge,
)

internal data class FixThisToolDispatcherServices(
    val defaultPackageName: String?,
    val projectRoot: File,
    val feedbackService: FeedbackSessionService,
    val cache: BridgeResultCache,
    val freshnessProbe: HostSourceFreshnessProbe,
    val consoleManager: ConsoleServerManager,
)

internal class FixThisToolDispatcher(
    handlers: List<McpToolHandler>,
) {
    private val handlersByName = handlers.associateBy { it.name }

    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val handler = handlersByName[name] ?: throw FixThisToolException("Unknown FixThis tool: $name")
        return handler.handle(arguments)
    }
}

internal fun FixThisToolDispatcher(
    ports: FixThisToolBridgePorts,
    services: FixThisToolDispatcherServices,
): FixThisToolDispatcher {
    val screenOperations = ScreenToolOperations(
        ports = ports,
        cache = services.cache,
        freshnessProbe = services.freshnessProbe,
        defaultPackageName = services.defaultPackageName,
    )
    val feedbackOperations = FeedbackToolOperations(
        feedbackService = services.feedbackService,
        consoleManager = services.consoleManager,
        cache = services.cache,
        projectRoot = services.projectRoot,
    )
    return FixThisToolDispatcher(defaultMcpToolHandlers(screenOperations, feedbackOperations))
}
