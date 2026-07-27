package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import java.io.File
import java.util.UUID

internal data class PreparedVerificationArtifact(
    val sessionId: String,
    val receiptId: String,
    internal val ownershipToken: String,
    internal val temporaryDirectory: File,
    internal val finalDirectory: File,
    internal val stagedFile: File?,
    internal val sourceMetadata: SnapshotScreenshotDto?,
)

@Suppress("TooGenericExceptionCaught")
internal class FeedbackVerificationArtifactStore(
    projectRoot: File,
    private val temporaryId: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    hooks: VerificationArtifactStoreHooks = VerificationArtifactStoreHooks(),
) {
    private val paths = VerificationArtifactPaths(projectRoot)
    private val fileSystem = VerificationArtifactSecureFileSystem(paths, hooks)
    private val cleaner = VerificationArtifactCleaner(fileSystem)

    fun prepare(
        session: SessionDto,
        receiptId: String,
        source: SnapshotScreenshotDto?,
    ): PreparedVerificationArtifact {
        requireSession(session)
        VerificationArtifactNaming.validateSegment(receiptId, "receiptId")
        val ownershipToken = temporaryId()
        VerificationArtifactNaming.validateToken(ownershipToken)
        val preparedWithoutSource = PreparedVerificationArtifact(
            sessionId = session.sessionId,
            receiptId = receiptId,
            ownershipToken = ownershipToken,
            temporaryDirectory = paths.temporaryDirectory(
                session.sessionId,
                receiptId,
                ownershipToken,
            ),
            finalDirectory = paths.finalDirectory(session.sessionId, receiptId),
            stagedFile = null,
            sourceMetadata = null,
        )
        return try {
            fileSystem.reserve(session.sessionId, receiptId, ownershipToken)
            val sourceFile = sourceFileOrNull(source)
            val stagedFile = sourceFile?.let {
                fileSystem.stagePng(
                    sessionId = session.sessionId,
                    receiptId = receiptId,
                    ownershipToken = ownershipToken,
                    source = it,
                )
            }
            preparedWithoutSource.copy(
                stagedFile = stagedFile,
                sourceMetadata = source.takeIf { sourceFile != null },
            )
        } catch (failure: Exception) {
            fileSystem.deleteOwned(preparedWithoutSource)
            throw artifactFailure("prepare", receiptId, failure)
        }
    }

    fun promote(prepared: PreparedVerificationArtifact): SnapshotScreenshotDto? = try {
        requirePreparedArtifact(prepared)
        fileSystem.promote(prepared)
        prepared.sourceMetadata?.copy(
            fullPath = null,
            cropPath = null,
            desktopFullPath = prepared.finalDirectory
                .resolve(VerificationArtifactNaming.AFTER_SCREENSHOT)
                .canonicalPath,
            desktopCropPath = null,
        )
    } catch (failure: Exception) {
        fileSystem.deleteOwned(prepared)
        throw artifactFailure("promote", prepared.receiptId, failure)
    }

    fun discard(prepared: PreparedVerificationArtifact) {
        try {
            requirePreparedArtifact(prepared)
            fileSystem.deleteOwned(prepared)
        } catch (failure: Exception) {
            throw artifactFailure("discard", prepared.receiptId, failure)
        }
    }

    fun deleteReceiptArtifact(session: SessionDto, receiptId: String) {
        try {
            requireSession(session)
            VerificationArtifactNaming.validateSegment(receiptId, "receiptId")
            fileSystem.deleteReceipt(session.sessionId, receiptId)
        } catch (failure: Exception) {
            throw artifactFailure("delete", receiptId, failure)
        }
    }

    fun cleanupIncomplete(): Int = try {
        cleaner.cleanupIncomplete()
    } catch (failure: Exception) {
        throw artifactFailure("clean incomplete", "all", failure)
    }

    fun cleanupOrphans(referencesBySession: Map<String, Set<String>>): Int = try {
        cleaner.cleanupOrphans(referencesBySession)
    } catch (failure: Exception) {
        throw artifactFailure("clean orphan", "all", failure)
    }

    private fun requireSession(session: SessionDto) {
        VerificationArtifactNaming.validateSegment(session.sessionId, "sessionId")
        artifactRequire(File(session.projectRoot).canonicalFile == paths.projectRoot) {
            "Session project root does not match the verification artifact store"
        }
    }

    private fun requirePreparedArtifact(prepared: PreparedVerificationArtifact) {
        VerificationArtifactNaming.validateSegment(prepared.sessionId, "sessionId")
        VerificationArtifactNaming.validateSegment(prepared.receiptId, "receiptId")
        VerificationArtifactNaming.validateToken(prepared.ownershipToken)
        val expectedTemporary = paths.temporaryDirectory(
            prepared.sessionId,
            prepared.receiptId,
            prepared.ownershipToken,
        )
        val expectedFinal = paths.finalDirectory(prepared.sessionId, prepared.receiptId)
        artifactRequire(prepared.temporaryDirectory.toPath() == expectedTemporary.toPath()) {
            "Verification temporary directory does not match its receipt ownership"
        }
        artifactRequire(prepared.finalDirectory.toPath() == expectedFinal.toPath()) {
            "Verification receipt destination does not match its ids"
        }
        prepared.stagedFile?.let { staged ->
            artifactRequire(
                staged.toPath() ==
                    expectedTemporary.resolve(VerificationArtifactNaming.AFTER_SCREENSHOT).toPath(),
            ) {
                "Verification staged file is outside its owned temporary directory"
            }
        }
    }

    private fun sourceFileOrNull(source: SnapshotScreenshotDto?): File? = source?.desktopFullPath?.trim()?.takeIf(String::isNotEmpty)?.let(::File)
}
