package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import java.io.File

internal class OwnedVerificationCapture private constructor(
    private val storeCapability: Any,
    val sessionId: String,
    val receiptId: String,
    val directory: File,
    internal val fileKey: Any,
) {
    fun belongsTo(capability: Any): Boolean = storeCapability === capability

    companion object {
        fun issue(
            storeCapability: Any,
            sessionId: String,
            receiptId: String,
            directory: File,
            fileKey: Any,
        ): OwnedVerificationCapture = OwnedVerificationCapture(
            storeCapability = storeCapability,
            sessionId = sessionId,
            receiptId = receiptId,
            directory = directory,
            fileKey = fileKey,
        )
    }
}

@Suppress("TooGenericExceptionCaught")
internal class FeedbackVerificationCaptureStore(
    projectRoot: File,
    hooks: VerificationArtifactStoreHooks = VerificationArtifactStoreHooks(),
) {
    private val storeCapability = Any()
    private val paths = VerificationArtifactPaths(projectRoot)
    private val access = VerificationArtifactDirectoryAccess(paths, hooks)

    fun reserve(session: SessionDto, receiptId: String): OwnedVerificationCapture = try {
        VerificationArtifactNaming.validateSessionId(session.sessionId)
        VerificationArtifactNaming.validateReceiptId(receiptId)
        artifactRequire(File(session.projectRoot).canonicalFile == paths.projectRoot) {
            "Session project root does not match the verification capture store"
        }
        val parentSegments = parentSegments(session.sessionId)
        val directoryName = directoryName(receiptId)
        access.ensureProjectDescendant(parentSegments)
        access.withProjectDirectory(parentSegments) { parent ->
            var createdFileKey: Any? = null
            try {
                createdFileKey = access.files.createTemporaryDirectoryChild(parent, directoryName)
                access.withChildDirectory(parent, directoryName) { directory ->
                    artifactRequire(directory.fileKey == createdFileKey) {
                        "Verification capture directory ownership changed during reservation"
                    }
                    OwnedVerificationCapture.issue(
                        storeCapability = storeCapability,
                        sessionId = session.sessionId,
                        receiptId = receiptId,
                        directory = directory.absolute.toFile(),
                        fileKey = directory.fileKey,
                    )
                }
            } catch (failure: Exception) {
                createdFileKey?.let { fileKey ->
                    runCatching { deleteOwnedDirectoryIfPresent(access, parent, directoryName, fileKey) }
                }
                throw failure
            }
        }
    } catch (failure: Exception) {
        throw artifactFailure("reserve live capture", receiptId, failure)
    }

    fun cleanup(capture: OwnedVerificationCapture) {
        try {
            requireOwned(capture)
            access.withProjectDirectory(parentSegments(capture.sessionId)) { parent ->
                if (!access.entryExists(parent, directoryName(capture.receiptId))) return@withProjectDirectory
                val attributes = access.attributes(parent, directoryName(capture.receiptId))
                artifactRequire(
                    attributes.isDirectory &&
                        !attributes.isSymbolicLink &&
                        attributes.fileKey() == capture.fileKey,
                ) {
                    "Verification capture directory ownership changed before cleanup"
                }
                access.files.deleteEntryRecursively(parent, directoryName(capture.receiptId))
            }
        } catch (failure: Exception) {
            throw artifactFailure("clean live capture", capture.receiptId, failure)
        }
    }

    private fun requireOwned(capture: OwnedVerificationCapture) {
        artifactRequire(capture.belongsTo(storeCapability)) {
            "Verification capture belongs to another store"
        }
        VerificationArtifactNaming.validateSessionId(capture.sessionId)
        VerificationArtifactNaming.validateReceiptId(capture.receiptId)
        val expected = paths.projectRoot
            .resolve(parentSegments(capture.sessionId).joinToString(File.separator))
            .resolve(directoryName(capture.receiptId))
        artifactRequire(capture.directory.toPath() == expected.toPath()) {
            "Verification capture directory does not match its receipt ownership"
        }
    }

    private fun parentSegments(sessionId: String): List<String> = listOf(
        ".fixthis",
        "preview-cache",
        sessionId,
    )

    private fun directoryName(receiptId: String): String = "verification-$receiptId"
}
