package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cover upload, through the real staging path (reported 10-09: a 2:1 upload was cut to a square at its sides before any tile drew it). A picture with a
 * white rim goes in; what is staged keeps its shape, and the rim is still on every side of it, so nothing was cropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomImageCoverStageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun rimmed(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF2B6CB0.toInt())
        val rim = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = minOf(width, height) / 10f
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawRect(rim.strokeWidth / 2f, rim.strokeWidth / 2f, width - rim.strokeWidth / 2f, height - rim.strokeWidth / 2f, rim)
        val file = File(temp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun stage(source: File, target: File): Boolean =
        runBlocking { CustomImageImportStore.stageCover(RuntimeEnvironment.getApplication(), Uri.fromFile(source), target) }

    private fun assertRimOnEverySide(staged: Bitmap) {
        val white = 0xFFFFFFFF.toInt()
        val inset = 2
        assertEquals("left rim", white, staged.getPixel(inset, staged.height / 2))
        assertEquals("right rim", white, staged.getPixel(staged.width - 1 - inset, staged.height / 2))
        assertEquals("top rim", white, staged.getPixel(staged.width / 2, inset))
        assertEquals("bottom rim", white, staged.getPixel(staged.width / 2, staged.height - 1 - inset))
    }

    @Test
    fun `a 2 to 1 cover is staged at 2 to 1 with its rim on every side`() {
        val source = rimmed("wide.png", 400, 200)
        val target = File(temp.root, "staged/upload.img")
        assertTrue("the cover was staged", stage(source, target))
        val staged = BitmapFactory.decodeFile(target.absolutePath)
        assertEquals(400, staged.width)
        assertEquals(200, staged.height)
        assertRimOnEverySide(staged)
    }

    @Test
    fun `a large 2 to 1 cover is brought down by its longest edge and stays 2 to 1`() {
        val source = rimmed("large.png", 2_048, 1_024)
        val target = File(temp.root, "staged/large.img")
        assertTrue(stage(source, target))
        val staged = BitmapFactory.decodeFile(target.absolutePath)
        assertEquals(1_024, staged.width)
        assertEquals(512, staged.height)
        assertRimOnEverySide(staged)
    }

    @Test
    fun `a tall cover keeps its portrait shape too`() {
        val source = rimmed("tall.png", 300, 400)
        val target = File(temp.root, "staged/tall.img")
        assertTrue(stage(source, target))
        val staged = BitmapFactory.decodeFile(target.absolutePath)
        assertEquals(300, staged.width)
        assertEquals(400, staged.height)
    }
}
