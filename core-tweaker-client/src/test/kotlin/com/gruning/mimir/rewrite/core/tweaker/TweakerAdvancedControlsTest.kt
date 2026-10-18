package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerAdvancedControlsTest {
    @Test
    fun `manual fan capability requires complete exact contract`() {
        val parsed = parseTweakerAdvancedCapabilitiesBundle(capabilityBundle(), 6)
        val fan = requireNotNull(parsed.manualFan)
        assertEquals(TweakerFanMode.AUTO, fan.currentMode)
        assertEquals(-100..100, fan.curveBiasRange)
        assertEquals("GPU", fan.curveSensorType)

        assertNull(
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().apply { putString("fanCurveSensorType", "CPU") },
                6,
            ).manualFan,
        )
    }


    @Test
    fun `malformed manual fan does not erase frame limits`() {
        val parsed = parseTweakerAdvancedCapabilitiesBundle(
            capabilityBundle().apply {
                putIntegerArrayList("frameLimitsHz", arrayListOf(60, 120))
                putString("fanCurveSensorType", "CPU")
            },
            6,
        )

        assertNull(parsed.manualFan)
        assertEquals(listOf(60, 120), parsed.frameLimitsHz)
    }

    @Test
    fun `Auto-only mode set parses -- pins the autoExit duty-owned shape that used to be silently dropped`() {
        // This is the exact shape MimirTweakerService's autoExit/duty-owned path has always sent
        // (fanControlModes = ["AUTO"]) but the old `require(modes == entries.toSet())` rejected
        // outright -- a latent bug independent of other fan changes. Pin it so it cannot regress.
        val parsed = parseTweakerAdvancedCapabilitiesBundle(
            capabilityBundle().apply {
                putStringArrayList("fanControlModes", arrayListOf("AUTO"))
            },
            6,
        )
        val fan = requireNotNull(parsed.manualFan)
        assertEquals(setOf(TweakerFanMode.AUTO), fan.modes)
        assertEquals(TweakerFanMode.AUTO, fan.currentMode)
    }

    @Test
    fun `an empty mode set is still rejected as malformed`() {
        assertNull(
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().apply {
                    putStringArrayList("fanControlModes", arrayListOf())
                },
                6,
            ).manualFan,
        )
    }

    @Test
    fun `a mode set without Auto is rejected as malformed`() {
        assertNull(
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().apply {
                    putStringArrayList("fanControlModes", arrayListOf("MANUAL"))
                    putString("fanControlMode", "MANUAL")
                    putInt("fanCurveBias", -40)
                },
                6,
            ).manualFan,
        )
    }

    @Test
    fun `an unrecognized mode value is rejected as malformed`() {
        assertNull(
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().apply {
                    putStringArrayList("fanControlModes", arrayListOf("AUTO", "TURBO"))
                },
                6,
            ).manualFan,
        )
    }

    @Test
    fun `a reported current mode outside the advertised modes is rejected as malformed`() {
        assertNull(
            parseTweakerAdvancedCapabilitiesBundle(
                capabilityBundle().apply {
                    putStringArrayList("fanControlModes", arrayListOf("AUTO"))
                    putString("fanControlMode", "MANUAL")
                    putInt("fanCurveBias", -40)
                },
                6,
            ).manualFan,
        )
    }

    @Test
    fun `absent capabilities remain fully absent`() {
        val parsed = parseTweakerAdvancedCapabilitiesBundle(
            Bundle().apply {
                putInt("apiVersion", 6)
                putInt("fanControlContractVersion", 2)
                putBoolean("manualFanControlAvailable", false)
                putIntegerArrayList("frameLimitsHz", arrayListOf())
                putBoolean("perGameOverridesAvailable", false)
            },
            6,
        )
        assertNull(parsed.manualFan)
        assertTrue(parsed.frameLimitsHz.isEmpty())
    }

    @Test
    fun `Auto and Manual choices retain the authoritative mode 6 identity`() {
        listOf(
            TweakerFanMode.AUTO to TweakerFanMode.MANUAL,
            TweakerFanMode.MANUAL to TweakerFanMode.AUTO,
        ).forEachIndexed { index, (priorMode, requestedMode) ->
            val attempt = fanAttempt(
                priorMode = priorMode,
                mode = requestedMode,
                generation = 7L + index,
                transactionId = "tx-${7 + index}",
            )
            val result = parseTweakerFanControlBundle(
                bundle = fanSuccessBundle(
                    attempt = attempt,
                    generation = attempt.generation,
                    transactionId = attempt.transactionId,
                ),
                expectedApiVersion = 6,
                attempt = attempt,
                observedAtElapsedRealtimeMs = 1_000,
            )

            assertEquals(
                TweakerFanControlAssessment.Accepted(
                    changed = true,
                    mode = requestedMode,
                    curveBias = if (requestedMode == TweakerFanMode.AUTO) 0 else -40,
                ),
                result,
            )
        }
    }

    /**
     * **RETIRED 2026-08-25, and it was pinning the defect.** This asserted `Uncertain` for exactly
     * the response the daemon emits on every mode transition: release spec §1.2 R1 was overturned
     * 2026-08-23, Manual now transitions to vendor mode 6, and a transition is a whole Guardian
     * transaction with a fresh generation and a fresh random-UUID transaction id. Four consecutive applied transitions were misreported as "FAN RESPONSE NOT APPLIED".
     *
     * The rule is now COHERENCE (see `TweakerFanModeTransitionAssessmentTest`): the identity is
     * either untouched or wholly succeeded, never half of each. The residual -- that a successor is
     * no longer refused on a change that needed no transition -- is stated there, deliberately, as
     * an accepted cost with the follow-up named.
     */
    @Test
    fun `a whole successor Guardian identity is accepted, and half of one is not`() {
        val attempt = fanAttempt(TweakerFanMode.AUTO, TweakerFanMode.MANUAL)

        assertTrue(
            "a mode transition mints both a new generation and a new transaction id",
            parseTweakerFanControlBundle(
                bundle = fanSuccessBundle(attempt, attempt.generation + 1L, "tx-successor"),
                expectedApiVersion = 6,
                attempt = attempt,
                observedAtElapsedRealtimeMs = 1_000,
            ) is TweakerFanControlAssessment.Accepted,
        )
        assertTrue(
            "a new generation under the old transaction id is not a Guardian successor",
            parseTweakerFanControlBundle(
                bundle = fanSuccessBundle(attempt, attempt.generation + 1L, attempt.transactionId),
                expectedApiVersion = 6,
                attempt = attempt,
                observedAtElapsedRealtimeMs = 1_000,
            ) is TweakerFanControlAssessment.Uncertain,
        )
    }

    @Test
    fun `same mode response retains exact Guardian identity`() {
        val attempt = fanAttempt(TweakerFanMode.MANUAL, TweakerFanMode.MANUAL)
        val exact = fanSuccessBundle(attempt, attempt.generation, attempt.transactionId)

        assertEquals(
            TweakerFanControlAssessment.Accepted(true, TweakerFanMode.MANUAL, -40),
            parseTweakerFanControlBundle(exact, 6, attempt, 1_000),
        )
        assertEquals(
            TweakerFanControlAssessment.Accepted(false, TweakerFanMode.MANUAL, -40),
            parseTweakerFanControlBundle(
                Bundle(exact).apply { putString("code", "FAN_CONTROL_UNCHANGED") },
                6,
                attempt,
                1_000,
            ),
        )
        // Was: a successor here must be Uncertain. Retired with the R1 overturn -- see
        // `a whole successor Guardian identity is accepted, and half of one is not` above. What
        // this still pins is the half-successor, which no daemon state can produce and which
        // therefore always signals a malformed or forged response.
        assertTrue(
            parseTweakerFanControlBundle(
                fanSuccessBundle(attempt, attempt.generation, "tx-successor"),
                6,
                attempt,
                1_000,
            ) is TweakerFanControlAssessment.Uncertain,
        )
    }

    @Test
    fun `fan successor response rejects malformed stale or mismatched authority`() {
        val attempt = fanAttempt(TweakerFanMode.AUTO, TweakerFanMode.MANUAL)
        val settledGeneration = attempt.generation
        val settledTransaction = attempt.transactionId
        val corruptions = listOf<Bundle.() -> Unit>(
            { remove("transactionId") },
            { putString("sessionState", "STOCK") },
            { putBoolean("mutationMayBeActive", false) },
            { putString("sessionScope", "GAME") },
            { putString("requestId", "mimir.system.other") },
            { putString("ownerRequestId", "mimir.system.other") },
            { putString("profileId", "quiet") },
            { putString("fanControlMode", TweakerFanMode.AUTO.name) },
            { putInt("fanCurveBias", 0) },
        )

        corruptions.forEach { corrupt ->
            val bundle = fanSuccessBundle(attempt, settledGeneration, settledTransaction).apply(corrupt)
            assertTrue(
                parseTweakerFanControlBundle(bundle, 6, attempt, 1_000) is
                    TweakerFanControlAssessment.Uncertain,
            )
        }
        assertTrue(
            parseTweakerFanControlBundle(
                fanSuccessBundle(attempt, settledGeneration, settledTransaction),
                6,
                attempt,
                attempt.requestDeadlineElapsedRealtimeMs,
            ) is TweakerFanControlAssessment.Uncertain,
        )
    }

    @Test
    fun `non success fan response remains an explicit rejection`() {
        val attempt = fanAttempt(TweakerFanMode.AUTO, TweakerFanMode.MANUAL)
        val response = fanSuccessBundle(attempt, attempt.generation, attempt.transactionId).apply {
            putBoolean("success", false)
            putString("code", "STALE_OWNER")
        }

        assertEquals(
            TweakerFanControlAssessment.Rejected("STALE_OWNER", "ok"),
            parseTweakerFanControlBundle(response, 6, attempt, 1_000),
        )
    }

    private fun fanAttempt(
        priorMode: TweakerFanMode,
        mode: TweakerFanMode,
        generation: Long = 7L,
        transactionId: String = "tx-7",
    ) = TweakerFanControlAttempt(
        callerRequestId = "mimir.fan.1",
        requestDeadlineElapsedRealtimeMs = 5_000L,
        ownerRequestId = "mimir.system.1",
        generation = generation,
        transactionId = transactionId,
        priorMode = priorMode,
        profileId = "balanced",
        mode = mode,
        curveBias = if (mode == TweakerFanMode.AUTO) 0 else -40,
    )

    private fun fanSuccessBundle(
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

    private fun capabilityBundle() = Bundle().apply {
        putInt("apiVersion", 6)
        putInt("fanControlContractVersion", 2)
        putBoolean("manualFanControlAvailable", true)
        putIntegerArrayList("frameLimitsHz", arrayListOf())
        putBoolean("perGameOverridesAvailable", false)
        putStringArrayList("fanControlModes", arrayListOf("AUTO", "MANUAL"))
        putInt("fanCurveBiasMin", -100)
        putInt("fanCurveBiasMax", 100)
        putInt("fanCurveBiasStep", 10)
        putString("fanCurveSensorType", "GPU")
        putString("fanControlMode", "AUTO")
        putInt("fanCurveBias", 0)
        putLong("generation", 7)
        putString("transactionId", "tx-7")
        putString("ownerRequestId", "mimir.system.1")
        putString("profileId", "quiet")
    }
}
