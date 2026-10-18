package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RC-47 option 2. `getTelemetry()` re-probed the whole hardware inventory on every tick; the cache
 * has to stop that WITHOUT freezing anything a mutation is admitted on.
 *
 * These tests count reads per path, so "cached" and "still live" are both observable facts rather
 * than claims about the code's shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class HardwareProbeStaticTopologyCacheTest {

    private val reads = mutableMapOf<String, Int>()

    private fun countingProbe(pServerAccepts: Boolean = false): HardwareProbe {
        val values = mapOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "300000 1000000 1800000",
            "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "1800000",
            "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq" to "300000",
            "/sys/devices/system/cpu/cpufreq/policy0/affected_cpus" to "0 1 2 3",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq" to "1200000",
            "${HardwareProbe.GPU_FREQUENCIES}" to "800000000 500000000",
        )
        val reader = SystemReader(
            privilegedNodes = null,
            directRead = { path ->
                reads[path] = (reads[path] ?: 0) + 1
                values[path]
            },
            directDirectories = { _, prefix ->
                if (prefix == "policy") {
                    reads["<dirs:policy>"] = (reads["<dirs:policy>"] ?: 0) + 1
                    listOf(File("/sys/devices/system/cpu/cpufreq/policy0"))
                } else {
                    emptyList()
                }
            },
        )
        return HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ ->
                    PServerTransactResult(accepted = pServerAccepts, output = null)
                },
            ),
        )
    }

    @Test
    fun `the cpu policy topology is discovered once across many probes`() {
        val probe = countingProbe()
        repeat(10) { probe.probe() }

        assertEquals(1, reads["<dirs:policy>"])
        assertEquals(
            1,
            reads["/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"],
        )
        assertEquals(1, reads["/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq"])
        assertEquals(1, reads["/sys/devices/system/cpu/cpufreq/policy0/affected_cpus"])
    }

    @Test
    fun `the gpu frequency table is discovered once across many probes`() {
        val probe = countingProbe()
        repeat(10) { probe.probe() }

        assertEquals(1, reads[HardwareProbe.GPU_FREQUENCIES])
    }

    @Test
    fun `the current clock is NOT cached - a field named current is never served frozen`() {
        val probe = countingProbe()
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"

        probe.probe()
        val afterDiscovery = reads[path] ?: 0
        repeat(9) { probe.probe() }

        // Nine further probes, nine further reads. Asserting the DELTA rather than a total keeps
        // this pinned on "live every call" instead of on how many times discovery happens to touch
        // the same path.
        assertEquals(afterDiscovery + 9, reads[path])
    }

    @Test
    fun `caching does not change what the probe reports`() {
        val probe = countingProbe()
        val first = probe.probe()
        val tenth = (1..9).map { probe.probe() }.last()

        assertEquals(first.cpuPolicies, tenth.cpuPolicies)
        assertEquals(first.gpuFrequenciesKhz, tenth.gpuFrequenciesKhz)
        assertEquals(first.deviceProfileId, tenth.deviceProfileId)
        assertEquals(first.socModel, tenth.socModel)
        assertEquals(first.refreshRatesHz, tenth.refreshRatesHz)
        assertEquals(first.supportedAxes, tenth.supportedAxes)
        assertEquals(first.status, tenth.status)
    }

    @Test
    fun `an explicit invalidation re-discovers the topology`() {
        val probe = countingProbe()
        repeat(3) { probe.probe() }
        assertEquals(1, reads["<dirs:policy>"])

        probe.invalidateStaticTopology()
        probe.probe()

        assertEquals(2, reads["<dirs:policy>"])
    }

    @Test
    fun `a pserver transition re-discovers the topology rather than serving what it found without it`() {
        // SystemReader falls back to PServerProtectedNodeAccess for protected cpufreq discovery, so a
        // topology found while pServer was down can be empty or partial. Serving that as settled
        // fact after pServer came up would withhold the CPU axis for the life of the process.
        val available = booleanArrayOf(false)
        val probe = HardwareProbe(
            context = RuntimeEnvironment.getApplication(),
            reader = SystemReader(
                privilegedNodes = null,
                directRead = { path ->
                    reads[path] = (reads[path] ?: 0) + 1
                    null
                },
                directDirectories = { _, prefix ->
                    if (prefix == "policy") {
                        reads["<dirs:policy>"] = (reads["<dirs:policy>"] ?: 0) + 1
                    }
                    emptyList()
                },
            ),
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ ->
                    PServerTransactResult(accepted = false, output = null)
                },
            ),
            pServerAvailable = { available[0] },
        )

        repeat(3) { probe.probe() }
        assertEquals(1, reads["<dirs:policy>"])

        available[0] = true
        val afterUp = probe.probe()

        assertTrue(afterUp.pServerAvailable)
        assertEquals(2, reads["<dirs:policy>"])

        // And it settles again on the new value rather than re-probing forever.
        repeat(3) { probe.probe() }
        assertEquals(2, reads["<dirs:policy>"])
    }
}
