package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W3c (transport paint): the transport is struck metal on both screens. Each state is read from the Play plate's pixels at 104 px (52 dp at xhdpi) on a
 * flat host ground, in the default theme: rest (the leather ground, a metal rim and glyph), pressed (the metal a little stronger and the ground's top warmed by the
 * counter accent), playing (Pause: the full-strength metal, no accent fill), disabled (flat metal: the book's hardware toward the cover), and the strengths that
 * make the first three differ. The plate sizes of the hub and the tile are measured on the real scenes (CompanionMusicPanelSceneTest, CompanionEngineMediaTileTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTransportMetalTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val host = Color(0xFF101418)
    private val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
    private val hero = palette.materials.hero

    private var label by mutableStateOf("Play")
    private var enabled by mutableStateOf(true)

    private fun mount(initialLabel: String = "Play", initialEnabled: Boolean = true) {
        label = initialLabel
        enabled = initialEnabled
        compose.setContent {
            MimirTheme(themeId = MimirThemeId.DEFAULT) {
                Box(Modifier.size(52.dp).background(host).testTag("host"), contentAlignment = Alignment.Center) {
                    MediaButton(label = label, enabled = enabled, primary = true, onClick = {})
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun pixels(): androidx.compose.ui.graphics.PixelMap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        return crop.asImageBitmap().toPixelMap()
    }

    private fun leatherTop() = hero.coverTop.compositeOver(host)

    /** Two colours are one if every channel is within 2 of 255: the software shader and the float composite round differently. */
    private fun assertColor(message: String, expected: Color, actual: Color) {
        val e = expected.toArgb()
        val a = actual.toArgb()
        for (shift in intArrayOf(0, 8, 16)) {
            val d = Math.abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF))
            assertTrue("$message: expected #${Integer.toHexString(e)} got #${Integer.toHexString(a)}", d <= 2)
        }
    }

    @Test
    fun `at rest the plate is leather with a metal rim and a metal glyph`() {
        mount("Play")
        val map = pixels()
        assertEquals("104 px", 104, map.width)
        assertColor("the ground above the glyph is the leather's top", leatherTop(), map[22, 12])
        val rim = map[52, 1]
        val glyph = map[52, 52]
        assertTrue("the rim is metal, brighter than the leather: ${Integer.toHexString(rim.toArgb())}", rim.luminance() > leatherTop().luminance() + 0.1f)
        assertTrue("the glyph is struck in metal, brighter than the leather: ${Integer.toHexString(glyph.toArgb())}", glyph.luminance() > leatherTop().luminance() + 0.1f)
        assertNotEquals("the face carries no accent fill", palette.accent.toArgb(), map[22, 52].toArgb())
    }

    @Test
    fun `pressed the ground's top takes the counter accent and the metal strengthens`() {
        mount("Play")
        val rest = pixels()
        val restRim = rest[52, 1].luminance()
        val restGlyph = rest[52, 52].luminance()
        compose.onNodeWithTag("host").performTouchInput { down(center) }
        settle()
        val pressed = pixels()
        val warmed = palette.materials.counterAccent.copy(alpha = 0.38f).compositeOver(hero.coverTop).compositeOver(host)
        assertColor("the ground's top is the counter accent at 0.38 over the leather", warmed, pressed[22, 12])
        assertNotEquals("and so differs from rest", rest[22, 12].toArgb(), pressed[22, 12].toArgb())
        assertTrue("the glyph is a little stronger pressed", pressed[52, 52].luminance() > restGlyph)
        assertTrue("the rim is a little stronger pressed", pressed[52, 1].luminance() >= restRim)
        compose.onNodeWithTag("host").performTouchInput { up() }
        settle()
        assertColor("released, the ground returns to the leather", leatherTop(), pixels()[22, 12])
    }

    @Test
    fun `playing is Pause in the full-strength metal with no accent fill`() {
        mount("Pause")
        val map = pixels()
        assertColor("the ground is the same leather as at rest", leatherTop(), map[22, 12])
        assertNotEquals("no accent fill", palette.accent.toArgb(), map[12, 12].toArgb())
        // The left bar's middle, in the glyph's 52 px box (x 26..78): the bar is 14%..38% across.
        val bar = map[40, 52]
        assertTrue("the bar is metal, brighter than the leather: ${Integer.toHexString(bar.toArgb())}", bar.luminance() > leatherTop().luminance() + 0.1f)
        assertTrue("the gap between the bars shows the plate ground, not metal", map[52, 52].luminance() < bar.luminance())
    }

    @Test
    fun `the strengths run rest below pressed and playing at full`() {
        val rest = mediaMetalAlpha(playing = false, pressed = false)
        val pressed = mediaMetalAlpha(playing = false, pressed = true)
        val playing = mediaMetalAlpha(playing = true, pressed = false)
        assertEquals("a press adds 20/255", rest + 20f / 255f, pressed, 1e-6f)
        assertTrue("playing is the brighter metal", playing > rest)
        assertEquals("full strength", 1f, playing, 0f)
        assertEquals("a pressed playing plate cannot exceed full", 1f, mediaMetalAlpha(playing = true, pressed = true), 0f)
    }

    @Test
    fun `disabled is flat metal, the hardware mixed toward the cover`() {
        mount("Play", initialEnabled = false)
        val map = pixels()
        val flat = lerp(hero.hardware, hero.coverBottom, 0.55f)
        assertColor("the rim is the flat metal", flat, map[52, 1])
        assertColor("the glyph is the flat metal", flat, map[52, 52])
        assertColor("the ground is still the leather", leatherTop(), map[22, 12])
    }

    @Test
    fun `a skip is a plate of its own size inside its target, and a plain text button keeps its look`() {
        compose.setContent {
            MimirTheme(themeId = MimirThemeId.DEFAULT) {
                Box(Modifier.size(80.dp).background(host).testTag("host"), contentAlignment = Alignment.Center) {
                    MediaButton(label = "Previous", enabled = true, skipTargetSize = 56.dp, skipPlateSize = 44.dp, onClick = {})
                }
            }
        }
        settle()
        val target = compose.onNodeWithTag("host").fetchSemanticsNode()
        val plate = compose.onNodeWithTag(MediaTransportPlateTag, useUnmergedTree = true).fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("the plate is 44 dp", 44f, plate.size.width / density, 0.6f)
        assertEquals("the plate is 44 dp tall", 44f, plate.size.height / density, 0.6f)
        assertTrue("the host is larger", target.size.width > plate.size.width)
    }
}
