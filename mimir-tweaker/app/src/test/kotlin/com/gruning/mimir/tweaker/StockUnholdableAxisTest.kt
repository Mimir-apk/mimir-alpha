package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **An axis the hardware will not hold cannot veto Stock**.
 *
 * The device wrote this test. On the Thor, 2026-09-21 17:29:30, Stock was pressed on a live
 * Quiet session and the withdrawal worked — the shared QoS node released value *and* permissions,
 * `mode=644` with every policy at `INT_MAX`. The guardian refused it anyway over one axis in six:
 *
 * ```
 * Guardian stock unconfirmed persistentAxes=1 FIRST_READBACK_NOT_STOCK entries=6 mismatched=1
 *                            axes=gpu:min_pwrlevel=4/644/0/0!=0
 * requested=stock prior=quiet result=Rejected(code=SYSTEM_STOCK_NOT_VERIFIED)
 * ```
 *
 * Because the SELECTION was rejected the standing profile stayed `quiet`, so the re-arm watcher put
 * Quiet back on fourteen seconds later. The Tweaker turned itself back on after being told to stop.
 *
 * That is what makes this refusal unlike every other one in the module. Ordinary apply
 * refusals leave the device at stock; refusing a RESTORE inverts the argument,
 * leaving it tuned and then re-arming.
 *
 * See [vetoesWindowedVerification] for the two directions the rule is narrowed in — which axes, and which
 * checks.
 */
class StockUnholdableAxisTest {

    /**
     * `RestoreGuardianSysfsEntry.validate` canonicalizes through `java.io.File`, so on Windows a
     * `/sys` path resolves to `C:\sys\…`, the backslash gate rejects it, and no guardian sysfs entry
     * can be constructed at all. The same wall is why `RestoreGuardianRunnerTest` carries known
     * Windows failures. Skipping is honest here; these run for real on Linux and on device builds,
     * and the two tests below this line need no entry and run everywhere.
     */
    private fun requireConstructibleSysfsEntries() = assumeTrue(
        "a /sys path cannot canonicalize on this host, so guardian sysfs entries cannot be built",
        SysfsPath.canonicalize(SHARED_CPU_QOS_CEILING_PATH) == SHARED_CPU_QOS_CEILING_PATH,
    )

    // `by lazy`, so a host that cannot build them still runs everything that does not ask.
    private val cpu by lazy {
        RestoreGuardianSysfsEntry(
            applyOrdinal = 1,
            logicalId = "cpu:0:scaling_max_freq",
            canonicalSysfsPath = SHARED_CPU_QOS_CEILING_PATH,
            expectedStock = RestoreGuardianStockState.PresentSysfs(UNPINNED_CPU_CEILING, "644", 0, 0),
            rendererAppliedIntermediates = listOf(1_017_600),
        )
    }
    private val gpuMin by lazy {
        RestoreGuardianSysfsEntry(
            applyOrdinal = 2,
            logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
            canonicalSysfsPath = "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
            expectedStock = RestoreGuardianStockState.PresentSysfs(0, "644", 0, 0),
            rendererAppliedIntermediates = listOf(3),
        )
    }
    private val fan by lazy {
        RestoreGuardianFanPutEntry(
            applyOrdinal = 3,
            expectedStock = RestoreGuardianStockState.PresentFanMode(0),
            rendererAppliedIntermediates = listOf(2),
        )
    }
    private val entries by lazy { listOf(cpu, gpuMin, fan) }

    /** Everything at stock unless a caller names the axis that is not. */
    private fun atStock(
        gpuValue: Int? = 0,
        cpuValue: Int? = UNPINNED_CPU_CEILING,
        fanValue: Int? = 0,
    ) = GuardianStockReadback(
        entries = mapOf(
            cpu.logicalId to GuardianObservedState.Sysfs(cpuValue, "644", 0, 0),
            gpuMin.logicalId to GuardianObservedState.Sysfs(gpuValue, "644", 0, 0),
            fan.logicalId to GuardianObservedState.FanMode(fanValue),
        ),
        observedElapsedRealtimeMs = 100,
    )

