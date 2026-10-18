package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Process
import android.provider.Settings
import com.gruning.mimir.rewrite.core.launch.LaunchDispatchDestination
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatch-to-matching-return measurements at or below this historical quick-return threshold are
 * conservatively discarded. Neither side of the threshold proves launch success or gameplay.
 */
internal const val HomeExternalSessionElapsedThresholdMs = 3_000L
private const val HomeExternalSessionElapsedSchemaVersion = 1

internal data class HomeExternalSession(
    val itemId: HomeShelfItemId,
    val returnToken: String,
    val dispatchedAtElapsedMillis: Long,
    val bootCount: Int,
    /**
     * The display the measured game owns, or null when the launch did not record one (a Home Android game, or a
     * document written before Rank 7c). Only a MAIN measurement may be finished at Main's return without a Home
     * return record; a Bottom game can still be running when Main comes back.
     */
    val placement: MimirGamePlacement? = null,
)

internal data class HomeExternalSessionElapsedDocument(
    val elapsedTotals: Map<HomeShelfItemId, Long> = emptyMap(),
    val active: HomeExternalSession? = null,
    /** Last valid dispatch-to-matching-return duration, absent for legacy or ambiguous values. */
    val lastSessionElapsed: Map<HomeShelfItemId, Long> = emptyMap(),
)

internal fun beginHomeExternalSession(
    document: HomeExternalSessionElapsedDocument,
    itemId: HomeShelfItemId,
    returnToken: String,
    dispatchedAtElapsedMillis: Long,
    bootCount: Int?,
    placement: MimirGamePlacement? = null,
): HomeExternalSessionElapsedDocument {
    if (returnToken.isBlank() || dispatchedAtElapsedMillis < 0L) return document
    val elapsedTotals =
        document.elapsedTotals + (itemId to (document.elapsedTotals[itemId] ?: 0L))
    return document.copy(
        elapsedTotals = elapsedTotals,
        // If Android cannot supply a boot discriminator, retain typed availability but do not arm a
        // duration that could cross elapsedRealtime epochs and invent time.
        active = bootCount?.takeIf { it >= 0 }?.let {
            HomeExternalSession(itemId, returnToken, dispatchedAtElapsedMillis, it, placement)
        },
    )
}

/**
 * Adds only monotonic dispatch-to-matching-return elapsed time. This reducer has no foreground,
 * background, process-liveness, secondary-task, or emulator-success oracle and must never be
 * presented as playtime.
 */
internal fun finalizeHomeExternalSession(
    document: HomeExternalSessionElapsedDocument,
    returnToken: String,
    nowElapsedMillis: Long,
    currentBootCount: Int?,
): HomeExternalSessionElapsedDocument {
    val active = document.active ?: return document
    if (active.returnToken != returnToken) return document
    if (currentBootCount == null || currentBootCount != active.bootCount) {
        return document.copy(active = null)
    }
    val elapsed = nowElapsedMillis - active.dispatchedAtElapsedMillis
    if (elapsed <= HomeExternalSessionElapsedThresholdMs) return document.copy(active = null)
    val previous = document.elapsedTotals[active.itemId] ?: 0L
    val nextTotal = if (Long.MAX_VALUE - previous < elapsed) Long.MAX_VALUE else previous + elapsed
    return document.copy(
        elapsedTotals = document.elapsedTotals + (active.itemId to nextTotal),
        lastSessionElapsed = document.lastSessionElapsed + (active.itemId to elapsed),
        active = null,
    )
}

/** Cancels only the matching in-flight measurement and preserves every accumulated total byte-for-byte. */
internal fun cancelHomeExternalSession(
    document: HomeExternalSessionElapsedDocument,
    returnToken: String,
): HomeExternalSessionElapsedDocument {
    val active = document.active ?: return document
    if (returnToken.isBlank() || active.returnToken != returnToken) return document
    return document.copy(active = null)
}

/**
 * The token of a MAIN measurement that an earlier process began and never finished, which Main's return may now
 * finish, or null.
 *
 * Rank 7c follow-up: a Bottom-card launch with no row on Main's Home writes no Home return record, so when Mimir's
 * process dies during that game there is neither an in-memory session for Main's return path nor a record for
 * Home's return to finish from. Finishing it at a Main-return read keeps the armed path's moment and reducer.
 *
 * Never a measurement whose game may still be running: a Bottom game can outlive Main's return, and a
 * measurement with no recorded placement cannot be told apart from one. Never one this process began: the
 * live return path owns it, and its game may not have returned. An earlier process is proven by a different
 * boot, or by a dispatch time before this process started (same boot, same elapsedRealtime clock).
 */
