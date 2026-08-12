package io.github.beyondwin.fixthis.mcp.session

import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView

internal class TestSecureDirectoryStream private constructor(
    private val directory: Path,
    private val delegate: DirectoryStream<Path>,
) : SecureDirectoryStream<Path> {
    override fun iterator(): MutableIterator<Path> = delegate.iterator()

    override fun close() = delegate.close()

    override fun newDirectoryStream(
        path: Path,
        vararg options: LinkOption,
    ): SecureDirectoryStream<Path> {
        val child = resolve(path)
        if (LinkOption.NOFOLLOW_LINKS in options && Files.isSymbolicLink(child)) {
            throw java.nio.file.FileSystemException(child.toString(), null, "symbolic link refused")
        }
        return open(child)
    }

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = Files.newByteChannel(resolve(path), options, *attrs)

    override fun deleteFile(path: Path) {
        Files.delete(resolve(path))
    }

    override fun deleteDirectory(path: Path) {
        Files.delete(resolve(path))
    }

    override fun move(
        sourcePath: Path,
        targetDirectory: SecureDirectoryStream<Path>,
        targetPath: Path,
    ) {
        val target = targetDirectory as TestSecureDirectoryStream
        Files.move(resolve(sourcePath), target.resolve(targetPath))
    }

    override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V? = Files.getFileAttributeView(directory, type)

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? = Files.getFileAttributeView(resolve(path), type, *options)

    private fun resolve(path: Path): Path = directory.resolve(path).normalize()

    companion object {
        fun open(path: Path): TestSecureDirectoryStream = TestSecureDirectoryStream(
            directory = path,
            delegate = Files.newDirectoryStream(path),
        )
    }
}
