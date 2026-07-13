package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal object RuntimeEvidencePrivatePermissions {
    private val directoryPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
    private val filePermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    private val directoryAttribute = PosixFilePermissions.asFileAttribute(directoryPermissions)
    private val fileAttribute = PosixFilePermissions.asFileAttribute(filePermissions)

    fun createDirectory(path: Path) {
        if (supportsPosix(path.parent)) {
            Files.createDirectory(path, directoryAttribute)
        } else {
            Files.createDirectory(path)
            restrictWithFileApi(path.toFile(), directory = true)
        }
        tightenDirectory(path)
    }

    fun openFile(path: Path, options: Set<OpenOption>): FileChannel {
        val channel = if (supportsPosix(path.parent)) {
            FileChannel.open(path, options, fileAttribute)
        } else {
            FileChannel.open(path, options)
        }
        runCatching { tightenFile(path) }
            .onFailure { channel.close() }
            .getOrThrow()
        return channel
    }

    fun tightenDirectory(path: Path) {
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Runtime-evidence private path must be a real directory"
        }
        if (supportsPosix(path)) {
            Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).setPermissions(directoryPermissions)
        } else {
            restrictWithFileApi(path.toFile(), directory = true)
        }
    }

    fun tightenFile(path: Path) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Runtime-evidence private path must be a real file"
        }
        if (supportsPosix(path)) {
            Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).setPermissions(filePermissions)
        } else {
            restrictWithFileApi(path.toFile(), directory = false)
        }
    }

    fun tightenTreeNoFollow(root: Path) {
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                when {
                    Files.isSymbolicLink(path) -> Unit
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> tightenDirectory(path)
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> tightenFile(path)
                }
            }
        }
    }

    private fun supportsPosix(path: Path): Boolean = Files.getFileAttributeView(
        path,
        PosixFileAttributeView::class.java,
        LinkOption.NOFOLLOW_LINKS,
    ) != null

    private fun restrictWithFileApi(file: File, directory: Boolean) {
        require(file.setReadable(false, false)) { "Unable to remove inherited runtime-evidence read permissions" }
        require(file.setWritable(false, false)) { "Unable to remove inherited runtime-evidence write permissions" }
        require(file.setExecutable(false, false)) { "Unable to remove inherited runtime-evidence execute permissions" }
        require(file.setReadable(true, true)) { "Unable to grant owner runtime-evidence read permission" }
        require(file.setWritable(true, true)) { "Unable to grant owner runtime-evidence write permission" }
        if (directory) {
            require(file.setExecutable(true, true)) { "Unable to grant owner runtime-evidence directory access" }
        }
    }
}

internal class RuntimeEvidenceArtifactPathGuard(
    projectRoot: File,
    private val evidenceRoot: File,
) {
    private val projectRoot = projectRoot.canonicalFile

    fun ensureWithinProject(file: File) {
        require(file.canonicalFile.toPath().startsWith(projectRoot.toPath())) {
            "Runtime-evidence path escapes the project root"
        }
    }

    fun ensureWithinEvidenceRoot(file: File) {
        val canonicalRoot = evidenceRoot.canonicalFile
        require(canonicalRoot.toPath().startsWith(projectRoot.toPath())) {
            "Runtime-evidence root escapes the project"
        }
        require(file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            "Runtime-evidence path escapes its storage root"
        }
    }

    fun requireSafeArtifactParent(parent: File) {
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) {
            "Runtime-evidence artifact parent changed or became a symlink"
        }
        ensureWithinEvidenceRoot(parent)
    }

    fun requireSafeArtifactDirectory(directory: File) {
        requireSafeArtifactParent(directory)
    }
}

