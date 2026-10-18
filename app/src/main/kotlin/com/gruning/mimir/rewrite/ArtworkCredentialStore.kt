package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gruning.mimir.rewrite.core.metadata.online.UserAuth
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable encrypted storage for the user's optional ScreenScraper member sign-in.
 *
 * The password is AES-256-GCM encrypted under Android Keystore; format, account, and revision are
 * authenticated as GCM additional data so plaintext metadata cannot be swapped independently. App-private
 * preferences contain only that metadata, ciphertext, and IV. A save succeeds only after a
 * synchronous preference commit and an exact decrypt/readback. Corrupt, partial, Keystore, or durability
 * failures latch this credential unavailable for the process so a mutated in-memory preference map cannot
 * reopen connected-art egress.
 *
 * Older installs already encrypted the password but had no format/binding marker. A complete legacy
 * encrypted record remains readable; [ensureBindingActivation] upgrades it before ConnectedArtStore can reopen
 * the gate. Partial legacy state fails closed.
 */
object ArtworkCredentialStore {
    private const val Prefs = "mimir_rewrite_artwork_creds"
    private const val KeyAlias = "mimir_artwork_creds_key"
    private const val KeyFormat = "ssCredentialFormat"
    private const val KeyId = "ssUser"
    private const val KeyPasswordCiphertext = "ssPwCipher"
    private const val KeyPasswordIv = "ssPwIv"
    private const val KeyBinding = "ssCredentialBinding"
    private const val GcmTagBits = 128

    private val accessLock = Any()
    private val runtimeState = ArtworkCredentialRuntimeState()

    /**
     * Persist a live-accepted member sign-in and return its non-secret activation binding.
     * Null means no caller may report the account as connected.
     */
    fun save(context: Context, id: String, password: String): String? =
        synchronized(accessLock) {
            (runtimeState.save { repository(context).save(id, password) }
                as? ArtworkCredentialLoadResult.Available)
                ?.bindingToken
        }

    /** The decrypted member sign-in, or null when missing, corrupt, or unavailable for this process. */
    fun load(context: Context): UserAuth? =
        synchronized(accessLock) {
            (runtimeState.load { repository(context).load() }
                as? ArtworkCredentialLoadResult.Available)
                ?.let { UserAuth(id = it.username, password = it.password) }
        }

    /** True only when a complete encrypted member sign-in decrypts in the active process. */
    fun hasHealthyCredentials(context: Context): Boolean =
        synchronized(accessLock) {
            runtimeState.load { repository(context).load() } is ArtworkCredentialLoadResult.Available
        }

    /** One atomic non-secret identity read, including null binding for a complete legacy record. */
    internal fun identitySnapshot(context: Context): ArtworkCredentialIdentitySnapshot? =
        synchronized(accessLock) {
            (runtimeState.load { repository(context).load() }
                as? ArtworkCredentialLoadResult.Available)
                ?.let {
                    ArtworkCredentialIdentitySnapshot(
                        username = it.username,
                        bindingToken = it.bindingToken,
                    )
                }
        }

    /** One atomic, non-secret activation view; username and revision always come from the same read. */
    internal fun activationSnapshot(context: Context): ArtworkCredentialActivationSnapshot? =
        synchronized(accessLock) {
            (runtimeState.load { repository(context).load() }
                as? ArtworkCredentialLoadResult.Available)
                ?.toActivationSnapshot()
        }

    /**
     * Upgrade a complete pre-binding encrypted record. This is used only during connected-art restore;
     * the provider gate stays closed until ConnectedArtStore durably records the returned binding too.
     */
    internal fun ensureBindingActivation(
        context: Context,
    ): ArtworkCredentialActivationSnapshot? =
        synchronized(accessLock) {
            (runtimeState.ensureBinding { repository(context).ensureBinding() }
                as? ArtworkCredentialLoadResult.Available)
                ?.toActivationSnapshot()
        }

