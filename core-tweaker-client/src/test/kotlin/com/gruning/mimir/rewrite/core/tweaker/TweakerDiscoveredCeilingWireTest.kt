package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerDiscoveredCeilingContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The v10 ceiling wire, client half.
 *
 * **The property these exist to defend is not "the fields round-trip".** It is that no failure on this
 * wire can ever be read as *"the daemon holds nothing"*. A refusal, a version mismatch, an outcome this
 * build does not recognise and a bundle with no outcome at all are four different faults, and every one
 * of them has to arrive as `Unavailable` — because the caller's response to `NotHeld` is to widen the
 * clock back to the silicon's own ceiling, and doing that to a cap the daemon is still holding is the
 * exact cold-start collision measured on the Thor on 2026-09-11.
 *
 * The same rule, stated the other way: `NotHeld` and `NothingHeld` may be produced by exactly one input
 * each — the daemon saying so in the outcome field. `notHeldIsUnreachableFromEveryFailure` is the test
 * that actually asserts it; the individual cases below say what each fault looks like on the way there.
 *
 * Separate from `TweakerDiscoveredCeilingTest`, which pins the Utility ROW's discovered-ceiling state.
 * One is about what the panel shows, this is about what crossed the Binder; they share a noun and
 * nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerDiscoveredCeilingWireTest {

    private fun bundleOf(outcome: String, apiVersion: Int = 10, fill: Bundle.() -> Unit = {}) = Bundle().apply {
        putInt(TweakerDiscoveredCeilingContract.KEY_API_VERSION, apiVersion)
        putString(TweakerDiscoveredCeilingContract.KEY_OUTCOME, outcome)
        fill()
    }

    private fun Bundle.putRecord(
        boot: String = "boot-a",
        stock: String = "0:1804800 7:2956800",
        requested: String = "0:1804800 7:1843200",
        gpuStockMin: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
        gpuStockMax: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    ) {
        putString(TweakerDiscoveredCeilingContract.KEY_BOOT_IDENTITY, boot)
        putString(TweakerDiscoveredCeilingContract.KEY_CPU_STOCK_ROW, stock)
        putString(TweakerDiscoveredCeilingContract.KEY_CPU_REQUESTED_ROW, requested)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MIN_LEVEL, gpuStockMin)
        putInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MAX_LEVEL, gpuStockMax)
    }

    // ---------------------------------------------------------------------------------------------
    // The write half: apply and withdraw share one bundle shape and one parser.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an applied ceiling carries the daemon's admitted value, not ours`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED) {
            putInt(TweakerDiscoveredCeilingContract.KEY_AXIS, TWEAKER_CEILING_AXIS_CPU)
            putInt(TweakerDiscoveredCeilingContract.KEY_POLICY_ID, 7)
            putInt(TweakerDiscoveredCeilingContract.KEY_REQUESTED_KHZ, 1_800_000)
            // The daemon snapped 1.80 GHz onto the published table and clamped to the live floor.
            putInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ, 1_843_200)
            putInt(TweakerDiscoveredCeilingContract.KEY_IN_FORCE_KHZ, 1_843_200)
            putRecord()
        }

        val applied = parseTweakerDiscoveredCeilingWriteBundle(bundle, 10)
            as TweakerDiscoveredCeilingWrite.Applied

        assertEquals(7, applied.policyId)
        assertEquals(1_800_000, applied.requestedKhz)
        assertEquals(1_843_200, applied.admittedKhz)
        assertEquals(1_843_200, applied.inForceKhz)
        assertEquals("boot-a", applied.record?.bootIdentity)
    }

    /**
     * `inForceKhz` is the one value that is READ BACK rather than asked for, so `-1` is a real state —
     * *"the write went out and the node could not be read after it"* — and must not become a number.
     * Repeating `admittedKhz` here would let the row claim a frequency nothing confirmed.
     */
    @Test
    fun `an unreadable in-force frequency is null, never the admitted one`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED) {
            putInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ, 1_843_200)
            putInt(
                TweakerDiscoveredCeilingContract.KEY_IN_FORCE_KHZ,
                TweakerDiscoveredCeilingContract.UNREADABLE_KHZ,
            )
        }

        val applied = parseTweakerDiscoveredCeilingWriteBundle(bundle, 10)
            as TweakerDiscoveredCeilingWrite.Applied

        assertNull(applied.inForceKhz)
        assertEquals(1_843_200, applied.admittedKhz)
    }

    @Test
    fun `an applied reply that carries no record reports none`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED) {
            putInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ, 1_843_200)
        }

        val applied = parseTweakerDiscoveredCeilingWriteBundle(bundle, 10)
            as TweakerDiscoveredCeilingWrite.Applied

        assertNull("a record with no boot identity is no record", applied.record)
    }

    /**
     * `admittedKhz` is the one number the contract promises on this outcome, and the surface has to
     * render it. A reply without it would leave the row showing a frequency the client made up, so it
     * is reported as nothing having happened instead.
     */
    @Test
    fun `an applied ceiling with no admitted frequency is unavailable`() {
        listOf(
            bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED),
            bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED) {
                putInt(
                    TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ,
                    TweakerDiscoveredCeilingContract.UNREADABLE_KHZ,
                )
            },
        ).forEach { bundle ->
            assertTrue(
                parseTweakerDiscoveredCeilingWriteBundle(bundle, 10) is TweakerDiscoveredCeilingWrite.Unavailable,
            )
        }
    }

    /**
     * **A missing echo is null, not a default.** The contract names only `admittedKhz` and `inForceKhz`
     * as this outcome's payload, so axis, policy id and the requested frequency may simply be absent —
     * and `getInt(key, -1)` would have turned an absent policy id into a reply claiming the GPU was
     * capped. The caller substitutes what it asked for; the parser refuses to guess.
     */
    @Test
    fun `an applied reply that echoes nothing reports the echoes as absent`() {
        val applied = parseTweakerDiscoveredCeilingWriteBundle(
            bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED) {
                putInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ, 1_843_200)
            },
            10,
        ) as TweakerDiscoveredCeilingWrite.Applied

        assertNull(applied.axis)
        assertNull(applied.policyId)
        assertNull(applied.requestedKhz)
        assertEquals(1_843_200, applied.admittedKhz)
    }

    @Test
    fun `a withdrawal reports what it gave back and what it left alone`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_WITHDRAWN) {
            putInt(TweakerDiscoveredCeilingContract.KEY_RESTORED_CPUS, 6)
            putInt(TweakerDiscoveredCeilingContract.KEY_LEFT_ALONE_CPUS, 2)
            putBoolean(TweakerDiscoveredCeilingContract.KEY_GPU_RESTORED, true)
        }

        assertEquals(
            TweakerDiscoveredCeilingWrite.Withdrawn(restoredCpus = 6, leftAloneCpus = 2, gpuRestored = true),
            parseTweakerDiscoveredCeilingWriteBundle(bundle, 10),
        )
    }

    /**
     * **A count nobody sent must not read as zero.** `restoredCpus = 0` is a real outcome the daemon can
     * report — every CPU had moved since it wrote them, so it gave nothing back — and defaulting a
     * missing key to it would manufacture that finding out of a malformed reply. `-1` is the contract's
     * own spelling of absence.
     */
    @Test
    fun `a withdrawal that names no counts says so, rather than claiming zero`() {
        val withdrawn = parseTweakerDiscoveredCeilingWriteBundle(
            bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_WITHDRAWN),
            10,
        ) as TweakerDiscoveredCeilingWrite.Withdrawn

        assertEquals(TWEAKER_CEILING_UNCOUNTED, withdrawn.restoredCpus)
        assertEquals(TWEAKER_CEILING_UNCOUNTED, withdrawn.leftAloneCpus)
    }

    @Test
    fun `nothing held is the daemon answering, not a failure`() {
        assertEquals(
            TweakerDiscoveredCeilingWrite.NothingHeld,
            parseTweakerDiscoveredCeilingWriteBundle(
                bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_NOTHING_HELD),
                10,
            ),
        )
    }

    /**
     * A refusal is the daemon JUDGING the request and is a fact about what we asked; an unavailability
     * is the transport and is a fact about nothing. The route needs them apart: a refusal means the
     * daemon owns the node, and the safe response to the other is to refuse the write rather than take
     * the node back.
     */
    @Test
    fun `a named refusal keeps its code and detail`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_REFUSED) {
            putString(
                TweakerDiscoveredCeilingContract.KEY_REFUSAL_CODE,
                TweakerDiscoveredCeilingContract.REFUSAL_PROFILE_ACTIVE,
            )
            putString(TweakerDiscoveredCeilingContract.KEY_REFUSAL_DETAIL, "balanced is active")
        }

        assertEquals(
            TweakerDiscoveredCeilingWrite.Refused("PROFILE_ACTIVE", "balanced is active"),
            parseTweakerDiscoveredCeilingWriteBundle(bundle, 10),
        )
    }

    @Test
    fun `an unnamed refusal is still a refusal`() {
        val refused = parseTweakerDiscoveredCeilingWriteBundle(
            bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_REFUSED),
            10,
        ) as TweakerDiscoveredCeilingWrite.Refused

        assertEquals("UNNAMED_REFUSAL", refused.code)
    }

    @Test
    fun `a write reply from the wrong API version is unavailable`() {
        assertTrue(
            parseTweakerDiscoveredCeilingWriteBundle(
                bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_APPLIED, apiVersion = 9),
                10,
            ) is TweakerDiscoveredCeilingWrite.Unavailable,
        )
    }

    /**
     * A `HELD` on an apply, or a blank outcome: the daemon answering a question nobody asked is a daemon
     * whose answer cannot be trusted to mean what it says, so it is reported as nothing having happened
     * rather than guessed into the nearest case.
     */
    @Test
    fun `a write reply with an outcome this call cannot produce is unavailable`() {
        listOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD, "", "SOMETHING_NEW").forEach { outcome ->
            assertTrue(
                "outcome $outcome must not be guessed at",
                parseTweakerDiscoveredCeilingWriteBundle(bundleOf(outcome), 10)
                    is TweakerDiscoveredCeilingWrite.Unavailable,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The read half.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a held ceiling carries the whole record`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD) {
            putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, true)
            putRecord(gpuStockMin = 0, gpuStockMax = 3)
            putInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MIN_LEVEL, 0)
            putInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MAX_LEVEL, 2)
        }

        val held = parseTweakerDiscoveredCeilingReadBundle(bundle, 10) as TweakerDiscoveredCeilingRead.Held

        assertEquals("boot-a", held.record.bootIdentity)
        assertEquals("0:1804800 7:2956800", held.record.cpuStockRow)
        assertEquals("0:1804800 7:1843200", held.record.cpuRequestedRow)
        assertEquals(3, held.record.gpuStockMaxLevel)
        assertEquals(2, held.record.gpuRequestedMaxLevel)
    }

    @Test
    fun `not held is the daemon answering that it holds nothing`() {
        assertEquals(
            TweakerDiscoveredCeilingRead.NotHeld,
            parseTweakerDiscoveredCeilingReadBundle(
                bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_NOT_HELD),
                10,
            ),
        )
    }

    /**
     * Boot re-establishes the axis, so a record that cannot say which boot it belongs to is describing
     * a machine state that may no longer exist. Adopting it would stamp a stale row over whatever is
     * on the node now — and reporting it as `NotHeld` instead would widen a cap that IS still held.
     * Neither. Unavailable.
     */
    @Test
    fun `a held ceiling that names no boot is unavailable, not absent`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD) {
            putString(TweakerDiscoveredCeilingContract.KEY_CPU_REQUESTED_ROW, "7:1843200")
        }

        assertTrue(
            parseTweakerDiscoveredCeilingReadBundle(bundle, 10) is TweakerDiscoveredCeilingRead.Unavailable,
        )
    }

    @Test
    fun `a refused query keeps the refusal code in its reason`() {
        val bundle = bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_REFUSED) {
            putString(
                TweakerDiscoveredCeilingContract.KEY_REFUSAL_CODE,
                TweakerDiscoveredCeilingContract.REFUSAL_POSTURE_NOT_ADMITTED,
            )
        }

        val unavailable = parseTweakerDiscoveredCeilingReadBundle(bundle, 10)
            as TweakerDiscoveredCeilingRead.Unavailable

        assertTrue(
            "the code is the only thing that says WHY; losing it here loses it everywhere",
            unavailable.reason.contains("POSTURE_NOT_ADMITTED"),
        )
    }

    /** THE INVARIANT. Every way this wire can fail, and not one of them reaches `NotHeld`. */
    @Test
    fun `not held is unreachable from every failure`() {
        val failures = listOf(
            "version mismatch" to bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD, apiVersion = 9),
            "refused" to bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_REFUSED),
            "held with no boot" to bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD),
            "held=false" to bundleOf(TweakerDiscoveredCeilingContract.OUTCOME_HELD) {
                putBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, false)
                putString(TweakerDiscoveredCeilingContract.KEY_BOOT_IDENTITY, "boot-a")
            },
            "no outcome" to Bundle().apply {
                putInt(TweakerDiscoveredCeilingContract.KEY_API_VERSION, 10)
            },
            "unknown outcome" to bundleOf("A_LATER_DAEMONS_IDEA"),
        )

        failures.forEach { (name, bundle) ->
            assertTrue(
                "$name must be Unavailable: reading it as NotHeld widens a cap the daemon still holds",
                parseTweakerDiscoveredCeilingReadBundle(bundle, 10) is TweakerDiscoveredCeilingRead.Unavailable,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Which of the two writers the daemon currently is.
    // ---------------------------------------------------------------------------------------------

    private fun truth(state: TweakerV4SessionState, mutating: Boolean = false) = TweakerV4SessionTruth(
        apiVersion = 10,
        state = state,
        mutationMayBeActive = mutating,
        requestId = null,
        generation = null,
        transactionId = null,
        requestDeadlineElapsedRealtimeMs = null,
        leaseDeadlineElapsedRealtimeMs = null,
        hardLeaseDeadlineElapsedRealtimeMs = null,
        leaseRenewable = false,
    )

    /**
     * Stock with nothing in flight is the one state in which nothing of the daemon's is on the node.
     * Everything else — including `UNKNOWN`, and including a Stock with a mutation mid-flight — reads
     * as the daemon holding it, because the safe direction is to believe it rather than write across it.
     */
    @Test
    fun `only confirmed stock says no profile is running`() {
        assertEquals(
            false,
            namedProfileSessionActive(TweakerV4SessionAssessment.Stock(truth(TweakerV4SessionState.STOCK))),
        )
        assertEquals(
            true,
            namedProfileSessionActive(TweakerV4SessionAssessment.Blocked(truth(TweakerV4SessionState.ACTIVE))),
        )
        assertEquals(
            true,
            namedProfileSessionActive(TweakerV4SessionAssessment.Blocked(truth(TweakerV4SessionState.UNKNOWN))),
        )
    }

    /** Uncertain is *"could not be established"* and is never folded into either answer. */
    @Test
    fun `an uncertain session and an unasked one are both null`() {
        assertNull(namedProfileSessionActive(TweakerV4SessionAssessment.Uncertain("not bound")))
        assertNull(namedProfileSessionActive(null))
    }
}
