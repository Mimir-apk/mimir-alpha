package com.gruning.mimir.tweaker

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FpsLimiterJournalTest {
    @Test
    fun `journal phases are monotonic and immutable identity cannot change`() {
        val journal = InMemoryFpsLimiterJournal()
        val generation = (journal.nextGeneration() as FpsLimiterGeneration.Ok).value
        val prepared = record(generation)
        assertTrue(journal.persist(prepared))
        assertFalse(journal.persist(prepared.copy(targetFps = 40)))
        assertTrue(journal.persist(prepared.copy(phase = FpsLimiterJournalPhase.APPLIED)))
        assertFalse(journal.persist(prepared.copy(phase = FpsLimiterJournalPhase.PREPARED)))
        assertTrue(journal.persist(prepared.copy(phase = FpsLimiterJournalPhase.RESTORING)))
        assertTrue(
            journal.persist(
                prepared.copy(
                    phase = FpsLimiterJournalPhase.RESTORE_FAILED,
                    lastFailure = "readback uncertain",
                ),
            ),
        )
        assertFalse(journal.clear("wrong-tx"))
        assertTrue(journal.clear(prepared.transactionId))
        assertEquals(FpsLimiterJournalLoad.Empty, journal.load())
    }

    @Test
    fun `payload and domain-separated signature round trip and reject tamper`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val available = GuardianTestKeyAuthority.create("a".repeat(64), keyPair).available
        val payload = FpsLimiterJournalCodec.encodePayload(record(1))
        val signed = available.signFpsJournal(payload)

        assertTrue(available.verifiesFpsJournal(signed))
        assertEquals(record(1), FpsLimiterJournalCodec.decodePayload(signed.encodedPayload))
        val tampered = signed.copy(encodedPayload = signed.encodedPayload.copyOf().also { it[it.lastIndex]++ })
        assertFalse(available.verifiesFpsJournal(tampered))
    }

    @Test
    fun `codec rejects truncation trailing bytes and malformed package identity`() {
        val payload = FpsLimiterJournalCodec.encodePayload(record(1))
        assertEquals(record(1), FpsLimiterJournalCodec.decodePayload(payload))
        assertEquals(null, FpsLimiterJournalCodec.decodePayload(payload.copyOf(payload.size - 1)))
        assertEquals(null, FpsLimiterJournalCodec.decodePayload(payload + 0))
    }

    @Test
    fun `atomic journal survives recreation and rejects disk tamper`() {
        val filesDir = Files.createTempDirectory("mimir-fps-journal").toFile()
        try {
            val first = AtomicFileFpsLimiterJournal(filesDir, DigestAuthentication, testSeam = true)
            val generation = (first.nextGeneration() as FpsLimiterGeneration.Ok).value
            val expected = record(generation)
            assertTrue(first.persist(expected))

            val recreated = AtomicFileFpsLimiterJournal(filesDir, DigestAuthentication, testSeam = true)
            assertEquals(FpsLimiterJournalLoad.Loaded(expected), recreated.load())

            val journalFile = filesDir.resolve("mimir_fps_intervention.journal")
            journalFile.writeBytes(journalFile.readBytes().also { bytes -> bytes[bytes.lastIndex]++ })
            assertTrue(recreated.load() is FpsLimiterJournalLoad.Corrupt)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun record(generation: Long) = FpsLimiterJournalRecord(
        generation = generation,
        requestId = "mimir.fps.request",
        transactionId = "mimir.fps.tx",
        ownerUid = 1000,
        packageIdentity = GameModeFpsPackageIdentity(
            packageName = "dev.eden.eden_emulator",
            userId = 0,
            uid = 10123,
            versionCode = 77L,
            signingCertificateSha256 = "b".repeat(64),
        ),
        targetFps = 60,
        phase = FpsLimiterJournalPhase.PREPARED,
        createdAtElapsedRealtimeMs = 10L,
        hardDeadlineElapsedRealtimeMs = 20L,
        bootIdentity = "boot-1",
    )

    private object DigestAuthentication : FpsLimiterJournalAuthentication {
        private const val KEY_IDENTITY = "test.fps.journal.key"

        override fun protect(payload: ByteArray): ByteArray {
            val unsigned = SignedFpsLimiterJournalRecord(KEY_IDENTITY, payload, byteArrayOf(1))
            return FpsLimiterJournalCodec.encodeSigned(
                unsigned.copy(signature = MessageDigest.getInstance("SHA-256").digest(unsigned.canonicalBytes())),
            )
        }

        override fun verifyAndDecode(bytes: ByteArray): FpsLimiterJournalLoad {
            val signed = FpsLimiterJournalCodec.decodeSigned(bytes)
                ?: return FpsLimiterJournalLoad.Corrupt("malformed test envelope")
            val expected = MessageDigest.getInstance("SHA-256").digest(signed.canonicalBytes())
            if (!MessageDigest.isEqual(expected, signed.signature)) {
                return FpsLimiterJournalLoad.Corrupt("invalid test signature")
            }
            val record = FpsLimiterJournalCodec.decodePayload(signed.encodedPayload)
                ?: return FpsLimiterJournalLoad.Corrupt("malformed test payload")
            return FpsLimiterJournalLoad.Loaded(record)
        }
    }
}
