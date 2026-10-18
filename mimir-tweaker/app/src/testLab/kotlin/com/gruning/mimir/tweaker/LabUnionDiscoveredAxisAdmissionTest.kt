package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAB posture's synthetic axis, and the proof that admission is still ALIVE underneath it.
 *
 * **This test exists because the failure it guards against is the seductive one.** A diagnostic seam
 * that widened discovery could just as easily have been written to short-circuit the verdict, and the
 * two are indistinguishable from a green "the record was stored" line. So the pins here are deliberately
 * NEGATIVE: a frequency the synthetic table does not publish must still be refused, a cpu outside the
 * synthetic cluster must still be refused, and a real device that published the same id must still win.
 * If any of those ever passes, the union has stopped being a discovery and become a bypass, and the
 * whole deterministic proof it supports is worthless.
 *
 * Lives in `src/testLab` rather than `src/test` because the type under test is compiled into the LAB
 * flavour ONLY. A copy in the shared test source set would fail to compile under
 * `testSafeDebugUnitTest` and `testProductionDebugUnitTest`, which is the structural property those
 * variants are supposed to have.
 */
class LabUnionDiscoveredAxisAdmissionTest {

    /** The AYN Thor's real clusters, captured 2026-08-31 — the same fixture the sibling suite uses. */
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

    private val union = composeDiscoveredAxisAdmission(thorDiscovery)

    private fun record(stock: String, requested: String) = DiscoveredAxisRecord(
        bootIdentity = "6f0f1d2e-0000-4000-8000-aaaaaaaaaaaa",
        cpuStockRow = stock,
        cpuRequestedRow = requested,
    )

    private fun refusalCode(stock: String, requested: String): String {
        val verdict = DiscoveredAxisAdmissionCheck.admit(record(stock, requested), union)
        assertTrue("expected a refusal, got $verdict", verdict is DiscoveredAxisAdmissionResult.Refused)
        return (verdict as DiscoveredAxisAdmissionResult.Refused).code
    }

    @Test
    fun `the synthetic session the lab axis produces is admitted through the union`() {
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(
                record(
                    stock = TweakerLabTunerAxisContract.LAB_STOCK_ROW,
                    requested = "240:${TweakerLabTunerAxisContract.LAB_CAP_KHZ} " +
                        "241:${TweakerLabTunerAxisContract.LAB_CAP_KHZ}",
                ),
                union,
            ),
        )
    }

    @Test
    fun `the real device's own cpus are still admitted while the union is in force`() {
        // The union ADDS; it must not replace. A lab daemon that stopped admitting this device's real
        // sessions would be a lab daemon nobody could use for a real-axis pass on the same install.
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(record(stock = "7:2956800", requested = "7:595200"), union),
        )
    }

    @Test
    fun `a frequency the synthetic table does not publish is still refused under the union`() {
        // 1700000 sits between two real steps of the synthetic table and is in neither. Admission is
        // per-cpu against a published set, and widening discovery must not have loosened that.
        assertEquals("UNDISCOVERED_FREQUENCY", refusalCode(stock = "240:1700000", requested = "240:1200000"))
    }

    @Test
    fun `a cpu outside the synthetic cluster is still refused under the union`() {
        // 242 is adjacent to the synthetic ids and belongs to neither the fixture nor the union.
        assertEquals("UNDISCOVERED_CPU", refusalCode(stock = "242:1800000", requested = "242:1200000"))
    }

    @Test
    fun `INT_MAX stays legal on a synthetic cpu, because it is the kernel's spelling of no ceiling`() {
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(record(stock = "240:2147483647", requested = "240:600000"), union),
        )
    }

    @Test
    fun `the real map wins an id collision, so a fixture can never shadow real silicon`() {
        val collidingDevice = object : DiscoveredAxisAdmission {
            override fun cpuFrequenciesKhz(): Map<Int, Set<Int>> =
                mapOf(TweakerLabTunerAxisContract.LAB_POLICY_ID to setOf(999_000))

            override fun gpuPowerLevelCount(): Int = 0
        }
        val colliding = composeDiscoveredAxisAdmission(collidingDevice)
        assertEquals(
            DiscoveredAxisAdmissionResult.Admitted,
            DiscoveredAxisAdmissionCheck.admit(record(stock = "240:999000", requested = "240:999000"), colliding),
        )
        val verdict = DiscoveredAxisAdmissionCheck.admit(
            record(stock = "240:${TweakerLabTunerAxisContract.LAB_CAP_KHZ}", requested = "240:999000"),
            colliding,
        )
        assertEquals(
            "UNDISCOVERED_FREQUENCY",
            (verdict as DiscoveredAxisAdmissionResult.Refused).code,
        )
    }

    @Test
    fun `the union reports the device's own gpu level count, never a synthetic one`() {
        assertEquals(9, union.gpuPowerLevelCount())
    }

    @Test
    fun `a record with no boot identity is refused under the union too`() {
        // The cheapest way to catch a union that had accidentally been wired in FRONT of admit rather
        // than into its discovery input: this refusal never consults the cpu map at all.
        val nameless = record(
            stock = TweakerLabTunerAxisContract.LAB_STOCK_ROW,
            requested = "240:1800000",
        ).copy(bootIdentity = "   ")
        val verdict = DiscoveredAxisAdmissionCheck.admit(nameless, union)
        assertEquals("NO_BOOT_IDENTITY", (verdict as DiscoveredAxisAdmissionResult.Refused).code)
    }
}
