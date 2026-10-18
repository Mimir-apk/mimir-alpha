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

class ArtworkCredentialRepositoryTest {
    @Test
    fun `save encrypts self verifies commits and verifies exact persisted values`() {
        val store = FakeArtworkCredentialStore()
        val repository = repository(store, tokens = mutableListOf("binding-1"))

        val saved = repository.save("  member  ", "member-password")

        assertAvailable(saved, "member", "member-password", "binding-1")
        assertEquals(1, store.writeCalls)
        assertNull(store.record.username?.takeIf { it == "member-password" })
        assertEquals(ArtworkCredentialEncryptedFormat, store.record.format)
        assertEquals("binding-1", store.record.bindingToken)
        assertAvailable(repository.load(), "member", "member-password", "binding-1")
    }

    @Test
    fun `save refuses a false commit and a mismatched persisted readback`() {
        val falseCommitStore = FakeArtworkCredentialStore(writeSucceeds = false)
        assertSame(
            ArtworkCredentialLoadResult.Unavailable,
            repository(falseCommitStore).save("member", "password"),
        )

        val corruptReadbackStore = FakeArtworkCredentialStore(corruptAfterWrite = true)
        assertSame(
            ArtworkCredentialLoadResult.Unavailable,
            repository(corruptReadbackStore).save("member", "password"),
        )
    }

    @Test
    fun `partial unknown and corrupt records fail closed instead of looking absent`() {
        val cases = listOf(
            ArtworkStoredCredentialRecord(true, null, "member", null, "iv", null),
            ArtworkStoredCredentialRecord(true, 99, "member", "ct:password", "iv", "binding"),
            ArtworkStoredCredentialRecord(
                true,
                ArtworkCredentialEncryptedFormat,
                "member",
                "broken",
                "iv",
                "binding",
            ),
        )

        cases.forEach { record ->
            val loaded = repository(FakeArtworkCredentialStore(record = record)).load()
            assertSame(record.toString(), ArtworkCredentialLoadResult.Unavailable, loaded)
        }
    }

    @Test
    fun `current format authenticates username binding and cross record ciphertext`() {
        val firstStore = FakeArtworkCredentialStore()
        val firstRepository = repository(firstStore, mutableListOf("binding-one"))
        assertTrue(
            firstRepository.save("member-one", "password-one") is
                ArtworkCredentialLoadResult.Available,
        )
        val first = firstStore.record

        firstStore.record = copyRecord(first, username = "tampered-member")
        assertSame(ArtworkCredentialLoadResult.Unavailable, firstRepository.load())

        firstStore.record = copyRecord(first, username = " member-one ")
        assertSame(ArtworkCredentialLoadResult.Unavailable, firstRepository.load())

        firstStore.record = copyRecord(first, bindingToken = "tampered-binding")
        assertSame(ArtworkCredentialLoadResult.Unavailable, firstRepository.load())

        val secondStore = FakeArtworkCredentialStore()
        assertTrue(
            repository(secondStore, mutableListOf("binding-two"))
                .save("member-two", "password-two") is ArtworkCredentialLoadResult.Available,
        )
        firstStore.record = copyRecord(
            first,
            passwordCiphertext = secondStore.record.passwordCiphertext,
            passwordIv = secondStore.record.passwordIv,
        )
        assertSame(ArtworkCredentialLoadResult.Unavailable, firstRepository.load())
    }

    @Test
    fun `complete legacy encrypted record stays readable and receives a binding before activation`() {
        val store = FakeArtworkCredentialStore(
            record = ArtworkStoredCredentialRecord(
                recordPresent = true,
                format = null,
                username = "legacy-member",
                passwordCiphertext = "ct:legacy-password",
                passwordIv = "iv",
                bindingToken = null,
            ),
        )
        val repository = repository(store, tokens = mutableListOf("migrated-binding"))

        assertAvailable(repository.load(), "legacy-member", "legacy-password", null)
        assertAvailable(
            repository.ensureBinding(),
            "legacy-member",
            "legacy-password",
            "migrated-binding",
        )
        assertEquals(ArtworkCredentialEncryptedFormat, store.record.format)
        assertEquals("migrated-binding", store.record.bindingToken)
    }

