package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controller bindings (L2 = caps/shift, R2 = cycle character set, wrapping alphabet -> numbers ->
 * symbols -> alphabet), pinned end-to-end through the REAL production composition — `MimirKeyboard`
 * mounted with a real `rememberMimirKeyboardHost`, driven the same way the app's input-dispatch layer
 * drives it (`dispatchMimirKeyboardInput` with `ControllerAction.TRIGGER_LEFT`/`TRIGGER_RIGHT`) — not the
 * pure-Kotlin `MimirKeyboardFocusBinding` unit tests in core-input, which stub the registered callbacks
 * rather than exercise `MimirKeyboardSurface`'s own `LaunchedEffect(controller)` registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirKeyboardShoulderActionsIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun l2TogglesCaseOfTheNextTypedLetter() {
        val authority = DefaultFocusAuthority()
        var draft by mutableStateOf("")
        var host: MimirKeyboardHost? = null

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

        fun triggerLeft(): Boolean {
            val handled = dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = ControllerAction.TRIGGER_LEFT,
                binding = requireNotNull(host).binding,
                onDismiss = {},
            )
            compose.waitForIdle()
            return handled
        }

        // Fresh empty draft starts shiftOn = true (MimirKeyboard.kt: `value.text.isEmpty()`). Toggle
        // three times to land deliberately on shiftOn = false (two toggles would return to the same
        // default-true state as a no-toggle baseline, which wouldn't prove L2 did anything).
        assertTrue("L2 (TRIGGER_LEFT) must be handled once the keyboard is mounted", triggerLeft())
        assertTrue(triggerLeft())
        assertTrue(triggerLeft())

        authority.requestFocus(mimirKeyboardFocusKey("char/n"))
        val typed = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = requireNotNull(host).binding,
            onDismiss = {},
        )
        assertTrue("CONFIRM on char/n must be handled", typed)
        compose.waitForIdle()
        assertEquals("three L2 presses from the fresh-empty default (shiftOn=true) must land on lowercase", "n", draft)
    }

    @Test
    fun r2CyclesAlphaToNumbersToSymbolsAndWrapsBackToAlpha() {
        val authority = DefaultFocusAuthority()
        var draft by mutableStateOf("")
        var host: MimirKeyboardHost? = null

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

        fun triggerRight(): Boolean {
            val handled = dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = ControllerAction.TRIGGER_RIGHT,
                binding = requireNotNull(host).binding,
                onDismiss = {},
            )
            compose.waitForIdle()
            return handled
        }

        // Fresh empty draft starts shiftOn = true (MimirKeyboard.kt: `value.text.isEmpty()`), and this
        // test never types or touches L2, so ALPHA always renders the letter key UPPERCASE ("Q") — the
        // key id (`char/q`) is shift-invariant, but its rendered label/contentDescription is not.
        // ALPHA (initial): a letter key exists, "@" (SYMBOLS-only) does not.
        compose.onNodeWithText("Q").assertExists()
        compose.onNodeWithText("@").assertDoesNotExist()

        assertTrue("R2 (TRIGGER_RIGHT) must be handled once the keyboard is mounted", triggerRight())
        // NUMBERS: no letters, no "@".
        compose.onNodeWithText("Q").assertDoesNotExist()
        compose.onNodeWithText("@").assertDoesNotExist()

        triggerRight()
        // SYMBOLS: "@" now present, still no letters.
        compose.onNodeWithText("Q").assertDoesNotExist()
        compose.onNodeWithText("@").assertExists()

        triggerRight()
        // Wrap back to ALPHA.
        compose.onNodeWithText("Q").assertExists()
        compose.onNodeWithText("@").assertDoesNotExist()
    }
}