internal fun homeExternalSessionOrphanToken(
    document: HomeExternalSessionElapsedDocument,
    processStartElapsedMillis: Long,
    currentBootCount: Int?,
): String? {
    val active = document.active ?: return null
    if (active.placement != MimirGamePlacement.MAIN) return null
    val bootCount = currentBootCount ?: return null
    val rebootedSince = bootCount != active.bootCount
    val begunBeforeThisProcess = active.dispatchedAtElapsedMillis < processStartElapsedMillis
    return active.returnToken.takeIf { rebootedSince || begunBeforeThisProcess }
}

/** The display a Home launch's game owns, recorded with its measurement (Rank 7c follow-up). */
internal fun homeExternalSessionPlacement(destination: LaunchDispatchDestination): MimirGamePlacement =
    when (destination) {
        LaunchDispatchDestination.Main -> MimirGamePlacement.MAIN
        is LaunchDispatchDestination.Secondary -> MimirGamePlacement.BOTTOM
    }

internal fun remapHomeExternalSessionGameId(
    document: HomeExternalSessionElapsedDocument,
    fromGameId: GameId,
    toGameId: GameId,
): HomeExternalSessionElapsedDocument {
    if (fromGameId == toGameId) return document
    val from = HomeShelfItemId.Emulated(fromGameId)
    val to = HomeShelfItemId.Emulated(toGameId)
    val fromTotal = document.elapsedTotals[from]
    val targetTotal = document.elapsedTotals[to]
    val fromLastSession = document.lastSessionElapsed[from]
    val targetLastSession = document.lastSessionElapsed[to]
    val elapsedTotals = document.elapsedTotals.toMutableMap().apply {
        remove(from)
        if (fromTotal != null || targetTotal != null) {
            val left = targetTotal ?: 0L
            val right = fromTotal ?: 0L
            put(to, if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right)
        }
    }
    val active = document.active?.let { session ->
        if (session.itemId == from) session.copy(itemId = to) else session
    }
    // There is no ordering metadata on legacy last-session values. If both IDs have a value,
    // preserve totals but remove the ambiguous last-session claim rather than guessing which is newer.
    val lastSessionElapsed = document.lastSessionElapsed.toMutableMap().apply {
        remove(from)
        when {
            fromLastSession != null && targetLastSession == null -> put(to, fromLastSession)
            fromLastSession == null && targetLastSession != null -> put(to, targetLastSession)
            else -> remove(to)
        }
    }
    return HomeExternalSessionElapsedDocument(elapsedTotals, active, lastSessionElapsed)
}

internal fun encodeHomeExternalSessionElapsedDocument(
    document: HomeExternalSessionElapsedDocument,
): String {
    val totals = JSONArray()
    document.elapsedTotals.entries
        .sortedWith(compareBy({ it.key.kind.name }, { it.key.playIdentity() }))
        .forEach { (itemId, totalMillis) ->
            totals.put(
                JSONObject()
                    .put("kind", itemId.kind.name)
                    .put("id", itemId.playIdentity())
                    .put("totalMillis", totalMillis.coerceAtLeast(0L)),
            )
        }
    val root = JSONObject()
        .put("schemaVersion", HomeExternalSessionElapsedSchemaVersion)
        // Existing v1 keys remain stable so the semantic correction never discards user history.
        .put("totals", totals)
    val lastSessions = JSONArray()
    document.lastSessionElapsed.entries
        .sortedWith(compareBy({ it.key.kind.name }, { it.key.playIdentity() }))
        .forEach { (itemId, elapsedMillis) ->
            if (elapsedMillis >= 0L) {
                lastSessions.put(
                    JSONObject()
                        .put("kind", itemId.kind.name)
                        .put("id", itemId.playIdentity())
                        .put("elapsedMillis", elapsedMillis),
                )
            }
        }
    root.put("lastSessionElapsed", lastSessions)
    document.active?.let { active ->
        root.put(
            "active",
            JSONObject()
                .put("kind", active.itemId.kind.name)
                .put("id", active.itemId.playIdentity())
                .put("returnToken", active.returnToken)
                .put("startedAtElapsedMillis", active.dispatchedAtElapsedMillis)
                .put("bootCount", active.bootCount)
                .apply { active.placement?.let { put("placement", it.name) } },
        )
    }
    return root.toString()
}

