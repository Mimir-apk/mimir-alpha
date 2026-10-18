package com.gruning.mimir.rewrite

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real Utility panel draws the Calm shine row right after Navigation hints, and the row asks for the opposite of
 * what it shows. The pad half is UtilityCalmShineRowTest; this proves the row is DRAWN where the focus order says (a
 * row listed and never drawn is how a control ships reachable and invisible), through the semantics touch shares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityCalmShineRowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val sets = mutableListOf<Boolean>()

    private fun composePanel(calmShine: Boolean) {
        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = calmShineTestBrightnessState(),
                    focusedControl = null,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    filesRouteAvailable = true,
                    calmShine = calmShine,
                    onSetCalmShine = { sets += it },
                    surface = UtilitySurface.UTILITY,
                    onBack = {},
                    onClose = {},
                    controllerHintsAvailable = false,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun row(label: String): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription(label) and hasClickAction(), useUnmergedTree = true)

    @Test
    fun `the Calm shine row is drawn below Navigation hints and reads Off by default`() {
        composePanel(calmShine = false)
        val hints = row("Navigation hints").fetchSemanticsNode()
        val calm = row("Reduce shimmer effects").fetchSemanticsNode()
        assertTrue("drawn after the row whose focus key precedes it", calm.positionInRoot.y > hints.positionInRoot.y)
        assertEquals("Reduce shimmer effects off", calm.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `the row reads On when calm shine is on`() {
        composePanel(calmShine = true)
        val calm = row("Reduce shimmer effects").fetchSemanticsNode()
        assertEquals("Reduce shimmer effects on", calm.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `activating the row while it is off asks to turn it on`() {
        composePanel(calmShine = false)
        row("Reduce shimmer effects").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(true), sets)
    }

    @Test
    fun `activating the row while it is on asks to turn it off`() {
        composePanel(calmShine = true)
        row("Reduce shimmer effects").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(false), sets)
    }
}
