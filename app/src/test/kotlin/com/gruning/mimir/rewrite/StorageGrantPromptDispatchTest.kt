package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller contract for [dispatchStorageGrantPrompt], shaped after `dispatchArtworkFetch`'s
 * `ConfirmNewGames` branch.
 *
 * The first test is the load-bearing one: with nothing lost this function must return **false** and touch
 * nothing, so adopting it in the shell's dispatcher can never cost the Home shelf a single press on a
 * healthy library. A modal helper that swallows input when its modal is closed is how a D-pad trap gets
 * built , and this feature is supposed to END a silent
 * failure, not introduce one.
 */
class StorageGrantPromptDispatchTest {

    private val lost = StoredRoot(
        rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs",
        locationKind = "SAF_TREE_GRANT",
        friendlyLabel = "SD card › ROMs",
    )

    private fun authorityWithPromptOpen(): DefaultFocusAuthority = DefaultFocusAuthority().apply {
        pushScope(StorageGrantFocusScope)
        register(StorageGrantReconnectKey, StorageGrantFocusScope, FocusBounds(0f, 0f, 200f, 40f))
        register(StorageGrantLaterKey, StorageGrantFocusScope, FocusBounds(0f, 60f, 200f, 100f))
        requestFocus(StorageGrantReconnectKey)
    }

    @Test
    fun `no lost root means the press belongs to whatever is behind, untouched`() {
        var reconnected = 0
        var latered = 0
        ControllerAction.entries.forEach { action ->
            assertFalse(
                "with nothing lost, $action must fall through to the shelf",
                dispatchStorageGrantPrompt(
                    action = action,
                    promptRoot = null,
                    onReconnect = { reconnected += 1 },
                    onLater = { latered += 1 },
                    authority = authorityWithPromptOpen(),
                ),
            )
        }
        assertEquals(0, reconnected)
        assertEquals(0, latered)
    }

    @Test
    fun `B and system back defer the prompt rather than repairing anything`() {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            var reconnected = 0
            var latered = 0
            assertTrue(
                dispatchStorageGrantPrompt(
                    action = action,
                    promptRoot = lost,
                    onReconnect = { reconnected += 1 },
                    onLater = { latered += 1 },
                    authority = authorityWithPromptOpen(),
                ),
            )
            assertEquals("$action must not open the picker", 0, reconnected)
            assertEquals("$action is LATER", 1, latered)
        }
    }

    @Test
    fun `A activates whichever button holds focus`() {
        val authority = authorityWithPromptOpen()
        var reconnected = 0
        var latered = 0
        val dispatch = { action: ControllerAction ->
            dispatchStorageGrantPrompt(
                action = action,
                promptRoot = lost,
                onReconnect = { reconnected += 1 },
                onLater = { latered += 1 },
                authority = authority,
            )
        }

        assertEquals(StorageGrantReconnectKey, authority.state.value.focused)
        assertTrue(dispatch(ControllerAction.CONFIRM))
        assertEquals(1, reconnected)
        assertEquals(0, latered)

        assertTrue(dispatch(ControllerAction.NAV_DOWN))
        assertEquals("Down must move focus to LATER", StorageGrantLaterKey, authority.state.value.focused)
        assertTrue(dispatch(ControllerAction.CONFIRM))
        assertEquals("A on LATER must not open the picker", 1, reconnected)
        assertEquals(1, latered)
    }

    @Test
    fun `the prompt swallows everything else so nothing behind it can act while it is up`() {
        var reconnected = 0
        var latered = 0
        val authority = authorityWithPromptOpen()
        listOf(
            ControllerAction.SYSTEM_START,
            ControllerAction.TRIGGER_LEFT,
            ControllerAction.TRIGGER_RIGHT,
        ).forEach { action ->
            assertTrue(
                "$action must be swallowed, not routed to the shelf under an open modal",
                dispatchStorageGrantPrompt(
                    action = action,
                    promptRoot = lost,
                    onReconnect = { reconnected += 1 },
                    onLater = { latered += 1 },
                    authority = authority,
                ),
            )
        }
        assertEquals(0, reconnected)
        assertEquals(0, latered)
    }

    @Test
    fun `a foreign active scope neither acts nor leaks the press`() {
        // If some other scope owns input, this prompt must not activate its own buttons from a stale
        // focus reading — but it must still swallow, because it is the surface on screen.
        val authority = authorityWithPromptOpen()
        authority.discardScope(StorageGrantFocusScope)
        var reconnected = 0
        var latered = 0
        assertTrue(
            dispatchStorageGrantPrompt(
                action = ControllerAction.CONFIRM,
                promptRoot = lost,
                onReconnect = { reconnected += 1 },
                onLater = { latered += 1 },
                authority = authority,
            ),
        )
        assertEquals(0, reconnected)
        assertEquals(0, latered)
    }
}
