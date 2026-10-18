package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.TomeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal const val HomeLaunchReturnDurabilityRetryMs = 250L
private const val HomeLaunchReturnSchemaVersion = 6
private val TolerantHomeLaunchReturnSchemaVersions = setOf(1, 2, 3, 4, 5, 6)

/** Every field required to reconstruct one exact typed Home return identity after process death. */
internal data class HomeLaunchReturnRecord(
    val schemaVersion: Int?,
    val kind: String?,
    val identity: String?,
    val rank: Long?,
    val token: String?,
    /** Legacy v1-v3 decode only. Modern writers always persist null. */
    val contextKey: String? = null,
    val tomeKind: String? = null,
    val tomeIdentity: String? = null,
    val tomeOrigin: String? = null,
    val browserFocusedIndex: Int? = null,
    val browserFirstVisibleIndex: Int? = null,
    val tomeGeneration: Long? = null,
    val rootKind: String? = null,
    val rootIdentity: String? = null,
    val fallbackRootKind: String? = null,
    val fallbackRootIdentity: String? = null,
    val homeViewMode: String? = null,
)

internal data class HomeLaunchReturnHandle(
    val target: HomeLaunchReturnTarget,
    val token: String,
    val tomeSession: HomeTomeSession? = null,
    val homeViewMode: HomeViewMode = tomeSession?.rootMode ?: HomeViewMode.EDITORS_CHOICE,
    /** Launch provenance, in memory only: never persisted, so a decoded handle always reads MAIN_HOME. */
    val origin: HomeLaunchOrigin = HomeLaunchOrigin.MAIN_HOME,
) {
    companion object {
        fun create(
            target: HomeLaunchReturnTarget,
            tomeSession: HomeTomeSession? = null,
            homeViewMode: HomeViewMode = tomeSession?.rootMode ?: HomeViewMode.EDITORS_CHOICE,
            origin: HomeLaunchOrigin = HomeLaunchOrigin.MAIN_HOME,
        ): HomeLaunchReturnHandle =
            HomeLaunchReturnHandle(target, UUID.randomUUID().toString(), tomeSession, homeViewMode, origin)
    }
}

internal enum class HomeLaunchReturnAcknowledgement {
    CLEARED,
    SUPERSEDED,
    RETRY,
}

internal interface HomeLaunchReturnPersistence {
    fun read(): HomeLaunchReturnRecord
    fun replace(record: HomeLaunchReturnRecord): Boolean
    fun clear(): Boolean
}

