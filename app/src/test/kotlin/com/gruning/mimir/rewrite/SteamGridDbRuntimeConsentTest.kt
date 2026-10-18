package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamGridDbRuntimeConsentTest {

    @After
    fun resetGate() {
        MimirSteamGridDbGate.setOpen(false)
    }

    @Test
    fun gateOpenFor_requiresVerifiedAvailableUserRecord() {
        assertFalse(
            steamGridDbGateOpenFor(
                SteamGridDbKeyLoadResult.Missing,
                userRecordVerified = true,
            ),
        )
        assertFalse(
            steamGridDbGateOpenFor(
                SteamGridDbKeyLoadResult.Available("user-key"),
                userRecordVerified = false,
            ),
        )
        assertFalse(
            steamGridDbGateOpenFor(
                SteamGridDbKeyLoadResult.Available(""),
                userRecordVerified = true,
            ),
        )
        assertTrue(
            steamGridDbGateOpenFor(
                SteamGridDbKeyLoadResult.Available("user-key", providerValidated = true),
                userRecordVerified = true,
            ),
        )
    }

    @Test
    fun loadTransitionTable() {
        SteamGridDbKeyRuntimeState().also { runtime ->
            runtime.load { SteamGridDbKeyLoadResult.Missing }
            assertFalse(runtime.userRecordVerified)
            assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))
        }

        SteamGridDbKeyRuntimeState().also { runtime ->
            runtime.load { SteamGridDbKeyLoadResult.Unavailable }
            assertFalse(runtime.userRecordVerified)
            assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))
        }

        SteamGridDbKeyRuntimeState().also { runtime ->
            runtime.load { SteamGridDbKeyLoadResult.Available("legacy-key") }
            assertTrue(runtime.userRecordVerified)
            assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))
        }
    }

    @Test
    fun saveTransitionTable() {
        val runtime = SteamGridDbKeyRuntimeState()

        assertTrue(
            runtime.save("user-key") {
                SteamGridDbKeyLoadResult.Available("user-key", providerValidated = true)
            },
        )
        assertTrue(runtime.userRecordVerified)
        assertTrue(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))

        assertTrue(runtime.save("") { SteamGridDbKeyLoadResult.Missing })
        assertFalse(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))

        assertFalse(runtime.save("next-key") { SteamGridDbKeyLoadResult.Unavailable })
        assertFalse(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))

        assertFalse(runtime.save("mismatch") { SteamGridDbKeyLoadResult.Available("other-key") })
        assertFalse(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))
    }

    @Test
    fun resolvedKeyNeverOpensGateOrSetsVerified() {
        val runtime = SteamGridDbKeyRuntimeState()
        runtime.load { SteamGridDbKeyLoadResult.Missing }

        assertEquals("debug-key", runtime.resolve("debug-key") { SteamGridDbKeyLoadResult.Missing })
        assertFalse(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))

        runtime.load { SteamGridDbKeyLoadResult.Available("user-key", providerValidated = true) }
        assertEquals("user-key", runtime.resolve("debug-key") { SteamGridDbKeyLoadResult.Missing })
        assertTrue(runtime.userRecordVerified)
    }

    @Test
    fun unavailableLoadLatchesClosedUntilExplicitRecovery() {
        val runtime = SteamGridDbKeyRuntimeState()
        assertFalse(runtime.save("user-key") { SteamGridDbKeyLoadResult.Unavailable })
        assertFalse(runtime.userRecordVerified)

        runtime.load { SteamGridDbKeyLoadResult.Available("user-key") }
        assertFalse(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))

        assertTrue(
            runtime.save("user-key") {
                SteamGridDbKeyLoadResult.Available("user-key", providerValidated = true)
            },
        )
        assertTrue(runtime.userRecordVerified)
    }

    @Test
    fun legacyMigrationOpensOnlyAfterAvailableVerification() {
        val store = InMemorySteamGridDbKeyRecordStore(
            values = mapOf("steamgriddb_api_key" to "legacy-key"),
        )
        val repository = SteamGridDbKeyRepository(store, FakeCipher())
        val runtime = SteamGridDbKeyRuntimeState()

        val loaded = runtime.load(repository::load)
        assertTrue(loaded is SteamGridDbKeyLoadResult.Available)
        assertEquals("legacy-key", (loaded as SteamGridDbKeyLoadResult.Available).key)
        assertTrue(runtime.userRecordVerified)
        assertFalse(steamGridDbGateOpenFor(runtime.lastLoadResult, runtime.userRecordVerified))
    }
}

private class InMemorySteamGridDbKeyRecordStore(
    private var values: Map<String, Any?>,
) : SteamGridDbKeyRecordStore {
    override fun read(): SteamGridDbStoredKeyRecord {
        val format = values["steamgriddb_key_format"] as? Int
        return SteamGridDbStoredKeyRecord(
            encryptedRecordPresent = format != null ||
                values.containsKey("steamgriddb_key_cipher") ||
                values.containsKey("steamgriddb_key_iv"),
            format = format,
            ciphertext = values["steamgriddb_key_cipher"] as? String,
            iv = values["steamgriddb_key_iv"] as? String,
            legacyPlaintext = values["steamgriddb_api_key"] as? String,
            providerValidationBinding = values["steamgriddb_key_provider_validation_binding"] as? String,
        )
    }

    override fun writeEncrypted(
        record: SteamGridDbEncryptedKeyRecord,
        providerValidated: Boolean,
    ): Boolean {
        values = values + mapOf(
            "steamgriddb_key_format" to SteamGridDbEncryptedKeyFormat,
            "steamgriddb_key_cipher" to record.ciphertext,
            "steamgriddb_key_iv" to record.iv,
        )
        values = if (providerValidated) {
            values + (
                "steamgriddb_key_provider_validation_binding" to
                    steamGridDbProviderValidationBinding(record)
                )
        } else {
            values - "steamgriddb_key_provider_validation_binding"
        }
        return true
    }

    override fun removeLegacy(): Boolean {
        values = values - "steamgriddb_api_key"
        return true
    }

    override fun clear(): Boolean {
        values = emptyMap()
        return true
    }
}

private class FakeCipher : SteamGridDbKeyCipher {
    override fun encrypt(plaintext: String): SteamGridDbEncryptedKeyRecord =
        SteamGridDbEncryptedKeyRecord(ciphertext = plaintext.reversed(), iv = "iv")

    override fun decrypt(record: SteamGridDbEncryptedKeyRecord): String = record.ciphertext.reversed()
}
