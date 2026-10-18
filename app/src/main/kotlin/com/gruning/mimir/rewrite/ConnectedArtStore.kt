package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperDevRegistration
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable ScreenScraper provider activation and the sole authority that opens [MimirConnectedArtGate].
 *
 * A provider record is active only when all of these independently durable facts agree:
 * - provider-neutral connected-services consent is true;
 * - the provider record is opted in;
 * - its account exactly equals the encrypted credential username;
 * - its non-secret revision exactly equals the encrypted credential revision;
 * - the credential decrypts and the build has a usable ScreenScraper registration.
 *
 * Restore closes the runtime gate before reading anything. Older installations used an active provider
 * record without a revision; they migrate in close-first order by binding the existing encrypted credential
 * and then committing the matching provider record. A crash between those writes remains closed.
 */
object ConnectedArtStore {
    private val accessLock = Any()
    private var storageUnavailableForProcess = false

    private val _optedIn = MutableStateFlow(false)
    val optedIn: StateFlow<Boolean> = _optedIn.asStateFlow()

    private val _previewVideoOptedIn = MutableStateFlow(false)
    val previewVideoOptedIn: StateFlow<Boolean> = _previewVideoOptedIn.asStateFlow()

    private val _connectedAccount = MutableStateFlow<String?>(null)
    val connectedAccount: StateFlow<String?> = _connectedAccount.asStateFlow()

    /**
     * Reconcile all durable activation records and open only on an exact all-record match.
     * This method also performs the one-time provider-neutral consent migration, so Activity and worker
     * callers cannot accidentally restore ScreenScraper before shared consent. When the ScreenScraper record
     * cannot be read, the durable consent is still published (without migrating), because RetroAchievements
     * and the companion hub read it too (product review 2026-09-28, F5).
     */
    fun restore(context: Context) {
        synchronized(accessLock) {
            publishClosed()
            if (storageUnavailableForProcess) {
                ConnectedServicesStore.restoreWithoutMigration(context)
                return
            }
            val repository = providerRepository(context)
            var provider = repository.load()
            if (provider === ConnectedArtRecordState.Unavailable) {
                storageUnavailableForProcess = true
                ConnectedServicesStore.restoreWithoutMigration(context)
                return
            }

            // Only the pre-format shipping record is allowed to seed the one-time provider-neutral
            // migration. A current provider record is one member of the modern all-record transaction;
            // if its neutral record is absent, that transaction is incomplete and must stay closed.
            val legacyProviderOptIn = providerCanSeedConnectedServicesMigration(provider)
            ConnectedServicesStore.restore(context, legacyProviderOptIn)
            val providerNeutralOptedIn = ConnectedServicesStore.optedIn.value
            if (!providerNeutralOptedIn) return

            if (provider is ConnectedArtRecordState.LegacyActive) {
                val identity = ArtworkCredentialStore.identitySnapshot(context)
                if (identity?.username != provider.account) {
                    storageUnavailableForProcess = true
                    return
                }
                val activation = ArtworkCredentialStore.ensureBindingActivation(context)
                if (activation == null || activation.username != provider.account) {
                    storageUnavailableForProcess = true
                    return
                }
                provider = repository.activate(
                    account = activation.username,
                    credentialBinding = activation.bindingToken,
                )
                if (provider !is ConnectedArtRecordState.Active) {
                    storageUnavailableForProcess = true
                    return
                }
            }

            val activation = ArtworkCredentialStore.activationSnapshot(context) ?: return
            val active = provider as? ConnectedArtRecordState.Active ?: return
            val exactMatch = connectedArtRecordsMatch(
                provider = active,
                credential = activation,
                providerNeutralOptedIn = providerNeutralOptedIn,
            )
            if (!exactMatch) return

            _optedIn.value = true
            _previewVideoOptedIn.value = active.previewVideoOptedIn
            _connectedAccount.value = activation.username
            recomputeGate(
                credentialsHealthy = true,
                bindingMatches = true,
                providerNeutralOptedIn = true,
            )
        }
    }

