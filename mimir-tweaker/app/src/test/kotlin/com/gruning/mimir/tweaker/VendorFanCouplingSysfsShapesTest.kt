package com.gruning.mimir.tweaker

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore shapes that carry COMPUTE entries beside the fan and the vendor mode -- the ones a real
 * apply renders -- through the Guardian envelope validator and the engine's plan (2026-10-13 fix round).
 *
 * **Linux CI only on this project's Windows host.** A [RestoreGuardianSysfsEntry] canonicalises its
 * path through the host filesystem at construction, which refuses `/sys/...` on Windows, so every test
 * here fails there at the fixture, before any rule is exercised (the same environment failure the
 * baseline carries for `ManualFanSafetyTest` and `RestoreGuardianEnvelopeTest`). The two-entry shapes
 * run on both hosts in `VendorFanCouplingOrderTest`.
 */
class VendorFanCouplingSysfsShapesTest {
    private val fanId = HardwareSnapshot.FAN_LOGICAL_ID
    private val vendorId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID

    private fun cpu(ordinal: Int, id: Int = 0) = RestoreGuardianSysfsEntry(
        applyOrdinal = ordinal,
        logicalId = "cpu:$id:scaling_max_freq",
        canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        expectedStock = RestoreGuardianStockState.PresentSysfs(2_000, "644", 0, 0),
        rendererAppliedIntermediates = listOf(1_000),
    )

    private fun gpu(ordinal: Int) = RestoreGuardianSysfsEntry(
        applyOrdinal = ordinal,
        logicalId = HardwareSnapshot.GPU_MAX_LOGICAL_ID,
        canonicalSysfsPath = HardwareProbe.GPU_MAX_LEVEL,
        expectedStock = RestoreGuardianStockState.PresentSysfs(0, "644", 0, 0),
        rendererAppliedIntermediates = listOf(2),
    )

    private fun fan(ordinal: Int, vararg intermediates: Int) = RestoreGuardianFanPutEntry(
        applyOrdinal = ordinal,
        expectedStock = RestoreGuardianStockState.PresentFanMode(TuningPlanner.FAN_SMART),
        rendererAppliedIntermediates = intermediates.toList(),
    )

    private fun vendor(ordinal: Int) = RestoreGuardianVendorModePutEntry(applyOrdinal = ordinal, origin = 0, applied = 1)