/** Synchronous ledger used on an IO dispatcher in production and with durable fakes in JVM tests. */
internal class HomeLaunchReturnLedger(
    private val persistence: HomeLaunchReturnPersistence,
) {
    fun arm(handle: HomeLaunchReturnHandle): Boolean = runCatching {
        require(handle.target.itemId !is HomeShelfItemId.AndroidApp) {
            "Android Apps cannot arm a Home launch return"
        }
        // Rank 7c: a skip writes nothing and is still a SUCCESS. Returning false here is ARMING_FAILED, which
        // refuses the launch (dispatchHomeExternalLaunchAfterArm).
        if (!homeLaunchReturnArmDecision(handle.origin).arms) return@runCatching true
        persistence.replace(handle.toRecord())
    }.getOrDefault(false)

    fun peek(finalShelfEnabled: Boolean): HomeLaunchReturnHandle? {
        if (!finalShelfEnabled) return null
        val record = runCatching(persistence::read).getOrNull() ?: return null
        if (record.kind == HomeShelfItemKind.ANDROID_APP.name) {
            runCatching(persistence::clear)
            return null
        }
        return runCatching(record::toHandle).getOrNull()
    }

    /**
     * The armed record's token, read and never written: unlike [peek], an ANDROID_APP record is left where it is (that
     * branch of [peek] clears it), and nothing is cleared, rewritten or finished. For a caller that only needs to know WHICH
     * launch is armed, such as the return cover's status line.
     */
    fun armedTokenOrNull(): String? =
        runCatching(persistence::read).getOrNull()
            ?.takeIf { it.kind != HomeShelfItemKind.ANDROID_APP.name }
            ?.let { runCatching(it::toHandle).getOrNull()?.token }

    fun acknowledge(expected: HomeLaunchReturnHandle): HomeLaunchReturnAcknowledgement {
        val current = runCatching { persistence.read().toHandle() }.getOrNull()
        // `origin` is never persisted, so a decoded record always reads MAIN_HOME. Compare only what the record
        // carries, or a Bottom-card handle could never clear the arm it wrote: the Bottom cleanup, the dispatch
        // watchdog and a failed dispatch all acknowledge with the in-memory handle.
        val expectedRecord = expected.copy(origin = HomeLaunchOrigin.MAIN_HOME)
        if (current != null && current != expectedRecord) return HomeLaunchReturnAcknowledgement.SUPERSEDED
        return if (runCatching(persistence::clear).getOrDefault(false)) {
            HomeLaunchReturnAcknowledgement.CLEARED
        } else {
            HomeLaunchReturnAcknowledgement.RETRY
        }
    }

    fun clear(): Boolean = runCatching(persistence::clear).getOrDefault(false)

    fun clearWhenFinalShelfDisabled(finalShelfEnabled: Boolean): Boolean =
        finalShelfEnabled || clear()

    fun remapGameId(fromGameId: GameId, toGameId: GameId): Boolean {
        val current = runCatching(persistence::read).getOrNull() ?: return true
        val next = remapHomeLaunchReturnRecord(current, fromGameId, toGameId)
        return current == next || runCatching { persistence.replace(next) }.getOrDefault(false)
    }

    fun remapGameIds(aliases: Map<GameId, GameId>): Boolean {
        val current = runCatching(persistence::read).getOrNull() ?: return true
        val next = aliases.entries.fold(current) { record, (fromGameId, toGameId) ->
            remapHomeLaunchReturnRecord(record, fromGameId, toGameId)
        }
        return current == next || runCatching { persistence.replace(next) }.getOrDefault(false)
    }
}

internal enum class HomeExternalLaunchAttempt {
    ARMING_FAILED,
    DISPATCHED,
    DISPATCH_FAILED,
}

