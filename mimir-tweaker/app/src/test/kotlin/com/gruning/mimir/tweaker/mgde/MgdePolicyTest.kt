package com.gruning.mimir.tweaker.mgde

import com.gruning.mimir.tweaker.AppSigningCertificateDigestSource
import com.gruning.mimir.tweaker.AtomicFileTransactionJournal
import com.gruning.mimir.tweaker.AuthenticatedTransactionJournalCodec
import com.gruning.mimir.tweaker.CapabilityStatus
import com.gruning.mimir.tweaker.CpuPolicy
import com.gruning.mimir.tweaker.DirectBootKeyAuthority
import com.gruning.mimir.tweaker.DirectBootKeyAuthorityTruth
import com.gruning.mimir.tweaker.DirectBootKeyMetadata
import com.gruning.mimir.tweaker.DirectBootKeyMetadataStore
import com.gruning.mimir.tweaker.DirectBootTransactionJournalAuthentication
import com.gruning.mimir.tweaker.GenerationResult
import com.gruning.mimir.tweaker.HARDWARE_SNAPSHOT_SCHEMA
import com.gruning.mimir.tweaker.HardwareCapabilities
import com.gruning.mimir.tweaker.HardwareFingerprint
import com.gruning.mimir.tweaker.HardwareSnapshot
import com.gruning.mimir.tweaker.LogicalMutationKind
import com.gruning.mimir.tweaker.PersistedAppliedMutation
import com.gruning.mimir.tweaker.PersistedTuningPlan
import com.gruning.mimir.tweaker.PlanHash
import com.gruning.mimir.tweaker.RestoreGuardianEnvelope
import com.gruning.mimir.tweaker.RestoreGuardianFanDeleteEntry
import com.gruning.mimir.tweaker.RestoreGuardianFanPutEntry
import com.gruning.mimir.tweaker.RestoreGuardianStockState
import com.gruning.mimir.tweaker.RestoreGuardianSysfsEntry
import com.gruning.mimir.tweaker.SnapshotIntegrity
import com.gruning.mimir.tweaker.NoopDirectoryDurability
import com.gruning.mimir.tweaker.TopologyNodeState
import com.gruning.mimir.tweaker.TransactionJournalRecord
import com.gruning.mimir.tweaker.TransactionPhase
import com.gruning.mimir.tweaker.TRANSACTION_JOURNAL_SCHEMA_V3
import com.gruning.mimir.tweaker.TuningAxis
import com.gruning.mimir.tweaker.guardianFakeAndroidKeyStore
import com.gruning.mimir.tweaker.reflectedDirectBootKeyAuthority
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MgdePolicyTest {
    @Test
    fun `D6 facts are exact sealed and never a raw 198 capacity claim`() {
        assertEquals(86, MgdeCommandBudget.INSTALLED_SOURCE_DIR_BYTES)
        assertEquals(152, MgdeCommandBudget.DIRECT_S_BYTES)
        assertEquals(152, MgdeCommandBudget.DIRECT_A_BYTES)
        assertEquals(152, MgdeCommandBudget.DIRECT_R_BYTES)
        assertEquals(152, MgdeCommandBudget.DIRECT_T_BYTES)
        assertEquals(152, MgdeCommandBudget.DIRECT_C_BYTES)
        assertEquals(165, MgdeCommandBudget.DIRECT_G_BYTES)
        assertEquals(169, MgdeCommandBudget.LOCATOR_S_BYTES)
        assertEquals(182, MgdeCommandBudget.LOCATOR_G_BYTES)
        assertTrue(MgdeCommandBudget.RAW_198_MARKER_RETURNED)
        assertTrue(MgdeCommandBudget.RAW_199_MARKER_RETURNED)
        assertTrue(MgdeCommandBudget.CHECKED_198_FRAMED)
        assertTrue(MgdeCommandBudget.CHECKED_199_RENDERED_NULL)
        assertTrue(MgdeCommandBudget.CHECKED_199_NOT_SENT)
        assertNotNull(MgdeCommandBudget.checked("x".repeat(198)))
        assertNull(MgdeCommandBudget.checked("x".repeat(199)))
        assertTrue(MgdeCommandBudget.rawObserved(198))
        assertTrue(MgdeCommandBudget.rawObserved(199))
        assertFalse(MgdeCommandBudget.rawObserved(200))
        assertEquals(
            "Mimir-Probe45-RP5-D4D6-V3-20260730T015646Z",
            MgdeCommandBudget.SEALED_EVIDENCE,
        )
        assertEquals(
            "0b27cfc9c10ebe80d4e27fccd7508841bd5e1c8c429ff53cc8d6c6f4615852a2",
            MgdeCommandBudget.EVIDENCE_MANIFEST_SHA256,
        )
    }

    @Test
    fun `real authority ECDSA authenticated journal and exact fan stock accept`() {
        val fixture = MgdeFixture()
        assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
    }

    @Test
    fun `exact CPU GPU fan put and fan delete authenticated projections accept`() {
        listOf(
            FixtureKind.CPU,
            FixtureKind.CPU_PAIR,
            FixtureKind.CPU_FLOOR,
            FixtureKind.GPU,
            FixtureKind.FAN_PUT,
            FixtureKind.FAN_DELETE,
        ).forEach { kind ->
            assertTrue("$kind", MgdeFixture(kind = kind).admit() is MgdeAdmissionResult.Accepted)
        }
        listOf(0, 1).forEach { frequencyCount ->
            assertReason(
                MgdeRejection.OFF_ALLOWLIST,
                MgdeFixture(kind = FixtureKind.GPU, gpuFrequencyCount = frequencyCount).admit(),
                "gpu frequencies=$frequencyCount",
            )
        }
        assertTrue(MgdeFixture(kind = FixtureKind.GPU, gpuFrequencyCount = 8).admit() is MgdeAdmissionResult.Accepted)
    }

    @Test
    fun `authenticated projection requires exact entry set and literal reverse apply order`() {
        val fixture = MgdeFixture(kind = FixtureKind.CPU_PAIR)
        val cpu1 = fixture.envelope.restoreEntries[0] as RestoreGuardianSysfsEntry
        val cpu0 = fixture.envelope.restoreEntries[1] as RestoreGuardianSysfsEntry
        val wrongReverseOrder = fixture.envelope.copy(
            restoreEntries = listOf(
                cpu0.copy(applyOrdinal = 1),
                cpu1.copy(applyOrdinal = 0),
            ),
        )
        assertReason(
            MgdeRejection.MALFORMED,
            fixture.admit(fixture.sourceFor(wrongReverseOrder)),
            "reverse apply order",
        )

        val extraEntry = fixture.envelope.copy(
            restoreEntries = listOf(
                RestoreGuardianFanPutEntry(
                    applyOrdinal = 2,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(1),
                ),
            ) + fixture.envelope.restoreEntries,
        )
        assertReason(
            MgdeRejection.MALFORMED,
            fixture.admit(fixture.sourceFor(extraEntry)),
            "exact entry set",
        )
    }

    @Test
    fun `absent malformed substituted replayed stale wrong boot session signer expired stock policy and preunlock reject`() {
        assertReason(MgdeRejection.ABSENT, MgdeFixture().admit(MgdeObservedSource.Absent))

        MgdeFixture().let { fixture ->
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceForRaw(byteArrayOf(1, 2, 3))))
        }
        MgdeFixture().let { fixture ->
            val changed = fixture.envelope.copy(taskNonce = "f".repeat(32))
            assertReason(MgdeRejection.SUBSTITUTED, fixture.admit(fixture.sourceFor(changed)))
        }
        MgdeFixture().let { fixture ->
            assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
            assertReason(MgdeRejection.REPLAYED, fixture.admit())
        }
        MgdeFixture().let { fixture ->
            assertReason(
                MgdeRejection.STALE,
                fixture.admit(fixture.sourceFor(fixture.envelope.copy(hardDeadlineElapsedRealtimeMs = fixture.identity.expectedDeadlineElapsedRealtimeMs + 1))),
            )
        }
        MgdeFixture().let { fixture ->
            assertReason(
                MgdeRejection.WRONG_BOOT,
                fixture.admit(fixture.sourceFor(fixture.envelope.copy(bootIdentity = "boot.other"))),
            )
        }
        listOf<(RestoreGuardianEnvelope) -> RestoreGuardianEnvelope>(
            { it.copy(helperBuildIdentity = "helper.other") },
            { it.copy(deviceProfile = "rp5.other") },
            { it.copy(hardwareFingerprint = "hardware.other") },
            { it.copy(generation = 2) },
            { it.copy(requestId = "request.other") },
            { it.copy(transactionId = "transaction.other") },
            { it.copy(topologySha256 = "1".repeat(64)) },
            { it.copy(snapshotSha256 = "2".repeat(64)) },
            { it.copy(planSha256 = "3".repeat(64)) },
        ).forEachIndexed { index, transform ->
            val fixture = MgdeFixture()
            assertReason(
                MgdeRejection.WRONG_SESSION,
                fixture.admit(fixture.sourceFor(transform(fixture.envelope))),
                "session case=$index",
            )
        }
        MgdeFixture().let { fixture ->
            assertReason(
                MgdeRejection.WRONG_SIGNER,
                fixture.admit(fixture.sourceFor(fixture.envelope.copy(tweakerCertificateSha256 = "4".repeat(64)))),
            )
        }
        MgdeFixture(now = 100, deadline = 100).let { fixture ->
            assertReason(MgdeRejection.EXPIRED, fixture.admit())
        }
        MgdeFixture().let { fixture ->
            val wrongStock = fixture.envelope.copy(
                restoreEntries = listOf(
                    RestoreGuardianFanPutEntry(
                        0,
                        expectedStock = RestoreGuardianStockState.PresentFanMode(5),
                        rendererAppliedIntermediates = listOf(1),
                    ),
                ),
            )
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceFor(wrongStock)))
        }
        assertTrue(
            "non-RP5 fan projection must fail before signing",
            runCatching { MgdeFixture(deviceProfile = "other") }.isFailure,
        )
        MgdeFixture(preUnlocked = false).let { fixture ->
            fixture.metadataFile.writeBytes(byteArrayOf(1))
            assertReason(MgdeRejection.PRE_UNLOCK, fixture.admit(MgdeObservedSource.Absent))
        }
    }

    @Test
    fun `signature tamper wrong key and authenticated journal mismatch fail closed`() {
        MgdeFixture().let { fixture ->
            val wire = fixture.signedWire(fixture.envelope).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceForRaw(wire)))
        }
        MgdeFixture().let { fixture ->
            val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            fixture.keyPair.set(other)
            assertReason(MgdeRejection.MALFORMED, fixture.admit())
        }
        MgdeFixture().let { fixture ->
            val tampered = fixture.journalBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            assertReason(MgdeRejection.MALFORMED, fixture.admit(journal = tampered))
        }
    }

    @Test
    fun `invalid signature journal and projection attempts never consume before later valid acceptance`() {
        MgdeFixture().let { fixture ->
            val wire = fixture.signedWire(fixture.envelope).also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceForRaw(wire)))
            assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
        }
        MgdeFixture().let { fixture ->
            val tampered = fixture.journalBytes.copyOf().also {
                it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte()
            }
            assertReason(MgdeRejection.MALFORMED, fixture.admit(journal = tampered))
            assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
        }
        MgdeFixture().let { fixture ->
            val wrongRenderer = fixture.envelope.copy(
                restoreEntries = listOf(
                    RestoreGuardianFanPutEntry(
                        0,
                        expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                        rendererAppliedIntermediates = listOf(5),
                    ),
                ),
            )
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceFor(wrongRenderer)))
            assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
        }
    }

    @Test
    fun `authority reloads MDKA installed signer and key before every MGDE read`() {
        MgdeFixture().let { fixture ->
            assertReason(MgdeRejection.ABSENT, fixture.admit(MgdeObservedSource.Absent))
            fixture.metadataFile.writeBytes(byteArrayOf(1, 2, 3))
            assertReason(MgdeRejection.MALFORMED, fixture.admit())
        }
        MgdeFixture().let { fixture ->
            assertReason(MgdeRejection.ABSENT, fixture.admit(MgdeObservedSource.Absent))
            fixture.certificate.set("9".repeat(64))
            assertReason(MgdeRejection.MALFORMED, fixture.admit())
        }
    }

    @Test
    fun `first failure order is preunlock authority source decode bindings signature journal policy consume`() {
        MgdeFixture(preUnlocked = false).let { fixture ->
            fixture.metadataFile.writeBytes(byteArrayOf(1))
            assertReason(MgdeRejection.PRE_UNLOCK, fixture.admit(MgdeObservedSource.Absent))
        }
        MgdeFixture().let { fixture ->
            fixture.metadataFile.writeBytes(byteArrayOf(1))
            assertReason(MgdeRejection.MALFORMED, fixture.admit(MgdeObservedSource.Absent))
        }
        MgdeFixture().let { fixture ->
            assertReason(MgdeRejection.ABSENT, fixture.admit(MgdeObservedSource.Absent, journal = byteArrayOf(1)))
        }
        MgdeFixture().let { fixture ->
            assertReason(MgdeRejection.MALFORMED, fixture.admit(fixture.sourceForRaw(byteArrayOf(1)), journal = byteArrayOf(1)))
        }
        MgdeFixture().let { fixture ->
            val wrongBoot = fixture.sourceFor(fixture.envelope.copy(bootIdentity = "boot.other"))
            assertReason(MgdeRejection.WRONG_BOOT, fixture.admit(wrongBoot, journal = byteArrayOf(1)))
        }
    }

    @Test
    fun `concurrent identical admissions consume authority owned replay exactly once`() {
        val fixture = MgdeFixture()
        val source = fixture.source
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (0..1).map {
            pool.submit<MgdeAdmissionResult> {
                start.await()
                fixture.admit(source)
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdownNow()
        assertEquals(1, results.count { it is MgdeAdmissionResult.Accepted })
        assertEquals(
            1,
            results.count { it is MgdeAdmissionResult.Rejected && it.reason == MgdeRejection.REPLAYED },
        )
    }

    @Test
    fun `accepted envelope remains replayed after authority and journal objects restart`() {
        val fixture = MgdeFixture()
        assertTrue(fixture.admit() is MgdeAdmissionResult.Accepted)
        assertReason(MgdeRejection.REPLAYED, fixture.admitFromFreshProcess())
    }

    @Test
    fun `production default authority remains unwired and fails closed at consume`() {
        val fixture = MgdeFixture()
        assertReason(MgdeRejection.MALFORMED, fixture.admitWithUnavailablePort())
    }

    @Test
    fun `signed schema v3 remains readable but cannot supply exact MGDE projection`() {
        val fixture = MgdeFixture(kind = FixtureKind.GPU)
        assertReason(
            MgdeRejection.MALFORMED,
            fixture.admit(journal = fixture.legacyJournalBytes(TRANSACTION_JOURNAL_SCHEMA_V3)),
        )
        assertTrue("legacy rejection must not consume current truth", fixture.admit() is MgdeAdmissionResult.Accepted)
    }

    @Test
    fun `derived absence observations reject at admission with absent reason only`() {
        val fixture = MgdeFixture()
        fixture.derivedAbsentObservations().forEach { observation ->
            assertReason(MgdeRejection.ABSENT, fixture.admit(observation))
        }
    }

    private fun assertReason(
        expected: MgdeRejection,
        result: MgdeAdmissionResult,
        message: String = "",
    ) {
        assertTrue(message, result is MgdeAdmissionResult.Rejected)
        assertEquals(message, expected, (result as MgdeAdmissionResult.Rejected).reason)
        if (expected == MgdeRejection.PRE_UNLOCK) {
            assertEquals(
                "PRE_UNLOCK_BLOCKED reason=not_observed_no_direct_boot_claim",
                result.diagnostic,
            )
        }
    }
}

private enum class FixtureKind { FAN_PUT, FAN_DELETE, CPU, CPU_PAIR, CPU_FLOOR, GPU }

private class MgdeFixture(
    val deadline: Long = 1_000,
    now: Long = 10,
    deviceProfile: String = "rp5",
    preUnlocked: Boolean = true,
    kind: FixtureKind = FixtureKind.FAN_PUT,
    gpuFrequencyCount: Int = 2,
) {
    private val directory = createTempDirectory("mgde-authority-").toFile()
    val certificate = AtomicReference(sha("certificate"))
    val keyPair = AtomicReference(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
    val metadataFile = File(directory, DirectBootKeyMetadataStore.METADATA_FILE)
    val capabilities = capabilities(deviceProfile, kind, gpuFrequencyCount)
    private val fingerprint = HardwareFingerprint.create(capabilities)
    private val snapshot = HardwareSnapshot(
        HARDWARE_SNAPSHOT_SCHEMA,
        fingerprint,
        "boot.1",
        when (kind) {
            FixtureKind.FAN_PUT -> listOf(fanNode(present = true))
            FixtureKind.FAN_DELETE -> listOf(fanNode(present = false))
            FixtureKind.CPU, FixtureKind.CPU_PAIR -> listOf(
                TopologyNodeState(
                    "cpu:0:scaling_max_freq",
                    TuningAxis.CPU,
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    1_200_000,
                    "600",
                    0,
                    0,
                    true,
                ),
            ) + if (kind == FixtureKind.CPU_PAIR) {
                listOf(
                    TopologyNodeState(
                        "cpu:1:scaling_max_freq",
                        TuningAxis.CPU,
                        "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq",
                        1_300_000,
                        "600",
                        0,
                        0,
                        true,
                    ),
                )
            } else {
                emptyList()
            }
            FixtureKind.CPU_FLOOR -> listOf(
                TopologyNodeState(
                    "cpu:0:scaling_min_freq",
                    TuningAxis.CPU,
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                    2_200_000,
                    "600",
                    0,
                    0,
                    true,
                ),
                TopologyNodeState(
                    "cpu:0:scaling_max_freq",
                    TuningAxis.CPU,
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    3_000_000,
                    "600",
                    0,
                    0,
                    true,
                ),
            )
            FixtureKind.GPU -> listOf(
                TopologyNodeState(
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    TuningAxis.GPU,
                    "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
                    7,
                    "600",
                    0,
                    0,
                    true,
                ),
                TopologyNodeState(
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    TuningAxis.GPU,
                    "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
                    0,
                    "600",
                    0,
                    0,
                    true,
                ),
            )
        },
    )
    private val plan = when (kind) {
        FixtureKind.FAN_PUT, FixtureKind.FAN_DELETE ->
            PersistedTuningPlan("balanced", "example.game", emptyMap(), null, 1, true)
        FixtureKind.CPU, FixtureKind.CPU_PAIR, FixtureKind.CPU_FLOOR ->
            PersistedTuningPlan(
                "balanced",
                "example.game",
                mapOf(0 to 2_000_000) +
                    if (kind == FixtureKind.CPU_PAIR) mapOf(1 to 2_100_000) else emptyMap(),
                null,
                null,
                true,
            )
        FixtureKind.GPU ->
            PersistedTuningPlan("balanced", "example.game", emptyMap(), 1, null, true)
    }
    private val topology = SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(snapshot))
    private val snapshotDigest = SnapshotIntegrity.snapshotSha256(snapshot)
    private val planDigest = PlanHash.ofPersisted(plan)
    private val appliedProjection = snapshot.nodes.map { node ->
        PersistedAppliedMutation(
            logicalId = node.logicalId,
            axis = node.axis,
            canonicalPath = node.canonicalPath,
            kind = when (node.axis) {
                TuningAxis.CPU, TuningAxis.GPU -> LogicalMutationKind.SYSFS_WRITE
                TuningAxis.FAN, TuningAxis.VENDOR_MODE -> LogicalMutationKind.SETTINGS_PUT
            },
            value = when (node.axis) {
                TuningAxis.CPU -> {
                    val id = node.logicalId.removePrefix("cpu:").substringBefore(':').toInt()
                    plan.cpuMaxKhz.getValue(id)
                }
                TuningAxis.GPU -> when (node.logicalId) {
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID -> 7
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID -> plan.gpuMaxPowerLevel
                    else -> error("unexpected GPU projection")
                }
                TuningAxis.FAN -> plan.fanMode
                TuningAxis.VENDOR_MODE -> plan.vendorMode
            },
            expectPresent = true,
        )
    }
    private val journalRecord = TransactionJournalRecord(
        generation = 1,
        requestId = "request.1",
        transactionId = "transaction.1",
        hardwareFingerprint = fingerprint,
        deviceProfile = deviceProfile,
        planHash = planDigest,
        profileId = plan.profileId,
        targetPackage = plan.packageName,
        phase = TransactionPhase.PREPARED,
        creationTime = 1,
        requestDeadline = deadline,
        leaseDeadline = deadline,
        bootIdentity = "boot.1",
        snapshot = snapshot,
        expectedTopologySha256 = topology,
        snapshotSha256 = snapshotDigest,
        lastCompletedMutationBoundary = 0,
        lastFailure = "none",
        appliedPlan = plan,
        applyOrderLogicalIds = snapshot.nodes.map { it.logicalId },
        appliedMutationProjection = appliedProjection,
    )
    val envelope = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1",
        tweakerCertificateSha256 = certificate.get(),
        hardwareFingerprint = fingerprint,
        deviceProfile = deviceProfile,
        bootIdentity = "boot.1",
        generation = 1,
        requestId = "request.1",
        transactionId = "transaction.1",
        taskNonce = "a".repeat(32),
        topologySha256 = topology,
        snapshotSha256 = snapshotDigest,
        planSha256 = planDigest,
        hardDeadlineElapsedRealtimeMs = deadline,
        restoreEntries = when (kind) {
            FixtureKind.FAN_PUT -> listOf(
                RestoreGuardianFanPutEntry(
                    0,
                    expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                    rendererAppliedIntermediates = listOf(1),
                ),
            )
            FixtureKind.FAN_DELETE -> listOf(RestoreGuardianFanDeleteEntry(0))
            FixtureKind.CPU, FixtureKind.CPU_PAIR ->
                (if (kind == FixtureKind.CPU_PAIR) {
                    listOf(
                        RestoreGuardianSysfsEntry(
                            1,
                            "cpu:1:scaling_max_freq",
                            "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq",
                            RestoreGuardianStockState.PresentSysfs(1_300_000, "600", 0, 0),
                            listOf(2_100_000),
                        ),
                    )
                } else {
                    emptyList()
                }) + listOf(
                    RestoreGuardianSysfsEntry(
                        0,
                        "cpu:0:scaling_max_freq",
                        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                        RestoreGuardianStockState.PresentSysfs(1_200_000, "600", 0, 0),
                        listOf(2_000_000),
                    ),
                )
            FixtureKind.CPU_FLOOR -> listOf(
                RestoreGuardianSysfsEntry(
                    1,
                    "cpu:0:scaling_max_freq",
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    RestoreGuardianStockState.PresentSysfs(3_000_000, "600", 0, 0),
                    listOf(2_000_000),
                ),
                RestoreGuardianSysfsEntry(
                    0,
                    "cpu:0:scaling_min_freq",
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                    RestoreGuardianStockState.PresentSysfs(2_200_000, "600", 0, 0),
                    listOf(2_000_000),
                ),
            )
            FixtureKind.GPU -> listOf(
                RestoreGuardianSysfsEntry(
                    1,
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID,
                    "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
                    RestoreGuardianStockState.PresentSysfs(0, "600", 0, 0),
                    listOf(1),
                ),
                RestoreGuardianSysfsEntry(
                    0,
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
                    RestoreGuardianStockState.PresentSysfs(7, "600", 0, 0),
                    listOf(7),
                ),
            )
        },
    )
    val identity = MgdeExpectedIdentity(
        helperBuildIdentity = envelope.helperBuildIdentity,
        deviceProfile = envelope.deviceProfile,
        hardwareFingerprint = envelope.hardwareFingerprint,
        bootIdentity = envelope.bootIdentity,
        generation = envelope.generation,
        requestId = envelope.requestId,
        transactionId = envelope.transactionId,
        taskNonce = envelope.taskNonce,
        topologySha256 = envelope.topologySha256,
        snapshotSha256 = envelope.snapshotSha256,
        planSha256 = envelope.planSha256,
        signerIdentity = envelope.tweakerCertificateSha256,
        expectedDeadlineElapsedRealtimeMs = deadline,
        nowElapsedRealtimeMs = now,
        observedNoDirectBootClaim = preUnlocked,
    )
    val authority: DirectBootKeyAuthority
    private val available: DirectBootKeyAuthorityTruth.Available
    private val replayJournal: AtomicFileTransactionJournal
    val journalBytes: ByteArray
    val source: MgdeObservedSource

    init {
        val initialPair = keyPair.get()
        val metadataStore = DirectBootKeyMetadataStore(directory)
        check(
            metadataStore.writeFresh(
                DirectBootKeyMetadata(
                    DirectBootKeyAuthority.KEY_ALIAS,
                    sha(initialPair.public.encoded),
                    certificate.get(),
                ),
            ),
        )
        val bootstrapAuthority = newAuthority(metadataStore)
        available = bootstrapAuthority.inspect() as DirectBootKeyAuthorityTruth.Available
        replayJournal = AtomicFileTransactionJournal(
            filesDir = directory,
            fileName = REPLAY_JOURNAL_FILE,
            generationFileName = REPLAY_GENERATION_FILE,
            testSeam = true,
            directoryDurability = NoopDirectoryDurability,
            authentication = DirectBootTransactionJournalAuthentication(
                inspectAuthority = { available },
                provisionFreshAuthority = { error("MGDE fixture never provisions") },
                liveBootIdentity = { journalRecord.bootIdentity },
            ),
        )
        check((replayJournal.nextGeneration() as GenerationResult.Ok).value == journalRecord.generation)
        check(replayJournal.persist(journalRecord))
        authority = newAuthority(metadataStore, replayJournal)
        journalBytes = File(directory, REPLAY_JOURNAL_FILE).readBytes()
        source = sourceFor(envelope)
    }

    fun signedWire(value: RestoreGuardianEnvelope): ByteArray {
        val signed = available.signEnvelope(value)
        return MgdeV2Codec.encode(signed.envelope, signed.signature)
    }

    fun sourceFor(value: RestoreGuardianEnvelope): MgdeObservedSource =
        sourceForRaw(signedWire(value), value.taskNonce)

    fun sourceForRaw(bytes: ByteArray, nonce: String = envelope.taskNonce): MgdeObservedSource {
        val locator = MgdeAdmission.locatorFor(nonce)
        val fake = FakeMgdePosixOps("${MgdeD7PublicationStore.ROOT}/$locator")
        val store = MgdeD7PublicationStore(fake)
        check(store.publish(nonce, bytes) === MgdePublicationResult.Published)
        return store.observe(nonce)
    }

    fun derivedAbsentObservations(): List<MgdeObservedSource> {
        val locator = MgdeAdmission.locatorFor(envelope.taskNonce)
        val session = "${MgdeD7PublicationStore.ROOT}/$locator"
        return listOf(
            MgdeD7PublicationStore(FakeMgdePosixOps(session).also { it.eraseRoot() }).observe(envelope.taskNonce),
            MgdeD7PublicationStore(FakeMgdePosixOps(session).also { it.eraseSession() }).observe(envelope.taskNonce),
        )
    }

    fun admit(
        observed: MgdeObservedSource = source,
        journal: ByteArray = journalBytes,
    ): MgdeAdmissionResult = authority.admitMgde(
        source = observed,
        identity = identity,
        authenticatedJournalBytes = journal,
        liveCapabilities = capabilities,
    )

    fun admitFromFreshProcess(): MgdeAdmissionResult {
        val metadataStore = DirectBootKeyMetadataStore(directory)
        val bootstrapAuthority = newAuthority(metadataStore)
        val reloadedAvailable = bootstrapAuthority.inspect() as DirectBootKeyAuthorityTruth.Available
        val reloadedJournal = AtomicFileTransactionJournal(
            filesDir = directory,
            fileName = REPLAY_JOURNAL_FILE,
            generationFileName = REPLAY_GENERATION_FILE,
            testSeam = true,
            directoryDurability = NoopDirectoryDurability,
            authentication = DirectBootTransactionJournalAuthentication(
                inspectAuthority = { reloadedAvailable },
                provisionFreshAuthority = { error("reloaded MGDE fixture never provisions") },
                liveBootIdentity = { journalRecord.bootIdentity },
            ),
        )
        val reloadedAuthority = newAuthority(metadataStore, reloadedJournal)
        return reloadedAuthority.admitMgde(
            source = source,
            identity = identity,
            authenticatedJournalBytes = journalBytes,
            liveCapabilities = capabilities,
        )
    }

    fun admitWithUnavailablePort(): MgdeAdmissionResult =
        newAuthority(DirectBootKeyMetadataStore(directory)).admitMgde(
            source = source,
            identity = identity,
            authenticatedJournalBytes = journalBytes,
            liveCapabilities = capabilities,
        )

    fun legacyJournalBytes(schema: Int): ByteArray {
        val legacy = journalRecord.copy(
            schemaVersion = schema,
            appliedMutationProjection = emptyList(),
            consumedMgdeEnvelopeSha256 = null,
        )
        val payload = AtomicFileTransactionJournal.encodeJournal(legacy)
            .toByteArray(Charsets.UTF_8)
        return AuthenticatedTransactionJournalCodec.encode(available.signJournal(payload))
    }

    private fun newAuthority(
        metadataStore: DirectBootKeyMetadataStore,
        consumePort: MgdeAdmissionConsumePort = UnavailableMgdeAdmissionConsumePort,
    ): DirectBootKeyAuthority =
        reflectedDirectBootKeyAuthority(
            metadataStore,
            AppSigningCertificateDigestSource { certificate.get() },
            { guardianFakeAndroidKeyStore(keyPair.get()) },
            { error("MGDE fixture never provisions") },
            consumePort,
        )

    private fun capabilities(
        deviceProfile: String,
        kind: FixtureKind,
        gpuFrequencyCount: Int,
    ) = HardwareCapabilities(
        status = CapabilityStatus.SUPPORTED,
        manufacturer = "ayn",
        model = "odin2",
        socModel = "sm8550",
        buildFingerprint = "build.1",
        deviceProfileId = deviceProfile,
        supportedAxes = setOf(
            when (kind) {
                FixtureKind.FAN_PUT, FixtureKind.FAN_DELETE -> TuningAxis.FAN
                FixtureKind.CPU, FixtureKind.CPU_PAIR, FixtureKind.CPU_FLOOR -> TuningAxis.CPU
                FixtureKind.GPU -> TuningAxis.GPU
            },
        ),
        pServerAvailable = true,
        cpuPolicies = if (
            kind == FixtureKind.CPU ||
            kind == FixtureKind.CPU_PAIR ||
            kind == FixtureKind.CPU_FLOOR
        ) {
            listOf(
                CpuPolicy(
                    0,
                    "/sys/devices/system/cpu/cpufreq/policy0",
                    listOf(0),
                    if (kind == FixtureKind.CPU_FLOOR) {
                        listOf(2_000_000, 2_200_000, 3_000_000)
                    } else {
                        listOf(1_200_000, 2_000_000)
                    },
                    if (kind == FixtureKind.CPU_FLOOR) 2_200_000 else 1_200_000,
                    if (kind == FixtureKind.CPU_FLOOR) 3_000_000 else 2_000_000,
                    if (kind == FixtureKind.CPU_FLOOR) 2_200_000 else 1_200_000,
                ),
            ) + if (kind == FixtureKind.CPU_PAIR) {
                listOf(
                    CpuPolicy(
                        1,
                        "/sys/devices/system/cpu/cpufreq/policy1",
                        listOf(1),
                        listOf(1_300_000, 2_100_000),
                        1_300_000,
                        2_100_000,
                        1_300_000,
                    ),
                )
            } else {
                emptyList()
            }
        } else {
            emptyList()
        },
        gpuFrequenciesKhz = if (kind == FixtureKind.GPU) List(gpuFrequencyCount) { it + 1 } else emptyList(),
        gpuNodeAvailable = kind == FixtureKind.GPU,
        fanNodeAvailable = kind == FixtureKind.FAN_PUT || kind == FixtureKind.FAN_DELETE,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    companion object {
        private const val REPLAY_JOURNAL_FILE = "mgde-replay-journal.mtja"
        private const val REPLAY_GENERATION_FILE = "mgde-replay-generation"

        private fun fanNode(present: Boolean) = TopologyNodeState(
            HardwareSnapshot.FAN_LOGICAL_ID,
            TuningAxis.FAN,
            HardwareSnapshot.FAN_SETTINGS_PATH,
            if (present) 4 else null,
            null,
            null,
            null,
            present,
        )

        private fun sha(value: String): String = sha(value.toByteArray(Charsets.UTF_8))
        private fun sha(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}
