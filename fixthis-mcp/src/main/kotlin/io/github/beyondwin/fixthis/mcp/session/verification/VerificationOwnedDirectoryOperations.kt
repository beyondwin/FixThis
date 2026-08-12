package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal fun createOwnedDirectoryChild(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    name: String,
): Any {
    access.assertBound(parent)
    val target = parent.absolute.resolve(name)
    if (parent.stream == null) {
        hooks.beforeFallbackDirectoryCreate(target)
        access.assertBound(parent)
    }
    Files.createDirectory(target)
    val fileKey = access.attributes(parent, name).fileKey()
        ?: throw FeedbackVerificationArtifactException("Verification artifacts require stable child directory file keys")
    access.assertBound(parent)
    return fileKey
}

internal fun deleteOwnedDirectoryIfPresent(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    name: String,
    fileKey: Any,
) {
    if (!access.entryExists(parent, name)) return
    artifactRequire(isOwnedDirectory(access.attributes(parent, name), fileKey)) {
        "Verification capture directory ownership changed before atomic cleanup claim"
    }
    val source = parent.absolute.resolve(name)
    hooks.afterOwnedDirectoryIdentityValidatedBeforeDelete(source)
    access.assertBound(parent)
    val claimedName = hooks.captureCleanupClaimName()
    VerificationArtifactNaming.validateSegment(claimedName, "capture cleanup claim")
    val quarantineFileKey = access.files.createTemporaryDirectoryChild(parent, claimedName)
    val claimedChildName = "owned"
    var claimedChildPresent = false
    runCatching {
        access.withChildDirectory(parent, claimedName) { quarantine ->
            artifactRequire(quarantine.fileKey == quarantineFileKey) {
                "Verification capture cleanup quarantine ownership changed after creation"
            }
            claimOwnedDirectory(
                context = CleanupClaimContext(access, hooks, parent, quarantine),
                sourceName = name,
                claimedName = claimedChildName,
            )
            claimedChildPresent = true
            if (!isOwnedDirectory(access.attributes(quarantine, claimedChildName), fileKey)) {
                throw FeedbackVerificationArtifactException(
                    "Verification capture directory ownership changed during cleanup claim; " +
                        "the unowned entry remains recoverable at ${quarantine.absolute.resolve(claimedChildName)}",
                )
            }
            hooks.afterOwnedDirectoryClaimedBeforeTraversal(source, quarantine.absolute.resolve(claimedChildName))
            access.withChildDirectory(quarantine, claimedChildName) { claimed ->
                artifactRequire(claimed.fileKey == fileKey) {
                    "Verification capture directory ownership changed during cleanup claim"
                }
                deleteOwnedDirectoryContents(access, claimed)
                access.assertBound(claimed)
            }
            deleteClaimedDirectory(access, hooks, quarantine, claimedChildName, fileKey)
            claimedChildPresent = false
        }
        deleteEmptyQuarantine(access, hooks, parent, claimedName, quarantineFileKey)
    }.onFailure {
        if (!claimedChildPresent) {
            runCatching { deleteEmptyQuarantine(access, hooks, parent, claimedName, quarantineFileKey) }
        }
    }.getOrThrow()
}

private data class CleanupClaimContext(
    val access: VerificationArtifactDirectoryAccess,
    val hooks: VerificationArtifactStoreHooks,
    val sourceParent: VerificationDirectoryHandle,
    val quarantine: VerificationDirectoryHandle,
)

private fun claimOwnedDirectory(
    context: CleanupClaimContext,
    sourceName: String,
    claimedName: String,
) {
    val access = context.access
    val hooks = context.hooks
    val sourceParent = context.sourceParent
    val quarantine = context.quarantine
    access.assertBound(sourceParent)
    access.assertBound(quarantine)
    val source = sourceParent.absolute.resolve(sourceName)
    val target = quarantine.absolute.resolve(claimedName)
    if (sourceParent.stream != null && quarantine.stream != null) {
        sourceParent.stream.move(Path.of(sourceName), quarantine.stream, Path.of(claimedName))
    } else {
        hooks.beforeFallbackMove(source, target)
        access.assertBound(sourceParent)
        access.assertBound(quarantine)
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }
    access.assertBound(sourceParent)
    access.assertBound(quarantine)
}

private fun deleteClaimedDirectory(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    quarantine: VerificationDirectoryHandle,
    claimedName: String,
    fileKey: Any,
) {
    artifactRequire(isOwnedDirectory(access.attributes(quarantine, claimedName), fileKey)) {
        "Verification capture cleanup claim changed before unlink"
    }
    if (quarantine.stream != null) {
        quarantine.stream.deleteDirectory(Path.of(claimedName))
    } else {
        val claimed = quarantine.absolute.resolve(claimedName)
        hooks.beforeFallbackDelete(claimed)
        access.assertBound(quarantine)
        Files.delete(claimed)
    }
    access.assertBound(quarantine)
}

private fun deleteEmptyQuarantine(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    quarantineName: String,
    quarantineFileKey: Any,
) {
    if (!access.entryExists(parent, quarantineName)) return
    access.withChildDirectory(parent, quarantineName) { quarantine ->
        artifactRequire(quarantine.fileKey == quarantineFileKey) {
            "Verification capture cleanup quarantine ownership changed before unlink"
        }
        artifactRequire(access.entryNames(quarantine).isEmpty()) {
            "Verification capture cleanup quarantine retained a recoverable claim"
        }
    }
    artifactRequire(isOwnedDirectory(access.attributes(parent, quarantineName), quarantineFileKey)) {
        "Verification capture cleanup quarantine ownership changed before unlink"
    }
    if (parent.stream != null) {
        parent.stream.deleteDirectory(Path.of(quarantineName))
    } else {
        val quarantine = parent.absolute.resolve(quarantineName)
        hooks.beforeFallbackDelete(quarantine)
        access.assertBound(parent)
        Files.delete(quarantine)
    }
    access.assertBound(parent)
}

private fun deleteOwnedDirectoryContents(
    access: VerificationArtifactDirectoryAccess,
    directory: VerificationDirectoryHandle,
) {
    access.entryNames(directory).forEach { child ->
        access.files.deleteEntryRecursively(directory, child)
    }
}

private fun isOwnedDirectory(attributes: BasicFileAttributes, fileKey: Any): Boolean = attributes.isDirectory &&
    !attributes.isSymbolicLink &&
    attributes.fileKey() == fileKey
