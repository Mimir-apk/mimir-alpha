package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchLayer
import com.gruning.mimir.rewrite.feature.companion.CompanionRecentsDrawer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA D17 and D11 (2026-09-11) on the REAL rail and drawer composables, under Robolectric with injected touch
 * (`performTouchInput { click() }`: a down/up pair at a point, not a semantics shortcut). A JVM proof of which callback
 * fires and how often; how either looks or feels on the Thor stays device-owed.
 *
 * Rail points are measured while the seat is enabled and uncovered, then the lock or the cover is applied and the SAME
 * point is tapped through the root, so neither disabled nor cleared semantics decide what gets hit. The drawer is
 * composed in its final state instead; see its test for why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompanionLaunchLockedTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val beta = CompanionAppEntry(packageName = "com.example.beta", label = "Beta")
    private val alpha = CompanionAppEntry(packageName = "com.example.alpha", label = "Alpha")

    private class Recorder {
        val ignored = mutableListOf<String>()
        val launched = mutableListOf<String>()
        var dismissed = 0
    }

    private class RailInputs {
        var locked by mutableStateOf(false)
        var covered by mutableStateOf(false)
    }

    private fun clickLabel(label: String) = SemanticsMatcher("click label '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun centreOf(matcher: SemanticsMatcher): Offset =
        compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput { click(point) }
        compose.waitForIdle()
    }

    private fun rail(recorder: Recorder): RailInputs {
        val inputs = RailInputs()
        compose.setContent {
            MimirTheme {
                CompanionQuickLaunchLayer(
                    // Q1 pinned, Q2 empty.
                    slots = listOf(beta.packageName, null),
                    apps = listOf(beta),
                    launchLocked = inputs.locked,
                    launchMessage = null,
                    onLaunch = { recorder.launched += "picker:$it" },
                    onPinnedLaunch = { recorder.launched += it },
                    onAssign = { _, _ -> },
                    railCovered = inputs.covered,
                    onQuickLaunchIgnored = { recorder.ignored += it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        return inputs
    }

    private fun drawer(recorder: Recorder, locked: Boolean) {
        compose.setContent {
            CompanionRecentsDrawer(
                entries = listOf(alpha),
                launchLocked = locked,
                launchMessage = null,
                themeId = null,
                quickSlots = emptyList(),
                quickLaunchApps = emptyList(),
                onQuickLaunch = {},
                onQuickAssign = { _, _ -> },
                onLaunch = { recorder.launched += it },
                onClose = {},
                onDismiss = { recorder.dismissed++ },
                onQuickLaunchIgnored = { recorder.ignored += it },
            )
        }
    }

    @Test
    fun `a locked seat reports each dropped tap once with its target and launches nothing`() {
        val recorder = Recorder()
        val inputs = rail(recorder)
        val pin = centreOf(clickLabel("Beta"))
        val allApps = centreOf(clickLabel("All apps"))
        val empty = compose.onNodeWithText("+", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

        inputs.locked = true
        compose.waitForIdle()
        // P3-1's shape: the pin tapped twice while its launch is pending.
        tapAt(pin)
        tapAt(pin)
        tapAt(allApps)
        tapAt(empty)

        assertEquals(listOf(beta.packageName, beta.packageName, "all_apps", "empty_q2"), recorder.ignored)
        assertEquals(emptyList<String>(), recorder.launched)
    }

    @Test
    fun `an unlocked seat launches and reports nothing`() {
        val recorder = Recorder()
        rail(recorder)

        tapAt(centreOf(clickLabel("Beta")))

        assertEquals(listOf(beta.packageName), recorder.launched)
        assertEquals(emptyList<String>(), recorder.ignored)
    }

    @Test
    fun `a covered rail takes no input and reports nothing, then gives input back when it clears`() {
        val recorder = Recorder()
        val inputs = rail(recorder)
        val pin = centreOf(clickLabel("Beta"))
        // Read on the merged tree, which is what accessibility reads: `clearAndSetSemantics` removes a node's
        // descendants there, while the unmerged tree still lists them. First prove this read can see the seat at all.
        assertEquals(1, compose.onAllNodes(clickLabel("Beta")).fetchSemanticsNodes().size)

        inputs.covered = true
        compose.waitForIdle()
        assertEquals(
            "a covered rail is not offered to accessibility either",
            0,
            compose.onAllNodes(clickLabel("Beta")).fetchSemanticsNodes().size,
        )
        tapAt(pin)
        inputs.locked = true
        compose.waitForIdle()
        tapAt(pin)
        assertEquals(emptyList<String>(), recorder.launched)
        assertEquals("the cover takes the tap before the lock can report it", emptyList<String>(), recorder.ignored)

        inputs.locked = false
        inputs.covered = false
        compose.waitForIdle()
        tapAt(pin)
        assertEquals(listOf(beta.packageName), recorder.launched)
    }

    @Test
    fun `a locked drawer reports a tab tap with its package and a backdrop tap as dismiss`() {
        val recorder = Recorder()
        // The lock spins an indeterminate indicator, an infinite animation, so this test drives the clock by hand.
        //
        // It also composes the drawer ALREADY locked. Flipped locked mid-test under the hand-driven clock, this harness
        // did not lay the drawer out again -- the tab kept its unlocked bounds, where a drawer composed locked lays out
        // taller -- and a tap there reached neither the tab's click nor its observer. Composed locked, the same tap
        // lands. A lock that engages after composition is exercised on the rail above.
        compose.mainClock.autoAdvance = false
        drawer(recorder, locked = true)
        compose.mainClock.advanceTimeBy(1_000)
        val tab = compose.onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        // Left of the panel, which is centred at 86% of the width: the dismiss scrim.
        val backdrop = Offset(4f, 4f)

        compose.onRoot().performTouchInput { click(tab) }
        compose.onRoot().performTouchInput { click(backdrop) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf(alpha.packageName, "dismiss"), recorder.ignored)
        assertEquals(emptyList<String>(), recorder.launched)
        assertEquals(0, recorder.dismissed)
    }

    @Test
    fun `an unlocked drawer launches a tab and dismisses from the backdrop, reporting nothing`() {
        val recorder = Recorder()
        drawer(recorder, locked = false)
        compose.waitForIdle()

        tapAt(compose.onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center)
        tapAt(Offset(4f, 4f))

        assertEquals(listOf(alpha.packageName), recorder.launched)
        assertEquals(1, recorder.dismissed)
        assertEquals(emptyList<String>(), recorder.ignored)
    }
}