internal class RuntimeEvidenceArtifactFileSystem(
    projectRoot: File,
    private val evidenceRoot: File,
    private val fileSizeReader: ((Path) -> Long)? = null,
) {
    private val projectRoot = projectRoot.canonicalFile
    val pathGuard = RuntimeEvidenceArtifactPathGuard(this.projectRoot, evidenceRoot)

    fun ensureDirectory(directory: File): File {
        require(!Files.isSymbolicLink(directory.toPath())) { "Runtime-evidence storage must not be a symlink" }
        if (!directory.exists()) {
            val parent = directory.parentFile
            if (parent != projectRoot) ensureDirectory(parent)
            RuntimeEvidencePrivatePermissions.createDirectory(directory.toPath())
        }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Runtime-evidence storage must be a real directory"
        }
        pathGuard.ensureWithinProject(directory)
        if (directory == evidenceRoot) {
            RuntimeEvidencePrivatePermissions.tightenTreeNoFollow(directory.toPath())
        } else {
            RuntimeEvidencePrivatePermissions.tightenDirectory(directory.toPath())
        }
        return directory
    }

    fun ensureSafeChild(parent: File, name: String, create: Boolean): File {
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "Unsafe runtime-evidence parent" }
        val child = File(parent, name)
        pathGuard.ensureWithinProject(child)
        require(!Files.isSymbolicLink(child.toPath())) { "Runtime-evidence paths must not be symlinks" }
        if (create && !child.exists()) RuntimeEvidencePrivatePermissions.createDirectory(child.toPath())
        if (child.exists()) {
            require(child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                "Runtime-evidence paths must be real directories"
            }
            pathGuard.ensureWithinProject(child)
            RuntimeEvidencePrivatePermissions.tightenDirectory(child.toPath())
        }
        return child
    }

    fun existingEvidenceRoot(): File? {
        if (!evidenceRoot.exists() && !Files.isSymbolicLink(evidenceRoot.toPath())) return null
        require(!Files.isSymbolicLink(evidenceRoot.toPath())) { "Runtime-evidence storage must not be a symlink" }
        require(evidenceRoot.isDirectory) { "Runtime-evidence storage must be a directory" }
        pathGuard.ensureWithinProject(evidenceRoot)
        RuntimeEvidencePrivatePermissions.tightenTreeNoFollow(evidenceRoot.toPath())
        return evidenceRoot
    }

    fun existingChildNoFollow(parent: File, name: String): File? {
        val child = File(parent, name)
        if (!child.exists() && !Files.isSymbolicLink(child.toPath())) return null
        if (!Files.isSymbolicLink(child.toPath())) {
            pathGuard.ensureWithinProject(child)
            require(child.isDirectory) { "Runtime-evidence bundle path must be a directory" }
        }
        return child
    }

    fun safeChildDirectories(parent: File): List<File> = parent.listFiles().orEmpty()
        .filter { child ->
            !Files.isSymbolicLink(child.toPath()) &&
                child.isDirectory &&
                runCatching { pathGuard.ensureWithinProject(child) }.isSuccess
        }

    fun directorySizeNoFollow(directory: File): Long {
        var total = 0L
        Files.walk(directory.toPath()).use { paths ->
            paths.forEach { path -> total = checkedFileTotal(total, path) }
        }
        return total
    }

    fun writeDurably(file: File, bytes: ByteArray, beforeWrite: (File) -> Unit) {
        require(file.parentFile.isDirectory && !Files.isSymbolicLink(file.parentFile.toPath())) {
            "Unsafe runtime-evidence artifact parent"
        }
        require(!file.exists()) { "Runtime-evidence artifact already exists: ${file.name}" }
        pathGuard.ensureWithinProject(file)
        beforeWrite(file)
        pathGuard.requireSafeArtifactParent(file.parentFile)
        pathGuard.ensureWithinEvidenceRoot(file)
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        RuntimeEvidencePrivatePermissions.openFile(file.toPath(), options).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    fun deleteTreeNoFollow(entry: File) {
        if (Files.isSymbolicLink(entry.toPath())) {
            Files.delete(entry.toPath())
            return
        }
        pathGuard.ensureWithinProject(entry)
        if (!entry.exists()) return
        entry.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath()) || child.isDirectory) {
                deleteTreeNoFollow(child)
            } else {
                require(child.delete()) { "Unable to delete runtime-evidence artifact: ${child.name}" }
            }
        }
        require(entry.delete()) { "Unable to delete runtime-evidence artifact: ${entry.name}" }
    }

    fun deleteIfEmpty(directory: File) {
        if (directory.isDirectory && directory.list().orEmpty().isEmpty()) directory.delete()
    }

    private fun checkedFileTotal(total: Long, path: Path): Long {
        require(!Files.isSymbolicLink(path)) { "Runtime-evidence quota scan rejected a symlink" }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return total
        val fileBytes = fileSizeReader?.invoke(path) ?: Files.size(path)
        return try {
            Math.addExact(total, fileBytes)
        } catch (cause: ArithmeticException) {
            throw RuntimeEvidenceArtifactQuotaException(
                "Runtime-evidence quota tree scan overflowed",
                cause,
            )
        }
    }
}