/** Dispatch is unreachable until the return record's durable write has been acknowledged. */
internal suspend fun dispatchHomeExternalLaunchAfterArm(
    arm: suspend () -> Boolean,
    dispatch: suspend () -> Boolean,
): HomeExternalLaunchAttempt {
    val armed = try {
        arm()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!armed) return HomeExternalLaunchAttempt.ARMING_FAILED
    val dispatched = try {
        dispatch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    return if (dispatched) {
        HomeExternalLaunchAttempt.DISPATCHED
    } else {
        HomeExternalLaunchAttempt.DISPATCH_FAILED
    }
}

/** Process-death-safe identity for an external launch that originated on Home Shelf. */
internal object HomeLaunchReturnStore {
    private const val PreferencesName = "mimir_home_launch_return"
    private const val LogTag = "MimirHomeShelf"
    private const val SchemaVersionKey = "schema_version"
    private const val KindKey = "kind"
    private const val IdentityKey = "identity"
    private const val RankKey = "rank"
    private const val TokenKey = "token"
    private const val ContextKey = "context"
    private const val TomeKindKey = "tome_kind"
    private const val TomeIdentityKey = "tome_identity"
    private const val TomeOriginKey = "tome_origin"
    private const val BrowserFocusKey = "browser_focus"
    private const val BrowserScrollKey = "browser_scroll"
    private const val TomeGenerationKey = "tome_generation"
    private const val RootKindKey = "root_kind"
    private const val RootIdentityKey = "root_identity"
    private const val FallbackRootKindKey = "fallback_root_kind"
    private const val FallbackRootIdentityKey = "fallback_root_identity"
    private const val HomeViewModeKey = "home_view_mode"
    private val mutationMutex = Mutex()

    suspend fun arm(context: Context, handle: HomeLaunchReturnHandle): Boolean {
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val resolved = handle.resolveCurrentGameIds(context)
                ledger(context).arm(resolved).also { committed ->
                    runCatching { Log.i(LogTag, homeLaunchReturnArmLogMessage(resolved, committed)) }
                }
            }
        }
    }

    suspend fun peek(
        context: Context,
        finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
    ): HomeLaunchReturnHandle? = withContext(Dispatchers.IO) {
        if (!finalShelfEnabled) return@withContext null
        val handle = mutationMutex.withLock { ledger(context).peek(finalShelfEnabled = true) }
        // Rank 7c follow-up: every Main return reads this (Home's ON_RESUME observer and the cold-start load), and
        // an armed launch's EXTERNAL ELAPSED is finished from the record it returns. A skipped arm has no record,
        // so a MAIN measurement that a dead process left open is finished here instead. Outside this store's
        // lock, and never allowed to fail the read: the return value is unchanged, so nothing here raises the
        // return cover or seeds focus.
        if (handle == null) {
            try {
                HomeExternalSessionElapsedStore.finishOrphanedMainReturn(context, SystemClock.elapsedRealtime())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Measurement bookkeeping never breaks the return read.
            }
        }
        handle
    }

    /**
     * The armed record's token and nothing else. Unlike [peek] it finishes no orphaned measurement when the record is
     * absent: the return cover's status line reads this to learn WHICH launch is being returned from, and a line of
     * words must not move the elapsed-time bookkeeping a return owns.
     */
    suspend fun armedToken(
        context: Context,
        finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
    ): String? = withContext(Dispatchers.IO) {
        if (!finalShelfEnabled) return@withContext null
        mutationMutex.withLock { ledger(context).armedTokenOrNull() }
    }

    suspend fun acknowledge(
        context: Context,
        expected: HomeLaunchReturnHandle,
    ): HomeLaunchReturnAcknowledgement {
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                ledger(context).acknowledge(expected.resolveCurrentGameIds(context))
            }
        }
    }

    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock { ledger(context).clear() }
    }

    suspend fun clearWhenFinalShelfDisabled(
        context: Context,
        finalShelfEnabled: Boolean = BuildConfig.FINAL_SHELF_ENABLED,
    ): Boolean {
        if (finalShelfEnabled) return true
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                ledger(context).clearWhenFinalShelfDisabled(finalShelfEnabled = false)
            }
        }
    }

    suspend fun remapGameId(context: Context, fromGameId: GameId, toGameId: GameId): Boolean =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock { ledger(context).remapGameId(fromGameId, toGameId) }
        }

    suspend fun remapGameIds(
        context: Context,
        aliases: Map<GameId, GameId>,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock { ledger(context).remapGameIds(aliases) }
    }

    private fun ledger(context: Context): HomeLaunchReturnLedger {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return HomeLaunchReturnLedger(SharedPreferencesHomeLaunchReturnPersistence(preferences))
    }

    private class SharedPreferencesHomeLaunchReturnPersistence(
        private val preferences: SharedPreferences,
    ) : HomeLaunchReturnPersistence {
        override fun read(): HomeLaunchReturnRecord = HomeLaunchReturnRecord(
            schemaVersion = preferences.readIntOrNull(SchemaVersionKey),
            kind = preferences.readStringOrNull(KindKey),
            identity = preferences.readStringOrNull(IdentityKey),
            rank = preferences.readLongOrNull(RankKey),
            token = preferences.readStringOrNull(TokenKey),
            contextKey = preferences.readStringOrNull(ContextKey),
            tomeKind = preferences.readStringOrNull(TomeKindKey),
            tomeIdentity = preferences.readStringOrNull(TomeIdentityKey),
            tomeOrigin = preferences.readStringOrNull(TomeOriginKey),
            browserFocusedIndex = preferences.readIntOrNull(BrowserFocusKey),
            browserFirstVisibleIndex = preferences.readIntOrNull(BrowserScrollKey),
            tomeGeneration = preferences.readLongOrNull(TomeGenerationKey),
            rootKind = preferences.readStringOrNull(RootKindKey),
            rootIdentity = preferences.readStringOrNull(RootIdentityKey),
            fallbackRootKind = preferences.readStringOrNull(FallbackRootKindKey),
            fallbackRootIdentity = preferences.readStringOrNull(FallbackRootIdentityKey),
            homeViewMode = preferences.readStringOrNull(HomeViewModeKey),
        )

        override fun replace(record: HomeLaunchReturnRecord): Boolean {
            val editor = preferences.edit()
                .clear()
                .putInt(SchemaVersionKey, requireNotNull(record.schemaVersion))
                .putString(KindKey, requireNotNull(record.kind))
                .putString(IdentityKey, requireNotNull(record.identity))
                .putLong(RankKey, requireNotNull(record.rank))
                .putString(TokenKey, requireNotNull(record.token))
            record.contextKey?.let { editor.putString(ContextKey, it) }
            record.tomeKind?.let { editor.putString(TomeKindKey, it) }
            record.tomeIdentity?.let { editor.putString(TomeIdentityKey, it) }
            record.tomeOrigin?.let { editor.putString(TomeOriginKey, it) }
            record.browserFocusedIndex?.let { editor.putInt(BrowserFocusKey, it) }
            record.browserFirstVisibleIndex?.let { editor.putInt(BrowserScrollKey, it) }
            record.tomeGeneration?.let { editor.putLong(TomeGenerationKey, it) }
            record.rootKind?.let { editor.putString(RootKindKey, it) }
            record.rootIdentity?.let { editor.putString(RootIdentityKey, it) }
            record.fallbackRootKind?.let { editor.putString(FallbackRootKindKey, it) }
            record.fallbackRootIdentity?.let { editor.putString(FallbackRootIdentityKey, it) }
            record.homeViewMode?.let { editor.putString(HomeViewModeKey, it) }
            return editor.commit()
        }

        override fun clear(): Boolean = preferences.edit().clear().commit()

        private fun SharedPreferences.readStringOrNull(key: String): String? =
            if (contains(key)) runCatching { getString(key, null) }.getOrNull() else null

        private fun SharedPreferences.readIntOrNull(key: String): Int? =
            if (contains(key)) runCatching { getInt(key, 0) }.getOrNull() else null

        private fun SharedPreferences.readLongOrNull(key: String): Long? =
            if (contains(key)) runCatching { getLong(key, 0L) }.getOrNull() else null
    }

}

