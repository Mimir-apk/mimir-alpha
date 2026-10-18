package com.gruning.mimir.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val HomeSurfaceSchemaVersion = 3

internal enum class HomeViewMode {
    EDITORS_CHOICE,
    PINNED_TOMES,
}

/** The collection's name as Home's eyebrow shows it (`HomeShelfCoordinator.surfaceLabel`). */
internal fun homeViewModeLabel(res: Resources, mode: HomeViewMode): String = res.getString(
    when (mode) {
        HomeViewMode.EDITORS_CHOICE -> R.string.app_home_surface_editors_choice
        HomeViewMode.PINNED_TOMES -> R.string.app_home_surface_pinned_tomes
    },
)

/** Durable root identity. Labels and prior context strings are never navigation identity. */
internal sealed interface HomeRootEntryRecord {
    data class Game(val itemId: HomeShelfItemId) : HomeRootEntryRecord
    data class Tome(val tomeId: TomeId) : HomeRootEntryRecord
}

internal data class HomeSurfaceSnapshot(
    val mode: HomeViewMode = HomeViewMode.EDITORS_CHOICE,
    val editorsChoiceRoot: HomeRootEntryRecord? = null,
    val pinnedTomesRoot: HomeRootEntryRecord.Tome? = null,
) {
    val rootEntry: HomeRootEntryRecord?
        get() = when (mode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceRoot
            HomeViewMode.PINNED_TOMES -> pinnedTomesRoot
        }
}

/** `contextKey` remains solely to decode schema-v1 records written before Favorite Home. */
internal data class HomeSurfaceRecord(
    val schemaVersion: Int?,
    val contextKey: String?,
    val kind: String?,
    val identity: String?,
    val mode: String? = null,
    val editorsChoiceKind: String? = null,
    val editorsChoiceIdentity: String? = null,
    val pinnedTomesKind: String? = null,
    val pinnedTomesIdentity: String? = null,
)

internal interface HomeSurfacePersistence {
    fun read(): HomeSurfaceRecord
    fun replace(record: HomeSurfaceRecord): Boolean
}

internal class HomeSurfaceLedger(
    private val persistence: HomeSurfacePersistence,
) {
    fun read(): HomeSurfaceSnapshot? = runCatching {
        val record = persistence.read()
        if (record.schemaVersion == 1 && record.kind == HomeShelfItemKind.ANDROID_APP.name) {
            persistence.replace(HomeSurfaceSnapshot().toRecord())
            null
        } else {
            record.toSnapshot()
        }
    }.getOrNull()

    fun replace(snapshot: HomeSurfaceSnapshot): Boolean = runCatching {
        persistence.replace(snapshot.toRecord())
    }.getOrDefault(false)
}

internal sealed interface HomeSurfaceRestoreResolution {
    data object AwaitingTruth : HomeSurfaceRestoreResolution
    data class Ready(val snapshot: HomeSurfaceSnapshot) : HomeSurfaceRestoreResolution
}

internal fun resolveHomeSurfaceRestore(
    stored: HomeSurfaceSnapshot,
    editorsChoiceEntries: List<HomeShelfEntry>?,
    pinnedTomesEntries: List<HomeShelfEntry>?,
): HomeSurfaceRestoreResolution {
    val editorsChoice = editorsChoiceEntries ?: return HomeSurfaceRestoreResolution.AwaitingTruth
    val pinnedTomes = pinnedTomesEntries ?: return HomeSurfaceRestoreResolution.AwaitingTruth
    val resolvedEditorsChoice = editorsChoice
        .firstOrNull { it.toRootRecord() == stored.editorsChoiceRoot }
        ?.toRootRecord()
        ?: editorsChoice.firstOrNull()?.toRootRecord()
    val resolvedPinnedTomes = pinnedTomes
        .firstOrNull { it.toRootRecord() == stored.pinnedTomesRoot }
        ?.toRootRecord() as? HomeRootEntryRecord.Tome
        ?: pinnedTomes.firstOrNull()?.toRootRecord() as? HomeRootEntryRecord.Tome
    val resolvedMode = if (
        stored.mode == HomeViewMode.PINNED_TOMES && resolvedPinnedTomes != null
    ) {
        HomeViewMode.PINNED_TOMES
    } else {
        HomeViewMode.EDITORS_CHOICE
    }
    return HomeSurfaceRestoreResolution.Ready(
        HomeSurfaceSnapshot(
            mode = resolvedMode,
            editorsChoiceRoot = resolvedEditorsChoice,
            pinnedTomesRoot = resolvedPinnedTomes,
        ),
    )
}

internal fun remapHomeSurfaceGameId(
    snapshot: HomeSurfaceSnapshot,
    fromGameId: GameId,
    toGameId: GameId,
): HomeSurfaceSnapshot {
    fun HomeRootEntryRecord?.remapped(): HomeRootEntryRecord? = when (val root = this) {
        is HomeRootEntryRecord.Game -> when (val item = root.itemId) {
            is HomeShelfItemId.Emulated ->
                if (item.gameId == fromGameId) {
                    HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(toGameId))
                } else {
                    root
                }
            is HomeShelfItemId.AndroidGame,
            is HomeShelfItemId.AndroidApp -> root
        }
        is HomeRootEntryRecord.Tome,
        null -> root
    }
    return snapshot.copy(editorsChoiceRoot = snapshot.editorsChoiceRoot.remapped())
}

