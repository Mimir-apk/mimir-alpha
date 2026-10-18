package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Nova's `availability=UNAVAILABLE` regression, pinned at the seam that actually caused it.
 *
 * The defect was an ABSENCE: a durable transaction-journal record whose started service lifetime
 * had been destroyed (`am force-stop`) reached `onCreate` on every later bind and `onStartCommand`
 * never again, so no code path owned its recovery -- for 47 hours of uptime on the
 * device, with the whole tuning rail reading UNAVAILABLE the entire time. An absence needs its own
 * gate, so these pin both halves: the policy, and the fact that `onCreate` still calls it.
 */
class CreateTimeRecoveryDispatchPolicyTest {

    @Test
    fun `an idle creation carrying the Nova's stranded record dispatches restore`() {
        // The exact shape measured on the Nova 2026-08-30: a terminal-phase system-scope record
        // (empty targetPackage) for `quiet`, with no live session anywhere in the process.
        val stranded = journalRecord().copy(profileId = "quiet", targetPackage = "")

        val plan = CreateTimeRecoveryDispatchPolicy.plan(
            startedLifetime = StartedServiceLifetime().snapshot(),
            hardwareWritesCompiled = true,
            load = JournalLoadResult.Loaded(stranded),
        )

        assertNotNull("a stranded durable record must not reach a creation with no owner", plan)
        assertEquals(MimirTweakerService.ACTION_RECOVERY_RESTORE, plan?.serviceAction)
    }

    @Test
    fun `a RESTORING record dispatches exactly as an APPLIED one does`() {
        // The public session state the client sees is RESTORING for BOTH -- an APPLIED record with
        // no live `active` session resolves to RESTORING by design. Neither may be the phase that
        // silently has no owner, so the dispatch must not discriminate between them.
        for (phase in listOf(TransactionPhase.APPLIED, TransactionPhase.RESTORING)) {
            val plan = CreateTimeRecoveryDispatchPolicy.plan(
                startedLifetime = StartedServiceLifetime().snapshot(),
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Loaded(journalRecord().copy(phase = phase)),
            )
            assertEquals(
                "phase $phase must dispatch recovery",
                MimirTweakerService.ACTION_RECOVERY_RESTORE,
                plan?.serviceAction,
            )
        }
    }

    @Test
    fun `a clean device dispatches nothing, so this can never loop`() {
        assertNull(
            CreateTimeRecoveryDispatchPolicy.plan(
                startedLifetime = StartedServiceLifetime().snapshot(),
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
            ),
        )
    }

    @Test
    fun `a started lifetime that already owns the record is never dispatched over`() {
        // Load-bearing, not padding: a second recovery on top of a live apply would restore the
        // profile out from under it -- the same hazard startCalibrationObservation refuses.
        val load = JournalLoadResult.Loaded(journalRecord())
        for (occupy in listOf<(StartedServiceLifetime) -> Unit>(
            { it.beginPending(startId = 1, nowElapsedRealtimeMs = 0L, graceMs = 10_000L) },
            {
                it.beginPending(startId = 1, nowElapsedRealtimeMs = 0L, graceMs = 10_000L)
                it.admitApply(nowElapsedRealtimeMs = 1L)
            },
            {
                it.beginPending(startId = 1, nowElapsedRealtimeMs = 0L, graceMs = 10_000L)
                it.admitApply(nowElapsedRealtimeMs = 1L)
                it.markActive(expectedStartId = 1)
            },
            { it.markRecovery(startId = 1) },
        )) {
            val lifetime = StartedServiceLifetime()
            occupy(lifetime)
            assertTrue("fixture must not be IDLE", !lifetime.snapshot().isIdle)
            assertNull(
                "phase ${lifetime.snapshot().phase} already owns the record",
                CreateTimeRecoveryDispatchPolicy.plan(
                    startedLifetime = lifetime.snapshot(),
                    hardwareWritesCompiled = true,
                    load = load,
                ),
            )
        }
    }

    @Test
    fun `a corrupt journal is dispatched too, never left unowned`() {
        assertEquals(
            MimirTweakerService.ACTION_RECOVERY_RESTORE,
            CreateTimeRecoveryDispatchPolicy.plan(
                startedLifetime = StartedServiceLifetime().snapshot(),
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Corrupt("nova"),
            )?.serviceAction,
        )
    }

    @Test
    fun `an FPS-only journal still dispatches its own recovery action`() {
        assertEquals(
            MimirTweakerService.ACTION_FPS_RECOVERY_RESTORE,
            CreateTimeRecoveryDispatchPolicy.plan(
                startedLifetime = StartedServiceLifetime().snapshot(),
                hardwareWritesCompiled = true,
                load = JournalLoadResult.Empty,
                fpsLoad = FpsLimiterJournalLoad.Loaded(fpsRecord()),
            )?.serviceAction,
        )
    }

    /**
     * The policy alone is not the fix -- an unwired policy is exactly the shape of the original
     * defect (a complete mechanism with no caller). Pin the call site, comment-stripped so the
     * KDoc that EXPLAINS the defect cannot satisfy the assertion.
     */
    @Test
    fun `onCreate still hands the obligation on`() {
        val src = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/MimirTweakerService.kt"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("MimirTweakerService.kt not found")
        val code = src.readText().lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        // A bare CALL line, not merely the name: `code.contains("dispatch...()")` is satisfied by
        // the `private fun` declaration alone, so deleting the call from onCreate left this test
        // green. Caught by replaying it against the removed call site (R-NOVA-1); an unwired
        // mechanism is the exact reachability defect being guarded, so the pin must see it.
        assertTrue(
            "onCreate must CALL dispatchCreateTimeRecoveryObligation(), not merely declare it",
            code.lineSequence().any { it.trim() == "dispatchCreateTimeRecoveryObligation()" },
        )
        assertTrue(
            "the dispatch must go through CreateTimeRecoveryDispatchPolicy",
            code.contains("CreateTimeRecoveryDispatchPolicy.plan("),
        )
        assertTrue(
            "the obligation is handed to onStartCommand, never recovered on the create path",
            code.contains("startForegroundService(") &&
                code.contains("Intent(this, MimirTweakerService::class.java).setAction(plan.serviceAction)"),
        )
    }

    private fun fpsRecord() = FpsLimiterJournalRecord(
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

    private fun journalRecord() = TransactionJournalRecord(
        schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V1,
        generation = 662,
        requestId = "mimir.system.1ba5f00e-2899-4cd2-bebc-f2380b24c6fd.5",
        transactionId = "c6eff393-543d-4daf-94f5-c7407ddf8f83",
        hardwareFingerprint = "fp",
        deviceProfile = "retroid-qcs8550-pserver",
        planHash = "ph",
        profileId = "quiet",
        targetPackage = "",
        phase = TransactionPhase.APPLIED,
        creationTime = 1,
        requestDeadline = 122_337_925,
        leaseDeadline = 165_477_926,
        bootIdentity = "boot",
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fp",
            bootIdentity = "boot",
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "e",
        snapshotSha256 = "s",
        lastCompletedMutationBoundary = 1,
    )
}
