package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renewal on a degraded manual request: a player's own MANUAL_OVERRIDE fan-control request that degrades (E1's
 * rollback re-apply, landing under a NEW generation) must begin a daemon renewal, mirroring
 * `renewMimirFanOwnershipOnWorker`'s own classification exactly -- staged identity = the active
 * session captured before the transition, settled = `supervisor.activeSession()` after. Before
 * this, `setFanControlV6` -> `transitionFanControlModeOnWorker` -> E1 rollback landed AUTO under
 * a new generation and returned; only the tick-driven renewal and the settle worker ever
 * `begin`/`afterAttempt` a renewal -- the next tick's `forActiveGeneration` dropped any state (the
 * generation never matched), live stayed 4, nothing minted. I1's "renewal PENDING while REFUSED"
 * was not honoured on the toggle path; only the frontend retried, never the daemon (I6).
 * Source-text pin -- `MimirTweakerService` has no host-test seam for
 * `transaction`/`supervisor`/`onTransactionWorker` today (same gap `TransitionManualRollbackTest`,
 * `SelectPathManualFanEntryTest`, and `FanManualReentryTest` document for their own neighbours).
 */
class FanControlV6RenewalTest {

    @Test
    fun `setFanControlV6's handler classifies the transition and begins a renewal on DEGRADED`() {
        val fn = setFanControlV6Fn()
        val transitionIndex = fn.indexOf("transitionFanControlModeOnWorker(")
        val outcomeIndex = fn.indexOf("fanOwnershipSettleOutcome(")
        val beginIndex = fn.indexOf("FanOwnershipRenewalPolicy.begin(")
        assertTrue("transitionFanControlModeOnWorker must still be called", transitionIndex >= 0)
        assertTrue(
            "setFanControlV6's handler must classify the transition's result with fanOwnershipSettleOutcome",
            outcomeIndex >= 0,
        )
        assertTrue(
            "the classification must sit after the transition call, not before it",
            transitionIndex in 0 until outcomeIndex,
        )
        assertTrue(
            "a DEGRADED classification must begin a daemon renewal via FanOwnershipRenewalPolicy.begin",
            beginIndex >= 0,
        )
        assertTrue(
            "the renewal must be begun after the classification, not before it",
            outcomeIndex in 0 until beginIndex,
        )
    }

    @Test
    fun `the classification uses the pre-transition staged identity and a fresh post-transition settled read`() {
        val fn = setFanControlV6Fn()
        val transitionIndex = fn.indexOf("transitionFanControlModeOnWorker(")
        assertTrue("transitionFanControlModeOnWorker must still be called", transitionIndex >= 0)
        val afterTransition = fn.substring(transitionIndex)
        val activeSessionCallsAfter = Regex(Regex.escape("supervisor.activeSession()"))
            .findAll(afterTransition)
            .count()
        assertTrue(
            "the settled identity must be read via a FRESH supervisor.activeSession() call, " +
                "after the transition -- found $activeSessionCallsAfter call(s) after " +
                "transitionFanControlModeOnWorker(",
            activeSessionCallsAfter >= 1,
        )
        assertTrue(
            "stagedProfileId passed to the classification must be the PRE-transition active " +
                "identity, not a post-transition read",
            fn.contains("stagedProfileId = active.appliedTargetContract?.profileId"),
        )
        assertTrue(
            "stagedGeneration passed to the classification must be the PRE-transition active " +
                "identity, not a post-transition read",
            fn.contains("stagedGeneration = active.generation"),
        )
    }

    @Test
    fun `the classification runs only for a MANUAL_OVERRIDE request, never for the exit-to-Auto direction`() {
        val fn = setFanControlV6Fn()
        val guardIndex = fn.indexOf("TuningPlanner.FanIntent.MANUAL_OVERRIDE)")
        val outcomeIndex = fn.indexOf("fanOwnershipSettleOutcome(")
        assertTrue(
            "the classification must be reachable only when this request's fanIntent is MANUAL_OVERRIDE",
            guardIndex >= 0,
        )
        assertTrue(
            "the MANUAL_OVERRIDE guard must enclose the classification, i.e. sit before it",
            guardIndex in 0 until outcomeIndex,
        )
    }

    @Test
    fun `setFanControlV6's handler does not change the response it returns to the client`() {
        val fn = setFanControlV6Fn()
        assertTrue(
            "the handler must still return the transition's own response, unmodified, as the " +
                "last expression of the requiresTransition branch",
            fn.contains("return@onTransactionWorker response"),
        )
    }

    private fun setFanControlV6Fn(): String = sliceBetween(
        mainSourceText("MimirTweakerService.kt"),
        "override fun setFanControlV6(",
        "override fun getFpsLimiterCapabilityV7(",
    )

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }
}
