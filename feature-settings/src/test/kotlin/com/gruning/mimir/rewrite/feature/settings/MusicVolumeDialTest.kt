package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Music's volume dial (spec 2026-09-30, "Volumes"): the pure detent arithmetic and the input
 * contract. The dial is a hard modal on the Focus-highlight dial's precedent, so every action returns true.
 */
class MusicVolumeDialTest {

    @Test
    fun `the step is five percent and the snap lands every level on a detent`() {
        assertEquals(5, MusicVolumeDialStepPercent)
        assertEquals(50, musicVolumeDialSnap(50))
        assertEquals(35, musicVolumeDialSnap(37))
        assertEquals(40, musicVolumeDialSnap(38))
        assertEquals(0, musicVolumeDialSnap(-10))
        assertEquals(100, musicVolumeDialSnap(140))
        (0..100).forEach { level ->
            assertEquals("$level snaps onto a detent", 0, musicVolumeDialSnap(level) % MusicVolumeDialStepPercent)
        }
    }

    @Test
    fun `a step moves one detent and the edges hold`() {
        assertEquals(55, musicVolumeDialStep(50, forward = true))
        assertEquals(45, musicVolumeDialStep(50, forward = false))
        assertEquals("100 stays 100", 100, musicVolumeDialStep(100, forward = true))
        assertEquals("0 stays 0", 0, musicVolumeDialStep(0, forward = false))
        assertEquals("an off-detent level steps from its snapped seat", 40, musicVolumeDialStep(37, forward = true))
    }

    @Test
    fun `a touch on the bar lands on the nearest detent`() {
        assertEquals(0, musicVolumeDialLevelAt(0f))
        assertEquals(50, musicVolumeDialLevelAt(0.5f))
        assertEquals(100, musicVolumeDialLevelAt(1f))
        assertEquals(100, musicVolumeDialLevelAt(7f))
        assertEquals(35, musicVolumeDialLevelAt(0.36f))
    }

    private class Sinks {
        var stepped: Int? = null
        var committed: Int? = null
        var dismissed = 0
    }

    private fun dispatch(level: Int, action: ControllerAction, sinks: Sinks = Sinks()): Pair<Boolean, Sinks> =
        dispatchMusicVolumeDial(
            levelPercent = level,
            action = action,
            onStepTo = { sinks.stepped = it },
            onCommit = { sinks.committed = it },
            onDismiss = { sinks.dismissed += 1 },
        ) to sinks

    @Test
    fun `right steps up and left steps down and both apply through onStepTo`() {
        val (rightHandled, right) = dispatch(50, ControllerAction.NAV_RIGHT)
        assertTrue(rightHandled)
        assertEquals(55, right.stepped)
        assertNull(right.committed)
        assertEquals(0, right.dismissed)

        val (leftHandled, left) = dispatch(50, ControllerAction.NAV_LEFT)
        assertTrue(leftHandled)
        assertEquals(45, left.stepped)
    }

    @Test
    fun `left at zero and right at full are swallowed without a step`() {
        val (_, atZero) = dispatch(0, ControllerAction.NAV_LEFT)
        assertNull("nothing changed, so nothing is applied", atZero.stepped)
        val (handled, atFull) = dispatch(100, ControllerAction.NAV_RIGHT)
        assertTrue("still a hard modal", handled)
        assertNull(atFull.stepped)
    }

    @Test
    fun `A keeps the current level snapped and B hands back to the host`() {
        val (_, kept) = dispatch(37, ControllerAction.CONFIRM)
        assertEquals("the committed level is a detent", 35, kept.committed)
        assertEquals(0, kept.dismissed)

        val (_, reverted) = dispatch(37, ControllerAction.CANCEL)
        assertEquals(1, reverted.dismissed)
        assertNull(reverted.committed)

        val (_, backed) = dispatch(37, ControllerAction.SYSTEM_BACK)
        assertEquals("the system back is the B button", 1, backed.dismissed)
    }

    @Test
    fun `every other action is swallowed so nothing reaches the pane beneath`() {
        ControllerAction.entries
            .filterNot {
                it == ControllerAction.NAV_LEFT || it == ControllerAction.NAV_RIGHT || it == ControllerAction.CONFIRM ||
                    it == ControllerAction.CANCEL || it == ControllerAction.SYSTEM_BACK
            }
            .forEach { action ->
                val (handled, sinks) = dispatch(50, action)
                assertTrue("$action is consumed", handled)
                assertNull("$action steps nothing", sinks.stepped)
                assertNull("$action commits nothing", sinks.committed)
                assertEquals("$action dismisses nothing", 0, sinks.dismissed)
            }
    }

    @Test
    fun `the legend is the colour dial's own and the scope is Music's`() {
        assertEquals("D-pad Adjust · A Keep · B Back", ColorDialControlHint)
        assertEquals("music/volumeDial", MusicVolumeDialScope.value)
    }
}
