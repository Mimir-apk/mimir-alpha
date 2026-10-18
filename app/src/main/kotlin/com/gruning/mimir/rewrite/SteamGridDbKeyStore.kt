package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's optional SteamGridDB API key, encrypted at rest with AES-256-GCM under an Android Keystore
 * key. App-private preferences hold only the format marker, ciphertext, and IV; the Keystore key never
 * leaves the Keystore. The credential is never logged or included in Mimir recovery exports.
 *
 * Installs that predate encrypted storage are migrated without risking the user's key: encrypted state is
 * committed and decrypt-verified before the legacy plaintext entry is removed. Once any encrypted record
 * exists, malformed or undecryptable state fails closed instead of falling back to legacy plaintext or a
 * bundled debug key.
 *
 * The in-process [key] value is required only by the connection editor. Provider requests obtain the key
 * through [resolvedKey], and release builds have no bundled fallback.
 */
object SteamGridDbKeyStore {
    private const val Prefs = "mimir_rewrite_artwork_keys"
    private const val KeyAlias = "mimir_steamgriddb_api_key"
    private const val KeyLegacyApiKey = "steamgriddb_api_key"
    private const val KeyFormat = "steamgriddb_key_format"
    private const val KeyCiphertext = "steamgriddb_key_cipher"
    private const val KeyIv = "steamgriddb_key_iv"
    private const val KeyProviderValidationBinding = "steamgriddb_key_provider_validation_binding"
    private const val GcmTagBits = 128

    private val accessLock = Any()
    private val runtimeState = SteamGridDbKeyRuntimeState()
    private val _key = MutableStateFlow("")
    private val _providerValidated = MutableStateFlow(false)

    /** The decrypted user-entered key for the active process (empty when absent or unavailable). */
    val key: StateFlow<String> = _key.asStateFlow()

    /** True only when the encrypted record was written after a live provider acceptance. */
    val providerValidated: StateFlow<Boolean> = _providerValidated.asStateFlow()

    fun load(context: Context) {
        synchronized(accessLock) {
            MimirSteamGridDbGate.setOpen(false)
            runtimeState.load { repository(context).load() }
            publishGateFromRuntime()
            _key.value = runtimeState.keyForUi
        }
    }

    /**
     * Encrypt and durably replace the user key. A failed write clears process state and latches credential
     * resolution unavailable until an explicit save/clear succeeds or a new process reloads disk truth.
     */
    private fun setKey(context: Context, value: String, providerValidated: Boolean): Boolean {
        val cleaned = value.trim()
        return synchronized(accessLock) {
            MimirSteamGridDbGate.setOpen(false)
            val success = runtimeState.save(cleaned) {
                repository(context).save(cleaned, providerValidated)
            }
            publishGateFromRuntime()
            _key.value = runtimeState.keyForUi
            success
        }
    }

    /** Persist a key only after [CustomHeroFetcher.validateSteamGridDbKey] accepted it live. */
    fun setValidatedKey(context: Context, value: String): Boolean =
        setKey(context, value, providerValidated = true)

    /** Remove both encrypted state and any interrupted-migration legacy entry. */
    fun clear(context: Context): Boolean = setKey(context, "", providerValidated = false)

    /**
     * Resolve the request credential. A provider-validated user key wins. The build-time key is used only
     * when no user record exists, never when a stored key is unvalidated, corrupt, or unavailable.
     */
    fun resolvedKey(context: Context): String {
        return synchronized(accessLock) {
            val resolved = runtimeState.resolve(BuildConfig.STEAMGRIDDB_API_KEY) {
                repository(context).load()
            }
            _key.value = runtimeState.keyForUi
            resolved
        }
    }

    private fun publishGateFromRuntime() {
        val validated = steamGridDbGateOpenFor(
            loaded = runtimeState.lastLoadResult,
            userRecordVerified = runtimeState.userRecordVerified,
        )
        _providerValidated.value = validated
        MimirSteamGridDbGate.setOpen(validated)
    }

    private fun repository(context: Context): SteamGridDbKeyRepository {
        val app = context.applicationContext
        return SteamGridDbKeyRepository(
            store = SharedPreferencesSteamGridDbKeyRecordStore(
                app.getSharedPreferences(Prefs, Context.MODE_PRIVATE),
            ),
            cipher = AndroidKeystoreSteamGridDbKeyCipher(KeyAlias),
        )
    }

