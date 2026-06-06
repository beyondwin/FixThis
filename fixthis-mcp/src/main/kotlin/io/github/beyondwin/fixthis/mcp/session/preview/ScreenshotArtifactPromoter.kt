package io.github.beyondwin.fixthis.mcp.session.preview

import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import java.io.File

class ScreenshotArtifactPromoter {
    fun promote(projectRoot: String, sessionId: String, screen: SnapshotDto): SnapshotDto {
        val screenshot = screen.screenshot ?: return screen
        val artifactDirectory = FeedbackSessionPaths(File(projectRoot))
            .screenArtifactDirectory(sessionId, screen.screenId)
        if (!artifactDirectory.exists()) {
            check(artifactDirectory.mkdirs()) {
                "Could not create FixThis artifact directory: ${artifactDirectory.absolutePath}"
            }
        }
        val promotedFullPath = promotePath(
            sourcePath = screenshot.desktopFullPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-full.png",
        )
        val promotedCropPath = promotePath(
            sourcePath = screenshot.desktopCropPath,
            artifactDirectory = artifactDirectory,
            fileName = "${screen.screenId}-crop.png",
        )
        return screen.copy(
            screenshot = screenshot.copy(
                desktopFullPath = promotedFullPath ?: screenshot.desktopFullPath,
                desktopCropPath = promotedCropPath ?: screenshot.desktopCropPath,
            ),
        )
    }

    private fun promotePath(sourcePath: String?, artifactDirectory: File, fileName: String): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        require(source.exists() && source.isFile) { "Preview screenshot artifact is missing: $sourcePath" }
        val destination = artifactDirectory.resolve(fileName)
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        return destination.absolutePath
    }
}