internal fun remapHomeLaunchReturnRecord(
    record: HomeLaunchReturnRecord,
    fromGameId: GameId,
    toGameId: GameId,
): HomeLaunchReturnRecord {
    val from = fromGameId.value
    val to = toGameId.value
    return record.copy(
        identity = if (record.kind == HomeShelfItemKind.EMULATED_GAME.name && record.identity == from) {
            to
        } else {
            record.identity
        },
        rootIdentity = if (record.rootKind == "GAME_EMULATED_GAME" && record.rootIdentity == from) {
            to
        } else {
            record.rootIdentity
        },
        fallbackRootIdentity =
            if (record.fallbackRootKind == "GAME_EMULATED_GAME" && record.fallbackRootIdentity == from) {
                to
            } else {
                record.fallbackRootIdentity
            },
    )
}

internal fun remapHomeLaunchReturnHandle(
    handle: HomeLaunchReturnHandle,
    fromGameId: GameId,
    toGameId: GameId,
): HomeLaunchReturnHandle {
    if (fromGameId == toGameId) return handle
    fun HomeShelfItemId.remapped(): HomeShelfItemId = when (this) {
        is HomeShelfItemId.Emulated ->
            if (gameId == fromGameId) HomeShelfItemId.Emulated(toGameId) else this
        is HomeShelfItemId.AndroidGame,
        is HomeShelfItemId.AndroidApp -> this
    }
    fun HomeShelfEntry?.remapped(): HomeShelfEntry? = when (val entry = this) {
        is HomeShelfEntry.Game -> HomeShelfEntry.Game(entry.itemId.remapped())
        is HomeShelfEntry.Tome,
        null -> entry
    }
    return handle.copy(
        target = handle.target.copy(itemId = handle.target.itemId.remapped()),
        tomeSession = handle.tomeSession?.let { session ->
            session.copy(
                rootEntry = session.rootEntry.remapped(),
                rootFallbackEntry = session.rootFallbackEntry.remapped(),
            )
        },
    )
}

