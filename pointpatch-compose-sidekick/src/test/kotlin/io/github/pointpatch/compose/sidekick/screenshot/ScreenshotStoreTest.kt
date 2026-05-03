package io.github.pointpatch.compose.sidekick.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScreenshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ScreenshotStore(
        context = context,
        dateProvider = { "2026-05-04" },
    )

    @Test
    fun savesFullAndCropPngUnderPointPatchDateDirectory() = runBlocking {
        val bitmap = bitmap(width = 8, height = 6)

        val info = store.save(
            annotationId = "annotation-1",
            fullBitmap = bitmap,
            selectedBounds = PointPatchRect(left = 2f, top = 1f, right = 6f, bottom = 5f),
        )

        assertEquals(8, info.width)
        assertEquals(6, info.height)
        assertEquals(null, info.captureFailedReason)
        assertPathEndsWith("pointpatch/2026-05-04/annotation-1-full.png", info.fullPath)
        assertPathEndsWith("pointpatch/2026-05-04/annotation-1-crop.png", info.cropPath)
        assertTrue(File(requireNotNull(info.fullPath)).isFile)
        assertTrue(File(requireNotNull(info.cropPath)).isFile)

        val crop = android.graphics.BitmapFactory.decodeFile(info.cropPath)
        assertEquals(4, crop.width)
        assertEquals(4, crop.height)
    }

    @Test
    fun coercesCropBoundsInsideFullBitmap() = runBlocking {
        val bitmap = bitmap(width = 8, height = 6)

        val info = store.save(
            annotationId = "annotation-2",
            fullBitmap = bitmap,
            selectedBounds = PointPatchRect(left = -10f, top = 4f, right = 100f, bottom = 20f),
        )

        val cropPath = requireNotNull(info.cropPath)
        val crop = android.graphics.BitmapFactory.decodeFile(cropPath)
        assertEquals(8, crop.width)
        assertEquals(2, crop.height)
    }

    @Test
    fun skipsCropWhenBoundsHaveNoAreaAfterCoercion() = runBlocking {
        val bitmap = bitmap(width = 8, height = 6)

        val info = store.save(
            annotationId = "annotation-3",
            fullBitmap = bitmap,
            selectedBounds = PointPatchRect(left = 20f, top = 20f, right = 25f, bottom = 25f),
        )

        assertNotNull(info.fullPath)
        assertEquals(null, info.cropPath)
        assertFalse(File(context.cacheDir, "pointpatch/2026-05-04/annotation-3-crop.png").exists())
    }

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }

    private fun assertPathEndsWith(expectedSuffix: String, actualPath: String?) {
        assertNotNull(actualPath)
        assertTrue(
            "Expected path to end with $expectedSuffix but was $actualPath",
            requireNotNull(actualPath).replace(File.separatorChar, '/').endsWith(expectedSuffix),
        )
    }
}
