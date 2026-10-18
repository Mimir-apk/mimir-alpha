package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5d: the native ᛗ overlay takes its theme from one push, which the companion runtime starts with the overlay.
 *
 * `CompanionOverlayProcessThemeTest` drives `startCompanionOverlay` with the real controller. These pins say the runtime
 * gets its overlay from that start, before the ᛗ topology can target it, and that no second push remains. A second push
 * was MainActivity's ThemeStore collector, the only one before round 5d, which stops with the Activity and does not exist
 * in a process the platform restarted for the Bottom.
 */
class CompanionOverlayThemePushSourceTest {

    @Test
    fun `the companion runtime takes its overlay from the themed start before the ᛗ topology can target it`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionProcessRuntime.kt")
        assertTrue("CompanionProcessRuntime.kt must be readable from the module dir", file.isFile)
        val lines = file.readLines().map { it.substringBefore("//").trim() }
        val starts = lines.withIndex()
            .filter { it.value == "val overlay = startCompanionOverlay(context, processScope)" }
            .map { it.index }
        assertEquals("CompanionProcessRuntime.create must take its overlay from startCompanionOverlay once", 1, starts.size)
        assertTrue(
            "no overlay instance may bypass the themed start",
            lines.none { "AndroidCompanionOverlayController.instance(" in it },
        )
        val topology = lines.indexOfFirst { it.startsWith("val mButtonTopology = MButtonTopologyController(") }
        assertTrue("fixture: the ᛗ topology is built in create ($topology)", topology >= 0)
        assertTrue("the themed start must run before the topology can target the overlay", starts.single() < topology)
    }

    @Test
    fun `the process-start collector is the only production push of a theme into the overlay`() {
        val root = File("src/main/kotlin")
        assertTrue("fixture: the app's main sources are readable from the module dir", root.isDirectory)
        val pushes = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        val code = line.substringBefore("//").trim()
                        !code.startsWith("*") && !code.startsWith("/*") && ".setOverlayTheme(" in code
                    }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
            .toList()
        assertEquals(
            "one push, startCompanionOverlay's ThemeStore collector (found at $pushes)",
            listOf("CompanionOverlayThemeAdapter.kt"),
            pushes.map { it.substringBefore(":") },
        )
    }
}
