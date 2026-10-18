package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Observed on Thor: scrolled rows drew under the "ᛗ · UTILITY" title. The rail's scroll viewport bleeds [UtilityRailRingBleed] (8 dp) above the divider so the first row's
 * ring is not clipped, and the header's own bottom padding is 4 dp, so that band reaches into the title's line box. Once the rail has scrolled, the viewport is clipped back to the divider.
 *
 * The rail's own geometry, rebuilt around the real modifiers: a ground-coloured header, the divider, then the viewport (clip, then bleed) over rows of one flat colour. Pixels are read
 * the way this repo rasterises (the view hierarchy drawn into a software bitmap; `captureToImage` waits on a redraw Robolectric's paused looper never delivers). Pixel values are
 * the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityRailBleedClipTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)
    private val rowInk = Color(0xFFCC3322)
    private val dividerInk = Color(0xFF888888)

    // dp are pixels at the default density. The header is 40 dp and the divider 1 dp, so the viewport's visible top is y = 41 and the bleed band is y = 33..41.
    private val headerHeight = 40
    private val inBand = 36
    private val belowDivider = 60
    private val inHeader = 10

    private val scroll = ScrollState(0)

    private fun mount() {
        compose.setContent {
            Column(Modifier.size(200.dp, 200.dp).background(ground).testTag("host")) {
                Box(Modifier.fillMaxWidth().height(headerHeight.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(dividerInk))
                Box(
                    Modifier
                        .weight(1f)
                        .utilityRailClipBleedWhenScrolled(scroll)
                        .utilityRailBleedUp(UtilityRailRingBleed),
                ) {
                    Column(Modifier.verticalScroll(scroll)) {
                        repeat(8) { Box(Modifier.fillMaxWidth().height(58.dp).background(rowInk)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(value: Int) {
        compose.runOnIdle { runBlocking { scroll.scrollTo(value) } }
        compose.waitForIdle()
    }

    private fun pixelAt(y: Int): Int {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        return crop.asImageBitmap().toPixelMap()[100, y].toArgb()
    }

    @Test
    fun `at the top of the rail the bleed band is drawn, so the first row's ring is not cut`() {
        mount()
        assertEquals("the header above the band is untouched", ground.toArgb(), pixelAt(inHeader))
        assertEquals("the viewport reaches over the divider while the rail is at zero", rowInk.toArgb(), pixelAt(inBand))
        assertEquals("and the rows below the divider draw as ever", rowInk.toArgb(), pixelAt(belowDivider))
    }

    @Test
    fun `once the rail has scrolled, no row draws over the header`() {
        mount()
        scrollTo(20)
        assertEquals("the band shows the header, not the row scrolled through it", ground.toArgb(), pixelAt(inBand))
        assertEquals("the header above it is untouched", ground.toArgb(), pixelAt(inHeader))
        assertEquals("the divider stands", dividerInk.toArgb(), pixelAt(headerHeight))
        assertEquals("the rows below the divider still draw", rowInk.toArgb(), pixelAt(belowDivider))
    }

    @Test
    fun `back at zero the band is drawn again`() {
        mount()
        scrollTo(20)
        assertEquals(ground.toArgb(), pixelAt(inBand))
        scrollTo(0)
        assertEquals("the ring's reach is back over the divider", rowInk.toArgb(), pixelAt(inBand))
    }

    @Test
    fun `the clip sits outside the bleed in the overlay's rail chain, after the visible-viewport report`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        ).first(File::isFile).readText()
        val chain = source.substringAfter(".weight(1f, fill = presentedSurface == UtilitySurface.TWEAKER)")
            .substringBefore(".verticalScroll(presentedRailScroll)")
        val report = chain.indexOf(".onGloballyPositioned")
        val clip = chain.indexOf(".utilityRailClipBleedWhenScrolled(presentedRailScroll)")
        val bleed = chain.indexOf(".utilityRailBleedUp(UtilityRailRingBleed)")
        assertTrue("the rail reports its visible viewport first: report=$report clip=$clip bleed=$bleed", report in 0 until clip)
        assertTrue("then the clip, outside the bleed it cuts: clip=$clip bleed=$bleed", clip in 0 until bleed)
        assertEquals(1, Regex("""\.utilityRailClipBleedWhenScrolled\(""").findAll(source).count())
    }
}
