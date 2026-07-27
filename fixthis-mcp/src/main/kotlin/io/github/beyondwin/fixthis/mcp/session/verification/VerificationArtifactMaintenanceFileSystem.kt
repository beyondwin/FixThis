package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.file.Files
import java.nio.file.LinkOption

internal class VerificationArtifactMaintenanceFileSystem(
    private val paths: VerificationArtifactPaths,
    private val access: VerificationArtifactDirectoryAccess,
) {
    fun deleteReceipt(sessionId: String, receiptId: String) {
        access.withVerificationDirectoryIfPresent(sessionId) { verification ->
            deleteIfPresent(verification, receiptId)
            val reservationName = VerificationArtifactNaming.reservationName(receiptId)
            if (access.entryExists(verification, reservationName)) {
                access.files.deleteFile(verification, reservationName)
            }
        }
    }

    fun sessionIds(): List<String> {
        if (!Files.exists(paths.feedbackRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return access.withProjectDirectory(listOf(".fixthis", "feedback-sessions")) { feedback ->
            access.entryNames(feedback).mapNotNull { name ->
                runCatching {
                    VerificationArtifactNaming.validateSessionId(name)
                    val attributes = access.attributes(feedback, name)
                    name.takeIf { attributes.isDirectory && !attributes.isSymbolicLink }
                }.getOrNull()
            }
        }
    }

    fun entries(sessionId: String): List<VerificationArtifactEntry> = access.withVerificationDirectoryIfPresent(sessionId) { verification ->
        access.entryNames(verification).map { name ->
            val attributes = access.attributes(verification, name)
            VerificationArtifactEntry(
                name = name,
                directory = attributes.isDirectory,
                symbolicLink = attributes.isSymbolicLink,
            )
        }
    }.orEmpty()

    fun deleteEntry(sessionId: String, name: String) {
        access.withVerificationDirectoryIfPresent(sessionId) { verification ->
            deleteIfPresent(verification, name)
        }
    }

    fun pruneSymlinks(sessionId: String, directoryName: String): Int = access.withVerificationDirectoryIfPresent(sessionId) { verification ->
        if (!access.entryExists(verification, directoryName)) {
            0
        } else {
            access.withChildDirectory(verification, directoryName, access.files::pruneSymlinks)
        }
    } ?: 0

    private fun deleteIfPresent(
        verification: VerificationDirectoryHandle,
        name: String,
    ) {
        if (access.entryExists(verification, name)) {
            access.files.deleteEntryRecursively(verification, name)
        }
    }
}
