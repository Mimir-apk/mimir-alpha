package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O5 on the real composable. [BottomQuickLaunchPanelHost] composes [CompanionQuickLaunchLayer] with no rail and a picker
 * request; that request has to open the same All apps picker the rail's seat opens, and a pick has to launch from it.
 * Robolectric with injected touch, as in [CompanionLaunchLockedTapTest]. How it looks over a game on the Thor stays
 * device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomQuickLaunchPickerLayerTest {

    @get:Rule
    val compose = createComposeRule()

    private val beta = CompanionAppEntry(packageName = "com.example.beta", label = "Beta")

    private class Record {
        val pickerOpen = mutableListOf<Boolean>()
        val launched = mutableListOf<String>()
    }

    private fun clickLabel(label: String) = SemanticsMatcher("click label '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun count(matcher: SemanticsMatcher): Int =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput { click(point) }
        compose.waitForIdle()
    }

    private fun layer(record: Record, railVisible: Boolean, request: Long?) {
        compose.setContent {
            MimirTheme {
                CompanionQuickLaunchLayer(
                    onPickerOpenChanged = { record.pickerOpen += it },
                    slots = emptyList(),
                    apps = listOf(beta),
                    launchLocked = false,
                    launchMessage = null,
                    onLaunch = { record.launched += it },
                    onAssign = { _, _ -> },
                    railVisible = railVisible,
                    generalPickerRequest = request,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the Bottom panel's request opens the All apps picker with no second rail, and a pick launches from it`() {
        val record = Record()
        layer(record, railVisible = false, request = 1L)

        assertEquals("the All apps picker is up", 1, count(clickLabel("Close app picker")))
        assertEquals("with no rail of its own under it", 0, count(clickLabel("All apps")))
        assertEquals(listOf(true), record.pickerOpen)

        tapAt(compose.onNodeWithText("Beta", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center)

        assertEquals(listOf(beta.packageName), record.launched)
        assertEquals("the pick closes the picker, which lets the window go", listOf(true, false), record.pickerOpen)
    }

    @Test
    fun `control - with no request nothing opens until the rail's All apps seat is tapped`() {
        val record = Record()
        layer(record, railVisible = true, request = null)

        assertEquals(0, count(clickLabel("Close app picker")))
        assertEquals(emptyList<Boolean>(), record.pickerOpen)

        tapAt(compose.onNode(clickLabel("All apps"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center)

        assertEquals("the same picker, opened by hand", 1, count(clickLabel("Close app picker")))
        assertEquals(listOf(true), record.pickerOpen)
    }
}
