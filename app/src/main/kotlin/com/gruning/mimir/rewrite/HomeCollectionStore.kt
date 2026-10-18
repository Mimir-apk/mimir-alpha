package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The only game identities that Favorite-driven Home and Tomes may contain. */
sealed interface FavoriteMemberId {
    data class Emulated(val gameId: GameId) : FavoriteMemberId
    data class AndroidGame(val packageName: AndroidPackageName) : FavoriteMemberId
}

fun FavoriteMemberId.toHomeShelfItemId(): HomeShelfItemId = when (this) {
    is FavoriteMemberId.Emulated -> HomeShelfItemId.Emulated(gameId)
    is FavoriteMemberId.AndroidGame -> HomeShelfItemId.AndroidGame(packageName)
}

fun HomeShelfItemId.toFavoriteMemberIdOrNull(): FavoriteMemberId? = when (this) {
    is HomeShelfItemId.Emulated -> FavoriteMemberId.Emulated(gameId)
    is HomeShelfItemId.AndroidGame -> FavoriteMemberId.AndroidGame(packageName)
    is HomeShelfItemId.AndroidApp -> null
}

data class CustomHomeTome(
    val id: TomeId.Custom,
    val label: String,
    /** Durable member order. Nonfavorites remain here as dormant identities. */
    val members: List<FavoriteMemberId>,
    val manualOrderCommitted: Boolean = false,
    val createdAtMillis: Long? = null,
    /** Optional sparse visual seats. Null is an intentionally empty well; [members] remains membership truth. */
    val memberSlots: List<FavoriteMemberId?> = emptyList(),
)

data class PlatformTomeOrderOverlay(
    val platformId: PlatformId,
    /** Known identities first; newly favorited games append in stable Favorite order. */
    val gameOrder: List<GameId>,
    /** Optional sparse visual seats. Old documents omit this and project as a dense first page. */
    val gameSlots: List<GameId?> = emptyList(),
)

data class HomeTomeDocument(
    val pinOrder: List<TomeId> = emptyList(),
    val customTomes: List<CustomHomeTome> = emptyList(),
    val platformOrderOverlays: List<PlatformTomeOrderOverlay> = emptyList(),
    /** Sparse 6x3-page arrangement for the aggregate Home overview. */
    val homeShelfSlots: List<FavoriteMemberId?> = emptyList(),
)

sealed interface HomeTomeEditResult {
    data class Saved(val tomeId: TomeId.Custom) : HomeTomeEditResult
    data class Failed(val message: String) : HomeTomeEditResult
}

const val HomeTomeLabelMax = 14

/** App-owned Tome authority. Names are labels; only [TomeId] values identify Tomes. */
object HomeTomeStore {
    private const val TAG = "MimirHomeTomeStore"
    private const val PrefsName = "mimir_home_tomes"
    internal const val KeyV1 = "home_tomes.v1"

    private const val LegacyPrefsName = "mimir_home_collections"
    internal const val LegacyCollectionsKeyV1 = "collections.v1"
    internal const val LegacyCollectionsKeyV2 = "collections.v2"
    internal const val LegacyCollectionsKeyV3 = "collections.v3"

    private val state = MutableStateFlow(HomeTomeDocument())
    val document: StateFlow<HomeTomeDocument> get() = state
    private var loadedFrom: Context? = null
    private val mutationMutex = Mutex()

    /** Loads durable Tome truth without making composition or the main thread touch SharedPreferences. */
    suspend fun load(context: Context) = mutationMutex.withLock {
        ensureLoaded(context)
    }

    /** EP-04 replay: rewrite one retired emulated identity without disturbing Android/package members. */
    suspend fun remapGameId(context: Context, fromGameId: GameId, toGameId: GameId): Boolean =
        mutationMutex.withLock {
            ensureLoaded(context)
            val current = state.value
            val next = remapHomeTomeGameId(current, fromGameId, toGameId)
            current == next || persist(context, next)
        }

