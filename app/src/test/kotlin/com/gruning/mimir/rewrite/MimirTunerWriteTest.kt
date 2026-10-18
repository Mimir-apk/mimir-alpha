package com.gruning.mimir.rewrite

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The write path, pinned against the ways it is known to go wrong on this silicon.
 *
 * Every fixture below is the AYN Thor's real state, captured 2026-08-31. The QoS row in particular is
 * the one that falsified the write-path review's central premise, so it is the last row that should be
 * replaced with something tidier.
 */
class MimirTunerWriteTest {

    /**
     * The Thor at rest, as captured during the proven round trip at 17:14. Seven CPUs at their
     * cluster's ceiling. **NOT eight INT_MAX**, which is what the review predicted from Odin captures
     * and what a restore-by-constant would have written.
     */
    private val thorQosRow =
        "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2956800"

    /**
     * The same node half an hour earlier, with CPU 7 at INT_MAX.
     *
     * Kept as a second fixture rather than replaced, because the two readings TOGETHER are the evidence
     * that this node has a writer other than Mimir — the thing that was wrongly recorded as settled.
     */
    private val thorQosRowEarlier =
        "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2147483647"

    private class FakeNodes(
        val files: MutableMap<String, String> = mutableMapOf(),
        val policyDirectories: List<String> = emptyList(),
    ) : MimirTunerNodes {
        override fun listCpuPolicyDirectories(): List<String> = policyDirectories
        override fun read(path: String): String? = files[path]
    }

    /**
     * A host that actually lands its writes in the fake filesystem.
     *
     * **Worth the twelve lines.** A host that only records commands lets a test assert a restore that
     * could never happen on a device, because the node it reads back was never touched by the write it
     * is testing. Two tests here passed for exactly that reason until `withdraw` started reading the
     * live node, and then failed for a defect that was in the fake rather than the code.
     */
    private fun applyingHost(nodes: FakeNodes, sent: MutableList<String>): (String) -> Boolean = { command ->
        sent += command
        command.split(';').forEach { statement ->
            val value = statement.substringAfter('\'', "").substringBeforeLast('\'', "")
            val path = statement.substringAfterLast("> ").trim()
            if (path.isNotEmpty() && statement.contains('>')) {
                // The QoS node MERGES: writing "7:595200" moves CPU 7 and leaves every other CPU where
                // it was. Device-confirmed on the Thor -- after capping PRIME alone, CPUs 0-6 still read
                // their original values in the full row. A fake that replaced the whole row would make
                // a partial write look like it wiped seven CPUs, and every restore test built on it
                // would be testing a device that does not exist.
                nodes.files[path] = if (path == MimirTunerWrite.CpuQosNode) {
                    val merged = MimirTunerWrite.parseCpuQosRow(nodes.files[path].orEmpty()) +
                        MimirTunerWrite.parseCpuQosRow(value)
                    merged.keys.sorted().joinToString(" ") { "$it:${merged.getValue(it)}" }
                } else {
                    value
                }
            }
        }
        true
    }

    private fun cpuPolicy(
        id: Int = 7,
        available: List<Int> = listOf(595_200, 2_092_800, 2_956_800),
        current: Int? = 2_092_800,
    ) = MimirTunerPolicy(
        id = id,
        kind = MimirTunerPolicy.Kind.CPU,
        label = "PRIME",
        nodePath = "/sys/devices/system/cpu/cpufreq/policy$id",
        availableKhz = available,
        currentMaxKhz = current,
    )

    private fun gpuPolicy() = MimirTunerPolicy(
        id = GpuPolicyId,
        kind = MimirTunerPolicy.Kind.GPU,
        label = "GPU",
        nodePath = "/sys/class/kgsl/kgsl-3d0",
        availableKhz = listOf(124_800, 220_000, 295_000, 348_000, 401_000, 475_000, 550_000, 615_000, 680_000),
        currentMaxKhz = 680_000,
    )

    private fun thorNodes() = FakeNodes(
        mutableMapOf(
            MimirTunerWrite.CpuQosNode to thorQosRow,
            "/sys/devices/system/cpu/cpufreq/policy7/affected_cpus" to "7\n",
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to "2092800\n",
            "/sys/devices/system/cpu/cpufreq/policy3/affected_cpus" to "3 4 5 6\n",
            "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq" to "2054400\n",
        ),
    )

    /**
     * The Thor's real row, from this file's own log on 2026-09-03: `quiet` was applied by the daemon and
     * `captureOnce` recorded it verbatim as stock.
     */
    private val thorQuietRow =
        "0:1555200 1:1555200 2:1555200 3:2054400 4:2054400 5:2054400 6:2054400 7:1843200"

    /** Thor cluster ceilings, from each policy's own `scaling_available_frequencies` top entry. */
    private val thorCeilings = mapOf(
        0 to 2_016_000, 1 to 2_016_000, 2 to 2_016_000,
        3 to 2_707_200, 4 to 2_707_200, 5 to 2_707_200, 6 to 2_707_200,
        7 to 2_956_800,
    )

    @Test
    fun `a row captured while another writer holds a cap is corrected to the ceilings`() {
        // The exact defect, with the exact numbers it produced on the reference device.
        assertEquals(
            "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2956800",
            MimirTunerWrite.stockCpuRowWithoutHeldCaps(thorQuietRow, thorCeilings),
        )
    }

    @Test
    fun `a genuinely unheld row is returned untouched`() {
        // Real stock takes two shapes and BOTH must survive: INT_MAX ("nothing requested"), and CPUs
        // sitting AT their cluster ceiling -- which this object's header KDoc records as the Thor's
        // actual stock, six of eight cores. A "repair" that rewrote either would be inventing a change.
        val unlimited = "0:2147483647 1:2147483647 7:2147483647"
        assertEquals(unlimited, MimirTunerWrite.stockCpuRowWithoutHeldCaps(unlimited, thorCeilings))
        val atCeiling = "0:2016000 3:2707200 7:2956800"
        assertEquals(atCeiling, MimirTunerWrite.stockCpuRowWithoutHeldCaps(atCeiling, thorCeilings))
    }

    @Test
    fun `a cpu whose ceiling could not be read is left alone`() {
        // Guessing on a CPU whose table we could not read is how a repair becomes a write nobody asked
        // for. Fail closed: correct only what is provably held.
        assertEquals(
            "0:2016000 9:1000000",
            MimirTunerWrite.stockCpuRowWithoutHeldCaps("0:1555200 9:1000000", mapOf(0 to 2_016_000)),
        )
    }

    @Test
    fun `an unreadable or empty row is not invented`() {
        assertEquals("", MimirTunerWrite.stockCpuRowWithoutHeldCaps("", thorCeilings))
        assertEquals("garbage", MimirTunerWrite.stockCpuRowWithoutHeldCaps("garbage", thorCeilings))
    }

