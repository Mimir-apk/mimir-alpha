package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilityStatus
import com.gruning.mimir.rewrite.core.tweaker.TweakerPosture
import com.gruning.mimir.rewrite.core.tweaker.TweakerSessionScope
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionState
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionTruth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session that is mid-restore keeps its capability truth.
 *
 * **The state this pins was measured, not imagined.** On 2026-09-04 both handhelds sat in
 * `Blocked(state=RESTORING, scope=RECOVERY, leaseRenewable=false)` — the Thor 17.6 hours past its own
 * hard lease deadline — while `getCapabilities()` returned a perfectly good bundle. Every one of those
 * capabilities was thrown away because [authoritativeProfileId] returns null for such a session, so the
 * Tweaker row showed a CPU ceiling and no profiles at all, with nothing on screen to say why.
 *
 * The refusal itself is correct and is not being relaxed here: a selection against a held session is
 * refused at the daemon with `SESSION_NOT_SETTLED`, and [TweakerUtilityRowState.canStep] must stay false.
 * What changes is that the row can say so.
 */
class TweakerHeldSessionTest {

    private fun truth(
        state: TweakerV4SessionState,
        scope: TweakerSessionScope,
        activeProfileId: String? = "quiet",
    ) = TweakerV4SessionTruth(
        apiVersion = 9,
        state = state,
        mutationMayBeActive = true,
        requestId = "mimir.system.test.4",
        generation = 116L,
        transactionId = "4a61b17f-1095-46b4-b883-11d0a6199a1b",
        requestDeadlineElapsedRealtimeMs = 157_696_058L,
        leaseDeadlineElapsedRealtimeMs = 200_836_063L,
        hardLeaseDeadlineElapsedRealtimeMs = 200_836_063L,
        leaseRenewable = false,
        scope = scope,
        activeProfileId = activeProfileId,
    )

    @Test
    fun `the exact session both handhelds were stuck in reads as held`() {
        assertTrue(
            tweakerSessionHeldByUnfinishedRestore(
                TweakerV4SessionAssessment.Blocked(
                    truth(TweakerV4SessionState.RESTORING, TweakerSessionScope.RECOVERY),
                ),
            ),
        )
    }

    @Test
    fun `either half is enough - restoring in any scope, or recovery in any state`() {
        assertTrue(
            "mid-restore is held wherever it is scoped",
            tweakerSessionHeldByUnfinishedRestore(
                TweakerV4SessionAssessment.Blocked(
                    truth(TweakerV4SessionState.RESTORING, TweakerSessionScope.SYSTEM),
                ),
            ),
        )
        assertTrue(
            "a recovery session is held whatever it says it is doing",
            tweakerSessionHeldByUnfinishedRestore(
                TweakerV4SessionAssessment.Blocked(
                    truth(TweakerV4SessionState.ACTIVE, TweakerSessionScope.RECOVERY),
                ),
            ),
        )
    }

    @Test
    fun `a healthy active system session is NOT held`() {
        assertFalse(
            "this is the ordinary applied profile; marking it held would freeze a working row",
            tweakerSessionHeldByUnfinishedRestore(
                TweakerV4SessionAssessment.Blocked(
                    truth(TweakerV4SessionState.ACTIVE, TweakerSessionScope.SYSTEM),
                ),
            ),
        )
    }

    @Test
    fun `a held session keeps the daemon's capabilities instead of discarding them`() {
        val observed = TweakerCapabilities(
            status = TweakerCapabilityStatus.CALIBRATION_REQUIRED,
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            hardwareFingerprint = "odin3",
            supportedAxes = setOf("CPU", "GPU"),
            notes = emptyList(),
            posture = TweakerPosture.PRODUCTION,
            dryRun = false,
            usageAccessGranted = true,
            supportedUncertifiedProfileIds = setOf("quiet", "balanced", "performance"),
        )
        val held = TweakerV4SessionAssessment.Blocked(
            truth(TweakerV4SessionState.RESTORING, TweakerSessionScope.RECOVERY),
        )
        assertNotNull(
            "the whole defect: these capabilities existed and were thrown away",
            authoritativeV4Capabilities(assessment = held) { observed },
        )
        assertEquals(observed, authoritativeV4Capabilities(assessment = held) { observed })
    }

    @Test
    fun `a held session with nothing to report still yields nothing`() {
        val held = TweakerV4SessionAssessment.Blocked(
            truth(TweakerV4SessionState.RESTORING, TweakerSessionScope.RECOVERY),
        )
        assertNull(
            "keeping a held session's truth must not invent one",
            authoritativeV4Capabilities(assessment = held) { null },
        )
    }
}
