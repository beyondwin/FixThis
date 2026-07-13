package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class RuntimeEvidenceAvailabilityService(projectRoot: File) {
    private val projectRoot = projectRoot.canonicalFile.toPath()

    fun materialize(session: SessionDto): SessionDto = session.copy(
        runtimeEvidence = session.runtimeEvidence.map { attachment ->
            if (attachment.captureId == null || attachment.artifactPath == null) {
                attachment
            } else if (isAvailable(session, attachment)) {
                attachment
            } else {
                attachment.copy(
                    status = if (attachment.status == RuntimeEvidenceStatus.COMPLETE) {
                        RuntimeEvidenceStatus.PARTIAL
                    } else {
                        attachment.status
                    },
                    warnings = (attachment.warnings + RuntimeEvidenceWarning.ARTIFACT_MISSING).distinct(),
                )
            }
        },
    )

    private fun isAvailable(session: SessionDto, attachment: RuntimeEvidenceAttachment): Boolean = runCatching {
        val relative = Path.of(requireNotNull(attachment.artifactPath))
        require(!relative.isAbsolute) { "Runtime-evidence artifact must be relative" }
        val normalized = relative.normalize()
        val captureId = requireNotNull(attachment.captureId)
        val expectedRoot = Path.of(
            RuntimeEvidenceArtifactNaming.EVIDENCE_ROOT_RELATIVE,
            session.sessionId,
            captureId,
        )
        require(normalized.startsWith(expectedRoot) && normalized.nameCount == expectedRoot.nameCount + 1) {
            "Runtime-evidence artifact is outside its committed bundle"
        }
        val artifact = projectRoot.resolve(normalized).normalize()
        require(artifact.startsWith(projectRoot)) { "Runtime-evidence artifact escapes project root" }
        requireNoSymlinkComponents(projectRoot, normalized)
        Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun requireNoSymlinkComponents(projectRoot: Path, relative: Path) {
        require(Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(projectRoot)) {
            "Runtime-evidence project root must be a real directory"
        }
        var current = projectRoot
        relative.forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Runtime-evidence artifact path contains a symlink" }
        }
    }
}
