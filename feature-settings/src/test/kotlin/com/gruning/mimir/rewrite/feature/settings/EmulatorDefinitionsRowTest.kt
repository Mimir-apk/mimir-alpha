package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.After
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
 * Settings > Emulators > Emulator updates: the words, and a row that is reachable and pressable on the single focus
 * authority. The state arrives through [EmulatorDefinitionsRowBridge]; the app owns what a press means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmulatorDefinitionsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val activations = mutableListOf<String>()
    private val authority = DefaultFocusAuthority()
    private val builtIn = "Built-in · 8 Oct 2026"

    @After
    fun bridgeBackToNothing() {
        EmulatorDefinitionsRowBridge.publish(null)
    }

    private fun show() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(560.dp).requiredHeight(600.dp)) {
                    EmulatorDefinitionsSection(authority = remember { authority }, onRowActivate = { activations += it })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun named(text: String) = hasContentDescription(text) or hasText(text, substring = true)

    private fun exists(text: String): Boolean =
        compose.onAllNodes(named(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(text: String): SemanticsNodeInteraction = compose.onAllNodes(named(text), useUnmergedTree = true)[0]

    // ---- the words ---------------------------------------------------------------------------------------------

    @Test
    fun `the status line is Built-in until an update is downloaded and then names the day and the release`() {
        val built = LocalDate.of(2026, 10, 8)
        assertEquals(builtIn, emulatorDefinitionsStatusLine(built, null, null, Locale.ENGLISH))
        assertEquals("Updated · 12 Oct 2026", emulatorDefinitionsStatusLine(built, LocalDate.of(2026, 10, 12), "defs-2", Locale.ENGLISH))
        assertEquals("a release with no recorded day still names itself", "Updated", emulatorDefinitionsStatusLine(built, null, "defs-2", Locale.ENGLISH))
    }

    @Test
    fun `the result lines say what happened in plain English and never a code`() {
        assertEquals("Up to date", EmulatorDefinitionsResult.UpToDate.line())
        assertEquals("Updated: 1 emulator", EmulatorDefinitionsResult.Updated(1).line())
        assertEquals("Updated: 3 emulators", EmulatorDefinitionsResult.Updated(3).line())
        assertEquals("Couldn’t update; using built-in", EmulatorDefinitionsResult.CouldntUpdate(usingFetched = false).line())
        assertEquals("Couldn’t update; keeping your last update", EmulatorDefinitionsResult.CouldntUpdate(usingFetched = true).line())
        assertEquals("This update needs a newer Mimir. Using built-in.", EmulatorDefinitionsResult.CouldntUpdate(false, needsAppUpdate = true).line())
        assertEquals("This update needs a newer Mimir. Keeping your last update.", EmulatorDefinitionsResult.CouldntUpdate(true, needsAppUpdate = true).line())
    }

    @Test
    fun `the row states the action, the explanation before the first download, and the status with the last result`() {
        val idle = EmulatorDefinitionsRowState(builtIn)
        assertEquals("Check for updates", idle.action)
        assertEquals(builtIn, idle.supporting)

        val asking = idle.copy(explaining = true)
        assertEquals("Download", asking.action)
        assertEquals(EmulatorDefinitionsExplanation, asking.supporting)
        assertTrue("it says what is fetched", EmulatorDefinitionsExplanation.contains("updated emulator support"))

        assertEquals("Checking…", idle.copy(checking = true).action)
        assertEquals("$builtIn\nUp to date", idle.copy(result = EmulatorDefinitionsResult.UpToDate).supporting)
    }

    // ---- the row on the screen ----------------------------------------------------------------------------------

    @Test
    fun `nothing is drawn until the app publishes the row`() {
        show()
        assertFalse(exists("Emulator support"))
        assertFalse(exists("Check for updates"))
    }

    @Test
    fun `the row shows the status and the action and is registered on the detail scope for the controller`() {
        EmulatorDefinitionsRowBridge.publish(EmulatorDefinitionsRowState(builtIn))
        show()

        assertTrue(exists("Emulator support"))
        assertTrue(exists(builtIn))
        assertTrue(exists("Check for updates"))
        assertTrue(
            "controller-reachable through the single FocusAuthority",
            detailKey(EmulatorDefinitionsRowId) in authority.registeredKeysInScope(DetailScope),
        )
    }

    @Test
    fun `a press raises the row id and the row follows the state the app publishes`() {
        EmulatorDefinitionsRowBridge.publish(EmulatorDefinitionsRowState(builtIn))
        show()

        node("Emulator support").performClick()
        compose.waitForIdle()
        assertEquals(listOf(EmulatorDefinitionsRowId), activations)

        EmulatorDefinitionsRowBridge.publish(EmulatorDefinitionsRowState(builtIn, explaining = true))
        compose.waitForIdle()
        assertTrue(exists("updated emulator support"))
        assertTrue(exists("Download"))
        assertFalse(exists("Check for updates"))

        EmulatorDefinitionsRowBridge.publish(
            EmulatorDefinitionsRowState("Updated · 12 Oct 2026", result = EmulatorDefinitionsResult.Updated(3)),
        )
        compose.waitForIdle()
        assertTrue(exists("Updated · 12 Oct 2026"))
        assertTrue(exists("Updated: 3 emulators"))
        assertTrue(exists("Check for updates"))
    }

    @Test
    fun `the full Emulators pane carries the section below the platform list`() {
        EmulatorDefinitionsRowBridge.publish(EmulatorDefinitionsRowState(builtIn))
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(560.dp).requiredHeight(900.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.EMULATORS,
                        authority = remember { authority },
                        onRowActivate = {},
                        emulatorAssignments = emptyList(),
                    )
                }
            }
        }
        compose.waitForIdle()

        assertTrue(exists("Emulator updates"))
        assertTrue(exists("Emulator support"))
        assertTrue(detailKey(EmulatorDefinitionsRowId) in authority.registeredKeysInScope(DetailScope))
    }
}
