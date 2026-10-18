package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerFpsLimiterContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Client half of the FPS-limiter contract, fourth of the set.
 *
 * Four parsers, four wire shapes, each probed against its own fixture. They cannot share one:
 * apply and restore require the daemon to echo the caller's request id and deadline exactly, a
 * query answer must name the package that was asked about, and a capability answer carries neither.
 * A merged fixture would be rejected wholesale by whichever parser it did not fit, and every key
 * inside it would then look dead.
 */
@RunWith(RobolectricTestRunner::class)
class TweakerFpsLimiterBundleContractTest {

    @Test
    fun `every contract key the client must consume actually changes a parse`() {
        val inert = TweakerFpsLimiterContract.CONSUMED_BY_CLIENT.filter { key -> !mattersAnywhere(key) }

        assertEquals(
            "contract says the client consumes these FPS keys, but dropping them changed nothing " +
                "in any of the four parsers -- the client stopped reading them, or the contract is wrong",
            emptyList<String>(),
            inert,
        )
    }

    @Test
    fun `keys the contract marks unread really are unread`() {
        val nowConsumed = TweakerFpsLimiterContract.NOT_CONSUMED_BY_CLIENT.filter { key -> mattersAnywhere(key) }

        assertEquals(
            "these are declared NOT_CONSUMED_BY_CLIENT but now affect a parse; move them to " +
                "CONSUMED_BY_CLIENT",
            emptyList<String>(),
            nowConsumed,
        )
    }

    private fun mattersAnywhere(key: String): Boolean {
        val capability = capabilityBundle()
        val query = queryBundle()
        val apply = applyBundle()
        val restore = applyBundle()
        return parseCapability(capability.withoutKey(key)) != parseCapability(capability) ||
            parseQuery(query.withoutKey(key)) != parseQuery(query) ||
            parseApply(apply.withoutKey(key)) != parseApply(apply) ||
            parseRestore(restore.withoutKey(key)) != parseRestore(restore)
    }

    private fun parseCapability(bundle: Bundle) = parseTweakerFpsCapabilityBundle(
        bundle = bundle,
        expectedApiVersion = API,
        expectedPackageName = PACKAGE,
    )

    private fun parseQuery(bundle: Bundle) = parseTweakerFpsQueryBundle(
        bundle = bundle,
        expectedApiVersion = API,
        expectedPackageName = PACKAGE,
    )

    private fun parseApply(bundle: Bundle) = parseTweakerFpsApplyBundle(
        bundle = bundle,
        expectedApiVersion = API,
        attempt = TweakerFpsApplyAttempt(
            requestId = REQUEST_ID,
            requestDeadlineElapsedRealtimeMs = DEADLINE_MS,
            packageName = PACKAGE,
            targetFps = TARGET_FPS,
        ),
        observedAtElapsedRealtimeMs = 1_000L,
    )

    private fun parseRestore(bundle: Bundle) = parseTweakerFpsRestoreBundle(
        bundle = bundle,
        expectedApiVersion = API,
        owner = TweakerFpsOwner(
            requestId = REQUEST_ID,
            generation = 43L,
            transactionId = TRANSACTION_ID,
            packageName = PACKAGE,
            targetFps = TARGET_FPS,
        ),
        expectedDeadlineElapsedRealtimeMs = DEADLINE_MS,
        observedAtElapsedRealtimeMs = 1_000L,
    )

    private fun Bundle.withoutKey(key: String): Bundle = Bundle(this).apply { remove(key) }

    private fun capabilityBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_AVAILABLE")
        putInt("apiVersion", API)
        putString("packageName", PACKAGE)
        putIntegerArrayList("frameLimitsHz", arrayListOf(30, 40, TARGET_FPS))
    }

    /** A query answer that reports a limit actually held, so the owner block is populated. */
    private fun queryBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_APPLIED")
        putInt("apiVersion", API)
        putString("message", "limit held")
        putBoolean("mutationMayBeActive", true)
        putString("requestedPackageName", PACKAGE)
        putString("state", "ACTIVE")
        putString("ownerRequestId", REQUEST_ID)
        putString("packageName", PACKAGE)
        putInt("targetFps", TARGET_FPS)
        putLong("generation", 43L)
        putString("transactionId", TRANSACTION_ID)
        putString("phase", "APPLIED")
    }

    /** Apply and restore share a shape; both echo the caller's identity back exactly. */
    private fun applyBundle(): Bundle = Bundle().apply {
        putBoolean("success", true)
        putString("code", "FPS_LIMIT_APPLIED")
        putInt("apiVersion", API)
        putString("message", "limit applied")
        putBoolean("mutationMayBeActive", true)
        putBoolean("restoreDebtRetained", false)
        putString("callerRequestId", REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", DEADLINE_MS)
    }

    private companion object {
        const val API = TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION
        const val PACKAGE = "com.futuremark.dmandroid.application"
        const val REQUEST_ID = "req-7"
        const val TRANSACTION_ID = "tx-7"
        const val TARGET_FPS = 60
        const val DEADLINE_MS = 31_000L
    }
}
