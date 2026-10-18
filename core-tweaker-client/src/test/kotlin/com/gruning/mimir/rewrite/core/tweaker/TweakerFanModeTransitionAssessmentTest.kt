package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FAILING-FIRST, written from the device before any fix.
 *
 *  The daemon applied every one of them. Nova log:
 *
 * ```
 * 23:25:04 request mode=AUTO   requiresTransition=false                      <- UI said APPLIED
 * 23:25:06 request mode=MANUAL requiresTransition=true  -> 23:25:13 APPLIED  <- UI said NOT APPLIED
 * 23:25:15 request mode=AUTO   requiresTransition=true  -> 23:25:23 APPLIED  <- UI said NOT APPLIED
 * 23:25:26 request mode=MANUAL requiresTransition=true  -> 23:25:32 APPLIED  <- UI said NOT APPLIED
 * 23:25:35 request mode=AUTO   requiresTransition=true  -> 23:25:44 APPLIED  <- UI said NOT APPLIED
 * ```
 *
 * A MODE change is a real Guardian transition and advances generation/transactionId. A CURVE-CHOICE
 * change is not and must not. `parseTweakerFanControlBundle` applies the curve-choice rule to both,
 * so every mode change is read as an invented successor and degraded to Uncertain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerFanModeTransitionAssessmentTest {

    @Test
    fun `a mode change is accepted when the Guardian identity advances`() {
        val attempt = attempt(priorMode = TweakerFanMode.AUTO, mode = TweakerFanMode.MANUAL)
        val response = successBundle(attempt, attempt.generation + 1L, "tx-8")

        val assessment = parseTweakerFanControlBundle(response, 6, attempt, 1_000)

        assertTrue(
            "the daemon APPLIED this on hardware; a successor identity is what a mode transition " +
                "IS, and reading it as failure is what told the player NOT APPLIED four times in a " +
                "row -- got $assessment",
            assessment is TweakerFanControlAssessment.Accepted,
        )
        assertEquals(TweakerFanMode.MANUAL, (assessment as TweakerFanControlAssessment.Accepted).mode)
    }

    @Test
    fun `an identity that goes BACKWARDS is refused whatever the mode did`() {
        listOf(TweakerFanMode.AUTO to TweakerFanMode.MANUAL, TweakerFanMode.MANUAL to TweakerFanMode.MANUAL)
            .forEach { (prior, mode) ->
                val attempt = attempt(priorMode = prior, mode = mode, generation = 7L)

                assertTrue(
                    "a regressing generation is a stale or replayed response, never a transition",
                    parseTweakerFanControlBundle(successBundle(attempt, 6L, "tx-6"), 6, attempt, 1_000)
                        !is TweakerFanControlAssessment.Accepted,
                )
            }
    }

    @Test
    fun `the two incoherent halves are refused`() {
        val attempt = attempt(priorMode = TweakerFanMode.AUTO, mode = TweakerFanMode.MANUAL)

        assertTrue(
            "a fresh generation under the SAME transaction id is not a Guardian successor -- the " +
                "two fields move together or neither does",
            parseTweakerFanControlBundle(
                successBundle(attempt, attempt.generation + 1L, attempt.transactionId), 6, attempt, 1_000,
            ) !is TweakerFanControlAssessment.Accepted,
        )
        assertTrue(
            "a new transaction id at the same generation is the other incoherent half",
            parseTweakerFanControlBundle(
                successBundle(attempt, attempt.generation, "tx-successor"), 6, attempt, 1_000,
            ) !is TweakerFanControlAssessment.Accepted,
        )
    }

    /**
     * **ACCEPTED RESIDUAL, stated so it is a decision and not an oversight.** The rule this file
     * pins is coherence, not prediction, so it can no longer refuse a successor identity on a
     * change that needed no transition. It cannot: `requiresTransition` is derived daemon-side from
     * the contract's fan mode, which never crosses the wire, and `attempt.priorMode` is the durable
     * per-profile preference rather than the live hardware mode (device-measured: a
     * `priorMode=MANUAL mode=AUTO` press ran `requiresTransition=false`).
     *
     * What is NOT lost: a response describing a session this client never targeted is still refused
     * by `ownerRequestId`/`requestId`/`profileId` above, and daemon-side by the STALE_OWNER
     * pre-check. The uncovered case is narrow -- a daemon opening an unwarranted successor under the
     * SAME owner identity. Closing it properly needs the daemon to declare the transition in the
     * response bundle; that is recorded as the follow-up rather than faked here.
     */
    @Test
    fun `a coherent successor is accepted even where no transition was needed`() {
        val attempt = attempt(priorMode = TweakerFanMode.MANUAL, mode = TweakerFanMode.MANUAL)
        val response = successBundle(attempt, attempt.generation + 1L, "tx-successor")

        assertTrue(
            "documents the residual rather than asserting a protection that is no longer there",
            parseTweakerFanControlBundle(response, 6, attempt, 1_000)
                is TweakerFanControlAssessment.Accepted,
        )
    }

    @Test
    fun `a mode change with an unchanged identity is still accepted`() {
        val attempt = attempt(priorMode = TweakerFanMode.AUTO, mode = TweakerFanMode.MANUAL)
        val response = successBundle(attempt, attempt.generation, attempt.transactionId)

        assertTrue(
            "the daemon may legitimately settle a mode change inside the same transaction " +
                "(requiresTransition=false), and that must not become a new failure",
            parseTweakerFanControlBundle(response, 6, attempt, 1_000)
                is TweakerFanControlAssessment.Accepted,
        )
    }

    private fun attempt(
        priorMode: TweakerFanMode,
        mode: TweakerFanMode,
        generation: Long = 7L,
    ) = TweakerFanControlAttempt(
        callerRequestId = "mimir.fan.1",
        requestDeadlineElapsedRealtimeMs = 5_000L,
        ownerRequestId = "mimir.system.1",
        generation = generation,
        transactionId = "tx-$generation",
        priorMode = priorMode,
        profileId = "balanced",
        mode = mode,
        curveBias = if (mode == TweakerFanMode.AUTO) 0 else -40,
    )

    private fun successBundle(
        attempt: TweakerFanControlAttempt,
        generation: Long,
        transactionId: String,
    ) = Bundle().apply {
        putInt("apiVersion", 6)
        putString("callerRequestId", attempt.callerRequestId)
        putLong("callerDeadlineElapsedRealtimeMs", attempt.requestDeadlineElapsedRealtimeMs)
        putBoolean("success", true)
        putString("code", "FAN_CONTROL_UPDATED")
        putString("message", "ok")
        putString("sessionState", "ACTIVE")
        putBoolean("mutationMayBeActive", true)
        putString("sessionScope", "SYSTEM")
        putString("requestId", attempt.ownerRequestId)
        putString("ownerRequestId", attempt.ownerRequestId)
        putString("profileId", attempt.profileId)
        putLong("generation", generation)
        putString("transactionId", transactionId)
        putString("fanControlMode", attempt.mode.name)
        putInt("fanCurveBias", if (attempt.mode == TweakerFanMode.AUTO) 0 else attempt.curveBias)
    }
}
