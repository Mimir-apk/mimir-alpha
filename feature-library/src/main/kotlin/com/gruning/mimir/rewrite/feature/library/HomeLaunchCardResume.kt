package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import java.util.concurrent.atomic.AtomicReference

/**
 * Cert 2026-10-08 F3: the game that Pressing HOME left running behind Mimir, so its launch card can say "Resume" where it
 * would say "Play". `:app` owns the lease and tells this object about it ([set]); the card reads it while it composes, so a
 * card that is open when the lease changes repaints its button.
 *
 * Compose state, written only inside a mutable snapshot (the safe way to write it from a thread that is not in composition,
 * and from a test), and only when the value changes.
 *
 * **Restart .** The card of the parked game also carries a Restart action next to Resume. Its press does not end anything
 * itself: it files a one-shot request for that game ([requestRestart]) and then presses Play, which the refused-launch path
 * already answers for a parked game. Every Play path takes the request at its entry ([consumeRestart]) and hands it down; the parked-game answer, instead of fronting the task, ends the
 * session through the same confirmed Quit that "End X and start Y?" uses, then lets the launch through. So the end, its failure
 * sentence and the fresh launch are the ones every Play press already has, and no second launch path exists.
 */
object HomeLaunchCardResume {
    const val Label: String = "Resume"

    /**
     * The Restart action's word. Proposed copy, exact wording remains unreviewed: kept in this one constant, so a wording change touches one line.
     */
    const val RestartLabel: String = "Restart"

    /**
     * A Restart request that no Play press took lapses after this long. A press takes it at its very entry ([consumeRestart], before it admits,
     * probes or asks anything), so the window only bounds a request whose press a guard declined before that entry.
     */
    internal const val RestartRequestWindowMs: Long = 3_000L

    /** One filed request: the parked game it is for, and when. Held in a single reference so taking it is one atomic step. */
    private class RestartRequest(val game: GameId, val atMs: Long)

    private val parkedGame = mutableStateOf<GameId?>(null)

    private val restartRequest = AtomicReference<RestartRequest?>(null)

    /** The game parked by a HOME press, or null. Reading it inside composition subscribes the reader. */
    fun parkedGameId(): GameId? = parkedGame.value

    fun set(gameId: GameId?) {
        if (parkedGame.value == gameId) return
        // A request belongs to the lease it was filed against; a new or released lease starts clean.
        restartRequest.set(null)
        Snapshot.withMutableSnapshot { parkedGame.value = gameId }
    }

    /**
     * Files a Restart request for the game that is parked right now. False (nothing filed) when none is: the press that follows is then
     * an ordinary Play, which starts the game fresh, which is all a Restart of a game that is not running means.
     */
    fun requestRestart(nowMs: Long = monotonicNowMs()): Boolean {
        val game = parkedGame.value ?: return false
        restartRequest.set(RestartRequest(game, nowMs))
        return true
    }

    /**
     * Takes the request, atomically: true once, for the game it was filed for, while it is fresh. A press calls this at the entry of its Play
     * path, so two presses in a row cannot both find it and a request is never left standing for a later press to mistake for its own. Any press
     * takes whatever is filed (a press on another game clears it and gets false): a request belongs to the one press the Restart made.
     */
    fun consumeRestart(gameId: GameId, nowMs: Long = monotonicNowMs()): Boolean {
        val request = restartRequest.getAndSet(null) ?: return false
        return request.game == gameId && nowMs - request.atMs in 0..RestartRequestWindowMs
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L
}

/** The primary button's word for a game or app: "Resume" for the one game parked behind Home, "Play" for every other game. */
internal fun homeLaunchCardPlayLabel(itemId: HomeShelfItemId): String =
    if (itemId is HomeShelfItemId.Emulated && HomeLaunchCardResume.parkedGameId() == itemId.gameId) {
        HomeLaunchCardResume.Label
    } else {
        MimirCopy.Play
    }

/** The Home card's Restart key: it sits between Resume and Open with, and is registered only while its game is parked. */
val HomeLaunchCardRestartKey = FocusKey("home/launch-card/restart")

/**
 * The Restart action for [itemId]'s card, or null unless its game is the one parked behind Home. The press files the request and then
 * presses Play through [onPlay] with focus put back on [playKey], because [onPlay] on a surface that routes taps through the focused key
 * (the Home card does) would otherwise press Restart again. [restartKey] is the card's own Restart key (its [MimirLaunchCardFocusContract.restartKey]):
 * both doors' cards carry this one action, each under its own key.
 */
internal fun homeLaunchCardRestartAction(
    itemId: HomeShelfItemId,
    authority: FocusAuthority,
    playKey: FocusKey,
    restartKey: FocusKey = HomeLaunchCardRestartKey,
    onPlay: () -> Unit,
): MimirLaunchCardExtraAction? =
    if (itemId is HomeShelfItemId.Emulated && HomeLaunchCardResume.parkedGameId() == itemId.gameId) {
        MimirLaunchCardExtraAction(
            label = HomeLaunchCardResume.RestartLabel,
            key = restartKey,
            onActivate = {
                HomeLaunchCardResume.requestRestart()
                authority.requestFocus(playKey)
                onPlay()
            },
        )
    } else {
        null
    }
