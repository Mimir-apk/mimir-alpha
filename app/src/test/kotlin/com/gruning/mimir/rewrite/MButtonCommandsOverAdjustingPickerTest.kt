package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerCommand
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerTransition
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M button's Home, Settings, Utility and Tweaker commands sweep every overlay with `dismissConflictingModals()` and send each the ONE CANCEL.
 * In the Mimir-button picker's ADJUST mode  CANCEL means "cancel the adjust": the placement being shaped is dropped, the browse
 * view comes back and the picker is still open. So the sweep's single CANCEL left the picker on top of the command -- Home navigated beneath it,
 * Utility opened buried under it -- the folder browser at depth of walk-32k (`MButtonCommandsOverKeptSurfaceTest`) in a new costume.
 *
 * The rule is the browser's: while adjusting, the picker is closed ENTIRELY in one step, and it writes nothing (B's own rule: the saved placement
 * stays). Not adjusting, CANCEL already dismisses the picker and the sweep still sends it. The real picker state and transitions, the real sweep, the
 * stack and the call sites' order ([OverlayStackHarness]) are the production ones; the hook's decision is production's own
 * `closeMimirButtonPickerWhileAdjusting`, and its wiring in the factory is pinned at source level below.
 */
class MButtonCommandsOverAdjustingPickerTest {

    private val star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", "/none/aaaaaaaa.png", MimirMarkPlacement(1.2f, 3f, -1.5f))

    private fun browsing(): MimirButtonPickerState = mimirButtonPickerInitialState(null, listOf(star))

    private fun adjusting(): MimirButtonPickerState =
        mimirButtonPickerTransition(browsing(), ControllerAction.TERTIARY, mimirButtonTileKey(star.id)).state.also {
            checkNotNull(it.adjust) { "Y on a saved image opens adjust" }
        }

    /** One run: the picker and what closing it did, with the harness's sweep wired through production's decision. */
    private class Run(private val picker: MimirButtonPickerState) {
        var current: MimirButtonPickerState? = picker
        var closes = 0

        val harness = OverlayStackHarness(
            MimirOverlayKind.MBUTTON_PICKER,
            fullClose = { kind ->
                closeMimirButtonPickerWhileAdjusting(kind, current) {
                    closes += 1
                    current = null
                }
            },
        )
    }

    // ---- the defect, as it was ----------------------------------------------------------------------------------------------

    @Test
    fun `in adjust mode the one CANCEL the sweep sends leaves the picker open`() {
        val after = mimirButtonPickerTransition(adjusting(), ControllerAction.CANCEL, focused = null)

        assertNull("cancelled the adjust", after.state.adjust)
        assertEquals(MimirButtonPickerCommand.LeaveAdjust(star.id), after.command)
        assertFalse("and that is not a dismissal: the picker is still there", after.command == MimirButtonPickerCommand.Dismiss)
    }

    // ---- the rule -------------------------------------------------------------------------------------------------------------

    @Test
    fun `M to Home while adjusting closes the picker entirely and writes nothing`() {
        val run = Run(adjusting())

        run.harness.pressMNavigation()

        assertEquals("closed in one step", listOf(MimirOverlayKind.MBUTTON_PICKER), run.harness.fullCloses)
        assertEquals(emptyList<MimirOverlayKind>(), run.harness.cancels)
        assertEquals(1, run.closes)
        assertNull("the picker's state is gone", run.current)
        assertNull("nothing is left on the stack", run.harness.top)
        assertEquals(listOf("sweepDone", "closeUtility", "applyMButtonHomeNavigation"), run.harness.settled)
    }

    @Test
    fun `M to Settings, Utility and Tweaker while adjusting close it the same way`() {
        val settings = Run(adjusting())
        settings.harness.pressMNavigation(settings = true)
        assertEquals(listOf(MimirOverlayKind.MBUTTON_PICKER), settings.harness.fullCloses)
        assertNull(settings.harness.top)
        assertEquals("openSettings", settings.harness.settled.last())

        val utility = Run(adjusting())
        utility.harness.pressMUtility()
        assertEquals(listOf(MimirOverlayKind.MBUTTON_PICKER), utility.harness.fullCloses)
        assertEquals(emptyList<MimirOverlayKind>(), utility.harness.cancels)
        assertEquals("Utility is on top, not buried beneath the picker", listOf(MimirOverlayKind.BRIGHTNESS), utility.harness.entries)
    }

