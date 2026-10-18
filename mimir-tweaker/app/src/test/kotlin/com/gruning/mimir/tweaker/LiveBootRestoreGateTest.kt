package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PS-04D4B: restore is fail-closed before contextual validation or a PServer command when
 * the durable journal/snapshot boot identity cannot be tied to this live kernel boot.
 */
class LiveBootRestoreGateTest {
    @Test
    fun `missing or blank boot identities fail closed before contextual validation`() {
        listOf(
            Triple("", "boot-a", "boot-a") to "journal bootIdentity missing",
            Triple("boot-a", "", "boot-a") to "snapshot bootIdentity missing",
            Triple("boot-a", "boot-a", null) to "live bootIdentity missing",
            Triple("boot-a", "boot-a", " ") to "live bootIdentity missing",
        ).forEach { (case, expectedFailure) ->
            val probe = commandProbe(liveBootIdentity = case.third)
            val verification = probe.port.validateRestore(record(case.first, case.second))

            assertFalse(verification.ok)
            assertEquals(listOf(expectedFailure), verification.failures)
            assertEquals(0, probe.capabilityReads)
            assertEquals(0, probe.commandTransacts)
        }
    }

    @Test
    fun `journal snapshot and live boot mismatches fail closed before any command`() {
        val snapshotMismatch = commandProbe(liveBootIdentity = "boot-a")
        val snapshotVerification = snapshotMismatch.port.validateRestore(record("boot-a", "boot-b"))
        assertFalse(snapshotVerification.ok)
        assertEquals(listOf("journal/snapshot bootIdentity mismatch"), snapshotVerification.failures)
        assertEquals(0, snapshotMismatch.capabilityReads)
        assertEquals(0, snapshotMismatch.commandTransacts)

        val liveMismatch = commandProbe(liveBootIdentity = "boot-b")
        val liveVerification = liveMismatch.port.validateRestore(record("boot-a", "boot-a"))
        assertFalse(liveVerification.ok)
        assertEquals(listOf("live bootIdentity mismatch"), liveVerification.failures)
        assertEquals(0, liveMismatch.capabilityReads)
        assertEquals(0, liveMismatch.commandTransacts)
    }

    private fun commandProbe(liveBootIdentity: String?): CommandProbe {
        var capabilityReads = 0
        var commandTransacts = 0
        val port = PServerHardwareCommandPort(
            pServer = PServerClient(
                transport = PServerBinderTransport { _, _ ->
                    commandTransacts += 1
                    PServerTransactResult(accepted = true, output = "unexpected")
                },
            ),
            reader = SystemReader(),
            capabilities = {
                capabilityReads += 1
                error("restore context must not be reached on a boot-identity failure")
            },
            liveBootIdentity = { liveBootIdentity },
        )
        return CommandProbe(
            port = port,
            capabilityReadCount = { capabilityReads },
            commandTransactCount = { commandTransacts },
        )
    }

    private fun record(
        journalBootIdentity: String,
        snapshotBootIdentity: String,
    ): TransactionJournalRecord = TransactionJournalRecord(
        generation = 1L,
        requestId = "request",
        transactionId = "transaction",
        hardwareFingerprint = "fingerprint",
        deviceProfile = "profile",
        planHash = "0".repeat(64),
        profileId = "balanced",
        targetPackage = "example.game",
        phase = TransactionPhase.APPLIED,
        creationTime = 1L,
        requestDeadline = 2L,
        leaseDeadline = 3L,
        bootIdentity = journalBootIdentity,
        snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "fingerprint",
            bootIdentity = snapshotBootIdentity,
            nodes = emptyList(),
        ),
        expectedTopologySha256 = "0".repeat(64),
        snapshotSha256 = "0".repeat(64),
        lastCompletedMutationBoundary = 0,
    )

    private class CommandProbe(
        val port: PServerHardwareCommandPort,
        private val capabilityReadCount: () -> Int,
        private val commandTransactCount: () -> Int,
    ) {
        val capabilityReads: Int get() = capabilityReadCount()
        val commandTransacts: Int get() = commandTransactCount()
    }
}
