package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

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
    if (parent.stream == null) {
        deleteAtomicallyClaimedOwnedDirectory(access, hooks, parent, name, fileKey)
    } else {
        deleteSecureOwnedDirectory(access, hooks, parent, name, fileKey)
    }
}

private fun deleteSecureOwnedDirectory(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    name: String,
    fileKey: Any,
) {
    val secureParent = checkNotNull(parent.stream)
    access.withChildDirectory(parent, name) { directory ->
        artifactRequire(directory.fileKey == fileKey) {
            "Verification capture directory ownership changed before deletion"
        }
        hooks.afterOwnedDirectoryIdentityValidatedBeforeDelete(directory.absolute)
        access.assertBound(directory)
        deleteOwnedDirectoryContents(access, directory)
        access.assertBound(directory)
        val current = access.attributes(parent, name)
        artifactRequire(isOwnedDirectory(current, fileKey)) {
            "Verification capture directory ownership changed before unlink"
        }
        access.assertBound(directory)
        secureParent.deleteDirectory(Path.of(name))
        access.assertBound(parent)
    }
}

private fun deleteAtomicallyClaimedOwnedDirectory(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    name: String,
    fileKey: Any,
) {
    artifactRequire(isOwnedDirectory(access.attributes(parent, name), fileKey)) {
        "Verification capture directory ownership changed before atomic cleanup claim"
    }
    val source = parent.absolute.resolve(name)
    hooks.afterOwnedDirectoryIdentityValidatedBeforeDelete(source)
    access.assertBound(parent)
    val claimedName = ".capture-cleanup-${UUID.randomUUID().toString().replace("-", "")}"
    VerificationArtifactNaming.validateSegment(claimedName, "capture cleanup claim")
    artifactRequire(!access.entryExists(parent, claimedName)) {
        "Verification capture cleanup claim already exists"
    }
    val claimed = parent.absolute.resolve(claimedName)
    hooks.beforeFallbackMove(source, claimed)
    access.assertBound(parent)
    Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE)
    access.assertBound(parent)
    val claimedAttributes = access.attributes(parent, claimedName)
    if (!isOwnedDirectory(claimedAttributes, fileKey)) {
        restoreUnownedAtomicClaim(access, hooks, parent, name, claimedName)
        throw FeedbackVerificationArtifactException(
            "Verification capture directory ownership changed during atomic cleanup claim",
        )
    }
    access.withChildDirectory(parent, claimedName) { directory ->
        artifactRequire(directory.fileKey == fileKey) {
            "Verification capture directory ownership changed after atomic cleanup claim"
        }
        deleteOwnedDirectoryContents(access, directory)
        hooks.beforeFallbackDelete(claimed)
        access.assertBound(directory)
        artifactRequire(isOwnedDirectory(access.attributes(parent, claimedName), fileKey)) {
            "Verification capture directory ownership changed before unlink"
        }
        Files.delete(claimed)
        access.assertBound(parent)
    }
}

private fun restoreUnownedAtomicClaim(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    originalName: String,
    claimedName: String,
) {
    if (access.entryExists(parent, originalName)) return
    val claimed = parent.absolute.resolve(claimedName)
    val original = parent.absolute.resolve(originalName)
    hooks.beforeFallbackMove(claimed, original)
    access.assertBound(parent)
    Files.move(claimed, original, StandardCopyOption.ATOMIC_MOVE)
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