    suspend fun remapGameIds(
        context: Context,
        aliases: Map<GameId, GameId>,
    ): Boolean = mutationMutex.withLock {
        ensureLoaded(context)
        val current = state.value
        val next = aliases.entries.fold(current) { document, (fromGameId, toGameId) ->
            remapHomeTomeGameId(document, fromGameId, toGameId)
        }
        current == next || persist(context, next)
    }

    private suspend fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        if (loadedFrom === app) return
        val loaded = withContext(Dispatchers.IO) {
            val currentPrefs = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            val currentRaw = currentPrefs.getString(KeyV1, null)
            if (!currentRaw.isNullOrBlank()) {
                parseHomeTomeDocument(currentRaw)
            } else {
                val legacy = app.getSharedPreferences(LegacyPrefsName, Context.MODE_PRIVATE)
                importLegacyHomeTomes(
                    v3Raw = legacy.getString(LegacyCollectionsKeyV3, null),
                    v2Raw = legacy.getString(LegacyCollectionsKeyV2, null),
                    v1Raw = legacy.getString(LegacyCollectionsKeyV1, null),
                ).also { imported ->
                    // Migration is acknowledged durably before its result becomes authoritative.
                    check(
                        currentPrefs.edit()
                            .putString(KeyV1, encodeHomeTomeDocument(imported))
                            .commit(),
                    ) { "Couldn't persist imported Tomes" }
                }
            }
        }
        loadedFrom = app
        state.value = normalizeHomeTomeDocument(loaded)
    }

    suspend fun create(context: Context, label: String): HomeTomeEditResult = mutationMutex.withLock {
        ensureLoaded(context)
        val clean = label.trim()
        val current = state.value
        val error = homeTomeLabelError(clean, current.customTomes)
        if (error != null) return@withLock HomeTomeEditResult.Failed(error)
        val tomeId = TomeId.Custom(UUID.randomUUID())
        val next = current.copy(
            customTomes = current.customTomes + CustomHomeTome(
                id = tomeId,
                label = clean,
                members = emptyList(),
                createdAtMillis = nextHomeTomeCreatedAtMillis(current.customTomes, System.currentTimeMillis()),
            ),
        )
        if (persist(context, next)) HomeTomeEditResult.Saved(tomeId)
        else HomeTomeEditResult.Failed("Couldn’t save Tome")
    }

    suspend fun rename(
        context: Context,
        tomeId: TomeId.Custom,
        label: String,
    ): HomeTomeEditResult = mutationMutex.withLock {
        ensureLoaded(context)
        val clean = label.trim()
        val current = state.value
        if (current.customTomes.none { it.id == tomeId }) {
            return@withLock HomeTomeEditResult.Failed("This Tome is no longer available")
        }
        val error = homeTomeLabelError(clean, current.customTomes.filterNot { it.id == tomeId })
        if (error != null) return@withLock HomeTomeEditResult.Failed(error)
        val next = current.copy(
            customTomes = current.customTomes.map { tome ->
                if (tome.id == tomeId) tome.copy(label = clean) else tome
            },
        )
        if (persist(context, next)) HomeTomeEditResult.Saved(tomeId)
        else HomeTomeEditResult.Failed("Couldn’t save Tome")
    }

    /**
     * O-7 : now surfaces persist()'s Boolean to its
     * caller, same as every sibling mutator here (create/rename/delete/setPinned/...). The Home tile
     * menu's Tome checkbox (`HomeShelfCoordinator.toggleTomeMembership` -> `MimirAppCoordinator`'s
     * `onToggleCollection`) uses this to revert its optimistic flip on a refused write.
     */
    suspend fun toggleMember(context: Context, tomeId: TomeId.Custom, member: FavoriteMemberId): Boolean =
        mutationMutex.withLock {
            ensureLoaded(context)
            val currentMember = member.resolveCurrent(context)
            val saved = persist(
                context,
                state.value.copy(
                    customTomes = toggleCustomTomeMember(
                        state.value.customTomes,
                        tomeId,
                        currentMember,
                    ),
                ),
            )
            if (!saved) {
                Log.w(TAG, "toggleMember: Tome membership change for $tomeId was not saved")
            }
            saved
        }

    suspend fun delete(context: Context, tomeId: TomeId.Custom) = mutationMutex.withLock {
        ensureLoaded(context)
        persist(
            context,
            state.value.copy(
                customTomes = state.value.customTomes.filterNot { it.id == tomeId },
                pinOrder = state.value.pinOrder.filterNot { it == tomeId },
            ),
        )
    }

    suspend fun setPinned(context: Context, tomeId: TomeId, pinned: Boolean): Boolean = mutationMutex.withLock {
        ensureLoaded(context)
        if (tomeId == TomeId.HomeOverview) return@withLock false
        val pins = state.value.pinOrder.filterNot { it == tomeId }.toMutableList()
        if (pinned) pins += tomeId
        persist(context, state.value.copy(pinOrder = pins))
    }

    suspend fun setPinOrder(context: Context, ordered: List<TomeId>): Boolean = mutationMutex.withLock {
        ensureLoaded(context)
        val known = state.value.pinOrder.toSet()
        val complete = ordered.filter(known::contains).distinct() + state.value.pinOrder.filterNot(ordered::contains)
        persist(context, state.value.copy(pinOrder = complete))
    }

    suspend fun commitCustomMemberOrder(
        context: Context,
        tomeId: TomeId.Custom,
        ordered: List<FavoriteMemberId>,
    ): Boolean {
        return mutationMutex.withLock {
            ensureLoaded(context)
            val currentOrder = ordered.resolveCurrent(context)
            val next = commitCustomTomeMemberOrder(state.value.customTomes, tomeId, currentOrder)
            persist(context, state.value.copy(customTomes = next))
        }
    }

    suspend fun commitCustomMemberSlots(
        context: Context,
        tomeId: TomeId.Custom,
        slots: List<FavoriteMemberId?>,
    ): Boolean {
        return mutationMutex.withLock {
            ensureLoaded(context)
            val currentSlots = slots.resolveCurrentNullable(context)
            val next = commitCustomTomeMemberSlots(state.value.customTomes, tomeId, currentSlots)
            persist(context, state.value.copy(customTomes = next))
        }
    }

    suspend fun setPlatformOrderOverlay(
        context: Context,
        platformId: PlatformId,
        ordered: List<GameId>,
    ): Boolean {
        return mutationMutex.withLock {
            ensureLoaded(context)
            val currentOrder = ordered.resolveCurrentGameIds(context)
            val overlays = state.value.platformOrderOverlays.filterNot { it.platformId == platformId } +
                PlatformTomeOrderOverlay(platformId, currentOrder.distinct())
            persist(context, state.value.copy(platformOrderOverlays = overlays))
        }
    }

    suspend fun setPlatformSlotOverlay(
        context: Context,
        platformId: PlatformId,
        slots: List<GameId?>,
    ): Boolean {
        return mutationMutex.withLock {
            ensureLoaded(context)
            val currentSlots = slots.resolveCurrentGameIdsNullable(context)
            val visibleOrder = currentSlots.filterNotNull().distinct()
            val previous = state.value.platformOrderOverlays.firstOrNull { it.platformId == platformId }
            val completeOrder = visibleOrder + previous?.gameOrder.orEmpty().filterNot(visibleOrder::contains)
            val overlays = state.value.platformOrderOverlays.filterNot { it.platformId == platformId } +
                PlatformTomeOrderOverlay(
                    platformId = platformId,
                    gameOrder = completeOrder,
                    gameSlots = normalizeSparseTomeSlots(currentSlots, completeOrder.toSet()),
                )
            persist(context, state.value.copy(platformOrderOverlays = overlays))
        }
    }

    suspend fun commitHomeShelfSlots(
        context: Context,
        slots: List<FavoriteMemberId?>,
    ): Boolean = mutationMutex.withLock {
        ensureLoaded(context)
        val resolved = slots.resolveCurrentNullable(context)
        persist(
            context,
            state.value.copy(
                homeShelfSlots = normalizeSparseTomeSlots(resolved, resolved.filterNotNull().toSet()),
            ),
        )
    }

    private suspend fun persist(context: Context, next: HomeTomeDocument): Boolean {
        val normalized = normalizeHomeTomeDocument(next)
        val committed = withContext(Dispatchers.IO) {
            context.applicationContext
                .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(KeyV1, encodeHomeTomeDocument(normalized))
                .commit()
        }
        if (committed) state.value = normalized
        return committed
    }
}

