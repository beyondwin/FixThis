package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

class CleanCommand : CoreCliktCommand(name = "clean") {
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val dryRun by option("--dry-run", help = "List artifact directories without deleting them").flag(default = false)
    private val olderThanDays by option(
        "--older-than-days",
        help = "Only clean artifact directories last modified more than this many days ago",
    ).int()

    override fun run() {
        val days = olderThanDays
        if (days != null && days < 0) {
            throw CliktError("--older-than-days must be non-negative")
        }

        val root = File(projectDir).canonicalFile
        val cutoffEpochMillis = days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it.toLong()) }
        cleanFixThisArtifacts(root, dryRun, cutoffEpochMillis).forEach { result ->
            echo("${result.relativePath}: ${result.status.outputText}")
        }
    }
}

internal fun cleanFixThisArtifacts(
    projectRoot: File,
    dryRun: Boolean,
    cutoffEpochMillis: Long? = null,
): List<CleanArtifactResult> {
    val projectRootPath = projectRoot.canonicalFile.toPath().normalize()
    return KnownArtifactDirectories.map { relativePath ->
        val directory = projectRoot.resolve(relativePath)
        val directoryPath = directory.toPath()
        val status = when {
            !Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS) -> CleanArtifactStatus.Missing
            Files.isSymbolicLink(directoryPath) -> CleanArtifactStatus.Skipped
            !Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS) -> CleanArtifactStatus.Skipped
            !directory.canonicalFile.toPath().normalize().startsWith(projectRootPath) -> CleanArtifactStatus.Skipped
            cutoffEpochMillis != null &&
                Files.getLastModifiedTime(directoryPath, LinkOption.NOFOLLOW_LINKS).toMillis() >= cutoffEpochMillis ->
                CleanArtifactStatus.Skipped
            dryRun -> CleanArtifactStatus.WouldDelete
            deleteDirectoryWithoutFollowingLinks(directoryPath, projectRootPath) -> CleanArtifactStatus.Deleted
            else -> throw CliktError("Could not delete ${directory.absolutePath}")
        }
        CleanArtifactResult(relativePath, status)
    }
}

private fun deleteDirectoryWithoutFollowingLinks(directory: Path, projectRoot: Path): Boolean = try {
    Files.walkFileTree(
        directory,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val canonicalDirectory = dir.toFile().canonicalFile.toPath().normalize()
                if (!canonicalDirectory.startsWith(projectRoot)) {
                    throw IOException("Refusing to delete outside project root: $dir")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
    true
} catch (_: IOException) {
    false
}

internal data class CleanArtifactResult(
    val relativePath: String,
    val status: CleanArtifactStatus,
)

internal enum class CleanArtifactStatus(val outputText: String) {
    WouldDelete("would delete"),
    Deleted("deleted"),
    Skipped("skipped"),
    Missing("missing"),
}

private val KnownArtifactDirectories = listOf(
    ".fixthis/feedback-sessions",
    ".fixthis/preview-cache",
    ".fixthis/artifacts",
    ".fixthis/smoke-reports",
)
