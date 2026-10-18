package com.gruning.mimir.rewrite.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Files end-cap leaves the dock while a game runs and returns when it ends. Seat departure/return and dock recentering animate on shared clocks without a pop. The frames below are the seats' layout, 16 ms apart on a
 * paused clock, at the Odin's width (the unscaled solver: 88 dp seats, the rail narrows by the seat and recentres) and at the Nova's (the
 * scaled solver: the rail keeps its 368 dp target and the five survivors widen from 61.33 dp to 73.6 dp as the end-cap goes). Both must move
 * through intermediate frames, never jump, and the end-cap's node must stay until its fade is done and be gone after. On the base the seat
 * is dropped the frame the list changes and the rail re-solves at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h1024dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirRunicQuickRailSeatWithdrawTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableStateOf(MimirQuickRailAction.entries.toList())
    private val focused = mutableStateOf<MimirQuickRailAction?>(MimirQuickRailAction.QUICK_LAUNCH)

    /**
     * One frame of the dock: each seat's semantics node (left edge, width) in px, for the seats in the tree. A seat at rest reports its slot.
     * A LEAVING seat's node is its content, measured at the full slot and placed centred inside the narrower width the seat reports to the
     * Row (`Modifier.quickRailSeatWidth`), so its drawn width is derived: the Row places it right after the stayers, and the node sits
     * `(full - drawn) / 2` to the left of that.
     */
    private class Frame(val seats: Map<MimirQuickRailAction, Pair<Float, Float>>) {
        fun left(action: MimirQuickRailAction): Float = seats.getValue(action).first
        fun width(action: MimirQuickRailAction): Float = seats.getValue(action).second
        fun has(action: MimirQuickRailAction): Boolean = action in seats
        private val stayersEnd: Float
            get() = left(MimirQuickRailAction.HOME) + seats.filterKeys { it != MimirQuickRailAction.FILES }.values.sumOf { it.second.toDouble() }.toFloat()
        val filesDrawn: Float
            get() = seats[MimirQuickRailAction.FILES]?.let { (x, full) -> full - 2f * (stayersEnd - x) } ?: 0f
        val railWidth: Float get() = stayersEnd - left(MimirQuickRailAction.HOME) + filesDrawn
    }

    @Test
    fun `at the Odin's width the end-cap fades out in place, the rail narrows and recentres, and the end-cap returns the same way`() {
        install(832.5.dp)
        val before = frame()
        val filesWidth = before.width(MimirQuickRailAction.FILES)
        assertEquals("the unscaled seat", 88f, filesWidth, 0.51f)

        val leaving = withdrawFiles()
        assertMovesThrough(leaving.map { it.filesDrawn }, from = filesWidth, to = 0f, label = "Files drawn width")
        assertMovesThrough(leaving.map { it.left(MimirQuickRailAction.HOME) }, from = before.left(MimirQuickRailAction.HOME), to = before.left(MimirQuickRailAction.HOME) + filesWidth / 2f, label = "Home left edge (the dock recentres)")
        leaving.forEach { assertEquals("the survivors keep their width while the end-cap goes", 88f, it.width(MimirQuickRailAction.QUICK_LAUNCH), 0.51f) }
        leaving.forEach { assertEquals("the rail is centred at every frame", (832.5.dp.value - it.railWidth) / 2f, it.left(MimirQuickRailAction.HOME), 1.6f) }
        assertSeatStaysThenGoes(leaving)
        val after = leaving.last()
        assertEquals("five contiguous seats", 5 * 88f, after.railWidth, 1.1f)

        val returning = offerFiles()
        assertMovesThrough(returning.map { it.filesDrawn }, from = 0f, to = filesWidth, label = "Files drawn width, returning")
        assertMovesThrough(returning.map { it.left(MimirQuickRailAction.HOME) }, from = after.left(MimirQuickRailAction.HOME), to = before.left(MimirQuickRailAction.HOME), label = "Home left edge, returning")
        assertEquals("back where it started", before.seats, returning.last().seats)
    }

    @Test
    fun `at the Nova's width the rail keeps its target width and the survivors widen frame by frame`() {
        install(640.dp)
        val before = frame()
        val target = mimirQuickRailTargetWidth(640.dp).value
        assertEquals(target, before.railWidth, 3.6f)
        val six = mimirQuickRailSlotWidths(MimirQuickRailAction.entries, mimirQuickRailTargetWidth(640.dp)).first().value
        val five = mimirQuickRailSlotWidths(MimirQuickRailAction.entries - MimirQuickRailAction.FILES, mimirQuickRailTargetWidth(640.dp)).first().value
        assertTrue("the two solutions differ, so a re-solve at the drop would be a jump", five - six > 10f)

        val leaving = withdrawFiles()
        assertMovesThrough(leaving.map { it.width(MimirQuickRailAction.QUICK_LAUNCH) }, from = six, to = five, label = "a survivor's width")
        assertMovesThrough(leaving.map { it.filesDrawn }, from = six, to = 0f, label = "Files drawn width")
        leaving.forEach { assertEquals("the rail never leaves its target width: the survivors take the room the end-cap gives up", target, it.railWidth, 3.6f) }
        assertSeatStaysThenGoes(leaving)
        leaving.last().seats.keys.zipWithNext { a, b ->
            assertEquals("contiguous after the drop", leaving.last().left(a) + leaving.last().width(a), leaving.last().left(b), 0.51f)
        }

        val returning = offerFiles()
        assertMovesThrough(returning.map { it.width(MimirQuickRailAction.QUICK_LAUNCH) }, from = five, to = six, label = "a survivor's width, returning")
        assertEquals("back where it started", before.seats, returning.last().seats)
    }

    @Test
    fun `the seat list the rail draws keeps a leaving seat in place and puts a returning seat back where it belongs`() {
        val all = MimirQuickRailAction.entries.toList()
        val noFiles = all - MimirQuickRailAction.FILES
        val noHome = all - MimirQuickRailAction.HOME
        assertEquals("a withdrawn end-cap stays last while it fades", all, mimirQuickRailSeatsShown(all, noFiles))
        assertEquals("a withdrawn first seat stays first", all, mimirQuickRailSeatsShown(all, noHome))
        assertEquals("the end-cap returns at the end", all, mimirQuickRailSeatsShown(noFiles, all))
        assertEquals("a leading seat returns at the front", all, mimirQuickRailSeatsShown(noHome, all))
        assertEquals("a middle seat returns after its predecessor", all, mimirQuickRailSeatsShown(all - MimirQuickRailAction.SETTINGS, all))
        assertEquals("nothing changes: the same list", noFiles, mimirQuickRailSeatsShown(noFiles, noFiles))
    }

    @Test
    fun `the presence-weighted solver is the discrete one at rest and keeps the target width in between`() {
        val target = mimirQuickRailTargetWidth(640.dp)
        val all = MimirQuickRailAction.entries.toList()
        assertEquals(mimirQuickRailSlotWidths(all, target), mimirQuickRailSlotWidths(all, List(all.size) { 1f }, target))
        assertEquals(mimirQuickRailSlotWidths(all, mimirQuickRailTargetWidth(832.5.dp)), mimirQuickRailSlotWidths(all, List(all.size) { 1f }, mimirQuickRailTargetWidth(832.5.dp)))

        val presences = List(all.size) { index -> if (all[index] == MimirQuickRailAction.FILES) 0.4f else 1f }
        val slots = mimirQuickRailSlotWidths(all, presences, target)
        val drawn = slots.indices.sumOf { (slots[it] * presences[it]).value.toDouble() }
        assertEquals("the drawn widths sum to the target while the end-cap is at 0.4", target.value.toDouble(), drawn, 0.01)
        val six = mimirQuickRailSlotWidths(all, target).first().value
        val five = mimirQuickRailSlotWidths(all - MimirQuickRailAction.FILES, target).first().value
        assertTrue("a survivor sits between the two solutions", slots.first().value > six && slots.first().value < five)

        val unscaled = mimirQuickRailSlotWidths(all, presences, mimirQuickRailTargetWidth(832.5.dp))
        assertEquals("above the authored width nothing scales, whatever the presences", mimirQuickRailSlotWidths(all, mimirQuickRailTargetWidth(832.5.dp)), unscaled)
    }

    @Test
    fun `the seat's fade and the rail's recentring ride the focus clock with the house ease, in the source`() {
        val source = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirRunicQuickRail.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirRunicQuickRail.kt"),
        ).first(File::exists).readText()
        assertTrue(source.contains("private fun quickRailSeatSpec() = tween<Float>(durationMillis = MimirMotion.FocusMs, easing = MimirMotion.Easing)"))
        val settle = source.substringAfter("suspend fun settle(").substringBefore("@Composable")
        assertTrue("reduced motion snaps", settle.contains("if (reducedMotion) animatable.snapTo(target) else animatable.animateTo(target, quickRailSeatSpec())"))
        assertTrue("a seat leaves the list only once its fade is done", settle.contains("if (target == 0f) shown = shown - seat"))
        val seat = source.substringAfter("private fun Modifier.quickRailSeatWidth(").substringBefore("private class QuickRailSeats(")
        assertTrue("the seat fades and is clipped to its share of the slot", seat.contains("alpha = presence") && seat.contains("clip = true"))
        assertTrue("at rest the seat is the width it always was", seat.contains("width(slotWidth)"))
        val loop = source.substringAfter("shown.forEachIndexed { index, action ->").substringBefore("@Composable\nprivate fun MimirRunicQuickRailItem(")
        assertTrue("a leaving seat is inert", loop.contains("onActivate = { if (offered) onActionActivate(action) }"))
        val ring = source.substringAfter("val ringAim = remember { QuickRailRingAim() }").substringBefore("val ringColor = palette.tileFocusRing")
        assertTrue("the ring effect is keyed on the seat alone, never on the geometry", ring.contains("LaunchedEffect(focusedSeat) {"))
        assertTrue("it reads the live geometry without being keyed on it", ring.contains("val (centres, diameters) = liveGeometry.value"))
        assertFalse("the rail no longer re-solves on the bare list", source.contains("mimirQuickRailSlotWidths(actions, mimirQuickRailTargetWidth(maxWidth, railEndReserve))"))
    }

    // ---- harness ------------------------------------------------------------------------------------------------------------

    private lateinit var hostView: View

    private fun install(viewport: Dp) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            hostView = LocalView.current
            MimirTheme {
                // Black under the rail: the ring read-back below tells painted pixels from the field by their distance from it.
                Box(Modifier.width(viewport).height(120.dp).background(Color.Black)) {
                    MimirRunicQuickRail(
                        selectedAction = MimirQuickRailAction.HOME,
                        focusedAction = focused.value,
                        onActionFocus = { },
                        onActionActivate = { },
                        actions = actions.value,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    /**
     * Lead's re-check of round 2 (P3): the ring effect was keyed on the per-frame geometry, so a glide running while a seat faded was
     * restarted every frame and cut to a snap. The ring is drawn in a Canvas, so this reads it back from the rendered frame: the row just
     * below the mark and inside the ring holds the ring and its wash alone, and the centroid of what is painted there is the ring's centre.
     * In rail coordinates (the rail recentres under it) the ring must travel from Settings to Quick Launch through intermediate frames,
     * never a cut, while the end-cap fades. Fails on round 2: a 76 px step on the second frame.
     */
    @Test
    fun `a glide already running keeps running while the end-cap fades`() {
        focused.value = MimirQuickRailAction.SETTINGS
        install(832.5.dp)
        compose.mainClock.advanceTimeBy(640)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val before = frame()
        val settings = before.seatCentreInRail(MimirQuickRailAction.SETTINGS)
        val quickLaunch = before.seatCentreInRail(MimirQuickRailAction.QUICK_LAUNCH)
        assertEquals("the ring arrived on Settings", settings, ringCentreInRail(before), 2.5f)

        // The game launches: the end-cap goes and the focus falls to Quick Launch in the same frame, so the glide and the fade run together.
        Snapshot.withMutableSnapshot {
            actions.value = MimirQuickRailAction.entries - MimirQuickRailAction.FILES
            focused.value = MimirQuickRailAction.QUICK_LAUNCH
        }
        val centres = mutableListOf<Float>()
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            centres += ringCentreInRail(frame())
        }
        assertMovesThrough(centres, from = settings, to = quickLaunch, label = "the ring's centre in the rail")
    }

    private fun Frame.seatCentreInRail(action: MimirQuickRailAction): Float = left(action) + width(action) / 2f - left(MimirQuickRailAction.HOME)

    /**
     * The ring's centre in rail coordinates, read back from the rendered frame (see the glide test). The host view is drawn into a bitmap
     * by hand: `captureToImage` waits on the frame clock, which this test holds still.
     */
    private fun ringCentreInRail(frame: Frame): Float {
        val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { hostView.draw(Canvas(bitmap)) }
        val image = bitmap.asImageBitmap().toPixelMap()
        val centreY = MimirRunicQuickRailDefaults.ActionBoxDiameter.value / 2f
        val coin = mimirQuickRailItemDiameter(MimirQuickRailAction.SETTINGS, mimirQuickRailDiameter(832.5.dp)).value
        val ringRadius = coin * MimirRunicQuickRailDefaults.FocusRingCoinRatio / 2f
        val markRadius = MimirRunicQuickRailDefaults.DrawnMarkSize.value / 2f
        assertTrue("the row lies between the mark and the ring", ringRadius > markRadius + 2f)
        val y = Math.round(centreY + (markRadius + ringRadius) / 2f)
        val field = image[0, y]
        var sum = 0.0
        var painted = 0
        for (x in 0 until image.width) {
            val pixel = image[x, y]
            val distance = maxOf(Math.abs(pixel.red - field.red), Math.abs(pixel.green - field.green), Math.abs(pixel.blue - field.blue))
            if (distance > 0.03f) {
                sum += x
                painted += 1
            }
        }
        assertTrue("the ring is painted on row $y", painted > 0)
        return (sum / painted).toFloat() + 0.5f - frame.left(MimirQuickRailAction.HOME)
    }

    private fun withdrawFiles(): List<Frame> = settle { actions.value = MimirQuickRailAction.entries - MimirQuickRailAction.FILES }

    private fun offerFiles(): List<Frame> = settle { actions.value = MimirQuickRailAction.entries.toList() }

    /** Applies [change] and steps 16 ms frames until the seat motion (FocusMs) is whole, with slack; returns every frame's layout. */
    private fun settle(change: () -> Unit): List<Frame> {
        Snapshot.withMutableSnapshot { change() }
        val frames = mutableListOf<Frame>()
        repeat(FramesObserved) {
            compose.mainClock.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            frames += frame()
        }
        return frames
    }

    private fun frame(): Frame = Frame(
        MimirQuickRailAction.entries.mapNotNull { action ->
            val node = compose.onAllNodesWithContentDescription(quickRailLabel(action)).fetchSemanticsNodes().singleOrNull() ?: return@mapNotNull null
            action to (node.positionInRoot.x to node.size.width.toFloat())
        }.toMap(),
    )

    /**
     * Monotone from [from] to [to] (a pixel and a half of rounding slack: a derived width carries the Row's integer placement), through at
     * least three distinct values in between, and no frame covers more than 60 % of the way.
     */
    private fun assertMovesThrough(values: List<Float>, from: Float, to: Float, label: String) {
        val span = to - from
        assertEquals("$label ends at $to: $values", to, values.last(), 1.6f)
        val steps = (listOf(from) + values).zipWithNext { a, b -> (b - a) * Math.signum(span) }
        assertTrue("$label moves one way only: $values", steps.all { it >= -1.6f })
        assertTrue("$label never jumps (max step ${steps.max()} of $span): $values", steps.max() < 0.6f * Math.abs(span))
        val between = values.filter { Math.abs(it - from) > 0.51f && Math.abs(it - to) > 0.51f }.distinct()
        assertTrue("$label moves through intermediate frames, not a cut: $values", between.size >= 3)
    }

    /** The end-cap's node is in the tree while it fades and gone once the fade is whole. */
    private fun assertSeatStaysThenGoes(frames: List<Frame>) {
        val wholeBy = (MimirMotion.FocusMs + 15) / 16 + 3
        assertTrue("the seat is still in the tree three frames in", frames[2].has(MimirQuickRailAction.FILES))
        assertNull("the seat is gone once its fade is whole", frames.last().seats[MimirQuickRailAction.FILES])
        assertFalse("and gone by frame $wholeBy", frames[wholeBy].has(MimirQuickRailAction.FILES))
    }

    private companion object {
        const val FramesObserved = 16
    }
}
