package com.gruning.mimir.rewrite

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition test for advancing from a completed username field to the password field. Reproduces ArtworkConnectOverlay.kt's real composition shape: MimirArtworkOverlayHost
 * mounts ONE `rememberMimirKeyboardHost(active = keyboardActive)` for the WHOLE connect flow, where
 * `keyboardActive` is true across BOTH `EnterId` and `EnterPassword` — so the host/controller/binding
 * instance is the SAME object across the state transition, but `ArtworkConnectOverlay`'s `when(state)`
 * mounts a structurally DIFFERENT composable subtree for each step (different branch), so the OLD
 * `MimirKeyboard`'s `DisposableEffect(controller)` must dispose (calling `binding.exit()`, which POPS the
 * keyboard scope) while the NEW step's `MimirKeyboard` mounts fresh (calling `binding.enter()`, which
 * PUSHES the scope back and requests focus on Done). This test asks Compose directly, rather than
 * asserting an ordering guess: after Done on the username step, is the FocusAuthority still (or again) in
 * `MimirKeyboardFocusScope`, with a real activator registered for the focused key on the password step?
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArtworkConnectUsernameToPasswordFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completingUsernameLeavesKeyboardScopeActiveAndOperableOnPasswordStep() {
        val authority = DefaultFocusAuthority()
        var connectUi: ArtworkConnectUi = ArtworkConnectUi.EnterId
        var password: String? = null
        var host: MimirKeyboardHost? = null

        compose.setContent {
            MimirTheme {
                val keyboardActive = connectUi is ArtworkConnectUi.EnterId ||
                    connectUi is ArtworkConnectUi.EnterPassword
                val keyboardHost = rememberMimirKeyboardHost(
                    authority = authority,
                    active = keyboardActive,
                    owner = MimirOverlayKind.ARTWORK_CONNECT,
                )
                SideEffect { host = keyboardHost }
                DisposableEffect(Unit) {
                    onDispose { host = null }
                }
                ArtworkConnectOverlay(
                    state = connectUi,
                    onIdEntered = { id -> connectUi = ArtworkConnectUi.EnterPassword(id) },
                    onPasswordEntered = { pw -> password = pw },
                    onClose = {},
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                )
            }
        }
        compose.waitForIdle()

        // Drive the username step to completion exactly like a controller would: CONFIRM on the
        // focused Done key. Initial focus on keyboard entry is action/done (MimirKeyboardInitialKeyId).
        authority.requestFocus(mimirKeyboardFocusKey("action/done"))
        val doneHandled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = requireNotNull(host).binding,
            onDismiss = {},
        )
        assertTrue("CONFIRM on username step's Done must be handled", doneHandled)
        compose.waitForIdle()

        assertTrue(
            "state must have advanced to EnterPassword",
            connectUi is ArtworkConnectUi.EnterPassword,
        )
        assertEquals(
            "FocusAuthority must still be in the keyboard scope on the password step " +
                "(if this is Root/something else, the password keyboard is D-pad-dead until the player " +
                "manually re-enters it)",
            MimirKeyboardFocusScope,
            authority.state.value.scope,
        )

        // The real test: is the password step's keyboard actually operable by controller right now,
        // with no extra player action beyond what Done on the username step already did?
        val passwordHost = requireNotNull(host)
        val confirmOnPasswordStep = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = passwordHost.binding,
            onDismiss = {},
        )
        assertTrue(
            "CONFIRM must be handled on the password step immediately after the username " +
                "transition (activateFocused() must find a registered activator)",
            confirmOnPasswordStep,
        )
    }
}
