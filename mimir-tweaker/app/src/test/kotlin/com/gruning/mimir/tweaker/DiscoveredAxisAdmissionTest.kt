package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this service will and will not write down for the discovered tuner .
 *
 * **The subject is admission, and admission is the security property.** The record family stores; it
 * never writes hardware. But a privileged, signature-protected process that stored whatever an
 * unprivileged caller claimed would be laundering the caller's numbers through its own authority, and
 * the app reads those numbers back to decide what to write to a sysfs node. So every value is admitted
 * against THIS process's own hardware discovery, and a record naming a CPU or a frequency the silicon
 * does not publish is refused rather than stored. No sysfs path can cross the boundary at all — the
 * record's shape has nowhere to put one, which is why there is no path-traversal test here to write.
 */
class DiscoveredAxisAdmissionTest {

    /** The AYN Thor's real clusters, captured 2026-08-31. */
    private val thorDiscovery = object : DiscoveredAxisAdmission {
        override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> {
            val little = setOf(300_000, 1_555_200, 2_016_000)
            val big = setOf(710_400, 2_054_400, 2_707_200)
            val prime = setOf(595_200, 2_092_800, 2_956_800)
            return buildMap {
                (0..2).forEach { put(it, little) }
                (3..6).forEach { put(it, big) }
                put(7, prime)
            }
        }

        override fun gpuPowerLevelCount(): Int = 9
    }

    private fun record(
        stock: String = "7:2956800",
        requested: String = "7:595200",
        gpuStockMin: Int = DiscoveredAxisRecord.ABSENT_LEVEL,
        gpuStockMax: Int = DiscoveredAxisRecord.ABSENT_LEVEL,
    ) = DiscoveredAxisRecord(
        bootIdentity = "6f0f1d2e-0000-4000-8000-aaaaaaaaaaaa",
        cpuStockRow = stock,
        cpuRequestedRow = requested,
        gpuStockMinLevel = gpuStockMin,
        gpuStockMaxLevel = gpuStockMax,
    )

    private fun refusal(record: DiscoveredAxisRecord): DiscoveredAxisAdmissionResult.Refused {
        val verdict = DiscoveredAxisAdmissionCheck.admit(record, thorDiscovery)
        assertTrue("expected a refusal, got $verdict", verdict is DiscoveredAxisAdmissionResult.Refused)
        return verdict as DiscoveredAxisAdmissionResult.Refused
    }

    @Test
    fun `a session this device could actually have produced is admitted`() {
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(record(), thorDiscovery),
        )
    }

    @Test
    fun `INT_MAX is admitted on any CPU, because it is the kernel's own spelling of no ceiling`() {
        // The Thor's CPU 7 was observed AT Int.MAX_VALUE on this very node. Refusing it would refuse a
        // real stock value and make the restore it belongs to impossible.
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(record(stock = "7:2147483647"), thorDiscovery),
        )
    }

    @Test
    fun `a CPU this device never published is refused`() {
        assertEquals("UNDISCOVERED_CPU", refusal(record(stock = "9:2016000")).code)
    }

    @Test
    fun `a frequency outside that CPU's own OPP table is refused`() {
        // 2707200 is real silicon -- on the BIG cluster. It is not a frequency CPU 7 publishes, and a
        // record is admitted per CPU, never against "some frequency this SoC has somewhere".
        assertEquals("UNDISCOVERED_FREQUENCY", refusal(record(stock = "7:2707200")).code)
    }

    @Test
    fun `a malformed row is refused rather than half-parsed`() {
        assertEquals("MALFORMED_ROW", refusal(record(stock = "7:not-a-number")).code)
        assertEquals("MALFORMED_ROW", refusal(record(stock = "7")).code)
    }

    @Test
    fun `a record that cannot name its boot is refused`() {
        // Boot re-establishes the axis, so a record with no boot identity can never be safely adopted.
        val nameless = record().copy(bootIdentity = "  ")
        assertEquals("NO_BOOT_IDENTITY", refusal(nameless).code)
    }

    @Test
    fun `a power level outside the published range is refused`() {
        assertEquals("UNDISCOVERED_POWER_LEVEL", refusal(record(gpuStockMin = 9, gpuStockMax = 0)).code)
        assertEquals("UNDISCOVERED_POWER_LEVEL", refusal(record(gpuStockMin = 8, gpuStockMax = -2)).code)
    }

    @Test
    fun `a GPU session is refused outright on a device that published no GPU axis`() {
        val noGpu = object : DiscoveredAxisAdmission {
            override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> = thorDiscovery.cpuFrequenciesKhz()
            override fun gpuPowerLevelCount(): Int = 0
        }
        val verdict = DiscoveredAxisAdmissionCheck.admit(
            record(gpuStockMin = 8, gpuStockMax = 0),
            noGpu,
        )
        assertEquals(
            "NO_GPU_AXIS",
            (verdict as DiscoveredAxisAdmissionResult.Refused).code,
        )
    }

    @Test
    fun `an empty row is absence, not a malformed record`() {
        // A CPU-only session names no GPU, and a GPU-only session names no CPUs. Neither is an error.
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(
                record(stock = "", requested = "", gpuStockMin = 8, gpuStockMax = 0),
                thorDiscovery,
            ),
        )
    }
}
