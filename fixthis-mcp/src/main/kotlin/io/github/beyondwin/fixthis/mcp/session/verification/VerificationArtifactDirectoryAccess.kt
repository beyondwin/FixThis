package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal data class VerificationDirectoryHandle(
    val stream: SecureDirectoryStream<Path>?,
    val absolute: Path,
    val fileKey: Any,
)

internal class VerificationArtifactDirectoryAccess(
    private val paths: VerificationArtifactPaths,
    private val hooks: VerificationArtifactStoreHooks,
) {
    val projectRootPath: Path = paths.projectRoot.toPath()
    val files: VerificationArtifactFileOperations by lazy {
        VerificationArtifactFileOperations(this, hooks)
    }

    fun ensureVerificationRoot(sessionId: String) {
        VerificationArtifactNaming.validateSegment(sessionId, "sessionId")
        withProjectDirectory(emptyList()) { root ->
            ensureDescendant(
                root,
                listOf(
                    ".fixthis",
                    "feedback-sessions",
                    sessionId,
                    VerificationArtifactNaming.VERIFICATION_DIRECTORY,
                ),
                index = 0,
            )
        }
    }

    fun <T> withVerificationDirectory(
        sessionId: String,
        block: (VerificationDirectoryHandle) -> T,
    ): T = withProjectDirectory(
        listOf(".fixthis", "feedback-sessions", sessionId, VerificationArtifactNaming.VERIFICATION_DIRECTORY),
        block,
    )

    fun <T> withVerificationDirectoryIfPresent(
        sessionId: String,
        block: (VerificationDirectoryHandle) -> T,
    ): T? = runCatching {
        withVerificationDirectory(sessionId, block)
    }.getOrNull()

    fun <T> withProjectDirectory(
        segments: List<String>,
        block: (VerificationDirectoryHandle) -> T,
    ): T {
        val rootStream = hooks.rootDirectoryStreamFactory(projectRootPath)
        rootStream.use { stream ->
            val root = VerificationDirectoryHandle(
                stream = stream as? SecureDirectoryStream<Path>,
                absolute = projectRootPath,
                fileKey = stableFileKey(absoluteAttributes(projectRootPath)),
            )
            assertBound(root)
            return descend(root, segments, index = 0, block)
        }
    }

    fun <T> withSourceParent(
        source: Path,
        block: (VerificationDirectoryHandle, String) -> T,
    ): T {
        val relative = projectRootPath.relativize(source)
        val segments = relative.map(Path::toString)
        return withProjectDirectory(segments.dropLast(1)) { parent ->
            block(parent, segments.last())
        }
    }

    fun <T> withChildDirectory(
        parent: VerificationDirectoryHandle,
        name: String,
        block: (VerificationDirectoryHandle) -> T,
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
                val child = VerificationDirectoryHandle(
                    stream = secureDirectoryStream(stream),
                    absolute = childPath,
                    fileKey = stableFileKey(childAttributes),
                )
                assertBound(child)
                return block(child)
            }
        }
        val child = VerificationDirectoryHandle(
            stream = null,
            absolute = childPath,
            fileKey = stableFileKey(childAttributes),
        )
        assertBound(child)
        return block(child)
    }

    fun attributes(parent: VerificationDirectoryHandle, name: String): BasicFileAttributes {
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

    fun entryNames(directory: VerificationDirectoryHandle): List<String> {
        assertBound(directory)
        val result = directory.stream?.map { it.fileName.toString() }?.toList()
            ?: Files.newDirectoryStream(directory.absolute).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
        assertBound(directory)
        return result
    }

    fun assertBound(handle: VerificationDirectoryHandle) {
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

    private fun <T> descend(
        parent: VerificationDirectoryHandle,
        segments: List<String>,
        index: Int,
        block: (VerificationDirectoryHandle) -> T,
    ): T {
        if (index == segments.size) return block(parent)
        val segment = segments[index]
        VerificationArtifactNaming.validateSegment(segment, "path segment")
        return withChildDirectory(parent, segment) { child ->
            descend(child, segments, index + 1, block)
        }
    }
}

internal fun VerificationArtifactDirectoryAccess.entryExists(
    parent: VerificationDirectoryHandle,
    name: String,
): Boolean = runCatching {
    attributes(parent, name)
}.isSuccess

private fun VerificationArtifactDirectoryAccess.ensureDescendant(
    parent: VerificationDirectoryHandle,
    segments: List<String>,
    index: Int,
) {
    if (index == segments.size) return
    val name = segments[index]
    VerificationArtifactNaming.validateSegment(name, "path segment")
    if (!entryExists(parent, name)) files.createTemporaryDirectoryChild(parent, name)
    withChildDirectory(parent, name) { child ->
        ensureDescendant(child, segments, index + 1)
    }
}

private fun secureDirectoryStream(
    stream: DirectoryStream<Path>,
): SecureDirectoryStream<Path> = stream as? SecureDirectoryStream<Path>
    ?: throw FeedbackVerificationArtifactException(
        "Verification artifacts require secure child directory streams",
    )

private fun stableFileKey(attributes: BasicFileAttributes): Any = attributes.fileKey()
    ?: throw FeedbackVerificationArtifactException(
        "Verification artifacts require stable child directory file keys",
    )

private fun absoluteAttributes(path: Path): BasicFileAttributes = Files.readAttributes(
    path,
    BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS,
)
