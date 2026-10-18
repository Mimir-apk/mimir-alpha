package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android Keystore/SharedPreferences proof; physical hardware execution remains a separate gate. */
@RunWith(AndroidJUnit4::class)
class ArtworkCredentialStoreDeviceTest {
    @Test
    fun encryptedRoundTripPersistsNoPlaintextAndClearVerifiesAbsence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ArtworkCredentialStore.clear(context))
        try {
            val binding = ArtworkCredentialStore.save(
                context,
                id = "device-test-member",
                password = "device-test-password",
            )
            assertNotNull(binding)
            val loaded = ArtworkCredentialStore.load(context)
            assertEquals("device-test-member", loaded?.id)
            assertEquals("device-test-password", loaded?.password)

            val values = preferences(context).all
            assertEquals(ArtworkCredentialEncryptedFormat, values[KEY_FORMAT])
            assertEquals(binding, values[KEY_BINDING])
            assertFalse(values.values.any { it == "device-test-password" })

            assertTrue(ArtworkCredentialStore.clear(context))
            assertTrue(preferences(context).all.isEmpty())
            assertNull(ArtworkCredentialStore.load(context))
        } finally {
            ArtworkCredentialStore.clear(context)
        }
    }

    @Test
    fun malformedCiphertextFailsClosedWithoutThrowingAndExplicitClearRecovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ArtworkCredentialStore.clear(context))
        try {
            assertNotNull(
                ArtworkCredentialStore.save(
                    context,
                    id = "corrupt-test-member",
                    password = "corrupt-test-password",
                ),
            )
            assertTrue(preferences(context).edit().putString(KEY_CIPHERTEXT, "%%%").commit())

            assertNull(ArtworkCredentialStore.load(context))
            assertFalse(ArtworkCredentialStore.hasHealthyCredentials(context))
            assertTrue(ArtworkCredentialStore.clear(context))
            assertTrue(preferences(context).all.isEmpty())
        } finally {
            ArtworkCredentialStore.clear(context)
        }
    }

    @Test
    fun completeLegacyEncryptedRecordReceivesARevisionBeforeActivation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ArtworkCredentialStore.clear(context))
        try {
            // Create the Keystore key through the production writer, then replace the record with the exact
            // pre-format ciphertext shape (AES-GCM without metadata AAD).
            assertNotNull(
                ArtworkCredentialStore.save(
                    context,
                    id = "legacy-test-member",
                    password = "legacy-test-password",
                ),
            )
            writeLegacyRecord(context, "legacy-test-member", "legacy-test-password")

            assertEquals("legacy-test-member", ArtworkCredentialStore.load(context)?.id)
            assertNotNull(ArtworkCredentialStore.ensureBindingActivation(context))
            assertEquals(ArtworkCredentialEncryptedFormat, preferences(context).getInt(KEY_FORMAT, -1))
            assertTrue(preferences(context).getString(KEY_BINDING, null).isNullOrBlank().not())
        } finally {
            ArtworkCredentialStore.clear(context)
        }
    }

    @Test
    fun currentRecordRejectsUsernameBindingAndCrossRecordCiphertextTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ArtworkCredentialStore.clear(context))
        try {
            assertNotNull(ArtworkCredentialStore.save(context, "member-one", "password-one"))
            assertTrue(preferences(context).edit().putString(KEY_USER, "tampered-member").commit())
            assertNull(ArtworkCredentialStore.load(context))

            assertTrue(ArtworkCredentialStore.clear(context))
            assertNotNull(ArtworkCredentialStore.save(context, "member-one", "password-one"))
            assertTrue(preferences(context).edit().putString(KEY_BINDING, "tampered-binding").commit())
            assertNull(ArtworkCredentialStore.load(context))

            assertTrue(ArtworkCredentialStore.clear(context))
            assertNotNull(ArtworkCredentialStore.save(context, "member-one", "password-one"))
            val firstCiphertext = preferences(context).getString(KEY_CIPHERTEXT, null)
            val firstIv = preferences(context).getString(KEY_IV, null)

            assertTrue(ArtworkCredentialStore.clear(context))
            assertNotNull(ArtworkCredentialStore.save(context, "member-two", "password-two"))
            assertTrue(
                preferences(context).edit()
                    .putString(KEY_CIPHERTEXT, firstCiphertext)
                    .putString(KEY_IV, firstIv)
                    .commit(),
            )
            assertNull(ArtworkCredentialStore.load(context))
        } finally {
            ArtworkCredentialStore.clear(context)
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun writeLegacyRecord(context: Context, username: String, password: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        assertTrue(
            preferences(context).edit()
                .remove(KEY_FORMAT)
                .remove(KEY_BINDING)
                .putString(KEY_USER, username)
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        )
    }

    private companion object {
        const val PREFERENCES = "mimir_rewrite_artwork_creds"
        const val KEY_ALIAS = "mimir_artwork_creds_key"
        const val KEY_FORMAT = "ssCredentialFormat"
        const val KEY_USER = "ssUser"
        const val KEY_CIPHERTEXT = "ssPwCipher"
        const val KEY_IV = "ssPwIv"
        const val KEY_BINDING = "ssCredentialBinding"
    }
}
