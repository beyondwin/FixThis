package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal data class PreparedVerificationArtifact(
    val sessionId: String,
    val receiptId: String,
    internal val temporaryDirectory: File,
    internal val finalDirectory: File,
    internal val stagedFile: File?,
    internal val sourceMetadata: SnapshotScreenshotDto?,
)

@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
internal class FeedbackVerificationArtifactStore(
    projectRoot: File,
    private val temporaryId: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
) {
    private val projectRoot = projectRoot.canonicalFile
    private val projectRootPath = this.projectRoot.toPath()
    private val feedbackRoot = this.projectRoot.resolve(FEEDBACK_ROOT_RELATIVE)

    fun prepare(
        session: SessionDto,
        receiptId: String,
        source: SnapshotScreenshotDto?,
    ): PreparedVerificationArtifact {
        var temporaryDirectory: File? = null
        return try {
            requireSession(session)
            validateSegment(receiptId, "receiptId")
            val verificationRoot = ensureVerificationRoot(session.sessionId)
            val finalDirectory = safeChild(verificationRoot, receiptId)
            require(!existsNoFollow(finalDirectory.toPath())) {
                "Verification receipt artifact already exists: $receiptId"
            }
            temporaryDirectory = createTemporaryDirectory(verificationRoot, receiptId)
            val sourceFile = sourceFileOrNull(source)
            val stagedFile = sourceFile?.let {
                copyPngDurably(
                    source = it,
                    destination = temporaryDirectory.resolve(AFTER_SCREENSHOT_NAME),
                    verificationRoot = verificationRoot,
                )
            }
            PreparedVerificationArtifact(
                sessionId = session.sessionId,
                receiptId = receiptId,
                temporaryDirectory = temporaryDirectory,
                finalDirectory = finalDirectory,
                stagedFile = stagedFile,
                sourceMetadata = source.takeIf { sourceFile != null },
            )
        } catch (failure: Exception) {
            temporaryDirectory?.let(::deleteTreeNoFollow)
            throw artifactFailure("prepare", receiptId, failure)
        }
    }

    fun promote(prepared: PreparedVerificationArtifact): SnapshotScreenshotDto? = try {
        val verificationRoot = requirePreparedArtifact(prepared)
        requireSafeDirectory(prepared.temporaryDirectory, verificationRoot)
        prepared.stagedFile?.let { stagedFile ->
            require(stagedFile.name == AFTER_SCREENSHOT_NAME) {
                "Verification screenshot must use the canonical artifact name"
            }
            requireRegularPng(stagedFile, verificationRoot)
        }
        require(!existsNoFollow(prepared.finalDirectory.toPath())) {
            "Verification receipt destination changed before promotion"
        }
        try {
            Files.move(
                prepared.temporaryDirectory.toPath(),
                prepared.finalDirectory.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                prepared.temporaryDirectory.toPath(),
                prepared.finalDirectory.toPath(),
            )
        }
        requireSafeDirectory(prepared.finalDirectory, verificationRoot)
        val promotedFile = prepared.stagedFile?.let {
            prepared.finalDirectory.resolve(AFTER_SCREENSHOT_NAME).also { finalFile ->
                requireRegularPng(finalFile, verificationRoot)
            }
        }
        prepared.sourceMetadata?.copy(
            fullPath = null,
            cropPath = null,
            desktopFullPath = promotedFile?.canonicalPath,
            desktopCropPath = null,
        )
    } catch (failure: Exception) {
        rollbackPreparedArtifact(prepared)
        throw artifactFailure("promote", prepared.receiptId, failure)
    }

    fun discard(prepared: PreparedVerificationArtifact) {
        try {
            requirePreparedArtifact(prepared)
            deleteTreeNoFollow(prepared.temporaryDirectory)
        } catch (failure: Exception) {
            throw artifactFailure("discard", prepared.receiptId, failure)
        }
    }

    fun deleteReceiptArtifact(session: SessionDto, receiptId: String) {
        try {
            requireSession(session)
            validateSegment(receiptId, "receiptId")
            val finalDirectory = safeChild(verificationRoot(session.sessionId), receiptId)
            deleteTreeNoFollow(finalDirectory)
        } catch (failure: Exception) {
            throw artifactFailure("delete", receiptId, failure)
        }
    }

    fun cleanupIncomplete(): Int = try {
        var deleted = 0
        existingSessionDirectories().forEach { sessionDirectory ->
            val verificationRoot = sessionDirectory.resolve(VERIFICATION_DIRECTORY_NAME)
            if (!existsNoFollow(verificationRoot.toPath())) return@forEach
            if (Files.isSymbolicLink(verificationRoot.toPath())) {
                deleteTreeNoFollow(verificationRoot)
                deleted += 1
                return@forEach
            }
            requireSafeDirectory(verificationRoot, feedbackRoot)
            verificationRoot.listFiles().orEmpty()
                .filter { isTemporaryDirectoryName(it.name) }
                .forEach { temporary ->
                    deleteTreeNoFollow(temporary)
                    deleted += 1
                }
        }
        deleted
    } catch (failure: Exception) {
        throw artifactFailure("clean incomplete", "all", failure)
    }

    fun cleanupOrphans(referencesBySession: Map<String, Set<String>>): Int = try {
        referencesBySession.forEach { (sessionId, receiptIds) ->
            validateSegment(sessionId, "sessionId")
            receiptIds.forEach { validateSegment(it, "receiptId") }
        }
        var deleted = 0
        existingSessionDirectories().forEach { sessionDirectory ->
            val references = referencesBySession[sessionDirectory.name].orEmpty()
            deleted += cleanupSessionOrphans(sessionDirectory, references)
        }
        deleted
    } catch (failure: Exception) {
        throw artifactFailure("clean orphan", "all", failure)
    }

    private fun requireSession(session: SessionDto) {
        validateSegment(session.sessionId, "sessionId")
        require(File(session.projectRoot).canonicalFile == projectRoot) {
            "Session project root does not match the verification artifact store"
        }
    }

    private fun ensureVerificationRoot(sessionId: String): File {
        val sessionDirectory = safeChild(feedbackRoot, sessionId)
        val verificationRoot = safeChild(sessionDirectory, VERIFICATION_DIRECTORY_NAME)
        listOf(
            projectRoot.resolve(".fixthis"),
            feedbackRoot,
            sessionDirectory,
            verificationRoot,
        ).forEach(::ensureDirectory)
        return verificationRoot
    }

    private fun verificationRoot(sessionId: String): File = safeChild(
        safeChild(feedbackRoot, sessionId),
        VERIFICATION_DIRECTORY_NAME,
    )

    private fun ensureDirectory(directory: File) {
        requireWithinProject(directory)
        require(!Files.isSymbolicLink(directory.toPath())) {
            "Verification artifact path contains a symbolic link"
        }
        if (!existsNoFollow(directory.toPath())) {
            Files.createDirectory(directory.toPath())
        }
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Verification artifact path must be a real directory"
        }
        require(!Files.isSymbolicLink(directory.toPath())) {
            "Verification artifact path contains a symbolic link"
        }
        requireWithinProject(directory)
    }

    private fun createTemporaryDirectory(verificationRoot: File, receiptId: String): File {
        val id = temporaryId()
        require(id.matches(TEMPORARY_ID_PATTERN)) {
            "Verification artifact temporary id must contain exactly 32 lowercase hex characters"
        }
        return safeChild(verificationRoot, ".$receiptId.tmp-$id").also { temporary ->
            require(!existsNoFollow(temporary.toPath())) {
                "Verification artifact temporary directory already exists"
            }
            Files.createDirectory(temporary.toPath())
            requireSafeDirectory(temporary, verificationRoot)
        }
    }

    private fun sourceFileOrNull(source: SnapshotScreenshotDto?): File? {
        val sourcePath = source?.desktopFullPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val sourceFile = File(sourcePath)
        require(sourceFile.extension.lowercase() == "png") {
            "Verification screenshot source must be a .png file"
        }
        val normalized = sourceFile.toPath().toAbsolutePath().normalize()
        requireNoSymlinksToProjectRoot(normalized)
        require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "Verification screenshot source must be a regular file"
        }
        val canonicalSource = normalized.toFile().canonicalFile
        require(canonicalSource.toPath().startsWith(projectRootPath)) {
            "Verification screenshot source escapes the canonical project root"
        }
        requireNoSymlinks(projectRootPath, canonicalSource.toPath())
        return canonicalSource
    }

    private fun copyPngDurably(
        source: File,
        destination: File,
        verificationRoot: File,
    ): File {
        requireSafeDirectory(destination.parentFile, verificationRoot)
        require(!existsNoFollow(destination.toPath())) {
            "Verification screenshot destination already exists"
        }
        Files.createFile(destination.toPath())
        requireRegularPng(destination, verificationRoot)
        Files.newInputStream(
            source.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        requireNoSymlinks(projectRootPath, source.toPath())
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Verification screenshot source changed during staging"
        }
        requireRegularPng(destination, verificationRoot)
        return destination
    }

    private fun requirePreparedArtifact(prepared: PreparedVerificationArtifact): File {
        validateSegment(prepared.sessionId, "sessionId")
        validateSegment(prepared.receiptId, "receiptId")
        val verificationRoot = verificationRoot(prepared.sessionId)
        val expectedFinal = safeChild(verificationRoot, prepared.receiptId)
        require(prepared.finalDirectory.toPath().toAbsolutePath().normalize() == expectedFinal.toPath()) {
            "Verification receipt destination does not match its ids"
        }
        require(
            prepared.temporaryDirectory.parentFile.toPath().toAbsolutePath().normalize() ==
                verificationRoot.toPath(),
        ) {
            "Verification temporary directory is outside its receipt root"
        }
        require(isTemporaryDirectoryName(prepared.temporaryDirectory.name)) {
            "Verification temporary directory has an invalid name"
        }
        prepared.stagedFile?.let { staged ->
            require(staged.parentFile.toPath() == prepared.temporaryDirectory.toPath()) {
                "Verification staged file is outside its temporary directory"
            }
        }
        return verificationRoot
    }

    private fun rollbackPreparedArtifact(prepared: PreparedVerificationArtifact) {
        if (
            runCatching { validateSegment(prepared.sessionId, "sessionId") }.isFailure ||
            runCatching { validateSegment(prepared.receiptId, "receiptId") }.isFailure
        ) {
            return
        }
        val verificationRoot = verificationRoot(prepared.sessionId)
        val expectedFinal = safeChild(verificationRoot, prepared.receiptId)
        val normalizedVerificationRoot = verificationRoot.toPath().toAbsolutePath().normalize()
        val normalizedTemporary = prepared.temporaryDirectory.toPath().toAbsolutePath().normalize()
        val normalizedFinal = prepared.finalDirectory.toPath().toAbsolutePath().normalize()
        if (
            normalizedTemporary.parent == normalizedVerificationRoot &&
            isTemporaryDirectoryName(normalizedTemporary.fileName.toString())
        ) {
            runCatching { deleteTreeNoFollow(prepared.temporaryDirectory) }
        }
        if (normalizedFinal == expectedFinal.toPath()) {
            runCatching { deleteTreeNoFollow(prepared.finalDirectory) }
        }
    }

    private fun requireRegularPng(file: File, verificationRoot: File) {
        require(file.extension.lowercase() == "png") {
            "Verification artifact must be a .png file"
        }
        requireNoSymlinks(verificationRoot.toPath(), file.toPath())
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Verification artifact must be a regular file"
        }
        require(file.canonicalFile.toPath().startsWith(verificationRoot.canonicalFile.toPath())) {
            "Verification artifact escapes its receipt root"
        }
    }

    private fun requireSafeDirectory(directory: File, containmentRoot: File) {
        requireNoSymlinks(containmentRoot.toPath(), directory.toPath())
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Verification artifact directory must be a real directory"
        }
        require(directory.canonicalFile.toPath().startsWith(containmentRoot.canonicalFile.toPath())) {
            "Verification artifact directory escapes its storage root"
        }
    }

    private fun requireNoSymlinks(root: Path, target: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        require(normalizedTarget.startsWith(normalizedRoot)) {
            "Verification artifact path escapes its storage root"
        }
        var current = normalizedRoot
        require(!Files.isSymbolicLink(current)) {
            "Verification artifact path contains a symbolic link"
        }
        normalizedRoot.relativize(normalizedTarget).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "Verification artifact path contains a symbolic link"
            }
        }
    }

    private fun requireNoSymlinksToProjectRoot(target: Path) {
        var current: Path? = target
        while (current != null) {
            require(!Files.isSymbolicLink(current)) {
                "Verification screenshot source path contains a symbolic link"
            }
            if (current.toFile().canonicalFile == projectRoot) return
            current = current.parent
        }
        error("Verification screenshot source escapes the project root")
    }

    private fun safeChild(parent: File, segment: String): File {
        val child = parent.toPath().resolve(segment).toAbsolutePath().normalize()
        val normalizedParent = parent.toPath().toAbsolutePath().normalize()
        require(child.parent == normalizedParent) {
            "Verification artifact path is not a single child segment"
        }
        require(child.startsWith(projectRootPath)) {
            "Verification artifact path escapes the project root"
        }
        return child.toFile()
    }

    private fun requireWithinProject(file: File) {
        val normalized = file.toPath().toAbsolutePath().normalize()
        require(normalized.startsWith(projectRootPath)) {
            "Verification artifact path escapes the project root"
        }
        require(file.canonicalFile.toPath().startsWith(projectRootPath)) {
            "Verification artifact path escapes the canonical project root"
        }
    }

    private fun existingSessionDirectories(): List<File> {
        if (!existsNoFollow(feedbackRoot.toPath())) return emptyList()
        requireSafeDirectory(feedbackRoot, projectRoot)
        return feedbackRoot.listFiles().orEmpty().filter { sessionDirectory ->
            !Files.isSymbolicLink(sessionDirectory.toPath()) &&
                Files.isDirectory(sessionDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun cleanupSessionOrphans(
        sessionDirectory: File,
        references: Set<String>,
    ): Int {
        val verificationRoot = sessionDirectory.resolve(VERIFICATION_DIRECTORY_NAME)
        if (!existsNoFollow(verificationRoot.toPath())) return 0
        return if (Files.isSymbolicLink(verificationRoot.toPath())) {
            deleteTreeNoFollow(verificationRoot)
            1
        } else {
            requireSafeDirectory(verificationRoot, feedbackRoot)
            var deleted = 0
            verificationRoot.listFiles().orEmpty().forEach { artifact ->
                when {
                    isTemporaryDirectoryName(artifact.name) -> Unit
                    artifact.name !in references ||
                        Files.isSymbolicLink(artifact.toPath()) ||
                        !Files.isDirectory(artifact.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                        deleteTreeNoFollow(artifact)
                        deleted += 1
                    }
                    else -> deleted += pruneSymlinks(artifact)
                }
            }
            deleted
        }
    }

    private fun pruneSymlinks(directory: File): Int {
        requireSafeDirectory(directory, feedbackRoot)
        var deleted = 0
        directory.listFiles().orEmpty().forEach { child ->
            when {
                Files.isSymbolicLink(child.toPath()) -> {
                    deleteTreeNoFollow(child)
                    deleted += 1
                }
                Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                    deleted += pruneSymlinks(child)
                }
            }
        }
        return deleted
    }

    private fun deleteTreeNoFollow(entry: File) {
        val path = entry.toPath()
        if (!existsNoFollow(path)) return
        require(path.toAbsolutePath().normalize().startsWith(projectRootPath)) {
            "Refusing to delete a verification artifact outside the project root"
        }
        if (Files.isSymbolicLink(path)) {
            Files.delete(path)
            return
        }
        require(entry.canonicalFile.toPath().startsWith(projectRootPath)) {
            "Refusing to delete a verification artifact outside the canonical project root"
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            entry.listFiles().orEmpty().forEach(::deleteTreeNoFollow)
        }
        Files.deleteIfExists(path)
    }

    private fun validateSegment(value: String, label: String) {
        require(
            value.length in 1..MAX_SEGMENT_LENGTH &&
                SAFE_SEGMENT.matches(value) &&
                value != "." &&
                value != "..",
        ) {
            "$label must be one safe path segment"
        }
    }

    private fun artifactFailure(operation: String, receiptId: String, failure: Exception): FeedbackSessionException = if (failure is FeedbackSessionException) {
        failure
    } else {
        FeedbackSessionException(
            "VERIFICATION_ARTIFACT_FAILED: Could not $operation receipt artifact $receiptId: ${failure.message}",
        )
    }

    private fun isTemporaryDirectoryName(name: String): Boolean = TEMPORARY_DIRECTORY_PATTERN.matches(name)

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private companion object {
        const val FEEDBACK_ROOT_RELATIVE = ".fixthis/feedback-sessions"
        const val VERIFICATION_DIRECTORY_NAME = "verification"
        const val AFTER_SCREENSHOT_NAME = "after.png"
        const val MAX_SEGMENT_LENGTH = 128
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
        val TEMPORARY_ID_PATTERN = Regex("[0-9a-f]{32}")
        val TEMPORARY_DIRECTORY_PATTERN = Regex(
            "\\.[A-Za-z0-9._-]{1,128}\\.tmp-[0-9a-f]{32}",
        )
    }
}
