package com.gruning.mimir.rewrite

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsCredentialRepositoryTest {
    @Test
    fun `save encrypts self verifies commits and verifies the exact persisted record`() {
        val store = FakeStore()
        val repository = repository(store, mutableListOf("binding-1"))

        val saved = repository.saveAccepted("  player  ", "  api-key  ")

        assertAvailable(saved, "player", "api-key", accepted = true, binding = "binding-1")
        assertEquals(1, store.writeCalls)
        assertEquals(RetroAchievementsCredentialEncryptedFormat, store.record.format)
        assertEquals("binding-1", store.record.bindingToken)
        assertAvailable(
            repository.load(),
            "player",
            "api-key",
            accepted = true,
            binding = "binding-1",
        )
    }

    @Test
    fun `save rejects invalid input false commits and mismatched readback`() {
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore()).saveAccepted("", "key"),
        )
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore()).saveAccepted("player", " "),
        )
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore(writeSucceeds = false)).saveAccepted("player", "key"),
        )
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore(corruptAfterWrite = true)).saveAccepted("player", "key"),
        )
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore(), mutableListOf(" ")).saveAccepted("player", "key"),
        )
    }

    @Test
    fun `missing partial malformed unknown and corrupt records fail closed`() {
        assertSame(
            RetroAchievementsCredentialLoadResult.Missing,
            repository(FakeStore()).load(),
        )

        val cases = listOf(
            stored(recordPresent = true, username = "player"),
            stored(
                recordPresent = true,
                malformed = true,
                format = RetroAchievementsCredentialEncryptedFormat,
                username = "player",
                ciphertext = "cipher",
                iv = "iv",
                accepted = true,
                binding = "binding",
            ),
            currentRecord(format = 99),
            currentRecord(username = " player "),
            currentRecord(ciphertext = "broken"),
            currentRecord(iv = ""),
            currentRecord(binding = ""),
            currentRecord(accepted = null),
        )

        cases.forEach { record ->
            assertSame(
                record.toString(),
                RetroAchievementsCredentialLoadResult.Unavailable,
                repository(FakeStore(record = record)).load(),
            )
        }

        val throwingStore = FakeStore().apply { readThrows = true }
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(throwingStore).load(),
        )
        val throwingCipher = FakeCipher().apply { decryptThrows = true }
        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository(FakeStore(record = currentRecord()), cipher = throwingCipher).load(),
        )
    }

    @Test
    fun `current format authenticates username accepted binding and record ciphertext`() {
        val firstStore = FakeStore()
        val firstRepository = repository(firstStore, mutableListOf("binding-one"))
        assertTrue(
            firstRepository.saveAccepted("player-one", "key-one") is
                RetroAchievementsCredentialLoadResult.Available,
        )
        val first = firstStore.record

        firstStore.record = copy(first, username = "tampered-player")
        assertSame(RetroAchievementsCredentialLoadResult.Unavailable, firstRepository.load())

        firstStore.record = copy(first, accepted = false)
        assertSame(RetroAchievementsCredentialLoadResult.Unavailable, firstRepository.load())

        firstStore.record = copy(first, binding = "tampered-binding")
        assertSame(RetroAchievementsCredentialLoadResult.Unavailable, firstRepository.load())

        val secondStore = FakeStore()
        assertTrue(
            repository(secondStore, mutableListOf("binding-two"))
                .saveAccepted("player-two", "key-two") is
                RetroAchievementsCredentialLoadResult.Available,
        )
        firstStore.record = copy(
            first,
            ciphertext = secondStore.record.keyCiphertext,
            iv = secondStore.record.keyIv,
        )
        assertSame(RetroAchievementsCredentialLoadResult.Unavailable, firstRepository.load())
    }

    @Test
    fun `complete legacy encrypted records migrate without changing acceptance truth`() {
        for (legacyAccepted in listOf(null, false, true)) {
            val store = FakeStore(
                record = stored(
                    recordPresent = true,
                    username = "legacy-player",
                    ciphertext = "legacy:legacy-key",
                    iv = "legacy-iv",
                    accepted = legacyAccepted,
                ),
            )
            val repository = repository(store, mutableListOf("migrated-binding"))

            assertAvailable(
                repository.load(),
                "legacy-player",
                "legacy-key",
                accepted = legacyAccepted ?: false,
                binding = null,
                legacy = true,
            )
            assertAvailable(
                repository.migrateLegacy(),
                "legacy-player",
                "legacy-key",
                accepted = legacyAccepted ?: false,
                binding = "migrated-binding",
            )
            assertEquals(RetroAchievementsCredentialEncryptedFormat, store.record.format)
        }
    }

    @Test
    fun `legacy activation stays closed when revision migration cannot be verified`() {
        val store = FakeStore(
            record = stored(
                recordPresent = true,
                username = "legacy-player",
                ciphertext = "legacy:legacy-key",
                iv = "legacy-iv",
                accepted = true,
            ),
            writeSucceeds = false,
        )
        val repository = repository(store, mutableListOf("migrated-binding"))

        assertSame(
            RetroAchievementsCredentialLoadResult.Unavailable,
            repository.loadCurrentForActivation(),
        )
        assertAvailable(
            repository.load(),
            "legacy-player",
            "legacy-key",
            accepted = true,
            binding = null,
            legacy = true,
        )
    }

    @Test
    fun `clear and exact snapshot restore require verified durable readback`() {
        val old = currentRecord(username = "old", apiKey = "old-key", binding = "old-binding")
        val store = FakeStore(record = old, clearSucceeds = false)
        val repository = repository(store)
        val snapshot = repository.snapshot()
        assertTrue(snapshot != null)

        assertSame(RetroAchievementsCredentialLoadResult.Unavailable, repository.clear())
        assertTrue(store.record.recordPresent)

        store.clearSucceeds = true
        assertSame(RetroAchievementsCredentialLoadResult.Missing, repository.clear())
        assertFalse(store.record.recordPresent)

        assertTrue(repository.restore(snapshot!!))
        assertAvailable(
            repository.load(),
            "old",
            "old-key",
            accepted = true,
            binding = "old-binding",
        )

        store.restoreSucceeds = false
        assertFalse(repository.restore(snapshot))
        store.snapshotThrows = true
        assertNull(repository.snapshot())
    }

    @Test
    fun `durable consent keeps the prior record until candidate verification succeeds`() {
        val successEvents = mutableListOf<String>()
        val activated = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = true,
            snapshot = { successEvents += "snapshot"; "old-record" },
            clearActive = { successEvents += "clear"; true },
            markConsent = { successEvents += "consent"; true },
            saveCandidate = { successEvents += "save"; "candidate" },
            candidateIsValid = { successEvents += "verify:$it"; true },
            consentStillDurable = { successEvents += "consent-check"; true },
            restorePrevious = { successEvents += "restore:$it"; true },
            closeWithoutPrior = { successEvents += "close"; true },
        )
        assertTrue(activated is RetroAchievementsCredentialTransactionResult.Activated)
        assertEquals(
            listOf("snapshot", "save", "verify:candidate", "consent-check"),
            successEvents,
        )

        val failureEvents = mutableListOf<String>()
        val failed = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = true,
            snapshot = { failureEvents += "snapshot"; "old-record" },
            clearActive = { failureEvents += "clear"; true },
            markConsent = { failureEvents += "consent"; true },
            saveCandidate = { failureEvents += "save"; null as String? },
            candidateIsValid = { failureEvents += "verify"; true },
            consentStillDurable = { failureEvents += "consent-check"; true },
            restorePrevious = { failureEvents += "restore:$it"; true },
            closeWithoutPrior = { failureEvents += "close"; true },
        )
        assertSame(RetroAchievementsCredentialTransactionResult.FailedRestored, failed)
        assertEquals(listOf("snapshot", "save", "restore:old-record"), failureEvents)
    }

    @Test
    fun `absent consent clears orphan before consent and never restores it`() {
        val events = mutableListOf<String>()
        val activated = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = false,
            snapshot = { events += "snapshot"; "orphan" },
            clearActive = { events += "clear"; true },
            markConsent = { events += "consent"; true },
            saveCandidate = { events += "save"; "candidate" },
            candidateIsValid = { events += "verify:$it"; true },
            consentStillDurable = { events += "consent-check"; true },
            restorePrevious = { events += "restore:$it"; true },
            closeWithoutPrior = { events += "close"; true },
        )
        assertTrue(activated is RetroAchievementsCredentialTransactionResult.Activated)
        assertEquals(
            listOf("snapshot", "clear", "consent", "save", "verify:candidate", "consent-check"),
            events,
        )

        val saveFailureEvents = mutableListOf<String>()
        val failed = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = false,
            snapshot = { saveFailureEvents += "snapshot"; "orphan" },
            clearActive = { saveFailureEvents += "clear"; true },
            markConsent = { saveFailureEvents += "consent"; true },
            saveCandidate = { saveFailureEvents += "save"; null as String? },
            candidateIsValid = { saveFailureEvents += "verify"; true },
            consentStillDurable = { saveFailureEvents += "consent-check"; true },
            restorePrevious = { saveFailureEvents += "restore:$it"; true },
            closeWithoutPrior = { saveFailureEvents += "close"; true },
        )
        assertSame(RetroAchievementsCredentialTransactionResult.FailedRestored, failed)
        assertEquals(
            listOf("snapshot", "clear", "consent", "save", "close"),
            saveFailureEvents,
        )

        val consentFailureEvents = mutableListOf<String>()
        val consentFailed = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = false,
            snapshot = { consentFailureEvents += "snapshot"; "orphan" },
            clearActive = { consentFailureEvents += "clear"; true },
            markConsent = { consentFailureEvents += "consent"; false },
            saveCandidate = { consentFailureEvents += "save"; "candidate" },
            candidateIsValid = { true },
            consentStillDurable = { true },
            restorePrevious = { consentFailureEvents += "restore:$it"; true },
            closeWithoutPrior = { consentFailureEvents += "close"; true },
        )
        assertSame(RetroAchievementsCredentialTransactionResult.FailedRestored, consentFailed)
        assertEquals(listOf("snapshot", "clear", "consent", "close"), consentFailureEvents)
    }

    @Test
    fun `transaction reports uncertainty whenever the required recovery cannot be proven`() {
        val snapshotFailures = listOf<() -> String?>(
            { null },
            { error("snapshot unavailable") },
        )
        snapshotFailures.forEach { snapshot ->
            assertSame(
                RetroAchievementsCredentialTransactionResult.FailedUncertain,
                commitAcceptedRetroAchievementsCredential(
                    consentWasDurable = true,
                    snapshot = snapshot,
                    clearActive = { true },
                    markConsent = { true },
                    saveCandidate = { "candidate" },
                    candidateIsValid = { true },
                    consentStillDurable = { true },
                    restorePrevious = { true },
                    closeWithoutPrior = { true },
                ),
            )
        }

        assertSame(
            RetroAchievementsCredentialTransactionResult.FailedUncertain,
            commitAcceptedRetroAchievementsCredential(
                consentWasDurable = false,
                snapshot = { "orphan" },
                clearActive = { false },
                markConsent = { true },
                saveCandidate = { "candidate" },
                candidateIsValid = { true },
                consentStillDurable = { true },
                restorePrevious = { true },
                closeWithoutPrior = { true },
            ),
        )
        assertSame(
            RetroAchievementsCredentialTransactionResult.FailedUncertain,
            commitAcceptedRetroAchievementsCredential(
                consentWasDurable = true,
                snapshot = { "old" },
                clearActive = { true },
                markConsent = { true },
                saveCandidate = { null as String? },
                candidateIsValid = { true },
                consentStillDurable = { true },
                restorePrevious = { false },
                closeWithoutPrior = { true },
            ),
        )
        assertSame(
            RetroAchievementsCredentialTransactionResult.FailedUncertain,
            commitAcceptedRetroAchievementsCredential(
                consentWasDurable = false,
                snapshot = { "orphan" },
                clearActive = { true },
                markConsent = { true },
                saveCandidate = { null as String? },
                candidateIsValid = { true },
                consentStillDurable = { true },
                restorePrevious = { true },
                closeWithoutPrior = { false },
            ),
        )
    }

    @Test
    fun `operation authority rejects stale tokens and linearizes disconnect after activation`() {
        val authority = RetroAchievementsConnectionOperationAuthority()
        val stale = authority.begin()
        val current = authority.begin()
        assertFalse(authority.isCurrent(stale))
        assertNull(authority.runIfCurrent(stale) { true })
        assertTrue(authority.isCurrent(current))
        assertFalse(authority.cancelIfCurrent(stale))

        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val disconnectFinished = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activation = Thread {
            authority.runIfCurrent(current) {
                events += "activate-start"
                activationEntered.countDown()
                assertTrue(releaseActivation.await(2, TimeUnit.SECONDS))
                events += "activate-end"
                true
            }
        }
        activation.start()
        assertTrue(activationEntered.await(2, TimeUnit.SECONDS))

        val disconnect = Thread {
            authority.invalidate {
                events += "disconnect"
                true
            }
            disconnectFinished.countDown()
        }
        disconnect.start()
        assertFalse(
            "disconnect must wait for a current activation",
            disconnectFinished.await(100, TimeUnit.MILLISECONDS),
        )

        releaseActivation.countDown()
        activation.join(2_000)
        disconnect.join(2_000)
        assertEquals(listOf("activate-start", "activate-end", "disconnect"), events)
        assertFalse(authority.isCurrent(current))
    }

    @Test
    fun `runtime durability failure stays latched until verified reconnect or clear`() {
        val runtime = RetroAchievementsCredentialRuntimeState()
        assertTrue(runtime.mayRestore())
        assertFalse(runtime.mayRead())

        runtime.activate("old-revision")
        assertTrue(runtime.mayRead())
        assertTrue(runtime.isActiveRevision("old-revision"))
        runtime.activate("new-revision")
        assertFalse("an old request cannot reject a replacement", runtime.isActiveRevision("old-revision"))
        assertTrue(runtime.isActiveRevision("new-revision"))
        runtime.failClosed()
        assertFalse(runtime.mayRestore())
        assertFalse(runtime.mayRead())

        runtime.close()
        assertFalse("ordinary close must not erase a durability failure", runtime.mayRestore())
        runtime.activate("recovered-revision")
        assertTrue("verified reconnect may recover the latch", runtime.mayRead())

        runtime.recordClear(cleared = false)
        assertFalse(runtime.mayRestore())
        runtime.recordClear(cleared = true)
        assertTrue("verified durable absence may recover the latch", runtime.mayRestore())
        assertFalse(runtime.mayRead())
    }

    @Test
    fun `late rejection from an old key cannot reject a same-username replacement`() {
        val runtime = RetroAchievementsCredentialRuntimeState()
        runtime.activate("old-key-revision")
        assertTrue(runtime.isActiveRevision("old-key-revision"))

        runtime.activate("new-key-revision")

        assertFalse(runtime.isActiveRevision("old-key-revision"))
        assertTrue(runtime.isActiveRevision("new-key-revision"))
    }

    @Test
    fun `credential state string forms redact every secret bearing field`() {
        val secret = "never-print-this"
        val values = listOf(
            stored(
                recordPresent = true,
                format = 1,
                username = secret,
                ciphertext = secret,
                iv = secret,
                accepted = true,
                binding = secret,
            ),
            RetroAchievementsEncryptedCredentialRecord(1, secret, secret, secret, true, secret),
            RetroAchievementsDecryptedCredential(secret, secret, true, secret),
            RetroAchievementsCredentialLoadResult.Available(secret, secret, true, secret, false),
            RetroAchievementsCredentialDurableSnapshot(
                CredentialPreferenceSnapshot(mapOf("secret" to secret)),
            ),
            RetroAchievementsCredentials("player", secret),
        )
        values.forEach { value ->
            assertFalse(value.toString(), value.toString().contains(secret))
        }
    }

    private fun repository(
        store: FakeStore,
        tokens: MutableList<String> = mutableListOf("binding"),
        cipher: FakeCipher = FakeCipher(),
    ): RetroAchievementsCredentialRepository =
        RetroAchievementsCredentialRepository(
            store = store,
            cipher = cipher,
            bindingToken = { tokens.removeAt(0) },
        )

    private fun assertAvailable(
        result: RetroAchievementsCredentialLoadResult,
        username: String,
        apiKey: String,
        accepted: Boolean,
        binding: String?,
        legacy: Boolean = false,
    ) {
        assertTrue(result is RetroAchievementsCredentialLoadResult.Available)
        result as RetroAchievementsCredentialLoadResult.Available
        assertEquals(username, result.username)
        assertEquals(apiKey, result.apiKey)
        assertEquals(accepted, result.accepted)
        assertEquals(binding, result.bindingToken)
        assertEquals(legacy, result.legacy)
    }

    private fun currentRecord(
        format: Int? = RetroAchievementsCredentialEncryptedFormat,
        username: String? = "player",
        apiKey: String = "key",
        ciphertext: String? = null,
        iv: String? = "iv",
        accepted: Boolean? = true,
        binding: String? = "binding",
    ): RetroAchievementsStoredCredentialRecord =
        stored(
            recordPresent = true,
            format = format,
            username = username,
            ciphertext = ciphertext ?: "current:$username:$accepted:$binding:$apiKey",
            iv = iv,
            accepted = accepted,
            binding = binding,
        )

    private fun stored(
        recordPresent: Boolean,
        malformed: Boolean = false,
        format: Int? = null,
        username: String? = null,
        ciphertext: String? = null,
        iv: String? = null,
        accepted: Boolean? = null,
        binding: String? = null,
    ): RetroAchievementsStoredCredentialRecord =
        RetroAchievementsStoredCredentialRecord(
            recordPresent = recordPresent,
            malformed = malformed,
            format = format,
            username = username,
            keyCiphertext = ciphertext,
            keyIv = iv,
            accepted = accepted,
            bindingToken = binding,
        )

    private fun copy(
        source: RetroAchievementsStoredCredentialRecord,
        format: Int? = source.format,
        username: String? = source.username,
        ciphertext: String? = source.keyCiphertext,
        iv: String? = source.keyIv,
        accepted: Boolean? = source.accepted,
        binding: String? = source.bindingToken,
    ): RetroAchievementsStoredCredentialRecord =
        stored(
            recordPresent = source.recordPresent,
            malformed = source.malformed,
            format = format,
            username = username,
            ciphertext = ciphertext,
            iv = iv,
            accepted = accepted,
            binding = binding,
        )

    private class FakeStore(
        var record: RetroAchievementsStoredCredentialRecord = RetroAchievementsStoredCredentialRecord(
            recordPresent = false,
            malformed = false,
            format = null,
            username = null,
            keyCiphertext = null,
            keyIv = null,
            accepted = null,
            bindingToken = null,
        ),
        var writeSucceeds: Boolean = true,
        var clearSucceeds: Boolean = true,
        var restoreSucceeds: Boolean = true,
        private val corruptAfterWrite: Boolean = false,
    ) : RetroAchievementsCredentialRecordStore {
        var writeCalls = 0
        var readThrows = false
        var snapshotThrows = false

        override fun read(): RetroAchievementsStoredCredentialRecord {
            if (readThrows) error("read failed")
            return record
        }

        override fun write(record: RetroAchievementsEncryptedCredentialRecord): Boolean {
            writeCalls += 1
            if (!writeSucceeds) return false
            this.record = storedFrom(record)
            if (corruptAfterWrite) {
                this.record = copyStored(this.record, username = "readback-mismatch")
            }
            return true
        }

        override fun clear(): Boolean {
            if (!clearSucceeds) return false
            record = missingRecord()
            return true
        }

        override fun snapshot(): RetroAchievementsCredentialDurableSnapshot {
            if (snapshotThrows) error("snapshot failed")
            return RetroAchievementsCredentialDurableSnapshot(
                CredentialPreferenceSnapshot(mapOf("record" to record)),
            )
        }

        override fun restore(snapshot: RetroAchievementsCredentialDurableSnapshot): Boolean {
            if (!restoreSucceeds) return false
            record = snapshot.preferences.values["record"] as RetroAchievementsStoredCredentialRecord
            return true
        }

        private fun storedFrom(
            encrypted: RetroAchievementsEncryptedCredentialRecord,
        ): RetroAchievementsStoredCredentialRecord =
            RetroAchievementsStoredCredentialRecord(
                recordPresent = true,
                malformed = false,
                format = encrypted.format,
                username = encrypted.username,
                keyCiphertext = encrypted.keyCiphertext,
                keyIv = encrypted.keyIv,
                accepted = encrypted.accepted,
                bindingToken = encrypted.bindingToken,
            )

        private fun copyStored(
            source: RetroAchievementsStoredCredentialRecord,
            username: String?,
        ): RetroAchievementsStoredCredentialRecord =
            RetroAchievementsStoredCredentialRecord(
                recordPresent = source.recordPresent,
                malformed = source.malformed,
                format = source.format,
                username = username,
                keyCiphertext = source.keyCiphertext,
                keyIv = source.keyIv,
                accepted = source.accepted,
                bindingToken = source.bindingToken,
            )

        private fun missingRecord(): RetroAchievementsStoredCredentialRecord =
            RetroAchievementsStoredCredentialRecord(
                recordPresent = false,
                malformed = false,
                format = null,
                username = null,
                keyCiphertext = null,
                keyIv = null,
                accepted = null,
                bindingToken = null,
            )
    }

    private class FakeCipher : RetroAchievementsCredentialCipher {
        var decryptThrows = false

        override fun encrypt(
            username: String,
            apiKey: String,
            accepted: Boolean,
            bindingToken: String,
        ): RetroAchievementsEncryptedCredentialRecord =
            RetroAchievementsEncryptedCredentialRecord(
                format = RetroAchievementsCredentialEncryptedFormat,
                username = username,
                keyCiphertext = "current:$username:$accepted:$bindingToken:$apiKey",
                keyIv = "iv",
                accepted = accepted,
                bindingToken = bindingToken,
            )

        override fun decrypt(
            record: RetroAchievementsEncryptedCredentialRecord,
        ): RetroAchievementsDecryptedCredential {
            if (decryptThrows) error("decrypt failed")
            val prefix = if (record.format == null) {
                "legacy:"
            } else {
                require(record.format == RetroAchievementsCredentialEncryptedFormat)
                "current:${record.username}:${record.accepted}:${record.bindingToken}:"
            }
            require(record.keyCiphertext.startsWith(prefix))
            return RetroAchievementsDecryptedCredential(
                username = record.username,
                apiKey = record.keyCiphertext.removePrefix(prefix),
                accepted = record.accepted,
                bindingToken = record.bindingToken.takeIf(String::isNotBlank),
            )
        }
    }
}
