package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduces the exact composition shape HomeCustomEnrichPicker.kt:1867-1890 wires (AnimatedVisibility ->
 * rememberMimirKeyboardHost -> HomeCustomEnrichKeyboardHostHolder -> MimirKeyboard) — real Compose
 * composition under Robolectric, not the pure-Kotlin MimirKeyboardFocusBinding unit tests in core-input,
 * which manually drive register/reconcile/activate in a hand-picked order and can't catch a composition
 * ordering bug.
 *
 * Both tests here PASS: decorateKey's activator registration reliably survives to when CONFIRM dispatches,
 * including across the shift-toggle-triggered reconcileFocus race the rename overlay's dispatch-layer
 * bypass exists to avoid (MimirAppInputDispatch.kt:134-141, GameTitleEditOverlay.kt:188). That rules OUT
 * the shared binding/composition wiring as the device defect's cause for Customize Media specifically —
 * see HomeCustomEnrichKeyboardCoordinatorReconcileTest / reconcileCustomEnrichPickerForWrite in
 * HomeCustomEnrichPicker.kt for where the real break was: the caller that applies the reducer's result,
 * not the activator map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeCustomEnrichKeyboardActivationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun press(sessionId: String, authority: DefaultFocusAuthority, id: String): Boolean {
        authority.requestFocus(mimirKeyboardFocusKey(id))
        val host = requireNotNull(HomeCustomEnrichKeyboardHostHolder.hostFor(sessionId)) {
            "keyboard host was never published for this session"
        }
        return dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = host.binding,
            onDismiss = {},
        )
    }

    @Test
    fun confirmOnFocusedCharacterKeyTypesTheCharacter() {
        val authority = DefaultFocusAuthority()
        val sessionId = "activation-session"
        var draft by mutableStateOf("")
        var keyboardOpen by mutableStateOf(false)

        compose.setContent {
            MimirTheme {
                AnimatedVisibility(visible = keyboardOpen) {
                    val keyboardHost = rememberMimirKeyboardHost(authority, keyboardOpen)
                    SideEffect {
                        HomeCustomEnrichKeyboardHostHolder.publish(
                            sessionId,
                            keyboardHost.takeIf { keyboardOpen },
                        )
                    }
                    DisposableEffect(sessionId) {
                        onDispose { HomeCustomEnrichKeyboardHostHolder.clear(sessionId) }
                    }
                    MimirKeyboard(
                        value = draft,
                        onValueChange = { draft = it },
                        onDone = {},
                        controller = keyboardHost?.controller,
                        keyDecorator = keyboardHost?.keyDecorator,
                        targetLabel = "Search",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()

        // Real usage: the picker mounts with the keyboard CLOSED; the user taps the search field and
        // `state.keyboardOpen` flips true, which is what actually drives AnimatedVisibility's enter
        // transition and (re)creates the keyboard host — matching HomeCustomEnrichPicker.kt:1867-1876.
        keyboardOpen = true
        compose.waitForIdle()

        val handled = try {
            press(sessionId, authority, "char/n")
        } finally {
            HomeCustomEnrichKeyboardHostHolder.clear(sessionId)
        }

        assertTrue(
            "CONFIRM on focused char/n must be handled (activateFocused() must find an activator)",
            handled,
        )
        // Shift defaults on for an empty draft (see keyboardRows(shiftOn = value.text.isEmpty())), so
        // the first character types uppercase; the point under test is that IT TYPES AT ALL.
        assertEquals("N", draft)
    }

    /**
     * The FIRST keypress above lands while shiftOn=true (empty draft). Typing a letter flips `shiftOn`
     * false via `onShiftOffAfterChar` (MimirKeyboard.kt:258-260), which recomputes `keyRows`
     * (remember(shiftOn, symbolsOn), MimirKeyboard.kt:185) and restarts the
     * `LaunchedEffect(controller, keyRows)` that calls `reconcileFocus` a frame later
     * (MimirKeyboard.kt:190-194). This presses a SECOND character key after that shift flip, to check
     * whether the shift-triggered reconcile drops the activator — the exact hazard the rename overlay's
     * dispatch-layer bypass comment describes (reconcileFocus pruning on shift/symbols toggle). It doesn't:
     * character key ids are shift-invariant (`MimirKeyboardKeyIds.character` always lowercases), so a
     * shift toggle changes labels, not ids, and reconcileFocus's visibleKeyIds set is unaffected.
     */
    @Test
    fun confirmOnSecondCharacterKeyAfterShiftTogglesStillTypes() {
        val authority = DefaultFocusAuthority()
        val sessionId = "activation-session-2"
        var draft by mutableStateOf("")
        var keyboardOpen by mutableStateOf(false)

        compose.setContent {
            MimirTheme {
                AnimatedVisibility(visible = keyboardOpen) {
                    val keyboardHost = rememberMimirKeyboardHost(authority, keyboardOpen)
                    SideEffect {
                        HomeCustomEnrichKeyboardHostHolder.publish(
                            sessionId,
                            keyboardHost.takeIf { keyboardOpen },
                        )
                    }
                    DisposableEffect(sessionId) {
                        onDispose { HomeCustomEnrichKeyboardHostHolder.clear(sessionId) }
                    }
                    MimirKeyboard(
                        value = draft,
                        onValueChange = { draft = it },
                        onDone = {},
                        controller = keyboardHost?.controller,
                        keyDecorator = keyboardHost?.keyDecorator,
                        targetLabel = "Search",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
        keyboardOpen = true
        compose.waitForIdle()

        val firstHandled = press(sessionId, authority, "char/n")
        // Let the shift-toggle-triggered LaunchedEffect(controller, keyRows) restart and its
        // withFrameNanos{} + reconcileFocus(visibleKeyIds) actually run before the next press.
        compose.waitForIdle()

        val secondHandled = try {
            press(sessionId, authority, "char/o")
        } finally {
            HomeCustomEnrichKeyboardHostHolder.clear(sessionId)
        }

        assertTrue("first CONFIRM (char/n, shift on) must be handled", firstHandled)
        assertTrue(
            "second CONFIRM (char/o, AFTER the shift-off reconcile) must still be handled — " +
                "if this is false, reconcileFocus's shift/symbols-toggle prune is dropping a live activator",
            secondHandled,
        )
        assertEquals("No", draft)
    }
}