internal class RuntimeEvidenceArtifactCleaner(
    private val fileSystem: RuntimeEvidenceArtifactFileSystem,
) {
    fun deleteBundle(root: File, sessionId: String, captureId: String) {
        fileSystem.existingChildNoFollow(root, sessionId)?.let { sessionDirectory ->
            if (Files.isSymbolicLink(sessionDirectory.toPath())) {
                fileSystem.deleteTreeNoFollow(sessionDirectory)
                return
            }
            fileSystem.existingChildNoFollow(sessionDirectory, captureId)?.let(fileSystem::deleteTreeNoFollow)
            fileSystem.deleteIfEmpty(sessionDirectory)
        }
    }

    fun cleanupIncomplete(root: File): Int {
        var deleted = unlinkRootSymlinks(root)
        fileSystem.safeChildDirectories(root).forEach { sessionDirectory ->
            sessionDirectory.listFiles().orEmpty()
                .filter { RuntimeEvidenceArtifactNaming.isTemporaryBundle(it.name) }
                .forEach { temporary ->
                    fileSystem.deleteTreeNoFollow(temporary)
                    deleted += 1
                }
            fileSystem.deleteIfEmpty(sessionDirectory)
        }
        return deleted
    }

    fun cleanupOrphans(root: File, referencedCaptureIdsBySession: Map<String, Set<String>>): Int {
        var deleted = 0
        root.listFiles().orEmpty().forEach { sessionDirectory ->
            if (Files.isSymbolicLink(sessionDirectory.toPath())) {
                fileSystem.deleteTreeNoFollow(sessionDirectory)
                deleted += 1
                return@forEach
            }
            if (!sessionDirectory.isDirectory) return@forEach
            fileSystem.pathGuard.ensureWithinEvidenceRoot(sessionDirectory)
            val references = referencedCaptureIdsBySession[sessionDirectory.name].orEmpty()
            sessionDirectory.listFiles().orEmpty().forEach { orphan ->
                val symlink = Files.isSymbolicLink(orphan.toPath())
                if (!symlink && RuntimeEvidenceArtifactNaming.isTemporaryBundle(orphan.name)) {
                    return@forEach
                }
                if (!symlink && orphan.name in references) {
                    deleted += pruneSymlinkLeaves(orphan)
                    return@forEach
                }
                fileSystem.deleteTreeNoFollow(orphan)
                deleted += 1
            }
            fileSystem.deleteIfEmpty(sessionDirectory)
        }
        return deleted
    }

    fun deleteSession(root: File, sessionId: String) {
        fileSystem.existingChildNoFollow(root, sessionId)?.let(fileSystem::deleteTreeNoFollow)
    }

    private fun unlinkRootSymlinks(root: File): Int {
        var deleted = 0
        root.listFiles().orEmpty()
            .filter { Files.isSymbolicLink(it.toPath()) }
            .forEach { poisonedSession ->
                fileSystem.deleteTreeNoFollow(poisonedSession)
                deleted += 1
            }
        return deleted
    }

    private fun pruneSymlinkLeaves(directory: File): Int {
        fileSystem.pathGuard.ensureWithinEvidenceRoot(directory)
        var deleted = 0
        directory.listFiles().orEmpty().forEach { child ->
            when {
                Files.isSymbolicLink(child.toPath()) -> {
                    fileSystem.deleteTreeNoFollow(child)
                    deleted += 1
                }
                child.isDirectory -> deleted += pruneSymlinkLeaves(child)
            }
        }
        return deleted
    }
}

internal object RuntimeEvidenceArtifactNaming {
    const val EVIDENCE_ROOT_RELATIVE = ".fixthis/runtime-evidence"
    const val MANIFEST_FILE_NAME = "manifest.json"
    private const val MAX_SEGMENT_LENGTH = 128
    private val safeSegment = Regex("[A-Za-z0-9._-]+")
    private val temporaryBundle = Regex(".+\\.tmp-[0-9a-f]{32}")

    fun validateId(value: String, label: String) {
        require(value.length in 1..MAX_SEGMENT_LENGTH && safeSegment.matches(value) && value != "." && value != "..") {
            "$label must match [A-Za-z0-9._-]+ and must not be a traversal segment"
        }
        if (label == "captureId") {
            require(!isTemporaryBundle(value)) { "captureId collides with the reserved temporary-bundle format" }
        }
    }

    fun validateFileName(fileName: String) {
        validateId(fileName, "fileName")
        require(fileName != MANIFEST_FILE_NAME) { "$MANIFEST_FILE_NAME is reserved" }
    }

    fun isTemporaryBundle(name: String): Boolean = temporaryBundle.matches(name)

    fun relativeDirectory(sessionId: String, captureId: String): String = "$EVIDENCE_ROOT_RELATIVE/$sessionId/$captureId"
}
