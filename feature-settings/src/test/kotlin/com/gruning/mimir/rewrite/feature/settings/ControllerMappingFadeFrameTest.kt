package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Audit 2026-10-14, RC-50 (S-40a, S-40b), the Controller mapping overlay**, on a paused clock.
 *
 * S-40a: when a capture ended the "listening" card faded out for 180 ms with the action-name line blanked (`pendingActionName.orEmpty()`), a line that went empty while the card
 * was still on screen. The last name is held through the fade.
 *
 * S-40b: the exit confirmation's scope was pushed from an effect, a frame late, and its request ran after another frame, while the sheet was already visible. The scope opens in
 * the command's own frame now ([openControllerMappingExitScope], which the coordinator's close calls) with Apply planted as its landing; the overlay's effect is the idempotent net.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerMappingFadeFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()
    private var pending by mutableStateOf<String?>(null)
    private var exitVisible by mutableStateOf(false)

    private fun mount() {
        compose.setContent {
            MimirTheme {
                ControllerMappingOverlay(
                    authority = authority,
                    rows = listOf(ControllerMappingRow("first", "NAV_UP", "Up", "DPAD UP")),
                    status = null,
                    pendingActionName = pending,
                    exitConfirmationVisible = exitVisible,
                    onBeginCapture = {},
                    onCancelCapture = {},
                    onReset = {},
                    onApply = {},
                    onDiscard = {},
                    onContinueEditing = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the capture card keeps the action name through its fade-out`() {
        mount()
        write { pending = "NAV_UP" }
        compose.waitForIdle()
        assertTrue("the card names the action it is listening for", shown("NAV_UP"))

        compose.mainClock.autoAdvance = false
        write { pending = null }
        val namedOnFrame = BooleanArray(6)
        for (frame in namedOnFrame.indices) {
            compose.mainClock.advanceTimeBy(16)
            namedOnFrame[frame] = shown("NAV_UP")
        }
        assertTrue(
            "the name stays on every frame the card is on screen (${namedOnFrame.joinToString()}), not blank from the first",
            namedOnFrame.all { it },
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("and the card is gone once it has faded", shown("NAV_UP"))
    }

    @Test
    fun `the exit scope opens in the command frame with Apply as its landing`() {
        mount()
        authority.pushScope(ControllerMappingScope)
        openControllerMappingExitScope(authority)
        assertEquals("the scope is the active one in the very frame of the command", ControllerMappingExitScope, authority.state.value.scope)
        assertEquals(
            "Apply is planted as the landing, so the first row to register is not what seeds",
            FocusKey(ControllerMappingExitApplyRowId),
            authority.rememberedFocusIn(ControllerMappingExitScope),
        )
        openControllerMappingExitScope(authority)
        assertEquals("asking twice is one scope", ControllerMappingExitScope, authority.state.value.scope)
        authority.popScope()
        assertEquals("one pop returns to the mapper", ControllerMappingScope, authority.state.value.scope)

        // The sheet then shows and its rows register: Apply takes the key, with no request after a frame.
        openControllerMappingExitScope(authority)
        write { exitVisible = true }
        compose.waitForIdle()
        assertEquals(FocusKey(ControllerMappingExitApplyRowId), authority.state.value.focused)
    }

    @Test
    fun `the overlay's net opens and closes the exit scope for a caller that did not`() {
        mount()
        authority.pushScope(ControllerMappingScope)
        write { exitVisible = true }
        compose.waitForIdle()
        assertEquals(ControllerMappingExitScope, authority.state.value.scope)
        assertEquals(FocusKey(ControllerMappingExitApplyRowId), authority.state.value.focused)
        write { exitVisible = false }
        compose.waitForIdle()
        assertEquals(ControllerMappingScope, authority.state.value.scope)
    }
}
