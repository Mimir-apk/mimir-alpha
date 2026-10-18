package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// The performance card's memory of the games Mimir launched ([PerformanceOverlayGameRegistry]) used to live in
// memory only. When Mimir's process died while a game ran (low memory, an `adb install -r`), the games Mimir had
// launched were forgotten; the game switched back to through Recents then looked like a stranger, and in
// GAMES_ONLY the card never came back for it (update). This file keeps the memory across the process.
//
// What is kept is exactly what the in-memory copy holds plus the little that names the launch: the package, the
// session key, the game id and the launch time. No URL, key or file path. What is forgotten is forgotten by the
// same rule: every change to the registry (a launch noted, a FAILED or STALE one forgotten, a closed tab or a
// process proven gone forgotten, an eviction past the cap) rewrites the whole document, so a forgotten game is
// gone from the file as well. ENDED does not forget, here as in memory: the player returning to Mimir leaves the
// game running in Recents.
//
// Restore at process start, expire by the same proofs. A document from another boot is dropped whole (a
// reboot ended every task). Within the same boot a remembered game is kept unless a positive probe says its
// process is gone; an unanswered probe keeps it, the bar every other release site uses.

private const val LaunchedGameDocumentSchemaVersion = 1

/** The document's size is bounded by the registry's own cap; this bounds one field against a corrupt file. */
private const val LaunchedGameFieldMaxLength = 255

internal data class LaunchedGamesDocument(
    /** The boot the document was written in, or null when the platform could not say (then nothing is restored). */
    val bootCount: Int?,
    /** Least recently used first, the registry's own order. */
    val games: List<LaunchedGame>,
)

internal fun encodeLaunchedGamesDocument(bootCount: Int?, games: List<LaunchedGame>): String {
    val rows = JSONArray()
    games.forEach { game ->
        rows.put(
            JSONObject()
                .put("package", game.packageName)
                .put("operation", game.operation)
                .apply {
                    game.gameId?.let { put("gameId", it) }
                    game.launchedAtEpochMs?.let { put("launchedAtEpochMs", it) }
                },
        )
    }
    return JSONObject()
        .put("schemaVersion", LaunchedGameDocumentSchemaVersion)
        .apply { bootCount?.let { put("bootCount", it) } }
        .put("games", rows)
        .toString()
}

/**
 * Reads a document back, tolerating anything: a missing, foreign or corrupt file is an empty document, and a bad
 * row is skipped without costing the good ones. At most the registry's cap of newest rows is taken, one per
 * package, so a hand-edited file cannot grow the registry.
 */
internal fun parseLaunchedGamesDocument(raw: String?): LaunchedGamesDocument {
    val empty = LaunchedGamesDocument(bootCount = null, games = emptyList())
    if (raw.isNullOrBlank()) return empty
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return empty
    if (root.optInt("schemaVersion", -1) != LaunchedGameDocumentSchemaVersion) return empty
    val bootCount = root.optInt("bootCount", -1).takeIf { it >= 0 }
    val rows = root.optJSONArray("games") ?: return LaunchedGamesDocument(bootCount, emptyList())
    val byPackage = LinkedHashMap<String, LaunchedGame>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val packageName = row.optString("package").trim().takeIf(::isPlausibleLaunchedGameField) ?: continue
        val operation = row.optString("operation").trim().takeIf(::isPlausibleLaunchedGameField) ?: continue
        val gameId = row.optString("gameId").trim().takeIf(::isPlausibleLaunchedGameField)
        val launchedAt = row.optLong("launchedAtEpochMs", -1L).takeIf { it >= 0L }
        byPackage.remove(packageName)
        byPackage[packageName] = LaunchedGame(packageName, operation, gameId, launchedAt)
    }
    return LaunchedGamesDocument(bootCount, byPackage.values.toList().takeLast(PerformanceOverlayGameRegistryCap))
}

private fun isPlausibleLaunchedGameField(value: String): Boolean =
    value.isNotEmpty() && value.length <= LaunchedGameFieldMaxLength && value.none(Char::isISOControl)

/** Where the document lives. Both calls block; the persistence runs them off the main thread. */
internal interface LaunchedGameDocumentMedium {
    fun read(): String?

    /** True once the document is durably written. */
    fun write(document: String): Boolean
}

/** One small private preferences file, the way the app's other small session records are kept. */
internal class PrefsLaunchedGameDocumentMedium(context: Context) : LaunchedGameDocumentMedium {
    private val appContext = context.applicationContext

    private fun prefs() = appContext.getSharedPreferences(LaunchedGamePreferencesName, Context.MODE_PRIVATE)

