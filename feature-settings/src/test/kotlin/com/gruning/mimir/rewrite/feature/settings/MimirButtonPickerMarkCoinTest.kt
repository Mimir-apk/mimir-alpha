package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceBoxDp
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceCoinDp
import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceFaceRadiusDp
import com.gruning.mimir.rewrite.core.model.mimirMarkDrawRect
import com.gruning.mimir.rewrite.core.model.mimirMarkInkBounds
import com.gruning.mimir.rewrite.core.model.mimirMarkTrimCrop
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The Mimir-button picker's small image tiles match the live button's trimmed mark. The live coin trims a mask to its ink, fits it by aspect in a box of 44 dp on its 56 dp, and clips it to the coin face
 * (26.125 dp of radius). [MarkCoin], which the picker's tiles, its stage and the Settings row all draw, takes the same numbers
 * scaled to its own diameter (`MimirButtonMarkGeometry.kt` in `:core-model`; `CompanionMarkSharedGeometryTest` proves the live
 * coin reads them too).
 *
 * The coin is the real [MarkCoin] on a real layout pass; its pixels are the view hierarchy's own, drawn into a software bitmap
 * (`captureToImage` waits on a redraw Robolectric's paused looper never delivers; MimirArtPlateLawTest says so). INK is a pixel
 * that differs from the same coin with no mark (a mask file that is not there), so the disc, the rim and the host never count.
 * Pixel values are the JVM's (Skia through Robolectric); the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirButtonPickerMarkCoinTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File
    private val density = 2f // xhdpi

    private class Spec(val path: String?, val diameter: Dp, val placement: MimirMarkPlacement)

    private var spec by mutableStateOf<Spec?>(null)
    private var mounted = false

    @Before
    fun setUp() {
        dir = File.createTempFile("markcoin", "dir").apply { delete(); mkdirs() }
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- masks -------------------------------------------------------------------------------------------------------

    private fun maskBitmap(canvasPx: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        val ink = Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        draw(Canvas(bitmap), ink)
        return bitmap
    }

    private fun write(name: String, bitmap: Bitmap): String {
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.absolutePath
    }

    /** A 128 px white disc on a transparent [canvasPx] square, centred at ([cx], [cy]): the padding is whatever the canvas leaves. */
    private fun disc(canvasPx: Int, cx: Float, cy: Float): Bitmap = maskBitmap(canvasPx) { canvas, ink -> canvas.drawCircle(cx, cy, 64f, ink) }

    /** An opaque white square over the whole canvas: no padding at all, and corners the coin face must round. */
    private fun solid(canvasPx: Int): Bitmap = maskBitmap(canvasPx) { canvas, ink -> canvas.drawRect(0f, 0f, canvasPx.toFloat(), canvasPx.toFloat(), ink) }

    private fun pixelsOf(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /** The ink's width and the longer side of the trimmed mask, in mask pixels, by the shared rules the live button trims with. */
    private fun inkAndTrimmedSide(bitmap: Bitmap): Pair<Int, Int> {
        val pixels = pixelsOf(bitmap)
        val ink = mimirMarkInkBounds(pixels, bitmap.width, bitmap.height)!!
        val crop = mimirMarkTrimCrop(ink, bitmap.width, bitmap.height)
        val trimmedWidth = if (crop == null) bitmap.width else crop[2] - crop[0]
        val trimmedHeight = if (crop == null) bitmap.height else crop[3] - crop[1]
        return (ink[2] - ink[0] + 1) to max(trimmedWidth, trimmedHeight)
    }

    // ---- the coin on screen ---------------------------------------------------------------------------------------------

    private fun mount(path: String?, diameter: Dp, placement: MimirMarkPlacement = MimirMarkPlacement.Default) {
        val next = Spec(path, diameter, placement)
        if (!mounted) {
            mounted = true
            spec = next
            compose.setContent {
                val current = spec
                if (current != null) {
                    MimirTheme {
                        Box(Modifier.size(HostDp.dp).testTag(Tag), contentAlignment = Alignment.Center) {
                            MarkCoin(path = current.path, diameter = current.diameter, placement = current.placement)
                        }
                    }
                }
            }
        } else {
            compose.runOnIdle { spec = next }
        }
        compose.waitForIdle()
    }

    /** The host's pixels, drawn by the view hierarchy itself into a software bitmap and cropped to the host. */
    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag(Tag).fetchSemanticsNode().boundsInWindow
        return Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
    }

    /** The coin with no mark: the path names a file that is not there, so the thumbnail never arrives and nothing is struck. */
    private fun bare(diameter: Dp): Bitmap {
        mount(File(dir, "not-there.png").absolutePath, diameter)
        return frame()
    }

    /**
     * The coin with [path] struck once its mask has decoded. The decode runs on IO, which `waitForIdle` does not wait for, so this
     * polls until the frame differs from [bare]; a mark that never arrives fails here rather than passing as an empty frame.
     */
    private fun struck(path: String, diameter: Dp, bare: Bitmap, placement: MimirMarkPlacement = MimirMarkPlacement.Default): Bitmap {
        mount(path, diameter, placement)
        val bareBits = pixelsOf(bare)
        val deadline = System.nanoTime() + 20_000_000_000L
        var shot = frame()
        while (inkIndices(pixelsOf(shot), bareBits).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(50)
            shot = frame()
        }
        assertTrue("the mark never arrived on a ${diameter.value} dp coin: the frame is still the bare coin", inkIndices(pixelsOf(shot), bareBits).isNotEmpty())
        return shot
    }

    private fun inkIndices(shot: IntArray, bare: IntArray): List<Int> = shot.indices.filter { shot[it] != bare[it] }

    private class Ink(val width: Int, val left: Int, val right: Int, val top: Int, val bottom: Int, val maxRadiusPx: Float, val centreX: Float, val centreY: Float) {
        val height: Int get() = bottom - top + 1
    }

    private fun measure(shot: Bitmap, bare: Bitmap): Ink {
        val width = shot.width
        val indices = inkIndices(pixelsOf(shot), pixelsOf(bare))
        assertTrue("the mark drew nothing", indices.isNotEmpty())
        val cx = shot.width / 2f
        val cy = shot.height / 2f
        val xs = indices.map { it % width }
        val ys = indices.map { it / width }
        val farthest = indices.maxOf { hypot((it % width) + 0.5f - cx, (it / width) + 0.5f - cy) }
        return Ink(
            width = xs.max() - xs.min() + 1, left = xs.min(), right = xs.max(), top = ys.min(), bottom = ys.max(),
            maxRadiusPx = farthest, centreX = cx, centreY = cy,
        )
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a padded mark on a picker tile fills the same share of the coin as on the live button`() {
        val padded = disc(canvasPx = 256, cx = 90f, cy = 170f)
        val (inkPx, trimmedSide) = inkAndTrimmedSide(padded)
        val path = write("padded", padded)
        for (diameterDp in listOf(36, 56)) {
            val diameterPx = diameterDp * density
            val bareCoin = bare(diameterDp.dp)
            val ink = measure(struck(path, diameterDp.dp, bareCoin), bareCoin)
            // The live button: the trimmed ink fitted in 44 of its 56 dp, scaled to this coin.
            val expectedPx = diameterPx * (MimirMarkReferenceBoxDp / MimirMarkReferenceCoinDp) * inkPx / trimmedSide
            assertTrue(
                "$diameterDp dp tile: ink ${ink.width} px across, the live button's share is $expectedPx px",
                abs(ink.width - expectedPx) <= INK_TOLERANCE_PX,
            )
            assertTrue("clearly bigger than the old untrimmed 0.3 of the coin: ${ink.width / diameterPx}", ink.width / diameterPx > 0.7f)
            // Centred on the coin, not left where the padding put it in the mask.
            assertTrue("centred: ${ink.left}..${ink.right} on a ${ink.centreX} px middle", abs((ink.left + ink.right + 1) / 2f - ink.centreX) <= INK_TOLERANCE_PX)
        }
    }

    @Test
    fun `the padding in the file makes no difference once it is trimmed`() {
        val lots = write("lots", disc(canvasPx = 256, cx = 90f, cy = 170f))
        val little = write("little", disc(canvasPx = 140, cx = 70f, cy = 70f))
        val bareCoin = bare(36.dp)
        val a = struck(lots, 36.dp, bareCoin)
        mount(File(dir, "not-there.png").absolutePath, 36.dp)
        val b = struck(little, 36.dp, bareCoin)
        assertArrayEquals("a mask with a great deal of padding and one with little draw the same coin", pixelsOf(a), pixelsOf(b))
    }

    @Test
    fun `no inked pixel lies outside the coin face, on the tile, the stage and the Settings row`() {
        // A solid square has the corners that reach furthest: the box's corners stand 31 dp from the centre of a 56 dp coin,
        // past the 26.125 dp face, so it is the face clip alone that holds them in.
        val path = write("solid", solid(256))
        for (diameterDp in listOf(20, 36, 56)) {
            val diameterPx = diameterDp * density
            val faceRadiusPx = diameterPx * (MimirMarkReferenceFaceRadiusDp / MimirMarkReferenceCoinDp)
            val bareCoin = bare(diameterDp.dp)
            val ink = measure(struck(path, diameterDp.dp, bareCoin), bareCoin)
            assertTrue("$diameterDp dp coin: ink reaches ${ink.maxRadiusPx} px, the face is $faceRadiusPx px", ink.maxRadiusPx <= faceRadiusPx + 1f)
            assertTrue("$diameterDp dp coin: the clip is what bounds it (${ink.maxRadiusPx} of $faceRadiusPx px)", ink.maxRadiusPx >= faceRadiusPx - 2f)
        }
    }

    @Test
    fun `a placement moves and sizes the ink on a tile by the shared geometry, on every coin size`() {
        val padded = disc(canvasPx = 256, cx = 90f, cy = 170f)
        val (inkPx, trimmedSide) = inkAndTrimmedSide(padded)
        val path = write("placed", padded)
        val placements = listOf(
            MimirMarkPlacement.Default,
            MimirMarkPlacement(scale = 0.7f, offsetX = 6f, offsetY = -6f),
            MimirMarkPlacement(scale = 0.6f, offsetX = -9f, offsetY = 5f),
            MimirMarkPlacement(scale = 1.0f, offsetX = 4f, offsetY = 3.5f),
        )
        for (diameterDp in listOf(36, 56)) {
            val diameterPx = diameterDp * density
            val bareCoin = bare(diameterDp.dp)
            for (placement in placements) {
                val rect = mimirMarkDrawRect(diameterPx, trimmedSide, trimmedSide, placement)
                val ink = measure(struck(path, diameterDp.dp, bareCoin, placement), bareCoin)
                val expectedInk = rect.width * inkPx / trimmedSide
                val label = "$diameterDp dp coin, $placement"
                assertTrue("$label: ink ${ink.width} x ${ink.height} px, the shared geometry says $expectedInk", abs(ink.width - expectedInk) <= INK_TOLERANCE_PX && abs(ink.height - expectedInk) <= INK_TOLERANCE_PX)
                // The coin sits in the middle of its host, so the geometry's coin coordinates are offset by the host's centre.
                val expectedX = ink.centreX + (rect.centerX - diameterPx / 2f)
                val expectedY = ink.centreY + (rect.centerY - diameterPx / 2f)
                assertTrue("$label: ink centre ${(ink.left + ink.right + 1) / 2f}, ${(ink.top + ink.bottom + 1) / 2f} against $expectedX, $expectedY",
                    abs((ink.left + ink.right + 1) / 2f - expectedX) <= INK_TOLERANCE_PX && abs((ink.top + ink.bottom + 1) / 2f - expectedY) <= INK_TOLERANCE_PX)
            }
        }
    }

    @Test
    fun `a placement never lets the ink cross the coin face, and a larger size is clipped by it`() {
        val path = write("solid-placed", solid(256))
        val diameterPx = 56 * density
        val faceRadiusPx = diameterPx * (MimirMarkReferenceFaceRadiusDp / MimirMarkReferenceCoinDp)
        val bareCoin = bare(56.dp)
        for (placement in listOf(MimirMarkPlacement(1.6f, 12f, 12f), MimirMarkPlacement(1.6f, -12f, 0f), MimirMarkPlacement(1.3f, 0f, -12f))) {
            val ink = measure(struck(path, 56.dp, bareCoin, placement), bareCoin)
            assertTrue("$placement: ink reaches ${ink.maxRadiusPx} px, the face is $faceRadiusPx px", ink.maxRadiusPx <= faceRadiusPx + 1f)
        }
    }

    @Test
    fun `the default placement is the same coin as before placement existed`() {
        val padded = write("same", disc(canvasPx = 256, cx = 90f, cy = 170f))
        val bareCoin = bare(36.dp)
        val plain = struck(padded, 36.dp, bareCoin)
        val explicit = struck(padded, 36.dp, bareCoin, MimirMarkPlacement(1f, 0f, 0f))
        assertArrayEquals(pixelsOf(plain), pixelsOf(explicit))
    }

    @Test
    fun `the default rune is still a glyph, not a mark`() {
        mount(null, 36.dp)
        assertEquals(1, compose.onAllNodes(hasText("ᛗ"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the loader trims a padded mask to its ink, leaves an empty one whole, and finds a missing one null`() {
        val padded = disc(canvasPx = 256, cx = 90f, cy = 170f)
        val (inkPx, _) = inkAndTrimmedSide(padded)
        val trimmed = decodeTrimmedMarkMask(write("padded", padded))
        assertNotNull(trimmed)
        assertEquals("the ink and the shared margin on each side", inkPx + 2 * 2, trimmed!!.width)
        assertEquals(trimmed.width, trimmed.height)

        val empty = decodeTrimmedMarkMask(write("empty", maskBitmap(256) { _, _ -> }))
        assertNotNull(empty)
        assertEquals("no ink, nothing to trim to", 256, empty!!.width)
        assertEquals(256, empty.height)

        assertNull(decodeTrimmedMarkMask(File(dir, "not-there.png").absolutePath))
        assertNull(decodeTrimmedMarkMask(File(dir, "garbage.png").apply { writeText("not an image") }.absolutePath))
        assertTrue("the loader says nothing, and never the path", ShadowLog.getLogs().none { it.msg?.contains(dir.name) == true })
    }

    @Test
    fun `the loader reads the app's copy and never rewrites it`() {
        val padded = disc(canvasPx = 256, cx = 90f, cy = 170f)
        val path = write("padded", padded)
        val before = File(path).readBytes()
        decodeTrimmedMarkMask(path)
        assertArrayEquals("trimming reads the file and never rewrites it", before, File(path).readBytes())
        val onDisk = BitmapFactory.decodeFile(path)
        assertEquals("the app's copy keeps its padding", 256, onDisk.width)
    }

    private companion object {
        const val Tag = "mark-coin-host"
        const val HostDp = 80
        /** The coin is scaled and filtered, so its edge is a pixel or two soft either side. */
        const val INK_TOLERANCE_PX = 4f
    }
}
