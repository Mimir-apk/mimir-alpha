package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.companionnative.createMimirButtonPreviewView
import com.gruning.mimir.rewrite.core.companionnative.setMimirButtonPreviewPlacement
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.mimirMarkDrawRect
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * The player's mark on the floating ᛗ button (concept A, brief §D): the mark's alpha is the mask and the theme's metal is
 * poured through it on the path the rune takes.
 *
 * The button is the REAL `CompanionMenuCoinView`, built by the same factory the picker's stage hosts
 * (`createMimirButtonPreviewView`), measured at 56 dp and drawn into a bitmap; each assertion reads pixels back. A mask is a
 * 256 px PNG whose LEFT half is opaque white ink and whose right half is see-through, so a pixel in the left half of the
 * mark's box is metal and one in the right half is the bare disc.
 *
 * Pixel values are the JVM's (Skia through Robolectric's native graphics); the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionButtonMarkDrawTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dir = File.createTempFile("mark", "dir").apply { delete(); mkdirs() }
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * A 256 px mask: white ink on the left half, nothing on the right except a one-pixel line down its far edge. The line is
     * what keeps the ink's bounding box the whole image: the coin trims a mask to its ink , and without it the
     * left half alone would be cropped and re-centred, and there would be no see-through half left to compare.
     */
    private fun halfMask(name: String): CompanionButtonMark.Image {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 256) for (x in 0 until 128) setPixel(x, y, Color.WHITE)
            for (y in 0 until 256) setPixel(255, y, Color.WHITE)
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CompanionButtonMark.Image(id = name, path = file.absolutePath)
    }

    private fun themeOf(id: MimirThemeId, mark: CompanionButtonMark = CompanionButtonMark.Rune): CompanionOverlayTheme =
        companionOverlayTheme(id, buttonMark = null).copy(mark = mark)

    private fun render(theme: CompanionOverlayTheme, pressed: Boolean = false): Bitmap {
        val view = createMimirButtonPreviewView(context, theme)
        val edge = (56 * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, edge, edge)
        view.isPressed = pressed
        return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun luma(argb: Int): Double = 0.2126 * Color.red(argb) + 0.7152 * Color.green(argb) + 0.0722 * Color.blue(argb)

    @Test
    fun `the mark's alpha is the mask and the metal is poured through it`() {
        val theme = themeOf(MimirThemeId.DEFAULT, halfMask("aaaaaaaa"))
        val struck = render(theme)
        // The same coin with a mask that has no ink: the bare disc and rim, nothing struck.
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("bbbbbbbb")))

        val edge = struck.width
        // The custom mark's box is inset 6 dp (a 44 dp box): 12 px .. edge - 12 px at xhdpi. Sample its left half and its right half, mid height.
        val left = edge / 2 - edge / 8
        val right = edge / 2 + edge / 8
        val mid = edge / 2
        assertNotEquals("the ink half is metal, not the disc", bare.getPixel(left, mid), struck.getPixel(left, mid))
        assertEquals("the see-through half is the bare disc", bare.getPixel(right, mid), struck.getPixel(right, mid))

        // The metal stays inside the mask: a pixel inside the disc but outside the mark's box is the disc, far darker than ink.
        val outsideBox = edge * 7 / 100
        val ink = luma(struck.getPixel(left, mid))
        val disc = luma(struck.getPixel(outsideBox, mid))
        assertTrue("ink $ink must stand well clear of the disc $disc", ink - disc > 25)

        // Struck, not tinted: the metal is a ramp with a top light and a bottom shadow, so the ink is brighter high than low.
        val top = edge * 30 / 100
        val bottom = edge * 70 / 100
        assertTrue("a top light: ${luma(struck.getPixel(left, top))} over ${luma(struck.getPixel(left, bottom))}", luma(struck.getPixel(left, top)) > luma(struck.getPixel(left, bottom)))
    }

    @Test
    fun `the struck mark takes the theme's metal and the press boost as the rune does`() {
        val mark = halfMask("cccccccc")
        val default = render(themeOf(MimirThemeId.DEFAULT, mark))
        val hel = render(themeOf(MimirThemeId.HEL, mark))
        val idle = render(themeOf(MimirThemeId.DEFAULT, mark), pressed = false)
        val pressed = render(themeOf(MimirThemeId.DEFAULT, mark), pressed = true)

        val edge = default.width
        val x = edge / 2 - edge / 8
        val y = edge / 2
        assertNotEquals("a theme change recolours the mark", default.getPixel(x, y), hel.getPixel(x, y))
        assertEquals("the same theme strikes the same metal", default.getPixel(x, y), idle.getPixel(x, y))
        assertNotEquals("a press reaches the metal", idle.getPixel(x, y), pressed.getPixel(x, y))
        // The disc, the rim and the 56 dp box are not the mark's to change: the corner pixel outside the coin is untouched.
        assertEquals(Color.TRANSPARENT, default.getPixel(1, 1))
    }

    @Test
    fun `a mark file that is gone draws the rune and says so`() {
        val gone = CompanionButtonMark.Image(id = "d".repeat(32), path = File(dir, "gone.png").absolutePath)

        val fallback = render(themeOf(MimirThemeId.DEFAULT, gone))
        val rune = render(themeOf(MimirThemeId.DEFAULT, CompanionButtonMark.Rune))

        assertFalse("fixture: the rune puts ink on the disc, so a match below is not two bare discs", rune.sameAs(render(themeOf(MimirThemeId.DEFAULT, blankMask("9999aaaa")))))
        assertTrue("a missing file is the shipping rune, pixel for pixel", fallback.sameAs(rune))
        val lines = ShadowLog.getLogsForTag("MimirCompanionNative").map { it.msg }
        assertTrue("logged: $lines", lines.any { it == "mark=rune reason=missing id=${"d".repeat(32)}" })
        assertFalse("the log names an id, never a path", lines.any { it.contains(dir.name) })

        // A file that is not an image is as good as missing.
        val corrupt = File(dir, "corrupt.png").apply { writeText("not a png") }
        val corruptMark = CompanionButtonMark.Image(id = "e".repeat(32), path = corrupt.absolutePath)
        assertTrue(render(themeOf(MimirThemeId.DEFAULT, corruptMark)).sameAs(rune))
        // A mark that exists is NOT the rune's drawable.
        val view = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, halfMask("ffffffff")))
        assertTrue(ReflectionHelpers.getField<Any?>(view, "mark") is BitmapDrawable)
    }

    /** A 256 px mask that is solid ink edge to edge, so the ink's extent IS the mark's box. */
    private fun fullMask(name: String): CompanionButtonMark.Image {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CompanionButtonMark.Image(id = name, path = file.absolutePath)
    }

    /** The horizontal extent of the ink on the mid row, in px: where [coin] differs from the same coin with no mark at all. */
    private fun inkWidthPx(coin: Bitmap, bare: Bitmap): Int {
        val mid = coin.height / 2
        val xs = (0 until coin.width).filter { coin.getPixel(it, mid) != bare.getPixel(it, mid) }
        return if (xs.isEmpty()) 0 else xs.last() - xs.first() + 1
    }

    /** Where the ink starts and stops on the mid row, in px, inclusive. */
    private fun inkSpanPx(coin: Bitmap, bare: Bitmap): IntRange {
        val mid = coin.height / 2
        val xs = (0 until coin.width).filter { coin.getPixel(it, mid) != bare.getPixel(it, mid) }
        return xs.first()..xs.last()
    }

    @Test
    fun `the player's own image is drawn 44 dp, about 1_47 times the rune's box, inside the coin face`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("12345678")))
        val custom = render(themeOf(MimirThemeId.DEFAULT, fullMask("11112222")))

        // The old box: the coin's 56 dp less 13 dp a side, 30 dp. A solid mask inks exactly its box.
        val oldBoxPx = 30f * density
        val drawn = inkWidthPx(custom, bare)
        assertTrue("drawn $drawn px must be at least 1.4 x the old $oldBoxPx px", drawn >= 1.4f * oldBoxPx)
        assertEquals("the box is 44 dp", 44f * density, drawn.toFloat(), 2f)

        // Inside the coin face: the circle the rim leaves. The rim is 1.25 dp, stroked on the disc's edge, so its inner edge is
        // the radius less half the stroke. The mid-row ink is the box's width, which fits the face's diameter.
        val edge = custom.width
        val rimPx = 1.25f * density
        val ringInnerRadiusPx = edge / 2f - rimPx - rimPx / 2f
        assertTrue("drawn $drawn px must fit the face's ${2 * ringInnerRadiusPx} px diameter", drawn <= 2 * ringInnerRadiusPx)

        // Struck about the same centre, not nudged to one side.
        val span = inkSpanPx(custom, bare)
        assertEquals("centred", (edge - 1).toDouble(), (span.first + span.last).toDouble(), 2.0)

        // The drawn box is the named token's, not a number typed in two places.
        val view = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, fullMask("33334444")))
        val insetDp = ReflectionHelpers.getField<Float>(view, "markInsetDp")
        assertEquals(6f, insetDp, 0f)
        assertEquals((56f - 2f * insetDp) / 30f, 44f / 30f, 0.001f)
    }

    /** A 256 px mask whose ink is a [inkWidth] x [inkHeight] white rectangle in the middle of a fully transparent image. */
    private fun paddedMask(name: String, inkWidth: Int, inkHeight: Int): CompanionButtonMark.Image {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in (256 - inkHeight) / 2 until (256 + inkHeight) / 2) for (x in (256 - inkWidth) / 2 until (256 + inkWidth) / 2) setPixel(x, y, Color.WHITE)
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CompanionButtonMark.Image(id = name, path = file.absolutePath)
    }

    /** The vertical extent of the ink on the mid column, in px. */
    private fun inkHeightPx(coin: Bitmap, bare: Bitmap): Int {
        val mid = coin.width / 2
        val ys = (0 until coin.height).filter { coin.getPixel(mid, it) != bare.getPixel(mid, it) }
        return if (ys.isEmpty()) 0 else ys.last() - ys.first() + 1
    }

    @Test
    fun `a mask's own transparent padding is trimmed, so a padded image fills the box and a plain one does not read small`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("aaaa1111")))
        // 64 px of ink in the middle of 256 px of nothing: untrimmed it is a quarter of the box, 22 px at 88.
        val padded = render(themeOf(MimirThemeId.DEFAULT, paddedMask("aaaa2222", 64, 64)))

        val boxPx = 44f * density
        val drawn = inkWidthPx(padded, bare)
        assertTrue("drawn $drawn px must fill the $boxPx px box (an untrimmed one would be ${boxPx / 4f})", drawn >= 0.9f * boxPx)
        assertTrue("and stay inside it", drawn <= boxPx)
        // The trim is by ink, not by a fixed fraction: a logo that already reaches its edges is drawn the same.
        val full = render(themeOf(MimirThemeId.DEFAULT, fullMask("aaaa3333")))
        assertEquals("a padded square and a solid one fill the same box, to the margin", inkWidthPx(full, bare).toFloat(), drawn.toFloat(), boxPx * 0.1f)
    }

    @Test
    fun `a trimmed mask that is not square keeps its aspect and its centre`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("bbbb1111")))
        // 128 x 32 of ink: a 4:1 bar. Trimmed to 132 x 36 and fitted into the 88 px box: 88 x 24, ink 85 x 21.
        val bar = render(themeOf(MimirThemeId.DEFAULT, paddedMask("bbbb2222", 128, 32)))

        val width = inkWidthPx(bar, bare)
        val height = inkHeightPx(bar, bare)
        val boxPx = 44f * density
        assertTrue("wide: $width px of the $boxPx px box", width >= 0.9f * boxPx)
        assertEquals("aspect kept, 4:1", 4f, width.toFloat() / height, 0.5f)
        val span = inkSpanPx(bar, bare)
        assertEquals("centred across", (bar.width - 1).toDouble(), (span.first + span.last).toDouble(), 2.0)
        val mid = bar.width / 2
        val ys = (0 until bar.height).filter { bar.getPixel(mid, it) != bare.getPixel(mid, it) }
        assertEquals("and centred down", (bar.height - 1).toDouble(), (ys.first() + ys.last()).toDouble(), 2.0)
    }

    @Test
    fun `no inked pixel is farther from the centre than the coin face, so nothing ever crosses the rim`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("cccc1111")))
        val custom = render(themeOf(MimirThemeId.DEFAULT, fullMask("cccc2222")))

        val edge = custom.width
        val rimPx = 1.25f * density
        val faceRadiusPx = edge / 2f - rimPx - rimPx / 2f
        var farthest = 0.0
        for (y in 0 until edge) for (x in 0 until edge) {
            if (custom.getPixel(x, y) != bare.getPixel(x, y)) {
                val distance = Math.hypot(x + 0.5 - edge / 2.0, y + 0.5 - edge / 2.0)
                if (distance > farthest) farthest = distance
            }
        }
        // One pixel for the clip's anti-aliased edge.
        assertTrue("ink reaches $farthest px from the centre, past the face's $faceRadiusPx px", farthest <= faceRadiusPx + 1.0)
        // A solid 44 dp square's corners stand 62 px out at xhdpi: the clip is what holds the ink at the face.
        assertTrue("the clip, not the box, bounds the ink: it reaches $farthest px of the face's $faceRadiusPx px", farthest >= faceRadiusPx - 3.0)
    }

    @Test
    fun `the default rune is drawn exactly as it was, in its own 30 dp box`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("56785678")))
        val rune = render(themeOf(MimirThemeId.DEFAULT, CompanionButtonMark.Rune))

        val view = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, CompanionButtonMark.Rune))
        assertEquals("the rune keeps its 13 dp inset", 13f, ReflectionHelpers.getField<Float>(view, "markInsetDp"), 0f)

        // Every pixel of rune ink lies inside the old box: 13 dp in from each side of the 56 dp coin.
        val edge = rune.width
        val oldLow = (13f * density).toInt()
        val oldHigh = edge - oldLow
        var inked = 0
        for (y in 0 until edge) for (x in 0 until edge) {
            if (rune.getPixel(x, y) != bare.getPixel(x, y)) {
                inked++
                assertTrue("rune ink at ($x,$y) is inside the old box", x in oldLow until oldHigh && y in oldLow until oldHigh)
            }
        }
        assertTrue("the rune is drawn at all", inked > 0)
        // A custom image whose file is gone draws the rune and keeps the rune's size, not the grown one.
        val gone = CompanionButtonMark.Image(id = "f".repeat(32), path = File(dir, "gone-again.png").absolutePath)
        val fallbackView = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, gone))
        assertEquals(13f, ReflectionHelpers.getField<Float>(fallbackView, "markInsetDp"), 0f)
    }

    // ---- the player's own placement (size and place your own image) -----------------------------------------------

    /**
     * A 256 px mask whose ink is a triangle, apex up, on a transparent ground: the synthetic shape the placement is read off, because a
     * triangle shows size, position and which way is up. Its ink box is the whole of its trimmed mask, so the box the coin fits is the ink's.
     */
    private fun triangleMask(name: String): CompanionButtonMark.Image {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = android.graphics.Path().apply {
            moveTo(128f, 8f)
            lineTo(248f, 248f)
            lineTo(8f, 248f)
            close()
        }
        canvas.drawPath(path, android.graphics.Paint().apply { color = Color.WHITE; style = android.graphics.Paint.Style.FILL })
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CompanionButtonMark.Image(id = name, path = file.absolutePath)
    }

    private class InkBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val centreX get() = (left + right + 1) / 2f
        val centreY get() = (top + bottom + 1) / 2f
    }

    /** The box of every pixel [coin] draws differently from [bare], or null when it drew nothing. */
    private fun inkBox(coin: Bitmap, bare: Bitmap): InkBox? {
        var left = coin.width
        var top = coin.height
        var right = -1
        var bottom = -1
        for (y in 0 until coin.height) for (x in 0 until coin.width) {
            if (coin.getPixel(x, y) != bare.getPixel(x, y)) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < 0) null else InkBox(left, top, right, bottom)
    }

    private fun placed(mark: CompanionButtonMark.Image, scale: Float, offsetX: Float, offsetY: Float) =
        mark.copy(placement = MimirMarkPlacement(scale, offsetX, offsetY))

    @Test
    fun `a placement moves the ink by its offset and the default is the centred image it always was`() {
        val density = context.resources.displayMetrics.density
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("dddd0001")))
        val mark = triangleMask("dddd0002")
        // Small enough that no corner meets the face clip, so the box moves whole.
        val centred = inkBox(render(themeOf(MimirThemeId.DEFAULT, placed(mark, 0.7f, 0f, 0f))), bare)!!
        val explicitDefault = render(themeOf(MimirThemeId.DEFAULT, mark.copy(placement = MimirMarkPlacement.Default)))
        assertTrue("no placement is the default placement, pixel for pixel", explicitDefault.sameAs(render(themeOf(MimirThemeId.DEFAULT, mark))))

        val moved = inkBox(render(themeOf(MimirThemeId.DEFAULT, placed(mark, 0.7f, 6f, -4f))), bare)!!
        assertEquals("right by 6 dp", 6f * density, moved.centreX - centred.centreX, 2f)
        assertEquals("up by 4 dp", -4f * density, moved.centreY - centred.centreY, 2f)
        assertEquals("a move does not size", centred.width.toFloat(), moved.width.toFloat(), 2f)
        assertEquals(centred.height.toFloat(), moved.height.toFloat(), 2f)
        // The default sits on the coin's centre, as the box always did.
        val edge = explicitDefault.width
        val whole = inkBox(explicitDefault, bare)!!
        assertEquals("centred across", edge / 2f, whole.centreX, 2f)
    }

    @Test
    fun `a placement sizes the ink by its scale about the image's own centre`() {
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("dddd0003")))
        val mark = triangleMask("dddd0004")
        val small = inkBox(render(themeOf(MimirThemeId.DEFAULT, placed(mark, 0.6f, 0f, 0f))), bare)!!
        val larger = inkBox(render(themeOf(MimirThemeId.DEFAULT, placed(mark, 0.8f, 0f, 0f))), bare)!!
        assertEquals("0.8 over 0.6 of the box", 0.8f / 0.6f, larger.width.toFloat() / small.width, 0.06f)
        assertEquals(0.8f / 0.6f, larger.height.toFloat() / small.height, 0.06f)
        assertEquals("both about the coin's centre", small.centreX, larger.centreX, 2f)
        assertEquals(small.centreY, larger.centreY, 2f)
        // The shared geometry's own numbers: a 0.6 box is 26.4 dp, which is 52.8 px at xhdpi.
        assertEquals(0.6f * 44f * context.resources.displayMetrics.density, small.width.toFloat(), 3f)
    }

    @Test
    fun `the coin draws the rect the shared geometry gives, for the placements the picker can make`() {
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("dddd0005")))
        val mark = triangleMask("dddd0006")
        val edge = bare.width
        // The triangle's trimmed mask is square (8..248 of 256 plus the 2 px margin each side), so the drawn rect is the box and the ink fills 240 of 244.
        val inkShare = 240f / 244f
        for (p in listOf(MimirMarkPlacement(0.6f, 0f, 0f), MimirMarkPlacement(0.7f, -5f, 4f), MimirMarkPlacement(0.65f, 6.5f, 6f), MimirMarkPlacement(0.8f, 0f, -3.5f))) {
            val rect = mimirMarkDrawRect(edge.toFloat(), 244, 244, p)
            val ink = inkBox(render(themeOf(MimirThemeId.DEFAULT, mark.copy(placement = p))), bare)!!
            assertEquals("$p width", rect.width * inkShare, ink.width.toFloat(), 3f)
            assertEquals("$p centre x", rect.centerX, ink.centreX, 3f)
            assertEquals("$p centre y", rect.centerY, ink.centreY, 3f)
        }
    }

    @Test
    fun `a placement set on a built coin repaints it in place, and the face clip still bounds the ink`() {
        val bare = render(themeOf(MimirThemeId.DEFAULT, blankMask("dddd0007")))
        val mark = triangleMask("dddd0008")
        val view = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, mark))
        fun draw(): Bitmap {
            val edge = (56 * context.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, edge, edge)
            return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val before = draw()
        setMimirButtonPreviewPlacement(view, MimirMarkPlacement(0.7f, 6f, 0f))
        val after = draw()
        assertFalse("the same view now draws the moved image", before.sameAs(after))
        assertTrue("and it is the same pixels a coin built with that placement draws", after.sameAs(render(themeOf(MimirThemeId.DEFAULT, placed(mark, 0.7f, 6f, 0f)))))
        setMimirButtonPreviewPlacement(view, MimirMarkPlacement.Default)
        assertTrue("setting the default back is the coin it was", draw().sameAs(before))

        // The largest size at the farthest offset is clipped to the face: nothing crosses the rim.
        val density = context.resources.displayMetrics.density
        val rimPx = 1.25f * density
        val faceRadiusPx = bare.width / 2f - rimPx - rimPx / 2f
        val worst = render(themeOf(MimirThemeId.DEFAULT, placed(fullMask("dddd0009"), 1.6f, 12f, 12f)))
        var farthest = 0.0
        for (y in 0 until worst.height) for (x in 0 until worst.width) {
            if (worst.getPixel(x, y) != bare.getPixel(x, y)) farthest = maxOf(farthest, Math.hypot(x + 0.5 - worst.width / 2.0, y + 0.5 - worst.height / 2.0))
        }
        assertTrue("ink reaches $farthest px, the face is $faceRadiusPx px", farthest <= faceRadiusPx + 1.0)
    }

    @Test
    fun `the rune is not the player's to move, so its pixels are the same whatever placement the coin holds`() {
        val rune = render(themeOf(MimirThemeId.DEFAULT, CompanionButtonMark.Rune))
        val view = createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, CompanionButtonMark.Rune))
        setMimirButtonPreviewPlacement(view, MimirMarkPlacement(1.6f, 12f, -12f))
        val edge = (56 * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, edge, edge)
        val moved = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        assertTrue("the rune is byte-for-byte the rune", moved.sameAs(rune))
        // A custom image whose file is gone draws the rune and keeps it, whatever placement it carries.
        val gone = CompanionButtonMark.Image(id = "9".repeat(32), path = File(dir, "gone-placed.png").absolutePath, placement = MimirMarkPlacement(1.5f, 9f, 9f))
        assertTrue(render(themeOf(MimirThemeId.DEFAULT, gone)).sameAs(rune))
        assertEquals(13f, ReflectionHelpers.getField<Float>(createMimirButtonPreviewView(context, themeOf(MimirThemeId.DEFAULT, gone)), "markInsetDp"), 0f)
    }

    /** A 256 px mask with no ink at all. */
    private fun blankMask(name: String): CompanionButtonMark.Image {
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CompanionButtonMark.Image(id = name, path = file.absolutePath)
    }
}
