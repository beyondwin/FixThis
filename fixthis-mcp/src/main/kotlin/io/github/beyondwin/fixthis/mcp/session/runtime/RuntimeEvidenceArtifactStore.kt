package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class RuntimeEvidenceArtifactInput(
    val type: RuntimeEvidenceType,
    val fileName: String,
    val redactedText: String,
)

data class CommittedRuntimeEvidenceBundle(
    val captureId: String,
    val relativeDirectory: String,
    val relativeFiles: Map<RuntimeEvidenceType, String>,
)

internal interface RuntimeEvidenceArtifactStore {
    fun commit(
        sessionId: String,
        captureId: String,
        inputs: List<RuntimeEvidenceArtifactInput>,
    ): CommittedRuntimeEvidenceBundle

    fun deleteBundle(sessionId: String, captureId: String)

    fun cleanupIncomplete(): Int

    fun cleanupOrphans(referencedCaptureIdsBySession: Map<String, Set<String>>): Int

    fun deleteSession(sessionId: String)
}

internal class RuntimeEvidenceArtifactLimitException(message: String) : IllegalArgumentException(message)

internal class RuntimeEvidenceArtifactQuotaException(message: String) : IllegalStateException(message)

internal data class RuntimeEvidenceArtifactStoreHooks(
    val beforeWrite: (File) -> Unit = {},
    val beforeAtomicMove: (File, File) -> Unit = { _, _ -> },
)