    private fun assertRejected(entries: List<RestoreGuardianRestoreEntry>) {
        try {
            GuardianEnvelopeValidation.requireRestoreOrder(entries)
            throw AssertionError("expected IllegalArgumentException for ${entries.map { it.logicalId to it.applyOrdinal }}")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- the validator over what the renderers actually produce --------------------------------------

    @Test
    fun `the validator accepts the three producer shapes with compute in them`() {
        // The new apply order, a Smart fan: compute reversed, the mode just before the fan.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(cpu(3, 6), cpu(2), vendor(0), fan(1, TuningPlanner.FAN_SMART)))
        // The new apply order, a duty-owned fan released first (also the fan-ownership sort's shape).
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fan(1, TuningPlanner.FAN_MANUAL), cpu(3, 6), cpu(2), vendor(0)))
        GuardianEnvelopeValidation.requireRestoreOrder(
            listOf(fan(1, TuningPlanner.FAN_SMART, TuningPlanner.FAN_MANUAL), cpu(3, 6), cpu(2), vendor(0)),
        )
        // No vendor mode in the plan: the fan-first apply, release first.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fan(0, TuningPlanner.FAN_SPORT), cpu(2), cpu(1, 6)))
        // What tw06 wrote (fan 0, vendor 1): plain reverse, and release-first.
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(cpu(2), vendor(1), fan(0, TuningPlanner.FAN_SMART)))
        GuardianEnvelopeValidation.requireRestoreOrder(listOf(fan(0, TuningPlanner.FAN_MANUAL), cpu(2), vendor(1)))
    }

    @Test
    fun `the manual-first bound holds -- the fan is first only at ordinal 0, or at 1 with the mode at 0`() {
        // The code review's two counter-examples: a fan applied in the middle of the order.
        assertRejected(listOf(fan(2, TuningPlanner.FAN_MANUAL), cpu(3)))
        assertRejected(listOf(fan(2, TuningPlanner.FAN_MANUAL), cpu(3), gpu(1), vendor(0)))
        assertRejected(listOf(fan(2, TuningPlanner.FAN_SPORT), cpu(3)))
        // Ordinal 1 without the mode at 0 behind it is not a shape any renderer produces.
        assertRejected(listOf(fan(1, TuningPlanner.FAN_MANUAL), cpu(2)))
        assertRejected(listOf(fan(1, TuningPlanner.FAN_MANUAL), cpu(2), vendor(3)))
        // The rest must still be strictly reverse on its own.
        assertRejected(listOf(fan(1, TuningPlanner.FAN_MANUAL), cpu(2), cpu(3, 6), vendor(0)))
    }

    @Test
    fun `the vendor-before-fan bound holds -- mode at 0, fan at 1, never a duty-owned fan, rest strictly reverse`() {
        assertRejected(listOf(cpu(2), vendor(0), fan(1, TuningPlanner.FAN_MANUAL)))
        assertRejected(listOf(cpu(3), vendor(1), fan(2, TuningPlanner.FAN_SMART)))
        assertRejected(listOf(cpu(2), cpu(3, 6), vendor(0), fan(1, TuningPlanner.FAN_SMART)))
        assertRejected(listOf(vendor(0), cpu(2), fan(1, TuningPlanner.FAN_SMART)))
    }

    // ---- the engine over an envelope with compute ---------------------------------------------------------

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

    private fun plan(
        value: RestoreGuardianEnvelope,
        fanMode: Int?,
        cpuValue: Int,
        vendorMode: Int?,
    ): List<GuardianRestoreIntent> {
        val engine = RestoreGuardianRunnerEngine(
            GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
            GuardianReceiptOutputPort { GuardianReceiptOutputDisposition.CONFIRMED },
            sessionKey,
        )
        engine.stage(GuardianEnvelopeVerificationOutcome.Verified(value), 1, "boot")
        engine.admitReady(2)
        engine.beginWatching(3)
        engine.ownerCancelled()
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(value)
        val evidence = GuardianRestoreFenceEvidence.SameBoot(FenceDiskState(true, identity, identity, true))
        return engine.planRestore(
            "boot",
            evidence,
            mapOf(
                fanId to GuardianObservedState.FanMode(fanMode),
                "cpu:0:scaling_max_freq" to GuardianObservedState.Sysfs(cpuValue, "644", 0, 0),
                vendorId to GuardianObservedState.VendorMode(vendorMode),
            ),
        ).restoreIntents
    }

    @Test
    fun `the production fan-ownership envelope -- Smart and Manual targets with compute -- restores release, compute, mode, fan`() {
        val value = envelope(listOf(fan(1, TuningPlanner.FAN_SMART, TuningPlanner.FAN_MANUAL), cpu(2), vendor(0)))

        val crashed = plan(value, fanMode = TuningPlanner.FAN_MANUAL, cpuValue = 1_000, vendorMode = 1)

        assertEquals(listOf(fanId, "cpu:0:scaling_max_freq", vendorId, fanId), crashed.map { it.entry.logicalId })
        assertEquals(listOf(false, false, false, true), crashed.map { it.afterVendorMode })
    }

    @Test
    fun `a fan already at stock with compute to repair keeps its release step in front and still gets the final step after the mode`() {
        val value = envelope(listOf(fan(1, TuningPlanner.FAN_MANUAL), cpu(2), vendor(0)))

        val repair = plan(value, fanMode = TuningPlanner.FAN_SMART, cpuValue = 1_000, vendorMode = 1)

        assertEquals(listOf(fanId, "cpu:0:scaling_max_freq", vendorId, fanId), repair.map { it.entry.logicalId })
        assertEquals(listOf(false, false, false, true), repair.map { it.afterVendorMode })
        // The same with nothing to repair and the mode already the player's: nothing is planned at all.
        assertTrue(plan(value, fanMode = TuningPlanner.FAN_SMART, cpuValue = 2_000, vendorMode = 2).isEmpty())
    }
}
