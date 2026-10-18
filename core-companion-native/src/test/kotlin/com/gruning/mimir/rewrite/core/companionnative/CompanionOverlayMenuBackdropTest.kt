package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu's openness is published, and it is published from EVERY place that changes it.
 *
 *  On a dual-screen device the thing to blur is Mimir's own `Presentation`, a different
 * window tree from the overlay entirely — so the only way the shelf can react to the menu is for the
 * controller to say so.
 *
 * The hazard this pins is not the publish, it is the places that do NOT publish. The field is assigned
 * at mount, at unmount and at the view swap; only the swap is the one a reader thinks of. A flow fed
 * from the swap alone stays true through an unmount that happened with the menu up — display removed,
 * overlay permission lost, panel revoked — and leaves the surface behind it blurred with no menu on
 * screen and nothing left to close to undo it. Backing the field itself removes the possibility, so
 * what is pinned here is that the field stays backed rather than reverting to a plain `Boolean`.
 *
 * Source-contract, for the reason [PrimaryMIdleThemeRebuildTest] gives: window lifecycle has no
 * JVM-reachable observable.
 */
class CompanionOverlayMenuBackdropTest {

    private val code: String by lazy {
        val file = File(
            "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/" +
                "AndroidCompanionOverlayController.kt",
        )
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        // Comment bodies stripped: the KDoc above the property names the very shape it forbids.
        file.readText().lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun theMenuOpenFieldIsBackedByAPublishedFlow() {
        val property = code.substringAfter("private var menuOpen: Boolean", "")
            .substringBefore("private var ", "")
        assertTrue(
            "menuOpen must be a property over the flow, never a plain field a writer can miss",
            "private var menuOpen: Boolean" in code &&
                "get() = _quickMenuOpen.value" in property &&
                "_quickMenuOpen.value = value" in property,
        )
        assertTrue(
            "every write must land the visual flow on the same value, or a blur outlives the menu it was for",
            "_quickMenuVisualOpen.value = value" in property,
        )
        assertTrue(
            "and both flows must be exposed read-only on the controller contract",
            "override val quickMenuOpen: StateFlow<Boolean> = _quickMenuOpen.asStateFlow()" in code &&
                "override val quickMenuVisualOpen: StateFlow<Boolean> = _quickMenuVisualOpen.asStateFlow()" in code,
        )
        assertEquals(
            "a second plain `menuOpen` field would silently take the writes back",
            0,
            Regex("""private var menuOpen\s*=""").findAll(code).count(),
        )
    }

    @Test
    fun everyWriteToMenuOpenGoesThroughThatProperty() {
        // Assignments, not reads: `menuOpen =` but not `menuOpen ==`.
        val writes = Regex("""\bmenuOpen = (?!=)""").findAll(code).count()
        assertTrue(
            "mount, unmount and the view swap all write it; found $writes",
            writes >= 3,
        )
        val unmount = code.substringAfter("private fun unmountOverlayWindowsOnMain(", "")
            .substringBefore("private fun ", "")
        assertTrue(
            "unmounting with the menu up must publish false, or the backdrop stays treated forever",
            "menuOpen = false" in unmount,
        )
    }

    @Test
    fun theVisualFlowLeadsOnlyWhileTheOutgoingSwapIsInFlight() {
        // Perf 2026-09-11 §4.4b: the scene blur waited for menuOpen, which lands after the 90 ms outgoing fade.
        val swap = code.substringAfter("private fun animateOverlaySwap(", "")
            .substringBefore("private fun addCurrentView(", "")
        assertTrue(
            "the destination value must go out as the outgoing fade starts",
            "_quickMenuVisualOpen.value = targetMenuOpen" in swap,
        )
        val cancel = code.substringAfter("private fun cancelOverlayTransition()", "")
            .substringBefore("private fun ", "")
        assertTrue(
            "a cancelled swap never reaches its menuOpen write, so the lead must end with it",
            "_quickMenuVisualOpen.value = menuOpen" in cancel,
        )
        assertEquals(
            "the window flow itself never leads: the property is its only writer",
            1,
            Regex("""_quickMenuOpen\.value = """).findAll(code).count(),
        )
    }
}
