package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keyboard navigation follows visual rows: RIGHT from paste reaches the spacebar;
 * RIGHT from the final key wraps to the start of the same row.
 *
 * Pinned end-to-end through the REAL production composition — `MimirKeyboard` mounted with a real
 * `rememberMimirKeyboardHost` (so `MimirKeyboardSurface`'s own `LaunchedEffect(controller)` performs the
 * real `onRowLayoutChanged` registration against the REAL `LETTER_ROWS`/`NUMBER_ROWS` layouts), driven
 * the same way the app's input-dispatch layer drives it (`dispatchMimirKeyboardInput` with
 * `ControllerAction.NAV_LEFT`/`NAV_RIGHT`/`NAV_UP`/`NAV_DOWN`) — not the pure-Kotlin
 * `MimirKeyboardFocusBinding` unit tests in core-input, which use synthetic row layouts to isolate the
 * mechanism from the real layout data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirKeyboardRowNavigationIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mountKeyboard(): Pair<DefaultFocusAuthority, () -> MimirKeyboardHost?> {
        val authority = DefaultFocusAuthority()
        var host: MimirKeyboardHost? = null
        var draft by mutableStateOf("")
        compose.setContent {
            MimirTheme {
                val keyboardHost = rememberMimirKeyboardHost(authority, active = true)
                host = keyboardHost
                MimirKeyboard(
                    value = draft,
                    onValueChange = { draft = it },
                    onDone = {},
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                    targetLabel = "Test",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
        return authority to { host }
    }

    private fun dispatch(authority: DefaultFocusAuthority, host: MimirKeyboardHost?, action: ControllerAction): Boolean {
        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = action,
            binding = requireNotNull(host).binding,
            onDismiss = {},
        )
        compose.waitForIdle()
        return handled
    }

    @Test
    fun rightFromPasteLandsOnSpacebar_ownersExample_realAlphaLayout() {
        val (authority, host) = mountKeyboard()
        authority.requestFocus(mimirKeyboardFocusKey("action/paste"))

        assertTrue("RIGHT must be handled", dispatch(authority, host(), ControllerAction.NAV_RIGHT))
        assertEquals(
            "RIGHT from paste must land on spacebar, on the REAL alphabet layout",
            mimirKeyboardFocusKey("action/space"),
            authority.state.value.focused,
        )
    }

    @Test
    fun rightFromLastKeyOfARowWrapsToFirstKeyOfSameRow_realAlphaLayout() {
        // ALPHA's last row is [symbols, paste, space, done] (MimirKeyboard.kt LETTER_ROWS) — "done" is
        // the last key of that row, and of the whole grid.
        val (authority, host) = mountKeyboard()
        authority.requestFocus(mimirKeyboardFocusKey("action/done"))

        assertTrue(dispatch(authority, host(), ControllerAction.NAV_RIGHT))
        assertEquals(
            "RIGHT from the last key of the row must wrap to the FIRST key of that SAME row (symbols), not the grid start",
            mimirKeyboardFocusKey("action/symbols"),
            authority.state.value.focused,
        )
    }

    @Test
    fun leftFromFirstKeyWrapsToLastKeyOfSameRow_realAlphaLayout() {
        val (authority, host) = mountKeyboard()
        authority.requestFocus(mimirKeyboardFocusKey("action/symbols"))

        assertTrue(dispatch(authority, host(), ControllerAction.NAV_LEFT))
        assertEquals(
            "LEFT from the first key of the row must wrap to the LAST key of that SAME row (symmetry with RIGHT)",
            mimirKeyboardFocusKey("action/done"),
            authority.state.value.focused,
        )
    }

    @Test
    fun rowWrapAlsoWorksOnTheNumberRowsLayout() {
        // Row navigation must also work beyond the alphabet layout. NUMBER_ROWS'
        // last row is [paste, space, done] (MimirKeyboard.kt) -- a DIFFERENT shape (3 keys, no leading
        // "symbols" key) from ALPHA's 4-key last row, and reached only via R2 (controller binding), never touch.
        val (authority, host) = mountKeyboard()
        // R2 = TRIGGER_RIGHT cycles ALPHA -> NUMBERS -> SYMBOLS -> ALPHA; one press reaches NUMBERS.
        val cycled = dispatch(authority, host(), ControllerAction.TRIGGER_RIGHT)
        assertTrue("R2 must be handled to reach NUMBERS", cycled)

        authority.requestFocus(mimirKeyboardFocusKey("action/paste"))
        assertTrue(dispatch(authority, host(), ControllerAction.NAV_RIGHT))
        assertEquals(
            "RIGHT from paste on NUMBER_ROWS must also land on spacebar",
            mimirKeyboardFocusKey("action/space"),
            authority.state.value.focused,
        )

        authority.requestFocus(mimirKeyboardFocusKey("action/done"))
        assertTrue(dispatch(authority, host(), ControllerAction.NAV_RIGHT))
        assertEquals(
            "RIGHT from the last key (done) of NUMBER_ROWS' 3-key last row must wrap to the first key (paste) of that SAME row",
            mimirKeyboardFocusKey("action/paste"),
            authority.state.value.focused,
        )
    }

    @Test
    fun upAndDownStillMoveFocusAcrossRowsOfDifferingWidth() {
        // Moving between rows of different key counts must land on a valid key, and
        // UP/DOWN navigation must remain functional. This pins that UP/DOWN keep working end-to-end on the real composed
        // layout (unchanged geometric behavior, not a new mechanism) -- not a specific landing key, since
        // that is the pre-existing, untouched `DefaultFocusAuthority.moveFocus` pixel contest.
        val (authority, host) = mountKeyboard()
        // Row 3 (9 keys: shift,z,x,c,v,b,n,m,back) is wider than row 4 (4 keys: symbols,paste,space,done).
        authority.requestFocus(mimirKeyboardFocusKey("action/back"))
        val beforeDown = authority.state.value.focused

        assertTrue("DOWN from a 9-key row into a 4-key row must still be handled", dispatch(authority, host(), ControllerAction.NAV_DOWN))
        assertNotEquals("DOWN must actually move focus, not dead-end", beforeDown, authority.state.value.focused)
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)

        val beforeUp = authority.state.value.focused
        assertTrue("UP from the 4-key row back into the 9-key row must still be handled", dispatch(authority, host(), ControllerAction.NAV_UP))
        assertNotEquals("UP must actually move focus, not dead-end", beforeUp, authority.state.value.focused)
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)
    }
}
