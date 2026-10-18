package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPrimaryResumePolicyTest {
    @Test
    fun `stop resume ledger ignores first resume and consumes each real stop once`() {
        val ledger = PrimaryStopResumeLedger()
        val ticket = PrimaryStopResumeTicket(primaryAppOperationId = "primary:test")

        assertEquals(PrimaryResumeObservation(false, null), ledger.observeResume())
        ledger.noteStopped(changingConfigurations = false, ticket = ticket)
        assertEquals(PrimaryResumeObservation(true, ticket), ledger.observeResume())
        assertEquals(PrimaryResumeObservation(false, ticket), ledger.observeResume())
        ledger.noteStopped(changingConfigurations = true, ticket = PrimaryStopResumeTicket())
        assertEquals(PrimaryResumeObservation(false, ticket), ledger.observeResume())
        ledger.acknowledgePrimaryApp("primary:test")
        assertEquals(PrimaryResumeObservation(false, null), ledger.observeResume())

        ledger.noteStopped(changingConfigurations = true, ticket = ticket)
        assertEquals(PrimaryResumeObservation(false, null), ledger.observeResume())
    }

    /**
     * The cold-launch rollback, pinned. Reproduced on Odin 2026-08-10: with Efficiency applied,
     * force-stopping Mimir left the profile fully applied — the clocks held at 1785600/2246400 with no
     * pid at all — and exact Stock (3532800/4320000) arrived only ~4s after the replacement process
     * started. Guardian stayed alive the whole time and no `SUPERVISOR restore reason=` was ever
     * logged, so the teardown was a settlement this process requested, not a safety restore.
     *
     * A fresh process observes `returnTicket = null` on its very first resume, so it must NOT settle.
     * Before the fix, `onResume` called `settleAfterReturn()` unconditionally and threw away a
     * system-wide profile the player had deliberately selected.
     */
    @Test
    fun `a freshly started process never settles a profile it did not launch`() {
        val ledger = PrimaryStopResumeLedger()

        // First resume of a brand-new process: nothing was ever stopped, so there is nothing to settle.
        val coldStart = ledger.observeResume()
        assertEquals(PrimaryResumeObservation(false, null), coldStart)
        assertFalse(shouldSettleTweakerAfterPrimaryResume(coldStart.returnTicket))
    }

    @Test
    fun `returning from a launched session still settles back to Stock`() {
        val ledger = PrimaryStopResumeLedger()
        val gameReturn = PrimaryStopResumeTicket(primaryAppOperationId = "primary:game")

        ledger.observeResume()
        ledger.noteStopped(changingConfigurations = false, ticket = gameReturn)
        val afterGame = ledger.observeResume()

        assertEquals(gameReturn, afterGame.returnTicket)
        assertTrue(shouldSettleTweakerAfterPrimaryResume(afterGame.returnTicket))
    }

    @Test
    fun `a parked game return is withheld while a primary app ticket remains`() {
        val parkedOperation = MimirGameSessionOperation("game:parked")
        var parked = true
        val ledger = PrimaryStopResumeLedger { parked && it == parkedOperation }
        val ticket = PrimaryStopResumeTicket(
            gameOperation = parkedOperation,
            primaryAppOperationId = "primary:app",
        )

        ledger.noteStopped(changingConfigurations = false, ticket = ticket)
        val observation = ledger.observeResume()

        assertEquals(
            ticket.copy(gameOperation = null, gameWasPreparing = false),
            observation.returnTicket,
        )
        assertEquals(null, observation.withheldGameOperation)
        // The pending game operation remains in the ledger so a real Main stop after restoration can settle it.
        assertTrue(ledger.gameReturnWithheld())

        parked = false
        ledger.noteStopped(changingConfigurations = false, ticket = ticket)
        assertEquals(ticket, ledger.observeResume().returnTicket)
        assertFalse(ledger.gameReturnWithheld())
    }

    @Test
    fun `a stop carrying no return operation does not settle`() {
        val ledger = PrimaryStopResumeLedger()

        ledger.observeResume()
        // A bare stop/resume -- backgrounding, Recents, a surface switch -- carries no operation, so
        // the ledger drops the ticket and the profile must survive.
        ledger.noteStopped(changingConfigurations = false, ticket = PrimaryStopResumeTicket())
        val bareResume = ledger.observeResume()

        assertEquals(null, bareResume.returnTicket)
        assertFalse(shouldSettleTweakerAfterPrimaryResume(bareResume.returnTicket))
    }

    @Test
    fun `a configuration change never settles`() {
        val ledger = PrimaryStopResumeLedger()
        val ticket = PrimaryStopResumeTicket(primaryAppOperationId = "primary:test")

        ledger.observeResume()
        ledger.noteStopped(changingConfigurations = true, ticket = ticket)
        val afterRotation = ledger.observeResume()

        assertFalse(shouldSettleTweakerAfterPrimaryResume(afterRotation.returnTicket))
    }

    /**
     * The predicate above is only worth anything if `onResume` actually asks it. That call sits behind
     * the Activity lifecycle, so a unit test cannot reach it — but it can read it. Without this, the
     * unconditional `settleAfterReturn()` could come back and every other test here would stay green.
     */
    @Test
    fun `onResume routes its Tweaker settlement through the gate`() {
        val source = File(requireNotNull(System.getProperty("mimir.mainActivitySource"))).readText()
        val settleCalls = Regex("""TweakerBridge\.settleAfterReturn\(\)""").findAll(source).count()
        val resumeGate = source.contains("shouldSettleTweakerAfterPrimaryResume(stopTicket)")

        assertTrue("onResume must decide settlement through the shared gate", resumeGate)
        // Two sites remain by design: the gated onResume branch, and the game-dispatch watchdog
        // cleanup, which settles a session it genuinely owns. A third would mean an ungated caller.
        assertEquals("unexpected settleAfterReturn call site in MainActivity", 2, settleCalls)
    }

    @Test
    fun `cold process recovery waits for explicit user return`() {
        assertFalse(shouldReclaimCompanionAfterPrimaryResume(snapshot = snapshot(recoveryNeeded())))
    }

    /**
     * **THE REGRESSION for policy L-3, 2026-09-01.** This ordinary-recovery predicate used to require
     * `resumedAfterStop`, and that flag is false exactly when recovery is needed.
     *
     * Measured on the Thor (`b687f3b5`): both displays hold a RESUMED activity at the same time —
     * `Display #4` and `Display #0` — which is the ordinary posture of a dual-screen handheld. The
     * primary is therefore never stopped, `resumedAfterStop` never becomes true, and ordinary runtime
     * `RecoveryNeeded` had no automatic way out at all. It was an absorbing state in the normal case, not an edge case,
     * and the device also burned a 100 ms UsageStats poll sitting in it.
     */
    @Test
    fun `ordinary recovery still reclaims when the primary was never stopped`() {
        assertTrue(
            "a resume with no preceding stop is the ordinary dual-screen posture, and must recover",
            shouldReclaimCompanionAfterPrimaryResume(snapshot = snapshot(ordinaryRecoveryNeeded())),
        )
    }

    @Test
    fun `cold return row needs the current process display observation`() {
        val recovered = recoveryNeeded()
        val persisted = snapshot(recovered)
        assertFalse(companionSecondaryReturnAvailable(persisted))
        assertTrue(
            companionSecondaryReturnAvailable(
                persisted.copy(availableDisplayId = recovered.displayId),
            ),
        )
    }

    private fun recoveryNeeded() = SecondaryDisplayDisposition.RecoveryNeeded(
        operationId = OperationId.create(),
        displayId = 4,
        fallback = PrimaryReturnOrigin("LIBRARY"),
        failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
    )

    private fun ordinaryRecoveryNeeded() = recoveryNeeded().copy(
        failure = FailureReason(FailureCode.RENDER_FAILED, "renderer recovery"),
    )

    @Test
    fun `primary stop resume never steals a live yielded secondary host`() {
        val yielded = SecondaryDisplayDisposition.Yielded(
            lease = HostLease(
                operationId = OperationId.create(),
                packageName = "org.example.game",
                displayId = 4,
                launchMode = "secondary_app",
                primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
            ),
            evidence = YieldEvidence.HOST_COVERED,
        )

        assertFalse(shouldReclaimCompanionAfterPrimaryResume(snapshot(yielded)))
    }

    @Test
    fun `resume never replaces active or in flight companion work`() {
        val owner = RendererOwnerId.create()
        val render = RenderAttempt(OperationId.create(), OperationId.create(), owner, displayId = 4)
        val mirror = snapshot(
            disposition = SecondaryDisplayDisposition.MirrorPresented(render),
            mirrorActive = true,
            rendererOwnerId = owner,
        )
        val presenting = snapshot(SecondaryDisplayDisposition.Presenting(render, PresentCause.RENDERER_REBOUND))
        val launch = snapshot(
            SecondaryDisplayDisposition.LaunchPending(
                lease = HostLease(
                    operationId = OperationId.create(),
                    packageName = "org.example.game",
                    displayId = 4,
                    launchMode = "secondary_app",
                    primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
                ),
                phase = LaunchPhase.TARGET_DISPATCHED,
            ),
        )

        assertFalse(shouldReclaimCompanionAfterPrimaryResume(mirror))
        assertFalse(shouldReclaimCompanionAfterPrimaryResume(presenting))
        assertFalse(shouldReclaimCompanionAfterPrimaryResume(launch))
    }

    private fun snapshot(
        disposition: SecondaryDisplayDisposition,
        mirrorActive: Boolean = false,
        rendererOwnerId: RendererOwnerId? = null,
        availableDisplayId: Int? = null,
    ) = CompanionRuntimeSnapshot(
        stateVersion = 1L,
        displayId = 4,
        disposition = disposition,
        rendererOwnerId = rendererOwnerId,
        mirrorActive = mirrorActive,
        launchLockOperationId = null,
        launchSettlement = null,
        availableDisplayId = availableDisplayId,
    )
}
