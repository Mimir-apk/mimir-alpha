package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFanSafetyTest {
    @Test
    fun `manual fan restore is first and remaining compute mutations stay reverse ordered`() {
        val fan = mutation(
            logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
            axis = TuningAxis.FAN,
            kind = LogicalMutationKind.SETTINGS_PUT,
            value = TuningPlanner.FAN_MANUAL,
            path = HardwareSnapshot.FAN_SETTINGS_PATH,
        )
        val cpu0 = mutation("cpu:0:scaling_max_freq", TuningAxis.CPU, value = 1_000)
        val cpu6 = mutation("cpu:6:scaling_max_freq", TuningAxis.CPU, value = 2_000)

        assertEquals(
            listOf(0, 2, 1),
            RestoreMutationOrder.indexed(listOf(fan, cpu0, cpu6)).map { it.index },
        )
        // Ownership now defaults to SPORT (5), not MANUAL (6) -- device-measured, see
        // TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES. It must
        // get the identical fan-first protection: both stop the vendor duty loop the same way, so
        // a SPORT-owned session is exactly as exposed to the vendor's delayed mode replay as a
        // MANUAL one. OWED-1 differential: reverting RestoreMutationOrder's `in
        // MIMIR_DUTY_OWNED_FAN_MODES` back to `== FAN_MANUAL` makes this assertion fail (SPORT
        // would fall through to plain reverse order, [2, 1, 0], like the SMART case below).
        assertEquals(
            listOf(0, 2, 1),
            RestoreMutationOrder.indexed(
                listOf(fan.copy(value = TuningPlanner.FAN_SPORT), cpu0, cpu6),
            ).map { it.index },
        )
        // A plain vendor mode (SMART=4) that Mimir does not duty-drive stays plain reverse order.
        assertEquals(
            listOf(2, 1, 0),
            RestoreMutationOrder.indexed(listOf(fan.copy(value = 4), cpu0, cpu6)).map { it.index },
        )
    }

    @Test
    fun `Guardian accepts only the bounded manual fan first exception`() {
        val cpu = RestoreGuardianSysfsEntry(
            applyOrdinal = 1,
            logicalId = "cpu:0:scaling_max_freq",
            canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            expectedStock = RestoreGuardianStockState.PresentSysfs(2_000, "644", 0, 0),
            rendererAppliedIntermediates = listOf(1_000),
        )
        val manualFan = RestoreGuardianFanPutEntry(
            applyOrdinal = 0,
            expectedStock = RestoreGuardianStockState.PresentFanMode(4),
            rendererAppliedIntermediates = listOf(TuningPlanner.FAN_MANUAL),
        )
        // Backward compatibility (OWED-1 requirement): an envelope recorded with the historic
        // exact [FAN_MANUAL] marker -- e.g. durable on disk from a build before this lane -- must
        // still validate unchanged. A device that crashed mid-session under the old build must
        // still recover under the new one.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(manualFan, cpu))

        // Ownership now defaults to SPORT (5) -- the marker and this validator must agree by
        // construction over the SAME TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES set, so a SPORT
        // marker is accepted exactly like MANUAL. OWED-1 differential: reverting
        // GuardianEnvelopeValidation.requireRestoreOrder's `in MIMIR_DUTY_OWNED_FAN_MODES` back to
        // `== listOf(FAN_MANUAL)` makes this assertion throw.
        GuardianEnvelopeValidation.requireRestoreOrder(
            listOf(manualFan.copy(rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SPORT)), cpu),
        )

        // A value outside the Mimir-duty-owned set (SMART=4 -- a real, known vendor mode, not
        // arbitrary noise) is still correctly rejected as a fan-first marker: extending the set
        // did not loosen this into accepting anything.
        assertRejected {
            GuardianEnvelopeValidation.requireRestoreOrder(
                listOf(manualFan.copy(rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SMART)), cpu),
            )
        }
        // Wrong apply order is still rejected regardless of which owned marker is present --
        // requireRestoreOrder was not weakened into accepting arbitrary orders.
        assertRejected {
            GuardianEnvelopeValidation.requireRestoreOrder(
                listOf(manualFan.copy(applyOrdinal = 2), cpu.copy(applyOrdinal = 3)),
            )
        }
        assertRejected {
            GuardianEnvelopeValidation.requireRestoreOrder(
                listOf(
                    manualFan.copy(
                        applyOrdinal = 2,
                        rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SPORT),
                    ),
                    cpu.copy(applyOrdinal = 3),
                ),
            )
        }
    }

    @Test
    fun `manual monitor deadman recognises a SPORT-owned session exactly like MANUAL`() {
        // S4.3 / OWED-1 (see TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES):
        // ManualFanMonitorLiveness.ownsManualFan() must key
        // off the same TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES set as the restore-ordering sites
        // above. Before this correction a SPORT-owned session was invisible to this deadman -- every
        // heartbeat was allowed unconditionally, regardless of controller health -- because
        // ownsManualFan() only matched FAN_MANUAL. Differential: reverting
        // ManualFanMonitorLiveness's `in MIMIR_DUTY_OWNED_FAN_MODES` back to `== FAN_MANUAL` makes
        // the first assertion below fail (allowsHeartbeat would return true with null truth).
        val liveness = ManualFanMonitorLiveness()
        val sportEnvelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper",
            tweakerCertificateSha256 = "a".repeat(64),
            hardwareFingerprint = "hardware",
            deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
            bootIdentity = "boot",
            generation = 7,
            requestId = "request",
            transactionId = "transaction",
            taskNonce = "b".repeat(32),
            topologySha256 = "c".repeat(64),
            snapshotSha256 = "d".repeat(64),
            planSha256 = "e".repeat(64),
            hardDeadlineElapsedRealtimeMs = 30_000,
            restoreEntries = listOf(
                RestoreGuardianFanPutEntry(
                    applyOrdinal = 0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SPORT),
                ),
            ),
        )
        // No journal truth yet: a genuinely Mimir-owned fan session (SPORT included) must deny
        // the heartbeat, exactly as the MANUAL case does in the test above.
        assertFalse(liveness.allowsHeartbeat(sportEnvelope, 1_000, null, null))
        // Once the controller reports healthy, the heartbeat is admitted within the same bounded
        // window MANUAL uses -- the SPORT and MANUAL paths share one implementation, not two.
        val identity = activeIdentity()
        liveness.markHealthy(identity, 1_000)
        assertTrue(
            liveness.allowsHeartbeat(sportEnvelope, 8_000, truth(TransactionPhase.APPLIED), null),
        )
        assertFalse(
            liveness.allowsHeartbeat(sportEnvelope, 8_001, truth(TransactionPhase.APPLIED), null),
        )

        // A plain vendor SMART envelope (Mimir does not duty-drive it) is correctly NOT gated by
        // this deadman at all -- allowsHeartbeat admits unconditionally, same as before this lane.
        val smartEnvelope = sportEnvelope.copy(
            restoreEntries = listOf(
                RestoreGuardianFanPutEntry(
                    applyOrdinal = 0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SMART),
                ),
            ),
        )
        assertTrue(liveness.allowsHeartbeat(smartEnvelope, 1_000, null, null))
    }

    /**
     * TR-06 : both `PowerTransitionFanRecovery` and this class exist to
     * catch a stopped vendor loop. Under R2 the loop is never stopped for a SMART-driven device, so
     * they become backstops rather than primaries -- but the Odin's SPORT-owned session is
     * UNCHANGED by this wave (TR-08 territory) and still genuinely stops the vendor loop, so this
     * deadman must still fire for it. This restates the SPORT case above for an observed session that is off-SMART mid-session -- rather
     * than adding new mechanism coverage: [ownsManualFan] already keys off
     * [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES], untouched by R2/R3, so a SMART-driven device
     * (every RP/AYN device but the Odin, now) stays correctly ungated, exactly as the SMART-envelope
     * assertion in the test above already proves.
     */
    @Test
    fun `manual monitor deadman still fires for a duty-owned session left off-SMART mid-session`() {
        val liveness = ManualFanMonitorLiveness()
        val offSmartEnvelope = manualEnvelope().copy(
            restoreEntries = listOf(
                RestoreGuardianFanPutEntry(
                    applyOrdinal = 0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    // The Odin's own ordinary entry: mode 5, not 6 -- but either duty-owned value
                    // must gate this deadman, which is the entire point of MIMIR_DUTY_OWNED_FAN_MODES.
                    rendererAppliedIntermediates = listOf(TuningPlanner.FAN_SPORT),
                ),
            ),
        )
        // No journal truth yet, mid-session, off-SMART: the deadman must deny the heartbeat rather
        // than admit unconditionally -- proving it still actively guards this session, not merely
        // that it exists.
        assertFalse(
            "a duty-owned session observed off-SMART mid-session must still be denied a heartbeat " +
                "with no journal truth",
            liveness.allowsHeartbeat(offSmartEnvelope, 1_000, null, null),
        )
    }

    @Test
    fun `manual monitor heartbeat admits bounded apply handoff then requires fresh controller progress`() {
        val liveness = ManualFanMonitorLiveness()
        val envelope = manualEnvelope()
        val identity = activeIdentity()
        val applying = truth(TransactionPhase.APPLYING)
        val applied = truth(TransactionPhase.APPLIED)

        assertFalse(liveness.allowsHeartbeat(envelope, 1_000, null, null))
        assertTrue(liveness.allowsHeartbeat(envelope, 1_000, applying, null))
        assertTrue(liveness.allowsHeartbeat(envelope, 46_000, applying, null))
        assertFalse(liveness.allowsHeartbeat(envelope, 46_001, applying, null))

        liveness.disarm()
        assertTrue(liveness.allowsHeartbeat(envelope, 1_000, applied, null))
        assertTrue(liveness.allowsHeartbeat(envelope, 7_000, applied, null))
        assertFalse(liveness.allowsHeartbeat(envelope, 7_001, applied, null))
        liveness.markHealthy(identity, 1_000)
        assertTrue(liveness.allowsHeartbeat(envelope, 8_000, applied, null))
        assertFalse(liveness.allowsHeartbeat(envelope, 8_001, applied, null))
        liveness.markHealthy(identity, 9_000)
        assertTrue(liveness.allowsHeartbeat(envelope, 8_983, applied, null))
        assertFalse(liveness.allowsHeartbeat(envelope, 8_749, applied, null))
        liveness.markHealthy(identity.copy(transactionId = "other"), 17_000)
        assertFalse(liveness.allowsHeartbeat(envelope, 17_000, applied, null))
        liveness.disarm()
        assertTrue(liveness.allowsHeartbeat(envelope, 17_000, applied, null))
        assertFalse(
            liveness.allowsHeartbeat(
                envelope,
                17_001,
                applied.copy(transactionId = "other"),
                null,
            ),
        )
    }

    private fun truth(phase: TransactionPhase) = ManualFanMonitorLiveness.JournalTruth(
        generation = 7,
        transactionId = "transaction",
        phase = phase,
    )

    private fun activeIdentity() = ActiveSessionIdentity(
        generation = 7,
        transactionId = "transaction",
        requestId = "request",
        ownerUid = 1,
        targetPackage = "example.game",
        activationDeadlineMs = 10_000,
        leaseDeadlineMs = 20_000,
        targetEverForeground = false,
    )

    private fun manualEnvelope() = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper",
        tweakerCertificateSha256 = "a".repeat(64),
        hardwareFingerprint = "hardware",
        deviceProfile = CpuCeilingTransport.ODIN_3_PROFILE,
        bootIdentity = "boot",
        generation = 7,
        requestId = "request",
        transactionId = "transaction",
        taskNonce = "b".repeat(32),
        topologySha256 = "c".repeat(64),
        snapshotSha256 = "d".repeat(64),
        planSha256 = "e".repeat(64),
        hardDeadlineElapsedRealtimeMs = 30_000,
        restoreEntries = listOf(
            RestoreGuardianFanPutEntry(
                applyOrdinal = 0,
                expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                rendererAppliedIntermediates = listOf(TuningPlanner.FAN_MANUAL),
            ),
        ),
    )

    private fun mutation(
        logicalId: String,
        axis: TuningAxis,
        kind: LogicalMutationKind = LogicalMutationKind.SYSFS_WRITE,
        value: Int,
        path: String = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
    ) = LogicalMutation(
        logicalId = logicalId,
        axis = axis,
        canonicalPath = path,
        kind = kind,
        value = value,
        restoreMode = if (kind == LogicalMutationKind.SYSFS_WRITE) "644" else null,
        restoreUid = if (kind == LogicalMutationKind.SYSFS_WRITE) 0 else null,
        restoreGid = if (kind == LogicalMutationKind.SYSFS_WRITE) 0 else null,
        expectPresent = true,
    )

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
