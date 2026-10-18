package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gruning.mimir.rewrite.core.model.RaKeyState
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable authority for the user's RetroAchievements username and Web API key.
 *
 * Current records use AES-256-GCM under Android Keystore and authenticate their format, username,
 * acceptance state, and non-secret revision as additional data. A record becomes process-authoritative
 * only after synchronous persistence, decrypt/readback verification, and durable provider-neutral consent.
 * Corrupt, partial, Keystore, or durability failures close this store for the process; only an explicit
 * verified reconnect or clear may recover it.
 *
 * The complete pre-format encrypted record remains readable. It never seeds connected-services consent;
 * when consent is independently durable, restore upgrades that record before publishing it as connected.
 */
object RetroAchievementsCredentialStore {
    private const val GcmTagBits = 128

    private val accessLock = Any()
    private val operations = RetroAchievementsConnectionOperationAuthority()
    private val runtime = RetroAchievementsCredentialRuntimeState()

    private val _connectedUser = MutableStateFlow<String?>(null)
    private val _validationState = MutableStateFlow(RaKeyState.NEVER_CHECKED)

    val connectedUser: StateFlow<String?> = _connectedUser.asStateFlow()
    val validationState: StateFlow<RaKeyState> = _validationState.asStateFlow()

    /** Run after ConnectedArtStore has reconciled the shared provider-neutral migration. */
    fun restore(context: Context) {
        synchronized(accessLock) {
            val previousProcessState = _validationState.value
            publishClosed()
            if (!runtime.mayRestore()) return
            if (!ConnectedServicesStore.hasDurableOptIn(context)) return

            val repository = repository(context)
            val loaded = repository.loadCurrentForActivation()
            if (loaded === RetroAchievementsCredentialLoadResult.Unavailable) {
                failClosedLocked()
                return
            }
            if (loaded === RetroAchievementsCredentialLoadResult.Missing) return
            loaded as RetroAchievementsCredentialLoadResult.Available
            activateLocked(loaded, previousProcessState)
        }
    }

    /**
     * Read credentials only while this process has reconciled both durable authorities.
     * Plaintext exists only for the explicit authenticated client call and is never cached.
     */
    fun load(context: Context): RetroAchievementsCredentials? =
        synchronized(accessLock) {
            val durableConsent = ConnectedServicesStore.hasDurableOptIn(context)
            if (
                !runtime.mayRead() ||
                _validationState.value == RaKeyState.REJECTED_THIS_SESSION ||
                !durableConsent
            ) {
                if (!durableConsent) publishClosed()
                return@synchronized null
            }
            when (val loaded = repository(context).load()) {
                is RetroAchievementsCredentialLoadResult.Available -> {
                    val credentialRevision = loaded.bindingToken?.takeIf(String::isNotBlank)
                    if (loaded.legacy || credentialRevision == null) {
                        failClosedLocked()
                        return@synchronized null
                    }
                    RetroAchievementsCredentials(
                        username = loaded.username,
                        apiKey = loaded.apiKey,
                        credentialRevision = credentialRevision,
                    )
                }
                RetroAchievementsCredentialLoadResult.Missing,
                RetroAchievementsCredentialLoadResult.Unavailable,
                -> {
                    failClosedLocked()
                    null
                }
            }
        }

    fun hasHealthyCredentials(context: Context): Boolean =
        hasUsableRetroAchievementsCredentialsForSession(
            storedUsable = load(context)?.isUsable == true,
            state = _validationState.value,
        )

    /** Begin one live validation. A later attempt or disconnect invalidates this token. */
    internal fun beginConnectAttempt(): Long = operations.begin()

    internal fun publishConnectResultIfCurrent(token: Long, publish: () -> Unit): Boolean =
        operations.runIfCurrent(token) {
            publish()
            true
        } == true

    /** Activity/composition cancellation fences a delayed provider answer but does not erase a prior account. */
    internal fun cancelConnectAttempt(token: Long): Boolean = operations.cancelIfCurrent(token)