    /** The second sample, far enough after the first to clear [GuardianDoubleStockReadbackPolicy]. */
    private fun GuardianStockReadback.settled() = copy(observedElapsedRealtimeMs = 400)

    @Test
    fun `the exact Thor refusal now confirms`() {
        requireConstructibleSysfsEntries()
        // gpu:min_pwrlevel rebounding 0 -> 4 while the CPU released cleanly: 17:29:30, verbatim.
        val rebounded = atStock(gpuValue = 4)

        assertEquals(
            "the withdrawal the device actually performed must be accepted as one",
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(entries, rebounded, rebounded.settled()),
        )
    }

    @Test
    fun `the axes a withdrawal can guarantee still veto`() {
        requireConstructibleSysfsEntries()
        // The shared QoS node stores what it is given, so a CPU that did not release is a real
        // failure to withdraw -- and a Stock that cannot confirm the CPU came down is worth nothing.
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                atStock(cpuValue = 1_017_600),
                atStock(cpuValue = 1_017_600).settled(),
            ),
        )
        // fan_mode is a Settings key. Nothing reclaims it, so a mismatch there is Mimir's own.
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                atStock(fanValue = 2),
                atStock(fanValue = 2).settled(),
            ),
        )
        // And a CPU that drifts only between the two samples is still caught on the second.
        assertEquals(
            GuardianDoubleStockDecision.SECOND_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                atStock(),
                atStock(cpuValue = 1_017_600).settled(),
            ),
        )
    }

    /**
     * **Suppressing a veto must not suppress the observation.** [GuardianDoubleStockReadbackPolicy]
     * exists because on 2026-08-30 the Nova held at `STOCK_UNCONFIRMED` through three installs
     * and the process that decided it could not say which axis. Confirming silently over a GPU that
     * stopped holding would be that same silence, reached from the other side.
     */
    @Test
    fun `a confirmation still names the axis that stopped holding`() {
        requireConstructibleSysfsEntries()
        val rebounded = atStock(gpuValue = 4)

        assertEquals(
            "STOCK_CONFIRMED unheld=gpu:min_pwrlevel=4/644/0/0!=0/644/0/0",
            GuardianDoubleStockReadbackPolicy.describe(entries, rebounded, rebounded.settled()),
        )
        assertEquals(
            "a device where every axis held says exactly what it said before -- no new noise on the " +
                "path that is already quiet",
            "STOCK_CONFIRMED",
            GuardianDoubleStockReadbackPolicy.describe(entries, atStock(), atStock().settled()),
        )
    }

    /**
     * An unholdable axis *missing* from the readback stops vetoing too, not only one holding a wrong
     * value. An unreadable GPU node is also unholdable, and the line still names it as `absent`.
     */
    @Test
    fun `an unreadable unholdable axis is reported rather than refused`() {
        requireConstructibleSysfsEntries()
        val unreadable = atStock().let { it.copy(entries = it.entries - gpuMin.logicalId) }

        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(entries, unreadable, unreadable.settled()),
        )
        assertEquals(
            "STOCK_CONFIRMED unheld=gpu:min_pwrlevel=absent",
            GuardianDoubleStockReadbackPolicy.describe(entries, unreadable, unreadable.settled()),
        )
        // A CPU missing from the readback still refuses, and still says `absent` rather than
        // collapsing into "wrong value" -- the distinction 08-30 was written to preserve.
        val cpuGone = atStock().let { it.copy(entries = it.entries - cpu.logicalId) }
        assertTrue(
            GuardianDoubleStockReadbackPolicy.describe(entries, cpuGone, cpuGone.settled())
                .contains("cpu:0:scaling_max_freq=absent"),
        )
    }

    /**
     * A plan carrying nothing but unholdable axes confirms vacuously. Acceptable, and the alternative
     * is not: the withdrawal writes still ran, and refusing would trap that chassis in its profile
     * with no press able to release it. Pinned so the emptiness is a decision on the record rather
     * than a property of `all {}` that nobody noticed.
     */
    @Test
    fun `a GPU-only plan releases rather than trapping the device`() {
        requireConstructibleSysfsEntries()
        val gpuOnly = listOf(gpuMin)
        val rebounded = atStock(gpuValue = 4)

        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(gpuOnly, rebounded, rebounded.settled()),
        )
        // The settle gap is NOT waived along with the veto: a caller that sampled twice too quickly
        // is still told so, because that is a defect in the observation, not in the hardware.
        assertEquals(
            GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT,
            GuardianDoubleStockReadbackPolicy.evaluate(gpuOnly, rebounded, rebounded),
        )
    }

    @Test
    fun `only the three axes 2026-08-31 measured are unholdable`() {
        assertEquals(
            setOf(
                HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID,
            ),
            UNHOLDABLE_AXIS_LOGICAL_IDS,
        )
        UNHOLDABLE_AXIS_LOGICAL_IDS.forEach { assertFalse(it, vetoesWindowedVerification(it)) }
        listOf(
            "cpu:0:scaling_max_freq",
            "cpu:7:scaling_max_freq",
            SHARED_CPU_QOS_CEILING_PATH,
            HardwareSnapshot.FAN_LOGICAL_ID,
        ).forEach { assertTrue(it, vetoesWindowedVerification(it)) }
    }

    /**
     * **The two windowed verifiers must share the rule, and the instantaneous one must not have it.**
     *
     * Three gates sit between a Stock press and a released device, and on 2026-09-21 only the first
     * was visible: the guardian's double readback refused, so the other two were never reached.
     * Fixing one alone would have cleared `STOCK_UNCONFIRMED` and produced `STOCK_UNSETTLED` from
     * `TransactionCoordinator:1883` on the next press — another device round trip to learn the same
     * thing.
     *
     * The line between them is not convenience. `verifySnapshotOnce` samples eleven times and admits
     * only on three consecutive final passes: it asks whether the restore **stayed**, which an axis
     * the governor reclaims after two seconds cannot answer honestly. The per-mutation
     * `state.readback(mutation)` in the restore loop asks whether the write **landed**, at the instant
     * it landed — a mismatch there is a genuinely failed write, and it keeps its veto. That same
     * function serves the APPLY path, where a GPU write that does not land is exactly the drift
     * `HardwareSessionSupervisor` is watching for.
     *
     * Source-text assertions because "these two share a derivation and that third one deliberately
     * does not" is not observable from any single path's behaviour.
     */
    @Test
    fun `the windowed verifiers share the rule and the instantaneous one keeps its veto`() {
        val ports = sourceFile("AndroidTransactionPorts.kt")
        val runner = sourceFile("RestoreGuardianRunner.kt")

        assertTrue(
            "the guardian's double readback must decide on the filtered entry set",
            runner.contains("entries.filter { vetoesWindowedVerification(it.logicalId) }"),
        )
        assertTrue(
            "the in-process windowed verifier must apply the same rule",
            ports.contains("if (!vetoesWindowedVerification(node.logicalId)) return@forEach"),
        )
        // The third site, found by the device on 2026-09-21 after the first two were fixed:
        // `gpu:min_pwrlevel` is not in GPU_CEILING_LOGICAL_IDS, so it fell through to an exact
        // projected-mutation readback INSIDE verifyFullWindow and refused about one apply in three
        // outright -- `Rejected(code=VERIFY_FAILED)` after running the whole apply twice.
        assertTrue(
            "the applied-plan window must apply the same rule to projected mutations",
            ports.contains("if (vetoesWindowedVerification(mutation.logicalId))"),
        )
        assertTrue(
            "and must still name what it stopped enforcing",
            ports.contains("applied-plan unheld="),
        )

        val instantaneous = ports.substringAfter("override fun readback(mutation: LogicalMutation)")
            .substringBefore("private fun readbackNode")
        assertFalse(
            "the per-mutation readback must keep its veto -- it checks landing, not staying",
            instantaneous.contains("vetoesWindowedVerification"),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