    @Test
    fun `capturing while a daemon cap is in force records stock, and withdraw gives the device back`() {
        // The end-to-end shape of the device defect: the daemon holds `quiet`, Mimir then requests its
        // own ceiling, and the withdrawal must NOT hand back quiet's values as though they were stock.
        val nodes = FakeNodes(
            files = mutableMapOf(
                MimirTunerWrite.CpuQosNode to thorQuietRow,
                "/sys/devices/system/cpu/cpufreq/policy7/affected_cpus" to "7\n",
                "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies" to
                    "595200 1843200 2092800 2956800\n",
                "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to "1843200\n",
            ),
            policyDirectories = listOf("/sys/devices/system/cpu/cpufreq/policy7"),
        )
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)
        val policy = cpuPolicy(id = 7, available = listOf(595_200, 1_843_200, 2_092_800, 2_956_800))

        MimirTunerWrite.requestCeiling(policy, 1_843_200, nodes, host)
        MimirTunerWrite.withdraw(nodes, host)

        // **Vacuity guard.** Without it this test could pass for the wrong reason -- if the fake never
        // held a cap, "restored to the ceiling" would be trivially true. The live row genuinely carried
        // quiet's 1843200 on CPU 7, which is what the pre-fix capture recorded verbatim and what the
        // withdrawal below would then have handed back as stock.
        assertEquals(1_843_200, MimirTunerWrite.parseCpuQosRow(thorQuietRow)[7])

