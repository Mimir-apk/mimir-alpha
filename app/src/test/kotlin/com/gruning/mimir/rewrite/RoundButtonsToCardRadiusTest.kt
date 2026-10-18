package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * no round buttons, Mimir's rounded rectangle (MimirSpacing.radiusCard, 14 dp) instead -- the Play/Pause button on BOTH screens (it is the
 * shared MediaButton), the Tabs drawer's close X, and the Bottom's recent-app button, whose 14 dp was a literal. Pills, slider thumbs, the dock's pending
 * dot, progress rings, glows, the idle disc and the native coin are not buttons of this kind and stay as they are.
 *
 * Play/Pause is read from its pixels: at 104 px (52 dp at xhdpi) a point 12 px in from the corner is OUTSIDE a circle (56.6 px from the centre, radius 52) and
 * INSIDE a 28 px corner (22.6 px from the corner's centre), so the filled pixel there tells the shapes apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoundButtonsToCardRadiusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)

    private fun pixels(primary: Boolean, label: String): androidx.compose.ui.graphics.PixelMap {
        compose.setContent {
            MimirTheme(themeId = MimirThemeId.DEFAULT) {
                Box(Modifier.size(52.dp).background(ground).testTag("host"), contentAlignment = Alignment.Center) {
                    MediaButton(label = label, enabled = true, primary = primary, onClick = {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        return crop.asImageBitmap().toPixelMap()
    }

    @Test
    fun `Play is a rounded rectangle, struck at the corner a circle would leave bare`() {
        val map = pixels(primary = true, label = "Play")
        assertEquals("the button is 52 dp = 104 px", 104, map.width)
        val ground = leatherTop()
        assertTrue("the ground beside the glyph is the leather", near(ground.toArgb(), map[22, 12].toArgb()))
        assertTrue("12 px in from the corner on the diagonal is struck: a 14 dp corner, not a circle", near(ground.toArgb(), map[12, 12].toArgb()))
        assertNotEquals("the very corner is still outside the rounded corner", ground.toArgb(), map[0, 0].toArgb())
        assertEquals("and it is the host's ground", this.ground.toArgb(), map[0, 0].toArgb())
    }

    @Test
    fun `Pause wears the same shape`() {
        val map = pixels(primary = true, label = "Pause")
        assertTrue(near(leatherTop().toArgb(), map[12, 12].toArgb()))
        assertEquals(this.ground.toArgb(), map[0, 0].toArgb())
    }

    /** Every channel within 2: the software shader and the float composite round differently. */
    private fun near(a: Int, b: Int): Boolean = intArrayOf(0, 8, 16).all { Math.abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= 2 }

    private fun leatherTop(): Color = MimirThemePalette.forId(MimirThemeId.DEFAULT).materials.hero.coverTop.compositeOver(ground)

    @Test
    fun `the three buttons name the card radius and keep no circle`() {
        val transport = File("src/main/kotlin/com/gruning/mimir/rewrite/MediaTransportButtons.kt").readText()
        assertFalse("no circle clip or ring in the transport", transport.contains("CircleShape") || transport.contains("MimirRingShape.Circle"))
        assertTrue(transport.contains("val shape = RoundedCornerShape(MimirSpacing.radiusCard)"))
        assertTrue("the plate is clipped to the card shape", transport.contains(".clip(shape)"))
        assertTrue(transport.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard),"))
        // pad11 : the plate's ground and rim moved to core-ui's shared struck-metal plate, which owns the corner token now; the transport
        // draws through it with the default corner, so the card radius is still what it draws.
        val shared = File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirMetalPlate.kt").readText()
        assertTrue("the plate's corner is the token, in the shared plate", shared.contains("cornerPx: Float = MimirSpacing.radiusCard.toPx()"))
        assertTrue("and the transport takes that default", transport.contains("drawMimirMetalPlate(palette, rimBrush = glyphBrush, rimAlpha = metalAlpha(), warmed = enabled && pressed.value)"))
        val surface = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSurface.kt").readText()
        val recent = surface.substringAfter("onActivate = {\n                                if (latestRecentEnabled) {").substringBefore("contentAlignment = Alignment.Center,")
        assertTrue("the recent-app button's corner is the token", recent.contains(".clip(RoundedCornerShape(MimirSpacing.radiusCard))"))
        assertFalse("and not a literal 14 dp", recent.contains("RoundedCornerShape(14.dp)"))
        val drawer = File("../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionRecentsDrawer.kt").readText()
        val close = drawer.substringAfter("val closeSource = remember { MutableInteractionSource() }").substringBefore("Text(\"✕\"")
        assertTrue("the Tabs close X clips and borders on the card radius", close.contains(".clip(RoundedCornerShape(MimirSpacing.radiusCard))") && close.contains(".border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))"))
        assertFalse("no half-the-target round corner", close.contains("minTarget / 2"))
    }
}
