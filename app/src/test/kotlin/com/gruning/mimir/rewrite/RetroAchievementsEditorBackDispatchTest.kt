package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardInitialKeyId
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product review 2026-09-28, F1: every QA walk and every end user was trapped in the RetroAchievements editor.
 * B reached the shared keyboard, whose CANCEL presses the Done key, and Done does nothing on an empty step.
 * B and Back must leave both steps without pressing Done, and must still wait while the key is being checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetroAchievementsEditorBackDispatchTest {
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `B on the username step closes the editor without pressing Done`() {
        val editor = editor()
        editor.open(currentUsername = null)
        assertTrue(editor.state is RetroAchievementsConnectUi.EnterUsername)

        val result = pressOnEditor(editor, ControllerAction.CANCEL)

        assertTrue(result.handled)
        assertNull("B must close the username step", editor.state)
        assertEquals("B must not press the keyboard's Done key", 0, result.donePresses)
    }

    @Test
    fun `B on the key step closes the editor without pressing Done`() {
        val editor = editor()
        editor.open(currentUsername = null)
        editor.usernameEntered("player")
        assertTrue(editor.state is RetroAchievementsConnectUi.EnterKey)

        val result = pressOnEditor(editor, ControllerAction.CANCEL)

        assertTrue(result.handled)
        assertNull("B must close the key step", editor.state)
        assertEquals("B must not press the keyboard's Done key", 0, result.donePresses)
    }

    @Test
    fun `system Back on the key step closes the editor too`() {
        val editor = editor()
        editor.open(currentUsername = null)
        editor.usernameEntered("player")

        val result = pressOnEditor(editor, ControllerAction.SYSTEM_BACK)

        assertTrue(result.handled)
        assertNull(editor.state)
        assertEquals(0, result.donePresses)
    }

    @Test
    fun `B while the key is being checked keeps the editor open`() {
        val editor = editor()
        editor.open(currentUsername = null)
        editor.usernameEntered("player")
        editor.submit(username = "player", apiKey = "not-a-real-key")
        assertTrue("the fake check never answers, so validation holds", editor.isValidating)

        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            val result = pressOnEditor(editor, action)
            assertTrue("Back is consumed while validating", result.handled)
            assertEquals("the editor is not dismissed mid-check", 0, result.dismissCalls)
            assertEquals(0, result.donePresses)
            assertTrue(editor.state is RetroAchievementsConnectUi.Validating)
        }
        assertFalse("the controller itself also refuses to close mid-check", editor.dismiss())
    }

    private class Press(val handled: Boolean, val dismissCalls: Int, val donePresses: Int)

    private fun pressOnEditor(editor: RetroAchievementsConnectController, action: ControllerAction): Press {
        val authority = DefaultFocusAuthority()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        val host = MimirKeyboardHost(
            controller = MimirKeyboardController(
                onEnter = binding::enter,
                onExit = binding::exit,
                onKeysChanged = binding::reconcileFocus,
            ),
            keyDecorator = { _, _ ->
                MimirKeyboardKeyDecoration(
                    focusModifier = androidx.compose.ui.Modifier,
                    focused = false,
                )
            },
            binding = binding,
            owner = MimirOverlayKind.RETROACHIEVEMENTS_KEY.name,
        )
        binding.enter()
        var donePresses = 0
        binding.registerActivator(MimirKeyboardInitialKeyId) { donePresses += 1 }
        var dismissCalls = 0
        val handled = dispatchTopOverlayMimirKeyboard(
            top = MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            authority = authority,
            settingsKeyboardHost = host,
            artworkConnectKeyboardHost = null,
            homeTomeLibraryDraftOpen = false,
            homeTomeLibraryConfirmDelete = false,
            homeTomeLibraryKeyboardHost = null,
            action = action,
            onDismissProfileName = {},
            onDismissSteamGridDbKey = {},
            onDismissRetroAchievements = {
                dismissCalls += 1
                editor.dismiss()
            },
            onDismissArtworkConnect = {},
            onDismissGameTitleEdit = {},
            onResetGameTitle = {},
            onHomeTomeLibraryAction = {},
            retroAchievementsValidating = editor.isValidating,
        )
        return Press(handled, dismissCalls, donePresses)
    }

    private fun editor(): RetroAchievementsConnectController =
        RetroAchievementsConnectController(
            scope = scope,
            beginAttempt = { 1L },
            validate = { _, _ -> awaitCancellation() },
            persistAccepted = { _, _, _ -> null },
            publishIfCurrent = { _, publish ->
                publish()
                true
            },
            cancelAttempt = { true },
            recoverUncertain = { true },
            onAcceptedClosed = {},
            clearDraft = {},
        )
}
