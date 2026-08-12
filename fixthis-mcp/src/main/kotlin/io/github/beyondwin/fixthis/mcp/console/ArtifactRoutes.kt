package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class ArtifactRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean = path.isFullScreenshotPath() ||
        path.isVerificationAfterScreenshotPath() ||
        path.isScreenPath()

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestURI.path.isFullScreenshotPath()) {
            exchange.requireMethod("GET") {
                exchange.sendScreenshot(exchange.requestURI.path.screenIdFromScreenshotPath())
            }
        } else if (exchange.requestURI.path.isVerificationAfterScreenshotPath()) {
            exchange.requireMethod("GET") {
                exchange.sendVerificationAfterScreenshot(
                    exchange.requestURI.path.receiptIdFromVerificationAfterScreenshotPath(),
                )
            }
        } else if (exchange.requestURI.path.isScreenPath()) {
            exchange.requireMethod("DELETE") {
                exchange.sendJson(
                    200,
                    service.deleteScreen(
                        exchange.session().sessionId,
                        exchange.requestURI.path.screenIdFromScreenPath(),
                    ),
                )
            }
        }
    }

    private fun HttpExchange.sendScreenshot(screenId: String) {
        val session = session()
        val screen = session.screens.firstOrNull { it.screenId == screenId }
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotPath = screen.screenshot?.desktopFullPath
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
        val screenshotFile = File(screenshotPath)
        val sessionArtifactsDir = FeedbackSessionPaths(File(session.projectRoot)).rootDirectory
        if (!screenshotFile.isAllowedPngArtifactUnder(sessionArtifactsDir)) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found")
        }
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.sendVerificationAfterScreenshot(receiptId: String) {
        val session = session()
        val receipt = session.verificationReceipts.firstOrNull { it.receiptId == receiptId }
            ?: screenshotNotFound()
        val screenshotPath = receipt.afterScreenshot?.desktopFullPath
            ?: screenshotNotFound()
        val receiptArtifactDirectory = session.verificationReceiptArtifactDirectory(receipt.receiptId)
            ?: screenshotNotFound()
        val screenshotFile = File(screenshotPath)
        if (!screenshotFile.isAllowedPngArtifactUnder(receiptArtifactDirectory, rejectSymbolicLinks = true)) {
            screenshotNotFound()
        }
        sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun HttpExchange.session(): SessionDto = sessionId()?.let { service.getSession(it) } ?: current()

    private fun HttpExchange.sessionId(): String? = queryParameter("sessionId")?.takeIf { it.isNotBlank() }

    private fun current(): SessionDto = service.requireCurrentSession()

    private fun screenshotNotFound(): Nothing = throw FeedbackConsoleHttpException(404, "Screenshot not found")

    private fun SessionDto.verificationReceiptArtifactDirectory(receiptId: String): File? {
        if (!sessionId.isSafeArtifactPathSegment() || !receiptId.isSafeArtifactPathSegment()) return null
        val feedbackRoot = FeedbackSessionPaths(File(projectRoot)).rootDirectory
        val sessionDirectory = File(feedbackRoot, sessionId).canonicalFile
        val verificationDirectory = File(sessionDirectory, "verification").canonicalFile
        val receiptDirectory = File(verificationDirectory, receiptId).canonicalFile
        return receiptDirectory.takeIf {
            sessionDirectory.toPath().startsWith(feedbackRoot.toPath()) &&
                verificationDirectory.toPath().startsWith(sessionDirectory.toPath()) &&
                receiptDirectory.toPath().startsWith(verificationDirectory.toPath())
        }
    }
}

private fun String.isFullScreenshotPath(): Boolean = split('/').size == 6 && startsWith("/api/screens/") && endsWith("/screenshot/full")

private fun String.screenIdFromScreenshotPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun String.isVerificationAfterScreenshotPath(): Boolean = split('/').size == 6 &&
    startsWith("/api/verification-receipts/") &&
    endsWith("/screenshot/after")

private fun String.receiptIdFromVerificationAfterScreenshotPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun String.isScreenPath(): Boolean = split('/').size == 4 && startsWith("/api/screens/")

private fun String.screenIdFromScreenPath(): String = URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())

private fun File.isAllowedPngArtifactUnder(
    artifactDirectory: File,
    rejectSymbolicLinks: Boolean = false,
): Boolean = extension.lowercase() == "png" &&
    (!rejectSymbolicLinks || Files.isRegularFile(toPath(), NOFOLLOW_LINKS)) &&
    canonicalFile.let { artifact ->
        artifact.isFile && artifact.toPath().startsWith(artifactDirectory.canonicalFile.toPath())
    }

private fun String.isSafeArtifactPathSegment(): Boolean = isNotBlank() &&
    this != "." &&
    this != ".." &&
    '/' !in this &&
    '\\' !in this