    /** Persist the preview-video sub-option only for a fully active provider record. */
    fun setPreviewVideoOptedIn(context: Context, enabled: Boolean) {
        synchronized(accessLock) {
            val updated = providerRepository(context).setPreviewVideo(enabled)
            if (updated) {
                _previewVideoOptedIn.value = enabled
            } else {
                storageUnavailableForProcess = true
                publishClosed()
            }
        }
    }

    /**
     * Remember how wide this account may fetch, so a new process does not start at one lane while it waits
     * for a live `ssuserInfos` answer. See [ConnectedArtKeyMaxThreads] for the measurement that motivated it.
     *
     * A failure here is deliberately silent — unlike [setPreviewVideoOptedIn] it does NOT mark storage
     * unavailable or close the gate, because a lost performance hint must never cost the player their
     * artwork. The next successful refresh writes it again.
     */
    fun rememberAccountMaxThreads(context: Context, value: Int?) {
        synchronized(accessLock) { providerRepository(context).setMaxThreads(value) }
    }

    /** The remembered allowance for the active account, or null when there is none to trust. */
    fun storedAccountMaxThreads(context: Context): Int? =
        synchronized(accessLock) { providerRepository(context).storedMaxThreads() }

    /**
     * Commit provider activation last. The credential identity/revision is read atomically and must match
     * both arguments; provider-neutral consent must already be durable.
     */
    fun markConnected(context: Context, account: String, credentialBinding: String): Boolean =
        synchronized(accessLock) {
            publishClosed()
            val cleanedAccount = account.trim()
            val activation = ArtworkCredentialStore.activationSnapshot(context)
            val prerequisitesMatch =
                cleanedAccount.isNotEmpty() &&
                    credentialBinding.isNotBlank() &&
                    ConnectedServicesStore.hasDurableOptIn(context) &&
                    activation?.username == cleanedAccount &&
                    activation.bindingToken == credentialBinding
            if (!prerequisitesMatch) {
                storageUnavailableForProcess = true
                return@synchronized false
            }
            val persisted = providerRepository(context).activate(
                account = cleanedAccount,
                credentialBinding = credentialBinding,
            )
            val active = persisted as? ConnectedArtRecordState.Active
            val verified =
                active?.account == cleanedAccount &&
                    active.credentialBinding == credentialBinding
            storageUnavailableForProcess = !verified
            if (!verified) return@synchronized false

            _optedIn.value = true
            _previewVideoOptedIn.value = active.previewVideoOptedIn
            _connectedAccount.value = cleanedAccount
            recomputeGate(
                credentialsHealthy = true,
                bindingMatches = true,
                providerNeutralOptedIn = true,
            )
            true
        }

    /** Deactivate durably and close immediately. Credentials are separately purged by the controller. */
    fun disconnect(context: Context): Boolean =
        synchronized(accessLock) {
            publishClosed()
            val disconnected = providerRepository(context).deactivate()
            storageUnavailableForProcess = !disconnected
            disconnected
        }

    internal fun durableSnapshot(context: Context): ConnectedArtDurableSnapshot? =
        synchronized(accessLock) {
            providerRepository(context).snapshot()
        }

    /** One controller rollback point, coherent with provider restore/migration and preview writes. */
    internal fun connectionSnapshot(context: Context): ArtworkConnectionDurableSnapshot? =
        synchronized(accessLock) {
            val provider = providerRepository(context).snapshot()
            val credential = ArtworkCredentialStore.durableSnapshot(context)
            if (provider != null && credential != null) {
                ArtworkConnectionDurableSnapshot(credential, provider)
            } else {
                null
            }
        }

