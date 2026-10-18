package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One ᛗ per book: a coverless book wears its embossed Mannaz, except while the game is a favourite, whose plate is a Mannaz of its own. [MediaArtBookCover]'s `showMannaz`
 * (and [MediaArtFallbackGlyph]'s and [MimirLedgeCoverArt]'s, which pass it through) is that switch: on, the emboss is drawn exactly as it always was (a book never given the flag is
 * pixel-for-pixel the book it was); off, the leather is bare where the emboss stood; and a change fades on the favourite plate's own clock ([MimirMotion.FocusMs]).
 *
 * Read from the pixels the book drew, in the box of the emboss's four tips (the book's own proportions, [mimirBookMannazTopDp]): a drawn glyph on the leather varies a lot in
 * brightness, a smooth gradient hardly at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaArtBookMannazTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val bookWidthDp = 120f
    private val bookHeightDp = 160f

    private val shown = mutableStateOf(true)

    private fun mount(initial: Boolean, viaLedge: Boolean = false) {
        shown.value = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    Box(Modifier.testTag(SeatTag).requiredSize(bookWidthDp.dp, bookHeightDp.dp)) {
                        if (viaLedge) {
                            MimirLedgeCoverArt(
                                model = null,
                                title = "Zelda",
                                focused = false,
                                caretProgress = 0f,
                                modifier = Modifier.requiredSize(bookWidthDp.dp, bookHeightDp.dp),
                                showMannaz = shown.value,
                            )
                        } else {
                            MediaArtBookCover(title = "Zelda", platformIdValue = null, showMannaz = shown.value)
                        }
                    }
                }
            }
        }
        settle(1_000L)
    }

    private fun settle(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** The box of the emboss's four tips, in window px: `MediaArtBookCover`'s own numbers on the book at the window's origin. */
    private fun mannazBox(book: Rect): Rect {
        val spine = maxOf(book.width * 0.075f, 3f * density)
        val foreEdge = maxOf(book.width * 0.055f, 2f * density)
        val leaf = book.width - foreEdge - spine
        val width = leaf * 0.18f
        val height = width * 1.06f
        val cx = book.left + spine + leaf / 2f
        val cy = book.top + book.height * 0.84f
        return Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    /** The book's box in the window: the 3:4 book the 120 x 160 seat holds, from where compose put the seat. */
    private fun bookBox(): Rect {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        frame = bitmap
        return compose.onNodeWithTag(SeatTag).fetchSemanticsNode().boundsInWindow
    }

    private lateinit var frame: Bitmap

    private fun spread(box: Rect): Float {
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
        return sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    private fun emboss(): Float {
        val book = bookBox()
        return spread(mannazBox(book)).also { println("MANNAZ spread=$it in ${mannazBox(book)}") }
    }

    @Test
    fun `a book draws its embossed Mannaz by default and when asked to`() {
        mount(initial = true)
        assertTrue("the emboss is drawn (spread ${emboss()})", emboss() > Drawn)
    }

    @Test
    fun `a book that is told not to leaves the leather bare where the emboss stood`() {
        mount(initial = false)
        assertTrue("no emboss (spread ${emboss()})", emboss() < Bare)
    }

    @Test
    fun `the ledge painter's no-art book follows the flag too`() {
        mount(initial = true, viaLedge = true)
        assertTrue("drawn through the ledge painter (spread ${emboss()})", emboss() > Drawn)
        shown.value = false
        settle(1_000L)
        assertTrue("and gone through it (spread ${emboss()})", emboss() < Bare)
    }

    @Test
    fun `the emboss fades out and back on the favourite plate's clock`() {
        mount(initial = true)
        val full = emboss()
        shown.value = false
        settle(MimirMotion.FocusMs / 2L)
        val middle = emboss()
        settle(MimirMotion.FocusMs.toLong())
        val gone = emboss()
        println("MANNAZ fade: middle=$middle gone=$gone")
        assertTrue("half way it is fainter than drawn ($full) but not gone (spread $middle)", middle < full - 10f && middle > Bare)
        assertTrue("then it is gone (spread $gone)", gone < Bare)
        shown.value = true
        settle(MimirMotion.FocusMs.toLong() + 200L)
        assertTrue("and it comes back (spread ${emboss()})", emboss() > Drawn)
    }

    private companion object {
        const val SeatTag = "book-seat"

        /** Brightness spread above which a box holds the drawn emboss, and below which it is bare leather. */
        const val Drawn = 15f
        const val Bare = 5f
    }
}