    /** Exact encrypted rollback point; its diagnostic form never exposes contained values. */
    internal fun durableSnapshot(context: Context): ArtworkCredentialDurableSnapshot? =
        synchronized(accessLock) {
            repository(context).snapshot()
        }

    /** Restore an interrupted activation to its exact previous encrypted preference state. */
    internal fun restoreSnapshot(
        context: Context,
        snapshot: ArtworkCredentialDurableSnapshot,
    ): Boolean =
        synchronized(accessLock) {
            val repository = repository(context)
            val restored = repository.restore(snapshot)
            val loaded = if (restored) repository.load() else ArtworkCredentialLoadResult.Unavailable
            runtimeState.restoreDurableState(loaded)
            restored
        }

    /** Remove encrypted state. False means deletion was not durably proven. */
    fun clear(context: Context): Boolean =
        synchronized(accessLock) {
            runtimeState.clear { repository(context).clear() }
        }

    /** Close credential use after any downstream connection-activation durability failure. */
    internal fun failClosedForProcess() {
        synchronized(accessLock) {
            runtimeState.failClosed()
        }
    }

    private fun repository(context: Context): ArtworkCredentialRepository {
        val app = context.applicationContext
        return ArtworkCredentialRepository(
            store = SharedPreferencesArtworkCredentialRecordStore(
                app.getSharedPreferences(Prefs, Context.MODE_PRIVATE),
            ),
            cipher = AndroidKeystoreArtworkCredentialCipher(KeyAlias),
            bindingToken = { UUID.randomUUID().toString() },
        )
    }

    private class SharedPreferencesArtworkCredentialRecordStore(
        private val preferences: SharedPreferences,
    ) : ArtworkCredentialRecordStore {
        override fun read(): ArtworkStoredCredentialRecord {
            val present = PreferenceKeys.any(preferences::contains)
            return ArtworkStoredCredentialRecord(
                recordPresent = present,
                format = preferences.getIntOrNull(KeyFormat),
                username = preferences.getStringOrNull(KeyId),
                passwordCiphertext = preferences.getStringOrNull(KeyPasswordCiphertext),
                passwordIv = preferences.getStringOrNull(KeyPasswordIv),
                bindingToken = preferences.getStringOrNull(KeyBinding),
            )
        }

        override fun write(record: ArtworkEncryptedCredentialRecord): Boolean {
            val format = record.format ?: return false
            return preferences.commitCredentialEdit(PreferenceKeys) {
                putInt(KeyFormat, format)
                putString(KeyId, record.username)
                putString(KeyPasswordCiphertext, record.passwordCiphertext)
                putString(KeyPasswordIv, record.passwordIv)
                putString(KeyBinding, record.bindingToken)
            }
        }

        override fun clear(): Boolean =
            preferences.commitCredentialEdit(PreferenceKeys) {
                PreferenceKeys.forEach(::remove)
            }

        override fun snapshot(): ArtworkCredentialDurableSnapshot? =
            preferences.snapshotCredentialValues(PreferenceKeys)
                ?.let(::ArtworkCredentialDurableSnapshot)

        override fun restore(snapshot: ArtworkCredentialDurableSnapshot): Boolean =
            preferences.restoreCredentialValues(PreferenceKeys, snapshot.preferences)

        private fun SharedPreferences.getStringOrNull(key: String): String? =
            if (contains(key)) getString(key, null) else null

        private fun SharedPreferences.getIntOrNull(key: String): Int? =
            if (contains(key)) getInt(key, Int.MIN_VALUE) else null

        private companion object {
            val PreferenceKeys = setOf(
                KeyFormat,
                KeyId,
                KeyPasswordCiphertext,
                KeyPasswordIv,
                KeyBinding,
            )
        }
    }

