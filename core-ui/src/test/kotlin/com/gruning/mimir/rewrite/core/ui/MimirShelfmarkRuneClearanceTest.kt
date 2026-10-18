package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Observed: on a Home book at font 1.3 the SNES shelfmark plate covered the left leg of the embossed Mannaz. The plate's ink is in sp and grew with the font, and the rune is
 * placed in the book's own proportions, in dp. The plate and the rune stay apart at every font scale now: the plate is the book's own stamp, a fixed 18 dp plate whose ink does not follow
 * the system font (it could not grow past its height anyway), and its ink is fitted to the room the book leaves it before the rune's left tip.
 *
 * The real [MimirLedgeCoverArt] book (no art) with the real [MimirPlatformShelfmark] in its seat, at the Thor's Home book (120 x 160 dp) and a smaller one, at font 1.0, 1.3 and 2.0, for
 * "SNES", "GB" and the widest code in the table. The plate's right edge is held clear of the rune's left tip, read from the layout and from the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirShelfmarkRuneClearanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f

    private var plate = Rect.Zero
    private var book = Rect.Zero

    private fun mount(platformId: String, bookWidthDp: Float, fontScale: Float) {
        val bookHeightDp = bookWidthDp * 4f / 3f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Box(Modifier.testTag(SeatTag).requiredSize(bookWidthDp.dp, bookHeightDp.dp)) {
                        MimirLedgeCoverArt(
                            model = null,
                            title = "Chrono Trigger",
                            focused = false,
                            caretProgress = 0f,
                            modifier = Modifier.requiredSize(bookWidthDp.dp, bookHeightDp.dp),
                            shelfmark = { Box(Modifier.testTag(PlateTag)) { MimirPlatformShelfmark(platformIdValue = platformId, tier = MimirShelfmarkTier.FOCUSED) } },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        book = compose.onNodeWithTag(SeatTag).fetchSemanticsNode().boundsInWindow
        plate = compose.onNodeWithTag(PlateTag).fetchSemanticsNode().boundsInWindow
    }

    /** The embossed Mannaz's box in window px: [MediaArtBookCover]'s own numbers on the book, the rune lifted above the plate's band as a book that seats a plate does. */
    private fun runeBox(): Rect {
        val spine = maxOf(book.width * 0.075f, 3f * density)
        val foreEdge = maxOf(book.width * 0.055f, 2f * density)
        val leaf = book.width - foreEdge - spine
        val width = leaf * 0.18f
        val height = width * 1.06f
        val cx = book.left + spine + leaf / 2f
        val top = book.top + mimirBookMannazTopDp(book.width / density, book.height / density, MimirShelfmarkBandDp) * density
        return Rect(cx - width / 2f, top, cx + width / 2f, top + height)
    }

    /** The brightness spread of the pixels in [box]: a drawn glyph on the leather varies a lot, a smooth gradient hardly at all. */
    private fun spread(frame: Bitmap, box: Rect): Float {
        val left = box.left.toInt().coerceIn(0, frame.width - 1)
        val top = box.top.toInt().coerceIn(0, frame.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, frame.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, frame.height)
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = frame.getPixel(x, y)
            val luma = 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
            sum += luma
            squares += luma * luma
            count++
        }
        val mean = sum / count
        return kotlin.math.sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    private fun check(platformId: String, bookWidthDp: Float, fontScale: Float) {
        mount(platformId, bookWidthDp, fontScale)
        val rune = runeBox()
        val label = "$platformId on a ${bookWidthDp.toInt()} dp book at font $fontScale"
        val gapX = (rune.left - plate.right) / density
        val gapY = (plate.top - rune.bottom) / density
        println("SHELFMARK $label: plate ${(plate.left - book.left) / density}..${(plate.right - book.left) / density} dp x ${(book.bottom - plate.bottom) / density}..${(book.bottom - plate.top) / density} dp from the bottom, rune ${(rune.left - book.left) / density}..${(rune.right - book.left) / density} dp x ${(book.bottom - rune.bottom) / density}..${(book.bottom - rune.top) / density} dp from the bottom, gap across ${gapX} dp, gap above $gapY dp")
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        val out = File(File("build/test-captures/sweepfix32/round3").apply { mkdirs() }, "shelfmark-${platformId}-w${bookWidthDp.toInt()}-f$fontScale.png")
        val crop = Bitmap.createBitmap(frame, book.left.toInt().coerceAtLeast(0), book.top.toInt().coerceAtLeast(0), book.width.toInt(), book.height.toInt())
        out.outputStream().use { Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false).compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The rune is where the formula says (read from the pixels), the whole of it clear of the plate in one direction or the other.
        val ink = spread(frame, rune)
        assertTrue("$label: the rune is drawn in its box (spread $ink)", ink > Drawn)
        assertTrue(
            "$label: the plate (to ${(plate.right - book.left) / density} dp, up to ${(book.bottom - plate.top) / density} dp) stands clear of the rune (from ${(rune.left - book.left) / density} dp, from ${(book.bottom - rune.bottom) / density} dp up): $gapX across, $gapY above",
            gapX >= MinGapDp || gapY >= MinGapDp,
        )
    }

    @Test fun `SNES at font 1_0 on the Home book`() = check("snes", 120f, 1.0f)
    @Test fun `SNES at font 1_3 on the Home book`() = check("snes", 120f, 1.3f)
    @Test fun `SNES at font 2_0 on the Home book`() = check("snes", 120f, 2.0f)
    @Test fun `GB at font 1_3 on the Home book`() = check("gb", 120f, 1.3f)

    @Test fun `the widest code at font 1_0 on the Home book`() = check(widestId(), 120f, 1.0f)
    @Test fun `the widest code at font 1_3 on the Home book`() = check(widestId(), 120f, 1.3f)
    @Test fun `the widest code at font 2_0 on the Home book`() = check(widestId(), 120f, 2.0f)

    @Test fun `the widest code at font 1_0 on a smaller book`() = check(widestId(), 96f, 1.0f)
    @Test fun `the widest code at font 1_3 on a smaller book`() = check(widestId(), 96f, 1.3f)
    @Test fun `the widest code at font 2_0 on a smaller book`() = check(widestId(), 96f, 2.0f)

    @Test
    fun `the plate is a fixed stamp, as wide at font 2_0 as at font 1_0`() {
        var one = Rect.Zero
        var two = Rect.Zero
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredSize(300.dp, 100.dp)) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.0f)) {
                        Box(Modifier.testTag("one")) { MimirPlatformShelfmark(platformIdValue = "snes", tier = MimirShelfmarkTier.FOCUSED) }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(density, 2.0f)) {
                        Box(Modifier.testTag("two")) { MimirPlatformShelfmark(platformIdValue = "snes", tier = MimirShelfmarkTier.FOCUSED) }
                    }
                }
            }
        }
        compose.waitForIdle()
        one = compose.onNodeWithTag("one").fetchSemanticsNode().boundsInWindow
        two = compose.onNodeWithTag("two").fetchSemanticsNode().boundsInWindow
        println("SHELFMARK stamp: font 1.0 ${one.width / density} x ${one.height / density} dp, font 2.0 ${two.width / density} x ${two.height / density} dp")
        assertEquals("the plate's width", one.width, two.width, 1f)
        assertEquals("the plate's height", one.height, two.height, 1f)
    }

    @Test
    fun `Home books at font 1_3 with the SNES and the GB plates, side by side`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                MimirTheme {
                    androidx.compose.foundation.layout.Row(
                        Modifier.requiredSize(280.dp, 180.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    ) {
                        listOf("snes" to "Chrono Trigger", "gb" to "Tetris").forEach { (id, title) ->
                            Box(Modifier.requiredSize(120.dp, 160.dp).testTag("book-$id")) {
                                MimirLedgeCoverArt(
                                    model = null,
                                    title = title,
                                    focused = false,
                                    caretProgress = 0f,
                                    modifier = Modifier.requiredSize(120.dp, 160.dp),
                                    shelfmark = { Box(Modifier.testTag("plate-$id")) { MimirPlatformShelfmark(platformIdValue = id, tier = MimirShelfmarkTier.FOCUSED) } },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        listOf("snes", "gb").forEach { id ->
            val b = compose.onNodeWithTag("book-$id").fetchSemanticsNode().boundsInWindow
            val p = compose.onNodeWithTag("plate-$id").fetchSemanticsNode().boundsInWindow
            val spine = maxOf(b.width * 0.075f, 3f * density)
            val foreEdge = maxOf(b.width * 0.055f, 2f * density)
            val leaf = b.width - foreEdge - spine
            val rw = leaf * 0.18f
            val rune = Rect(b.left + spine + leaf / 2f - rw / 2f, b.top + mimirBookMannazTopDp(b.width / density, b.height / density, MimirShelfmarkBandDp) * density, b.left + spine + leaf / 2f + rw / 2f, b.top + mimirBookMannazTopDp(b.width / density, b.height / density, MimirShelfmarkBandDp) * density + rw * 1.06f)
            assertTrue("$id: the plate and the rune do not meet", !p.overlaps(rune))
        }
        val first = compose.onNodeWithTag("book-snes").fetchSemanticsNode().boundsInWindow
        val second = compose.onNodeWithTag("book-gb").fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(frame, first.left.toInt(), first.top.toInt(), (second.right - first.left).toInt(), first.height.toInt())
        val out = File(File("build/test-captures/sweepfix32/round3").apply { mkdirs() }, "home-books-1.3-snes-gb.png")
        out.outputStream().use { Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false).compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the PNG was written: $out", out.isFile && out.length() > 1_000L)
    }

    /** The registry id whose code is the widest ink in the table: read from the table's own source, then measured in the plate's type. */
    private fun widestId(): String {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirPlatformShelfmark.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirPlatformShelfmark.kt"),
        ).first(File::isFile).readText()
        val table = source.substringAfter("private val CuratedShelfmarkCodes").substringBefore("\n)\n")
        val entries = Regex("\"([a-z0-9_]+)\"\\s+to\\s+\"([A-Z0-9]+)\"").findAll(table).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("the table was read (${entries.size} entries)", entries.size > 50)
        val measurer = TextMeasurer(createFontFamilyResolver(compose.activity), Density(density, 1f), LayoutDirection.Ltr)
        val style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        val widest = entries.maxByOrNull { entry -> measurer.measure(AnnotatedString(entry.second), style, maxLines = 1, softWrap = false).size.width }!!
        val widestPx = measurer.measure(AnnotatedString(widest.second), style, maxLines = 1, softWrap = false).size.width
        println("SHELFMARK widest code: ${widest.second} (id ${widest.first}), $widestPx px at 10 sp")
        return widest.first
    }

    private companion object {
        const val SeatTag = "book-seat"
        const val PlateTag = "plate"

        /** The least air between the plate's right edge and the rune's left tip. */
        const val MinGapDp = 2f

        /** Brightness spread above which a box holds the drawn emboss. */
        const val Drawn = 15f
    }
}
