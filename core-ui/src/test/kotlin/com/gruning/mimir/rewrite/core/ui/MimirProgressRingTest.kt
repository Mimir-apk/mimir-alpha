package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The progress ring: the fraction arithmetic, the one spoken node, and sampled pixels at empty, partial and full, on the JVM's native
 * graphics (the pixels on a handheld are device-owed). The ring is 64 dp at 2 px per dp here, so the arc's centreline is 29 dp (58 px) from the middle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirProgressRingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)
    private val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
    private val tag = "ring-host"

    private var done by mutableStateOf(0)
    private var total by mutableStateOf(40)
    private var subject by mutableStateOf<String?>(null)

    @Composable
    private fun Host() {
        MimirTheme {
            Box(Modifier.size(80.dp).background(ground).testTag(tag), contentAlignment = Alignment.Center) {
                MimirProgressRing(done = done, total = total, palette = palette, size = 64.dp, stroke = 6.dp, subject = subject)
            }
        }
    }

    private fun render(): Bitmap {
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
    }

    private fun mount() {
        compose.setContent { Host() }
    }

    /** The pixel at [degrees] clockwise from twelve o'clock on the arc's centreline. */
    private fun sample(bitmap: Bitmap, degrees: Double): Int {
        val cx = bitmap.width / 2.0
        val cy = bitmap.height / 2.0
        val r = 29.0 * 2.0
        val rad = Math.toRadians(degrees)
        return bitmap.getPixel((cx + r * Math.sin(rad)).toInt(), (cy - r * Math.cos(rad)).toInt())
    }

    private fun near(expected: Color, actual: Int, tolerance: Int = 6): Boolean {
        val e = expected.toArgb()
        return listOf(16, 8, 0).all { shift -> abs(((e shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= tolerance }
    }

    private val accent get() = palette.accent.compositeOver(ground)
    private val track get() = palette.textSecondary.copy(alpha = MimirProgressRingTrackAlpha).compositeOver(ground)

    private fun isArc(px: Int) = near(accent, px)
    private fun isTrack(px: Int) = near(track, px)

    @Test
    fun `the fraction is clamped and a zero total is an empty ring`() {
        assertEquals(0f, mimirProgressFraction(0, 40), 0f)
        assertEquals(0.3f, mimirProgressFraction(12, 40), 0.0001f)
        assertEquals(1f, mimirProgressFraction(40, 40), 0f)
        assertEquals(1f, mimirProgressFraction(41, 40), 0f)
        assertEquals(0f, mimirProgressFraction(-3, 40), 0f)
        assertEquals(0f, mimirProgressFraction(5, 0), 0f)
        assertEquals(0f, mimirProgressFraction(5, -1), 0f)
    }

    @Test
    fun `the spoken counts are clamped into zero to total, as Main's achievement label is`() {
        assertEquals("40 of 40", mimirProgressSpoken(41, 40))
        assertEquals("0 of 40", mimirProgressSpoken(-3, 40))
        assertEquals("0 of 40", mimirProgressSpoken(0, 40))
        assertEquals("12 of 40", mimirProgressSpoken(12, 40))
        assertEquals("40 of 40 achievements", mimirProgressSpoken(99, 40, "achievements"))
        // Every case agrees with Main's own label, character for character once its slash is read as "of".
        for (total in listOf(1, 40, 500)) for (done in listOf(-5, 0, 1, total - 1, total, total + 1, Int.MAX_VALUE)) {
            assertEquals(
                "done=$done total=$total",
                mimirAchievementProgressLabel(done, total)?.replace("/", " of "),
                mimirProgressSpoken(done, total),
            )
        }
    }

    @Test
    fun `a zero or negative total is no count, so nothing is said, as Main shows no label`() {
        for (total in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals("total=$total", null, mimirProgressSpoken(5, total))
            assertEquals("Main shows nothing for total=$total", null, mimirAchievementProgressLabel(5, total))
            assertEquals("and the fraction is empty", 0f, mimirProgressFraction(5, total), 0f)
        }
    }

    @Test
    fun `an over-full count paints full and announces the total, not the over-count`() {
        done = 41
        total = 40
        mount()
        val ring = compose.onNodeWithTag(tag).fetchSemanticsNode().children.single()
        assertEquals(listOf("40 of 40"), ring.config[SemanticsProperties.ContentDescription])
        val bitmap = render()
        for (degrees in listOf(0.0, 90.0, 180.0, 270.0, 359.0)) assertTrue("arc at $degrees degrees", isArc(sample(bitmap, degrees)))
    }

    @Test
    fun `a negative count is an empty ring that says zero of the total`() {
        done = -4
        total = 40
        mount()
        val ring = compose.onNodeWithTag(tag).fetchSemanticsNode().children.single()
        assertEquals(listOf("0 of 40"), ring.config[SemanticsProperties.ContentDescription])
        val bitmap = render()
        for (degrees in listOf(0.0, 90.0, 180.0, 270.0)) assertTrue("track at $degrees degrees", isTrack(sample(bitmap, degrees)))
    }

    @Test
    fun `a zero total draws no ring and adds no node`() {
        done = 3
        total = 0
        mount()
        assertTrue("no ring node", compose.onNodeWithTag(tag).fetchSemanticsNode().children.isEmpty())
        val bitmap = render()
        assertTrue("nothing is drawn: the centre and the ring's track positions are untouched ground", listOf(0.0, 90.0, 180.0, 270.0).all { near(ground, sample(bitmap, it)) })
    }

    @Test
    fun `one node speaks the counts and takes no focus or taps`() {
        done = 12
        total = 40
        subject = null
        mount()
        val ring = compose.onNodeWithTag(tag).fetchSemanticsNode().children.single()
        assertEquals(listOf("12 of 40"), ring.config[SemanticsProperties.ContentDescription])
        assertTrue("a ring has no focus semantics", SemanticsProperties.Focused !in ring.config)
        compose.runOnIdle { subject = "achievements" }
        compose.waitForIdle()
        val named = compose.onNodeWithTag(tag).fetchSemanticsNode().children.single()
        assertEquals(listOf("12 of 40 achievements"), named.config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun `an empty ring draws the track alone, with no cap dot at twelve o'clock`() {
        done = 0
        mount()
        val bitmap = render()
        for (degrees in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
            assertTrue("track at $degrees degrees, was ${Integer.toHexString(sample(bitmap, degrees))}", isTrack(sample(bitmap, degrees)))
        }
        assertTrue("the centre is untouched ground", near(ground, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
    }

    @Test
    fun `a partial ring fills clockwise from twelve o'clock and leaves the rest as track`() {
        done = 10 // a quarter: twelve o'clock to three o'clock
        mount()
        val bitmap = render()
        for (degrees in listOf(15.0, 45.0, 75.0)) {
            assertTrue("arc at $degrees degrees, was ${Integer.toHexString(sample(bitmap, degrees))}", isArc(sample(bitmap, degrees)))
        }
        for (degrees in listOf(110.0, 180.0, 270.0, 340.0)) {
            assertTrue("track at $degrees degrees, was ${Integer.toHexString(sample(bitmap, degrees))}", isTrack(sample(bitmap, degrees)))
        }
        compose.runOnIdle { done = 30 } // three quarters: the arc has reached nine o'clock
        val three = render()
        for (degrees in listOf(15.0, 90.0, 180.0, 255.0)) {
            assertTrue("arc at $degrees degrees at 30 of 40", isArc(sample(three, degrees)))
        }
        assertTrue("track at 300 degrees at 30 of 40", isTrack(sample(three, 300.0)))
    }

    @Test
    fun `a full ring is the whole circle in the accent`() {
        done = 40
        mount()
        val bitmap = render()
        for (degrees in listOf(0.0, 1.0, 90.0, 180.0, 270.0, 359.0)) {
            assertTrue("arc at $degrees degrees, was ${Integer.toHexString(sample(bitmap, degrees))}", isArc(sample(bitmap, degrees)))
        }
    }
}
