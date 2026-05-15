package io.beyondwin.fixthis.mcp.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class SessionArtifactJanitorTest {
    @Test
    fun deletesScreenArtifactDirectory() {
        val root = createTempDir(prefix = "fixthis-artifacts")
        try {
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root))
            val screenDir = persistence.artifactPaths().screenArtifactDirectory("session-1", "screen-1")
            File(screenDir, "full.png").apply {
                parentFile.mkdirs()
                writeText("png")
            }

            SessionArtifactJanitor(persistence).deleteScreenArtifacts("session-1", "screen-1")

            assertFalse(screenDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
