package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchChoice
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchLayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice B1 of the Thor Bottom revamp, on the REAL [CompanionQuickLaunchLayer] under Robolectric on a paused clock (autoAdvance off,
 * `Snapshot.sendApplyNotifications()` after each state write): the quick-launch picker and the launch prompt (the "Opens on" dialog, and
 * PAUSE GAME) arrive on the swap tier and leave on OverlayMs, the pad gate is never held, and a page takes no touch through its lead or its
 * leave. The receipts' lengths are measured on the same clocks in core-ui's `MimirOverlayMotionTest`; the log runs on the real clock while Compose
 * runs on a virtual one, so this class pins sequence, input and the moment a leaving page goes.
 *
 * Pin actions run the identical `AnimatedContent` shape as the launch prompt (one helper, one gate); it is covered by `BottomOverlayTiersSourcePinTest`,
 * because reaching it needs a long press on a pinned seat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomOverlayTiersTest {

    @get:Rule
    val compose = createComposeRule()

    private val beta = CompanionAppEntry(packageName = "com.example.beta", label = "Beta")
    private var pickerRequest by mutableStateOf<Long?>(null)
    private var choice by mutableStateOf<CompanionQuickLaunchChoice?>(null)
    private val pickerOpen = mutableListOf<Boolean>()
    private val chosen = mutableListOf<CompanionQuickLaunchDestination>()
    private val dismissed = mutableListOf<CompanionQuickLaunchChoice>()

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    CompanionQuickLaunchLayer(
                        onPickerOpenChanged = { pickerOpen += it },
                        slots = listOf(beta.packageName, null),
                        apps = listOf(beta),
                        launchLocked = false,
                        launchMessage = null,
                        onLaunch = {},
                        onAssign = { _, _ -> },
                        quickLaunchChoice = choice,
                        onQuickLaunchChoice = { _, destination -> chosen += destination },
                        onQuickLaunchChoiceDismiss = { dismissed += it },
                        railVisible = false,
                        generalPickerRequest = pickerRequest,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun write(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertFalse("the Bottom never holds the process-wide pad gate", MimirTransitionInputGate.holding)
    }

    private fun advance(ms: Long) {
        // In steps, so the gate is read on the way and not only at the end.
        var left = ms
        while (left > 0) {
            val step = minOf(left, 16L)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle() // lets the frame's recomposition and effects run before the next one, as a device does
            left -= step
            assertFalse("the Bottom never holds the process-wide pad gate", MimirTransitionInputGate.holding)
        }
    }

    private fun settle() {
        advance((MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs + 200).toLong())
        compose.waitForIdle()
    }

    /** Every node with [tag], including a page whose semantics a leaving page has cleared: its being composed is the fact. */
    private fun composed(tag: String): Int = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun centreOf(tag: String, index: Int = 0): Offset =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()[index].boundsInRoot.center

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput { click(point) }
        compose.mainClock.advanceTimeByFrame()
    }

    private val opensOn = CompanionQuickLaunchChoice(
        requestId = 1L,
        packageName = "com.example.maps",
        label = "Maps",
        editing = false,
        fromGeneral = false,
    )

    @Test
    fun `the picker takes no touch through its lead, takes it once shown, and leaves in 180 ms`() {
        mount()
        assertEquals("nothing is up", 0, composed(CLOSE_TAG))
        write { pickerRequest = 1L }
        assertEquals("the picker is asked for", listOf(true), pickerOpen)
        advance(64L) // inside the 130 ms lead: the page stands at alpha 0
        assertEquals("the picker is composed through its lead", 1, composed(CLOSE_TAG))
        tapAt(centreOf(CLOSE_TAG))
        assertEquals("a tap through the lead reaches nothing: $pickerOpen", listOf(true), pickerOpen)

        settle()
        tapAt(centreOf(CLOSE_TAG))
        assertEquals("a shown picker takes its Close", listOf(true, false), pickerOpen)

        advance(96L) // 180 ms close, the page still on its way out
        assertEquals("the leaving picker is still composed 100 ms into its 180 ms leave", 1, composed(CLOSE_TAG))
        advance(MimirMotion.OverlayMs.toLong() + 100L - 96L)
        compose.mainClock.advanceTimeByFrame() // the removal is a recomposition, which needs a frame to run in
        compose.waitForIdle()
        assertEquals("and gone within about 100 ms of its 180 ms leave (frame quantization)", 0, composed(CLOSE_TAG))
    }

    @Test
    fun `the Opens on dialog takes no touch through its lead, answers once shown, and leaves in 180 ms`() {
        mount()
        write { choice = opensOn }
        advance(64L)
        assertEquals("the dialog's actions are composed through the lead", 3, composed(ACTION_TAG))
        tapAt(centreOf(ACTION_TAG, 0))
        assertTrue("a tap through the lead answers nothing: $chosen", chosen.isEmpty())

        settle()
        tapAt(centreOf(ACTION_TAG, 1))
        assertEquals("a shown dialog answers: Bottom screen is the second action", listOf(CompanionQuickLaunchDestination.BOTTOM), chosen)

        write { choice = null } // the host has taken the answer
        advance(96L)
        assertEquals("the leaving dialog is still composed 100 ms into its 180 ms leave", 3, composed(ACTION_TAG))
        advance(MimirMotion.OverlayMs.toLong() + 100L - 96L)
        compose.mainClock.advanceTimeByFrame() // the removal is a recomposition, which needs a frame to run in
        compose.waitForIdle()
        assertEquals("and gone within about 100 ms of its 180 ms leave (frame quantization)", 0, composed(ACTION_TAG))
    }

    @Test
    fun `a leaving dialog takes no tap`() {
        mount()
        write { choice = opensOn }
        settle()
        write { choice = null }
        advance(48L)
        val action = centreOf(ACTION_TAG, 0)
        tapAt(action)
        assertTrue("a tap on the dialog while it leaves answers nothing: $chosen", chosen.isEmpty())
        assertTrue("and dismisses nothing: $dismissed", dismissed.isEmpty())
    }

    @Test
    fun `PAUSE GAME is the same dialog and says Bottom screen`() {
        mount()
        write { choice = opensOn.copy(coversMainGame = true) }
        settle()
        compose.onNodeWithTag(DIALOG_TAG, useUnmergedTree = true).assertExists()
        assertEquals("Continue and Cancel", 2, composed(ACTION_TAG))
        compose.onNode(hasText("Maps opens on the Bottom screen.", substring = true), useUnmergedTree = true).assertExists()
        assertEquals("no lower screen", 0, compose.onAllNodes(hasText("lower screen", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        tapAt(centreOf(ACTION_TAG, 0))
        assertEquals("Continue goes to the Bottom", listOf(CompanionQuickLaunchDestination.BOTTOM), chosen)
    }

    @Test
    fun `a dialog switched for another keeps the pad free and ends with only the new one`() {
        mount()
        write { choice = opensOn }
        settle()
        write { choice = opensOn.copy(requestId = 2L, label = "Browser") }
        settle()
        assertEquals("one dialog, three actions", 3, composed(ACTION_TAG))
        assertFalse(MimirTransitionInputGate.holding)
    }

    private companion object {
        const val DISPLAY = 4
        const val CLOSE_TAG = "companion-picker-close"
        const val ACTION_TAG = "companion-choice-action"
        const val DIALOG_TAG = "companion-choice-dialog"
    }
}
