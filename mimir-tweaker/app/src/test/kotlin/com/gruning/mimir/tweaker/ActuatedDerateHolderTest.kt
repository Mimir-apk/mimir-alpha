package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The holder that decides what the player is told about a derate.
 *
 * The trap it exists to avoid: [GuardianDerateObservability.lastRecorded] holds every DECISION,
 * including the ones that moved nothing — an unsigned rung degrades to "remain" and the ceilings
 * stay exactly where they were. Publishing reduced-caps off that would tell the player his clocks
 * had been cut when they had not, which is the same lie as the one this landing removes, pointed
 * the other way.
 */
class ActuatedDerateHolderTest {

    @Test
    fun `only an actuated derate is held, and an unactuated one never clears it`() {
        val actuated = record(rung = 1, success = true, applied = true)
        assertNotNull("a derate that moved hardware is the truth the row needs", actuated)

        // Rung 2 is unsigned on the Odin: the decision happens, the hardware does not move.
        val unsigned = holdThen(actuated!!, rung = 2, success = true, applied = false)
        assertEquals(
            "the hardware is STILL derated at rung 1; forgetting that would put nominal caps back " +
                "on the row while the clocks stayed down",
            1,
            unsigned?.rung,
        )
    }

    @Test
    fun `an actuated escalation advances the held position`() {
        val first = record(rung = 1, success = true, applied = true)!!
        val second = holdThen(first, rung = 2, success = true, applied = true)

        assertEquals(2, second?.rung)
    }

    @Test
    fun `the denominator is the ladder depth, not the steps this decision moved`() {
        val held = record(rung = 1, success = true, applied = true)!!

        assertEquals(
            "SupervisorDecision.Derate.rungs is steps-moved-by-THIS-decision. Reading it as the " +
                "denominator prints 'rung 1 of 1' on a three-rung ladder.",
            ThermalDerateLadder.maxRung,
            held.maxRung,
        )
    }

    @Test
    fun `a derate from a finished session never reaches the wire`() {
        val held = record(rung = 1, success = true, applied = true)!!

        assertNotNull(activeDerateForSession(held, session(held.generation, held.transactionId)))
        assertNull("no session at all", activeDerateForSession(held, null))
        assertNull(
            "a successor session must not inherit its predecessor's derate",
            activeDerateForSession(held, session(held.generation + 1, "tx-next")),
        )
        assertNull(activeDerateForSession(held, session(held.generation, "tx-other")))
    }

    private fun record(rung: Int, success: Boolean, applied: Boolean): ActuatedDerate? {
        GuardianDerateObservability.reset()
        return holdThen(null, rung, success, applied)
    }

    private fun holdThen(
        prior: ActuatedDerate?,
        rung: Int,
        success: Boolean,
        applied: Boolean,
    ): ActuatedDerate? {
        GuardianDerateObservability.onDerateDecided(
            decision = SupervisorDecision.Derate(
                generation = 7L,
                transactionId = "tx-7",
                reason = "THERMAL_ANDROID",
                rungs = 1,
                rung = rung,
            ),
            attempt = TransactionResult(
                success = success,
                applied = applied,
                restored = false,
                code = if (applied) "THERMAL_DERATE_APPLIED" else "THERMAL_DERATE_UNSIGNED_RUNG",
                message = "-",
                plan = TuningPlan(
                    profileId = "performance",
                    packageName = "example.game",
                    cpuMaxKhz = mapOf(0 to 1_996_800),
                    gpuMaxPowerLevel = 2,
                    fanMode = 4,
                    calibrated = true,
                ),
            ),
            diagnosticsCompiled = false,
            labEvidenceEnabled = { false },
            logLabEvidence = {},
        )
        return GuardianDerateObservability.lastActuated ?: prior
    }

    private fun session(generation: Long, transactionId: String) = ActiveSessionIdentity(
        generation = generation,
        transactionId = transactionId,
        requestId = "mimir.system.test",
        ownerUid = 1,
        targetPackage = "example.game",
        activationDeadlineMs = Long.MAX_VALUE,
        leaseDeadlineMs = Long.MAX_VALUE,
        targetEverForeground = true,
        appliedTargetContract = null,
        scope = ActiveSessionScope.SYSTEM,
    )
}
