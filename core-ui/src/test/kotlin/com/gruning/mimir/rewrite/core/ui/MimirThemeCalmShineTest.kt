package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
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
 * Calm shine reaches every window and offers subdued effects for sensitive users. The switch is snapshot state
 * ([MimirCalmShineSettings], persisted by the app) and [MimirTheme], which every window wraps its content in, publishes
 * it as [LocalMimirCalmShine]. Off, the default, publishes false, so a themed window shines exactly as it always did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirThemeCalmShineTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun reset() {
        // The holder is process-wide: a test that leaves it on would tone down every later themed test.
        MimirCalmShineSettings.enabled = false
        Snapshot.sendApplyNotifications()
    }

    /** Composes a themed window and returns what a gleam inside it would read, as of the latest composition. */
    private fun readThroughTheme(): () -> Boolean? {
        var seen: Boolean? = null
        compose.setContent { MimirTheme { seen = LocalMimirCalmShine.current } }
        compose.waitForIdle()
        return { seen }
    }

    @Test
    fun `calm shine is off by default, so a themed window shines exactly as it always did`() {
        assertFalse(MimirCalmShineSettings.enabled)
        assertEquals(false, readThroughTheme()())
    }

    @Test
    fun `a window that wears the theme reads the switch when it is on`() {
        MimirCalmShineSettings.enabled = true
        Snapshot.sendApplyNotifications()
        assertEquals(true, readThroughTheme()())
    }

    @Test
    fun `flipping the switch reaches a window that is already showing`() {
        val seen = readThroughTheme()
        assertEquals(false, seen())

        MimirCalmShineSettings.enabled = true
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("on reaches it live", true, seen())

        MimirCalmShineSettings.enabled = false
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("and off returns it to today's shine", false, seen())
    }

    @Test
    fun `the theme is the one place that publishes the switch, from one snapshot holder`() {
        val theme = coreUiSource("MimirTheme.kt")
        assertTrue(theme.contains("LocalMimirCalmShine provides MimirCalmShineSettings.enabled,"))
        val holder = coreUiSource("MimirAppearanceOverrides.kt")
        assertTrue(holder.contains("object MimirCalmShineSettings {"))
        assertTrue("off until a stored yes is loaded", holder.contains("var enabled by mutableStateOf(false)"))
        val providers = coreUiDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("LocalMimirCalmShine provides") }
            .map { it.name }
            .toList()
        assertEquals("no window provides its own", listOf("MimirTheme.kt"), providers)
    }

    private fun coreUiDirectory(): File = listOf(
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui"),
    ).firstOrNull(File::isDirectory) ?: error("core-ui sources not found from ${File(".").absolutePath}")

    private fun coreUiSource(name: String): String = File(coreUiDirectory(), name).readText()
}
