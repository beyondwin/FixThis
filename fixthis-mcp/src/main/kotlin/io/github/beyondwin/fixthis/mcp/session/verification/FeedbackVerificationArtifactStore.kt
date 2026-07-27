package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import java.io.File
import java.util.UUID

internal data class PreparedVerificationArtifactData(
    val sessionId: String,
    val receiptId: String,
    val ownershipToken: String,
    val temporaryDirectory: File,
    val finalDirectory: File,
    val stagedFile: File?,
    val sourceMetadata: SnapshotScreenshotDto?,
)

internal class PreparedVerificationArtifact private constructor(
    private val storeCapability: Any,
    private val data: PreparedVerificationArtifactData,
) {
    val sessionId: String get() = data.sessionId
    val receiptId: String get() = data.receiptId
    internal val ownershipToken: String get() = data.ownershipToken
    internal val temporaryDirectory: File get() = data.temporaryDirectory
    internal val finalDirectory: File get() = data.finalDirectory
    internal val stagedFile: File? get() = data.stagedFile
    internal val sourceMetadata: SnapshotScreenshotDto? get() = data.sourceMetadata

    fun belongsTo(capability: Any): Boolean = storeCapability === capability

    fun withStagedFile(
        stagedFile: File?,
        sourceMetadata: SnapshotScreenshotDto?,
    ): PreparedVerificationArtifact = PreparedVerificationArtifact(
        storeCapability = storeCapability,
        data = data.copy(
            stagedFile = stagedFile,
            sourceMetadata = sourceMetadata,
        ),
    )

    companion object {
        fun issue(
            storeCapability: Any,
            data: PreparedVerificationArtifactData,
        ): PreparedVerificationArtifact = PreparedVerificationArtifact(
            storeCapability = storeCapability,
            data = data,
        )
    }
}

@Suppress("TooGenericExceptionCaught")
internal class FeedbackVerificationArtifactStore(
    projectRoot: File,
    private val temporaryId: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    private val hooks: VerificationArtifactStoreHooks = VerificationArtifactStoreHooks(),
) {
    private val storeCapability = Any()
    private val paths = VerificationArtifactPaths(projectRoot)
    private val operationLocks = VerificationArtifactOperationLocks(paths, hooks)
    private val fileSystem = VerificationArtifactSecureFileSystem(paths, hooks)
    private val maintenance = VerificationArtifactMaintenanceFileSystem(
        paths,
        VerificationArtifactDirectoryAccess(paths, hooks),
    )
    private val cleaner = VerificationArtifactCleaner(maintenance)

    fun prepare(
        session: SessionDto,
        receiptId: String,
        source: SnapshotScreenshotDto?,
    ): PreparedVerificationArtifact = operationLocks.withReceiptLock(session.sessionId, receiptId) {
        requireSession(session)
        VerificationArtifactNaming.validateReceiptId(receiptId)
        val ownershipToken = temporaryId()
        VerificationArtifactNaming.validateToken(ownershipToken)
        val preparedWithoutSource = PreparedVerificationArtifact.issue(
            storeCapability = storeCapability,
            data = PreparedVerificationArtifactData(
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
            ),
        )
        try {
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
            preparedWithoutSource.withStagedFile(
                stagedFile = stagedFile,
                sourceMetadata = source.takeIf { sourceFile != null },
            )
        } catch (failure: Exception) {
            runCatching {
                fileSystem.deletePrepared(preparedWithoutSource, includeFinal = false)
            }
            throw artifactFailure("prepare", receiptId, failure)
        }
    }

    fun promote(
        prepared: PreparedVerificationArtifact,
    ): SnapshotScreenshotDto? = operationLocks.withReceiptLock(prepared.sessionId, prepared.receiptId) {
        var finalRollbackAuthorized = false
        try {
            requirePreparedArtifact(prepared)
            fileSystem.promote(prepared) {
                finalRollbackAuthorized = true
            }
            fileSystem.completePromotion(prepared)
            val promoted = prepared.sourceMetadata?.let { sourceMetadata ->
                hooks.beforePromotionResultConstruction(prepared.finalDirectory.toPath())
                sourceMetadata.copy(
                    fullPath = null,
                    cropPath = null,
                    desktopFullPath = prepared.finalDirectory
                        .resolve(VerificationArtifactNaming.AFTER_SCREENSHOT)
                        .canonicalPath,
                    desktopCropPath = null,
                )
            }
            finalRollbackAuthorized = false
            promoted
        } catch (failure: Exception) {
            if (prepared.belongsTo(storeCapability)) {
                runCatching {
                    fileSystem.deletePrepared(
                        prepared,
                        includeFinal = finalRollbackAuthorized,
                    )
                }
            }
            throw artifactFailure("promote", prepared.receiptId, failure)
        }
    }

    fun discard(prepared: PreparedVerificationArtifact) = operationLocks.withReceiptLock(
        prepared.sessionId,
        prepared.receiptId,
    ) {
        try {
            requirePreparedArtifact(prepared)
            fileSystem.deletePrepared(
                prepared,
                includeFinal = false,
            )
        } catch (failure: Exception) {
            throw artifactFailure("discard", prepared.receiptId, failure)
        }
    }

    fun deleteReceiptArtifact(session: SessionDto, receiptId: String) = operationLocks.withReceiptLock(
        session.sessionId,
        receiptId,
    ) {
        try {
            requireSession(session)
            VerificationArtifactNaming.validateReceiptId(receiptId)
            maintenance.deleteReceipt(session.sessionId, receiptId)
        } catch (failure: Exception) {
            throw artifactFailure("delete", receiptId, failure)
        }
    }

    fun cleanupIncomplete(): Int = operationLocks.withRootLock {
        try {
            cleaner.cleanupIncomplete()
        } catch (failure: Exception) {
            throw artifactFailure("clean incomplete", "all", failure)
        }
    }

    fun cleanupOrphans(referencesBySession: Map<String, Set<String>>): Int = operationLocks.withRootLock {
        try {
            cleaner.cleanupOrphans(referencesBySession)
        } catch (failure: Exception) {
            throw artifactFailure("clean orphan", "all", failure)
        }
    }

    private fun requireSession(session: SessionDto) {
        VerificationArtifactNaming.validateSessionId(session.sessionId)
        artifactRequire(File(session.projectRoot).canonicalFile == paths.projectRoot) {
            "Session project root does not match the verification artifact store"
        }
    }

    private fun requirePreparedArtifact(prepared: PreparedVerificationArtifact) {
        artifactRequire(prepared.belongsTo(storeCapability)) {
            "Verification artifact preparation belongs to another store"
        }
        VerificationArtifactNaming.validateSessionId(prepared.sessionId)
        VerificationArtifactNaming.validateReceiptId(prepared.receiptId)
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
