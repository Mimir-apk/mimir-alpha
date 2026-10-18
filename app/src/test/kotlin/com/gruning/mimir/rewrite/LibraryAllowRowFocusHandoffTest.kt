package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.LibraryAllowFilesRowId
import com.gruning.mimir.rewrite.feature.settings.detailKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library pane's "Allow file access" row leaves the screen when the grant returns, and the focus authority would drop
 * focus on the neighbouring source row. It belongs on RESCAN (alpha cert review, P3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAllowRowFocusHandoffTest {

    private val allowKey = detailKey(LibraryAllowFilesRowId)
    private val addKey = detailKey("library/add")
    private val rescanKey = detailKey("library/rescan")

    private fun box(top: Float) = FocusBounds(0f, top, 400f, top + 50f)

    /** Add Library folder, then the Allow row just under it, then RESCAN well below: the nearest sibling is Add. */
    private fun authority() = DefaultFocusAuthority().apply {
        pushScope(DetailScope)
        register(addKey, DetailScope, box(0f))
        register(allowKey, DetailScope, box(60f))
        register(rescanKey, DetailScope, box(400f))
        requestFocus(allowKey)
    }

    @Test
    fun `the decision needs the row to have held focus, lost it, and the grant to be held`() {
        assertTrue(fileAccessFocusHandoff(allowRowWasFocused = true, allowRowFocusedNow = false, allFilesAccess = true))
        assertFalse(fileAccessFocusHandoff(allowRowWasFocused = false, allowRowFocusedNow = false, allFilesAccess = true))
        assertFalse(fileAccessFocusHandoff(allowRowWasFocused = true, allowRowFocusedNow = true, allFilesAccess = true))
        assertFalse(fileAccessFocusHandoff(allowRowWasFocused = true, allowRowFocusedNow = false, allFilesAccess = false))
    }

    @Test
    fun `when the grant returns and the row goes, focus lands on RESCAN rather than on the neighbour`() = runTest {
        val authority = authority()
        var access = false
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            watchLibraryAllowRowFocus(authority, { access }) { authority.requestFocus(rescanKey) }
        }
        runCurrent()
        assertEquals(allowKey, authority.state.value.focused)

        // The player comes back from Android's screen: the grant is held and the pane no longer draws the row.
        access = true
        authority.unregister(allowKey)
        runCurrent()

        assertEquals(rescanKey, authority.state.value.focused)
        watcher.cancel()
    }

    @Test
    fun `the row going away with the grant still off hands nothing over`() = runTest {
        val authority = authority()
        var handOffs = 0
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            watchLibraryAllowRowFocus(authority, { false }) { handOffs++ }
        }
        runCurrent()

        authority.unregister(allowKey)
        runCurrent()

        assertEquals(0, handOffs)
        watcher.cancel()
    }

    @Test
    fun `the player moving off the row by hand hands nothing over, now or when the grant arrives later`() = runTest {
        val authority = authority()
        var access = false
        var handOffs = 0
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            watchLibraryAllowRowFocus(authority, { access }) { handOffs++ }
        }
        runCurrent()

        authority.requestFocus(addKey)
        runCurrent()
        access = true
        authority.requestFocus(rescanKey)
        runCurrent()
        authority.requestFocus(addKey)
        runCurrent()

        assertEquals(0, handOffs)
        watcher.cancel()
    }
}
