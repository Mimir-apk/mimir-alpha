package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Audit 2026-10-14, RC-11 (device P2):** the Settings rail swapped the detail content 50-100 ms before its ring moved. The pane and the ring retarget on the SAME frame (both read
 * one collected focus state), so "start on the ring's frame" was already true. The lead is in the curves: the pane's floor is its start alpha ([SettingsDetailGlideStartAlpha]) from
 * its first frame, the ring's floor is nothing, on a slow-start ease over 420 ms. So the pane holds its previous category until the ring has reached that same weight
 * ([settingsDetailCommitLeadMs]), restarting on every hop so a traverse coalesces, and snaps the moment the rail is not the one moving (RIGHT or A opens the pane at once).
 *
 * Read off real pixels, frame by frame on a paused clock: "the ring has moved" is the new entry's own pixels reaching a share of what they settle to, never `progress > 0`.
 * "The pane has committed" is the new category's rows registering with the authority, which happens on the frame they first lay out.
 *
 * The traverse is driven the way the router drives it ([MimirFocusTraversal.onDirectionalMove], on a clock the test injects), so the ring is on its 140 ms traverse clock and the hold
 * is the 35 ms one. Fix round W1A #6: the first cut of the coalescing test never fed it and so never left the 104 ms settle lead; it holds only that a burst of hops each sooner than
 * the lead builds the pane it stops on. A held pad's own 120 ms cruise is slower than 35 ms, so it builds every pane it passes (as before this change); a test says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRailRingLeadsPaneTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val rootDensity = 369f / 160f
    private var mounted = false
    private val realClock = MimirFocusTraversal.clock

    @Before
    fun noTraverseYet() {
        MimirFocusTraversal.reset()
    }

    @After
    fun putTheTraverseClockBack() {
        MimirFocusTraversal.reset()
        MimirFocusTraversal.clock = realClock
    }

    /** The router's own stamp for an accepted directional move, on the test's animation clock, then the move. The stamp before it makes the first hop part of a stream. */
    private fun streamingHop(category: SettingsCategory) {
        MimirFocusTraversal.clock = { compose.mainClock.currentTime }
        val now = compose.mainClock.currentTime
        if (!MimirFocusTraversal.streaming) MimirFocusTraversal.onDirectionalMove(now - 100L)
        MimirFocusTraversal.onDirectionalMove(now)
        write { authority.requestFocus(settingsRailFocusKey(category)) }
    }

    private val appearanceFirstRow = detailKey(settingsFirstDetailRowId(SettingsCategory.APPEARANCE))
    private val libraryFirstRow = detailKey(settingsFirstDetailRowId(SettingsCategory.LIBRARY))
    private val musicFirstRow = detailKey(settingsFirstDetailRowId(SettingsCategory.MUSIC))

    private fun mount() {
        if (mounted) return
        mounted = true
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(rootDensity, 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp).background(palette.surfaceBase)) {
                        SettingsHome(authority = authority)
                    }
                }
            }
        }
    }

    private fun settle(ms: Long = 2_000L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun entryRegion(category: SettingsCategory): Region {
        val b = compose.onNodeWithTag(SettingsRailEntryTestTagPrefix + category.name, useUnmergedTree = true).getBoundsInRoot()
        return Region(
            (b.left.value * rootDensity).toInt(),
            (b.top.value * rootDensity).toInt(),
            (b.right.value * rootDensity).toInt(),
            (b.bottom.value * rootDensity).toInt(),
        )
    }

    /** The detail pane: everything right of the rail, inside the page. */
    private fun paneRegion(bitmap: Bitmap): Region {
        val rail = compose.onNodeWithTag(SettingsHomeRailTestTag, useUnmergedTree = true).getBoundsInRoot()
        val left = ((rail.right.value + 16f) * rootDensity).toInt()
        return Region(left, (32f * rootDensity).toInt(), bitmap.width, bitmap.height)
    }

    /** Mean absolute channel difference over the region, sampled every second pixel. */
    private fun meanDiff(a: Bitmap, b: Bitmap, region: Region): Float {
        var sum = 0L
        var count = 0L
        var y = region.top.coerceAtLeast(0)
        while (y < region.bottom.coerceAtMost(a.height)) {
            var x = region.left.coerceAtLeast(0)
            while (x < region.right.coerceAtMost(a.width)) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    abs((p and 0xFF) - (q and 0xFF))
                count += 3
                x += 2
            }
            y += 2
        }
        return if (count == 0L) 0f else sum.toFloat() / count
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    /** At the page with the rail on [from], settled; the clock is then paused for the test to step. */
    private fun restOn(from: SettingsCategory) {
        mount()
        settle()
        write { authority.requestFocus(settingsRailFocusKey(from)) }
        settle()
        compose.mainClock.autoAdvance = false
    }

    private fun step(): Bitmap {
        compose.mainClock.advanceTimeBy(16)
        return bitmap()
    }

    @Test
    fun `the pane commits only once the ring has reached the weight the pane starts at`() {
        restOn(SettingsCategory.PROFILE)
        val base = bitmap()
        val entry = entryRegion(SettingsCategory.APPEARANCE)
        val pane = paneRegion(base)

        write { authority.requestFocus(settingsRailFocusKey(SettingsCategory.APPEARANCE)) }
        val ringDiffs = ArrayList<Float>()
        val committedAt = ArrayList<Boolean>()
        for (frame in 0 until 40) {
            val shot = step()
            ringDiffs += meanDiff(shot, base, entry)
            committedAt += authority.registeredKeysInScope(DetailScope).contains(appearanceFirstRow)
        }
        compose.mainClock.autoAdvance = true
        settle()
        val settled = bitmap()
        val settledRing = meanDiff(settled, base, entry)
        val settledPane = meanDiff(settled, base, pane)
        assertTrue("the ring settles to something visible on the new entry ($settledRing)", settledRing > 2f)
        assertTrue("the new pane settles to something different from the old ($settledPane)", settledPane > 2f)

        val commitFrame = committedAt.indexOfFirst { it }
        assertTrue("the new pane committed within the run", commitFrame >= 0)
        val ringShare = ringDiffs[commitFrame] / settledRing
        println("RC11 pane committed on frame $commitFrame (${(commitFrame + 1) * 16} ms); the ring is at ${"%.2f".format(ringShare)} of its settled weight")
        assertTrue(
            "when the new pane first lays out the ring has reached at least ${SettingsDetailGlideStartAlpha * 0.75f} of its settled weight, " +
                "not just begun (frame $commitFrame, ring at $ringShare)",
            ringShare >= SettingsDetailGlideStartAlpha * 0.75f,
        )
        // The hold waits on the animation clock (it returns on the first frame at or after the lead), so the lead, the frame that starts it and the recomposition that follows it
        // add up to a few frames over the 104 ms: bounded, and not a second clock.
        assertTrue("and the hold is bounded: the pane is in within ${(commitFrame + 1) * 16} ms of the move", (commitFrame + 1) * 16 <= 200)
        assertTrue("the ring was not yet at that weight on the very first frame, so the test sees a hold", ringDiffs[0] / settledRing < SettingsDetailGlideStartAlpha)
    }

    @Test
    fun `RIGHT during the hold opens the pane at once with a key landed`() {
        restOn(SettingsCategory.PROFILE)
        write { authority.requestFocus(settingsRailFocusKey(SettingsCategory.APPEARANCE)) }
        step()
        step()
        write {
            dispatchToSettings(
                authority = authority,
                action = ControllerAction.NAV_RIGHT,
                onRailOpen = {},
                onRowActivate = {},
                onExit = {},
            )
        }
        var landedAt = -1
        for (frame in 0 until 8) {
            step()
            if (landedAt < 0 && authority.state.value.focused == appearanceFirstRow) landedAt = frame
        }
        assertEquals("the detail scope is the active one", DetailScope, authority.state.value.scope)
        assertTrue("the new pane's first row took the key within the next few frames, not after the hold ($landedAt)", landedAt in 0..3)
        assertTrue(authority.registeredKeysInScope(DetailScope).contains(appearanceFirstRow))
    }

    /** Steps [frames] frames, noting which of the Library and Music panes was ever built, and the frame the Appearance pane first was (-1 while it is not). */
    private class Seen(var library: Boolean = false, var music: Boolean = false, var appearanceAt: Int = -1)

    private fun stepNoting(frames: Int, seen: Seen, startFrame: Int = 0) {
        repeat(frames) { i ->
            step()
            val registered = authority.registeredKeysInScope(DetailScope)
            seen.library = seen.library || registered.contains(libraryFirstRow)
            seen.music = seen.music || registered.contains(musicFirstRow)
            if (seen.appearanceAt < 0 && registered.contains(appearanceFirstRow)) seen.appearanceAt = startFrame + i
        }
    }

    @Test
    fun `a slow burst on the settle clock coalesces - the panes passed on the way are never composed`() {
        restOn(SettingsCategory.PROFILE)
        val seen = Seen()
        listOf(SettingsCategory.LIBRARY, SettingsCategory.MUSIC, SettingsCategory.APPEARANCE).forEach { category ->
            write { authority.requestFocus(settingsRailFocusKey(category)) }
            stepNoting(3, seen) // three frames, 48 ms, between hops: inside the 104 ms settle lead
        }
        stepNoting(30, seen)
        assertFalse("Library's pane was passed over, not built", seen.library)
        assertFalse("Music's pane was passed over, not built", seen.music)
        assertTrue("the pane the burst stopped on is built", authority.registeredKeysInScope(DetailScope).contains(appearanceFirstRow))
    }

    @Test
    fun `a pad on the traverse clock commits the pane after the short lead, not the settle lead`() {
        restOn(SettingsCategory.PROFILE)
        streamingHop(SettingsCategory.APPEARANCE)
        assertTrue("the move is part of a stream, so the ring is on the traverse clock", MimirFocusTraversal.streaming)
        val seen = Seen()
        stepNoting(20, seen)
        println("RC11 traverse: the pane committed on frame ${seen.appearanceAt} (${(seen.appearanceAt + 1) * 16} ms)")
        assertTrue("the pane is built", seen.appearanceAt >= 0)
        assertTrue(
            "after the ${settingsDetailCommitLeadMs(140)} ms traverse lead and the frames that carry it, well before the settle path's (frame 9, 160 ms) (frame ${seen.appearanceAt})",
            seen.appearanceAt <= 6,
        )
    }

    @Test
    fun `a burst on the traverse clock faster than its lead still builds only the pane it stops on`() {
        restOn(SettingsCategory.PROFILE)
        val seen = Seen()
        listOf(SettingsCategory.LIBRARY, SettingsCategory.MUSIC, SettingsCategory.APPEARANCE).forEach { category ->
            streamingHop(category)
            assertTrue(MimirFocusTraversal.streaming)
            stepNoting(2, seen) // 32 ms between hops: inside the 35 ms traverse lead
        }
        stepNoting(30, seen)
        assertFalse("Library's pane was passed over, not built", seen.library)
        assertFalse("Music's pane was passed over, not built", seen.music)
        assertTrue("the pane the burst stopped on is built", authority.registeredKeysInScope(DetailScope).contains(appearanceFirstRow))
    }

    @Test
    fun `a held pad at its 120 ms cruise builds every pane it passes, as it did before the hold`() {
        restOn(SettingsCategory.PROFILE)
        val seen = Seen()
        listOf(SettingsCategory.LIBRARY, SettingsCategory.MUSIC, SettingsCategory.APPEARANCE).forEach { category ->
            streamingHop(category)
            stepNoting(8, seen) // 128 ms between hops: longer than the 35 ms traverse lead
        }
        stepNoting(10, seen)
        assertTrue("Library's pane was built on the way past", seen.library)
        assertTrue("and so was Music's", seen.music)
    }
}
