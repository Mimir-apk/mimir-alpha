package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerSessionContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Client half of the transaction-result and V4 session contract, completing the set alongside
 * `TweakerCapabilitiesBundleContractTest` and `TweakerTelemetryBundleContractTest`.
 *
 * Two client parsers consume overlapping subsets of the same wire shape --
 * [parseTweakerV4SessionBundle] for a session query and [TweakerApplyResult.fromBundle] for an
 * apply outcome -- so a key counts as consumed if EITHER reads it. This is the bundle that reports
 * whether a mutation actually landed, which makes a silently dropped key here the mechanism behind
 * a control that advertises what the layer beneath refused.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerSessionBundleContractTest {

    @Test
    fun `every contract key the client must consume actually changes a parse`() {
        val inert = TweakerSessionContract.CONSUMED_BY_CLIENT.filter { key -> !mattersAnywhere(key) }

        assertEquals(
            "contract says the client consumes these session keys, but dropping them changed " +
                "nothing in either parser -- the client stopped reading them, or the contract is wrong",
            emptyList<String>(),
            inert,
        )
    }

    @Test
    fun `keys the contract marks unread really are unread`() {
        val nowConsumed = TweakerSessionContract.NOT_CONSUMED_BY_CLIENT.filter { key -> mattersAnywhere(key) }

        assertEquals(
            "these are declared NOT_CONSUMED_BY_CLIENT but now affect a parse; move them to " +
                "CONSUMED_BY_CLIENT",
            emptyList<String>(),
            nowConsumed,
        )
    }

    /**
     * Consumed by EITHER parser, each against ITS OWN wire shape. These are not one bundle with
     * two readers: a session query must carry `code == SESSION_QUERY` and is required to claim
     * neither `applied` nor `restored`, while an apply result reports exactly that. Probing both
     * parsers against a single merged fixture makes the query parser reject wholesale, and then
     * nothing inside it appears to matter -- which is precisely what it reported before this was
     * split.
     */
    private fun mattersAnywhere(key: String): Boolean {
        val query = sessionQueryBundle()
        val result = applyResultBundle()
        return assess(query.withoutKey(key)) != assess(query) ||
            TweakerApplyResult.fromBundle(result.withoutKey(key)) !=
            TweakerApplyResult.fromBundle(result)
    }

    /**
     * The V4 parser validates the daemon's answer against what the caller asked for, so the
     * expectations must match the fixture -- otherwise every parse rejects for the same reason and
     * no key would appear to matter.
     */
    private fun assess(bundle: Bundle) = parseTweakerV4SessionBundle(
        bundle = bundle,
        expectedApiVersion = EXPECTED_API_VERSION,
        expectedCallerRequestId = EXPECTED_REQUEST_ID,
        expectedCallerDeadlineElapsedRealtimeMs = EXPECTED_DEADLINE_MS,
        observedAtElapsedRealtimeMs = 1_000L,
    )

    private fun Bundle.withoutKey(key: String): Bundle = Bundle(this).apply { remove(key) }

    /** An apply outcome: the plan-shaped keys are present and it reports a real mutation. */
    private fun applyResultBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putBoolean("applied", true)
        putBoolean("restored", false)
        putString("code", "OK")
        putString("message", "applied and verified")
        putString("profileId", "performance")
        putString("packageName", "com.futuremark.dmandroid.application")
        putString("requestId", EXPECTED_REQUEST_ID)
        putString("transactionId", "tx-7")
        putLong("generation", 43L)
        putBoolean("calibrated", true)
        putStringArrayList("cpuMaxKhz", arrayListOf("policy0=1996800", "policy6=2841600"))
        putInt("gpuMaxPowerLevel", 2)
        putInt("fanMode", 5)
        putLong("requestDeadlineElapsedRealtimeMs", EXPECTED_DEADLINE_MS)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", 901_000L)
    }

    /** A session query: fixed code, mutates nothing, and echoes the caller's identity exactly. */
    private fun sessionQueryBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "SESSION_QUERY")
        putString("message", "session truth")
        putString("requestId", EXPECTED_REQUEST_ID)
        putString("transactionId", "tx-7")
        putLong("generation", 43L)
        putLong("requestDeadlineElapsedRealtimeMs", EXPECTED_DEADLINE_MS)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", 901_000L)

        putString("sessionState", "ACTIVE")
        putString("activeProfileId", "performance")
        putInt("apiVersion", EXPECTED_API_VERSION)
        putBoolean("leaseRenewable", true)
        putBoolean("mutationMayBeActive", true)
        putString("sessionScope", "LAUNCH")
        putString("callerRequestId", EXPECTED_REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", EXPECTED_DEADLINE_MS)
        putLong("leaseDeadlineElapsedRealtimeMs", 901_000L)
    }

    private companion object {
        const val EXPECTED_API_VERSION = 8
        const val EXPECTED_REQUEST_ID = "req-7"
        const val EXPECTED_DEADLINE_MS = 31_000L
    }
}