    /**
     * Commit a live-accepted candidate if [token] is still current.
     *
     * When consent is already durable, the exact old encrypted record is retained until the candidate's
     * single active-record commit succeeds. When consent is absent, any orphaned RA record is durably
     * removed first, consent is committed second, and the verified credential write is the final activation
     * step. A crash can therefore leave either the prior connected account or no RA account, never an
     * unconsented candidate that later opens by accident.
     */
    internal fun commitAccepted(
        context: Context,
        token: Long,
        username: String,
        apiKey: String,
    ): RetroAchievementsCredentialCommitResult? =
        operations.runIfCurrent(token) {
            synchronized(accessLock) {
                commitAcceptedLocked(context, username, apiKey)
            }
        }

    /**
     * Compatibility seam for exact storage fixtures. Production connect uses an explicit attempt token.
     */
    internal fun saveAccepted(context: Context, username: String, apiKey: String): Boolean {
        val token = beginConnectAttempt()
        return commitAccepted(context, token, username, apiKey) ===
            RetroAchievementsCredentialCommitResult.Activated
    }

    /** Runtime 401/403 rejects only the exact activation revision that authenticated that request. */
    fun markRejectedIfCurrent(credentialRevision: String?) {
        synchronized(accessLock) {
            if (runtime.isActiveRevision(credentialRevision)) {
                _validationState.value = RaKeyState.REJECTED_THIS_SESSION
            }
        }
    }

    /**
     * Disconnect closes runtime use before touching disk and invalidates every older validation.
     * False means durable absence could not be proven and the user must retry before restart.
     */
    fun clear(context: Context): Boolean =
        operations.invalidate {
            synchronized(accessLock) {
                publishClosed()
                val result = repository(context).clear()
                val cleared = result === RetroAchievementsCredentialLoadResult.Missing
                runtime.recordClear(cleared)
                cleared
            }
        }

    private fun commitAcceptedLocked(
        context: Context,
        username: String,
        apiKey: String,
    ): RetroAchievementsCredentialCommitResult {
        val repository = repository(context)
        val previousProcessState = _validationState.value
        publishClosed()
        val consentWasDurable = ConnectedServicesStore.hasDurableOptIn(context)
        val transaction = commitAcceptedRetroAchievementsCredential(
            consentWasDurable = consentWasDurable,
            snapshot = repository::snapshot,
            clearActive = {
                repository.clear() === RetroAchievementsCredentialLoadResult.Missing
            },
            markConsent = { ConnectedServicesStore.markOptedIn(context) },
            saveCandidate = { repository.saveAccepted(username, apiKey) },
            candidateIsValid = { candidate ->
                candidate is RetroAchievementsCredentialLoadResult.Available &&
                    !candidate.legacy &&
                    candidate.accepted
            },
            consentStillDurable = { ConnectedServicesStore.hasDurableOptIn(context) },
            restorePrevious = repository::restore,
            closeWithoutPrior = {
                repository.clear() === RetroAchievementsCredentialLoadResult.Missing
            },
        )
        return when (transaction) {
            is RetroAchievementsCredentialTransactionResult.Activated -> {
                val candidate = transaction.candidate as
                    RetroAchievementsCredentialLoadResult.Available
                if (activateLocked(candidate, RaKeyState.ACCEPTED)) {
                    RetroAchievementsCredentialCommitResult.Activated
                } else {
                    RetroAchievementsCredentialCommitResult.FailedUncertain
                }
            }
            RetroAchievementsCredentialTransactionResult.FailedRestored -> {
                if (consentWasDurable) {
                    restorePreviousRuntime(
                        context = context,
                        repository = repository,
                        previousProcessState = previousProcessState,
                    )
                } else {
                    publishClosed()
                    runtime.recoverClosed()
                    RetroAchievementsCredentialCommitResult.FailedRestored
                }
            }
            RetroAchievementsCredentialTransactionResult.FailedUncertain -> {
                failClosedLocked()
                RetroAchievementsCredentialCommitResult.FailedUncertain
            }
        }
    }

