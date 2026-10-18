package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.mgde.MgdeConsumeIdentity
import com.gruning.mimir.tweaker.mgde.MgdeConsumeOutcome
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthenticatedTransactionJournalTest {
    private val keyPair = fixedKeyPair()
    private val otherKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val available = GuardianTestKeyAuthority.create(sha("certificate"), keyPair).available
    private val pinned = available.journalVerifier()

    @Test
    fun `fan ownership amendment survives reopen without loosening ordinary journal writes`() {
        val directory = newJournalDirectory("fan-amend")
        val journal = liveJournal(directory, available, "boot-1")
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        val original = record()
        val snapshot = original.snapshot.copy(nodes = original.snapshot.nodes.map {
            it.copy(originalValue = 4, present = true)
        })
        val base = original.copy(schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5, generation = generation,
            snapshot = snapshot,
            expectedTopologySha256 = SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(snapshot)),
            snapshotSha256 = SnapshotIntegrity.snapshotSha256(snapshot))
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper-build-1", tweakerCertificateSha256 = sha("certificate"),
            hardwareFingerprint = base.hardwareFingerprint, deviceProfile = base.deviceProfile,
            bootIdentity = base.bootIdentity, generation = generation, requestId = base.requestId,
            transactionId = base.transactionId, taskNonce = "b".repeat(32),
            topologySha256 = base.expectedTopologySha256, snapshotSha256 = base.snapshotSha256,
            planSha256 = base.planHash, hardDeadlineElapsedRealtimeMs = base.leaseDeadline,
            restoreEntries = listOf(RestoreGuardianFanPutEntry(0,
                expectedStock = RestoreGuardianStockState.PresentFanMode(4),
                rendererAppliedIntermediates = listOf(4, 6))))
        val binding = GuardianJournalBinding(envelope.digestSha256(), envelope.taskNonce,
            envelope.helperBuildIdentity, envelope.tweakerCertificateSha256, envelope.hardDeadlineElapsedRealtimeMs)
        val prepared = base.copy(guardianBinding = binding)
        assertTrue(journal.persist(prepared))
        assertTrue(journal.persist(prepared.copy(phase = TransactionPhase.ARMING)))
        val ready = prepared.copy(phase = TransactionPhase.GUARDIAN_READY,
            guardianBinding = binding.copy(readySessionSha256 = sha("ready")))
        assertTrue(journal.persist(ready))
        assertTrue(journal.persist(ready.copy(phase = TransactionPhase.APPLYING)))
        val applied = ready.copy(phase = TransactionPhase.APPLIED)
        assertTrue(journal.persist(applied))
        val manual = checkNotNull(FanOwnershipAmendment.candidate(applied, 6))
        assertTrue("ordinary persist remains immutable", !journal.persist(manual))
        assertTrue("compute amendment cannot change fan", !journal.amendApplied(manual))
        assertTrue(!journal.amendFanOwnership(manual.copy(leaseDeadline = 99), envelope))
        assertTrue(!journal.amendFanOwnership(manual, envelope.copy(planSha256 = sha("forged"))))
        assertEquals(applied, (journal.load() as JournalLoadResult.Loaded).record)
        assertTrue(journal.amendFanOwnership(manual, envelope))
        val reopened = liveJournal(directory, available, "boot-1")
        assertEquals(manual, (reopened.load() as JournalLoadResult.Loaded).record)
        assertTrue(reopened.amendFanOwnership(applied, envelope))
        assertEquals(applied, (reopened.load() as JournalLoadResult.Loaded).record)
        assertTrue(!reopened.persist(manual))
    }

    @Test
    fun `schema v5 Guardian binding is signed readable and legacy writer remains v4`() {
        val base = record()
        val binding = GuardianJournalBinding(
            envelopeSha256 = sha("guardian-envelope"),
            taskNonce = "0123456789abcdef0123456789abcdef",
            helperBuildIdentity = "helper-build-1",
            tweakerCertificateSha256 = sha("certificate"),
            hardDeadlineElapsedRealtimeMs = 99L,
        )
        val v5 = base.copy(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            guardianBinding = binding,
        )
        val encoded = payload(record = v5)
        val decoded = AtomicFileTransactionJournal.decodeJournal(encoded.toString(Charsets.UTF_8))
        assertTrue(decoded is JournalLoadResult.Loaded)
        assertEquals(binding, (decoded as JournalLoadResult.Loaded).record.guardianBinding)
        assertTrue(AuthenticatedTransactionJournalCodec.decodeAndVerify(
            AuthenticatedTransactionJournalCodec.encode(available.signJournal(encoded)),
            expected(),
            pinned,
        ) is JournalLoadResult.Loaded)
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V4, base.schemaVersion)
    }

    @Test
    fun `schema v5 persistence is phase monotonic with immutable base and append-only evidence`() {
        val directory = newJournalDirectory("v5-transitions")
        val journal = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        val baseBinding = GuardianJournalBinding(
            envelopeSha256 = sha("guardian-envelope"),
            taskNonce = "0123456789abcdef0123456789abcdef",
            helperBuildIdentity = "helper-build-1",
            tweakerCertificateSha256 = sha("certificate"),
            hardDeadlineElapsedRealtimeMs = 99L,
        )
        val prepared = record().copy(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            generation = generation,
            phase = TransactionPhase.PREPARED,
            guardianBinding = baseBinding,
        )
        assertTrue(journal.persist(prepared))
        val arming = prepared.copy(phase = TransactionPhase.ARMING)
        assertTrue(journal.persist(arming))

        assertTrue(
            "ARMING cannot carry terminal evidence",
            !journal.persist(
                arming.copy(
                    guardianBinding = baseBinding.copy(
                        terminalReceiptSha256 = sha("premature-terminal"),
                    ),
                ),
            ),
        )
        assertTrue(
            "GUARDIAN_READY requires READY evidence",
            !journal.persist(arming.copy(phase = TransactionPhase.GUARDIAN_READY)),
        )

        val readyDigest = sha("ready-session")
        val ready = arming.copy(
            phase = TransactionPhase.GUARDIAN_READY,
            guardianBinding = baseBinding.copy(readySessionSha256 = readyDigest),
        )
        assertTrue(journal.persist(ready))
        assertTrue(
            "immutable Guardian identity cannot change",
            !journal.persist(
                ready.copy(
                    guardianBinding = ready.guardianBinding!!.copy(taskNonce = "f".repeat(32)),
                ),
            ),
        )
        assertTrue(
            "READY evidence cannot be replaced",
            !journal.persist(
                ready.copy(
                    guardianBinding = ready.guardianBinding!!.copy(
                        readySessionSha256 = sha("other-ready-session"),
                    ),
                ),
            ),
        )

        val restoring = ready.copy(phase = TransactionPhase.RESTORING)
        assertTrue(journal.persist(restoring))
        assertTrue(
            "terminal evidence cannot precede GUARDIAN_SETTLED",
            !journal.persist(
                restoring.copy(
                    guardianBinding = restoring.guardianBinding!!.copy(
                        terminalReceiptSha256 = sha("premature-terminal"),
                    ),
                ),
            ),
        )
        val terminalDigest = sha("stock-terminal-receipt")
        val settled = restoring.copy(
            phase = TransactionPhase.GUARDIAN_SETTLED,
            guardianBinding = restoring.guardianBinding!!.copy(
                terminalReceiptSha256 = terminalDigest,
            ),
        )
        assertTrue(journal.persist(settled))
        assertTrue(
            "terminal evidence cannot be replaced",
            !journal.persist(
                settled.copy(
                    guardianBinding = settled.guardianBinding!!.copy(
                        terminalReceiptSha256 = sha("other-terminal"),
                    ),
                ),
            ),
        )
        assertTrue("settled phase cannot roll back", !journal.persist(settled.copy(phase = TransactionPhase.RESTORING)))

        val durable = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TransactionPhase.GUARDIAN_SETTLED, durable.phase)
        assertEquals(readyDigest, durable.guardianBinding?.readySessionSha256)
        assertEquals(terminalDigest, durable.guardianBinding?.terminalReceiptSha256)
        assertTrue(
            AppliedMutationProjection.sameCanonical(
                prepared.appliedMutationProjection,
                durable.appliedMutationProjection,
            ),
        )
    }

    @Test
    fun `real authenticated journal replays READY settlement arm-first and journal-last`() {
        val directory = newJournalDirectory("v5-reconcile")
        val journal = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        val base = record().copy(generation = generation)
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "helper-build-1",
            tweakerCertificateSha256 = sha("certificate"),
            hardwareFingerprint = base.hardwareFingerprint,
            deviceProfile = base.deviceProfile,
            bootIdentity = base.bootIdentity,
            generation = base.generation,
            requestId = base.requestId,
            transactionId = base.transactionId,
            taskNonce = "0123456789abcdef0123456789abcdef",
            topologySha256 = base.expectedTopologySha256,
            snapshotSha256 = base.snapshotSha256,
            planSha256 = base.planHash,
            hardDeadlineElapsedRealtimeMs = 99,
            restoreEntries = listOf(RestoreGuardianFanDeleteEntry(applyOrdinal = 0)),
        )
        val binding = GuardianJournalBinding(
            envelopeSha256 = envelope.digestSha256(),
            taskNonce = envelope.taskNonce,
            helperBuildIdentity = envelope.helperBuildIdentity,
            tweakerCertificateSha256 = envelope.tweakerCertificateSha256,
            hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
        )
        val prepared = base.copy(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            phase = TransactionPhase.PREPARED,
            guardianBinding = binding,
        )
        assertTrue(journal.persist(prepared))
        assertTrue(journal.persist(prepared.copy(phase = TransactionPhase.ARMING)))

        val armStore = InMemoryRestoreGuardianArmStore(available)
        val signedEnvelope = available.signEnvelope(envelope)
        assertEquals(GuardianArmStoreWrite.Armed, armStore.arm(signedEnvelope))
        val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
        val sessionSigner = EcdsaP256GuardianSessionSigner(sessionPair.private)
        val readyReceipt = RestoreGuardianReadyReceipt(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            sessionPublicKey = sessionKey,
            issuedElapsedRealtimeMs = 1,
        )
        val signedReady = RestoreGuardianCrypto.signReadyReceipt(readyReceipt, sessionSigner)
        val expected = checkNotNull(GuardianArmExpectedIdentityDerivation.from(prepared))
        assertEquals(
            GuardianSessionPinWrite.Pinned,
            armStore.pinSessionKey(expected, sessionKey, signedReady, 2),
        )
        val stockReceipt = RestoreGuardianStockConfirmedReceipt(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(envelope.restoreEntries),
            firstReadbackElapsedRealtimeMs = 2,
            secondReadbackElapsedRealtimeMs = 77,
            issuedElapsedRealtimeMs = 78,
        )
        val signedStock = RestoreGuardianCrypto.signStockConfirmedReceipt(stockReceipt, sessionSigner)
        val exitedReceipt = RestoreGuardianExitedReceipt(
            helperBuildIdentity = envelope.helperBuildIdentity,
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            requestId = envelope.requestId,
            transactionId = envelope.transactionId,
            taskNonce = envelope.taskNonce,
            envelopeSha256 = envelope.digestSha256(),
            sessionKeySha256 = sessionKey.sha256(),
            verifiedStockEntrySetSha256 = GuardianRestoreEntrySetDigest.of(envelope.restoreEntries),
            stockConfirmedReceiptSha256 = stockReceipt.digestSha256(),
            issuedElapsedRealtimeMs = 79,
        )
        val terminal = GuardianTerminalLoad.Received(
            signedStock,
            RestoreGuardianCrypto.signExitedReceipt(exitedReceipt, sessionSigner),
        )
        val executor = GuardianTransactionReconciliationExecutor(
            journal,
            armStore,
            GuardianTerminalReceiptPort { terminal },
        )

        assertEquals(GuardianTransactionStep.APPEND_JOURNAL_READY, executor.reconcileOnce().appliedStep)
        assertEquals(GuardianTransactionStep.ADVANCE_JOURNAL_SETTLED, executor.reconcileOnce().appliedStep)
        assertEquals(
            TransactionPhase.GUARDIAN_SETTLED,
            (journal.load() as JournalLoadResult.Loaded).record.phase,
        )
        assertEquals(GuardianTransactionStep.CLEAR_GUARDIAN_ARM, executor.reconcileOnce().appliedStep)
        assertEquals(GuardianTransactionStep.CLEAR_SETTLED_JOURNAL, executor.reconcileOnce().appliedStep)
        assertTrue(journal.load() is JournalLoadResult.Empty)
        assertEquals(GuardianArmStorePresence.ABSENT, armStore.presence())
    }

    @Test
    fun `fixed MTJA v1 golden and SPKI remain readable and fresh writes stay version one`() {
        val fixedSpki = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEn8iA2yDc+VaFstxdHPCyZ55VX0b4WgPHIgJIQ6NVo3RyysHmT0A4VP2QOhxaePgTMxwxuH0Pnw3TuZ0v+KD8IA==",
        )
        val golden = Base64.getDecoder().decode(
            "TVRKQQAAAAEAAAACAAAAQGRiZGE0OGE3NmM3NmEwZWEwNWFjYjA1MTYxMmFiODZmOTRkODhjYjQ5M2NiMTk1ZjBjMzg2MzlkOTU4ODI4MWYAAAROeyJzY2hlbWFWZXJzaW9uIjoyLCJnZW5lcmF0aW9uIjo3LCJyZXF1ZXN0SWQiOiJyZXF1ZXN0LTciLCJ0cmFuc2FjdGlvbklkIjoidHJhbnNhY3Rpb24tNyIsImhhcmR3YXJlRmluZ2VycHJpbnQiOiJoYXJkd2FyZS0xIiwiZGV2aWNlUHJvZmlsZSI6InByb2ZpbGUtMSIsInBsYW5IYXNoIjoiNjQ4NzlmN2Q2Yjk2MGEwMTkwOTc2MmQ5MTFhMzJkNDU4MmMyMDAxMGM1NjQxZWU5MDI3OGI2NDRhOWUzYjUyNSIsInByb2ZpbGVJZCI6InN0b2NrIiwidGFyZ2V0UGFja2FnZSI6ImV4YW1wbGUuZ2FtZSIsInBoYXNlIjoiUFJFUEFSRUQiLCJjcmVhdGlvblRpbWUiOjEsInJlcXVlc3REZWFkbGluZSI6MiwibGVhc2VEZWFkbGluZSI6MywiYm9vdElkZW50aXR5IjoiYm9vdC0xIiwibGFzdENvbXBsZXRlZE11dGF0aW9uQm91bmRhcnkiOjAsImxhc3RDb21wbGV0ZWRSZXN0b3JlQm91bmRhcnkiOjAsImV4cGVjdGVkVG9wb2xvZ3lTaGEyNTYiOiI4NTlkN2E4NDJiOTg5YzlhMGZmNDc5ZGU0MjBkNDNiNjI2YzZkNzgxYWM4NGZiOGMzODVmMGYyZmYzM2Q4OThlIiwic25hcHNob3RTaGEyNTYiOiIyNDlhMzE5OTgyYTZhMTU4ZDJkYTdjZDFiYjRjYTM2ODU1MTQyN2U2MzA1ZWEyYjBjNTE3Y2ZkMjQ3ODY3MDJlIiwic25hcHNob3QiOnsic2NoZW1hVmVyc2lvbiI6MSwiaGFyZHdhcmVGaW5nZXJwcmludCI6ImhhcmR3YXJlLTEiLCJib290SWRlbnRpdHkiOiJib290LTEiLCJub2RlcyI6W3sibG9naWNhbElkIjoiZmFuOmZhbl9tb2RlIiwiYXhpcyI6IkZBTiIsImNhbm9uaWNhbFBhdGgiOiJzZXR0aW5nczpzeXN0ZW06ZmFuX21vZGUiLCJvcmlnaW5hbFZhbHVlIjpudWxsLCJvcmlnaW5hbE1vZGUiOm51bGwsIm9yaWdpbmFsVWlkIjpudWxsLCJvcmlnaW5hbEdpZCI6bnVsbCwicHJlc2VudCI6ZmFsc2V9XX0sImxhc3RGYWlsdXJlIjoibm9uZSIsImFwcGxpZWRQbGFuIjp7InByb2ZpbGVJZCI6InN0b2NrIiwicGFja2FnZU5hbWUiOiJleGFtcGxlLmdhbWUiLCJjcHVNYXhLaHoiOnt9LCJncHVNYXhQb3dlckxldmVsIjpudWxsLCJmYW5Nb2RlIjpudWxsLCJjYWxpYnJhdGVkIjpmYWxzZX0sImFwcGx5T3JkZXJMb2dpY2FsSWRzIjpbImZhbjpmYW5fbW9kZSJdfQAAAEYwRAIgb4UpczalNLpEHgbjfMvnYCiPw0f76/xjxFU0UAnAIMACIG/GB96tAgEpA5454MWH0O/QYa+C4wUHZZ2uYI+FK5rG",
        )
        assertTrue(fixedSpki.contentEquals(keyPair.public.encoded))
        assertTrue(
            AuthenticatedTransactionJournalCodec.decodeAndVerify(golden, expected(), pinned) is JournalLoadResult.Loaded,
        )
        assertEquals(1, readInt(golden, 4))
        val fresh = AuthenticatedTransactionJournalCodec.encode(available.signJournal(payload()))
        assertEquals(1, readInt(fresh, 4))
    }

    @Test
    fun `MTJA rejects future versions magic schema cross format and every offset truncation`() {
        val signed = available.signJournal(payload())
        val exact = AuthenticatedTransactionJournalCodec.encode(signed)
        for (length in 0 until exact.size) {
            assertCorrupt(exact.copyOf(length))
        }
        listOf(0, 2, 3, -1, Int.MAX_VALUE).forEach { version ->
            assertCorrupt(exact.copyOf().also { writeInt(it, 4, version) })
        }
        assertCorrupt(exact.copyOf().also { writeInt(it, 0, 0x4d475241) })
        assertCorrupt(exact.copyOf().also { writeInt(it, 8, TRANSACTION_JOURNAL_SCHEMA_V5 + 1) })
        assertCorrupt(byteArrayOf(0x4d, 0x47, 0x52, 0x41, 0, 0, 0, 3))
        assertCorrupt(reSignedRecordVersion(2, signed))
    }

    @Test
    fun `canonical authenticated record loads only with pinned key and exact identity`() {
        val payload = payload()
        val signed = available.signJournal(payload)
        val encoded = AuthenticatedTransactionJournalCodec.encode(signed)
        assertTrue(encoded.contentEquals(AuthenticatedTransactionJournalCodec.encode(signed)))

        val loaded = AuthenticatedTransactionJournalCodec.decodeAndVerify(encoded, expected(), pinned)
        assertTrue(loaded is JournalLoadResult.Loaded)
        assertEquals(7L, (loaded as JournalLoadResult.Loaded).record.generation)
    }

    @Test
    fun `unsigned malformed truncated trailing key provenance and tampered records are corrupt`() {
        val signed = available.signJournal(payload())
        val encoded = AuthenticatedTransactionJournalCodec.encode(signed)
        val cases = listOf(
            ByteArray(0),
            encoded.copyOf(encoded.size - 1),
            encoded + 0x01,
            AuthenticatedTransactionJournalCodec.encode(signed.copy(signature = ByteArray(1) { 3 })),
            AuthenticatedTransactionJournalCodec.encode(signed.copy(keyIdentity = "other-key")),
            encoded.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() },
        )
        cases.forEach { assertCorrupt(it) }
        assertCorrupt(
            encoded,
            GuardianTestKeyAuthority.create(sha("certificate"), otherKeyPair).available.journalVerifier(),
        )
    }

    @Test
    fun `recomputed inner journal digests and replay identities cannot bypass signature gate`() {
        val original = payload()
        val signed = available.signJournal(original)
        val encoded = AuthenticatedTransactionJournalCodec.encode(signed)
        assertCorrupt(encoded, identity = expected().copy(generation = 8))
        assertCorrupt(encoded, identity = expected().copy(transactionId = "transaction-8"))
        assertCorrupt(encoded, identity = expected().copy(bootIdentity = "boot-2"))

        // The payload is a syntactically valid journal with freshly recomputed inner topology and
        // snapshot digests, but changing any signed byte remains Corrupt.
        val recomputed = payload(record = record().copy(planHash = sha("other-plan")))
        val forged = AuthenticatedTransactionJournalCodec.encode(signed.copy(encodedJournalPayload = recomputed))
        assertCorrupt(forged)
    }

    @Test
    fun `live atomic journal persists only MTJA and rejects tamper wrong key and wrong boot`() {
        val directory = newJournalDirectory("live-auth")
        val journal = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        assertTrue(journal.persist(record().copy(generation = generation)))

        val recordFile = File(directory, "journal.mtja")
        val exact = recordFile.readBytes()
        assertTrue("live record must not be unsigned JSON", exact.firstOrNull()?.toInt() != '{'.code)
        assertTrue(journal.load() is JournalLoadResult.Loaded)

        val wrongKey = liveJournal(
            directory,
            GuardianTestKeyAuthority.create(sha("certificate"), otherKeyPair).available,
            bootIdentity = "boot-1",
        )
        assertTrue(wrongKey.load() is JournalLoadResult.Corrupt)
        assertTrue(liveJournal(directory, available, bootIdentity = "other-boot").load() is JournalLoadResult.Corrupt)

        recordFile.writeBytes(exact.copyOf().also { bytes ->
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        })
        assertTrue(journal.load() is JournalLoadResult.Corrupt)
    }

    @Test
    fun `authenticated schema v5 remains readable across boot for bounded Guardian recovery`() {
        val directory = newJournalDirectory("v5-cross-boot")
        val firstBoot = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (firstBoot.nextGeneration() as GenerationResult.Ok).value
        val base = record().copy(generation = generation)
        val v5 = base.copy(
            schemaVersion = TRANSACTION_JOURNAL_SCHEMA_V5,
            guardianBinding = GuardianJournalBinding(
                envelopeSha256 = sha("cross-boot-envelope"),
                taskNonce = "0123456789abcdef0123456789abcdef",
                helperBuildIdentity = "helper-build-1",
                tweakerCertificateSha256 = sha("certificate"),
                hardDeadlineElapsedRealtimeMs = 99,
            ),
        )
        assertTrue(firstBoot.persist(v5))

        val afterBoot = liveJournal(directory, available, bootIdentity = "boot-2").load()

        assertTrue(afterBoot is JournalLoadResult.Loaded)
        assertEquals("boot-1", (afterBoot as JournalLoadResult.Loaded).record.bootIdentity)
        assertEquals(TRANSACTION_JOURNAL_SCHEMA_V5, afterBoot.record.schemaVersion)
    }

    @Test
    fun `live journal provisions only for fresh write and never blesses unsigned active state`() {
        val directory = newJournalDirectory("fresh-write")
        var truth: DirectBootKeyAuthorityTruth = DirectBootKeyAuthorityTruth.Fresh
        var provisions = 0
        val authentication = DirectBootTransactionJournalAuthentication(
            inspectAuthority = { truth },
            provisionFreshAuthority = {
                provisions += 1
                available.also { truth = it }
            },
            liveBootIdentity = { "boot-1" },
        )
        val journal = atomicJournal(directory, authentication)
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        assertTrue(journal.persist(record().copy(generation = generation)))
        assertEquals(1, provisions)
        assertTrue(journal.load() is JournalLoadResult.Loaded)

        val legacyDirectory = newJournalDirectory("unsigned-read")
        val unsigned = atomicJournal(legacyDirectory, authentication = null)
        val unsignedGeneration = (unsigned.nextGeneration() as GenerationResult.Ok).value
        assertTrue(unsigned.persist(record().copy(generation = unsignedGeneration)))
        truth = DirectBootKeyAuthorityTruth.Fresh
        provisions = 0
        assertTrue(atomicJournal(legacyDirectory, authentication).load() is JournalLoadResult.Corrupt)
        assertEquals("read must not provision or bless an unsigned record", 0, provisions)
    }

    @Test
    fun `schema v4 consume survives restart and stale phase writes cannot alter projection or marker`() {
        val directory = newJournalDirectory("v4-consume")
        val journal = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (journal.nextGeneration() as GenerationResult.Ok).value
        val active = record().copy(generation = generation)
        assertTrue(journal.persist(active))

        val identity = MgdeConsumeIdentity(
            bootIdentity = active.bootIdentity,
            generation = generation,
            requestId = active.requestId,
            transactionId = active.transactionId,
            envelopeSha256 = sha("accepted-envelope"),
        )
        assertEquals(MgdeConsumeOutcome.FIRST_CONSUME, journal.consumeExact(identity))
        val consumed = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TRANSACTION_JOURNAL_SCHEMA, consumed.schemaVersion)
        assertEquals(identity.envelopeSha256, consumed.consumedMgdeEnvelopeSha256)

        assertEquals(
            MgdeConsumeOutcome.ALREADY_CONSUMED,
            liveJournal(directory, available, bootIdentity = "boot-1").consumeExact(identity),
        )
        val beforeConflict = File(directory, "journal.mtja").readBytes()
        assertEquals(
            MgdeConsumeOutcome.CONFLICTING_ENVELOPE,
            journal.consumeExact(
                identity.copy(envelopeSha256 = sha("conflicting-envelope")),
            ),
        )
        assertTrue(
            "conflicting consume must leave the signed journal byte-identical",
            beforeConflict.contentEquals(File(directory, "journal.mtja").readBytes()),
        )

        // A transaction phase writer may still hold the pre-consume v4 object.
        assertTrue(journal.persist(active.copy(phase = TransactionPhase.APPLYING)))
        val afterStaleWrite = (journal.load() as JournalLoadResult.Loaded).record
        assertEquals(TransactionPhase.APPLYING, afterStaleWrite.phase)
        assertEquals(TRANSACTION_JOURNAL_SCHEMA, afterStaleWrite.schemaVersion)
        assertEquals(identity.envelopeSha256, afterStaleWrite.consumedMgdeEnvelopeSha256)
        assertTrue(
            AppliedMutationProjection.sameCanonical(
                active.appliedMutationProjection,
                afterStaleWrite.appliedMutationProjection,
            ),
        )

        assertTrue(
            "a different consumed digest must not replace established truth",
            !journal.persist(
                afterStaleWrite.copy(
                    consumedMgdeEnvelopeSha256 = sha("different-envelope"),
                ),
            ),
        )
        assertTrue(
            "a changed renderer projection must not replace established truth",
            !journal.persist(
                afterStaleWrite.copy(
                    appliedMutationProjection = afterStaleWrite.appliedMutationProjection.map {
                        it.copy(value = (it.value ?: 0) + 1)
                    },
                ),
            ),
        )
    }

    @Test
    fun `signed v2 and v3 stay recovery readable but cannot migrate or consume`() {
        listOf(TRANSACTION_JOURNAL_SCHEMA_V2, TRANSACTION_JOURNAL_SCHEMA_V3).forEach { schema ->
            val directory = newJournalDirectory("legacy-$schema-consume")
            val journal = liveJournal(directory, available, bootIdentity = "boot-1")
            val generation = (journal.nextGeneration() as GenerationResult.Ok).value
            val legacy = record().copy(
                schemaVersion = schema,
                generation = generation,
                appliedMutationProjection = emptyList(),
                consumedMgdeEnvelopeSha256 = null,
            )
            assertTrue(journal.persist(legacy))
            val before = File(directory, "journal.mtja").readBytes()
            val identity = MgdeConsumeIdentity(
                bootIdentity = legacy.bootIdentity,
                generation = generation,
                requestId = legacy.requestId,
                transactionId = legacy.transactionId,
                envelopeSha256 = sha("legacy-envelope-$schema"),
            )
            assertEquals(MgdeConsumeOutcome.UNVERIFIABLE, journal.consumeExact(identity))
            assertTrue(before.contentEquals(File(directory, "journal.mtja").readBytes()))
            assertTrue(!journal.persist(legacy.copy(phase = TransactionPhase.APPLYING)))
            val loaded = (journal.load() as JournalLoadResult.Loaded).record
            assertEquals(schema, loaded.schemaVersion)
            assertEquals(TransactionPhase.PREPARED, loaded.phase)
            assertTrue(loaded.appliedMutationProjection.isEmpty())
        }
    }

    @Test
    fun `two journal objects serialize one exact first consume`() {
        val directory = newJournalDirectory("concurrent-consume")
        val writer = liveJournal(directory, available, bootIdentity = "boot-1")
        val generation = (writer.nextGeneration() as GenerationResult.Ok).value
        val active = record().copy(generation = generation)
        assertTrue(writer.persist(active))
        val identity = MgdeConsumeIdentity(
            bootIdentity = active.bootIdentity,
            generation = generation,
            requestId = active.requestId,
            transactionId = active.transactionId,
            envelopeSha256 = sha("one-envelope"),
        )
        val journals = listOf(
            liveJournal(directory, available, bootIdentity = "boot-1"),
            liveJournal(directory, available, bootIdentity = "boot-1"),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = journals.map { journal ->
            executor.submit<MgdeConsumeOutcome> {
                start.await()
                journal.consumeExact(identity)
            }
        }
        start.countDown()
        val results = outcomes.map { it.get() }
        executor.shutdownNow()
        assertEquals(1, results.count { it == MgdeConsumeOutcome.FIRST_CONSUME })
        assertEquals(1, results.count { it == MgdeConsumeOutcome.ALREADY_CONSUMED })
    }

    private fun assertCorrupt(
        bytes: ByteArray,
        verifier: PinnedTransactionJournalVerifier = pinned,
        identity: TransactionJournalExpectedIdentity = expected(),
    ) = assertTrue(
        "expected Corrupt",
        AuthenticatedTransactionJournalCodec.decodeAndVerify(bytes, identity, verifier) is JournalLoadResult.Corrupt,
    )

    private fun expected() = TransactionJournalExpectedIdentity("boot-1", 7, "request-7", "transaction-7")

    private fun liveJournal(
        directory: File,
        key: DirectBootKeyAuthorityTruth.Available,
        bootIdentity: String,
    ): AtomicFileTransactionJournal = atomicJournal(
        directory = directory,
        authentication = DirectBootTransactionJournalAuthentication(
            inspectAuthority = { key },
            provisionFreshAuthority = { error("available authority must not provision") },
            liveBootIdentity = { bootIdentity },
        ),
    )

    private fun atomicJournal(
        directory: File,
        authentication: TransactionJournalAuthentication?,
    ): AtomicFileTransactionJournal = AtomicFileTransactionJournal(
        filesDir = directory,
        fileName = "journal.mtja",
        generationFileName = "generation",
        testSeam = true,
        directoryDurability = NoopDirectoryDurability,
        authentication = authentication,
    )

    private fun newJournalDirectory(suffix: String): File =
        kotlin.io.path.createTempDirectory("mimir-$suffix-").toFile()

    private fun payload(record: TransactionJournalRecord = record()): ByteArray =
        AtomicFileTransactionJournal.encodeJournal(record).toByteArray(Charsets.UTF_8)

    private fun record(): TransactionJournalRecord {
        val snapshot = HardwareSnapshot(
            schemaVersion = HARDWARE_SNAPSHOT_SCHEMA,
            hardwareFingerprint = "hardware-1",
            bootIdentity = "boot-1",
            nodes = listOf(
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
        val plan = PersistedTuningPlan(
            "balanced",
            "example.game",
            emptyMap(),
            null,
            TuningPlanner.FAN_SMART,
            true,
        )
        return TransactionJournalRecord(
            generation = 7,
            requestId = "request-7",
            transactionId = "transaction-7",
            hardwareFingerprint = snapshot.hardwareFingerprint,
            deviceProfile = "rp5",
            planHash = PlanHash.ofPersisted(plan),
            profileId = plan.profileId,
            targetPackage = "example.game",
            phase = TransactionPhase.PREPARED,
            creationTime = 1,
            requestDeadline = 2,
            leaseDeadline = 3,
            bootIdentity = snapshot.bootIdentity,
            snapshot = snapshot,
            expectedTopologySha256 = SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(snapshot)),
            snapshotSha256 = SnapshotIntegrity.snapshotSha256(snapshot),
            lastCompletedMutationBoundary = 0,
            lastFailure = "none",
            appliedPlan = plan,
            applyOrderLogicalIds = listOf(HardwareSnapshot.FAN_LOGICAL_ID),
            appliedMutationProjection = listOf(
                PersistedAppliedMutation(
                    logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                    axis = TuningAxis.FAN,
                    canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                    kind = LogicalMutationKind.SETTINGS_PUT,
                    value = TuningPlanner.FAN_SMART,
                    expectPresent = true,
                ),
            ),
        )
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun reSignedRecordVersion(version: Int, signed: SignedTransactionJournalRecord): ByteArray {
        val canonical = ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.string("MIMIR_AUTHENTICATED_TRANSACTION_JOURNAL")
                out.writeInt(version)
                out.writeInt(signed.journalSchemaVersion)
                out.string(signed.keyIdentity)
                out.bytes(signed.encodedJournalPayload)
            }
        }.toByteArray()
        val signature = EcdsaP256Signer(keyPair.private).sign(canonical)
        return ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeInt(0x4d544a41)
                out.writeInt(version)
                out.writeInt(signed.journalSchemaVersion)
                out.string(signed.keyIdentity)
                out.bytes(signed.encodedJournalPayload)
                out.bytes(signature)
            }
        }.toByteArray()
    }

    private fun DataOutputStream.bytes(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun fixedKeyPair(): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(
                    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgj7SiOeQqxYCWlRliDTNAEYB7zYMIUyVEhP2bNGaOpMuhRANCAASfyIDbINz5VoWy3F0c8LJnnlVfRvhaA8ciAkhDo1WjdHLKweZPQDhU/ZA6HFp4+BMzHDG4fQ+fDdO5nS/4oPwg",
                ),
            ),
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(
                Base64.getDecoder().decode(
                    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEn8iA2yDc+VaFstxdHPCyZ55VX0b4WgPHIgJIQ6NVo3RyysHmT0A4VP2QOhxaePgTMxwxuH0Pnw3TuZ0v+KD8IA==",
                ),
            ),
        )
        return KeyPair(publicKey, privateKey)
    }
}
