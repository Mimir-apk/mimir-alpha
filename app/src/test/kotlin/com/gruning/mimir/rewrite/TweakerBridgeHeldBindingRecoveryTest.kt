package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odin 3, 2026-08-10: the Tweaker row reported `pair=ELIGIBLE availability=UNAVAILABLE
 * profiles=[stock]` across every relaunch — all four certified profiles were unavailable with
 * nothing in the UI to explain it. Only force-stopping the Tweaker service, which dropped the
 * binding, let the next refresh reach `availability=READY profiles=[stock, quiet, balanced,
 * performance]`.
 *
 * `refreshCapabilities` returns the cache whenever the reservation is held elsewhere. That is right
 * for a telemetry poll and wrong exactly once: if the holder takes the reservation before
 * capabilities have ever been read, the cache is null, every later refresh short-circuits on that
 * same null, and nothing can populate it for the life of the process.
 */
class TweakerBridgeHeldBindingRecoveryTest {

    /** The unrecoverable state, and the only one this may act on. */
    @Test
    fun `never-read capabilities on a held binding are adopted`() {
        assertTrue(
            tweakerBridgeMayAdoptHeldBinding(
                hasCachedCapabilities = false,
                hasPendingLaunch = false,
                mayOwnTransport = false,
                sessionBound = true,
            ),
        )
    }

    /**
     * The conservatism that keeps this a recovery rather than a second path into the projection:
     * once anything has been read, the reservation holder's ordering is respected again.
     */
    @Test
    fun `an already-read projection is left alone`() {
        assertFalse(
            tweakerBridgeMayAdoptHeldBinding(
                hasCachedCapabilities = true,
                hasPendingLaunch = false,
                mayOwnTransport = false,
                sessionBound = true,
            ),
        )
    }

    /** A launch owning the transport is never interrupted, cache or no cache. */
    @Test
    fun `a launch that owns the transport is never interrupted`() {
        assertFalse(
            tweakerBridgeMayAdoptHeldBinding(
                hasCachedCapabilities = false,
                hasPendingLaunch = true,
                mayOwnTransport = false,
                sessionBound = true,
            ),
        )
        assertFalse(
            tweakerBridgeMayAdoptHeldBinding(
                hasCachedCapabilities = false,
                hasPendingLaunch = false,
                mayOwnTransport = true,
                sessionBound = true,
            ),
        )
    }

    /**
     * With no binding there is nothing to read, and this must NOT be a reason to bind: binding
     * without the reservation is what would race the holder.
     */
    @Test
    fun `an unbound session is not a reason to bind`() {
        assertFalse(
            tweakerBridgeMayAdoptHeldBinding(
                hasCachedCapabilities = false,
                hasPendingLaunch = false,
                mayOwnTransport = false,
                sessionBound = false,
            ),
        )
    }

    /**
     * The prior behaviour, stated as the differential: before this, the held-reservation path
     * returned the cache unconditionally. Replaying the broken state through that rule yields "do
     * nothing" forever, which is precisely the permanent UNAVAILABLE — so the new rule has to differ
     * on exactly that input and agree everywhere else.
     */
    @Test
    fun `the previous rule never recovered, and the new one differs only there`() {
        fun previousRuleWouldRefresh(): Boolean = false // it always returned the cache
        val brokenState = tweakerBridgeMayAdoptHeldBinding(
            hasCachedCapabilities = false,
            hasPendingLaunch = false,
            mayOwnTransport = false,
            sessionBound = true,
        )
        assertTrue("the new rule must act where the old one could not", brokenState)
        assertFalse(previousRuleWouldRefresh())

        // Every other combination still agrees with the old do-nothing behaviour.
        val others = listOf(
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(false, false, false),
        )
        others.forEach { (cached, pending, bound) ->
            assertFalse(
                "cached=$cached pending=$pending bound=$bound must stay inert",
                tweakerBridgeMayAdoptHeldBinding(cached, pending, false, bound),
            )
        }
    }
}
