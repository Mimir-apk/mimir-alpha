package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TransactionCoordinatorTest {
    @Test
    fun `default hard lease covers the complete bounded apply request`() {
        assertTrue(
            TransactionCoordinator.DEFAULT_LEASE_WINDOW_MS >=
                TransactionCoordinator.DEFAULT_REQUEST_WINDOW_MS,
        )
    }

    @Test
    fun `schema v5 verified restore never clears journal before Guardian settlement`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val base = appliedRecord(stockSnapshot(), schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5)
        val active = base.copy(
            phase = TransactionPhase.RESTORING,
            guardianBinding = GuardianJournalBinding(
                envelopeSha256 = "a".repeat(64),
                taskNonce = "b".repeat(32),
                helperBuildIdentity = "helper-build",
                tweakerCertificateSha256 = "c".repeat(64),
                hardDeadlineElapsedRealtimeMs = 9_000,
                readySessionSha256 = "d".repeat(64),
            ),
        )
        journal.record = active
        val coordinator = coordinator(rig, journal, hardwareWritesCompiled = true)

        // Differential pin: the public restore entry intentionally quarantines v5, so invoke the
        // exact verified-restore tail where the orphaning clear currently lives. Once fixed, this
        // path must retain durable journal truth until authenticated terminal settlement.
        val method = TransactionCoordinator::class.java.getDeclaredMethod(
            "attemptFullRestore",
            TransactionJournalRecord::class.java,
        ).apply { isAccessible = true }
        method.invoke(coordinator, active)

        assertEquals(0, journal.clears)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
    }

    @Test
    fun `schema v5 restore phases are quarantined with zero commands and zero clear`() {
        TransactionPhase.entries.forEach { phase ->
            val rig = StatefulHardwareRig()
            val journal = DurableFakeJournal()
            val base = appliedRecord(stockSnapshot(), schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5)
            journal.record = base.copy(
                phase = phase,
                guardianBinding = GuardianJournalBinding(
                    envelopeSha256 = "a".repeat(64),
                    taskNonce = "b".repeat(32),
                    helperBuildIdentity = "helper-build",
                    tweakerCertificateSha256 = "c".repeat(64),
                    hardDeadlineElapsedRealtimeMs = 9,
                ),
            )
            val result = coordinator(rig, journal, hardwareWritesCompiled = true).restore()
            assertEquals("$phase", "CROSS_STORE_RECONCILIATION_REQUIRED", result.code)
            assertEquals("$phase", 0, rig.executes)
            assertEquals("$phase", 0, journal.clears)
        }
    }
    @Test
    fun `non-product writer stays schema v4 with no Guardian binding when nothing is prepared`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val persisted = mutableListOf<TransactionJournalRecord>()
        journal.onPersist = { persisted += it }

        val result = coordinator(rig, journal)
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "unarmed")

        assertEquals("APPLIED", result.code)
        val prepared = persisted.first()
        assertEquals(TransactionPhase.PREPARED, prepared.phase)
        // No Guardian identity exists before PREPARED, so none is invented.
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V4, prepared.schemaVersion)
        assertNull(prepared.guardianBinding)
        assertTrue(persisted.none { it.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 })
    }

    @Test
    fun `production-shaped writer rejects missing prepared Guardian instead of falling back to v4`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
        ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "required")

        assertEquals("GUARDIAN_BINDING_UNAVAILABLE", result.code)
        assertFalse(result.success)
        assertFalse(result.applied)
        assertEquals(0, journal.persists)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `prepared Guardian envelope makes the new record authenticated schema v5 with zero mutation`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var seen: GuardianJournalIdentity? = null

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> seen = identity; preparedEnvelope(identity) },
        ).apply(
            "balanced",
            SystemWideProfileTransition.TARGET_PACKAGE_ID,
            capabilities(),
            dryRun = false,
            requestId = "armed",
        )

        // Differential vs the current unwired writer: that writer can only ever emit v4 here.
        val prepared = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V5, prepared.schemaVersion)
        assertNotEquals(TRANSACTION_JOURNAL_SCHEMA_V4, prepared.schemaVersion)
        assertEquals(TransactionPhase.ARMING, prepared.phase)

        val exact = preparedEnvelope(seen!!).envelope
        val binding = prepared.guardianBinding!!
        assertEquals(exact.digestSha256(), binding.envelopeSha256)
        assertEquals(exact.taskNonce, binding.taskNonce)
        assertEquals(exact.helperBuildIdentity, binding.helperBuildIdentity)
        assertEquals(exact.tweakerCertificateSha256, binding.tweakerCertificateSha256)
        assertEquals(exact.hardDeadlineElapsedRealtimeMs, binding.hardDeadlineElapsedRealtimeMs)
        // PREPARED/ARMING may never carry future READY/terminal evidence.
        assertNull(binding.readySessionSha256)
        assertNull(binding.terminalReceiptSha256)
        assertTrue(GuardianJournalBindingValidator.validate(prepared).isEmpty())
        assertEquals(SystemWideProfileTransition.TARGET_PACKAGE_ID, prepared.targetPackage)
        assertEquals(SystemWideProfileTransition.TARGET_PACKAGE_ID, prepared.appliedPlan?.packageName)

        // Mutation authority starts at durable Guardian READY, which is not admitted yet.
        assertEquals("GUARDIAN_ARM_RETAINED", result.code)
        assertFalse(result.success)
        assertFalse(result.applied)
        assertEquals(0, rig.executes)
        assertEquals(0, journal.clears)
        assertEquals(2, journal.persists)
        assertTrue(rig.isExactStock())

        // The exact production-shaped v5 record replays as a real crash boundary.
        assertEquals(
            GuardianTransactionOutcome.Resume(
                GuardianTransactionReconciliation.JOURNAL_ONLY_RETAIN,
                GuardianTransactionStep.DISCARD_UNMUTATED_JOURNAL,
            ),
            GuardianTransactionReconciler.adjudicate(journal.load(), GuardianArmStoreLoad.Empty),
        )
        // The production encoder accepts the exact record the writer produced.
        val encoded = AtomicFileTransactionJournal.encodeJournal(prepared)
        assertEquals(
            prepared,
            (AtomicFileTransactionJournal.decodeJournal(encoded) as JournalLoadResult.Loaded).record,
        )
    }

    @Test
    fun `a prepared envelope for another transaction fails closed instead of writing anything`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity ->
                preparedEnvelope(identity.copy(transactionId = "foreign-transaction"))
            },
        ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "foreign")

        assertEquals("GUARDIAN_BINDING_MISMATCH", result.code)
        assertFalse(result.success)
        assertEquals(0, journal.persists)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `failed Guardian envelope preparation never downgrades to a legacy write`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { error("Guardian preparation unavailable") },
        ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "unreadable")

        assertEquals("GUARDIAN_BINDING_UNAVAILABLE", result.code)
        assertFalse(result.success)
        assertEquals(0, journal.persists)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `exact Guardian READY is durable before APPLYING and the first command`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val phases = mutableListOf<TransactionPhase>()
        journal.onPersist = { phases += it.phase }
        val arm = FakeGuardianArmAuthority { signed, expected ->
            GuardianArmAdmission.Ready(
                GuardianReadyHandle(expected, signed, "d".repeat(64)),
            )
        }

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
            guardianArmAuthority = arm,
        ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "ready")

        assertEquals("APPLIED", result.code)
        assertTrue(result.applied)
        assertTrue(rig.executes > 0)
        assertTrue(phases.indexOf(TransactionPhase.PREPARED) < phases.indexOf(TransactionPhase.ARMING))
        assertTrue(phases.indexOf(TransactionPhase.ARMING) < phases.indexOf(TransactionPhase.GUARDIAN_READY))
        assertTrue(phases.indexOf(TransactionPhase.GUARDIAN_READY) < phases.indexOf(TransactionPhase.APPLYING))
        assertEquals(TransactionPhase.APPLIED, (journal.load() as JournalLoadResult.Loaded).record.phase)
        assertEquals("d".repeat(64), (journal.load() as JournalLoadResult.Loaded).record.guardianBinding?.readySessionSha256)
    }

    @Test
    fun `schema v5 restore succeeds only after authority proves journal and arm absent`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var armPresent = true
        val arm = object : GuardianArmAuthorityPort {
            override fun armToReady(
                signed: SignedRestoreGuardianEnvelope,
                expected: RestoreGuardianExpectedIdentity,
            ) = GuardianArmAdmission.Ready(
                GuardianReadyHandle(expected, signed, "d".repeat(64)),
            )

            override fun presence() = if (armPresent) {
                GuardianArmStorePresence.PRESENT
            } else {
                GuardianArmStorePresence.ABSENT
            }

            override fun verifyTerminal(handle: GuardianReadyHandle) =
                GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)

            override fun disarm(handle: GuardianReadyHandle) = false

            override fun restoreAndSettle(journalPort: TransactionJournal): GuardianCrossStoreSettlement {
                val record = (journalPort.load() as JournalLoadResult.Loaded).record
                armPresent = false
                assertTrue(journalPort.clear(record.transactionId))
                return GuardianCrossStoreSettlement.Settled
            }
        }
        val coordinator = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
            guardianArmAuthority = arm,
        )

        assertEquals(
            "APPLIED",
            coordinator.apply(
                "balanced",
                "example.game",
                capabilities(),
                dryRun = false,
                requestId = "settled",
            ).code,
        )
        val executesAfterApply = rig.executes
        val restored = coordinator.restore()

        assertTrue(restored.restored)
        assertEquals("RESTORED", restored.code)
        assertEquals(executesAfterApply, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertEquals(GuardianArmStorePresence.ABSENT, arm.presence())
    }

    @Test
    fun `schema v5 pre-ready restore clears staged arm before untouched journal`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var armPresent = true
        val arm = object : GuardianArmAuthorityPort {
            override fun armToReady(
                signed: SignedRestoreGuardianEnvelope,
                expected: RestoreGuardianExpectedIdentity,
            ) = GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.GUARDIAN_LAUNCH_AMBIGUOUS)

            override fun presence() = if (armPresent) {
                GuardianArmStorePresence.PRESENT
            } else {
                GuardianArmStorePresence.ABSENT
            }

            override fun verifyTerminal(handle: GuardianReadyHandle) =
                GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)

            override fun disarm(handle: GuardianReadyHandle) = false

            override fun restoreAndSettle(journalPort: TransactionJournal): GuardianCrossStoreSettlement {
                val record = (journalPort.load() as JournalLoadResult.Loaded).record
                assertEquals(TransactionPhase.ARMING, record.phase)
                assertEquals(0, record.lastCompletedMutationBoundary)
                assertNull(record.guardianBinding?.readySessionSha256)
                armPresent = false // arm-first
                assertTrue(journalPort.clear(record.transactionId))
                return GuardianCrossStoreSettlement.Settled
            }
        }
        val coordinator = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
            guardianArmAuthority = arm,
        )

        val applied = coordinator.apply(
            "balanced",
            "example.game",
            capabilities(),
            dryRun = false,
            requestId = "pre-ready",
        )
        assertEquals("GUARDIAN_ARM_RETAINED", applied.code)
        assertEquals(0, rig.executes)

        val restored = coordinator.restore()

        assertEquals("RESTORED", restored.code)
        assertTrue(restored.restored)
        assertEquals(0, rig.executes)
        assertEquals(GuardianArmStorePresence.ABSENT, arm.presence())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `Guardian rejection clears only after exact absent-arm proof`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val arm = FakeGuardianArmAuthority(
            presence = GuardianArmStorePresence.ABSENT,
        ) { _, _ ->
            GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.INVALID_ENVELOPE_SIGNATURE)
        }

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
            guardianArmAuthority = arm,
        ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "rejected")

        assertEquals("GUARDIAN_ARM_REJECTED", result.code)
        assertEquals(0, rig.executes)
        assertEquals(1, journal.clears)
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `Guardian retained and unproven rejection both retain stores with zero commands`() {
        listOf<GuardianArmAdmission>(
            GuardianArmAdmission.ArmRetained(GuardianArmRetainedReason.READY_UNCONFIRMED),
            GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.PREARM_PORT_FAILURE),
        ).forEach { admission ->
            val rig = StatefulHardwareRig()
            val journal = DurableFakeJournal()
            val arm = FakeGuardianArmAuthority(
                presence = GuardianArmStorePresence.PRESENT,
            ) { _, _ -> admission }

            val result = coordinator(
                rig,
                journal,
                requiresGuardianAuthority = true,
                guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
                guardianArmAuthority = arm,
            ).apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "retained-${admission.javaClass.simpleName}")

            assertEquals("GUARDIAN_ARM_RETAINED", result.code)
            assertEquals(0, rig.executes)
            assertEquals(0, journal.clears)
            assertEquals(TransactionPhase.ARMING, (journal.load() as JournalLoadResult.Loaded).record.phase)
        }
    }

    @Test
    fun `cancel after Guardian READY retains RESTORING truth and executes zero commands`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val arm = FakeGuardianArmAuthority { signed, expected ->
            GuardianArmAdmission.Ready(GuardianReadyHandle(expected, signed, "e".repeat(64)))
        }

        val result = coordinator(
            rig,
            journal,
            requiresGuardianAuthority = true,
            guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
            guardianArmAuthority = arm,
        ).apply(
            "balanced",
            "example.game",
            capabilities(),
            dryRun = false,
            requestId = "cancelled-ready",
            isCancelled = { true },
        )

        assertEquals("GUARDIAN_ARM_RETAINED", result.code)
        assertEquals(0, rig.executes)
        assertEquals(0, journal.clears)
        assertEquals(TransactionPhase.RESTORING, (journal.load() as JournalLoadResult.Loaded).record.phase)
    }

    @Test
    fun `SAFE compile-time gate prevents every hardware call`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal, hardwareWritesCompiled = false)

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false)

        assertFalse(result.success)
        assertEquals("WRITE_GATE_DISABLED", result.code)
        assertEquals(0, rig.executes)
        assertEquals(0, journal.persists)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `lab dry-run preview succeeds without hardware calls`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal)

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = true)

        assertTrue(result.success)
        assertEquals("DRY_RUN", result.code)
        assertEquals(0, rig.executes)
        assertEquals(0, journal.persists)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `uncalibrated plan cannot cross enabled write gate`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = TransactionCoordinator(
            TuningPlanner(),
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
        )

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false)

        assertEquals("PLAN_NOT_ADMITTED", result.code)
        assertEquals(0, rig.executes)
        assertEquals(0, journal.persists)
    }

    @Test
    fun `corrupt journal blocks new apply`() {
        val journal = DurableFakeJournal().apply { corruptReason = "truncated" }
        val rig = StatefulHardwareRig()
        val coordinator = coordinator(rig, journal)

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "r1")

        assertEquals("JOURNAL_CORRUPT", result.code)
        assertEquals(0, rig.executes)
    }

    @Test
    fun `incomplete capture is rejected before journal write`() {
        val rig = StatefulHardwareRig().apply { omitModeOnCapture = true }
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal)

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "cap")

        assertEquals("CAPTURE_INCOMPLETE", result.code)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertEquals(0, journal.persists)
        assertEquals(0, rig.executes)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `snapshot completeness rejects missing mode uid and gid`() {
        val base = stockSnapshot()
        val node = base.nodes.first { it.axis == TuningAxis.CPU }
        assertTrue(SnapshotCompleteness.validate(base).isEmpty())
        assertTrue(
            SnapshotCompleteness.validate(
                base.copy(nodes = base.nodes.map { if (it.logicalId == node.logicalId) it.copy(originalMode = null) else it }),
            ).any { "missing mode" in it },
        )
        assertTrue(
            SnapshotCompleteness.validate(
                base.copy(nodes = base.nodes.map { if (it.logicalId == node.logicalId) it.copy(originalUid = null) else it }),
            ).any { "missing UID" in it },
        )
        assertTrue(
            SnapshotCompleteness.validate(
                base.copy(nodes = base.nodes.map { if (it.logicalId == node.logicalId) it.copy(originalGid = null) else it }),
            ).any { "missing GID" in it },
        )
        assertTrue(
            SnapshotCompleteness.validate(
                base.copy(nodes = base.nodes.map { if (it.logicalId == node.logicalId) it.copy(originalValue = null) else it }),
            ).any { "missing value" in it },
        )
    }

    @Test
    fun `internal Guardian rollback notifies before apply returns even when settlement fails`() {
        for (settled in listOf(true, false)) {
            val rig = StatefulHardwareRig().apply { failExecuteIndex = 1 }
            val journal = DurableFakeJournal()
            var armPresent = true
            var notifications = 0
            var restoreFinished = false
            val arm = object : GuardianArmAuthorityPort {
                override fun armToReady(signed: SignedRestoreGuardianEnvelope, expected: RestoreGuardianExpectedIdentity) =
                    GuardianArmAdmission.Ready(GuardianReadyHandle(expected, signed, "d".repeat(64)))
                override fun presence() = if (armPresent) GuardianArmStorePresence.PRESENT else GuardianArmStorePresence.ABSENT
                override fun verifyTerminal(handle: GuardianReadyHandle) =
                    GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
                override fun disarm(handle: GuardianReadyHandle) = false
                override fun restoreAndSettle(journal: TransactionJournal): GuardianCrossStoreSettlement {
                    restoreFinished = true
                    if (!settled) return GuardianCrossStoreSettlement.Retained("TEST_RESTORE_FAILED")
                    val record = (journal.load() as JournalLoadResult.Loaded).record
                    armPresent = false
                    assertTrue(journal.clear(record.transactionId))
                    return GuardianCrossStoreSettlement.Settled
                }
            }
            val coordinator = coordinator(
                rig, journal,
                requiresGuardianAuthority = true,
                guardianPreparedEnvelope = { identity -> preparedEnvelope(identity) },
                guardianArmAuthority = arm,
                onRestoreAttemptFinished = {
                    assertTrue("stamp must follow the restore attempt", restoreFinished)
                    notifications++
                },
            )
            val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false)
            assertFalse(result.applied)
            assertEquals(settled, result.restored)
            assertTrue("fault must reach the remote restore seam", restoreFinished)
            assertEquals("caller may immediately re-apply after this result", 1, notifications)
        }
    }

    @Test
    fun `legacy internal rollback notifies before an immediate reapply`() {
        val rig = StatefulHardwareRig().apply { failPlanVerify = true }
        val journal = DurableFakeJournal()
        var notifications = 0
        val coordinator = coordinator(rig, journal, onRestoreAttemptFinished = { notifications++ })
        val failed = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false)
        assertTrue(failed.restored)
        assertEquals(1, notifications)
        rig.failPlanVerify = false
        assertTrue(coordinator.apply("balanced", "example.game", capabilities(), dryRun = false).applied)
        assertEquals("a successful apply is not a restore", 1, notifications)
    }

    @Test
    fun `failed apply restores verifies and clears durable journal`() {
        val rig = StatefulHardwareRig().apply { failPlanVerify = true }
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "req-a")

        assertEquals("VERIFY_FAILED", result.code)
        assertTrue(result.restored)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `failApply persists RESTORING before rollback commands`() {
        val phases = mutableListOf<TransactionPhase>()
        val rig = StatefulHardwareRig().apply { failExecuteIndex = 0 }
        val journal = DurableFakeJournal().apply {
            onPersist = { record -> phases += record.phase }
        }
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())

        val result = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "phase")

        assertTrue(result.restored)
        val restoringIndex = phases.indexOf(TransactionPhase.RESTORING)
        assertTrue(restoringIndex >= 0)
        assertTrue(phases.take(restoringIndex).none { it == TransactionPhase.RESTORE_FAILED })
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `APPLYING boundary zero recovers complete snapshot after reconstruction`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val barrier = OneShotDeathBarrier(TransactionBarrierPoint.AFTER_APPLY_COMMAND_BEFORE_READBACK, 0)
        val dying = coordinator(rig, journal, barrier = barrier, idFactory = sequentialIds())
        try {
            dying.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "death-cmd")
            fail("expected death")
        } catch (_: TransactionDeathException) {
            val loaded = journal.load() as JournalLoadResult.Loaded
            assertEquals(TransactionPhase.APPLYING, loaded.record.phase)
            assertEquals(0, loaded.record.lastCompletedMutationBoundary)
            assertFalse(rig.isExactStock())
        }

        val recovered = coordinator(rig, journal, idFactory = sequentialIds())
        val restored = recovered.restore()
        assertEquals("RESTORED", restored.code)
        assertTrue(restored.restored)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `new apply persists CPU ceilings only`() {
        val rig = StatefulHardwareRig(cpuMinByPolicy = mapOf(0 to 1_171_200))
        val journal = DurableFakeJournal()

        val applied = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("quiet", "example.game", rig.liveCapabilities(), dryRun = false, requestId = "ceiling-only")

        assertEquals("APPLIED", applied.code)
        val record = (journal.load() as JournalLoadResult.Loaded).record
        assertTrue(record.snapshot.cpuMinKhz.isEmpty())
        assertTrue(record.snapshot.nodes.none { it.logicalId.endsWith(":scaling_min_freq") })
        assertTrue(record.applyOrderLogicalIds.none { it.endsWith(":scaling_min_freq") })
    }

    @Test
    fun `CPU floor observations do not become new apply topology`() {
        val driftRig = StatefulHardwareRig(cpuMinByPolicy = mapOf(0 to 1_171_200))
        val driftCaps = driftRig.liveCapabilities()
        val driftJournal = DurableFakeJournal()
        val drift = coordinator(driftRig, driftJournal, idFactory = sequentialIds())
            .apply("quiet", "example.game", driftCaps, dryRun = false, requestId = "floor-drift")
        assertEquals("APPLIED", drift.code)
        val record = (driftJournal.load() as JournalLoadResult.Loaded).record
        assertTrue(record.snapshot.cpuMinKhz.isEmpty())
        assertTrue(record.applyOrderLogicalIds.none { it.endsWith(":scaling_min_freq") })
    }

    @Test
    fun `restore command before readback recovers exact stock after reconstruction`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val applied = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "applied")
        assertEquals("APPLIED", applied.code)
        assertFalse(rig.isExactStock())

        val barrier = OneShotDeathBarrier(TransactionBarrierPoint.AFTER_RESTORE_COMMAND_BEFORE_READBACK, 0)
        try {
            coordinator(rig, journal, barrier = barrier).restore()
            fail("expected death")
        } catch (_: TransactionDeathException) {
            assertTrue(journal.load() is JournalLoadResult.Loaded)
        }

        val recovered = coordinator(rig, journal)
        val restored = recovered.restore()
        assertEquals("RESTORED", restored.code)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `death at every apply and restore command boundary restores exact stock`() {
        val sampleRig = StatefulHardwareRig()
        val samplePlan = certifiedPlanner().plan("balanced", "example.game", capabilities())!!
        val mutationCount = sampleRig.plannedMutations(samplePlan, sampleRig.captureStockSnapshot()).size
        assertTrue(mutationCount > 1)

        val applyPhasePoints = listOf(
            TransactionBarrierPoint.AFTER_CAPTURE_BEFORE_JOURNAL to -1,
            TransactionBarrierPoint.AFTER_PREPARED to -1,
            TransactionBarrierPoint.AFTER_APPLYING_PHASE to -1,
            TransactionBarrierPoint.BEFORE_APPLY_VERIFY to -1,
            TransactionBarrierPoint.AFTER_APPLY_VERIFIED_BEFORE_APPLIED to -1,
            TransactionBarrierPoint.AFTER_APPLIED to -1,
        )
        for ((point, index) in applyPhasePoints) {
            assertDeathThenExactStockOrEmpty(point, index, duringApply = true)
        }
        for (index in 0 until mutationCount) {
            assertDeathThenExactStockOrEmpty(TransactionBarrierPoint.BEFORE_MUTATION, index, duringApply = true)
            assertDeathThenExactStockOrEmpty(
                TransactionBarrierPoint.AFTER_APPLY_COMMAND_BEFORE_READBACK,
                index,
                duringApply = true,
            )
            assertDeathThenExactStockOrEmpty(TransactionBarrierPoint.AFTER_MUTATION, index, duringApply = true)
        }

        val restorePhasePoints = listOf(
            TransactionBarrierPoint.AFTER_RESTORING_PHASE to -1,
            TransactionBarrierPoint.BEFORE_RESTORE_VERIFY to -1,
            TransactionBarrierPoint.AFTER_RESTORE_VERIFIED_BEFORE_CLEAR to -1,
        )
        for ((point, index) in restorePhasePoints) {
            assertDeathThenExactStockOrEmpty(point, index, duringApply = false)
        }
        for (index in 0 until mutationCount) {
            assertDeathThenExactStockOrEmpty(TransactionBarrierPoint.BEFORE_RESTORE_MUTATION, index, duringApply = false)
            assertDeathThenExactStockOrEmpty(
                TransactionBarrierPoint.AFTER_RESTORE_COMMAND_BEFORE_READBACK,
                index,
                duringApply = false,
            )
            assertDeathThenExactStockOrEmpty(TransactionBarrierPoint.AFTER_RESTORE_MUTATION, index, duringApply = false)
        }
    }

    @Test
    fun `persist failure at every phase retains recovery truth or blocks unproven PREPARED`() {
        data class Case(
            val label: String,
            val duringApply: Boolean,
            val matcher: (TransactionJournalRecord) -> Boolean,
            val expectHardwareChangedBeforeFail: Boolean = true,
        )
        val sampleRig = StatefulHardwareRig()
        val samplePlan = certifiedPlanner().plan("balanced", "example.game", capabilities())!!
        val mutationCount = sampleRig.plannedMutations(samplePlan, sampleRig.captureStockSnapshot()).size

        val cases = mutableListOf(
            Case("PREPARED", true, { it.phase == TransactionPhase.PREPARED }, expectHardwareChangedBeforeFail = false),
            Case("APPLYING", true, { it.phase == TransactionPhase.APPLYING }),
            Case("APPLIED", true, { it.phase == TransactionPhase.APPLIED }),
            Case("RESTORING", false, { it.phase == TransactionPhase.RESTORING }),
            Case("RESTORE_FAILED", false, {
                it.phase == TransactionPhase.RESTORE_FAILED
            }),
        )
        for (index in 0 until mutationCount) {
            cases += Case(
                "apply-boundary-$index",
                true,
                {
                    it.phase == TransactionPhase.APPLYING &&
                        it.lastCompletedMutationBoundary == index + 1
                },
            )
            cases += Case(
                "restore-boundary-$index",
                false,
                {
                    it.phase == TransactionPhase.RESTORING &&
                        it.lastCompletedRestoreBoundary == index + 1
                },
            )
        }

        for (case in cases) {
            val rig = StatefulHardwareRig()
            val journal = DurableFakeJournal()
            if (!case.duringApply) {
                assertEquals(
                    "APPLIED",
                    coordinator(rig, journal, idFactory = sequentialIds())
                        .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "pre-${case.label}")
                        .code,
                )
                assertFalse(rig.isExactStock())
                if (case.label == "RESTORE_FAILED") {
                    // Force restore verification failure after mutations so RESTORE_FAILED is attempted.
                    journal.failPersistOnceMatching = case.matcher
                    rig.failSnapshotVerify = true
                    val restored = coordinator(rig, journal).restore()
                    assertEquals("RESTORE_FAILED", restored.code)
                    // RESTORE_FAILED persist rejected — prior recovery truth must remain nonempty.
                    val retained = journal.load()
                    assertTrue("empty journal after ${case.label}", retained is JournalLoadResult.Loaded)
                    val phase = (retained as JournalLoadResult.Loaded).record.phase
                    assertTrue(
                        phase == TransactionPhase.RESTORING || phase == TransactionPhase.RESTORE_FAILED,
                    )
                    continue
                }
            }
            journal.failPersistOnceMatching = case.matcher
            if (!case.duringApply && case.label != "RESTORE_FAILED") {
                val result = coordinator(rig, journal).restore()
                when (val loaded = journal.load()) {
                    is JournalLoadResult.Empty -> {
                        assertTrue("stock mismatch after ${case.label}", rig.isExactStock())
                        assertTrue(result.restored)
                    }
                    is JournalLoadResult.Loaded -> {
                        assertTrue(
                            "unexpected phase ${loaded.record.phase} for ${case.label}",
                            loaded.record.phase == TransactionPhase.RESTORING ||
                                loaded.record.phase == TransactionPhase.RESTORE_FAILED,
                        )
                    }
                    is JournalLoadResult.Corrupt -> fail("corrupt at ${case.label}")
                }
            } else if (case.duringApply) {
                val result = coordinator(rig, journal, idFactory = sequentialIds())
                    .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "fail-${case.label}")
                if (case.label == "PREPARED") {
                    assertEquals("JOURNAL_PERSIST_FAILED", result.code)
                    assertTrue(journal.load() is JournalLoadResult.Empty)
                    assertEquals(0, rig.executes)
                    assertTrue(rig.isExactStock())
                } else {
                    when (val loaded = journal.load()) {
                        is JournalLoadResult.Empty -> assertTrue("stock mismatch after ${case.label}", rig.isExactStock())
                        is JournalLoadResult.Loaded -> {
                            assertTrue(
                                "unexpected phase ${loaded.record.phase} for ${case.label}",
                                loaded.record.phase == TransactionPhase.RESTORING ||
                                    loaded.record.phase == TransactionPhase.RESTORE_FAILED ||
                                    loaded.record.phase == TransactionPhase.APPLYING ||
                                    loaded.record.phase == TransactionPhase.PREPARED ||
                                    loaded.record.phase == TransactionPhase.APPLIED,
                            )
                        }
                        is JournalLoadResult.Corrupt -> fail("corrupt at ${case.label}")
                    }
                }
            }
        }

        // Explicit clear-step failure after verified restore.
        val clearRig = StatefulHardwareRig()
        val clearJournal = DurableFakeJournal().apply { clearSucceeds = false }
        assertEquals(
            "APPLIED",
            coordinator(clearRig, clearJournal, idFactory = sequentialIds())
                .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "clear-fail")
                .code,
        )
        val clearResult = coordinator(clearRig, clearJournal).restore()
        assertEquals("RESTORE_FAILED", clearResult.code)
        val retained = clearJournal.load() as JournalLoadResult.Loaded
        assertEquals(TransactionPhase.RESTORE_FAILED, retained.record.phase)
        assertTrue(clearRig.isExactStock())
    }

    @Test
    fun `RESTORE_FAILED retry resets restore boundary and repairs drifted prior node`() {
        val caps = capabilities()
        val snapshot = stockSnapshot(caps)
        val plan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        val rig = StatefulHardwareRig()
        // Drift first CPU node away from stock while journal claims prior restore progress.
        val firstCpu = snapshot.nodes.first { it.axis == TuningAxis.CPU }
        rig.perturbSysfs(firstCpu.canonicalPath, value = (firstCpu.originalValue ?: 0) - 1000)
        assertFalse(rig.isExactStock())

        val journal = DurableFakeJournal().apply {
            val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)
                ?: error("retry snapshot failed integrity bind")
            val applyOrder = HistoricApplyOrder.v1LogicalIds(plan.profileId, snapshot)
            val projectedById = rig.plannedMutations(plan, snapshot).associateBy { it.logicalId }
            record = TransactionJournalRecord(
                generation = 3,
                requestId = "retry",
                transactionId = "tx-retry",
                hardwareFingerprint = snapshot.hardwareFingerprint,
                deviceProfile = "rp5",
                planHash = PlanHash.of(plan),
                profileId = plan.profileId,
                targetPackage = plan.packageName,
                phase = TransactionPhase.RESTORE_FAILED,
                creationTime = 1_000L,
                requestDeadline = 31_000L,
                leaseDeadline = 901_000L,
                bootIdentity = snapshot.bootIdentity,
                snapshot = snapshot,
                expectedTopologySha256 = hashes.first,
                snapshotSha256 = hashes.second,
                lastCompletedMutationBoundary = snapshot.nodes.size,
                lastCompletedRestoreBoundary = 2,
                lastFailure = "prior restore verification failed",
                appliedPlan = PersistedTuningPlan.from(plan),
                applyOrderLogicalIds = applyOrder,
                appliedMutationProjection = applyOrder.map { logicalId ->
                    PersistedAppliedMutation.from(projectedById.getValue(logicalId))
                },
            )
        }
        val restoringBoundaries = mutableListOf<Int>()
        journal.onPersist = { persisted ->
            if (persisted.phase == TransactionPhase.RESTORING) {
                restoringBoundaries += persisted.lastCompletedRestoreBoundary
            }
        }

        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORED", restored.code)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(restoringBoundaries.isNotEmpty())
        assertEquals(0, restoringBoundaries.first())
        assertTrue(restoringBoundaries.any { it == snapshot.nodes.size })
    }

    @Test
    fun `real port settles coupled fan before compute and preserves literal reverse restore`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val snapshot = stockSnapshot(caps)

        // "performance"'s static ProfileShape carries fanMode=FAN_SPORT(5) as a generic vendor
        // passthrough value on every device (TuningPlanner.profiles) -- this device is "rp5", not
        // Odin 3, so this is NOT TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES
        // territory (RestoreMutationOrder's fan-first exception is a numeric-value check, not
        // device-gated, and is specifically about the Odin-3 vendor duty loop). Pin an explicit
        // non-owned value so this test keeps proving what its name says -- literal/plain reverse
        // restore -- independent of that unrelated coincidence. ManualFanSafetyTest and
        // RestoreGuardianRunnerEngineTest cover the actual fan-first exception directly.
        val performancePlan = certifiedPlanner().plan("performance", "example.game", caps)!!
            .copy(fanMode = TuningPlanner.FAN_SMART)
        val performanceApply = port.plannedMutations(performancePlan, snapshot)
        val performanceRecord = appliedRecord(snapshot, performancePlan, performanceApply.map { it.logicalId })
        val performanceRestore = port.restoreMutations(performanceRecord)
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, performanceApply.first().logicalId)
        assertTrue(performanceApply.drop(1).all { it.axis != TuningAxis.FAN })
        assertEquals(performanceApply.map { it.logicalId }.reversed(), performanceRestore.map { it.logicalId })
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, performanceRestore.last().logicalId)
        assertEquals(snapshot.nodes.size, performanceRestore.size)

        val quietPlan = certifiedPlanner().plan("quiet", "example.game", caps)!!
        val quietApply = port.plannedMutations(quietPlan, snapshot)
        val quietRecord = appliedRecord(snapshot, quietPlan, quietApply.map { it.logicalId })
        val quietRestore = port.restoreMutations(quietRecord)
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, quietApply.first().logicalId)
        assertTrue(quietApply.drop(1).all { it.axis != TuningAxis.FAN })
        assertEquals(quietApply.map { it.logicalId }.reversed(), quietRestore.map { it.logicalId })
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, quietRestore.last().logicalId)
        assertEquals(snapshot.nodes.size, quietRestore.size)

        val balancedPlan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        val balancedApply = port.plannedMutations(balancedPlan, snapshot)
        val balancedRecord = appliedRecord(snapshot, balancedPlan, balancedApply.map { it.logicalId })
        val balancedRestore = port.restoreMutations(balancedRecord)
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, balancedApply.first().logicalId)
        assertTrue(balancedApply.drop(1).all { it.axis != TuningAxis.FAN })
        assertEquals(balancedApply.map { it.logicalId }.reversed(), balancedRestore.map { it.logicalId })
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, balancedRestore.last().logicalId)
    }

    @Test
    fun `Odin quiet real port preserves vendor GPU minimum and applies calibrated max ceiling`() {
        val caps = odinCapabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val quiet = certifiedPlanner().plan("quiet", "example.game", caps)!!
        val quietSnapshot = transactionSnapshot(caps, quiet).copy(
            nodes = transactionSnapshot(caps, quiet).nodes.filterNot {
                it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID
            },
        )
        assertTrue(quietSnapshot.cpuMinKhz.isEmpty())

        val apply = port.plannedMutations(quiet, quietSnapshot)
        val cpuApply = apply.filter { it.axis == TuningAxis.CPU }
        assertTrue(apply.none { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID })
        assertEquals(
            quiet.gpuMaxPowerLevel,
            apply.single { it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID }.value,
        )
        assertEquals(
            listOf("cpu:0:scaling_max_freq", "cpu:6:scaling_max_freq"),
            cpuApply.map { it.logicalId },
        )
        assertEquals(
            listOf(quiet.cpuMaxKhz.getValue(0), quiet.cpuMaxKhz.getValue(6)),
            cpuApply.map { it.value },
        )

        val record = appliedRecord(
            snapshot = quietSnapshot,
            plan = quiet,
            applyOrder = apply.map { it.logicalId },
            caps = caps,
        ).copy(
            deviceProfile = caps.deviceProfileId,
            appliedMutationProjection = apply.map(PersistedAppliedMutation::from),
        )
        assertTrue(AppliedMutationProjectionValidator.validate(record).isEmpty())
        val restore = port.restoreMutations(record)
        assertEquals(
            listOf("cpu:6:scaling_max_freq", "cpu:0:scaling_max_freq"),
            restore.filter { it.axis == TuningAxis.CPU }.map { it.logicalId },
        )

        val performance = certifiedPlanner().plan("performance", "example.game", caps)!!
        val performanceSnapshot = transactionSnapshot(caps, performance)
        assertTrue(performanceSnapshot.cpuMinKhz.isEmpty())
        assertTrue(
            port.plannedMutations(performance, performanceSnapshot)
                .none { it.logicalId.endsWith(":scaling_min_freq") },
        )
    }

    @Test
    fun `GPU minimum changes only when required to admit the requested cap`() {
        val caps = capabilities()
        val commands = mutableListOf<String>()
        val port = PServerHardwareCommandPort(
            pServer = confirmingClient(commands),
            reader = SystemReader(),
            capabilities = { caps },
        )
        // Synthetic capped plan keeps the transport's generic range-admission branch covered
        // independently from exact-device profile calibration.
        val capped = certifiedPlanner().plan("quiet", "example.game", caps)!!.copy(
            gpuMaxPowerLevel = 2,
        )
        val performance = certifiedPlanner().plan("performance", "example.game", caps)!!
        val cappedTarget = capped.gpuMaxPowerLevel!!
        fun snapshotWithMin(plan: TuningPlan, value: Int) = transactionSnapshot(caps, plan).copy(
            nodes = transactionSnapshot(caps, plan).nodes.map { node ->
                if (node.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID) {
                    node.copy(originalValue = value)
                } else {
                    node
                }
            },
        )
        fun plannedMin(plan: TuningPlan, value: Int): Int? =
            port.plannedMutations(plan, snapshotWithMin(plan, value))
            .single { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }
            .value

        assertEquals(cappedTarget, plannedMin(capped, cappedTarget))
        assertEquals(1, plannedMin(performance, 1))
        assertEquals(caps.gpuFrequenciesKhz.lastIndex, plannedMin(capped, cappedTarget - 1))
        try {
            plannedMin(capped, caps.gpuFrequenciesKhz.size)
            fail("expected captured GPU minimum range rejection")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("outside live range"))
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `Odin slower Quiet ceiling moves the live GPU bound to the same admitted level`() {
        val caps = odinCapabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("quiet", "example.game", caps)!!
        val target = checkNotNull(plan.gpuMaxPowerLevel)
        // Sequence 9 moved quiet's Odin GPU ceiling from factor-derived level 8 to the
        // explicit level 5 -- assert the cross-profile
        // invariant this test actually cares about (quiet is always the slowest tunable
        // profile, so its level must exceed balanced's), rather than a magic threshold that
        // would need re-tuning by hand every time either level changes.
        assertTrue(target > TuningPlanner.ODIN_3_BALANCED_GPU_MAX_POWER_LEVEL)

        // Level 5 is gentle enough that the shared transactionSnapshot()'s default captured
        // GPU_MIN (level 6) already admits it without moving -- the OTHER branch of
        // PServerHardwareCommandPort.plannedMutations ("capturedMin >= target -> capturedMin").
        // This test is specifically about the ODIN "widen GPU_MIN to match a slower max" branch
        // (the same admitted level the test name refers to), so its captured min is overridden
        // here, locally, to stay one live level faster than quiet's new ceiling -- the shared
        // helper's default is intentionally left alone since many other tests depend on it.
        val capturedFasterThanQuiet = target - 1
        val snapshot = transactionSnapshot(caps, plan).copy(
            nodes = transactionSnapshot(caps, plan).nodes.map { node ->
                if (node.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID) {
                    node.copy(originalValue = capturedFasterThanQuiet)
                } else {
                    node
                }
            },
        )
        val gpuApply = port.plannedMutations(plan, snapshot).filter { it.axis == TuningAxis.GPU }

        assertEquals(
            listOf(HardwareSnapshot.GPU_MIN_LOGICAL_ID, HardwareSnapshot.GPU_MAX_LOGICAL_ID),
            gpuApply.map { it.logicalId },
        )
        assertEquals(listOf(target, target), gpuApply.map { it.value })
    }

    /**
     * **withdrew the 2026-08-18 floor: Odin balanced emits NO GPU minimum.**
     *
     * The 2026-08-18 floor change raised `min_pwrlevel` 6 -> 3 (660 -> 900 MHz). Measured on the Odin
     * `reference device`, the firmware resets that node back to 6 within ~5 s of a readback-verified write,
     * the supervisor correctly reads the mismatch as contract drift and restores stock, and the
     * frontend correctly re-arms the standing profile -- a livelock observed past generation 1244.
     *
     * Note what the withdrawal does and does not change. The GPU_MIN mutation is still emitted and
     * the node is still owned and still restorable -- what changes is its VALUE, which falls back
     * to the vendor's own captured bound (6). That is the fix: the contract minimum now equals the
     * number the firmware itself maintains, so `sample.gpuMinPowerLevel != contract.gpuMinPowerLevel`
     * can no longer be true and `TARGET_CONTRACT_DRIFT_GPU_MIN` becomes unreachable on this device.
     * Writing 6 over 6 is a no-op the firmware has no reason to fight.
     */
    @Test
    fun `Odin balanced admits the vendors captured GPU floor, not the withdrawn raised one`() {
        val caps = odinCapabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        val target = checkNotNull(plan.gpuMaxPowerLevel)
        // The shared snapshot's captured GPU_MIN is the vendor's live value 6 -- exactly the
        // state the floor change addressed (capturedMin >= target used to preserve 6).
        val snapshot = transactionSnapshot(caps, plan)
        assertEquals(
            6,
            snapshot.nodes.single { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }.originalValue,
        )

        val gpuApply = port.plannedMutations(plan, snapshot).filter { it.axis == TuningAxis.GPU }

        // Shape is unchanged -- min then max, both still owned and both still restorable.
        assertEquals(
            listOf(HardwareSnapshot.GPU_MIN_LOGICAL_ID, HardwareSnapshot.GPU_MAX_LOGICAL_ID),
            gpuApply.map { it.logicalId },
        )
        assertEquals(target, gpuApply.last().value)

        // The value is what changed, and it is the whole fix.
        val admittedMin = gpuApply.first().value!!
        assertEquals("balanced must admit the vendor's own captured bound", 6, admittedMin)
        assertNotEquals(
            "the withdrawn 900 MHz floor must never be emitted again -- the firmware resets it " +
                "within ~5s and the supervisor then restores stock forever",
            TuningPlanner.ODIN_3_BALANCED_GPU_MIN_POWER_LEVEL,
            admittedMin,
        )

        // The property that actually kills the livelock, stated independently of the value above:
        // the admitted minimum equals the captured one, so the supervisor's drift predicate
        // (sample != contract) compares a number against itself and cannot fire.
        val capturedMin =
            snapshot.nodes.single { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }.originalValue
        assertEquals(
            "a contract minimum the firmware does not itself maintain is what livelocked the Odin",
            capturedMin,
            admittedMin,
        )

        // The pre-existing invariant this must never weaken: the floor still admits the ceiling
        // (inverted indices -- larger is slower).
        assertTrue(admittedMin >= target)
        // And restore authority is untouched: revert still returns the floor to the vendor's 6.
        assertEquals(6, capturedMin)
    }

    /**
     * The 2026-08-18 floor change is balanced-only: quiet's signed ceiling (level 5, 734 MHz)
     * is itself below 900 MHz so a 900 MHz floor cannot admit it, and performance uses its separate envelope. Both must keep today's derived behaviour byte-identical -- the captured vendor
     * bound is preserved when it already admits the ceiling.
     */
    @Test
    fun `Odin quiet and performance keep the captured GPU floor during balanced-only adjustment`() {
        val caps = odinCapabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        listOf("quiet", "performance").forEach { profileId ->
            val plan = certifiedPlanner().plan(profileId, "example.game", caps)!!
            val gpuMin = port.plannedMutations(plan, transactionSnapshot(caps, plan))
                .single { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }
            assertEquals(profileId, 6, gpuMin.value)
        }
    }

    @Test
    fun `non-Odin balanced keeps the derived GPU floor byte-identical`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        val capturedMin = caps.gpuFrequenciesKhz.lastIndex
        val snapshot = transactionSnapshot(caps, plan).copy(
            nodes = transactionSnapshot(caps, plan).nodes.map { node ->
                if (node.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID) {
                    node.copy(originalValue = capturedMin)
                } else {
                    node
                }
            },
        )
        val gpuMin = port.plannedMutations(plan, snapshot)
            .single { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }
        // The generic branch: a captured bound that already admits the ceiling is preserved.
        assertEquals(capturedMin, gpuMin.value)
    }

    @Test
    fun `GPU minimum absent from captured topology stays outside mutation and restore authority`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("quiet", "example.game", caps)!!.copy(fanMode = null)
        val snapshot = transactionSnapshot(caps, plan).copy(
            nodes = transactionSnapshot(caps, plan).nodes.filterNot {
                it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID
            },
        )

        val apply = port.plannedMutations(plan, snapshot)
        assertTrue(apply.none { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID })
        assertEquals(
            plan.gpuMaxPowerLevel,
            apply.single { it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID }.value,
        )
        assertEquals(
            SnapshotIntegrity.identitiesOf(snapshot),
            SnapshotIntegrity.expectedTopology(plan, caps, includeGpuMin = false),
        )
        val record = appliedRecord(
            snapshot = snapshot,
            plan = plan,
            applyOrder = apply.map { it.logicalId },
            caps = caps,
        ).copy(
            appliedMutationProjection = apply.map(PersistedAppliedMutation::from),
        )
        assertTrue(AppliedMutationProjectionValidator.validate(record).isEmpty())
        assertTrue(
            port.restoreMutations(record)
                .none { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID },
        )
    }

    @Test
    fun `decoder rejects incomplete duplicate mismatched and illegal snapshot integrity`() {
        val base = appliedRecord(stockSnapshot())
        fun corrupt(mutate: (org.json.JSONObject) -> Unit): JournalLoadResult {
            val root = org.json.JSONObject(AtomicFileTransactionJournal.encodeJournal(base))
            mutate(root)
            return AtomicFileTransactionJournal.decodeJournal(root.toString())
        }
        fun nodesOf(root: org.json.JSONObject): org.json.JSONArray =
            root.getJSONObject("snapshot").getJSONArray("nodes")

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            // Drop first CPU node; leave expectedTopologySha256 bound to full plan.
            nodes.remove(0)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val gpuMinIndex = (0 until nodes.length()).first {
                nodes.getJSONObject(it).getString("logicalId") == HardwareSnapshot.GPU_MIN_LOGICAL_ID
            }
            nodes.remove(gpuMinIndex)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val fanIndex = (0 until nodes.length()).first {
                nodes.getJSONObject(it).getString("logicalId") == HardwareSnapshot.FAN_LOGICAL_ID
            }
            nodes.remove(fanIndex)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            nodes.put(org.json.JSONObject(nodes.getJSONObject(0).toString()))
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val dup = org.json.JSONObject(nodes.getJSONObject(0).toString())
            dup.put("logicalId", "cpu:999:scaling_max_freq")
            nodes.put(dup)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val cpu = nodes.getJSONObject(0)
            cpu.put("axis", TuningAxis.GPU.name)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val cpu = nodes.getJSONObject(0)
            cpu.put("originalValue", (cpu.getInt("originalValue") - 1000))
            // Leave snapshotSha256 stale.
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val fan = (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .first { it.getString("logicalId") == HardwareSnapshot.FAN_LOGICAL_ID }
            fan.put("present", true)
            fan.put("originalValue", org.json.JSONObject.NULL)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val fan = (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .first { it.getString("logicalId") == HardwareSnapshot.FAN_LOGICAL_ID }
            fan.put("present", false)
            fan.put("originalValue", 4)
        } is JournalLoadResult.Corrupt)

        assertTrue(corrupt { root ->
            val nodes = nodesOf(root)
            val fan = (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .first { it.getString("logicalId") == HardwareSnapshot.FAN_LOGICAL_ID }
            fan.put("present", true)
            fan.put("originalValue", 99)
        } is JournalLoadResult.Corrupt)

        // Round-trip of an integrity-bound record remains Loaded.
        val ok = AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(base),
        )
        assertTrue(ok is JournalLoadResult.Loaded)
    }

    @Test
    fun `runtime topology mismatch executes zero restore commands and retains RESTORE_FAILED`() {
        data class Case(val label: String, val mutate: (HardwareSnapshot) -> HardwareSnapshot)
        val caps = capabilities()
        val plan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        val full = stockSnapshot(caps)
        val expected = SnapshotIntegrity.expectedTopology(plan, caps)!!
        val cases = listOf(
            Case("missing CPU") { snap ->
                snap.copy(nodes = snap.nodes.filterNot { it.logicalId == "cpu:0:scaling_max_freq" })
            },
            Case("missing GPU") { snap ->
                snap.copy(nodes = snap.nodes.filterNot { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID })
            },
            Case("missing fan") { snap ->
                snap.copy(nodes = snap.nodes.filterNot { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID })
            },
            Case("wrong logicalId axis path") { snap ->
                snap.copy(
                    nodes = snap.nodes.map { node ->
                        if (node.logicalId == "cpu:0:scaling_max_freq") {
                            node.copy(
                                logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                                axis = TuningAxis.GPU,
                                canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
                            )
                        } else {
                            node
                        }
                    },
                )
            },
            Case("stale snapshot hash after value change") { snap ->
                snap.copy(
                    nodes = snap.nodes.map { node ->
                        if (node.logicalId == "cpu:0:scaling_max_freq") {
                            node.copy(originalValue = (node.originalValue ?: 0) - 1000)
                        } else {
                            node
                        }
                    },
                )
            },
        )
        cases.forEach { case ->
            val mutated = case.mutate(full)
            val topologySha = when (case.label) {
                "stale snapshot hash after value change" -> SnapshotIntegrity.topologySha256(expected)
                else -> SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(mutated))
            }
            val digestSha = when (case.label) {
                "stale snapshot hash after value change" -> SnapshotIntegrity.snapshotSha256(full)
                else -> SnapshotIntegrity.snapshotSha256(mutated)
            }
            val hotRig = StatefulHardwareRig()
            val warmJournal = DurableFakeJournal()
            assertEquals(
                "APPLIED",
                coordinator(hotRig, warmJournal, idFactory = sequentialIds())
                    .apply("balanced", "example.game", caps, dryRun = false, requestId = "hot-${case.label}")
                    .code,
            )
            val executesBefore = hotRig.executes
            val tampered = DurableFakeJournal().apply {
                record = TransactionJournalRecord(
                    generation = 9,
                    requestId = "mismatch-${case.label}",
                    transactionId = "tx-mismatch-${case.label}",
                    hardwareFingerprint = full.hardwareFingerprint,
                    deviceProfile = "rp5",
                    planHash = PlanHash.of(plan),
                    profileId = plan.profileId,
                    targetPackage = plan.packageName,
                    phase = TransactionPhase.APPLIED,
                    creationTime = 1_000L,
                    requestDeadline = 31_000L,
                    leaseDeadline = 901_000L,
                    bootIdentity = full.bootIdentity,
                    snapshot = mutated,
                    expectedTopologySha256 = topologySha,
                    snapshotSha256 = digestSha,
                    lastCompletedMutationBoundary = mutated.nodes.size.coerceAtMost(full.nodes.size),
                    appliedPlan = PersistedTuningPlan.from(plan),
                    applyOrderLogicalIds = mutated.nodes.map { it.logicalId },
                )
            }
            val restored = coordinator(hotRig, tampered).restore()
            assertEquals(case.label, "RESTORE_FAILED", restored.code)
            assertEquals(case.label, executesBefore, hotRig.executes)
            val loaded = tampered.load() as JournalLoadResult.Loaded
            assertEquals(case.label, TransactionPhase.RESTORE_FAILED, loaded.record.phase)
            assertFalse(case.label, hotRig.isExactStock())
        }
    }

    @Test
    fun `stock fan mode zero applies Mimir mode then restores exact zero`() {
        val rig = StatefulHardwareRig(initialFanMode = 0)
        val journal = DurableFakeJournal()
        assertEquals(0, rig.fanMode)

        val applied = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "fan0")
        assertEquals("APPLIED", applied.code)
        assertEquals(TuningPlanner.FAN_SMART, rig.fanMode)

        val caps = capabilities()
        val snapshot = (journal.load() as JournalLoadResult.Loaded).record.snapshot
        // 2026-08-23: the bounded restore-settlement barrier is no longer exact-Odin-gated, so this
        // RP5 fixture (a fan-bearing device) now runs it. Give the port a truthful fan_mode reading
        // and a no-op sleeper: the default `fanModeRaw = { null }` would fail the settle and turn
        // this test into an assertion about the fixture rather than about the restore. The
        // transport observes the `settings put system fan_mode N` write, so the reading is the
        // value actually written, not a constant the test asserts against itself.
        var liveFanMode = rig.fanMode
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = FanObservingTransport { liveFanMode = it }),
            reader = SystemReader(),
            capabilities = { caps },
            fanModeRaw = { liveFanMode.toString() },
            sleeper = {},
        )
        val restoreFan = port.restoreMutations(
            (journal.load() as JournalLoadResult.Loaded).record,
        ).first { it.axis == TuningAxis.FAN }
        assertTrue(restoreFan.capturedSnapshotAuthority)
        assertEquals(0, restoreFan.value)
        assertEquals(LogicalMutationKind.SETTINGS_PUT, restoreFan.kind)
        val executed = port.execute(restoreFan)
        assertEquals(CheckedCommandStatus.CONFIRMED, executed.status)

        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORED", restored.code)
        assertEquals(0, rig.fanMode)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `SysfsPath rejects traversal and canonical escape outside sys`() {
        assertNull(SysfsPath.canonicalize("/sys/foo/../bar"))
        assertNull(SysfsPath.canonicalize("/sys/foo/./bar"))
        assertNull(SysfsPath.canonicalize("/sys/foo\\bar"))
        assertNull(SysfsPath.canonicalize("/tmp/evil"))
        val escaped = SysfsPath.canonicalize(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            resolver = SysfsCanonicalResolver { "/data/local/tmp/escaped" },
        )
        assertNull(escaped)
        val ok = SysfsPath.canonicalize(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            resolver = SysfsCanonicalResolver { "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" },
        )
        assertEquals("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", ok)
        val qualcommKgsl = SysfsPath.canonicalize(
            "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
            resolver = SysfsCanonicalResolver {
                "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/max_pwrlevel"
            },
        )
        assertEquals(
            "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/max_pwrlevel",
            qualcommKgsl,
        )
        assertNull(
            SysfsPath.canonicalize(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                resolver = SysfsCanonicalResolver { "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq/../evil" },
            ),
        )
    }

    /**
     * Thermal derate amendment, against the REAL durable journal.
     *
     * Device-caught 2026-08-09 (Odin 3, generations 43 and 44): every thermal rung returned
     * THERMAL_DERATE_JOURNAL_PERSIST_FAILED *after* the hardware had already moved, so the
     * record and reality diverged and the drift watchdog restored the session to Stock. Cause:
     * `applyDerate` wrote through `persist`, whose guard demands a byte-identical applied
     * projection -- the one thing a derate necessarily changes. The whole derate suite stayed
     * green because every one of its journals is an accept-everything in-memory double, so
     * nothing exercised this guard. These tests use the durable implementation deliberately.
     */
    @Test
    fun `amendApplied admits a strictly slower derate projection that persist still refuses`() {
        val journal = derateAmendJournal("tx-derate-amend", "journal-amend.json", "gen-amend")
        val applied = appliedRecordFor(journal)
        assertTrue(journal.persist(applied))

        val slower = shiftedRecord(applied, cpuDeltaKhz = -100_000, gpuLevelDelta = 1)

        // The frozen lane must STILL refuse it. That immutability is what stops a faster
        // projection ever reaching the drift watchdog's AppliedTargetContract or
        // repairAppliedCpuQosBound, which re-writes projection values to hardware.
        assertFalse(journal.persist(slower))
        // The amendment lane admits it, because every value moved slower.
        assertTrue(journal.amendApplied(slower))

        val loaded = journal.load()
        assertTrue(loaded is JournalLoadResult.Loaded)
        assertEquals(
            slower.appliedMutationProjection,
            (loaded as JournalLoadResult.Loaded).record.appliedMutationProjection,
        )
    }

    @Test
    fun `amendApplied refuses a projection that speeds any axis back up`() {
        val journal = derateAmendJournal("tx-derate-faster", "journal-faster.json", "gen-faster")
        val applied = appliedRecordFor(journal)
        assertTrue(journal.persist(applied))

        val fasterCpu = shiftedRecord(applied, cpuDeltaKhz = 100_000, gpuLevelDelta = 0)
        assertFalse(journal.amendApplied(fasterCpu))

        // Lower max_pwrlevel index == faster GPU, so this direction must be refused too.
        val fasterGpu = shiftedRecord(applied, cpuDeltaKhz = 0, gpuLevelDelta = -1)
        assertFalse(journal.amendApplied(fasterGpu))

        // Nothing durable moved.
        val loaded = journal.load()
        assertTrue(loaded is JournalLoadResult.Loaded)
        assertEquals(
            applied.appliedMutationProjection,
            (loaded as JournalLoadResult.Loaded).record.appliedMutationProjection,
        )
    }

    /**
     * The full device sequence that stalled on the Odin 2026-08-09: apply, derate, then restore.
     *
     * After a confirmed derate the session entered RESTORING and never left it -- every later apply
     * was refused with SESSION_NOT_SETTLED reporting the same frozen generation, and it survived a
     * package replace, so it was durable journal state. This walks the same journal transitions to
     * find out whether the amendment lane is what jams them.
     */
    @Test
    fun `a derated record can still advance to RESTORING and be cleared`() {
        val journal = derateAmendJournal("tx-derate-restore", "journal-restore.json", "gen-restore")
        val applied = appliedRecordFor(journal)
        assertTrue("APPLIED must persist", journal.persist(applied))

        val derated = shiftedRecord(applied, cpuDeltaKhz = -100_000, gpuLevelDelta = 1)
        assertTrue("the derate amendment must land", journal.amendApplied(derated))

        // Restore copies the DURABLE record and only advances its phase, so the projection it
        // carries is the derated one. If the frozen persist lane refused that, the session could
        // never leave RESTORING -- which is exactly the shape of the device stall.
        val restoring = derated.copy(phase = TransactionPhase.RESTORING, lastCompletedRestoreBoundary = 0)
        assertTrue("RESTORING must persist over a derated record", journal.persist(restoring))

        assertTrue("the journal must clear on the same transaction", journal.clear(derated.transactionId))
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `amendApplied refuses a reshaped projection and refuses to amend nothing`() {
        val journal = derateAmendJournal("tx-derate-reshape", "journal-reshape.json", "gen-reshape")
        val applied = appliedRecordFor(journal)

        // Nothing is applied yet, so there is nothing to amend downward.
        assertFalse(journal.amendApplied(applied))

        assertTrue(journal.persist(applied))
        // Slower values are not a licence to re-shape the projection itself.
        val slower = shiftedRecord(applied, cpuDeltaKhz = -100_000, gpuLevelDelta = 1)
        val reshaped = slower.copy(
            appliedMutationProjection = slower.appliedMutationProjection
                .map { it.copy(canonicalPath = it.canonicalPath + "/decoy") },
        )
        assertFalse(journal.amendApplied(reshaped))
        val dropped = slower.copy(appliedMutationProjection = slower.appliedMutationProjection.drop(1))
        assertFalse(journal.amendApplied(dropped))
    }

    /**
     * Shift the applied plan AND its projection together, exactly as `applyDerate` does. They
     * are not independent: `encodeJournal` rejects a record whose projection disagrees with
     * `appliedPlan`, so a test that moved only one would prove nothing about the write guard.
     * Negative deltas on CPU and positive deltas on GPU max_pwrlevel are both "slower".
     */
    private fun shiftedRecord(
        applied: TransactionJournalRecord,
        cpuDeltaKhz: Int,
        gpuLevelDelta: Int,
    ): TransactionJournalRecord {
        val plan = checkNotNull(applied.appliedPlan)
        val shiftedPlan = plan.copy(
            cpuMaxKhz = plan.cpuMaxKhz.mapValues { (_, khz) -> khz + cpuDeltaKhz },
            gpuMaxPowerLevel = plan.gpuMaxPowerLevel?.plus(gpuLevelDelta),
        )
        val shiftedProjection = applied.appliedMutationProjection.map { mutation ->
            val current = mutation.value
            when {
                current == null -> mutation
                mutation.axis == TuningAxis.CPU -> mutation.copy(value = current + cpuDeltaKhz)
                mutation.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID ->
                    mutation.copy(value = current + gpuLevelDelta)
                else -> mutation
            }
        }
        return applied.copy(
            appliedPlan = shiftedPlan,
            appliedMutationProjection = shiftedProjection,
            planHash = PlanHash.ofPersisted(shiftedPlan),
        )
    }

    /** The journal validates a record against its own generation file, so allocate one first. */
    private fun appliedRecordFor(journal: AtomicFileTransactionJournal): TransactionJournalRecord {
        val generation = when (val next = journal.nextGeneration()) {
            is GenerationResult.Ok -> next.value
            is GenerationResult.Failed -> error("generation allocate failed: ${next.reason}")
        }
        return appliedRecord(stockSnapshot()).copy(generation = generation)
    }

    private fun derateAmendJournal(
        dirName: String,
        fileName: String,
        generationFileName: String,
    ): AtomicFileTransactionJournal {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, dirName).apply {
            deleteRecursively()
            mkdirs()
        }
        return AtomicFileTransactionJournal(
            filesDir = dir,
            fileName = fileName,
            generationFileName = generationFileName,
            testSeam = true,
            directoryDurability = NoopDirectoryDurability,
        )
    }

    @Test
    fun `active journal load fails corrupt on generation mismatch`() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "tx-gen-mismatch").apply {
            deleteRecursively()
            mkdirs()
        }
        val journal = AtomicFileTransactionJournal(
            filesDir = dir,
            fileName = "journal-mismatch.json",
            generationFileName = "gen-mismatch",
            testSeam = true,
            directoryDurability = NoopDirectoryDurability,
        )
        val generation = when (val next = journal.nextGeneration()) {
            is GenerationResult.Ok -> next.value
            is GenerationResult.Failed -> {
                fail("generation allocate failed: ${next.reason}")
                return
            }
        }
        val record = appliedRecord(stockSnapshot()).copy(generation = generation)
        assertTrue(journal.persist(record))
        File(dir, "gen-mismatch").writeText((generation + 1).toString())
        val loaded = journal.load()
        assertTrue(loaded is JournalLoadResult.Corrupt)
        assertTrue((loaded as JournalLoadResult.Corrupt).reason.contains("Generation mismatch"))
    }

    @Test
    fun `directory durability failure blocks PREPARED before hardware writes`() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "tx-dir-fsync-fail").apply {
            deleteRecursively()
            mkdirs()
        }
        var synced = 0
        val journal = AtomicFileTransactionJournal(
            filesDir = dir,
            fileName = "journal-fsync.json",
            generationFileName = "gen-fsync",
            testSeam = true,
            directoryDurability = DirectoryDurability {
                synced++
                // Allow generation allocate, reject the first journal persist (PREPARED).
                if (synced > 1) error("injected directory fsync failure")
            },
        )
        val rig = StatefulHardwareRig()
        val result = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "fsync")
        assertEquals("JOURNAL_PERSIST_FAILED", result.code)
        assertEquals(0, rig.executes)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `decode rejects mutation and restore boundary overflow`() {
        val record = appliedRecord(stockSnapshot())
        val overflowMutation = record.copy(lastCompletedMutationBoundary = record.snapshot.nodes.size + 1)
        val mutationCorrupt = AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(overflowMutation),
        )
        assertTrue(mutationCorrupt is JournalLoadResult.Corrupt)
        assertTrue((mutationCorrupt as JournalLoadResult.Corrupt).reason.contains("Mutation boundary overflow"))

        val overflowRestore = record.copy(lastCompletedRestoreBoundary = record.snapshot.nodes.size + 1)
        val restoreCorrupt = AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(overflowRestore),
        )
        assertTrue(restoreCorrupt is JournalLoadResult.Corrupt)
        assertTrue((restoreCorrupt as JournalLoadResult.Corrupt).reason.contains("Restore boundary overflow"))
    }

    @Test
    fun `absent fan_mode deletes at full restore boundary`() {
        val rig = StatefulHardwareRig(fanPresentInitially = false)
        val journal = DurableFakeJournal()
        val applied = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "fan")
        assertEquals("APPLIED", applied.code)
        assertEquals(TuningPlanner.FAN_SMART, rig.fanMode)

        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORED", restored.code)
        assertNull(rig.fanMode)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
    }

    @Test
    fun `duplicate requestId conflict when identity mismatches`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val first = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "same")
        assertEquals("APPLIED", first.code)
        val executes = rig.executes

        val conflict = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("quiet", "example.game", capabilities(), dryRun = false, requestId = "same")
        assertEquals("REQUEST_ID_CONFLICT", conflict.code)
        assertEquals(executes, rig.executes)
    }

    @Test
    fun `duplicate requestId is idempotent once APPLIED with identical identity`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())
        val first = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "same")
        assertEquals("APPLIED", first.code)
        val executes = rig.executes
        val second = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "same")
        assertEquals("APPLIED", second.code)
        assertEquals(first.transactionId, second.transactionId)
        assertEquals(executes, rig.executes)
    }

    @Test
    fun `fingerprint mismatch blocks restore commands and retains RESTORE_FAILED`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        assertEquals(
            "APPLIED",
            coordinator(rig, journal, idFactory = sequentialIds())
                .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "fp")
                .code,
        )
        rig.fingerprintOverride = "other-fingerprint"
        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORE_FAILED", restored.code)
        val loaded = journal.load() as JournalLoadResult.Loaded
        assertEquals(TransactionPhase.RESTORE_FAILED, loaded.record.phase)
        assertFalse(rig.isExactStock())
    }

    @Test
    fun `corrupt generation blocks apply before PREPARED`() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "tx-gen-corrupt").apply {
            deleteRecursively()
            mkdirs()
        }
        File(dir, "gen-corrupt-test").writeText("not-a-number")
        val journal = AtomicFileTransactionJournal(
            filesDir = dir,
            fileName = "journal-corrupt-gen.json",
            generationFileName = "gen-corrupt-test",
            testSeam = true,
            directoryDurability = NoopDirectoryDurability,
        )
        val rig = StatefulHardwareRig()
        val result = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "gen")
        assertEquals("JOURNAL_GENERATION_FAILED", result.code)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(journal.nextGeneration() is GenerationResult.Failed)
    }

    @Test
    fun `false clear after verified restore retains RESTORE_FAILED`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal().apply { clearSucceeds = false }
        assertEquals(
            "APPLIED",
            coordinator(rig, journal, idFactory = sequentialIds())
                .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "clear")
                .code,
        )
        val restored = coordinator(rig, journal).restore()
        assertEquals("RESTORE_FAILED", restored.code)
        val loaded = journal.load() as JournalLoadResult.Loaded
        assertEquals(TransactionPhase.RESTORE_FAILED, loaded.record.phase)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `performance plans cool before compute ceilings via command port`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("performance", "example.game", caps)!!
        val ordered = port.plannedMutations(plan, stockSnapshot(caps))
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, ordered.first().logicalId)
        assertTrue(ordered.drop(1).all { it.axis != TuningAxis.FAN })
    }

    @Test
    fun `quiet plans settle coupled cooling before compute via command port`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val plan = certifiedPlanner().plan("quiet", "example.game", caps)!!
        val ordered = port.plannedMutations(plan, stockSnapshot(caps))
        assertEquals(HardwareSnapshot.FAN_LOGICAL_ID, ordered.first().logicalId)
        assertTrue(ordered.drop(1).all { it.axis != TuningAxis.FAN })
    }

    @Test
    fun `command port rejects non-allowlisted and noncanonical paths`() {
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(transport = ConfirmingTransport()),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val bad = LogicalMutation(
            logicalId = "cpu:0:scaling_max_freq",
            axis = TuningAxis.CPU,
            canonicalPath = "/tmp/evil",
            kind = LogicalMutationKind.SYSFS_WRITE,
            value = 1_000_000,
            restoreMode = "644",
            restoreUid = 0,
            restoreGid = 0,
            expectPresent = true,
        )
        try {
            port.execute(bad)
            fail("expected allowlist rejection")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("canonical") || error.message!!.contains("allowlist"))
        }
    }

    @Test
    fun `split Sysfs statements fit 198 envelope and retain old compound differential`() {
        val commands = mutableListOf<String>()
        val caps = capabilities()
        val port = PServerHardwareCommandPort(
            pServer = confirmingClient(commands),
            reader = SystemReader(),
            capabilities = { caps },
        )
        val paths = caps.cpuPolicies.flatMap { policy ->
            listOf("${policy.path}/scaling_max_freq", "${policy.path}/scaling_min_freq")
        } + listOf(HardwareProbe.GPU_MIN_LEVEL, HardwareProbe.GPU_MAX_LEVEL)
        paths.forEachIndexed { index, path ->
            val result = port.execute(
                LogicalMutation(
                    logicalId = "max-$index",
                    axis = TuningAxis.CPU,
                    canonicalPath = path,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = Int.MAX_VALUE,
                    restoreMode = "7777",
                    restoreUid = Int.MAX_VALUE,
                    restoreGid = Int.MAX_VALUE,
                    expectPresent = true,
                ),
            )
            assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        }
        assertTrue(commands.all { it.toByteArray(Charsets.UTF_8).size <= PServerClient.MAX_CHECKED_COMMAND_BYTES })

        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val old = "chmod 666 $path && echo 1804800 > $path && chown 0:0 $path && chmod 644 $path"
        val oldWrapped = "b=\$( ( $old ) 2>/dev/null);s=\$?;printf 'MIMIR_TX_EXIT:n1_abcdef12:%s:%s:%s\\n' \"\$s\" \"\${#b}\" \"\$b\""
        assertEquals(281, old.toByteArray(Charsets.UTF_8).size)
        assertEquals(372, oldWrapped.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `split Sysfs preserves exact order and distinct default nonces`() {
        val commands = mutableListOf<String>()
        val port = PServerHardwareCommandPort(confirmingClient(commands), SystemReader(), ::capabilities)
        val result = port.execute(sysfsMutation())
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(0, result.exitCode)
        assertTrue(result.output!!.startsWith(PServerClient.EXIT_MARKER_PREFIX))
        assertEquals("sysfs 4/4 (restore-mode): Exit 0 confirmed", result.detail)
        assertEquals(4, commands.size)
        assertTrue(commands[0].contains("chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(commands[1].contains("echo 1804800 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(commands[2].contains("chown 0:0 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(commands[3].contains("chmod 644 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertEquals(4, commands.map(::nonceOf).toSet().size)
    }

    @Test
    fun `split Sysfs stops at every failed or ambiguous step preserving result`() {
        listOf(CheckedCommandStatus.FAILED, CheckedCommandStatus.AMBIGUOUS).forEach { status ->
            (1..4).forEach { failingStep ->
                val commands = mutableListOf<String>()
                var calls = 0
                val client = PServerClient(transport = PServerBinderTransport { command, _ ->
                    commands += command
                    calls += 1
                    val nonce = nonceOf(command)
                    when {
                        calls != failingStep -> PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:")
                        status == CheckedCommandStatus.FAILED -> PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:17:0:")
                        else -> PServerTransactResult(false)
                    }
                })
                val port = PServerHardwareCommandPort(client, SystemReader(), ::capabilities)
                val result = port.execute(sysfsMutation())
                assertEquals("$status/$failingStep", status, result.status)
                assertEquals("$status/$failingStep", failingStep, commands.size)
                assertEquals("$status/$failingStep", if (status == CheckedCommandStatus.FAILED) 17 else null, result.exitCode)
                assertTrue(
                    "$status/$failingStep ${result.detail}",
                    result.detail.startsWith("sysfs $failingStep/4 (${listOf("chmod-666", "write-value", "restore-owner", "restore-mode")[failingStep - 1]}):"),
                )
            }
        }
    }

    @Test
    fun `step two apply failure restores captured mode last and clears journal`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var calls = 0
        val pServerCommands = mutableListOf<String>()
        // 2026-08-23: fan restore now runs the bounded settlement barrier on every fan-bearing
        // device, not only the Odin -- see the sibling note in `stock fan mode zero ...`. The
        // transport tracks the fan_mode it is asked to write so `fanModeRaw` can answer truthfully
        // during the barrier; the barrier itself is exercised, only its 6 x 500ms sleep is skipped.
        var liveFanMode = rig.fanMode
        val pServer = PServerClient(transport = PServerBinderTransport { command, _ ->
            pServerCommands += command
            calls += 1
            val nonce = nonceOf(command)
            if (calls == 2) {
                PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:9:0:")
            } else {
                Regex("fan_mode (\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { liveFanMode = it }
                PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:")
            }
        })
        val rawPort = PServerHardwareCommandPort(
            pServer,
            SystemReader(),
            ::capabilities,
            liveBootIdentity = { rig.captureStockSnapshot().bootIdentity },
            fanModeRaw = { liveFanMode.toString() },
            sleeper = {},
        )
        val commands = object : HardwareCommandPort by rawPort {
            override fun execute(mutation: LogicalMutation): CheckedCommandResult {
                val result = rawPort.execute(mutation)
                return result.also { if (it.status == CheckedCommandStatus.CONFIRMED) rig.execute(mutation) }
            }
        }
        val result = TransactionCoordinator(certifiedPlanner(), rig, commands, journal, true, idFactory = sequentialIds(), clock = { 1_000L })
            .apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "split-step-two")
        assertEquals("VERIFY_FAILED", result.code)
        assertTrue(result.restored)
        assertTrue(rig.isExactStock())
        assertTrue(journal.load() is JournalLoadResult.Empty)
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val restoreStart = pServerCommands.indexOfLast { it.contains("chmod 666 $path") }
        assertTrue("missing captured CPU restore commands", restoreStart >= 0)
        val restore = pServerCommands.drop(restoreStart).take(4)
        assertEquals(4, restore.size)
        assertTrue(restore[0].contains("chmod 666 $path"))
        assertTrue(restore[1].contains("echo 1804800 > $path"))
        assertTrue(restore[2].contains("chown 0:0 $path"))
        assertTrue(restore[3].contains("chmod 644 $path"))
    }

    @Test
    fun `settings mutation remains one checked statement`() {
        val commands = mutableListOf<String>()
        val port = PServerHardwareCommandPort(confirmingClient(commands), SystemReader(), ::capabilities)
        val result = port.execute(
            LogicalMutation(HardwareSnapshot.FAN_LOGICAL_ID, TuningAxis.FAN, HardwareSnapshot.FAN_SETTINGS_PATH,
                LogicalMutationKind.SETTINGS_PUT, TuningPlanner.FAN_SMART, null, expectPresent = true),
        )
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("settings put system fan_mode ${TuningPlanner.FAN_SMART}"))
    }

    // Fan mode write regression (2026-08-13): a redundant fan_mode write -- same value the vendor already holds --
    // still re-triggers vendor SPORT entry and slams duty to its entry value for ~0.2-0.9s, device-proven
    // by writing fan_mode=5 with nothing else running while it already read 5. These two tests pin the
    // guard added to PServerHardwareCommandPort.execute's ordinary SETTINGS_PUT branch: same value must
    // never reach pServer at all; a genuine drift must still write exactly as before.
    @Test
    fun `same-value fan_mode write is skipped -- no redundant vendor SPORT-entry duty reset`() {
        val commands = mutableListOf<String>()
        val port = PServerHardwareCommandPort(
            confirmingClient(commands),
            SystemReader(),
            ::capabilities,
            fanModeRaw = { TuningPlanner.FAN_SPORT.toString() },
        )
        val result = port.execute(
            LogicalMutation(HardwareSnapshot.FAN_LOGICAL_ID, TuningAxis.FAN, HardwareSnapshot.FAN_SETTINGS_PATH,
                LogicalMutationKind.SETTINGS_PUT, TuningPlanner.FAN_SPORT, null, expectPresent = true),
        )
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue("no pServer command should be sent for a same-value fan_mode write", commands.isEmpty())
    }

    @Test
    fun `different-value fan_mode write still fires -- a genuine drift is never silently dropped`() {
        val commands = mutableListOf<String>()
        val port = PServerHardwareCommandPort(
            confirmingClient(commands),
            SystemReader(),
            ::capabilities,
            fanModeRaw = { TuningPlanner.FAN_SMART.toString() },
        )
        val result = port.execute(
            LogicalMutation(HardwareSnapshot.FAN_LOGICAL_ID, TuningAxis.FAN, HardwareSnapshot.FAN_SETTINGS_PATH,
                LogicalMutationKind.SETTINGS_PUT, TuningPlanner.FAN_SPORT, null, expectPresent = true),
        )
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("settings put system fan_mode ${TuningPlanner.FAN_SPORT}"))
    }

    @Test
    fun `plan target validator rejects illegal OPP GPU and fan values`() {
        val caps = capabilities()
        val plan = certifiedPlanner().plan("balanced", "example.game", caps)!!
        assertTrue(PlanTargetValidator.validate(plan, caps).isEmpty())
        assertTrue(
            PlanTargetValidator.validate(plan.copy(cpuMaxKhz = mapOf(0 to 123)), caps)
                .any { "OPP" in it },
        )
        assertTrue(
            PlanTargetValidator.validate(plan.copy(gpuMaxPowerLevel = 99), caps)
                .any { "gpu" in it },
        )
        assertTrue(
            PlanTargetValidator.validate(plan.copy(fanMode = 99), caps)
                .any { "fan_mode" in it },
        )
        // 2026-08-23: the exact-Odin restriction on FAN_MANUAL is retired -- this RP5 fixture has a
        // live fan axis, so a manual target is now valid for it. Inverted rather than deleted, so
        // the retirement is asserted and not merely absent.
        assertEquals(
            emptyList<String>(),
            PlanTargetValidator.validate(plan.copy(fanMode = TuningPlanner.FAN_MANUAL), caps),
        )
        // The fail-closed half the device id used to stand in for: no live fan axis, no manual
        // target, on any device.
        assertTrue(
            PlanTargetValidator.validate(
                plan.copy(fanMode = TuningPlanner.FAN_MANUAL),
                caps.copy(
                    supportedAxes = caps.supportedAxes - TuningAxis.FAN,
                    fanNodeAvailable = false,
                ),
            ).any { "admitted live fan axis" in it },
        )
        val listedBelowMinimum = caps.copy(
            cpuPolicies = caps.cpuPolicies.map { policy ->
                if (policy.id == 0) {
                    policy.copy(availableFrequenciesKhz = listOf(123) + policy.availableFrequenciesKhz)
                } else {
                    policy
                }
            },
        )
        assertTrue(
            PlanTargetValidator.validate(
                plan.copy(cpuMaxKhz = plan.cpuMaxKhz + (0 to 123)),
                listedBelowMinimum,
            ).any { "immutable range" in it },
        )
    }

    @Test
    fun `journal encode round-trip and rejects superseded phases`() {
        val record = appliedRecord(stockSnapshot())
        val encoded = AtomicFileTransactionJournal.encodeJournal(record)
        val loaded = AtomicFileTransactionJournal.decodeJournal(encoded) as JournalLoadResult.Loaded
        assertEquals(record.generation, loaded.record.generation)
        assertEquals(record.lastCompletedRestoreBoundary, loaded.record.lastCompletedRestoreBoundary)
        assertEquals(record.appliedPlan, loaded.record.appliedPlan)
        assertEquals(record.applyOrderLogicalIds, loaded.record.applyOrderLogicalIds)
        assertEquals(record.appliedMutationProjection, loaded.record.appliedMutationProjection)
        assertTrue(
            AtomicFileTransactionJournal.decodeJournal(encoded.replace("\"APPLIED\"", "\"ACTIVE\""))
                is JournalLoadResult.Corrupt,
        )
        assertTrue(
            AtomicFileTransactionJournal.decodeJournal(
                encoded.replace(Regex(""",\"consumedMgdeEnvelopeSha256\":null"""), ""),
            ) is JournalLoadResult.Corrupt,
        )
        val v2Raw = encoded
            .replace(
                "\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA,",
                "\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA_V2,",
            )
            .replace(Regex(""",\"consumedMgdeEnvelopeSha256\":null"""), "")
            .replace(Regex(""",\"appliedMutationProjection\":\[[^]]*\]"""), "")
        val v2Loaded = AtomicFileTransactionJournal.decodeJournal(v2Raw) as JournalLoadResult.Loaded
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V2, v2Loaded.record.schemaVersion)
        assertEquals(record.appliedPlan, v2Loaded.record.appliedPlan)
        assertEquals(record.applyOrderLogicalIds, v2Loaded.record.applyOrderLogicalIds)
        assertEquals(null, v2Loaded.record.consumedMgdeEnvelopeSha256)
        assertTrue(v2Loaded.record.appliedMutationProjection.isEmpty())

        val v3Raw = v2Raw
            .replace(
                "\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA_V2,",
                "\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA_V3,",
            )
            .replace(Regex("""\}$"""), ""","consumedMgdeEnvelopeSha256":null}""")
        val v3Loaded = AtomicFileTransactionJournal.decodeJournal(v3Raw) as JournalLoadResult.Loaded
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V3, v3Loaded.record.schemaVersion)
        assertTrue(v3Loaded.record.appliedMutationProjection.isEmpty())

        val v1Raw = v2Raw
            .replace("\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA_V2,", "\"schemaVersion\":$TRANSACTION_JOURNAL_SCHEMA_V1,")
            .replace(Regex(""",\"appliedPlan\":\{.*?\},"""), ",")
            .replace(Regex(""",\"applyOrderLogicalIds\":\[[^\]]*\],"""), ",")
        val v1Loaded = AtomicFileTransactionJournal.decodeJournal(v1Raw) as JournalLoadResult.Loaded
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V1, v1Loaded.record.schemaVersion)
        assertEquals(null, v1Loaded.record.appliedPlan)
        assertTrue(v1Loaded.record.applyOrderLogicalIds.isEmpty())
        assertEquals(
            HistoricApplyOrder.v1LogicalIds(v1Loaded.record.profileId, v1Loaded.record.snapshot),
            HistoricApplyOrder.resolveLogicalIds(v1Loaded.record),
        )
    }

    @Test
    fun `schema v4 carries conditional CPU floor while v1 through v3 reject it during decode`() {
        val rig = StatefulHardwareRig()
        val caps = rig.liveCapabilities()
        val plan = certifiedPlanner().plan("quiet", "example.game", caps)!!
        val ceilingOnly = rig.captureForPlan(plan, caps).snapshot!!
        val snapshot = ceilingOnly.copy(
            nodes = listOf(
                TopologyNodeState(
                    logicalId = "cpu:0:scaling_min_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath =
                        "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                    originalValue = 1_344_000,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                ),
            ) + ceilingOnly.nodes,
        )
        val record = appliedRecord(snapshot, plan = plan, caps = caps)
        val encoded = AtomicFileTransactionJournal.encodeJournal(record)

        val loaded = AtomicFileTransactionJournal.decodeJournal(encoded) as JournalLoadResult.Loaded
        assertEquals(setOf(0), loaded.record.snapshot.cpuMinKhz.keys)
        assertEquals("cpu:0:scaling_min_freq", loaded.record.applyOrderLogicalIds[0])
        assertEquals("cpu:0:scaling_max_freq", loaded.record.applyOrderLogicalIds[1])

        fun corruptProjection(mutate: (org.json.JSONArray) -> Unit): JournalLoadResult {
            val root = org.json.JSONObject(encoded)
            mutate(root.getJSONArray("appliedMutationProjection"))
            return AtomicFileTransactionJournal.decodeJournal(root.toString())
        }
        assertTrue(
            corruptProjection { projection ->
                val floor = projection.getJSONObject(0)
                val ceiling = projection.getJSONObject(1)
                projection.put(0, ceiling)
                projection.put(1, floor)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corruptProjection { projection ->
                projection.getJSONObject(0).put("value", plan.cpuMaxKhz.getValue(0) + 1)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corruptProjection { projection ->
                projection.getJSONObject(0).put(
                    "canonicalPath",
                    "/sys/devices/system/cpu/cpufreq/policy1/scaling_min_freq",
                )
            } is JournalLoadResult.Corrupt,
        )

        listOf(
            TRANSACTION_JOURNAL_SCHEMA_V1,
            TRANSACTION_JOURNAL_SCHEMA_V2,
            TRANSACTION_JOURNAL_SCHEMA_V3,
        ).forEach { legacySchema ->
            val legacy = org.json.JSONObject(encoded)
                .put("schemaVersion", legacySchema)
                .toString()
            val rejected = AtomicFileTransactionJournal.decodeJournal(legacy)
            assertTrue("schema $legacySchema admitted CPU floor", rejected is JournalLoadResult.Corrupt)
        }
    }

    @Test
    fun `schema v4 decoder rejects missing reordered altered and legacy renderer projections`() {
        val encoded = AtomicFileTransactionJournal.encodeJournal(appliedRecord(stockSnapshot()))
        fun corrupt(mutate: (org.json.JSONObject) -> Unit): JournalLoadResult {
            val root = org.json.JSONObject(encoded)
            mutate(root)
            return AtomicFileTransactionJournal.decodeJournal(root.toString())
        }

        assertTrue(corrupt { it.remove("appliedMutationProjection") } is JournalLoadResult.Corrupt)
        assertTrue(
            corrupt { root ->
                val projection = root.getJSONArray("appliedMutationProjection")
                val first = projection.getJSONObject(0)
                val second = projection.getJSONObject(1)
                projection.put(0, second)
                projection.put(1, first)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corrupt { root ->
                root.getJSONArray("appliedMutationProjection")
                    .getJSONObject(0)
                    .put("expectPresent", false)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corrupt { root ->
                root.getJSONArray("appliedMutationProjection")
                    .getJSONObject(0)
                    .put("value", -1)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corrupt { root ->
                root.getJSONArray("appliedMutationProjection")
                    .getJSONObject(0)
                    .put("kind", LogicalMutationKind.SETTINGS_DELETE.name)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corrupt { root ->
                root.getJSONArray("appliedMutationProjection")
                    .getJSONObject(0)
                    .put("unexpectedAuthority", true)
            } is JournalLoadResult.Corrupt,
        )
        assertTrue(
            corrupt { root ->
                root.put("schemaVersion", TRANSACTION_JOURNAL_SCHEMA_V3)
            } is JournalLoadResult.Corrupt,
        )
    }

    @Test
    fun `PREPARED persists exact renderer GPU min and immutable ordered projection`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val caps = capabilities()
        val result = coordinator(rig, journal, idFactory = sequentialIds())
            .apply("balanced", "example.game", caps, dryRun = false, requestId = "projection")
        assertEquals("APPLIED", result.code)
        val record = (journal.load() as JournalLoadResult.Loaded).record
        val plan = record.appliedPlan!!.toTuningPlan()
        val exact = AppliedMutationProjection.from(rig.plannedMutations(plan, record.snapshot))
        assertTrue(
            AppliedMutationProjection.sameCanonical(exact, record.appliedMutationProjection),
        )
        val gpuMin = record.appliedMutationProjection.single {
            it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID
        }
        assertEquals(caps.gpuFrequenciesKhz.size - 1, gpuMin.value)
        assertEquals(
            record.applyOrderLogicalIds,
            record.appliedMutationProjection.map { it.logicalId },
        )
    }

    @Test
    fun `write-disabled build retains any real saved journal`() {
        val journal = DurableFakeJournal().apply { record = appliedRecord(stockSnapshot()) }
        val result = TransactionCoordinator(
            certifiedPlanner(),
            StatefulHardwareRig(),
            StatefulHardwareRig(),
            journal,
            hardwareWritesCompiled = false,
        ).restore()
        assertEquals("WRITE_GATE_DISABLED", result.code)
        assertTrue(journal.record != null)
    }

    @Test
    fun `deadline before first mutation clears journal with zero commands`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var now = 1_000L
        val barrier = TransactionBarrier { point, _ ->
            if (point == TransactionBarrierPoint.AFTER_APPLYING_PHASE) {
                now = 2_000L
            }
        }
        val gated = TransactionCoordinator(
            certifiedPlanner(),
            rig,
            rig,
            journal,
            hardwareWritesCompiled = true,
            barrier = barrier,
            idFactory = sequentialIds(),
            clock = { now },
            requestWindowMs = 50L,
        )
        val result = gated.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "deadline")
        assertEquals("REQUEST_EXPIRED", result.code)
        assertEquals(0, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `caller request identity and service hard deadline persist and return exactly`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())

        val result = coordinator.apply(
            profileId = "balanced",
            packageName = "example.game",
            capabilities = capabilities(),
            dryRun = false,
            requestId = "caller-request-7",
            requestDeadlineElapsedRealtimeMs = 9_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 91_000L,
        )

        assertEquals("APPLIED", result.code)
        assertEquals("caller-request-7", result.requestId)
        assertEquals(9_000L, result.requestDeadlineElapsedRealtimeMs)
        assertEquals(91_000L, result.hardLeaseDeadlineElapsedRealtimeMs)
        val record = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals("caller-request-7", record.requestId)
        assertEquals(9_000L, record.requestDeadline)
        assertEquals(91_000L, record.leaseDeadline)
        assertEquals(record.generation, result.generation)
        assertEquals(record.transactionId, result.transactionId)
    }

    @Test
    fun `owner cancellation during adoption exact restores before success return`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        var cancelled = false
        var provisionalAdoptionCleared = false
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())

        val result = coordinator.apply(
            profileId = "balanced",
            packageName = "example.game",
            capabilities = capabilities(),
            dryRun = false,
            requestId = "owner-died-during-adopt",
            requestDeadlineElapsedRealtimeMs = 9_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 91_000L,
            isCancelled = { cancelled },
            onAppliedAdopt = {
                cancelled = true
                true
            },
            onAdoptionRollback = {
                provisionalAdoptionCleared = true
            },
        )

        assertEquals("REQUEST_CANCELLED", result.code)
        assertTrue(result.restored)
        assertTrue(provisionalAdoptionCleared)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `cancel during blocking fake command restores after command returns`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : HardwareCommandPort by rig {
            override fun execute(mutation: LogicalMutation): CheckedCommandResult {
                entered.countDown()
                check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                return rig.execute(mutation)
            }
        }
        val coordinator = TransactionCoordinator(
            certifiedPlanner(),
            rig,
            blocking,
            journal,
            hardwareWritesCompiled = true,
            idFactory = sequentialIds(),
            clock = { 1_000L },
        )
        val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit<TransactionResult> {
            coordinator.apply(
                "balanced",
                "example.game",
                capabilities(),
                dryRun = false,
                requestId = "late-cancel",
                isCancelled = { cancelled.get() },
            )
        }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        cancelled.set(true)
        release.countDown()
        val result = future.get(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("REQUEST_CANCELLED", result.code)
        assertTrue(result.restored)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertTrue(rig.isExactStock())
    }

    @Test
    fun `old generation restoreBound cannot disturb newer journal`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val coordinator = coordinator(rig, journal, idFactory = sequentialIds())
        val first = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "g1")
        assertEquals("APPLIED", first.code)
        val gen1 = (journal.load() as JournalLoadResult.Loaded).record.generation
        val tx1 = (journal.load() as JournalLoadResult.Loaded).record.transactionId
        assertTrue(coordinator.restore().restored)
        val second = coordinator.apply("balanced", "example.game", capabilities(), dryRun = false, requestId = "g2")
        assertEquals("APPLIED", second.code)
        val loaded = journal.load() as JournalLoadResult.Loaded
        val executesBefore = rig.executes
        val stale = coordinator.restoreBound(gen1, tx1)
        assertEquals("STALE_GENERATION", stale.code)
        assertFalse(stale.restored)
        assertEquals(executesBefore, rig.executes)
        assertTrue(journal.load() is JournalLoadResult.Loaded)
        assertEquals(loaded.record.transactionId, (journal.load() as JournalLoadResult.Loaded).record.transactionId)
    }

    private fun assertDeathThenExactStockOrEmpty(
        point: TransactionBarrierPoint,
        mutationIndex: Int,
        duringApply: Boolean,
    ) {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        if (!duringApply) {
            assertEquals(
                "APPLIED",
                coordinator(rig, journal, idFactory = sequentialIds())
                    .apply(
                        "balanced",
                        "example.game",
                        capabilities(),
                        dryRun = false,
                        requestId = "pre-$point-$mutationIndex",
                    )
                    .code,
            )
            assertFalse(rig.isExactStock())
        }
        try {
            if (duringApply) {
                coordinator(
                    rig,
                    journal,
                    barrier = OneShotDeathBarrier(point, mutationIndex),
                    idFactory = sequentialIds(),
                ).apply(
                    "balanced",
                    "example.game",
                    capabilities(),
                    dryRun = false,
                    requestId = "death-$point-$mutationIndex",
                )
            } else {
                coordinator(
                    rig,
                    journal,
                    barrier = OneShotDeathBarrier(point, mutationIndex),
                ).restore()
            }
            fail("Expected death at $point index=$mutationIndex")
        } catch (_: TransactionDeathException) {
            when (point) {
                TransactionBarrierPoint.AFTER_CAPTURE_BEFORE_JOURNAL ->
                    assertTrue(journal.load() is JournalLoadResult.Empty)
                TransactionBarrierPoint.AFTER_APPLIED -> {
                    val loaded = journal.load() as JournalLoadResult.Loaded
                    assertEquals(TransactionPhase.APPLIED, loaded.record.phase)
                }
                else -> Unit
            }
        }
        when (val loaded = journal.load()) {
            is JournalLoadResult.Loaded -> {
                val restored = coordinator(rig, journal).restore()
                assertEquals("RESTORED", restored.code)
                assertTrue("stock mismatch at $point/$mutationIndex", rig.isExactStock())
                assertTrue(journal.load() is JournalLoadResult.Empty)
                assertTrue(rig.exactIdentityMatchesStock())
            }
            JournalLoadResult.Empty -> {
                assertTrue("stock mismatch at empty $point/$mutationIndex", rig.isExactStock())
                assertTrue(rig.exactIdentityMatchesStock())
            }
            is JournalLoadResult.Corrupt -> fail("corrupt journal at $point/$mutationIndex: ${loaded.reason}")
        }
    }

    // ---- the vendor performance-mode axis (2026-10-13 vendor-mode design, transport lane) ----------

    /** Fan + vendor mode only, so the topology holds no sysfs node and means the same on every host. */
    private fun settingsOnlyVendorCapabilities() = capabilities().copy(
        cpuPolicies = emptyList(),
        supportedAxes = setOf(TuningAxis.FAN, TuningAxis.VENDOR_MODE),
        gpuNodeAvailable = false,
        gpuFrequenciesKhz = emptyList(),
        vendorPerformanceModePresent = true,
    )

    @Test
    fun `a plan that drives the vendor mode applies fan then vendor mode and journals the mode in plan, order and projection`() {
        val rig = StatefulHardwareRig(initialVendorMode = 0)
        val journal = DurableFakeJournal()
        val persisted = mutableListOf<TransactionJournalRecord>()
        journal.onPersist = { persisted += it }

        val result = coordinator(rig, journal)
            .apply("balanced", "example.game", settingsOnlyVendorCapabilities(), dryRun = false, requestId = "vm-1")

        assertEquals(result.message, "APPLIED", result.code)
        // Balanced maps to the vendor's mode 1 , and the rig now holds it.
        assertEquals(TuningPlanner.VENDOR_MODE_PERFORMANCE, result.plan?.vendorMode)
        assertEquals(TuningPlanner.VENDOR_MODE_PERFORMANCE, rig.vendorMode)
        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, HardwareSnapshot.VENDOR_MODE_LOGICAL_ID),
            rig.executedMutations.map { it.logicalId },
        )
        val record = persisted.last()
        assertEquals(TransactionPhase.APPLIED, record.phase)
        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, HardwareSnapshot.VENDOR_MODE_LOGICAL_ID),
            record.applyOrderLogicalIds,
        )
        assertEquals(TuningPlanner.VENDOR_MODE_PERFORMANCE, record.appliedPlan?.vendorMode)
        assertEquals(PlanHash.ofPersisted(checkNotNull(record.appliedPlan)), record.planHash)
        assertEquals(
            TuningPlanner.VENDOR_MODE_PERFORMANCE,
            record.appliedMutationProjection.single { it.axis == TuningAxis.VENDOR_MODE }.value,
        )
        assertTrue(AppliedMutationProjectionValidator.validate(record).isEmpty())
        // The honest origin is the captured stock, not the mode Mimir wrote.
        assertEquals(0, record.snapshot.vendorMode)
        // The journal survives its own codec with the mode intact.
        val reloaded = (AtomicFileTransactionJournal.decodeJournal(
            AtomicFileTransactionJournal.encodeJournal(record),
        ) as JournalLoadResult.Loaded).record
        assertEquals(record, reloaded)
    }

    @Test
    fun `a device without the vendor-mode axis plans, applies and journals exactly as before`() {
        val rig = StatefulHardwareRig()
        val journal = DurableFakeJournal()
        val persisted = mutableListOf<TransactionJournalRecord>()
        journal.onPersist = { persisted += it }
        val withoutAxis = settingsOnlyVendorCapabilities().copy(
            supportedAxes = setOf(TuningAxis.FAN),
            vendorPerformanceModePresent = false,
        )

        val result = coordinator(rig, journal)
            .apply("balanced", "example.game", withoutAxis, dryRun = false, requestId = "vm-0")

        assertEquals(result.message, "APPLIED", result.code)
        assertNull(result.plan?.vendorMode)
        assertNull(rig.vendorMode)
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID), rig.executedMutations.map { it.logicalId })
        assertNull(persisted.last().appliedPlan?.vendorMode)
        assertTrue(persisted.last().snapshot.nodes.none { it.axis == TuningAxis.VENDOR_MODE })
    }

    @Test
    fun `the post-cooling CPU admission runs after the vendor mode write and before the first compute write`() {
        // The compute topology resolves /sys paths, which only a POSIX host can; on Windows the
        // coordinator refuses before PREPARED (the same reason the sibling apply suites are red there).
        org.junit.Assume.assumeTrue(SysfsPath.canonicalize(HardwareProbe.GPU_MIN_LEVEL) != null)
        val rig = StatefulHardwareRig(initialVendorMode = 0)
        val journal = DurableFakeJournal()
        val vendorCapabilities = capabilities().copy(
            supportedAxes = capabilities().supportedAxes + TuningAxis.VENDOR_MODE,
            vendorPerformanceModePresent = true,
        )

        val result = coordinator(rig, journal)
            .apply("balanced", "example.game", vendorCapabilities, dryRun = false, requestId = "vm-2")

        assertEquals(result.message, "APPLIED", result.code)
        assertEquals(
            listOf(HardwareSnapshot.FAN_LOGICAL_ID, HardwareSnapshot.VENDOR_MODE_LOGICAL_ID),
            rig.executedMutations.take(2).map { it.logicalId },
        )
        // Two cooling-side writes (fan, vendor mode) had run when the admission fired: it sits
        // immediately before the first compute command, not before the vendor write.
        assertEquals(2, rig.executesAtCpuAdmission)
        assertTrue(rig.executedMutations.drop(2).all { it.axis != TuningAxis.VENDOR_MODE })
    }

    private fun coordinator(
        rig: StatefulHardwareRig,
        journal: TransactionJournal,
        hardwareWritesCompiled: Boolean = true,
        barrier: TransactionBarrier = NoopTransactionBarrier,
        idFactory: () -> String = sequentialIds(),
        requiresGuardianAuthority: Boolean = false,
        guardianPreparedEnvelope: GuardianPreparedEnvelopePort = GuardianPreparedEnvelopePort { null },
        guardianArmAuthority: GuardianArmAuthorityPort = NoGuardianArmAuthority,
        onRestoreAttemptFinished: () -> Unit = {},
    ) = TransactionCoordinator(
        certifiedPlanner(),
        rig,
        rig,
        journal,
        hardwareWritesCompiled = hardwareWritesCompiled,
        barrier = barrier,
        idFactory = idFactory,
        clock = { 1_000L },
        requiresGuardianAuthority = requiresGuardianAuthority,
        guardianPreparedEnvelope = guardianPreparedEnvelope,
        guardianArmAuthority = guardianArmAuthority,
        onRestoreAttemptFinished = onRestoreAttemptFinished,
    )

    /**
     * The exact in-memory envelope prepared for [identity] before PREPARED is durable. Only the
     * Guardian-owned fields are fixtures; every journal-identity field is echoed, so a mismatch
     * can only come from the test deliberately introducing one. The arm store remains untouched.
     */
    private fun preparedEnvelope(identity: GuardianJournalIdentity) = SignedRestoreGuardianEnvelope(
        envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper-build-1",
            tweakerCertificateSha256 = "c".repeat(64),
            hardwareFingerprint = identity.hardwareFingerprint,
            deviceProfile = identity.deviceProfile,
            bootIdentity = identity.bootIdentity,
            generation = identity.generation,
            requestId = identity.requestId,
            transactionId = identity.transactionId,
            taskNonce = "b".repeat(32),
            topologySha256 = identity.expectedTopologySha256,
            snapshotSha256 = identity.snapshotSha256,
            planSha256 = identity.planSha256,
            hardDeadlineElapsedRealtimeMs = 901_000L,
            restoreEntries = listOf(RestoreGuardianFanDeleteEntry(applyOrdinal = 0)),
        ),
        signature = byteArrayOf(1),
    )

    private fun certifiedPlanner() = TuningPlanner { _, _ -> true }

    private fun sequentialIds(): () -> String {
        var n = 0
        return { "id-${n++}" }
    }

    private fun capabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_516_032, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(710_400, 2_032_320, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(844_800, 2_386_944, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 525_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private fun odinCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "Odin3",
        socModel = "CQ8725S",
        buildFingerprint = "qti/sun/test",
        deviceProfileId = "ayn-odin3-cq8725s-pserver",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(
                id = 0,
                path = "/sys/devices/system/cpu/cpufreq/policy0",
                cpuIds = listOf(0, 1, 2, 3, 4, 5),
                availableFrequenciesKhz = listOf(
                    998_400,
                    1_785_600,
                    1_996_800,
                    2_227_200,
                    2_745_600,
                    3_532_800,
                ),
                minKhz = 1_996_800,
                maxKhz = 3_532_800,
                currentKhz = 3_532_800,
            ),
            CpuPolicy(
                id = 6,
                path = "/sys/devices/system/cpu/cpufreq/policy6",
                cpuIds = listOf(6, 7),
                availableFrequenciesKhz = listOf(
                    1_075_200,
                    1_958_400,
                    2_246_400,
                    3_072_000,
                    4_320_000,
                ),
                minKhz = 2_246_400,
                maxKhz = 4_320_000,
                currentKhz = 4_320_000,
            ),
        ),
        gpuFrequenciesKhz = List(14) { index -> 200_000 + (index * 50_000) },
        gpuNodeAvailable = true,
        fanNodeAvailable = false,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
    )

    private fun transactionSnapshot(
        caps: HardwareCapabilities,
        plan: TuningPlan,
    ): HardwareSnapshot {
        val nodes = mutableListOf<TopologyNodeState>()
        caps.cpuPolicies.forEach { policy ->
            plan.cpuMaxKhz[policy.id] ?: return@forEach
            nodes += TopologyNodeState(
                logicalId = "cpu:${policy.id}:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = "${policy.path}/scaling_max_freq",
                originalValue = policy.maxKhz,
                originalMode = "664",
                originalUid = 1000,
                originalGid = 1000,
                present = true,
            )
        }
        if (plan.gpuMaxPowerLevel != null) {
            nodes += TopologyNodeState(
                logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
                originalValue = 6,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            )
            nodes += TopologyNodeState(
                logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                axis = TuningAxis.GPU,
                canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
                originalValue = 0,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            )
        }
        return HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = HardwareFingerprint.create(caps),
            bootIdentity = "boot-odin-test",
            nodes = nodes,
        )
    }

    private fun stockSnapshot(caps: HardwareCapabilities = capabilities()): HardwareSnapshot {
        val nodes = mutableListOf<TopologyNodeState>()
        caps.cpuPolicies.forEach { policy ->
            nodes += TopologyNodeState(
                logicalId = "cpu:${policy.id}:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = "${policy.path}/scaling_max_freq",
                originalValue = policy.maxKhz,
                originalMode = "644",
                originalUid = 0,
                originalGid = 0,
                present = true,
            )
        }
        nodes += TopologyNodeState(
            logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
            axis = TuningAxis.GPU,
            canonicalPath = HardwareProbe.GPU_MIN_LEVEL,
            originalValue = 0,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )
        nodes += TopologyNodeState(
            logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
            axis = TuningAxis.GPU,
            canonicalPath = HardwareProbe.GPU_MAX_LEVEL,
            originalValue = 0,
            originalMode = "644",
            originalUid = 0,
            originalGid = 0,
            present = true,
        )
        nodes += TopologyNodeState(
            logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
            axis = TuningAxis.FAN,
            canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            originalValue = 4,
            originalMode = null,
            originalUid = null,
            originalGid = null,
            present = true,
        )
        return HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = HardwareFingerprint.create(caps),
            bootIdentity = "boot-test",
            nodes = nodes,
        )
    }

    private fun appliedRecord(
        snapshot: HardwareSnapshot,
        plan: TuningPlan = certifiedPlanner().plan("balanced", "example.game", capabilities())!!,
        applyOrder: List<String> = snapshot.nodes.map { it.logicalId },
        schemaVersion: Int = TRANSACTION_JOURNAL_SCHEMA,
        caps: HardwareCapabilities = capabilities(),
    ): TransactionJournalRecord {
        val hashes = SnapshotIntegrity.bindHashes(plan, caps, snapshot)
            ?: error("stock snapshot failed integrity bind")
        val projectedById = StatefulHardwareRig().plannedMutations(plan, snapshot)
            .associateBy { it.logicalId }
        return TransactionJournalRecord(
            schemaVersion = schemaVersion,
            generation = 1,
            requestId = "req",
            transactionId = "tx",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = "rp5",
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1_000L,
            requestDeadline = 31_000L,
            leaseDeadline = 901_000L,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = snapshot.nodes.size,
            appliedPlan = if (schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V2) {
                PersistedTuningPlan.from(plan)
            } else {
                null
            },
            applyOrderLogicalIds =
                if (schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V2) applyOrder else emptyList(),
            appliedMutationProjection =
                if (schemaVersion >= TRANSACTION_JOURNAL_SCHEMA) {
                    applyOrder.mapNotNull(projectedById::get)
                        .map(PersistedAppliedMutation::from)
                } else {
                    emptyList()
                },
        )
    }

    private class OneShotDeathBarrier(
        private val point: TransactionBarrierPoint,
        private val mutationIndex: Int,
    ) : TransactionBarrier {
        private var fired = false
        override fun check(point: TransactionBarrierPoint, mutationIndex: Int) {
            if (fired) return
            if (point != this.point) return
            if (this.mutationIndex >= 0 && mutationIndex >= 0 && mutationIndex != this.mutationIndex) return
            fired = true
            throw TransactionDeathException(point, mutationIndex)
        }
    }

    /**
     * [ConfirmingTransport] that also observes the `settings put system fan_mode N` it is asked to
     * run, so a port built on it can answer `fanModeRaw` with the value actually written while the
     * bounded restore-settlement barrier is running. Added 2026-08-23 when that barrier stopped
     * being exact-Odin-gated and started applying to this file's RP5 fixture.
     */
    private class FanObservingTransport(private val onFanMode: (Int) -> Unit) : PServerBinderTransport {
        override fun transact(command: String, captureOutput: Boolean): PServerTransactResult {
            Regex("fan_mode (\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()?.let(onFanMode)
            return ConfirmingTransport().transact(command, captureOutput)
        }
    }

    private class ConfirmingTransport : PServerBinderTransport {
        override fun transact(command: String, captureOutput: Boolean): PServerTransactResult {
            val nonce = Regex("""MIMIR_TX_EXIT:([A-Za-z0-9_-]+):%s""").find(command)?.groupValues?.get(1)
                ?: "n1_abcdef12"
            return PServerTransactResult(
                accepted = true,
                output = "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:",
            )
        }
    }

    private fun confirmingClient(commands: MutableList<String>): PServerClient = PServerClient(
        transport = PServerBinderTransport { command, _ ->
            commands += command
            PServerTransactResult(true, "${PServerClient.EXIT_MARKER_PREFIX}${nonceOf(command)}:0:0:")
        },
    )

    private fun nonceOf(command: String): String =
        Regex("""MIMIR_TX_EXIT:([A-Za-z0-9_-]+):%s""").find(command)!!.groupValues[1]

    private fun sysfsMutation() = LogicalMutation(
        logicalId = "cpu:0:scaling_max_freq",
        axis = TuningAxis.CPU,
        canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        kind = LogicalMutationKind.SYSFS_WRITE,
        value = 1_804_800,
        restoreMode = "644",
        restoreUid = 0,
        restoreGid = 0,
        expectPresent = true,
    )

    /**
     * In-memory hardware that mutates real value/mode/UID/GID/fan state so death/reconstruction
     * proofs assert exact stock restoration rather than tautological fake booleans.
     */
    private class StatefulHardwareRig(
        private val fanPresentInitially: Boolean = true,
        initialFanMode: Int? = if (fanPresentInitially) 4 else null,
        private val cpuMinByPolicy: Map<Int, Int> = emptyMap(),
        initialVendorMode: Int? = null,
    ) : HardwareStatePort, HardwareCommandPort {
        private val cpu0Min = cpuMinByPolicy[0] ?: 300_000
        private val cpu4Min = cpuMinByPolicy[4] ?: 710_400
        private val cpu7Min = cpuMinByPolicy[7] ?: 844_800
        private val caps = HardwareCapabilities(
            status = CapabilityStatus.SUPPORTED,
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            buildFingerprint = "rp5/test",
            deviceProfileId = "rp5",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
            pServerAvailable = true,
            cpuPolicies = listOf(
                CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", listOf(0, 1, 2, 3), listOf(300_000, 1_516_032, 1_804_800), cpu0Min, 1_804_800, 1_804_800),
                CpuPolicy(4, "/sys/devices/system/cpu/cpufreq/policy4", listOf(4, 5, 6), listOf(710_400, 2_032_320, 2_419_200), cpu4Min, 2_419_200, 2_419_200),
                CpuPolicy(7, "/sys/devices/system/cpu/cpufreq/policy7", listOf(7), listOf(844_800, 2_386_944, 2_841_600), cpu7Min, 2_841_600, 2_841_600),
            ),
            gpuFrequenciesKhz = listOf(305_000, 525_000, 905_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
        )

        data class Node(
            var value: Int?,
            var mode: String?,
            var uid: Int?,
            var gid: Int?,
            val stockValue: Int?,
            val stockMode: String?,
            val stockUid: Int?,
            val stockGid: Int?,
            val presentStock: Boolean,
        )

        private val sysfs = linkedMapOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to Node(cpu0Min, "644", 0, 0, cpu0Min, "644", 0, 0, true),
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to Node(1_804_800, "644", 0, 0, 1_804_800, "644", 0, 0, true),
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq" to Node(cpu4Min, "644", 0, 0, cpu4Min, "644", 0, 0, true),
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq" to Node(2_419_200, "644", 0, 0, 2_419_200, "644", 0, 0, true),
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq" to Node(cpu7Min, "644", 0, 0, cpu7Min, "644", 0, 0, true),
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq" to Node(2_841_600, "644", 0, 0, 2_841_600, "644", 0, 0, true),
            HardwareProbe.GPU_MIN_LEVEL to Node(0, "644", 0, 0, 0, "644", 0, 0, true),
            HardwareProbe.GPU_MAX_LEVEL to Node(0, "644", 0, 0, 0, "644", 0, 0, true),
        )
        var fanMode: Int? = initialFanMode
        private val stockFan: Int? = initialFanMode
        /** The vendor `performance_mode`; null = the key does not exist on this rig. */
        var vendorMode: Int? = initialVendorMode
        private val stockVendorMode: Int? = initialVendorMode

        /** Every mutation `execute` received, in order -- the apply/restore order is observable. */
        val executedMutations = mutableListOf<LogicalMutation>()

        /** `executes` at the instant the post-cooling CPU admission ran (-1 = never). */
        var executesAtCpuAdmission = -1
        var omitModeOnCapture = false
        var failPlanVerify = false
        var failSnapshotVerify = false
        var failExecuteIndex: Int? = null
        var fingerprintOverride: String? = null
        var executes = 0

        fun liveCapabilities(): HardwareCapabilities = caps

        fun isExactStock(): Boolean {
            if (fanMode != stockFan) return false
            if (vendorMode != stockVendorMode) return false
            return sysfs.values.all {
                it.value == it.stockValue &&
                    it.mode == it.stockMode &&
                    it.uid == it.stockUid &&
                    it.gid == it.stockGid
            }
        }

        fun exactIdentityMatchesStock(): Boolean = isExactStock()

        fun perturbSysfs(path: String, value: Int) {
            sysfs.getValue(path).value = value
        }

        fun captureStockSnapshot(): HardwareSnapshot {
            val plan = TuningPlanner { _, _ -> true }.plan("balanced", "example.game", caps)!!
            return captureForPlan(plan, caps).snapshot!!
        }

        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
            val failures = mutableListOf<String>()
            val nodes = mutableListOf<TopologyNodeState>()
            plan.cpuMaxKhz.keys.forEach { policyId ->
                val policy = capabilities.cpuPolicies.first { it.id == policyId }
                val targetMax = plan.cpuMaxKhz.getValue(policyId)
                val capturedMin = sysfs.getValue("${policy.path}/scaling_min_freq")
                if (capturedMin.value != policy.minKhz) {
                    failures += "cpu:$policyId:scaling_min_freq changed during capture"
                }
                val nodeNames = buildList {
                    if (targetMax < policy.minKhz) add("scaling_min_freq")
                    add("scaling_max_freq")
                }
                nodeNames.forEach { nodeName ->
                    val path = "${policy.path}/$nodeName"
                    val node = sysfs.getValue(path)
                    if (omitModeOnCapture) failures += "cpu:$policyId:$nodeName missing mode"
                    nodes += TopologyNodeState(
                        logicalId = "cpu:$policyId:$nodeName",
                        axis = TuningAxis.CPU,
                        canonicalPath = path,
                        originalValue = node.value,
                        originalMode = if (omitModeOnCapture) null else node.mode,
                        originalUid = node.uid,
                        originalGid = node.gid,
                        present = true,
                    )
                }
            }
            if (plan.gpuMaxPowerLevel != null) {
                listOf(
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID to HardwareProbe.GPU_MIN_LEVEL,
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID to HardwareProbe.GPU_MAX_LEVEL,
                ).forEach { (logicalId, path) ->
                    val node = sysfs.getValue(path)
                    nodes += TopologyNodeState(
                        logicalId = logicalId,
                        axis = TuningAxis.GPU,
                        canonicalPath = path,
                        originalValue = node.value,
                        originalMode = node.mode,
                        originalUid = node.uid,
                        originalGid = node.gid,
                        present = true,
                    )
                }
            }
            if (plan.fanMode != null) {
                nodes += TopologyNodeState(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    originalValue = fanMode,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = fanMode != null,
                )
            }
            if (plan.vendorMode != null) {
                // The node value is the origin (what the probe resolves), here simply the live key.
                nodes += TopologyNodeState(
                    logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                    axis = TuningAxis.VENDOR_MODE,
                    canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
                    originalValue = vendorMode,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = vendorMode != null,
                )
            }
            if (failures.isNotEmpty()) return CaptureOutcome(failures = failures)
            return CaptureOutcome(
                snapshot = HardwareSnapshot(
                    schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                    hardwareFingerprint = fingerprintOverride ?: HardwareFingerprint.create(capabilities),
                    bootIdentity = "boot-test",
                    nodes = nodes,
                ),
            )
        }

        override fun verify(plan: TuningPlan): Boolean = verifyPlan(plan).ok
        override fun verifyPlan(plan: TuningPlan): PlanVerification {
            if (failPlanVerify) return PlanVerification(false, listOf("injected plan verify failure"))
            val failures = mutableListOf<String>()
            plan.cpuMaxKhz.forEach { (id, target) ->
                val path = caps.cpuPolicies.first { it.id == id }.path
                val min = sysfs.getValue("$path/scaling_min_freq").value
                val max = sysfs.getValue("$path/scaling_max_freq").value
                if (min == null || min <= 0 || min > target) failures += "cpu floor $id"
                if (max != target) failures += "cpu $id"
            }
            plan.gpuMaxPowerLevel?.let { target ->
                if (sysfs.getValue(HardwareProbe.GPU_MAX_LEVEL).value != target) failures += "gpu"
            }
            plan.fanMode?.let { target ->
                if (fanMode != target) failures += "fan"
            }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun verifyAppliedPlan(
            plan: TuningPlan,
            mutations: List<LogicalMutation>,
        ): PlanVerification {
            val failures = verifyPlan(plan).failures.toMutableList()
            // The real port treats the vendor mode as player-owned (PLAYER_OWNED_AXIS_LOGICAL_IDS):
            // reported, never a veto.
            mutations.filter { it.axis != TuningAxis.VENDOR_MODE }.forEach { mutation ->
                if (!readback(mutation)) failures += "projected mutation ${mutation.logicalId}"
            }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean = verifySnapshotPlan(snapshot).ok
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification {
            if (failSnapshotVerify) return PlanVerification(false, listOf("injected snapshot verify failure"))
            val failures = mutableListOf<String>()
            snapshot.nodes.forEach { node ->
                when (node.axis) {
                    TuningAxis.FAN -> {
                        val ok = if (!node.present) fanMode == null else fanMode == node.originalValue
                        if (!ok) failures += node.logicalId
                    }
                    TuningAxis.VENDOR_MODE -> Unit // player-owned: not a windowed veto
                    else -> {
                        val live = sysfs[node.canonicalPath]
                        if (live == null ||
                            live.value != node.originalValue ||
                            live.mode != node.originalMode ||
                            live.uid != node.originalUid ||
                            live.gid != node.originalGid
                        ) {
                            failures += node.logicalId
                        }
                    }
                }
            }
            return PlanVerification(failures.isEmpty(), failures)
        }

        override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification {
            executesAtCpuAdmission = executes
            return PlanVerification(true)
        }

        override fun readback(mutation: LogicalMutation): Boolean {
            return when (mutation.kind) {
                LogicalMutationKind.SYSFS_WRITE -> {
                    val live = sysfs[mutation.canonicalPath] ?: return false
                    live.value == mutation.value &&
                        live.mode == mutation.restoreMode &&
                        live.uid == mutation.restoreUid &&
                        live.gid == mutation.restoreGid
                }
                LogicalMutationKind.SETTINGS_PUT -> if (mutation.axis == TuningAxis.VENDOR_MODE) {
                    val live = vendorMode ?: return false
                    val applied = mutation.vendorModeContext?.applied
                    if (mutation.capturedSnapshotAuthority && applied != null) live != applied else live == mutation.value
                } else {
                    mutation.expectPresent && fanMode == mutation.value
                }
                LogicalMutationKind.SETTINGS_DELETE -> !mutation.expectPresent && fanMode == null
            }
        }

        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> {
            val compute = snapshot.nodes.filter {
                it.axis != TuningAxis.FAN && it.axis != TuningAxis.VENDOR_MODE
            }.map { node ->
                val value = when (node.axis) {
                    TuningAxis.CPU -> {
                        val id = node.logicalId.removePrefix("cpu:").substringBefore(":").toInt()
                        val max = plan.cpuMaxKhz.getValue(id)
                        if (node.logicalId.endsWith(":scaling_min_freq")) {
                            minOf(node.originalValue ?: max, max)
                        } else {
                            max
                        }
                    }
                    TuningAxis.GPU -> {
                        if (node.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID) {
                            caps.gpuFrequenciesKhz.size - 1
                        } else {
                            plan.gpuMaxPowerLevel
                        }
                    }
                    TuningAxis.FAN, TuningAxis.VENDOR_MODE -> null
                }
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = value,
                    restoreMode = node.originalMode,
                    restoreUid = node.originalUid,
                    restoreGid = node.originalGid,
                    expectPresent = true,
                )
            }
            val vendor = snapshot.nodes.filter { it.axis == TuningAxis.VENDOR_MODE }.map { node ->
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = LogicalMutationKind.SETTINGS_PUT,
                    value = plan.vendorMode,
                    restoreMode = null,
                    expectPresent = true,
                    vendorModeContext = VendorModeMutationContext(
                        origin = node.originalValue ?: 0,
                        applied = plan.vendorMode ?: 0,
                        transactionId = "rig",
                    ),
                )
            }
            val cooling = snapshot.nodes.filter { it.axis == TuningAxis.FAN }.map { node ->
                LogicalMutation(
                    logicalId = node.logicalId,
                    axis = node.axis,
                    canonicalPath = node.canonicalPath,
                    kind = LogicalMutationKind.SETTINGS_PUT,
                    value = plan.fanMode,
                    restoreMode = null,
                    expectPresent = true,
                )
            }
            // A plan that drives the vendor mode follows the real port: fan, vendor mode, compute.
            if (vendor.isNotEmpty()) return cooling + vendor + compute
            return when (plan.profileId) {
                "performance" -> cooling + compute
                else -> compute + cooling
            }
        }

        override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> {
            val order = HistoricApplyOrder.resolveLogicalIds(record).ifEmpty {
                val plan = record.appliedPlan?.toTuningPlan()
                    ?: TuningPlanner { _, _ -> true }.plan(record.profileId, "restore.order", caps)
                    ?: error("unknown profile ${record.profileId}")
                plannedMutations(plan, record.snapshot).map { it.logicalId }
            }
            return order.asReversed().map { logicalId ->
                val node = record.snapshot.nodes.first { it.logicalId == logicalId }
                when (node.axis) {
                    TuningAxis.FAN -> {
                        if (node.present) {
                            LogicalMutation(
                                logicalId = node.logicalId,
                                axis = node.axis,
                                canonicalPath = node.canonicalPath,
                                kind = LogicalMutationKind.SETTINGS_PUT,
                                value = node.originalValue,
                                restoreMode = null,
                                expectPresent = true,
                                capturedSnapshotAuthority = true,
                            )
                        } else {
                            LogicalMutation(
                                logicalId = node.logicalId,
                                axis = node.axis,
                                canonicalPath = node.canonicalPath,
                                kind = LogicalMutationKind.SETTINGS_DELETE,
                                value = null,
                                restoreMode = null,
                                expectPresent = false,
                                capturedSnapshotAuthority = true,
                            )
                        }
                    }
                    TuningAxis.VENDOR_MODE -> LogicalMutation(
                        logicalId = node.logicalId,
                        axis = node.axis,
                        canonicalPath = node.canonicalPath,
                        kind = LogicalMutationKind.SETTINGS_PUT,
                        value = node.originalValue,
                        restoreMode = null,
                        expectPresent = true,
                        capturedSnapshotAuthority = true,
                        vendorModeContext = VendorModeMutationContext(
                            origin = node.originalValue!!,
                            applied = record.appliedPlan!!.vendorMode!!,
                            transactionId = record.transactionId,
                        ),
                    )
                    else -> LogicalMutation(
                        logicalId = node.logicalId,
                        axis = node.axis,
                        canonicalPath = node.canonicalPath,
                        kind = LogicalMutationKind.SYSFS_WRITE,
                        value = node.originalValue,
                        restoreMode = node.originalMode,
                        restoreUid = node.originalUid,
                        restoreGid = node.originalGid,
                        expectPresent = true,
                    )
                }
            }
        }

        override fun validateRestore(record: TransactionJournalRecord): PlanVerification {
            val liveFp = fingerprintOverride ?: HardwareFingerprint.create(caps)
            if (record.hardwareFingerprint != liveFp) {
                return PlanVerification(false, listOf("hardwareFingerprint mismatch"))
            }
            return RestoreContextValidator.validate(record, caps)
        }

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            if (failExecuteIndex != null && executes == failExecuteIndex) {
                executes++
                return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "injected")
            }
            executes++
            executedMutations += mutation
            if (mutation.axis == TuningAxis.VENDOR_MODE) {
                val context = mutation.vendorModeContext
                if (mutation.capturedSnapshotAuthority && context != null) {
                    // The same three-way rule the real port applies: only Mimir's own value is written over.
                    if (vendorMode == context.applied) vendorMode = mutation.value
                } else {
                    vendorMode = mutation.value
                }
                return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
            }
            when (mutation.kind) {
                LogicalMutationKind.SYSFS_WRITE -> {
                    val live = sysfs.getValue(mutation.canonicalPath)
                    live.value = mutation.value
                    live.mode = mutation.restoreMode
                    live.uid = mutation.restoreUid
                    live.gid = mutation.restoreGid
                }
                LogicalMutationKind.SETTINGS_PUT -> fanMode = mutation.value
                LogicalMutationKind.SETTINGS_DELETE -> fanMode = null
            }
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }
    }

    private class DurableFakeJournal : TransactionJournal {
        var record: TransactionJournalRecord? = null
        var corruptReason: String? = null
        var persists = 0
        var clears = 0
        var clearSucceeds = true
        var onPersist: ((TransactionJournalRecord) -> Unit)? = null
        var failPersistOnceMatching: ((TransactionJournalRecord) -> Boolean)? = null
        private var generation = 0L

        override fun load(): JournalLoadResult {
            corruptReason?.let { return JournalLoadResult.Corrupt(it) }
            return record?.let { JournalLoadResult.Loaded(it) } ?: JournalLoadResult.Empty
        }

        override fun nextGeneration(): GenerationResult {
            generation += 1
            return GenerationResult.Ok(generation)
        }

        override fun persist(record: TransactionJournalRecord): Boolean {
            persists++
            onPersist?.invoke(record)
            val matcher = failPersistOnceMatching
            if (matcher != null && matcher(record)) {
                failPersistOnceMatching = null
                return false
            }
            this.record = record
            return true
        }

        override fun clear(expectedTransactionId: String): Boolean {
            clears++
            if (!clearSucceeds) return false
            val current = record ?: return true
            if (current.transactionId != expectedTransactionId) return false
            record = null
            return true
        }
    }

    private class FakeGuardianArmAuthority(
        private val presence: GuardianArmStorePresence = GuardianArmStorePresence.PRESENT,
        private val admission: (
            SignedRestoreGuardianEnvelope,
            RestoreGuardianExpectedIdentity,
        ) -> GuardianArmAdmission,
    ) : GuardianArmAuthorityPort {
        override fun armToReady(
            signed: SignedRestoreGuardianEnvelope,
            expected: RestoreGuardianExpectedIdentity,
        ): GuardianArmAdmission = admission(signed, expected)
        override fun presence(): GuardianArmStorePresence = presence
        override fun verifyTerminal(handle: GuardianReadyHandle) =
            GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
        override fun disarm(handle: GuardianReadyHandle) = false
    }
}