    private class SharedPreferencesSteamGridDbKeyRecordStore(
        private val preferences: SharedPreferences,
    ) : SteamGridDbKeyRecordStore {
        private val rollback = SteamGridDbPreferenceRollback(
            snapshot = ::snapshot,
            restore = ::restore,
        )

        override fun read(): SteamGridDbStoredKeyRecord {
            val encryptedRecordPresent =
                preferences.contains(KeyFormat) ||
                    preferences.contains(KeyCiphertext) ||
                    preferences.contains(KeyIv)
            return SteamGridDbStoredKeyRecord(
                encryptedRecordPresent = encryptedRecordPresent,
                format = preferences.getIntOrNull(KeyFormat),
                ciphertext = preferences.getStringOrNull(KeyCiphertext),
                iv = preferences.getStringOrNull(KeyIv),
                legacyPlaintext = preferences.getStringOrNull(KeyLegacyApiKey),
                providerValidationBinding = preferences.getStringOrNull(KeyProviderValidationBinding),
            )
        }

        override fun writeEncrypted(
            record: SteamGridDbEncryptedKeyRecord,
            providerValidated: Boolean,
        ): Boolean =
            rollback.commit {
                val editor = preferences.edit()
                    .putInt(KeyFormat, SteamGridDbEncryptedKeyFormat)
                    .putString(KeyCiphertext, record.ciphertext)
                    .putString(KeyIv, record.iv)
                if (providerValidated) {
                    editor.putString(
                        KeyProviderValidationBinding,
                        steamGridDbProviderValidationBinding(record),
                    )
                } else {
                    editor.remove(KeyProviderValidationBinding)
                }
                editor.commit()
            }

        override fun removeLegacy(): Boolean =
            rollback.commit {
                preferences.edit().remove(KeyLegacyApiKey).commit()
            }

        override fun clear(): Boolean =
            rollback.commit {
                preferences.edit()
                    .remove(KeyLegacyApiKey)
                    .remove(KeyFormat)
                    .remove(KeyCiphertext)
                    .remove(KeyIv)
                    .remove(KeyProviderValidationBinding)
                    .commit()
            }

        private fun snapshot(): SteamGridDbPreferenceSnapshot {
            val all = preferences.all
            return SteamGridDbPreferenceSnapshot(
                PreferenceKeys.mapNotNull { key ->
                    if (all.containsKey(key)) key to all[key] else null
                }.toMap(),
            )
        }

        @SuppressLint("ApplySharedPref") // Synchronous commit restores SharedPreferences' live map on failure.
        private fun restore(snapshot: SteamGridDbPreferenceSnapshot) {
            val editor = preferences.edit()
            PreferenceKeys.forEach(editor::remove)
            snapshot.values.forEach { (key, value) -> editor.putPreferenceValue(key, value) }
            // Even if this returns false, SharedPreferences has already restored its process map. The
            // caller still receives failure and the process-level authority remains latched unavailable.
            editor.commit()
        }

        private fun SharedPreferences.getStringOrNull(key: String): String? =
            if (contains(key)) getString(key, null) else null

        private fun SharedPreferences.getIntOrNull(key: String): Int? =
            if (contains(key)) getInt(key, Int.MIN_VALUE) else null

        @Suppress("UNCHECKED_CAST")
        private fun SharedPreferences.Editor.putPreferenceValue(
            key: String,
            value: Any?,
        ) {
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, (value as Set<String>).toSet())
                else -> error("Unsupported SharedPreferences value")
            }
        }

        private companion object {
            val PreferenceKeys = setOf(
                KeyLegacyApiKey,
                KeyFormat,
                KeyCiphertext,
                KeyIv,
                KeyProviderValidationBinding,
            )
        }
    }

    private class AndroidKeystoreSteamGridDbKeyCipher(
        private val alias: String,
    ) : SteamGridDbKeyCipher {
        override fun encrypt(plaintext: String): SteamGridDbEncryptedKeyRecord {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            return SteamGridDbEncryptedKeyRecord(
                ciphertext = b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))),
                iv = b64(cipher.iv),
            )
        }

        override fun decrypt(record: SteamGridDbEncryptedKeyRecord): String {
            val ciphertext = unb64(record.ciphertext)
            val iv = unb64(record.iv)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
                String(doFinal(ciphertext), Charsets.UTF_8)
            }
        }

        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }

        private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}

