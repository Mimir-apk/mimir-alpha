package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirCrossSwapMs
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.BackgroundPickerScope
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePreviewGallery
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.backgroundPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
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
 * The Appearance pages' previews are painted from the first frame the host shows them (the shared swap policy moved the Background and Mimir-button pickers onto the Theme gallery's cross-swap: no 130 ms lead to hide a
 * first composition behind, and one 180 ms arrival instead of 540). [AppearancePickerSwapFrameTest] holds the host's clock over flat pages; this holds that the REAL pages, the Theme gallery's cards, the Background
 * picker's stage with the real shelf pieces on it and the Mimir-button picker's stage, are drawn whole by the time the host's alpha is above zero.
 *
 * The page is mounted inside a layer whose alpha is the host's own arrival (`tween(arriveMs, delayMillis = lead, MimirMotion.Easing)` from the host's policy functions) over a solid red Settings pane, and the frame is read
 * off the composed pixels every 16 ms. The layer is a plain alpha over an opaque page, so each pixel of frame i must be `red + a(i) * (settled - red)`: the meter is how far the frame is from that, as a share of the
 * page's whole contrast with the red, in each of four bands of the screen (the stage is in the upper two, the tiles and the footer in the lower two). A preview that composed a frame late, or cold, or that
 * rose on a clock of its own, would sit under that line by the share of the band it fills. The worst band of the real pages measures 3.4% (the Theme gallery's, while its focus ring lands; the pickers' 2.6% and 1.9%); the control mounts a page
 * five frames late and is 24% off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearancePickerArrivalContentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val stride = 3
    private val pane = Color(255, 0, 0)

    private class Capture(val alpha: Float, val rgb: IntArray, val width: Int, val height: Int)

    private fun capture(alpha: Float): Capture {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val w = bitmap.width / stride
        val h = bitmap.height / stride
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = bitmap.getPixel(x * stride, y * stride)
        bitmap.recycle()
        return Capture(alpha, out, w, h)
    }

    private fun write(change: () -> Unit) {
        Snapshot.withMutableSnapshot { change() }
        Snapshot.sendApplyNotifications()
    }

    /**
     * Mounts [page] under the host's arrival for [kind] over the red pane, [contentAfterFrames] frames after the layer starts (0 is how the host mounts it), and returns the first [frames] frames, 16 ms apart.
     */
    private fun arrive(kind: MimirOverlayKind, scope: FocusScope, frames: Int, contentAfterFrames: Int = 0, page: @Composable () -> Unit): List<Capture> {
        authority.pushScope(scope)
        val arriveMs = mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false)
        val leadMs = mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind)
        var open by mutableStateOf(false)
        var contentOn by mutableStateOf(contentAfterFrames == 0)
        var layer: Animatable<Float, *>? = null
        compose.setContent {
            CompositionLocalProvider(LocalMimirViewportProfile provides mimirViewportProfile(640f, 360f)) {
                MimirTheme {
                    Box(Modifier.fillMaxSize().background(pane)) {
                        if (open) {
                            val alpha = remember { Animatable(0f) }
                            layer = alpha
                            LaunchedEffect(Unit) { alpha.animateTo(1f, tween(arriveMs, delayMillis = leadMs, easing = MimirMotion.Easing)) }
                            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
                                if (contentOn) page()
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        write { open = true }
        val shots = ArrayList<Capture>()
        for (frame in 1..frames) {
            if (frame == contentAfterFrames) write { contentOn = true }
            compose.mainClock.advanceTimeBy(16)
            shots += capture(layer?.value ?: 0f)
        }
        return shots
    }

    /** Per band, how far frame [shot] is from `pane + alpha * (settled - pane)`, as a share of the band's whole contrast of [settled] with the pane. */
    private fun bandErrors(shot: Capture, settled: Capture, bands: Int = 4): List<Float> {
        val pr = pane.red * 255f
        val pg = pane.green * 255f
        val pb = pane.blue * 255f
        return (0 until bands).map { band ->
            val from = band * shot.height / bands
            val to = (band + 1) * shot.height / bands
            var off = 0f
            var contrast = 0f
            for (y in from until to) for (x in 0 until shot.width) {
                val s = settled.rgb[y * shot.width + x]
                val p = shot.rgb[y * shot.width + x]
                val sr = ((s shr 16) and 0xFF).toFloat()
                val sg = ((s shr 8) and 0xFF).toFloat()
                val sb = (s and 0xFF).toFloat()
                contrast += abs(sr - pr) + abs(sg - pg) + abs(sb - pb)
                off += abs(((p shr 16) and 0xFF) - (pr + shot.alpha * (sr - pr))) +
                    abs(((p shr 8) and 0xFF) - (pg + shot.alpha * (sg - pg))) +
                    abs((p and 0xFF) - (pb + shot.alpha * (sb - pb)))
            }
            assertTrue("band $band of the settled page has contrast with the pane", contrast > 0f)
            off / contrast
        }
    }

    /** The worst band error over every frame the layer shows (alpha above 2%), and the first such frame's index. */
    private fun worst(shots: List<Capture>): Pair<Float, Int> {
        val settled = shots.last()
        assertTrue("the layer is whole at the end of the run (${settled.alpha})", settled.alpha > 0.999f)
        val shown = shots.indices.filter { shots[it].alpha > 0.02f }
        assertTrue("the layer shows within the run", shown.isNotEmpty())
        val errors = shown.map { i -> bandErrors(shots[i], settled) }
        println("APPEARANCECONTENT first shown frame ${shown.first()} alpha ${shots[shown.first()].alpha}; worst band error per frame " + errors.joinToString(" ") { "%.3f".format(it.max()) })
        return errors.maxOf { it.max() } to shown.first()
    }

    private fun backgroundPage(): @Composable () -> Unit {
        var state by mutableStateOf(backgroundPickerInitialState(MimirThemeId.DEFAULT, null, emptyList()))
        return {
            BackgroundPickerOverlay(
                authority = authority,
                state = state,
                dualScreen = false,
                onFocus = { key -> state = backgroundPickerOnFocus(state, key) },
                onTransition = { transition -> state = transition.state },
                presenceManagedExternally = true,
                stageChrome = {
                    BackgroundPickerLiveStageChrome(
                        authority = authority,
                        platforms = listOf(
                            PlatformRow(PlatformId("snes"), "Super Nintendo", 12),
                            PlatformRow(PlatformId("dreamcast"), "Dreamcast", 1),
                            PlatformRow(PlatformId("gba"), "Game Boy Advance", 7),
                        ),
                        gameCount = 50,
                    )
                },
            )
        }
    }

    private fun mimirButtonPage(): @Composable () -> Unit {
        var state by mutableStateOf(mimirButtonPickerInitialState(null, emptyList()))
        return {
            MimirButtonPickerOverlay(
                authority = authority,
                state = state,
                onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                onTransition = { transition -> state = transition.state },
                presenceManagedExternally = true,
                stage = { preview -> MimirButtonPickerLiveStage(preview) },
                adjustCoin = { mark, scale -> MimirButtonPickerAdjustCoin(mark, scale) },
            )
        }
    }

    /** Frames to run: the whole 180 ms arrival, and enough after it that the settled frame is the page at rest. */
    private val frames = 40

    @Test
    fun `the Background picker's stage and shelf pieces are drawn with the layer from its first frame`() {
        val shots = arrive(MimirOverlayKind.BACKGROUND_PICKER, BackgroundPickerScope, frames, page = backgroundPage())
        val (error, first) = worst(shots)
        assertTrue("the layer shows within three frames of no lead ($first)", first <= 2)
        assertTrue("every band of every frame is within 6% of the page's own contrast ($error)", error <= 0.06f)
    }

    @Test
    fun `the Mimir-button picker's stage and tiles are drawn with the layer from its first frame`() {
        val shots = arrive(MimirOverlayKind.MBUTTON_PICKER, MimirButtonPickerScope, frames, page = mimirButtonPage())
        val (error, first) = worst(shots)
        assertTrue("the layer shows within three frames of no lead ($first)", first <= 2)
        assertTrue("every band of every frame is within 6% of the page's own contrast ($error)", error <= 0.06f)
    }

    @Test
    fun `the Theme gallery's cards are drawn with the layer from its first frame`() {
        val shots = arrive(MimirOverlayKind.THEME_PICKER, ThemePickerScope, frames) {
            ThemePreviewGallery(
                authority = authority,
                scope = ThemePickerScope,
                currentThemeId = MimirThemeId.DEFAULT,
                title = "Choose your look",
                subtitle = "Every screen takes on this palette.",
                dismissHint = "Back",
                onPick = {},
                onDismiss = {},
                presenceManagedExternally = true,
            )
        }
        val (error, first) = worst(shots)
        assertTrue("the layer shows within three frames of no lead ($first)", first <= 2)
        assertTrue("every band of every frame is within 6% of the page's own contrast ($error)", error <= 0.06f)
    }

    @Test
    fun `the meter sees a page that composes late - a picker mounted five frames after its layer starts is far off the line`() {
        val shots = arrive(MimirOverlayKind.BACKGROUND_PICKER, BackgroundPickerScope, frames, contentAfterFrames = 5, page = backgroundPage())
        val (error, _) = worst(shots)
        // Five frames in the layer is only about a quarter shown, so a page that is not there yet is off the line by that quarter: well past the 6% a page drawn with the layer stays under (worst measured 3.4%).
        assertTrue("a late page is not within 6% of the layer's alpha ($error)", error > 0.15f)
        assertEquals("and the arrival it is judged on is the sub-pages' one clock", MimirCrossSwapMs, mimirIncomingOverlayArriveMs(MimirOverlayKind.BACKGROUND_PICKER, null, reducedMotion = false))
    }
}
