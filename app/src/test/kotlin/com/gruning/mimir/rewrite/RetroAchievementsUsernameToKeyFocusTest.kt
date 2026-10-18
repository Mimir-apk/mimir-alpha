package com.gruning.mimir.rewrite

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsKeyEditOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Same GROUND-phase question as ArtworkConnectUsernameToPasswordFocusTest, for RetroAchievements'
 * structurally identical EnterUsername -> EnterKey flow (MimirAppHost.kt:1966-1988): one
 * `rememberMimirKeyboardHost(active = ...)` stays mounted across BOTH steps (`active` is true for both
 * `EnterUsername` and `EnterKey`, RetroAchievementsKeyEditModal.kt), while `RetroAchievementsKeyEditOverlay`'s
 * `when(state)` swaps to a structurally different composable subtree per step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetroAchievementsUsernameToKeyFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completingUsernameLeavesKeyboardScopeActiveAndOperableOnKeyStep() {
        val authority = DefaultFocusAuthority()
        // Non-blank initialUsername: UsernameStep's own onDone guards on non-blank BEFORE calling
        // onUsernameEntered (RetroAchievementsKeyEditModal.kt:234-236), so an empty draft would never
        // reach the transition under test at all — this seeds a realistic "already typed" username via
        // the public state contract instead of the module-internal draft stash.
        var connectUi: RetroAchievementsConnectUi = RetroAchievementsConnectUi.EnterUsername("player1")
        var host: MimirKeyboardHost? = null

        compose.setContent {
            MimirTheme {
                val keyboardActive = connectUi is RetroAchievementsConnectUi.EnterUsername ||
                    connectUi is RetroAchievementsConnectUi.EnterKey
                val keyboardHost = rememberMimirKeyboardHost(
                    authority = authority,
                    active = keyboardActive,
                    owner = MimirOverlayKind.RETROACHIEVEMENTS_KEY,
                )
                SideEffect { host = keyboardHost }
                DisposableEffect(Unit) {
                    onDispose { host = null }
                }
                RetroAchievementsKeyEditOverlay(
                    state = connectUi,
                    onUsernameEntered = { username ->
                        connectUi = RetroAchievementsConnectUi.EnterKey(username)
                    },
                    onValidate = { _, _ -> },
                    onRetry = {},
                    onClose = {},
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                )
            }
        }
        compose.waitForIdle()

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
            "state must have advanced to EnterKey",
            connectUi is RetroAchievementsConnectUi.EnterKey,
        )
        assertEquals(
            "FocusAuthority must still be in the keyboard scope on the key step",
            MimirKeyboardFocusScope,
            authority.state.value.scope,
        )

        val keyStepHost = requireNotNull(host)
        val confirmOnKeyStep = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = keyStepHost.binding,
            onDismiss = {},
        )
        assertTrue(
            "CONFIRM must be handled on the key step immediately after the username transition",
            confirmOnKeyStep,
        )
    }
}
