package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class VerificationArtifactFileOperations(
    private val access: VerificationArtifactDirectoryAccess,
    private val hooks: VerificationArtifactStoreHooks,
) {
    fun <T> readFile(
        parent: VerificationDirectoryHandle,
        name: String,
        block: (SeekableByteChannel) -> T,
    ): T = openFile(parent, name, verificationReadOptions()).use(block)

    fun createNewSyncedFile(
        parent: VerificationDirectoryHandle,
        name: String,
        bytes: ByteArray,
    ) {
        openFile(parent, name, verificationWriteOptions()).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            forceChannel(channel)
        }
    }

    fun createNewSyncedFile(
        parent: VerificationDirectoryHandle,
        name: String,
        input: SeekableByteChannel,
    ) {
        openFile(parent, name, verificationWriteOptions()).use { output ->
            copyChannels(input, output)
            forceChannel(output)
        }
    }

    fun createTemporaryDirectory(sessionId: String, directoryName: String) {
        access.withVerificationDirectory(sessionId) { verification ->
            createTemporaryDirectoryChild(verification, directoryName)
            access.withChildDirectory(verification, directoryName) { created ->
                access.assertBound(created)
            }
        }
    }

    fun createTemporaryDirectoryChild(parent: VerificationDirectoryHandle, name: String) {
        access.assertBound(parent)
        val target = parent.absolute.resolve(name)
        if (parent.stream == null) {
            hooks.beforeFallbackDirectoryCreate(target)
            access.assertBound(parent)
        }
        Files.createDirectory(target)
        access.assertBound(parent)
    }

    fun moveDirectory(
        parent: VerificationDirectoryHandle,
        sourceName: String,
        targetName: String,
        onMoved: () -> Unit,
    ) {
        access.assertBound(parent)
        val source = parent.absolute.resolve(sourceName)
        val target = parent.absolute.resolve(targetName)
        if (parent.stream == null) {
            hooks.beforeFallbackMove(source, target)
            access.assertBound(parent)
        }
        try {
            hooks.atomicDirectoryMove(source, target)
        } catch (_: AtomicMoveNotSupportedException) {
            hooks.beforeAtomicMoveFallback(source, target)
            access.assertBound(parent)
            if (parent.stream != null) {
                parent.stream.move(Path.of(sourceName), parent.stream, Path.of(targetName))
            } else {
                Files.move(source, target)
            }
        }
        onMoved()
        hooks.afterDirectoryMoveBeforeValidation(source, target)
        access.assertBound(parent)
    }

    fun deleteEntryRecursively(parent: VerificationDirectoryHandle, name: String) {
        val entryAttributes = access.attributes(parent, name)
        if (entryAttributes.isDirectory && !entryAttributes.isSymbolicLink) {
            access.withChildDirectory(parent, name) { directory ->
                access.entryNames(directory).forEach { child ->
                    deleteEntryRecursively(directory, child)
                }
            }
            deleteArtifactDirectory(access, hooks, parent, name)
        } else {
            deleteFile(parent, name)
        }
    }

    fun pruneSymlinks(directory: VerificationDirectoryHandle): Int {
        var deleted = 0
        access.entryNames(directory).forEach { name ->
            val childAttributes = access.attributes(directory, name)
            when {
                childAttributes.isSymbolicLink -> {
                    deleteFile(directory, name)
                    deleted += 1
                }
                childAttributes.isDirectory -> {
                    deleted += access.withChildDirectory(directory, name, ::pruneSymlinks)
                }
            }
        }
        return deleted
    }

    fun deleteFile(parent: VerificationDirectoryHandle, name: String) {
        access.assertBound(parent)
        val target = parent.absolute.resolve(name)
        if (parent.stream != null) {
            parent.stream.deleteFile(Path.of(name))
        } else {
            hooks.beforeFallbackDelete(target)
            access.assertBound(parent)
            Files.delete(target)
        }
        access.assertBound(parent)
    }

    private fun openFile(
        parent: VerificationDirectoryHandle,
        name: String,
        options: Set<OpenOption>,
    ): SeekableByteChannel {
        access.assertBound(parent)
        val relative = Path.of(name)
        val target = parent.absolute.resolve(name)
        val channel = if (parent.stream != null) {
            parent.stream.newByteChannel(relative, options)
        } else {
            if (StandardOpenOption.CREATE_NEW in options) {
                hooks.beforeFallbackFileCreate(target)
                access.assertBound(parent)
            }
            Files.newByteChannel(target, options).also {
                hooks.afterFallbackFileOpenedBeforeValidation(target)
            }
        }
        runCatching {
            access.assertBound(parent)
        }.onFailure {
            channel.close()
        }.getOrThrow()
        return channel
    }
}

private fun deleteArtifactDirectory(
    access: VerificationArtifactDirectoryAccess,
    hooks: VerificationArtifactStoreHooks,
    parent: VerificationDirectoryHandle,
    name: String,
) {
    access.assertBound(parent)
    val target = parent.absolute.resolve(name)
    if (parent.stream != null) {
        parent.stream.deleteDirectory(Path.of(name))
    } else {
        hooks.beforeFallbackDelete(target)
        access.assertBound(parent)
        Files.delete(target)
    }
    access.assertBound(parent)
}

private fun copyChannels(input: SeekableByteChannel, output: SeekableByteChannel) {
    val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
    while (true) {
        buffer.clear()
        val count = input.read(buffer)
        if (count < 0) return
        buffer.flip()
        writeFully(output, buffer)
    }
}

private fun writeFully(channel: SeekableByteChannel, buffer: ByteBuffer) {
    while (buffer.hasRemaining()) channel.write(buffer)
}

private fun forceChannel(channel: SeekableByteChannel) = (
    channel as? FileChannel
        ?: throw FeedbackVerificationArtifactException("Verification artifacts require a force-capable file channel")
    )
    .force(true)

private fun verificationReadOptions(): Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

private fun verificationWriteOptions(): Set<OpenOption> = setOf(
    StandardOpenOption.CREATE_NEW,
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS,
)

private const val COPY_BUFFER_BYTES = 16 * 1024