private suspend fun HomeLaunchReturnHandle.resolveCurrentGameIds(
    context: Context,
): HomeLaunchReturnHandle {
    fun HomeShelfEntry?.gameId(): GameId? =
        ((this as? HomeShelfEntry.Game)?.itemId as? HomeShelfItemId.Emulated)?.gameId
    val ids = buildList {
        (target.itemId as? HomeShelfItemId.Emulated)?.gameId?.let(::add)
        tomeSession?.rootEntry.gameId()?.let(::add)
        tomeSession?.rootFallbackEntry.gameId()?.let(::add)
    }
    val aliases = GameIdentityAliasResolver.resolve(context, ids)
    return aliases.entries.fold(this) { current, (oldId, newId) ->
        remapHomeLaunchReturnHandle(current, oldId, newId)
    }
}

private fun HomeLaunchReturnHandle.toRecord(): HomeLaunchReturnRecord {
    val tome = tomeSession?.tomeId
    val root = tomeSession?.rootEntry
    val fallbackRoot = tomeSession?.rootFallbackEntry
    return HomeLaunchReturnRecord(
        schemaVersion = HomeLaunchReturnSchemaVersion,
        kind = target.itemId.kind.name,
        identity = target.itemId.rawIdentity(),
        rank = target.rank,
        token = token,
        contextKey = null,
        tomeKind = tome?.let {
            when (it) {
                is TomeId.Platform -> "PLATFORM"
                is TomeId.Custom -> "CUSTOM"
                TomeId.HomeOverview -> "HOME_OVERVIEW"
            }
        },
        tomeIdentity = tome?.rawIdentity(),
        tomeOrigin = tomeSession?.origin?.name,
        browserFocusedIndex = tomeSession?.browserFocusedIndex,
        browserFirstVisibleIndex = tomeSession?.browserFirstVisibleIndex,
        tomeGeneration = tomeSession?.generation,
        rootKind = root?.rootKind(),
        rootIdentity = root?.rootIdentity(),
        fallbackRootKind = fallbackRoot?.rootKind(),
        fallbackRootIdentity = fallbackRoot?.rootIdentity(),
        homeViewMode = homeViewMode.name,
    )
}

private fun HomeLaunchReturnRecord.toHandle(): HomeLaunchReturnHandle? {
    val schema = schemaVersion ?: return null
    if (schema !in TolerantHomeLaunchReturnSchemaVersions) {
        return null
    }
    val parsedKind = kind
        ?.let { runCatching { HomeShelfItemKind.valueOf(it) }.getOrNull() }
        ?: return null
    val parsedIdentity = identity?.takeIf(String::isNotBlank) ?: return null
    val parsedRank = rank ?: return null
    val itemId = runCatching {
        when (parsedKind) {
            HomeShelfItemKind.EMULATED_GAME -> HomeShelfItemId.Emulated(GameId(parsedIdentity))
            HomeShelfItemKind.ANDROID_GAME ->
                HomeShelfItemId.AndroidGame(AndroidPackageName(parsedIdentity))
            HomeShelfItemKind.ANDROID_APP -> return null
        }
    }.getOrNull() ?: return null
    val target = HomeLaunchReturnTarget(
        itemId = itemId,
        rank = parsedRank,
        contextKey = contextKey.takeIf { schemaVersion in 1..3 },
    )
    val parsedToken = if (schemaVersion == 1) {
        "legacy:${parsedKind.name}:$parsedIdentity:$parsedRank"
    } else {
        token?.takeIf(String::isNotBlank) ?: return null
    }
    val parsedHomeViewMode = if (schema >= 6) {
        homeViewMode?.let { runCatching { HomeViewMode.valueOf(it) }.getOrNull() } ?: return null
    } else {
        HomeViewMode.EDITORS_CHOICE
    }
    val session = if (schemaVersion in 4..HomeLaunchReturnSchemaVersion && tomeKind != null) {
        val tomeId = parseTomeId(tomeKind, tomeIdentity) ?: return null
        val origin = tomeOrigin?.let { runCatching { HomeTomeOrigin.valueOf(it) }.getOrNull() } ?: return null
        val generation = if (schemaVersion == 4) 0L else tomeGeneration ?: return null
        HomeTomeSession(
            tomeId = tomeId,
            origin = origin,
            rootEntry = parseRootEntry(rootKind, rootIdentity),
            rootMode = parsedHomeViewMode,
            rootFallbackEntry = parseRootEntry(fallbackRootKind, fallbackRootIdentity),
            browserFocusedIndex = browserFocusedIndex,
            browserFirstVisibleIndex = browserFirstVisibleIndex,
            generation = generation,
        )
    } else null
    return HomeLaunchReturnHandle(target, parsedToken, session, parsedHomeViewMode)
}