private suspend fun FavoriteMemberId.resolveCurrent(context: Context): FavoriteMemberId = when (this) {
    is FavoriteMemberId.Emulated ->
        FavoriteMemberId.Emulated(GameIdentityAliasResolver.resolve(context, gameId))
    is FavoriteMemberId.AndroidGame -> this
}

private suspend fun List<FavoriteMemberId>.resolveCurrent(context: Context): List<FavoriteMemberId> {
    val emulated = filterIsInstance<FavoriteMemberId.Emulated>().map(FavoriteMemberId.Emulated::gameId)
    val aliases = GameIdentityAliasResolver.resolve(context, emulated)
    return map { member ->
        when (member) {
            is FavoriteMemberId.Emulated -> FavoriteMemberId.Emulated(aliases[member.gameId] ?: member.gameId)
            is FavoriteMemberId.AndroidGame -> member
        }
    }
}

private suspend fun List<FavoriteMemberId?>.resolveCurrentNullable(
    context: Context,
): List<FavoriteMemberId?> {
    val members = filterNotNull()
    val resolved = members.resolveCurrent(context).iterator()
    return map { member -> if (member == null) null else resolved.next() }
}

private suspend fun List<GameId>.resolveCurrentGameIds(context: Context): List<GameId> {
    val aliases = GameIdentityAliasResolver.resolve(context, this)
    return map { aliases[it] ?: it }
}

