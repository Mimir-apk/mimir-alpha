package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
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
 * The song-title chip (spec 2026-09-30): about three seconds, enters and leaves on the overlay fade, holds no
 * focus and takes no input. Timing is proven on a paused clock, with the snapshot applied by hand after every
 * state write .
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicTitleChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val frame = 16L

    private fun shown(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun composeChip(state: MusicTitleChipState) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MimirTheme { MusicTitleChip(state) } }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `the chip text is the note then the title, with no source after it`() {
        assertEquals("♪ Fallen Hero", musicTitleChipText("Fallen Hero"))
        assertFalse("the source is not on the chip", musicTitleChipText("Tiny 190").contains("Music on this device"))
        assertEquals(3_000L, MusicTitleChipHoldMs)
    }

    @Test
    fun `only a change of track is announced - the first emission shows and a resume of the same bed is silent`() {
        val bed = MusicTitleKey("Fallen Hero", "Mimir")
        val other = MusicTitleKey("Overworld", "Your music")
        assertTrue("the first emission the host sees shows", musicTitleChipShouldAnnounce(null, bed))
        assertFalse("a return from a game, a yield, or Music off and on resumes the same bed", musicTitleChipShouldAnnounce(bed, bed))
        assertFalse("a Skip on the single bed has the same title; the Now playing row says it restarted", musicTitleChipShouldAnnounce(bed, bed.copy()))
        assertTrue("a bed change shows", musicTitleChipShouldAnnounce(bed, other))
        assertTrue("and back again shows, because the last announced moved on", musicTitleChipShouldAnnounce(other, bed))
        assertTrue("the same title from another source is a change", musicTitleChipShouldAnnounce(bed, bed.copy(source = "Your music")))
    }

    @Test
    fun `the state keeps the last notice through its exit and a newer track supersedes an older timer`() {
        val state = MusicTitleChipState()
        assertNull(state.notice)
        val first = state.show("Fallen Hero", "Mimir")
        val second = state.show("Overworld", "Your music")
        state.expire(first)
        assertEquals("the older timer must not take the newer chip down", "Overworld", state.notice?.title)
        state.expire(second)
        assertNull(state.notice)
        assertEquals("the exit fade still has its words", "Overworld", state.lastNotice?.title)
    }

    @Test
    fun `the chip appears on a track start and is gone after the hold plus the fade`() {
        val state = MusicTitleChipState()
        composeChip(state)
        val text = musicTitleChipText("Fallen Hero")
        assertFalse("nothing playing, nothing shown", shown(text))

        state.show("Fallen Hero", "Mimir")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + frame)
        assertTrue("entered on the overlay fade", shown(text))

        compose.mainClock.advanceTimeBy(MusicTitleChipHoldMs - MimirMotion.OverlayMs - 2 * frame)
        assertTrue("still up inside the hold", shown(text))

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 4 * frame)
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs.toLong() + 4 * frame)
        assertFalse("hold over, fade over, chip gone", shown(text))
    }

    @Test
    fun `a second track inside the hold replaces the words and restarts the clock`() {
        val state = MusicTitleChipState()
        composeChip(state)
        val first = musicTitleChipText("Fallen Hero")
        val second = musicTitleChipText("Overworld")

        state.show("Fallen Hero", "Mimir")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000L)
        assertTrue(shown(first))

        state.show("Overworld", "Your music")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(frame)
        assertTrue(shown(second))
        assertFalse(shown(first))

        // Past the FIRST track's expiry: the second's own hold keeps it up.
        compose.mainClock.advanceTimeBy(1_500L)
        assertTrue("the first track's timer did not take the second chip down", shown(second))

        compose.mainClock.advanceTimeBy(MusicTitleChipHoldMs + 2 * MimirMotion.OverlayMs + 8 * frame)
        assertFalse(shown(second))
    }
}
