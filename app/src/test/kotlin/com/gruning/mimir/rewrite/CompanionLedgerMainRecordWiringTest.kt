package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session ledger is authoritative: SWAP reads the session ledger directly, and the
 * exchange's 3 s agreement window goes away.
 *
 * Both sites run inside MainActivity: the menu handler and a `lifecycleScope` coroutine on a real Activity, which this
 * module has no harness to construct. The wiring is therefore pinned as source here. The behaviour behind it — the card
 * as a view of the ledger, and the landing settlement the exchange now applies itself — is tested in
 * `CompanionRunningAppStateTest`.
 */
class CompanionLedgerMainRecordWiringTest {

    /** The SWAP branch up to the plan: everything that decides who occupies each screen. */
    private fun swapPlanningInputs(): String =
        sourceFile("MainActivity.kt")
            .substringAfter("QuickMenuItem.SECONDARY_SWAP -> {")
            .substringBefore("val plan = companionSwapPlan(")

    private fun exchangeBody(): String =
        sourceFile("MainActivity.kt")
            .substringAfter("private fun exchangeCompanionApps(")
            .substringBefore("private fun moveSecondaryAppToPrimary(")

    @Test
    fun swapPlanningReadsWhoOccupiesMainFromTheLedgerAlone() {
        val inputs = swapPlanningInputs()

        assertTrue("SWAP must read Main's occupant from the session ledger", inputs.contains(".primaryOccupant()"))
        assertTrue(inputs.contains("MimirSessionAuthority.state.value"))
        assertFalse(
            "SWAP must not read the transfer card as a second record of Main",
            inputs.contains("CompanionRunningAppState"),
        )
    }

    @Test
    fun theExchangeHasNoAgreementWindowAndSettlesTheLandingBeforeLegThree() {
        val body = exchangeBody()

        assertFalse("the 3 s agreement wait is gone", body.contains("withTimeoutOrNull(3_000L)"))
        assertFalse("leg 3 no longer launches past a disagreement", body.contains("launching anyway"))
        val settle = body.indexOf("settleCompanionMainAppLandedOnBottom(")
        val legThree = body.indexOf("CompanionQuickLaunchPrimaryRoute.dispatch(")
        assertTrue("the exchange settles the landing in the ledger itself", settle >= 0)
        assertTrue("leg 3 still dispatches through the dock's Main route", legThree >= 0)
        assertTrue("the settlement precedes leg 3's admission", settle < legThree)
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
