package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4.3 / OWED-2.
 *
 * [TransactionCoordinator.reassertAppliedFanBound] is the exact method
 * `MimirTweakerService`'s `fanReasserter` wiring calls (mirrors how
 * [TransactionCoordinatorTargetRepairTest] exercises `repairAppliedCpuQosBound`, the method
 * `targetRepairer` wires -- same fixture shape, same style, adapted for the FAN axis). `Fixture
 * .rig` stands in for the live device port (pServer/sysfs/settings); a write actually reaching it
 * is exactly what OWED-2 requires: "a fan-only drift now actually re-asserts against the device
 * port."
 */
class TransactionCoordinatorFanReassertTest {
    @Test
    fun `late amendment acknowledgement failure retains its actual durable target`() {
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        val before = checkNotNull(f.journal.record)
        f.journal.failAfterAmendment = true
        val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }
        assertEquals("FAN_OWNERSHIP_PERSIST_FAILED", result.code)
        assertFalse(result.success)
        assertEquals(FanOwnershipAmendment.candidate(before, 6), f.journal.record)
        assertEquals(6, AppliedTargetContract.fromAuthenticatedAppliedRecord(checkNotNull(f.journal.record))?.fanMode)
        assertEquals(0, f.journal.clears)
    }

    @Test
    fun `owner loss after fan write blocks compute replay and journal amendment`() {
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        val before = checkNotNull(f.journal.record)
        var ownerLive = true
        f.rig.afterExecute = { ownerLive = false }
        val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { ownerLive }
        assertFalse(result.success)
        assertFalse(result.restored)
        assertEquals(before, f.journal.record)
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID), f.rig.executedLogicalIds)
        assertEquals(0, f.journal.clears)
    }

    @Test
    fun `unconfirmed compute replay keeps the original recovery authority`() {
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        val before = checkNotNull(f.journal.record)
        val cpu = before.appliedMutationProjection.single { it.axis == TuningAxis.CPU }
        f.rig.values[cpu.logicalId] = 3_532_800
        f.rig.failExecuteLogicalId = cpu.logicalId
        val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }
        assertEquals("FAN_TRANSITION_COMPUTE_UNCONFIRMED", result.code)
        assertFalse(result.restored)
        assertEquals(before, f.journal.record)
        assertEquals(0, f.journal.clears)
    }

    @Test
    fun `ownership transition repairs vendor replay using the existing compute target`() {
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        val before = checkNotNull(f.journal.record)
        val cpu = before.appliedMutationProjection.single { it.axis == TuningAxis.CPU }
        // The vendor Smart handoff can replay this policy before the transition runs.
        f.rig.values[cpu.logicalId] = 3_532_800
        val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }
        assertEquals(result.message, "FAN_OWNERSHIP_CHANGED", result.code)
        assertEquals(cpu.value, f.rig.values[cpu.logicalId])
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID, cpu.logicalId), f.rig.executedLogicalIds)
        assertEquals(FanOwnershipAmendment.candidate(before, 6), f.journal.record)
        assertEquals(0, f.journal.clears)
    }

    @Test
    fun `a fan ownership transition repairs compute but never rewrites a vendor mode the player changed`() {
        // The Guardian envelope validates /sys paths against the host filesystem, so this fixture can only be
        // built on a POSIX host (the sibling reassert tests are red on Windows for the same reason).
        org.junit.Assume.assumeTrue(SysfsPath.canonicalize(HardwareProbe.GPU_MIN_LEVEL) != null)
        // The session wrote vendor mode 2 over the player's 0. The player has since moved it (a yield
        // for the supervisor), and the vendor's fan-entry replay disturbed the CPU target. The
        // transition may repair the compute axis; the vendor mode is the player's and must not be
        // put back.
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true, vendorMode = 2)
        val before = checkNotNull(f.journal.record)
        val cpu = before.appliedMutationProjection.single { it.axis == TuningAxis.CPU }
        f.rig.values[cpu.logicalId] = 3_532_800
        f.rig.values[HardwareSnapshot.VENDOR_MODE_LOGICAL_ID] = 0

        val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }

        assertEquals(result.message, "FAN_OWNERSHIP_CHANGED", result.code)
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID, cpu.logicalId), f.rig.executedLogicalIds)
        assertFalse(f.rig.executedLogicalIds.contains(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID))
        assertEquals("the player's mode stands", 0, f.rig.values[HardwareSnapshot.VENDOR_MODE_LOGICAL_ID])
        assertEquals(cpu.value, f.rig.values[cpu.logicalId])
        // The amendment changes the fan and nothing else: the plan still names the session's mode.
        assertEquals(2, f.journal.record?.appliedPlan?.vendorMode)
        assertEquals(FanOwnershipAmendment.candidate(before, 6), f.journal.record)
        assertEquals(0, f.journal.clears)
    }

    @Test
    fun `signed ownership transitions preserve compute snapshot generation and lease in both directions`() {
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        val before = checkNotNull(f.journal.record)
        val compute = f.rig.values.filterKeys { it != HardwareSnapshot.FAN_LOGICAL_ID }
        for (target in listOf(6, 4)) {
            val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", target) { true }
            assertEquals(result.message, "FAN_OWNERSHIP_CHANGED", result.code)
            assertTrue(result.success)
            assertFalse(result.restored)
            assertEquals(FanOwnershipAmendment.candidate(before, target), f.journal.record)
            assertEquals(compute, f.rig.values.filterKeys { it != HardwareSnapshot.FAN_LOGICAL_ID })
            assertTrue(f.rig.executedLogicalIds.all { it == HardwareSnapshot.FAN_LOGICAL_ID })
            assertEquals(0, f.journal.clears)
        }
    }

    @Test
    fun `failed fan mutation or amendment never restores compute or clears the active journal`() {
        for (persistFailure in listOf(false, true)) {
            val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
            val before = f.journal.record
            val compute = f.rig.values.filterKeys { it != HardwareSnapshot.FAN_LOGICAL_ID }
            f.rig.failExecute = !persistFailure
            f.journal.failAmendment = persistFailure
            val result = f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }
            assertFalse(result.success)
            assertFalse(result.restored)
            assertEquals(before, f.journal.record)
            assertEquals(compute, f.rig.values.filterKeys { it != HardwareSnapshot.FAN_LOGICAL_ID })
            assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID), f.rig.executedLogicalIds)
            assertEquals(0, f.journal.clears)
        }
    }

    @Test
    fun `old envelopes and stale owners cannot grant a fan ownership transition`() {
        val old = fixture(true, fanMode = 4)
        assertFalse(old.coordinator.fanOwnershipTransitionAdmitted(7, "tx-7", 6))
        assertFalse(old.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { true }.success)
        assertTrue(old.rig.executedLogicalIds.isEmpty())
        val f = fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true)
        assertTrue(f.coordinator.fanOwnershipTransitionAdmitted(7, "tx-7", 6))
        assertFalse(f.coordinator.fanOwnershipTransitionAdmitted(8, "tx-8", 6))
        assertFalse(f.coordinator.transitionAppliedFanBound(7, "tx-7", 6) { false }.success)
        assertTrue(f.rig.executedLogicalIds.isEmpty())
    }

    @Test
    fun `fan amendment cannot change compute authority lease or snapshot`() {
        val before = checkNotNull(fixture(true, fanMode = 4, fanOwnershipTransitionsAllowed = true).journal.record)
        val valid = checkNotNull(FanOwnershipAmendment.candidate(before, 6))
        assertTrue(FanOwnershipAmendment.admits(before, valid))
        for (invalid in listOf(
            valid.copy(generation = 8), valid.copy(transactionId = "other"),
            valid.copy(leaseDeadline = valid.leaseDeadline + 1),
            valid.copy(snapshot = valid.snapshot.copy(bootIdentity = "other")),
            valid.copy(guardianBinding = null), valid.copy(phase = TransactionPhase.RESTORING),
            valid.copy(appliedPlan = valid.appliedPlan!!.copy(cpuMaxKhz = mapOf(0 to 3_532_800))),
            valid.copy(appliedMutationProjection = valid.appliedMutationProjection.map {
                if (it.axis == TuningAxis.CPU) it.copy(value = 3_532_800) else it
            }),
        )) assertFalse(FanOwnershipAmendment.admits(before, invalid))
    }
    /**
     * 2026-08-23. `contextFailures` required `record.deviceProfile == ODIN_3_PROFILE`, so this
     * whole repair path was refused on every other device for its identity alone -- every other leg
     * passing -- and the session fell through to Restore instead of being reasserted. The pickup
     * handoff filed that gate as "off the manual-fan path"; tracing
     * `HardwareSessionSupervisor.admitFanReassert -> GenerationBoundFanReasserter -> here` shows it
     * is squarely ON it, and family-wide manual fan is what made it bite: a Nova session holding
     * mode 6 is exactly the case this repair exists for.
     */
    @Test
    fun `a duty-owned Nova session reasserts its fan instead of being refused for its device profile`() {
        val fixture = fixture(
            withActiveGuardian = true,
            fanMode = TuningPlanner.FAN_MANUAL,
            deviceProfileId = "retroid-qcs8550-pserver",
            manufacturer = "Moorechip",
            model = "Retroid Pocket Nova",
            socModel = "QCS8550",
        )
        fixture.rig.driftFan(TuningPlanner.FAN_SMART)

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }

        assertEquals(result.message, TransactionCoordinator.FAN_REASSERTED, result.code)
        assertTrue(result.success)
        // The device port observed the write, and it carries the contract's own duty-owned mode.
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID), fixture.rig.executedLogicalIds)
        assertEquals(TuningPlanner.FAN_MANUAL, fixture.rig.values[HardwareSnapshot.FAN_LOGICAL_ID])
    }

    @Test
    fun `a SPORT-owned session reasserts fan_mode through the Guardian-fenced device port`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record
        val persistsBefore = fixture.journal.persists
        fixture.rig.driftFan(TuningPlanner.FAN_SMART)

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }

        assertEquals(TransactionCoordinator.FAN_REASSERTED, result.code)
        assertTrue(result.success)
        assertFalse(result.applied)
        // The device port actually observed the write, and it carries the SIGNED contract's own
        // fanMode (SPORT) -- read back from the journal, never a hardcoded constant.
        assertEquals(listOf(HardwareSnapshot.FAN_LOGICAL_ID), fixture.rig.executedLogicalIds)
        assertEquals(TuningPlanner.FAN_SPORT, fixture.rig.values[HardwareSnapshot.FAN_LOGICAL_ID])
        // Duty is not part of this signed transaction on this device (only fan_mode is -- see
        // HardwareSnapshot.FAN_LOGICAL_ID) and stays exclusively owned by the existing,
        // already-bounded fan-control tick loop, unchanged by this lane.
        assertTrue(fixture.rig.executedLogicalIds.none { it.contains("duty", ignoreCase = true) })
        // Coordinator-silent, exactly like repairAppliedCpuQosBound: no new journal write, no
        // clear -- this is a reassert of an UNCHANGED contract, not a new apply.
        assertEquals(persistsBefore, fixture.journal.persists)
        assertEquals(0, fixture.journal.clears)
        assertEquals(before, fixture.journal.record)
    }

    @Test
    fun `a MANUAL-owned session reasserts its own signed fan_mode -- never a hardcoded value`() {
        // Same method, a DIFFERENT already-signed contract (MANUAL=6 instead of SPORT=5): proves
        // reassertAppliedFanBound reads the target from the journal every time rather than being
        // hardcoded to one mode.
        val fixture = fixture(withActiveGuardian = true, fanMode = TuningPlanner.FAN_MANUAL)
        fixture.rig.driftFan(TuningPlanner.FAN_SMART)

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }

        assertEquals(TransactionCoordinator.FAN_REASSERTED, result.code)
        assertEquals(TuningPlanner.FAN_MANUAL, fixture.rig.values[HardwareSnapshot.FAN_LOGICAL_ID])
    }

    @Test
    fun `missing Guardian stale identity and lease margin execute zero fan reassert commands`() {
        val noGuardian = fixture(withActiveGuardian = false)
        noGuardian.rig.driftFan(TuningPlanner.FAN_SMART)
        assertEquals(
            "FAN_REASSERT_CONTEXT_REJECTED",
            noGuardian.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }.code,
        )
        assertTrue(noGuardian.rig.executedLogicalIds.isEmpty())

        val stale = fixture(withActiveGuardian = true)
        stale.rig.driftFan(TuningPlanner.FAN_SMART)
        assertEquals(
            "STALE_GENERATION",
            stale.coordinator.reassertAppliedFanBound(8L, "tx-8") { true }.code,
        )
        assertTrue(stale.rig.executedLogicalIds.isEmpty())

        val expiring = fixture(withActiveGuardian = true, nowMs = 88_000L)
        expiring.rig.driftFan(TuningPlanner.FAN_SMART)
        assertEquals(
            "FAN_REASSERT_CONTEXT_REJECTED",
            expiring.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }.code,
        )
        assertTrue(expiring.rig.executedLogicalIds.isEmpty())
    }

    @Test
    fun `a session not duty-owning the fan is rejected before any command`() {
        // A structurally valid, signed SMART (vendor-Smart) contract is still not a mode Mimir
        // directly duty-drives -- MIMIR_DUTY_OWNED_FAN_MODES is SPORT and MANUAL only. Reasserting
        // here would be meaningless (there is nothing Mimir owns to reassert) and must be refused
        // before any command, the same defense-in-depth posture as the other context checks.
        val fixture = fixture(withActiveGuardian = true, fanMode = TuningPlanner.FAN_SMART)
        fixture.rig.driftFan(TuningPlanner.FAN_SILENT)

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }

        assertEquals("FAN_REASSERT_CONTEXT_REJECTED", result.code)
        assertTrue(fixture.rig.executedLogicalIds.isEmpty())
    }

    @Test
    fun `fan reassert command failure degrades honestly -- never restores, never persists`() {
        val fixture = fixture(withActiveGuardian = true)
        val before = fixture.journal.record
        fixture.rig.driftFan(TuningPlanner.FAN_SMART)
        fixture.rig.failExecute = true

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }

        assertEquals("FAN_REASSERT_FAILED", result.code)
        assertFalse(result.success)
        assertFalse(result.restored)
        assertEquals(before, fixture.journal.record)
        assertEquals(0, fixture.journal.persists)
        assertEquals(0, fixture.journal.clears)
    }

    @Test
    fun `mid reassert owner loss stops the write and this method never restores`() {
        val fixture = fixture(withActiveGuardian = true)
        var authorized = true
        fixture.rig.driftFan(TuningPlanner.FAN_SMART)
        fixture.rig.afterExecute = { authorized = false }

        val result = fixture.coordinator.reassertAppliedFanBound(7L, "tx-7") { authorized }

        assertEquals("FAN_REASSERT_FENCE_LOST", result.code)
        assertFalse(result.success)
        assertFalse(result.restored)
        assertEquals(1, fixture.rig.executedLogicalIds.size)
    }

    @Test
    fun `mid reassert lease or Guardian loss stops the write and never restores`() {
        val lease = fixture(withActiveGuardian = true)
        lease.rig.driftFan(TuningPlanner.FAN_SMART)
        lease.rig.afterExecute = { lease.clock.nowMs = 90_000L }
        assertEquals(
            "FAN_REASSERT_FENCE_LOST",
            lease.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }.code,
        )
        assertEquals(1, lease.rig.executedLogicalIds.size)

        val guardian = fixture(withActiveGuardian = true)
        guardian.rig.driftFan(TuningPlanner.FAN_SMART)
        guardian.rig.afterExecute = guardian::clearGuardian
        assertEquals(
            "FAN_REASSERT_FENCE_LOST",
            guardian.coordinator.reassertAppliedFanBound(7L, "tx-7") { true }.code,
        )
        assertEquals(1, guardian.rig.executedLogicalIds.size)
    }

    private fun fixture(
        withActiveGuardian: Boolean,
        nowMs: Long = 1_000L,
        fanMode: Int = TuningPlanner.FAN_SPORT,
        packageName: String = "com.example.game",
        // 2026-08-23: the reassert context no longer requires the exact Odin device profile, so the
        // fixture has to be able to be a different device. Identity is threaded as a set rather
        // than a lone id -- a record whose profile id says Nova while its manufacturer says AYN
        // would be a fixture that cannot exist on hardware, and the fingerprint is derived from all
        // of it.
        deviceProfileId: String = CpuCeilingTransport.ODIN_3_PROFILE,
        manufacturer: String = "ayn",
        model: String = "Odin3",
        socModel: String = "CQ8725S",
        fanOwnershipTransitionsAllowed: Boolean = false,
        /** Non-null adds the vendor-mode node, plan target and projection entry (origin 0). */
        vendorMode: Int? = null,
    ): Fixture {
        val capabilities = fanCapabilities(deviceProfileId, manufacturer, model, socModel)
        val plan = TuningPlan(
            profileId = "performance",
            packageName = packageName,
            cpuMaxKhz = mapOf(0 to 1_996_800),
            gpuMaxPowerLevel = null,
            fanMode = fanMode,
            calibrated = true,
            vendorMode = vendorMode,
        )
        val fingerprint = HardwareFingerprint.create(capabilities)
        val cpu = cpuNode(0, 3_532_800)
        // Captured Stock vendor fan mode -- distinct from `fanMode` (the signed target), same
        // shape as production capture: SMART is Odin's own idle/default policy.
        val fan = fanNode(TuningPlanner.FAN_SMART)
        // Topology/snapshot identity ordering (SnapshotIntegrity.expectedTopology) is CPU-then-
        // FAN, independent of apply-mutation ordering below.
        val vendor = vendorMode?.let { vendorNode(origin = 0) }
        val nodes = listOfNotNull(cpu, fan, vendor)
        val snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = fingerprint,
            bootIdentity = "boot-odin",
            nodes = nodes,
        )
        val hashes = checkNotNull(SnapshotIntegrity.bindHashes(plan, capabilities, snapshot))
        // Apply-mutation ordering is fan-first ("cooling + compute", AndroidTransactionPorts
        // .plannedMutations) -- required so the fan restore entry lands at applyOrdinal 0, the
        // exact position GuardianEnvelopeValidation.requireRestoreOrder's fan-first exception
        // requires.
        val mutations = listOfNotNull(
            fanMutation(fan, fanMode),
            mutation(cpu, plan.cpuMaxKhz.getValue(0)),
            // Last in this fixture so the Guardian entries (which have no vendor-mode kind yet) stay
            // contiguous from ordinal 0; the real renderer order is fan, vendor mode, compute.
            vendor?.let { vendorMutation(it, checkNotNull(vendorMode)) },
        )
        val unsignedRecord = TransactionJournalRecord(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            generation = 7L,
            requestId = "req-7",
            transactionId = "tx-7",
            hardwareFingerprint = fingerprint,
            deviceProfile = deviceProfileId,
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1_000L,
            requestDeadline = 31_000L,
            leaseDeadline = 90_000L,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = hashes.first,
            snapshotSha256 = hashes.second,
            lastCompletedMutationBoundary = mutations.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = mutations.map { it.logicalId },
            appliedMutationProjection = mutations.map(PersistedAppliedMutation::from),
        )
        // Built through the same production ordering helper OWED-1 extended -- this envelope is
        // exactly what AndroidGuardianPreparedEnvelopePort renders for a real SPORT/MANUAL
        // session, not a hand-rolled approximation.
        val restoreEntries = (if (fanOwnershipTransitionsAllowed) mutations.withIndex().toList()
            else RestoreMutationOrder.indexed(mutations)).filterNot { it.value.axis == TuningAxis.VENDOR_MODE }.map { indexed ->
            val applyOrdinal = indexed.index
            val m = indexed.value
            val node = nodes.single { it.logicalId == m.logicalId }
            if (m.axis == TuningAxis.FAN) {
                RestoreGuardianFanPutEntry(
                    applyOrdinal = applyOrdinal,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(
                        checkNotNull(node.originalValue),
                    ),
                    rendererAppliedIntermediates = if (fanOwnershipTransitionsAllowed) FAN_OWNERSHIP_TARGETS else listOf(checkNotNull(m.value)),
                )
            } else {
                RestoreGuardianSysfsEntry(
                    applyOrdinal = applyOrdinal,
                    logicalId = m.logicalId,
                    canonicalSysfsPath = m.canonicalPath,
                    expectedStock = RestoreGuardianStockState.PresentSysfs(
                        value = checkNotNull(node.originalValue),
                        mode = checkNotNull(node.originalMode),
                        uid = checkNotNull(node.originalUid),
                        gid = checkNotNull(node.originalGid),
                    ),
                    rendererAppliedIntermediates = listOf(checkNotNull(m.value)),
                )
            }
        }
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper-build",
            tweakerCertificateSha256 = "c".repeat(64),
            hardwareFingerprint = fingerprint,
            deviceProfile = unsignedRecord.deviceProfile,
            bootIdentity = unsignedRecord.bootIdentity,
            generation = unsignedRecord.generation,
            requestId = unsignedRecord.requestId,
            transactionId = unsignedRecord.transactionId,
            taskNonce = "b".repeat(32),
            topologySha256 = unsignedRecord.expectedTopologySha256,
            snapshotSha256 = unsignedRecord.snapshotSha256,
            planSha256 = unsignedRecord.planHash,
            hardDeadlineElapsedRealtimeMs = unsignedRecord.leaseDeadline,
            restoreEntries = restoreEntries,
        )
        val record = unsignedRecord.copy(
            guardianBinding = GuardianJournalBinding(
                envelopeSha256 = envelope.digestSha256(),
                taskNonce = envelope.taskNonce,
                helperBuildIdentity = envelope.helperBuildIdentity,
                tweakerCertificateSha256 = envelope.tweakerCertificateSha256,
                hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
                readySessionSha256 = "d".repeat(64),
            ),
        )
        check(AppliedMutationProjectionValidator.validate(record).isEmpty())
        check(GuardianJournalBindingValidator.validate(record).isEmpty())
        val journal = MemoryJournal(record)
        val rig = FanReassertRig(record)
        val clock = MutableClock(nowMs)
        val coordinator = TransactionCoordinator(
            planner = TuningPlanner { _, _ -> true },
            state = rig,
            commands = rig,
            journal = journal,
            hardwareWritesCompiled = true,
            clock = { clock.nowMs },
            requiresGuardianAuthority = true,
        )
        if (withActiveGuardian) {
            val expected = checkNotNull(GuardianArmExpectedIdentityDerivation.from(record))
            val handle = GuardianReadyHandle(
                expectedIdentity = expected,
                signedEnvelope = SignedRestoreGuardianEnvelope(envelope, byteArrayOf(1)),
                readySessionSha256 = "d".repeat(64),
            )
            TransactionCoordinator::class.java.getDeclaredField("activeGuardianHandle")
                .apply { isAccessible = true }
                .set(coordinator, handle)
        }
        return Fixture(coordinator, rig, journal, clock)
    }

    private fun cpuNode(policyId: Int, original: Int) = TopologyNodeState(
        logicalId = "cpu:$policyId:scaling_max_freq",
        axis = TuningAxis.CPU,
        canonicalPath = HardwareProbe.CPU_QOS_MAX_PATH,
        originalValue = original,
        originalMode = "644",
        originalUid = 0,
        originalGid = 0,
        present = true,
    )

    private fun fanNode(capturedVendorMode: Int) = TopologyNodeState(
        logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
        axis = TuningAxis.FAN,
        canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
        originalValue = capturedVendorMode,
        originalMode = null,
        originalUid = null,
        originalGid = null,
        present = true,
    )

    private fun vendorNode(origin: Int) = TopologyNodeState(
        logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
        axis = TuningAxis.VENDOR_MODE,
        canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
        originalValue = origin,
        originalMode = null,
        originalUid = null,
        originalGid = null,
        present = true,
    )

    private fun vendorMutation(node: TopologyNodeState, value: Int) = LogicalMutation(
        logicalId = node.logicalId,
        axis = node.axis,
        canonicalPath = node.canonicalPath,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        expectPresent = true,
    )

    private fun mutation(node: TopologyNodeState, value: Int) = LogicalMutation(
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

    private fun fanMutation(node: TopologyNodeState, value: Int) = LogicalMutation(
        logicalId = node.logicalId,
        axis = node.axis,
        canonicalPath = node.canonicalPath,
        kind = LogicalMutationKind.SETTINGS_PUT,
        value = value,
        restoreMode = null,
        restoreUid = null,
        restoreGid = null,
        expectPresent = true,
    )

    private fun fanCapabilities(
        deviceProfileId: String = CpuCeilingTransport.ODIN_3_PROFILE,
        manufacturer: String = "ayn",
        model: String = "Odin3",
        socModel: String = "CQ8725S",
    ) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = manufacturer,
        model = model,
        socModel = socModel,
        buildFingerprint = "qti/sun/test",
        deviceProfileId = deviceProfileId,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/sys/devices/system/cpu/cpufreq/policy0", (0..5).toList(), listOf(1_996_800, 3_532_800), 998_400, 3_532_800, 3_532_800),
        ),
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60, 120),
        notes = emptyList(),
        cpuCeilingPath = HardwareProbe.CPU_QOS_MAX_PATH,
    )

    private data class Fixture(
        val coordinator: TransactionCoordinator,
        val rig: FanReassertRig,
        val journal: MemoryJournal,
        val clock: MutableClock,
    ) {
        fun clearGuardian() {
            TransactionCoordinator::class.java.getDeclaredField("activeGuardianHandle")
                .apply { isAccessible = true }
                .set(coordinator, null)
        }
    }

    private data class MutableClock(var nowMs: Long)

    private class MemoryJournal(initial: TransactionJournalRecord) : TransactionJournal {
        var record: TransactionJournalRecord? = initial
        var persists = 0
        var clears = 0
        var failAmendment = false
        var failAfterAmendment = false
        override fun load(): JournalLoadResult = record?.let(JournalLoadResult::Loaded) ?: JournalLoadResult.Empty
        override fun nextGeneration() = GenerationResult.Ok(8L)
        override fun persist(record: TransactionJournalRecord): Boolean {
            persists++
            this.record = record
            return true
        }
        override fun amendFanOwnership(record: TransactionJournalRecord, envelope: RestoreGuardianEnvelope): Boolean {
            val before = this.record ?: return false
            if (failAmendment || !FanOwnershipAmendment.admits(before, record) ||
                !FanOwnershipAmendment.authorized(before, envelope)
            ) return false
            val persisted = persist(record)
            return persisted && !failAfterAmendment
        }
        override fun clear(expectedTransactionId: String): Boolean {
            clears++
            record = null
            return true
        }
    }

    /** Stands in for the live device port (pServer/sysfs/settings) -- see class kdoc. */
    private class FanReassertRig(record: TransactionJournalRecord) : HardwareStatePort, HardwareCommandPort {
        private val stockValues = record.snapshot.nodes.associate { it.logicalId to it.originalValue }
        val values = record.appliedMutationProjection.associate { it.logicalId to it.value }.toMutableMap()
        val executedLogicalIds = mutableListOf<String>()
        var failExecute = false
        var failExecuteLogicalId: String? = null
        var afterExecute: (() -> Unit)? = null

        fun driftFan(liveVendorMode: Int) {
            values[HardwareSnapshot.FAN_LOGICAL_ID] = liveVendorMode
        }

        override fun execute(mutation: LogicalMutation): CheckedCommandResult {
            executedLogicalIds += mutation.logicalId
            if (failExecute || mutation.logicalId == failExecuteLogicalId) {
                return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "injected")
            }
            values[mutation.logicalId] = mutation.value
            afterExecute?.invoke()
            return CheckedCommandResult(CheckedCommandStatus.CONFIRMED, exitCode = 0, detail = "ok")
        }

        override fun readback(mutation: LogicalMutation): Boolean = values[mutation.logicalId] == mutation.value
        // Like the real port, the player-owned vendor mode is reported, never a verification veto.
        override fun verifyAppliedPlan(plan: TuningPlan, mutations: List<LogicalMutation>) =
            PlanVerification(mutations.filter { it.axis != TuningAxis.VENDOR_MODE }.all(::readback))
        override fun validateRestore(record: TransactionJournalRecord) = PlanVerification(true)
        override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities) = error("unused")
        override fun verify(plan: TuningPlan) = true
        override fun verifyPlan(plan: TuningPlan) = PlanVerification(true)
        override fun verifySnapshot(snapshot: HardwareSnapshot) = true
        override fun verifySnapshotPlan(snapshot: HardwareSnapshot) = PlanVerification(true)
        override fun admitCpuTargetsAfterCooling(plan: TuningPlan) = PlanVerification(true)
        override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot) = emptyList<LogicalMutation>()
        override fun restoreMutations(record: TransactionJournalRecord) = emptyList<LogicalMutation>()

        @Suppress("unused")
        private val unusedStockValuesReadAccess = stockValues
    }
}