private suspend fun List<GameId?>.resolveCurrentGameIdsNullable(context: Context): List<GameId?> {
    val gameIds = filterNotNull()
    val aliases = GameIdentityAliasResolver.resolve(context, gameIds)
    return map { gameId -> gameId?.let { aliases[it] ?: it } }
}

fun normalizeHomeTomeDocument(document: HomeTomeDocument): HomeTomeDocument {
    val custom = document.customTomes.distinctBy(CustomHomeTome::id).map { tome ->
        val members = tome.members.distinct()
        tome.copy(
            members = members,
            memberSlots = normalizeSparseTomeSlots(tome.memberSlots, members.toSet()),
        )
    }
    val customIds = custom.mapTo(hashSetOf(), CustomHomeTome::id)
    val pins = document.pinOrder.distinct().filter { id ->
        id != TomeId.HomeOverview && (id !is TomeId.Custom || id in customIds)
    }
    val overlays = document.platformOrderOverlays.distinctBy(PlatformTomeOrderOverlay::platformId).map { overlay ->
        val gameOrder = (overlay.gameSlots.filterNotNull() + overlay.gameOrder).distinct()
        overlay.copy(
            gameOrder = gameOrder,
            gameSlots = normalizeSparseTomeSlots(overlay.gameSlots, gameOrder.toSet()),
        )
    }
    val overviewAllowed = document.homeShelfSlots.filterNotNull().toSet()
    val overviewSlots = normalizeSparseTomeSlots(document.homeShelfSlots, overviewAllowed)
    return HomeTomeDocument(pins, custom, overlays, overviewSlots)
}

internal fun remapHomeTomeGameId(
    document: HomeTomeDocument,
    fromGameId: GameId,
    toGameId: GameId,
): HomeTomeDocument {
    if (fromGameId == toGameId) return document
    fun FavoriteMemberId.remapped(): FavoriteMemberId = when (this) {
        is FavoriteMemberId.Emulated ->
            if (gameId == fromGameId) FavoriteMemberId.Emulated(toGameId) else this
        is FavoriteMemberId.AndroidGame -> this
    }
    return normalizeHomeTomeDocument(
        document.copy(
            customTomes = document.customTomes.map { tome ->
                tome.copy(
                    members = tome.members.map { it.remapped() },
                    memberSlots = tome.memberSlots.map { it?.remapped() },
                )
            },
            platformOrderOverlays = document.platformOrderOverlays.map { overlay ->
                overlay.copy(
                    gameOrder = overlay.gameOrder.map { if (it == fromGameId) toGameId else it },
                    gameSlots = overlay.gameSlots.map { gameId ->
                        if (gameId == fromGameId) toGameId else gameId
                    },
                )
            },
            homeShelfSlots = document.homeShelfSlots.map { it?.remapped() },
        ),
    )
}