    @Test
    fun `clear succeeds only after persisted absence is re-read`() {
        val store = FakeArtworkCredentialStore(
            record = currentRecord(),
            clearSucceeds = false,
        )
        assertSame(ArtworkCredentialLoadResult.Unavailable, repository(store).clear())
        assertTrue(store.record.recordPresent)

        store.clearSucceeds = true
        assertSame(ArtworkCredentialLoadResult.Missing, repository(store).clear())
        assertFalse(store.record.recordPresent)
    }

    @Test
    fun `runtime durability failure latches ordinary reads and binding migration closed`() {
        val runtime = ArtworkCredentialRuntimeState()
        var reads = 0

        assertSame(
            ArtworkCredentialLoadResult.Unavailable,
            runtime.load {
                reads += 1
                ArtworkCredentialLoadResult.Unavailable
            },
        )
        assertSame(
            ArtworkCredentialLoadResult.Unavailable,
            runtime.load {
                reads += 1
                available()
            },
        )
        assertSame(
            ArtworkCredentialLoadResult.Unavailable,
            runtime.ensureBinding {
                reads += 1
                available()
            },
        )
        assertEquals(1, reads)

        val recovered = runtime.save { available() }
        assertTrue(recovered is ArtworkCredentialLoadResult.Available)
        assertTrue(runtime.load { available() } is ArtworkCredentialLoadResult.Available)
    }

    @Test
    fun `failed preference commit restores the pre-edit live map`() {
        val liveMap = mutableMapOf<String, Any?>("credential" to "old-secret", "flag" to false)
        val rollback = CredentialPreferenceRollback(
            snapshot = { CredentialPreferenceSnapshot(liveMap.toMap()) },
            restore = { before ->
                liveMap.clear()
                liveMap.putAll(before.values)
            },
        )

        val committed = rollback.commit {
            liveMap["credential"] = "candidate-secret"
            liveMap["flag"] = true
            false
        }

        assertFalse(committed)
        assertEquals("old-secret", liveMap["credential"])
        assertEquals(false, liveMap["flag"])
        assertFalse(CredentialPreferenceSnapshot(liveMap).toString().contains("old-secret"))
    }

