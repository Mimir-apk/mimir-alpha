package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.feature.companion.CompanionPaneLiveScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Artist / Album browser on the Bottom , at the Presentation's 1240 x 1080 px and 369 dpi: the player's Library tab and its sheet at each level,
 * a long artist name, rows with and without art, a raised font size, and the empty page. They land in `build/test-captures/music250/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.BottomQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicLibraryBottomRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeEngine(initial: MusicControlState, library: MusicLibraryView) : MusicPanelEngine {
        override val state = MutableStateFlow(initial)
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.7f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings(enabled = true))
        override val library = MutableStateFlow(library)
        val played = mutableListOf<String>()
        var playedAll = 0

        override fun playPause(source: MusicSource) = Unit
        override fun next(source: MusicSource) = Unit
        override fun previous(source: MusicSource) = Unit
        override fun setMusicVolume(fraction: Float) = Unit
        override fun setMediaVolume(fraction: Float) = Unit
        override fun applyEqualizerPreset(id: EqualizerPresetId) = Unit
        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = false
        override fun playFromLibrary(trackId: String) {
            played += trackId
            // As the real library does: the pressed track is the pick.
            library.value = library.value.copy(pickedTrackId = trackId)
        }

        override fun playAll() {
            playedAll += 1
            library.value = library.value.copy(pickedTrackId = null)
        }
    }

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    private val state = MusicControlState(
        yours = YourMusicState(
            title = "Tone",
            isPlaying = true,
            canPrevious = true,
            canNext = true,
            trackId = "a2",
            format = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304),
            durationMs = 215_000L,
            positionMs = 61_000L,
            canSeek = true,
        ),
        otherAccess = true,
    )

    private fun settle(times: Int = 4) {
        Snapshot.sendApplyNotifications()
        repeat(times) {
            compose.mainClock.advanceTimeBy(400L)
            compose.waitForIdle()
        }
    }

    private fun tap(key: String) {
        // Only rows already on screen are pressed: a scroll under a paused test clock does not settle.
        compose.onNodeWithTag(MusicBrowserRowTestTag + key).performClick()
        settle(3)
    }

    private fun back(times: Int) {
        repeat(times) {
            compose.onNodeWithTag(MusicBrowserBackTestTag).performClick()
            settle(3)
        }
    }

    private fun draw(name: String, fontScale: Float, art: Set<String>, content: @Composable () -> Unit, then: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalBottomUiDisplay provides 4,
                LocalYourMusicArtSource provides FakeYourMusicArt(art),
            ) {
                MimirTheme { content() }
            }
        }
        settle(5)
        then()
        val image = MusicRenderSupport.window(compose.activity.window.decorView)
        MusicRenderSupport.assertDrew(image, name)
        MusicRenderSupport.write(image, name)
        // Geometry too: rows stand clear of one another, as on Main (the Bottom has no ring, but one list grammar keeps one gap).
        if (MusicRenderSupport.rowBounds(compose).size > 1) MusicRenderSupport.assertRowsClear(compose, minGapDp = MusicBrowseRowGap.value, what = name)
    }

    private fun scene(
        name: String,
        view: MusicLibraryView = MusicLibraryFixtures.view(),
        art: Set<String> = MusicLibraryFixtures.artIds,
        fontScale: Float = 1f,
        tab: MusicPlayerTab? = MusicPlayerTab.LIBRARY,
        taps: List<String> = emptyList(),
    ): FakeEngine {
        val engine = FakeEngine(state, view)
        draw(
            name, fontScale, art,
            content = {
                Box(Modifier.fillMaxSize()) {
                    CompanionPaneLiveScope(live = true) {
                        CompanionMusicPanelScene(engine = engine, mediaVolume = FakeMedia(), navigationEnabled = true, onBack = {}, initialTab = tab)
                    }
                }
            },
            then = { taps.forEach(::tap) },
        )
        return engine
    }

    @Test
    fun `the player offers the Library tab beside the others`() {
        scene("bottom-library-tab.png", tab = null)
        compose.onNodeWithTag(companionMusicPlayerTabTag(MusicPlayerTab.LIBRARY)).assertExists()
    }

    @Test
    fun `artists in the Library sheet, some with art and some without`() {
        scene("bottom-library-artists.png")
    }

    @Test
    fun `an artist's albums, with the Back chip`() {
        scene("bottom-library-albums.png", taps = listOf("alpha"))
    }

    @Test
    fun `an album's tracks, the one on air marked, and a press on a track plays it`() {
        val engine = scene("bottom-library-tracks.png", taps = listOf("alpha", "first", "a3"))
        assertEquals(listOf("a3"), engine.played)
    }

    // ── All music, and the picked row (the policy, 10-09) ────────────────────────────────────

    @Test
    fun `with no pick All music is the selected row`() {
        scene("bottom-library-artists-nopick.png")
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsNotSelected()
    }

    @Test
    fun `after a track is pressed its artist is the selected row, and All music is not`() {
        val engine = scene("bottom-library-artists-picked.png", taps = listOf("alpha", "first", "a3"))
        assertEquals(listOf("a3"), engine.played)
        back(2)
        MusicRenderSupport.write(MusicRenderSupport.window(compose.activity.window.decorView), "bottom-library-artists-picked.png")
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsNotSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "beta").assertIsNotSelected()
    }

    @Test
    fun `after a track is pressed its album is the selected row in the artist's albums`() {
        scene("bottom-library-albums-picked.png", taps = listOf("alpha", "first", "a3"))
        back(1)
        MusicRenderSupport.write(MusicRenderSupport.window(compose.activity.window.decorView), "bottom-library-albums-picked.png")
        compose.onNodeWithTag(MusicBrowserRowTestTag + "first").assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "second").assertIsNotSelected()
    }

    @Test
    fun `a tap on All music clears the pick and plays everything`() {
        val engine = scene("bottom-library-all-pressed.png", taps = listOf("alpha", "first", "a3"))
        back(2)
        tap(MusicBrowseAllKey)
        assertEquals(1, engine.playedAll)
        MusicRenderSupport.write(MusicRenderSupport.window(compose.activity.window.decorView), "bottom-library-all-pressed.png")
        compose.onNodeWithTag(MusicBrowserRowTestTag + MusicBrowseAllKey).assertIsSelected()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertIsNotSelected()
    }

    @Test
    fun `a long artist name in the list`() {
        scene("bottom-library-long-artist.png")
    }

    @Test
    fun `a long artist and album name over the tracks`() {
        val artist = MusicLibraryFixtures.LongArtist.lowercase()
        val album = MusicLibraryFixtures.LongAlbum.lowercase()
        scene("bottom-library-long-artist-tracks.png", taps = listOf(artist, album))
    }

    @Test
    fun `every row with art`() {
        val all = MusicLibraryFixtures.view(MusicLibraryFixtures.tracksWithArt(true))
        scene("bottom-library-art.png", view = all, art = MusicLibraryFixtures.tracks.map { it.id }.toSet())
    }

    @Test
    fun `no row with art keeps the plate and glyph`() {
        val none = MusicLibraryFixtures.view(MusicLibraryFixtures.tracksWithArt(false))
        scene("bottom-library-noart.png", view = none, art = emptySet())
    }

    @Test
    fun `artists at font 1_3`() {
        scene("bottom-library-artists-font1_3.png", fontScale = 1.3f)
    }

    @Test
    fun `tracks at font 1_3`() {
        scene("bottom-library-tracks-font1_3.png", fontScale = 1.3f, taps = listOf("alpha", "first"))
    }

    @Test
    fun `Back goes up a level, and from the top closes the sheet`() {
        scene("bottom-library-back-start.png", taps = listOf("alpha", "first"))
        compose.onNodeWithTag(MusicBrowserRowTestTag + "a3").assertExists()
        compose.onNodeWithTag(MusicBrowserBackTestTag).performClick()
        settle(2)
        compose.onNodeWithTag(MusicBrowserRowTestTag + "second").assertExists()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "a3").assertDoesNotExist()
        compose.onNodeWithTag(MusicBrowserBackTestTag).performClick()
        settle(2)
        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha").assertExists()
        compose.onNodeWithTag(MusicBrowserBackTestTag).performClick()
        settle(8)
        compose.onNodeWithTag(CompanionMusicPlayerSheetTag).assertDoesNotExist()
    }

    @Test
    fun `the empty page, drawn alone in the sheet's glass`() {
        val empty = MusicLibraryView(loaded = true)
        val ui = musicBrowseUi(empty.tree, MusicBrowseState().resolved(empty.tree), null, 0, 0)
        draw(
            "bottom-library-empty.png", 1f, emptySet(),
            content = {
                val palette = LocalMimirPalette.current.liquidGlassContentPalette()
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    MusicLibraryBrowserPage(ui = ui, palette = palette, ringFocused = false, onPress = {}, onBack = {}, touch = true)
                }
            },
        )
    }
}