        val restored = MimirTunerWrite.parseCpuQosRow(nodes.files.getValue(MimirTunerWrite.CpuQosNode))
        assertEquals(
            "withdraw handed back quiet's ceiling as though it were stock -- the exact regression this " +
                "guards. CPU 7 must return to its cluster ceiling, not to 1843200.",
            2_956_800,
            restored[7],
        )
    }

    /**
     * **Every test in this file is a device with NO daemon unless it says otherwise.**
     *
     * That is not a convenience: the direct arm is the half that has to stay byte-identical under the shared routing contract, and the way to prove it is to run the whole pre-existing
     * suite with the route explicitly pinned to DIRECT rather than with whatever a previous test left
     * in a process-scoped object. [MimirTunerDaemonRoute.reset] runs on both sides for the same reason
     * `forgetCapture` does — a process-scoped fact that leaks between tests makes every one of them
     * conditional on the order they ran in.
     */
    @Before fun clean() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
        MimirTunerDaemonRoute.presence = noDaemon
    }

    @After fun tidy() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
    }

    /** No Tweaker installed: the universal case, and the one this file's older tests all describe. */
    private val noDaemon = MimirTunerDaemonPresence(
        pairCanBind = false,
        apiVersion = null,
        profileSessionActive = null,
    )

    /** A Tweaker that takes the write. */
    private val v10Daemon = MimirTunerDaemonPresence(
        pairCanBind = true,
        apiVersion = MimirTunerDaemonRouteMinApiVersion,
        profileSessionActive = false,
    )

    /** A Tweaker too old to take the write, so the Tuner still writes directly — with the v9 record. */
    private val oldDaemon = MimirTunerDaemonPresence(
        pairCanBind = true,
        apiVersion = MimirTunerDaemonRouteMinApiVersion - 1,
        profileSessionActive = false,
    )

    /**
     * The daemon's answers, scripted.
     *
     * Counts every call, because what most of the routing tests actually assert is a NEGATIVE — the
     * host was never spoken to, or the daemon was never asked — and a fake that cannot say "nobody
     * called me" can only ever prove the positive half.
     */
    private class FakeActuator(
        val onApply: (MimirTunerPolicy.Kind, Int, Int) -> DaemonCeilingOutcome = { _, _, _ ->
            DaemonCeilingOutcome.Unavailable("not stubbed")
        },
        val onWithdraw: () -> DaemonCeilingOutcome = { DaemonCeilingOutcome.NothingHeld },
        val onQuery: () -> DaemonCeilingOutcome = { DaemonCeilingOutcome.NotHeld },
    ) : DaemonTunerActuator {
        val applied = mutableListOf<Triple<MimirTunerPolicy.Kind, Int, Int>>()
        var withdrawals = 0
        var queries = 0

        override fun apply(
            kind: MimirTunerPolicy.Kind,
            policyId: Int,
            ceilingKhz: Int,
        ): DaemonCeilingOutcome {
            applied += Triple(kind, policyId, ceilingKhz)
            return onApply(kind, policyId, ceilingKhz)
        }

        override fun withdraw(): DaemonCeilingOutcome {
            withdrawals += 1
            return onWithdraw()
        }

        override fun query(): DaemonCeilingOutcome {
            queries += 1
            return onQuery()
        }
    }

    /** A record store that answers one thing and counts what was done to it. */
    private class FakeRecord(
        private val answer: MimirTunerRecordRead = MimirTunerRecordRead.Absent,
    ) : MimirTunerRecordStore {
        val written = mutableListOf<MimirTunerSnapshot>()
        var cleared = 0

        /** Counted because "the v9 store was never consulted" is the assertion on the daemon route. */
        var reads = 0

        override fun read(): MimirTunerRecordRead {
            reads += 1
            return answer
        }
        override fun write(snapshot: MimirTunerSnapshot): Boolean {
            written += snapshot
            return true
        }
        override fun clear(): Boolean {
            cleared += 1
            return true
        }
    }

    /**
     * The OLD boolean, recovered from the typed outcome.
     *
     * The pins below were written against a `Boolean` and each one names a device behaviour that must
     * not move, so the boolean is recovered rather than the assertions rewritten: `Released` and
     * `NothingToRelease` are exactly the two answers that used to be `true`.
     */
    private fun MimirTunerWriteOutcome.gaveTheDeviceBack(): Boolean =
        this is MimirTunerWriteOutcome.Released || this is MimirTunerWriteOutcome.NothingToRelease

    /** The old `Int?`: what is IN FORCE after the write. */
    private fun MimirTunerWriteOutcome.inForceKhz(): Int? =
        (this as MimirTunerWriteOutcome.InForce).khz

    @Test
    fun `a cluster is capped on every core the kernel says belongs to it`() {
        // The node's rows are per-CPU while the player's choice is per-CLUSTER. Naming only the policy
        // id would leave the cluster's other cores at whatever they held, and a cluster running at two
        // ceilings is not a cap, it is a bug that looks like one.
        val perf = cpuPolicy(id = 3, available = listOf(499_200, 2_054_400, 2_707_200), current = 2_054_400)

        val command = MimirTunerWrite.cpuCeilingCommand(perf, 2_054_400, thorNodes())

        assertTrue(command.contains("3:2054400 4:2054400 5:2054400 6:2054400"))
        assertTrue(command.contains(MimirTunerWrite.CpuQosNode))
    }

    @Test
    fun `the GPU floor is widened BEFORE the ceiling is set, or the ceiling snaps back`() {
        // The kernel clamps max_pwrlevel so it can never name a slower level than min_pwrlevel. Set the
        // ceiling first and it silently reverts while the surface reports a change that never happened.
        val command = MimirTunerWrite.gpuCeilingCommand(gpuPolicy(), 401_000)

        val minAt = command.indexOf("min_pwrlevel")
        val maxAt = command.indexOf("max_pwrlevel")
        assertTrue("min_pwrlevel must be written", minAt >= 0)
        assertTrue("max_pwrlevel must be written", maxAt >= 0)
        assertTrue("the floor is widened first", minAt < maxAt)
        // 401000 is index 4 of nine ascending; level counts from the fastest, so 8 - 4 = 4.
        assertTrue(command.contains("echo '4' > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"))
        assertTrue(command.contains("echo '8' > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"))
    }

    @Test
    fun `nothing is ever chmodded, because a locked node outlives the app that locked it`() {
        // The published technique leaves the sysfs node 444 so nothing else can move it. It works, and
        // it leaves the device changed in a way only a reboot undoes -- invisible, and outliving us.
        val cpu = MimirTunerWrite.cpuCeilingCommand(cpuPolicy(), 2_092_800, thorNodes())
        val gpu = MimirTunerWrite.gpuCeilingCommand(gpuPolicy(), 401_000)

        assertFalse("no chmod on the CPU path", cpu.contains("chmod"))
        assertFalse("no chmod on the GPU path", gpu.contains("chmod"))
    }

    @Test
    fun `a request the hardware cannot honour is refused, never rounded into a write`() {
        // This is the only door a number can come through, so it is where safety lives: nothing reaches
        // the command builder that did not come out of a table the kernel published.
        val policy = cpuPolicy()

        assertEquals(2_956_800, MimirTunerWrite.nearestSupportedKhz(policy, 2_900_000))
        assertNull(MimirTunerWrite.nearestSupportedKhz(policy, 0))
        assertNull(MimirTunerWrite.nearestSupportedKhz(policy, -1))
        assertNull(MimirTunerWrite.nearestSupportedKhz(policy.copy(availableKhz = emptyList()), 1_000_000))
    }

    @Test
    fun `stock is captured once, before anything of ours is in force`() {
        // Capturing per-write would let the SECOND write record the first write's own cap as "stock",
        // and withdrawal would then restore the cap. Once, or the restore is a no-op that looks fine.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()

        MimirTunerWrite.requestCeiling(cpuPolicy(), 2_092_800, nodes) { sent += it; true }
        assertEquals(thorQosRow, MimirTunerWrite.captured())

        // The device now reports our cap where stock used to be...
        nodes.files[MimirTunerWrite.CpuQosNode] = "0:2016000 1:2016000 2:2016000 3:2707200 " +
            "4:2707200 5:2707200 6:2707200 7:595200"
        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes) { sent += it; true }

        // ...and the capture is still the row the device booted with.
        assertEquals("the capture must not drift onto our own cap", thorQosRow, MimirTunerWrite.captured())
    }

    @Test
    fun `withdrawing writes back exactly what the device had, not a constant`() {
        // The review argued stock is INT_MAX everywhere, so withdrawal could be a constant. The Thor
        // holds six CPUs at their cluster CEILING instead. Behaviourally that is still no cap -- which
        // is why the wrong answer would have looked right -- but writing INT_MAX would leave six CPUs
        // on a value the device did not boot with, and we could not then tell our change from anyone's.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)
        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, host)

        assertTrue(MimirTunerWrite.withdraw(nodes, host).gaveTheDeviceBack())

        val restore = sent.last()
        assertTrue("CPU 7 goes back to the ceiling it actually had", restore.contains("7:2956800"))
        assertFalse("and never to INT_MAX, which is not what this device held", restore.contains("7:2147483647"))
        assertEquals("the node ends byte-identical to stock", thorQosRow, nodes.files[MimirTunerWrite.CpuQosNode])
    }

    @Test
    fun `withdrawing leaves alone any CPU somebody else has moved since we wrote it`() {
        // The node HAS another writer -- CPU 7 was seen at INT_MAX at 16:44 and 2956800 at 17:14 with
        // no Mimir write between. A blind restore of the whole captured row would stamp our stale value
        // over theirs: Mimir undoing a change it never made and cannot see, while logging success.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        val perf = cpuPolicy(id = 3, available = listOf(499_200, 2_054_400, 2_707_200))
        MimirTunerWrite.requestCeiling(perf, 499_200, nodes) { sent += it; true }

        // We wrote 3,4,5,6 to 499200. Someone else then moves CPU 5 somewhere we never asked for.
        nodes.files[MimirTunerWrite.CpuQosNode] =
            "0:2016000 1:2016000 2:2016000 3:499200 4:499200 5:1900800 6:499200 7:2147483647"

        assertTrue(MimirTunerWrite.withdraw(nodes) { sent += it; true }.gaveTheDeviceBack())

        val restore = sent.last()
        assertTrue("CPUs still holding our cap go back to stock", restore.contains("3:2707200"))
        assertTrue(restore.contains("4:2707200"))
        assertTrue(restore.contains("6:2707200"))
        assertTrue("the CPU somebody else moved keeps THEIR value", restore.contains("5:1900800"))
        assertFalse("and never ours", restore.contains("5:2707200"))
    }

    @Test
    fun `a withdraw finding nothing of ours still in force writes nothing`() {
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes) { true }

        // The other writer has replaced our cap entirely. There is nothing of ours left to take back,
        // so speaking would be asserting a value nobody asked for.
        nodes.files[MimirTunerWrite.CpuQosNode] =
            "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2147483647"

        assertTrue(MimirTunerWrite.withdraw(nodes) { sent += it; true }.gaveTheDeviceBack())

        assertTrue("nothing of ours in force means nothing to say", sent.isEmpty())
        assertNull("and the capture is spent either way", MimirTunerWrite.captured())
    }

    @Test
    fun `withdrawing without ever having written says nothing at all`() {
        // A withdraw that asserted a default "just in case" would be a write dressed as a restore, and
        // on the Thor it would have moved six CPUs off their boot values on the way to doing nothing.
        val sent = mutableListOf<String>()

        assertTrue(MimirTunerWrite.withdraw { sent += it; true }.gaveTheDeviceBack())

        assertTrue("a restore with nothing to restore is silence", sent.isEmpty())
    }

    @Test
    fun `a failed withdraw keeps the capture, so the next attempt can still put it back`() {
        val nodes = thorNodes()
        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, mutableListOf()))

        assertFalse(MimirTunerWrite.withdraw(nodes) { false }.gaveTheDeviceBack())

        assertEquals("the only copy of stock must survive a failed restore", thorQosRow, MimirTunerWrite.captured())
    }

    @Test
    fun `cold start stays silent on a clean boot`() {
        // This runs on every launch. If it spoke when nothing was held it would be a privileged write
        // every time Mimir opened, which is exactly the kind of thing that becomes normal and then bites.
        val nodes = thorNodes()
        val policies = listOf(cpuPolicy(id = 7, available = listOf(595_200, 2_956_800)))
        val sent = mutableListOf<String>()

        val spoke = MimirTunerWrite.withdrawOnColdStart(policies, nodes) { sent += it; true }

        assertFalse("CPU 7 at INT_MAX is not a cap", spoke)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `cold start widens to the ceiling when it finds a cap it cannot explain`() {
        // Mimir died holding a cap and the player has not rebooted. There is no capture -- it died with
        // the process -- so the only safe value is the cluster's own published ceiling: requesting a
        // ceiling AT the ceiling restricts nothing and cannot overclock, because the silicon named it.
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] =
            "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:595200"
        val policies = listOf(cpuPolicy(id = 7, available = listOf(595_200, 2_956_800)))
        val sent = mutableListOf<String>()

        val spoke = MimirTunerWrite.withdrawOnColdStart(policies, nodes) { sent += it; true }

        assertTrue("a held cluster with no capture must be released", spoke)
        assertTrue(sent.single().contains("7:2956800"))
    }

    @Test
    fun `the surface is told what is IN FORCE, never what was asked for`() {
        // On the Thor these genuinely disagree: policy0's scaling_max_freq reads 1555200 while the QoS
        // node requests 2016000 for the same CPUs. Rendering the request as though it were the live
        // value is the claim section 4 bans, and it is the easiest one in this file to make by accident.
        val nodes = thorNodes()
        val policy = cpuPolicy(id = 3, available = listOf(499_200, 2_054_400, 2_707_200))

        val inForce = MimirTunerWrite.requestCeiling(policy, 2_707_200, nodes) { true }.inForceKhz()

        assertEquals("the read-back wins over the request", 2_054_400, inForce)
    }

    @Test
    fun `the cold-start release is wired, and wired BEFORE anything can capture`() {
        // A source pin because the defect was pure wiring, and behaviour tests cannot see it: every
        // withdrawOnColdStart test below passed while the function was never called by the app at all.
        //
        // What that cost, on the device, inside an hour: a previous Mimir process left CPUs 0-2 capped
        // and died. The next process took its capture and recorded THAT CAP AS STOCK, so the withdrawal
        // faithfully restored the cap and logged delivered=true. A restore that succeeds at restoring
        // the wrong value is worse than one that fails, because nothing anywhere says so.
        //
        // **Policy R2 (2026-09-01) moved the call, and this pin moved with it rather than being
        // relaxed.** The cold start now goes through `TweakerBridge.reconcileDiscoveredTunerColdStart`,
        // because the daemon may be holding the record of what this device was tuned FROM and widening
        // before asking would destroy the evidence an exact restore needs. The guarantee is unchanged
        // and the pin is stronger: the entry point must still run before anything can capture, AND the
        // safety action must be reachable on BOTH of that function's branches, so a device with no
        // daemon and a device whose daemon never answers both still release an orphaned cap.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val onCreate = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")

        val releaseAt = onCreate.indexOf("TweakerBridge.reconcileDiscoveredTunerColdStart()")
        assertTrue("onCreate must run the cold-start reconcile", releaseAt >= 0)

        // Ordering is the whole fix: the capture is only meaningful if nothing of ours is in force when
        // it is taken, so nothing that could reach requestCeiling may run first.
        val requestAt = onCreate.indexOf("MimirTunerWrite.requestCeiling")
        assertTrue(
            "the release must precede any path that can write a ceiling",
            requestAt < 0 || releaseAt < requestAt,
        )

        // The reconcile is only a redirection if it still gets there. Two call sites: the synchronous
        // one for a device that cannot bind a daemon, and the deferred one after the daemon has
        // answered or failed to. Losing either would silently reintroduce the orphaned-cap defect on
        // exactly one class of device, which is the hardest kind to notice.
        val bridge = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt").readText()
        val reconcile = bridge
            .substringAfter("fun reconcileDiscoveredTunerColdStart()")
            .substringBefore("private suspend fun readDiscoveredSessionAtBind")
        assertEquals(
            "both branches of the reconcile must still release an orphaned cap",
            2,
            Regex("MimirTunerWrite\\.releaseAnyOrphanedCap\\(\\)").findAll(reconcile).count(),
        )
    }

    @Test
    fun `a GPU ceiling can be taken back, floor and all`() {
        // **The gate caught this in shipped code: the GPU row offered a ceiling nothing could reverse.**
        // gpuCeilingCommand writes min_pwrlevel AND max_pwrlevel, while capture, withdraw and cold-start
        // all covered CPUs only. A control that claims a reversible change it cannot reverse is the
        // exact failure this whole file exists to prevent, and it shipped.
        val nodes = thorNodes()
        nodes.files["/sys/class/kgsl/kgsl-3d0/min_pwrlevel"] = "4\n"
        nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"] = "0\n"
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)

        MimirTunerWrite.requestCeiling(gpuPolicy(), 401_000, nodes, host)
        assertEquals("the cap is in force", "4", nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"])

        assertTrue(MimirTunerWrite.withdraw(nodes, host).gaveTheDeviceBack())

        assertEquals("the ceiling comes back", "0", nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"])
        assertEquals("and so does the floor", "4", nodes.files["/sys/class/kgsl/kgsl-3d0/min_pwrlevel"])
    }

    @Test
    fun `restoring the GPU raises the ceiling before it narrows the floor`() {
        // The reverse of the way out, and it has to be: applying widens the floor FIRST because the
        // kernel clamps max_pwrlevel against min_pwrlevel. Restoring in the same order would put the
        // floor back while the ceiling is still capped, and the floor would clamp the ceiling we are
        // about to restore -- a restore that reports success and leaves the GPU held.
        val nodes = thorNodes()
        nodes.files["/sys/class/kgsl/kgsl-3d0/min_pwrlevel"] = "4\n"
        nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"] = "0\n"
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)
        MimirTunerWrite.requestCeiling(gpuPolicy(), 401_000, nodes, host)

        MimirTunerWrite.withdraw(nodes, host)

        val restore = sent.last()
        assertTrue(
            "max_pwrlevel must be written before min_pwrlevel on the way back",
            restore.indexOf("max_pwrlevel") < restore.indexOf("min_pwrlevel"),
        )
    }

    @Test
    fun `a GPU somebody else has moved is left alone`() {
        val nodes = thorNodes()
        nodes.files["/sys/class/kgsl/kgsl-3d0/min_pwrlevel"] = "4\n"
        nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"] = "0\n"
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)
        MimirTunerWrite.requestCeiling(gpuPolicy(), 401_000, nodes, host)

        // Someone else moves the ceiling somewhere we never asked for.
        nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"] = "2"
        val before = sent.size

        assertTrue(MimirTunerWrite.withdraw(nodes, host).gaveTheDeviceBack())

        assertEquals("nothing of ours is in force, so nothing is written", before, sent.size)
        assertEquals("and their value stands", "2", nodes.files["/sys/class/kgsl/kgsl-3d0/max_pwrlevel"])
    }

    @Test
    fun `a QoS row parses per CPU and survives a row it cannot read`() {
        val parsed = MimirTunerWrite.parseCpuQosRow(thorQosRow)

        assertEquals(8, parsed.size)
        assertEquals(2_016_000, parsed[0])
        assertEquals(2_956_800, parsed[7])
        // INT_MAX is a real value this node takes -- it is the kernel's spelling of "no ceiling asked
        // for" -- so parsing must carry it rather than treat it as an overflow or a sentinel to drop.
        assertEquals(Int.MAX_VALUE, MimirTunerWrite.parseCpuQosRow(thorQosRowEarlier)[7])
        assertTrue(MimirTunerWrite.parseCpuQosRow("nonsense").isEmpty())
    }

    // ── The route (policy 2026-09-03, built 2026-09-11) ────────────────────────────────────

    /**
     * The device every other test in this file describes, stated once as a fact rather than assumed.
     *
     * Without this, "the direct path is unchanged" rests on the absence of a routing test rather than
     * on the presence of one, and the first person to install a presence in a shared setup would move
     * twenty pins at once without any of them saying so.
     */
    @Test
    fun `a device with no daemon takes the DIRECT route and writes the node itself`() {
        val nodes = thorNodes()
        val sent = mutableListOf<String>()

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))

        assertEquals(MimirTunerRouteDecision.Direct, outcome.route)
        assertTrue("the direct route speaks to the host", sent.single().contains("7:595200"))
        assertEquals(595_200, (outcome as MimirTunerWriteOutcome.InForce).admittedKhz)
    }

    @Test
    fun `the daemon route mirrors the daemon's record and never speaks to the host`() {
        // The ruling in one test: the daemon writes, and this process ends up able to answer the same
        // questions about the session that it could answer when it wrote the node itself.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        val daemonRecord = DaemonCeilingRecord(
            bootIdentity = "this-boot",
            cpuStockRow = thorQosRow,
            cpuRequested = mapOf(7 to 595_200),
        )
        val actuator = FakeActuator(
            onApply = { _, _, khz ->
                DaemonCeilingOutcome.Applied(
                    kind = MimirTunerPolicy.Kind.CPU,
                    policyId = 7,
                    requestedKhz = khz,
                    admittedKhz = khz,
                    inForceKhz = 1_555_200,
                    record = daemonRecord,
                )
            },
        )
        val store = FakeRecord()
        MimirTunerWrite.recordStore = store
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))
        val applied = outcome as MimirTunerWriteOutcome.InForce

        assertEquals(MimirTunerRouteDecision.Daemon, outcome.route)
        // IN FORCE is the daemon's read-back, which on this silicon is not the admitted value.
        assertEquals(1_555_200, applied.khz)
        assertEquals(595_200, applied.admittedKhz)
        assertTrue("the daemon owns the node; nothing of ours may reach the host", sent.isEmpty())
        assertEquals("and the node is untouched by us", thorQosRow, nodes.files[MimirTunerWrite.CpuQosNode])
        assertTrue("the mirror answers for the surface exactly as a direct write would", MimirTunerWrite.isHolding())
        assertEquals(thorQosRow, MimirTunerWrite.captured())
        assertEquals("the v9 record belongs to the daemon on this route", 0, store.written.size)
    }

    @Test
    fun `a request is snapped onto a published step BEFORE it crosses to the daemon`() {
        // The daemon re-admits every value against its own discovery, and that is not a reason to hand
        // it an unpublished one: `nearestSupportedKhz` is the single door a number comes through, and a
        // debug hook must not be able to route around it by being on a device that has a daemon.
        val nodes = thorNodes()
        val actuator = FakeActuator(
            onApply = { _, _, khz ->
                DaemonCeilingOutcome.Applied(
                    kind = MimirTunerPolicy.Kind.CPU,
                    policyId = 7,
                    requestedKhz = khz,
                    admittedKhz = khz,
                    inForceKhz = khz,
                    record = null,
                )
            },
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        MimirTunerWrite.requestCeiling(cpuPolicy(), 600_000, nodes) { true }

        assertEquals(
            "the daemon is asked for a step this device published, not for what adb typed",
            listOf(Triple(MimirTunerPolicy.Kind.CPU, 7, 595_200)),
            actuator.applied.toList(),
        )
    }

    @Test
    fun `a frequency this device never published is refused on either route, and written on neither`() {
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        val actuator = FakeActuator()
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 0, nodes, applyingHost(nodes, sent))

        assertEquals(
            MimirTunerRefusalUndiscoveredFrequency,
            (outcome as MimirTunerWriteOutcome.Refused).code,
        )
        assertTrue("nothing crosses to the daemon", actuator.applied.isEmpty())
        assertTrue("and nothing reaches the host", sent.isEmpty())
    }

    @Test
    fun `a daemon refusal writes nothing and holds nothing`() {
        // PROFILE_ACTIVE is the refusal a player can actually act on: the daemon is running a named
        // profile, which is the richer truth, and a ceiling written over it would be a second writer
        // again. Nothing may be captured here either -- a capture is a claim to hold something.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        val actuator = FakeActuator(
            onApply = { _, _, _ ->
                DaemonCeilingOutcome.Refused(MimirTunerRefusalProfileActive, "quiet is running")
            },
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))

        assertEquals(MimirTunerRefusalProfileActive, (outcome as MimirTunerWriteOutcome.Refused).code)
        assertEquals("quiet is running", outcome.detail)
        assertTrue("a refusal is not a fallback to writing the node ourselves", sent.isEmpty())
        assertFalse("and it leaves nothing claimed", MimirTunerWrite.isHolding())
        assertNull(MimirTunerWrite.captured())
    }

    @Test
    fun `a DAEMON route with no actuator installed is unavailable, never a direct write`() {
        // A wiring bug, not a device state. The dangerous failure would be falling back to writing the
        // node ourselves, because that recreates the two-writer state on precisely the devices the
        // ruling was written for.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        MimirTunerDaemonRoute.presence = v10Daemon

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))

        assertTrue(outcome is MimirTunerWriteOutcome.Unavailable)
        assertTrue(sent.isEmpty())
        assertFalse(MimirTunerWrite.isHolding())
    }

    @Test
    fun `an unresolved route neither writes nor withdraws`() {
        // The bridge has not looked yet. Nothing is known about who owns the node, and a write on a
        // guess is the one thing the table forbids outright.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        MimirTunerDaemonRoute.reset()

        val write = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))
        val back = MimirTunerWrite.withdraw(nodes, applyingHost(nodes, sent))

        assertEquals(MimirTunerRefusalRouteRefused, (write as MimirTunerWriteOutcome.Refused).code)
        assertEquals(MimirTunerRefusePairUnresolved, write.detail)
        assertEquals(MimirTunerRefusalRouteRefused, (back as MimirTunerWriteOutcome.Refused).code)
        assertTrue("not one command on an unresolved route", sent.isEmpty())
    }

    @Test
    fun `a daemon that answers BUSY refuses rather than letting the Tuner write around it`() {
        // pairCanBind with no API version: a Tweaker is installed and has not answered. The table calls
        // that a refusal, because writing here is exactly how the node came to have two writers.
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        MimirTunerDaemonRoute.presence = MimirTunerDaemonPresence(
            pairCanBind = true,
            apiVersion = null,
            profileSessionActive = null,
        )

        val outcome = MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, applyingHost(nodes, sent))

        assertEquals(MimirTunerRefuseDaemonBusy, (outcome as MimirTunerWriteOutcome.Refused).detail)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the daemon withdraws, and the mirror goes with it`() {
        val nodes = thorNodes()
        val sent = mutableListOf<String>()
        MimirTunerWrite.adoptDaemonRecord(
            DaemonCeilingRecord(
                bootIdentity = "this-boot",
                cpuStockRow = thorQosRow,
                cpuRequested = mapOf(7 to 595_200),
            ),
        )
        assertTrue("the mirror is the precondition, not the thing under test", MimirTunerWrite.isHolding())
        val actuator = FakeActuator(
            onWithdraw = {
                DaemonCeilingOutcome.Withdrawn(restoredCpus = 4, leftAloneCpus = 1, gpuRestored = true)
            },
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        val outcome = MimirTunerWrite.withdraw(nodes, applyingHost(nodes, sent))
        val released = outcome as MimirTunerWriteOutcome.Released

        assertEquals(1, actuator.withdrawals)
        assertEquals(4, released.restoredCpus)
        assertEquals("a CPU somebody else moved is not a failure, it is left alone", 1, released.leftAloneCpus)
        assertTrue(released.gpuRestored)
        assertFalse("the mirror is spent once the daemon says it holds nothing", MimirTunerWrite.isHolding())
        assertTrue("the restore is the daemon's write, never ours", sent.isEmpty())
    }

    @Test
    fun `a daemon holding nothing reports NOTHING TO RELEASE and still clears the mirror`() {
        val nodes = thorNodes()
        MimirTunerWrite.adoptDaemonRecord(
            DaemonCeilingRecord(
                bootIdentity = "this-boot",
                cpuStockRow = thorQosRow,
                cpuRequested = mapOf(7 to 595_200),
            ),
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = FakeActuator(onWithdraw = { DaemonCeilingOutcome.NothingHeld })

        val outcome = MimirTunerWrite.withdraw(nodes) { true }

        assertTrue(outcome is MimirTunerWriteOutcome.NothingToRelease)
        assertFalse(
            "a mirror of a session the daemon is not holding is a claim to nothing",
            MimirTunerWrite.isHolding(),
        )
    }

    @Test
    fun `a refused or unreachable withdrawal leaves the mirror exactly where it was`() {
        // Clearing on silence would report `isHolding() == false` while a cap is still on the node --
        // the one lie this file exists to prevent, arriving through the way back rather than the way out.
        val nodes = thorNodes()
        MimirTunerWrite.adoptDaemonRecord(
            DaemonCeilingRecord(
                bootIdentity = "this-boot",
                cpuStockRow = thorQosRow,
                cpuRequested = mapOf(7 to 595_200),
            ),
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = FakeActuator(
            onWithdraw = { DaemonCeilingOutcome.Unavailable("service died") },
        )

        val outcome = MimirTunerWrite.withdraw(nodes) { true }

        assertEquals("service died", (outcome as MimirTunerWriteOutcome.Unavailable).reason)
        assertTrue("still held, because nobody said otherwise", MimirTunerWrite.isHolding())
        assertEquals(thorQosRow, MimirTunerWrite.captured())
    }

    // ── Cold start, one branch at a time ─────────────────────────────────────────────────────────

    /** A cap in force with no capture: the shape every cold-start branch below is asked about. */
    private fun orphanedNodes(): FakeNodes = thorNodes().also {
        it.files[MimirTunerWrite.CpuQosNode] = "7:595200"
    }

    private fun coldStartPolicies() = listOf(cpuPolicy(id = 7, available = listOf(595_200, 2_956_800)))

    @Test
    fun `cold start on the daemon route adopts what the daemon holds, and widens nothing`() {
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        val store = FakeRecord()
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = FakeActuator(
            onQuery = {
                DaemonCeilingOutcome.Held(
                    DaemonCeilingRecord(
                        bootIdentity = "this-boot",
                        cpuStockRow = thorQosRow,
                        cpuRequested = mapOf(7 to 595_200),
                    ),
                )
            },
        )

        val widened = MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, store, applyingHost(nodes, sent))

        assertFalse("adoption is not a widen", widened)
        assertTrue("the cap the player set stays in force", sent.isEmpty())
        assertEquals("7:595200", nodes.files[MimirTunerWrite.CpuQosNode])
        assertTrue(MimirTunerWrite.isHolding())
        assertEquals("the v9 store is not even consulted on this route", 0, store.reads)
    }

    @Test
    fun `cold start on the daemon route leaves a cap the daemon owns alone`() {
        // NOT_HELD means the daemon holds nothing FOR US. The row in force is still the daemon's --
        // a profile, a thermal clamp, its own business -- and widening it would be Mimir overruling
        // the authoritative writer on the strength of a reading that names nobody.
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        val store = FakeRecord()
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = FakeActuator(onQuery = { DaemonCeilingOutcome.NotHeld })

        val widened = MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, store, applyingHost(nodes, sent))

        assertFalse(widened)
        assertTrue("no widen on the daemon route, ever", sent.isEmpty())
        assertEquals("7:595200", nodes.files[MimirTunerWrite.CpuQosNode])
        assertFalse(MimirTunerWrite.isHolding())
    }

    @Test
    fun `cold start on an unreachable daemon changes nothing and clears nothing`() {
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        val store = FakeRecord(MimirTunerRecordRead.Held(thorSnapshot()))
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = FakeActuator(onQuery = { DaemonCeilingOutcome.Unavailable("unbound") })

        val widened = MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, store, applyingHost(nodes, sent))

        assertFalse(widened)
        assertTrue(sent.isEmpty())
        assertFalse(MimirTunerWrite.isHolding())
        assertEquals("a transport that could not be asked cannot be cleared over", 0, store.cleared)
    }

    @Test
    fun `cold start with an unresolved route touches nothing at all`() {
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        val store = FakeRecord(MimirTunerRecordRead.Held(thorSnapshot()))
        MimirTunerDaemonRoute.reset()

        val widened = MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, store, applyingHost(nodes, sent))

        assertFalse(widened)
        assertTrue(sent.isEmpty())
        assertEquals("nothing is read on a route that does not know who owns the node", 0, store.reads)
        assertEquals(0, store.cleared)
        assertFalse(MimirTunerWrite.isHolding())
    }

    @Test
    fun `cold start does not widen a cap an old daemon is holding for a named profile`() {
        // The device fact behind this branch: `selectSystemProfileOnWorker` writes the whole eight-CPU
        // row during Mimir's own startup (instrumented 2026-09-01), and every CPU it caps then reads as
        // "held below its cluster ceiling" -- which is exactly what the orphan test looks for. Widening
        // would undo the player's profile at every launch and log it as a safety action.
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        MimirTunerDaemonRoute.presence = oldDaemon.copy(profileSessionActive = true)

        val widened = MimirTunerWrite.reconcileColdStart(
            coldStartPolicies(),
            nodes,
            FakeRecord(),
            applyingHost(nodes, sent),
        )

        assertFalse("a cap this process did not write is not ours to widen", widened)
        assertTrue(sent.isEmpty())
        assertEquals("7:595200", nodes.files[MimirTunerWrite.CpuQosNode])
    }

    @Test
    fun `cold start still widens an orphan when the old daemon is running no profile`() {
        // The vacuity guard for the test above: with the same node, the same policies and the same
        // record, only `profileSessionActive` differing, the safety action must still run -- otherwise
        // that test would pass on a reconcile that had simply stopped working.
        val nodes = orphanedNodes()
        val sent = mutableListOf<String>()
        MimirTunerDaemonRoute.presence = oldDaemon

        val widened = MimirTunerWrite.reconcileColdStart(
            coldStartPolicies(),
            nodes,
            FakeRecord(),
            applyingHost(nodes, sent),
        )

        assertTrue("an orphaned cap is still released on the direct route", widened)
        assertTrue(sent.single().contains("7:2956800"))
    }

    /** The snapshot the v9 store hands back; only its presence matters to the branches above. */
    private fun thorSnapshot() = MimirTunerSnapshot(
        bootIdentity = "some-other-boot",
        cpuStockRow = thorQosRow,
        cpuRequested = mapOf(7 to 595_200),
    )

    /** A Tweaker that is there and has not answered yet: the state every eligible device boots into. */
    private val busyDaemon = MimirTunerDaemonPresence(
        pairCanBind = true,
        apiVersion = null,
        profileSessionActive = null,
    )

    /** The daemon's held session, as the Odin 3 was holding one at 12:33 on 2026-09-11. */
    private fun heldByDaemon() = DaemonCeilingOutcome.Held(
        DaemonCeilingRecord(
            bootIdentity = "this-boot",
            cpuStockRow = thorQosRow,
            cpuRequested = mapOf(7 to 595_200),
        ),
    )

    /**
     * Drive a cold start that ends without the daemon ever being asked, exactly as the device did.
     *
     * `presence` is an eligible pair with no API version — the synchronous half of
     * `TweakerBridge.reconcileDiscoveredTunerColdStart` publishes precisely that — so the route is
     * REFUSE and the reconcile records `daemon_unresolved`.
     */
    private fun coldStartEndingUnresolved(): FakeNodes {
        val nodes = orphanedNodes()
        MimirTunerDaemonRoute.presence = busyDaemon
        MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, FakeRecord(), applyingHost(nodes, mutableListOf()))
        return nodes
    }

    @Test
    fun `a cold start that ended unresolved asks the daemon again once the route resolves`() {
        // **Odin 3, 2026-09-11: `branch=daemon_unresolved route=REFUSE` at 12:33:10.801, and
        // `decision=Daemon` at 12:33:11.068.** The query that adopts a held ceiling into the mirror
        // never ran, and nothing ran it later, so the surface reported no session while the daemon held
        // one for the life of the process. This is the exit.
        val nodes = coldStartEndingUnresolved()
        val actuator = FakeActuator(onQuery = { heldByDaemon() })
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        val ran = MimirTunerWrite.reconcileDaemonRouteDeferred()

        assertTrue("the deferred reconcile must actually run", ran)
        assertEquals("and it asks the daemon exactly once", 1, actuator.queries)
        assertTrue("the daemon's session is now mirrored in this process", MimirTunerWrite.isHolding())
        assertEquals("nothing is widened on this route, ever", "7:595200", nodes.files[MimirTunerWrite.CpuQosNode])
    }

    @Test
    fun `the deferred reconcile runs at most once per process`() {
        // The trigger is every daemon answer, and the standing projection refresh answers on a 15 s
        // cadence for as long as the re-arm watch is up. Without the latch this would be a Binder round
        // trip four times a minute, forever, to re-adopt a session it adopted the first time.
        coldStartEndingUnresolved()
        val actuator = FakeActuator(onQuery = { heldByDaemon() })
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        assertTrue(MimirTunerWrite.reconcileDaemonRouteDeferred())
        assertFalse("a second trigger is declined", MimirTunerWrite.reconcileDaemonRouteDeferred())
        assertFalse("and it does not even report itself owed", MimirTunerWrite.deferredDaemonRouteReconcileOwed())
        assertEquals(1, actuator.queries)
    }

    @Test
    fun `a daemon that could not be reached at cold start is asked again`() {
        // `daemon_unreachable` is the other half of the same hole: the cold start DID ask, the transport
        // was down, and the answer — deliberately — adopted nothing and cleared nothing. That state is
        // resolved by the next answer or by nothing at all.
        val nodes = orphanedNodes()
        val unreachable = FakeActuator(onQuery = { DaemonCeilingOutcome.Unavailable("unbound") })
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = unreachable
        MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, FakeRecord(), applyingHost(nodes, mutableListOf()))
        assertEquals("the cold start asked, and nothing answered", 1, unreachable.queries)

        // The daemon comes back — a restart, a finished restore, a bind that took two tries.
        val answering = FakeActuator(onQuery = { heldByDaemon() })
        MimirTunerDaemonRoute.actuator = answering

        assertTrue(MimirTunerWrite.reconcileDaemonRouteDeferred())
        assertEquals(1, answering.queries)
        assertTrue(MimirTunerWrite.isHolding())
    }

    @Test
    fun `a cold start that already asked the daemon is never asked again`() {
        // The vacuity guard for the three tests above: `daemon_owned` is the daemon's own word, and a
        // deferred re-ask on top of it would be this process second-guessing the authoritative writer — on no new evidence at all.
        val nodes = orphanedNodes()
        val actuator = FakeActuator(onQuery = { DaemonCeilingOutcome.NotHeld })
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator
        MimirTunerWrite.reconcileColdStart(coldStartPolicies(), nodes, FakeRecord(), applyingHost(nodes, mutableListOf()))

        assertFalse("nothing is owed after a finished answer", MimirTunerWrite.deferredDaemonRouteReconcileOwed())
        assertFalse(MimirTunerWrite.reconcileDaemonRouteDeferred())
        assertEquals("the cold start's own query, and no other", 1, actuator.queries)
    }

    @Test
    fun `the deferred reconcile never runs on the direct route`() {
        // A device with no daemon has nobody to ask, and an OLD daemon is a device where THIS process
        // is the writer. Either way a query would be a round trip to establish something the route has
        // already decided — and on the direct route the mirror it would write is this process's own.
        coldStartEndingUnresolved()
        val actuator = FakeActuator(onQuery = { heldByDaemon() })
        MimirTunerDaemonRoute.actuator = actuator

        MimirTunerDaemonRoute.presence = noDaemon
        assertFalse(MimirTunerWrite.reconcileDaemonRouteDeferred())
        MimirTunerDaemonRoute.presence = oldDaemon
        assertFalse(MimirTunerWrite.reconcileDaemonRouteDeferred())
        MimirTunerDaemonRoute.presence = busyDaemon
        assertFalse("nor on a route that still knows nothing", MimirTunerWrite.reconcileDaemonRouteDeferred())

        assertEquals("the daemon is never spoken to off the DAEMON route", 0, actuator.queries)
        assertFalse(MimirTunerWrite.isHolding())
    }

    @Test
    fun `the deferred reconcile declines while a request is in flight`() {
        // **This is the shape of the collision, not a hypothetical.** The bridge installs the route from
        // inside its own Binder round trip, so the trigger fires DURING somebody else's transaction —
        // and `apply` holds the exclusive mutation reservation while `query` wants the settlement one,
        // which `TweakerSessionReservation` refuses outright. The deferred reconcile would have spent
        // its single run on a guaranteed `daemon_unreachable`.
        coldStartEndingUnresolved()
        var deferredDuringApply: Boolean? = null
        val actuator = FakeActuator(
            onApply = { _, _, khz ->
                deferredDuringApply = MimirTunerWrite.reconcileDaemonRouteDeferred()
                DaemonCeilingOutcome.Applied(
                    kind = MimirTunerPolicy.Kind.CPU,
                    policyId = 7,
                    requestedKhz = khz,
                    admittedKhz = khz,
                    inForceKhz = khz,
                    record = null,
                )
            },
            onQuery = { heldByDaemon() },
        )
        MimirTunerDaemonRoute.presence = v10Daemon
        MimirTunerDaemonRoute.actuator = actuator

        MimirTunerWrite.requestCeiling(cpuPolicy(), 2_092_800, thorNodes()) { true }

        assertEquals("the write itself is untouched", 1, actuator.applied.size)
        assertEquals("a mid-press trigger is declined", false, deferredDuringApply)
        assertEquals("and it never reached the daemon", 0, actuator.queries)
        // Declined, not consumed: the run is still there for the next answer, which is the whole point
        // of asking the question again rather than latching on the attempt.
        assertTrue("the deferred run survives the decline", MimirTunerWrite.deferredDaemonRouteReconcileOwed())
    }

    @Test
    fun `the deferred line names itself, and only the deferred line does`() {
        // A source pin because the field IS the interface: a device capture with two `cold start
        // reconcile:` lines has to say which one was the recovery, and the ordinary line has to stay
        // byte-identical to the one the 2026-09-01 proof scripts wait on.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTunerWrite.kt").readText()

        assertTrue("the deferred run names itself", source.contains("\" deferred=true\""))
        assertFalse("and an ordinary cold start says nothing new", source.contains("deferred=false"))
        assertTrue(
            "the deferred entry point runs the daemon query path rather than a copy of it",
            source.contains("reconcileColdStartThroughDaemon(daemonStore = false, route = route, deferred = true)"),
        )
    }

    @Test
    fun `the bridge triggers the deferred reconcile when the daemon answers`() {
        // The trigger cannot be reached from a JVM test — `noteDaemonAnswered` is private, and reaching
        // it means a Context, a PackageManager and a Binder. So the wiring is pinned as source, the same
        // way the cold-start release above is: a behaviour test that cannot see the call site would pass
        // on a writer nothing calls, which is the exact defect this fix is for.
        val bridge = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt").readText()

        // Sliced to the BODY of each function, not to a span that happens to contain the other one:
        // a pin whose window included the trigger's own definition would go green on a bridge that
        // defined it and called it from nowhere, which is the defect shape exactly.
        val answered = bridge
            .substringAfter("private fun noteDaemonAnswered(")
            .substringBefore("private fun reconcileDaemonRouteDeferredIfOwed()")
        assertTrue(
            "the moment the route becomes DAEMON must ask whether a reconcile is owed",
            answered.contains("reconcileDaemonRouteDeferredIfOwed()"),
        )

        val trigger = bridge
            .substringAfter("private fun reconcileDaemonRouteDeferredIfOwed()")
            .substringBefore("private val daemonTunerActuator")
        assertTrue(
            "the trigger runs it off the caller's thread, on the bridge's own IO scope",
            trigger.contains("ownerScope.launch { MimirTunerWrite.reconcileDaemonRouteDeferred() }"),
        )
        assertTrue(
            "never while the cold start is still running: it is about to ask the same question",
            trigger.contains("if (discoveredColdStartPending.get()) return"),
        )

        // The second trigger closes the window from the other side: the route can resolve WHILE the
        // cold start is in flight, and that answer is suppressed by the guard above.
        val reconcile = bridge
            .substringAfter("fun reconcileDiscoveredTunerColdStart()")
            .substringBefore("private suspend fun readDiscoveredSessionAtBind")
        assertTrue(
            "the end of the cold start re-asks once its verdict is written down",
            reconcile.contains("discoveredColdStartPending.set(false)") &&
                reconcile.contains("reconcileDaemonRouteDeferredIfOwed()"),
        )
    }

    @Test
    fun `the cold-start line still names its branch, its store AND now its route`() {
        // A source pin, because the line is an interface: a device script greps it to know the cold
        // start finished and to know who was allowed to write. Losing the route field would leave
        // `branch=quiet` meaning two different things on two different devices, with nothing to tell
        // them apart in a capture.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirTunerWrite.kt").readText()

        assertTrue("branch is still named", source.contains("branch=\$branch"))
        assertTrue("the store is still named", source.contains("daemonStore=\$daemonStore"))
        assertTrue("and the route joins it", source.contains("route=\${mimirTunerRouteLabel(route)}"))
    }
}
