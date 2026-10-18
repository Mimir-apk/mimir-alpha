package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick-launch acknowledgement wiring:
 * the M-button's QUICK_LAUNCH command discarded `executeQuickLaunchInPlace(...)`'s
 * `QuickLaunchInPlaceOutcome` as a bare statement, then unconditionally acknowledged the request. Both
 * refusal arms (`REFUSED_OBSTRUCTED`, `REFUSED_STARTUP_RESTORE`) are documented "honest refusal[s]" the
 * player is meant to retry (`MButtonQuickLaunchInPlace.kt`'s own KDoc: "the user presses again once the
 * obstruction resolves"), but a physical M-button press that silently does nothing is indistinguishable
 * from a broken button. `executeQuickLaunchInPlace` itself is pure and already JVM-tested; this pins the
 * coordinator wiring around it — the call lives inside a `LaunchedEffect` no Robolectric harness in this
 * module can drive with a real `Toast`, the same accepted gap `SessionMutexRefusalWiringTest` and
 * `MediaMenuHonestyCoordinatorWiringTest` document and accept for the identical reason.
 *
 * PLAYER-FACING COPY — [QuickLaunchNotReadyCopy] ("Quick launch isn’t ready yet. Try again in a moment.")
 * and [QuickLaunchBlockedCopy] are proposed copy pending review. The second arrived with
 * walk-32k (2026-09-30), when the shared retry sentence was shown to be false for an obstruction.
 */
class QuickLaunchInPlaceWiringTest {

    /** The whole `if (request.command == MButtonPrimaryCommand.QUICK_LAUNCH) { ... }` body. */
    private fun quickLaunchBlock(): String =
        sourceFile("MimirAppCoordinator.kt")
            .substringAfter("if (request.command == MButtonPrimaryCommand.QUICK_LAUNCH) {")
            .substringBefore("if (request.command == MButtonPrimaryCommand.UTILITY ||")

    @Test
    fun theOutcomeIsBoundNotDiscarded() {
        assertTrue(
            "executeQuickLaunchInPlace's result must be bound to a val, not called as a bare statement",
            quickLaunchBlock().contains("val quickLaunchOutcome = executeQuickLaunchInPlace("),
        )
    }

    /**
     * walk-32k (2026-09-30) changed this. The two refusal arms used to share one when-arm and one sentence, "try
     * again in a moment" — true for the startup restore, false for an obstruction the player has to finish or
     * close. Each arm now toasts its own copy, and the obstruction's is chosen by [quickLaunchObstructionCopy] from
     * the overlay that refused (still on top after a refusal).
     */
    @Test
    fun eachRefusalArmToastsItsOwnCopyNotOneSharedRetrySentence() {
        val whenBlock = quickLaunchBlock()
            .substringAfter("when (quickLaunchOutcome) {")
            .substringBefore("MButtonNavigationCommands.acknowledge(request.id)")

        val startupArm = whenBlock
            .substringAfter("QuickLaunchInPlaceOutcome.REFUSED_STARTUP_RESTORE ->")
            .substringBefore("QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED ->")
        assertTrue(
            "the startup restore is the transient refusal and keeps the retry sentence",
            startupArm.contains("QuickLaunchNotReadyCopy"),
        )
        val obstructedArm = whenBlock
            .substringAfter("QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED ->")
            .substringBefore("QuickLaunchInPlaceOutcome.OPENED, QuickLaunchInPlaceOutcome.RECOVERED_EXISTING")
        assertTrue(
            "an obstruction's copy follows the overlay that refused",
            obstructedArm.contains("quickLaunchObstructionCopy(overlays.top)"),
        )
        assertTrue(
            "the obstruction arm must not fall back to the retry sentence as a literal",
            !obstructedArm.contains("Try again"),
        )
        assertTrue(
            "OPENED and RECOVERED_EXISTING (success paths) must not toast",
            whenBlock.contains("QuickLaunchInPlaceOutcome.OPENED, QuickLaunchInPlaceOutcome.RECOVERED_EXISTING -> Unit"),
        )
    }

    /** Where proposed Quick Launch refusal copy is pinned; final wording remains under review. */
    @Test
    fun theRefusalCopyIsPinnedVerbatim() {
        assertEquals("Quick launch isn’t ready yet. Try again in a moment.", QuickLaunchNotReadyCopy)
        assertEquals("Finish or close this first, then open Quick launch.", QuickLaunchBlockedCopy)
    }

    @Test
    fun theRequestIsStillAcknowledgedOnEveryOutcome() {
        // Healthy-path pin: acknowledging the M-button press must not become conditional on the fix —
        // it sits after the `when`, outside every arm.
        assertTrue(quickLaunchBlock().contains("MButtonNavigationCommands.acknowledge(request.id)"))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