    private fun restorePreviousRuntime(
        context: Context,
        repository: RetroAchievementsCredentialRepository,
        previousProcessState: RaKeyState,
    ): RetroAchievementsCredentialCommitResult {
        publishClosed()
        if (!ConnectedServicesStore.hasDurableOptIn(context)) {
            failClosedLocked()
            return RetroAchievementsCredentialCommitResult.FailedUncertain
        }
        return when (val restored = repository.loadCurrentForActivation()) {
            is RetroAchievementsCredentialLoadResult.Available -> {
                if (activateLocked(restored, previousProcessState)) {
                    RetroAchievementsCredentialCommitResult.FailedRestored
                } else {
                    RetroAchievementsCredentialCommitResult.FailedUncertain
                }
            }
            RetroAchievementsCredentialLoadResult.Missing -> {
                runtime.recoverClosed()
                RetroAchievementsCredentialCommitResult.FailedRestored
            }
            RetroAchievementsCredentialLoadResult.Unavailable -> {
                failClosedLocked()
                RetroAchievementsCredentialCommitResult.FailedUncertain
            }
        }
    }

    private fun activateLocked(
        loaded: RetroAchievementsCredentialLoadResult.Available,
        processState: RaKeyState,
    ): Boolean {
        val credentialRevision = loaded.bindingToken
            ?.takeIf(String::isNotBlank)
        if (loaded.legacy || credentialRevision == null) {
            failClosedLocked()
            return false
        }
        runtime.activate(credentialRevision)
        _connectedUser.value = loaded.username
        _validationState.value = restoredRaKeyState(
            storedUsable = loaded.username.isNotBlank() && loaded.apiKey.isNotBlank(),
            durableAccepted = loaded.accepted,
            currentProcessState = processState,
        )
        return true
    }

    private fun publishClosed() {
        runtime.close()
        _connectedUser.value = null
        _validationState.value = RaKeyState.NEVER_CHECKED
    }

    private fun failClosedLocked() {
        runtime.failClosed()
        publishClosed()
    }

    private fun repository(context: Context): RetroAchievementsCredentialRepository {
        val app = context.applicationContext
        return RetroAchievementsCredentialRepository(
            store = SharedPreferencesRetroAchievementsCredentialRecordStore(
                app.getSharedPreferences(
                    RetroAchievementsCredentialPreferences,
                    Context.MODE_PRIVATE,
                ),
            ),
            cipher = AndroidKeystoreRetroAchievementsCredentialCipher(
                RetroAchievementsCredentialKeyAlias,
            ),
            bindingToken = { UUID.randomUUID().toString() },
        )
    }

