package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

internal class RuntimeEvidenceArtifactQuotaException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class RuntimeEvidenceArtifactStoreHooks(
    val beforeWrite: (File) -> Unit = {},
    val beforeAtomicMove: (File, File) -> Unit = { _, _ -> },
    val quotaByteCounter: ((File) -> Long)? = null,
    val beforeQuotaCheck: () -> Unit = {},
    val fileSizeReader: ((Path) -> Long)? = null,
)

internal class FileRuntimeEvidenceArtifactStore(
    projectRoot: File,
    private val redactor: RuntimeEvidenceRedactor,
    private val hooks: RuntimeEvidenceArtifactStoreHooks = RuntimeEvidenceArtifactStoreHooks(),
) : RuntimeEvidenceArtifactStore {
    private val projectRoot = projectRoot.canonicalFile
    private val evidenceRoot = File(this.projectRoot, RuntimeEvidenceArtifactNaming.EVIDENCE_ROOT_RELATIVE)
    private val fileSystem = RuntimeEvidenceArtifactFileSystem(this.projectRoot, evidenceRoot, hooks.fileSizeReader)
    private val cleaner = RuntimeEvidenceArtifactCleaner(fileSystem)

    @Synchronized
    override fun commit(
        sessionId: String,
        captureId: String,
        inputs: List<RuntimeEvidenceArtifactInput>,
    ): CommittedRuntimeEvidenceBundle {
        RuntimeEvidenceArtifactNaming.validateId(sessionId, "sessionId")
        RuntimeEvidenceArtifactNaming.validateId(captureId, "captureId")
        val prepared = prepareInputs(inputs)
        val manifest = manifestBytes(captureId, prepared)
        val bundleBytes = prepared.sumOf { it.bytes.size.toLong() } + manifest.size
        if (bundleBytes > BUNDLE_LIMIT_BYTES) {
            throw RuntimeEvidenceArtifactLimitException(
                "Runtime-evidence bundle exceeds its $BUNDLE_LIMIT_BYTES-byte limit",
            )
        }
        fileSystem.pathGuard.ensureWithinProject(evidenceRoot)
        val root = fileSystem.ensureDirectory(evidenceRoot)
        val quotaGuard = RuntimeEvidenceArtifactQuotaGuard(
            evidenceRoot = root,
            byteCounter = hooks.quotaByteCounter ?: fileSystem::directorySizeNoFollow,
            beforeQuotaCheck = hooks.beforeQuotaCheck,
        )
        return quotaGuard.withReservation(bundleBytes) {
            commitReservedBundle(root, sessionId, captureId, prepared, manifest)
        }
    }

    private fun commitReservedBundle(
        root: File,
        sessionId: String,
        captureId: String,
        prepared: List<PreparedArtifact>,
        manifest: ByteArray,
    ): CommittedRuntimeEvidenceBundle {
        val sessionDirectory = fileSystem.ensureSafeChild(root, sessionId, create = true)
        val finalDirectory = fileSystem.ensureSafeChild(sessionDirectory, captureId, create = false)
        require(!finalDirectory.exists()) { "Runtime-evidence bundle already exists: $captureId" }
        val tempDirectory = fileSystem.ensureSafeChild(
            sessionDirectory,
            "$captureId.tmp-${UUID.randomUUID().toString().replace("-", "")}",
            create = true,
        )
        val failure = runCatching {
            prepared.forEach { artifact ->
                fileSystem.writeDurably(File(tempDirectory, artifact.input.fileName), artifact.bytes, hooks.beforeWrite)
            }
            fileSystem.writeDurably(
                File(tempDirectory, RuntimeEvidenceArtifactNaming.MANIFEST_FILE_NAME),
                manifest,
                hooks.beforeWrite,
            )
            hooks.beforeAtomicMove(tempDirectory, finalDirectory)
            fileSystem.pathGuard.requireSafeArtifactDirectory(tempDirectory)
            fileSystem.pathGuard.requireSafeArtifactParent(finalDirectory.parentFile)
            require(!finalDirectory.exists() && !Files.isSymbolicLink(finalDirectory.toPath())) {
                "Runtime-evidence destination changed before atomic commit"
            }
            Files.move(tempDirectory.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.exceptionOrNull()
        if (failure != null) {
            runCatching { fileSystem.deleteTreeNoFollow(tempDirectory) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw IllegalStateException("Unable to commit runtime-evidence artifact bundle", failure)
        }
        return committedBundle(sessionId, captureId, prepared)
    }

    private fun committedBundle(
        sessionId: String,
        captureId: String,
        prepared: List<PreparedArtifact>,
    ): CommittedRuntimeEvidenceBundle {
        val relativeDirectory = RuntimeEvidenceArtifactNaming.relativeDirectory(sessionId, captureId)
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
        RuntimeEvidenceArtifactNaming.validateId(sessionId, "sessionId")
        RuntimeEvidenceArtifactNaming.validateId(captureId, "captureId")
        fileSystem.existingEvidenceRoot()?.let { root ->
            quotaGuard(root).withExclusive {
                cleaner.deleteBundle(root, sessionId, captureId)
            }
        }
    }

    @Synchronized
    override fun cleanupIncomplete(): Int {
        val root = fileSystem.existingEvidenceRoot() ?: return 0
        return quotaGuard(root).withExclusive { cleaner.cleanupIncomplete(root) }
    }

    @Synchronized
    override fun cleanupOrphans(referencedCaptureIdsBySession: Map<String, Set<String>>): Int {
        referencedCaptureIdsBySession.forEach { (sessionId, captures) ->
            RuntimeEvidenceArtifactNaming.validateId(sessionId, "sessionId")
            captures.forEach { RuntimeEvidenceArtifactNaming.validateId(it, "captureId") }
        }
        val root = fileSystem.existingEvidenceRoot() ?: return 0
        return quotaGuard(root).withExclusive { cleaner.cleanupOrphans(root, referencedCaptureIdsBySession) }
    }

    @Synchronized
    override fun deleteSession(sessionId: String) {
        RuntimeEvidenceArtifactNaming.validateId(sessionId, "sessionId")
        val root = fileSystem.existingEvidenceRoot() ?: return
        quotaGuard(root).withExclusive {
            cleaner.deleteSession(root, sessionId)
        }
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
            RuntimeEvidenceArtifactNaming.validateFileName(input.fileName)
            val rawBytes = input.redactedText.toByteArray(StandardCharsets.UTF_8)
            val fileLimit = FILE_LIMITS.getValue(input.type)
            if (rawBytes.size > fileLimit) {
                throw RuntimeEvidenceArtifactLimitException(
                    "${input.type} exceeds its $fileLimit-byte artifact limit",
                )
            }
            val redactedBytes = redactor.redact(input.redactedText).text.toByteArray(StandardCharsets.UTF_8)
            if (redactedBytes.size > fileLimit) {
                throw RuntimeEvidenceArtifactLimitException(
                    "${input.type} exceeds its $fileLimit-byte artifact limit after redaction",
                )
            }
            PreparedArtifact(input, redactedBytes)
        }
        return prepared
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

    private fun quotaGuard(root: File): RuntimeEvidenceArtifactQuotaGuard = RuntimeEvidenceArtifactQuotaGuard(
        evidenceRoot = root,
        byteCounter = hooks.quotaByteCounter ?: fileSystem::directorySizeNoFollow,
        beforeQuotaCheck = hooks.beforeQuotaCheck,
    )

    private data class PreparedArtifact(
        val input: RuntimeEvidenceArtifactInput,
        val bytes: ByteArray,
    )

    private companion object {
        const val BUNDLE_LIMIT_BYTES = 2L * 1024L * 1024L
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