    @Test
    fun `not adjusting, the picker is sent its CANCEL as before`() {
        val run = Run(browsing())

        run.harness.pressMNavigation()

        assertEquals("no full close for a picker that is only browsing", emptyList<MimirOverlayKind>(), run.harness.fullCloses)
        assertEquals("the sweep sent the one CANCEL, which dismisses a picker that is not adjusting", listOf(MimirOverlayKind.MBUTTON_PICKER), run.harness.cancels)
        assertEquals(0, run.closes)
    }

    @Test
    fun `the decision itself, kind by kind`() {
        var closed = 0
        val close = { closed += 1 }
        val adjusting = adjusting()

        assertTrue(closeMimirButtonPickerWhileAdjusting(MimirOverlayKind.MBUTTON_PICKER, adjusting, close))
        assertEquals(1, closed)
        assertFalse("browsing: declined, so the sweep sends CANCEL", closeMimirButtonPickerWhileAdjusting(MimirOverlayKind.MBUTTON_PICKER, browsing(), close))
        assertFalse("no picker state at all", closeMimirButtonPickerWhileAdjusting(MimirOverlayKind.MBUTTON_PICKER, null, close))
        for (kind in MimirOverlayKind.entries.filter { it != MimirOverlayKind.MBUTTON_PICKER }) {
            assertFalse("$kind is not the picker's to close", closeMimirButtonPickerWhileAdjusting(kind, adjusting, close))
        }
        assertEquals("only the first call closed anything", 1, closed)
    }

    @Test
    fun `closing while adjusting drops the placement being shaped and never saves it`() {
        // Shape it first: three half-dp nudges right and one size step, so the working placement is NOT the saved one.
        val shaped = listOf(ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.PAGE_RIGHT)
            .fold(adjusting()) { state, action -> mimirButtonPickerTransition(state, action, focused = null).state }
        val working = shaped.adjust!!.working
        assertEquals(MimirMarkPlacement(1.25f, 4.5f, -1.5f).offsetX, working.offsetX, 0f)
        assertEquals("the saved list still holds the placement it was opened with", star.placement, shaped.saved.single().placement)

        val run = Run(shaped)
        run.harness.pressMNavigation()

        assertNull("the picker is closed, with its working placement", run.current)
        assertEquals(1, run.closes)
        // Nothing in the sweep's path is a SaveAdjusted: the only thing it called was the close, which the source pin below shows writes nothing.
        assertEquals(star.placement, shaped.saved.single().placement)
    }

    // ---- production's wiring --------------------------------------------------------------------------------------------------

    @Test
    fun `the factory's hook keeps the folder browser's arm and adds the picker's, reading the live adjust state`() {
        val factory = sourceFile("MimirOverlayCoordinator.kt")
        val hook = factory.substringAfter("closeEntirely = { kind ->").substringBefore("\n    )\n}")
        assertTrue(hook.contains("if (kind == MimirOverlayKind.FOLDER_BROWSER) {"))
        assertTrue(
            "the picker's arm asks the live picker state and closes through the settings coordinator's own close",
            hook.contains("closeMimirButtonPickerWhileAdjusting(kind, settings.mimirButtonPicker, settings.closeMimirButtonPicker)"),
        )
        val decision = factory.substringAfter("internal fun closeMimirButtonPickerWhileAdjusting(").substringBefore("\n}\n")
        assertTrue("it is the adjust state that decides", decision.contains("picker?.adjust == null"))
        assertTrue(decision.contains("kind != MimirOverlayKind.MBUTTON_PICKER"))

        val settings = sourceFile("MimirSettingsCoordinator.kt")
        val close = settings.substringAfter("val closeMimirButtonPicker = {").substringBefore("\n    }\n")
        assertTrue("the close pops the picker's scope and clears its state, and writes nothing", close.contains("authority.popScope()") && close.contains("state.mimirButtonPicker = null"))
        assertFalse(close.contains("AppearanceOverrideStore"))
        assertTrue("and it is on the coordinator the overlay factory reads", settings.contains("closeMimirButtonPicker = closeMimirButtonPicker,"))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }
}