internal const val SteamGridDbEncryptedKeyFormat = 1

internal interface SteamGridDbKeyRecordStore {
    fun read(): SteamGridDbStoredKeyRecord
    fun writeEncrypted(record: SteamGridDbEncryptedKeyRecord, providerValidated: Boolean): Boolean
    fun removeLegacy(): Boolean
    fun clear(): Boolean
}

internal interface SteamGridDbKeyCipher {
    fun encrypt(plaintext: String): SteamGridDbEncryptedKeyRecord
    fun decrypt(record: SteamGridDbEncryptedKeyRecord): String
}

/**
 * Storage snapshot that may contain a legacy secret. Its string form is deliberately redacted.
 */
internal class SteamGridDbStoredKeyRecord(
    val encryptedRecordPresent: Boolean,
    val format: Int?,
    val ciphertext: String?,
    val iv: String?,
    val legacyPlaintext: String?,
    val providerValidationBinding: String? = null,
) {
    override fun toString(): String =
        "SteamGridDbStoredKeyRecord(" +
            "encryptedRecordPresent=$encryptedRecordPresent, " +
            "format=$format, ciphertext=***, iv=***, legacyPlaintext=***, " +
            "providerValidationBinding=***)"
}

/** Ciphertext container; redacted too so diagnostic interpolation cannot disclose credential material. */
internal class SteamGridDbEncryptedKeyRecord(
    val ciphertext: String,
    val iv: String,
) {
    override fun toString(): String = "SteamGridDbEncryptedKeyRecord(ciphertext=***, iv=***)"
}

/** Pre-edit preference state; never expose its values through diagnostics. */
internal class SteamGridDbPreferenceSnapshot(
    val values: Map<String, Any?>,
) {
    override fun toString(): String = "SteamGridDbPreferenceSnapshot(values=***)"
}

/**
 * SharedPreferences applies an edit to its live process map before the disk write reports success. This
 * wrapper restores the pre-edit map after false/throw so later reads cannot mistake an uncommitted mutation
 * for durable truth. The caller must still latch the operation as unavailable.
 */
internal class SteamGridDbPreferenceRollback(
    private val snapshot: () -> SteamGridDbPreferenceSnapshot,
    private val restore: (SteamGridDbPreferenceSnapshot) -> Unit,
) {
    fun commit(operation: () -> Boolean): Boolean {
        val before = runCatching(snapshot).getOrElse { return false }
        val committed = runCatching(operation).getOrDefault(false)
        if (!committed) runCatching { restore(before) }
        return committed
    }
}

/**
 * Process-lifetime credential authority. Android preference writes can fail after mutating their live map,
 * and Keystore work can fail before creating any record. Either case latches resolution unavailable so a
 * cached key or debug fallback cannot silently bypass the failure. Only an explicit durable save/clear or a
 * new process may recover.
 */
internal class SteamGridDbKeyRuntimeState {
    private var storageUnavailable = false
    private var cachedKey = ""
    private var _userRecordVerified = false
    private var _lastLoadResult: SteamGridDbKeyLoadResult = SteamGridDbKeyLoadResult.Missing

    val userRecordVerified: Boolean
        get() = _userRecordVerified

    val lastLoadResult: SteamGridDbKeyLoadResult
        get() = _lastLoadResult

    val keyForUi: String
        get() = cachedKey

    fun load(readDurable: () -> SteamGridDbKeyLoadResult): SteamGridDbKeyLoadResult {
        _userRecordVerified = false
        val loaded = if (storageUnavailable) {
            SteamGridDbKeyLoadResult.Unavailable
        } else {
            runCatching(readDurable).getOrElse { SteamGridDbKeyLoadResult.Unavailable }
        }
        acceptLoad(loaded)
        return loaded
    }

