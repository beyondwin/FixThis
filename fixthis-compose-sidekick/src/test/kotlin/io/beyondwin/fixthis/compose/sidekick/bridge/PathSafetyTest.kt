package io.beyondwin.fixthis.compose.sidekick.bridge

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSafetyTest {
    @Test
    fun rejectsDoubleDotEscapeToEtcPasswd() {
        val parent = tempDirectory("fixthis-cache")
        val child = File(parent, "../../etc/passwd")
        assertFalse(
            "Path traversing out of parent must be rejected",
            PathSafety.isUnder(child, parent),
        )
    }

    @Test
    fun rejectsRelativeDotDotEscape() {
        val parent = tempDirectory("fixthis-cache")
        val child = File(parent, "./../foo")
        assertFalse(PathSafety.isUnder(child, parent))
    }

    @Test
    fun acceptsPathThatTraversesUpThenBackInside() {
        val parent = tempDirectory("fixthis-cache")
        val child = File(parent, "cache/../cache/file.png")
        assertTrue(
            "Canonical resolution should keep the path inside the parent",
            PathSafety.isUnder(child, parent),
        )
    }

    @Test
    fun rejectsSymlinkPointingOutsideParent() {
        val parent = tempDirectory("fixthis-cache")
        val outsideRoot = tempDirectory("fixthis-outside")
        val outsideTarget = File(outsideRoot, "secret.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val symlink = File(parent, "link.png")
        Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())

        assertFalse(
            "Symlink whose target is outside the cache must be rejected",
            PathSafety.isUnder(symlink, parent),
        )
    }

    @Test
    fun acceptsChildWithTrailingSlashedParent() {
        val parent = tempDirectory("fixthis-cache")
        val parentWithSlash = File(parent.path + File.separator)
        val child = File(parent, "screenshot.png").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(PathSafety.isUnder(child, parentWithSlash))
    }

    @Test
    fun acceptsChildWithRepeatedSeparators() {
        val parent = tempDirectory("fixthis-cache")
        val sep = File.separator
        val child = File(parent.path + "$sep$sep" + "screenshot.png").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(
            "Repeated separators must normalise to inside the parent",
            PathSafety.isUnder(child, parent),
        )
    }

    @Test
    fun parentEqualToChildIsInside() {
        val parent = tempDirectory("fixthis-cache")
        assertTrue(
            "A path equal to the parent canonical root is treated as inside (boundary case)",
            PathSafety.isUnder(parent, parent),
        )
    }

    @Test
    fun rejectsSiblingDirectoryWithSharedPrefix() {
        // Guards against the naive `path.startsWith(parent.path)` bug
        // where `/tmp/cache` would incorrectly match `/tmp/cache-evil`.
        val parent = tempDirectory("cache")
        val sibling = File(parent.parentFile, parent.name + "-evil").apply {
            check(mkdirs() || exists())
            deleteOnExit()
        }
        val child = File(sibling, "x.png").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(PathSafety.isUnder(child, parent))
    }

    private fun tempDirectory(prefix: String): File =
        createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
}
