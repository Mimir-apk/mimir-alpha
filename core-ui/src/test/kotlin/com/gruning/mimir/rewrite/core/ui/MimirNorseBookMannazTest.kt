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
 * one ᛗ per book, on the Tome: a Tome with no art and no platform wears the cover's own embossed Mannaz, and the pin it can carry is a Mannaz of its own. The cover's
 * `showMannaz` is the switch the Tome library flips while the pin shows: on, the emboss is drawn exactly as it always was (a cover never given the flag is the cover it was); off, the
 * leather is bare where the emboss stood; and a change fades over `mannazFadeMs`, the pin's own clock.
 *
 * Read from the pixels the cover drew, in the box of the emboss ([mimirNorseBookMannazBox], the one formula the drawing uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirNorseBookMannazTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val bookWidthDp = 120f
    private val bookHeightDp = 160f

    private val shown = mutableStateOf(true)

    private fun mount(initial: Boolean, fadeMs: Int = FadeMs) {
        shown.value = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    Box(Modifier.testTag(SeatTag).requiredSize(bookWidthDp.dp, bookHeightDp.dp)) {
                        MimirNorseBookCover(
                            focused = false,
                            eyebrow = "Empty",
                            title = "Tome",
                            showMannaz = shown.value,
                            mannazFadeMs = fadeMs,
                        )
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

    /** The emboss's box in window px: the cover's own formula, on the book the seat holds. */
    private fun mannazBox(book: Rect): Rect {
        val spine = (book.width * 0.085f).coerceAtLeast(2f * density)
        val leaf = (book.width - 2f * spine).coerceAtLeast(1f)
        val box = mimirNorseBookMannazBox(
            bookHeight = book.height,
            leafWidth = leaf,
            widthFraction = MimirNorseBookEmptyMarkFraction,
            maxWidth = MimirNorseBookEmptyMarkMaxDp * density,
            centerYFraction = MimirNorseBookMarkCenterYFraction,
        )
        val cx = book.left + spine + leaf / 2f
        return Rect(cx - box.width / 2f, book.top + box.top, cx + box.width / 2f, book.top + box.top + box.height)
    }

    private fun emboss(): Float {
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        val book = compose.onNodeWithTag(SeatTag).fetchSemanticsNode().boundsInWindow
        val box = mannazBox(book)
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
        val spread = sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
        println("NORSEMANNAZ spread=$spread in $box")
        return spread
    }

    @Test
    fun `a Tome cover draws its embossed Mannaz when asked to, as it always did`() {
        mount(initial = true)
        assertTrue("the emboss is drawn (spread ${emboss()})", emboss() > Drawn)
    }

    @Test
    fun `a Tome cover that is told not to leaves the leather bare where the emboss stood`() {
        mount(initial = false)
        assertTrue("no emboss (spread ${emboss()})", emboss() < Bare)
    }

    @Test
    fun `the emboss fades out and back on the clock it is given`() {
        mount(initial = true)
        val full = emboss()
        shown.value = false
        settle(FadeMs / 2L)
        val middle = emboss()
        settle(FadeMs.toLong())
        val gone = emboss()
        println("NORSEMANNAZ fade: full=$full middle=$middle gone=$gone")
        assertTrue("half way it is fainter than drawn ($full) but not gone (spread $middle)", middle < full - 5f && middle > Bare)
        assertTrue("then it is gone (spread $gone)", gone < Bare)
        shown.value = true
        settle(FadeMs.toLong() + 200L)
        assertTrue("and it comes back (spread ${emboss()})", emboss() > Drawn)
    }

    private companion object {
        const val SeatTag = "tome-seat"

        /** The pin's clock in the Tome library (110 ms), not the default: the two marks cross over on one clock. */
        const val FadeMs = 110

        /** Brightness spread above which a box holds the drawn emboss, and below which it is bare leather. */
        const val Drawn = 15f
        const val Bare = 5f
    }
}