    /**
     * Restore an interrupted connect's exact provider record. This never opens the gate by itself; the
     * caller restores the credential record too, then invokes [restore] for one all-record decision.
     */
    internal fun restoreSnapshot(
        context: Context,
        snapshot: ConnectedArtDurableSnapshot,
    ): Boolean =
        synchronized(accessLock) {
            publishClosed()
            val repository = providerRepository(context)
            val restored = repository.restore(snapshot)
            storageUnavailableForProcess =
                !restored || repository.load() === ConnectedArtRecordState.Unavailable
            restored
        }

    /** Restore both records under the provider lock, then run one close-first all-record decision. */
    internal fun restoreConnectionSnapshot(
        context: Context,
        snapshot: ArtworkConnectionDurableSnapshot,
    ): Boolean =
        synchronized(accessLock) {
            publishClosed()
            val repository = providerRepository(context)
            val providerRestored = repository.restore(snapshot.provider)
            val credentialRestored = ArtworkCredentialStore.restoreSnapshot(
                context,
                snapshot.credential,
            )
            val providerReadable =
                providerRestored && repository.load() !== ConnectedArtRecordState.Unavailable
            storageUnavailableForProcess = !providerReadable || !credentialRestored
            if (storageUnavailableForProcess) {
                publishClosed()
                false
            } else {
                restore(context)
                true
            }
        }

    internal fun failClosedForProcess() {
        synchronized(accessLock) {
            storageUnavailableForProcess = true
            publishClosed()
        }
    }

    /** Tests only: the storage flag is process-wide and sticky, so each test starts from a fresh process state. */
    internal fun resetProcessStateForTest() {
        synchronized(accessLock) {
            storageUnavailableForProcess = false
            publishClosed()
        }
    }

    private fun providerRepository(context: Context): ConnectedArtActivationRepository {
        val preferences = context.applicationContext.getSharedPreferences(
            ConnectedArtPreferences,
            Context.MODE_PRIVATE,
        )
        return ConnectedArtActivationRepository(
            SharedPreferencesConnectedArtRecordStore(preferences),
        )
    }

    private fun publishClosed() {
        _optedIn.value = false
        _previewVideoOptedIn.value = false
        _connectedAccount.value = null
        MimirConnectedArtGate.setOpen(false)
    }

    private fun recomputeGate(
        credentialsHealthy: Boolean,
        bindingMatches: Boolean,
        providerNeutralOptedIn: Boolean,
    ) {
        MimirConnectedArtGate.setOpen(
            gateShouldOpen(
                optedIn = _optedIn.value,
                devConfigured = ScreenScraperDevRegistration.isConfigured,
                credentialsHealthy = credentialsHealthy,
                bindingMatches = bindingMatches,
                providerNeutralOptedIn = providerNeutralOptedIn,
                storageAvailableForProcess = !storageUnavailableForProcess,
            ),
        )
    }

    fun gateShouldOpen(
        optedIn: Boolean,
        devConfigured: Boolean,
        credentialsHealthy: Boolean,
        bindingMatches: Boolean = true,
        providerNeutralOptedIn: Boolean = true,
        storageAvailableForProcess: Boolean = true,
    ): Boolean =
        optedIn &&
            devConfigured &&
            credentialsHealthy &&
            bindingMatches &&
            providerNeutralOptedIn &&
            storageAvailableForProcess
}

internal const val ConnectedArtPreferences = "mimir_rewrite_connected_art"
internal const val ConnectedArtRecordFormat = 1
internal const val ConnectedArtKeyFormat = "coverArtRecordFormat"
internal const val ConnectedArtKeyOptedIn = "coverArtOptedIn"
internal const val ConnectedArtKeyAccount = "coverArtAccount"
internal const val ConnectedArtKeyPreviewVideo = "previewVideoOptedIn"
internal const val ConnectedArtKeyCredentialBinding = "coverArtCredentialBinding"

