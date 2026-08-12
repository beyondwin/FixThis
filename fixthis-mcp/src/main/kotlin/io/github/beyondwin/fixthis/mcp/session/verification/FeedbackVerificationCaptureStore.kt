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
    private val hooks: VerificationArtifactStoreHooks = VerificationArtifactStoreHooks(),
) {
    private val storeCapability = Any()
    private val paths = VerificationArtifactPaths(projectRoot)
    private val operationLocks = VerificationArtifactOperationLocks(paths, hooks)
    private val access = VerificationArtifactDirectoryAccess(paths, hooks)

    fun reserve(session: SessionDto, receiptId: String): OwnedVerificationCapture = try {
        operationLocks.withReceiptLock(session.sessionId, receiptId) {
            reserveLocked(session, receiptId)
        }
    } catch (failure: Exception) {
        throw artifactFailure("reserve live capture", receiptId, failure)
    }

    private fun reserveLocked(session: SessionDto, receiptId: String): OwnedVerificationCapture = try {
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
                hooks.afterDirectoryCreateIdentityCaptured(parent.absolute.resolve(directoryName))
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
                val rollbackFailure = createdFileKey?.let { fileKey ->
                    runCatching {
                        deleteOwnedDirectoryIfPresent(access, hooks, parent, directoryName, fileKey)
                    }.exceptionOrNull()
                }
                if (rollbackFailure != null) {
                    rollbackFailure.addSuppressed(failure)
                    throw rollbackFailure
                }
                throw failure
            }
        }
    } catch (failure: Exception) {
        throw artifactFailure("reserve live capture", receiptId, failure)
    }

    fun cleanup(capture: OwnedVerificationCapture) = try {
        operationLocks.withReceiptLock(capture.sessionId, capture.receiptId) {
            cleanupLocked(capture)
        }
    } catch (failure: Exception) {
        throw artifactFailure("clean live capture", capture.receiptId, failure)
    }

    private fun cleanupLocked(capture: OwnedVerificationCapture) {
        try {
            requireOwned(capture)
            access.withProjectDirectory(parentSegments(capture.sessionId)) { parent ->
                deleteOwnedDirectoryIfPresent(
                    access = access,
                    hooks = hooks,
                    parent = parent,
                    name = directoryName(capture.receiptId),
                    fileKey = capture.fileKey,
                )
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
