package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import kotlinx.coroutines.launch

/**
 * A Thor real-game check found that the launch card's EXTERNAL ELAPSED read NOT TRACKED after four
 * minutes of play.
 *
 * **Cause.** The elapsed store ([HomeExternalSessionElapsedStore]) holds one total per game and opens a measurement only when a launch
 * begins one, and only Home's two launch sites did (`settleEmulatedHomeLaunch` and the Android-game launch), with the token of the Home
 * return record they had just armed. A game started from the Library (or any surface with no Home return record) had a session but no
 * measurement and no entry in the store, so nothing ever finished a segment for it and its card kept saying NOT TRACKED, however long it
 * ran. Every finish site is keyed by that token too (`primaryGameReturnElapsedToken`, the Bottom's game cleanup, the park's segments), and
 * all of them found none.
 *
 * **Fix.** One session, one measurement, whoever launched it. A session that carries a Home return handle keeps that handle's token and
 * item, exactly as before. Any other session names its measurement by its own operation ([elapsedToken], [elapsedItemId]) and opens it at
 * the dispatch edge ([onDispatched], through [MimirSessionAuthority]'s `onGameDispatched`), so a store entry exists from the first
 * moment the game runs and every existing finish site (Main's return, the Bottom's cleanup, a HOME park's segments) finishes it with
 * the same token. The store is the one that already persists, canonicalises game ids through the identity aliases, and that the Bottom's
 * "before this session" total reads: no second store exists to disagree with it.
 *
 * What a session adds is still launch-to-return, minus the time the game sat parked behind Home ([HomeParkSegments]): the segment the
 * HOME press ends is finished at the press, and Resume begins the next. The store has no foreground or gameplay oracle, and none is
 * claimed.
 */
internal fun MimirGameSession.elapsedToken(): String = homeReturnHandle?.token ?: "session:${operation.value}"

/** The same token, from the dispatch watchdog's cleanup of a game that never took the screen: it cancels the measurement the dispatch opened. */
internal fun MimirGameDispatchCleanup.elapsedToken(): String = homeReturnHandle?.token ?: "session:${operation.value}"

/** The store item this session's time belongs to: the Home handle's own, else the game. */
internal fun MimirGameSession.elapsedItemId(): HomeShelfItemId =
    homeReturnHandle?.target?.itemId ?: HomeShelfItemId.Emulated(gameId)

internal object GameSessionPlayTime {
    /**
     * Whether [game] needs its measurement opened here: a session that was just dispatched and has no Home return handle. A session with a
     * handle had its measurement opened by the Home launch that armed the handle.
     */
    fun needsMeasurement(game: MimirGameSession?): Boolean =
        game != null && game.homeReturnHandle == null && game.dispatchedAtElapsedMs != null && game.phase == MimirSessionPhase.DISPATCHED

    /**
     * Opens the measurement of [game] at its dispatch time; true when the store took it and the launch was still going when it did. Always
     * off the main thread (the store's own IO hop), never throws.
     *
     * this runs on its own coroutine, so a launch cancelled while it starts (the dispatch watchdog's cleanup, a tab close) can cancel
     * the measurement BEFORE this opens it. The late open would then leave a measurement open for a game that no longer exists. So the
     * launch is checked again after the store took the write ([beginWhileLaunched]), and a measurement opened for a dead launch is
     * cancelled here by the same token.
     */
    suspend fun begin(context: Context, game: MimirGameSession): Boolean = beginWhileLaunched(
        game = game,
        current = { MimirSessionAuthority.state.value.gameSession },
        open = {
            HomeExternalSessionElapsedStore.begin(
                context = context,
                itemId = game.elapsedItemId(),
                returnToken = game.elapsedToken(),
                dispatchedAtElapsedMillis = game.dispatchedAtElapsedMs ?: SystemClock.elapsedRealtime(),
                placement = game.placement,
            )
        },
        cancel = { HomeExternalSessionElapsedStore.cancel(context, game.elapsedToken()) },
    )

    /**
     * Whether [game]'s launch is still the process's current one: [current] is the same operation and has not been cancelled or finished
     * (RECOVERING is the watchdog's cleanup, FAILED, ENDED and STALE are over). Any other phase, up to and including RETURNING, still owns
     * the measurement, which its own finish site closes.
     */
    fun stillLaunched(game: MimirGameSession, current: MimirGameSession?): Boolean =
        current != null && current.operation == game.operation && current.phase !in LaunchOverPhases

    /**
     * [begin]'s order of events, with the store as ports. The check after [open] is the one that matters: a cancel that ran first changed
     * the session before it touched the store, so a launch that is over here is a cancel that already ran (or is running) and found nothing to
     * cancel, and [cancel] closes what [open] just opened. A cancel that has not yet changed the session will run after this check, and the
     * store's own mutex has already ordered [open] before it.
     */
    internal suspend fun beginWhileLaunched(
        game: MimirGameSession,
        current: () -> MimirGameSession?,
        open: suspend () -> Boolean,
        cancel: suspend () -> Unit,
    ): Boolean {
        if (!stillLaunched(game, current())) return false
        val opened = runCatching { open() }.getOrDefault(false)
        if (opened && !stillLaunched(game, current())) {
            runCatching { cancel() }
            return false
        }
        return opened
    }

    private val LaunchOverPhases = setOf(
        MimirSessionPhase.RECOVERING,
        MimirSessionPhase.FAILED,
        MimirSessionPhase.ENDED,
        MimirSessionPhase.STALE,
    )

    /**
     * The dispatch edge ([MimirSessionAuthority]'s `onGameDispatched`), under the coordinator's lock: only files the work. A process that
     * has no application context yet (a unit test, a cold start before `LaunchHonesty` installed it) has nothing to measure with and
     * skips, silently, which is what a session that dispatched before the app was up should do.
     */
    fun onDispatched(game: MimirGameSession?) {
        if (!needsMeasurement(game)) return
        val session = game ?: return
        val context = MainGameHomePark.appContext ?: return
        CompanionProcessRuntime.processScope.launch {
            val opened = begin(context, session)
            runCatching { Log.i("MimirPlayTime", "measurement op=${session.operation.value} placement=${session.placement} opened=$opened") }
        }
    }
}