    private class AndroidKeystoreArtworkCredentialCipher(
        private val alias: String,
    ) : ArtworkCredentialCipher {
        override fun encrypt(
            username: String,
            password: String,
            bindingToken: String,
        ): ArtworkEncryptedCredentialRecord {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
                updateAAD(
                    artworkCredentialAad(
                        format = ArtworkCredentialEncryptedFormat,
                        username = username,
                        bindingToken = bindingToken,
                    ),
                )
            }
            return ArtworkEncryptedCredentialRecord(
                format = ArtworkCredentialEncryptedFormat,
                username = username,
                passwordCiphertext = b64(cipher.doFinal(password.toByteArray(Charsets.UTF_8))),
                passwordIv = b64(cipher.iv),
                bindingToken = bindingToken,
            )
        }

        override fun decrypt(record: ArtworkEncryptedCredentialRecord): ArtworkDecryptedCredential {
            val ciphertext = unb64(record.passwordCiphertext)
            val iv = unb64(record.passwordIv)
            val password = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
                record.format?.let { format ->
                    require(format == ArtworkCredentialEncryptedFormat)
                    updateAAD(
                        artworkCredentialAad(
                            format = format,
                            username = record.username,
                            bindingToken = record.bindingToken,
                        ),
                    )
                }
                String(doFinal(ciphertext), Charsets.UTF_8)
            }
            return ArtworkDecryptedCredential(
                username = record.username,
                password = password,
                bindingToken = record.bindingToken,
            )
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

internal const val ArtworkCredentialEncryptedFormat = 2

internal interface ArtworkCredentialRecordStore {
    fun read(): ArtworkStoredCredentialRecord
    fun write(record: ArtworkEncryptedCredentialRecord): Boolean
    fun clear(): Boolean
    fun snapshot(): ArtworkCredentialDurableSnapshot?
    fun restore(snapshot: ArtworkCredentialDurableSnapshot): Boolean
}

internal interface ArtworkCredentialCipher {
    fun encrypt(
        username: String,
        password: String,
        bindingToken: String,
    ): ArtworkEncryptedCredentialRecord

    fun decrypt(record: ArtworkEncryptedCredentialRecord): ArtworkDecryptedCredential
}

internal class ArtworkStoredCredentialRecord(
    val recordPresent: Boolean,
    val format: Int?,
    val username: String?,
    val passwordCiphertext: String?,
    val passwordIv: String?,
    val bindingToken: String?,
) {
    override fun toString(): String =
        "ArtworkStoredCredentialRecord(" +
            "recordPresent=$recordPresent, format=$format, username=***, " +
            "passwordCiphertext=***, passwordIv=***, bindingToken=***)"
}

internal class ArtworkEncryptedCredentialRecord(
    val format: Int?,
    val username: String,
    val passwordCiphertext: String,
    val passwordIv: String,
    val bindingToken: String,
) {
    override fun toString(): String =
        "ArtworkEncryptedCredentialRecord(" +
            "format=$format, username=***, passwordCiphertext=***, " +
            "passwordIv=***, bindingToken=***)"
}

internal class ArtworkDecryptedCredential(
    val username: String,
    val password: String,
    val bindingToken: String?,
) {
    override fun toString(): String =
        "ArtworkDecryptedCredential(username=***, password=***, bindingToken=***)"
}

internal class ArtworkCredentialActivationSnapshot(
    val username: String,
    val bindingToken: String,
) {
    override fun toString(): String =
        "ArtworkCredentialActivationSnapshot(username=$username, bindingToken=***)"
}

internal class ArtworkCredentialIdentitySnapshot(
    val username: String,
    val bindingToken: String?,
) {
    override fun toString(): String =
        "ArtworkCredentialIdentitySnapshot(username=$username, bindingToken=***)"
}

internal class ArtworkCredentialDurableSnapshot(
    val preferences: CredentialPreferenceSnapshot,
) {
    override fun toString(): String = "ArtworkCredentialDurableSnapshot(preferences=***)"
}

internal sealed interface ArtworkCredentialLoadResult {
    class Available(
        val username: String,
        val password: String,
        val bindingToken: String?,
    ) : ArtworkCredentialLoadResult {
        override fun toString(): String =
            "ArtworkCredentialLoadResult.Available(" +
                "username=***, password=***, bindingToken=***)"
    }

