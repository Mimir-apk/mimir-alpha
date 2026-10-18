package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicServiceIdentityTest {
    @Test
    fun `product hard lease is finite service owned and overflow safe`() {
        val now = 1_000L
        val expected = now + ProductOwnerHardLeasePolicy.HARD_LEASE_MS

        assertEquals(
            expected,
            ProductOwnerHardLeasePolicy.hardDeadlineElapsedRealtimeMs(now),
        )
        assertEquals(
            expected,
            MimirTweakerService().createOwnerHardLeasePolicy()
                .hardDeadlineElapsedRealtimeMs(now),
        )
        assertEquals(
            Long.MAX_VALUE,
            ProductOwnerHardLeasePolicy.hardDeadlineElapsedRealtimeMs(
                Long.MAX_VALUE - ProductOwnerHardLeasePolicy.HARD_LEASE_MS,
            ),
        )
        assertNull(
            ProductOwnerHardLeasePolicy.hardDeadlineElapsedRealtimeMs(
                Long.MAX_VALUE - ProductOwnerHardLeasePolicy.HARD_LEASE_MS + 1L,
            ),
        )
        assertNull(ProductOwnerHardLeasePolicy.hardDeadlineElapsedRealtimeMs(-1L))
    }

    /**
     * A hard-lease expiry must not poison the sessions that follow it.
     *
     * Unverified device behaviour after the 2026-08-10 lifecycle work: the 12-hour ceiling is non-renewable by
     * design, so if it were ever scoped to the service lifetime or the boot rather than the session,
     * then after the first expiry every later apply would be refused at
     * `TransactionCoordinator`'s `effectiveHardLeaseDeadline <= admissionNow` check -- and the player's
     * standing profile would never come back, with the app-side re-arm watcher looping against a
     * service that can only say HARD_LEASE_EXPIRED. A 12-hour wall-clock device run is the only way to
     * observe that directly, so this pins the property that makes the device run predictable.
     *
     * The ceiling is minted per apply: `MimirTweakerService` takes `SystemClock.elapsedRealtime()` at
     * request time and hands it to `PublicRequestContract.hardLeaseDeadline`. A `now` far beyond an
     * earlier, long-expired deadline therefore still yields a fresh, valid, full-length ceiling.
     */
    @Test
    fun `a fresh session after a hard lease expiry gets a full new ceiling`() {
        val firstSessionStart = 1_000L
        val firstCeiling = checkNotNull(
            PublicRequestContract.hardLeaseDeadline(ProductOwnerHardLeasePolicy, firstSessionStart),
        )

        // Well past the first ceiling: the lease has expired and the Guardian has restored Stock.
        val afterExpiry = firstCeiling + 60_000L
        val secondCeiling = checkNotNull(
            PublicRequestContract.hardLeaseDeadline(ProductOwnerHardLeasePolicy, afterExpiry),
        )

        assertTrue("the new ceiling must be in the future", secondCeiling > afterExpiry)
        assertEquals(
            "the ceiling is per-apply, so the replacement is full length",
            ProductOwnerHardLeasePolicy.HARD_LEASE_MS,
            secondCeiling - afterExpiry,
        )
        assertTrue("and strictly later than the expired one", secondCeiling > firstCeiling)
    }

    /**
     * The same property one level down, stated as the invariant a future refactor would break: for any
     * valid clock reading, the minted ceiling is exactly HARD_LEASE_MS ahead of *that* reading. Nothing
     * carries over from a previous session.
     */
    @Test
    fun `the ceiling depends only on the clock reading handed to it`() {
        listOf(0L, 1_000L, ProductOwnerHardLeasePolicy.HARD_LEASE_MS, 9_999_999_999L).forEach { now ->
            assertEquals(
                "ceiling at now=$now must be now + HARD_LEASE_MS",
                now + ProductOwnerHardLeasePolicy.HARD_LEASE_MS,
                ProductOwnerHardLeasePolicy.hardDeadlineElapsedRealtimeMs(now),
            )
        }
    }

    @Test
    fun `request envelope is caller owned while hard lease policy is service owned`() {
        assertNull(PublicRequestContract.validate("request-1", 2_000L, 1_000L))
        assertEquals(
            "INVALID_REQUEST_ID",
            PublicRequestContract.validate("bad request", 2_000L, 1_000L)?.code,
        )
        assertEquals(
            "REQUEST_EXPIRED",
            PublicRequestContract.validate("request-1", 1_000L, 1_000L)?.code,
        )

        assertNull(
            PublicRequestContract.hardLeaseDeadline(
                DisabledOwnerHardLeasePolicy,
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
        assertEquals(
            91_000L,
            PublicRequestContract.hardLeaseDeadline(
                OwnerHardLeasePolicy { now -> now + 90_000L },
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
        assertNull(
            PublicRequestContract.hardLeaseDeadline(
                OwnerHardLeasePolicy { now -> now },
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun `token death is linked before work and cancels only the exact in-flight request`() {
        val events = mutableListOf<String>()
        val deaths = mutableListOf<PublicOwnerSnapshot>()
        val registry = PublicOwnerRegistry(deaths::add)
        val token = FakeClientToken(events)

        val registered = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        events += "capture"
        assertEquals(listOf("linked", "capture"), events)

        assertTrue(
            registry.cancelMatching(99, "request-a", token, 0L, "") is PublicOwnerMatch.Stale,
        )
        assertTrue(
            registry.cancelMatching(42, "request-b", token, 0L, "") is PublicOwnerMatch.Stale,
        )
        assertTrue(
            registry.cancelMatching(
                42,
                "request-a",
                FakeClientToken(),
                0L,
                "",
            ) is PublicOwnerMatch.Stale,
        )
        assertFalse(registered.cancelToken.isCancelled())

        token.die()

        assertTrue(registered.cancelToken.isCancelled())
        assertEquals(registered.owner.tokenId, deaths.single().tokenId)
        assertEquals("request-a", deaths.single().requestId)
        assertTrue(deaths.single().inFlight)
    }

    @Test
    fun `active ownership requires exact uid token request generation and transaction`() {
        val deaths = mutableListOf<PublicOwnerSnapshot>()
        val registry = PublicOwnerRegistry(deaths::add)
        val token = FakeClientToken()
        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)

        assertNull(registry.matchActive(99, "request-a", token, 7L, "tx-7"))
        assertNull(registry.matchActive(42, "request-b", token, 7L, "tx-7"))
        assertNull(registry.matchActive(42, "request-a", FakeClientToken(), 7L, "tx-7"))
        assertNull(registry.matchActive(42, "request-a", token, 8L, "tx-7"))
        assertNull(registry.matchActive(42, "request-a", token, 7L, "tx-other"))

        val exact = checkNotNull(registry.matchActive(42, "request-a", token, 7L, "tx-7"))
        val identity = activeIdentity(ownerTokenId = exact.tokenId)
        assertTrue(registry.isOwnerAlive(identity))
        assertFalse(registry.isOwnerAlive(identity.copy(ownerUid = 99)))
        assertFalse(registry.isOwnerAlive(identity.copy(ownerTokenId = exact.tokenId + 1)))

        assertTrue(
            registry.cancelMatching(
                42,
                "request-a",
                token,
                7L,
                "tx-other",
            ) is PublicOwnerMatch.Stale,
        )
        assertFalse(checkNotNull(registry.snapshot()).cancelled)
        assertTrue(
            registry.cancelMatching(
                42,
                "request-a",
                token,
                7L,
                "tx-7",
            ) is PublicOwnerMatch.Active,
        )
        assertTrue(checkNotNull(registry.snapshot()).cancelled)
    }

    @Test
    fun `activeOwnerForRenewal returns the live owner and token only while bound, alive, and idle`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeClientToken()

        // No owner registered at all.
        assertNull(registry.activeOwnerForRenewal())

        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        // In flight (registerApply leaves the fresh entry in-flight) -- not eligible yet.
        assertNull(registry.activeOwnerForRenewal())

        // Not yet bound to a generation/transactionId, even once no longer in flight.
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)
        assertNull(registry.activeOwnerForRenewal())

        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))
        val eligible = checkNotNull(registry.activeOwnerForRenewal())
        assertEquals(42, eligible.first.ownerUid)
        assertEquals(7L, eligible.first.generation)
        assertEquals("tx-7", eligible.first.transactionId)
        assertTrue(eligible.second.sameToken(token))

        // Mid-transition (an active restore/re-apply already owns the write) -- must not be
        // treated as eligible to renew concurrently.
        val transition = checkNotNull(registry.beginActiveTransition(42, "request-a", token, 7L, "tx-7"))
        assertNull(registry.activeOwnerForRenewal())
        assertTrue(registry.bindAppliedSession(transition.owner.tokenId, 8L, "tx-8"))
        registry.completeApply(transition.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.activeOwnerForRenewal() != null)

        // Token death: no longer eligible.
        token.die()
        assertNull(registry.activeOwnerForRenewal())
    }

    /**
     * Renewal cancellation fence:
     * [PublicOwnerRegistry.activeOwnerForRenewal] previously omitted the cancel check entirely -- a renewal could be handed an owner whose in-flight request had already been
     * cancelled (e.g. by a fresh public request racing the renewal tick), and would then drive a
     * hardware write on that owner's behalf regardless. `PublicOwnerSnapshot.cancelled` already
     * existed and was already populated from `cancelToken.isCancelled()`; this pin is what makes
     * [activeOwnerForRenewal] actually read it.
     */
    @Test
    fun `activeOwnerForRenewal returns null for a cancelled owner even though it is alive and bound`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeClientToken()

        val registration = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        registry.completeApply(registration.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.bindAppliedSession(registration.owner.tokenId, 7L, "tx-7"))
        assertTrue("eligible before cancellation", registry.activeOwnerForRenewal() != null)

        assertTrue(
            "cancelMatching must actually cancel the exact bound owner",
            registry.cancelMatching(42, "request-a", token, 7L, "tx-7") is PublicOwnerMatch.Active,
        )
        assertTrue("the entry is still alive and still bound", checkNotNull(registry.snapshot()).alive)
        assertTrue(checkNotNull(registry.snapshot()).cancelled)

        assertNull(
            "a cancelled owner must never be handed to a renewal attempt",
            registry.activeOwnerForRenewal(),
        )
    }

    @Test
    fun `exact active transition rotates cancellation and binds only the replacement session`() {
        val registry = PublicOwnerRegistry {}
        val token = FakeClientToken()
        val initial = registry.registerApply(42, "request-a", token)
            as PublicOwnerRegistration.Registered
        assertTrue(registry.bindAppliedSession(initial.owner.tokenId, 7L, "tx-7"))
        registry.completeApply(initial.owner.tokenId, retainActiveOwner = true)

        assertNull(registry.beginActiveTransition(42, "request-a", token, 8L, "tx-7"))
        val transition = checkNotNull(
            registry.beginActiveTransition(42, "request-a", token, 7L, "tx-7"),
        )
        assertNotSame(initial.cancelToken, transition.cancelToken)
        assertTrue(checkNotNull(registry.snapshot()).inFlight)
        assertNull(registry.matchActive(42, "request-a", token, 7L, "tx-7"))

        assertTrue(registry.bindAppliedSession(transition.owner.tokenId, 8L, "tx-8"))
        registry.completeApply(transition.owner.tokenId, retainActiveOwner = true)
        assertTrue(registry.matchActive(42, "request-a", token, 8L, "tx-8") != null)
        token.die()
        assertTrue(transition.cancelToken.isCancelled())
    }

    @Test
    fun `stale death callback cannot cancel or replace a newer token owner`() {
        val deaths = mutableListOf<PublicOwnerSnapshot>()
        val registry = PublicOwnerRegistry(deaths::add)
        val oldToken = FakeClientToken()
        val old = registry.registerApply(42, "request-old", oldToken)
            as PublicOwnerRegistration.Registered
        registry.clearIfToken(old.owner.tokenId)

        val newToken = FakeClientToken()
        val current = registry.registerApply(42, "request-new", newToken)
            as PublicOwnerRegistration.Registered
        oldToken.fireStaleDeath()

        assertEquals(current.owner.tokenId, checkNotNull(registry.snapshot()).tokenId)
        assertFalse(current.cancelToken.isCancelled())
        assertTrue(deaths.isEmpty())
    }

    @Test
    fun `strict query truth exposes only seven states and conservative mutation truth`() {
        assertEquals(
            listOf(
                "STOCK",
                "APPLYING",
                "ACTIVE",
                "RESTORING",
                "RECOVERY_FAILED",
                "CORRUPT",
                "UNKNOWN",
            ),
            PublicSessionState.entries.map { it.name },
        )

        val stock = resolve(JournalLoadResult.Empty)
        assertEquals(PublicSessionState.STOCK, stock.state)
        assertFalse(stock.mutationMayBeActive)

        val inFlight = resolve(
            journal = JournalLoadResult.Empty,
            inFlightOwner = PublicOwnerSnapshot(
                tokenId = 1L,
                requestId = "request-a",
                ownerUid = 42,
                inFlight = true,
                alive = true,
                cancelled = false,
                generation = null,
                transactionId = null,
            ),
        )
        assertEquals(PublicSessionState.APPLYING, inFlight.state)
        assertFalse(inFlight.mutationMayBeActive)

        val prepared = resolve(JournalLoadResult.Loaded(record(TransactionPhase.PREPARED)))
        assertEquals(PublicSessionState.APPLYING, prepared.state)
        assertFalse(prepared.mutationMayBeActive)

        val applying = resolve(JournalLoadResult.Loaded(record(TransactionPhase.APPLYING)))
        assertEquals(PublicSessionState.APPLYING, applying.state)
        assertTrue(applying.mutationMayBeActive)

        val active = resolve(
            journal = JournalLoadResult.Loaded(record(TransactionPhase.APPLIED)),
            active = activeIdentity(),
            leaseRenewable = true,
        )
        assertEquals(PublicSessionState.ACTIVE, active.state)
        assertTrue(active.mutationMayBeActive)
        assertTrue(active.leaseRenewable)
        assertEquals(7L, active.generation)
        assertEquals("tx-7", active.transactionId)
        assertEquals(10_000L, active.hardLeaseDeadlineElapsedRealtimeMs)

        val restoring = resolve(JournalLoadResult.Loaded(record(TransactionPhase.APPLIED)))
        assertEquals(PublicSessionState.RESTORING, restoring.state)
        assertTrue(restoring.mutationMayBeActive)

        val failed = resolve(JournalLoadResult.Loaded(record(TransactionPhase.RESTORE_FAILED)))
        assertEquals(PublicSessionState.RECOVERY_FAILED, failed.state)
        assertTrue(failed.mutationMayBeActive)

        val corrupt = resolve(JournalLoadResult.Corrupt("truncated"))
        assertEquals(PublicSessionState.CORRUPT, corrupt.state)
        assertTrue(corrupt.mutationMayBeActive)

        val unknown = resolve(
            journal = JournalLoadResult.Loaded(record(TransactionPhase.APPLIED)),
            active = activeIdentity().copy(generation = 8L),
        )
        assertEquals(PublicSessionState.UNKNOWN, unknown.state)
        assertTrue(unknown.mutationMayBeActive)

        val contradictoryEmpty = resolve(
            journal = JournalLoadResult.Empty,
            active = activeIdentity(),
        )
        assertEquals(PublicSessionState.UNKNOWN, contradictoryEmpty.state)
        assertTrue(contradictoryEmpty.mutationMayBeActive)
    }

    @Test
    fun `durably empty dead owner settles only exact or already absent runtime`() {
        val deadOwner = PublicOwnerSnapshot(
            tokenId = 11L,
            requestId = "request-a",
            ownerUid = 42,
            inFlight = false,
            alive = false,
            cancelled = true,
            generation = 7L,
            transactionId = "tx-7",
        )
        val exactActive = activeIdentity(ownerTokenId = deadOwner.tokenId)

        assertEquals(
            EmptyJournalDeadOwnerSettlement.ExactDeadOwner(deadOwner.tokenId),
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Empty,
                deadOwner,
                deadOwner,
                exactActive,
            ),
        )
        assertEquals(
            EmptyJournalDeadOwnerSettlement.AlreadyAbsent,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Empty,
                deadOwner,
                currentOwner = null,
                activeSession = null,
            ),
        )

        assertEquals(
            EmptyJournalDeadOwnerSettlement.Rejected,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Loaded(record(TransactionPhase.APPLIED)),
                deadOwner,
                deadOwner,
                exactActive,
            ),
        )
        assertEquals(
            EmptyJournalDeadOwnerSettlement.Rejected,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Corrupt("unreadable"),
                deadOwner,
                deadOwner,
                exactActive,
            ),
        )
        assertEquals(
            EmptyJournalDeadOwnerSettlement.Rejected,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Empty,
                deadOwner,
                deadOwner.copy(tokenId = deadOwner.tokenId + 1L),
                exactActive,
            ),
        )
        assertEquals(
            EmptyJournalDeadOwnerSettlement.Rejected,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Empty,
                deadOwner,
                deadOwner,
                exactActive.copy(generation = exactActive.generation + 1L),
            ),
        )
        assertEquals(
            EmptyJournalDeadOwnerSettlement.Rejected,
            EmptyJournalDeadOwnerSettlementGate.evaluate(
                JournalLoadResult.Empty,
                deadOwner,
                currentOwner = null,
                activeSession = exactActive,
            ),
        )
    }

    @Test
    fun `already absent settlement never clears a replacement owner registered after admission`() {
        val deadOwner = PublicOwnerSnapshot(
            tokenId = 11L,
            requestId = "request-a",
            ownerUid = 42,
            inFlight = false,
            alive = false,
            cancelled = true,
            generation = 7L,
            transactionId = "tx-7",
        )
        val settlement = EmptyJournalDeadOwnerSettlementGate.evaluate(
            journal = JournalLoadResult.Empty,
            deadOwner = deadOwner,
            currentOwner = null,
            activeSession = null,
        )
        assertEquals(EmptyJournalDeadOwnerSettlement.AlreadyAbsent, settlement)

        val registry = PublicOwnerRegistry {}
        val replacement = registry.registerApply(
            ownerUid = 77,
            requestId = "replacement-request",
            token = FakeClientToken(),
        )
        assertTrue(replacement is PublicOwnerRegistration.Registered)

        val tokenToClear =
            (settlement as? EmptyJournalDeadOwnerSettlement.ExactDeadOwner)?.tokenId
        tokenToClear?.let(registry::clearIfToken)

        val retained = registry.snapshot()
        assertEquals(77, retained?.ownerUid)
        assertTrue(retained?.alive == true)
        assertTrue(retained?.inFlight == true)
    }

    private fun resolve(
        journal: JournalLoadResult,
        active: ActiveSessionIdentity? = null,
        recoveryOwned: Boolean = false,
        inFlightOwner: PublicOwnerSnapshot? = null,
        leaseRenewable: Boolean = false,
    ): PublicSessionTruth = PublicSessionTruthResolver.resolve(
        journal = journal,
        active = active,
        recoveryOwned = recoveryOwned,
        inFlightOwner = inFlightOwner,
        leaseRenewable = leaseRenewable,
    )

    private fun activeIdentity(ownerTokenId: Long = 1L) = ActiveSessionIdentity(
        generation = 7L,
        transactionId = "tx-7",
        requestId = "request-a",
        ownerUid = 42,
        targetPackage = "example.game",
        activationDeadlineMs = 2_000L,
        leaseDeadlineMs = 5_000L,
        targetEverForeground = true,
        ownerTokenId = ownerTokenId,
        requestDeadlineElapsedRealtimeMs = 3_000L,
        hardLeaseDeadlineMs = 10_000L,
    )

    private fun record(
        phase: TransactionPhase,
        lastCompletedMutationBoundary: Int = 0,
    ) = TransactionJournalRecord(
        generation = 7L,
        requestId = "request-a",
        transactionId = "tx-7",
        hardwareFingerprint = "fp",
        deviceProfile = "rp5",
        planHash = "plan",
        profileId = "hidden",
        targetPackage = "example.game",
        phase = phase,
        creationTime = 1_000L,
        requestDeadline = 3_000L,
        leaseDeadline = 10_000L,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "topology",
        snapshotSha256 = "snapshot",
        lastCompletedMutationBoundary = lastCompletedMutationBoundary,
    )

    private class FakeClientToken(
        private val events: MutableList<String>? = null,
        private var alive: Boolean = true,
    ) : ClientTokenHandle {
        private var currentDeath: (() -> Unit)? = null
        private val linkedDeaths = mutableListOf<() -> Unit>()

        override fun sameToken(other: ClientTokenHandle): Boolean = this === other

        override fun isAlive(): Boolean = alive

        override fun linkToDeath(onDeath: () -> Unit): Boolean {
            events?.add("linked")
            currentDeath = onDeath
            linkedDeaths += onDeath
            return alive
        }

        override fun unlinkFromDeath() {
            currentDeath = null
        }

        fun die() {
            if (!alive) return
            alive = false
            currentDeath?.invoke()
        }

        fun fireStaleDeath() {
            linkedDeaths.lastOrNull()?.invoke()
        }
    }
}
