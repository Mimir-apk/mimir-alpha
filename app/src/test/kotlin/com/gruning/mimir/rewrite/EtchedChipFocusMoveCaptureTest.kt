package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMetalRimDp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.drawMimirMetalPlate
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirMetalRimAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Slow-motion inspection on Thor and an emulator showed blinking during launch-option changes, then two
 * separate outlines for the ring and rim in the first fix. The target: ONE band growing outward, the ring flush on the rim and the rim taking the
 * ring's colour.
 *
 * Four rows of two pills side by side (a plain one and a primary one), built the way `HomeLaunchActionButton` builds one (`mimirSelectionRing`, a modulating layer, the clip, then
 * `mimirEtchedChipSurface`):
 *  - FLUSH, the product: ring and surface both `edgeFlush`;
 *  - PLATE, the product's other shape: the ring rides in the `modifier` of a `MimirEtchedChipPlate` and both opt in, as the nine plate callers and the
 *    Activity filter chips now do;
 *  - FADE, what the first fix shipped: the default ring and a rim that fades out under it (a half-dp gap between them), for the harness to prove it can SEE two outlines;
 *  - LEGACY, the one-step hand-over the surface had before, for the harness to prove it can see a pill with neither rim nor ring;
 *  - FLAT, ground only, the "nothing there" reference.
 * Focus moves from the left pill to the right (and back); every frame (one 16 ms step of the paused clock) is the view hierarchy drawn into a software bitmap.
 *
 * **Outlines are counted, not averaged.** A mean over a region cannot tell one wide line from two thin ones. So the test lays a line ACROSS each edge of a pill (its middle, from 12 dp outside
 * to the rim's inner side), reads the luminance along it, and counts the prominent peaks: bands. The product must show at most ONE in every frame, on all four edges, on the pill arriving
 * and the pill leaving, primary and plain. The density is 4x, so the 0.5 dp gap between the ring and the rim of the FADE row is two whole pixels and cannot hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h420dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtchedChipFocusMoveCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focusedId by mutableStateOf("left")

    private val bounds = mutableMapOf<String, Rect>()

    private val pillWidth = 120.dp
    private val pillHeight = 44.dp

    private val density get() = compose.activity.resources.displayMetrics.density

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
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Pill("flush-left", "left", primary = false, kind = Kind.Flush)
                        Pill("flush-right", "right", primary = true, kind = Kind.Flush)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Pill("plate-left", "left", primary = false, kind = Kind.Plate)
                        Pill("plate-right", "right", primary = true, kind = Kind.Plate)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Pill("fade-left", "left", primary = false, kind = Kind.Fade)
                        Pill("fade-right", "right", primary = true, kind = Kind.Fade)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Pill("legacy-left", "left", primary = false, kind = Kind.Legacy)
                        Pill("legacy-right", "right", primary = true, kind = Kind.Legacy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Pill("flat-left", "none", primary = false, kind = Kind.Flat)
                        Pill("flat-right", "none", primary = true, kind = Kind.Flat)
                    }
                }
            }
        }
        settle()
    }

    private enum class Kind { Flush, Plate, Fade, Legacy, Flat }

    private enum class Edge { Top, Bottom, Left, Right }

    @Composable
    private fun Pill(tag: String, id: String, primary: Boolean, kind: Kind) {
        val palette = LocalMimirPalette.current
        val focused = focusedId == id
        val shape = RoundedCornerShape(percent = 50)
        val base = Modifier
            .size(pillWidth, pillHeight)
            .onGloballyPositioned { bounds[tag] = it.boundsInWindow() }
        val ringed = if (kind == Kind.Flat) {
            base
        } else {
            base.mimirSelectionRing(
                focused = focused,
                identity = tag,
                shape = MimirRingShape.Pill,
                flare = false,
                edgeFlush = kind == Kind.Flush || kind == Kind.Plate,
            )
        }
        val layered = ringed.graphicsLayer {
            alpha = 1f
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }.clip(shape)
        if (kind == Kind.Plate) {
            // The plate applies its modifier (the ring, the layer, the clip) BEFORE its own surface, as every plate caller relies on.
            MimirEtchedChipPlate(modifier = layered, warmed = false, focused = focused, primary = primary, palette = palette, edgeFlush = true) {}
            return
        }
        val surface = when (kind) {
            Kind.Plate -> layered // handled above
            Kind.Flush -> layered.mimirEtchedChipSurface(palette, warmed = false, focused = focused, primary = primary, edgeFlush = true)
            Kind.Fade -> layered.mimirEtchedChipSurface(palette, warmed = false, focused = focused, primary = primary)
            Kind.Legacy -> layered.drawWithCache {
                val metal: Brush = if (primary) SolidColor(palette.accent) else platformConsoleIconBrush(palette)
                val rimAlpha = if (focused) 0f else if (primary) 1f else mimirMetalRimAlpha(false)
                onDrawBehind { drawMimirMetalPlate(palette, metal, rimAlpha, false, cornerPx = size.height / 2f) }
            }
            Kind.Flat -> layered.drawWithCache {
                val metal: Brush = SolidColor(Color.Transparent)
                onDrawBehind { drawMimirMetalPlate(palette, metal, 0f, false, cornerPx = size.height / 2f) }
            }
        }
        Box(surface)
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

    // ---- outlines, counted -------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * The luminance along a line ACROSS one edge of [pill], at the middle of that edge, from 12 dp outside it (past the ring's reach, clear of the next pill's) to the rim's inner side
     * and a dp of ground beyond it (a band that ran off the end of the line would have no valley to stand out from), index 0 outermost. Nine parallel lines are averaged, so one noisy pixel is not a band.
     */
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

    /** How far [index] stands above the higher of its two bases: the lowest the profile falls on either side before it rises above [index]'s value again (or ends). */
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

    /** The bands in a cross-section: the peaks that stand [BandProminence] clear of the valleys beside them. Ties count once (at the first of equal values). */
    private fun bandCount(profile: FloatArray): Int {
        val closed = closed(profile)
        return closed.indices.count { prominence(closed, it) >= BandProminence }
    }

    /**
     * The profile with its one-pixel dips filled (a grey closing over two samples). Where the plate's anti-aliased edge meets the ring, one pixel is a shade darker than its neighbours:
     * a seam, not a gap. A gap the player can see is the half dp of the FADE row, which is two pixels at this density and survives.
     */
    private fun closed(profile: FloatArray): FloatArray {
        val dilated = FloatArray(profile.size) { i -> if (i + 1 < profile.size) max(profile[i], profile[i + 1]) else profile[i] }
        return FloatArray(profile.size) { i -> if (i > 0) min(dilated[i - 1], dilated[i]) else dilated[i] }
    }

    private fun bands(bitmap: Bitmap, tag: String): Map<Edge, Int> =
        Edge.entries.associateWith { bandCount(crossSection(bitmap, bounds.getValue(tag), it)) }

    private fun mostBands(bitmap: Bitmap, tag: String): Int = bands(bitmap, tag).values.max()

    private fun profileText(bitmap: Bitmap, tag: String, edge: Edge) =
        crossSection(bitmap, bounds.getValue(tag), edge).joinToString(" ") { "%.2f".format(it) }

    /** The profile of every edge that shows more than one band, for a failure message. */
    private fun crowded(bitmap: Bitmap, tag: String, perEdge: Map<Edge, Int>) =
        perEdge.filterValues { it > 1 }.keys.joinToString("; ") { "$it: ${profileText(bitmap, tag, it)}" }

    // ---- regions, averaged (a pill with neither rim nor ring is dead) --------------------------------------------------------------------------------------------------

    /** The rim region of a pill: its middle half along the top edge, from 8 dp outside (the ring's reach) to 5 dp inside. */
    private fun rimBand(bitmap: Bitmap, pill: Rect): FloatArray {
        val x0 = (pill.left + pill.width * 0.3f).toInt()
        val x1 = (pill.left + pill.width * 0.7f).toInt()
        val y0 = (pill.top - 8f * density).toInt()
        val y1 = (pill.top + 5f * density).toInt()
        val out = FloatArray((x1 - x0) * (y1 - y0))
        var i = 0
        for (y in y0 until y1) for (x in x0 until x1) out[i++] = luma(bitmap.getPixel(x, y))
        return out
    }

    /** How different a pill's rim region is from another's: the mean absolute luma difference, and the share of pixels that differ visibly. */
    private data class Difference(val mean: Float, val share: Float)

    private fun difference(bitmap: Bitmap, tag: String, otherTag: String): Difference {
        val a = rimBand(bitmap, bounds.getValue(tag))
        val b = rimBand(bitmap, bounds.getValue(otherTag))
        var sum = 0f
        var marked = 0
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            sum += d
            if (d > 0.03f) marked++
        }
        return Difference(sum / a.size, marked.toFloat() / a.size)
    }

    /** A frame is "dead" when the destination's rim region shows nothing but the flat pill's: neither rim nor ring. */
    private fun isDead(d: Difference) = d.mean < DeadMean && d.share < DeadShare

    // ---- driving ---------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Moves focus to [to] and runs the ring clock out (three overlay lengths), handing every frame's bitmap to [onFrame] with its index. */
    private fun move(to: String, onFrame: (index: Int, shot: Bitmap) -> Unit) {
        write(to)
        var elapsed = 0L
        var index = 0
        while (elapsed < MimirMotion.OverlayMs * 3L) {
            frame()
            elapsed += FrameMs
            onFrame(index++, shoot())
        }
    }

    private fun write(id: String) {
        compose.runOnUiThread { focusedId = id }
        Snapshot.sendApplyNotifications()
    }

    private fun line(log: List<Difference>) = log.joinToString { "%.4f/%.2f".format(it.mean, it.share) }

    // ---- the tests ---------------------------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the product shows at most one outline in every frame, on every edge, arriving and leaving, primary and plain`() {
        val tags = listOf("flush-left", "flush-right")
        val worst = mutableMapOf<String, MutableList<Int>>().apply { tags.forEach { put(it, mutableListOf()) } }
        var checked = 0

        fun record(direction: String, shot: Bitmap, index: Int) {
            for (tag in tags) {
                val perEdge = bands(shot, tag)
                worst.getValue(tag) += perEdge.values.max()
                checked++
                if (index in listOf(2, 6, 10, 14, 20, 33)) {
                    println("EtchedChipFocusMove flush profile $direction #$index $tag top (outside to inside): ${profileText(shot, tag, Edge.Top)}")
                }
                assertTrue(
                    "$direction frame $index: $tag shows two outlines on ${perEdge.filterValues { it > 1 }.keys}; outside to inside, ring-side first: ${crowded(shot, tag, perEdge)}",
                    perEdge.values.all { it <= 1 },
                )
            }
        }

        move("right") { index, shot -> record("onto the primary pill, off the plain one:", shot, index) }
        val ontoPrimary = worst.mapValues { it.value.toList() }
        println("EtchedChipFocusMove flush bands per frame, onto primary: " + ontoPrimary.entries.joinToString { "${it.key} ${it.value.joinToString("")}" })
        worst.values.forEach { it.clear() }
        move("left") { index, shot -> record("onto the plain pill, off the primary one:", shot, index) }
        println("EtchedChipFocusMove flush bands per frame, onto plain: " + worst.entries.joinToString { "${it.key} ${it.value.joinToString("")}" })

        assertTrue("the test looked at both pills in both moves ($checked samples)", checked >= 2 * 2 * 30)
        // A flush pill is never empty either: some band is on every frame of the arriving pill's move (the rim holds it until the ring is there).
        assertTrue("the arriving primary pill had a band in every frame of its move", ontoPrimary.getValue("flush-right").all { it >= 1 })
    }

    /**
     * The plate shape uses one band growing outward. The ring rides in the plate's own `modifier` and both halves opt in, and the
     * band counter reads at most ONE outline on every edge of both plates in every frame of the move, arriving and leaving, primary and plain. The FADE row (the default ring and a fading
     * rim: what every one of these call sites drew until now) is the control that proves the same counter sees two.
     */
    @Test
    fun `the plate shape shows at most one outline in every frame of the move, arriving and leaving`() {
        val tags = listOf("plate-left", "plate-right")
        var checked = 0
        val arriving = mutableListOf<Int>()

        fun record(direction: String, shot: Bitmap, index: Int) {
            for (tag in tags) {
                val perEdge = bands(shot, tag)
                checked++
                if (tag == "plate-right" && direction.startsWith("onto the primary")) arriving += perEdge.values.max()
                assertTrue(
                    "$direction frame $index: $tag shows two outlines on ${perEdge.filterValues { it > 1 }.keys}; outside to inside, ring-side first: ${crowded(shot, tag, perEdge)}",
                    perEdge.values.all { it <= 1 },
                )
            }
        }

        move("right") { index, shot -> record("onto the primary plate, off the plain one:", shot, index) }
        move("left") { index, shot -> record("onto the plain plate, off the primary one:", shot, index) }
        println("EtchedChipFocusMove plate bands per frame, onto primary: ${arriving.joinToString("")}")
        assertTrue("the test looked at both plates in both moves ($checked samples)", checked >= 2 * 2 * 30)
        assertTrue("the arriving primary plate had a band in every frame of its move: $arriving", arriving.all { it >= 1 })
        // A settled focused plate is one band, and the plate that gave up focus rests as the fade row's does.
        write("right")
        repeat(40) { frame() }
        val settled = shoot()
        val settledBands = bands(settled, "plate-right")
        assertTrue("plate-right settled focused: one band on every edge, got $settledBands: ${crowded(settled, "plate-right", settledBands)}", settledBands.values.all { it == 1 })
        assertEquals("the plate that gave up focus rests as it did", 0f, difference(settled, "plate-left", "fade-left").mean, 0.0005f)
    }

    @Test
    fun `the harness sees two outlines in the fade hand-over the first fix shipped`() {
        val twoBandFrames = mutableListOf<String>()
        var worstProfile = ""
        val logs = mutableMapOf<String, MutableList<Int>>("fade-left" to mutableListOf(), "fade-right" to mutableListOf())
        move("right") { index, shot ->
            for (tag in logs.keys) {
                val perEdge = bands(shot, tag)
                logs.getValue(tag) += perEdge.values.max()
                if (perEdge.values.any { it >= 2 }) {
                    twoBandFrames += "$tag#$index"
                    if (index in listOf(4, 8, 12, 16, 20)) println("EtchedChipFocusMove fade $tag#$index top: ${profileText(shot, tag, Edge.Top)}")
                    if (worstProfile.isEmpty()) worstProfile = "$tag#$index: " + profileText(shot, tag, Edge.Top)
                }
            }
        }
        println("EtchedChipFocusMove fade bands per frame: " + logs.entries.joinToString { "${it.key} ${it.value.joinToString("")}" })
        println("EtchedChipFocusMove fade first two-outline profile: $worstProfile")
        assertTrue(
            "the FADE row (default ring, rim fading beside it) must show two outlines in some frame, or this test cannot catch them: $twoBandFrames",
            twoBandFrames.size >= 2,
        )
    }

    @Test
    fun `the destination pill never stands without rim or ring on a focus move`() {
        // The move onto the primary pill (PLAY), then back onto the plain one (OPEN WITH).
        val restPrimary = difference(shoot(), "flush-right", "flat-right")
        val onto = mutableListOf<Difference>()
        move("right") { _, shot -> onto += difference(shot, "flush-right", "flat-right") }
        val ontoDead = onto.withIndex().filter { isDead(it.value) }.map { it.index }
        println("EtchedChipFocusMove flush onto primary (rest ${"%.4f".format(restPrimary.mean)}): ${line(onto)}")
        assertTrue("onto the primary pill: frames with neither rim nor ring $ontoDead", ontoDead.isEmpty())
        // The strongest form: not one frame is weaker than the pill's own resting rim, so the focus indicator never dips on the way.
        assertTrue(
            "onto the primary pill: a frame is weaker than the rim at rest (${restPrimary.mean}): ${onto.minOf { it.mean }}",
            onto.all { it.mean >= restPrimary.mean * 0.9f },
        )

        write("none")
        repeat(40) { frame() }
        val plainRest = difference(shoot(), "flush-left", "flat-left") // not focused: the rim whole
        val back = mutableListOf<Difference>()
        move("left") { _, shot -> back += difference(shot, "flush-left", "flat-left") }
        val backDead = back.withIndex().filter { isDead(it.value) }.map { it.index }
        println("EtchedChipFocusMove flush onto plain (rest ${"%.4f".format(plainRest.mean)}): ${line(back)}")
        assertTrue("onto the plain pill: frames with neither rim nor ring $backDead", backDead.isEmpty())
        assertTrue(
            "onto the plain pill: a frame is weaker than the rim at rest (${plainRest.mean}): ${back.minOf { it.mean }}",
            back.all { it.mean >= plainRest.mean * 0.9f },
        )
    }

    @Test
    fun `the harness sees the defect - the one-step hand-over leaves dead frames`() {
        val onto = mutableListOf<Difference>()
        move("right") { _, shot -> onto += difference(shot, "legacy-right", "flat-right") }
        val dead = onto.count { isDead(it) }
        println("EtchedChipFocusMove legacy onto primary: ${line(onto)}")
        assertTrue("the legacy surface must show frames with neither rim nor ring, or this test cannot catch the defect (dead=$dead)", dead >= 2)
    }

    @Test
    fun `the pill being left never spikes above its settled look, where the legacy one did`() {
        val settledFlush = difference(shoot(), "flush-left", "flat-left").mean
        val settledLegacy = difference(shoot(), "legacy-left", "flat-left").mean
        val flushLeaving = mutableListOf<Difference>()
        val legacyLeaving = mutableListOf<Difference>()
        move("right") { _, shot ->
            flushLeaving += difference(shot, "flush-left", "flat-left")
            legacyLeaving += difference(shot, "legacy-left", "flat-left")
        }
        println("EtchedChipFocusMove leaving, settled flush ${"%.4f".format(settledFlush)}: ${line(flushLeaving)}; settled legacy ${"%.4f".format(settledLegacy)}: ${line(legacyLeaving)}")
        // Two outlines whole at once: the legacy pill got its rim back in one step under a ring that was still near whole.
        val legacyPeak = legacyLeaving.maxOf { it.mean }
        val flushPeak = flushLeaving.maxOf { it.mean }
        assertTrue("the harness must see the legacy spike ($legacyPeak over $settledLegacy)", legacyPeak > settledLegacy * 1.05f)
        assertTrue("the flush pill must not spike ($flushPeak over $settledFlush)", flushPeak <= settledFlush * 1.03f)
    }

    @Test
    fun `a settled pill is one band when focused, and a resting one is what it was before`() {
        // Resting (the right pills are unfocused at the start): the flush surface paints exactly what the fade one does, the primary's whole accent rim.
        val resting = shoot()
        println("EtchedChipFocusMove resting primary top: ${profileText(resting, "flush-right", Edge.Top)}")
        val restingDifference = difference(resting, "flush-right", "fade-right")
        assertEquals("the resting primary paints what it did", 0f, restingDifference.mean, 0.0005f)
        assertEquals("and not one pixel differs visibly", 0f, restingDifference.share, 0f)
        assertEquals("the resting primary has its one rim", 1, mostBands(resting, "flush-right"))

        // Settled focused: one band on every edge, and not dead. The left pill is focused at the start.
        val focusedLeft = difference(resting, "flush-left", "flat-left")
        val focusedLeftBands = bands(resting, "flush-left")
        assertTrue("flush-left settled focused: one band on every edge, got $focusedLeftBands: ${crowded(resting, "flush-left", focusedLeftBands)}", focusedLeftBands.values.all { it == 1 })
        write("right")
        repeat(40) { frame() }
        val settled = shoot()
        val focusedRight = difference(settled, "flush-right", "flat-right")
        assertTrue("both settled focused pills show a band: $focusedLeft $focusedRight", !isDead(focusedLeft) && !isDead(focusedRight))
        val focusedRightBands = bands(settled, "flush-right")
        assertTrue("flush-right settled focused: one band on every edge, got $focusedRightBands: ${crowded(settled, "flush-right", focusedRightBands)}", focusedRightBands.values.all { it == 1 })

        // And the plain pill, unfocused again, is back to its resting self: the same pixels as the fade row's.
        println("EtchedChipFocusMove resting plain top: ${profileText(settled, "flush-left", Edge.Top)}")
        println("EtchedChipFocusMove focused primary top: ${profileText(settled, "flush-right", Edge.Top)}")
        val plainRest = difference(settled, "flush-left", "fade-left")
        assertEquals("the pill that gave up focus rests as it did", 0f, plainRest.mean, 0.0005f)
    }

    @Test
    fun `a contact sheet of the move, legacy over fade over the flush product`() {
        val picks = listOf(0, 1, 2, 3, 5, 7, 10, 14, 20, 28)
        // Rows: the arriving primary pill (legacy, fade, flush), then the leaving plain pill (fade, flush).
        val rows = listOf("legacy-right", "fade-right", "flush-right", "fade-left", "flush-left")
        val margin = (14f * density).roundToInt()
        val cells = mutableMapOf<Pair<String, Int>, Bitmap>()
        move("right") { index, shot ->
            if (index in picks) {
                for (tag in rows) {
                    val r = bounds.getValue(tag)
                    val x = (r.left.roundToInt() - margin).coerceAtLeast(0)
                    val y = (r.top.roundToInt() - margin).coerceAtLeast(0)
                    val w = min(r.width.roundToInt() + 2 * margin, shot.width - x)
                    val h = min(r.height.roundToInt() + 2 * margin, shot.height - y)
                    cells[tag to index] = Bitmap.createBitmap(shot, x, y, w, h)
                }
            }
        }
        val cellW = cells.values.maxOf { it.width }
        val cellH = cells.values.maxOf { it.height }
        val sheet = Bitmap.createBitmap(cellW * picks.size, cellH * rows.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        rows.forEachIndexed { row, tag ->
            picks.forEachIndexed { column, index -> canvas.drawBitmap(cells.getValue(tag to index), (column * cellW).toFloat(), (row * cellH).toFloat(), null) }
        }
        val out = File(File("build/ttx").apply { mkdirs() }, "chip-focus-move-v2.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/ttx")
        if (shared.isDirectory) out.copyTo(File(shared, "chip-focus-move-v2.png"), overwrite = true)
        println("TTX wrote ${out.absolutePath} (${sheet.width}x${sheet.height} px): frames ${picks.joinToString()} of 16 ms; rows ${rows.joinToString()}")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    private companion object {
        const val FrameMs = 16L

        /** Calibrated on the first run: under these a band is the flat pill's own. */
        const val DeadMean = 0.004f
        const val DeadShare = 0.01f

        /** A cross-section starts this far outside the edge: clear of the ring's 13.5 dp reach of the NEXT pill (28 dp away). */
        const val OutsideDp = 12f

        /** The ground past the rim that the cross-section ends in. */
        const val TailDp = 1f

        /** Lines averaged either side of the cross-section's own (so nine). */
        const val Lanes = 4

        /** How far a peak must stand above the valleys beside it, in luminance (0 to 1), to be a band. */
        const val BandProminence = 0.04f
    }
}
