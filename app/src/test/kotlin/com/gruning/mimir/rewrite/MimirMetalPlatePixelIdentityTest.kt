package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.drawMimirMetalPlate
import com.gruning.mimir.rewrite.core.ui.mimirMetalRimAlpha
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * pad11 : the media transport plate's ground, rim and press warmth moved into core-ui as the shared struck-metal plate
 * (`drawMimirMetalPlate`), which the Bottom's header plates draw through too. This is the moved-pieces proof for it: the plate is drawn by the shared
 * function and by a verbatim copy of what `MediaMetalButton` drew inline before the move (W3c, d513efee1), on every theme palette, in every state the
 * transport has (rest, pressed, playing, disabled-flat), at the transport's own plate sizes, and the pixels must be the SAME. The Main transport goldens
 * (MediaTransportMetalTest, MusicPanelMainPixelIdentityTest, MovedPiecesPixelIdentityTest) run untouched beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirMetalPlatePixelIdentityTest {

    private val palettes = listOf(
        "Default" to MimirThemePalette.Default,
        "Freyr" to MimirThemePalette.Freyr,
        "Surtr" to MimirThemePalette.Surtr,
        "Mani" to MimirThemePalette.Mani,
        "Hel" to MimirThemePalette.Hel,
        "Niflheim" to MimirThemePalette.Niflheim,
        "Yggdrasil" to MimirThemePalette.Yggdrasil,
        "Folkvangr" to MimirThemePalette.Folkvangr,
        "Baldr" to MimirThemePalette.Baldr,
    )

    private fun render(size: Int, block: DrawScope.() -> Unit): IntArray {
        val bitmap = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(bitmap), Size(size.toFloat(), size.toFloat()), block)
        return bitmap.toPixelMap().buffer
    }

    /** The drawing as `MediaMetalButton` had it inline before pad11, copied verbatim (the file's names kept). */
    private fun DrawScope.beforeThePad11Move(palette: MimirThemePalette, glyphBrush: Brush, alpha: Float, enabled: Boolean, pressed: Boolean) {
        val hero = palette.materials.hero
        val corner = CornerRadius(MimirSpacing.radiusCard.toPx())
        val groundTop = if (enabled && pressed) {
            palette.materials.counterAccent.copy(alpha = 0.38f).compositeOver(hero.coverTop)
        } else {
            hero.coverTop
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                0.27f to groundTop,
                0.75f to hero.coverBottom,
            ),
            cornerRadius = corner,
        )
        val rim = 1.25.dp.toPx()
        drawRoundRect(
            brush = glyphBrush,
            topLeft = Offset(rim / 2f, rim / 2f),
            size = Size(size.width - rim, size.height - rim),
            cornerRadius = CornerRadius((corner.x - rim / 2f).coerceAtLeast(0f)),
            alpha = alpha,
            style = Stroke(rim),
        )
    }

    @Test
    fun theSharedPlateIsThePixelsTheTransportAlwaysDrew_onEveryThemeInEveryState() {
        // The transport's plate sizes, in px at 2x: 104 (Play, 52 dp), 88 (the hub's skip, 44 dp), 80 (the tile's skip, 40 dp).
        for ((name, palette) in palettes) {
            val metal = platformConsoleIconBrush(palette)
            val hero = palette.materials.hero
            val flat = SolidColor(lerp(hero.hardware, hero.coverBottom, 0.55f))
            for (px in listOf(104, 88, 80)) {
                // rest, pressed, playing (Pause), disabled: (brush, rim alpha, enabled, pressed) as the button computed them.
                val states = listOf(
                    Triple("rest", metal, 0.82f) to (true to false),
                    Triple("pressed", metal, minOf(1f, 0.82f + 20f / 255f)) to (true to true),
                    Triple("playing", metal, 1f) to (true to false),
                    Triple("disabled", flat, 1f) to (false to false),
                )
                for ((spec, flags) in states) {
                    val (state, brush, alpha) = spec
                    val (enabled, pressed) = flags
                    val before = render(px) { beforeThePad11Move(palette, brush, alpha, enabled, pressed) }
                    val after = render(px) { drawMimirMetalPlate(palette, rimBrush = brush, rimAlpha = alpha, warmed = enabled && pressed) }
                    assertArrayEquals("$name $state at $px px: the shared plate must draw the transport's pixels exactly", before, after)
                }
            }
        }
    }

    @Test
    fun theRestAndPressedRimAlphasAreTheTransportsOwn() {
        assertTrue(mimirMetalRimAlpha(false) == 0.82f)
        assertTrue(mimirMetalRimAlpha(true) == minOf(1f, 0.82f + 20f / 255f))
    }

    @Test
    fun theTransportDrawsThroughTheSharedPlateAndKeepsItsOwnGlyphsAndStates() {
        val media = File("src/main/kotlin/com/gruning/mimir/rewrite/MediaTransportButtons.kt").readText()
        assertTrue("the plate's ground and rim are the shared drawing", media.contains("drawMimirMetalPlate(palette, rimBrush = glyphBrush, rimAlpha = metalAlpha(), warmed = enabled && pressed.value)"))
        assertFalse("no inline copy of the ground's stops is left", media.contains("MediaGroundTopStop") || media.contains("MediaMetalRimDp"))
        assertTrue("the transport's own rest/pressed/playing strengths are still its own", media.contains("internal fun mediaMetalAlpha(playing: Boolean, pressed: Boolean)"))
    }
}