    private class SharedPreferencesRetroAchievementsCredentialRecordStore(
        private val preferences: SharedPreferences,
    ) : RetroAchievementsCredentialRecordStore {
        override fun read(): RetroAchievementsStoredCredentialRecord {
            val values = preferences.all
            return RetroAchievementsStoredCredentialRecord(
                recordPresent = RetroAchievementsCredentialPreferenceKeys.any(values::containsKey),
                malformed =
                    values.hasWrongOptionalIntType(RetroAchievementsCredentialKeyFormat) ||
                        values.hasWrongOptionalStringType(RetroAchievementsCredentialKeyUsername) ||
                        values.hasWrongOptionalStringType(RetroAchievementsCredentialKeyCiphertext) ||
                        values.hasWrongOptionalStringType(RetroAchievementsCredentialKeyIv) ||
                        values.hasWrongOptionalBooleanType(RetroAchievementsCredentialKeyAccepted) ||
                        values.hasWrongOptionalStringType(RetroAchievementsCredentialKeyBinding),
                format = values.intOrNull(RetroAchievementsCredentialKeyFormat),
                username = values.stringOrNull(RetroAchievementsCredentialKeyUsername),
                keyCiphertext = values.stringOrNull(RetroAchievementsCredentialKeyCiphertext),
                keyIv = values.stringOrNull(RetroAchievementsCredentialKeyIv),
                accepted = values.booleanOrNull(RetroAchievementsCredentialKeyAccepted),
                bindingToken = values.stringOrNull(RetroAchievementsCredentialKeyBinding),
            )
        }

        override fun write(record: RetroAchievementsEncryptedCredentialRecord): Boolean {
            val format = record.format ?: return false
            return preferences.commitCredentialEdit(RetroAchievementsCredentialPreferenceKeys) {
                putInt(RetroAchievementsCredentialKeyFormat, format)
                putString(RetroAchievementsCredentialKeyUsername, record.username)
                putString(RetroAchievementsCredentialKeyCiphertext, record.keyCiphertext)
                putString(RetroAchievementsCredentialKeyIv, record.keyIv)
                putBoolean(RetroAchievementsCredentialKeyAccepted, record.accepted)
                putString(RetroAchievementsCredentialKeyBinding, record.bindingToken)
            }
        }

        override fun clear(): Boolean =
            preferences.commitCredentialEdit(RetroAchievementsCredentialPreferenceKeys) {
                RetroAchievementsCredentialPreferenceKeys.forEach(::remove)
            }

        override fun snapshot(): RetroAchievementsCredentialDurableSnapshot? =
            preferences.snapshotCredentialValues(RetroAchievementsCredentialPreferenceKeys)
                ?.let(::RetroAchievementsCredentialDurableSnapshot)

        override fun restore(snapshot: RetroAchievementsCredentialDurableSnapshot): Boolean =
            preferences.restoreCredentialValues(
                RetroAchievementsCredentialPreferenceKeys,
                snapshot.preferences,
            )

        private fun Map<String, *>.intOrNull(key: String): Int? = this[key] as? Int
        private fun Map<String, *>.stringOrNull(key: String): String? = this[key] as? String
        private fun Map<String, *>.booleanOrNull(key: String): Boolean? = this[key] as? Boolean

        private fun Map<String, *>.hasWrongOptionalIntType(key: String): Boolean =
            containsKey(key) && this[key] !is Int

        private fun Map<String, *>.hasWrongOptionalStringType(key: String): Boolean =
            containsKey(key) && this[key] !is String

        private fun Map<String, *>.hasWrongOptionalBooleanType(key: String): Boolean =
            containsKey(key) && this[key] !is Boolean
    }

    private class AndroidKeystoreRetroAchievementsCredentialCipher(
        private val alias: String,
    ) : RetroAchievementsCredentialCipher {
        override fun encrypt(
            username: String,
            apiKey: String,
            accepted: Boolean,
            bindingToken: String,
        ): RetroAchievementsEncryptedCredentialRecord {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
                updateAAD(
                    retroAchievementsCredentialAad(
                        format = RetroAchievementsCredentialEncryptedFormat,
                        username = username,
                        accepted = accepted,
                        bindingToken = bindingToken,
                    ),
                )
            }
            return RetroAchievementsEncryptedCredentialRecord(
                format = RetroAchievementsCredentialEncryptedFormat,
                username = username,
                keyCiphertext = Base64.encodeToString(
                    cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                ),
                keyIv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                accepted = accepted,
                bindingToken = bindingToken,
            )
        }

        override fun decrypt(
            record: RetroAchievementsEncryptedCredentialRecord,
        ): RetroAchievementsDecryptedCredential {
            val ciphertext = Base64.decode(record.keyCiphertext, Base64.NO_WRAP)
            val iv = Base64.decode(record.keyIv, Base64.NO_WRAP)
            val apiKey = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
                record.format?.let { format ->
                    require(format == RetroAchievementsCredentialEncryptedFormat)
                    updateAAD(
                        retroAchievementsCredentialAad(
                            format = format,
                            username = record.username,
                            accepted = record.accepted,
                            bindingToken = record.bindingToken,
                        ),
                    )
                }
                String(doFinal(ciphertext), Charsets.UTF_8)
            }
            return RetroAchievementsDecryptedCredential(
                username = record.username,
                apiKey = apiKey,
                accepted = record.accepted,
                bindingToken = record.bindingToken.takeIf(String::isNotBlank),
            )
        }

        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            ).apply {
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
    }
}

