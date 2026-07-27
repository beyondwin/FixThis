package io.github.beyondwin.fixthis.mcp.session.verification

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal data class VerificationArtifactEntry(
    val name: String,
    val directory: Boolean,
    val symbolicLink: Boolean,
)

@Suppress("TooManyFunctions")
internal class VerificationArtifactSecureFileSystem(
    private val paths: VerificationArtifactPaths,
    private val hooks: VerificationArtifactStoreHooks,
) {
    private val projectRootPath = paths.projectRoot.toPath()

    fun ensureVerificationRoot(sessionId: String): File {
        VerificationArtifactNaming.validateSegment(sessionId, "sessionId")
        val directories = listOf(
            paths.projectRoot.resolve(".fixthis"),
            paths.feedbackRoot,
            paths.sessionRoot(sessionId),
            paths.verificationRoot(sessionId),
        )
        directories.forEach(::ensureDirectory)
        withVerificationDirectory(sessionId) { handle -> assertBound(handle) }
        return paths.verificationRoot(sessionId)
    }

    fun reserve(
        sessionId: String,
        receiptId: String,
        ownershipToken: String,
    ) {
        ensureVerificationRoot(sessionId)
        withVerificationDirectory(sessionId) { verification ->
            createNewSyncedFile(
                verification,
                VerificationArtifactNaming.reservationName(receiptId),
                ownershipToken.toByteArray(),
            )
        }
        createOwnedDirectory(
            sessionId = sessionId,
            directory = paths.temporaryDirectory(sessionId, receiptId, ownershipToken),
            ownershipToken = ownershipToken,
        )
    }

    fun stagePng(
        sessionId: String,
        receiptId: String,
        ownershipToken: String,
        source: File,
    ): File {
        val temporaryName = VerificationArtifactNaming.temporaryName(receiptId, ownershipToken)
        val canonicalSource = canonicalSource(source)
        withSourceParent(canonicalSource) { sourceParent, sourceName ->
            hooks.afterSourceParentOpened(sourceParent.absolute)
            assertBound(sourceParent)
            val sourceAttributes = attributes(sourceParent, sourceName)
            artifactRequire(sourceAttributes.isRegularFile && !sourceAttributes.isSymbolicLink) {
                "Verification screenshot source must be a regular file"
            }
            openFile(sourceParent, sourceName, readOptions()).use { input ->
                withVerificationDirectory(sessionId) { verification ->
                    withChildDirectory(verification, temporaryName) { temporary ->
                        hooks.afterTemporaryDirectoryOpened(temporary.absolute)
                        assertBound(verification)
                        assertBound(temporary)
                        requireOwner(temporary, ownershipToken)
                        createNewSyncedFile(
                            temporary,
                            VerificationArtifactNaming.AFTER_SCREENSHOT,
                            input,
                        )
                        assertBound(sourceParent)
                        assertBound(temporary)
                    }
                }
            }
        }
        return paths.temporaryDirectory(sessionId, receiptId, ownershipToken)
            .resolve(VerificationArtifactNaming.AFTER_SCREENSHOT)
    }

    fun promote(prepared: PreparedVerificationArtifact) {
        val temporaryName = VerificationArtifactNaming.isTemporaryNameFor(
            prepared.temporaryDirectory.name,
            prepared.receiptId,
            prepared.ownershipToken,
        )
        artifactRequire(temporaryName) {
            "Verification temporary directory is not bound to its receipt id"
        }
        withVerificationDirectory(prepared.sessionId) { verification ->
            hooks.beforePromotion()
            assertBound(verification)
            requireReservation(verification, prepared.receiptId, prepared.ownershipToken)
            withChildDirectory(verification, prepared.temporaryDirectory.name) { temporary ->
                assertBound(temporary)
                requireOwner(temporary, prepared.ownershipToken)
                artifactRequire(!entryExists(verification, prepared.receiptId)) {
                    "Verification receipt destination already exists"
                }
            }
            try {
                Files.move(
                    prepared.temporaryDirectory.toPath(),
                    prepared.finalDirectory.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    prepared.temporaryDirectory.toPath(),
                    prepared.finalDirectory.toPath(),
                )
            }
            assertBound(verification)
            withChildDirectory(verification, prepared.receiptId) { final ->
                assertBound(final)
                requireOwner(final, prepared.ownershipToken)
            }
            deleteFile(
                verification,
                VerificationArtifactNaming.reservationName(prepared.receiptId),
            )
            withChildDirectory(verification, prepared.receiptId) { final ->
                requireOwner(final, prepared.ownershipToken)
                deleteFile(
                    final,
                    VerificationArtifactNaming.ownerMarkerName(prepared.ownershipToken),
                )
            }
        }
    }

    fun deleteOwned(prepared: PreparedVerificationArtifact) {
        runCatching {
            withVerificationDirectory(prepared.sessionId) { verification ->
                deleteOwnedDirectory(
                    verification,
                    prepared.temporaryDirectory.name,
                    prepared.ownershipToken,
                )
                deleteOwnedDirectory(
                    verification,
                    prepared.receiptId,
                    prepared.ownershipToken,
                )
                val reservationName = VerificationArtifactNaming.reservationName(prepared.receiptId)
                if (tokenMatches(verification, reservationName, prepared.ownershipToken)) {
                    deleteFile(verification, reservationName)
                }
            }
        }
    }

    fun deleteReceipt(sessionId: String, receiptId: String) {
        withVerificationDirectoryIfPresent(sessionId) { verification ->
            if (entryExists(verification, receiptId)) {
                deleteEntryRecursively(verification, receiptId)
            }
            val reservationName = VerificationArtifactNaming.reservationName(receiptId)
            if (entryExists(verification, reservationName)) {
                deleteFile(verification, reservationName)
            }
        }
    }

    fun sessionIds(): List<String> {
        if (!Files.exists(paths.feedbackRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return withProjectDirectory(
            listOf(".fixthis", "feedback-sessions"),
        ) { feedback ->
            entryNames(feedback).mapNotNull { name ->
                runCatching {
                    VerificationArtifactNaming.validateSegment(name, "sessionId")
                    val attributes = attributes(feedback, name)
                    name.takeIf { attributes.isDirectory && !attributes.isSymbolicLink }
                }.getOrNull()
            }
        }
    }

    fun entries(sessionId: String): List<VerificationArtifactEntry> = withVerificationDirectoryIfPresent(sessionId) { verification ->
        entryNames(verification).map { name ->
            val attributes = attributes(verification, name)
            VerificationArtifactEntry(
                name = name,
                directory = attributes.isDirectory,
                symbolicLink = attributes.isSymbolicLink,
            )
        }
    }.orEmpty()

    fun hasOwnerMarker(sessionId: String, directoryName: String): Boolean = withVerificationDirectoryIfPresent(sessionId) { verification ->
        runCatching {
            withChildDirectory(verification, directoryName) { directory ->
                entryNames(directory).any { name -> name.startsWith(".owner-") }
            }
        }.getOrDefault(false)
    } ?: false

    fun deleteEntry(sessionId: String, name: String) {
        withVerificationDirectoryIfPresent(sessionId) { verification ->
            if (entryExists(verification, name)) deleteEntryRecursively(verification, name)
        }
    }

    fun pruneSymlinks(sessionId: String, directoryName: String): Int = withVerificationDirectoryIfPresent(sessionId) { verification ->
        if (!entryExists(verification, directoryName)) {
            0
        } else {
            withChildDirectory(verification, directoryName, ::pruneSymlinks)
        }
    } ?: 0

    private fun createOwnedDirectory(
        sessionId: String,
        directory: File,
        ownershipToken: String,
    ) {
        withVerificationDirectory(sessionId) { verification ->
            createOwnedDirectory(verification, directory, ownershipToken)
        }
    }

    private fun createOwnedDirectory(
        verification: DirectoryHandle,
        directory: File,
        ownershipToken: String,
    ) {
        assertBound(verification)
        artifactRequire(directory.parentFile.canonicalFile == verification.absolute.toFile().canonicalFile) {
            "Verification artifact directory escapes its storage root"
        }
        Files.createDirectory(directory.toPath())
        withChildDirectory(verification, directory.name) { created ->
            assertBound(verification)
            assertBound(created)
            createNewSyncedFile(
                created,
                VerificationArtifactNaming.ownerMarkerName(ownershipToken),
                ownershipToken.toByteArray(),
            )
        }
    }

    private fun canonicalSource(source: File): File {
        artifactRequire(source.extension.lowercase() == "png") {
            "Verification screenshot source must be a .png file"
        }
        val absolute = source.toPath().toAbsolutePath().normalize()
        requireNoSymlinksToProjectRoot(absolute)
        artifactRequire(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "Verification screenshot source must be a regular file"
        }
        val canonical = absolute.toFile().canonicalFile
        artifactRequire(canonical.toPath().startsWith(projectRootPath)) {
            "Verification screenshot source escapes the canonical project root"
        }
        return canonical
    }

    private fun <T> withSourceParent(
        source: File,
        block: (DirectoryHandle, String) -> T,
    ): T {
        val relative = projectRootPath.relativize(source.toPath())
        val segments = relative.map(Path::toString)
        return withProjectDirectory(segments.dropLast(1)) { parent ->
            block(parent, segments.last())
        }
    }

    private fun ensureDirectory(directory: File) {
        val normalized = directory.toPath().toAbsolutePath().normalize()
        artifactRequire(normalized.startsWith(projectRootPath)) {
            "Verification artifact path escapes the project root"
        }
        artifactRequire(!Files.isSymbolicLink(normalized)) {
            "Verification artifact path contains a symbolic link"
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(normalized)
        artifactRequire(
            Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(normalized),
        ) {
            "Verification artifact path must be a real directory"
        }
        artifactRequire(directory.canonicalFile.toPath().startsWith(projectRootPath)) {
            "Verification artifact path escapes the canonical project root"
        }
    }

    private fun <T> withVerificationDirectory(
        sessionId: String,
        block: (DirectoryHandle) -> T,
    ): T = withProjectDirectory(
        listOf(".fixthis", "feedback-sessions", sessionId, VerificationArtifactNaming.VERIFICATION_DIRECTORY),
        block,
    )

    private fun <T> withVerificationDirectoryIfPresent(
        sessionId: String,
        block: (DirectoryHandle) -> T,
    ): T? {
        if (!Files.exists(paths.verificationRoot(sessionId).toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return withVerificationDirectory(sessionId, block)
    }

    private fun <T> withProjectDirectory(
        segments: List<String>,
        block: (DirectoryHandle) -> T,
    ): T {
        val rootStream = hooks.rootDirectoryStreamFactory(projectRootPath)
        rootStream.use { stream ->
            val root = DirectoryHandle(
                stream = stream as? SecureDirectoryStream<Path>,
                absolute = projectRootPath,
                fileKey = absoluteAttributes(projectRootPath).fileKey()
                    ?: throw FeedbackVerificationArtifactException(
                        "Verification artifacts require stable directory file keys",
                    ),
            )
            assertBound(root)
            return descend(root, segments, 0, block)
        }
    }

    private fun <T> descend(
        parent: DirectoryHandle,
        segments: List<String>,
        index: Int,
        block: (DirectoryHandle) -> T,
    ): T {
        if (index == segments.size) return block(parent)
        val segment = segments[index]
        VerificationArtifactNaming.validateSegment(segment, "path segment")
        return withChildDirectory(parent, segment) { child ->
            descend(child, segments, index + 1, block)
        }
    }

    private fun <T> withChildDirectory(
        parent: DirectoryHandle,
        name: String,
        block: (DirectoryHandle) -> T,
    ): T {
        assertBound(parent)
        val childPath = parent.absolute.resolve(name)
        val childAttributes = attributes(parent, name)
        artifactRequire(childAttributes.isDirectory && !childAttributes.isSymbolicLink) {
            "Verification artifact path must be a real directory"
        }
        val secureParent = parent.stream
        if (secureParent != null) {
            val childStream = secureParent.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS)
            childStream.use { stream ->
                val child = DirectoryHandle(
                    stream = requireSecureDirectoryStream(stream),
                    absolute = childPath,
                    fileKey = requireStableFileKey(childAttributes),
                )
                assertBound(child)
                return block(child)
            }
        }
        val child = DirectoryHandle(
            stream = null,
            absolute = childPath,
            fileKey = requireStableFileKey(childAttributes),
        )
        assertBound(child)
        return block(child)
    }

    private fun requireSecureDirectoryStream(
        stream: DirectoryStream<Path>,
    ): SecureDirectoryStream<Path> = stream as? SecureDirectoryStream<Path>
        ?: throw FeedbackVerificationArtifactException(
            "Verification artifacts require secure child directory streams",
        )

    private fun requireStableFileKey(attributes: BasicFileAttributes): Any = attributes.fileKey()
        ?: throw FeedbackVerificationArtifactException(
            "Verification artifacts require stable child directory file keys",
        )

    private fun assertBound(handle: DirectoryHandle) {
        val handleKey = handle.stream
            ?.getFileAttributeView(BasicFileAttributeView::class.java)
            ?.readAttributes()
            ?.fileKey()
            ?: handle.fileKey
        val absoluteKey = try {
            absoluteAttributes(handle.absolute).fileKey()
        } catch (_: NoSuchFileException) {
            null
        }
        artifactRequire(handleKey == absoluteKey) {
            "Verification artifact directory changed after secure open"
        }
    }

    private fun attributes(parent: DirectoryHandle, name: String): BasicFileAttributes {
        assertBound(parent)
        val result = parent.stream
            ?.getFileAttributeView(
                Path.of(name),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            ?.readAttributes()
            ?: absoluteAttributes(parent.absolute.resolve(name))
        assertBound(parent)
        return result
    }

    private fun openFile(
        parent: DirectoryHandle,
        name: String,
        options: Set<OpenOption>,
    ): SeekableByteChannel {
        assertBound(parent)
        val channel = parent.stream?.newByteChannel(Path.of(name), options)
            ?: Files.newByteChannel(parent.absolute.resolve(name), options)
        runCatching {
            assertBound(parent)
        }.onFailure {
            channel.close()
        }.getOrThrow()
        return channel
    }

    private fun createNewSyncedFile(
        parent: DirectoryHandle,
        name: String,
        bytes: ByteArray,
    ) {
        openFile(parent, name, writeOptions()).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            force(channel)
        }
    }

    private fun createNewSyncedFile(
        parent: DirectoryHandle,
        name: String,
        input: SeekableByteChannel,
    ) {
        openFile(parent, name, writeOptions()).use { output ->
            copy(input, output)
            force(output)
        }
    }

    private fun requireReservation(
        verification: DirectoryHandle,
        receiptId: String,
        ownershipToken: String,
    ) {
        artifactRequire(
            tokenMatches(
                verification,
                VerificationArtifactNaming.reservationName(receiptId),
                ownershipToken,
            ),
        ) {
            "Verification receipt reservation is not owned by this preparation"
        }
    }

    private fun requireOwner(directory: DirectoryHandle, ownershipToken: String) {
        artifactRequire(
            tokenMatches(
                directory,
                VerificationArtifactNaming.ownerMarkerName(ownershipToken),
                ownershipToken,
            ),
        ) {
            "Verification artifact directory is not owned by this preparation"
        }
    }

    private fun tokenMatches(
        directory: DirectoryHandle,
        name: String,
        ownershipToken: String,
    ): Boolean = runCatching {
        openFile(directory, name, readOptions()).use { channel ->
            val bytes = ByteArray(MAX_OWNERSHIP_TOKEN_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            val count = channel.read(buffer)
            String(bytes, 0, count) == ownershipToken && channel.read(ByteBuffer.allocate(1)) == -1
        }
    }.getOrDefault(false)

    private fun deleteOwnedDirectory(
        verification: DirectoryHandle,
        name: String,
        ownershipToken: String,
    ) {
        if (!entryExists(verification, name)) return
        val owned = runCatching {
            withChildDirectory(verification, name) { directory ->
                tokenMatches(
                    directory,
                    VerificationArtifactNaming.ownerMarkerName(ownershipToken),
                    ownershipToken,
                )
            }
        }.getOrDefault(false)
        if (owned) deleteEntryRecursively(verification, name)
    }

    private fun deleteEntryRecursively(parent: DirectoryHandle, name: String) {
        val entryAttributes = attributes(parent, name)
        if (entryAttributes.isDirectory && !entryAttributes.isSymbolicLink) {
            withChildDirectory(parent, name) { directory ->
                entryNames(directory).forEach { child ->
                    deleteEntryRecursively(directory, child)
                }
            }
            deleteDirectory(parent, name)
        } else {
            deleteFile(parent, name)
        }
    }

    private fun pruneSymlinks(directory: DirectoryHandle): Int {
        var deleted = 0
        entryNames(directory).forEach { name ->
            val childAttributes = attributes(directory, name)
            when {
                childAttributes.isSymbolicLink -> {
                    deleteFile(directory, name)
                    deleted += 1
                }
                childAttributes.isDirectory -> {
                    deleted += withChildDirectory(directory, name, ::pruneSymlinks)
                }
            }
        }
        return deleted
    }

    private fun entryNames(directory: DirectoryHandle): List<String> {
        assertBound(directory)
        val result = directory.stream?.map { it.fileName.toString() }?.toList()
            ?: Files.newDirectoryStream(directory.absolute).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
        assertBound(directory)
        return result
    }

    private fun deleteFile(parent: DirectoryHandle, name: String) {
        assertBound(parent)
        parent.stream?.deleteFile(Path.of(name))
            ?: Files.delete(parent.absolute.resolve(name))
        assertBound(parent)
    }

    private fun deleteDirectory(parent: DirectoryHandle, name: String) {
        assertBound(parent)
        parent.stream?.deleteDirectory(Path.of(name))
            ?: Files.delete(parent.absolute.resolve(name))
        assertBound(parent)
    }

    private fun entryExists(parent: DirectoryHandle, name: String): Boolean = runCatching {
        attributes(parent, name)
    }.isSuccess

    private fun absoluteAttributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun requireNoSymlinksToProjectRoot(target: Path) {
        var current: Path? = target
        while (current != null) {
            artifactRequire(!Files.isSymbolicLink(current)) {
                "Verification screenshot source path contains a symbolic link"
            }
            if (current.toFile().canonicalFile == paths.projectRoot) return
            current = current.parent
        }
        throw FeedbackVerificationArtifactException(
            "Verification screenshot source escapes the project root",
        )
    }

    private fun copy(input: SeekableByteChannel, output: SeekableByteChannel) {
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

    private fun force(channel: SeekableByteChannel) {
        val fileChannel = channel as? FileChannel
            ?: throw FeedbackVerificationArtifactException(
                "Verification artifacts require a force-capable file channel",
            )
        fileChannel.force(true)
    }

    private fun readOptions(): Set<OpenOption> = setOf(
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun writeOptions(): Set<OpenOption> = setOf(
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )

    private data class DirectoryHandle(
        val stream: SecureDirectoryStream<Path>?,
        val absolute: Path,
        val fileKey: Any,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val MAX_OWNERSHIP_TOKEN_BYTES = 64
    }
}
