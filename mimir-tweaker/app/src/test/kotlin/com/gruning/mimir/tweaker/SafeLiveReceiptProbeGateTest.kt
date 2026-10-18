package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLiveReceiptProbeGateTest {
    @Test
    fun `only an unlocked signature-authorized SAFE empty-journal service can observe the receipt channel`() {
        assertEquals(
            SafeLiveReceiptProbeGate.Open,
            gate(),
        )
        assertEquals(SafeLiveReceiptProbeGate.PostureRejected, gate(posture = TweakerPostureTruth.LAB))
        assertEquals(SafeLiveReceiptProbeGate.WriteBuildRejected, gate(hardwareWritesCompiled = true))
        assertEquals(SafeLiveReceiptProbeGate.UserLocked, gate(userUnlocked = false))
        assertEquals(SafeLiveReceiptProbeGate.PermissionRejected, gate(controlPermissionGranted = false))
        assertEquals(SafeLiveReceiptProbeGate.JournalNotEmpty, gate(journalEmpty = false))
        assertEquals(SafeLiveReceiptProbeGate.PServerUnavailable, gate(pServerInitialized = false))
    }

    @Test
    fun `READY timeout is visible to the bounded SAFE port while terminal timeout remains controller-private`() {
        assertEquals(5_000L, RestoreGuardianController.READY_TIMEOUT_MS)
    }

    @Test
    fun `terminal marker preserves the exact READY load state`() {
        assertEquals("GuardianReadyLoad.Ambiguous", guardianReadyLoadMarker(GuardianReadyLoad.Ambiguous))
        assertEquals("GuardianReadyLoad.Unavailable", guardianReadyLoadMarker(GuardianReadyLoad.Unavailable))
        assertEquals("GuardianReadyLoad.TimedOut", guardianReadyLoadMarker(GuardianReadyLoad.TimedOut))
    }

    private fun gate(
        posture: String = TweakerPostureTruth.SAFE,
        hardwareWritesCompiled: Boolean = false,
        userUnlocked: Boolean = true,
        controlPermissionGranted: Boolean = true,
        journalEmpty: Boolean = true,
        pServerInitialized: Boolean = true,
    ): SafeLiveReceiptProbeGate = SafeLiveReceiptProbeGate.evaluate(
        posture = posture,
        hardwareWritesCompiled = hardwareWritesCompiled,
        userUnlocked = userUnlocked,
        controlPermissionGranted = controlPermissionGranted,
        journalEmpty = journalEmpty,
        pServerInitialized = pServerInitialized,
    )
}
