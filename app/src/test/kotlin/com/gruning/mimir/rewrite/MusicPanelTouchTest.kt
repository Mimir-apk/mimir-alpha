package com.gruning.mimir.rewrite

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.click
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A finger is never perfectly still: a tap whose pointer drifts a few pixels between down and up (inside touch slop) is still
 * a tap. The music panel once wrapped its whole tree in a catch-all that consumed every event after its children; in
 * foundation 1.7.3 `detectTapGestures` checks the Final pass of each move and cancels the tap when anything consumed it, so
 * every drifting tap was dropped (transport buttons and the outside-tap close) while a motionless tap, whose only event is the
 * up, still worked. These tests inject a real down, move, up sequence through the root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelTouchTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val model = musicPanelModel(
        control = MusicControlState(
            yours = YourMusicState(title = "Song", isPlaying = false, canPrevious = true, canNext = true),
            otherAccess = true,
        ),
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
    )

    private fun install(panelModel: MusicPanelModel = model) {
        compose.setContent {
            MimirTheme {
                MusicPanelOverlay(
                    model = panelModel,
                    focused = null,
                    authority = null,
                    anchor = null,
                    onDismiss = { events += "dismiss" },
                    onPlayPause = { events += "play" + suffix(it) },
                    onPrevious = { events += "previous" + suffix(it) },
                    onNext = { events += "next" + suffix(it) },
                    onMediaRequested = {},
                    onMusicRequested = {},
                    onMusicInteractionEnd = {},
                    onRequestFocus = {},
                    onSwitchSource = { events += "switch" },
                )
            }
        }
        compose.waitForIdle()
    }

    /** One card's events carry no suffix (the single-card tests); two cards say which card was pressed. */
    private var suffixCards = false

    private fun suffix(card: MusicPanelCard) = if (suffixCards) ":${card.name}" else ""

    private fun tapWithDrift(label: String) {
        compose.onNodeWithContentDescription(label).performTouchInput {
            down(center)
            moveBy(Offset(2f, 1f))
            moveBy(Offset(1f, 2f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a motionless tap on Play activates it`() {
        install()
        compose.onNodeWithContentDescription("Play").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf("play"), events)
    }

    @Test
    fun `a tap that drifts a few pixels still activates Play, Previous and Next`() {
        install()
        tapWithDrift("Play")
        tapWithDrift("Previous")
        tapWithDrift("Next")
        assertEquals(listOf("play", "previous", "next"), events)
    }

    @Test
    fun `with a switch row a drifting tap lands on the card's buttons or on the row, and the row switches`() {
        suffixCards = true
        val both = musicPanelModel(
            control = MusicControlState(
                yours = YourMusicState(title = "Song", isPlaying = false, canPrevious = true, canNext = true),
                other = OtherPlayerSnapshot("Track", "Artist", "Spotify", artwork = null, isPlaying = true),
                otherAccess = true,
            ),
            stopped = false,
            musicVolume = 0.4f,
            mediaVolume = 0.7f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
        )
        // The playing other player is the one card; Your music is the row under it.
        assertEquals(listOf(MusicPanelCard.OTHER), both.cards.map { it.card })
        assertEquals(MusicPanelCard.YOURS, both.switchRow!!.card)
        install(both)
        tapWithDrift("Pause")
        tapWithDrift("Next")
        tapWithDrift("Music on this device")
        tapWithDrift("Previous")
        assertEquals(listOf("play:OTHER", "next:OTHER", "switch", "previous:OTHER"), events)
    }

    @Test
    fun `a drifting tap outside the card closes the panel and a drifting tap on the card does not`() {
        install()
        tapWithDrift("Play")
        assertEquals("a tap on the card must not reach the backdrop", listOf("play"), events)
        compose.onRoot().performTouchInput {
            down(Offset(4f, 4f))
            moveBy(Offset(2f, 2f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("play", "dismiss"), events)
    }
}
