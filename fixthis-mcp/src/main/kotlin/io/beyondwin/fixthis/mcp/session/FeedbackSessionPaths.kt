package io.beyondwin.fixthis.mcp.session

import java.io.File

class FeedbackSessionPaths(projectRoot: File) {
    val projectRoot: File = projectRoot.canonicalFile
    val rootDirectory: File = File(this.projectRoot, ".fixthis/feedback-sessions").canonicalFile
    val indexFile: File = File(rootDirectory, "index.json").canonicalFile

    fun sessionDirectory(sessionId: String): File =
        child(rootDirectory, safeId(sessionId))

    fun sessionFile(sessionId: String): File =
        child(sessionDirectory(sessionId), "session.json")

    fun screenArtifactDirectory(sessionId: String, screenId: String): File =
        child(child(child(sessionDirectory(sessionId), "artifacts"), "screens"), safeId(screenId))

    fun fullScreenshotFile(sessionId: String, screenId: String): File =
        child(screenArtifactDirectory(sessionId, screenId), "${safeId(screenId)}-full.png")

    fun isUnderFeedbackStorage(file: File): Boolean =
        file.canonicalFile.toPath().startsWith(rootDirectory.toPath())

    private fun child(parent: File, segment: String): File {
        val child = File(parent, segment).canonicalFile
        require(child.toPath().startsWith(parent.canonicalFile.toPath())) {
            "Path escapes FixThis feedback storage: $segment"
        }
        return child
    }

    private fun safeId(value: String): String {
        require(value.isNotBlank()) { "FixThis id must not be blank" }
        require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe FixThis id: $value" }
        require(value != "." && value != "..") { "Unsafe FixThis id: $value" }
        return value
    }
}
