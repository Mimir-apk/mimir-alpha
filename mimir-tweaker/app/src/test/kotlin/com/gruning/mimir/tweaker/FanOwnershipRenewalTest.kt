package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for
 * [fanOwnershipSettleOutcome] (the settle worker's honest-vs-degraded decision after a
 * `TRANSITION_TO_MANUAL` attempt) and [FanOwnershipRenewalPolicy] (the tick-driven retry state
 * machine for a SMART-staged contract under an active session).
 *
 * Red-first history for [fanOwnershipSettleOutcome]: 0b203f3e's settle worker treated every
 * non-success identically (DEGRADED_VENDOR_SMART, `return stagedResult` unmodified). The first two
 * pins below (`SETTLED_MANUAL`, `DEGRADED_VENDOR_SMART`) already passed under that interim
 * behaviour; the `SESSION_LOST` pin is the one that turned this red .
 */
class FanOwnershipRenewalTest {
    @Test
    fun `both unavailable-owner retries preserve a different generation's renewal`() {
        val fn = sliceBetween(mainSourceText("MimirTweakerService.kt"),
            "private fun renewMimirFanOwnershipOnWorker(", "val code = response.getString(")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }
        val guards = Regex(Regex.escape("if (it.generation == active.generation && it.transactionId == active.transactionId)"))
            .findAll(fn).toList()
        assertEquals(2, guards.size)
        for (guard in guards) {
            val body = fn.substring(guard.range.first).substringBefore("FanOwnershipObservability.onRenewAttempt(")
            assertTrue(body.contains("FanOwnershipRenewalPolicy.afterAttempt(it, \"OWNER_UNAVAILABLE\""))
            assertTrue("a stale callback must preserve the newer state", body.contains("} else it"))
        }
    }

    // --- fanOwnershipSettleOutcome -----------------------------------------------------------
    //
    // The settle outcome check was widened from a
    // generation-only comparison to a scope+profile-aware one. For recovery, PostTransactionDisposition.RetainRecovery re-adopts the SAME generation with
    // scope = RECOVERY -- the old generation-only DEGRADED check misread that as a degrade and
    // kept renewal retrying a recovery session forever. E1's own A' rollback re-apply is the
    // other half of why generation equality can no longer gate the degrade case: a MANUAL entry
    // that fails and rolls forward into a PROFILE_AUTO re-apply lands under a NEW generation, not
    // the staged one -- "a re-apply that lands under a DIFFERENT generation is also session-lost"
    // (this file's own pre-E2 pin) FLIPS under E2: that is now exactly E1's own degrade shape,
    // and generation is no longer what distinguishes it from session-lost -- profile identity is.

    @Test
    fun `a successful transition into Manual settles, under SYSTEM scope, matching profile`() {
        assertEquals(
            FanOwnershipSettleOutcome.SETTLED_MANUAL,
            fanOwnershipSettleOutcome(
                transitionSucceeded = true,
                settledFanMode = TuningPlanner.FAN_MANUAL,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `a pre-restore rejection degrades to vendor Smart -- same profile, SYSTEM scope, same generation`() {
        // The transition refused before proceed ever ran restoreBound/clearSession -- the staged
        // generation this apply committed is still the one active.
        assertEquals(
            FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART,
            fanOwnershipSettleOutcome(
                transitionSucceeded = false,
                settledFanMode = TuningPlanner.FAN_SMART,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `E1 A prime -- a rollback re-apply that lands under a DIFFERENT generation still degrades, not session-lost`() {
        // E1's own rollback re-apply (PROFILE_AUTO after a failed MANUAL_OVERRIDE) is a FRESH
        // Guardian transaction -- its generation legitimately differs from the one this renewal
        // attempt staged. Same profile + SYSTEM scope is what proves this is still the same
        // apply's session now that generation can no longer do that job.
        assertEquals(
            FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART,
            fanOwnershipSettleOutcome(
                transitionSucceeded = false,
                settledFanMode = TuningPlanner.FAN_SMART,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 9L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `a failure after the restore reports session-lost, never a degraded staged apply`() {
        // proceed ran restoreBound(...) + clearSession() and the re-apply then failed to land --
        // there is no active session carrying the staged generation any more.
        assertEquals(
            FanOwnershipSettleOutcome.SESSION_LOST,
            fanOwnershipSettleOutcome(
                transitionSucceeded = false,
                settledFanMode = null,
                settledScope = null,
                settledProfileId = null,
                stagedProfileId = "balanced",
                settledGeneration = null,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `F2 -- a RECOVERY-scoped re-adoption is session-lost, never degraded, even with the same generation`() {
        // PostTransactionDisposition.RetainRecovery re-adopts the SAME generation with
        // scope = RECOVERY. The old generation-only DEGRADED check misread this as a degrade and
        // kept renewal retrying a recovery session forever (F2, spec §5 adversarial review).
        assertEquals(
            FanOwnershipSettleOutcome.SESSION_LOST,
            fanOwnershipSettleOutcome(
                transitionSucceeded = false,
                settledFanMode = TuningPlanner.FAN_SMART,
                settledScope = ActiveSessionScope.RECOVERY,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `a SMART settle under SYSTEM scope but a DIFFERENT profile is session-lost, not degraded`() {
        assertEquals(
            FanOwnershipSettleOutcome.SESSION_LOST,
            fanOwnershipSettleOutcome(
                transitionSucceeded = false,
                settledFanMode = TuningPlanner.FAN_SMART,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "performance",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `success alone is not enough -- the settled fan mode must actually be Manual`() {
        assertEquals(
            FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART,
            fanOwnershipSettleOutcome(
                transitionSucceeded = true,
                settledFanMode = TuningPlanner.FAN_SMART,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    @Test
    fun `success alone is not enough either -- SYSTEM scope and matching profile are both required to settle`() {
        assertEquals(
            "a MANUAL settle outside SYSTEM scope is session-lost, not settled",
            FanOwnershipSettleOutcome.SESSION_LOST,
            fanOwnershipSettleOutcome(
                transitionSucceeded = true,
                settledFanMode = TuningPlanner.FAN_MANUAL,
                settledScope = ActiveSessionScope.LAUNCH,
                settledProfileId = "balanced",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
        assertEquals(
            "a MANUAL settle under a DIFFERENT profile is session-lost, not settled",
            FanOwnershipSettleOutcome.SESSION_LOST,
            fanOwnershipSettleOutcome(
                transitionSucceeded = true,
                settledFanMode = TuningPlanner.FAN_MANUAL,
                settledScope = ActiveSessionScope.SYSTEM,
                settledProfileId = "performance",
                stagedProfileId = "balanced",
                settledGeneration = 7L,
                stagedGeneration = 7L,
            ),
        )
    }

    // --- rekeyRenewalToSettledGeneration (E1/E2 seam) -----------------------------------------

    @Test
    fun `rekeyRenewalToSettledGeneration re-keys onto the settled identity, preserving attempts`() {
        val state = FanOwnershipRenewalPolicy.begin(
            generation = 7L,
            transactionId = "tx-7",
            code = "FAN_HANDOFF_UNAVAILABLE",
            nowElapsedMs = 1_000L,
        )
        val retried = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", 20_000L)
        assertEquals(1, retried.attempts)

        val rekeyed = rekeyRenewalToSettledGeneration(
            retried,
            settledGeneration = 9L,
            settledTransactionId = "tx-9",
        )
        assertEquals(9L, rekeyed.generation)
        assertEquals("tx-9", rekeyed.transactionId)
        assertEquals(
            "attempts/code/schedule survive the re-key -- only identity changes",
            1,
            rekeyed.attempts,
        )
        assertEquals("CODE_2", rekeyed.code)
        assertEquals(retried.nextAttemptAtElapsedMs, rekeyed.nextAttemptAtElapsedMs)
    }

    @Test
    fun `rekeyRenewalToSettledGeneration leaves state untouched when there is no settled identity`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)
        assertEquals(state, rekeyRenewalToSettledGeneration(state, settledGeneration = null, settledTransactionId = null))
        assertEquals(
            state,
            rekeyRenewalToSettledGeneration(state, settledGeneration = 9L, settledTransactionId = null),
        )
        assertEquals(
            state,
            rekeyRenewalToSettledGeneration(state, settledGeneration = null, settledTransactionId = "tx-9"),
        )
    }

    @Test
    fun `re-keying onto the SAME identity is a no-op -- the ordinary same-generation degrade is unaffected`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)
        val rekeyed = rekeyRenewalToSettledGeneration(state, settledGeneration = 7L, settledTransactionId = "tx-7")
        assertEquals(state, rekeyed)
    }

    // --- E5: renewalScopeAdmitted ---------------------------------------------------------------

    @Test
    fun `renewalScopeAdmitted admits only SYSTEM scope`() {
        assertTrue(renewalScopeAdmitted(ActiveSessionScope.SYSTEM))
        assertFalse(
            "a LAUNCH-scope session must never begin or keep a renewal state",
            renewalScopeAdmitted(ActiveSessionScope.LAUNCH),
        )
        assertFalse(
            "a RECOVERY-scoped session must never begin or keep a renewal state",
            renewalScopeAdmitted(ActiveSessionScope.RECOVERY),
        )
    }

    // --- FanOwnershipRenewalPolicy ------------------------------------------------------------

    @Test
    fun `begin starts at zero attempts and is due after the first-retry delay`() {
        val state = FanOwnershipRenewalPolicy.begin(
            generation = 7L,
            transactionId = "tx-7",
            code = "FAN_HANDOFF_UNAVAILABLE",
            nowElapsedMs = 1_000L,
        )
        assertEquals(7L, state.generation)
        assertEquals("tx-7", state.transactionId)
        assertEquals("FAN_HANDOFF_UNAVAILABLE", state.code)
        assertEquals(0, state.attempts)
        assertEquals(1_000L + FanOwnershipRenewalPolicy.FIRST_RETRY_MS, state.nextAttemptAtElapsedMs)
    }

    @Test
    fun `due is false before the delay elapses and true once it has`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)

        assertFalse(
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = 7L,
                nowElapsedMs = 1_000L + FanOwnershipRenewalPolicy.FIRST_RETRY_MS - 1,
            ),
        )
        assertTrue(
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = 7L,
                nowElapsedMs = 1_000L + FanOwnershipRenewalPolicy.FIRST_RETRY_MS,
            ),
        )
    }

    @Test
    fun `due is false when there is no pending state at all`() {
        assertFalse(FanOwnershipRenewalPolicy.due(null, activeGeneration = 7L, nowElapsedMs = 999_999L))
    }

    @Test
    fun `due is false when the active generation differs from the one the state was minted for`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)
        assertFalse(
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = 8L,
                nowElapsedMs = 1_000L + FanOwnershipRenewalPolicy.FIRST_RETRY_MS,
            ),
        )
    }

    @Test
    fun `due is false when there is no active session at all, even past the delay`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)
        assertFalse(
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = null,
                nowElapsedMs = 1_000L + FanOwnershipRenewalPolicy.FIRST_RETRY_MS,
            ),
        )
    }

    @Test
    fun `afterAttempt increments attempts, records the new code, and reschedules by the retry delay`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "FIRST_CODE", nowElapsedMs = 1_000L)
        val retried = FanOwnershipRenewalPolicy.afterAttempt(
            state,
            code = "SECOND_CODE",
            nowElapsedMs = 20_000L,
        )
        assertEquals(1, retried.attempts)
        assertEquals("SECOND_CODE", retried.code)
        assertEquals(20_000L + FanOwnershipRenewalPolicy.RETRY_MS, retried.nextAttemptAtElapsedMs)
        // Identity fields survive a retry -- this is still the same renewal, not a new one.
        assertEquals(7L, retried.generation)
        assertEquals("tx-7", retried.transactionId)

        val retriedAgain = FanOwnershipRenewalPolicy.afterAttempt(retried, "THIRD_CODE", 60_000L)
        assertEquals(2, retriedAgain.attempts)
    }

    @Test
    fun `forActiveGeneration drops a stale generation`() {
        val state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE", nowElapsedMs = 1_000L)

        assertEquals(state, FanOwnershipRenewalPolicy.forActiveGeneration(state, activeGeneration = 7L))
        assertNull(FanOwnershipRenewalPolicy.forActiveGeneration(state, activeGeneration = 8L))
        assertNull(FanOwnershipRenewalPolicy.forActiveGeneration(state, activeGeneration = null))
        assertNull(FanOwnershipRenewalPolicy.forActiveGeneration(null, activeGeneration = 7L))
    }

    // --- Bound degraded renewals: cap consecutive DEGRADED renewal attempts. Under a persistent post-restore
    // MANUAL refusal the pre-F4 steady state was a renewal every 30s forever, each cycle spending
    // several seconds with the compute axes at Stock. MAX_CONSECUTIVE_ATTEMPTS (3) backs a
    // renewal off to EXHAUSTED_HOLD_MS (10 min, the same window the bridge caps its own standing
    // rearm to) instead of RETRY_MS, and marks the state exhausted; onExternalDrift is the only
    // way back onto the ordinary cadence before that window elapses on its own. --------------

    @Test
    fun `the 3rd consecutive failed attempt exhausts the renewal and backs off to the 10-minute hold`() {
        val begun = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        val first = FanOwnershipRenewalPolicy.afterAttempt(begun, "CODE_1", nowElapsedMs = 20_000L)
        assertEquals(1, first.attempts)
        assertFalse("attempt 1 of 3 must not exhaust the renewal", first.exhausted)
        assertEquals(20_000L + FanOwnershipRenewalPolicy.RETRY_MS, first.nextAttemptAtElapsedMs)

        val second = FanOwnershipRenewalPolicy.afterAttempt(first, "CODE_2", nowElapsedMs = 60_000L)
        assertEquals(2, second.attempts)
        assertFalse("attempt 2 of 3 must not exhaust the renewal", second.exhausted)
        assertEquals(60_000L + FanOwnershipRenewalPolicy.RETRY_MS, second.nextAttemptAtElapsedMs)

        val third = FanOwnershipRenewalPolicy.afterAttempt(second, "CODE_3", nowElapsedMs = 100_000L)
        assertEquals(3, third.attempts)
        assertEquals(FanOwnershipRenewalPolicy.MAX_CONSECUTIVE_ATTEMPTS, third.attempts)
        assertTrue("the 3rd consecutive attempt must mark the renewal exhausted", third.exhausted)
        assertEquals(
            "an exhausted renewal backs off to EXHAUSTED_HOLD_MS (10 min), not the ordinary RETRY_MS",
            100_000L + FanOwnershipRenewalPolicy.EXHAUSTED_HOLD_MS,
            third.nextAttemptAtElapsedMs,
        )
        assertEquals(600_000L, FanOwnershipRenewalPolicy.EXHAUSTED_HOLD_MS)
    }

    @Test
    fun `a 4th afterAttempt against an already-exhausted state stays exhausted`() {
        var state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_1", nowElapsedMs = 20_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", nowElapsedMs = 60_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_3", nowElapsedMs = 100_000L)
        assertTrue(state.exhausted)

        val fourth = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_4", nowElapsedMs = 800_000L)
        assertEquals(4, fourth.attempts)
        assertTrue("a 4th attempt on an exhausted renewal must stay exhausted, not clear", fourth.exhausted)
        assertEquals(800_000L + FanOwnershipRenewalPolicy.EXHAUSTED_HOLD_MS, fourth.nextAttemptAtElapsedMs)
    }

    @Test
    fun `due is false during the exhausted hold`() {
        var state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_1", nowElapsedMs = 20_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", nowElapsedMs = 60_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_3", nowElapsedMs = 100_000L)
        assertTrue(state.exhausted)

        assertFalse(
            "due must stay false for the entire 10-minute exhausted hold",
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = 7L,
                nowElapsedMs = state.nextAttemptAtElapsedMs - 1,
            ),
        )
        // The hold is a RATE reduction, not a standing freeze: `due` never consults `exhausted`
        // directly, so an exhausted renewal becomes due again on its own once EXHAUSTED_HOLD_MS
        // elapses -- exactly the "3rd failed attempt -> next due at +10 min" reading.
        assertTrue(
            "due must become true once the exhausted hold itself elapses",
            FanOwnershipRenewalPolicy.due(
                state,
                activeGeneration = 7L,
                nowElapsedMs = state.nextAttemptAtElapsedMs,
            ),
        )
    }

    @Test
    fun `onExternalDrift re-arms exactly one attempt, due now, no longer exhausted`() {
        // H (found in review of executor G's commit): a reset re-arms exactly ONE attempt
        // (MAX_CONSECUTIVE_ATTEMPTS - 1), not a fresh run of MAX_CONSECUTIVE_ATTEMPTS -- each
        // external change should cost at most one restore/re-apply cycle before the hold resumes.
        var state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_1", nowElapsedMs = 20_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", nowElapsedMs = 60_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_3", nowElapsedMs = 100_000L)
        assertTrue(state.exhausted)

        val reset = FanOwnershipRenewalPolicy.onExternalDrift(state, nowElapsedMs = 150_000L)
        assertEquals(
            "an external drift re-arms exactly one attempt (MAX_CONSECUTIVE_ATTEMPTS - 1), not zero",
            FanOwnershipRenewalPolicy.MAX_CONSECUTIVE_ATTEMPTS - 1,
            reset.attempts,
        )
        assertFalse("an external drift clears the exhausted marker", reset.exhausted)
        assertEquals(150_000L, reset.nextAttemptAtElapsedMs)
        assertTrue(
            "a drift-reset renewal must be due immediately, not wait out the remaining hold",
            FanOwnershipRenewalPolicy.due(reset, activeGeneration = 7L, nowElapsedMs = 150_000L),
        )
        // Identity is untouched by the reset -- this is still the same renewal, not a new one.
        assertEquals(7L, reset.generation)
        assertEquals("tx-7", reset.transactionId)
    }

    @Test
    fun `a fresh begin after a cleared renewal never starts exhausted`() {
        // The success path (renewMimirFanOwnershipOnWorker's SETTLED_MANUAL branch) clears the
        // whole renewal state to null unconditionally, regardless of how many attempts or whether
        // it was exhausted -- there is nothing left to carry an exhausted flag forward into the
        // next apply's fresh begin().
        val fresh = FanOwnershipRenewalPolicy.begin(9L, "tx-9", "CODE", nowElapsedMs = 1_000L)
        assertFalse(fresh.exhausted)
        assertEquals(0, fresh.attempts)
    }

    // --- Select path: selectPathFanEntryRenewalCode --------------------------
    //
    // A SMART contract reaching
    // FanOwnershipSettleRoute.SCHEDULE_RENEWAL means D2's own MANUAL attempt on this apply either
    // failed or was never attempted (the profile/hardware were ineligible before any hardware
    // read). The settle worker begins the renewal with D2's own recorded fallback code, or
    // SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE when D2 recorded none at all.

    @Test
    fun `selectPathFanEntryRenewalCode carries D2's own recorded code through unchanged`() {
        assertEquals("FAN_ENTRY_HANDOFF_BUSY", selectPathFanEntryRenewalCode("FAN_ENTRY_HANDOFF_BUSY"))
    }

    @Test
    fun `selectPathFanEntryRenewalCode falls back to MANUAL_NOT_ATTEMPTED when D2 recorded nothing`() {
        assertEquals(
            SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE,
            selectPathFanEntryRenewalCode(null),
        )
        assertEquals("MANUAL_NOT_ATTEMPTED", SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE)
    }

    // --- Exhausted renewal edge: observeWhileExhausted must be
    // EDGE-triggered on a CHANGE of the live fan mode, never LEVEL-triggered on it merely being
    // non-Smart. Pre-fix, `fanControlDecision`'s exhausted branch called `onExternalDrift`
    // directly whenever `liveFanMode != FAN_SMART` on ANY tick -- if
    // `handFanToVendorSmartForRenewal` can never get the vendor to hold mode 4 (a game or the
    // vendor UI keeps writing SPORT/SILENT), the live mode is != 4 on EVERY tick, so the
    // 10-minute exhausted hold was re-armed every ~1s tick and the daemon slammed full PWM +
    // mode 4 at the fan every ~20s for the rest of the session. The fix: the first live-mode
    // observation after exhaustion is RECORDED (`exhaustedLiveFanMode`); only a LATER
    // observation that DIFFERS from the recorded level (a genuinely new external write) resets
    // the hold -- the level being merely HELD does not.

    private fun exhaustedRenewalState(exhaustedAtElapsedMs: Long = 100_000L): FanOwnershipRenewalState {
        var state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_1", nowElapsedMs = 20_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", nowElapsedMs = 60_000L)
        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_3", nowElapsedMs = exhaustedAtElapsedMs)
        check(state.exhausted) { "fixture must be exhausted for this pin" }
        check(state.generation == 7L)
        return state
    }

    @Test
    fun `G1 -- the first live-mode observation after exhaustion is recorded, not treated as a reset`() {
        val exhausted = exhaustedRenewalState()
        val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
            exhausted,
            liveFanMode = 5,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "the first observed live mode after exhaustion is recorded on the state",
            5,
            observed.exhaustedLiveFanMode,
        )
        assertTrue(
            "a first observation alone must NOT reset the exhausted hold",
            observed.exhausted,
        )
        assertFalse(
            "the hold's own backoff window must be untouched by a first observation",
            FanOwnershipRenewalPolicy.due(observed, activeGeneration = 7L, nowElapsedMs = 500_000L),
        )
    }

    @Test
    fun `G2 -- the SAME live mode held across later ticks does not reset the hold`() {
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val observedAgain = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = 5,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "a level that is merely HELD (unchanged from the recorded observation) must leave " +
                "the renewal completely untouched -- this is the core level-vs-edge distinction",
            recorded,
            observedAgain,
        )
    }

    @Test
    fun `G3 -- a live mode that DIFFERS from the recorded level is a new external write and resets`() {
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val reset = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = 1,
            nowElapsedMs = 500_000L,
        )
        // H (found in review of executor G's commit): a reset re-arms exactly ONE attempt
        // (MAX_CONSECUTIVE_ATTEMPTS - 1), not a fresh run of MAX_CONSECUTIVE_ATTEMPTS.
        assertEquals(
            "a genuine external write re-arms exactly one attempt, not zero",
            FanOwnershipRenewalPolicy.MAX_CONSECUTIVE_ATTEMPTS - 1,
            reset.attempts,
        )
        assertFalse("a genuine external write clears the exhausted marker", reset.exhausted)
        assertTrue(
            "a drift-reset renewal must be due immediately",
            FanOwnershipRenewalPolicy.due(reset, activeGeneration = 7L, nowElapsedMs = 500_000L),
        )
        // I-1 pin-table completeness (spec row "exhausted, last=5, observe 1 -> reset ... last
        // null"): onExternalDrift already nulls exhaustedLiveFanMode -- unaffected by I-1 -- but
        // the row was not asserted here before.
        assertNull(
            "a reset renewal has observed nothing yet on its fresh cadence -- last must be null",
            reset.exhaustedLiveFanMode,
        )
    }

    @Test
    fun `G4 -- the vendor itself landing back on Smart (live=4) is never recorded (I-1) -- state is untouched`() {
        // I-1 (third review, R3-1) withdraws H's "follows the LAST observed mode" claim as it
        // applies to Smart: the daemon writes 4 itself on every failed attempt (the renewal
        // hand-to-4, or the E1 rollback landing at 4), so a live read of Smart can never be told
        // apart from the daemon's own write. Recording it (H's original behaviour, asserted by
        // this test before I-1) let the vendor's next re-assert of the level it actually holds
        // read as a change from the daemon's own stale record and reset the hold at the vendor's
        // re-assert rate -- unbounded by MAX_CONSECUTIVE_ATTEMPTS. Smart must therefore leave the
        // WHOLE state untouched -- full equality, not just the reset-relevant fields.
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = TuningPlanner.FAN_SMART,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "a live read of Smart (4) must never be recorded, and must leave the whole state untouched",
            recorded,
            observed,
        )
    }

    @Test
    fun `G5 -- a failed telemetry read (null) never touches an exhausted renewal`() {
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = null,
            nowElapsedMs = 500_000L,
        )
        assertEquals("a null (failed) telemetry read leaves the renewal untouched", recorded, observed)
    }

    @Test
    fun `G6 -- fanControlDecision's exhausted branch routes through observeWhileExhausted, not onExternalDrift directly`() {
        val body = fanControlDecisionSource()
        assertTrue(
            "the exhausted branch must call the edge-triggered policy entry point",
            body.contains("observeWhileExhausted("),
        )
        assertFalse(
            "the exhausted branch must no longer call onExternalDrift directly -- only " +
                "observeWhileExhausted (internally) may do that now",
            body.contains("onExternalDrift("),
        )
    }

    // --- Exhausted renewal refinements: two refinements to observeWhileExhausted /
    // onExternalDrift. (1) exhaustedLiveFanMode must follow the LAST observed live mode, not the
    // first -- a Sport(5) -> Smart(4) -> Sport(5) sequence is a genuine new external write that
    // G's "compare against the first-recorded level" missed once the vendor's intervening Smart
    // was folded in unchanged. (2) a reset re-arms exactly ONE attempt
    // (MAX_CONSECUTIVE_ATTEMPTS - 1), not a fresh run of MAX_CONSECUTIVE_ATTEMPTS -- each external
    // change should cost at most one restore/re-apply cycle (each several seconds with the compute
    // axes at Stock) before the hold resumes on a fresh failure. ---------------------------------

    @Test
    fun `H1 -- I-1 corrects H -- the vendor landing on Smart must NOT update the recorded level`() {
        // I-1 (third review, R3-1) withdraws H's claim that the recorded level "follows the LAST
        // observed mode, including ... Smart" -- Smart is exactly what the daemon's own hand-to-4
        // writes on every failed attempt, so recording it is the R3-1 defect (a vendor re-assert
        // of the held level then reads as a change and resets, at the vendor's re-assert rate,
        // unbounded by MAX_CONSECUTIVE_ATTEMPTS). The recorded level must stay the last observed
        // VENDOR-HELD NON-Smart level.
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = TuningPlanner.FAN_SMART,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "the recorded level must stay the last observed NON-Smart mode -- Smart is never recorded",
            5,
            observed.exhaustedLiveFanMode,
        )
        assertTrue(
            "the vendor landing on Smart must not reset the hold",
            observed.exhausted,
        )
        assertFalse(
            "the hold's own backoff window must be untouched by the vendor landing on Smart",
            FanOwnershipRenewalPolicy.due(observed, activeGeneration = 7L, nowElapsedMs = 500_000L),
        )
    }

    @Test
    fun `H2 -- I-1 withdraws this -- Sport to Smart back to Sport no longer resets (documented cost)`() {
        // I-1 (third review, R3-1) withdraws H's claim: Sport(5) -> Smart(4) -> Sport(5) is no
        // longer a genuine external write. The Smart observed in between is indistinguishable
        // from the daemon's own hand-to-4 write, so (see the new G4/H1) it is never recorded --
        // the SECOND Sport observation is then compared against the STILL-recorded first Sport
        // and found unchanged, not reset. Documented cost (spec I-1): a player's
        // Sport -> Smart -> Sport now waits out the hold (<= 10 min).
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val afterVendorTookSmart = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = TuningPlanner.FAN_SMART,
            nowElapsedMs = 400_000L,
        )
        val stillHeld = FanOwnershipRenewalPolicy.observeWhileExhausted(
            afterVendorTookSmart,
            liveFanMode = 5,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "no reset -- the second Sport observation compares against the still-recorded first Sport",
            recorded,
            stillHeld,
        )
        assertTrue("Sport -> Smart -> Sport must NOT reset the hold", stillHeld.exhausted)
    }

    // --- Exhausted renewal observation: observeWhileExhausted must never record Smart (4) -- the
    // daemon writes 4 itself on every failed attempt (the renewal hand-to-4, or the E1 rollback
    // landing at 4), so a live read of 4 can never be told apart from the daemon's own write.
    // Covers the observation cases not already exercised above. ------------

    @Test
    fun `I-1a -- Smart observed before any level is recorded leaves the renewal untouched`() {
        // Pin-table row: exhausted, last=null, observe 4 -> unchanged (last stays null, still
        // exhausted).
        val exhausted = exhaustedRenewalState()
        assertNull("fixture must start with nothing recorded", exhausted.exhaustedLiveFanMode)
        val observed = FanOwnershipRenewalPolicy.observeWhileExhausted(
            exhausted,
            liveFanMode = TuningPlanner.FAN_SMART,
            nowElapsedMs = 500_000L,
        )
        assertEquals(
            "Smart observed with nothing recorded yet must still not be recorded -- state untouched",
            exhausted,
            observed,
        )
    }

    @Test
    fun `I-1b -- F-1 pin -- the daemon's own hand-to-4, then the vendor's re-assert of the held level, never resets`() {
        // R3-1: before I-1, the first post-attempt tick recorded the daemon's OWN hand-to-4 as a
        // vendor level (last=null -> record 4). The vendor's next re-assert of the level it
        // actually holds (5) then read as a CHANGE from that recorded 4 and reset the hold --
        // one attempt, re-exhaust, repeat at the vendor's re-assert rate, unbounded by
        // MAX_CONSECUTIVE_ATTEMPTS. Pin-table row: exhausted, last=null -> observe 4 (the
        // daemon's own write) -> observe 5 (vendor re-assert of the held level) -> NO reset (5 is
        // merely recorded); then observe 5 again -> no reset.
        val exhausted = exhaustedRenewalState()
        assertNull("fixture must start with nothing recorded", exhausted.exhaustedLiveFanMode)

        // Tick 1: the daemon's own hand-to-4 write is observed as live.
        val afterOwnWrite = FanOwnershipRenewalPolicy.observeWhileExhausted(
            exhausted,
            liveFanMode = TuningPlanner.FAN_SMART,
            nowElapsedMs = 500_000L,
        )
        assertEquals("the daemon's own 4 must never be recorded", exhausted, afterOwnWrite)

        // Tick 2: the vendor re-asserts the level it actually holds (5).
        val afterVendorReassert = FanOwnershipRenewalPolicy.observeWhileExhausted(
            afterOwnWrite,
            liveFanMode = 5,
            nowElapsedMs = 501_000L,
        )
        assertEquals(
            "the vendor's re-assert of its held level is merely recorded, not a reset",
            5,
            afterVendorReassert.exhaustedLiveFanMode,
        )
        assertTrue("no reset on the vendor's re-assert", afterVendorReassert.exhausted)
        assertEquals(
            "no reset -- attempts untouched by the vendor's re-assert",
            exhausted.attempts,
            afterVendorReassert.attempts,
        )

        // Tick 3: the vendor re-asserts the SAME held level again.
        val afterSecondReassert = FanOwnershipRenewalPolicy.observeWhileExhausted(
            afterVendorReassert,
            liveFanMode = 5,
            nowElapsedMs = 502_000L,
        )
        assertEquals(
            "a second re-assert of the same held level is still a no-op, not a reset",
            afterVendorReassert,
            afterSecondReassert,
        )
    }

    @Test
    fun `H3 -- the same level held across repeated ticks never resets, recorded level stays put`() {
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        var state = recorded
        repeat(3) {
            state = FanOwnershipRenewalPolicy.observeWhileExhausted(
                state,
                liveFanMode = 5,
                nowElapsedMs = 500_000L,
            )
        }
        assertEquals(
            "a level held across repeated ticks leaves the renewal completely untouched",
            recorded,
            state,
        )
        assertEquals(5, state.exhaustedLiveFanMode)
    }

    @Test
    fun `H4 -- after a reset, a single failed attempt re-exhausts the renewal`() {
        val recorded = exhaustedRenewalState().copy(exhaustedLiveFanMode = 5)
        val reset = FanOwnershipRenewalPolicy.observeWhileExhausted(
            recorded,
            liveFanMode = 1,
            nowElapsedMs = 500_000L,
        )
        assertFalse("fixture must be reset (not exhausted) for this pin", reset.exhausted)

        val failedAgain = FanOwnershipRenewalPolicy.afterAttempt(reset, "CODE_RETRY", nowElapsedMs = 500_000L)
        assertTrue(
            "one failed attempt after a reset must re-exhaust -- the reset only re-armed ONE " +
                "attempt, not a fresh run of MAX_CONSECUTIVE_ATTEMPTS",
            failedAgain.exhausted,
        )
        assertEquals(
            "a re-exhausted renewal backs off to EXHAUSTED_HOLD_MS again",
            500_000L + FanOwnershipRenewalPolicy.EXHAUSTED_HOLD_MS,
            failedAgain.nextAttemptAtElapsedMs,
        )
    }

    @Test
    fun `H5 -- a fresh begin (not via a reset) still allows MAX_CONSECUTIVE_ATTEMPTS failures before exhausting`() {
        var state = FanOwnershipRenewalPolicy.begin(7L, "tx-7", "CODE_0", nowElapsedMs = 1_000L)
        assertEquals(0, state.attempts)

        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_1", nowElapsedMs = 20_000L)
        assertFalse("attempt 1 of a fresh renewal must not exhaust it", state.exhausted)

        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_2", nowElapsedMs = 60_000L)
        assertFalse("attempt 2 of a fresh renewal must not exhaust it", state.exhausted)

        state = FanOwnershipRenewalPolicy.afterAttempt(state, "CODE_3", nowElapsedMs = 100_000L)
        assertTrue(
            "a fresh begin must still take the full MAX_CONSECUTIVE_ATTEMPTS failures to exhaust " +
                "-- unaffected by the reset re-arm change, which only applies to a renewal that " +
                "went through onExternalDrift",
            state.exhausted,
        )
        assertEquals(FanOwnershipRenewalPolicy.MAX_CONSECUTIVE_ATTEMPTS, state.attempts)
    }

    private fun fanControlDecisionSource(): String = sliceBetween(
        mainSourceText("MimirTweakerService.kt"),
        "private fun fanControlDecision(",
        "private fun ActiveSessionIdentity.fanRestore(",
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
