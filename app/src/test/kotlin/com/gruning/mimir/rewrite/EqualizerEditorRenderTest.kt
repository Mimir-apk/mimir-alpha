package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MediaOutput
import com.gruning.mimir.rewrite.core.audio.MediaOutputKind
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.ThemeBedState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The equalizer editor as pixels on the Thor's Main (832.8 x 468.3 dp at 369 dpi), at font 1.0 and 1.3, drawn the way this repo rasterises (the
 * view hierarchy into a software bitmap). The PNGs land in `build/test-captures/audiofix/`. Pixel values are the JVM's; a handheld's are device-owed.
 *
 * This class holds the states of the one-Equalizer change: the foot hint as the footer's etched keycap, the header's
 * natural-case data lines, and the header's information block: where media sound goes, what is playing from any app (and the one line without
 * notification access), and which Equalizer is active, with the global engine holding the mix, held by another app, or refused. Mimir's own
 * track's format sits right under its "Playing/Paused: Mimir" entry and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerEditorRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rootDensity = 369f / 160f

    private val flac = TrackFormat(
        codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304,
        output = OutputFormat(bitDepth = 32, isFloat = true, sampleRateHz = 48_000),
    )

    private val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)

    private val working = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON)
    private val taken = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL)
    private val refused = SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE)

    private val speaker = MediaOutput(MediaOutputKind.SPEAKER)
    private val buds = MediaOutput(MediaOutputKind.BLUETOOTH, "WH-1000XM5")

    private val mp3 = TrackFormat(
        codec = "MP3", lossless = false, bitrateKbps = 320, sampleRateHz = 44_100,
        output = OutputFormat(bitDepth = 16, sampleRateHz = 48_000),
    )

    private val mimirMusicPlaying = MusicControlState(yours = YourMusicState(title = "Overworld Theme", isPlaying = true, format = flac), otherAccess = true)

    /** The default source: Mimir's theme bed is audibly playing, and Your music is not the source (so there is no Your music state at all). */
    private val themeBedPlaying = MusicControlState(bed = ThemeBedState(title = "Overworld Theme", isPlaying = true), otherAccess = true)

    /** Your music paused for Spotify, which now plays: its MP3's format is still held on the state, and must not read as Spotify's. */
    private val spotifyTookFocus = MusicControlState(
        yours = YourMusicState(title = "Overworld Theme", isPlaying = false, pausedReason = MusicPauseReason.OTHER_APP, format = mp3),
        other = OtherPlayerSnapshot(title = "Hotel California", artist = "Eagles", appLabel = "Spotify", artwork = null, isPlaying = true),
        otherAccess = true,
        lastPlayed = MusicSource.OTHER_PLAYER,
    )
    private val spotifyPlaying = MusicControlState(
        other = OtherPlayerSnapshot(title = "Hotel California", artist = "Eagles", appLabel = "Spotify", artwork = null, isPlaying = true),
        otherAccess = true,
        lastPlayed = MusicSource.OTHER_PLAYER,
    )
    private val noAccess = MusicControlState(otherAccess = false)

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun save(frame: Bitmap, name: String) {
        val dir = File("build/test-captures/audiofix").apply { if (!isDirectory) mkdirs() }
        File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(
        name: String,
        fontScale: Float,
        focused: EqualizerEditorControl?,
        settings: EqualizerSettings = rock,
        systemEq: SystemEqualizerState = working,
        output: MediaOutput? = null,
        music: MusicControlState? = null,
    ) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        val infoLines = if (output != null || music != null) equalizerInfoLines(output, music ?: MusicControlState(otherAccess = true), settings, systemEq) else emptyList()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(rootDensity, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp)) {
                        EqualizerEditorOverlay(
                            settings = settings,
                            focused = focused,
                            authority = null,
                            onToggle = {},
                            onPresetStep = {},
                            onGainRequested = { _, _ -> },
                            onRequestFocus = {},
                            onDismiss = {},
                            systemEq = systemEq,
                            infoLines = infoLines,
                        )
                    }
                }
            }
        }
        advance(1_500)
        val bitmap = frame()
        assertTrue("a frame was drawn", bitmap.width > 0 && bitmap.height > 0)
        save(bitmap, name)
        if (infoLines.isNotEmpty()) {
            // The card is held inside the display (the controls' window takes what the header leaves), and the controls window is not squeezed to nothing.
            val card = compose.onNodeWithTag(EqualizerEditorCardTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val window = compose.onNodeWithTag(EqualizerEditorViewportTestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            println("EQRENDER $name: card ${card.top.value}..${card.bottom.value} dp, controls window ${window.top.value}..${window.bottom.value} dp (${(window.bottom - window.top).value} dp tall)")
            assertTrue("$name: the card is inside the 468.3 dp display", card.top.value >= 0f && card.bottom.value <= 468.3f + 0.5f)
            assertTrue("$name: the controls window keeps room for the switch's row at least (${(window.bottom - window.top).value} dp)", (window.bottom - window.top).value >= 100f)
        }
    }

    // ---- the foot: the footer's etched keycap and its word ----

    @Test
    fun footReset_100() = render("foot-reset-1.0x.png", 1.0f, EqualizerEditorControl.BASS)

    @Test
    fun footReset_130() = render("foot-reset-1.3x.png", 1.3f, EqualizerEditorControl.BASS)

    @Test
    fun footRetry_100() = render("foot-retry-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, systemEq = refused)

    @Test
    fun footRetry_130() = render("foot-retry-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, systemEq = refused)

    // ---- the header: natural-case data lines under the chrome heading ----

    @Test
    fun headerCasing_100() = render("header-casing-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, music = mimirMusicPlaying)

    @Test
    fun headerCasing_130() = render("header-casing-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, music = mimirMusicPlaying)

    // ---- the information block: speaker + Mimir's own music, the engine holding the mix ----

    @Test
    fun speakerMimirMusic_100() = render("info-speaker-mimir-music-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, output = speaker, music = mimirMusicPlaying)

    @Test
    fun speakerMimirMusic_130() = render("info-speaker-mimir-music-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, output = speaker, music = mimirMusicPlaying)

    // ---- R1: Spotify took focus, Your music is paused: the MP3's format must not read as Spotify's ----

    @Test
    fun r1SpotifyWhileYoursPaused_100() = render("r1-spotify-yours-paused-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, output = speaker, music = spotifyTookFocus)

    @Test
    fun r1SpotifyWhileYoursPaused_130() = render("r1-spotify-yours-paused-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, output = speaker, music = spotifyTookFocus)

    // ---- R2: the theme bed is the source and sounds: the header names it, and never says nothing is playing ----

    @Test
    fun r2ThemeBedPlaying_100() = render("r2-theme-bed-playing-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, output = speaker, music = themeBedPlaying)

    @Test
    fun r2ThemeBedPlaying_130() = render("r2-theme-bed-playing-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, output = speaker, music = themeBedPlaying)

    // ---- Bluetooth + another app playing (no format line: none is invented for it) ----

    @Test
    fun bluetoothOtherApp_100() = render("info-bluetooth-other-app-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, output = buds, music = spotifyPlaying)

    @Test
    fun bluetoothOtherApp_130() = render("info-bluetooth-other-app-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, output = buds, music = spotifyPlaying)

    // ---- notification access off: one honest line ----

    @Test
    fun noNotificationAccess_100() = render("info-no-notification-access-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, output = speaker, music = noAccess)

    @Test
    fun noNotificationAccess_130() = render("info-no-notification-access-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, output = speaker, music = noAccess)

    // ---- which Equalizer is active: held by another app, refused by Android, and off ----

    @Test
    fun notActiveTaken_100() = render("info-eq-not-active-taken-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, systemEq = taken, output = speaker, music = mimirMusicPlaying)

    @Test
    fun notActiveTaken_130() = render("info-eq-not-active-taken-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, systemEq = taken, output = speaker, music = mimirMusicPlaying)

    @Test
    fun notActiveRefused_100() = render("info-eq-not-active-refused-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, systemEq = refused, output = buds, music = spotifyPlaying)

    @Test
    fun notActiveRefused_130() = render("info-eq-not-active-refused-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, systemEq = refused, output = buds, music = spotifyPlaying)

    @Test
    fun equalizerOff_100() = render("info-eq-off-1.0x.png", 1.0f, EqualizerEditorControl.TOGGLE, settings = EqualizerSettings(), systemEq = SystemEqualizerState(), output = speaker, music = mimirMusicPlaying)

    @Test
    fun equalizerOff_130() = render("info-eq-off-1.3x.png", 1.3f, EqualizerEditorControl.TOGGLE, settings = EqualizerSettings(), systemEq = SystemEqualizerState(), output = speaker, music = mimirMusicPlaying)
}
