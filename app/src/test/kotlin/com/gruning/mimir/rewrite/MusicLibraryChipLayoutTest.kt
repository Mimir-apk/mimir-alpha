package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.companionnative.MusicPanelEdgeClearanceDp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Main card with Your music's cover AND the Library chip (the earlier update review's P2): the card is capped at the display's height less its clearance, and the Music slider
 * must stand whole inside it. The chip had a row of its own, 56 dp, which with the art's 88 dp took the 340 dp card to 484 dp and cut the slider. The chip now stands on the
 * heading line and adds no row. These measure the real composables at the Thor's Main geometry, by each row's UNCLIPPED bounds (a clipped node reports only what shows, so a
 * cut row would pass a check on its visible bounds), and write the pictures to `build/test-captures/music250/`.
 *
 * What the card cannot hold it scrolls, with the Utility rail's fade at the foot: with the Equalizer row on as well, the art's 88 dp pushes that last row past the cap with or
 * without the chip, and a press down to it scrolls it in. The checks below hold the Music slider whole in every state and the whole card whole where it fits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.MainQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicLibraryChipLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val control = MusicControlState(
        yours = YourMusicState(
            title = "Sweep",
            isPlaying = true,
            canPrevious = true,
            canNext = true,
            trackId = "a2",
            format = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304),
        ),
        otherAccess = true,
    )

    private val library = MusicLibraryFixtures.view()

    private fun model(withChip: Boolean, withArt: Boolean, withEqualizer: Boolean): MusicPanelModel = musicPanelModel(
        control = control,
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
        equalizerShown = withEqualizer,
        library = if (withChip) library else MusicLibraryView(loaded = true),
    ).withYoursArt(if (withArt) YourMusicArtState("a2", EmbeddedArtHint.YES, MusicRenderSupport.cover(256, seed = 1)) else null)

    /** What the card measured to, in dp. Row rects are UNCLIPPED (where the row is laid out, shown or not). */
    private data class Measured(
        val card: Rect,
        val media: Rect,
        val music: Rect,
        val equalizer: Rect?,
        val chip: Rect?,
        val windowHeightDp: Float,
    ) {
        val cap: Float get() = windowHeightDp - 2 * MusicPanelEdgeClearanceDp
    }

    private fun render(
        name: String,
        withChip: Boolean,
        withArt: Boolean,
        fontScale: Float,
        withEqualizer: Boolean = false,
        focused: MusicPanelControl = MusicPanelControl.YOURS_PLAY_PAUSE,
    ): Measured {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalYourMusicArtSource provides FakeYourMusicArt(MusicLibraryFixtures.artIds),
            ) {
                MimirTheme {
                    MusicPanelOverlay(
                        model = model(withChip, withArt, withEqualizer),
                        focused = focused,
                        authority = null,
                        anchor = null,
                        onDismiss = {},
                        onPlayPause = {},
                        onPrevious = {},
                        onNext = {},
                        onMediaRequested = {},
                        onMusicRequested = {},
                        onMusicInteractionEnd = {},
                        onRequestFocus = {},
                        browse = MusicBrowseHooks(onOpen = {}),
                    )
                }
            }
        }
        Snapshot.sendApplyNotifications()
        repeat(6) {
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
        }
        val image = MusicRenderSupport.window(compose.activity.window.decorView)
        MusicRenderSupport.assertDrew(image, name)
        MusicRenderSupport.write(image, name)
        val density = compose.activity.resources.displayMetrics.density
        fun unclipped(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().let {
            Rect(it.positionInRoot.x / density, it.positionInRoot.y / density, (it.positionInRoot.x + it.size.width) / density, (it.positionInRoot.y + it.size.height) / density)
        }
        fun clipped(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.let {
            Rect(it.left / density, it.top / density, it.right / density, it.bottom / density)
        }
        fun orNull(block: () -> Rect): Rect? = runCatching(block).getOrNull()
        val measured = Measured(
            card = clipped(MusicPanelCardTestTag),
            media = unclipped(MusicPanelMediaRowTestTag),
            music = unclipped(MusicPanelMusicRowTestTag),
            equalizer = orNull { unclipped(MusicPanelEqualizerRowTestTag) },
            chip = orNull { clipped(MusicPanelBrowseChipTestTag) },
            windowHeightDp = image.height / density,
        )
        println("MUSIC-LAYOUT $name font=$fontScale art=$withArt chip=$withChip eq=$withEqualizer card=${measured.card} media=${measured.media} music=${measured.music} eq=${measured.equalizer} chip=${measured.chip} cap=${measured.cap}dp")
        return measured
    }

    private fun assertCardHolds(what: String, m: Measured, includeEqualizer: Boolean) {
        val eps = 0.6f
        assertTrue("$what: the card (${m.card.height} dp) is within the display's cap (${m.cap} dp)", m.card.height <= m.cap + eps)
        assertTrue("$what: Game & media (bottom ${m.media.bottom}) is whole inside the card (bottom ${m.card.bottom})", m.media.bottom <= m.card.bottom + eps)
        assertTrue("$what: the Music slider (bottom ${m.music.bottom}) is whole inside the card (bottom ${m.card.bottom})", m.music.bottom <= m.card.bottom + eps)
        if (includeEqualizer) {
            val eq = m.equalizer
            assertTrue("$what: the Equalizer row is on the card", eq != null)
            assertTrue("$what: the Equalizer row (bottom ${eq!!.bottom}) is whole inside the card (bottom ${m.card.bottom})", eq.bottom <= m.card.bottom + eps)
        }
    }

    @Test
    fun `the cover and the chip together keep the card whole, the chip on the heading line`() {
        val m = render("main-yours-art-chip.png", withChip = true, withArt = true, fontScale = 1f)
        assertCardHolds("art + chip at 1.0x", m, includeEqualizer = false)
        val chip = m.chip
        assertTrue("the chip is on the card", chip != null)
        assertTrue("the chip stands on the heading line (top ${chip!!.top}), above the cover", chip.top < m.card.top + 60f)
        assertTrue("and inside the card's width", chip.right <= m.card.right && chip.left >= m.card.left)
    }

    @Test
    fun `the cover and the chip together at font 1_3 keep the sliders whole`() {
        val m = render("main-yours-art-chip-font1_3.png", withChip = true, withArt = true, fontScale = 1.3f)
        println("MUSIC-LAYOUT 1.3x card height ${m.card.height} dp")
        assertCardHolds("art + chip at 1.3x", m, includeEqualizer = false)
    }

    @Test
    fun `with the Equalizer on as well the Music slider is still whole`() {
        val m = render("main-yours-art-chip-eq.png", withChip = true, withArt = true, fontScale = 1f, withEqualizer = true)
        assertCardHolds("art + chip + Equalizer at 1.0x", m, includeEqualizer = false)
    }

    @Test
    fun `with the Equalizer on at font 1_3 the Music slider is still whole`() {
        val m = render("main-yours-art-chip-eq-font1_3.png", withChip = true, withArt = true, fontScale = 1.3f, withEqualizer = true)
        assertCardHolds("art + chip + Equalizer at 1.3x", m, includeEqualizer = false)
    }

    @Test
    fun `without a cover the chip and the Equalizer fit the card whole`() {
        val m = render("main-yours-noart-chip-eq.png", withChip = true, withArt = false, fontScale = 1f, withEqualizer = true)
        assertCardHolds("no art + chip + Equalizer at 1.0x", m, includeEqualizer = true)
    }

    @Test
    fun `the chip focused keeps its ring inside the panel`() {
        val m = render("main-yours-art-chip-focused.png", withChip = true, withArt = true, fontScale = 1f, focused = MusicPanelControl.BROWSE)
        val chip = m.chip!!
        // The ring reaches MimirFocusTileRingReach (14 dp) past the plate; inside the panel it needs the inner and middle strokes' reach (8 dp) above and beside the plate.
        assertTrue("room above the chip for its ring: ${chip.top - m.card.top} dp", chip.top - m.card.top >= 8f)
        assertTrue("room beside the chip for its ring: ${m.card.right - chip.right} dp", m.card.right - chip.right >= 8f)
    }
}
