package io.github.beyondwin.fixthis.mcp.session.verification

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class VerificationArtifactEntry(
    val name: String,
    val directory: Boolean,
    val symbolicLink: Boolean,
)

internal class VerificationArtifactSecureFileSystem(
    private val paths: VerificationArtifactPaths,
    private val hooks: VerificationArtifactStoreHooks,
) {
    private val access = VerificationArtifactDirectoryAccess(paths, hooks)

    fun ensureVerificationRoot(sessionId: String): File {
        access.ensureVerificationRoot(sessionId)
        return paths.verificationRoot(sessionId)
    }

    fun reserve(
        sessionId: String,
        receiptId: String,
        ownershipToken: String,
    ) {
        ensureVerificationRoot(sessionId)
        access.withVerificationDirectory(sessionId) { verification ->
            access.files.createNewSyncedFile(
                verification,
                VerificationArtifactNaming.reservationName(receiptId),
                ownershipToken.toByteArray(),
            )
        }
        access.files.createTemporaryDirectory(
            sessionId,
            VerificationArtifactNaming.temporaryName(receiptId, ownershipToken),
        )
    }

    fun stagePng(
        sessionId: String,
        receiptId: String,
        ownershipToken: String,
        source: File,
    ): File {
        val temporaryName = VerificationArtifactNaming.temporaryName(receiptId, ownershipToken)
        val canonicalSource = canonicalSource(source)
        access.withSourceParent(canonicalSource.toPath()) { sourceParent, sourceName ->
            hooks.afterSourceParentOpened(sourceParent.absolute)
            access.assertBound(sourceParent)
            val sourceAttributes = access.attributes(sourceParent, sourceName)
            artifactRequire(sourceAttributes.isRegularFile && !sourceAttributes.isSymbolicLink) {
                "Verification screenshot source must be a regular file"
            }
            access.files.readFile(sourceParent, sourceName) { input ->
                access.withVerificationDirectory(sessionId) { verification ->
                    access.withChildDirectory(verification, temporaryName) { temporary ->
                        hooks.afterTemporaryDirectoryOpened(temporary.absolute)
                        access.assertBound(verification)
                        access.assertBound(temporary)
                        access.files.createNewSyncedFile(
                            temporary,
                            VerificationArtifactNaming.AFTER_SCREENSHOT,
                            input,
                        )
                        access.assertBound(sourceParent)
                        access.assertBound(temporary)
                    }
                }
            }
        }
        return paths.temporaryDirectory(sessionId, receiptId, ownershipToken)
            .resolve(VerificationArtifactNaming.AFTER_SCREENSHOT)
    }

    fun promote(
        prepared: PreparedVerificationArtifact,
        onMoved: () -> Unit,
    ) {
        artifactRequire(
            prepared.temporaryDirectory.name == VerificationArtifactNaming.temporaryName(
                prepared.receiptId,
                prepared.ownershipToken,
            ),
        ) {
            "Verification temporary directory is not bound to its receipt id"
        }
        access.withVerificationDirectory(prepared.sessionId) { verification ->
            hooks.beforePromotion()
            access.assertBound(verification)
            requireReservation(verification, prepared.receiptId, prepared.ownershipToken)
            access.withChildDirectory(verification, prepared.temporaryDirectory.name) { temporary ->
                access.assertBound(temporary)
                artifactRequire(!access.entryExists(verification, prepared.receiptId)) {
                    "Verification receipt destination already exists"
                }
            }
            access.files.moveDirectory(
                verification,
                prepared.temporaryDirectory.name,
                prepared.receiptId,
            )
            onMoved()
            access.withChildDirectory(verification, prepared.receiptId) { final ->
                access.assertBound(final)
            }
        }
    }

    fun completePromotion(prepared: PreparedVerificationArtifact) {
        access.withVerificationDirectory(prepared.sessionId) { verification ->
            requireReservation(verification, prepared.receiptId, prepared.ownershipToken)
            access.files.deleteFile(
                verification,
                VerificationArtifactNaming.reservationName(prepared.receiptId),
            )
        }
    }

    fun deletePrepared(
        prepared: PreparedVerificationArtifact,
        includeFinal: Boolean,
    ) {
        access.withVerificationDirectory(prepared.sessionId) { verification ->
            deleteVerificationEntryIfPresent(access, verification, prepared.temporaryDirectory.name)
            if (includeFinal) {
                deleteVerificationEntryIfPresent(access, verification, prepared.receiptId)
            }
            val reservationName = VerificationArtifactNaming.reservationName(prepared.receiptId)
            if (tokenMatches(verification, reservationName, prepared.ownershipToken)) {
                access.files.deleteFile(verification, reservationName)
            }
        }
    }

    private fun canonicalSource(source: File): File {
        artifactRequire(source.extension.lowercase() == "png") {
            "Verification screenshot source must be a .png file"
        }
        val absolute = source.toPath().toAbsolutePath().normalize()
        requireNoSymlinksToProjectRoot(absolute)
        artifactRequire(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "Verification screenshot source must be a regular file"
        }
        val canonical = absolute.toFile().canonicalFile
        artifactRequire(canonical.toPath().startsWith(access.projectRootPath)) {
            "Verification screenshot source escapes the canonical project root"
        }
        return canonical
    }

    private fun requireNoSymlinksToProjectRoot(target: Path) {
        var current: Path? = target
        while (current != null) {
            artifactRequire(!Files.isSymbolicLink(current)) {
                "Verification screenshot source path contains a symbolic link"
            }
            if (current.toFile().canonicalFile == paths.projectRoot) return
            current = current.parent
        }
        throw FeedbackVerificationArtifactException(
            "Verification screenshot source escapes the project root",
        )
    }

    private fun requireReservation(
        verification: VerificationDirectoryHandle,
        receiptId: String,
        ownershipToken: String,
    ) {
        artifactRequire(
            tokenMatches(
                verification,
                VerificationArtifactNaming.reservationName(receiptId),
                ownershipToken,
            ),
        ) {
            "Verification receipt reservation is not owned by this preparation"
        }
    }

    private fun tokenMatches(
        directory: VerificationDirectoryHandle,
        name: String,
        ownershipToken: String,
    ): Boolean = runCatching {
        access.files.readFile(directory, name) { channel ->
            val bytes = ByteArray(MAX_OWNERSHIP_TOKEN_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            val count = channel.read(buffer)
            String(bytes, 0, count) == ownershipToken && channel.read(ByteBuffer.allocate(1)) == -1
        }
    }.getOrDefault(false)

    private companion object {
        const val MAX_OWNERSHIP_TOKEN_BYTES = 64
    }
}

private fun deleteVerificationEntryIfPresent(
    access: VerificationArtifactDirectoryAccess,
    verification: VerificationDirectoryHandle,
    name: String,
) {
    if (access.entryExists(verification, name)) {
        access.files.deleteEntryRecursively(verification, name)
    }
}
