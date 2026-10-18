package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.companionnative.OverlayButtonBounds
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The music card on a real layout pass: the measured-zero hold (D-16: valid A, exact zero, a distinct valid B, as the other
 * boundaries' replays do), the 320 dp width and its cap at the display less 14 dp each side, the anchor, and the scroll when
 * the card is taller than the display allows.
 *
 * Width-cap fixtures: the cap only bites below 348 dp (320 + 2 x 14), so the capped cases use 300 dp and 340 dp displays, once
 * at density 1 and once as a 720 px square at density 2.4 (the RG-class square, 300 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val yours = MusicControlState(
        yours = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true),
        otherAccess = true,
    )

    private fun modelFor(control: MusicControlState) = musicPanelModel(
        control = control,
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
    )

    private val model = modelFor(yours)

    /** Another player's session and Your music, the other one playing (so the card; Your music is the switch row), notification access held (no foot line). */
    private val bothModel = modelFor(
        MusicControlState(
            yours = YourMusicState(title = "Song", isPlaying = false, canPrevious = true, canNext = true),
            other = OtherPlayerSnapshot("Track", "Artist", "Spotify", artwork = null, isPlaying = true),
            otherAccess = true,
        ),
    )

    private data class Viewport(val widthDp: Float, val heightDp: Float)

    private data class Box4(val left: Float, val top: Float, val width: Float, val height: Float)

    private val zero = Viewport(0f, 0f)

    private fun install(
        viewport: androidx.compose.runtime.MutableState<Viewport>,
        density: Float = 1f,
        anchor: OverlayButtonBounds? = null,
        panelModel: MusicPanelModel = model,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 1f)) {
                MimirTheme {
                    FixedViewport(viewport.value) {
                        MusicPanelOverlay(
                            model = panelModel,
                            focused = null,
                            authority = null,
                            anchor = anchor,
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
        }
        compose.waitForIdle()
    }

    private fun card(): Box4 {
        val rect = compose.onNodeWithTag(MusicPanelCardTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Box4(rect.left.value, rect.top.value, (rect.right - rect.left).value, (rect.bottom - rect.top).value)
    }

    @Test
    fun `a measured zero holds the card where it was, keeps its content, and a distinct size settles after it`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport)
        assertChrome()
        val a = card()
        assertTrue("A geometry must be positive", a.width > 0f && a.height > 0f)

        compose.runOnIdle { viewport.value = zero }
        compose.waitForIdle()
        assertChrome()
        // Size only: the held seat is centred inside a zero-sized parent, which moves its origin and nothing else.
        val held = card()
        assertEquals("held zero width", a.width, held.width, Tolerance)
        assertEquals("held zero height", a.height, held.height, Tolerance)

        compose.runOnIdle { viewport.value = Viewport(300f, 300f) }
        compose.waitForIdle()
        assertChrome()
        val b = card()
        assertTrue("B must settle a distinct geometry", b.width != a.width || b.height != a.height)
        assertTrue("B stays positive", b.width > 0f && b.height > 0f)
    }

    @Test
    fun `a zero before any valid size renders nothing for that frame and does not crash`() {
        val viewport = mutableStateOf(zero)
        install(viewport)
        assertTrue(
            "no card yet",
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(MusicPanelCardTestTag), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.runOnIdle { viewport.value = Viewport(900f, 700f) }
        compose.waitForIdle()
        assertTrue(card().width > 0f)
    }

    @Test
    fun `the card is 320 dp wide on a display that has room`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport)
        for ((w, h) in listOf(900f to 700f, 720f to 720f, 348f to 600f)) {
            compose.runOnIdle { viewport.value = Viewport(w, h) }
            compose.waitForIdle()
            assertEquals("${w}x$h", 320f, card().width, Tolerance)
        }
    }

    @Test
    fun `the card is capped at the display less 14 dp each side`() {
        val viewport = mutableStateOf(Viewport(300f, 700f))
        install(viewport)
        assertEquals("300 dp display", 272f, card().width, Tolerance)

        compose.runOnIdle { viewport.value = Viewport(340f, 700f) }
        compose.waitForIdle()
        assertEquals("340 dp display", 312f, card().width, Tolerance)
    }

    @Test
    fun `a 720 px square at density 2_4 is a 300 dp display and caps the card at 272 dp`() {
        val viewport = mutableStateOf(Viewport(300f, 300f))
        install(viewport, density = 2.4f)
        val c = card()
        assertEquals(272f, c.width, Tolerance)
        assertTrue("never taller than the display less the clearance: ${c.height}", c.height <= 272f + Tolerance)
        assertTrue("clear of the top and bottom edges", c.top >= 14f - Tolerance && c.top + c.height <= 286f + Tolerance)
    }

    @Test
    fun `with no badge the card is centred`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport)
        val c = card()
        assertEquals((900f - c.width) / 2f, c.left, Tolerance)
        assertEquals((700f - c.height) / 2f, c.top, Tolerance)
    }

    @Test
    fun `on the left edge the card's outer side and top meet the badge's`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport, anchor = OverlayButtonBounds(14, 120, 70, 176, 900, 700))
        val c = card()
        assertEquals(14f, c.left, Tolerance)
        assertEquals(120f, c.top, Tolerance)
    }

    @Test
    fun `on the right edge the card ends where the badge does`() {
        val viewport = mutableStateOf(Viewport(900f, 700f))
        install(viewport, anchor = OverlayButtonBounds(830, 120, 886, 176, 900, 700))
        val c = card()
        assertEquals(886f, c.left + c.width, Tolerance)
        assertEquals(120f, c.top, Tolerance)
    }

    @Test
    fun `a card taller than the display allows is held inside it and scrolls`() {
        val viewport = mutableStateOf(Viewport(400f, 200f))
        install(viewport)
        val c = card()
        assertTrue("height ${c.height} fits 200 less 28", c.height <= 172f + Tolerance)
        assertEquals(14f, c.top, Tolerance)
    }

    /** Chrome text speaks its natural string as the content description (the visual is capitals), so that is what is matched. */
    private fun named(text: String, exact: Boolean) =
        hasContentDescription(text, substring = !exact) or hasText(text, substring = !exact)

    private fun textTop(text: String, exact: Boolean = false): Float =
        compose.onAllNodes(named(text, exact), useUnmergedTree = true)[0].getUnclippedBoundsInRoot().top.value

    private fun textCount(text: String, exact: Boolean = false): Int =
        compose.onAllNodes(named(text, exact), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `two players show one full card and a compact switch row under it, and the sliders follow once`() {
        val viewport = mutableStateOf(Viewport(900f, 1200f))
        install(viewport, panelModel = bothModel)
        // The other player is playing, so it is the one card; Your music is a single row under it, by name over its track.
        assertEquals("one status line, the playing card's", 1, textCount("Playing — Track"))
        assertEquals("Your music has no card of its own", 0, textCount("Paused — Song"))
        assertEquals("one transport", 1, compose.onAllNodes(hasContentDescription("Next"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("the row is named for the source", textCount("Music on this device", exact = true) >= 1)
        assertTrue("its track is under the name", textCount("Song", exact = true) >= 1)
        assertTrue("the row is under the card", textTop("Music on this device", exact = true) > textTop("Playing — Track"))
        assertTrue("the Game & media slider is below the row", textTop("Game & media", exact = true) > textTop("Music on this device", exact = true))
        assertTrue("the Music slider follows it", textTop("Music", exact = true) > textTop("Game & media", exact = true))
        // A slider is its label and the control's own node, both named so: two nodes for one slider, not four for two.
        assertEquals("the sliders are there once", 2, textCount("Game & media", exact = true))
        assertEquals(2, textCount("Music", exact = true))
        assertEquals("the other player's artist is on its card", 1, textCount("Artist", exact = true))
        assertEquals("the card keeps the usual width", 320f, card().width, Tolerance)
    }

    @Test
    fun `Your music is the card when it is the one playing, and the other player is the row`() {
        val viewport = mutableStateOf(Viewport(900f, 1400f))
        install(
            viewport,
            panelModel = modelFor(
                MusicControlState(
                    yours = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true),
                    other = OtherPlayerSnapshot("Track", "Artist", "Spotify", artwork = null, isPlaying = false),
                    otherAccess = true,
                ),
            ),
        )
        assertEquals(1, textCount("Playing — Song"))
        assertEquals(0, textCount("Paused — Track"))
        assertTrue("the other player's row is under the card", textTop("Spotify", exact = true) > textTop("Playing — Song"))
        assertTrue("its track is on the row", textCount("Track", exact = true) >= 1)
    }

    @Test
    fun `a card and its row on a short display are held inside it and scroll, as one does`() {
        val viewport = mutableStateOf(Viewport(400f, 220f))
        install(viewport, panelModel = bothModel)
        val c = card()
        assertTrue("height ${c.height} fits 220 less 28", c.height <= 192f + Tolerance)
        assertEquals(14f, c.top, Tolerance)
        assertTrue("the content fills the display, so the card is held to it", c.height >= 192f - Tolerance)
    }

    @Test
    fun `the foot line is on the card below the sliders while Your music is shown without notification access`() {
        val viewport = mutableStateOf(Viewport(900f, 1200f))
        install(viewport, panelModel = modelFor(MusicControlState(yours = YourMusicState(title = "Song", isPlaying = true))))
        assertEquals(1, textCount(MusicPanelAccessHint, exact = true))
        assertTrue("below the sliders", textTop(MusicPanelAccessHint, exact = true) > textTop("Music", exact = true))
    }

    @Test
    fun `no foot line when access is held`() {
        val viewport = mutableStateOf(Viewport(900f, 1200f))
        install(viewport)
        assertEquals(0, textCount(MusicPanelAccessHint, exact = true))
    }

    private fun assertChrome() {
        assertTrue(
            "the heading must stay present",
            compose.onAllNodes(
                hasText("Music on this device", substring = true, ignoreCase = true) or
                    hasContentDescription("Music on this device", substring = true, ignoreCase = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** Forces the child to receive exact viewport constraints, including [Constraints.fixed] (0, 0). */
    @Composable
    private fun FixedViewport(viewport: Viewport, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { viewport.widthDp.dp.roundToPx() }
        val heightPx = with(density) { viewport.heightDp.dp.roundToPx() }
        Layout(
            content = {
                Box(propagateMinConstraints = true, content = { content() })
            },
        ) { measurables, constraints ->
            val placeable = measurables.single().measure(Constraints.fixed(widthPx, heightPx))
            val layoutWidth = widthPx.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight = heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                placeable.placeRelative(0, 0)
            }
        }
    }

    private companion object {
        const val Tolerance = 1.5f
    }
}