    fun save(
        requestedKey: String,
        writeDurable: () -> SteamGridDbKeyLoadResult,
    ): Boolean {
        _userRecordVerified = false
        val cleaned = requestedKey.trim()
        val stored = runCatching(writeDurable).getOrElse {
            SteamGridDbKeyLoadResult.Unavailable
        }
        val success = when {
            cleaned.isEmpty() -> stored === SteamGridDbKeyLoadResult.Missing
            else -> stored is SteamGridDbKeyLoadResult.Available && stored.key == cleaned
        }
        storageUnavailable = !success
        cachedKey = if (success) {
            (stored as? SteamGridDbKeyLoadResult.Available)?.key.orEmpty()
        } else {
            ""
        }
        if (success) {
            acceptLoad(stored)
        } else {
            _lastLoadResult = stored
            if (stored === SteamGridDbKeyLoadResult.Unavailable) storageUnavailable = true
            _userRecordVerified = false
        }
        return success
    }

    fun resolve(
        buildTimeDebugKey: String,
        readDurable: () -> SteamGridDbKeyLoadResult,
    ): String {
        if (storageUnavailable) return ""
        if (cachedKey.isNotBlank()) {
            return cachedKey.takeIf {
                (_lastLoadResult as? SteamGridDbKeyLoadResult.Available)?.providerValidated == true
            }.orEmpty()
        }
        val loaded = runCatching(readDurable).getOrElse {
            SteamGridDbKeyLoadResult.Unavailable
        }
        acceptLoad(loaded, touchVerified = false)
        return resolveSteamGridDbKey(
            stored = loaded,
            buildTimeDebugKey = buildTimeDebugKey,
            storageUnavailableForProcess = storageUnavailable,
        )
    }

    private fun acceptLoad(loaded: SteamGridDbKeyLoadResult, touchVerified: Boolean = true) {
        _lastLoadResult = loaded
        if (loaded === SteamGridDbKeyLoadResult.Unavailable) storageUnavailable = true
        cachedKey = (loaded as? SteamGridDbKeyLoadResult.Available)?.key.orEmpty()
        if (touchVerified) {
            _userRecordVerified = loaded is SteamGridDbKeyLoadResult.Available && cachedKey.isNotBlank()
        }
    }

    override fun toString(): String =
        "SteamGridDbKeyRuntimeState(storageUnavailable=$storageUnavailable, cachedKey=***)"
}

internal sealed interface SteamGridDbKeyLoadResult {
    /** Decrypted only inside the process; never interpolate this result into logs. */
    class Available(
        val key: String,
        val providerValidated: Boolean = false,
    ) : SteamGridDbKeyLoadResult {
        override fun toString(): String = "SteamGridDbKeyLoadResult.Available(key=***)"
    }

    data object Missing : SteamGridDbKeyLoadResult
    data object Unavailable : SteamGridDbKeyLoadResult
}

/**
 * Crash-safe storage state machine. It is Android-free so migration order and failure behavior can be
 * proven on the host; Android Keystore behavior remains an instrumentation/device gate.
 */
