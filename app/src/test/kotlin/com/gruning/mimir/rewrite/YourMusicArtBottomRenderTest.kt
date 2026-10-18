package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
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
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of Your music's embedded art on the Bottom : the full player's art seat (with art, without, and at font 1.3) and the Activity page's media tile,
 * at the Presentation's 1240 x 1080 px and 369 dpi. They land in `build/test-captures/music250/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.BottomQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YourMusicArtBottomRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeEngine(initial: MusicControlState) : MusicPanelEngine {
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

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    /** The art a track "has": the same cover for any track, or none. */
    private class FakeArt(private val has: Boolean, private val side: Int = 640) : YourMusicArtSource {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun hint(trackId: String) = if (has) EmbeddedArtHint.YES else EmbeddedArtHint.NO
        override fun cached(trackId: String, px: Int): Bitmap? = null
        override fun knownMissing(trackId: String, px: Int) = !has
        override suspend fun load(context: Context, trackId: String, px: Int): Bitmap? = if (has) MusicRenderSupport.cover(minOf(side, px), seed = 2) else null
    }

    private val state = MusicControlState(
        yours = YourMusicState(
            title = "Dreams of the Hedgehog",
            isPlaying = true,
            canPrevious = true,
            canNext = true,
            trackId = "track-1",
            format = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304),
            durationMs = 215_000L,
            positionMs = 61_000L,
            canSeek = true,
        ),
        otherAccess = true,
    )

    private fun draw(name: String, fontScale: Float, art: YourMusicArtSource, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalBottomUiDisplay provides 4,
                LocalYourMusicArtSource provides art,
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
        MusicRenderSupport.write(image, name)
    }

    private fun player(name: String, has: Boolean, fontScale: Float = 1f) {
        val engine = FakeEngine(state)
        draw(name, fontScale, FakeArt(has)) {
            Box(Modifier.fillMaxSize()) {
                CompanionPaneLiveScope(live = true) {
                    CompanionMusicPanelScene(engine = engine, mediaVolume = FakeMedia(), navigationEnabled = true, onBack = {})
                }
            }
        }
    }

    private fun tile(name: String, has: Boolean, fontScale: Float = 1f) {
        val engine = FakeEngine(state)
        draw(name, fontScale, FakeArt(has)) {
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
    fun `bottom player with art`() = player("bottom-player-art.png", has = true)

    @Test
    fun `bottom player without art shows the plate and glyph it always showed`() = player("bottom-player-noart.png", has = false)

    @Test
    fun `bottom player with art at font 1_3`() = player("bottom-player-art-font1_3.png", has = true, fontScale = 1.3f)

    @Test
    fun `activity media tile with art`() = tile("bottom-tile-art.png", has = true)

    @Test
    fun `activity media tile without art`() = tile("bottom-tile-noart.png", has = false)
}
