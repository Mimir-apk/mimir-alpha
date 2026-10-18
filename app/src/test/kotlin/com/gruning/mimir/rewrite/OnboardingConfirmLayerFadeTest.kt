package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-33 (audit 2026-10-14, O-13): the profile step's "Continue without a name?" confirm mounted at full alpha with no fade while the
 * keyboard layer beside it used a sheet. It is a sheet now. Over the real layer and the real focus authority: it is absent while
 * closed, it fades in, it is still drawn mid-leave (not cut), and while it leaves it is inert: its focus scope is popped in the frame
 * `open` goes false, not 180 ms later, and its pills take no touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingConfirmLayerFadeTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = mutableStateOf(false)
    private val authority = DefaultFocusAuthority()
    private var keepEditing = 0
    private var continues = 0

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                OnboardingConfirmLayer(
                    open = open.value,
                    authority = authority,
                    focusIntent = OnboardingFocusIntentState(),
                    confirmEpoch = 0,
                    palette = LocalMimirPalette.current,
                    onKeepEditing = { keepEditing++ },
                    onContinue = { continues++ },
                )
            }
        }
        step(0L)
    }

    private fun titleShown() = compose.shows("Continue without a name?", exact = false)

    @Test
    fun `the confirm fades in and out as a sheet, inert and off the pad while it leaves`() {
        val before = authority.state.value.scope
        mount()
        assertFalse("the confirm is absent", titleShown())
        assertEquals("a closed layer holds no scope", before, authority.state.value.scope)

        Snapshot.withMutableSnapshot { open.value = true }
        step(32L)
        assertTrue("the confirm is drawn", titleShown())
        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertEquals("while open it owns the pad", OnboardingConfirmScope, authority.state.value.scope)
        compose.labelled("Keep editing")[0].performTouchInput { down(center); up() }
        step(0L)
        assertEquals("an open confirm answers a touch", 1, keepEditing)

        Snapshot.withMutableSnapshot { open.value = false }
        step(16L)
        assertTrue("the confirm is drawn", titleShown())
        assertNotEquals(
            "the scope is popped in the frame the layer closes, not when its fade ends",
            OnboardingConfirmScope,
            authority.state.value.scope,
        )
        step(74L)
        assertTrue("the confirm is drawn", titleShown())
        compose.labelled("Continue")[0].performTouchInput { down(center); up() }
        step(0L)
        assertEquals("a leaving confirm takes no touch", 0, continues)

        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertFalse("the confirm is absent", titleShown())
    }
}
