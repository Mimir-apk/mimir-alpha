package com.gruning.mimir.tweaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabOwnerValidationActivationGateTest {
    private val stableVector = LabOwnerValidationMutableAxisVector(
        bootId = "boot",
        cpuQosCeilingKhz = mapOf(0 to 3_000_000),
        cpuQosTransport = CpuQosTransport.SHARED_QOS,
        gpuMinPowerLevel = 6,
        gpuMaxPowerLevel = 0,
        vendorFanMode = null,
    )

    @Test
    fun `target must remain foreground for the complete vendor quiescence window`() {
        var now = 1_000L
        var reads = 0

        val ready = LabOwnerValidationActivationGate.await(
            observe = {
                reads++
                if (reads < 3) WorkloadTruth.OTHER_FOREGROUND else WorkloadTruth.TARGET_FOREGROUND
            },
            sampleMutableAxes = { stableVector },
            nowElapsedRealtimeMs = { now },
            sleepMs = { now += it },
        )

        assertTrue(ready)
        assertTrue(now >= 1_000L + (2 * LabOwnerValidationActivationGate.POLL_MS) +
            LabOwnerValidationActivationGate.QUIESCENCE_MS)
    }

    @Test
    fun `stale paused truth before dispatch waits for the new target foreground`() {
        var now = 1_000L
        var reads = 0
        val ready = LabOwnerValidationActivationGate.await(
            observe = {
                reads++
                if (reads <= 3) WorkloadTruth.TARGET_PAUSED_OR_STOPPED
                else WorkloadTruth.TARGET_FOREGROUND
            },
            sampleMutableAxes = { stableVector },
            nowElapsedRealtimeMs = { now },
            sleepMs = { now += it },
        )

        assertTrue(ready)
    }

    @Test
    fun `foreground loss resets the quiescence clock`() {
        var now = 2_000L
        var reads = 0
        val ready = LabOwnerValidationActivationGate.await(
            observe = {
                reads++
                when {
                    reads <= 5 -> WorkloadTruth.TARGET_FOREGROUND
                    reads == 6 -> WorkloadTruth.OTHER_FOREGROUND
                    else -> WorkloadTruth.TARGET_FOREGROUND
                }
            },
            sampleMutableAxes = { stableVector },
            nowElapsedRealtimeMs = { now },
            sleepMs = { now += it },
        )

        assertTrue(ready)
        assertTrue(reads > 10)
    }

    @Test
    fun `access failure and timeout fail closed before mutation`() {
        var now = 0L
        assertFalse(
            LabOwnerValidationActivationGate.await(
                observe = { WorkloadTruth.ACCESS_DENIED },
                sampleMutableAxes = { stableVector },
                nowElapsedRealtimeMs = { now },
                sleepMs = { now += it },
            ),
        )

        now = 0L
        assertFalse(
            LabOwnerValidationActivationGate.await(
                observe = { WorkloadTruth.INDETERMINATE },
                sampleMutableAxes = { stableVector },
                nowElapsedRealtimeMs = { now },
                sleepMs = { now += it },
            ),
        )
        assertTrue(now >= LabOwnerValidationActivationGate.FOREGROUND_TIMEOUT_MS)
    }

    @Test
    fun `mutable axis changes restart the stable window`() {
        var now = 0L
        var samples = 0
        val ready = LabOwnerValidationActivationGate.await(
            observe = { WorkloadTruth.TARGET_FOREGROUND },
            sampleMutableAxes = {
                samples++
                if (samples <= 5) stableVector
                else stableVector.copy(cpuQosCeilingKhz = mapOf(0 to 3_200_000))
            },
            nowElapsedRealtimeMs = { now },
            sleepMs = { now += it },
        )

        assertTrue(ready)
        assertTrue(samples > 10)
    }
}
