package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of Your music's embedded art  on the surfaces that show a Your-music card: Main's panel (with art, without, and at font 1.3), drawn at the Thor's
 * Main geometry (1920 x 1080 px at 369 dpi). They land in `build/test-captures/music250/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = MusicRenderSupport.MainQualifiers)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YourMusicArtMainRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun model(title: String = "Dreams of the Hedgehog", art: YourMusicArtState? = null): MusicPanelModel =
        musicPanelModel(
            control = MusicControlState(
                yours = YourMusicState(
                    title = title,
                    isPlaying = true,
                    canPrevious = true,
                    canNext = true,
                    trackId = "track-1",
                    format = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304),
                ),
                otherAccess = true,
            ),
            stopped = false,
            musicVolume = 0.4f,
            mediaVolume = 0.7f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
        ).withYoursArt(art)

    private fun render(name: String, model: MusicPanelModel, fontScale: Float = 1f) {
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
        MusicRenderSupport.write(image, name)
    }

    private val cover: Bitmap = MusicRenderSupport.cover(256, seed = 0)

    @Test
    fun `main panel Your music with art`() {
        render("main-yours-art.png", model(art = YourMusicArtState("track-1", EmbeddedArtHint.YES, cover)))
    }

    @Test
    fun `main panel Your music without art keeps the card it had`() {
        render("main-yours-noart.png", model(art = YourMusicArtState("track-1", EmbeddedArtHint.NO, null)))
    }

    @Test
    fun `main panel Your music while the art loads holds the seat blank`() {
        render("main-yours-art-loading.png", model(art = YourMusicArtState("track-1", EmbeddedArtHint.YES, null)))
    }

    @Test
    fun `main panel Your music with art at font 1_3 and a long title`() {
        render(
            "main-yours-art-font1_3.png",
            model(title = "A Very Long Track Title That Will Not Fit On One Line Of The Card", art = YourMusicArtState("track-1", EmbeddedArtHint.YES, cover)),
            fontScale = 1.3f,
        )
    }
}
