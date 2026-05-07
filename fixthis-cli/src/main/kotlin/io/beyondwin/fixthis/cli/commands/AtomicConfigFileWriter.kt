package io.beyondwin.fixthis.cli.commands

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object AtomicConfigFileWriter {
    fun write(
        file: File,
        content: String,
        forceFile: (Path) -> Unit = ::forceRegularFile,
        forceDirectory: (Path) -> Unit = ::forceDirectoryBestEffort,
        move: (Path, Path, Array<out CopyOption>) -> Path = { source, target, options ->
            Files.move(source, target, *options)
        },
    ) {
        val target = file.toPath()
        val parent = target.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".${target.fileName}", ".tmp")
        var moved = false
        try {
            Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
            forceFile(temp)
            try {
                move(temp, target, arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE))
            } catch (_: AtomicMoveNotSupportedException) {
                move(temp, target, arrayOf(StandardCopyOption.REPLACE_EXISTING))
            }
            moved = true
            forceDirectoryBestEffort(parent, forceDirectory)
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun forceRegularFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    }

    private fun forceDirectoryBestEffort(path: Path) {
        forceDirectoryBestEffort(path) { directory ->
            FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private fun forceDirectoryBestEffort(path: Path, force: (Path) -> Unit) {
        try {
            force(path)
        } catch (_: IOException) {
            // Some platforms/filesystems do not support opening directories for fsync.
        } catch (_: UnsupportedOperationException) {
            // Best effort only; the already-moved file remains intact.
        } catch (_: SecurityException) {
            // Best effort only; the already-moved file remains intact.
        }
    }
}
