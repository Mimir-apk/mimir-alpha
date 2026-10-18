package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The daemon previously ran a full `capabilities()` on every telemetry sample.
 * Measured on Nova: ~244 ms of daemon CPU per compose (653/649 jiffies per 120s with
 * Mimir alive vs 5 with it force-stopped, over 27/26 composes) — 5.4% of a core at the hidden 5s
 * cadence and ~24% at the sanctioned 1 Hz card-wanted cadence, i.e. while a game is running.
 *
 * These pin the three mechanisms that made the sample expensive. Two of them pin a trap that was
 * hit for real while writing the fix, which is why they assert the way they do.
 */
class TelemetrySamplingCostTest {

    @Before
    fun clearMemo() = SupportedUncertifiedProfiles.clearComputeMemoForTest()

    // ---- the memo ---------------------------------------------------------------------------

    @Test
    fun `compute is memoised on topology, not on live frequency`() {
        // THE TRAP, hit for real: `CpuPolicy` is a data class carrying `currentKhz`, which
        // `HardwareProbe.probe()` refreshes from sysfs on EVERY call. A key using data-class
        // equality would differ on nearly every probe, the memo would never hit, and the "fix"
        // would be pure overhead. `currentKhz` provably cannot move compute's answer — it is
        // written by the probe and read only by BundleAdapters' log line.
        val first = SupportedUncertifiedProfiles.compute(
            CapabilityStatus.CALIBRATION_REQUIRED, NOVA_SOC, novaPolicies(currentKhz = 300_000),
        )
        assertEquals(1, SupportedUncertifiedProfiles.computeMemoSizeForTest())

        val second = SupportedUncertifiedProfiles.compute(
            CapabilityStatus.CALIBRATION_REQUIRED, NOVA_SOC, novaPolicies(currentKhz = 2_803_200),
        )
        assertSame("a moved currentKhz must still hit the memo", first, second)
        assertEquals(
            "a live-frequency change must not add a memo entry",
            1,
            SupportedUncertifiedProfiles.computeMemoSizeForTest(),
        )
    }

    @Test
    fun `a changed topology is a miss, never a stale hit`() {
        SupportedUncertifiedProfiles.compute(
            CapabilityStatus.CALIBRATION_REQUIRED, NOVA_SOC, novaPolicies(),
        )
        SupportedUncertifiedProfiles.compute(
            CapabilityStatus.CALIBRATION_REQUIRED,
            NOVA_SOC,
            novaPolicies().map { it.copy(availableFrequenciesKhz = it.availableFrequenciesKhz.dropLast(1)) },
        )
        assertEquals(2, SupportedUncertifiedProfiles.computeMemoSizeForTest())
    }

    @Test
    fun `status is part of the key, so an UNSUPPORTED probe cannot serve a supported answer`() {
        val supported = SupportedUncertifiedProfiles.compute(
            CapabilityStatus.CALIBRATION_REQUIRED, NOVA_SOC, novaPolicies(),
        )
        val unsupported = SupportedUncertifiedProfiles.compute(
            CapabilityStatus.UNSUPPORTED, NOVA_SOC, novaPolicies(),
        )
        assertTrue(unsupported.isEmpty())
        assertNotEquals(supported, unsupported)
    }

    @Test
    fun `the memo is bounded and cannot grow without limit`() {
        repeat(40) { i ->
            SupportedUncertifiedProfiles.compute(
                CapabilityStatus.CALIBRATION_REQUIRED, "soc-$i", novaPolicies(),
            )
        }
        assertTrue(
            "memo grew unbounded: ${SupportedUncertifiedProfiles.computeMemoSizeForTest()}",
            SupportedUncertifiedProfiles.computeMemoSizeForTest() <= 8,
        )
    }

    // ---- the telemetry composer -------------------------------------------------------------

    /**
     * The composer must not call `capabilities()`. Source-contract, because the cost is structural
     * and the alternative pin (a device jiffies measurement) cannot run in CI. Comment-stripped so
     * the KDoc that EXPLAINS the defect cannot satisfy the assertion — that exact failure mode occurred
     * in the earlier source pin (R-NOVA-1).
     */
    @Test
    fun `the telemetry composer does not pay for qualification truth`() {
        val code = strippedSource("MimirTweakerService.kt")
        val composer = code.substringAfter("LiveTelemetrySnapshotCache(").substringBefore("supervisorPolicy =")
        assertTrue(
            "the composer must build telemetry from probe.probe(), not capabilities()",
            composer.contains("val capabilities = probe.probe()"),
        )
        assertFalse(
            "capabilities() drags manifest decode, a SHA-256, an AppOps query and a disk read " +
                "into a path that reads none of them",
            composer.contains("val capabilities = capabilities()"),
        )
    }

    /**
     * The five fields the telemetry path actually reads. If a future change makes telemetry read a
     * field that `capabilities()` adds — posture, mutationAvailable, dryRun, usageAccessGranted,
     * qualificationSource, or either profile-id set — then `probe.probe()` is no longer sufficient
     * and this test is the gate that says so.
     */
    @Test
    fun `probe alone still carries everything telemetry reads`() {
        val hardware = strippedSource("AndroidHardware.kt")
        val telemetryBody = hardware.substringAfter("fun telemetry(capabilities: HardwareCapabilities)")
            .substringBefore("\n    fun ")
        val forbidden = listOf(
            "capabilities.posture",
            "capabilities.mutationAvailable",
            "capabilities.dryRun",
            "capabilities.usageAccessGranted",
            "capabilities.qualificationSource",
            "capabilities.certifiedProfileIds",
            "capabilities.supportedUncertifiedProfileIds",
        )
        for (field in forbidden) {
            assertFalse(
                "$field is added by capabilities(), which the telemetry path no longer calls",
                telemetryBody.contains(field),
            )
        }
    }

    private fun strippedSource(name: String): String {
        val src = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("$name not found")
        return src.readText().lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private companion object {
        const val NOVA_SOC = "SM8550"
        fun novaPolicies(currentKhz: Int = 1_000_000) = listOf(
            CpuPolicy(
                id = 0, path = "/sys/devices/system/cpu/cpufreq/policy0", cpuIds = listOf(0, 1, 2),
                availableFrequenciesKhz = listOf(300_000, 1_000_000, 2_016_000),
                minKhz = 300_000, maxKhz = 2_016_000, currentKhz = currentKhz,
            ),
            CpuPolicy(
                id = 3, path = "/sys/devices/system/cpu/cpufreq/policy3", cpuIds = listOf(3, 4, 5, 6),
                availableFrequenciesKhz = listOf(600_000, 1_800_000, 2_803_200),
                minKhz = 600_000, maxKhz = 2_803_200, currentKhz = currentKhz,
            ),
            CpuPolicy(
                id = 7, path = "/sys/devices/system/cpu/cpufreq/policy7", cpuIds = listOf(7),
                availableFrequenciesKhz = listOf(700_000, 2_000_000, 3_187_200),
                minKhz = 700_000, maxKhz = 3_187_200, currentKhz = currentKhz,
            ),
        )
    }
}
