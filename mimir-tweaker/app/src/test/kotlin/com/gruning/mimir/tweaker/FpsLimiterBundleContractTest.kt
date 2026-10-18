package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Daemon half of the FPS-limiter contract, fourth of the set.
 *
 * All four builders in `FpsLimiterPublicBundles.kt` are pure, so each is called directly and held
 * to [TweakerFpsLimiterContract]. The record-bearing branches -- an applied limit, or a query
 * answer carrying the owner block -- write their keys through `putFpsRecord`, and those keys are
 * pinned on the client side, where a consumer dropping one is what actually causes damage. What is
 * pinned HERE is the thing only this side can see: that no builder invents a key the contract has
 * never heard of, and that the three always-written keys survive every branch, including the
 * failure branches a caller has to be able to render.
 */
@RunWith(RobolectricTestRunner::class)
class FpsLimiterBundleContractTest {

    @Test
    fun `no builder emits a key the contract has never heard of`() {
        val declared = TweakerFpsLimiterContract.ALL.toSet()

        assertEquals("capability", emptySet<String>(), capabilityKeys() - declared)
        assertEquals("capability (unsupported)", emptySet<String>(), unsupportedCapabilityKeys() - declared)
        assertEquals("apply", emptySet<String>(), applyKeys() - declared)
        assertEquals("restore", emptySet<String>(), restoreKeys() - declared)
        assertEquals("query", emptySet<String>(), queryKeys() - declared)
    }

    @Test
    fun `every branch keeps the always-written keys`() {
        val always = TweakerFpsLimiterContract.ALWAYS.toSet()

        listOf(
            "capability" to capabilityKeys(),
            "capability (unsupported)" to unsupportedCapabilityKeys(),
            "apply" to applyKeys(),
            "restore" to restoreKeys(),
            "query" to queryKeys(),
        ).forEach { (name, keys) ->
            assertTrue("$name dropped one of $always: it emitted $keys", keys.containsAll(always))
        }
    }

    @Test
    fun `an unsupported package still carries the frame-limit key, and that is safe`() {
        // The builder writes frameLimitsHz unconditionally -- an EMPTY list when the package has
        // no limits. That would be dangerous if the client read it, since an empty list and "no
        // answer" are different claims. It does not: parseTweakerFpsCapabilityBundle checks
        // success first and returns null on FPS_LIMIT_UNAVAILABLE before the list is ever touched.
        // Pinned so that ordering stays deliberate rather than incidental.
        assertTrue(capabilityKeys().contains("frameLimitsHz"))
        assertTrue(unsupportedCapabilityKeys().contains("frameLimitsHz"))
        assertTrue(unsupportedCapabilityKeys().containsAll(TweakerFpsLimiterContract.ALWAYS))
    }

    @Test
    fun `every declared FPS key is classified for the client exactly once`() {
        val consumed = TweakerFpsLimiterContract.CONSUMED_BY_CLIENT.toSet()
        val notConsumed = TweakerFpsLimiterContract.NOT_CONSUMED_BY_CLIENT.toSet()

        assertEquals(emptySet<String>(), consumed intersect notConsumed)
        assertEquals(TweakerFpsLimiterContract.ALL.toSet(), consumed + notConsumed)
    }

    private fun capabilityKeys() = fpsCapabilityBundle(
        apiVersion = API,
        packageName = PACKAGE,
        capability = GameModeFpsCapability(
            packageIdentity = identity(),
            frameLimitsHz = listOf(30, 40, 60),
        ),
    ).keySet()

    private fun unsupportedCapabilityKeys() =
        fpsCapabilityBundle(apiVersion = API, packageName = PACKAGE, capability = null).keySet()

    private fun applyKeys() = fpsApplyBundle(
        apiVersion = API,
        callerRequestId = REQUEST_ID,
        callerDeadlineElapsedRealtimeMs = DEADLINE_MS,
        result = FpsLimiterApplyResult.Rejected("FPS_LIMIT_UNAVAILABLE"),
    ).keySet()

    private fun restoreKeys() = fpsRestoreBundle(
        apiVersion = API,
        callerRequestId = REQUEST_ID,
        callerDeadlineElapsedRealtimeMs = DEADLINE_MS,
        result = FpsLimiterRestoreResult.Retained("FPS_LIMIT_RESTORE_RETAINED"),
    ).keySet()

    private fun queryKeys() = fpsQueryBundle(
        apiVersion = API,
        requestedPackageName = PACKAGE,
        terminalTruth = FpsLimiterTerminalTruth(
            journalEmpty = true,
            ownerAbsent = true,
            lifetimeIdle = true,
        ),
        record = null,
    ).keySet()

    private fun identity() = GameModeFpsPackageIdentity(
        packageName = PACKAGE,
        userId = 0,
        uid = 10_154,
        versionCode = 1L,
        signingCertificateSha256 = "a".repeat(64),
    )

    private companion object {
        const val API = 8
        const val PACKAGE = "com.futuremark.dmandroid.application"
        const val REQUEST_ID = "req-7"
        const val DEADLINE_MS = 31_000L
    }
}
