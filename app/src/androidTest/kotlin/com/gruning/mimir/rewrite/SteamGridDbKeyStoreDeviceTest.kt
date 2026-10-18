package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamGridDbKeyStoreDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetBefore() {
        rawPreferences().edit().clear().commit()
        SteamGridDbKeyStore.load(context)
    }

    @After
    fun resetAfter() {
        SteamGridDbKeyStore.clear(context)
    }

    @Test
    fun keyRoundTripsThroughKeystoreWithoutPlaintextAtRest() {
        val secret = "device-proof-secret-391"

        assertTrue(SteamGridDbKeyStore.setValidatedKey(context, secret))
        SteamGridDbKeyStore.load(context)

        assertEquals(secret, SteamGridDbKeyStore.key.value)
        assertEquals(secret, SteamGridDbKeyStore.resolvedKey(context))
        assertNoPlaintext(secret)
        assertFalse(rawPreferences().contains(LegacyKey))
        assertTrue(rawPreferences().contains(CiphertextKey))
        assertTrue(rawPreferences().contains(IvKey))
        assertTrue(rawPreferences().contains(ProviderValidationBindingKey))
    }

    @Test
    fun legacyPlaintextMigratesOnlyToEncryptedFields() {
        val secret = "legacy-device-secret-742"
        rawPreferences().edit().putString(LegacyKey, " $secret ").commit()

        SteamGridDbKeyStore.load(context)

        assertEquals(secret, SteamGridDbKeyStore.key.value)
        assertEquals("", SteamGridDbKeyStore.resolvedKey(context))
        assertFalse(SteamGridDbKeyStore.providerValidated.value)
        assertFalse(rawPreferences().contains(LegacyKey))
        assertNoPlaintext(secret)
    }

    @Test
    fun corruptEncryptedRecordFailsClosedEvenWhenDebugFallbackExists() {
        val secret = "corruption-device-secret-119"
        assertTrue(SteamGridDbKeyStore.setValidatedKey(context, secret))
        rawPreferences().edit().putString(CiphertextKey, "not-base64!").commit()

        SteamGridDbKeyStore.load(context)

        assertEquals("", SteamGridDbKeyStore.key.value)
        assertEquals("", SteamGridDbKeyStore.resolvedKey(context))
        assertNoPlaintext(secret)
    }

    private fun assertNoPlaintext(secret: String) {
        assertTrue(
            rawPreferences().all.values.none { value ->
                value?.toString()?.contains(secret) == true
            },
        )
    }

    private fun rawPreferences() =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    private companion object {
        const val PreferencesName = "mimir_rewrite_artwork_keys"
        const val LegacyKey = "steamgriddb_api_key"
        const val CiphertextKey = "steamgriddb_key_cipher"
        const val IvKey = "steamgriddb_key_iv"
        const val ProviderValidationBindingKey = "steamgriddb_key_provider_validation_binding"
    }
}
