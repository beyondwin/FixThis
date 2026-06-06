package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import java.io.File

/**
 * Atomic two-phase commit transaction for [SetupWritePlan]s.
 *
 * Phase 1 stages every write to `<configFile>.fixthis-staging` (fsync'd), Phase 1.5 snapshots existing
 * targets into `<configFile>.fixthis-rollback`, and Phase 2 commits via `ATOMIC_MOVE` (falling back to
 * copy+delete) with parent-directory fsync, restoring from the rollback snapshots if any commit fails.
 *
 * The injected seams ([move], [forceFile], [forceDirectory], [copyForRollback], [emit]) exist so tests can
 * inject failures; defaults reuse the [AtomicConfigFileWriter] fsync helpers and real filesystem moves.
 */
@Suppress("SpreadOperator")
internal class TwoPhaseConfigCommit(
    private val move: (java.nio.file.Path, java.nio.file.Path, Array<out java.nio.file.CopyOption>) -> java.nio.file.Path = { source, target, options ->
        java.nio.file.Files.move(source, target, *options)
    },
    private val forceFile: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceRegularFile,
    private val forceDirectory: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceDirectoryBestEffort,
    private val copyForRollback: (File, File) -> Unit = { source, destination ->
        source.copyTo(destination, overwrite = true)
    },
    private val emit: (String) -> Unit = { /* no-op: callers opt in to user-facing echo */ },
) {
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught", "SpreadOperator")
    fun commit(plans: List<SetupWritePlan>) {
        // Phase 1: stage all writes to <configFile>.fixthis-staging.
        val staged = mutableListOf<Pair<SetupWritePlan, File>>()
        try {
            plans.forEach { plan ->
                val stagingFile = File(plan.configFile.absolutePath + ".fixthis-staging")
                stagingFile.parentFile?.mkdirs()
                stagingFile.writeText(plan.content)
                // P2 durability: fsync staging file before any commit can read it.
                forceFile(stagingFile.toPath())
                staged += plan to stagingFile
            }
        } catch (e: Exception) {
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            val appliedNames = staged.joinToString { it.first.writerName }
            throw CliktError(
                "Could not stage MCP config writes (${e.message}). Staged so far: ${appliedNames.ifEmpty { "none" }} (cleaned up).",
                cause = e,
            )
        }

        // Phase 1.5: snapshot existing targets into rollback files BEFORE first move.
        // Wrapped to bound the failure surface — copyTo can fail mid-loop on permissions,
        // disk-full, or symlink edge cases (impl-details §3).
        val rollbacks = mutableMapOf<SetupWritePlan, File?>()
        try {
            staged.forEach { (plan, _) ->
                rollbacks[plan] = if (plan.configFile.exists()) {
                    val rb = File(plan.configFile.absolutePath + ".fixthis-rollback")
                    copyForRollback(plan.configFile, rb)
                    rb
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            rollbacks.values.filterNotNull().forEach { if (it.exists()) it.delete() }
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            throw CliktError(
                "Could not prepare two-phase MCP config commit (rollback snapshot failed: ${e.message}).",
                cause = e,
            )
        }

        // Phase 2: commit (ATOMIC_MOVE with fallback to copy+delete).
        val committed = mutableListOf<SetupWritePlan>()
        try {
            staged.forEach { (plan, stagingFile) ->
                try {
                    move(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        arrayOf(
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        ),
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.copy(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    java.nio.file.Files.deleteIfExists(stagingFile.toPath())
                }
                // P2 durability: fsync parent dir so the rename is persisted.
                plan.configFile.toPath().parent?.let { forceDirectory(it) }
                committed += plan
                SetupRunResults.applied.get() += InstallAgentJsonReport.Applied(
                    target = plan.writerName,
                    path = plan.configFile.absolutePath,
                    scope = plan.scope,
                )
                emit("Wrote ${plan.writerName} MCP config (${plan.scope}): ${plan.configFile.absolutePath}")
            }
            // Clean up rollback files on success.
            rollbacks.values.filterNotNull().forEach { it.delete() }
        } catch (e: Exception) {
            // Restore from rollback for any committed (already-moved) targets.
            committed.forEach { plan ->
                val rb = rollbacks[plan]
                if (rb != null && rb.exists()) {
                    rb.copyTo(plan.configFile, overwrite = true)
                } else {
                    plan.configFile.delete()
                }
            }
            // Clean up staging files that didn't move yet + rollback files.
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            rollbacks.values.filterNotNull().forEach { if (it.exists()) it.delete() }
            val appliedNames = committed.joinToString { it.writerName }
            throw CliktError(
                "Atomic commit failed: ${e.message}. Applied so far: ${appliedNames.ifEmpty { "none" }} (rolled back).",
                cause = e,
            )
        }
    }
}
