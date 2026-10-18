package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardPanelTag
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.browse.BrowseMimirKeyboardHost
import com.gruning.mimir.rewrite.feature.browse.BrowseScope
import com.gruning.mimir.rewrite.feature.browse.BrowseScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-33 (audit 2026-10-14, L-40c / X-36): Browse's search keyboard was `if (keyboardOpen)`, so it popped in and out in one frame.
 * It rides the shared sheet host now. Over the real screen with the real focus authority: the keyboard is absent while Browse is
 * closed, it is still drawn one frame and a middle frame after `keyboardOpen` goes false (it leaves, it is not cut), the pad is
 * handed back to Browse in the frame the flag flips (not 180 ms later), and the keyboard is gone once the clock has run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseKeyboardSheetFadeTest {

    @get:Rule
    val compose = createComposeRule()

    private val keyboardOpen = mutableStateOf(false)
    private val authority = DefaultFocusAuthority()
    private var host: BrowseMimirKeyboardHost? = null

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                BrowseScreen(
                    hiddenGames = emptyList(),
                    platformLabels = emptyMap<PlatformId, String>(),
                    searchQuery = "",
                    keyboardOpen = keyboardOpen.value,
                    authority = authority,
                    onRestore = {},
                    onSearchChange = {},
                    onOpenSearch = {},
                    onClearSearch = {},
                    onCloseKeyboard = { keyboardOpen.value = false },
                    onExit = {},
                    onKeyboardHostChanged = { host = it },
                )
            }
        }
        step(0L)
    }

    @Test
    fun `the keyboard leaves on the sheet clock instead of being cut, and the pad is handed back at once`() {
        mount()
        compose.onNodeWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(BrowseScope, authority.state.value.scope)

        Snapshot.withMutableSnapshot { keyboardOpen.value = true }
        step(MimirMotion.OverlayMs.toLong() + 32L)
        compose.onNodeWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).assertExists()
        assertNotNull("while open the keyboard is wired", host)
        assertEquals("the pad is in the keyboard's scope", MimirKeyboardFocusScope, authority.state.value.scope)

        Snapshot.withMutableSnapshot { keyboardOpen.value = false }
        step(16L)
        compose.onNodeWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).assertExists()
        assertNull("from the frame the flag flips, nothing is wired to the leaving keyboard", host)
        assertEquals("and the pad is Browse's again, not 180 ms later", BrowseScope, authority.state.value.scope)

        step(74L)
        compose.onNodeWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).assertExists()

        step(MimirMotion.OverlayMs.toLong())
        compose.onNodeWithTag(MimirKeyboardPanelTag, useUnmergedTree = true).assertDoesNotExist()
    }
}