/**
 * The one line [HomeLaunchReturnStore.arm] logs per launch (Rank 7c: nothing was logged when the return cover
 * rose or lifted). `armed` only when the record committed. A decision to arm whose write did not commit logs
 * `skipped` with `reason=arm_not_committed`, because that launch is then refused as ARMING_FAILED.
 */
internal fun homeLaunchReturnArmLogMessage(handle: HomeLaunchReturnHandle, committed: Boolean): String {
    val decision = homeLaunchReturnArmDecision(handle.origin)
    val verb = if (decision.arms && committed) "armed" else "skipped"
    val reason = if (decision.arms && !committed) "arm_not_committed" else decision.reason
    return "home launch return $verb identity=${handle.target.itemId.rawIdentity()} " +
        "origin=${handle.origin.name} reason=$reason"
}

private fun HomeShelfItemId.rawIdentity(): String = when (this) {
    is HomeShelfItemId.Emulated -> gameId.value
    is HomeShelfItemId.AndroidGame -> packageName.value
    is HomeShelfItemId.AndroidApp -> packageName.value
}

private fun TomeId.rawIdentity(): String = when (this) {
    is TomeId.Platform -> platformId.value
    is TomeId.Custom -> id.toString()
    TomeId.HomeOverview -> "home"
}

private fun parseTomeId(kind: String, identity: String?): TomeId? = runCatching {
    when (kind) {
        "PLATFORM" -> TomeId.Platform(PlatformId(requireNotNull(identity)))
        "CUSTOM" -> TomeId.Custom(UUID.fromString(requireNotNull(identity)))
        "HOME_OVERVIEW" -> TomeId.HomeOverview
        else -> null
    }
}.getOrNull()

private fun HomeShelfEntry.rootKind(): String = when (this) {
    is HomeShelfEntry.Game -> when (itemId) {
        is HomeShelfItemId.Emulated,
        is HomeShelfItemId.AndroidGame -> "GAME_${itemId.kind.name}"
        is HomeShelfItemId.AndroidApp -> error("Android Apps cannot be a Home return root")
    }
    is HomeShelfEntry.Tome -> when (tomeId) {
        is TomeId.Platform -> "TOME_PLATFORM"
        is TomeId.Custom -> "TOME_CUSTOM"
        TomeId.HomeOverview -> "TOME_HOME_OVERVIEW"
    }
}

private fun HomeShelfEntry.rootIdentity(): String = when (this) {
    is HomeShelfEntry.Game -> itemId.rawIdentity()
    is HomeShelfEntry.Tome -> tomeId.rawIdentity()
}

private fun parseRootEntry(kind: String?, identity: String?): HomeShelfEntry? {
    val value = identity ?: return null
    return runCatching {
        when (kind) {
            "GAME_EMULATED_GAME" -> HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId(value)))
            "GAME_ANDROID_GAME" -> HomeShelfEntry.Game(HomeShelfItemId.AndroidGame(AndroidPackageName(value)))
            "TOME_PLATFORM" -> HomeShelfEntry.Tome(TomeId.Platform(PlatformId(value)))
            "TOME_CUSTOM" -> HomeShelfEntry.Tome(TomeId.Custom(UUID.fromString(value)))
            "TOME_HOME_OVERVIEW" -> HomeShelfEntry.Tome(TomeId.HomeOverview)
            else -> null
        }
    }.getOrNull()
}
