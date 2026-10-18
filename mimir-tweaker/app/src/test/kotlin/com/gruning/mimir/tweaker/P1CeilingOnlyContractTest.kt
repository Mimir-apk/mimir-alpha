package com.gruning.mimir.tweaker

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Host-fidelity regression matrix for the P1 cpufreq correction.
 *
 * A live `scaling_min_freq` is a QoS observation, never a capability or a
 * transaction-owned node. These fakes intentionally model that distinction instead
 * of giving min/max independent, timeless behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class P1CeilingOnlyContractTest {
    /**
     * The clamp is only safe if the CLAMPED plan is the one journalled. The write, the readback,
     * the applied contract and the supervisor's drift check all compare CPU targets; if the plan of
     * record still held the unclamped value, drift would fire against the device immediately. That
     * is not hypothetical -- an earlier attempt at this clamped in derivation against a cached floor
     * and produced a restore/re-apply loop that made the Nova unusable.
     *
     * A wiring pin, and honest about being one: the property is "adopted before anything is
     * journalled", which is a statement about order in the apply path, not about a value a pure
     * function returns.
     */
    @Test
    fun `the coordinator adopts the clamped targets before journalling`() {
        val src = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/TransactionCoordinator.kt"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/TransactionCoordinator.kt"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("TransactionCoordinator.kt not found")
        val code = src.readText().lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "the clamped targets must be adopted into the plan",
            code.contains("plan = plan.copy(cpuMaxKhz = capture.admittedCpuMaxKhz)"),
        )
        val adopt = code.indexOf("plan = plan.copy(cpuMaxKhz = capture.admittedCpuMaxKhz)")
        val journal = code.indexOf("PersistedTuningPlan.from(plan)")
        assertTrue("adoption must be present", adopt >= 0)
        assertTrue("the journal write must be present", journal >= 0)
        assertTrue(
            "adoption must happen BEFORE the plan is journalled -- otherwise the contract and the " +
                "device disagree from the first drift check",
            adopt < journal,
        )
    }

    @Test
    fun `below settled external CPU floor fails before generation journal or command`() {
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(
                failures = listOf("cpu policy 0 target 900000 is below settled live floor 1200000"),
                code = HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR,
            ),
        )
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply("quiet", PACKAGE, capabilities(), dryRun = false)

        assertEquals(HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR, result.code)
        assertEquals(0, journal.generationRequests)
        assertEquals(0, journal.persists)
        assertEquals(0, rig.executed.size)
    }

    @Test
    fun `settled QoS floor does not enter immutable capability snapshot or new mutations`() {
        // The stable minimum OPP is 300 MHz. The temporary launch/QoS floor is 600 MHz;
        // quiet's 900 MHz ceiling is admissible and must not own that floor.
        val snapshot = maxOnlySnapshot()
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = snapshot))
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply("quiet", PACKAGE, capabilities(), dryRun = false)

        assertEquals("APPLIED", result.code)
        val record = (journal.load() as JournalLoadResult.Loaded).record
        assertTrue(record.snapshot.cpuMinKhz.isEmpty())
        assertTrue(record.snapshot.nodes.none { it.logicalId.endsWith(":scaling_min_freq") })
        assertTrue(rig.executed.none { it.logicalId.endsWith(":scaling_min_freq") })
        assertTrue(record.applyOrderLogicalIds.none { it.endsWith(":scaling_min_freq") })
    }

    @Test
    fun `new apply rejects a floor-bearing capture before generation persist or command`() {
        val floor = TopologyNodeState(
            logicalId = "cpu:0:scaling_min_freq",
            axis = TuningAxis.CPU,
            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            originalValue = 300_000,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot().copy(nodes = listOf(floor) + maxOnlySnapshot().nodes)))
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply("quiet", PACKAGE, capabilities(), dryRun = false)

        assertEquals("CPU_FLOOR_OWNERSHIP_FORBIDDEN", result.code)
        assertEquals(0, journal.generationRequests)
        assertEquals(0, journal.persists)
        assertTrue(rig.executed.isEmpty())
    }

    @Test
    fun `new plan is fan first and CPU ceiling only even when QoS floor is present`() {
        val snapshot = maxOnlySnapshot()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(
                transport = PServerBinderTransport { command, _ ->
                    PServerTransactResult(
                        accepted = true,
                        output = "${PServerClient.EXIT_MARKER_PREFIX}${nonceOf(command)}:0:0:",
                    )
                },
            ),
            reader = SystemReader(),
            capabilities = ::capabilities,
        )
        val plan = planner().plan("quiet", PACKAGE, capabilities())!!

        val mutations = port.plannedMutations(plan, snapshot)

        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, mutations.first().logicalId)
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID, "cpu:0:scaling_max_freq"), mutations.map { it.logicalId })
        assertTrue(mutations.none { it.logicalId.endsWith(":scaling_min_freq") })
        assertEquals(900_000, mutations.single { it.axis == TuningAxis.CPU }.value)
    }

    @Test
    fun `PServer apply renderer refuses a floor-bearing snapshot`() {
        val floor = TopologyNodeState(
            logicalId = "cpu:0:scaling_min_freq",
            axis = TuningAxis.CPU,
            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            originalValue = 300_000,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(PServerBinderTransport { _, _ -> PServerTransactResult(false) }),
            reader = SystemReader(),
            capabilities = ::capabilities,
        )

        val failure = runCatching {
            port.plannedMutations(planner().plan("quiet", PACKAGE, capabilities())!!, maxOnlySnapshot().copy(nodes = listOf(floor) + maxOnlySnapshot().nodes))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("must not own CPU scaling_min_freq"))
    }

    @Test
    fun `rebound after initially matching apply cannot early pass and is restored`() {
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            // AndroidHardwareStatePort owns the eleven-sample/full-window observation.
            // By the coordinator boundary, this is the truthful settled verdict.
            // A vendor fan write can reset CPU max after its own immediate readback.
            planVerifications = ArrayDeque(
                listOf(
                    PlanVerification(false, listOf("cpu policy 0 delayed vendor rebound")),
                ),
            ),
        )
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply("quiet", PACKAGE, capabilities(), dryRun = false)

        assertEquals("VERIFY_FAILED", result.code)
        assertTrue(result.restored)
        assertEquals(1, rig.verifyPlanCalls)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `Android verification rejects a delayed ceiling rebound instead of early passing`() {
        val result = verifyFullWindow(
            List(7) { PlanVerification(true) } +
                PlanVerification(false, listOf("cpu policy 0 expected 900000 got 1800000")),
        )

        assertFalse(result.ok)
        assertTrue(result.failures.any { "expected 900000" in it })
    }

    @Test
    fun `Android verification consumes all eleven samples ten sleeps and requires final three passes`() {
        val sleeps = mutableListOf<Long>()
        val result = verifyFullWindow(
            List(8) { PlanVerification(false, listOf("not yet")) } + List(3) { PlanVerification(true) },
            sleeps,
        )

        assertTrue(result.ok)
        assertEquals(10, sleeps.size)
        assertTrue(sleeps.all { it == 75L })
    }

    @Test
    fun `Android verification rejects early passes followed by failure after all eleven samples`() {
        val sleeps = mutableListOf<Long>()
        val result = verifyFullWindow(
            List(3) { PlanVerification(true) } + List(8) { PlanVerification(false, listOf("late rebound")) },
            sleeps,
        )

        assertFalse(result.ok)
        assertTrue(result.failures.any { "late rebound" in it })
        assertEquals(10, sleeps.size)
    }

    /**
     * **This used to assert the opposite, and the product moved first.** Until 2026-09-21 it pinned
     * that a `gpu:min_pwrlevel` that held for seven samples and then rebounded was REJECTED. That
     * stopped being the contract in 23d4cc90d ("An unholdable axis cannot block an apply either"):
     * the Thor's vendor governor takes the GPU minimum back within about three seconds of the write
     * (`gpu=0` at the write, `gpu=4` at +2.3 s), so enforcing it refused roughly one apply in three
     * outright and Quiet could not be selected. [vetoesWindowedVerification] now leaves
     * [UNHOLDABLE_AXIS_LOGICAL_IDS] out of the windowed verdict and the window reports the axis as
     * `unheld=` instead -- exactly this rebound, which is why the old assertion could no longer hold
     * on ANY host (Windows failed it too, at the same line; it was never a Linux-only difference).
     * The rule that still has to hold -- a delayed rebound on an axis that CAN be guaranteed is
     * rejected -- is pinned by the next test, on the CPU floor.
     */
    @Test
    fun `Android applied-plan verification reports a delayed GPU minimum rebound as unheld not rejected`() {
        val rig = AppliedPlanWindowRig(
            nodePath = HardwareProbe.GPU_MIN_LEVEL,
            heldValue = "2",
            reboundValue = "1",
        )

        val result = rig.state.verifyAppliedPlan(
            rig.plan,
            listOf(
                rig.mutation(HardwareSnapshot.GPU_MIN_LOGICAL_ID, TuningAxis.GPU, HardwareProbe.GPU_MIN_LEVEL, 2),
                rig.mutation(HardwareSnapshot.GPU_MAX_LOGICAL_ID, TuningAxis.GPU, HardwareProbe.GPU_MAX_LEVEL, 0),
            ),
        )

        assertTrue(result.failures.toString(), result.ok)
        assertTrue(result.failures.isEmpty())
        // The window still ran in full: suppressing the veto must not shorten the observation.
        assertEquals(11, rig.nodeReads)
        assertEquals(10, rig.sleeps.size)
    }

    @Test
    fun `Android applied-plan verification rejects delayed CPU minimum rebound`() {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
        val rig = AppliedPlanWindowRig(
            nodePath = path,
            heldValue = "1000000",
            reboundValue = "900000",
        )

        val result = rig.state.verifyAppliedPlan(
            rig.plan,
            listOf(
                rig.mutation("cpu:0:scaling_min_freq", TuningAxis.CPU, path, 1_000_000),
                rig.mutation(HardwareSnapshot.GPU_MAX_LOGICAL_ID, TuningAxis.GPU, HardwareProbe.GPU_MAX_LEVEL, 0),
            ),
        )

        assertFalse(result.ok)
        assertTrue(result.failures.any { "cpu:0:scaling_min_freq" in it })
        assertEquals(11, rig.nodeReads)
        assertEquals(10, rig.sleeps.size)
    }

    /**
     * One node holds [heldValue] for seven reads and then rebounds to [reboundValue] for the last
     * four of the eleven-sample window. Nothing here reads a clock or the host filesystem: the
     * sleeper is recorded, the node is a counter, and [sysfsResolver] is the identity so
     * `SysfsPath.canonicalize` does not depend on how the host spells `/sys` (a Windows
     * `File.canonicalPath` is `C:\sys\...`, which it refuses).
     */
    private inner class AppliedPlanWindowRig(
        nodePath: String,
        heldValue: String,
        reboundValue: String,
    ) {
        var nodeReads = 0
            private set
        val sleeps = mutableListOf<Long>()
        val plan = TuningPlan(
            profileId = "performance",
            packageName = PACKAGE,
            cpuMaxKhz = emptyMap(),
            gpuMaxPowerLevel = 0,
            fanMode = null,
            calibrated = true,
        )
        val state: AndroidHardwareStatePort

        init {
            val gpuCapabilities = capabilities().copy(
                supportedAxes = setOf(TuningAxis.GPU),
                cpuPolicies = emptyList(),
                gpuFrequenciesKhz = listOf(305_000, 525_000, 905_000),
                gpuNodeAvailable = true,
                fanNodeAvailable = false,
            )
            val reader = SystemReader(
                directRead = { path ->
                    when (path) {
                        nodePath -> {
                            nodeReads++
                            if (nodeReads <= 7) heldValue else reboundValue
                        }
                        HardwareProbe.GPU_MAX_LEVEL -> "0"
                        else -> null
                    }
                },
                directDirectories = { _, _ -> emptyList() },
            )
            val probe = HardwareProbe(
                context = NoPowerManagerContext(RuntimeEnvironment.getApplication()),
                reader = reader,
                pServer = PServerClient(
                    transport = PServerBinderTransport { _, _ -> PServerTransactResult(false) },
                ),
                sleeper = {},
            )
            state = AndroidHardwareStatePort(
                probe = probe,
                capabilities = { gpuCapabilities },
                posix = PosixNodeMetadataReader { PosixNodeMetadata("644", 0, 0) },
                sleeper = { sleeps += it },
                sysfsResolver = SysfsCanonicalResolver { it },
            )
        }

        fun mutation(logicalId: String, axis: TuningAxis, path: String, value: Int) = LogicalMutation(
            logicalId = logicalId,
            axis = axis,
            canonicalPath = path,
            kind = LogicalMutationKind.SYSFS_WRITE,
            value = value,
            restoreMode = "644",
            restoreUid = 0,
            restoreGid = 0,
            expectPresent = true,
        )
    }

    /**
     * **Rewritten for window-based admission, 2026-09-01. The duties it used to pin are stated before they
     * change, because two of them are now deliberately the opposite.**
     *
     * It pinned that admission read the FINAL SETTLED sample: a launch boost that decayed before the
     * end of the dwell was ignored, and a window still moving at the end refused
     * `CPU_WINDOW_UNSETTLED`. Both were the right shape for a device whose floor holds still.
     *
     * The Thor is not that device. Its vendor perf HAL drives `scaling_min_freq` continuously
     * — policy0 measured oscillating 1459200 ↔ 1555200 — so "did not settle" was true forever and NO
     * profile could ever apply. Worse, when a clamp did happen it was computed from whichever phase
     * the last sample caught, so the journalled value was stale by readback and the apply died
     * `VERIFY_FAILED`.
     *
     * Admission is now against the window the dwell actually observed: floor = the highest seen,
     * ceiling = the lowest seen. Highest, because **a ceiling below the live floor is not a writable
     * state** — the kernel raises it to meet the floor and the readback stops matching the contract.
     */
    @Test
    fun `a boost inside the observed window no longer decides anything`() {
        fun sample(min: Int, max: Int = 1_800_000) = mapOf(0 to CpuLiveWindowSample(min, max))

        // This test has been inverted twice, and the history is the point.
        //
        // First it asserted that the 1.2M sample was a decayed launch boost to be IGNORED. Then the
        // window-based admission included it, so it RAISED the admitted ceiling. The floor clamp was
        // retired outright on 2026-09-21, and one sample in eleven cannot change the plan at all.
        //
        // What makes the middle version wrong is that the boost is not rare. Measured on the Thor the
        // same evening, 45 one-second samples with the device IDLE: ten of them at the cluster maxima.
        // A rule that admits against the highest floor in the window is a rule that admits against the
        // burst almost every time, which made the journalled contract a coin toss -- runs 15 and 16,
        // same APK and same plan, produced {0=2016000, 3=2707200} and {0=1113600, 3=1651200}.
        val boostInWindow = CpuWindowAdmissionPolicy.afterDwell(
            listOf(sample(1_200_000)) + List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) { sample(300_000) },
            mapOf(0 to 900_000),
        )
        assertTrue(boostInWindow.failures.isEmpty())
        assertNull("nothing rewrote the plan, so there is no code to report", boostInWindow.code)
        assertTrue(
            "and nothing to journal: the plan goes out as the profile asked for it",
            boostInWindow.admittedTargetsKhz.isEmpty(),
        )
    }

    /**
     * The retired requirement, pinned in its new position: a window still moving at the end of the
     * dwell is ordinary on a live chassis and must no longer refuse. This is the exact shape that
     * made the Thor unable to apply any profile at all.
     */
    @Test
    fun `a window still moving at the end of the dwell no longer refuses`() {
        fun sample(min: Int, max: Int = 1_800_000) = mapOf(0 to CpuLiveWindowSample(min, max))

        val movingAtTheEnd = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) { sample(300_000) } + sample(600_000),
            mapOf(0 to 900_000),
        )

        assertTrue(
            "a moving floor is not a broken instrument; it is a live device",
            movingAtTheEnd.failures.isEmpty(),
        )
        // 900k clears the highest floor seen (600k), so nothing is clamped.
        assertTrue(movingAtTheEnd.admittedTargetsKhz.isEmpty())
    }

    /** Structural faults are NOT relaxed: a broken observation still refuses, and clamps nothing. */
    @Test
    fun `structural faults still refuse after the window rule changed`() {
        fun sample(min: Int?, max: Int?) = mapOf(0 to CpuLiveWindowSample(min, max))

        val wrongCount = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT + 1) { sample(300_000, 1_800_000) },
            mapOf(0 to 900_000),
        )
        assertEquals(HardwareProbe.CPU_WINDOW_UNSETTLED, wrongCount.code)

        val missingFloor = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) { sample(300_000, 1_800_000) } + sample(null, 1_800_000),
            mapOf(0 to 900_000),
        )
        assertEquals(HardwareProbe.CPU_WINDOW_UNSETTLED, missingFloor.code)
        assertTrue(missingFloor.admittedTargetsKhz.isEmpty())

        // Floor and ceiling that never held one consistent band: every sample is individually valid,
        // but the highest floor seen is above the lowest ceiling seen, so there is no window a target
        // could be admitted against.
        val crossedBand = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) { mapOf(0 to CpuLiveWindowSample(300_000, 900_000)) } +
                listOf(mapOf(0 to CpuLiveWindowSample(1_500_000, 1_800_000))),
            mapOf(0 to 800_000),
        )
        assertEquals(HardwareProbe.CPU_WINDOW_UNSETTLED, crossedBand.code)
        assertTrue(crossedBand.admittedTargetsKhz.isEmpty())
    }

    /**
     * Below-floor targets remain clamped rather than refused at this profile-switch boundary.
     * Refusal would leave the device at Stock instead of applying the lower performance ceiling.
     */
    @Test
    fun `a target below the observed floor goes out as asked`() {
        fun sample(min: Int, max: Int = 1_800_000) = mapOf(0 to CpuLiveWindowSample(min, max))

        val belowFloor = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 2) { sample(300_000) } + List(2) { sample(1_200_000) },
            mapOf(0 to 900_000),
        )

        assertTrue("a below-floor target must not fail the apply", belowFloor.failures.isEmpty())
        assertNull(
            "a below-floor target is no longer raised to the floor",
            belowFloor.code,
        )
        assertTrue(
            "the plan reaches the transport as the profile wrote it",
            belowFloor.admittedTargetsKhz.isEmpty(),
        )
    }

    /**
     * The capture boundary used to demand the live window read EXACTLY as the dwell had, then merely
     * INSIDE what the dwell observed. **The floor half stopped refusing on 2026-09-21**, and the two halves are no longer symmetric.
     *
     * The check was asymmetric by construction: the dwell gets eleven reads and an aggregate, the
     * boundary gets ONE, and that one had to land inside the eleven. A fresh draw from the same
     * distribution can always be the new maximum, so it was a strictly harder test than the dwell
     * itself passes. On the Thor the floor is a square wave -- 222 reads over forty seconds,
     * policy3 oscillating 1536000 <-> 2707200 in bursts as short as 0.1 s with resting gaps up to
     * 6.1 s, against a dwell spanning 2.0 s -- so a dwell landing inside a gap saw only the resting
     * floor and any burst at the boundary refused the whole apply. It refused Quiet once in three
     * presses on the 2026-09-21 device run.
     *
     * What makes it safe to drop is not the rate. After the 2026-09-21 admission changes **nothing is
     * derived from the floor**: `targetAdmission` reads it to check the instrument, and the plan comes
     * from the silicon's operating-point table. A floor that moved invalidates no number this produces.
     *
     * The ceiling half stays, because the observed ceiling still has a live consumer -- it is journalled
     * as the readback expectation on the per-policy `scaling_max_freq` transport. A ceiling genuinely
     * below what the dwell saw would journal an expectation the device cannot meet and the apply would
     * die `VERIFY_FAILED` later, with a worse message than this one. The same trace measured the
     * ceilings steady at 30 of 30 samples, so that half is not the coin toss the floor half was.
     */
    @Test
    fun `the capture boundary admits a floor above the window and still refuses a dropped ceiling`() {
        fun sample(min: Int, max: Int = 1_800_000) = mapOf(0 to CpuLiveWindowSample(min, max))
        val targets = mapOf(0 to 900_000)
        // Observed THROUGH THE SNAP since the floor clamp was retired (2026-09-21). This test is about
        // the boundary's in-band/out-of-band judgement, and it needs some visible rewrite to watch; the
        // clamp used to be that, and the off-table snap is now. 900000 is off this table, so it snaps
        // down to 870400 in every admission below -- same input, same output, every time.
        val table = mapOf(0 to listOf(600_000, 870_400, 1_200_000, 1_800_000))

        val dwell = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 1) { sample(1_200_000) } + List(1) { sample(1_000_000) },
            targets,
            table,
        )
        // Observed window: floor 1.2M (highest seen), ceiling 1.8M (lowest seen).
        assertEquals(mapOf(0 to 870_400), dwell.admittedTargetsKhz)

        // A floor anywhere inside the band admits, and admits the SAME value the dwell decided --
        // that stability is the whole point, because this number is what gets journalled and later
        // verified against.
        val inBand = CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = dwell.values,
            boundary = sample(1_000_000),
            targetsKhz = targets,
            operatingPointsKhz = table,
        )
        assertTrue(inBand.failures.isEmpty())
        assertEquals(mapOf(0 to 870_400), inBand.admittedTargetsKhz)

        // A floor ABOVE everything the dwell saw -- the vendor's min-freq burst landing on the one
        // read the boundary gets -- admits, and admits the SAME value. This is the case that refused
        // Quiet on the walk, and the stability of this number is why it may: it does not depend on the
        // floor at all.
        val aboveBand = CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = dwell.values,
            boundary = sample(1_500_000),
            targetsKhz = targets,
            operatingPointsKhz = table,
        )
        assertTrue(
            "a floor that moved invalidates nothing this function produces, so it may not refuse",
            aboveBand.failures.isEmpty(),
        )
        assertEquals(mapOf(0 to 870_400), aboveBand.admittedTargetsKhz)

        // A ceiling BELOW everything the dwell saw still refuses -- that one is journalled.
        val ceilingDropped = CpuWindowAdmissionPolicy.atCaptureBoundary(
            settled = dwell.values,
            boundary = mapOf(0 to CpuLiveWindowSample(1_200_000, 1_500_000)),
            targetsKhz = targets,
            operatingPointsKhz = table,
        )
        assertEquals(HardwareProbe.CPU_WINDOW_UNSETTLED, ceilingDropped.code)
        assertTrue(
            "an untrustworthy observation must rewrite nothing, not even onto the table",
            ceilingDropped.admittedTargetsKhz.isEmpty(),
        )
        // **Both sides of the inequality, named.** From outside the device neither number can be
        // obtained -- `scaling_min_freq` is SELinux-denied to shell AND to the app via `run-as` on the
        // Thor -- so a refusal that does not carry them cannot be read at all. That is the
        // trap `logCpuWindowRefusal` records twice in its own KDoc.
        val detail = ceilingDropped.failures.single()
        listOf("cpu policy 0", "observed 1200000..1800000", "live 1200000..1500000").forEach {
            assertTrue("refusal must name $it -- was: $detail", detail.contains(it))
        }
    }

    /**
     * **The other direction used to refuse, and no longer does** —
     *
     * The refusal was self-locking. The gate read the live ceiling to decide what was admissible, so a
     * clamp Mimir itself had written became the evidence forbidding its own removal — the Thor,
     * 2026-09-19, refused `requested=balanced prior=stock` against `live window 1017600..1017600`, a
     * window Mimir had put there, and only a reboot escaped it. The operating-point table cannot fail
     * that way: it is a property of the silicon, not of Mimir's last write.
     *
     * The three cases that define the new gate, and the middle one defines the admission change: a target the
     * table supports is admitted even though it sits ABOVE the live ceiling.
     */
    @Test
    fun `a target above the observed ceiling is admitted against the operating-point table`() {
        val table = listOf(300_000, 1_200_000, 1_804_800, 2_400_000)
        fun dwell(targetKhz: Int, operatingPoints: List<Int>?) = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT) { mapOf(0 to CpuLiveWindowSample(300_000, 1_800_000)) },
            mapOf(0 to targetKhz),
            operatingPoints?.let { mapOf(0 to it) } ?: emptyMap(),
        )

        // On the table: admitted untouched, far above the live ceiling of 1800000.
        val onTable = dwell(2_400_000, table)
        assertTrue("a published operating point must never refuse", onTable.failures.isEmpty())
        assertNull(onTable.code)
        assertTrue(onTable.admittedTargetsKhz.isEmpty())

        // Off the table: snapped DOWN to the nearest point below -- and 1804800 is still above the
        // live ceiling, which is what proves the ceiling is no longer the authority.
        val offTable = dwell(2_000_000, table)
        assertTrue(offTable.failures.isEmpty())
        assertEquals(HardwareProbe.CPU_TARGET_SNAPPED_TO_OPERATING_POINT, offTable.code)
        assertEquals(mapOf(0 to 1_804_800), offTable.admittedTargetsKhz)

        // No table published: nothing to snap onto, so the target passes through as asked rather than
        // being refused against a number this policy never advertised.
        val noTable = dwell(2_000_000, null)
        assertTrue(noTable.failures.isEmpty())
        assertNull(noTable.code)
    }

    /**
     * **The drift check reads a ceiling too, and drew the same wrong conclusion** — policy
     * 2026-09-21, extended to the supervisor the evening it was made for admission.
     *
     * Run 14, the Thor, 01:57: Balanced applied, was confirmed `Active(balanced)` and honoured for five
     * seconds, then `SUPERVISOR restore reason=TARGET_CONTRACT_DRIFT_CPU` with
     * `expected={0=2016000, 3=2707200, 7=1843200}` against `observed={0=2016000, 3=2803200, 7=3187200}`
     * — five times in ninety seconds. The observed numbers are the BOOST maxima, which is what the node
     * reports when nothing is pinned, and no contract can contain them because they are not writable.
     * Policy 0 agreed only by luck: the little cluster has no boost bin, so its unpinned read already
     * equalled its contract.
     *
     * Folding the observed read onto the writable table makes policy 3 stop lying. It does NOT make
     * policy 7 stop differing, and that is the point of the third assertion here: a ceiling genuinely
     * held below its contract is still drift, and this device still has one.
     *
     * **The fold is one-sided** — policy 2026-09-21, narrowing the first cut the same evening. The
     * first cut normalised the contract too, which has nothing above the top to fold today and exactly
     * one thing tomorrow: an unpinned request. The last assertion pins that, because the day Stock stops
     * replaying a remembered number is the day a two-sided fold would call "nothing should be held"
     * and "the cluster is held at 2707200" the same state.
     *
     * This test does not establish a fix for the Thor: run 14's reverts were vendor-driven
     * through the fallback transport the Tweaker was using.
     */
    @Test
    fun `a ceiling and its boost-inclusive read name one state, a held-down ceiling still differs`() {
        val writable = mapOf(0 to 2_016_000, 3 to 2_707_200, 7 to 2_956_800)
        val contract = mapOf(0 to 2_016_000, 3 to 2_707_200, 7 to 1_843_200)
        val observed = mapOf(0 to 2_016_000, 3 to 2_803_200, 7 to 3_187_200)

        val normalized = normalizeObservedCeilings(observed, writable)!!

        assertEquals(
            "policy 3's contract is its writable top; the boost read names the same state",
            contract[3],
            normalized[3],
        )
        assertEquals("policy 0 was never the problem", contract[0], normalized[0])
        assertTrue(
            "policy 7 was written 1843200 and read back unpinned -- that is real drift and must survive " +
                "the fold",
            contract[7] != normalized[7],
        )

        // A policy with no readable table is compared verbatim -- an unreadable table must never become
        // a silent fold that hides a real divergence.
        assertEquals(observed, normalizeObservedCeilings(observed, emptyMap()))

        // An UNPINNED request is left alone, because only the observed side is folded. A two-sided fold
        // would clamp Int.MAX_VALUE to 2707200 and call a cluster held at its top a match for a session
        // that asked for nothing to be held at all.
        val unpinnedContract = mapOf(3 to Int.MAX_VALUE)
        val heldAtTop = normalizeObservedCeilings(mapOf(3 to 2_707_200), writable)!!
        assertTrue(
            "an unpinned contract against a cluster held at its writable top is a divergence, not a match",
            unpinnedContract != heldAtTop,
        )
    }

    /**
     * **The guard that keeps the new gate from being a disaster.** `detectCpuPolicies` synthesizes a
     * two-entry stand-in from `cpuinfo_min_freq`/`cpuinfo_max_freq` when `scaling_available_frequencies`
     * cannot be read — and it cannot always be read: on the Thor, `shell` gets permission denied
     * on policy0's scaling files outright, per file and per policy.
     *
     * Snapping DOWN against a table whose only entries are the policy's own floor and ceiling rounds
     * every target between them to the FLOOR. Every profile would pin the cluster at its slowest
     * frequency — worse than the refusal the admission change retired, and silent. A stand-in must therefore be
     * treated as no table at all.
     */
    @Test
    fun `a synthesized two-entry table is not mistaken for an operating-point table`() {
        val real = CpuPolicy(
            id = 0,
            path = "/sys/devices/system/cpu/cpufreq/policy0",
            cpuIds = listOf(0),
            availableFrequenciesKhz = listOf(300_000, 1_200_000, 1_804_800),
            minKhz = 300_000,
            maxKhz = 1_804_800,
            currentKhz = 300_000,
        )
        val standIn = real.copy(availableFrequenciesKhz = listOf(300_000, 1_804_800))

        assertEquals(
            real.availableFrequenciesKhz,
            CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(real),
        )
        assertTrue(
            "cpuinfo min/max is not an operating-point table",
            CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(standIn).isEmpty(),
        )

        // The consequence, stated as behaviour: a mid-range target survives instead of collapsing.
        val admission = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT) { mapOf(0 to CpuLiveWindowSample(300_000, 1_800_000)) },
            mapOf(0 to 1_500_000),
            mapOf(0 to CpuWindowAdmissionPolicy.publishedOperatingPointsKhz(standIn)),
        )
        assertNull(admission.code)
        assertTrue(
            "an unreadable table must leave the target alone, never snap it to the floor",
            admission.admittedTargetsKhz.isEmpty(),
        )
    }

    /**
     * 2026-08-22 (late): `AndroidHardware.logCpuWindowRefusal` logs this admission's `failures`
     * list, always-on, so the Nova's `CPU_TARGET_BELOW_LIVE_FLOOR` refusals stop being undiagnosable
     * from outside the device (`shell` cannot read `scaling_min_freq`, SELinux). That log is only as
     * useful as the message it prints -- this pins that `targetAdmission`'s failure text actually
     * names BOTH numbers that decided the refusal (the requested target and the settled live
     * floor/ceiling), for both directions this admission can refuse. Not a source-revert
     * differential: the message text predates this ticket (the ticket adds the *logging*, not the
     * message), so this is a forward-regression pin protecting the new log's usefulness -- a future
     * edit that trims the message back to just a code would leave `logCpuWindowRefusal` printing a
     * code with no diagnosable numbers again, silently.
     */
    @Test
    fun `a CPU window refusal message names both numbers that decided it, not only the code`() {
        fun sample(min: Int, max: Int = 1_800_000) = mapOf(0 to CpuLiveWindowSample(min, max))

        // Below-floor was a refusal, then a clamp, and since 2026-09-21 it is neither: the floor
        // does not decide a target at all. There is nothing left to diagnose on this path, so what
        // this half now pins is the SILENCE -- no code, no admitted entry, nothing written to the
        // journal that the profile did not ask for.
        val belowFloor = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 2) { sample(300_000) } + List(2) { sample(1_200_000) },
            mapOf(0 to 900_000),
        )
        assertTrue(belowFloor.failures.isEmpty())
        assertNull(belowFloor.code)
        assertTrue(
            "a decision that is no longer made has no numbers to carry",
            belowFloor.admittedTargetsKhz.isEmpty(),
        )

        // The ceiling half of this pin went with the refusal it described .
        // The duty it protected does not go with it: a decision that silently changes the plan must
        // still carry the numbers that made it. For the snap that is the admitted map, the same place
        // the clamp above is asserted from and the same place the journalled plan is built from.
        val offTable = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT) { sample(min = 300_000, max = 1_800_000) },
            mapOf(0 to 2_000_000),
            mapOf(0 to listOf(300_000, 1_200_000, 1_804_800, 2_400_000)),
        )
        assertTrue(offTable.failures.isEmpty())
        assertEquals(HardwareProbe.CPU_TARGET_SNAPPED_TO_OPERATING_POINT, offTable.code)
        assertEquals(
            "the snap must carry both numbers that decided it -- requested 2000000, nearest point " +
                "below it 1804800",
            mapOf(0 to 1_804_800),
            offTable.admittedTargetsKhz,
        )
    }

    @Test
    fun `fixed fan topology is admitted while manual ownership stays exact Odin only`() {
        assertTrue(
            FanMutationAdmissionPolicy.admits(
                deviceProfileId = "ayn-odin3-cq8725s-pserver",
                fanNodeAvailable = true,
                fanModeViaSettings = true,
            ),
        )
        assertTrue(
            FanMutationAdmissionPolicy.admits(
                deviceProfileId = "rp5",
                fanNodeAvailable = true,
                fanModeViaSettings = true,
            ),
        )
        assertFalse(
            FanMutationAdmissionPolicy.admits(
                deviceProfileId = "unknown",
                fanNodeAvailable = true,
                fanModeViaSettings = true,
            ),
        )
    }

    @Test
    fun `fan first max only crash boundaries reconstruct literal reverse to Stock`() {
        repeat(2) { deathIndex ->
            val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
            val journal = CountingJournal()
            val barrier = TransactionBarrier { point, index ->
                if (point == TransactionBarrierPoint.AFTER_MUTATION && index == deathIndex) {
                    throw TransactionDeathException(point, index)
                }
            }
            val dying = TransactionCoordinator(
                planner = planner(), state = rig, commands = rig, journal = journal,
                hardwareWritesCompiled = true, barrier = barrier, idFactory = { "tx" }, clock = { 1_000L },
            )

            try {
                dying.apply("quiet", PACKAGE, capabilities(), dryRun = false)
            } catch (_: TransactionDeathException) {
                val record = (journal.load() as JournalLoadResult.Loaded).record
                assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID, "cpu:0:scaling_max_freq"), record.applyOrderLogicalIds)
                assertTrue(record.snapshot.cpuMinKhz.isEmpty())
                assertTrue(record.applyOrderLogicalIds.none { it.endsWith(":scaling_min_freq") })
            }

            val restored = coordinator(rig, journal).restore()
            assertEquals("RESTORED", restored.code)
            assertTrue(rig.exactStock)
            assertEquals(listOf("cpu:0:scaling_max_freq", HardwareSnapshot.FAN_LOGICAL_ID), rig.executed.takeLast(2).map { it.logicalId })
            assertTrue(journal.load() is JournalLoadResult.Empty)
        }
    }

    @Test
    fun `same value fan reset blocks CPU before command restores Stock and clears journal`() {
        val plan = planner().plan("quiet", PACKAGE, capabilities())!!
        val snapshot = maxOnlySnapshot().copy(
            nodes = maxOnlySnapshot().nodes.map { node ->
                if (node.axis == TuningAxis.FAN) node.copy(originalValue = plan.fanMode) else node
            },
        )
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = snapshot),
            resetFloorAboveTargetOnFan = true,
        )
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply("quiet", PACKAGE, capabilities(), dryRun = false)

        assertEquals(HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR, result.code)
        assertTrue(result.restored)
        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, "cpu:0:scaling_max_freq", HardwareSnapshot.FAN_LOGICAL_ID),
            rig.executed.map { it.logicalId },
        )
        assertEquals(0, rig.cpuCommands)
        assertTrue(rig.fanWasSameValueWhenApplied)
        assertTrue(rig.exactStock)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `post fan admission failed cleanup retains journal and rollback drill stops`() {
        val plan = planner().plan("quiet", PACKAGE, capabilities())!!
        val snapshot = maxOnlySnapshot().copy(nodes = maxOnlySnapshot().nodes.map {
            if (it.axis == TuningAxis.FAN) it.copy(originalValue = plan.fanMode) else it
        })
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = snapshot), resetFloorAboveTargetOnFan = true)
        val journal = CountingJournal().apply { clearSucceeds = false }
        val runner = RollbackDrillRunner(
            transaction = coordinator(rig, journal), capabilities = ::capabilities, planner = planner(),
            calibrationStore = EmptyCalibrationStore, hardwareWritesCompiled = true, diagnosticsCompiled = true,
        )

        val result = runner.run("quiet", PACKAGE, drillCount = 3)

        assertFalse(result.success)
        assertEquals("RESTORE_FAILED", result.code)
        assertEquals(1, result.attempts)
        assertEquals(0, result.successes)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
        assertEquals(TransactionPhase.RESTORE_FAILED, (journal.load() as JournalLoadResult.Loaded).record.phase)
        assertEquals(0, rig.cpuCommands)
    }

    @Test
    fun `cancellation during post cooling dwell wins admission failure restores literal reverse and clears`() {
        var cancelled = false
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            resetFloorAboveTargetOnFan = true,
            onPostCoolingAdmission = { cancelled = true },
        )
        val journal = CountingJournal()

        val result = coordinator(rig, journal).apply(
            "quiet", PACKAGE, capabilities(), dryRun = false, isCancelled = { cancelled },
        )

        assertEquals("REQUEST_CANCELLED", result.code)
        assertTrue(result.restored)
        assertEquals(0, rig.cpuCommands)
        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, "cpu:0:scaling_max_freq", HardwareSnapshot.FAN_LOGICAL_ID),
            rig.executed.map { it.logicalId },
        )
        assertTrue(rig.exactStock)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `deadline during post cooling dwell wins admission failure before compute`() {
        var now = 1_000L
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            resetFloorAboveTargetOnFan = true,
            onPostCoolingAdmission = { now = 2_000L },
        )
        val journal = CountingJournal()
        val tx = TransactionCoordinator(planner(), rig, rig, journal, true, idFactory = { "dwell-deadline" }, clock = { now })

        val result = tx.apply(
            "quiet", PACKAGE, capabilities(), dryRun = false, requestDeadlineElapsedRealtimeMs = 2_000L,
        )

        assertEquals("REQUEST_EXPIRED", result.code)
        assertTrue(result.restored)
        assertEquals(0, rig.cpuCommands)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `hard lease expiry during post cooling dwell wins admission failure before compute`() {
        var now = 1_000L
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            resetFloorAboveTargetOnFan = true,
            onPostCoolingAdmission = { now = 2_000L },
        )
        val journal = CountingJournal()
        val tx = TransactionCoordinator(planner(), rig, rig, journal, true, idFactory = { "dwell-lease" }, clock = { now })

        val result = tx.apply(
            "quiet", PACKAGE, capabilities(), dryRun = false,
            requestDeadlineElapsedRealtimeMs = 3_000L, hardLeaseDeadlineElapsedRealtimeMs = 2_000L,
        )

        assertEquals("HARD_LEASE_EXPIRED", result.code)
        assertTrue(result.restored)
        assertEquals(0, rig.cpuCommands)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `cancellation during dwell with failed restore clear retains RESTORE_FAILED journal`() {
        var cancelled = false
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            resetFloorAboveTargetOnFan = true,
            onPostCoolingAdmission = { cancelled = true },
        )
        val journal = CountingJournal().apply { clearSucceeds = false }

        val result = coordinator(rig, journal).apply(
            "quiet", PACKAGE, capabilities(), dryRun = false, isCancelled = { cancelled },
        )

        assertEquals("RESTORE_FAILED", result.code)
        assertEquals(0, rig.cpuCommands)
        val retained = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TransactionPhase.RESTORE_FAILED, retained.phase)
    }

    @Test
    fun `legacy floor snapshot remains restoreable while new records stay max only`() {
        val plan = planner().plan("quiet", PACKAGE, capabilities())!!
        val legacyFloor = TopologyNodeState(
            logicalId = "cpu:0:scaling_min_freq",
            axis = TuningAxis.CPU,
            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            originalValue = 1_200_000,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )
        val snapshot = maxOnlySnapshot().copy(
            nodes = listOf(legacyFloor) + maxOnlySnapshot().nodes,
        )
        val record = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V2,
            generation = 1,
            requestId = "legacy-request",
            transactionId = "legacy-tx",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = "rp5",
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = "legacy-topology",
            snapshotSha256 = "legacy-snapshot",
            lastCompletedMutationBoundary = 3,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = listOf(
                "cpu:0:scaling_min_freq",
                "cpu:0:scaling_max_freq",
                HardwareSnapshot.FAN_LOGICAL_ID,
            ),
        )
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(PServerBinderTransport { _, _ -> PServerTransactResult(false) }),
            reader = SystemReader(),
            capabilities = ::capabilities,
        )

        val restore = port.restoreMutations(record)

        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, restore.first().logicalId)
        assertTrue(restore.any { it.logicalId == "cpu:0:scaling_min_freq" && it.value == 1_200_000 })
        assertTrue(restore.any { it.logicalId == "cpu:0:scaling_max_freq" && it.value == 1_800_000 })
    }

    @Test
    fun `authenticated schema v4 legacy floor record loads validates restores readbacks and clears while new apply refuses it`() {
        val floor = TopologyNodeState(
            logicalId = "cpu:0:scaling_min_freq", axis = TuningAxis.CPU,
            canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
            originalValue = 1_200_000, originalMode = "644", originalUid = 0, originalGid = 0, present = true,
        )
        val snapshot = maxOnlySnapshot().copy(nodes = listOf(floor) + maxOnlySnapshot().nodes.filter { it.axis == TuningAxis.CPU })
        val plan = TuningPlan("quiet", PACKAGE, mapOf(0 to 900_000), null, null, true)
        val expected = SnapshotIntegrity.expectedTopology(plan, capabilities(), cpuMinPolicyIds = setOf(0))!!
        val apply = listOf(
            LogicalMutation(floor.logicalId, TuningAxis.CPU, floor.canonicalPath, LogicalMutationKind.SYSFS_WRITE, 900_000, "644", 0, 0, true),
            LogicalMutation("cpu:0:scaling_max_freq", TuningAxis.CPU, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", LogicalMutationKind.SYSFS_WRITE, 900_000, "644", 0, 0, true),
        )
        val record = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA,
            generation = 1, requestId = "legacy-v4-request", transactionId = "legacy-v4-tx",
            hardwareFingerprint = snapshot.hardwareFingerprint, deviceProfile = "rp5", planHash = PlanHash.of(plan),
            profileId = plan.profileId, targetPackage = plan.packageName, phase = TransactionPhase.APPLIED,
            creationTime = 1, requestDeadline = 2, leaseDeadline = 3, bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot, expectedTopologySha256 = SnapshotIntegrity.topologySha256(expected),
            snapshotSha256 = SnapshotIntegrity.snapshotSha256(snapshot), lastCompletedMutationBoundary = 2,
            appliedPlan = PersistedTuningPlan.from(plan), applyOrderLogicalIds = apply.map { it.logicalId },
            appliedMutationProjection = apply.map(PersistedAppliedMutation::from),
        )
        val authority = GuardianTestKeyAuthority.create().available
        val authentication = object : TransactionJournalAuthentication {
            override fun protect(encodedJournalPayload: ByteArray): ByteArray =
                AuthenticatedTransactionJournalCodec.encode(authority.signJournal(encodedJournalPayload))
            override fun verifyAndDecode(authenticatedRecord: ByteArray): JournalLoadResult =
                AuthenticatedTransactionJournalCodec.decodeAndVerify(authenticatedRecord, authority.journalVerifier())
        }
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "p1-v4-legacy-auth").apply { deleteRecursively(); mkdirs() }
        val journal = AtomicFileTransactionJournal(
            filesDir = directory, fileName = "legacy.mtja", generationFileName = "legacy.gen", testSeam = true,
            directoryDurability = NoopDirectoryDurability, authentication = authentication,
        )
        assertEquals(1L, (journal.nextGeneration() as GenerationResult.Ok).value)
        assertTrue(journal.persist(record))
        val loaded = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TRANSACTION_JOURNAL_SCHEMA, loaded.schemaVersion)
        assertEquals(setOf(0), loaded.snapshot.cpuMinKhz.keys)
        assertTrue(RestoreContextValidator.validate(loaded, capabilities(), SysfsCanonicalResolver { it }).ok)

        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = snapshot))
        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORED", restored.code)
        assertTrue(rig.exactStock)
        assertTrue(journal.load() is JournalLoadResult.Empty)

        val refusing = coordinator(CeilingOnlyRig(CaptureOutcome(snapshot = snapshot)), CountingJournal())
            .apply("quiet", PACKAGE, capabilities(), dryRun = false)
        assertEquals("CPU_FLOOR_OWNERSHIP_FORBIDDEN", refusing.code)
    }

    @Test
    fun `drill stops at first failed restore and reports one attempted zero successful`() {
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            snapshotVerifications = ArrayDeque(
                listOf(PlanVerification(false, listOf("vendor reset after restore"))),
            ),
        )
        val journal = CountingJournal()
        val runner = RollbackDrillRunner(
            transaction = coordinator(rig, journal),
            capabilities = ::capabilities,
            planner = planner(),
            calibrationStore = EmptyCalibrationStore,
            hardwareWritesCompiled = true,
            diagnosticsCompiled = true,
        )

        val result = runner.run("quiet", PACKAGE, drillCount = 3)

        assertFalse(result.success)
        assertEquals("RESTORE_FAILED", result.code)
        assertEquals(1, result.attempts)
        assertEquals(0, result.successes)
        assertEquals(1, rig.applyStarts)
    }

    @Test
    fun `late vendor reassert after restore blocks the next drill before any next cycle command`() {
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            snapshotVerifications = ArrayDeque(
                listOf(
                    PlanVerification(true),
                    PlanVerification(false, listOf("late AYN Stock reassert")),
                    PlanVerification(false, listOf("late AYN Stock reassert")),
                    PlanVerification(false, listOf("late AYN Stock reassert")),
                    PlanVerification(false, listOf("late AYN Stock reassert")),
                ),
            ),
        )
        val journal = CountingJournal()
        val runner = RollbackDrillRunner(
            transaction = coordinator(rig, journal),
            capabilities = ::capabilities,
            planner = planner(),
            calibrationStore = EmptyCalibrationStore,
            hardwareWritesCompiled = true,
            diagnosticsCompiled = true,
        )

        val result = runner.run("quiet", PACKAGE, drillCount = 3)

        assertFalse(result.success)
        assertEquals("STOCK_UNSETTLED", result.code)
        assertEquals(1, result.attempts)
        assertEquals(1, result.successes)
        assertEquals(1, rig.applyStarts)
        assertEquals(4, rig.executed.size)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `settled Stock observation admits three drills and reports the measured window`() {
        var now = 1_000L
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            onSnapshotVerification = { now += 750L },
            onPostCoolingAdmission = { now += 750L },
        )
        val journal = CountingJournal()
        val transaction = TransactionCoordinator(
            planner = planner(),
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
            idFactory = { "settled-${journal.generationRequests}" },
            clock = { now },
        )
        val runner = RollbackDrillRunner(
            transaction = transaction,
            capabilities = ::capabilities,
            planner = planner(),
            calibrationStore = EmptyCalibrationStore,
            hardwareWritesCompiled = true,
            diagnosticsCompiled = true,
        )

        val result = runner.run("quiet", PACKAGE, drillCount = 3)

        assertTrue(result.success)
        assertEquals("DRILLS_PASSED", result.code)
        assertEquals("STOCK_SETTLED_OBSERVED", result.settlementCode)
        assertEquals(3, result.attempts)
        assertEquals(3, result.successes)
        assertEquals(6_000L, result.observedSettlementWindowMs)
        assertEquals(2, result.settlementCleanRounds)
        assertEquals(3_000L, result.minimumCleanSettlementWindowMs)
        assertEquals(3, rig.applyStarts)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `Stock settlement requires two consecutive clean rounds after observed movement`() {
        var now = 1_000L
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            snapshotVerifications = ArrayDeque(
                listOf(
                    PlanVerification(true),
                    PlanVerification(true),
                    PlanVerification(false, listOf("vendor movement")),
                    PlanVerification(true),
                    PlanVerification(true),
                ),
            ),
            onSnapshotVerification = { now += 750L },
            onPostCoolingAdmission = { now += 750L },
        )
        val journal = CountingJournal()
        val transaction = TransactionCoordinator(
            planner = planner(),
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
            idFactory = { "two-clean" },
            clock = { now },
        )
        val applied = transaction.apply("quiet", PACKAGE, capabilities(), dryRun = false)
        val restored = transaction.restore()

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = checkNotNull(restored.generation),
            expectedTransactionId = checkNotNull(restored.transactionId),
            nextPlan = checkNotNull(applied.plan),
        )

        assertTrue(settlement.settled)
        assertEquals("STOCK_SETTLED_OBSERVED", settlement.code)
        assertEquals(2, settlement.consecutiveCleanRounds)
        assertEquals(6_000L, settlement.observedWindowMs)
        assertEquals(3_000L, settlement.consecutiveCleanWindowMs)
        assertEquals(5, rig.snapshotVerifyCalls)
    }

    @Test
    fun `specific next cycle floor rejection outranks generic Stock settlement failure`() {
        val belowFloor = PlanVerification(
            ok = false,
            failures = listOf("target below settled vendor floor"),
            code = HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR,
        )
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            snapshotVerifications = ArrayDeque(
                listOf(
                    PlanVerification(true),
                    PlanVerification(false, listOf("Stock moved")),
                ),
            ),
            postCoolingAdmissions = ArrayDeque(listOf(PlanVerification(true), belowFloor)),
        )
        val journal = CountingJournal()
        val transaction = coordinator(rig, journal)
        val applied = transaction.apply("quiet", PACKAGE, capabilities(), dryRun = false)
        val restored = transaction.restore()

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = checkNotNull(restored.generation),
            expectedTransactionId = checkNotNull(restored.transactionId),
            nextPlan = checkNotNull(applied.plan),
        )

        assertFalse(settlement.settled)
        assertEquals(HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR, settlement.code)
        assertEquals(1, rig.applyStarts)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `stale restore identity performs zero settlement observations`() {
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
        val journal = CountingJournal()

        val settlement = coordinator(rig, journal).observeRestoredStockSettlement(
            expectedGeneration = 99L,
            expectedTransactionId = "unknown",
            nextPlan = planner().plan("quiet", PACKAGE, capabilities()),
        )

        assertFalse(settlement.settled)
        assertEquals("STALE_GENERATION", settlement.code)
        assertEquals(0, rig.snapshotVerifyCalls)
        assertEquals(0, rig.postCoolingAdmissionCalls)
        assertTrue(rig.executed.isEmpty())
    }

    @Test
    fun `write disabled posture refuses Stock settlement before any observation`() {
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
        val journal = CountingJournal()
        val transaction = TransactionCoordinator(
            planner = planner(),
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = false,
        )

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = 1L,
            expectedTransactionId = "disabled",
            nextPlan = planner().plan("quiet", PACKAGE, capabilities()),
        )

        assertFalse(settlement.settled)
        assertEquals("WRITE_GATE_DISABLED", settlement.code)
        assertEquals(0, rig.snapshotVerifyCalls)
        assertEquals(0, rig.postCoolingAdmissionCalls)
        assertTrue(rig.executed.isEmpty())
    }

    @Test
    fun `corrupt journal outranks stale identity and performs zero observation`() {
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
        val journal = CountingJournal().apply {
            forcedLoad = JournalLoadResult.Corrupt("test corruption")
        }

        val settlement = coordinator(rig, journal).observeRestoredStockSettlement(
            expectedGeneration = 1L,
            expectedTransactionId = "corrupt",
            nextPlan = planner().plan("quiet", PACKAGE, capabilities()),
        )

        assertFalse(settlement.settled)
        assertEquals("JOURNAL_CORRUPT", settlement.code)
        assertEquals(0, rig.snapshotVerifyCalls)
        assertEquals(0, rig.postCoolingAdmissionCalls)
        assertTrue(rig.executed.isEmpty())
    }

    @Test
    fun `active journal refuses Stock settlement without adding observations or commands`() {
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
        val journal = CountingJournal()
        val transaction = coordinator(rig, journal)
        val applied = transaction.apply("quiet", PACKAGE, capabilities(), dryRun = false)
        val snapshotsBefore = rig.snapshotVerifyCalls
        val admissionsBefore = rig.postCoolingAdmissionCalls
        val commandsBefore = rig.executed.size

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = checkNotNull(applied.generation),
            expectedTransactionId = checkNotNull(applied.transactionId),
            nextPlan = checkNotNull(applied.plan),
        )

        assertFalse(settlement.settled)
        assertEquals("SESSION_ACTIVE", settlement.code)
        assertEquals(snapshotsBefore, rig.snapshotVerifyCalls)
        assertEquals(admissionsBefore, rig.postCoolingAdmissionCalls)
        assertEquals(commandsBefore, rig.executed.size)
    }

    @Test
    fun `unsettled next cycle CPU window blocks before another apply`() {
        val unsettled = PlanVerification(
            ok = false,
            failures = listOf("live CPU window changed"),
            code = HardwareProbe.CPU_WINDOW_UNSETTLED,
        )
        val rig = CeilingOnlyRig(
            capture = CaptureOutcome(snapshot = maxOnlySnapshot()),
            postCoolingAdmissions = ArrayDeque(
                listOf(
                    PlanVerification(true),
                    unsettled,
                    unsettled,
                    unsettled,
                    unsettled,
                ),
            ),
        )
        val journal = CountingJournal()
        val transaction = coordinator(rig, journal)
        val applied = transaction.apply("quiet", PACKAGE, capabilities(), dryRun = false)
        val restored = transaction.restore()
        val commandsBefore = rig.executed.size

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = checkNotNull(restored.generation),
            expectedTransactionId = checkNotNull(restored.transactionId),
            nextPlan = checkNotNull(applied.plan),
        )

        assertFalse(settlement.settled)
        assertEquals(HardwareProbe.CPU_WINDOW_UNSETTLED, settlement.code)
        assertEquals(commandsBefore, rig.executed.size)
        assertEquals(1, rig.applyStarts)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `plan without CPU targets cannot degrade Stock settlement to one predicate`() {
        val rig = CeilingOnlyRig(capture = CaptureOutcome(snapshot = maxOnlySnapshot()))
        val journal = CountingJournal()
        val transaction = coordinator(rig, journal)
        val applied = transaction.apply("quiet", PACKAGE, capabilities(), dryRun = false)
        val restored = transaction.restore()
        val snapshotsBefore = rig.snapshotVerifyCalls
        val admissionsBefore = rig.postCoolingAdmissionCalls

        val settlement = transaction.observeRestoredStockSettlement(
            expectedGeneration = checkNotNull(restored.generation),
            expectedTransactionId = checkNotNull(restored.transactionId),
            nextPlan = checkNotNull(applied.plan).copy(cpuMaxKhz = emptyMap()),
        )

        assertFalse(settlement.settled)
        assertEquals("CPU_SETTLEMENT_UNAVAILABLE", settlement.code)
        assertEquals(snapshotsBefore, rig.snapshotVerifyCalls)
        assertEquals(admissionsBefore, rig.postCoolingAdmissionCalls)
    }

    private fun coordinator(rig: CeilingOnlyRig, journal: TransactionJournal): TransactionCoordinator =
        TransactionCoordinator(
            planner = planner(),
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
            idFactory = { "tx" },
            clock = { 1_000L },
        )

    private fun planner() = TuningPlanner { _, _ -> true }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "test",
        model = "qualcomm",
        socModel = "generic",
        buildFingerprint = "test/p1",
        // Fan persistence is a vendor RP5 setting; production projection validation
        // intentionally refuses to model it on arbitrary profiles.
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(
                id = 0,
                path = "/sys/devices/system/cpu/cpufreq/policy0",
                cpuIds = listOf(0, 1, 2, 3),
                availableFrequenciesKhz = listOf(300_000, 900_000, 1_800_000),
                minKhz = 300_000,
                maxKhz = 1_800_000,
                currentKhz = 1_800_000,
            ),
        ),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun maxOnlySnapshot(): HardwareSnapshot = HardwareSnapshot(
        schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
        hardwareFingerprint = HardwareFingerprint.create(capabilities()),
        bootIdentity = "boot-p1",
        nodes = listOf(
            TopologyNodeState(
                logicalId = "cpu:0:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                originalValue = 1_800_000,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            ),
            TopologyNodeState(
                logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                axis = TuningAxis.FAN,
                canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                originalValue = 4,
                originalMode = null,
                originalUid = null,
                originalGid = null,
                present = true,
            ),
        ),
    )

    private fun androidState(maxRead: () -> String, sleeper: (Long) -> Unit = {}): AndroidHardwareStatePort {
        val cpuRoot = "/sys/devices/system/cpu/cpufreq/policy0"
        val reader = SystemReader(
            directRead = { path ->
                when (path) {
                    "$cpuRoot/scaling_min_freq" -> "300000"
                    "$cpuRoot/scaling_max_freq" -> maxRead()
                    "$cpuRoot/scaling_cur_freq" -> "900000"
                    else -> null
                }
            },
            directDirectories = { _, _ -> emptyList() },
        )
        val probe = HardwareProbe(
            context = NoPowerManagerContext(RuntimeEnvironment.getApplication()),
            reader = reader,
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ -> PServerTransactResult(false) },
            ),
            sleeper = sleeper,
        )
        return AndroidHardwareStatePort(
            probe = probe,
            capabilities = ::capabilities,
            sleeper = sleeper,
        )
    }

    /** Exercises the production full-window reducer directly; telemetry itself is already
     * covered by its own probe tests and older Robolectric android.jar lacks the class
     * overload of Context.getSystemService used on actual devices. */
    private fun verifyFullWindow(samples: List<PlanVerification>, sleeps: MutableList<Long> = mutableListOf()): PlanVerification {
        val cursor = samples.iterator()
        val method = AndroidHardwareStatePort::class.java
            .getDeclaredMethod("verifyFullWindow", kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(
            androidState(maxRead = { "900000" }, sleeper = { sleeps += it }),
            { if (cursor.hasNext()) cursor.next() else samples.last() },
        ) as PlanVerification
    }


    private class CeilingOnlyRig(
        private val capture: CaptureOutcome,
        private val planVerifications: ArrayDeque<PlanVerification> = ArrayDeque(),
        private val snapshotVerifications: ArrayDeque<PlanVerification> = ArrayDeque(),
        private val postCoolingAdmissions: ArrayDeque<PlanVerification> = ArrayDeque(),
        private val resetFloorAboveTargetOnFan: Boolean = false,
        private val onSnapshotVerification: (() -> Unit)? = null,
        private val onPostCoolingAdmission: (() -> Unit)? = null,
    ) : HardwareStatePort, HardwareCommandPort {
        val executed = mutableListOf<LogicalMutation>()
        var verifyPlanCalls = 0
            private set
        var applyStarts = 0
            private set
        var snapshotVerifyCalls = 0
            private set
        var postCoolingAdmissionCalls = 0
            private set
        var exactStock = false
            private set
        var cpuCommands = 0
            private set
        var fanWasSameValueWhenApplied = false
            private set
        private var floorKhz = 300_000
        private var ceilingKhz = 1_800_000
        private var postFanAdmissionFailed = false

        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
            applyStarts++
            return capture
        }

        override fun verify(plan: TuningPlan): Boolean = verifyPlan(plan).ok

        override fun verifyPlan(plan: TuningPlan): PlanVerification {
            verifyPlanCalls++
            return if (planVerifications.isEmpty()) PlanVerification(true) else planVerifications.removeFirst()
        }

        override fun verifyAppliedPlan(
            plan: TuningPlan,
            mutations: List<LogicalMutation>,
        ): PlanVerification = verifyPlan(plan)

        override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean = verifySnapshotPlan(snapshot).ok

        override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification {
            snapshotVerifyCalls++
            onSnapshotVerification?.invoke()
            val result = if (snapshotVerifications.isEmpty()) PlanVerification(true) else snapshotVerifications.removeFirst()
            if (result.ok) exactStock = true
            return result
        }

        override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification {
            postCoolingAdmissionCalls++
            onPostCoolingAdmission?.invoke()
            if (postCoolingAdmissions.isNotEmpty()) return postCoolingAdmissions.removeFirst()
            val target = plan.cpuMaxKhz.getValue(0)
            return if (resetFloorAboveTargetOnFan && executed.any { it.axis == TuningAxis.FAN }) {
                postFanAdmissionFailed = true
                PlanVerification(
                    ok = false,
                    failures = listOf("cpu policy 0 target $target is below settled live floor $floorKhz after same-value fan reset"),
                    code = HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR,
                )
            } else {
                PlanVerification(true)
            }
        }

        override fun readback(mutation: LogicalMutation): Boolean = true

        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> =
            snapshot.nodes.map { node ->
                val value = when (node.axis) {
                    TuningAxis.CPU -> plan.cpuMaxKhz.getValue(0)
                    TuningAxis.FAN -> plan.fanMode
                    TuningAxis.GPU -> error("GPU is absent in this P1 rig")
                    TuningAxis.VENDOR_MODE -> error("vendor mode is absent in this P1 rig")
                }
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = if (node.axis == TuningAxis.FAN) {
                        LogicalMutationKind.SETTINGS_PUT
                    } else {
                        LogicalMutationKind.SYSFS_WRITE
                    },
                    value = value,
                    restoreMode = node.originalMode,
                    restoreUid = node.originalUid,
                    restoreGid = node.originalGid,
                    expectPresent = true,
                )
            }.sortedBy { if (it.axis == TuningAxis.FAN) 0 else 1 }

        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> =
            record.applyOrderLogicalIds.asReversed().map { logicalId ->
                val node = record.snapshot.nodes.first { it.logicalId == logicalId }
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = if (node.axis == TuningAxis.FAN) {
                        LogicalMutationKind.SETTINGS_PUT
                    } else {
                        LogicalMutationKind.SYSFS_WRITE
                    },
                    value = node.originalValue,
                    restoreMode = node.originalMode,
                    restoreUid = node.originalUid,
                    restoreGid = node.originalGid,
                    expectPresent = node.present,
                    capturedSnapshotAuthority = node.axis == TuningAxis.FAN,
                )
            }

        override fun validateRestore(record: TransactionJournalRecord): PlanVerification = PlanVerification(true)

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            executed += mutation
            if (mutation.axis == TuningAxis.FAN && resetFloorAboveTargetOnFan) {
                fanWasSameValueWhenApplied = mutation.value == capture.snapshot?.fanMode
                floorKhz = 5_000_000
            }
            if (mutation.axis == TuningAxis.CPU) {
                // A CPU command after this marker is the literal-reverse recovery write,
                // not a compute/apply command.
                if (!postFanAdmissionFailed) cpuCommands++
                ceilingKhz = mutation.value ?: ceilingKhz
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0)
        }
    }

    private class CountingJournal : TransactionJournal {
        var record: TransactionJournalRecord? = null
        var forcedLoad: JournalLoadResult? = null
        var generationRequests = 0
        var persists = 0
        var clearSucceeds = true

        override fun load(): JournalLoadResult =
            forcedLoad ?: record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty

        override fun nextGeneration(): GenerationResult = GenerationResult.Ok((++generationRequests).toLong())

        override fun persist(record: TransactionJournalRecord): Boolean {
            persists++
            this.record = record
            return true
        }

        override fun clear(expectedTransactionId: String): Boolean {
            if (!clearSucceeds) return false
            if (record?.transactionId != expectedTransactionId) return false
            record = null
            return true
        }
    }

    private object EmptyCalibrationStore : CalibrationReportStore {
        override fun load(): CalibrationReport? = null
        override fun save(report: CalibrationReport): Boolean = false
        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null
        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
    }

    /** Telemetry needs no PowerManager fact for this CPU-only timeline; use a concrete
     * Context wrapper so the JVM fixture never reaches an Android mock method. */
    private class NoPowerManagerContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? = null
    }

    private fun nonceOf(command: String): String =
        Regex("""MIMIR_TX_EXIT:([A-Za-z0-9_-]+):%s""").find(command)!!.groupValues[1]

    private companion object {
        const val PACKAGE = "example.game"
    }

    /**
     * **The live floor no longer steers the plan.** The clamp was removed on 2026-09-21
     * after reviewing the SoC mechanism and measuring its behaviour.
     *
     * The review, on the Thor, 45 one-second samples of the QoS min node with the device IDLE: 35
     * resting at {0=672000, 3=1536000, 7=0} and 10 burst to {0=2016000, 3=2707200, 7=0} — the cluster
     * maxima, in one-to-two second bursts every three to five seconds. `observedWindow` takes
     * `mins.max()`, so it almost always caught a burst. Runs 15 and 16, byte-identical APK and the same
     * Balanced plan, journalled {0=2016000, 3=2707200, 7=1843200} and {0=1113600, 3=1651200, 7=1843200}:
     * the contract was a coin toss on which phase the dwell sampled.
     *
     * The measurement, same evening, with the clamp off: Quiet and Balanced both reached the shared QoS
     * node verbatim, zero admission lines, and policy3's max request GOVERNED over a vendor min request
     * nearly twice as high — `scaling_max_freq` and `scaling_cur_freq` both followed it down.
     *
     * The clamp only ever protected the per-policy `scaling_max_freq` transport, where request and
     * effective state are one file. No qualified chassis is known to use it: the Odin, Nova and Thor all
     * read the shared node all-`INT_MAX` at stock, and the fallback is reachable only on unqualified
     * hardware or on a node left stranded by an interrupted session. A chassis that does fall back now
     * refuses honestly rather than silently shipping three identical profiles.
     */
    @Test
    fun `a burst in the window no longer rewrites the plan, and a broken instrument still refuses`() {
        // Mostly resting at 672000, two at the cluster maximum — the shape the Thor presents.
        // Under the old rule the aggregate floor became 2016000 and swallowed the whole underclock.
        fun samples() = List(CpuWindowAdmissionPolicy.SAMPLE_COUNT - 2) { mapOf(0 to CpuLiveWindowSample(672_000, 2_016_000)) } +
            List(2) { mapOf(0 to CpuLiveWindowSample(2_016_000, 2_016_000)) }
        val target = mapOf(0 to 1_113_600)

        val admitted = CpuWindowAdmissionPolicy.afterDwell(samples(), target)
        assertTrue(admitted.failures.isEmpty())
        assertNull("the plan is not rewritten, so there is no code to report", admitted.code)
        assertTrue(
            "and nothing is journalled that the profile did not ask for",
            admitted.admittedTargetsKhz.isEmpty(),
        )

        // Off-table snapping is NOT what was retired. It is a property of the silicon, true on every
        // transport and in every phase, and it must survive the floor's removal. It used to be gated on
        // `onTable >= min`, which is exactly the coupling that would have dropped it here.
        val offTable = CpuWindowAdmissionPolicy.afterDwell(
            samples(),
            mapOf(0 to 1_100_000),
            mapOf(0 to listOf(672_000, 1_017_600, 1_113_600, 2_016_000)),
        )
        assertEquals(HardwareProbe.CPU_TARGET_SNAPPED_TO_OPERATING_POINT, offTable.code)
        assertEquals(mapOf(0 to 1_017_600), offTable.admittedTargetsKhz)

        // The floor is still READ, purely to check the instrument. A policy that cannot present one is
        // a broken observation, and retiring a clamp must not retire the refusal that says so.
        val missingFloor = CpuWindowAdmissionPolicy.afterDwell(
            List(CpuWindowAdmissionPolicy.SAMPLE_COUNT) { mapOf(0 to CpuLiveWindowSample(null, 2_016_000)) },
            target,
        )
        assertTrue("an unreadable floor is still a refusal", missingFloor.failures.isNotEmpty())
    }

}
