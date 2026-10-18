package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdinCpuQosCeilingTest {
    @Test
    fun `the shared QoS transport is selected by the NODE, not by the chip family`() {
        val policies = capabilities().cpuPolicies
        val stock = (0..7).joinToString(" ") { "$it:${Int.MAX_VALUE}" }

        // 2026-08-25. This test used to assert the opposite for the third case below, under the
        // earlier transport restriction to the 8-Elite family. Generalizing from device id to family
        // was still too narrow, as measurements showed: a Retroid Pocket Nova (QCS8550,
        // NOT 8-Elite) publishes a complete, coherent map at this node, read live 2026-08-25T00:34Z
        // as `0:2147483647 ... 7:2147483647`. Being outside the family is the whole reason it was
        // forced onto per-policy scaling_max_freq -- the node its vendor perf server reasserts --
        // and that is the mechanism behind its TARGET_CONTRACT_DRIFT_CPU churn (21 restores in four
        // minutes, against zero on the Odin, on identical code).
        //
        // The pin is restated rather than deleted, because what it was protecting still needs
        // protecting: this transport must never be selected on a guess. Every fail-closed case
        // below is unchanged and still asserted.
        // ONE assertion, deliberately. The old test made two -- one per socModel -- and the socModel
        // argument no longer exists, so a second call here would be the identical expression dressed
        // up as a second case: a test that cannot fail independently of the first. What used to be
        // "which chip families are allowed" is now unexpressible through this API, which IS the
        // change. Family scoping is pinned where it still lives instead: the CPU-axis guard in
        // HardwareProbe (isSnapdragon8EliteFamily), which decides whether a family MEMBER may fall
        // back to per-policy when the node is missing.
        assertEquals(
            "any chassis publishing a complete, coherent map composes instead of racing its vendor",
            HardwareProbe.CPU_QOS_MAX_PATH,
            CpuCeilingTransport.selectedPath(policies, stock),
        )

        // FAIL-CLOSED, all unchanged. Absent node.
        assertNull("no node at all keeps per-policy scaling_max_freq", CpuCeilingTransport.selectedPath(policies, null))
        assertNull("an empty read is not a map", CpuCeilingTransport.selectedPath(policies, "   "))
        // A map that does not cover every cpu in the live policy topology.
        assertNull(CpuCeilingTransport.selectedPath(policies, stock.substringBeforeLast(' ')))
        // A duplicate entry -- parse() rejects the whole map rather than picking a winner.
        assertNull(CpuCeilingTransport.selectedPath(policies, "0:1 0:1 1:1 2:1 3:1 4:1 5:1 6:1 7:1"))
        // No policies to resolve against.
        assertNull(CpuCeilingTransport.selectedPath(emptyList(), stock))
        assertNull(CpuCeilingTransport.parse("0:-1"))
        assertNull(CpuCeilingTransport.parse("0:2147483648"))
    }

    /**
     * A policy whose cpus disagree in the map cannot resolve to one ceiling, so the whole transport
     * is refused rather than picking one of the two values.
     *
     * Split out because the case above it only ever exercised whole-map faults; with the family
     * conjunct gone, per-policy coherence is doing more of the fail-closed work than it used to.
     */
    @Test
    fun `a policy whose cpus carry different ceilings refuses the transport`() {
        val policies = capabilities().cpuPolicies
        val split = policies.first().cpuIds.let { ids ->
            require(ids.size > 1) { "this test needs a multi-cpu policy to split" }
            (0..7).joinToString(" ") { id ->
                if (id == ids.last()) "$id:1000000" else "$id:${Int.MAX_VALUE}"
            }
        }
        assertNull(CpuCeilingTransport.selectedPath(policies, split))
    }

    @Test
    fun `shared QoS topology remains policy-addressable and digest-bound`() {
        val caps = capabilities()
        val plan = plan()
        val expected = SnapshotIntegrity.expectedTopology(
            plan,
            caps,
            resolver = SysfsCanonicalResolver { it },
        )!!
        assertEquals(2, expected.size)
        assertTrue(expected.all { it.canonicalPath == HardwareProbe.CPU_QOS_MAX_PATH })

        val snapshot = snapshot(caps)
        assertNull(
            SnapshotIntegrity.validateDecodedSnapshot(
                snapshot = snapshot,
                expectedTopologyDigest = SnapshotIntegrity.topologySha256(expected),
                expectedSnapshotDigest = SnapshotIntegrity.snapshotSha256(snapshot),
                resolver = SysfsCanonicalResolver { it },
            ),
        )
    }

    @Test
    fun `Odin renderer writes every CPU in its policy through the shared QoS node`() {
        val commands = mutableListOf<String>()
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = confirmingClient(commands),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val mutations = port.plannedMutations(plan(), snapshot(caps))

        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq"),
            mutations.map { it.logicalId },
        )
        assertTrue(mutations.all { it.canonicalPath == HardwareProbe.CPU_QOS_MAX_PATH })
        assertTrue(mutations.all { it.restoreMode == CpuCeilingTransport.ACTIVE_SHARED_QOS_MODE })

        assertEquals(CheckedCommandStatus.CONFIRMED, port.execute(mutations.first()).status)
        assertEquals(9, commands.size)
        assertTrue(commands[0].contains("chmod 666 ${HardwareProbe.CPU_QOS_MAX_PATH}"))
        (0..5).forEach { cpuId ->
            assertTrue(commands[cpuId + 1].contains("echo $cpuId:1996800 > ${HardwareProbe.CPU_QOS_MAX_PATH}"))
        }
        assertTrue(commands[7].contains("chown 0:0 ${HardwareProbe.CPU_QOS_MAX_PATH}"))
        assertTrue(commands[8].contains("chmod 444 ${HardwareProbe.CPU_QOS_MAX_PATH}"))
    }

    @Test
    fun `protected reader permits only the fixed QoS path outside cpufreq`() {
        val commands = mutableListOf<String>()
        val access = PServerProtectedNodeAccess(
            PServerClient(
                PServerBinderTransport { command, _ ->
                    commands += command
                    PServerTransactResult(true, "0:1 1:1")
                },
            ),
        )

        assertEquals("0:1 1:1", access.read(HardwareProbe.CPU_QOS_MAX_PATH))
        assertNull(access.read("/sys/kernel/msm_performance/parameters/cpu_min_freq"))
        assertEquals(listOf("cat ${HardwareProbe.CPU_QOS_MAX_PATH}"), commands)
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "AYN",
        model = "Odin3",
        socModel = "SM8750",
        buildFingerprint = "odin-build",
        deviceProfileId = CpuCeilingTransport.ODIN_3_PROFILE,
        supportedAxes = setOf(TuningAxis.CPU),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(
                id = 0,
                path = "${HardwareProbe.CPU_ROOT}/policy0",
                cpuIds = (0..5).toList(),
                availableFrequenciesKhz = listOf(768_000, 1_996_800, 3_532_800),
                minKhz = 768_000,
                maxKhz = 3_532_800,
                currentKhz = 768_000,
            ),
            CpuPolicy(
                id = 6,
                path = "${HardwareProbe.CPU_ROOT}/policy6",
                cpuIds = listOf(6, 7),
                availableFrequenciesKhz = listOf(768_000, 2_246_400, 4_320_000),
                minKhz = 768_000,
                maxKhz = 4_320_000,
                currentKhz = 768_000,
            ),
        ),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
        cpuCeilingPath = HardwareProbe.CPU_QOS_MAX_PATH,
    )

    private fun plan() = TuningPlan(
        profileId = "quiet",
        packageName = "example.game",
        cpuMaxKhz = mapOf(0 to 1_996_800, 6 to 2_246_400),
        gpuMaxPowerLevel = null,
        fanMode = null,
        calibrated = true,
    )

    private fun snapshot(capabilities: HardwareCapabilities) = HardwareSnapshot(
        schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
        hardwareFingerprint = HardwareFingerprint.create(capabilities),
        bootIdentity = "boot",
        nodes = capabilities.cpuPolicies.map { policy ->
            TopologyNodeState(
                logicalId = "cpu:${policy.id}:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
                originalValue = Int.MAX_VALUE,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            )
        },
    )

    private fun confirmingClient(commands: MutableList<String>) = PServerClient(
        PServerBinderTransport { command, _ ->
            commands += command
            val nonce = Regex("""MIMIR_TX_EXIT:([A-Za-z0-9_-]+):%s""")
                .find(command)!!
                .groupValues[1]
            PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:")
        },
    )
}
