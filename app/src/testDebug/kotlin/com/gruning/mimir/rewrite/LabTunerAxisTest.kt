package com.gruning.mimir.rewrite

import com.gruning.mimir.tweaker.TweakerLabTunerAxisContract
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The deterministic adoption proof, run entirely on the JVM against real files.
 *
 * **What is pinned here and what is deliberately NOT.** Everything below the Binder is real production
 * code driven through its ordinary entry points: `discoverTunerPolicies` parses a real
 * `scaling_available_frequencies`, `affectedCpuIds` reads a real `affected_cpus`, `captureOnce` reads
 * the axis before anything is written, `nearestSupportedKhz` snaps onto the published table,
 * `recordedSessionStillInForce` re-reads the file, and `reconcileColdStart` decides. What is device-only
 * and is not simulated here: process death actually destroying the `@Volatile` capture, the v9 bundles
 * crossing a real Binder between two uids, the record's durability in device-protected `AtomicFile`
 * across a force-stop of the DAEMON, the widen-wait ordering under real bind latency, the `sh -c` exec,
 * and `boot_id` being readable by both uids.
 *
 * **The in-memory record store here is a TEST HARNESS, not the proof.** This tree's recorded failure
 * R2.3a is that a test "proved" adoption by injecting a fake record store — "the one arrangement
 * production never builds" — and that is precisely why the DEVICE half of this proof uses the real
 * daemon over the real Binder. What these tests own is the half a device cannot iterate on cheaply: that
 * the reconcile makes the right decision given an answer. The half that proves the answer survives a
 * kill is on hardware, and neither substitutes for the other.
 */
class LabTunerAxisTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var axis: File

    /** A boot identity that cannot move under the test, so an adoption pin cannot flake. */
    private val bootIdentity = "6f0f1d2e-0000-4000-8000-aaaaaaaaaaaa"

    /**
     * Everything outside the lab directory is unreadable.
     *
     * On a device the delegate is `RealMimirTunerNodes`, which answers `/proc/sys/kernel/random/boot_id`
     * and refuses the rest. On the JVM that node does not exist, so `bootIdentity` would fall back to a
     * ten-second wall-clock bucket computed once at snapshot and again at reconcile — and a bucket
     * boundary landing between the two would fail an adoption pin for a reason that has nothing to do
     * with adoption. Pinning the identity here removes a flake, not a guard: the OTHER-BOOT branch is
     * exercised on its own below by naming a different identity outright.
     */
    private inner class OffDeviceNodes : MimirTunerNodes {
        override fun listCpuPolicyDirectories(): List<String> = emptyList()
        override fun read(path: String): String? =
            if (path == MimirTunerWrite.BootIdNode) bootIdentity else null
    }

    private fun nodes(): MimirTunerNodes = LabTunerNodes(axis, OffDeviceNodes())

    /**
     * The shell, with no shell.
     *
     * `LabTunerHost` runs its rewritten command through `/system/bin/sh -c` on a device, which does not
     * exist on a build machine and is explicitly out of scope for a JVM pin. This performs the same
     * redirect the shell would — truncate, write the row, terminate the line — so everything downstream
     * of the exec is still the real code reading a real file.
     */
    private val shellFreeExec: (String) -> Boolean = { command ->
        val row = command.substringAfter('\'').substringBeforeLast('\'')
        val path = command.substringAfterLast("> ").trim()
        File(path).writeText("$row\n")
        true
    }

    private fun host(): LabTunerHost = LabTunerHost(axis, shellFreeExec)

    private fun qosFile(): File = File(axis, TweakerLabTunerAxisContract.LAB_QOS_FILE_NAME)

    private fun qosRow(): String = qosFile().readText().trim()

    private class InMemoryRecordStore(private var answer: MimirTunerRecordRead) : MimirTunerRecordStore {
        var cleared = false
        var written: MimirTunerSnapshot? = null
        override fun read(): MimirTunerRecordRead = answer
        override fun write(snapshot: MimirTunerSnapshot): Boolean {
            written = snapshot
            answer = MimirTunerRecordRead.Held(snapshot)
            return true
        }

        override fun clear(): Boolean {
            cleared = true
            answer = MimirTunerRecordRead.Absent
            return true
        }
    }

    @Before
    fun seedAxis() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
        // The synthetic axis is a device with ONE writer, which is the whole reason it can certify
        // anything -- so the route is pinned to the arm where this process is that writer. A v10
        // daemon would (correctly) take the write away from the code this proof exists to exercise.
        MimirTunerDaemonRoute.presence = MimirTunerDaemonPresence(
            pairCanBind = false,
            apiVersion = null,
            profileSessionActive = null,
        )
        axis = temporaryFolder.newFolder(TweakerLabTunerAxisContract.LAB_AXIS_DIRECTORY_NAME)
        LabTunerAxisInstaller.seed(axis)
    }

    @After
    fun tidy() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
    }

    // ── The unarmed device is unchanged ──────────────────────────────────────────────────────────

    @Test
    fun `the backend's defaults are the real device, so an unarmed build behaves as it always did`() {
        // The one pin that says this whole seam costs a shipping device nothing: nothing in `src/main`
        // ever writes these fields, so on any build nobody has explicitly armed they are still
        // RealMimirTunerNodes and PServerHost. If this ever fails, a lab path has leaked into production.
        assertTrue(MimirTunerBackend.nodes() is RealMimirTunerNodes)
        assertFalse("no PServer exists off-device", MimirTunerBackend.hostAvailable())
        assertFalse("no privileged host exists off-device", MimirTunerBackend.host("echo 1 > /dev/null"))
    }

    // ── Discovery ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the armed axis discovers exactly one CPU policy and no GPU axis`() {
        val policies = discoverTunerPolicies(nodes())
        assertEquals(1, policies.size)
        val policy = policies.single()
        assertEquals(TweakerLabTunerAxisContract.LAB_POLICY_ID, policy.id)
        assertEquals(MimirTunerPolicy.Kind.CPU, policy.kind)
        assertEquals(listOf(600_000, 1_200_000, 1_800_000, 2_400_000), policy.availableKhz)
        assertEquals(TweakerLabTunerAxisContract.LAB_CEILING_KHZ, policy.ceilingKhz)
        // Seeded stock is INT_MAX, which the in-force file reflects as the published ceiling -- so a
        // freshly armed axis reads as UNCAPPED, exactly like a device at rest.
        assertEquals(TweakerLabTunerAxisContract.LAB_CEILING_KHZ, policy.currentMaxKhz)
        assertFalse(policy.capped)
    }

    @Test
    fun `the axis governs both of its cpus, read from the kernel's own affected_cpus`() {
        val policy = discoverTunerPolicies(nodes()).single()
        assertEquals(
            TweakerLabTunerAxisContract.cpuIds(),
            MimirTunerWrite.affectedCpuIds(policy, nodes()),
        )
    }

    @Test
    fun `the boot identity still comes from the real node, never from the lab axis`() {
        // The lab axis must not fake this. If it did, the OTHER-BOOT discard would be permanently
        // untestable on an armed device and a record could be adopted across a reboot.
        assertEquals(bootIdentity, MimirTunerWrite.bootIdentity(nodes()))
        assertNull(nodes().read("${MimirTunerWrite.GpuRoot}/devfreq/available_frequencies"))
    }

    // ── The host ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the lab host accepts exactly the QoS command the write path builds, and rewrites its path`() {
        val policy = discoverTunerPolicies(nodes()).single()
        val command = MimirTunerWrite.cpuCeilingCommand(
            policy,
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            nodes(),
        )
        val row = host().acceptedRow(command)
        assertEquals("240:1800000 241:1800000", row)
        assertEquals(
            "echo '240:1800000 241:1800000' > ${qosFile().absolutePath}",
            host().rewrite(row!!),
        )
    }

    @Test
    fun `the lab host refuses every command it does not own`() {
        // A lab host that ran whatever it was handed would be a worse thing to ship in a debug build
        // than the axis is worth. The GPU command in particular is a COMPOUND statement, which is how a
        // shape check that was not anchored would be walked past.
        val gpu = MimirTunerWrite.gpuCeilingCommand(
            MimirTunerPolicy(
                id = GpuPolicyId,
                kind = MimirTunerPolicy.Kind.GPU,
                label = "GPU",
                nodePath = MimirTunerWrite.GpuRoot,
                availableKhz = listOf(300_000, 600_000),
                currentMaxKhz = 600_000,
            ),
            600_000,
        )
        assertNull(host().acceptedRow(gpu))
        assertNull(host().acceptedRow("echo '240:1800000' > /etc/passwd"))
        assertNull(host().acceptedRow("cat ${MimirTunerWrite.CpuQosNode}"))
        assertNull(host().acceptedRow("echo 'rm -rf /' > ${MimirTunerWrite.CpuQosNode}"))
        assertNull(
            host().acceptedRow(
                "echo '240:1800000' > ${MimirTunerWrite.CpuQosNode}; echo x > /tmp/y",
            ),
        )
        assertFalse(host().run("echo '240:1800000' > /etc/passwd"))
    }

    // ── The whole loop ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cap survives the process that set it and is adopted, exactly, by the next one`() {
        val policy = discoverTunerPolicies(nodes()).single()

        // Process A: the player asks for a ceiling.
        val inForce = MimirTunerWrite.requestCeiling(
            policy,
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            nodes(),
            host()::run,
        )
        assertEquals(
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            (inForce as MimirTunerWriteOutcome.InForce).khz,
        )
        assertEquals("240:1800000 241:1800000", qosRow())
        assertEquals(TweakerLabTunerAxisContract.LAB_STOCK_ROW, MimirTunerWrite.captured())
        val recorded = MimirTunerWrite.snapshotOf(nodes())

        // Process A dies. The cap stays on the node; the capture does not survive with it.
        MimirTunerWrite.forgetCapture()
        assertFalse(MimirTunerWrite.isHolding())
        assertEquals("240:1800000 241:1800000", qosRow())

        // Process B: the daemon still holds the record, so cold start adopts rather than widens.
        val store = InMemoryRecordStore(MimirTunerRecordRead.Held(recorded))
        val widened = MimirTunerWrite.reconcileColdStart(
            discoverTunerPolicies(nodes()),
            nodes(),
            store,
            host()::run,
        )
        assertFalse("adoption must not widen", widened)
        assertFalse("an adopted record is not thrown away", store.cleared)
        assertTrue(MimirTunerWrite.isHolding())
        assertEquals("240:1800000 241:1800000", qosRow())

        // ...and the way back is now EXACT, which is the entire point of the record.
        assertTrue(MimirTunerWrite.withdraw(nodes(), host()::run) is MimirTunerWriteOutcome.Released)
        assertEquals(TweakerLabTunerAxisContract.LAB_STOCK_ROW, qosRow())
        assertFalse(MimirTunerWrite.isHolding())
    }

    @Test
    fun `a record whose cap a second writer removed is discarded, never adopted`() {
        val policy = discoverTunerPolicies(nodes()).single()
        MimirTunerWrite.requestCeiling(
            policy,
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            nodes(),
            host()::run,
        )
        val recorded = MimirTunerWrite.snapshotOf(nodes())
        MimirTunerWrite.forgetCapture()

        // The honest simulation of the thing the guard exists for: somebody else moved the node while
        // Mimir was not running. Adopting here would report a hold whose withdraw gives nothing back.
        qosFile().writeText(
            File(axis, TweakerLabTunerAxisContract.LAB_STOCK_ROW_FILE_NAME).readText(),
        )

        val store = InMemoryRecordStore(MimirTunerRecordRead.Held(recorded))
        val widened = MimirTunerWrite.reconcileColdStart(
            discoverTunerPolicies(nodes()),
            nodes(),
            store,
            host()::run,
        )
        assertFalse(MimirTunerWrite.isHolding())
        assertTrue("a discarded record must be forgotten", store.cleared)
        // Stock is INT_MAX, which is not below the published ceiling, so there is nothing orphaned to
        // widen and the reconcile speaks to the hardware not at all.
        assertFalse(widened)
        assertEquals(TweakerLabTunerAxisContract.LAB_STOCK_ROW, qosRow())
    }

    @Test
    fun `a record from another boot is discarded, and the orphaned cap is then widened`() {
        val policy = discoverTunerPolicies(nodes()).single()
        MimirTunerWrite.requestCeiling(
            policy,
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            nodes(),
            host()::run,
        )
        val previousBoot = MimirTunerWrite.snapshotOf(nodes()).copy(bootIdentity = "a-previous-boot")
        MimirTunerWrite.forgetCapture()

        val store = InMemoryRecordStore(MimirTunerRecordRead.Held(previousBoot))
        val widened = MimirTunerWrite.reconcileColdStart(
            discoverTunerPolicies(nodes()),
            nodes(),
            store,
            host()::run,
        )
        assertTrue("a cap with no usable record is orphaned and must be widened", widened)
        assertTrue(store.cleared)
        assertFalse(MimirTunerWrite.isHolding())
        // The third of the three distinct file states: not the cap, not exact stock, the ceiling.
        assertEquals("240:2400000 241:2400000", qosRow())
    }

    @Test
    fun `an orphaned cap with no record at all is widened to the published ceiling`() {
        val policy = discoverTunerPolicies(nodes()).single()
        MimirTunerWrite.requestCeiling(
            policy,
            TweakerLabTunerAxisContract.LAB_CAP_KHZ,
            nodes(),
            host()::run,
        )
        MimirTunerWrite.forgetCapture()

        val store = InMemoryRecordStore(MimirTunerRecordRead.Absent)
        val widened = MimirTunerWrite.reconcileColdStart(
            discoverTunerPolicies(nodes()),
            nodes(),
            store,
            host()::run,
        )
        assertTrue(widened)
        assertEquals("240:2400000 241:2400000", qosRow())
    }

    @Test
    fun `a clean axis makes the reconcile speak to nothing`() {
        val store = InMemoryRecordStore(MimirTunerRecordRead.Absent)
        val spoken = mutableListOf<String>()
        val widened = MimirTunerWrite.reconcileColdStart(
            discoverTunerPolicies(nodes()),
            nodes(),
            store,
        ) { command -> spoken += command; true }
        assertFalse(widened)
        assertEquals(emptyList<String>(), spoken)
        assertEquals(TweakerLabTunerAxisContract.LAB_STOCK_ROW, qosRow())
    }

    // ── The oracle ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the four outcomes are four distinct contents of one file`() {
        // Why the synthetic stock is INT_MAX rather than the table ceiling: a single `cat` on the device
        // has to distinguish adopted from withdrawn from widened without reading a log. If stock were
        // 2400000, "withdrawn" and "widened" would be the same bytes and the device script could not
        // tell a restore from a safety widen.
        val cap = "240:1800000 241:1800000"
        val stock = TweakerLabTunerAxisContract.LAB_STOCK_ROW
        val ceiling = "240:2400000 241:2400000"
        assertEquals(3, setOf(cap, stock, ceiling).size)
        assertEquals(Int.MAX_VALUE.toString(), stock.substringAfter("240:").substringBefore(' '))
    }
}
