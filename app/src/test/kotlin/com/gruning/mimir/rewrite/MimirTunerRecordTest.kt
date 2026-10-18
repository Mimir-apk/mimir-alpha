package com.gruning.mimir.rewrite

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The daemon-held record, and what a cold start does with it .
 *
 * **What these tests are actually defending.** Before R2 the capture lived in `@Volatile` process
 * memory, so killing Mimir with a cap held destroyed the only record of what the device had been
 * holding, and the best a relaunch could do was widen every CPU to the ceiling the silicon publishes —
 * safe, but not the player's value. The record moves that knowledge into the daemon. The WRITE does
 * not move: every command in these tests is still built and sent by `MimirTunerWrite`.
 *
 * The fixtures are the AYN Thor's real state, captured 2026-08-31, for the same reason
 * `MimirTunerWriteTest` uses them: this silicon has already falsified one tidy premise about itself.
 */
class MimirTunerRecordTest {

    private val thorQosRow =
        "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2956800"

    private val thisBoot = "6f0f1d2e-0000-4000-8000-aaaaaaaaaaaa"
    private val anotherBoot = "0b0b0b0b-1111-4111-8111-bbbbbbbbbbbb"

    private class FakeNodes(
        val files: MutableMap<String, String> = mutableMapOf(),
    ) : MimirTunerNodes {
        override fun listCpuPolicyDirectories(): List<String> = emptyList()
        override fun read(path: String): String? = files[path]
    }

    /** Records what the store was asked to do, so a test can assert bookkeeping as well as writes. */
    private class FakeRecord(
        var answer: MimirTunerRecordRead = MimirTunerRecordRead.Absent,
    ) : MimirTunerRecordStore {
        val written = mutableListOf<MimirTunerSnapshot>()
        var cleared = 0
        override fun read(): MimirTunerRecordRead = answer
        override fun write(snapshot: MimirTunerSnapshot): Boolean {
            written += snapshot
            return true
        }
        override fun clear(): Boolean {
            cleared += 1
            return true
        }
    }

    private fun applyingHost(nodes: FakeNodes, sent: MutableList<String>): (String) -> Boolean = { command ->
        sent += command
        command.split(';').forEach { statement ->
            val value = statement.substringAfter('\'', "").substringBeforeLast('\'', "")
            val path = statement.substringAfterLast("> ").trim()
            if (path.isNotEmpty() && statement.contains('>')) {
                nodes.files[path] = if (path == MimirTunerWrite.CpuQosNode) {
                    val merged = MimirTunerWrite.parseCpuQosRow(nodes.files[path].orEmpty()) +
                        MimirTunerWrite.parseCpuQosRow(value)
                    MimirTunerWrite.formatCpuQosRow(merged)
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

    private fun thorNodes() = FakeNodes(
        mutableMapOf(
            MimirTunerWrite.CpuQosNode to thorQosRow,
            MimirTunerWrite.BootIdNode to thisBoot,
            "/sys/devices/system/cpu/cpufreq/policy7/affected_cpus" to "7\n",
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to "2092800\n",
        ),
    )

    /**
     * **These are all V9-daemon devices, and that is now something the setup has to SAY.**
     *
     * The record store being a daemon's is what every test below is about; under the routing ruling of
     * 2026-09-03 it is also, separately, a fact about who may write the node. A Tweaker that answers
     * the v9 record but predates the v10 ceiling API routes DIRECT — the Tuner still writes, still
     * captures, still keeps the record — which is exactly the arrangement these tests describe. Left
     * unset, the route would be REFUSE (nothing is known about the pair) and every reconcile here would
     * be asserting the behaviour of a device that has not finished starting up.
     */
    @Before fun clean() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
        MimirTunerDaemonRoute.presence = MimirTunerDaemonPresence(
            pairCanBind = true,
            apiVersion = MimirTunerDaemonRouteMinApiVersion - 1,
            profileSessionActive = false,
        )
    }

    @After fun tidy() {
        MimirTunerWrite.forgetCapture()
        MimirTunerDaemonRoute.reset()
    }

    @Test
    fun `the record is upserted on every delivered write, not only on the first capture`() {
        // A stepped session moves `requestedCpus`. A record still naming the FIRST step would, after a
        // kill, treat this process's own second write as somebody else's and decline to restore it --
        // the ours-check defeating itself with stale evidence.
        val nodes = thorNodes()
        val record = FakeRecord()
        MimirTunerWrite.recordStore = record
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)

        MimirTunerWrite.requestCeiling(cpuPolicy(), 2_092_800, nodes, host)
        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, host)

        assertEquals("both writes are recorded", 2, record.written.size)
        assertEquals(
            "the record tracks the LATEST request",
            mapOf(7 to 595_200),
            record.written.last().cpuRequested,
        )
        assertEquals(
            "the stock capture never drifts onto our own cap",
            thorQosRow,
            record.written.last().cpuStockRow,
        )
        assertEquals(thisBoot, record.written.last().bootIdentity)
    }

    @Test
    fun `withdraw clears the record once nothing is held`() {
        val nodes = thorNodes()
        val record = FakeRecord()
        MimirTunerWrite.recordStore = record
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)

        MimirTunerWrite.requestCeiling(cpuPolicy(), 595_200, nodes, host)
        assertTrue(MimirTunerWrite.isHolding())
        MimirTunerWrite.withdraw(nodes, host)

        assertFalse("nothing is held after a withdraw", MimirTunerWrite.isHolding())
        assertTrue("a record outliving its session is the stale capture R2 exists to end", record.cleared > 0)
    }

