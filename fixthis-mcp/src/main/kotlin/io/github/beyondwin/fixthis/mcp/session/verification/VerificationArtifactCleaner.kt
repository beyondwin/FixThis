package io.github.beyondwin.fixthis.mcp.session.verification

internal class VerificationArtifactCleaner(
    private val fileSystem: VerificationArtifactSecureFileSystem,
) {
    fun cleanupIncomplete(): Int {
        var deleted = 0
        fileSystem.sessionIds().forEach { sessionId ->
            fileSystem.entries(sessionId).forEach { entry ->
                val incomplete = VerificationArtifactNaming.isTemporaryName(entry.name) ||
                    VerificationArtifactNaming.isReservationName(entry.name) ||
                    entry.directory && fileSystem.hasOwnerMarker(sessionId, entry.name)
                if (incomplete) {
                    fileSystem.deleteEntry(sessionId, entry.name)
                    deleted += 1
                }
            }
        }
        return deleted
    }

    fun cleanupOrphans(referencesBySession: Map<String, Set<String>>): Int {
        referencesBySession.forEach { (sessionId, receiptIds) ->
            VerificationArtifactNaming.validateSegment(sessionId, "sessionId")
            receiptIds.forEach { VerificationArtifactNaming.validateSegment(it, "receiptId") }
        }
        var deleted = 0
        fileSystem.sessionIds().forEach { sessionId ->
            val references = referencesBySession[sessionId].orEmpty()
            fileSystem.entries(sessionId).forEach { entry ->
                when {
                    VerificationArtifactNaming.isTemporaryName(entry.name) ||
                        VerificationArtifactNaming.isReservationName(entry.name) -> Unit
                    entry.name !in references || !entry.directory || entry.symbolicLink -> {
                        fileSystem.deleteEntry(sessionId, entry.name)
                        deleted += 1
                    }
                    else -> deleted += fileSystem.pruneSymlinks(sessionId, entry.name)
                }
            }
        }
        return deleted
    }
}