internal const val RetroAchievementsCredentialPreferences = "mimir_rewrite_ra_creds"
internal const val RetroAchievementsCredentialKeyAlias = "mimir_ra_creds_key"
internal const val RetroAchievementsCredentialEncryptedFormat = 1
internal const val RetroAchievementsCredentialKeyFormat = "raCredentialFormat"
internal const val RetroAchievementsCredentialKeyUsername = "raUser"
internal const val RetroAchievementsCredentialKeyCiphertext = "raKeyCipher"
internal const val RetroAchievementsCredentialKeyIv = "raKeyIv"
internal const val RetroAchievementsCredentialKeyAccepted = "raAccepted"
internal const val RetroAchievementsCredentialKeyBinding = "raCredentialBinding"
internal val RetroAchievementsCredentialPreferenceKeys = setOf(
    RetroAchievementsCredentialKeyFormat,
    RetroAchievementsCredentialKeyUsername,
    RetroAchievementsCredentialKeyCiphertext,
    RetroAchievementsCredentialKeyIv,
    RetroAchievementsCredentialKeyAccepted,
    RetroAchievementsCredentialKeyBinding,
)

internal enum class RetroAchievementsCredentialCommitResult {
    Activated,
    FailedRestored,
    FailedUncertain,
}

internal sealed interface RetroAchievementsCredentialTransactionResult<out Candidate> {
    class Activated<Candidate>(val candidate: Candidate) :
        RetroAchievementsCredentialTransactionResult<Candidate>

    data object FailedRestored : RetroAchievementsCredentialTransactionResult<Nothing>
    data object FailedUncertain : RetroAchievementsCredentialTransactionResult<Nothing>
}

/**
 * Android-free activation transaction used by the process store and exhaustive failure-order tests.
 *
 * Existing consent keeps the prior record authoritative until a verified candidate is durable. Without
 * consent, an orphan record is removed before consent is granted and is never restored after that point.
 */
internal fun <Snapshot : Any, Candidate : Any> commitAcceptedRetroAchievementsCredential(
    consentWasDurable: Boolean,
    snapshot: () -> Snapshot?,
    clearActive: () -> Boolean,
    markConsent: () -> Boolean,
    saveCandidate: () -> Candidate?,
    candidateIsValid: (Candidate) -> Boolean,
    consentStillDurable: () -> Boolean,
    restorePrevious: (Snapshot) -> Boolean,
    closeWithoutPrior: () -> Boolean,
): RetroAchievementsCredentialTransactionResult<Candidate> {
    val prior = runCatching(snapshot).getOrNull()
        ?: return RetroAchievementsCredentialTransactionResult.FailedUncertain

    if (!consentWasDurable) {
        if (!runCatching(clearActive).getOrDefault(false)) {
            return RetroAchievementsCredentialTransactionResult.FailedUncertain
        }
        if (!runCatching(markConsent).getOrDefault(false)) {
            return if (runCatching(closeWithoutPrior).getOrDefault(false)) {
                RetroAchievementsCredentialTransactionResult.FailedRestored
            } else {
                RetroAchievementsCredentialTransactionResult.FailedUncertain
            }
        }
    }

    val candidate = runCatching(saveCandidate).getOrNull()
    if (
        candidate != null &&
        runCatching { candidateIsValid(candidate) }.getOrDefault(false) &&
        runCatching(consentStillDurable).getOrDefault(false)
    ) {
        return RetroAchievementsCredentialTransactionResult.Activated(candidate)
    }

    return if (consentWasDurable) {
        if (runCatching { restorePrevious(prior) }.getOrDefault(false)) {
            RetroAchievementsCredentialTransactionResult.FailedRestored
        } else {
            RetroAchievementsCredentialTransactionResult.FailedUncertain
        }
    } else {
        if (runCatching(closeWithoutPrior).getOrDefault(false)) {
            RetroAchievementsCredentialTransactionResult.FailedRestored
        } else {
            RetroAchievementsCredentialTransactionResult.FailedUncertain
        }
    }
}

