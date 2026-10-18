package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.online.RaValidation
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-backed fixtures for RA Keystore format, migration, tamper, and process-rejection boundaries. */
@RunWith(AndroidJUnit4::class)
class RetroAchievementsConnectPersistenceTest {

    @Test
    fun acceptedRecordIsEncryptedBoundAndSessionRejectionNeverMutatesIt() {
        val context = isolatedPreferencesContext()
        reset(context)
        try {
            assertTrue(
                RetroAchievementsCredentialStore.saveAccepted(
                    context,
                    username = "accepted-user",
                    apiKey = "accepted-key",
                ),
            )
            val stored = raPreferences(context).all.toMap()
            assertEquals(
                RetroAchievementsCredentialEncryptedFormat,
                stored[RetroAchievementsCredentialKeyFormat],
            )
            assertTrue((stored[RetroAchievementsCredentialKeyBinding] as? String).orEmpty().isNotBlank())
            assertFalse(stored.values.joinToString().contains("accepted-key"))
            assertEquals(RaKeyState.ACCEPTED, RetroAchievementsCredentialStore.validationState.value)

            val preferenceEditsBeforeRejection = context.preferenceEditCallCount
            RetroAchievementsCredentialStore.markRejectedIfCurrent(
                stored[RetroAchievementsCredentialKeyBinding] as? String,
            )

            assertEquals(
                RaKeyState.REJECTED_THIS_SESSION,
                RetroAchievementsCredentialStore.validationState.value,
            )
            assertEquals(preferenceEditsBeforeRejection, context.preferenceEditCallCount)
            assertEquals(stored, raPreferences(context).all)
            RetroAchievementsCredentialStore.restore(context)
            assertEquals(
                RaKeyState.REJECTED_THIS_SESSION,
                RetroAchievementsCredentialStore.validationState.value,
            )
        } finally {
            reset(context)
        }
    }

    @Test
    fun legacyCiphertextNeverSeedsConsentAndMigratesOnlyAfterIndependentOptIn() {
        val context = isolatedPreferencesContext()
        reset(context)
        try {
            writeLegacyRecord(
                context = context,
                username = "legacy-user",
                apiKey = "legacy-key",
                accepted = true,
            )
            val legacy = raPreferences(context).all.toMap()

            RetroAchievementsCredentialStore.restore(context)

            assertNull(RetroAchievementsCredentialStore.load(context))
            assertNull(RetroAchievementsCredentialStore.connectedUser.value)
            assertEquals(legacy, raPreferences(context).all)

            assertTrue(ConnectedServicesStore.markOptedIn(context))
            RetroAchievementsCredentialStore.restore(context)

            assertEquals("legacy-user", RetroAchievementsCredentialStore.connectedUser.value)
            assertEquals(RaKeyState.ACCEPTED, RetroAchievementsCredentialStore.validationState.value)
            assertEquals(
                RetroAchievementsCredentialEncryptedFormat,
                raPreferences(context).getInt(RetroAchievementsCredentialKeyFormat, -1),
            )
            assertTrue(
                raPreferences(context)
                    .getString(RetroAchievementsCredentialKeyBinding, null)
                    .orEmpty()
                    .isNotBlank(),
            )
            assertEquals("legacy-key", RetroAchievementsCredentialStore.load(context)?.apiKey)
        } finally {
            reset(context)
        }
    }