/**
 * The account's last known `ssuser.maxthreads` — how wide the artwork batch may run.
 *
 * NOT a credential and NOT load-bearing for the gate: it is a performance hint that happens to belong to
 * the connected account, which is why it lives beside the record rather than in a store of its own. Absent
 * or wrong-typed, it reads back as null and the batch falls to one lane — the behaviour before it existed.
 * It must NEVER mark a record malformed; see the deliberate omission in [ConnectedArtPreferences]' reader.
 *
 * Why it is persisted at all : it used to live only in memory, so every new
 * process began at one lane until a live `ssuserInfos` round trip answered — and a failed round trip
 * ERASED it. Measured on a 6-lane account: the first-run batch ran one game at a time, 58 games
 * in 14m17s of which only ~132s was actual work. Four games a minute, against ~78 at the account's width.
 */
internal const val ConnectedArtKeyMaxThreads = "coverArtMaxThreads"
internal val ConnectedArtPreferenceKeys = setOf(
    ConnectedArtKeyFormat,
    ConnectedArtKeyOptedIn,
    ConnectedArtKeyAccount,
    ConnectedArtKeyPreviewVideo,
    ConnectedArtKeyCredentialBinding,
    ConnectedArtKeyMaxThreads,
)

internal fun connectedArtRecordsMatch(
    provider: ConnectedArtRecordState.Active?,
    credential: ArtworkCredentialActivationSnapshot?,
    providerNeutralOptedIn: Boolean,
): Boolean =
    providerNeutralOptedIn &&
        provider != null &&
        credential != null &&
        provider.account == credential.username &&
        provider.credentialBinding == credential.bindingToken

internal fun providerCanSeedConnectedServicesMigration(
    provider: ConnectedArtRecordState,
): Boolean = provider is ConnectedArtRecordState.LegacyActive

internal class ConnectedArtStoredRecord(
    val malformed: Boolean,
    val format: Int?,
    val optedIn: Boolean,
    val account: String?,
    val previewVideoOptedIn: Boolean,
    val credentialBinding: String?,
    /** See [ConnectedArtKeyMaxThreads]. Last, with a default, so existing positional callers still read. */
    val maxThreads: Int? = null,
) {
    override fun toString(): String =
        "ConnectedArtStoredRecord(" +
            "malformed=$malformed, format=$format, optedIn=$optedIn, account=$account, " +
            "previewVideoOptedIn=$previewVideoOptedIn, credentialBinding=***, maxThreads=$maxThreads)"
}

internal sealed interface ConnectedArtRecordState {
    class Active(
        val account: String,
        val credentialBinding: String,
        val previewVideoOptedIn: Boolean,
    ) : ConnectedArtRecordState {
        override fun toString(): String =
            "ConnectedArtRecordState.Active(" +
                "account=$account, credentialBinding=***, previewVideoOptedIn=$previewVideoOptedIn)"
    }

    class LegacyActive(
        val account: String,
        val previewVideoOptedIn: Boolean,
    ) : ConnectedArtRecordState

    data object Inactive : ConnectedArtRecordState
    data object Unavailable : ConnectedArtRecordState
}

internal class ConnectedArtDurableSnapshot(
    val preferences: CredentialPreferenceSnapshot,
) {
    override fun toString(): String = "ConnectedArtDurableSnapshot(preferences=***)"
}

internal interface ConnectedArtRecordStore {
    fun read(): ConnectedArtStoredRecord
    fun writeActive(account: String, credentialBinding: String): Boolean
    fun writeInactive(): Boolean
    fun setPreviewVideo(enabled: Boolean): Boolean

    /** Persist (or, with null, forget) the account's thread allowance. See [ConnectedArtKeyMaxThreads]. */
    fun setMaxThreads(value: Int?): Boolean
    fun snapshot(): ConnectedArtDurableSnapshot?
    fun restore(snapshot: ConnectedArtDurableSnapshot): Boolean
}

