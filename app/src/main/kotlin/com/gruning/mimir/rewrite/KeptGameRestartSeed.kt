package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import android.view.Display
import com.gruning.mimir.rewrite.core.audio.MimirMediaBed
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A game Mimir launched keeps its stream running behind Mimir, and the media bed does not count that stream as another app's music
// (MimirKeptGameStreams). That discount lives in memory, so a restarted process lost it: the game's stream blocked the theme again until the
// next launch. This file gives it back, but only on proof.
//
// What can be proved. The elapsed store (HomeExternalSessionElapsedStore) keeps a record of the game Mimir launched on Main. It is closed at the
// HOME press, so a game parked or kept by HOME has no open record: the record is open only when the process died while the game was still the
// measured one. When a later process finds that record open (the store hands it over as it closes it, once), the record names the game by id,
// and the persisted memory of launched games (LaunchedGameRegistryPersistence) says which package that game launched. The same task lookup that
// keeps a HOME-parked session (the vendor gateway's resolveTaskOutcome) then says whether that package still has a task. Found: the game is
// still there and its stream is the one behind Mimir. NoTask, or no answer: nothing is seeded, and the stream is a stranger as before.
// No privileged call is added: this is the lookup MainGameHomePark already makes, once or twice, off the main thread.

/** What the restart seed decided: how many launched games are proven to still run, and why (for the log). */
internal data class KeptGameSeed(val keptTasks: Int, val reason: String)

/** How many times the task lookup is asked when the gateway cannot answer yet (it may not be up in the first seconds of a process). */
internal const val KeptGameSeedAttempts = 2

/** The wait between those asks. */
internal const val KeptGameSeedRetryDelayMs = 3_000L

/**
 * The package of the game an open elapsed record names, from the persisted memory of launched games, or null when none can be named. Only an emulated
 * launch seeds: the audio-focus gate that arms the kept-game discount (Suspended) is armed by the launch controller for emulated launches alone, so an
 * Android game or app never had a discount to lose. An emulated game is found by its game id (the newest launch of it).
 */
internal fun keptGameSeedPackage(session: HomeExternalSession, launched: List<LaunchedGame>): String? =
    when (val item = session.itemId) {
        is HomeShelfItemId.Emulated ->
            launched.filter { it.gameId == item.gameId.value }.maxByOrNull { it.launchedAtEpochMs ?: -1L }?.packageName
        is HomeShelfItemId.AndroidGame -> null
        is HomeShelfItemId.AndroidApp -> null
    }

/**
 * How many launched games are proven to be still running: one when the orphan record is open AND the task lookup found the task, none otherwise
 * (no record, no task, or a lookup that did not answer, which is proof of nothing).
 */
internal fun keptGameSeedCount(orphanOpen: Boolean, taskOutcome: VendorTaskOutcome?): Int =
    if (orphanOpen && taskOutcome is VendorTaskOutcome.Found) 1 else 0

/**
 * The whole decision. [session] is the open orphan record (null: none). [resolveTaskOutcome] is the task lookup on Main's display, off the main
 * thread; it is asked up to [KeptGameSeedAttempts] times while it says Unavailable, [pause] apart, and not at all without a record or a package.
 */
internal suspend fun keptGameSeed(
    session: HomeExternalSession?,
    launched: List<LaunchedGame>,
    resolveTaskOutcome: suspend (packageName: String) -> VendorTaskOutcome,
    pause: suspend (Long) -> Unit = { delay(it) },
): KeptGameSeed {
    if (session == null) return KeptGameSeed(0, "no open record")
    val packageName = keptGameSeedPackage(session, launched) ?: return KeptGameSeed(0, "the record names no launched package")
    var outcome: VendorTaskOutcome = VendorTaskOutcome.Unavailable("not_asked")
    for (attempt in 1..KeptGameSeedAttempts) {
        outcome = resolveTaskOutcome(packageName)
        if (outcome !is VendorTaskOutcome.Unavailable) break
        if (attempt < KeptGameSeedAttempts) pause(KeptGameSeedRetryDelayMs)
    }
    val kept = keptGameSeedCount(orphanOpen = true, taskOutcome = outcome)
    val answer = when (outcome) {
        is VendorTaskOutcome.Found -> "task found"
        VendorTaskOutcome.NoTask -> "no task"
        is VendorTaskOutcome.Unavailable -> "no answer (${outcome.reason})"
    }
    return KeptGameSeed(kept, "$packageName: $answer")
}

/** The production side: reads the persisted launched games, asks the gateway, and seeds the media bed. Idempotent: the bed refuses a second seed. */
internal object KeptGameRestartSeed {
    private const val Tag = "MimirKeptGameSeed"

    // Process lifetime, so the handler: a failed lookup is logged loudly and never costs the launcher.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Log.e(Tag, "kept-game restart seed failed", error) },
    )

    /** The elapsed store found [session] open from an earlier process and closed it: decide, once, whether its game's stream is still behind Mimir. */
    fun onOrphanRecord(context: Context, session: HomeExternalSession) {
        val app = context.applicationContext
        scope.launch {
            val seed = keptGameSeed(session, launchedGames(app), resolveTaskOutcome = { packageName -> resolve(app, packageName) })
            if (seed.keptTasks <= 0) {
                Log.i(Tag, "restart: nothing seeded (${seed.reason})")
                return@launch
            }
            val state = CompanionMediaSessionBridge.state.value
            val strangerSessionPlaying = sessionAccessGrantedFor(state) && otherPlayerSnapshotFor(state)?.isPlaying == true
            Log.i(Tag, "restart: a game Mimir launched is still running (${seed.reason}); its stream is not other music")
            MimirMediaBed.seedKeptGame(seed.keptTasks, strangerSessionPlaying)
        }
    }

    /** The persisted memory of launched games, only if it was written in this boot (a reboot ended every task). */
    private fun launchedGames(app: Context): List<LaunchedGame> {
        val document = parseLaunchedGamesDocument(runCatching { PrefsLaunchedGameDocumentMedium(app).read() }.getOrNull())
        val boot = HomeExternalSessionElapsedStore.bootCountNow(app)
        return if (boot != null && document.bootCount == boot) document.games else emptyList()
    }

    /** One look at the gateway, the shape HomeParkPorts uses: ready first, the lookup bounded, an unanswered one is Unavailable. */
    private suspend fun resolve(app: Context, packageName: String): VendorTaskOutcome {
        val gateway = CompanionProcessRuntime.services(app).privilegedUtility
        // posture() re-attests the transport on every call: blocking, so off the main thread.
        val ready = withContext(Dispatchers.IO) { runCatching { gateway.posture().status == PrivilegedUtilityStatus.READY }.getOrDefault(false) }
        if (!ready) return VendorTaskOutcome.Unavailable("not_ready")
        return answerWithin(HomeParkResolveTimeoutMs) {
            runCatching { gateway.resolveTaskOutcome(packageName, Display.DEFAULT_DISPLAY) }
                .getOrElse { VendorTaskOutcome.Unavailable("unavailable") }
        } ?: VendorTaskOutcome.Unavailable("timeout")
    }
}
