package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host contract for
 * [fanManualReentryStep], the pure re-entry-step decision
 * [MimirTweakerService.prepareManualFanReentryForReassert] drives on every tick-driven MANUAL fan
 * reassert, and for the [FanOwnershipRenewalPolicy.afterAttempt] reschedule the P3 churn fix
 * (`renewMimirFanOwnershipOnWorker`'s two early "owner unavailable" returns) now relies on.
 *
 * Red-first history: [fanManualReentryStep] originally always returned `REASSERT` unconditionally
 * -- the earlier behavior, where the reassert wrote straight to `fan_mode` with no
 * re-entry preparation at all. All three cases below (plus the null-liveFanMode case) failed
 * against that stub.
 */
class FanManualReentryTest {

    @Test
    fun `hardware away from vendor Smart must be handed to vendor Smart first`() {
        assertEquals(
            FanManualReentryStep.HAND_TO_VENDOR_SMART,
            fanManualReentryStep(liveFanMode = TuningPlanner.FAN_MANUAL, handoffPrepared = false),
        )
    }

    @Test
    fun `a null live read -- a failed telemetry sample, not an observed write -- still hands to vendor Smart first`() {
        // Same reasoning fanControlDecision's own P3 branch documents for its narrower
        // liveFanMode != null gate: a null read must never be treated as "already at Smart" and
        // skip straight to a capture that could sample stale/wrong vendor state.
        assertEquals(
            FanManualReentryStep.HAND_TO_VENDOR_SMART,
            fanManualReentryStep(liveFanMode = null, handoffPrepared = false),
        )
    }

    @Test
    fun `vendor Smart with no handoff prepared yet must capture the handoff`() {
        assertEquals(
            FanManualReentryStep.CAPTURE_HANDOFF,
            fanManualReentryStep(liveFanMode = TuningPlanner.FAN_SMART, handoffPrepared = false),
        )
    }

    @Test
    fun `vendor Smart with a handoff already prepared may reassert`() {
        assertEquals(
            FanManualReentryStep.REASSERT,
            fanManualReentryStep(liveFanMode = TuningPlanner.FAN_SMART, handoffPrepared = true),
        )
    }

    // --- FanOwnershipRenewalPolicy: the P3 churn fix's own reschedule -------------------------

    @Test
    fun `an OWNER_UNAVAILABLE attempt still reschedules by the ordinary retry delay`() {
        // Part B: renewMimirFanOwnershipOnWorker's two early "no owner to renew for" returns must
        // reschedule via afterAttempt exactly like any other failed attempt, rather than leaving
        // nextAttemptAtElapsedMs in the past -- else the tick re-dispatches a no-op attempt every
        // single tick instead of backing off.
        val state = FanOwnershipRenewalPolicy.begin(
            generation = 7L,
            transactionId = "tx-7",
            code = "FAN_HANDOFF_UNAVAILABLE",
            nowElapsedMs = 1_000L,
        )
        val retried = FanOwnershipRenewalPolicy.afterAttempt(
            state,
            code = "OWNER_UNAVAILABLE",
            nowElapsedMs = 20_000L,
        )
        assertEquals("OWNER_UNAVAILABLE", retried.code)
        assertEquals(1, retried.attempts)
        assertEquals(20_000L + FanOwnershipRenewalPolicy.RETRY_MS, retried.nextAttemptAtElapsedMs)
    }

    // --- Re-entry authority fence:
    // prepareManualFanReentryForReassert's own owner precheck, before ANY hardware write. That
    // function is private on MimirTweakerService with no live-service test harness (see
    // SelectPathManualFanEntryTest's own kdoc for the seams that DO exist -- none of them a live
    // instance). Realised here as: (a) a small mimic of the precheck-then-write sequence, driven
    // against the REAL PublicOwnerRegistry (not a fake) so the cancelled/dead-owner behaviour it
    // asserts is the registry's own, with a fake PORT that records write counts -- proving zero
    // writes reach it when the precheck declines; and (b) a source-text pin that the real function
    // performs the identical registry checks before its own hand-to-vendor-Smart write.

    private class FakeReentryCommandPort {
        var handToVendorSmartCalls = 0
            private set

        fun handFanToVendorSmartForRenewal() {
            handToVendorSmartCalls++
        }
    }

    private class FakeReentryToken(private var alive: Boolean = true) : ClientTokenHandle {
        override fun sameToken(other: ClientTokenHandle): Boolean = this === other
        override fun isAlive(): Boolean = alive
        override fun linkToDeath(onDeath: () -> Unit): Boolean = alive
        override fun unlinkFromDeath() = Unit
        fun die() {
            alive = false
        }
    }

    /**
     * Mimics [MimirTweakerService.prepareManualFanReentryForReassert]'s own owner precheck --
     * `!publicOwners.isOwnerAlive(active) || publicOwners.snapshot()?.cancelled != false` declines
     * before any hardware write -- see the source-text pin below for proof the real function
     * performs this exact check in this position.
     */
    private fun mimicReentryOwnerPrecheckThenWrite(
        owners: PublicOwnerRegistry,
        active: ActiveSessionIdentity,
        port: FakeReentryCommandPort,
    ): String? {
        if (!owners.isOwnerAlive(active) || owners.snapshot()?.cancelled != false) {
            return "FAN_REASSERT_OWNER_UNAVAILABLE"
        }
        port.handFanToVendorSmartForRenewal()
        return null
    }

    private fun reentryActiveIdentity(ownerTokenId: Long) = ActiveSessionIdentity(
        generation = 7L,
        transactionId = "tx-7",
        requestId = "request-a",
        ownerUid = 42,
        targetPackage = "com.gruning.mimir.rewrite",
        activationDeadlineMs = Long.MAX_VALUE,
        leaseDeadlineMs = Long.MAX_VALUE,
        targetEverForeground = true,
        ownerTokenId = ownerTokenId,
    )

    @Test
    fun `a cancelled owner declines the reassert re-entry precheck before any hardware write`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeReentryToken()
        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))
        assertTrue(
            registry.cancelMatching(42, "request-a", token, 7L, "tx-7") is PublicOwnerMatch.Active,
        )

        val port = FakeReentryCommandPort()
        val code = mimicReentryOwnerPrecheckThenWrite(
            registry,
            reentryActiveIdentity(registration.owner.tokenId),
            port,
        )

        assertEquals("FAN_REASSERT_OWNER_UNAVAILABLE", code)
        assertEquals(
            "a cancelled owner must never reach the hand-to-vendor-Smart write",
            0,
            port.handToVendorSmartCalls,
        )
    }

    @Test
    fun `a dead owner declines the reassert re-entry precheck before any hardware write`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeReentryToken()
        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))
        token.die()

        val port = FakeReentryCommandPort()
        val code = mimicReentryOwnerPrecheckThenWrite(
            registry,
            reentryActiveIdentity(registration.owner.tokenId),
            port,
        )

        assertEquals("FAN_REASSERT_OWNER_UNAVAILABLE", code)
        assertEquals(
            "a dead owner must never reach the hand-to-vendor-Smart write",
            0,
            port.handToVendorSmartCalls,
        )
    }

    @Test
    fun `a live, uncancelled, exactly-bound owner passes the reassert re-entry precheck through to the write`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeReentryToken()
        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))

        val port = FakeReentryCommandPort()
        val code = mimicReentryOwnerPrecheckThenWrite(
            registry,
            reentryActiveIdentity(registration.owner.tokenId),
            port,
        )

        assertEquals(null, code)
        assertEquals(1, port.handToVendorSmartCalls)
    }

    /**
     * Source-text pin proving [MimirTweakerService.prepareManualFanReentryForReassert] itself
     * performs the identical registry checks [mimicReentryOwnerPrecheckThenWrite] mimics above,
     * strictly before its own hand-to-vendor-Smart write.
     */
    @Test
    fun `prepareManualFanReentryForReassert checks owner eligibility before handing the fan to vendor Smart`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun prepareManualFanReentryForReassert(",
            "private fun renewMimirFanOwnershipOnWorker(",
        )
        val ownerAliveIndex = fn.indexOf("publicOwners.isOwnerAlive(active)")
        val cancelledIndex = fn.indexOf("publicOwners.snapshot()?.cancelled")
        val writeIndex = fn.indexOf("directHardwareCommands?.handFanToVendorSmartForRenewal()")
        assertTrue("the owner-alive check must be present", ownerAliveIndex >= 0)
        assertTrue("the cancelled check must be present", cancelledIndex >= 0)
        assertTrue("the hand-to-vendor-Smart write must be present", writeIndex >= 0)
        assertTrue("the owner-alive precheck must run BEFORE the write", ownerAliveIndex < writeIndex)
        assertTrue("the cancelled precheck must run BEFORE the write", cancelledIndex < writeIndex)
    }

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }
}
