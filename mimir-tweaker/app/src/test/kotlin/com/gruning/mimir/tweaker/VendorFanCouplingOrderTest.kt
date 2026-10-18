package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore ORDER rules behind the 2026-10-13 fan-and-mode coupling fix, pure and host-independent:
 * [RestoreMutationOrder] over the new apply order (vendor mode, fan, compute) and over what tw06 wrote
 * (fan, vendor mode, compute); the Guardian envelope validator's accepted shapes; and the engine's final
 * fan intent. Compute entries appear only as [LogicalMutation]s here -- a Guardian sysfs entry
 * canonicalises its path through the host filesystem, which refuses `/sys/...` on a Windows host.
 * The device-shaped flows (settle, apply, both restores) are in `VendorFanCouplingTest`.
 */
class VendorFanCouplingOrderTest {
    private val fanId = HardwareSnapshot.FAN_LOGICAL_ID
    private val vendorId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID

    private fun mutation(logicalId: String, axis: TuningAxis, value: Int, path: String, kind: LogicalMutationKind) =
        LogicalMutation(
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

    private fun fan(value: Int) =
        mutation(fanId, TuningAxis.FAN, value, HardwareSnapshot.FAN_SETTINGS_PATH, LogicalMutationKind.SETTINGS_PUT)

    private val vendor =
        mutation(vendorId, TuningAxis.VENDOR_MODE, 1, HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH, LogicalMutationKind.SETTINGS_PUT)

    private val cpu0 = mutation(
        "cpu:0:scaling_max_freq", TuningAxis.CPU, 1_000, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        LogicalMutationKind.SYSFS_WRITE,
    )
    private val cpu6 = mutation(
        "cpu:6:scaling_max_freq", TuningAxis.CPU, 2_000, "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
        LogicalMutationKind.SYSFS_WRITE,
    )

    private fun indices(mutations: List<LogicalMutation>) = RestoreMutationOrder.indexed(mutations).map { it.index }

    private fun persistedIndices(mutations: List<LogicalMutation>) =
        RestoreMutationOrder.indexedPersisted(mutations.map(PersistedAppliedMutation::from)).map { it.index }

    // ---- RestoreMutationOrder over the new apply order: vendor mode, fan, compute --------------------------

    @Test
    fun `a Smart session restores compute, then the vendor mode, then the fan -- the mode moves in front of the fan`() {
        val applied = listOf(vendor, fan(TuningPlanner.FAN_SMART), cpu0, cpu6)
        assertEquals(listOf(3, 2, 0, 1), indices(applied))
        assertEquals(indices(applied), persistedIndices(applied))
    }

    @Test
    fun `a duty-owned session releases the fan first, compute, then the vendor mode last -- SPORT and MANUAL alike`() {
        listOf(TuningPlanner.FAN_MANUAL, TuningPlanner.FAN_SPORT).forEach { owned ->
            val applied = listOf(vendor, fan(owned), cpu0, cpu6)
            assertEquals("fan $owned", listOf(1, 3, 2, 0), indices(applied))
            assertEquals("fan $owned", indices(applied), persistedIndices(applied))
        }
    }

    @Test
    fun `without a vendor mode the historic orders are untouched`() {
        assertEquals(listOf(0, 2, 1), indices(listOf(fan(TuningPlanner.FAN_MANUAL), cpu0, cpu6)))
        assertEquals(listOf(0, 2, 1), indices(listOf(fan(TuningPlanner.FAN_SPORT), cpu0, cpu6)))
        assertEquals(listOf(2, 1, 0), indices(listOf(fan(TuningPlanner.FAN_SMART), cpu0, cpu6)))
    }

    // ---- ... and over what tw06 wrote: fan, vendor mode, compute -------------------------------------------

    @Test
    fun `a tw06 projection keeps the exact entry order it always had`() {
        val tw06Smart = listOf(fan(TuningPlanner.FAN_SMART), vendor, cpu0)
        assertEquals(listOf(2, 1, 0), indices(tw06Smart))
        val tw06Manual = listOf(fan(TuningPlanner.FAN_MANUAL), vendor, cpu0)
        assertEquals(listOf(0, 2, 1), indices(tw06Manual))
        assertEquals(indices(tw06Manual), persistedIndices(tw06Manual))
    }

    // ---- the renderer's own list: the second fan step --------------------------------------------------------

    private fun record(applied: List<LogicalMutation>, fanValue: Int): TransactionJournalRecord {
        val snapshotNodes = applied.map { mutation ->
            TopologyNodeState(
                logicalId = mutation.logicalId,
                axis = mutation.axis,
                canonicalPath = mutation.canonicalPath,
                originalValue = when (mutation.axis) {
                    TuningAxis.FAN -> TuningPlanner.FAN_SMART
                    TuningAxis.VENDOR_MODE -> 0
                    else -> 2_000
                },
                originalMode = mutation.restoreMode,
                originalUid = mutation.restoreUid,
                originalGid = mutation.restoreGid,
                present = true,
            )
        }
        val plan = TuningPlan(
            profileId = "balanced",
            packageName = "example.game",
            cpuMaxKhz = applied.filter { it.axis == TuningAxis.CPU }.associate {
                it.logicalId.removePrefix("cpu:").substringBefore(':').toInt() to requireNotNull(it.value)
            },
            gpuMaxPowerLevel = null,
            fanMode = fanValue,
            calibrated = true,
            vendorMode = if (applied.any { it.axis == TuningAxis.VENDOR_MODE }) 1 else null,
        )
        return TransactionJournalRecord(
            generation = 1L,
            requestId = "r",
            transactionId = "t",
            hardwareFingerprint = "fp",
            deviceProfile = "p",
            planHash = PlanHash.of(plan),
            profileId = plan.profileId,
            targetPackage = plan.packageName,
            phase = TransactionPhase.APPLIED,
            creationTime = 1L,
            requestDeadline = 2L,
            leaseDeadline = 3L,
            bootIdentity = "b",
            snapshot = HardwareSnapshot(
                schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
                hardwareFingerprint = "fp",
                bootIdentity = "b",
                nodes = snapshotNodes,
            ),
            expectedTopologySha256 = "x",
            snapshotSha256 = "y",
            lastCompletedMutationBoundary = applied.size,
            appliedPlan = PersistedTuningPlan.from(plan),
            applyOrderLogicalIds = applied.map { it.logicalId },
            appliedMutationProjection = applied.map(PersistedAppliedMutation::from),
        )
    }

    @Test
    fun `the renderer puts a duty-owned fan back once more after the vendor mode, new order and tw06 alike`() {
        assertEquals(
            listOf(fanId, cpu0.logicalId, vendorId, fanId),
            RestoreMutationOrder.logicalIds(record(listOf(vendor, fan(TuningPlanner.FAN_MANUAL), cpu0), TuningPlanner.FAN_MANUAL)),
        )
        assertEquals(
            listOf(fanId, cpu0.logicalId, vendorId, fanId),
            RestoreMutationOrder.logicalIds(record(listOf(fan(TuningPlanner.FAN_MANUAL), vendor, cpu0), TuningPlanner.FAN_MANUAL)),
        )
    }

    @Test
    fun `the renderer writes a Smart fan exactly once, last, and never repeats a fan nothing can disturb`() {
        assertEquals(
            listOf(cpu0.logicalId, vendorId, fanId),
            RestoreMutationOrder.logicalIds(record(listOf(vendor, fan(TuningPlanner.FAN_SMART), cpu0), TuningPlanner.FAN_SMART)),
        )
        // No vendor mode in the plan: the release is the only fan step, as it always was.
        assertEquals(
            listOf(fanId, cpu0.logicalId),
            RestoreMutationOrder.logicalIds(record(listOf(fan(TuningPlanner.FAN_MANUAL), cpu0), TuningPlanner.FAN_MANUAL)),
        )
    }

    // ---- the Guardian envelope validator --------------------------------------------------------------------

    private fun fanEntry(ordinal: Int, vararg intermediates: Int) = RestoreGuardianFanPutEntry(
        applyOrdinal = ordinal,
        expectedStock = RestoreGuardianStockState.PresentFanMode(TuningPlanner.FAN_SMART),
        rendererAppliedIntermediates = intermediates.toList(),
    )

    private fun vendorEntry(ordinal: Int) = RestoreGuardianVendorModePutEntry(applyOrdinal = ordinal, origin = 0, applied = 1)

    private fun assertRejected(entries: List<RestoreGuardianRestoreEntry>) {
        try {
            GuardianEnvelopeValidation.requireRestoreOrder(entries)
            throw AssertionError("expected IllegalArgumentException for ${entries.map { it.logicalId to it.applyOrdinal }}")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the validator accepts the vendor mode in front of the fan, the historic reverse, and a manual-first fan at any ordinal`() {
        // New apply order (vendor 0, fan 1), a Smart fan: the mode sits just before the fan.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(vendorEntry(0), fanEntry(1, TuningPlanner.FAN_SMART)))
        // New apply order, a duty-owned fan released first: its ordinal is 1 now, not 0.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fanEntry(1, TuningPlanner.FAN_MANUAL), vendorEntry(0)))
        // The fan-ownership envelope (Smart/Manual pair) under the new order, the same shape.
        GuardianEnvelopeValidation.requireRestoreOrder(
            listOf(fanEntry(1, TuningPlanner.FAN_SMART, TuningPlanner.FAN_MANUAL), vendorEntry(0)),
        )
        // A capture that found no fan_mode key restores by delete; the mode still moves in front of it.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(vendorEntry(0), RestoreGuardianFanDeleteEntry(applyOrdinal = 1)))
        // What tw06 wrote (fan 0, vendor 1): plain reverse, and manual-first at ordinal 0.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(vendorEntry(1), fanEntry(0, TuningPlanner.FAN_SMART)))
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fanEntry(0, TuningPlanner.FAN_MANUAL), vendorEntry(1)))
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fanEntry(0, TuningPlanner.FAN_SPORT), vendorEntry(1)))
    }

    @Test
    fun `the validator still refuses a Smart fan ahead of the mode that would undo it, a duty-owned fan behind it, and duplicate ordinals`() {
        assertRejected(listOf(fanEntry(0, TuningPlanner.FAN_SMART), vendorEntry(1)))
        assertRejected(listOf(fanEntry(0, TuningPlanner.FAN_SMART), vendorEntry(0)))
        assertRejected(listOf(fanEntry(1, TuningPlanner.FAN_MANUAL), vendorEntry(1)))
        // A duty-owned fan is released FIRST, never restored behind the mode: the vendor-before-fan
        // shape is pinned to a fan Mimir does not duty-drive.
        assertRejected(listOf(vendorEntry(0), fanEntry(1, TuningPlanner.FAN_MANUAL)))
        assertRejected(listOf(vendorEntry(0), fanEntry(1, TuningPlanner.FAN_SPORT)))
    }

    // ---- TR-08 as the Guardian's gate: strict, never lenient ---------------------------------------------

    @Test
    fun `a captured loop-stopper counts as restored only at Smart -- the literal value still live is a write, never stock`() {
        fun decide(captured: Int, observed: Int?) = GuardianTypedRestorePolicy.decide(
            RestoreGuardianFanPutEntry(
                applyOrdinal = 1,
                expectedStock = RestoreGuardianStockState.PresentFanMode(captured),
                rendererAppliedIntermediates = listOf(TuningPlanner.FAN_MANUAL),
            ),
            GuardianObservedState.FanMode(observed),
        )
        listOf(TuningPlanner.FAN_SILENT, TuningPlanner.FAN_SPORT).forEach { stopper ->
            assertEquals("captured $stopper, Smart live", GuardianRestoreDecision.ALREADY_STOCK, decide(stopper, TuningPlanner.FAN_SMART))
            assertEquals("captured $stopper still live is NOT stock", GuardianRestoreDecision.WRITE_REQUIRED, decide(stopper, stopper))
            assertEquals("captured $stopper, nothing readable", GuardianRestoreDecision.WRITE_REQUIRED, decide(stopper, null))
        }
        // Every other captured value is still demanded literally.
        listOf(0, 2, 3, TuningPlanner.FAN_SMART, 7).forEach { honoured ->
            assertEquals("captured $honoured", GuardianRestoreDecision.ALREADY_STOCK, decide(honoured, honoured))
            assertEquals("captured $honoured, Smart is not it", if (honoured == TuningPlanner.FAN_SMART) GuardianRestoreDecision.ALREADY_STOCK else GuardianRestoreDecision.WRITE_REQUIRED, decide(honoured, TuningPlanner.FAN_SMART))
        }
        assertEquals(TuningPlanner.FAN_SMART, FanRestoreTarget.of(TuningPlanner.FAN_SILENT))
        assertEquals(TuningPlanner.FAN_SMART, FanRestoreTarget.of(TuningPlanner.FAN_SPORT))
        assertEquals(3, FanRestoreTarget.of(3))
    }

    // ---- the engine: the final fan intent ------------------------------------------------------------------

    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public,
    )

    private fun sha(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())

    private fun envelope(entries: List<RestoreGuardianRestoreEntry>) = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1",
        tweakerCertificateSha256 = sha("certificate"),
        hardwareFingerprint = "hardware.1",
        deviceProfile = "profile.1",
        bootIdentity = "boot",
        generation = 7,
        requestId = "request.1",
        transactionId = "transaction.1",
        taskNonce = "a".repeat(32),
        topologySha256 = sha("topology"),
        snapshotSha256 = sha("snapshot"),
        planSha256 = sha("plan"),
        hardDeadlineElapsedRealtimeMs = 10_000,
        restoreEntries = entries,
    )

    private fun restoringEngine(value: RestoreGuardianEnvelope): RestoreGuardianRunnerEngine =
        RestoreGuardianRunnerEngine(
            GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
            GuardianReceiptOutputPort { GuardianReceiptOutputDisposition.CONFIRMED },
            sessionKey,
        ).also {
            it.stage(GuardianEnvelopeVerificationOutcome.Verified(value), 1, "boot")
            it.admitReady(2)
            it.beginWatching(3)
            it.ownerCancelled()
        }

    private fun plan(value: RestoreGuardianEnvelope, fan: Int?, vendor: Int?): List<GuardianRestoreIntent> {
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(value)
        val evidence = GuardianRestoreFenceEvidence.SameBoot(FenceDiskState(true, identity, identity, true))
        return restoringEngine(value).planRestore(
            "boot",
            evidence,
            mapOf(fanId to GuardianObservedState.FanMode(fan), vendorId to GuardianObservedState.VendorMode(vendor)),
        ).restoreIntents
    }

    @Test
    fun `a vendor write is always followed by the fan, whatever the fan read when the Guardian planned`() {
        val tw06Manual = envelope(listOf(fanEntry(0, TuningPlanner.FAN_MANUAL), vendorEntry(1)))
        val newManual = envelope(listOf(fanEntry(1, TuningPlanner.FAN_MANUAL), vendorEntry(0)))
        listOf(tw06Manual, newManual).forEach { value ->
            // Mid-session death: the fan is still Mimir's 6 and the mode still Mimir's 1.
            val crashed = plan(value, fan = TuningPlanner.FAN_MANUAL, vendor = 1)
            assertEquals(listOf(fanId, vendorId, fanId), crashed.map { it.entry.logicalId })
            assertEquals(listOf(false, false, true), crashed.map { it.afterVendorMode })
            // The daemon's release already ran; only the mode is left -- the fan is still re-put after it.
            val released = plan(value, fan = TuningPlanner.FAN_SMART, vendor = 1)
            assertEquals(listOf(vendorId, fanId), released.map { it.entry.logicalId })
            assertTrue(released.last().afterVendorMode)
            // Nothing to write for the mode: nothing can disturb the fan, so nothing is planned for it.
            assertTrue(plan(value, fan = TuningPlanner.FAN_SMART, vendor = 0).isEmpty())
            assertTrue("the player's mode is not Mimir's to touch", plan(value, fan = TuningPlanner.FAN_SMART, vendor = 2).isEmpty())
        }
    }

    @Test
    fun `a Smart envelope's own fan entry behind the mode is the final write`() {
        val value = envelope(listOf(vendorEntry(0), fanEntry(1, TuningPlanner.FAN_SMART)))
        val moved = plan(value, fan = TuningPlanner.FAN_SPORT, vendor = 1)
        assertEquals(listOf(vendorId, fanId), moved.map { it.entry.logicalId })
        assertEquals(listOf(false, true), moved.map { it.afterVendorMode })
        val stock = plan(value, fan = TuningPlanner.FAN_SMART, vendor = 1)
        assertEquals(listOf(vendorId, fanId), stock.map { it.entry.logicalId })
        assertTrue(stock.last().afterVendorMode)
    }
}
