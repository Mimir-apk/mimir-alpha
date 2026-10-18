package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.TrackFormat
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
 * The music panel's in-place changes on a paused clock (a missing transition is a defect): the card swap,
 * the switch row's text and presence, and the lines of a card. Each asserts an intermediate frame (the outgoing and incoming layers
 * both there, a size or a position strictly between its ends) and the settled end state. With `autoAdvance = false` a write to state
 * needs [Snapshot.sendApplyNotifications], or nothing recomposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelSwapTest {
    @get:Rule
    val compose = createComposeRule()

    private val song = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true)
    private val spotify = OtherPlayerSnapshot("Track", "Artist", "Spotify", artwork = null, isPlaying = true)

    private fun modelFor(control: MusicControlState, fullCard: MusicPanelCard = musicPanelFullCard(control)) = musicPanelModel(
        control = control,
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
        fullCard = fullCard,
    )

    /** Your music is the card and the other player the row. */
    private val yoursCard = modelFor(
        MusicControlState(yours = song, other = spotify.copy(isPlaying = false), otherAccess = true),
    )

    /** The other player is the card and Your music the row, the way the press on the row leaves it. */
    private val otherCard = modelFor(
        MusicControlState(
            yours = song.copy(isPlaying = false, pausedReason = MusicPauseReason.OTHER_APP),
            other = spotify,
            otherAccess = true,
        ),
    )

    private var model by mutableStateOf(yoursCard)

    /** Animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun install(density: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1f)) {
                MimirTheme {
                    MusicPanelOverlay(
                        model = model,
                        focused = null,
                        authority = null,
                        anchor = null,
                        onDismiss = {},
                        onPlayPause = { _ -> },
                        onPrevious = { _ -> },
                        onNext = { _ -> },
                        onMediaRequested = {},
                        onMusicRequested = {},
                        onMusicInteractionEnd = {},
                        onRequestFocus = {},
                    )
                }
            }
        }
        // Past the panel's own entry, so what moves next is the change under test.
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun layer(card: MusicPanelCard): SemanticsNodeInteraction =
        compose.onNodeWithTag(MusicPanelCardLayerTestTag + card.name, useUnmergedTree = true)

    private fun layerCount(card: MusicPanelCard): Int =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(MusicPanelCardLayerTestTag + card.name), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    /** Chrome text speaks its natural string as the content description (the visual is capitals). */
    private fun count(text: String): Int =
        compose.onAllNodes(hasContentDescription(text) or hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun cardHeight(): Float =
        compose.onNodeWithTag(MusicPanelCardTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun switchRowCount(): Int =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(MusicPanelSwitchRowTestTag), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun `a change of the card's source crossfades, the old layer leaving and the new one arriving, and settles on the new card alone`() {
        install()
        assertEquals(1, layerCount(MusicPanelCard.YOURS))
        assertEquals(0, layerCount(MusicPanelCard.OTHER))
        assertEquals(1, count("Playing — Song"))

        write { model = otherCard }
        compose.mainClock.advanceTimeBy(60)
        // Mid-swap: both layers are on screen (the old one fading out inside the 130 ms lead, the new one waiting at alpha 0 for it).
        assertEquals("the outgoing layer is still there", 1, layerCount(MusicPanelCard.YOURS))
        assertEquals("the incoming layer has arrived", 1, layerCount(MusicPanelCard.OTHER))
        compose.mainClock.advanceTimeBy(100)
        assertEquals("the incoming layer is mid-arrival, the outgoing one still dissolving", 1, layerCount(MusicPanelCard.OTHER))
        val risingTop = layer(MusicPanelCard.OTHER).getUnclippedBoundsInRoot().top.value

        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the outgoing layer is gone once settled", 0, layerCount(MusicPanelCard.YOURS))
        assertEquals(1, layerCount(MusicPanelCard.OTHER))
        assertEquals(1, count("Playing — Track"))
        assertEquals("the old status line is gone", 0, count("Playing — Song"))
        val settledTop = layer(MusicPanelCard.OTHER).getUnclippedBoundsInRoot().top.value
        assertTrue("the arriving card rose into place ($risingTop vs settled $settledTop)", risingTop != settledTop)
    }

    @Test
    fun `the panel's height follows the swap on the arrival's clock and never jumps`() {
        install()
        val before = cardHeight()
        write { model = otherCard }
        // The other player's card carries a 96 dp art the Your-music card has not: the panel grows.
        compose.mainClock.advanceTimeBy(40)
        val early = cardHeight()
        assertEquals("the lead holds the old size", before, early, 1.5f)
        compose.mainClock.advanceTimeBy(150)
        val mid = cardHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = cardHeight()
        assertTrue("the card is taller with the art ($before -> $settled)", settled > before + 1f)
        assertTrue("mid-swap the height is strictly between ($before < $mid < $settled)", mid > before + 0.5f && mid < settled - 0.5f)
    }

    @Test
    fun `the switch row is one node through the swap, and only its text changes`() {
        install()
        assertEquals(1, switchRowCount())
        assertEquals("the row names the other player", 1, count("Spotify"))
        write { model = otherCard }
        compose.mainClock.advanceTimeBy(60)
        assertEquals("never a second row, never none", 1, switchRowCount())
        assertEquals("the old name is still fading", 1, count("Spotify"))
        assertEquals("the new name has arrived", 1, count("Music on this device"))
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, switchRowCount())
        assertEquals(0, count("Spotify"))
        assertEquals(1, count("Music on this device"))
    }

    @Test
    fun `the switch row grows in and out when a second source arrives or leaves`() {
        val one = modelFor(MusicControlState(yours = song, otherAccess = true))
        model = one
        install()
        assertEquals(0, switchRowCount())
        val before = cardHeight()

        write { model = yoursCard }
        compose.mainClock.advanceTimeBy(80)
        assertEquals(1, switchRowCount())
        val mid = cardHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = cardHeight()
        assertTrue("the panel grew by the row ($before -> $settled)", settled > before + 1f)
        assertTrue("mid-growth is strictly between ($before < $mid < $settled)", mid > before + 0.5f && mid < settled - 0.5f)

        write { model = one }
        compose.mainClock.advanceTimeBy(80)
        assertEquals("the row is still there while it shrinks", 1, switchRowCount())
        val shrinking = cardHeight()
        assertTrue("mid-shrink is strictly between", shrinking < settled - 0.5f && shrinking > before + 0.5f)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, switchRowCount())
        assertEquals(before, cardHeight(), 1.5f)
    }

    @Test
    fun `a line of the card that changes crossfades in place, and a format line arriving grows the card instead of popping it`() {
        val noFormat = modelFor(MusicControlState(yours = song, otherAccess = true))
        val withFormat = modelFor(
            MusicControlState(
                yours = song.copy(
                    title = "Next song",
                    format = TrackFormat("FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304),
                ),
                otherAccess = true,
            ),
        )
        model = noFormat
        install()
        val before = cardHeight()
        assertEquals(1, count("Playing — Song"))

        write { model = withFormat }
        compose.mainClock.advanceTimeBy(60)
        assertEquals("the old title is still fading", 1, count("Playing — Song"))
        assertEquals("the new title has arrived", 1, count("Playing — Next song"))
        val mid = cardHeight()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = cardHeight()
        assertEquals(0, count("Playing — Song"))
        assertEquals(1, count("Playing — Next song"))
        assertTrue("the format line added height ($before -> $settled)", settled > before + 1f)
        assertTrue("mid-change the height is strictly between ($before < $mid < $settled)", mid > before + 0.5f && mid < settled - 0.5f)
    }

    @Test
    fun `reduced motion swaps the card at once, with no outgoing layer left`() {
        setAnimatorScale(0f)
        try {
            install()
            write { model = otherCard }
            compose.mainClock.advanceTimeBy(64)
            assertEquals("the old card is gone", 0, layerCount(MusicPanelCard.YOURS))
            assertEquals(1, layerCount(MusicPanelCard.OTHER))
            assertEquals(1, switchRowCount())
            assertFalse("no mid-swap state to see", count("Playing — Song") > 0)
        } finally {
            setAnimatorScale(1f)
        }
    }

    @Test
    fun `the swap clock is the lead plus the arrival, and none under reduced motion`() {
        assertEquals(310L, musicPanelSwapSettleMs())
        setAnimatorScale(0f)
        try {
            assertEquals(0L, musicPanelSwapSettleMs())
        } finally {
            setAnimatorScale(1f)
        }
        // The source the row names is the one the press plays.
        assertEquals(MusicSource.OTHER_PLAYER, yoursCard.switchRow!!.card.source)
    }
}