internal class ConnectedArtActivationRepository(
    private val store: ConnectedArtRecordStore,
) {
    fun load(): ConnectedArtRecordState {
        val record = runCatching(store::read).getOrElse {
            return ConnectedArtRecordState.Unavailable
        }
        if (record.malformed) return ConnectedArtRecordState.Unavailable
        val legacyFormat = record.format == null
        val currentFormat = record.format == ConnectedArtRecordFormat
        if (!legacyFormat && !currentFormat) return ConnectedArtRecordState.Unavailable
        if (!record.optedIn) {
            return if (
                record.account == null &&
                record.credentialBinding == null &&
                !record.previewVideoOptedIn
            ) {
                ConnectedArtRecordState.Inactive
            } else {
                ConnectedArtRecordState.Unavailable
            }
        }
        val account = record.account
            ?.takeIf { it.isNotBlank() && it == it.trim() }
            ?: return ConnectedArtRecordState.Unavailable
        val binding = record.credentialBinding
        return if (legacyFormat && binding == null) {
            ConnectedArtRecordState.LegacyActive(account, record.previewVideoOptedIn)
        } else if (
            currentFormat &&
            !binding.isNullOrBlank() &&
            binding == binding.trim()
        ) {
            ConnectedArtRecordState.Active(account, binding, record.previewVideoOptedIn)
        } else {
            ConnectedArtRecordState.Unavailable
        }
    }

    fun activate(account: String, credentialBinding: String): ConnectedArtRecordState {
        if (
            account.isBlank() ||
            account != account.trim() ||
            credentialBinding.isBlank() ||
            credentialBinding != credentialBinding.trim()
        ) {
            return ConnectedArtRecordState.Unavailable
        }
        if (!runCatching { store.writeActive(account, credentialBinding) }.getOrDefault(false)) {
            return ConnectedArtRecordState.Unavailable
        }
        val persisted = load()
        return if (
            persisted is ConnectedArtRecordState.Active &&
            persisted.account == account &&
            persisted.credentialBinding == credentialBinding
        ) {
            persisted
        } else {
            ConnectedArtRecordState.Unavailable
        }
    }

    fun deactivate(): Boolean {
        if (!runCatching(store::writeInactive).getOrDefault(false)) return false
        return load() === ConnectedArtRecordState.Inactive
    }

    fun setPreviewVideo(enabled: Boolean): Boolean {
        val current = load()
        if (current !is ConnectedArtRecordState.Active) return false
        if (!runCatching { store.setPreviewVideo(enabled) }.getOrDefault(false)) return false
        val persisted = load()
        return persisted is ConnectedArtRecordState.Active &&
            persisted.account == current.account &&
            persisted.credentialBinding == current.credentialBinding &&
            persisted.previewVideoOptedIn == enabled
    }

    /**
     * Remember the account's thread allowance, active records only — an allowance with no connection to
     * describe is meaningless. Unlike [setPreviewVideo] this does NOT re-read to verify: the value steers
     * batch width, so a write that silently did not land costs a slow fetch, not a wrong one, and failing
     * the caller over it would be a worse trade than the lane it protects.
     */
    fun setMaxThreads(value: Int?): Boolean {
        if (load() !is ConnectedArtRecordState.Active) return false
        return runCatching { store.setMaxThreads(value) }.getOrDefault(false)
    }

    /** The persisted allowance, or null when unknown. Never throws: unknown is always a safe answer. */
    fun storedMaxThreads(): Int? = runCatching { store.read() }.getOrNull()
        ?.takeIf { !it.malformed && it.optedIn }
        ?.maxThreads

    fun snapshot(): ConnectedArtDurableSnapshot? =
        runCatching(store::snapshot).getOrNull()

    fun restore(snapshot: ConnectedArtDurableSnapshot): Boolean =
        runCatching { store.restore(snapshot) }.getOrDefault(false)
}