internal interface RetroAchievementsCredentialRecordStore {
    fun read(): RetroAchievementsStoredCredentialRecord
    fun write(record: RetroAchievementsEncryptedCredentialRecord): Boolean
    fun clear(): Boolean
    fun snapshot(): RetroAchievementsCredentialDurableSnapshot?
    fun restore(snapshot: RetroAchievementsCredentialDurableSnapshot): Boolean
}

internal interface RetroAchievementsCredentialCipher {
    fun encrypt(
        username: String,
        apiKey: String,
        accepted: Boolean,
        bindingToken: String,
    ): RetroAchievementsEncryptedCredentialRecord

    fun decrypt(
        record: RetroAchievementsEncryptedCredentialRecord,
    ): RetroAchievementsDecryptedCredential
}

internal class RetroAchievementsStoredCredentialRecord(
    val recordPresent: Boolean,
    val malformed: Boolean,
    val format: Int?,
    val username: String?,
    val keyCiphertext: String?,
    val keyIv: String?,
    val accepted: Boolean?,
    val bindingToken: String?,
) {
    override fun toString(): String =
        "RetroAchievementsStoredCredentialRecord(" +
            "recordPresent=$recordPresent, malformed=$malformed, format=$format, " +
            "username=***, keyCiphertext=***, keyIv=***, accepted=$accepted, bindingToken=***)"
}

internal class RetroAchievementsEncryptedCredentialRecord(
    val format: Int?,
    val username: String,
    val keyCiphertext: String,
    val keyIv: String,
    val accepted: Boolean,
    val bindingToken: String,
) {
    override fun toString(): String =
        "RetroAchievementsEncryptedCredentialRecord(" +
            "format=$format, username=***, keyCiphertext=***, keyIv=***, " +
            "accepted=$accepted, bindingToken=***)"
}

internal class RetroAchievementsDecryptedCredential(
    val username: String,
    val apiKey: String,
    val accepted: Boolean,
    val bindingToken: String?,
) {
    override fun toString(): String =
        "RetroAchievementsDecryptedCredential(" +
            "username=***, apiKey=***, accepted=$accepted, bindingToken=***)"
}

internal sealed interface RetroAchievementsCredentialLoadResult {
    class Available(
        val username: String,
        val apiKey: String,
        val accepted: Boolean,
        val bindingToken: String?,
        val legacy: Boolean,
    ) : RetroAchievementsCredentialLoadResult {
        override fun toString(): String =
            "RetroAchievementsCredentialLoadResult.Available(" +
                "username=***, apiKey=***, accepted=$accepted, bindingToken=***, legacy=$legacy)"
    }

    data object Missing : RetroAchievementsCredentialLoadResult
    data object Unavailable : RetroAchievementsCredentialLoadResult
}

internal class RetroAchievementsCredentialDurableSnapshot(
    val preferences: CredentialPreferenceSnapshot,
) {
    override fun toString(): String =
        "RetroAchievementsCredentialDurableSnapshot(preferences=***)"
}

