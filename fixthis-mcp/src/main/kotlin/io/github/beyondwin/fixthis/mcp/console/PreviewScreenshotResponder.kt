package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import java.io.File

internal class PreviewScreenshotResponder(
    private val service: FeedbackSessionService,
) {
    fun sendExact(exchange: HttpExchange, previewId: String) {
        val explicitSessionId = exchange.queryParameter("sessionId")?.takeIf { it.isNotBlank() }
        val session = explicitSessionId?.let { service.getSession(it) } ?: service.requireCurrentSession()
        val screenshotFile = try {
            service.previewScreenshotFile(session.sessionId, previewId)
        } catch (error: FeedbackSessionException) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found", cause = error)
        }
        exchange.sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    fun sendLatest(exchange: HttpExchange) {
        val session = exchange.queryParameter("sessionId")
            ?.takeIf { it.isNotBlank() }
            ?.let { service.getSession(it) }
            ?: service.requireCurrentSession()
        val projectRoot = File(session.projectRoot).canonicalFile
        val previewRoot = File(projectRoot, ".fixthis/preview-cache/${session.sessionId}").canonicalFile
        val persistedRoot = FeedbackSessionPaths(projectRoot).rootDirectory
        val roots = listOf(previewRoot, persistedRoot)
        val screenshotFile = latestPreviewScreenshot(previewRoot, roots)
            ?: latestPersistedScreenshot(session, roots)
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")

        exchange.sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun latestPreviewScreenshot(previewRoot: File, allowedRoots: List<File>): File? {
        if (!previewRoot.isDirectory) return null
        return previewRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith("-full.png") }
            .map { file -> file.canonicalFile }
            .filter { file -> file.isAllowedPngArtifact(allowedRoots) }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
    }

    private fun latestPersistedScreenshot(session: SessionDto, allowedRoots: List<File>): File? = session.screens
        .asReversed()
        .asSequence()
        .mapNotNull { screen -> screen.screenshot?.desktopFullPath?.let(::File) }
        .map { file -> file.canonicalFile }
        .firstOrNull { file -> file.isAllowedPngArtifact(allowedRoots) }
}

internal fun File.isAllowedPngArtifact(allowedRoots: List<File>): Boolean = isFile &&
    extension.lowercase() == "png" &&
    allowedRoots.any { root -> toPath().startsWith(root.toPath()) }