fun homeTomeLabelError(clean: String, existing: List<CustomHomeTome>): String? = when {
    clean.isEmpty() -> "Name a Tome first"
    clean.length > HomeTomeLabelMax -> "Keep it under $HomeTomeLabelMax characters"
    existing.any { it.label.equals(clean, ignoreCase = true) } -> "That Tome name is already in use"
    else -> null
}

fun toggleCustomTomeMember(
    tomes: List<CustomHomeTome>,
    tomeId: TomeId.Custom,
    member: FavoriteMemberId,
): List<CustomHomeTome> = tomes.map { tome ->
    if (tome.id != tomeId) tome
    else if (member in tome.members) tome.copy(
        members = tome.members - member,
        memberSlots = tome.memberSlots.map { it.takeUnless(member::equals) },
    )
    else tome.copy(members = tome.members + member)
}

fun commitCustomTomeMemberOrder(
    tomes: List<CustomHomeTome>,
    tomeId: TomeId.Custom,
    ordered: List<FavoriteMemberId>,
): List<CustomHomeTome> = tomes.map { tome ->
    if (tome.id != tomeId) return@map tome
    val existing = tome.members.toSet()
    val complete = ordered.filter(existing::contains).distinct() + tome.members.filterNot(ordered::contains)
    tome.copy(
        members = complete,
        memberSlots = ordered.filter(existing::contains).distinct(),
        manualOrderCommitted = true,
    )
}

fun commitCustomTomeMemberSlots(
    tomes: List<CustomHomeTome>,
    tomeId: TomeId.Custom,
    slots: List<FavoriteMemberId?>,
): List<CustomHomeTome> = tomes.map { tome ->
    if (tome.id != tomeId) return@map tome
    val existing = tome.members.toSet()
    val normalizedSlots = normalizeSparseTomeSlots(slots, existing)
    val visibleOrder = normalizedSlots.filterNotNull()
    tome.copy(
        members = visibleOrder + tome.members.filterNot(visibleOrder::contains),
        memberSlots = normalizedSlots,
        manualOrderCommitted = true,
    )
}

internal fun <T> normalizeSparseTomeSlots(slots: List<T?>, allowed: Set<T>): List<T?> {
    if (slots.isEmpty()) return emptyList()
    val seen = hashSetOf<T>()
    return slots.map { identity ->
        identity?.takeIf { it in allowed && seen.add(it) }
    }
}

fun nextHomeTomeCreatedAtMillis(tomes: List<CustomHomeTome>, nowMillis: Long): Long {
    val previous = tomes.mapNotNull(CustomHomeTome::createdAtMillis).maxOrNull()
    val floor = when (previous) {
        null -> 1L
        Long.MAX_VALUE -> Long.MAX_VALUE
        else -> previous + 1L
    }
    return maxOf(nowMillis, floor, 1L)
}