internal class SharedPreferencesConnectedArtRecordStore(
    private val preferences: SharedPreferences,
) : ConnectedArtRecordStore {
    override fun read(): ConnectedArtStoredRecord {
        val values = preferences.all
        val format = values.intOrNull(ConnectedArtKeyFormat)
        val optedIn = values.booleanOrDefault(ConnectedArtKeyOptedIn, false)
        val preview = values.booleanOrDefault(ConnectedArtKeyPreviewVideo, false)
        val account = values.stringOrNull(ConnectedArtKeyAccount)
        val binding = values.stringOrNull(ConnectedArtKeyCredentialBinding)
        val malformed =
            values.hasWrongOptionalIntType(ConnectedArtKeyFormat) ||
            optedIn == null ||
                preview == null ||
                values.hasWrongOptionalStringType(ConnectedArtKeyAccount) ||
                values.hasWrongOptionalStringType(ConnectedArtKeyCredentialBinding)
        return ConnectedArtStoredRecord(
            malformed = malformed,
            format = format,
            optedIn = optedIn ?: false,
            account = account,
            previewVideoOptedIn = preview ?: false,
            credentialBinding = binding,
            // Deliberately absent from `malformed` above: a junk thread hint must cost one lane, never the
            // whole connection. A wrong type reads as null here, exactly like a key that was never written.
            maxThreads = values.intOrNull(ConnectedArtKeyMaxThreads)?.takeIf { it >= 1 },
        )
    }

    override fun writeActive(account: String, credentialBinding: String): Boolean =
        preferences.commitCredentialEdit(ConnectedArtPreferenceKeys) {
            putInt(ConnectedArtKeyFormat, ConnectedArtRecordFormat)
            putBoolean(ConnectedArtKeyOptedIn, true)
            putString(ConnectedArtKeyAccount, account)
            putString(ConnectedArtKeyCredentialBinding, credentialBinding)
        }

    override fun writeInactive(): Boolean =
        preferences.commitCredentialEdit(ConnectedArtPreferenceKeys) {
            putInt(ConnectedArtKeyFormat, ConnectedArtRecordFormat)
            putBoolean(ConnectedArtKeyOptedIn, false)
            putBoolean(ConnectedArtKeyPreviewVideo, false)
            remove(ConnectedArtKeyAccount)
            remove(ConnectedArtKeyCredentialBinding)
            // The allowance describes the account that just went away; it must not outlive it.
            remove(ConnectedArtKeyMaxThreads)
        }

    override fun setPreviewVideo(enabled: Boolean): Boolean =
        preferences.commitCredentialEdit(ConnectedArtPreferenceKeys) {
            putBoolean(ConnectedArtKeyPreviewVideo, enabled)
        }

    override fun setMaxThreads(value: Int?): Boolean =
        preferences.commitCredentialEdit(ConnectedArtPreferenceKeys) {
            if (value == null) remove(ConnectedArtKeyMaxThreads) else putInt(ConnectedArtKeyMaxThreads, value)
        }

    override fun snapshot(): ConnectedArtDurableSnapshot? =
        preferences.snapshotCredentialValues(ConnectedArtPreferenceKeys)
            ?.let(::ConnectedArtDurableSnapshot)

    override fun restore(snapshot: ConnectedArtDurableSnapshot): Boolean =
        preferences.restoreCredentialValues(ConnectedArtPreferenceKeys, snapshot.preferences)

    private fun Map<String, *>.booleanOrDefault(key: String, default: Boolean): Boolean? =
        if (!containsKey(key)) default else this[key] as? Boolean

    private fun Map<String, *>.intOrNull(key: String): Int? =
        if (!containsKey(key)) null else this[key] as? Int

    private fun Map<String, *>.stringOrNull(key: String): String? =
        if (!containsKey(key)) null else this[key] as? String

    private fun Map<String, *>.hasWrongOptionalStringType(key: String): Boolean =
        containsKey(key) && this[key] !is String

    private fun Map<String, *>.hasWrongOptionalIntType(key: String): Boolean =
        containsKey(key) && this[key] !is Int
}
