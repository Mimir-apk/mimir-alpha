package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamGridDbKeyRepositoryTest {
    @Test
    fun `missing user record alone permits unpersisted debug fallback`() {
        assertEquals(
            "debug-key",
            resolveSteamGridDbKey(SteamGridDbKeyLoadResult.Missing, " debug-key "),
        )
        assertEquals(
            "",
            resolveSteamGridDbKey(SteamGridDbKeyLoadResult.Unavailable, "debug-key"),
        )
        assertEquals(
            "user-key",
            resolveSteamGridDbKey(
                SteamGridDbKeyLoadResult.Available("user-key", providerValidated = true),
                "debug-key",
            ),
        )
    }

    @Test
    fun `runtime first save failure blocks debug fallback without rereading missing store`() {
        val runtime = SteamGridDbKeyRuntimeState()
        var readCalls = 0

        assertFalse(runtime.save("user-key") { SteamGridDbKeyLoadResult.Unavailable })
        assertEquals(
            "",
            runtime.resolve("debug-key") {
                readCalls += 1
                SteamGridDbKeyLoadResult.Missing
            },
        )
        assertEquals(0, readCalls)
        assertEquals("", runtime.keyForUi)
    }

    @Test
    fun `runtime failure suppresses a previously cached key`() {
        val runtime = SteamGridDbKeyRuntimeState()
        assertTrue(
            runtime.save("first-key") {
                SteamGridDbKeyLoadResult.Available("first-key", providerValidated = true)
            },
        )
        assertEquals("first-key", runtime.resolve("debug-key") { error("must not read") })

        assertFalse(runtime.save("second-key") { SteamGridDbKeyLoadResult.Unavailable })

        assertEquals("", runtime.keyForUi)
        assertEquals("", runtime.resolve("debug-key") { error("must stay latched") })
    }

    @Test
    fun `runtime latch survives ordinary load and resolve`() {
        val runtime = SteamGridDbKeyRuntimeState()
        var readCalls = 0
        assertFalse(runtime.save("key") { SteamGridDbKeyLoadResult.Unavailable })

        assertSame(
            SteamGridDbKeyLoadResult.Unavailable,
            runtime.load {
                readCalls += 1
                SteamGridDbKeyLoadResult.Missing
            },
        )
        assertEquals(
            "",
            runtime.resolve("debug-key") {
                readCalls += 1
                SteamGridDbKeyLoadResult.Missing
            },
        )
        assertEquals(0, readCalls)
    }

    @Test
    fun `runtime latch resets only on explicit successful save or clear`() {
        val runtime = SteamGridDbKeyRuntimeState()
        assertFalse(runtime.save("key") { SteamGridDbKeyLoadResult.Unavailable })
        assertFalse(runtime.save("key") { SteamGridDbKeyLoadResult.Unavailable })
        assertEquals("", runtime.resolve("debug-key") { error("must stay latched") })

        assertTrue(
            runtime.save("key") {
                SteamGridDbKeyLoadResult.Available("key", providerValidated = true)
            },
        )
        assertEquals("key", runtime.resolve("debug-key") { error("cached key wins") })

        assertFalse(runtime.save("replacement") { SteamGridDbKeyLoadResult.Unavailable })
        assertTrue(runtime.save("") { SteamGridDbKeyLoadResult.Missing })
        assertEquals(
            "debug-key",
            runtime.resolve("debug-key") { SteamGridDbKeyLoadResult.Missing },
        )
    }

    @Test
    fun `false encrypted write rolls the live preference map back to missing`() {
        assertFalseCommitRollsBack(
            initial = emptyMap(),
            mutated = mapOf(
                "steamgriddb_key_format" to 1,
                "steamgriddb_key_cipher" to "ciphertext",
                "steamgriddb_key_iv" to "iv",
            ),
        )
    }

    @Test
    fun `false legacy deletion restores plaintext in the live map for fail-closed retry`() {
        assertFalseCommitRollsBack(
            initial = mapOf(
                "steamgriddb_api_key" to "legacy-key",
                "steamgriddb_key_format" to 1,
                "steamgriddb_key_cipher" to "ciphertext",
                "steamgriddb_key_iv" to "iv",
            ),
            mutated = mapOf(
                "steamgriddb_key_format" to 1,
                "steamgriddb_key_cipher" to "ciphertext",
                "steamgriddb_key_iv" to "iv",
            ),
        )
    }

    @Test
    fun `false clear restores encrypted state in the live map and cannot enable fallback`() {
        assertFalseCommitRollsBack(
            initial = mapOf(
                "steamgriddb_key_format" to 1,
                "steamgriddb_key_cipher" to "ciphertext",
                "steamgriddb_key_iv" to "iv",
            ),
            mutated = emptyMap(),
        )
    }

    @Test
    fun `legacy key is removed only after encrypted write and persisted decrypt verification`() {
        val events = mutableListOf<String>()
        val store = FakeStore(legacy = " old-key ", events = events)
        val repository = SteamGridDbKeyRepository(store, FakeCipher(events = events))

        val result = repository.load()

        assertAvailable("old-key", result)
        assertEquals(
            listOf(
                "read",
                "encrypt:old-key",
                "decrypt:ct:old-key",
                "write-encrypted",
                "read",
                "decrypt:ct:old-key",
                "remove-legacy",
            ),
            events,
        )
        assertEquals(null, store.legacy)
        assertEquals("ct:old-key", store.ciphertext)
    }

    @Test
    fun `encryption failure preserves legacy and fails closed`() {
        val store = FakeStore(legacy = "old-key")
        val repository = SteamGridDbKeyRepository(store, FakeCipher(failEncrypt = true))

        assertSame(SteamGridDbKeyLoadResult.Unavailable, repository.load())
        assertEquals("old-key", store.legacy)
        assertFalse(store.encryptedPresent)
    }

    @Test
    fun `durable write failure preserves legacy and fails closed`() {
        val store = FakeStore(legacy = "old-key", failWrite = true)
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        assertSame(SteamGridDbKeyLoadResult.Unavailable, repository.load())
        assertEquals("old-key", store.legacy)
        assertFalse(store.encryptedPresent)
    }

    @Test
    fun `persisted verification failure preserves legacy and fails closed`() {
        val store = FakeStore(legacy = "old-key", corruptAfterWrite = true)
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        assertSame(SteamGridDbKeyLoadResult.Unavailable, repository.load())
        assertEquals("old-key", store.legacy)
        assertTrue(store.encryptedPresent)
    }

    @Test
    fun `interrupted legacy cleanup fails closed and retries from verified encrypted authority`() {
        val store = FakeStore(legacy = "old-key", failRemoveLegacyCount = 1)
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        assertSame(SteamGridDbKeyLoadResult.Unavailable, repository.load())
        assertEquals("old-key", store.legacy)

        assertAvailable("old-key", repository.load())
        assertEquals(null, store.legacy)
        assertEquals(2, store.removeLegacyCalls)
    }

    @Test
    fun `replacement cannot report success while legacy plaintext cleanup fails`() {
        val store = FakeStore(legacy = "old-key", failRemoveLegacyCount = 1)
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        assertSame(SteamGridDbKeyLoadResult.Unavailable, repository.save("new-key"))
        assertEquals("old-key", store.legacy)

        assertAvailable("new-key", repository.load())
        assertEquals(null, store.legacy)
    }

    @Test
    fun `partial encrypted state never rolls back to legacy plaintext`() {
        val store = FakeStore(
            legacy = "legacy-key",
            encryptedPresent = true,
            ciphertext = "ct:legacy-key",
            iv = null,
        )

        assertSame(
            SteamGridDbKeyLoadResult.Unavailable,
            SteamGridDbKeyRepository(store, FakeCipher()).load(),
        )
        assertEquals("legacy-key", store.legacy)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `corrupt encrypted state never rolls back to legacy or debug fallback`() {
        val store = FakeStore(
            legacy = "legacy-key",
            encryptedPresent = true,
            ciphertext = "corrupt",
            iv = "iv",
        )
        val loaded = SteamGridDbKeyRepository(store, FakeCipher()).load()

        assertSame(SteamGridDbKeyLoadResult.Unavailable, loaded)
        assertEquals("", resolveSteamGridDbKey(loaded, "debug-key"))
        assertEquals("legacy-key", store.legacy)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `replacement is trimmed encrypted verified and legacy cleanup follows`() {
        val store = FakeStore(legacy = "previous-legacy")
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        val result = repository.save(" replacement ")

        assertAvailable("replacement", result)
        assertEquals("ct:replacement", store.ciphertext)
        assertEquals(null, store.legacy)
    }

    @Test
    fun `provider validation is bound to the exact encrypted record`() {
        val store = FakeStore()
        val repository = SteamGridDbKeyRepository(store, FakeCipher())

        val accepted = repository.save("accepted-key", providerValidated = true)
        assertTrue((accepted as SteamGridDbKeyLoadResult.Available).providerValidated)
        assertEquals(
            "accepted-key",
            resolveSteamGridDbKey(accepted, buildTimeDebugKey = ""),
        )

        val replacement = repository.save("replacement-key")
        assertFalse((replacement as SteamGridDbKeyLoadResult.Available).providerValidated)
        assertEquals("", resolveSteamGridDbKey(replacement, buildTimeDebugKey = ""))
    }

    @Test
    fun `existing encrypted key without provider marker remains editable but disconnected`() {
        val store = FakeStore(
            encryptedPresent = true,
            ciphertext = "ct:old-key",
            iv = "iv",
        )

        val loaded = SteamGridDbKeyRepository(store, FakeCipher()).load()

        assertAvailable("old-key", loaded)
        assertFalse((loaded as SteamGridDbKeyLoadResult.Available).providerValidated)
        assertEquals("", resolveSteamGridDbKey(loaded, buildTimeDebugKey = ""))
    }

    @Test
    fun `blank replacement clears every record`() {
        val store = FakeStore(
            legacy = "legacy",
            encryptedPresent = true,
            ciphertext = "ct:key",
            iv = "iv",
        )

        assertSame(
            SteamGridDbKeyLoadResult.Missing,
            SteamGridDbKeyRepository(store, FakeCipher()).save("  "),
        )
        assertFalse(store.encryptedPresent)
        assertEquals(null, store.legacy)
    }

    @Test
    fun `clear failure is surfaced and existing state is retained`() {
        val store = FakeStore(
            encryptedPresent = true,
            ciphertext = "ct:key",
            iv = "iv",
            failClear = true,
        )

        assertSame(
            SteamGridDbKeyLoadResult.Unavailable,
            SteamGridDbKeyRepository(store, FakeCipher()).save(""),
        )
        assertTrue(store.encryptedPresent)
    }

    @Test
    fun `all secret-bearing diagnostic forms are redacted`() {
        val secret = "do-not-print"
        val stored = SteamGridDbStoredKeyRecord(
            encryptedRecordPresent = true,
            format = SteamGridDbEncryptedKeyFormat,
            ciphertext = secret,
            iv = secret,
            legacyPlaintext = secret,
        )
        val encrypted = SteamGridDbEncryptedKeyRecord(secret, secret)
        val available = SteamGridDbKeyLoadResult.Available(secret)
        val preferenceSnapshot = SteamGridDbPreferenceSnapshot(mapOf("key" to secret))
        val runtime = SteamGridDbKeyRuntimeState().apply {
            save(secret) { SteamGridDbKeyLoadResult.Available(secret) }
        }

        assertFalse(stored.toString().contains(secret))
        assertFalse(encrypted.toString().contains(secret))
        assertFalse(available.toString().contains(secret))
        assertFalse(preferenceSnapshot.toString().contains(secret))
        assertFalse(runtime.toString().contains(secret))
    }

    private fun assertFalseCommitRollsBack(
        initial: Map<String, Any?>,
        mutated: Map<String, Any?>,
    ) {
        var liveMap = initial.toMap()
        val rollback = SteamGridDbPreferenceRollback(
            snapshot = { SteamGridDbPreferenceSnapshot(liveMap.toMap()) },
            restore = { liveMap = it.values.toMap() },
        )

        val committed = rollback.commit {
            // This models SharedPreferencesImpl.commitToMemory(): live state changes first, then disk fails.
            liveMap = mutated.toMap()
            false
        }

        assertFalse(committed)
        assertEquals(initial, liveMap)
    }

    private fun assertAvailable(expected: String, result: SteamGridDbKeyLoadResult) {
        assertTrue(result is SteamGridDbKeyLoadResult.Available)
        assertEquals(expected, (result as SteamGridDbKeyLoadResult.Available).key)
    }

    private class FakeCipher(
        private val failEncrypt: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : SteamGridDbKeyCipher {
        override fun encrypt(plaintext: String): SteamGridDbEncryptedKeyRecord {
            events?.add("encrypt:$plaintext")
            if (failEncrypt) error("encryption unavailable")
            return SteamGridDbEncryptedKeyRecord("ct:$plaintext", "iv")
        }

        override fun decrypt(record: SteamGridDbEncryptedKeyRecord): String {
            events?.add("decrypt:${record.ciphertext}")
            check(record.ciphertext.startsWith("ct:")) { "corrupt ciphertext" }
            return record.ciphertext.removePrefix("ct:")
        }
    }

    private class FakeStore(
        var legacy: String? = null,
        var encryptedPresent: Boolean = false,
        var format: Int? = if (encryptedPresent) SteamGridDbEncryptedKeyFormat else null,
        var ciphertext: String? = null,
        var iv: String? = if (encryptedPresent) "iv" else null,
        var providerValidationBinding: String? = null,
        private val failWrite: Boolean = false,
        private val corruptAfterWrite: Boolean = false,
        private var failRemoveLegacyCount: Int = 0,
        private val failClear: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : SteamGridDbKeyRecordStore {
        var writeCalls = 0
        var removeLegacyCalls = 0

        override fun read(): SteamGridDbStoredKeyRecord {
            events?.add("read")
            return SteamGridDbStoredKeyRecord(
                encryptedRecordPresent = encryptedPresent,
                format = format,
                ciphertext = ciphertext,
                iv = iv,
                legacyPlaintext = legacy,
                providerValidationBinding = providerValidationBinding,
            )
        }

        override fun writeEncrypted(
            record: SteamGridDbEncryptedKeyRecord,
            providerValidated: Boolean,
        ): Boolean {
            events?.add("write-encrypted")
            writeCalls += 1
            if (failWrite) return false
            encryptedPresent = true
            format = SteamGridDbEncryptedKeyFormat
            ciphertext = if (corruptAfterWrite) "corrupt" else record.ciphertext
            iv = record.iv
            providerValidationBinding = if (providerValidated) {
                steamGridDbProviderValidationBinding(record)
            } else {
                null
            }
            return true
        }

        override fun removeLegacy(): Boolean {
            events?.add("remove-legacy")
            removeLegacyCalls += 1
            if (failRemoveLegacyCount > 0) {
                failRemoveLegacyCount -= 1
                return false
            }
            legacy = null
            return true
        }

        override fun clear(): Boolean {
            if (failClear) return false
            legacy = null
            encryptedPresent = false
            format = null
            ciphertext = null
            iv = null
            providerValidationBinding = null
            return true
        }
    }
}
