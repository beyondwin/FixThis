package io.github.beyondwin.fixthis.mcp.session

internal class SessionArtifactJanitor(
    private val persistence: FeedbackSessionPersistence?,
) {
    fun deleteScreenArtifacts(sessionId: String, screenId: String) {
        persistence?.artifactPaths()
            ?.screenArtifactDirectory(sessionId, screenId)
            ?.deleteRecursively()
    }
}