fun encodeHomeTomeDocument(document: HomeTomeDocument): String {
    val normalized = normalizeHomeTomeDocument(document)
    return JSONObject()
        .put("version", 1)
        .put("pinOrder", JSONArray().also { array -> normalized.pinOrder.forEach { array.put(encodeTomeId(it)) } })
        .put("customTomes", JSONArray().also { array ->
            normalized.customTomes.forEach { tome ->
                array.put(
                    JSONObject()
                        .put("id", tome.id.id.toString())
                        .put("label", tome.label)
                        .put("members", JSONArray().also { members ->
                            tome.members.forEach { members.put(encodeFavoriteMember(it)) }
                        })
                        .put("memberSlots", JSONArray().also { slots ->
                            tome.memberSlots.forEach { member ->
                                slots.put(member?.let(::encodeFavoriteMember) ?: JSONObject.NULL)
                            }
                        })
                        .put("manualOrderCommitted", tome.manualOrderCommitted)
                        .also { encoded -> tome.createdAtMillis?.let { encoded.put("createdAtMillis", it) } },
                )
            }
        })
        .put("platformOrderOverlays", JSONArray().also { array ->
            normalized.platformOrderOverlays.forEach { overlay ->
                array.put(
                    JSONObject()
                        .put("platformId", overlay.platformId.value)
                        .put("gameOrder", JSONArray().also { games ->
                            overlay.gameOrder.forEach { games.put(it.value) }
                        })
                        .put("gameSlots", JSONArray().also { slots ->
                            overlay.gameSlots.forEach { game -> slots.put(game?.value ?: JSONObject.NULL) }
                        }),
                )
            }
        })
        .put("homeShelfSlots", JSONArray().also { slots ->
            normalized.homeShelfSlots.forEach { member ->
                slots.put(member?.let(::encodeFavoriteMember) ?: JSONObject.NULL)
            }
        })
        .toString()
}

fun parseHomeTomeDocument(raw: String?): HomeTomeDocument {
    if (raw.isNullOrBlank()) return HomeTomeDocument()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeTomeDocument()
    if (root.optInt("version", -1) != 1) return HomeTomeDocument()
    val custom = root.optJSONArray("customTomes").objects().mapNotNull { item ->
        val id = item.optString("id").takeIf(String::isNotBlank)?.let { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrNull()
        }
            ?: return@mapNotNull null
        val label = item.optString("label").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        CustomHomeTome(
            id = TomeId.Custom(id),
            label = label,
            members = item.optJSONArray("members").objects().mapNotNull(::parseFavoriteMember).distinct(),
            manualOrderCommitted = item.optBoolean("manualOrderCommitted", false),
            createdAtMillis = item.optLong("createdAtMillis", Long.MIN_VALUE).takeIf { it > 0L },
            memberSlots = item.optJSONArray("memberSlots").favoriteSlots(),
        )
    }
    val pins = root.optJSONArray("pinOrder").objects().mapNotNull(::parseTomeId)
    val overlays = root.optJSONArray("platformOrderOverlays").objects().mapNotNull { item ->
        val platform = item.optString("platformId").takeIf(String::isNotBlank) ?: return@mapNotNull null
        PlatformTomeOrderOverlay(
            platformId = PlatformId(platform),
            gameOrder = item.optJSONArray("gameOrder").strings().map(::GameId).distinct(),
            gameSlots = item.optJSONArray("gameSlots").nullableStrings().map { it?.let(::GameId) },
        )
    }
    return normalizeHomeTomeDocument(
        HomeTomeDocument(
            pinOrder = pins,
            customTomes = custom,
            platformOrderOverlays = overlays,
            homeShelfSlots = root.optJSONArray("homeShelfSlots").favoriteSlots(),
        ),
    )
}

/** Imports legacy collection v1-v3 without ever using labels as identity. */
fun importLegacyHomeTomes(v3Raw: String?, v2Raw: String?, v1Raw: String?): HomeTomeDocument {
    val source = when {
        !v3Raw.isNullOrBlank() -> parseLegacyTypedCollections(v3Raw, readsPins = true)
        !v2Raw.isNullOrBlank() -> parseLegacyTypedCollections(v2Raw, readsPins = false)
        else -> parseLegacyUntypedCollections(v1Raw)
    }
    val custom = source.mapIndexed { ordinal, legacy ->
        CustomHomeTome(
            id = deterministicLegacyTomeId(ordinal, legacy.label, legacy.createdAtMillis),
            label = legacy.label,
            members = legacy.members,
            createdAtMillis = legacy.createdAtMillis,
        )
    }
    return HomeTomeDocument(
        pinOrder = source.mapIndexedNotNull { index, legacy -> custom[index].id.takeIf { legacy.wasPinned } },
        customTomes = custom,
    )
}

