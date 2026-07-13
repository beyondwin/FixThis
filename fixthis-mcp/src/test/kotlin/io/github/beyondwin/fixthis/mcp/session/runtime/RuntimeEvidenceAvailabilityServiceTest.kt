package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceAvailabilityServiceTest {
    @Test
    fun existingArtifactRemainsCompleteWithoutRewritingInput() = withProject { root ->
        val artifact = File(root, ".fixthis/runtime-evidence/s1/c1/logcat.txt").apply {
            parentFile.mkdirs()
            writeText("redacted")
        }
        val original = session(root, artifact.relativeTo(root).invariantSeparatorsPath)

        val actual = RuntimeEvidenceAvailabilityService(root).materialize(original)

        assertEquals(RuntimeEvidenceStatus.COMPLETE, actual.runtimeEvidence.single().status)
        assertTrue(actual.runtimeEvidence.single().warnings.isEmpty())
        assertEquals(RuntimeEvidenceStatus.COMPLETE, original.runtimeEvidence.single().status)
    }

    @Test
    fun missingReferencedArtifactIsReadTimePartialOnly() = withProject { root ->
        val original = session(root, ".fixthis/runtime-evidence/s1/c1/missing.txt")

        val actual = RuntimeEvidenceAvailabilityService(root).materialize(original)

        val attachment = actual.runtimeEvidence.single()
        assertEquals(RuntimeEvidenceStatus.PARTIAL, attachment.status)
        assertEquals(null, attachment.failureReason)
        assertTrue(RuntimeEvidenceWarning.ARTIFACT_MISSING in attachment.warnings)
        assertEquals(RuntimeEvidenceStatus.COMPLETE, original.runtimeEvidence.single().status)
        assertTrue(original.runtimeEvidence.single().warnings.isEmpty())
    }

    @Test
    fun symlinkedArtifactAndEscapingPathFailClosedWithoutFollowingLink() = withProject { root ->
        val outside = Files.createTempFile("fixthis-outside", ".txt").toFile().apply { writeText("secret") }
        try {
            val link = File(root, ".fixthis/runtime-evidence/s1/c1/logcat.txt")
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val linked = RuntimeEvidenceAvailabilityService(root).materialize(
                session(root, link.relativeTo(root).invariantSeparatorsPath),
            )
            val escaping = RuntimeEvidenceAvailabilityService(root).materialize(
                session(root, ".fixthis/runtime-evidence/../../outside.txt"),
            )

            assertTrue(RuntimeEvidenceWarning.ARTIFACT_MISSING in linked.runtimeEvidence.single().warnings)
            assertTrue(RuntimeEvidenceWarning.ARTIFACT_MISSING in escaping.runtimeEvidence.single().warnings)
            assertFalse(linked.runtimeEvidence.single().summary.contains("secret"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun persistedProjectRootCannotRedirectAvailabilityOutsideConfiguredRoot() = withProject { root ->
        val outside = Files.createTempDirectory("fixthis-tampered-root").toFile()
        try {
            val artifact = File(outside, ".fixthis/runtime-evidence/s1/c1/logcat.txt").apply {
                parentFile.mkdirs()
                writeText("outside")
            }
            val tampered = session(outside, artifact.relativeTo(outside).invariantSeparatorsPath)

            val actual = RuntimeEvidenceAvailabilityService(root).materialize(tampered)

            assertTrue(RuntimeEvidenceWarning.ARTIFACT_MISSING in actual.runtimeEvidence.single().warnings)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun existingFailureReasonSurvivesMissingArtifactMaterialization() = withProject { root ->
        val original = session(root, ".fixthis/runtime-evidence/s1/c1/missing.txt").copy(
            runtimeEvidence = session(root, ".fixthis/runtime-evidence/s1/c1/missing.txt").runtimeEvidence.map {
                it.copy(status = RuntimeEvidenceStatus.FAILED, failureReason = RuntimeEvidenceFailureReason.PERMISSION_DENIED)
            },
        )

        val actual = RuntimeEvidenceAvailabilityService(root).materialize(original)

        assertEquals(RuntimeEvidenceStatus.FAILED, actual.runtimeEvidence.single().status)
        assertEquals(RuntimeEvidenceFailureReason.PERMISSION_DENIED, actual.runtimeEvidence.single().failureReason)
        assertTrue(RuntimeEvidenceWarning.ARTIFACT_MISSING in actual.runtimeEvidence.single().warnings)
    }

    private fun session(root: File, artifactPath: String) = SessionDto(
        sessionId = "s1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = root.absolutePath,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        runtimeEvidence = listOf(
            RuntimeEvidenceAttachment(
                evidenceId = "e1",
                type = RuntimeEvidenceType.LOGCAT_WINDOW,
                capturedAtEpochMillis = 2,
                packageName = "io.github.beyondwin.fixthis.sample",
                summary = "summary",
                artifactPath = artifactPath,
                captureId = "c1",
            ),
        ),
    )

    private fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("fixthis-availability").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
