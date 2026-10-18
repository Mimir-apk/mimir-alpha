package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * presses are ignored until the new page shows; Back and the shoulders always work.
 */
class PageChangeInputGateTest {

    @Test
    fun `the pad and the face buttons wait, Back the shoulders and the system keys pass`() {
        val waits = ControllerAction.entries.filter { it.waitsForPageChange() }.toSet()
        assertEquals(
            setOf(
                ControllerAction.NAV_UP,
                ControllerAction.NAV_DOWN,
                ControllerAction.NAV_LEFT,
                ControllerAction.NAV_RIGHT,
                ControllerAction.CONFIRM,
                ControllerAction.SECONDARY,
                ControllerAction.TERTIARY,
                ControllerAction.SYSTEM_START,
                ControllerAction.SYSTEM_SELECT,
            ),
            waits,
        )
    }

    @Test
    fun `the router asks the gate before anything else acts on a press`() {
        val router = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/InputRouter.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/InputRouter.kt"),
        ).first(File::isFile).readText()
        val normalized = router.substringAfter("is InputDecision.Normalized -> {").substringBefore("interactionMotionGate")
        val gate = normalized.indexOf("MimirTransitionInputGate.holding &&")
        assertTrue("the gate is asked", gate >= 0)
        assertTrue(
            "and only the presses that wait for a page are dropped",
            normalized.contains("decision.action.waitsForPageChange()"),
        )
        assertTrue(
            "before the shoulders' mode selection or anything else acts on the press",
            gate in 0 until normalized.indexOf("performHomeModeSelection"),
        )
    }
}