    @Test
    fun `connect activates the exact binding last and fails closed on every downstream failure`() {
        val successEvents = mutableListOf<String>()
        assertSame(
            ArtworkConnectionCommitResult.Activated,
            commitValidatedArtworkConnection(
                snapshot = { successEvents += "snapshot"; connectionSnapshot() },
                failClosed = { successEvents += "close" },
                saveCredential = { successEvents += "credential"; "revision" },
                markConnectedServicesOptedIn = { successEvents += "consent"; true },
                activateProvider = { successEvents += "activate:$it"; true },
                restorePrevious = { successEvents += "restore"; true },
            ),
        )
        assertEquals(
            listOf("snapshot", "close", "credential", "consent", "activate:revision"),
            successEvents,
        )

        val failedConsentEvents = mutableListOf<String>()
        assertSame(
            ArtworkConnectionCommitResult.FailedRestored,
            commitValidatedArtworkConnection(
                snapshot = { failedConsentEvents += "snapshot"; connectionSnapshot() },
                failClosed = { failedConsentEvents += "close" },
                saveCredential = { failedConsentEvents += "credential"; "revision" },
                markConnectedServicesOptedIn = { failedConsentEvents += "consent"; false },
                activateProvider = { failedConsentEvents += "activate"; true },
                restorePrevious = { failedConsentEvents += "restore"; true },
            ),
        )
        assertEquals(
            listOf("snapshot", "close", "credential", "consent", "close", "restore"),
            failedConsentEvents,
        )

        val failedActivationEvents = mutableListOf<String>()
        assertSame(
            ArtworkConnectionCommitResult.FailedRestored,
            commitValidatedArtworkConnection(
                snapshot = { failedActivationEvents += "snapshot"; connectionSnapshot() },
                failClosed = { failedActivationEvents += "close" },
                saveCredential = { failedActivationEvents += "credential"; "revision" },
                markConnectedServicesOptedIn = { failedActivationEvents += "consent"; true },
                activateProvider = { failedActivationEvents += "activate:$it"; false },
                restorePrevious = { failedActivationEvents += "restore"; true },
            ),
        )
        assertEquals(
            listOf(
                "snapshot",
                "close",
                "credential",
                "consent",
                "activate:revision",
                "close",
                "restore",
            ),
            failedActivationEvents,
        )

        val failedCredentialEvents = mutableListOf<String>()
        assertSame(
            ArtworkConnectionCommitResult.FailedRestored,
            commitValidatedArtworkConnection(
                snapshot = { failedCredentialEvents += "snapshot"; connectionSnapshot() },
                failClosed = { failedCredentialEvents += "close" },
                saveCredential = { failedCredentialEvents += "credential"; null },
                markConnectedServicesOptedIn = { failedCredentialEvents += "consent"; true },
                activateProvider = { failedCredentialEvents += "activate"; true },
                restorePrevious = { failedCredentialEvents += "restore"; true },
            ),
        )
        assertEquals(
            listOf("snapshot", "close", "credential", "close", "restore"),
            failedCredentialEvents,
        )

        val uncertainEvents = mutableListOf<String>()
        assertSame(
            ArtworkConnectionCommitResult.FailedUncertain,
            commitValidatedArtworkConnection(
                snapshot = { uncertainEvents += "snapshot"; connectionSnapshot() },
                failClosed = { uncertainEvents += "close" },
                saveCredential = { uncertainEvents += "credential"; "revision" },
                markConnectedServicesOptedIn = { uncertainEvents += "consent"; false },
                activateProvider = { uncertainEvents += "activate"; true },
                restorePrevious = { uncertainEvents += "restore"; false },
            ),
        )
        assertEquals(
            listOf("snapshot", "close", "credential", "consent", "close", "restore", "close"),
            uncertainEvents,
        )

        for (snapshotFailure in listOf<() -> ArtworkConnectionDurableSnapshot?>(
            { null },
            { error("snapshot unavailable") },
        )) {
            val snapshotFailureEvents = mutableListOf<String>()
            assertSame(
                ArtworkConnectionCommitResult.FailedUncertain,
                commitValidatedArtworkConnection(
                    snapshot = {
                        snapshotFailureEvents += "snapshot"
                        snapshotFailure()
                    },
                    failClosed = { snapshotFailureEvents += "close" },
                    saveCredential = { snapshotFailureEvents += "credential"; "revision" },
                    markConnectedServicesOptedIn = {
                        snapshotFailureEvents += "consent"
                        true
                    },
                    activateProvider = { snapshotFailureEvents += "activate"; true },
                    restorePrevious = { snapshotFailureEvents += "restore"; true },
                ),
            )
            assertEquals(listOf("snapshot", "close"), snapshotFailureEvents)
        }
    }

    @Test
    fun `disconnect closes first and attempts both durable removals`() {
        for (providerResult in listOf(false, true)) {
            for (credentialResult in listOf(false, true)) {
                val events = mutableListOf<String>()
                assertEquals(
                    providerResult && credentialResult,
                    disconnectArtworkConnection(
                        failClosed = { events += "close" },
                        deactivateProvider = { events += "provider"; providerResult },
                        clearCredential = { events += "credential"; credentialResult },
                    ),
                )
                assertEquals(
                    listOf("close", "provider", "credential"),
                    events,
                )
            }
        }
    }