    data object Missing : ArtworkCredentialLoadResult
    data object Unavailable : ArtworkCredentialLoadResult
}

/**
 * Android-free encrypted-record state machine. It proves exact readback and keeps complete legacy
 * encrypted records readable without treating partial state as absence.
 */
internal class ArtworkCredentialRepository(
    private val store: ArtworkCredentialRecordStore,
    private val cipher: ArtworkCredentialCipher,
    private val bindingToken: () -> String,
) {
    fun load(): ArtworkCredentialLoadResult {
        val stored = runCatching(store::read).getOrElse {
            return ArtworkCredentialLoadResult.Unavailable
        }
        if (!stored.recordPresent) return ArtworkCredentialLoadResult.Missing
        return decrypt(stored)
    }

    fun save(id: String, password: String): ArtworkCredentialLoadResult {
        val username = id.trim()
        if (username.isEmpty() || password.isBlank()) return ArtworkCredentialLoadResult.Unavailable
        val binding = runCatching(bindingToken)
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return ArtworkCredentialLoadResult.Unavailable
        val encrypted = runCatching {
            cipher.encrypt(username, password, binding)
        }.getOrNull() ?: return ArtworkCredentialLoadResult.Unavailable
        val selfVerified = runCatching { cipher.decrypt(encrypted) }.getOrNull()
        if (
            selfVerified?.username != username ||
            selfVerified.password != password ||
            selfVerified.bindingToken != binding
        ) {
            return ArtworkCredentialLoadResult.Unavailable
        }
        if (!runCatching { store.write(encrypted) }.getOrDefault(false)) {
            return ArtworkCredentialLoadResult.Unavailable
        }
        val persisted = load()
        return if (
            persisted is ArtworkCredentialLoadResult.Available &&
            persisted.username == username &&
            persisted.password == password &&
            persisted.bindingToken == binding
        ) {
            persisted
        } else {
            ArtworkCredentialLoadResult.Unavailable
        }
    }

    fun ensureBinding(): ArtworkCredentialLoadResult {
        val current = load()
        if (current !is ArtworkCredentialLoadResult.Available) return current
        if (!current.bindingToken.isNullOrBlank()) return current
        return save(current.username, current.password)
    }

    fun clear(): ArtworkCredentialLoadResult {
        if (!runCatching(store::clear).getOrDefault(false)) {
            return ArtworkCredentialLoadResult.Unavailable
        }
        return if (load() === ArtworkCredentialLoadResult.Missing) {
            ArtworkCredentialLoadResult.Missing
        } else {
            ArtworkCredentialLoadResult.Unavailable
        }
    }

    fun snapshot(): ArtworkCredentialDurableSnapshot? =
        runCatching(store::snapshot).getOrNull()

    fun restore(snapshot: ArtworkCredentialDurableSnapshot): Boolean =
        runCatching { store.restore(snapshot) }.getOrDefault(false)

    private fun decrypt(stored: ArtworkStoredCredentialRecord): ArtworkCredentialLoadResult {
        val legacyEncrypted = stored.format == null && stored.bindingToken == null
        val currentEncrypted =
            stored.format == ArtworkCredentialEncryptedFormat &&
                !stored.bindingToken.isNullOrBlank()
        if (!legacyEncrypted && !currentEncrypted) return ArtworkCredentialLoadResult.Unavailable

        val username = stored.username
            ?.takeIf { it.isNotBlank() && it == it.trim() }
            ?: return ArtworkCredentialLoadResult.Unavailable
        val ciphertext = stored.passwordCiphertext?.takeIf(String::isNotBlank)
            ?: return ArtworkCredentialLoadResult.Unavailable
        val iv = stored.passwordIv?.takeIf(String::isNotBlank)
            ?: return ArtworkCredentialLoadResult.Unavailable
        val encrypted = ArtworkEncryptedCredentialRecord(
            format = stored.format,
            username = username,
            passwordCiphertext = ciphertext,
            passwordIv = iv,
            bindingToken = stored.bindingToken.orEmpty(),
        )
        val decrypted = runCatching { cipher.decrypt(encrypted) }.getOrNull()
            ?: return ArtworkCredentialLoadResult.Unavailable
        if (
            decrypted.username.trim() != username ||
            decrypted.password.isBlank() ||
            decrypted.bindingToken != stored.bindingToken.orEmpty()
        ) {
            return ArtworkCredentialLoadResult.Unavailable
        }
        return ArtworkCredentialLoadResult.Available(
            username = username,
            password = decrypted.password,
            bindingToken = stored.bindingToken,
        )
    }
}

/**
 * Process-lifetime fail-closed authority. Explicit save/clear may recover; ordinary loads and legacy
 * binding migration cannot silently clear a prior Keystore/durability failure.
 */
internal class ArtworkCredentialRuntimeState {
    private var storageUnavailable = false