internal object HomeSurfaceStateStore {
    private const val PreferencesName = "mimir_home_surface"
    private const val SchemaKey = "schema_version"
    private const val ContextKey = "context"
    private const val KindKey = "kind"
    private const val IdentityKey = "identity"
    private const val ModeKey = "mode"
    private const val EditorsChoiceKindKey = "editors_choice_kind"
    private const val EditorsChoiceIdentityKey = "editors_choice_identity"
    private const val PinnedTomesKindKey = "pinned_tomes_kind"
    private const val PinnedTomesIdentityKey = "pinned_tomes_identity"

    private val mutationMutex = Mutex()
    private val latestRevision = AtomicLong(0L)

    suspend fun read(context: Context): HomeSurfaceSnapshot? = withContext(Dispatchers.IO) {
        mutationMutex.withLock { ledger(context).read() }
    }

    fun nextRevision(): Long = latestRevision.incrementAndGet()

    /** Revision-checked durable acknowledgement; both the preference write and lock stay on IO. */
    suspend fun replace(context: Context, snapshot: HomeSurfaceSnapshot, revision: Long): Boolean {
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val gameId = ((snapshot.editorsChoiceRoot as? HomeRootEntryRecord.Game)?.itemId
                    as? HomeShelfItemId.Emulated)?.gameId
                val currentSnapshot = gameId?.let { oldId ->
                    val currentId = GameIdentityAliasResolver.resolve(context, oldId)
                    remapHomeSurfaceGameId(snapshot, oldId, currentId)
                } ?: snapshot
                if (revision != latestRevision.get()) return@withLock false
                ledger(context).replace(currentSnapshot)
            }
        }
    }

    suspend fun remapGameId(context: Context, fromGameId: GameId, toGameId: GameId): Boolean =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                // Invalidate every UI write that captured a revision before this identity transition.
                latestRevision.incrementAndGet()
                val target = ledger(context)
                val current = target.read() ?: return@withLock true
                val next = remapHomeSurfaceGameId(current, fromGameId, toGameId)
                current == next || target.replace(next)
            }
        }

    suspend fun remapGameIds(
        context: Context,
        aliases: Map<GameId, GameId>,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (aliases.isNotEmpty()) latestRevision.incrementAndGet()
            val target = ledger(context)
            val current = target.read() ?: return@withLock true
            val next = aliases.entries.fold(current) { snapshot, (fromGameId, toGameId) ->
                remapHomeSurfaceGameId(snapshot, fromGameId, toGameId)
            }
            current == next || target.replace(next)
        }
    }

    private fun ledger(context: Context): HomeSurfaceLedger {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return HomeSurfaceLedger(SharedPreferencesHomeSurfacePersistence(preferences))
    }

    private class SharedPreferencesHomeSurfacePersistence(
        private val preferences: SharedPreferences,
    ) : HomeSurfacePersistence {
        override fun read(): HomeSurfaceRecord = HomeSurfaceRecord(
            schemaVersion = preferences.intOrNull(SchemaKey),
            contextKey = preferences.stringOrNull(ContextKey),
            kind = preferences.stringOrNull(KindKey),
            identity = preferences.stringOrNull(IdentityKey),
            mode = preferences.stringOrNull(ModeKey),
            editorsChoiceKind = preferences.stringOrNull(EditorsChoiceKindKey),
            editorsChoiceIdentity = preferences.stringOrNull(EditorsChoiceIdentityKey),
            pinnedTomesKind = preferences.stringOrNull(PinnedTomesKindKey),
            pinnedTomesIdentity = preferences.stringOrNull(PinnedTomesIdentityKey),
        )

        override fun replace(record: HomeSurfaceRecord): Boolean {
            val editor = preferences.edit()
                .clear()
                .putInt(SchemaKey, requireNotNull(record.schemaVersion))
            record.mode?.let { editor.putString(ModeKey, it) }
            record.kind?.let { editor.putString(KindKey, it) }
            record.identity?.let { editor.putString(IdentityKey, it) }
            record.editorsChoiceKind?.let { editor.putString(EditorsChoiceKindKey, it) }
            record.editorsChoiceIdentity?.let { editor.putString(EditorsChoiceIdentityKey, it) }
            record.pinnedTomesKind?.let { editor.putString(PinnedTomesKindKey, it) }
            record.pinnedTomesIdentity?.let { editor.putString(PinnedTomesIdentityKey, it) }
            return editor.commit()
        }

        private fun SharedPreferences.stringOrNull(key: String): String? =
            if (contains(key)) runCatching { getString(key, null) }.getOrNull() else null

        private fun SharedPreferences.intOrNull(key: String): Int? =
            if (contains(key)) runCatching { getInt(key, 0) }.getOrNull() else null
    }
}