    @Test
    fun currentRecordMetadataTamperFailsClosed() {
        val context = isolatedPreferencesContext()
        val tamperKeys = listOf(
            RetroAchievementsCredentialKeyUsername,
            RetroAchievementsCredentialKeyAccepted,
            RetroAchievementsCredentialKeyBinding,
        )
        try {
            tamperKeys.forEach { key ->
                reset(context)
                assertTrue(
                    RetroAchievementsCredentialStore.saveAccepted(
                        context,
                        username = "player",
                        apiKey = "api-key",
                    ),
                )
                val preferences = raPreferences(context)
                val changed = when (key) {
                    RetroAchievementsCredentialKeyAccepted ->
                        preferences.edit().putBoolean(key, false).commit()
                    else ->
                        preferences.edit()
                            .putString(key, "${preferences.getString(key, "")}-tampered")
                            .commit()
                }
                assertTrue(changed)

                RetroAchievementsCredentialStore.restore(context)

                assertNull(RetroAchievementsCredentialStore.load(context))
                assertNull(RetroAchievementsCredentialStore.connectedUser.value)
                assertEquals(
                    RaKeyState.NEVER_CHECKED,
                    RetroAchievementsCredentialStore.validationState.value,
                )
            }
        } finally {
            reset(context)
        }
    }

    @Test
    fun rejectedAndUnreachableCandidatesLeaveTheCredentialStoreEmpty() = runBlocking {
        val context = isolatedPreferencesContext()
        reset(context)
        try {
            for ((validation, expected) in listOf(
                RaValidation.KeyRejected to RetroAchievementsConnectUi.Rejected(
                    "candidate",
                    RetroAchievementsConnectUi.RejectedField.API_KEY,
                ),
                RaValidation.Unreachable to RetroAchievementsConnectUi.Unreachable("candidate"),
            )) {
                val result = validateAndPersistRetroAchievements(
                    username = "candidate",
                    apiKey = "never-persist-this",
                    validate = { _, _ -> validation },
                    persistAccepted = { username, key ->
                        if (RetroAchievementsCredentialStore.saveAccepted(context, username, key)) {
                            RetroAchievementsCredentialCommitResult.Activated
                        } else {
                            RetroAchievementsCredentialCommitResult.FailedUncertain
                        }
                    },
                )

                assertEquals(expected, result)
                assertNull(RetroAchievementsCredentialStore.load(context))
                assertTrue(raPreferences(context).all.isEmpty())
            }
        } finally {
            reset(context)
        }
    }

    private fun reset(context: RecordingPreferencesContext) {
        RetroAchievementsCredentialStore.clear(context)
        context.getSharedPreferences(
            ConnectedServicesPreferences,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        ConnectedServicesStore.restore(context, legacyScreenScraperOptedIn = false)
    }

    private fun writeLegacyRecord(
        context: Context,
        username: String,
        apiKey: String,
        accepted: Boolean,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        assertTrue(
            raPreferences(context).edit()
                .clear()
                .putString(RetroAchievementsCredentialKeyUsername, username)
                .putString(
                    RetroAchievementsCredentialKeyCiphertext,
                    Base64.encodeToString(
                        cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)),
                        Base64.NO_WRAP,
                    ),
                )
                .putString(
                    RetroAchievementsCredentialKeyIv,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                )
                .putBoolean(RetroAchievementsCredentialKeyAccepted, accepted)
                .commit(),
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(RetroAchievementsCredentialKeyAlias, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    RetroAchievementsCredentialKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun isolatedPreferencesContext(): RecordingPreferencesContext {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return RecordingPreferencesContext(target)
    }

    private class RecordingPreferencesContext(base: Context) : ContextWrapper(base) {
        private val preferencesByName = mutableMapOf<String, RecordingSharedPreferences>()

        val preferenceEditCallCount: Int
            get() = preferencesByName.values.sumOf(RecordingSharedPreferences::editCallCount)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferencesByName.getOrPut(name) {
                RecordingSharedPreferences(
                    baseContext.getSharedPreferences("${name}_ra_connect_android_test", mode),
                )
            }
    }

    private class RecordingSharedPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var editCallCount: Int = 0
            private set

        override fun edit(): SharedPreferences.Editor {
            editCallCount += 1
            return delegate.edit()
        }
    }

    private companion object {
        const val ConnectedServicesPreferences = "mimir_rewrite_connected_services"

        fun raPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(
                RetroAchievementsCredentialPreferences,
                Context.MODE_PRIVATE,
            )
    }
}
