package com.gruning.mimir.rewrite

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO writes the clock ceiling — the five-row table in `MimirTunerRoute.kt`, pinned.
 *
 * **Why a table test and not five assertions scattered through the call sites.** The ruling
 * (2026-09-03: *the daemon owns the node whenever it is present; the Tuner routes through it and writes
 * directly only on a device with no daemon*) is a statement about two facts, and the moment it is
 * re-derived at a second call site the two copies can disagree — which is how the node acquired two
 * writers in the first place. So the policy is one pure function, and this is the test that says what
 * it means. Every row below is a device state that exists.
 *
 * **The two REFUSE rows are the ones worth reading.** Both describe a device where writing directly
 * would "work" — the node is there, the privileged host is there, a stepper press would land. Refusing
 * anyway is the whole ruling: a cap written around a daemon that exists is the two-writer state, and on
 * the Thor on 2026-09-11 it produced a reconcile that widened CPU 7 to 2956800 over an active `quiet`
 * profile and was only saved by the daemon's own supervisor racing it back.
 */
class MimirTunerRouteDecisionTest {

    @After
    fun tearDown() = MimirTunerDaemonRoute.reset()

    private fun presence(pairCanBind: Boolean, apiVersion: Int?, profileSessionActive: Boolean? = null) =
        MimirTunerDaemonPresence(pairCanBind, apiVersion, profileSessionActive)

    /** Row 1. Before the bridge has looked, nothing is known, and nothing is written on a guess. */
    @Test
    fun `an unresolved pair refuses`() {
        val decision = mimirTunerRouteDecision(null)

        assertEquals(
            MimirTunerRouteDecision.Refuse(MimirTunerRefusePairUnresolved),
            decision,
        )
    }

    /** Row 2. No daemon on this device: discovery is the half that works everywhere. */
    @Test
    fun `a device with no daemon writes directly`() {
        assertEquals(
            MimirTunerRouteDecision.Direct,
            mimirTunerRouteDecision(presence(pairCanBind = false, apiVersion = null)),
        )
    }

    /**
     * Row 3, and the sharp edge of the ruling. A daemon exists and did not answer — busy, restarting,
     * mid-restore. This is the row a "fall back to the direct write" instinct gets wrong: falling back
     * here recreates the two-writer state precisely on the devices that have a daemon to collide with.
     */
    @Test
    fun `an eligible pair with no API version refuses rather than falling back`() {
        val decision = mimirTunerRouteDecision(presence(pairCanBind = true, apiVersion = null))

        assertEquals(MimirTunerRouteDecision.Refuse(MimirTunerRefuseDaemonBusy), decision)
        assertTrue(
            "a momentarily unreachable daemon must never route DIRECT",
            decision !is MimirTunerRouteDecision.Direct,
        )
    }

    /** Row 4. An old daemon cannot take the write, so today's path and the v9 record stand. */
    @Test
    fun `a daemon below v10 keeps the direct write`() {
        (1 until MimirTunerDaemonRouteMinApiVersion).forEach { version ->
            assertEquals(
                "API $version cannot take a ceiling command",
                MimirTunerRouteDecision.Direct,
                mimirTunerRouteDecision(presence(pairCanBind = true, apiVersion = version)),
            )
        }
    }

    /** Row 5. The ruled shape. */
    @Test
    fun `a v10 daemon takes the write`() {
        assertEquals(
            MimirTunerRouteDecision.Daemon,
            mimirTunerRouteDecision(presence(pairCanBind = true, apiVersion = MimirTunerDaemonRouteMinApiVersion)),
        )
        assertEquals(
            "a later daemon is still a daemon",
            MimirTunerRouteDecision.Daemon,
            mimirTunerRouteDecision(presence(pairCanBind = true, apiVersion = 11)),
        )
    }

    /**
     * The version is the axis, and the session is not.
     *
     * `profileSessionActive` steers the COLD START (an old daemon running a named profile must not have
     * its cap widened) and the daemon's own `PROFILE_ACTIVE` refusal. It is deliberately not an input to
     * the route: who writes is a question about which processes exist, and letting a transient session
     * state move the writer would mean the answer changed while a press was in flight.
     */
    @Test
    fun `an active profile session never moves the route`() {
        listOf(true, false, null).forEach { session ->
            assertEquals(
                "session=$session must not change who writes",
                MimirTunerRouteDecision.Daemon,
                mimirTunerRouteDecision(presence(true, MimirTunerDaemonRouteMinApiVersion, session)),
            )
            assertEquals(
                "session=$session must not change who writes",
                MimirTunerRouteDecision.Direct,
                mimirTunerRouteDecision(presence(false, null, session)),
            )
        }
    }

    /** The reason strings are spelled once so a surface and a log can never name the same refusal differently. */
    @Test
    fun `each refusal names itself`() {
        val unresolved = mimirTunerRouteDecision(null) as MimirTunerRouteDecision.Refuse
        val busy = mimirTunerRouteDecision(presence(true, null)) as MimirTunerRouteDecision.Refuse

        assertEquals("PAIR UNRESOLVED", unresolved.reason)
        assertEquals("TWEAKER BUSY", busy.reason)
        assertTrue("two refusals, two reasons", unresolved.reason != busy.reason)
    }

    /**
     * The process-scoped binding starts empty, and empty is REFUSE.
     *
     * This is the state every process is in for the first few milliseconds, before `TweakerBridge`'s
     * cold start states the pair fact. A press in that window is refused, which is correct and is why
     * the bridge sets presence SYNCHRONOUSLY rather than from the IO scope it does the rest of the
     * cold start on.
     */
    @Test
    fun `the route starts with nothing installed and refuses`() {
        MimirTunerDaemonRoute.reset()

        assertNull(MimirTunerDaemonRoute.presence)
        assertNull(MimirTunerDaemonRoute.actuator)
        assertEquals(
            MimirTunerRouteDecision.Refuse(MimirTunerRefusePairUnresolved),
            MimirTunerDaemonRoute.decision(),
        )
    }

    @Test
    fun `the installed presence is what decision reads`() {
        MimirTunerDaemonRoute.presence = presence(true, 10, profileSessionActive = false)

        assertEquals(MimirTunerRouteDecision.Daemon, MimirTunerDaemonRoute.decision())
    }

    /**
     * The seam's own constant, checked against the contract's minimum rather than assumed equal to it.
     * They live in two modules and one build may ship without the other; a drift here would route
     * ceilings to a daemon that has no method to receive them.
     */
    @Test
    fun `the route's minimum version is the ceiling API's minimum version`() {
        assertEquals(
            com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V10_DISCOVERED_CEILING_MIN_API_VERSION,
            MimirTunerDaemonRouteMinApiVersion,
        )
    }
}
