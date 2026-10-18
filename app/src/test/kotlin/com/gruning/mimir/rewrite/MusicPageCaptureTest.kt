package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.GameMusicDownloadReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowId
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowIds
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourScope
import com.gruning.mimir.rewrite.feature.settings.MusicEqualizerRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetCancelRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetScope
import com.gruning.mimir.rewrite.feature.settings.MusicFolderReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicFolderRowId
import com.gruning.mimir.rewrite.feature.settings.MusicGameDownloadRowId
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundRowId
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundValue
import com.gruning.mimir.rewrite.feature.settings.MusicNowPlayingReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicOrderValue
import com.gruning.mimir.rewrite.feature.settings.MusicPlayerEnabledRowId
import com.gruning.mimir.rewrite.feature.settings.MusicReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicRequestsTodayReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicSourcePickerScope
import com.gruning.mimir.rewrite.feature.settings.MusicSourceRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSourceValue
import com.gruning.mimir.rewrite.feature.settings.MusicVolumeRowId
import com.gruning.mimir.rewrite.feature.settings.SettingsCategory
import com.gruning.mimir.rewrite.feature.settings.SettingsHome
import com.gruning.mimir.rewrite.feature.settings.SettingsRailScope
import com.gruning.mimir.rewrite.feature.settings.detailKey
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import com.gruning.mimir.rewrite.feature.settings.settingsRailFocusKey
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Settings > Music page looks like (update), drawn the way this repo rasterises (the view hierarchy into a software bitmap; API 30, the level at which the
 * platform cannot blur, because a software canvas cannot draw the glass sheet's RenderNode layers) at Main size, 1920 x 1080 px at density 369, and at a type scale of
 * 1.0 and 1.3. The PNGs land in `app/build/music273/` and are copied to `build/test-captures/musicpage273/`. Pixel values are the JVM's; the pixels on the Thor are device-owed.
 * One mount per test, so every shot is its own test at 1.0x and at 1.3x.
 */
abstract class MusicCaptureSupport {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    protected val authority = DefaultFocusAuthority()

    /** Hears the laid-out text of every Mimir text line on the page (spoken text and layout), when a test sets it before [mount]. */
    protected var textObserver: ((String, TextLayoutResult) -> Unit)? = null

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    protected fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    protected fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/music273").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/musicpage273").apply { mkdirs() }
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("MUSIC273 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    protected fun scaleName(scale: Float): String = String.format(java.util.Locale.ROOT, "%.1fx", scale)

    protected fun playingModel(
        musicEnabled: Boolean = true,
        launch: MusicLaunchSoundValue = MusicLaunchSoundValue.HALF,
        equalizerPreset: String? = null,
    ) = MusicReadModel(
        musicEnabled = musicEnabled,
        playing = musicEnabled,
        nowPlaying = if (musicEnabled) {
            MusicNowPlayingReadModel("Overworld Theme", "Music on this device", singleTrack = false, formatLine = "FLAC · 24/96 · 2,304 kbps")
        } else {
            null
        },
        source = MusicSourceValue.CUSTOM,
        customSourceAvailable = true,
        order = MusicOrderValue.SHUFFLE,
        volumePercent = 70,
        songTitlesEnabled = true,
        folder = MusicFolderReadModel(
            friendlyPath = "SD card › Music",
            trackCount = 1234,
            lastScanLabel = "Scanned 2 h ago",
            scanning = false,
            error = null,
        ),
        launchSoundEnabled = launch.enabled,
        launchSoundVolumePercent = if (launch.enabled) launch.percent else 100,
        gameMusic = GameMusicDownloadReadModel.Idle(have = 7, total = 12, requestsToday = MusicRequestsTodayReadModel(40, "12 min ago")),
        playOnHighlight = false,
        outputValue = if (musicEnabled) "16-bit · 48 kHz" else null,
        fadeBetweenTracks = true,
        equalizerEnabled = equalizerPreset != null,
        equalizerPresetLabel = equalizerPreset ?: "Flat",
    )

    /** Settings at [widthDp] x [heightDp], landing on [focus] (in [focusScope]) and drawn at [scale]. [shell] wraps it in the Main shell with its footer legend. */
    protected fun mount(
        widthDp: Float,
        heightDp: Float,
        scale: Float,
        music: MusicReadModel,
        focus: FocusKey,
        focusScope: FocusScope,
        shell: Boolean,
        overlay: (@Composable () -> Unit)? = null,
    ) {
        compose.mainClock.autoAdvance = false
        setAnimatorScale(0f)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(Density369, scale),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
                LocalMimirTextLayoutObserver provides textObserver,
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        val settings: @Composable () -> Unit = {
                            SettingsHome(
                                authority = authority,
                                music = music,
                                initialFocusKey = focus,
                                initialFocusScope = focusScope,
                            )
                        }
                        if (shell) {
                            val focusState = authority.state.collectAsState()
                            AppShellScaffold(
                                activeTab = ShellTab.SETTINGS,
                                onSelectTab = {},
                                footerHints = { settingsFooterHints(focusState.value, music.folder != null) },
                            ) { settings() }
                        } else {
                            settings()
                        }
                        overlay?.invoke()
                    }
                }
            }
        }
        settle()
    }

    protected companion object {
        const val Density369 = 369f / 160f

        /** Main is 1920 x 1080 px at 369 dpi. */
        const val MainWidthDp = 1920f / Density369
        const val MainHeightDp = 1080f / Density369
    }
}