    override fun read(): String? = prefs().getString(LaunchedGameDocumentKey, null)

    override fun write(document: String): Boolean =
        prefs().edit().putString(LaunchedGameDocumentKey, document).commit()

    companion object {
        /** Also listed in [MimirPrefsPrefetch], so its disk load is already under way when the restore reads it. */
        const val LaunchedGamePreferencesName = "mimir_launched_games"
        private const val LaunchedGameDocumentKey = "document.v1"
    }
}

/**
 * Restores the registry at process start and writes every later change back, all off the caller's thread.
 *
 * The restore reads the document, drops it whole when it is from another boot, merges what is left UNDER anything
 * the process has already noted ([PerformanceOverlayGameRegistry.restore]), and only then starts listening, so a
 * change racing the restore can never overwrite the file before it was read. Writes go through a conflated queue
 * drained by one coroutine: the registry's listener only enqueues (it runs under the registry's lock, possibly on
 * the main thread), the newest full list wins, and an unchanged document is not written again.
 *
 * Nothing here waits on the first frame: until the restore lands the registry is simply what it was before this
 * existed (empty), and the card's next beat, a second later at most, sees the restored games.
 */
internal class LaunchedGameRegistryPersistence(
    private val registry: PerformanceOverlayGameRegistry,
    private val medium: LaunchedGameDocumentMedium,
    private val bootCount: () -> Int?,
    private val liveness: (String) -> PrivilegedPackageLiveness,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val snapshots = Channel<List<LaunchedGame>>(Channel.CONFLATED)
    private var lastWritten: String? = null

    /**
     * Starts the writer and the one-shot restore; the returned job completes when the restore, and the probe of
     * what it restored, are done. The writer lives as long as [scope].
     */
    fun start(): Job {
        scope.launch {
            for (snapshot in snapshots) writeIfChanged(snapshot)
        }
        return scope.launch {
            val restored = restoreFromMedium()
            registry.listener = { snapshots.trySend(it) }
            // The merged list, once: what the restore produced plus anything noted while it ran.
            snapshots.trySend(registry.games())
            dropGone(restored)
        }
    }

    private fun restoreFromMedium(): List<LaunchedGame> {
        val document = parseLaunchedGamesDocument(runCatching(medium::read).getOrNull())
        val boot = bootCount()
        if (boot == null || document.bootCount != boot) {
            if (document.games.isNotEmpty()) {
                log("launched games dropped count=${document.games.size} reason=another_boot")
            }
            return emptyList()
        }
        val taken = registry.restore(document.games)
        if (taken > 0) log("launched games restored count=$taken")
        return document.games
    }

    /**
     * A restored game whose process is positively gone is forgotten, exactly the launch that was restored (a newer
     * launch of the same emulator is untouched). Unknown or alive keeps it: an unanswered probe is never proof.
     */
    private fun dropGone(restored: List<LaunchedGame>) {
        for (game in restored) {
            if (!registry.contains(game.packageName)) continue
            val answer = runCatching { liveness(game.packageName) }.getOrDefault(PrivilegedPackageLiveness.UNKNOWN)
            if (answer == PrivilegedPackageLiveness.ABSENT && registry.forget(game.packageName, game.operation)) {
                log("launched game dropped package=${game.packageName} reason=process_gone")
            }
        }
    }

    private fun writeIfChanged(snapshot: List<LaunchedGame>) {
        val document = encodeLaunchedGamesDocument(bootCount(), snapshot)
        if (document == lastWritten) return
        val written = runCatching { medium.write(document) }.getOrDefault(false)
        if (written) lastWritten = document else log("launched games not written count=${snapshot.size}")
    }
}

/** Starts [LaunchedGameRegistryPersistence] for the process-wide registry. Called once, from the Application. */
internal object MimirLaunchedGamesPersistence {
    private const val Tag = "MimirLaunchedGames"

    // Process lifetime, so the handler: a failed read or write is logged loudly and never costs the launcher.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e(Tag, "launched-game persistence failed", error)
        },
    )
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        LaunchedGameRegistryPersistence(
            registry = MimirLaunchedGames.registry,
            medium = PrefsLaunchedGameDocumentMedium(app),
            bootCount = { HomeExternalSessionElapsedStore.bootCountNow(app) },
            // The vendor gateway's own liveness answer: UNKNOWN whenever its stop policy or capability probe
            // declines, which keeps the game. This asks nothing the Tabs liveness reconcile does not ask.
            liveness = { packageName -> CompanionProcessRuntime.services(app).verifiedPackageLiveness(packageName) },
            scope = scope,
            log = { line -> Log.i(Tag, line) },
        ).start()
    }
}
