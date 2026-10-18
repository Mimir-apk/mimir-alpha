package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class FpsLimiterPublicBundlesTest {
    @Test
    fun `applied bundle echoes owner identity and reports active debt`() {
        val record = record()
        val bundle = fpsApplyBundle(7, record.requestId, 9_000L, FpsLimiterApplyResult.Applied(record))

        assertTrue(bundle.getBoolean("success"))
        assertEquals("FPS_LIMIT_APPLIED", bundle.getString("code"))
        assertEquals(record.requestId, bundle.getString("callerRequestId"))
        assertEquals(record.packageIdentity.packageName, bundle.getString("packageName"))
        assertEquals(record.targetFps, bundle.getInt("targetFps"))
        assertEquals(record.generation, bundle.getLong("generation"))
        assertTrue(bundle.getBoolean("mutationMayBeActive"))
        assertTrue(bundle.getBoolean("restoreDebtRetained"))
    }

    @Test
    fun `rejected and restored bundles never claim an active mutation`() {
        val rejected = fpsApplyBundle(
            7,
            "mimir.fps.request",
            9_000L,
            FpsLimiterApplyResult.Rejected("FPS_LIMIT_UNSUPPORTED"),
        )
        val restored = fpsRestoreBundle(
            7,
            "mimir.fps.request",
            9_000L,
            FpsLimiterRestoreResult.Restored,
        )

        assertFalse(rejected.getBoolean("success"))
        assertFalse(rejected.getBoolean("mutationMayBeActive"))
        assertTrue(restored.getBoolean("success"))
        assertFalse(restored.getBoolean("mutationMayBeActive"))
        assertFalse(restored.getBoolean("restoreDebtRetained"))
    }

    @Test
    fun `query distinguishes stock active and unresolved recovery`() {
        val stock = fpsQueryBundle(7, "dev.eden.eden_emulator", FpsLimiterTerminalTruth(true, true, true), null)
        val active = fpsQueryBundle(7, "dev.eden.eden_emulator", FpsLimiterTerminalTruth(false, false, false), record())
        val recovery = fpsQueryBundle(7, "dev.eden.eden_emulator", FpsLimiterTerminalTruth(false, true, false), null)

        assertEquals("STOCK", stock.getString("state"))
        assertEquals("ACTIVE", active.getString("state"))
        assertEquals("RECOVERY", recovery.getString("state"))
        assertFalse(stock.getBoolean("mutationMayBeActive"))
        assertTrue(active.getBoolean("mutationMayBeActive"))
        assertTrue(recovery.getBoolean("mutationMayBeActive"))
    }

    private fun record() = FpsLimiterJournalRecord(
        generation = 1L,
        requestId = "mimir.fps.request",
        transactionId = "mimir.fps.tx",
        ownerUid = 1_000,
        packageIdentity = GameModeFpsPackageIdentity(
            packageName = "dev.eden.eden_emulator",
            userId = 0,
            uid = 10_123,
            versionCode = 7L,
            signingCertificateSha256 = "a".repeat(64),
        ),
        targetFps = 60,
        phase = FpsLimiterJournalPhase.APPLIED,
        createdAtElapsedRealtimeMs = 10L,
        hardDeadlineElapsedRealtimeMs = 9_000L,
        bootIdentity = "boot-1",
    )
}
