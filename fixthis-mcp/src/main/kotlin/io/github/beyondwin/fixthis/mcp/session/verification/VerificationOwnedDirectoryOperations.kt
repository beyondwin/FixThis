package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.file.Files

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
    hooks.afterDirectoryCreateIdentityCaptured(target)
    access.assertBound(parent)
    return fileKey
}

internal fun deleteOwnedDirectoryIfPresent(
    access: VerificationArtifactDirectoryAccess,
    parent: VerificationDirectoryHandle,
    name: String,
    fileKey: Any,
) {
    if (!access.entryExists(parent, name)) return
    val attributes = access.attributes(parent, name)
    if (attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() == fileKey) {
        access.files.deleteEntryRecursively(parent, name)
    }
}
