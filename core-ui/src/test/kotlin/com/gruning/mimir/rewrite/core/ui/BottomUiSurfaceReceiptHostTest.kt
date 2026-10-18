package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V2: the receipt composable names the window that composes its surface on BOTH edges and when it is torn down with that window.
 *
 * The entry is keyed by the host ([BottomUiReceiptLog.isSurfaceVisible]), so a picker torn down with the Tabs drawer or the quick
 * menu's window must close ITS entry: an exit that named no host would look for the Presentation's entry, find nothing, and leave
 * the hosted one "entered" forever. A Presentation picker that is still open must not be closed by it either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BottomUiSurfaceReceiptHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = ArrayList<String>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> {} },
        stackSnapshots = true,
    )

    @Test
    fun aHostedPickerTornDownWithItsWindowClosesItsOwnEntryAndLeavesThePresentationsOpen() {
        log.surface(BottomSurface.LATTICE, 4, true)
        var tabsWindow by mutableStateOf(true)
        compose.setContent {
            BottomUiSurfaceReceipt(surface = BottomSurface.PICKER, visible = true, display = 4, log = log)
            if (tabsWindow) {
                BottomUiSurfaceReceipt(surface = BottomSurface.PICKER, visible = true, display = 4, log = log, host = BottomSurface.TABS)
            }
        }
        compose.waitForIdle()
        assertTrue(log.isSurfaceVisible(BottomSurface.PICKER, 4))
        assertTrue(log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
        assertEquals("one enter for each window's picker", 2, lines.count { it.startsWith("ev=surface.enter surface=picker") })

        tabsWindow = false
        compose.waitForIdle()
        assertFalse("the Tabs window's picker left with it, and its exit was written", log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
        assertTrue("the Presentation's picker is still open", log.isSurfaceVisible(BottomSurface.PICKER, 4))
        assertEquals(1, lines.count { it.startsWith("ev=surface.exit surface=picker") })
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.PICKER), log.surfaceStack(4))
    }

    @Test
    fun aHostedPickerThatHidesWritesItsExitAgainstItsOwnEntry() {
        log.surface(BottomSurface.LATTICE, 4, true)
        log.surface(BottomSurface.M_MENU, 4, true)
        var open by mutableStateOf(true)
        compose.setContent {
            BottomUiSurfaceReceipt(surface = BottomSurface.PICKER, visible = open, display = 4, log = log, host = BottomSurface.M_MENU)
        }
        compose.waitForIdle()
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.M_MENU, BottomSurface.PICKER), log.surfaceStack(4))

        open = false
        compose.waitForIdle()
        assertFalse(log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.M_MENU))
        assertEquals(1, lines.count { it.startsWith("ev=surface.exit surface=picker") })
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.M_MENU), log.surfaceStack(4))
    }
}
