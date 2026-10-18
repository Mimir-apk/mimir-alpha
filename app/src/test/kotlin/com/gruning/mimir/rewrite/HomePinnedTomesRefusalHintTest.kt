package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 (product review 2026-09-28; nova-6, thor-11, eu-thor-18): R1 on Home with no pinned Tome shows one hint instead
 * of nothing, and pressing it again does not stack a second card.
 */
class HomePinnedTomesRefusalHintTest {

    @Test
    fun `R1 is refused for want of a pinned Tome only on Editor's Choice with none pinned`() {
        assertTrue(
            homePinnedTomesRefusalApplies(viewModeToggleAvailable = false, viewMode = HomeViewMode.EDITORS_CHOICE),
        )
        assertFalse(
            "with a pinned Tome the switch works, so there is nothing to explain",
            homePinnedTomesRefusalApplies(viewModeToggleAvailable = true, viewMode = HomeViewMode.EDITORS_CHOICE),
        )
        assertFalse(
            "already on Pinned Tomes the switch back must stay possible",
            homePinnedTomesRefusalApplies(viewModeToggleAvailable = false, viewMode = HomeViewMode.PINNED_TOMES),
        )
    }

    @Test
    fun `the hint is plain guidance that points at the control`() {
        assertEquals("No pinned Tomes yet, X to make one", HomePinnedTomesRefusalHint)
        assertEquals(
            "nothing failed, and Warning would play the error sound",
            MimirMessageClass.Guidance,
            HomePinnedTomesRefusalClass,
        )
    }

    /**
     * The rail coalesces by message class, text and supporting line (`MimirMessage.fingerprint`, internal to
     * core-ui). Two refusals differ only in their event id, so a repeat inside the 2.4 s window keeps the card
     * that is already showing rather than stacking or restarting it.
     */
    @Test
    fun `repeated refusals are one message to the rail`() {
        fun refusal(eventId: Long) = MimirMessage(
            eventId = eventId,
            text = HomePinnedTomesRefusalHint,
            messageClass = HomePinnedTomesRefusalClass,
        )
        val first = refusal(1L)
        val second = refusal(2L)
        assertNotEquals("each press is still its own event", first.eventId, second.eventId)
        assertEquals(first.copy(eventId = 0L), second.copy(eventId = 0L))
    }

    @Test
    fun `the router keeps its shoulder arm for the refusal and R1 publishes the hint`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("MimirAppCoordinator.kt not found")
        val wiring = source.substringAfter("inputRouter.homeViewModeSelectEnabled = {")
            .substringBefore("inputRouter.onControllerShortcut")
        assertTrue(wiring.contains("homePinnedTomesRefusalOffered(currentBindings.value)"))
        assertTrue(wiring.contains("if (right) current.homeShelf.refusePinnedTomesSwitch()"))
    }
}