    @Test
    fun `a cold start with a held record ADOPTS it and leaves the cap in force`() {
        // The gate's middle clause: relaunch and the surface shows the same in-force number. A restart
        // that withdrew by itself would be a control acting while nobody touched it.
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val record = FakeRecord(
            MimirTunerRecordRead.Held(
                MimirTunerSnapshot(
                    bootIdentity = thisBoot,
                    cpuStockRow = thorQosRow,
                    cpuRequested = mapOf(7 to 595_200),
                ),
            ),
        )
        val sent = mutableListOf<String>()

        MimirTunerWrite.reconcileColdStart(
            listOf(cpuPolicy()),
            nodes,
            record,
            applyingHost(nodes, sent),
        )

        assertTrue("the session is adopted", MimirTunerWrite.isHolding())
        assertEquals("the cap is NOT widened away", emptyList<String>(), sent)
        assertEquals("7:595200", nodes.files[MimirTunerWrite.CpuQosNode])
        assertEquals("an adopted record is not cleared", 0, record.cleared)
    }

    @Test
    fun `an adopted session withdraws to the EXACT stock value, not to the ceiling`() {
        // This is the whole product payoff of R2. Before it, a relaunch could only widen CPU 7 to
        // 2956800 -- its cluster ceiling. The Thor's real stock for CPU 7 was 2956800 at 17:14 but
        // 2147483647 half an hour earlier, so "the ceiling" and "what it had" are demonstrably not the
        // same number on this device.
        val nodes = thorNodes()
        val stockWithIntMax =
            "0:2016000 1:2016000 2:2016000 3:2707200 4:2707200 5:2707200 6:2707200 7:2147483647"
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val record = FakeRecord(
            MimirTunerRecordRead.Held(
                MimirTunerSnapshot(
                    bootIdentity = thisBoot,
                    cpuStockRow = stockWithIntMax,
                    cpuRequested = mapOf(7 to 595_200),
                ),
            ),
        )
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)

        MimirTunerWrite.reconcileColdStart(listOf(cpuPolicy()), nodes, record, host)
        MimirTunerWrite.withdraw(nodes, host)