private data class LegacyTomeImportRow(
    val label: String,
    val members: List<FavoriteMemberId>,
    val createdAtMillis: Long?,
    val wasPinned: Boolean,
)

private fun parseLegacyTypedCollections(raw: String, readsPins: Boolean): List<LegacyTomeImportRow> {
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return array.objects().mapNotNull { item ->
        val label = item.optString("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val members = item.optJSONArray("members").objects().mapNotNull { member ->
            parseLegacyFavoriteMember(member.optString("kind"), member.optString("id"))
        }.distinct()
        LegacyTomeImportRow(
            label = label,
            members = members,
            createdAtMillis = item.optLong("createdAtMillis", Long.MIN_VALUE).takeIf { it > 0L },
            wasPinned = readsPins && item.optBoolean("shownAsTome", false),
        )
    }
}

private fun parseLegacyUntypedCollections(raw: String?): List<LegacyTomeImportRow> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return array.objects().mapNotNull { item ->
        val label = item.optString("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        LegacyTomeImportRow(
            label = label,
            members = item.optJSONArray("games").strings()
                .filter(String::isNotBlank)
                .map { FavoriteMemberId.Emulated(GameId(it)) }
                .distinct(),
            createdAtMillis = null,
            wasPinned = false,
        )
    }
}

private fun deterministicLegacyTomeId(ordinal: Int, label: String, createdAtMillis: Long?): TomeId.Custom {
    val stableSeed = "mimir/home-tomes/legacy/v1|$ordinal|$label|${createdAtMillis ?: "unknown"}"
    return TomeId.Custom(UUID.nameUUIDFromBytes(stableSeed.toByteArray(StandardCharsets.UTF_8)))
}

private fun encodeTomeId(id: TomeId): JSONObject = when (id) {
    is TomeId.Platform -> JSONObject().put("kind", "PLATFORM").put("id", id.platformId.value)
    is TomeId.Custom -> JSONObject().put("kind", "CUSTOM").put("id", id.id.toString())
    TomeId.HomeOverview -> JSONObject().put("kind", "HOME_OVERVIEW").put("id", "home")
}

private fun parseTomeId(item: JSONObject): TomeId? {
    val id = item.optString("id").takeIf(String::isNotBlank) ?: return null
    return when (item.optString("kind")) {
        "PLATFORM" -> TomeId.Platform(PlatformId(id))
        "CUSTOM" -> runCatching { TomeId.Custom(UUID.fromString(id)) }.getOrNull()
        "HOME_OVERVIEW" -> TomeId.HomeOverview
        else -> null
    }
}

private fun encodeFavoriteMember(member: FavoriteMemberId): JSONObject = when (member) {
    is FavoriteMemberId.Emulated -> JSONObject().put("kind", "EMULATED_GAME").put("id", member.gameId.value)
    is FavoriteMemberId.AndroidGame -> JSONObject().put("kind", "ANDROID_GAME").put("id", member.packageName.value)
}

private fun parseFavoriteMember(item: JSONObject): FavoriteMemberId? =
    parseLegacyFavoriteMember(item.optString("kind"), item.optString("id"))

private fun parseLegacyFavoriteMember(kind: String, id: String): FavoriteMemberId? {
    if (id.isBlank()) return null
    return when (runCatching { HomeShelfItemKind.valueOf(kind) }.getOrNull()) {
        HomeShelfItemKind.EMULATED_GAME -> FavoriteMemberId.Emulated(GameId(id))
        HomeShelfItemKind.ANDROID_GAME -> FavoriteMemberId.AndroidGame(AndroidPackageName(id))
        HomeShelfItemKind.ANDROID_APP, null -> null
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

private fun JSONArray?.nullableStrings(): List<String?> = if (this == null) emptyList() else
    (0 until length()).map { index ->
        if (isNull(index)) null else optString(index).takeIf(String::isNotBlank)
    }

private fun JSONArray?.favoriteSlots(): List<FavoriteMemberId?> = if (this == null) emptyList() else
    (0 until length()).map { index ->
        if (isNull(index)) null else optJSONObject(index)?.let(::parseFavoriteMember)
    }
