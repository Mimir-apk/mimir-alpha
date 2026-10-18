package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V6 of the Thor Bottom revamp, rasterised: the lattice cursor and the cursor inside an open Tome wear Main's one ring (`mimirSelectionRing`, the
 * `tileFocusRing` colour, the ring clock and its one sweep of gleam), on the REAL tile ([CompanionHomeGridTile]) and the REAL Tome leaves
 * ([CompanionTomeLeaves]), under Robolectric's native graphics at the Thor Bottom's density (369 dpi).
 *
 * **What is real and what is a frame around it.** The real [CompanionSurface] cannot be rasterised under Robolectric (its scene transition draws explicit
 * graphics layers, which the software canvas refuses, and `captureToImage` is never delivered a redraw by the paused looper), so the surface's geometry is
 * pinned by [CompanionLatticeFootprintRenderTest] (semantics) and this class composes the tile in a 3 x 3 page of exactly those measured cell sizes
 * ([ThorBottomCells]), with the page's own skeleton: the 12 dp rail inset inside a clipping pager, the 12 dp column gap, rows that touch. The Tome's leaves are the
 * production composable itself. Each is compared against the same page with NO cursor, so every changed pixel is the ring.
 *
 * Every number the report quotes is printed with the tag `V6-PIXELS`. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h445dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionCursorRingRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF10141C)
    private val ringColor: Color = MimirThemePalette.forId(MimirThemeId.DEFAULT).tileFocusRing

    private val tiles = listOf(
        "Nintendo Entertainment System",
        "Super Nintendo Entertainment System",
        "Game Boy Advance",
        "Sega Genesis",
        "PlayStation Portable",
        "Nintendo 64",
        "Dreamcast",
        "Neo Geo Pocket Color",
        "Atari 2600",
    ).mapIndexed { i, name -> CompanionHomeTile("p${i + 1}", CompanionHomeTileKind.PLATFORM, name) }

    private var cursor by mutableStateOf<String?>(null)

    /** Something is painted over the lattice or the Tome (the recents drawer, an open Tome): every tile is handed it, as the surface hands its tiles. */
    private var covered by mutableStateOf(false)
    private var fontScale = 1f
    private var density = 0f

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The harnesses.
    // ---------------------------------------------------------------------------------------------------------------------------------

    /** The 3 x 3 page: the rail inset inside a clipping pager, rows that touch, the 12 dp column gap, every tile the real one. */
    @Composable
    private fun LatticePage(cell: ThorBottomCell, layerAlpha: Float) {
        val inset = CompanionRailInsetDp
        Box(
            Modifier
                .requiredSize((3 * cell.width + 2 * 12f + 2 * inset).dp, (3 * cell.height + 2 * inset).dp)
                .graphicsLayer { alpha = layerAlpha }
                .background(ground)
                .clipToBounds()
                .testTag(PageTag),
        ) {
            Column(Modifier.fillMaxSize().padding(inset.dp)) {
                repeat(3) { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { col ->
                            val tile = tiles[row * 3 + col]
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                CompanionHomeGridTile(
                                    tile = tile,
                                    cursor = tile.id == cursor,
                                    covered = covered,
                                    ghost = false,
                                    binding = false,
                                    onTap = { },
                                    testTag = companionTileTag(tile.id),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val children = (1..6).map { CompanionHomeTile("c$it", CompanionHomeTileKind.PLATFORM, "Console number $it") }

    /** The real Tome leaves under a full-size layer at [layerAlpha], as the Tome's reveal draws its page, with a bookplate's worth of room above. */
    @Composable
    private fun TomeLeaves(layerAlpha: Float) {
        Box(Modifier.requiredSize(480.dp, 380.dp).graphicsLayer { alpha = layerAlpha }.background(ground).testTag(PageTag)) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(36.dp))
                CompanionTomeLeaves(
                    children = children,
                    selectedChildId = cursor,
                    covered = covered,
                    maxHeightDp = 240f,
                    onTap = { cursor = it.id },
                    onLongPress = {},
                )
            }
        }
    }

    private fun mount(scale: Float, content: @Composable () -> Unit) {
        fontScale = scale
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, scale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    content()
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 2_000L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Pixels.
    // ---------------------------------------------------------------------------------------------------------------------------------

    /** The window's pixels, drawn by the view hierarchy itself into a software bitmap. */
    private fun fullBitmap(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private fun frame(): PixelMap = fullBitmap().asImageBitmap().toPixelMap()

    private fun cropped(region: Rect): PixelMap {
        val full = fullBitmap()
        return Bitmap.createBitmap(full, region.left.toInt(), region.top.toInt(), region.width.toInt(), region.height.toInt()).asImageBitmap().toPixelMap()
    }

    private fun boundsPx(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun PixelMap.diffAt(other: PixelMap, x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        val a = this[x, y]
        val b = other[x, y]
        return max(max(abs(a.red - b.red), abs(a.green - b.green)), abs(a.blue - b.blue))
    }

    /** The largest per-channel difference over [rect] (px), and how many pixels differ by more than [above]. */
    private fun PixelMap.diffIn(other: PixelMap, rect: Rect, above: Float = 0.12f): Pair<Float, Int> {
        var worst = 0f
        var count = 0
        for (y in rect.top.toInt()..rect.bottom.toInt()) {
            for (x in rect.left.toInt()..rect.right.toInt()) {
                val d = diffAt(other, x, y)
                if (d > worst) worst = d
                if (d > above) count++
            }
        }
        return worst to count
    }

    private fun Rect.inflateDp(dp: Float) = Rect(left - dp * density, top - dp * density, right + dp * density, bottom + dp * density)

    private fun Color.distanceTo(other: Color) = max(max(abs(red - other.red), abs(green - other.green)), abs(blue - other.blue))

    /** Where a tile's face stands in window px: the cell's top, its ring square centred, the face 4 dp inside the square (the solver's own geometry). */
    private fun faceRectPx(cell: Rect): Rect {
        val solved = solveCompanionLatticeCell(cell.width / density, cell.height / density, fontScale)
        val inset = (solved.ringEdgeDp - solved.faceEdgeDp) / 2f
        val left = cell.left + ((cell.width / density - solved.ringEdgeDp) / 2f + inset) * density
        val top = cell.top + inset * density
        return Rect(left, top, left + solved.faceEdgeDp * density, top + solved.faceEdgeDp * density)
    }

    private fun labelBandPx(cell: Rect): Rect {
        val solved = solveCompanionLatticeCell(cell.width / density, cell.height / density, fontScale)
        return Rect(cell.left, cell.top + solved.labelTopDp * density, cell.right, cell.top + (solved.labelTopDp + solved.bandDp) * density)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The lattice: the ring is drawn, clears the neighbouring faces and the names, and is not cut by the page or its fade.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun assertLattice(scale: Float, cell: ThorBottomCell, name: String) {
        mount(scale) { LatticePage(cell, layerAlpha = 1f) }
        val without = frame()
        cursor = "p5"
        settle()
        val with = frame()

        val cellPx = boundsPx(companionTileTag("p5"))
        val face = faceRectPx(cellPx)
        val solved = solveCompanionLatticeCell(cellPx.width / density, cellPx.height / density, scale)
        println("V6-PIXELS $name cell=${cellPx.width / density}x${cellPx.height / density}dp ring=${solved.ringEdgeDp} face=${solved.faceEdgeDp} band=${solved.bandDp}")

        // 1. The ring is drawn: at the face's left and right edge, 2 dp outside it (the bright stroke's centre), at mid height, the pixel is the ring's colour and no
        // longer what the page drew without a cursor.
        val midY = ((face.top + face.bottom) / 2f).toInt()
        for ((side, x) in listOf("left" to (face.left - 2f * density), "right" to (face.right + 2f * density))) {
            val on = with[x.toInt(), midY]
            val off = without[x.toInt(), midY]
            println("V6-PIXELS stroke $name $side on=$on off=$off ring=$ringColor")
            assertTrue("the $side stroke is drawn: $off -> $on", on.distanceTo(off) > 0.25f)
            assertTrue("the $side stroke is in the ring's colour ($ringColor), was $on", on.distanceTo(ringColor) < 0.45f && on.distanceTo(ringColor) < off.distanceTo(ringColor))
        }
        val (ringWorst, ringCount) = with.diffIn(without, face.inflateDp(14f), above = 0.25f)
        println("V6-PIXELS ring $name strongPixels=$ringCount worstDiff=$ringWorst")
        assertTrue("the ring has strong pixels around the face", ringCount > 200)

        // 2. It clears every other face on the page: not one pixel of any neighbouring face differs from the page with no cursor.
        for (other in 1..9) {
            if (other == 5) continue
            val neighbour = faceRectPx(boundsPx(companionTileTag("p$other")))
            val (worst, count) = with.diffIn(without, neighbour, above = 0.0f)
            assertEquals("the ring touches the face of tile p$other ($worst)", 0, count)
        }

        // 3. It clears the names per the 8 dp rule: the 0.95 bright stroke reaches 3.5 dp past the face and the 0.18 middle one 8.5 dp, and the name bands stand 8 dp
        // from the face (this tile's own band under it, and the band of the row above, which ends 4 dp above this cell). Only the 8% outer bloom (6.5 to 13.5 dp)
        // may cross into a band, about 6 dp deep: so no pixel moves by more than the bloom beyond the band's first dp, and nothing strong lands in it at all.
        val own = labelBandPx(cellPx)
        val above = labelBandPx(boundsPx(companionTileTag("p2")))
        for ((where, band) in listOf("own" to own, "row above" to above)) {
            val (worstAll, countAll) = with.diffIn(without, band, above = 0.30f)
            val deep = if (where == "own") Rect(band.left, band.top + 1f * density, band.right, band.bottom) else Rect(band.left, band.top, band.right, band.bottom - 1f * density)
            val (worstDeep, countDeep) = with.diffIn(without, deep, above = 0.12f)
            println("V6-PIXELS band $name $where worstDiff=$worstAll strong=$countAll worstDiffBeyondFirstDp=$worstDeep strongBeyondFirstDp=$countDeep")
            assertEquals("a strong stroke reaches the $where name band", 0, countAll)
            assertEquals("more than the outer bloom reaches the $where name band beyond its first dp ($worstDeep)", 0, countDeep)
        }
    }

    @Test
    fun `lattice ring at font scale 1_0`() = assertLattice(1.0f, ThorBottomCells.Scale10, "1.0")

    @Test
    fun `lattice ring at font scale 1_3 with the hint shown`() = assertLattice(1.3f, ThorBottomCells.Scale13Shown, "1.3-hint-shown")

    @Test
    fun `lattice ring at font scale 1_3 with the hint retired`() = assertLattice(1.3f, ThorBottomCells.Scale13Retired, "1.3-hint-retired")

    /**
     * The offscreen-fade trap: a layer below alpha 1 is composited at its own bounds, so a ring drawn inside a fading layer is cut at those bounds mid-fade and
     * pops back at alpha 1. The lattice's fades are the Presentation's intro (`graphicsLayer { alpha }` over the whole window) and the scene transition's: both are
     * full-size, so the ring on the TOP row, whose bloom reaches the furthest toward an edge, is the case. Here the whole page sits under a layer at 0.6 alpha,
     * inside the clipping pager's bounds (the 12 dp rail inset): the bloom still reaches its full 13.5 dp above the face.
     */
    @Test
    fun `the top row's ring is not cut by the pager or by a fading layer`() {
        val cell = ThorBottomCells.Scale10
        mount(1.0f) { LatticePage(cell, layerAlpha = 0.6f) }
        val without = frame()
        cursor = "p2"
        settle()
        val with = frame()
        val cellPx = boundsPx(companionTileTag("p2"))
        val face = faceRectPx(cellPx)
        val page = boundsPx(PageTag)
        // The bloom's outer edge is 13.5 dp above the face; the pager's clip is 12 dp above the cell and the cell is 4 dp above the face: 16 dp of room.
        val room = (face.top - page.top) / density
        println("V6-PIXELS fade topRow roomAboveFace=${room}dp bloomReach=13.5dp")
        assertTrue("the page leaves room for the bloom above the top row: $room dp", room >= 13.5f)
        // Pixels changed in the outer bloom's far band (11.5 to 13 dp above the face), across the face's width: it is drawn, under the fading layer.
        val far = Rect(face.left, face.top - 13f * density, face.right, face.top - 11.5f * density)
        val (worst, count) = with.diffIn(without, far, above = 0.0f)
        println("V6-PIXELS fade topRow changedPixels11_5to13dpAboveFace=$count worstDiff=$worst")
        assertTrue("the ring's outer bloom is drawn 11.5 to 13 dp above the top row's face, under a fading layer: $count pixels", count > 40)
        // And it is the SAME ring a settled page draws: the fade only dims it (the strong stroke 2 dp out is there at the faded alpha).
        val midY = ((face.top + face.bottom) / 2f).toInt()
        val on = with[(face.left - 2f * density).toInt(), midY]
        val off = without[(face.left - 2f * density).toInt(), midY]
        assertTrue("the strong stroke is drawn under the fade: $off -> $on", on.distanceTo(off) > 0.1f)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Inside an open Tome: the real leaves.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun assertTome(scale: Float, layerAlpha: Float, label: String) {
        mount(scale) { TomeLeaves(layerAlpha) }
        cursor = "c6"
        settle()
        val withLast = frame()
        cursor = "c1"
        settle()
        val withFirst = frame()

        val cell = boundsPx(companionTileCellTag("c1"))
        val solved = solveCompanionLatticeCell(cell.width / density, cell.height / density, scale)
        println(
            "V6-PIXELS tome $label cell=${cell.width / density}x${cell.height / density}dp ring=${solved.ringEdgeDp} face=${solved.faceEdgeDp} band=${solved.bandDp} " +
                "headroom=${CompanionTomeRingHeadroomDp}dp",
        )
        assertEquals("the in-Tome cell is the briefed 104 x 122", 104f, cell.width / density, 0.6f)
        assertEquals(122f, cell.height / density, 0.6f)
        val face = faceRectPx(cell)

        // The ring is drawn around the first child's face: the surface differs from the one with the cursor on the last child by this ring (and the last child's).
        val (worst, count) = withFirst.diffIn(withLast, face.inflateDp(14f), above = 0.25f * layerAlpha)
        println("V6-PIXELS tome ring $label strongPixels=$count worstDiff=$worst")
        assertTrue("the ring is drawn around the first child's face inside the Tome", count > 200 * layerAlpha)

        // The scroll above the first row clips its content. The face stands 4 dp under its cell, the bloom reaches 13.5 dp past the face: the scroll's content starts
        // 10 dp above the cell, so the outer bloom (10 to 12 dp above the face) is painted and not cut flat at the cell's top edge 4 dp up.
        val above = Rect(face.left, face.top - 12f * density, face.right, face.top - 10f * density)
        val (aboveWorst, aboveCount) = withFirst.diffIn(withLast, above, above = 0.0f)
        println("V6-PIXELS tome headroom $label changedPixels10to12dpAboveFace=$aboveCount worstDiff=$aboveWorst")
        assertTrue("the ring's bloom reaches 10 to 12 dp above the first row's face inside the Tome's scroll: it is not clipped ($aboveCount px)", aboveCount > 20)

        // It clears its sibling in the row (12 dp apart): c2's face is identical in both frames (c6's ring is elsewhere), and the name of its own cell keeps the 8 dp rule.
        val sibling = faceRectPx(boundsPx(companionTileCellTag("c2")))
        val (_, siblingCount) = withFirst.diffIn(withLast, sibling.inflateDp(-1f), above = 0.0f)
        assertEquals("the ring stays off the sibling's face", 0, siblingCount)
        val band = labelBandPx(cell)
        val deep = Rect(band.left, band.top + 1f * density, band.right, band.bottom)
        val (_, bandDeep) = withFirst.diffIn(withLast, deep, above = 0.12f)
        assertEquals("more than the outer bloom reaches the name beyond the band's first dp", 0, bandDeep)
    }

    @Test
    fun `inside a Tome at font scale 1_0`() = assertTome(1.0f, 1f, "1.0")

    @Test
    fun `inside a Tome at font scale 1_3`() = assertTome(1.3f, 1f, "1.3")

    /** The Tome's reveal draws its page under a full-size layer at the reveal's alpha: the ring inside it must not be cut mid-fade. */
    @Test
    fun `inside a Tome under its fading reveal layer`() = assertTome(1.0f, 0.6f, "1.0-reveal-0.6")

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The clock: the ring arrives on the ring clock, the gleam sweeps once, and then the frame is still.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the ring arrives on the ring clock and the gleam sweeps once and then the frame is still`() {
        mount(1.0f) { LatticePage(ThorBottomCells.Scale10, layerAlpha = 1f) }
        val cellPx = boundsPx(companionTileTag("p5"))
        val face = faceRectPx(cellPx)
        val region = face.inflateDp(15f)
        val without = cropped(region)

        var writes = 0
        val observer = Snapshot.registerGlobalWriteObserver { writes++ }
        val frames = ArrayList<PixelMap>()
        val pageHashes = ArrayList<Int>()
        val writesAt = ArrayList<Int>()
        try {
            cursor = "p5"
            Snapshot.sendApplyNotifications()
            // One frame at a time for 3.2 s: the ring (420 ms), the gleam's settle (160 ms) and sweep (540 ms), then a long still stretch.
            repeat(200) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val full = fullBitmap()
                frames.add(Bitmap.createBitmap(full, region.left.toInt(), region.top.toInt(), region.width.toInt(), region.height.toInt()).asImageBitmap().toPixelMap())
                val pixels = IntArray(full.width * full.height)
                full.getPixels(pixels, 0, full.width, 0, 0, full.width, full.height)
                pageHashes.add(pixels.contentHashCode())
                writesAt.add(writes)
            }
        } finally {
            observer.dispose()
        }
        val last = frames.last()
        fun meanDiff(a: PixelMap, b: PixelMap): Float {
            var sum = 0f
            for (y in 0 until a.height) for (x in 0 until a.width) sum += a.diffAt(b, x, y)
            return sum / (a.width * a.height)
        }
        // d(t): how far frame t is from the settled ring, one frame (16 ms) apart; lit(t): how many pixels of frame t differ from it at all.
        val d = frames.map { meanDiff(it, last) }
        val lit = frames.map { f ->
            var n = 0
            for (y in 0 until f.height) for (x in 0 until f.width) if (f.diffAt(last, x, y) > 0f) n++
            n
        }
        val settledFrom = lit.indexOfLast { it > 0 } + 1
        val fromNone = meanDiff(without, last)
        println(
            "V6-PIXELS clock frames=${frames.size} diffNoRing=$fromNone settledAtFrame=$settledFrom (~${settledFrom * 16}ms) " +
                "d@16ms=${d[0]} d@32ms=${d[1]} d@144ms=${d[8]} d@288ms=${d[17]} d@432ms=${d[26]}",
        )
        println("V6-PIXELS clock litPixelsPerFrame(0..${settledFrom + 2})=${lit.take(settledFrom + 3)}")
        println("V6-PIXELS clock meanDiffPerFrame(0..40)=${d.take(41).map { "%.4f".format(it) }}")
        // The ring grows on the 420 ms ring clock, not the 140 ms house focus clock: at 144 ms (9 frames) it is still well short of whole, and it only grows until the gleam.
        assertTrue("at 144 ms the ring is not yet whole (d=${d[8]}, vs $fromNone with none)", d[8] > 0.2f * fromNone)
        assertTrue("the ring grows monotonically while it arrives", (1..20).all { d[it] <= d[it - 1] + 0.0005f })
        assertTrue("the ring has begun by the third frame (48 ms): d=${d[2]} vs $fromNone with none", d[2] < fromNone)
        assertTrue("the ring is whole well after its 420 ms: d@432ms=${d[26]} is under a tenth of the way", d[26] < 0.1f * fromNone)

        // The gleam: a band of near-white light on the inner stroke, brighter than the settled ring ever is. So the pixels of a frame that are BRIGHTER than the settled frame
        // are the gleam and nothing else (the ring only grows toward its settled look from below). They form ONE contiguous run of frames, which starts after the 160 ms settle
        // (frame 10) and ends before the sweep's 700 ms (160 + 540), and the lit pixels' centroid travels along the diagonal from the top-left to the bottom-right.
        fun brighterThanSettled(frameIndex: Int, sink: ((Int, Int) -> Unit)? = null): Int {
            var n = 0
            val f = frames[frameIndex]
            for (y in 0 until last.height) for (x in 0 until last.width) {
                val a = f[x, y]
                val b = last[x, y]
                if (max(max(a.red - b.red, a.green - b.green), a.blue - b.blue) > 0.04f) {
                    n++
                    sink?.invoke(x, y)
                }
            }
            return n
        }
        val bright = frames.indices.map { brighterThanSettled(it) }
        val sweep = bright.indices.filter { bright[it] > 0 }
        println("V6-PIXELS gleam brighterPixelsPerFrame=${bright.take(settledFrom + 3)}")
        println("V6-PIXELS gleam frames=${sweep.size} first=${sweep.firstOrNull()} (~${(sweep.firstOrNull() ?: 0) * 16 + 16}ms) last=${sweep.lastOrNull()} (~${(sweep.lastOrNull() ?: 0) * 16 + 16}ms)")
        assertTrue("the gleam is drawn", sweep.isNotEmpty())
        assertTrue("the gleam is one contiguous run of frames: $sweep", sweep.zipWithNext().all { (a, b) -> b == a + 1 })
        assertTrue("the gleam waits out the 160 ms settle: it starts at frame ${sweep.first()}", (sweep.first() + 1) * 16 >= 160)
        assertTrue("the gleam ends by 700 ms (+2 frames), was frame ${sweep.last()}", (sweep.last() + 1) * 16 <= 700 + 32)
        fun centroid(frameIndex: Int): Float {
            var sum = 0f
            var n = 0
            brighterThanSettled(frameIndex) { x, y -> sum += (x + y); n++ }
            return if (n == 0) Float.NaN else sum / n
        }
        val early = centroid(sweep.first() + 1)
        val late = centroid(sweep.last() - 1)
        println("V6-PIXELS gleam centroid(x+y, px) early=$early late=$late")
        assertTrue("the gleam travels (centroid x+y $early -> $late)", !early.isNaN() && !late.isNaN() && late > early + 40f)

        // Then still. Not one pixel of the page changes once the ring has settled and the gleam's last visible pixel is gone, and not one snapshot state is written once the
        // gleam's own clock (the 160 ms settle and the 540 ms sweep, 700 ms after the cursor landed) has run out: no ticker, no breath, no frame requested at rest.
        val writesPerFrame = writesAt.zipWithNext { a, b -> b - a }
        val quietFrom = writesPerFrame.indexOfLast { it > 0 } + 2
        println("V6-PIXELS writesPerFrame(0..60)=${writesPerFrame.take(61)}")
        println("V6-PIXELS still pixelsStillFromFrame=$settledFrom (~${settledFrom * 16}ms) writesQuietFromFrame=$quietFrom (~${quietFrom * 16}ms) quietFrames=${frames.size - quietFrom}")
        assertTrue("the clock ran its 160 + 540 ms (quiet from ${quietFrom * 16} ms)", quietFrom * 16 >= 640)
        assertTrue("the clock is over by 700 ms (+ three frames): quiet from ${quietFrom * 16} ms", quietFrom * 16 <= 700 + 48)
        assertTrue("a long still stretch follows (${frames.size - quietFrom} frames)", frames.size - quietFrom > 100)
        assertEquals("no state is written for the whole still stretch", 0, writesAt.last() - writesAt[quietFrom])
        for (i in settledFrom until frames.size) {
            assertEquals("the page at frame $i differs from the settled one", pageHashes.last(), pageHashes[i])
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The V6 fix round: the ring follows the cursor only, and a cover settles it.
    // ---------------------------------------------------------------------------------------------------------------------------------

    /** The window's pixels as one hash: any frame-to-frame change anywhere on the page, the ring's growth or the gleam's band, changes it. */
    private fun pageHash(): Int {
        val full = fullBitmap()
        val pixels = IntArray(full.width * full.height)
        full.getPixels(pixels, 0, full.width, 0, 0, full.width, full.height)
        return pixels.contentHashCode()
    }

    private fun frames(count: Int): List<Int> = (1..count).map {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        pageHash()
    }

    /**
     * a press that is cancelled on the selected tile used to toggle `focused` through `!lit`, fading the ring down and back and replaying the gleam with no cursor
     * move. The ring follows the cursor only; the press has its own response (the press scale of B5 and the rim). So after a press and a cancel the page returns to exactly the settled
     * frame as soon as the press scale has (the 140 ms focus beat), and never again leaves it: no fade, no regrowth, no second sweep.
     */
    @Test
    fun `a press that is cancelled on the selected tile changes nothing about its ring or its gleam`() {
        mount(1.0f) { LatticePage(ThorBottomCells.Scale10, layerAlpha = 1f) }
        cursor = "p5"
        settle(3_000L)
        val still = pageHash()
        compose.onNodeWithTag(companionTileTag("p5")).performTouchInput { down(center) }
        val pressed = frames(12)
        println("V6B-PIXELS press pressedFramesDifferFromStill=${pressed.count { it != still }}/${pressed.size}")
        assertTrue("the press is seen (its scale and rim): the page is not the still frame while it is held", pressed.last() != still)
        compose.onNodeWithTag(companionTileTag("p5")).performTouchInput { cancel() }
        val after = frames(110)
        // The press scale's 140 ms tween is over within about 14 frames.
        val returnedAt = after.indexOfFirst { it == still }
        println("V6B-PIXELS press afterCancel returnedToStillAtFrame=$returnedAt (~${(returnedAt + 1) * 16}ms) frames=${after.size}")
        assertTrue("the page returns to the settled frame once the press response is done (frame $returnedAt)", returnedAt in 0..14)
        for (i in returnedAt until after.size) {
            assertEquals("frame $i after the cancel differs from the settled one: the ring faded, regrew or gleamed", still, after[i])
        }
    }

    /**
     * `armed` only gates the START of the sweep, so a cover that came up mid-sweep left it running behind the scrim. A covered cursor tile settles at once: the ring is
     * whole on the first covered frame, the sweep is cancelled, and nothing replays when the cover leaves. A new cursor is a new arrival.
     */
    @Test
    fun `a cover that arrives mid-sweep settles the ring at once and nothing replays when it leaves`() {
        mount(1.0f) { LatticePage(ThorBottomCells.Scale10, layerAlpha = 1f) }
        val cellPx = boundsPx(companionTileTag("p5"))
        val face = faceRectPx(cellPx)
        val without = frame()
        cursor = "p5"
        Snapshot.sendApplyNotifications()
        frames(25)
        val mid = frames(4)
        assertTrue("the sweep is in flight at 400 ms: the page is still changing from frame to frame (${mid.toSet().size} distinct)", mid.toSet().size > 2)

        covered = true
        Snapshot.sendApplyNotifications()
        val under = frames(125)
        println("V6B-PIXELS cover distinctFramesFromSecondCoveredFrame=${under.drop(2).toSet().size} of ${under.size - 2}")
        for (i in 2 until under.size) {
            assertEquals("frame $i under the cover differs from the settled one: the ring or its sweep ran on behind the cover", under[1], under[i])
        }
        // And the ring that settled is whole, in its colour, with its strong pixels, not a half-grown one.
        val withRing = frame()
        val midY = ((face.top + face.bottom) / 2f).toInt()
        val on = withRing[(face.left - 2f * density).toInt(), midY]
        assertTrue("the ring is whole under the cover: $on vs $ringColor", on.distanceTo(ringColor) < 0.45f)
        val (_, strong) = withRing.diffIn(without, face.inflateDp(14f), above = 0.25f)
        assertTrue("the settled ring has its strong pixels ($strong)", strong > 200)

        covered = false
        Snapshot.sendApplyNotifications()
        val back = frames(110)
        for (i in back.indices) {
            assertEquals("frame $i after the cover left differs: the sweep replayed or the ring regrew", under[1], back[i])
        }

        // A cursor that moves is a new arrival with its own ring and sweep.
        cursor = "p6"
        Snapshot.sendApplyNotifications()
        val arrival = frames(60)
        println("V6B-PIXELS cover newCursorDistinctFrames=${arrival.toSet().size}")
        assertTrue("the next cursor arrives with its ring and sweep (${arrival.toSet().size} distinct frames)", arrival.toSet().size > 20)
    }

    /**
     * a Tome covered during its reveal. The Tome's children are handed the cover like the lattice's tiles are, so a cursor that lands while the Tome is covered neither
     * sweeps behind it nor sweeps when the cover leaves.
     */
    @Test
    fun `inside a covered Tome the cursor ring does not sweep behind the cover or replay after it`() {
        mount(1.0f) { TomeLeaves(layerAlpha = 1f) }
        covered = true
        Snapshot.sendApplyNotifications()
        cursor = "c1"
        Snapshot.sendApplyNotifications()
        val under = frames(125)
        println("V6B-PIXELS tomeCovered distinctFramesFromSecondFrame=${under.drop(2).toSet().size} of ${under.size - 2}")
        for (i in 2 until under.size) {
            assertEquals("frame $i of a covered Tome differs: a sweep ran behind the cover", under[1], under[i])
        }
        covered = false
        Snapshot.sendApplyNotifications()
        val back = frames(110)
        for (i in back.indices) assertEquals("frame $i after the Tome's cover left differs: the sweep replayed", under[1], back[i])
        // Uncovered, the same cursor moving on is an arrival with its own sweep.
        cursor = "c2"
        Snapshot.sendApplyNotifications()
        assertTrue("an uncovered Tome's next cursor sweeps", frames(60).toSet().size > 20)
    }

    /** How many pixels of [region] differ strongly from [without]: the ring's own ink on that face (the bright stroke is what clears the threshold). */
    private fun inkIn(region: Rect, without: PixelMap): Int = frame().diffIn(without, region, above = 0.25f).second

    private fun nextFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /**
     * Ledger D3 (v6b-recheck P3), leaving a cover-settled tile: the tile's ring used to be dropped at once when the cursor moved on, because the settled identity ended with the
     * cursor and the fresh one began at rest. It fades out over the ring clock now, as every other ring does, and is gone when the 420 ms are up.
     */
    @Test
    fun `a cursor that leaves a cover-settled tile fades its ring out over the ring clock and does not drop it`() {
        mount(1.0f) { LatticePage(ThorBottomCells.Scale10, layerAlpha = 1f) }
        val without = frame()
        val region = faceRectPx(boundsPx(companionTileTag("p5"))).inflateDp(14f)
        cursor = "p5"
        settle()
        covered = true
        settle(150L)
        covered = false
        settle(150L)
        val whole = inkIn(region, without)
        assertTrue("the settled ring is whole before the cursor leaves ($whole strong pixels)", whole > 200)

        cursor = null
        Snapshot.sendApplyNotifications()
        val ink = (1..40).map {
            nextFrame()
            inkIn(region, without)
        }
        println("D3-PIXELS leaveSettled whole=$whole inkByFrame=$ink")
        assertTrue("the ring is still drawn on the frame after the cursor left (${ink[0]} of $whole): it was dropped", ink[0] > 0)
        assertTrue("and 4 frames later (about 64 ms) too (${ink[3]}): a fade, not a cut", ink[3] > 0)
        assertTrue("the ink only falls, frame by frame: $ink", ink.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("it takes more than a few frames to go: ${ink.count { it > 0 }} frames with ink", ink.count { it > 0 } >= 5)
        assertEquals("the ring is gone once the ring clock (420 ms) is up: $ink", 0, ink.last())
    }

    /**
     * Ledger D3 (v6b-recheck P3), a ring already fading out when a cover arrives: the fade used to run on behind the new cover. A cover re-keys the ring onto a settled identity at
     * rest, so the ring is gone on the first covered frame and stays gone when the cover leaves.
     */
    @Test
    fun `a ring that is fading out when a cover arrives is gone behind it at once`() {
        mount(1.0f) { LatticePage(ThorBottomCells.Scale10, layerAlpha = 1f) }
        val without = frame()
        val region = faceRectPx(boundsPx(companionTileTag("p5"))).inflateDp(14f)
        cursor = "p5"
        settle()
        assertTrue("the ring is drawn before the cursor leaves", inkIn(region, without) > 200)

        cursor = null
        Snapshot.sendApplyNotifications()
        repeat(4) { nextFrame() }
        val fading = inkIn(region, without)
        assertTrue("the ring is mid-fade when the cover arrives ($fading strong pixels)", fading > 0)

        covered = true
        Snapshot.sendApplyNotifications()
        nextFrame()
        val ink = (1..30).map {
            nextFrame()
            inkIn(region, without)
        }
        println("D3-PIXELS coverOverFade fading=$fading inkUnderCover=$ink")
        assertTrue("the fading ring went on behind the cover instead of being cut: $ink", ink.all { it == 0 })

        covered = false
        Snapshot.sendApplyNotifications()
        val back = (1..30).map {
            nextFrame()
            inkIn(region, without)
        }
        assertTrue("and does not come back when the cover leaves: $back", back.all { it == 0 })
    }

    private companion object {
        /** The Thor Bottom's density: 369 dpi / 160. */
        const val Density369 = 369f / 160f
        const val PageTag = "ring-page"
    }
}
