package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreGuardianRunnerEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sessionPair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val sysfs = RestoreGuardianSysfsEntry(2, "cpu:0", "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(100, "644", 0, 0), mutableListOf(200))
    private val fan = RestoreGuardianFanPutEntry(1, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = mutableListOf(1))
    private val envelope = RestoreGuardianEnvelope(helperBuildIdentity = "build", tweakerCertificateSha256 = "a".repeat(64), hardwareFingerprint = "rp5", deviceProfile = "rp5", bootIdentity = "boot", generation = 1, requestId = "request", transactionId = "transaction", taskNonce = "b".repeat(32), topologySha256 = "c".repeat(64), snapshotSha256 = "d".repeat(64), planSha256 = "e".repeat(64), hardDeadlineElapsedRealtimeMs = 1_000, restoreEntries = mutableListOf(sysfs, fan))

    @Test
    fun `owned terminal write failure retries receipt and advances engine to exited`() {
        val brokenRoot = temporaryFolder.newFile("terminal-receipts")
        val store = GuardianTerminalReceiptStore(brokenRoot)
        val collector = GuardianProcessReceiptCollector(store)
        val engine = RestoreGuardianRunnerEngine(
            receiptAuthentication = GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
            receiptOutput = collector,
            sessionKey = sessionKey,
        )
        val value = baseEnvelope()
        engine.stage(GuardianEnvelopeVerificationOutcome.Verified(value), 1, "boot")
        engine.admitReady(2)
        engine.beginWatching(3)
        engine.ownerCancelled()
        val stock = mapOf(
            "cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0),
            fan.logicalId to GuardianObservedState.FanMode(0),
        )
        assertEquals(
            GuardianRunnerLifecycleState.STOCK_CONFIRMED,
            engine.confirmStock(
                GuardianStockReadback(stock, 100),
                GuardianStockReadback(stock, 175),
            ).state,
        )
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, engine.exit(176).state)
        assertTrue(collector.hasPendingTerminalReceipt())

        assertTrue(brokenRoot.delete())
        assertTrue(brokenRoot.mkdirs())
        assertEquals(
            GuardianReceiptOutputDisposition.CONFIRMED,
            collector.retryPendingTerminalReceipt(),
        )
        assertEquals(GuardianRunnerLifecycleState.EXITED, engine.exit(177).state)
        assertTrue(store.loadUntil(0) is GuardianTerminalLoad.Received)
    }

    @Test fun `stage requires nonnegative monotonic exact boot and deeply pins envelope`() {
        assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, fixture().engine.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), -1, "boot").state)
        assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, fixture().engine.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), 1, "other").state)
        val sourceSysfs = RestoreGuardianSysfsEntry(2, "cpu:0", "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", RestoreGuardianStockState.PresentSysfs(100, "644", 0, 0), mutableListOf(200))
        val source = baseEnvelope().copy(restoreEntries = mutableListOf(sourceSysfs, fan.deepCopy()))
        val f = fixture(); f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(source), 1, "boot")
        (source.restoreEntries as MutableList).clear(); (sourceSysfs.rendererAppliedIntermediates as MutableList).clear()
        f.engine.admitReady(2); f.engine.beginWatching(3); f.engine.ownerCancelled()
        val intents = f.engine.planRestore("boot", GuardianRestoreFenceEvidence.SameBoot(fence()), mapOf("cpu:0" to GuardianObservedState.Sysfs(200, "644", 0, 0), fan.logicalId to GuardianObservedState.FanMode(1))).restoreIntents
        assertEquals(listOf("cpu:0", fan.logicalId), intents.map { it.entry.logicalId })
        (intents[0].entry as RestoreGuardianSysfsEntry).rendererAppliedIntermediates.let { assertEquals(listOf(200), it) }
    }

    @Test fun `ready boundary owner cancel and tick regressions restore not reject`() {
        val f = watching(); assertEquals(GuardianRunnerLifecycleState.RESTORING, f.engine.ownerCancelled().state)
        val ready = fixture(); ready.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot"); ready.engine.admitReady(2)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, ready.engine.beginWatching(1).state)
        val startTick = watching(); assertEquals(GuardianRunnerLifecycleState.RESTORING, startTick.engine.watchTick(2).state)
        val ticks = watching(); ticks.engine.watchTick(20); assertEquals(GuardianRunnerLifecycleState.RESTORING, ticks.engine.watchTick(19).state)
        val below = fixture(); val longLease = baseEnvelope().copy(hardDeadlineElapsedRealtimeMs = 40_000)
        below.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(longLease), 1, "boot"); below.engine.admitReady(2); below.engine.beginWatching(3)
        assertEquals(GuardianRunnerLifecycleState.WATCHING, below.engine.watchTick(3 + 29_999).state)
    }

    @Test fun `duplicate ready is a no op and READY itself is watchdog guarded`() {
        val f = fixture(); val longLease = baseEnvelope().copy(hardDeadlineElapsedRealtimeMs = 40_000)
        f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(longLease), 1, "boot"); f.engine.admitReady(2)
        assertEquals(GuardianRunnerLifecycleState.READY, f.engine.admitReady(3).state)
        assertTrue(!f.engine.admitReady(4).armRejected)
        assertEquals(GuardianRunnerLifecycleState.READY, f.engine.watchTick(2 + 29_999).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, f.engine.watchTick(2 + 30_000).state)
        val expiry = fixture(); expiry.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot"); expiry.engine.admitReady(2)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, expiry.engine.watchTick(1_000).state)
    }

    @Test fun `accepted heartbeat refreshes stale origin and readbacks cannot precede ready restore`() {
        val f = fixture(); val longLease = baseEnvelope().copy(hardDeadlineElapsedRealtimeMs = 40_000)
        f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(longLease), 1, "boot"); f.engine.admitReady(2); f.engine.beginWatching(3)
        assertEquals(GuardianRunnerLifecycleState.WATCHING, f.engine.heartbeat(heartbeat(longLease, 1, 10), true, 10).state)
        assertEquals(GuardianRunnerLifecycleState.WATCHING, f.engine.watchTick(10 + 29_999).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, f.engine.watchTick(10 + 30_000).state)
        val noRestore = watching(); val stock = GuardianStockReadback(emptyMap(), 0)
        assertEquals(GuardianRunnerLifecycleState.WATCHING, noRestore.engine.confirmStock(stock, stock.copy(observedElapsedRealtimeMs = 75)).state)
        val beforeReady = fixture(); beforeReady.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot")
        assertEquals(GuardianRunnerLifecycleState.VERIFIED_INITIAL_ENVELOPE, beforeReady.engine.confirmStock(stock, stock.copy(observedElapsedRealtimeMs = 75)).state)
        val restoring = watching(); restoring.engine.ownerCancelled()
        val exact = mapOf("cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0), fan.logicalId to GuardianObservedState.FanMode(0))
        assertEquals(GuardianRunnerLifecycleState.RESTORING, restoring.engine.confirmStock(GuardianStockReadback(exact, 1), GuardianStockReadback(exact, 76)).state)
        assertTrue(restoring.receipts.none { it.type == GuardianReceiptType.STOCK_CONFIRMED })
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, restoring.engine.confirmStock(GuardianStockReadback(exact, 100), GuardianStockReadback(exact, 175)).state)
    }

    @Test fun `pid mismatch faults and all receipt output failures do not advance`() {
        val mismatch = watching(); mismatch.engine.ownerCancelled()
        assertEquals(GuardianRunnerLifecycleState.FAULT, mismatch.engine.ownerDecision(GuardianOwnerDecision.PID_REUSED_OR_MISMATCH).state)
        val rejected = fixture(); rejected.engine.stage(GuardianEnvelopeVerificationOutcome.Rejected, 1, "boot")
        assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, rejected.engine.ownerDecision(GuardianOwnerDecision.PID_REUSED_OR_MISMATCH).state)
        listOf(GuardianReceiptOutputDisposition.REJECTED, GuardianReceiptOutputDisposition.AMBIGUOUS).forEach { disposition ->
            val f = fixture(disposition = disposition); f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot")
            assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, f.engine.admitReady(2).state)
        }
        val empty = fixture(authentication = ByteArray(0)); empty.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot"); assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, empty.engine.admitReady(2).state)
        val throwing = fixture(throws = true); throwing.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot"); assertEquals(GuardianRunnerLifecycleState.ARM_REJECTED, throwing.engine.admitReady(2).state)
    }

    // RENAMED AND RESCOPED,  This test previously pinned terminal proof against the COMPLETE captured
    // set on a changed boot. That rule was unsatisfiable on real hardware -- the Nova had
    // captured every CPU unpinned (INT_MAX via the shared-QoS transport), read concrete ceilings
    // after a reboot, and the cross-boot filter forbids writing them, so the full-set demand asked
    // for a value nothing was permitted to produce. Held through four installs.
    // The assertion is not deleted: it is moved onto the restorable-axis scope, which is the eligible set.
    @Test fun `other boot writes persistent axes only and its proof attests to exactly those`() {
        val f = watching(); f.engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH)
        val proof = GuardianRestoreFenceEvidence.DifferentBootPersistent("newboot", GuardianPersistentRestoreFenceProof(true, true, true))
        val intents = f.engine.planRestore("newboot", proof, mapOf("cpu:0" to GuardianObservedState.Sysfs(200, "644", 0, 0), fan.logicalId to GuardianObservedState.FanMode(1))).restoreIntents
        assertEquals(listOf(fan.logicalId), intents.map { it.entry.logicalId })
        val exact = mapOf(
            "cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0),
            fan.logicalId to GuardianObservedState.FanMode(0),
        )
        val first = GuardianStockReadback(exact, 100); val second = first.copy(observedElapsedRealtimeMs = 175)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, f.engine.confirmStock(first, second).state)
        assertEquals(GuardianRunnerLifecycleState.EXITED, f.engine.exit(176).state)
        val stock = f.receipts.first { it.type == GuardianReceiptType.STOCK_CONFIRMED }
        val parsed = checkNotNull(GuardianTerminalWireCodec.signedStock(stock.canonicalPayload, stock.authentication))
        assertEquals(GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK, parsed.receipt.restoreScope)
        assertEquals("boot", parsed.receipt.bootIdentity)
        assertEquals("newboot", parsed.receipt.recoveryBootIdentity)
        // The receipt attests to what was actually verified -- the eligible (persistent) axes --
        // never to the full set. That truthfulness is automatic: the digest is taken over the same
        // list confirmStock evaluated.
        assertEquals(
            GuardianRestoreEntrySetDigest.of(baseEnvelope().restoreEntries.filter { it.isPersistentGuardianAxis() }),
            parsed.receipt.verifiedStockEntrySetSha256,
        )
        assertNotEquals(
            "a cross-boot proof must NOT claim the complete captured set",
            GuardianRestoreEntrySetDigest.of(baseEnvelope().restoreEntries),
            parsed.receipt.verifiedStockEntrySetSha256,
        )
        // REGRESSION, 2026-09-05: proven live on the Odin with a temporary probe before
        // this fix landed. The receipt above is correct and signed exactly as the two assertions
        // just above prove -- but the verifier (`RestoreGuardianCrypto.terminalScopeRefusal`) used
        // to recompute this digest over the COMPLETE, unfiltered envelope regardless of scope, so
        // it could never authenticate a cross-boot receipt no matter how correctly the producer
        // emitted it. This is the check that gap left untested: not "did the receipt attest to the
        // right thing" (pinned above) but "does the verifier's digest recomputation agree with it."
        //
        // Calls `terminalScopeRefusal` directly rather than the full `verifyStockConfirmedReceipt`
        // chain: this fixture's `GuardianReceiptAuthenticationStrategy` returns a fixed dummy byte
        // array (see `fixture()` below), not a real EC signature, so the signature clause of that
        // chain cannot pass here regardless of this fix. `RestoreGuardianEnvelopeTest`'s
        // `cross boot terminal crypto accepts a receipt digested over the persistent axis alone`
        // covers the identical scenario through real signing end to end.
        assertEquals(
            "the verifier's scope-filtered digest must agree with the producer's",
            null,
            RestoreGuardianCrypto.terminalScopeRefusal(
                baseEnvelope(),
                parsed.receipt.recoveryBootIdentity,
                parsed.receipt.restoreScope,
                parsed.receipt.verifiedStockEntrySetSha256,
            ),
        )
        val noPersistent = fixture(); val sysfsOnly = baseEnvelope().copy(restoreEntries = listOf(sysfs.deepCopy()))
        noPersistent.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(sysfsOnly), 1, "boot"); noPersistent.engine.admitReady(2); noPersistent.engine.beginWatching(3); noPersistent.engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH)
        noPersistent.engine.planRestore("newboot", proof, mapOf("cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0)))
        val sysfsReadback = mapOf("cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0))
        // Cross-boot settlement: A cross-boot plan with NO persistent axis has an empty
        // eligible set, and an empty set has no representation in the signed receipt -- the digest
        // refuses it. So it fails CLOSED instead of settling on an unverifiable claim. Previously
        // this settled by matching the full captured set, which is the retired full-set demand.
        assertNotEquals(
            GuardianRunnerLifecycleState.STOCK_CONFIRMED,
            noPersistent.engine.confirmStock(GuardianStockReadback(sysfsReadback, 100), GuardianStockReadback(sysfsReadback, 175)).state,
        )
        // Restorable-axis settlement: the volatile axis is no longer DEMANDED -- but with no
        // persistent axis there is nothing left to attest to, so this still does not settle. The
        // case the restorable-axis check rescues is a plan that HAS a persistent axis; see
        // `a persistent axis is still restored AND still verified across a boot`.
        val drifted = fixture(); val cpuOnly = baseEnvelope().copy(restoreEntries = listOf(sysfs.deepCopy()))
        drifted.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(cpuOnly), 1, "boot")
        drifted.engine.admitReady(2); drifted.engine.beginWatching(3); drifted.engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH)
        drifted.engine.planRestore("newboot", proof, mapOf("cpu:0" to GuardianObservedState.Sysfs(999_999, "644", 0, 0)))
        val nonStock = mapOf("cpu:0" to GuardianObservedState.Sysfs(999_999, "644", 0, 0))
        assertNotEquals(
            GuardianRunnerLifecycleState.STOCK_CONFIRMED,
            drifted.engine.confirmStock(GuardianStockReadback(nonStock, 100), GuardianStockReadback(nonStock, 175)).state,
        )
        // Cross-boot attestation: a cross-boot plan with no persistent axis emits NO stock receipt
        // at all now, because there is no eligible set to attest to and the digest field cannot
        // represent an empty one. Previously it emitted a receipt over the complete captured set.
        // Asserting the absence is the honest replacement for asserting its contents.
        assertTrue(
            "a cross-boot plan with no persistent axis must emit no stock receipt",
            noPersistent.receipts.none { it.type == GuardianReceiptType.STOCK_CONFIRMED },
        )
        assertTrue(
            "and neither may the drifted one",
            drifted.receipts.none { it.type == GuardianReceiptType.STOCK_CONFIRMED },
        )
    }

    @Test fun `same boot exact terminal marker admits guardian restore while delayed apply is terminalized`() {
        val f = watching(); f.engine.ownerCancelled()
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(baseEnvelope())
        val terminalDisk = FenceDiskState(true, identity, identity, true)
        val terminal = GuardianRestoreFenceEvidence.SameBoot(terminalDisk)
        assertEquals(listOf(fan.logicalId), f.engine.planRestore("boot", terminal, mapOf(fan.logicalId to GuardianObservedState.FanMode(1))).restoreIntents.map { it.entry.logicalId })
        assertEquals(FenceApplyDecision.TERMINALIZED, GuardianRunnerFencePolicy.evaluate(baseEnvelope(), terminalDisk, "boot", false))
        listOf(
            FenceDiskState(false, identity, identity, true),
            FenceDiskState(true, identity, identity, false),
            FenceDiskState(true, null, identity, true),
            FenceDiskState(true, identity, null, true),
            FenceDiskState(true, GenerationFenceIdentity("boot", 2, "transaction", "b".repeat(32)), identity, true),
            FenceDiskState(true, identity, GenerationFenceIdentity("boot", 2, "transaction", "b".repeat(32)), true),
        ).forEach { disk -> assertTrue(f.engine.planRestore("boot", GuardianRestoreFenceEvidence.SameBoot(disk), mapOf(fan.logicalId to GuardianObservedState.FanMode(1))).restoreIntents.isEmpty()) }
        val first = GuardianStockReadback(mapOf("cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0), fan.logicalId to GuardianObservedState.FanMode(0)), 100); val second = first.copy(observedElapsedRealtimeMs = 175)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, f.engine.confirmStock(first, second).state)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, f.engine.ownerCancelled().state)
        assertEquals(GuardianRunnerLifecycleState.EXITED, f.engine.exit(175).state)
    }

    @Test fun `same boot manual fan Stock remains a settlement barrier before compute repair`() {
        assertFanSettlementBarrier(listOf(TuningPlanner.FAN_MANUAL))
    }

    @Test fun `dual ownership envelope retains fan settlement barrier before compute repair`() {
        assertFanSettlementBarrier(listOf(TuningPlanner.FAN_SMART, TuningPlanner.FAN_MANUAL))
    }

    private fun assertFanSettlementBarrier(targets: List<Int>) {
        val manualFan = RestoreGuardianFanPutEntry(
            applyOrdinal = 0,
            expectedStock = RestoreGuardianStockState.PresentFanMode(4),
            rendererAppliedIntermediates = targets,
        )
        val manualEnvelope = baseEnvelope().copy(
            restoreEntries = listOf(manualFan, sysfs.deepCopy()),
        )
        val f = fixture()
        f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(manualEnvelope), 1, "boot")
        f.engine.admitReady(2)
        f.engine.beginWatching(3)
        f.engine.ownerCancelled()
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(manualEnvelope)
        val evidence = GuardianRestoreFenceEvidence.SameBoot(
            FenceDiskState(true, identity, identity, true),
        )

        val repair = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                manualFan.logicalId to GuardianObservedState.FanMode(4),
                sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0),
            ),
        )
        assertEquals(
            listOf(manualFan.logicalId, sysfs.logicalId),
            repair.restoreIntents.map { it.entry.logicalId },
        )

        val alreadyStock = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                manualFan.logicalId to GuardianObservedState.FanMode(4),
                sysfs.logicalId to GuardianObservedState.Sysfs(100, "644", 0, 0),
            ),
        )
        assertTrue(alreadyStock.restoreIntents.isEmpty())

        val missingFanTruth = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0)),
        )
        assertTrue(missingFanTruth.restoreIntents.isEmpty())

        val invalidFanTruth = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                manualFan.logicalId to GuardianObservedState.Sysfs(4, null, null, null),
                sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0),
            ),
        )
        assertTrue(invalidFanTruth.restoreIntents.isEmpty())
    }

    // OWED-1 (S4.3): fan ownership now defaults to SPORT (5), not MANUAL
    // (6). This is the exact SPORT counterpart of the MANUAL test directly above -- same fixture
    // shape, same four scenarios, same expected outcomes -- proving the fan-first settlement
    // barrier this Guardian watchdog process enforces was extended coherently, not just at the
    // app-process sites. Differential: reverting RestoreGuardianRunnerEngine.planRestore's `in
    // MIMIR_DUTY_OWNED_FAN_MODES` back to `== listOf(FAN_MANUAL)` makes the first assertion below
    // fail (a SPORT marker would no longer be recognised as the fan-first entry, so `repair` would
    // return only [sysfs.logicalId], compute-only, without the defensive fan reassert first).
    @Test fun `same boot SPORT-owned fan Stock remains a settlement barrier before compute repair`() {
        val sportFan = RestoreGuardianFanPutEntry(
            applyOrdinal = 0,
            expectedStock = RestoreGuardianStockState.PresentFanMode(4),
            rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SPORT),
        )
        val sportEnvelope = baseEnvelope().copy(
            restoreEntries = listOf(sportFan, sysfs.deepCopy()),
        )
        val f = fixture()
        f.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(sportEnvelope), 1, "boot")
        f.engine.admitReady(2)
        f.engine.beginWatching(3)
        f.engine.ownerCancelled()
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(sportEnvelope)
        val evidence = GuardianRestoreFenceEvidence.SameBoot(
            FenceDiskState(true, identity, identity, true),
        )

        val repair = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                sportFan.logicalId to GuardianObservedState.FanMode(4),
                sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0),
            ),
        )
        assertEquals(
            listOf(sportFan.logicalId, sysfs.logicalId),
            repair.restoreIntents.map { it.entry.logicalId },
        )

        val alreadyStock = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                sportFan.logicalId to GuardianObservedState.FanMode(4),
                sysfs.logicalId to GuardianObservedState.Sysfs(100, "644", 0, 0),
            ),
        )
        assertTrue(alreadyStock.restoreIntents.isEmpty())

        val missingFanTruth = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0)),
        )
        assertTrue(missingFanTruth.restoreIntents.isEmpty())

        val invalidFanTruth = f.engine.planRestore(
            "boot",
            evidence,
            mapOf(
                sportFan.logicalId to GuardianObservedState.Sysfs(4, null, null, null),
                sysfs.logicalId to GuardianObservedState.Sysfs(200, "644", 0, 0),
            ),
        )
        assertTrue(invalidFanTruth.restoreIntents.isEmpty())
    }

    @Test fun `durable READY resumes same boot watching and changed boot persistent restore only`() {
        val ready = durableReady(baseEnvelope().copy(hardDeadlineElapsedRealtimeMs = 40_000))
        val sameBoot = fixture()
        assertEquals(
            GuardianRunnerLifecycleState.WATCHING,
            sameBoot.engine.resumeFromDurableReady(ready, 10, "boot").state,
        )
        assertEquals(
            GuardianRunnerLifecycleState.WATCHING,
            sameBoot.engine.watchTick(10 + RestoreGuardianRunnerEngine.STALE_HEARTBEAT_THRESHOLD_MS - 1).state,
        )

        val changedBoot = fixture()
        assertEquals(
            GuardianRunnerLifecycleState.RESTORING,
            changedBoot.engine.resumeFromDurableReady(ready, 10, "newboot").state,
        )
        val proof = GuardianRestoreFenceEvidence.DifferentBootPersistent(
            "newboot",
            GuardianPersistentRestoreFenceProof(true, true, true),
        )
        val intents = changedBoot.engine.planRestore(
            "newboot",
            proof,
            mapOf(
                "cpu:0" to GuardianObservedState.Sysfs(200, "644", 0, 0),
                fan.logicalId to GuardianObservedState.FanMode(1),
            ),
        ).restoreIntents
        assertEquals(listOf(fan.logicalId), intents.map { it.entry.logicalId })
    }

    @Test fun `durable recovery retries same boot and reuses only the exact active identity`() {
        assertEquals(
            GuardianRecoveryRetryPlan.SAME_GENERATION,
            guardianRecoveryRetryPlan(GuardianRunnerLifecycleState.WATCHING),
        )
        assertEquals(
            GuardianRecoveryRetryPlan.EXISTING_CROSS_BOOT,
            guardianRecoveryRetryPlan(GuardianRunnerLifecycleState.RESTORING),
        )
        assertEquals(
            GuardianRecoveryRetryPlan.NONE,
            guardianRecoveryRetryPlan(GuardianRunnerLifecycleState.EXITED),
        )
        val value = baseEnvelope()
        assertTrue(guardianRecoveryIdentityMatches(value, sessionKey, value.deepCopy(), sessionKey))
        assertTrue(
            !guardianRecoveryIdentityMatches(
                value,
                sessionKey,
                value.copy(transactionId = "other-transaction"),
                sessionKey,
            ),
        )
        val otherSession = checkNotNull(GuardianProcessSessionAuthority.generate()).sessionPublicKey
        assertTrue(!guardianRecoveryIdentityMatches(value, sessionKey, value.deepCopy(), otherSession))
    }

    private fun baseEnvelope() = envelope.deepCopy()
    private fun watching(): Fixture = fixture().also { it.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(baseEnvelope()), 1, "boot"); it.engine.admitReady(2); it.engine.beginWatching(3) }
    private fun fence(): FenceDiskState {
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(baseEnvelope())
        return FenceDiskState(true, identity, identity, true)
    }
    private fun heartbeat(value: RestoreGuardianEnvelope, counter: Long, elapsed: Long) = RestoreGuardianHeartbeat(helperBuildIdentity = value.helperBuildIdentity, bootIdentity = value.bootIdentity, generation = value.generation, requestId = value.requestId, transactionId = value.transactionId, taskNonce = value.taskNonce, envelopeSha256 = value.digestSha256(), counter = counter, elapsedRealtimeMs = elapsed, hardDeadlineElapsedRealtimeMs = value.hardDeadlineElapsedRealtimeMs)
    private fun durableReady(value: RestoreGuardianEnvelope): GuardianArmStoreLoad.LoadedReady {
        val available = GuardianTestKeyAuthority.create(value.tweakerCertificateSha256).available
        val signedEnvelope = available.signEnvelope(value)
        val receipt = RestoreGuardianReadyReceipt(
            helperBuildIdentity = value.helperBuildIdentity,
            bootIdentity = value.bootIdentity,
            generation = value.generation,
            requestId = value.requestId,
            transactionId = value.transactionId,
            taskNonce = value.taskNonce,
            envelopeSha256 = value.digestSha256(),
            sessionPublicKey = sessionKey,
            issuedElapsedRealtimeMs = 2,
        )
        val signedReady = SignedRestoreGuardianReadyReceipt(
            receipt,
            EcdsaP256GuardianSessionSigner(sessionPair.private).sign(receipt.canonicalBytes()),
        )
        val pin = RestoreGuardianSessionPin(
            envelopeSha256 = value.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            readyReceiptSha256 = GuardianCanonicalCodec.sha256(receipt.canonicalBytes()),
            readyIssuedElapsedRealtimeMs = 2,
            pinnedElapsedRealtimeMs = 3,
        )
        return GuardianArmStoreLoad.LoadedReady(
            signedEnvelope,
            sessionKey,
            signedReady,
            available.signSessionPin(pin),
        )
    }
    private fun fixture(disposition: GuardianReceiptOutputDisposition = GuardianReceiptOutputDisposition.CONFIRMED, authentication: ByteArray = byteArrayOf(1), throws: Boolean = false): Fixture { val receipts = mutableListOf<GuardianAuthenticatedReceipt>(); return Fixture(RestoreGuardianRunnerEngine(GuardianReceiptAuthenticationStrategy { _, _ -> if (throws) error("boom") else authentication }, GuardianReceiptOutputPort { receipts += it; disposition }, sessionKey), receipts) }
    private data class Fixture(val engine: RestoreGuardianRunnerEngine, val receipts: MutableList<GuardianAuthenticatedReceipt>)
}