    @Test
    fun `failed activation restores the exact prior credential and provider snapshots`() {
        var credentialState = "old-credential-record"
        var providerState = "old-provider-record"
        val result = commitValidatedArtworkConnection(
            snapshot = { connectionSnapshot(credentialState, providerState) },
            failClosed = {},
            saveCredential = {
                credentialState = "candidate-credential-record"
                "candidate-binding"
            },
            markConnectedServicesOptedIn = { true },
            activateProvider = {
                providerState = "candidate-provider-record"
                false
            },
            restorePrevious = { previous ->
                credentialState = previous.credential.preferences.values["state"] as String
                providerState = previous.provider.preferences.values["state"] as String
                true
            },
        )

        assertSame(ArtworkConnectionCommitResult.FailedRestored, result)
        assertEquals("old-credential-record", credentialState)
        assertEquals("old-provider-record", providerState)
    }

    @Test
    fun `operation authority rejects stale validation and linearizes activation before cancel cleanup`() {
        val authority = ArtworkConnectionOperationAuthority()
        val stale = authority.begin()
        authority.invalidate { true }
        assertNull(authority.runIfCurrent(stale) { true })

        val current = authority.begin()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
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

        val cancel = Thread {
            authority.invalidate {
                events += "cancel-cleanup"
                true
            }
            cancelFinished.countDown()
        }
        cancel.start()
        assertFalse("cancel must wait for the current activation", cancelFinished.await(100, TimeUnit.MILLISECONDS))

        releaseActivation.countDown()
        activation.join(2_000)
        cancel.join(2_000)
        assertEquals(
            listOf("activate-start", "activate-end", "cancel-cleanup"),
            events,
        )
        assertNull(authority.runIfCurrent(current) { true })
    }

    @Test
    fun `secret bearing state string forms stay redacted`() {
        val secret = "never-print-this"
        val values = listOf(
            ArtworkStoredCredentialRecord(true, 1, "member", secret, secret, secret),
            ArtworkEncryptedCredentialRecord(
                ArtworkCredentialEncryptedFormat,
                "member",
                secret,
                secret,
                "binding",
            ),
            ArtworkDecryptedCredential("member", secret, "binding"),
            ArtworkCredentialLoadResult.Available("member", secret, "binding"),
        )
        values.forEach { value ->
            assertFalse(value.toString(), value.toString().contains(secret))
        }
    }

    private fun repository(
        store: FakeArtworkCredentialStore,
        tokens: MutableList<String> = mutableListOf("binding"),
    ): ArtworkCredentialRepository =
        ArtworkCredentialRepository(
            store = store,
            cipher = FakeArtworkCredentialCipher(),
            bindingToken = { tokens.removeAt(0) },
        )

    private fun currentRecord(): ArtworkStoredCredentialRecord =
        ArtworkStoredCredentialRecord(
            recordPresent = true,
            format = ArtworkCredentialEncryptedFormat,
            username = "member",
            passwordCiphertext = "ct:member:binding:password",
            passwordIv = "iv",
            bindingToken = "binding",
        )

    private fun connectionSnapshot(
        credentialState: String? = null,
        providerState: String? = null,
    ): ArtworkConnectionDurableSnapshot =
        ArtworkConnectionDurableSnapshot(
            credential = ArtworkCredentialDurableSnapshot(
                CredentialPreferenceSnapshot(
                    credentialState?.let { mapOf("state" to it) }.orEmpty(),
                ),
            ),
            provider = ConnectedArtDurableSnapshot(
                CredentialPreferenceSnapshot(
                    providerState?.let { mapOf("state" to it) }.orEmpty(),
                ),
            ),
        )

    private fun copyRecord(
        source: ArtworkStoredCredentialRecord,
        format: Int? = source.format,
        username: String? = source.username,
        passwordCiphertext: String? = source.passwordCiphertext,
        passwordIv: String? = source.passwordIv,
        bindingToken: String? = source.bindingToken,
    ): ArtworkStoredCredentialRecord =
        ArtworkStoredCredentialRecord(
            recordPresent = source.recordPresent,
            format = format,
            username = username,
            passwordCiphertext = passwordCiphertext,
            passwordIv = passwordIv,
            bindingToken = bindingToken,
        )

