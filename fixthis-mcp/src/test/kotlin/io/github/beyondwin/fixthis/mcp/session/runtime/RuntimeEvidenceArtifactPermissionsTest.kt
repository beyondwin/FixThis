package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeEvidenceArtifactPermissionsTest {
    @Test
    fun committedEvidenceIsOwnerOnlyAndExistingPathsAreTightened() {
        val root = Files.createTempDirectory("fixthis-runtime-evidence-permissions-").toFile()
        try {
            val evidenceRoot = File(root, ".fixthis/runtime-evidence").apply { mkdirs() }
            if (!supportsPosix(evidenceRoot)) return
            val existingSession = File(evidenceRoot, "session-private").apply { mkdir() }
            val existingBundle = File(existingSession, "existing-capture").apply { mkdir() }
            val existingArtifact = File(existingBundle, "existing.txt").apply { writeText("existing private evidence") }
            listOf(evidenceRoot, existingSession, existingBundle, existingArtifact).forEach(::makeWorldAccessible)
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            store.commit(
                sessionId = "session-private",
                captureId = "capture-private",
                inputs = listOf(
                    RuntimeEvidenceArtifactInput(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "private evidence"),
                ),
            )

            val bundle = File(existingSession, "capture-private")
            listOf(evidenceRoot, existingSession, existingBundle, bundle).forEach { directory ->
                assertEquals(
                    DIRECTORY_PERMISSIONS,
                    Files.getPosixFilePermissions(directory.toPath()),
                    "directory permissions for ${directory.name}",
                )
            }
            listOf(
                File(bundle, "logcat.txt"),
                File(bundle, RuntimeEvidenceArtifactNaming.MANIFEST_FILE_NAME),
                File(evidenceRoot, RuntimeEvidenceArtifactQuotaGuard.LOCK_FILE_NAME),
                File(evidenceRoot, RuntimeEvidenceQuotaFileLock.RECOVERY_LOCK_FILE_NAME),
                existingArtifact,
            ).forEach { file ->
                assertEquals(
                    FILE_PERMISSIONS,
                    Files.getPosixFilePermissions(file.toPath()),
                    "file permissions for ${file.name}",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun supportsPosix(file: File): Boolean = Files.getFileAttributeView(
        file.toPath(),
        PosixFileAttributeView::class.java,
    ) != null

    private fun makeWorldAccessible(file: File) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermission.entries.toSet())
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