/** Android-free record repository for host fault and ordering tests. */
internal class RetroAchievementsCredentialRepository(
    private val store: RetroAchievementsCredentialRecordStore,
    private val cipher: RetroAchievementsCredentialCipher,
    private val bindingToken: () -> String,
) {
    fun load(): RetroAchievementsCredentialLoadResult {
        val stored = runCatching(store::read).getOrElse {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        if (!stored.recordPresent) return RetroAchievementsCredentialLoadResult.Missing
        if (stored.malformed) return RetroAchievementsCredentialLoadResult.Unavailable

        val legacy = stored.format == null && stored.bindingToken == null
        val current =
            stored.format == RetroAchievementsCredentialEncryptedFormat &&
                !stored.bindingToken.isNullOrBlank() &&
                stored.accepted != null
        if (!legacy && !current) return RetroAchievementsCredentialLoadResult.Unavailable

        val username = stored.username
            ?.takeIf { it.isNotBlank() && it == it.trim() }
            ?: return RetroAchievementsCredentialLoadResult.Unavailable
        val ciphertext = stored.keyCiphertext?.takeIf(String::isNotBlank)
            ?: return RetroAchievementsCredentialLoadResult.Unavailable
        val iv = stored.keyIv?.takeIf(String::isNotBlank)
            ?: return RetroAchievementsCredentialLoadResult.Unavailable
        val accepted = stored.accepted ?: false
        val encrypted = RetroAchievementsEncryptedCredentialRecord(
            format = stored.format,
            username = username,
            keyCiphertext = ciphertext,
            keyIv = iv,
            accepted = accepted,
            bindingToken = stored.bindingToken.orEmpty(),
        )
        val decrypted = runCatching { cipher.decrypt(encrypted) }.getOrNull()
            ?: return RetroAchievementsCredentialLoadResult.Unavailable
        if (
            decrypted.username != username ||
            decrypted.apiKey.isBlank() ||
            decrypted.accepted != accepted ||
            decrypted.bindingToken != stored.bindingToken
        ) {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        return RetroAchievementsCredentialLoadResult.Available(
            username = username,
            apiKey = decrypted.apiKey,
            accepted = accepted,
            bindingToken = stored.bindingToken,
            legacy = legacy,
        )
    }

    fun saveAccepted(
        username: String,
        apiKey: String,
    ): RetroAchievementsCredentialLoadResult =
        save(username, apiKey, accepted = true)

    fun migrateLegacy(): RetroAchievementsCredentialLoadResult {
        val current = load()
        if (current !is RetroAchievementsCredentialLoadResult.Available || !current.legacy) {
            return current
        }
        return save(current.username, current.apiKey, current.accepted)
    }

    fun clear(): RetroAchievementsCredentialLoadResult {
        if (!runCatching(store::clear).getOrDefault(false)) {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        return if (load() === RetroAchievementsCredentialLoadResult.Missing) {
            RetroAchievementsCredentialLoadResult.Missing
        } else {
            RetroAchievementsCredentialLoadResult.Unavailable
        }
    }

    fun snapshot(): RetroAchievementsCredentialDurableSnapshot? =
        runCatching(store::snapshot).getOrNull()

    fun restore(snapshot: RetroAchievementsCredentialDurableSnapshot): Boolean =
        runCatching { store.restore(snapshot) }.getOrDefault(false)

    private fun save(
        rawUsername: String,
        rawApiKey: String,
        accepted: Boolean,
    ): RetroAchievementsCredentialLoadResult {
        val username = rawUsername.trim()
        val apiKey = rawApiKey.trim()
        if (username.isEmpty() || apiKey.isEmpty()) {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        val binding = runCatching(bindingToken)
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return RetroAchievementsCredentialLoadResult.Unavailable
        val encrypted = runCatching {
            cipher.encrypt(username, apiKey, accepted, binding)
        }.getOrNull() ?: return RetroAchievementsCredentialLoadResult.Unavailable
        val selfVerified = runCatching { cipher.decrypt(encrypted) }.getOrNull()
        if (
            selfVerified?.username != username ||
            selfVerified.apiKey != apiKey ||
            selfVerified.accepted != accepted ||
            selfVerified.bindingToken != binding
        ) {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        if (!runCatching { store.write(encrypted) }.getOrDefault(false)) {
            return RetroAchievementsCredentialLoadResult.Unavailable
        }
        val persisted = load()
        return if (
            persisted is RetroAchievementsCredentialLoadResult.Available &&
            !persisted.legacy &&
            persisted.username == username &&
            persisted.apiKey == apiKey &&
            persisted.accepted == accepted &&
            persisted.bindingToken == binding
        ) {
            persisted
        } else {
            RetroAchievementsCredentialLoadResult.Unavailable
        }
    }
}

/** Upgrade the sole complete legacy shape before any process is allowed to activate it. */
internal fun RetroAchievementsCredentialRepository.loadCurrentForActivation():
    RetroAchievementsCredentialLoadResult {
    val loaded = load()
    return if (loaded is RetroAchievementsCredentialLoadResult.Available && loaded.legacy) {
        migrateLegacy()
    } else {
        loaded
    }
}

/** A decrypted RA sign-in. Not a data class so generated diagnostics cannot expose the key. */
class RetroAchievementsCredentials(
    val username: String,
    val apiKey: String,
    val credentialRevision: String? = null,
) {
    val isUsable: Boolean get() = username.isNotBlank() && apiKey.isNotBlank()

    override fun toString(): String =
        "RetroAchievementsCredentials(username=$username, apiKey=***, credentialRevision=***)"
}

/** Process-local storage and activation latch; only verified reconnect or clear may recover a failure. */
internal class RetroAchievementsCredentialRuntimeState {
    private var storageUnavailable = false
    private var connectionActive = false
    private var activeCredentialRevision: String? = null

    fun mayRestore(): Boolean = !storageUnavailable

    fun mayRead(): Boolean = !storageUnavailable && connectionActive

    fun isActiveRevision(credentialRevision: String?): Boolean =
        mayRead() && activeCredentialRevision == credentialRevision

    fun close() {
        connectionActive = false
        activeCredentialRevision = null
    }

    fun activate(credentialRevision: String? = null) {
        storageUnavailable = false
        connectionActive = true
        activeCredentialRevision = credentialRevision
    }

    fun recoverClosed() {
        storageUnavailable = false
        connectionActive = false
        activeCredentialRevision = null
    }

    fun failClosed() {
        storageUnavailable = true
        connectionActive = false
        activeCredentialRevision = null
    }

    fun recordClear(cleared: Boolean) {
        if (cleared) recoverClosed() else failClosed()
    }
}

internal class RetroAchievementsConnectionOperationAuthority {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Long = synchronized(lock) {
        generation += 1L
        generation
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

    fun cancelIfCurrent(token: Long): Boolean =
        synchronized(lock) {
            if (token != generation) return@synchronized false
            generation += 1L
            true
        }

    fun <T : Any> runIfCurrent(token: Long, operation: () -> T): T? =
        synchronized(lock) {
            if (token != generation) null else operation()
        }

    fun <T : Any> invalidate(operation: () -> T): T =
        synchronized(lock) {
            generation += 1L
            operation()
        }
}

internal fun hasUsableRetroAchievementsCredentialsForSession(
    storedUsable: Boolean,
    state: RaKeyState,
): Boolean = storedUsable && state != RaKeyState.REJECTED_THIS_SESSION

internal fun restoredRaKeyState(
    storedUsable: Boolean,
    durableAccepted: Boolean,
    currentProcessState: RaKeyState,
): RaKeyState = when {
    !storedUsable -> RaKeyState.NEVER_CHECKED
    currentProcessState == RaKeyState.REJECTED_THIS_SESSION ->
        RaKeyState.REJECTED_THIS_SESSION
    durableAccepted -> RaKeyState.ACCEPTED
    else -> RaKeyState.NEVER_CHECKED
}

internal fun retroAchievementsCredentialAad(
    format: Int,
    username: String,
    accepted: Boolean,
    bindingToken: String,
): ByteArray {
    val usernameBytes = username.toByteArray(Charsets.UTF_8)
    val bindingBytes = bindingToken.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(
        Int.SIZE_BYTES * 3 + 1 + usernameBytes.size + bindingBytes.size,
    )
        .putInt(format)
        .put(if (accepted) 1.toByte() else 0.toByte())
        .putInt(usernameBytes.size)
        .put(usernameBytes)
        .putInt(bindingBytes.size)
        .put(bindingBytes)
        .array()
}
