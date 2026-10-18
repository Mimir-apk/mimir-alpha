package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMetalRimDp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chip focus band grows outward as one band, with a flush ring and a rim taking the ring's colour, at the chip sites
 * in `LibraryScreen.kt`. The gamelist's Search pill, full and small, now hands its rim to the ring (`edgeFlush` on the ring AND on the surface). This drives the REAL
 * [GamelistSearchBar] (not a model of it) through focus arriving and leaving, draws every 16 ms frame into a software bitmap, and counts the outlines on every edge of the
 * drawn pill. The control is the same pill built with the OLD chain (default ring, rim fading beside it): the harness must see TWO bands on it in some frame, or it
 * could not have caught the defect.
 *
 * The band counter is the one `EtchedChipFocusMoveCaptureTest` (app module) uses: a nine-lane cross-section from 12 dp outside an edge to the rim's inner side, a
 * one-sample closing so an anti-aliasing seam is not a gap, and peaks that stand [BandProminence] clear of the valleys beside them. The density is 4x, so the 0.5 dp gap
 * of the old chain is two whole pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h300dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistSearchPillSingleBandTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focusedNow by mutableStateOf(false)
    private val bounds = mutableMapOf<String, Rect>()
    private val authority = DefaultFocusAuthority()

    private val density get() = compose.activity.resources.displayMetrics.density
    private val pillWidth = 180.dp

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                val palette = LocalMimirPalette.current
                Column(
                    Modifier.background(palette.surfaceBase).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Row {
                        GamelistSearchBar(
                            query = "",
                            authority = authority,
                            controllerEnabled = true,
                            focused = focusedNow,
                            onOpen = {},
                            onClear = {},
                            pillWidth = pillWidth,
                            modifier = Modifier.onGloballyPositioned { bounds[Full] = it.boundsInWindow() },
                        )
                    }
                    Row {
                        GamelistSearchBar(
                            query = "",
                            authority = authority,
                            controllerEnabled = true,
                            focused = focusedNow,
                            onOpen = {},
                            onClear = {},
                            pillWidth = pillWidth,
                            small = true,
                            modifier = Modifier.onGloballyPositioned { bounds[SmallTouchArea] = it.boundsInWindow() },
                        )
                    }
                    Row {
                        // The control: the pill as it was before Q8 (the default ring, the rim fading out under it).
                        Box(
                            Modifier
                                .size(pillWidth, LibraryGamelistSearchControlMinHeightDp.dp)
                                .onGloballyPositioned { bounds[Old] = it.boundsInWindow() }
                                .mimirSelectionRing(focused = focusedNow, identity = Old, shape = MimirRingShape.Pill)
                                .mimirEtchedChipSurface(palette, warmed = false, focused = focusedNow)
                                .clip(RoundedCornerShape(percent = 50)),
                        )
                    }
                }
            }
        }
        settle()
    }

    private enum class Edge { Top, Bottom, Left, Right }

    /** The drawn pill of each tag: the small bar's touch area is 44 dp tall and the pill it draws (and rings) is 32 dp, centred in it. */
    private fun pill(tag: String): Rect {
        val outer = bounds.getValue(tag.takeUnless { it == Small } ?: SmallTouchArea)
        if (tag != Small) return outer
        val drawn = LibraryGamelistCompactSearchPillHeightDp * density
        val inset = (outer.height - drawn) / 2f
        return Rect(outer.left, outer.top + inset, outer.right, outer.bottom - inset)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(800L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(FrameMs)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun shoot(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun luma(pixel: Int): Float {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    // ---- outlines, counted (the app module's EtchedChipFocusMoveCaptureTest counter) -----------------------------------------------------------------------------------

    private fun crossSection(bitmap: Bitmap, pill: Rect, edge: Edge): FloatArray {
        val outside = (OutsideDp * density).roundToInt()
        val inside = (MimirMetalRimDp.value * density).roundToInt()
        val tail = (TailDp * density).roundToInt()
        val left = pill.left.roundToInt()
        val top = pill.top.roundToInt()
        val right = pill.right.roundToInt()
        val bottom = pill.bottom.roundToInt()
        val centreX = (left + right) / 2
        val centreY = (top + bottom) / 2
        val out = FloatArray(outside + inside + tail)
        for (i in out.indices) {
            var sum = 0f
            for (lane in -Lanes..Lanes) {
                val pixel = when (edge) {
                    Edge.Top -> bitmap.getPixel(centreX + lane, top - outside + i)
                    Edge.Bottom -> bitmap.getPixel(centreX + lane, bottom + outside - 1 - i)
                    Edge.Left -> bitmap.getPixel(left - outside + i, centreY + lane)
                    Edge.Right -> bitmap.getPixel(right + outside - 1 - i, centreY + lane)
                }
                sum += luma(pixel)
            }
            out[i] = sum / (2 * Lanes + 1)
        }
        return out
    }

    private fun prominence(profile: FloatArray, index: Int): Float {
        val v = profile[index]
        var leftBase = v
        var j = index - 1
        while (j >= 0 && profile[j] < v) { leftBase = min(leftBase, profile[j]); j-- }
        var rightBase = v
        var k = index + 1
        while (k < profile.size && profile[k] <= v) { rightBase = min(rightBase, profile[k]); k++ }
        return v - max(leftBase, rightBase)
    }

    private fun closed(profile: FloatArray): FloatArray {
        val dilated = FloatArray(profile.size) { i -> if (i + 1 < profile.size) max(profile[i], profile[i + 1]) else profile[i] }
        return FloatArray(profile.size) { i -> if (i > 0) min(dilated[i - 1], dilated[i]) else dilated[i] }
    }

    private fun bandCount(profile: FloatArray): Int {
        val closed = closed(profile)
        return closed.indices.count { prominence(closed, it) >= BandProminence }
    }

    private fun bands(bitmap: Bitmap, tag: String): Map<Edge, Int> = Edge.entries.associateWith { bandCount(crossSection(bitmap, pill(tag), it)) }

    private fun profileText(bitmap: Bitmap, tag: String, edge: Edge) =
        crossSection(bitmap, pill(tag), edge).joinToString(" ") { "%.2f".format(it) }

    private fun crowded(bitmap: Bitmap, tag: String, perEdge: Map<Edge, Int>) =
        perEdge.filterValues { it > 1 }.keys.joinToString("; ") { "$it: ${profileText(bitmap, tag, it)}" }

    // ---- driving ---------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Sets focus and runs the ring clock out (three overlay lengths), handing every frame's bitmap to [onFrame] with its index. */
    private fun move(focused: Boolean, onFrame: (index: Int, shot: Bitmap) -> Unit) {
        compose.runOnUiThread { focusedNow = focused }
        Snapshot.sendApplyNotifications()
        var elapsed = 0L
        var index = 0
        while (elapsed < MimirMotion.OverlayMs * 3L) {
            frame()
            elapsed += FrameMs
            onFrame(index++, shoot())
        }
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the real search pill, full and small, shows at most one outline in every frame of focus arriving and leaving`() {
        var checked = 0
        val perPill = mutableMapOf(Full to mutableListOf<Int>(), Small to mutableListOf<Int>())

        fun record(direction: String, shot: Bitmap, index: Int) {
            for (tag in listOf(Full, Small)) {
                val perEdge = bands(shot, tag)
                perPill.getValue(tag) += perEdge.values.max()
                checked++
                assertTrue(
                    "$direction frame $index: the $tag pill shows two outlines on ${perEdge.filterValues { it > 1 }.keys}; outside to inside, ring-side first: ${crowded(shot, tag, perEdge)}",
                    perEdge.values.all { it <= 1 },
                )
            }
        }

        move(focused = true) { index, shot -> record("focus arriving:", shot, index) }
        println("GamelistSearchPillSingleBand bands per frame, arriving: " + perPill.entries.joinToString { "${it.key} ${it.value.joinToString("")}" })
        val settled = shoot()
        for (tag in listOf(Full, Small)) {
            val perEdge = bands(settled, tag)
            assertTrue("$tag pill settled focused: one band on every edge, got $perEdge: ${crowded(settled, tag, perEdge)}", perEdge.values.all { it == 1 })
        }
        perPill.values.forEach { it.clear() }
        move(focused = false) { index, shot -> record("focus leaving:", shot, index) }
        println("GamelistSearchPillSingleBand bands per frame, leaving: " + perPill.entries.joinToString { "${it.key} ${it.value.joinToString("")}" })
        assertTrue("both pills were looked at in both moves ($checked samples)", checked >= 2 * 2 * 30)
    }

    @Test
    fun `the harness sees two outlines on the pill built the way it was before`() {
        val twoBandFrames = mutableListOf<Int>()
        val log = mutableListOf<Int>()
        move(focused = true) { index, shot ->
            val perEdge = bands(shot, Old)
            log += perEdge.values.max()
            if (perEdge.values.any { it >= 2 }) twoBandFrames += index
        }
        move(focused = false) { index, shot ->
            val perEdge = bands(shot, Old)
            log += perEdge.values.max()
            if (perEdge.values.any { it >= 2 }) twoBandFrames += 1000 + index
        }
        println("GamelistSearchPillSingleBand control (old chain) bands per frame: ${log.joinToString("")}")
        assertTrue(
            "the pill with the default ring and a fading rim must show two outlines in some frame, or this test cannot catch them: $twoBandFrames",
            twoBandFrames.size >= 2,
        )
    }

    private companion object {
        const val Full = "full"
        const val Small = "small"
        const val SmallTouchArea = "small-touch-area"
        const val Old = "old-chain"
        const val FrameMs = 16L

        /** A cross-section starts this far outside the edge: clear of the next pill's ring (28 dp away). */
        const val OutsideDp = 12f

        /** The ground past the rim that the cross-section ends in. */
        const val TailDp = 1f

        /** Lines averaged either side of the cross-section's own (so nine). */
        const val Lanes = 4

        /** How far a peak must stand above the valleys beside it, in luminance (0 to 1), to be a band. */
        const val BandProminence = 0.04f
    }
}
