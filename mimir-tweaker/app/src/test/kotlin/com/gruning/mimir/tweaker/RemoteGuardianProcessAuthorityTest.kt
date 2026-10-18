package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class RemoteGuardianProcessAuthorityTest {
    @Test
    fun `ownership envelope signs both fan targets and preserves fan-first Stock settlement`() {
        val certificate = "a".repeat(64)
        val available = GuardianTestKeyAuthority.create(certificate).available
        val port = AndroidGuardianPreparedEnvelopePort(RuntimeEnvironment.getApplication(), available,
            certificate, "helper.1")
        val fan = TopologyNodeState(HardwareSnapshot.FAN_LOGICAL_ID, TuningAxis.FAN,
            HardwareSnapshot.FAN_SETTINGS_PATH, 4, null, null, null, true)
        val cpu = TopologyNodeState("cpu:0:scaling_max_freq", TuningAxis.CPU,
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", 1_800_000, "644", 0, 0, true)
        val snapshot = HardwareSnapshot(2, "hardware", "boot", listOf(cpu, fan))
        val identity = GuardianJournalIdentity(7, "request", "transaction", "hardware", "odin3", "boot",
            "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val mutations = listOf(
            LogicalMutation(fan.logicalId, fan.axis, fan.canonicalPath, LogicalMutationKind.SETTINGS_PUT,
                4, null, expectPresent = true),
            LogicalMutation(cpu.logicalId, cpu.axis, cpu.canonicalPath, LogicalMutationKind.SYSFS_WRITE,
                1_200_000, "644", 0, 0, true),
        )
        for (initialMode in listOf(4, 6)) {
            val signed = checkNotNull(port.preparedEnvelope(GuardianPreparedEnvelopeRequest(
                identity, 10_000, snapshot, mutations.map { if (it.axis == TuningAxis.FAN) it.copy(value = initialMode) else it },
                fanOwnershipTransitionsAllowed = true)))
            assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, available.envelopeVerifier()))
            assertTrue(identity.matches(signed.envelope))
            assertEquals(listOf(0, 1), signed.envelope.restoreEntries.map { it.applyOrdinal })
            val fanEntry = signed.envelope.restoreEntries.first() as RestoreGuardianFanPutEntry
            assertEquals(listOf(4, 6), fanEntry.rendererAppliedIntermediates)
            assertEquals(4, fanEntry.expectedStock.value)
            val cpuEntry = signed.envelope.restoreEntries.last() as RestoreGuardianSysfsEntry
            assertEquals(listOf(1_200_000), cpuEntry.rendererAppliedIntermediates)
            assertEquals(1_800_000, cpuEntry.expectedStock.value)
        }
        // A request for this narrow authority cannot smuggle in Sport or another target.
        assertNull(port.preparedEnvelope(GuardianPreparedEnvelopeRequest(identity, 10_000, snapshot,
            mutations.map { if (it.axis == TuningAxis.FAN) it.copy(value = 5) else it }, true)))
    }

    @Test
    fun `prepared envelope is signed and owns exact reverse Stock projection`() {
        val certificate = "a".repeat(64)
        val available = GuardianTestKeyAuthority.create(certificate).available
        val port = AndroidGuardianPreparedEnvelopePort(
            context = RuntimeEnvironment.getApplication(),
            authority = available,
            certificateDigest = certificate,
            helperBuildIdentity = "helper.1",
            random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
        )
        val snapshot = HardwareSnapshot(
            schemaVersion = 2,
            hardwareFingerprint = "odin3-fingerprint",
            bootIdentity = "boot.1",
            nodes = listOf(
                TopologyNodeState(
                    logicalId = "cpu:0:scaling_max_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    originalValue = 1_800_000,
                    originalMode = "644",
                    originalUid = 0,
                    originalGid = 0,
                    present = true,
                ),
                TopologyNodeState(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    originalValue = null,
                    originalMode = null,
                    originalUid = null,
                    originalGid = null,
                    present = false,
                ),
            ),
        )
        val identity = GuardianJournalIdentity(
            generation = 7,
            requestId = "request.1",
            transactionId = "transaction.1",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = "odin3",
            bootIdentity = snapshot.bootIdentity,
            expectedTopologySha256 = "b".repeat(64),
            snapshotSha256 = "c".repeat(64),
            planSha256 = "d".repeat(64),
        )
        val signed = port.preparedEnvelope(
            GuardianPreparedEnvelopeRequest(
                identity = identity,
                hardDeadlineElapsedRealtimeMs = 10_000,
                snapshot = snapshot,
                plannedMutations = listOf(
                    LogicalMutation(
                        logicalId = "cpu:0:scaling_max_freq",
                        axis = TuningAxis.CPU,
                        canonicalPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                        kind = LogicalMutationKind.SYSFS_WRITE,
                        value = 1_200_000,
                        restoreMode = "644",
                        restoreUid = 0,
                        restoreGid = 0,
                        expectPresent = true,
                    ),
                    LogicalMutation(
                        logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                        axis = TuningAxis.FAN,
                        canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                        kind = LogicalMutationKind.SETTINGS_PUT,
                        value = 1,
                        restoreMode = null,
                        expectPresent = true,
                    ),
                ),
            ),
        )

        assertNotNull(signed)
        val exact = requireNotNull(signed)
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(exact, available.envelopeVerifier()))
        assertTrue(identity.matches(exact.envelope))
        assertEquals(listOf(1, 0), exact.envelope.restoreEntries.map { it.applyOrdinal })
        assertTrue(exact.envelope.restoreEntries[0] is RestoreGuardianFanDeleteEntry)
        val cpu = exact.envelope.restoreEntries[1] as RestoreGuardianSysfsEntry
        assertEquals(1_800_000, cpu.expectedStock.value)
        assertEquals(listOf(1_200_000), cpu.rendererAppliedIntermediates)
    }

    @Test
    fun `identity-only production call cannot invent restore entries`() {
        val available = GuardianTestKeyAuthority.create("a".repeat(64)).available
        val port = AndroidGuardianPreparedEnvelopePort(
            context = RuntimeEnvironment.getApplication(),
            authority = available,
            certificateDigest = "a".repeat(64),
            helperBuildIdentity = "helper.1",
        )
        assertNull(
            port.preparedEnvelope(
                GuardianJournalIdentity(
                    generation = 1,
                    requestId = "request",
                    transactionId = "transaction",
                    hardwareFingerprint = "hardware",
                    deviceProfile = "odin3",
                    bootIdentity = "boot",
                    expectedTopologySha256 = "b".repeat(64),
                    snapshotSha256 = "c".repeat(64),
                    planSha256 = "d".repeat(64),
                ),
            ),
        )
    }
}