    fun load(readDurable: () -> ArtworkCredentialLoadResult): ArtworkCredentialLoadResult {
        if (storageUnavailable) return ArtworkCredentialLoadResult.Unavailable
        val loaded = runCatching(readDurable).getOrElse {
            ArtworkCredentialLoadResult.Unavailable
        }
        if (loaded === ArtworkCredentialLoadResult.Unavailable) storageUnavailable = true
        return loaded
    }

    fun save(writeDurable: () -> ArtworkCredentialLoadResult): ArtworkCredentialLoadResult {
        val written = runCatching(writeDurable).getOrElse {
            ArtworkCredentialLoadResult.Unavailable
        }
        storageUnavailable = written !is ArtworkCredentialLoadResult.Available
        return written
    }

    fun ensureBinding(writeDurable: () -> ArtworkCredentialLoadResult): ArtworkCredentialLoadResult {
        if (storageUnavailable) return ArtworkCredentialLoadResult.Unavailable
        val written = runCatching(writeDurable).getOrElse {
            ArtworkCredentialLoadResult.Unavailable
        }
        if (written === ArtworkCredentialLoadResult.Unavailable) storageUnavailable = true
        return written
    }

    fun clear(clearDurable: () -> ArtworkCredentialLoadResult): Boolean {
        val cleared = runCatching(clearDurable).getOrElse {
            ArtworkCredentialLoadResult.Unavailable
        }
        val success = cleared === ArtworkCredentialLoadResult.Missing
        storageUnavailable = !success
        return success
    }

    fun failClosed() {
        storageUnavailable = true
    }

    fun restoreDurableState(restored: ArtworkCredentialLoadResult) {
        storageUnavailable = restored === ArtworkCredentialLoadResult.Unavailable
    }

    override fun toString(): String =
        "ArtworkCredentialRuntimeState(storageUnavailable=$storageUnavailable)"
}

private fun ArtworkCredentialLoadResult.Available.toActivationSnapshot():
    ArtworkCredentialActivationSnapshot? =
    bindingToken
        ?.takeIf(String::isNotBlank)
        ?.let { ArtworkCredentialActivationSnapshot(username, it) }

internal fun artworkCredentialAad(
    format: Int,
    username: String,
    bindingToken: String,
): ByteArray {
    val usernameBytes = username.toByteArray(Charsets.UTF_8)
    val bindingBytes = bindingToken.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(
        Int.SIZE_BYTES * 3 + usernameBytes.size + bindingBytes.size,
    )
        .putInt(format)
        .putInt(usernameBytes.size)
        .put(usernameBytes)
        .putInt(bindingBytes.size)
        .put(bindingBytes)
        .array()
}
