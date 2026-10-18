package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirMotion
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
 * the "Storage access lost" prompt cut in on the frame no overlay was open and cut out on
 * Later, Reconnect or any overlay opening. Over the real prompt and the real focus authority: it fades in rather than appearing at
 * once, it is still drawn mid-leave (not cut), and while it leaves it is inert, with its focus scope discarded in the frame the leave
 * begins (so it never holds the pad under it) and its buttons taking no touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageGrantPromptFadeTest {

    @get:Rule
    val compose = createComposeRule()

    private val root = StoredRoot(rootUri = "content://tree/sd", locationKind = "SAF_TREE_GRANT", friendlyLabel = "SD card › ROMs")
    private val rootState = mutableStateOf<StoredRoot?>(null)
    private val visible = mutableStateOf(true)
    private val authority = DefaultFocusAuthority()
    private var reconnects = 0
    private var laters = 0

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                StorageGrantPromptLayer(
                    root = rootState.value,
                    visible = visible.value,
                    authority = authority,
                    onReconnect = { reconnects++ },
                    onLater = { laters++ },
                )
            }
        }
        step(0L)
    }

    private fun titleShown() = compose.shows(StorageGrantLostTitleCopy, exact = false)

    @Test
    fun `the prompt arrives and leaves on the fade, inert while it leaves`() {
        mount()
        assertFalse("the prompt is absent", titleShown())

        Snapshot.withMutableSnapshot { rootState.value = root }
        step(32L)
        assertTrue("the prompt is drawn", titleShown())
        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertEquals("while it is shown it holds the pad", StorageGrantFocusScope, authority.state.value.scope)
        compose.labelled(StorageGrantReconnectCopy)[0].performTouchInput { down(center); up() }
        step(0L)
        assertEquals("a shown prompt answers a touch", 1, reconnects)

        // An overlay opens: the prompt is asked to leave.
        Snapshot.withMutableSnapshot { visible.value = false }
        step(16L)
        assertTrue("the prompt is drawn", titleShown())
        assertFalse(
            "from the frame the leave begins the prompt holds no scope (it would shadow the overlay opening over it)",
            authority.state.value.scope == StorageGrantFocusScope,
        )
        step(74L)
        assertTrue("the prompt is drawn", titleShown())
        compose.labelled(StorageGrantReconnectCopy)[0].performTouchInput { down(center); up() }
        step(0L)
        assertEquals("a leaving prompt takes no touch", 1, reconnects)

        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertFalse("the prompt is absent", titleShown())
        assertEquals(0, laters)
    }

    @Test
    fun `a prompt that is deferred leaves the same way instead of being cut`() {
        mount()
        Snapshot.withMutableSnapshot { rootState.value = root }
        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertTrue("the prompt is drawn", titleShown())

        Snapshot.withMutableSnapshot { rootState.value = null }
        step(16L)
        assertTrue("the prompt is drawn", titleShown())
        step(MimirMotion.OverlayMs.toLong() + 32L)
        assertFalse("the prompt is absent", titleShown())
        assertTrue(authority.state.value.scope != FocusScope("storage/grant-lost"))
    }
}
