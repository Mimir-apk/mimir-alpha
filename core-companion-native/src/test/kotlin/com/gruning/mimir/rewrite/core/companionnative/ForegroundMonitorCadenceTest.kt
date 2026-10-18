package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundMonitorCadenceTest {
    private val policy = ForegroundMonitorCadencePolicy()

    @Test
    fun `mirror cadence follows primary task edges within one quarter second`() {
        assertEquals(
            250L,
            policy.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.MIRROR_PRESENTED),
                usageAccessGranted = true,
            ),
        )
        assertEquals(
            100L,
            policy.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.PRESENTING,
                    taskObservationPending = true,
                ),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun taskEvidenceDispositionsUseConfigurableFastCadence() {
        val custom = ForegroundMonitorCadencePolicy(
            ForegroundMonitorCadence(taskEvidencePollMs = 321L, mirrorPollMs = 4_321L),
        )

        listOf(
            ForegroundMonitorDisposition.LAUNCH_PENDING,
            ForegroundMonitorDisposition.RECLAIM_PENDING,
            ForegroundMonitorDisposition.RECOVERY_NEEDED,
        ).forEach { disposition ->
            assertEquals(
                321L,
                custom.nextDelayMs(ForegroundMonitorDemand(disposition), usageAccessGranted = true),
            )
        }
        assertEquals(
            4_321L,
            custom.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.YIELDED),
                usageAccessGranted = true,
            ),
        )
        assertEquals(
            321L,
            custom.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.YIELDED,
                    taskObservationPending = true,
                ),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun `cold recovery has no idle task cadence but pending evidence still wakes`() {
        assertNull(
            policy.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.RECOVERY_NEEDED,
                    recoveryTaskEvidenceRequired = false,
                ),
                usageAccessGranted = true,
            ),
        )
        assertEquals(
            100L,
            policy.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.RECOVERY_NEEDED,
                    taskObservationPending = true,
                    recoveryTaskEvidenceRequired = false,
                ),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun noUsageAccessKeepsFiveSecondSkippedCadence() {
        assertNull(
            policy.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.NO_DISPLAY),
                usageAccessGranted = false,
            ),
        )
        assertEquals(
            5_000L,
            policy.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.MIRROR_PRESENTED),
                usageAccessGranted = false,
            ),
        )
    }

    @Test
    fun `active primary stops idle follow but never suppresses pending evidence`() {
        assertNull(
            policy.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.MIRROR_PRESENTED,
                    externalFollowActive = false,
                ),
                usageAccessGranted = true,
            ),
        )
        assertEquals(
            100L,
            policy.nextDelayMs(
                ForegroundMonitorDemand(
                    ForegroundMonitorDisposition.MIRROR_PRESENTED,
                    taskObservationPending = true,
                    externalFollowActive = false,
                ),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun `the shared loop is idle on a single-screen device, which is why a launch cover has its own beat`() {
        // NO_DISPLAY is what a device with no secondary display reports for the whole session. Nothing wakes the loop
        // there, not even pending task evidence, so the cover cannot borrow it: this is the finding that made the cover's
        // observation its own seam.
        assertNull(
            policy.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.NO_DISPLAY, taskObservationPending = true),
                usageAccessGranted = true,
            ),
        )
    }

    @Test
    fun `a launch cover reads activity resumes at the 100 ms task-evidence beat`() {
        assertEquals(100L, policy.launchCoverDelayMs(usageAccessGranted = true))
        assertEquals(
            "the cover's beat IS the task-evidence cadence, so a change to that cadence moves both",
            policy.nextDelayMs(
                ForegroundMonitorDemand(ForegroundMonitorDisposition.LAUNCH_PENDING),
                usageAccessGranted = true,
            ),
            policy.launchCoverDelayMs(usageAccessGranted = true),
        )
        val custom = ForegroundMonitorCadencePolicy(ForegroundMonitorCadence(taskEvidencePollMs = 321L))
        assertEquals(321L, custom.launchCoverDelayMs(usageAccessGranted = true))
    }

    @Test
    fun `a launch cover reads nothing without Usage Access because the events do not exist`() {
        assertNull(policy.launchCoverDelayMs(usageAccessGranted = false))
    }

    @Test
    fun displaylessObservationCanNeverSettleDisposition() {
        val observation = ForegroundReturnObservation(packageName = "org.example.game")

        assertFalse(observation.canSettleDisplayDisposition)
        assertNull(observation.displayId)
    }
}
