package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery, pinned against the two nodes it reads and the one arithmetic it can get backwards.
 *
 * The fixtures are the AYN Thor's REAL rows, captured from the device on 2026-08-31 — QCS8550, three
 * cpufreq policies and an Adreno. A fixture invented by hand would have agreed with whatever this file
 * assumed; these came out of the kernel.
 */
class MimirTunerTest {

    /** policy0 on the Thor: ascending kHz, space separated, trailing space included as the node has it. */
    private val thorPolicy0 =
        "307200 441600 556800 672000 787200 902400 1017600 1113600 1228800 1344000 1459200 " +
            "1555200 1670400 1785600 1900800 2016000 \n"

    /** The Adreno's row: DESCENDING, and in Hz rather than kHz. Both differences are real. */
    private val thorGpu =
        "680000000 615000000 550000000 475000000 401000000 348000000 295000000 220000000 124800000\n"

    private class FakeNodes(
        private val policies: List<String> = emptyList(),
        private val files: Map<String, String> = emptyMap(),
    ) : MimirTunerNodes {
        override fun listCpuPolicyDirectories(): List<String> = policies
        override fun read(path: String): String? = files[path]
    }

    @Test
    fun `a cpufreq row becomes ascending kilohertz`() {
        val parsed = parseCpuFrequenciesKhz(thorPolicy0)

        assertEquals(16, parsed.size)
        assertEquals(307_200, parsed.first())
        assertEquals(2_016_000, parsed.last())
        assertEquals(parsed.sorted(), parsed)
    }

    @Test
    fun `the adreno row is turned around and converted, because it is neither ascending nor kilohertz`() {
        // The node publishes descending Hz. Downstream must never have to know that, so both the order
        // and the unit are fixed at the boundary -- and this is the test that says so.
        val parsed = parseGpuFrequenciesKhz(thorGpu)

        assertEquals(9, parsed.size)
        assertEquals(124_800, parsed.first())
        assertEquals(680_000, parsed.last())
        assertEquals(parsed.sorted(), parsed)
    }

    @Test
    fun `a row it cannot read yields nothing rather than a fabricated table`() {
        assertEquals(emptyList<Int>(), parseCpuFrequenciesKhz(null))
        assertEquals(emptyList<Int>(), parseCpuFrequenciesKhz(""))
        assertEquals(emptyList<Int>(), parseCpuFrequenciesKhz("not a table"))
        assertEquals(emptyList<Int>(), parseGpuFrequenciesKhz(null))
        // A zero or a negative in the row is dropped, never offered as a selectable clock: echoing a
        // non-positive frequency at a cpufreq node is rejected by the kernel at best.
        assertEquals(listOf(500), parseCpuFrequenciesKhz("0 500 -100"))
    }

    @Test
    fun `the adreno power level is counted from the FASTEST entry, not the first`() {
        // The whole function is this inversion, and it is the easiest thing here to write backwards.
        // Level 0 is the LAST entry of an ascending table. Getting it wrong would cap a player's GPU at
        // its floor while the surface claimed it had raised the ceiling.
        val table = parseGpuFrequenciesKhz(thorGpu)

        assertEquals(0, gpuPowerLevelForKhz(table, 680_000))
        assertEquals(8, gpuPowerLevelForKhz(table, 124_800))
        assertEquals(4, gpuPowerLevelForKhz(table, 401_000))

        // And it round-trips, which is what makes a level the kernel reports renderable as a number.
        table.forEach { khz ->
            assertEquals(khz, gpuKhzForPowerLevel(table, gpuPowerLevelForKhz(table, khz)))
        }
    }

    @Test
    fun `a target between two steps takes the nearest one, never an interpolation`() {
        val table = parseGpuFrequenciesKhz(thorGpu)

        // 610000 sits just under 615000. The hardware has no such clock, so the nearest real one wins.
        assertEquals(615_000, gpuKhzForPowerLevel(table, gpuPowerLevelForKhz(table, 610_000)))
    }

    @Test
    fun `an empty table is answered with absence, not with a zeroth level`() {
        assertNull(gpuKhzForPowerLevel(emptyList(), 0))
        assertEquals(0, gpuPowerLevelForKhz(emptyList(), 500_000))
    }

    @Test
    fun `three clusters read as efficiency performance and prime, and four do not guess`() {
        val thor = listOf(0, 3, 7)
        assertEquals("EFFICIENCY", cpuClusterLabel(0, thor))
        assertEquals("PERFORMANCE", cpuClusterLabel(3, thor))
        assertEquals("PRIME", cpuClusterLabel(7, thor))

        // A shape this function has never seen is named by its id rather than told a story about itself.
        val four = listOf(0, 2, 4, 6)
        assertEquals("CPU 0", cpuClusterLabel(0, four))
        assertEquals("CPU 6", cpuClusterLabel(6, four))
    }

    @Test
    fun `discovery reports the thor as the thor actually is`() {
        val nodes = FakeNodes(
            policies = listOf("/sys/.../policy0", "/sys/.../policy7"),
            files = mapOf(
                "/sys/.../policy0/scaling_available_frequencies" to thorPolicy0,
                "/sys/.../policy0/scaling_max_freq" to "1555200\n",
                "/sys/.../policy7/scaling_available_frequencies" to
                    "595200 2956800\n",
                "/sys/.../policy7/scaling_max_freq" to "2092800\n",
                "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies" to thorGpu,
                "/sys/class/kgsl/kgsl-3d0/max_pwrlevel" to "0\n",
            ),
        )

        val policies = discoverTunerPolicies(nodes)

        assertEquals(3, policies.size)
        assertEquals(listOf(0, 7, GpuPolicyId), policies.map { it.id })
        // A cap below the ceiling is reported as a cap. On the device all three CPU clusters were held
        // down like this, which is a thing a player would want to see and a manifest could never say.
        assertTrue("policy0 is held below its ceiling", policies[0].capped)
        assertEquals(2_016_000, policies[0].ceilingKhz)
        // The GPU's level 0 is its ceiling, so an uncapped GPU must not read as capped.
        assertFalse("an uncapped GPU is not a capped one", policies[2].capped)
        assertEquals(680_000, policies[2].currentMaxKhz)
    }

    @Test
    fun `a cluster with no readable table is dropped rather than listed dead`() {
        val nodes = FakeNodes(
            policies = listOf("/sys/.../policy0", "/sys/.../policy9"),
            files = mapOf("/sys/.../policy0/scaling_available_frequencies" to thorPolicy0),
        )

        val policies = discoverTunerPolicies(nodes)

        assertEquals("policy9 publishes nothing, so it is not a row", 1, policies.size)
        assertEquals(0, policies.single().id)
    }

    @Test
    fun `a device that publishes nothing reports absence, and says READ and WRITE separately`() {
        // The two facts are never collapsed into one "supported" boolean. On a device that can be read
        // but not written, either single answer is a lie: unsupported hides real readings, supported
        // offers controls that cannot act.
        val silent = MimirTunerAvailability(canWrite = false, policies = emptyList())
        assertFalse(silent.canRead)

        val readOnly = MimirTunerAvailability(
            canWrite = false,
            policies = discoverTunerPolicies(
                FakeNodes(
                    policies = listOf("/sys/.../policy0"),
                    files = mapOf("/sys/.../policy0/scaling_available_frequencies" to thorPolicy0),
                ),
            ),
        )
        assertTrue("readable without a privileged host is a real state", readOnly.canRead)
        assertFalse(readOnly.canWrite)
    }
}
