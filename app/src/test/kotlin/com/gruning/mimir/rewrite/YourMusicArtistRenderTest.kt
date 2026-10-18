package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.companion.CompanionPaneLiveScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two tracks in this fixture: "Sweep CBR128" with an artist tag and without one (it then shows its format line, as it always did). */
private object ArtistFixtures {
    val format = TrackFormat(codec = "MP3", lossless = false, sampleRateHz = 44_100, bitrateKbps = 128)

    private fun yours(artist: String?, title: String = "Sweep CBR128") = YourMusicState(
        title = title,
        artist = artist,
        isPlaying = true,
        canPrevious = true,
        canNext = true,
        trackId = "track-1",
        format = format,
        durationMs = 6_000L,
        positionMs = 1_000L,
        canSeek = true,
    )

    fun state(artist: String?, title: String = "Sweep CBR128") = MusicControlState(yours = yours(artist, title), otherAccess = true)

    const val Artist = "Sweep Band"
    const val LongArtist = "The Remarkably Long Named Orchestra and Chamber Ensemble of the Northern Provinces"
    const val OutDir = "build/test-captures/fix29b"
}

/** Every word the semantics carries (text and descriptions), as the panel's own tests read it: chrome is drawn capped but described as written. */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.words(): List<String> = onAllNodes(
    androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text) or
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription),
    useUnmergedTree = true,
).fetchSemanticsNodes().flatMap { node ->
    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty()
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.says(fragment: String) = words().any { fragment.lowercase() in it.lowercase() }

private class ArtistFakeEngine(initial: MusicControlState) : MusicPanelEngine {
    override val state = MutableStateFlow(initial)
    override val musicVolume = MutableStateFlow(0.4f)
    override val mediaVolume = MutableStateFlow(0.7f)
    override val cappedByMedia = MutableStateFlow(false)
    override val equalizer = MutableStateFlow(EqualizerSettings(enabled = true))

    override fun playPause(source: MusicSource) = Unit
    override fun next(source: MusicSource) = Unit
    override fun previous(source: MusicSource) = Unit
    override fun setMusicVolume(fraction: Float) = Unit
    override fun setMediaVolume(fraction: Float) = Unit
    override fun applyEqualizerPreset(id: EqualizerPresetId) = Unit
    override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = false
}

private class ArtistFakeMedia : MediaVolumeGateway {
    override fun isAvailable() = true
    override fun isFixedVolume() = false
    override fun readFraction(): Float? = null
    override fun writeFraction(fraction: Float) = false
    override fun step(direction: Int): Float? = null
}

/**
 * the music player showed the file format ("MP3 · 128 KBPS · 44.1 KHZ") under the title where an artist belongs. Pictures of the line under the title on the Main
 * panel, on the Bottom's full player and on the Activity page's media tile: a tagged track names its artist in its own case, an untagged one keeps its format line (capped, as it was).
 * Main is 1920 x 1080 px at 369 dpi; the Bottom is 1240 x 1080. They land in `build/test-captures/fix29b/`. Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.MainQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YourMusicArtistMainRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, state: MusicControlState, fontScale: Float = 1f): MusicPanelModel {
        val model = musicPanelModel(
            control = state,
            stopped = false,
            musicVolume = 0.4f,
            mediaVolume = 0.7f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MimirTheme {
                    MusicPanelOverlay(
                        model = model,
                        focused = MusicPanelControl.YOURS_PLAY_PAUSE,
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
                    )
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        val image = MusicRenderSupport.window(compose.activity.window.decorView)
        MusicRenderSupport.assertDrew(image, name)
        MusicRenderSupport.write(image, name, ArtistFixtures.OutDir)
        return model
    }

    private fun drawn(text: String) = compose.says(text)

    @Test
    fun `main panel - a tagged track names its artist in its own case under the title`() {
        val model = render("h1-main-tagged.png", ArtistFixtures.state(ArtistFixtures.Artist))
        assertTrue("the artist is the line", model.top.artist == ArtistFixtures.Artist && model.top.artistIsAuthored)
        assertTrue("the artist is drawn as tagged, not capped", drawn(ArtistFixtures.Artist))
        assertTrue("and the format line is not under the title", !drawn("MP3 · 128 kbps · 44.1 kHz") && !drawn("MP3 · 128 KBPS · 44.1 KHZ"))
    }

    @Test
    fun `main panel - an untagged track keeps its format line`() {
        val model = render("h1-main-untagged.png", ArtistFixtures.state(null))
        assertTrue("the format line is the line", model.top.artist == "MP3 · 128 kbps · 44.1 kHz" && !model.top.artistIsAuthored)
        assertTrue("the format line is drawn", drawn("MP3 · 128 KBPS · 44.1 KHZ") || drawn("MP3 · 128 kbps · 44.1 kHz"))
    }

    @Test
    fun `main panel - a long artist at font 1_3 ends in its ellipsis and the card holds`() {
        render("h1-main-tagged-long-1.3.png", ArtistFixtures.state(ArtistFixtures.LongArtist), fontScale = 1.3f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.BottomQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YourMusicArtistBottomRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun draw(name: String, fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalBottomUiDisplay provides 4,
            ) {
                MimirTheme { content() }
            }
        }
        Snapshot.sendApplyNotifications()
        repeat(4) {
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
        }
        val image = MusicRenderSupport.window(compose.activity.window.decorView)
        MusicRenderSupport.assertDrew(image, name)
        MusicRenderSupport.write(image, name, ArtistFixtures.OutDir)
    }

    private fun drawn(text: String) = compose.says(text)

    private fun player(name: String, artist: String?, fontScale: Float = 1f) {
        val engine = ArtistFakeEngine(ArtistFixtures.state(artist))
        draw(name, fontScale) {
            Box(Modifier.fillMaxSize()) {
                CompanionPaneLiveScope(live = true) {
                    CompanionMusicPanelScene(engine = engine, mediaVolume = ArtistFakeMedia(), navigationEnabled = true, onBack = {})
                }
            }
        }
    }

    private fun tile(name: String, artist: String?, fontScale: Float = 1f) {
        val state = ArtistFixtures.state(artist)
        val engine = ArtistFakeEngine(state)
        draw(name, fontScale) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                ActivityEngineMediaTile(
                    card = requireNotNull(musicTileCard(state)),
                    engine = engine,
                    navigationEnabled = true,
                    onOpenPanel = {},
                    modifier = Modifier.width(300.dp),
                )
            }
        }
    }

    @Test
    fun `bottom player - a tagged track names its artist under the title`() {
        player("h1-bottom-player-tagged.png", ArtistFixtures.Artist)
        assertTrue("the artist is drawn as tagged", drawn(ArtistFixtures.Artist))
    }

    @Test
    fun `bottom player - an untagged track keeps its format line`() {
        player("h1-bottom-player-untagged.png", null)
        assertTrue("the format line is drawn", drawn("MP3 · 128 KBPS · 44.1 KHZ") || drawn("MP3 · 128 kbps · 44.1 kHz"))
    }

    @Test
    fun `bottom player - a long artist at font 1_3`() = player("h1-bottom-player-tagged-long-1.3.png", ArtistFixtures.LongArtist, fontScale = 1.3f)

    @Test
    fun `activity PLAYING tile - a tagged track names its artist`() {
        tile("h1-bottom-tile-tagged.png", ArtistFixtures.Artist)
        assertTrue("the artist is drawn as tagged", drawn(ArtistFixtures.Artist))
    }

    @Test
    fun `activity PLAYING tile - an untagged track keeps its format line`() {
        tile("h1-bottom-tile-untagged.png", null)
        assertTrue("the format line is drawn", drawn("MP3 · 128 KBPS · 44.1 KHZ") || drawn("MP3 · 128 kbps · 44.1 kHz"))
    }
}
