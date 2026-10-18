package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RG-2 (alpha audit 2026-09-30). The Library's three waits for covers -- the page entry, the Carousel/List change and
 * the L2/R2 platform swap -- each cap the wait at `MimirPageMotion.SettleCapMs`, and the gate they hold lapses that cap,
 * the lead and the exit after it was taken. A wait that ended late left the arrival less than its lead and exit, so each
 * of them re-arms the gate for the lead and the exit when the wait ends, and each logs how long it waited for the RG.
 *
 * The page entry and the platform swap run on Compose harnesses (LibraryPageMotionTest, LibraryGamelistSwapMotionTest);
 * this pins the third, which has no probe of its own, and that all three call it.
 */
class LibrarySettleRearmTest {

    private val source: String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    private fun rearm(owner: String) =
        "MimirTransitionInputGate.rearm($owner, MimirPageMotion.LeadMs + MimirPageMotion.ExitMs)"

    @Test
    fun `each of the three waits re-arms the gate the moment it ends, and no other place does`() {
        assertEquals(3, Regex(Regex.escape("MimirTransitionInputGate.rearm(")).findAll(source).count())

        val entry = source.substringAfter("is LibraryPageMotion.Entry -> {").substringBefore("arrived = true")
        assertTrue("the page entry re-arms its own hold", entry.contains(rearm("gateOwner")))
        assertTrue(
            "after the wait and before the exit starts",
            entry.indexOf("settling = false") < entry.indexOf(rearm("gateOwner")) &&
                entry.indexOf(rearm("gateOwner")) < entry.indexOf("val exit = tween<Float>("),
        )

        val view = source.substringAfter("internal fun GamelistViewModeLandingTransition(")
            .substringBefore("internal fun GamelistDestinationPageTransition(")
        assertTrue("the view change re-arms the hold it took for its progress", view.contains(rearm("progress")))
        assertTrue(
            "after the wait and before the arrival",
            view.indexOf("departureReleased = true") < view.indexOf(rearm("progress")) &&
                view.indexOf(rearm("progress")) < view.indexOf("progress.animateTo("),
        )

        val swap = source.substringAfter("internal fun GamelistDestinationPageTransition(")
            .substringBefore("MimirPageHandoff(")
        assertTrue("the platform swap re-arms its own hold", swap.contains(rearm("gateOwner")))
        assertTrue(
            "inside the wait it re-arms after, never for a burst that waits for nothing",
            swap.indexOf("if (!burst) {") < swap.indexOf(rearm("gateOwner")) &&
                swap.indexOf(rearm("gateOwner")) < swap.indexOf("settlingState.value = false"),
        )
    }

    @Test
    fun `all three waits log how long they waited, and which one it was`() {
        assertEquals(3, Regex("\"MimirSettle\"").findAll(source).count())
        assertTrue(source.contains("capped=\$capped site=view"))
        assertTrue(source.contains("capped=\$capped site=platform"))
        // The page entry's own line is the one without a site, as it always was.
        assertTrue(source.contains("capped=\${!settled}\","))
    }
}