@Suppress("TooManyFunctions") // Filesystem containment and lifecycle helpers stay private to the atomic store.
internal class FileRuntimeEvidenceArtifactStore(
    projectRoot: File,
    private val redactor: RuntimeEvidenceRedactor,
    private val hooks: RuntimeEvidenceArtifactStoreHooks = RuntimeEvidenceArtifactStoreHooks(),
) : RuntimeEvidenceArtifactStore {
    private val projectRoot = projectRoot.canonicalFile
    private val evidenceRoot = File(this.projectRoot, EVIDENCE_ROOT_RELATIVE)

    @Suppress("TooGenericExceptionCaught") // Every write/move failure must trigger fail-closed temp cleanup.
    @Synchronized
    override fun commit(
        sessionId: String,
        captureId: String,
        inputs: List<RuntimeEvidenceArtifactInput>,
    ): CommittedRuntimeEvidenceBundle {
        validateId(sessionId, "sessionId")
        validateId(captureId, "captureId")
        val prepared = prepareInputs(inputs)
        val manifest = manifestBytes(captureId, prepared)
        val bundleBytes = prepared.sumOf { it.bytes.size.toLong() } + manifest.size
        if (bundleBytes > BUNDLE_LIMIT_BYTES) {
            throw RuntimeEvidenceArtifactLimitException(
                "Runtime-evidence bundle exceeds its $BUNDLE_LIMIT_BYTES-byte limit",
            )
        }
        ensureWithinProject(evidenceRoot)
        val root = ensureDirectory(evidenceRoot)
        val sessionDirectory = ensureSafeChild(root, sessionId, create = true)
        val finalDirectory = ensureSafeChild(sessionDirectory, captureId, create = false)
        require(!finalDirectory.exists()) { "Runtime-evidence bundle already exists: $captureId" }
        enforceProjectQuota(bundleBytes)

        val tempDirectory = ensureSafeChild(
            sessionDirectory,
            "$captureId.tmp-${UUID.randomUUID().toString().replace("-", "")}",
            create = true,
        )
        try {
            prepared.forEach { artifact -> writeDurably(File(tempDirectory, artifact.input.fileName), artifact.bytes) }
            writeDurably(File(tempDirectory, MANIFEST_FILE_NAME), manifest)
            hooks.beforeAtomicMove(tempDirectory, finalDirectory)
            Files.move(tempDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (cause: Exception) {
            runCatching { deleteDirectoryNoFollow(tempDirectory, rejectSymlinks = false) }
                .exceptionOrNull()
                ?.let(cause::addSuppressed)
            throw IllegalStateException("Unable to commit runtime-evidence artifact bundle", cause)
        }

        val relativeDirectory = relativeDirectory(sessionId, captureId)
        return CommittedRuntimeEvidenceBundle(
            captureId = captureId,
            relativeDirectory = relativeDirectory,
            relativeFiles = prepared.associate { artifact ->
                artifact.input.type to "$relativeDirectory/${artifact.input.fileName}"
            },
        )
    }

    @Synchronized
    override fun deleteBundle(sessionId: String, captureId: String) {
        validateId(sessionId, "sessionId")
        validateId(captureId, "captureId")
        existingEvidenceRoot()?.let { root ->
            safeExistingChild(root, sessionId)?.let { sessionDirectory ->
                safeExistingChild(sessionDirectory, captureId)?.let(::deleteDirectoryNoFollow)
                deleteIfEmpty(sessionDirectory)
            }
        }
    }

    @Synchronized
    override fun cleanupIncomplete(): Int {
        val root = existingEvidenceRoot() ?: return 0
        var deleted = 0
        safeChildDirectories(root).forEach { sessionDirectory ->
            safeChildDirectories(sessionDirectory)
                .filter { TEMPORARY_BUNDLE.matches(it.name) }
                .forEach { temporary ->
                    deleteDirectoryNoFollow(temporary, rejectSymlinks = false)
                    deleted += 1
                }
            deleteIfEmpty(sessionDirectory)
        }
        return deleted
    }

    @Synchronized
    override fun cleanupOrphans(referencedCaptureIdsBySession: Map<String, Set<String>>): Int {
        referencedCaptureIdsBySession.forEach { (sessionId, captures) ->
            validateId(sessionId, "sessionId")
            captures.forEach { validateId(it, "captureId") }
        }
        val root = existingEvidenceRoot() ?: return 0
        var deleted = 0
        safeChildDirectories(root).forEach { sessionDirectory ->
            val references = referencedCaptureIdsBySession[sessionDirectory.name].orEmpty()
            safeChildDirectories(sessionDirectory)
                .filterNot { TEMPORARY_BUNDLE.matches(it.name) }
                .filter { it.name !in references }
                .forEach { orphan ->
                    deleteDirectoryNoFollow(orphan)
                    deleted += 1
                }
            deleteIfEmpty(sessionDirectory)
        }
        return deleted
    }

    @Synchronized
    override fun deleteSession(sessionId: String) {
        validateId(sessionId, "sessionId")
        val root = existingEvidenceRoot() ?: return
        val sessionDirectory = safeExistingChild(root, sessionId) ?: return
        deleteDirectoryNoFollow(sessionDirectory)
    }

    private fun prepareInputs(inputs: List<RuntimeEvidenceArtifactInput>): List<PreparedArtifact> {
        require(inputs.isNotEmpty()) { "A runtime-evidence bundle must contain at least one artifact" }
        require(inputs.map { it.type }.distinct().size == inputs.size) {
            "A runtime-evidence bundle cannot contain duplicate evidence types"
        }
        require(inputs.map { it.fileName }.distinct().size == inputs.size) {
            "A runtime-evidence bundle cannot contain duplicate file names"
        }
        val prepared = inputs.map { input ->
            validateFileName(input.fileName)
            val rawBytes = input.redactedText.toByteArray(StandardCharsets.UTF_8)
            val fileLimit = FILE_LIMITS.getValue(input.type)
            if (rawBytes.size > fileLimit) {
                throw RuntimeEvidenceArtifactLimitException(
                    "${input.type} exceeds its $fileLimit-byte artifact limit",
                )
            }
            val redactedBytes = redactor.redact(input.redactedText).text.toByteArray(StandardCharsets.UTF_8)
            PreparedArtifact(input, redactedBytes)
        }
        return prepared
    }

    private fun enforceProjectQuota(newArtifactBytes: Long) {
        val currentBytes = if (evidenceRoot.exists()) directorySizeNoFollow(evidenceRoot) else 0L
        if (currentBytes > PROJECT_QUOTA_BYTES - newArtifactBytes) {
            throw RuntimeEvidenceArtifactQuotaException(
                "Runtime-evidence project quota of $PROJECT_QUOTA_BYTES bytes would be exceeded",
            )
        }
    }

    private fun manifestBytes(
        captureId: String,
        artifacts: List<PreparedArtifact>,
    ): ByteArray {
        val manifest = RuntimeEvidenceArtifactManifest(
            captureId = captureId,
            files = artifacts.map { artifact ->
                RuntimeEvidenceArtifactManifestEntry(
                    type = artifact.input.type.name.lowercase(),
                    fileName = artifact.input.fileName,
                    bytes = artifact.bytes.size.toLong(),
                )
            },
        )
        return fixThisJson.encodeToString(RuntimeEvidenceArtifactManifest.serializer(), manifest)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        require(file.parentFile.isDirectory && !Files.isSymbolicLink(file.parentFile.toPath())) {
            "Unsafe runtime-evidence artifact parent"
        }
        require(!file.exists()) { "Runtime-evidence artifact already exists: ${file.name}" }
        ensureWithinProject(file)
        hooks.beforeWrite(file)
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        FileChannel.open(file.toPath(), options).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun ensureDirectory(directory: File): File {
        require(!Files.isSymbolicLink(directory.toPath())) { "Runtime-evidence storage must not be a symlink" }
        if (!directory.exists()) {
            val parent = directory.parentFile
            if (parent != projectRoot) ensureDirectory(parent)
            require(directory.mkdir()) { "Unable to create runtime-evidence directory" }
        }
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Runtime-evidence storage must be a real directory"
        }
        ensureWithinProject(directory)
        return directory
    }

    private fun ensureSafeChild(parent: File, name: String, create: Boolean): File {
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "Unsafe runtime-evidence parent" }
        val child = File(parent, name)
        ensureWithinProject(child)
        require(!Files.isSymbolicLink(child.toPath())) { "Runtime-evidence paths must not be symlinks" }
        if (create && !child.exists()) require(child.mkdir()) { "Unable to create runtime-evidence directory" }
        if (child.exists()) {
            require(child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                "Runtime-evidence paths must be real directories"
            }
            ensureWithinProject(child)
        }
        return child
    }

    private fun existingEvidenceRoot(): File? {
        if (!evidenceRoot.exists() && !Files.isSymbolicLink(evidenceRoot.toPath())) return null
        require(!Files.isSymbolicLink(evidenceRoot.toPath())) { "Runtime-evidence storage must not be a symlink" }
        require(evidenceRoot.isDirectory) { "Runtime-evidence storage must be a directory" }
        ensureWithinProject(evidenceRoot)
        return evidenceRoot
    }

    private fun safeExistingChild(parent: File, name: String): File? {
        val child = File(parent, name)
        if (!child.exists() && !Files.isSymbolicLink(child.toPath())) return null
        require(!Files.isSymbolicLink(child.toPath())) { "Runtime-evidence paths must not be symlinks" }
        ensureWithinProject(child)
        require(child.isDirectory) { "Runtime-evidence bundle path must be a directory" }
        return child
    }

    private fun safeChildDirectories(parent: File): List<File> = parent.listFiles().orEmpty()
        .filter { child ->
            !Files.isSymbolicLink(child.toPath()) &&
                child.isDirectory &&
                runCatching { ensureWithinProject(child) }.isSuccess
        }

    private fun directorySizeNoFollow(directory: File): Long {
        var total = 0L
        Files.walk(directory.toPath()).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Runtime-evidence quota scan rejected a symlink" }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(path)
            }
        }
        return total
    }

    private fun deleteDirectoryNoFollow(directory: File, rejectSymlinks: Boolean = true) {
        require(!Files.isSymbolicLink(directory.toPath())) { "Refusing to delete a runtime-evidence symlink" }
        ensureWithinProject(directory)
        if (!directory.exists()) return
        directory.listFiles().orEmpty().forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                require(!rejectSymlinks) { "Refusing to traverse a runtime-evidence symlink" }
                Files.delete(child.toPath())
            } else if (child.isDirectory) {
                deleteDirectoryNoFollow(child, rejectSymlinks)
            } else {
                require(child.delete()) { "Unable to delete runtime-evidence artifact: ${child.name}" }
            }
        }
        require(directory.delete()) { "Unable to delete runtime-evidence artifact: ${directory.name}" }
    }

    private fun deleteIfEmpty(directory: File) {
        if (directory.isDirectory && directory.list().orEmpty().isEmpty()) directory.delete()
    }

    private fun ensureWithinProject(file: File) {
        require(file.canonicalFile.toPath().startsWith(projectRoot.toPath())) {
            "Runtime-evidence path escapes the project root"
        }
    }

    private fun validateId(value: String, label: String) {
        require(value.length in 1..MAX_SEGMENT_LENGTH && SAFE_SEGMENT.matches(value) && value != "." && value != "..") {
            "$label must match [A-Za-z0-9._-]+ and must not be a traversal segment"
        }
        if (label == "captureId") {
            require(!TEMPORARY_BUNDLE.matches(value)) { "captureId collides with the reserved temporary-bundle format" }
        }
    }

    private fun validateFileName(fileName: String) {
        validateId(fileName, "fileName")
        require(fileName != MANIFEST_FILE_NAME) { "$MANIFEST_FILE_NAME is reserved" }
    }

    private fun relativeDirectory(sessionId: String, captureId: String): String = "$EVIDENCE_ROOT_RELATIVE/$sessionId/$captureId"

    private data class PreparedArtifact(
        val input: RuntimeEvidenceArtifactInput,
        val bytes: ByteArray,
    )

    private companion object {
        const val EVIDENCE_ROOT_RELATIVE = ".fixthis/runtime-evidence"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val MAX_SEGMENT_LENGTH = 128
        const val BUNDLE_LIMIT_BYTES = 2L * 1024L * 1024L
        const val PROJECT_QUOTA_BYTES = 250L * 1024L * 1024L
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
        val TEMPORARY_BUNDLE = Regex(".+\\.tmp-[0-9a-f]{32}")
        val FILE_LIMITS = mapOf(
            RuntimeEvidenceType.LOGCAT_WINDOW to 512 * 1024,
            RuntimeEvidenceType.MEMORY_SUMMARY to 128 * 1024,
            RuntimeEvidenceType.FRAME_SUMMARY to 128 * 1024,
            RuntimeEvidenceType.TRACE_ARTIFACT to 25 * 1024 * 1024,
        )
    }
}

@Serializable
private data class RuntimeEvidenceArtifactManifest(
    val captureId: String,
    val files: List<RuntimeEvidenceArtifactManifestEntry>,
)

@Serializable
private data class RuntimeEvidenceArtifactManifestEntry(
    val type: String,
    val fileName: String,
    val bytes: Long,
)