private fun HomeSurfaceSnapshot.toRecord(): HomeSurfaceRecord {
    val (editorsChoiceKind, editorsChoiceIdentity) = editorsChoiceRoot.toStoredIdentity()
    val (pinnedTomesKind, pinnedTomesIdentity) = pinnedTomesRoot.toStoredIdentity()
    return HomeSurfaceRecord(
        schemaVersion = HomeSurfaceSchemaVersion,
        contextKey = null,
        kind = null,
        identity = null,
        mode = mode.name,
        editorsChoiceKind = editorsChoiceKind,
        editorsChoiceIdentity = editorsChoiceIdentity,
        pinnedTomesKind = pinnedTomesKind,
        pinnedTomesIdentity = pinnedTomesIdentity,
    )
}

private fun HomeSurfaceRecord.toSnapshot(): HomeSurfaceSnapshot? {
    return when (schemaVersion) {
        HomeSurfaceSchemaVersion -> {
            val parsedMode = mode?.let { runCatching { HomeViewMode.valueOf(it) }.getOrNull() }
                ?: return null
            val editorsChoice = parseOptionalTypedRoot(editorsChoiceKind, editorsChoiceIdentity)
                ?: return null
            val pinnedTomes = parseOptionalTypedRoot(pinnedTomesKind, pinnedTomesIdentity)
                ?: return null
            if (pinnedTomes.value != null && pinnedTomes.value !is HomeRootEntryRecord.Tome) return null
            HomeSurfaceSnapshot(
                mode = parsedMode,
                editorsChoiceRoot = editorsChoice.value,
                pinnedTomesRoot = pinnedTomes.value as? HomeRootEntryRecord.Tome,
            )
        }
        2 -> {
            val root = if (kind == null && identity == null) {
                null
            } else {
                parseTypedRoot(kind, identity) ?: return null
            }
            HomeSurfaceSnapshot(editorsChoiceRoot = root)
        }
        1 -> parseLegacyGame(kind, identity)?.let { HomeSurfaceSnapshot(editorsChoiceRoot = it) }
        else -> null
    }
}

private data class OptionalRoot(val value: HomeRootEntryRecord?)

private fun parseOptionalTypedRoot(kind: String?, identity: String?): OptionalRoot? = when {
    kind == null && identity == null -> OptionalRoot(null)
    kind == null || identity == null -> null
    else -> parseTypedRoot(kind, identity)?.let(::OptionalRoot)
}

private fun HomeRootEntryRecord?.toStoredIdentity(): Pair<String?, String?> = when (val root = this) {
    null -> null to null
    is HomeRootEntryRecord.Game -> when (val item = root.itemId) {
        is HomeShelfItemId.Emulated -> "GAME_EMULATED" to item.gameId.value
        is HomeShelfItemId.AndroidGame -> "GAME_ANDROID" to item.packageName.value
        is HomeShelfItemId.AndroidApp -> error("Android Apps cannot be persisted in Editor's Choice")
    }
    is HomeRootEntryRecord.Tome -> when (val tome = root.tomeId) {
        is TomeId.Platform -> "TOME_PLATFORM" to tome.platformId.value
        is TomeId.Custom -> "TOME_CUSTOM" to tome.id.toString()
        TomeId.HomeOverview -> "TOME_HOME_OVERVIEW" to "home"
    }
}

private fun parseTypedRoot(kind: String?, identity: String?): HomeRootEntryRecord? {
    if (kind == null && identity == null) return null
    val value = identity?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        when (kind) {
            "GAME_EMULATED" -> HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId(value)))
            "GAME_ANDROID" -> HomeRootEntryRecord.Game(HomeShelfItemId.AndroidGame(AndroidPackageName(value)))
            "TOME_PLATFORM" -> HomeRootEntryRecord.Tome(TomeId.Platform(PlatformId(value)))
            "TOME_CUSTOM" -> HomeRootEntryRecord.Tome(TomeId.Custom(UUID.fromString(value)))
            "TOME_HOME_OVERVIEW" -> HomeRootEntryRecord.Tome(TomeId.HomeOverview)
            else -> null
        }
    }.getOrNull()
}

/** Valid schema-v1 game focus migrates; obsolete context and Android-App focus are discarded. */
private fun parseLegacyGame(kind: String?, identity: String?): HomeRootEntryRecord.Game? {
    val parsedKind = kind?.let { runCatching { HomeShelfItemKind.valueOf(it) }.getOrNull() } ?: return null
    val value = identity?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        when (parsedKind) {
            HomeShelfItemKind.EMULATED_GAME -> HomeRootEntryRecord.Game(HomeShelfItemId.Emulated(GameId(value)))
            HomeShelfItemKind.ANDROID_GAME -> HomeRootEntryRecord.Game(
                HomeShelfItemId.AndroidGame(AndroidPackageName(value)),
            )
            HomeShelfItemKind.ANDROID_APP -> null
        }
    }.getOrNull()
}

internal fun HomeShelfEntry.toRootRecord(): HomeRootEntryRecord = when (this) {
    is HomeShelfEntry.Game -> HomeRootEntryRecord.Game(itemId)
    is HomeShelfEntry.Tome -> HomeRootEntryRecord.Tome(tomeId)
}
