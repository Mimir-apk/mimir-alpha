package com.gruning.mimir.rewrite

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerOverlay
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePickerScope
import com.gruning.mimir.rewrite.feature.settings.ThemePreviewGallery
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Thor drive 3 (combined45): the system's gesture handle, the white pill, sat over the footer hints on the full-screen pages that end on one ("B BACK" in Settings, the theme gallery's "A SELECT · B BACK", the Mimir-button
 * picker's "REMOVE · Y ADJUST"). Mimir is immersive-sticky, so the platform tells the app no bottom inset (`dumpsys window`: the navigation bar and bottom mandatory-gesture sources are empty and not visible); the handle is a
 * separate system window, 55 px (24 dp at 369 dpi) tall. `mimirGestureHandleClearance` is the larger of the insets the platform reports and, in gesture navigation, the handle's 24 dp strip, and these footers stand above it.
 *
 * The device is the Thor's Main: 832 x 468 dp at 369 dpi, `navigation_mode` 2 (gesture). Each footer's bottom edge is read off the composed page: with the setting at 2 it is above the handle's strip, with the setting at 0
 * (three-button, where the bar is hidden: the handle is not there) it is where it always was, in that strip, so a device without the handle lays out as it did and this test cannot pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureHandleClearanceFooterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    /** The handle's strip, in px: 24 dp (the Thor's 55 px at 369 dpi). */
    private val handlePx: Float get() = 24f * compose.activity.resources.displayMetrics.density

    private fun navigationMode(mode: Int) {
        Settings.Secure.putInt(compose.activity.contentResolver, "navigation_mode", mode)
    }

    private fun frames(count: Int = 8) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private val rootHeightPx: Float get() = compose.activity.window.decorView.height.toFloat()

    private fun assertAboveHandle(bottomPx: Float, where: String, clear: Boolean) {
        val strip = rootHeightPx - handlePx
        println("GESTURECLEARANCE $where: footer bottom ${bottomPx / compose.activity.resources.displayMetrics.density} dp, handle strip starts at ${strip / compose.activity.resources.displayMetrics.density} dp, clear=$clear")
        assertTrue("the handle's strip is a real one (${handlePx} px)", handlePx > 20f)
        if (clear) {
            assertTrue("$where: the footer ends above the handle's strip ($bottomPx <= $strip)", bottomPx <= strip + 0.5f)
        } else {
            assertTrue("$where: with no handle the footer is where it was, in the strip ($bottomPx > $strip)", bottomPx > strip)
        }
    }

    // ---- Settings (the app shell's legend) ------------------------------------------------------------------------------------

    private fun shell(mode: Int, clear: Boolean) {
        navigationMode(mode)
        compose.setContent {
            MimirTheme {
                CompositionLocalProvider(LocalMimirViewportProfile provides mimirViewportProfile(832f, 468f)) {
                    AppShellScaffold(activeTab = ShellTab.SETTINGS, onSelectTab = {}) {}
                }
            }
        }
        frames()
        val back = compose.onAllNodes(hasContentDescription("Back"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertAboveHandle(back.boundsInRoot.bottom, "Settings shell, mode $mode", clear)
    }

    @Test
    fun `Settings' B BACK legend stands above the gesture handle in gesture navigation`() = shell(mode = 2, clear = true)

    @Test
    fun `Settings' legend is where it was without the handle`() = shell(mode = 0, clear = false)

    // ---- the theme gallery ------------------------------------------------------------------------------------------------------

    private fun gallery(mode: Int, scale: Float, clear: Boolean) {
        navigationMode(mode)
        authority.pushScope(ThemePickerScope)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalMimirViewportProfile provides mimirViewportProfile(832f, 468f),
            ) {
                MimirTheme {
                    ThemePreviewGallery(
                        authority = authority,
                        scope = ThemePickerScope,
                        currentThemeId = MimirThemeId.DEFAULT,
                        title = "Choose your look",
                        subtitle = "Every screen takes on this palette.",
                        dismissHint = "Back",
                        onPick = {},
                        onDismiss = {},
                        presenceManagedExternally = true,
                    )
                }
            }
        }
        frames()
        val footer = compose.onAllNodes(hasContentDescription("back", substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().last()
        assertAboveHandle(footer.boundsInRoot.bottom, "Theme gallery, mode $mode, font $scale", clear)
    }

    @Test
    fun `the theme gallery's footer stands above the gesture handle at font 1 point 0`() = gallery(mode = 2, scale = 1f, clear = true)

    @Test
    fun `the theme gallery's footer stands above the gesture handle at font 1 point 3`() = gallery(mode = 2, scale = 1.3f, clear = true)

    @Test
    fun `the theme gallery is where it was without the handle`() = gallery(mode = 0, scale = 1f, clear = false)

    // ---- the Mimir-button picker -------------------------------------------------------------------------------------------------

    private fun mimirButton(mode: Int, scale: Float, clear: Boolean) {
        navigationMode(mode)
        authority.pushScope(MimirButtonPickerScope)
        var state by mutableStateOf(mimirButtonPickerInitialState(null, emptyList()))
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalMimirViewportProfile provides mimirViewportProfile(832f, 468f),
            ) {
                MimirTheme {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition -> state = transition.state },
                        presenceManagedExternally = true,
                        stage = { preview -> MimirButtonPickerLiveStage(preview) },
                        adjustCoin = { mark, adjustScale -> MimirButtonPickerAdjustCoin(mark, adjustScale) },
                    )
                }
            }
        }
        frames()
        val footer = compose.onAllNodes(hasContentDescription("A USE · B BACK"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertAboveHandle(footer.boundsInRoot.bottom, "Mimir-button picker, mode $mode, font $scale", clear)
        // The stage takes what the bands leave, so the room the handle needs comes out of it: it keeps well over the 100 dp its two coins and captions need.
        val stage = compose.onNode(hasTestTag("appearance-picker-stage"), useUnmergedTree = true).fetchSemanticsNode()
        val stageDp = stage.boundsInRoot.height / compose.activity.resources.displayMetrics.density
        println("GESTURECLEARANCE Mimir-button picker, mode $mode, font $scale: stage ${stageDp} dp")
        assertTrue("the stage still holds its coins and captions (${stageDp} dp)", stageDp >= 110f)
    }

    @Test
    fun `the Mimir-button picker's footer stands above the gesture handle at font 1 point 0`() = mimirButton(mode = 2, scale = 1f, clear = true)

    @Test
    fun `the Mimir-button picker's footer stands above the gesture handle at font 1 point 3`() = mimirButton(mode = 2, scale = 1.3f, clear = true)

    @Test
    fun `the Mimir-button picker is where it was without the handle`() = mimirButton(mode = 0, scale = 1f, clear = false)
}