/** Main's true frame: the shell, the rail, the Music pane at 468 dp, and the footer legend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPageMainFrameCaptureTest : MusicCaptureSupport() {

    private fun frame(scale: Float, focus: FocusKey, scope: FocusScope, name: String, music: MusicReadModel = playingModel()) {
        mount(MainWidthDp, MainHeightDp, scale, music, focus, scope, shell = true)
        save("music273-main-$name-${scaleName(scale)}.png")
    }

    private fun folderFocused(scale: Float) = frame(scale, detailKey(MusicFolderRowId), DetailScope, "folder-focused-xlegend")

    private fun musicRowFocused(scale: Float) = frame(scale, detailKey(MusicPlayerEnabledRowId), DetailScope, "page-top-music-focused")

    private fun folderScanning(scale: Float) {
        val scanning = playingModel().let { it.copy(folder = it.folder!!.copy(scanning = true)) }
        frame(scale, detailKey(MusicFolderRowId), DetailScope, "folder-scanning", scanning)
    }

    private fun behaviourFlow(scale: Float) {
        mount(MainWidthDp, MainHeightDp, scale, playingModel(), detailKey(MusicBehaviourRowId), DetailScope, shell = true)
        save("music273-main-behaviour-row-focused-${scaleName(scale)}.png")
        compose.runOnIdle {
            authority.rememberFocusForScope(MusicBehaviourScope, detailKey(MusicBehaviourRowIds.first()))
            authority.pushScope(MusicBehaviourScope)
        }
        settle()
        assertEquals(detailKey(MusicBehaviourRowIds.first()), authority.state.value.focused)
        save("music273-main-behaviour-subpage-${scaleName(scale)}.png")
        compose.runOnIdle {
            repeat(2) { dispatchToSettings(authority, ControllerAction.NAV_DOWN, onRailOpen = {}, onRowActivate = {}, onExit = {}) }
        }
        settle()
        save("music273-main-behaviour-subpage-third-switch-${scaleName(scale)}.png")
        compose.runOnIdle {
            dispatchToSettings(authority, ControllerAction.CANCEL, onRailOpen = {}, onRowActivate = {}, onExit = { error("B must not leave Settings") })
        }
        settle()
        assertEquals("B returned to the Behaviour row", detailKey(MusicBehaviourRowId), authority.state.value.focused)
        assertEquals(DetailScope, authority.state.value.scope)
        save("music273-main-behaviour-back-on-row-${scaleName(scale)}.png")
    }

    private fun forgetConfirmation(scale: Float) {
        mount(MainWidthDp, MainHeightDp, scale, playingModel(), detailKey(MusicFolderRowId), DetailScope, shell = true)
        // The coordinator's entry for Y on the folder row: Cancel holds focus first, and the footer legend goes back to the shell's own.
        compose.runOnIdle {
            authority.rememberFocusForScope(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId))
            authority.pushScope(MusicFolderForgetScope)
        }
        settle()
        assertEquals(detailKey(MusicFolderForgetCancelRowId), authority.state.value.focused)
        save("music273-main-forget-confirmation-${scaleName(scale)}.png")
        compose.runOnIdle {
            dispatchToSettings(authority, ControllerAction.NAV_DOWN, onRailOpen = {}, onRowActivate = {}, onExit = {})
        }
        settle()
        save("music273-main-forget-confirmation-forget-row-${scaleName(scale)}.png")
    }

    private fun launchSoundChoice(scale: Float) {
        // The coordinator raises the open flag and pushes the scope in one go, and the overlay lands focus on the current step as it mounts: the same order here.
        val open = mutableStateOf(false)
        mount(
            MainWidthDp, MainHeightDp, scale, playingModel(), detailKey(MusicLaunchSoundRowId), DetailScope, shell = true,
            overlay = {
                if (open.value) {
                    MusicLaunchSoundPickerOverlay(
                        authority = authority,
                        current = MusicLaunchSoundValue.HALF,
                        onSelect = {},
                        onDismiss = {},
                    )
                }
            },
        )
        compose.runOnIdle {
            open.value = true
            authority.pushScope(MusicSourcePickerScope)
        }
        settle()
        save("music273-main-launchsound-choice-${scaleName(scale)}.png")
    }

    @Test fun folderFocusedXLegend_1_0x() = folderFocused(1.0f)

    @Test fun folderFocusedXLegend_1_3x() = folderFocused(1.3f)

    @Test fun musicRowFocused_1_0x() = musicRowFocused(1.0f)

    @Test fun musicRowFocused_1_3x() = musicRowFocused(1.3f)

    @Test fun folderScanning_1_0x() = folderScanning(1.0f)

    @Test fun folderScanning_1_3x() = folderScanning(1.3f)

    @Test fun behaviourFlow_1_0x() = behaviourFlow(1.0f)

    @Test fun behaviourFlow_1_3x() = behaviourFlow(1.3f)

    @Test fun forgetConfirmation_1_0x() = forgetConfirmation(1.0f)

    @Test fun forgetConfirmation_1_3x() = forgetConfirmation(1.3f)

    @Test fun launchSoundChoice_1_0x() = launchSoundChoice(1.0f)

    @Test fun launchSoundChoice_1_3x() = launchSoundChoice(1.3f)
}

/** The whole pages, in a window tall enough that nothing is below the fold: the rail beside the Music page, at Main width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h1100dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPageTallCaptureTest : MusicCaptureSupport() {

    private val tallHeightDp = 1100f

    private fun page(
        scale: Float,
        name: String,
        music: MusicReadModel,
        focus: FocusKey = settingsRailFocusKey(SettingsCategory.MUSIC),
        scope: FocusScope = SettingsRailScope,
    ) {
        mount(MainWidthDp, tallHeightDp, scale, music, focus, scope, shell = false)
        save("music273-tall-$name-${scaleName(scale)}.png")
    }

    private fun behaviourPage(scale: Float) {
        mount(MainWidthDp, tallHeightDp, scale, playingModel(), detailKey(MusicBehaviourRowId), DetailScope, shell = false)
        compose.runOnIdle {
            authority.rememberFocusForScope(MusicBehaviourScope, detailKey(MusicBehaviourRowIds.first()))
            authority.pushScope(MusicBehaviourScope)
        }
        settle()
        save("music273-tall-behaviour-subpage-${scaleName(scale)}.png")
    }

    private fun focused(scale: Float, name: String, row: String) = page(scale, name, playingModel(), detailKey(row), DetailScope)

    @Test fun pageMusicOn_1_0x() = page(1.0f, "page-music-on", playingModel(musicEnabled = true))

    @Test fun pageMusicOn_1_3x() = page(1.3f, "page-music-on", playingModel(musicEnabled = true))

    @Test fun pageMusicOnEqualizerOn_1_0x() = page(1.0f, "page-music-on-equalizer-rock", playingModel(equalizerPreset = "Rock"))

    @Test fun pageMusicOnEqualizerOn_1_3x() = page(1.3f, "page-music-on-equalizer-rock", playingModel(equalizerPreset = "Rock"))

    // The one Equalizer's row when the global engine is out of reach (another app holds it, or Android refused it): only music on this device is shaped.
    @Test fun pageMusicOnEqualizerMimirOnly_1_0x() = page(1.0f, "page-music-on-equalizer-mimir-only", playingModel(equalizerPreset = "Rock").copy(equalizerMimirMusicOnly = true))

    @Test fun pageMusicOnEqualizerMimirOnly_1_3x() = page(1.3f, "page-music-on-equalizer-mimir-only", playingModel(equalizerPreset = "Rock").copy(equalizerMimirMusicOnly = true))

    @Test fun pageMusicOff_1_0x() = page(1.0f, "page-music-off", playingModel(musicEnabled = false))

    @Test fun pageMusicOff_1_3x() = page(1.3f, "page-music-off", playingModel(musicEnabled = false))

    @Test fun behaviourSubpage_1_0x() = behaviourPage(1.0f)

    @Test fun behaviourSubpage_1_3x() = behaviourPage(1.3f)

    @Test fun playerSourceFocused_1_0x() = focused(1.0f, "player-source-focused", MusicSourceRowId)

    @Test fun playerSourceFocused_1_3x() = focused(1.3f, "player-source-focused", MusicSourceRowId)

    @Test fun playerVolumeFocused_1_0x() = focused(1.0f, "player-volume-focused", MusicVolumeRowId)

    @Test fun playerVolumeFocused_1_3x() = focused(1.3f, "player-volume-focused", MusicVolumeRowId)

    @Test fun playerEqualizerFocused_1_0x() = focused(1.0f, "player-equalizer-focused", MusicEqualizerRowId)

    @Test fun yourMusicFolderFocused_1_0x() = focused(1.0f, "yourmusic-folder-focused", MusicFolderRowId)

    @Test fun yourMusicFolderFocused_1_3x() = focused(1.3f, "yourmusic-folder-focused", MusicFolderRowId)

    @Test fun gameLaunchSoundFocused_1_0x() = focused(1.0f, "game-launchsound-focused", MusicLaunchSoundRowId)

    @Test fun gameLaunchSoundFocused_1_3x() = focused(1.3f, "game-launchsound-focused", MusicLaunchSoundRowId)

    @Test fun gameDownloadFocused_1_0x() = focused(1.0f, "game-download-focused", MusicGameDownloadRowId)

    @Test fun gameDownloadFocused_1_3x() = focused(1.3f, "game-download-focused", MusicGameDownloadRowId)
}