    private fun available(): ArtworkCredentialLoadResult.Available =
        ArtworkCredentialLoadResult.Available("member", "password", "binding")

    private fun assertAvailable(
        result: ArtworkCredentialLoadResult,
        username: String,
        password: String,
        binding: String?,
    ) {
        assertTrue(result is ArtworkCredentialLoadResult.Available)
        result as ArtworkCredentialLoadResult.Available
        assertEquals(username, result.username)
        assertEquals(password, result.password)
        assertEquals(binding, result.bindingToken)
    }

    private class FakeArtworkCredentialCipher : ArtworkCredentialCipher {
        override fun encrypt(
            username: String,
            password: String,
            bindingToken: String,
        ): ArtworkEncryptedCredentialRecord =
            ArtworkEncryptedCredentialRecord(
                format = ArtworkCredentialEncryptedFormat,
                username = username,
                passwordCiphertext = "ct:$username:$bindingToken:$password",
                passwordIv = "iv",
                bindingToken = bindingToken,
            )

        override fun decrypt(record: ArtworkEncryptedCredentialRecord): ArtworkDecryptedCredential {
            val prefix = if (record.format == null) {
                "ct:"
            } else {
                require(record.format == ArtworkCredentialEncryptedFormat)
                "ct:${record.username}:${record.bindingToken}:"
            }
            require(record.passwordCiphertext.startsWith(prefix))
            return ArtworkDecryptedCredential(
                username = record.username,
                password = record.passwordCiphertext.removePrefix(prefix),
                bindingToken = record.bindingToken,
            )
        }
    }

    private class FakeArtworkCredentialStore(
        var record: ArtworkStoredCredentialRecord = ArtworkStoredCredentialRecord(
            false,
            null,
            null,
            null,
            null,
            null,
        ),
        var writeSucceeds: Boolean = true,
        var clearSucceeds: Boolean = true,
        private val corruptAfterWrite: Boolean = false,
    ) : ArtworkCredentialRecordStore {
        var writeCalls = 0

        override fun read(): ArtworkStoredCredentialRecord = record

        override fun write(record: ArtworkEncryptedCredentialRecord): Boolean {
            writeCalls += 1
            if (!writeSucceeds) return false
            this.record = ArtworkStoredCredentialRecord(
                recordPresent = true,
                format = record.format,
                username = record.username,
                passwordCiphertext = if (corruptAfterWrite) "broken" else record.passwordCiphertext,
                passwordIv = record.passwordIv,
                bindingToken = record.bindingToken,
            )
            return true
        }

        override fun clear(): Boolean {
            if (!clearSucceeds) return false
            record = ArtworkStoredCredentialRecord(false, null, null, null, null, null)
            return true
        }

        override fun snapshot(): ArtworkCredentialDurableSnapshot =
            ArtworkCredentialDurableSnapshot(
                CredentialPreferenceSnapshot(
                    buildMap {
                        put("present", record.recordPresent)
                        record.format?.let { put("format", it) }
                        record.username?.let { put("username", it) }
                        record.passwordCiphertext?.let { put("ciphertext", it) }
                        record.passwordIv?.let { put("iv", it) }
                        record.bindingToken?.let { put("binding", it) }
                    },
                ),
            )

        override fun restore(snapshot: ArtworkCredentialDurableSnapshot): Boolean {
            val values = snapshot.preferences.values
            record = ArtworkStoredCredentialRecord(
                recordPresent = values["present"] as? Boolean ?: false,
                format = values["format"] as? Int,
                username = values["username"] as? String,
                passwordCiphertext = values["ciphertext"] as? String,
                passwordIv = values["iv"] as? String,
                bindingToken = values["binding"] as? String,
            )
            return true
        }
    }
}