        assertEquals(
            "the captured stock returns exactly",
            2_147_483_647,
            MimirTunerWrite.parseCpuQosRow(nodes.files.getValue(MimirTunerWrite.CpuQosNode))[7],
        )
    }

    @Test
    fun `a record from another boot is discarded, and the cold start widens instead`() {
        // Boot re-establishes the axis, so a record that outlived a reboot describes a machine state
        // that no longer exists. The ours-check would defeat most stale adoptions by coincidence; a
        // coincidence is not a guard.
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val record = FakeRecord(
            MimirTunerRecordRead.Held(
                MimirTunerSnapshot(
                    bootIdentity = anotherBoot,
                    cpuStockRow = thorQosRow,
                    cpuRequested = mapOf(7 to 595_200),
                ),
            ),
        )
        val sent = mutableListOf<String>()

        MimirTunerWrite.reconcileColdStart(
            listOf(cpuPolicy()),
            nodes,
            record,
            applyingHost(nodes, sent),
        )

        assertFalse("a stale record is never adopted", MimirTunerWrite.isHolding())
        assertTrue("the stale record is dropped", record.cleared > 0)
        assertTrue("the orphaned cap is still widened", sent.any { it.contains("2956800") })
    }

    @Test
    fun `the daemon answering ABSENT keeps exactly the old cold-start behaviour`() {
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val record = FakeRecord(MimirTunerRecordRead.Absent)
        val sent = mutableListOf<String>()

        MimirTunerWrite.reconcileColdStart(
            listOf(cpuPolicy()),
            nodes,
            record,
            applyingHost(nodes, sent),
        )

        assertTrue("an orphan with no record is widened", sent.any { it.contains("2956800") })
        assertEquals("nothing to forget", 0, record.cleared)
    }

    @Test
    fun `an UNAVAILABLE daemon still widens, and does NOT try to clear what it could not read`() {
        // The safety action is never skipped because a transport was silent -- that would leave a cap in
        // force on the strength of a timeout.
        //
        // **But it must not clear either, and that is a correction.** A transport that could not be
        // queried cannot be cleared over; and a store that answers Unavailable on a LATER read is not
        // saying the session ended, so a clear here would forget a session that is still live. The
        // staleness it was meant to prevent is caught at adoption instead -- see the test below, which
        // is the guard that actually holds.
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val record = FakeRecord(MimirTunerRecordRead.Unavailable)
        val sent = mutableListOf<String>()

        MimirTunerWrite.reconcileColdStart(
            listOf(cpuPolicy()),
            nodes,
            record,
            applyingHost(nodes, sent),
        )

        assertTrue("the safety action still runs", sent.any { it.contains("2956800") })
        assertEquals("an unreadable transport is never issued a clear", 0, record.cleared)
    }

    @Test
    fun `a record whose cap is no longer in force is discarded, never adopted`() {
        // **A record can outlive the cap it describes**: the clear crosses a Binder and can fail, and the
        // cold-start widen deliberately does not depend on one having succeeded. Adopting such a record
        // would make `isHolding()` true with nothing held -- the companion surface would offer a withdraw
        // (`MimirCompanionPresentation.kt:439`) that gives nothing back when pressed, which is a control
        // claiming to hold what it does not.
        val nodes = thorNodes()
        // The node is at its stock row: nothing Mimir asked for is on the hardware any more.
        val record = FakeRecord(
            MimirTunerRecordRead.Held(
                MimirTunerSnapshot(
                    bootIdentity = thisBoot,
                    cpuStockRow = thorQosRow,
                    cpuRequested = mapOf(7 to 595_200),
                ),
            ),
        )
        val sent = mutableListOf<String>()

        MimirTunerWrite.reconcileColdStart(
            listOf(cpuPolicy()),
            nodes,
            record,
            applyingHost(nodes, sent),
        )

        assertFalse("nothing is adopted", MimirTunerWrite.isHolding())
        assertTrue("and the stale record is dropped", record.cleared > 0)
    }

    @Test
    fun `a late adoption prefers the daemon's stock over a capture taken while a cap was in force`() {
        // The race: an eligible device whose binding is still resolving when the player moves a stepper.
        // This process captures the PREVIOUS session's cap and would record it as stock -- the exact bug
        // `releaseAnyOrphanedCap` exists to prevent, arriving by a different road. The daemon's stock was
        // taken when nothing of ours was in force, so it wins.
        val nodes = thorNodes()
        nodes.files[MimirTunerWrite.CpuQosNode] = "7:595200"
        val sent = mutableListOf<String>()
        val host = applyingHost(nodes, sent)

        // The contaminated capture happens first, with no record store bound yet.
        MimirTunerWrite.requestCeiling(cpuPolicy(), 2_092_800, nodes, host)
        assertEquals("7:595200", MimirTunerWrite.captured())

        MimirTunerWrite.adoptRecordedSession(
            MimirTunerSnapshot(
                bootIdentity = thisBoot,
                cpuStockRow = thorQosRow,
                cpuRequested = mapOf(3 to 2_707_200),
            ),
        )

        assertEquals("the daemon's clean stock replaces the contaminated one", thorQosRow, MimirTunerWrite.captured())
    }
}
