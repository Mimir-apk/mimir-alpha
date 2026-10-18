package com.gruning.mimir.tweaker

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemWideProfileSupervisorTest {
    @Test
    fun `system scope skips workload package observation but retains owner and thermal gates`() {
        val fixture = Fixture()
        fixture.adopt()

        assertEquals(SupervisorDecision.Continue, fixture.supervisor.tick())
        assertEquals(0, fixture.workloadCalls)

        fixture.ownerAlive = false
        val death = fixture.supervisor.tick() as SupervisorDecision.Restore
        assertEquals("OWNER_TOKEN_DIED", death.reason)
    }

    @Test
    fun `system scope adopts immutable hard deadline and is not renewable`() {
        val fixture = Fixture()
        val deadline = ActiveSessionLeaseDeadline.initial(
            scope = ActiveSessionScope.SYSTEM,
            nowElapsedRealtimeMs = 0L,
            renewableWindowMs = 30_000L,
            hardDeadlineElapsedRealtimeMs = 43_200_000L,
        )
        fixture.adopt(leaseDeadlineMs = deadline, hardDeadlineMs = 43_200_000L)
        fixture.now = 10_000L

        assertEquals(43_200_000L, fixture.supervisor.activeSession()?.leaseDeadlineMs)
        assertEquals(false, fixture.supervisor.leaseRenewable())
        assertEquals(
            false,
            fixture.supervisor.renewLease(
                ownerUid = 42,
                ownerTokenId = 9L,
                generation = 7L,
                transactionId = "tx-7",
                requestId = "req-7",
            ),
        )
        assertEquals(
            40_000L,
            ActiveSessionLeaseDeadline.initial(
                scope = ActiveSessionScope.LAUNCH,
                nowElapsedRealtimeMs = 10_000L,
                renewableWindowMs = 30_000L,
                hardDeadlineElapsedRealtimeMs = 43_200_000L,
            ),
        )
    }

    @Test
    fun `system scope hard expiry restores even with live owner`() {
        val fixture = Fixture()
        fixture.adopt(leaseDeadlineMs = 43_200_000L, hardDeadlineMs = 43_200_000L)
        fixture.now = 43_200_000L

        val expired = fixture.supervisor.tick() as SupervisorDecision.Restore

        assertEquals("HARD_LEASE_EXPIRED", expired.reason)
        assertEquals(0, fixture.workloadCalls)
    }

    private class Fixture {
        var now = 0L
        var ownerAlive = true
        var workloadCalls = 0
        val supervisor = HardwareSessionSupervisor(
            policy = SupervisorPolicy(),
            clock = SupervisorClock { now },
            workload = WorkloadObservationPort { workloadCalls++; WorkloadTruth.QUERY_FAILED },
            telemetry = ThermalTelemetryPort {
                ThermalTelemetrySample(
                    androidThermalStatus = PowerManager.THERMAL_STATUS_NONE,
                    cpuTempC = 40,
                    gpuTempC = 40,
                    batteryTempDeciC = 300,
                )
            },
            ownerLiveness = OwnerLivenessPort { ownerAlive },
            restorer = GenerationBoundRestorer { _, _ ->
                TransactionResult(true, false, true, "RESTORED", "restored")
            },
        )

        fun adopt(leaseDeadlineMs: Long = 30_000L, hardDeadlineMs: Long = 43_200_000L) {
            supervisor.adoptAppliedSession(
                ActiveSessionIdentity(
                    generation = 7L,
                    transactionId = "tx-7",
                    requestId = "req-7",
                    ownerUid = 42,
                    targetPackage = "",
                    activationDeadlineMs = 10_000L,
                    leaseDeadlineMs = leaseDeadlineMs,
                    targetEverForeground = true,
                    ownerTokenId = 9L,
                    hardLeaseDeadlineMs = hardDeadlineMs,
                    scope = ActiveSessionScope.SYSTEM,
                ),
            )
        }
    }
}
