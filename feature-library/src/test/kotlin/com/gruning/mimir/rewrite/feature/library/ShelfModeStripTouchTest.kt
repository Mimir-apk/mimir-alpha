package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cert 2026-10-08 (A1): the real [ShelfModeStrip] under a real finger. The inactive tab reports its label once, through the
 * pointer gesture (not the accessibility action), the active tab reports nothing and offers no click action, and neither
 * plants a Compose focus node (`mimirActivatable`, the sanctioned tap with no focus node, is what carries the tap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfModeStripTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableListOf<String>()

    private fun strip(active: String) {
        compose.setContent { MimirTheme { ShelfModeStrip(active = active, onSelect = { selected += it }) } }
        compose.waitForIdle()
    }

    @Test
    fun `a finger on the inactive tab reports its label once`() {
        strip(active = "EMULATION")

        compose.onNodeWithContentDescription("Android games").performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(listOf("ANDROID GAMES"), selected)
    }

    @Test
    fun `a finger on the other tab reports it when Android is the active one`() {
        strip(active = "ANDROID GAMES")

        compose.onNodeWithContentDescription("Emulation").performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(listOf("EMULATION"), selected)
    }

    @Test
    fun `a finger on the active tab reports nothing`() {
        strip(active = "EMULATION")

        compose.onNodeWithContentDescription("Emulation").performTouchInput { click() }
        compose.waitForIdle()

        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun `only the inactive tab offers a click action, and it reads as a tab`() {
        strip(active = "EMULATION")

        compose.onNodeWithContentDescription("Android games")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithContentDescription("Emulation")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
