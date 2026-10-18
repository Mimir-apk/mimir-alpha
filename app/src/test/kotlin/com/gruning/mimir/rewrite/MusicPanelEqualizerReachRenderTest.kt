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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Music panel's Equalizer row on the Thor's Main (832.8 x 468.3 dp at 369 dpi), at font 1.0 and 1.3: its one line says "Shapes all sound on this device."
 * while the global engine has the mix, and "Music on this device only right now." when another app holds the system equalizer or Android refused it
 * (the same words as the Settings row and the editor's header). The PNGs land in `build/test-captures/audiofix/`. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPanelEqualizerReachRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rootDensity = 369f / 160f

    private val rock = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK)

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

    private fun render(name: String, fontScale: Float, status: SystemEqStatus, expectedLine: String) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        val control = MusicControlState(
            yours = YourMusicState(title = "Overworld Theme", isPlaying = true, canPrevious = true, canNext = true),
            otherAccess = true,
        )
        val model = musicPanelModel(
            control = control,
            stopped = false,
            musicVolume = 0.6f,
            mediaVolume = 0.8f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
            equalizer = rock,
            systemEq = SystemEqualizerState(wanted = true, status = status),
            equalizerShown = true,
        )
        assertEquals(expectedLine, model.equalizerSupporting)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(rootDensity, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(832.8.dp, 468.3.dp)) {
                        MusicPanelOverlay(
                            model = model,
                            focused = MusicPanelControl.EQUALIZER,
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
        }
        advance(1_500)
        val bitmap = frame()
        assertTrue("a frame was drawn", bitmap.width > 0 && bitmap.height > 0)
        save(bitmap, name)
        val row = compose.onNodeWithTag(MusicPanelEqualizerRowTestTag, useUnmergedTree = true).fetchSemanticsNode()
        println("AUDIOFIX $name: equalizer row ${row.boundsInWindow}")
        val drawn = compose.onAllNodes(hasText(expectedLine) or hasContentDescription(expectedLine), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$name: the row's line \"$expectedLine\" is drawn", drawn.isNotEmpty())
    }

    @Test
    fun allSound_100() = render("panel-eq-all-sound-1.0x.png", 1.0f, SystemEqStatus.ON, "Shapes all sound on this device.")

    @Test
    fun allSound_130() = render("panel-eq-all-sound-1.3x.png", 1.3f, SystemEqStatus.ON, "Shapes all sound on this device.")

    @Test
    fun mimirOnlyTaken_100() = render("panel-eq-music-only-taken-1.0x.png", 1.0f, SystemEqStatus.NO_CONTROL, "Music on this device only right now.")

    @Test
    fun mimirOnlyTaken_130() = render("panel-eq-music-only-taken-1.3x.png", 1.3f, SystemEqStatus.NO_CONTROL, "Music on this device only right now.")

    @Test
    fun mimirOnlyRefused_130() = render("panel-eq-music-only-refused-1.3x.png", 1.3f, SystemEqStatus.UNAVAILABLE, "Music on this device only right now.")
}
