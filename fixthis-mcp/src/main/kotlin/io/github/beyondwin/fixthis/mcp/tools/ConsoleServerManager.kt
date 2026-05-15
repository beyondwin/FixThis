package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import java.io.File

internal class ConsoleServerManager(
    private val service: FeedbackSessionService,
    private val consoleAssetsDir: File?,
    private val consolePort: Int,
) {
    private val lock = Any()
    private var consoleServer: FeedbackConsoleServer? = null

    fun open(session: SessionDto, resumed: Boolean = false): OpenFeedbackConsoleResult = synchronized(lock) {
        val server = consoleServer ?: FeedbackConsoleServer(
            service = service,
            consoleAssetsDir = consoleAssetsDir,
            port = consolePort,
        ).also { consoleServer = it }
        OpenFeedbackConsoleResult(session = session, consoleUrl = server.start(), resumed = resumed)
    }

    fun stop() = synchronized(lock) {
        consoleServer?.stop()
        consoleServer = null
    }
}

internal data class OpenFeedbackConsoleResult(
    val session: SessionDto,
    val consoleUrl: String,
    val resumed: Boolean,
)