internal class SteamGridDbKeyRepository(
    private val store: SteamGridDbKeyRecordStore,
    private val cipher: SteamGridDbKeyCipher,
) {
    fun load(): SteamGridDbKeyLoadResult {
        val record = runCatching(store::read).getOrElse {
            return SteamGridDbKeyLoadResult.Unavailable
        }
        if (record.encryptedRecordPresent) return readEncrypted(record)

        val legacy = record.legacyPlaintext?.trim().orEmpty()
        if (legacy.isEmpty()) return SteamGridDbKeyLoadResult.Missing
        return migrateLegacy(legacy)
    }

    fun save(value: String, providerValidated: Boolean = false): SteamGridDbKeyLoadResult {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) {
            return if (runCatching(store::clear).getOrDefault(false)) {
                SteamGridDbKeyLoadResult.Missing
            } else {
                SteamGridDbKeyLoadResult.Unavailable
            }
        }

        val encrypted = encryptAndSelfVerify(cleaned)
            ?: return SteamGridDbKeyLoadResult.Unavailable
        if (!runCatching { store.writeEncrypted(encrypted, providerValidated) }.getOrDefault(false)) {
            return SteamGridDbKeyLoadResult.Unavailable
        }
        val verified = verifyPersisted(cleaned, providerValidated)
        if (verified !is SteamGridDbKeyLoadResult.Available) return verified
        return if (removeLegacyVerified()) verified else SteamGridDbKeyLoadResult.Unavailable
    }

    private fun migrateLegacy(legacy: String): SteamGridDbKeyLoadResult {
        val encrypted = encryptAndSelfVerify(legacy)
            ?: return SteamGridDbKeyLoadResult.Unavailable
        if (!runCatching { store.writeEncrypted(encrypted, providerValidated = false) }.getOrDefault(false)) {
            return SteamGridDbKeyLoadResult.Unavailable
        }
        val verified = verifyPersisted(legacy, expectedProviderValidated = false)
        if (verified !is SteamGridDbKeyLoadResult.Available) return verified
        // The encrypted record is already authoritative. A failed cleanup fails closed; a new repository
        // can retry it after process restart, or an explicit save can recover this process. Callers cannot
        // report success while plaintext remains.
        return if (removeLegacyVerified()) verified else SteamGridDbKeyLoadResult.Unavailable
    }

    private fun verifyPersisted(
        expected: String,
        expectedProviderValidated: Boolean,
    ): SteamGridDbKeyLoadResult {
        val persisted = runCatching(store::read).getOrElse {
            return SteamGridDbKeyLoadResult.Unavailable
        }
        val loaded = readEncrypted(persisted, removeLegacyAfterVerification = false)
        return if (
            loaded is SteamGridDbKeyLoadResult.Available &&
            loaded.key == expected &&
            loaded.providerValidated == expectedProviderValidated
        ) {
            loaded
        } else {
            SteamGridDbKeyLoadResult.Unavailable
        }
    }

    private fun readEncrypted(
        record: SteamGridDbStoredKeyRecord,
        removeLegacyAfterVerification: Boolean = true,
    ): SteamGridDbKeyLoadResult {
        if (
            !record.encryptedRecordPresent ||
            record.format != SteamGridDbEncryptedKeyFormat ||
            record.ciphertext.isNullOrBlank() ||
            record.iv.isNullOrBlank()
        ) {
            return SteamGridDbKeyLoadResult.Unavailable
        }
        val encrypted = SteamGridDbEncryptedKeyRecord(record.ciphertext, record.iv)
        val plaintext = runCatching { cipher.decrypt(encrypted) }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (plaintext.isEmpty()) return SteamGridDbKeyLoadResult.Unavailable
        if (removeLegacyAfterVerification && !record.legacyPlaintext.isNullOrEmpty()) {
            if (!removeLegacyVerified()) return SteamGridDbKeyLoadResult.Unavailable
        }
        return SteamGridDbKeyLoadResult.Available(
            key = plaintext,
            providerValidated = record.providerValidationBinding ==
                steamGridDbProviderValidationBinding(encrypted),
        )
    }

    private fun removeLegacyVerified(): Boolean =
        runCatching(store::removeLegacy).getOrDefault(false)

    private fun encryptAndSelfVerify(plaintext: String): SteamGridDbEncryptedKeyRecord? {
        val encrypted = runCatching { cipher.encrypt(plaintext) }.getOrNull() ?: return null
        val verified = runCatching { cipher.decrypt(encrypted) }.getOrNull()
        return encrypted.takeIf { verified == plaintext }
    }
}

internal fun steamGridDbGateOpenFor(
    loaded: SteamGridDbKeyLoadResult,
    userRecordVerified: Boolean,
): Boolean =
    userRecordVerified &&
        loaded is SteamGridDbKeyLoadResult.Available &&
        loaded.providerValidated &&
        loaded.key.isNotBlank()

internal fun resolveSteamGridDbKey(
    stored: SteamGridDbKeyLoadResult,
    buildTimeDebugKey: String,
    storageUnavailableForProcess: Boolean = false,
): String {
    if (storageUnavailableForProcess) return ""
    return when (stored) {
        is SteamGridDbKeyLoadResult.Available -> stored.key.takeIf { stored.providerValidated }.orEmpty()
        SteamGridDbKeyLoadResult.Missing -> buildTimeDebugKey.trim()
        SteamGridDbKeyLoadResult.Unavailable -> ""
    }
}

/** Non-secret binding between provider acceptance and one exact randomized encrypted record. */
internal fun steamGridDbProviderValidationBinding(record: SteamGridDbEncryptedKeyRecord): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$SteamGridDbEncryptedKeyFormat|${record.ciphertext}|${record.iv}".toByteArray(Charsets.UTF_8),
    )
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
