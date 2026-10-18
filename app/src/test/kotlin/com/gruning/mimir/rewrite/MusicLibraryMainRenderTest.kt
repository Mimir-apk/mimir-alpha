package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Artist / Album browser on Main's panel , at the Thor's Main geometry (1920 x 1080 px at 369 dpi): each level, a focused row, a long artist
 * name, rows with and without art, the empty page, a raised font size, and the player's card with its Library chip. They land in `build/test-captures/music250/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.MainQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicLibraryMainRenderTest {
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

    private fun model(view: MusicLibraryView, browse: MusicBrowseUi?) = musicPanelModel(
        control = control,
        stopped = false,
        musicVolume = 0.4f,
        mediaVolume = 0.7f,
        mediaAvailable = true,
        mediaFixed = false,
        cappedByMedia = false,
        capPushed = false,
        library = view,
        browse = browse,
    )

    private fun ui(view: MusicLibraryView, state: MusicBrowseState, nowPlaying: String? = "a2", picked: String? = null) =
        musicBrowseUi(view.tree, state.resolved(view.tree), nowPlaying, view.tagsRead, view.trackCount, picked)

    private fun render(
        name: String,
        model: MusicPanelModel,
        focused: MusicPanelControl? = MusicPanelControl.BROWSE_LIST,
        art: Set<String> = MusicLibraryFixtures.artIds,
        fontScale: Float = 1f,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalYourMusicArtSource provides FakeYourMusicArt(art),
            ) {
                MimirTheme {
                    MusicPanelOverlay(
                        model = model,
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
        // Geometry, not just that something drew: the browser's rows stand clear of one another (the focused row's ring reaches the neighbour otherwise), and nothing is
        // taller than a row can be (a wrapped name would show here).
        if (model.browse != null && model.browse.rows.size > 1) {
            MusicRenderSupport.assertRowsClear(compose, minGapDp = MusicBrowseRowGap.value, what = name)
        }
    }

    private val library = MusicLibraryFixtures.view()

    // ── All music, and the picked row (the policy, 10-09) ────────────────────────────────────

    @Test
    fun `artists with no pick, All music is the selected row, the cursor on Alpha`() {
        render("main-library-artists-nopick.png", model(library, ui(library, MusicBrowseState(selectedKey = "alpha"))))
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsNotSelected()
    }

    @Test
    fun `artists with the cursor on All music`() {
        render("main-library-all-focused.png", model(library, ui(library, MusicBrowseState(selectedKey = MusicBrowseAllKey))))
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsSelected()
    }

    @Test
    fun `artists with a pick, Alpha is the selected row, the cursor on Beta`() {
        render("main-library-artists-picked.png", model(library, ui(library, MusicBrowseState(selectedKey = "beta"), picked = "a2")))
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsNotSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "beta").assertIsNotSelected()
    }

    @Test
    fun `the cursor on the picked row wears the plate and the ring together`() {
        render("main-library-picked-focused.png", model(library, ui(library, MusicBrowseState(selectedKey = "alpha"), picked = "a2")))
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsSelected()
    }

    @Test
    fun `an artist's albums with a pick, the album that is the queue is the selected row`() {
        val state = MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey = "alpha", selectedKey = "second")
        render("main-library-albums-picked.png", model(library, ui(library, state, picked = "a2")))
        compose.onNodeWithTag(MusicBrowserRowTestTag + "first").assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "second").assertIsNotSelected()
    }

    @Test
    fun `another artist's albums show no selected row`() {
        val state = MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey = "beta", selectedKey = "only")
        render("main-library-albums-notpicked.png", model(library, ui(library, state, picked = "a2")))
        compose.onNodeWithTag(MusicBrowserRowTestTag + "only").assertIsNotSelected()
    }

    // ── the rows' geometry ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the focused row's halo does not reach the row beside it`() {
        render("main-library-halo.png", model(library, ui(library, MusicBrowseState(selectedKey = "alpha"), picked = "a2")))
        // The ring's inner and middle strokes reach MimirSpacing.sm past a row (MediaArtFrame: what siblings need clear at 0 gap); the gap must hold it.
        val rows = MusicRenderSupport.rowBounds(compose)
        assertTrue("several rows are on screen: ${rows.size}", rows.size >= 3)
        rows.zipWithNext().forEach { (above, below) ->
            val gap = below.top - above.bottom
            assertTrue("the gap between two rows ($gap dp) holds the ring's reach (${MimirSpacing.sm.value} dp)", gap >= MimirSpacing.sm.value - 0.6f)
        }
        assertTrue("and the gap constant is the Utility list's", MusicBrowseRowGap >= MimirSpacing.sm)
    }

    @Test
    fun `artists with the cursor on the second row, some with art and some without`() {
        val state = MusicBrowseState(selectedKey = "beta")
        render("main-library-artists.png", model(library, ui(library, state)))
    }

    @Test
    fun `an artist's albums`() {
        val state = MusicBrowseState(MusicBrowseLevel.ALBUMS, artistKey = "alpha", selectedKey = "first")
        render("main-library-albums.png", model(library, ui(library, state)))
    }

    @Test
    fun `an album's tracks, the one on air marked and the cursor on another`() {
        val state = MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first", selectedKey = "a3")
        render("main-library-tracks.png", model(library, ui(library, state, nowPlaying = "a2")))
    }

    @Test
    fun `a long artist name in the list, and over its album`() {
        val key = MusicLibraryFixtures.LongArtist.lowercase()
        render("main-library-long-artist.png", model(library, ui(library, MusicBrowseState(selectedKey = key))))
    }

    @Test
    fun `a long artist and album name over the tracks`() {
        val artist = MusicLibraryFixtures.LongArtist.lowercase()
        val album = MusicLibraryFixtures.LongAlbum.lowercase()
        val state = MusicBrowseState(MusicBrowseLevel.TRACKS, artist, album, selectedKey = "l1")
        render("main-library-long-artist-tracks.png", model(library, ui(library, state, nowPlaying = null)))
    }

    @Test
    fun `every row with art`() {
        val all = MusicLibraryFixtures.view(MusicLibraryFixtures.tracksWithArt(true))
        render("main-library-art.png", model(all, ui(all, MusicBrowseState(selectedKey = "alpha"))), art = MusicLibraryFixtures.tracks.map { it.id }.toSet())
    }

    @Test
    fun `no row with art keeps the plate and glyph the panel draws`() {
        val none = MusicLibraryFixtures.view(MusicLibraryFixtures.tracksWithArt(false))
        render("main-library-noart.png", model(none, ui(none, MusicBrowseState(selectedKey = "alpha"))), art = emptySet())
    }

    @Test
    fun `while the tags are still being read the top says how far`() {
        val reading = MusicLibraryFixtures.view(tagsRead = 4)
        render("main-library-reading.png", model(reading, ui(reading, MusicBrowseState(selectedKey = "alpha"))))
    }

    @Test
    fun `the empty page says there is nothing to browse`() {
        val empty = MusicLibraryView(loaded = true)
        // The card never offers the browser over an empty library (there is no chip), so this is the page as the card would draw it if the library emptied under it.
        render("main-library-empty.png", model(library, null).copy(browse = ui(empty, MusicBrowseState())))
    }

    @Test
    fun `tracks at font 1_3`() {
        val state = MusicBrowseState(MusicBrowseLevel.TRACKS, "alpha", "first", selectedKey = "a3")
        render("main-library-tracks-font1_3.png", model(library, ui(library, state)), fontScale = 1.3f)
    }

    @Test
    fun `artists at font 1_3`() {
        render("main-library-artists-font1_3.png", model(library, ui(library, MusicBrowseState(selectedKey = "alpha"))), fontScale = 1.3f)
    }

    @Test
    fun `the player's card with the Library chip focused`() {
        render("main-library-chip-focused.png", model(library, null), focused = MusicPanelControl.BROWSE)
    }

    @Test
    fun `the player's card with the Library chip at rest`() {
        render("main-library-chip.png", model(library, null), focused = MusicPanelControl.YOURS_PLAY_PAUSE)
    }
}