internal fun parseHomeExternalSessionElapsedDocument(raw: String?): HomeExternalSessionElapsedDocument {
    if (raw.isNullOrBlank()) return HomeExternalSessionElapsedDocument()
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return HomeExternalSessionElapsedDocument()
    if (root.optInt("schemaVersion", -1) != HomeExternalSessionElapsedSchemaVersion) {
        return HomeExternalSessionElapsedDocument()
    }
    val totalsArray = root.optJSONArray("totals") ?: JSONArray()
    val totals = buildMap {
        for (index in 0 until totalsArray.length()) {
            val row = totalsArray.optJSONObject(index) ?: continue
            val itemId = homeExternalSessionItem(row.optString("kind"), row.optString("id")) ?: continue
            val total = row.optLong("totalMillis", -1L).takeIf { it >= 0L } ?: continue
            put(itemId, total)
        }
    }
    val lastSessions = buildMap {
        val rows = root.optJSONArray("lastSessionElapsed") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val itemId = homeExternalSessionItem(row.optString("kind"), row.optString("id")) ?: continue
            val elapsed = row.optLong("elapsedMillis", -1L).takeIf { it >= 0L } ?: continue
            put(itemId, elapsed)
        }
    }
    val active = root.optJSONObject("active")?.let { row ->
        val itemId = homeExternalSessionItem(row.optString("kind"), row.optString("id")) ?: return@let null
        val token = row.optString("returnToken").takeIf(String::isNotBlank) ?: return@let null
        val started = row.optLong("startedAtElapsedMillis", -1L).takeIf { it >= 0L } ?: return@let null
        // Documents written before B4 have no boot discriminator. Preserve their totals but discard
        // the active anchor because elapsedRealtime cannot be compared safely across an unknown boot.
        val bootCount = row.optInt("bootCount", -1).takeIf { it >= 0 } ?: return@let null
        // Absent from documents written before placement was recorded; an unrecognised value reads as unknown,
        // never as MAIN, so it can never be finished at Main's return.
        val placement = row.optString("placement").takeIf(String::isNotBlank)
            ?.let { runCatching { MimirGamePlacement.valueOf(it) }.getOrNull() }
        HomeExternalSession(itemId, token, started, bootCount, placement)
    }
    return HomeExternalSessionElapsedDocument(totals, active, lastSessions)
}

/**
 * Prefs-backed external-session elapsed measurement. The legacy preference identifiers are retained
 * for in-place compatibility; neither this store nor its consumers may infer foreground runtime,
 * emulator success, or gameplay.
 */
internal object HomeExternalSessionElapsedStore {
    private const val PreferencesName = "mimir_home_play_time"
    private const val DocumentKey = "document.v1"
    private val loadLock = Any()
    private val mutationMutex = Mutex()
    private val totalsState = MutableStateFlow<Map<HomeShelfItemId, Long>>(emptyMap())
    private val lastSessionsState = MutableStateFlow<Map<HomeShelfItemId, Long>>(emptyMap())
    private var loadedFrom: Context? = null

    fun observe(context: Context): StateFlow<Map<HomeShelfItemId, Long>> {
        ensureLoaded(context)
        return totalsState
    }

    fun observeLastSessions(context: Context): StateFlow<Map<HomeShelfItemId, Long>> {
        ensureLoaded(context)
        return lastSessionsState
    }

    private fun ensureLoaded(context: Context) = synchronized(loadLock) {
        val app = context.applicationContext
        if (loadedFrom === app) return@synchronized
        loadedFrom = app
        val document = read(app)
        totalsState.value = document.elapsedTotals
        lastSessionsState.value = document.lastSessionElapsed
    }

    suspend fun begin(
        context: Context,
        itemId: HomeShelfItemId,
        returnToken: String,
        dispatchedAtElapsedMillis: Long,
    ): Boolean = begin(context, itemId, returnToken, dispatchedAtElapsedMillis, placement = null)

