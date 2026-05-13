package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import java.io.File
import java.net.URLDecoder

internal class ArtifactRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean = path.isFullScreenshotPath() || path.isScreenPath()

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestURI.path.isFullScreenshotPath()) {
            exchange.requireMethod("GET") {
                exchange.sendScreenshot(exchange.requestURI.path.screenIdFromScreenshotPath())
            }
        } else if (exchange.requestURI.path.isScreenPath()) {
            exchange.requireMethod("DELETE") {
                exchange.sendJson(
                    200,
                    service.deleteScreen(
                        exchange.requestedSession().sessionId,
                        exchange.requestURI.path.screenIdFromScreenPath(),
                    ),
                )
            }
        }
    }

    private fun HttpExchange.sendScreenshot(screenId: String) {
        val session = requestedSession()
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotPath = screen.screenshot?.desktopFullPath
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotFile = File(screenshotPath).canonicalFile
        val sessionArtifactsDir = FeedbackSessionPaths(File(session.projectRoot)).rootDirectory
        val legacyArtifactsDir = File(session.projectRoot, ".fixthis/artifacts").canonicalFile
        val isAllowedArtifact = screenshotFile.toPath().startsWith(sessionArtifactsDir.toPath()) ||
            screenshotFile.toPath().startsWith(legacyArtifactsDir.toPath())
        if (
            !screenshotFile.isFile ||
            screenshotFile.extension.lowercase() != "png" ||
            !isAllowedArtifact
        ) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found")
        }
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.requestedSession() =
        queryParameter("sessionId")?.takeIf { it.isNotBlank() }?.let { service.getSession(it) }
            ?: service.requireCurrentSession()
}

private fun String.isFullScreenshotPath(): Boolean = split('/').size == 6 && startsWith("/api/screens/") && endsWith("/screenshot/full")

private fun String.screenIdFromScreenshotPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun String.isScreenPath(): Boolean = split('/').size == 4 && startsWith("/api/screens/")

private fun String.screenIdFromScreenPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())
