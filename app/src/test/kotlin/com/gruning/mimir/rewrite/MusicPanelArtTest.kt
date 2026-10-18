package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The other player's art on the panel (wired art never shows a fallback icon or plate; fallback is for a source with no art). A pending load draws neither; a load that finished empty draws the
 * fallback; a finished load draws the art. On a paused clock, every frame of a swap or an arrival is checked, not just the ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelArtTest {
    @get:Rule
    val compose = createComposeRule()

    private val song = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true)
    private val art: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun other(artwork: Bitmap? = null, pending: Boolean = false, failed: Boolean = false) = OtherPlayerSnapshot(
        title = "Track", artist = "Artist", appLabel = "YouTube Music", artwork = artwork, isPlaying = true, artworkPending = pending,
        artworkFailed = failed,
    )

    /** The other player is the card (Your music paused for it). */
    private fun otherCard(snapshot: OtherPlayerSnapshot) = musicPanelModel(
        control = MusicControlState(
            yours = song.copy(isPlaying = false, pausedReason = MusicPauseReason.OTHER_APP),
            other = snapshot,
            otherAccess = true,
        ),
        stopped = false, musicVolume = 0.4f, mediaVolume = 0.7f, mediaAvailable = true, mediaFixed = false,
        cappedByMedia = false, capPushed = false,
    )

    /** Your music is the card, the other player the row: the state a swap to the other player starts from. */
    private val yoursCard = musicPanelModel(
        control = MusicControlState(yours = song, other = other().copy(isPlaying = false), otherAccess = true),
        stopped = false, musicVolume = 0.4f, mediaVolume = 0.7f, mediaAvailable = true, mediaFixed = false,
        cappedByMedia = false, capPushed = false,
    )

    private var model by mutableStateOf(yoursCard)

    private fun install(initial: MusicPanelModel) {
        model = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                MusicPanelOverlay(
                    model = model, focused = null, authority = null, anchor = null, onDismiss = {},
                    onPlayPause = { _ -> }, onPrevious = { _ -> }, onNext = { _ -> },
                    onMediaRequested = {}, onMusicRequested = {}, onMusicInteractionEnd = {}, onRequestFocus = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun fallbacks() = compose.onAllNodes(hasTestTag(MusicPanelArtFallbackTestTag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun images() = compose.onAllNodes(hasTestTag(MusicPanelArtImageTestTag), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Steps the clock a frame at a time to [totalMs] and fails if the fallback is ever on screen. */
    private fun noFallbackThrough(totalMs: Int) {
        var t = 0
        while (t < totalMs) {
            compose.mainClock.advanceTimeBy(16)
            t += 16
            assertEquals("fallback drawn at +${t}ms", 0, fallbacks())
        }
    }

    @Test
    fun `a pending load draws no fallback and no art`() {
        install(otherCard(other(pending = true)))
        assertEquals(0, fallbacks())
        assertEquals(0, images())
    }

    @Test
    fun `a load that finished with no art draws the fallback`() {
        install(otherCard(other(pending = false)))
        assertEquals(1, fallbacks())
        assertEquals(0, images())
    }

    @Test
    fun `the card model carries a failed load only while no bitmap arrived`() {
        assertTrue(otherCard(other(failed = true)).cards.first().artworkFailed)
        assertFalse("a bitmap that did arrive is not a failed load", otherCard(other(artwork = art, failed = true)).cards.first().artworkFailed)
        assertFalse(otherCard(other()).cards.first().artworkFailed)
    }

    @Test
    fun `a failed load draws neither art nor fallback`() {
        // R8 : wired art that fails shows nothing, the same as a pending one. The fallback is for no art wired.
        install(otherCard(other(failed = true)))
        assertEquals(0, fallbacks())
        assertEquals(0, images())
        noFallbackThrough(400)
        assertEquals(0, images())
    }

    @Test
    fun `a load that fails after pending never draws the fallback on any frame`() {
        install(otherCard(other(pending = true)))
        write { model = otherCard(other(pending = false, failed = true)) }
        noFallbackThrough(400)
        assertEquals(0, images())
    }

    @Test
    fun `a finished load draws the art and no fallback`() {
        install(otherCard(other(artwork = art)))
        assertEquals(1, images())
        assertEquals(0, fallbacks())
    }

    @Test
    fun `art that arrives after a pending load fades in with no fallback on any frame`() {
        install(otherCard(other(pending = true)))
        write { model = otherCard(other(artwork = art, pending = false)) }
        noFallbackThrough(400)
        assertEquals(1, images())
    }

    @Test
    fun `a load that ends with no art gives way to the fallback only once it has finished`() {
        install(otherCard(other(pending = true)))
        noFallbackThrough(200)
        write { model = otherCard(other(pending = false)) }
        compose.mainClock.advanceTimeBy(64)
        assertEquals("finished empty: now the fallback", 1, fallbacks())
    }

    @Test
    fun `a swap to a card whose art is still loading shows no fallback on any frame of the swap`() {
        install(yoursCard)
        write { model = otherCard(other(pending = true)) }
        noFallbackThrough(500)
        assertEquals(0, images())
        // The art lands after the swap: it fades in, and the fallback has still never been drawn.
        write { model = otherCard(other(artwork = art)) }
        noFallbackThrough(400)
        assertTrue(images() == 1)
        assertFalse(fallbacks() > 0)
    }
}