    /**
     * Rank 7c follow-up: [placement] is persisted with the measurement so Main's return can finish a MAIN game
     * that a dead process left open ([finishOrphanedMainReturn]). An overload, not a default argument: the
     * four-argument call inside `rememberHomeShelfCoordinator`, which sits at ART's instruction limit, must keep
     * compiling to the same bytecode.
     */
    suspend fun begin(
        context: Context,
        itemId: HomeShelfItemId,
        returnToken: String,
        dispatchedAtElapsedMillis: Long,
        placement: MimirGamePlacement?,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                ensureLoaded(context)
                val currentItemId = when (itemId) {
                    is HomeShelfItemId.Emulated ->
                        HomeShelfItemId.Emulated(GameIdentityAliasResolver.resolve(context, itemId.gameId))
                    is HomeShelfItemId.AndroidGame,
                    is HomeShelfItemId.AndroidApp -> itemId
                }
                val current = read(context.applicationContext)
                replace(
                    context.applicationContext,
                    // A successful dispatch establishes measurement availability immediately. A
                    // quick return remains typed zero/"NO MEASURED ELAPSED", never "NOT TRACKED".
                    beginHomeExternalSession(
                        current,
                        currentItemId,
                        returnToken,
                        dispatchedAtElapsedMillis,
                        currentBootCount(context),
                        placement,
                    ),
                )
            }
        }
    }

    suspend fun finalize(
        context: Context,
        returnToken: String,
        nowElapsedMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            ensureLoaded(context)
            val current = read(context.applicationContext)
            val next = finalizeHomeExternalSession(
                current,
                returnToken,
                nowElapsedMillis,
                currentBootCount(context),
            )
            if (next == current) return@withLock false
            replace(context.applicationContext, next)
        }
    }

    /**
     * Rank 7c follow-up: finish, once, a MAIN game's measurement that a process which has since died left open.
     * Called from [HomeLaunchReturnStore.peek] when it finds no return record: the same Main-return reads at
     * which Home finishes an armed launch from its record. The finish is [finalizeHomeExternalSession], the
     * token-matched, boot-checked reducer [finalize] applies.
     */
    suspend fun finishOrphanedMainReturn(
        context: Context,
        nowElapsedMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            ensureLoaded(context)
            val current = read(context.applicationContext)
            val bootCount = currentBootCount(context)
            val token = homeExternalSessionOrphanToken(current, Process.getStartElapsedRealtime(), bootCount)
                ?: return@withLock false
            val next = finalizeHomeExternalSession(current, token, nowElapsedMillis, bootCount)
            if (next == current) return@withLock false
            val written = replace(context.applicationContext, next)
            // Once, as the record closes: the one moment a restarted process can still see that this game's measurement was open. A hand-off only.
            if (written) current.active?.let { session -> runCatching { orphanObserver?.invoke(session) } }
            written
        }
    }

    /**
     * Told of each MAIN measurement that an earlier process left open, once, as [finishOrphanedMainReturn] closes it. The kept-game restart seed
     * ([KeptGameRestartSeed]) is the reader: the game may still be running behind Mimir. It runs under this store's lock, so it must only hand the
     * session off.
     */
    @Volatile
    var orphanObserver: ((HomeExternalSession) -> Unit)? = null

    suspend fun cancel(
        context: Context,
        returnToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            ensureLoaded(context)
            val current = read(context.applicationContext)
            val next = cancelHomeExternalSession(current, returnToken)
            if (next == current) return@withLock false
            replace(context.applicationContext, next)
        }
    }

    /**
     * The open measurement, read under the store's lock, or null. O14 reads it to decide whether a retired Bottom game
     * tab closes a dead process's measurement; the close itself is [finalize], token-matched like every other finish.
     */
    suspend fun activeMeasurement(context: Context): HomeExternalSession? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            ensureLoaded(context)
            read(context.applicationContext).active
        }
    }

    /** The boot discriminator every measurement records (`Settings.Global.BOOT_COUNT`), or null when unreadable. */
    fun bootCountNow(context: Context): Int? = currentBootCount(context)

    suspend fun remapGameId(context: Context, fromGameId: GameId, toGameId: GameId): Boolean =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                ensureLoaded(context)
                val current = read(context.applicationContext)
                val next = remapHomeExternalSessionGameId(current, fromGameId, toGameId)
                current == next || replace(context.applicationContext, next)
            }
        }

    suspend fun remapGameIds(
        context: Context,
        aliases: Map<GameId, GameId>,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            ensureLoaded(context)
            val current = read(context.applicationContext)
            val next = aliases.entries.fold(current) { document, (fromGameId, toGameId) ->
                remapHomeExternalSessionGameId(document, fromGameId, toGameId)
            }
            current == next || replace(context.applicationContext, next)
        }
    }

    private fun read(context: Context): HomeExternalSessionElapsedDocument =
        parseHomeExternalSessionElapsedDocument(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(DocumentKey, null),
        )

    private fun replace(context: Context, document: HomeExternalSessionElapsedDocument): Boolean {
        val written = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(DocumentKey, encodeHomeExternalSessionElapsedDocument(document))
            .commit()
        if (written) {
            totalsState.value = document.elapsedTotals
            lastSessionsState.value = document.lastSessionElapsed
        }
        return written
    }
}

private fun currentBootCount(context: Context): Int? = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
}.getOrNull()?.takeIf { it >= 0 }

private fun HomeShelfItemId.playIdentity(): String = when (this) {
    is HomeShelfItemId.Emulated -> gameId.value
    is HomeShelfItemId.AndroidGame -> packageName.value
    is HomeShelfItemId.AndroidApp -> packageName.value
}

private fun homeExternalSessionItem(kind: String, id: String): HomeShelfItemId? {
    if (id.isBlank()) return null
    return runCatching {
        when (HomeShelfItemKind.valueOf(kind)) {
            HomeShelfItemKind.EMULATED_GAME -> HomeShelfItemId.Emulated(GameId(id))
            HomeShelfItemKind.ANDROID_GAME -> HomeShelfItemId.AndroidGame(AndroidPackageName(id))
            HomeShelfItemKind.ANDROID_APP -> HomeShelfItemId.AndroidApp(AndroidPackageName(id))
        }
    }.getOrNull()
}
