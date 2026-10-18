package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown
import com.gruning.mimir.rewrite.core.ui.mimirHold
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * Hiding or keeping a game must animate its departure before the shown list removes it.
 *
 * A game the player sends out of a gamelist DEPARTS: the card dissolves in place while the cards after it close the gap, one
 * pitch, on the same clock (MimirMotion.OverlayMs, MimirMotion.Easing), and the pad waits until the gap has closed. It is one
 * grammar for the carousel and the list, decided in one place, from what the screen shows: this file holds the pure part
 * (what counts as a departure, where it seats) and the small state machine that carries it across recompositions.
 *
 * Nothing here decides whether a game leaves. That is [LibraryPendingRemovals], which the ViewModel owns and the screen
 * applies in the same composition as the focus hand-off, so by the time the list is one game shorter the focus already names
 * the card that stays. A departure is only ever the SHOWN list losing exactly the game the player sent away, with the centred
 * card being that game and the new focus landing on the card beside it. Everything else (a scan removing a game, a search
 * narrowing, a platform switch, a game the player was not on) is not one, and cuts exactly as it did.
 */

/** The one game a shown list lost between two states, and the index it stood at. */
internal data class GamelistRemoval(val game: GameRow, val index: Int)

/**
 * The single game [after] lacks compared with [before], when [after] is exactly [before] without one game and [leaving]
 * says that game is one the player sent away; otherwise null. Only identity and order are compared, so a metadata refresh
 * of the surviving rows is not a difference. An emptied list is not a removal here: there is nothing left to close a gap.
 */
internal fun singleGamelistRemoval(
    before: List<GameRow>,
    after: List<GameRow>,
    leaving: (GameRow) -> Boolean,
): GamelistRemoval? {
    if (after.isEmpty() || after.size != before.size - 1) return null
    var index = 0
    while (index < after.size && before[index].gameId == after[index].gameId) index++
    // `index` is where the two lists first differ, so the removed game sits there in [before] and the rest of [after] has to
    // be the rest of [before] shifted up one place.
    var rest = index
    while (rest < after.size) {
        if (before[rest + 1].gameId != after[rest].gameId) return null
        rest++
    }
    val removed = before[index]
    return if (leaving(removed)) GamelistRemoval(removed, index) else null
}

/**
 * Whether [removal] is one the departure can seat. The motion has two shapes and only these: the centred card leaves and
 * focus lands on the card that takes its place (the cards after it close up, so the centre does not move), or the LAST card
 * leaves and focus lands on the one before it (the centre glides back one place, as it does for any step). A removal beside
 * a card the player is not on would need the centre and the gap to move on two clocks at once, so it is left to cut.
 */
internal fun gamelistDepartureSeats(
    removal: GamelistRemoval,
    sizeBefore: Int,
    centredBefore: Int,
    centredAfter: Int,
): Boolean = sizeBefore >= 2 && centredBefore == removal.index &&
    (centredAfter == removal.index || (removal.index == sizeBefore - 1 && centredAfter == removal.index - 1))

/**
 * Which card is the centre of a list: the artwork pill keeps its game centred while it owns focus, and a focus that names no
 * game in the list (a platform switch, the beat before a landing) centres game one. The carousel and the tracker both read
 * this, so they cannot disagree about which card was leaving.
 */
internal fun gamelistCentredIndex(
    games: List<GameRow>,
    centeredKey: FocusKey?,
    getArtFocused: Boolean,
    detailGame: GameRow?,
): Int = gamelistCentredIndex(GamelistKeyIndex.of(games), centeredKey, getArtFocused, detailGame)

/**
 * [gamelistCentredIndex] over a list's [GamelistKeyIndex]: one hash lookup and no walk of the list. This is the form the screen asks on every
 * focus step (audit 2026-10-14, RC-45); the list form above finds the index itself and is for a caller with none at hand.
 */
internal fun gamelistCentredIndex(
    index: GamelistKeyIndex,
    centeredKey: FocusKey?,
    getArtFocused: Boolean,
    detailGame: GameRow?,
): Int {
    val seat = when {
        getArtFocused -> detailGame?.let { retained -> index.indexOf(retained.gameId) }
        else -> centeredKey?.let(index::indexOf)
    }
    return seat?.takeIf { it >= 0 } ?: 0
}

/**
 * The continuous seat of a card that STAYS while a departure closes its gap. The cards from the removed index on are one
 * place nearer than their old seats and start there, [gap] earlier: at a gap of 1 every card sits exactly where it sat
 * before the removal and at 0 exactly where the new list puts it, so the first frame is the last frame of the old list.
 */
internal fun gamelistDepartureSeat(itemIndex: Int, centre: Float, departureIndex: Int?, gap: Float): Float =
    itemIndex - centre + if (departureIndex != null && itemIndex >= departureIndex) gap else 0f

/** Where the leaving card sits in the stack: above its neighbours, below whichever card is arriving at the centre. */
internal const val GamelistDepartureStackPosition = 0.5f

internal fun gamelistLeavingCardTestTag(game: GameRow): String = "gamelist_card_leaving_${game.gameId.value}"

/**
 * How a game leaves. A [Dissolve] is the house departure: the card fades where it stood while the cards after it close the
 * gap, on one clock. A [Shatter] is a HIDE: the card was captured and is
 * broken apart by [LibraryShatterOverlay], so the card itself never paints; the gap opens its closing [MimirShatterMotion.GapLeadMs]
 * after the strike and glides for [MimirShatterMotion.GapGlideMs]; the hero holds only [MimirShatterMotion.HeroHoldMs]; and
 * the pad waits until the gap has shut. A favourite removed by SELECT in a favourites view is still a [Dissolve], because
 * nothing is hidden.
 */
internal enum class GamelistDepartureKind { Dissolve, Shatter }

/**
 * One game leaving a list. [progress] runs 0 to 1: for a [GamelistDepartureKind.Dissolve] on the house overlay clock, the
 * leaving card's presentation alpha is `1 - progress` and the gap the cards after it close is the same number, so the
 * dissolve and the glide are one motion; for a [GamelistDepartureKind.Shatter] it is only the gap, on its own delayed clock,
 * and the card is not painted at all. It is read in the layout and draw phases only, so a departure never recomposes the
 * cards it moves.
 *
 * [finished] flips once, when the motion has run, and is the only thing composition reads besides [holdsHero]: it removes
 * the card and lets go of the hero.
 */
@Stable
internal class GamelistDeparture(
    /** The game as the player last saw it. A card that has left the list is still painted from this. */
    val game: GameRow,
    /** Where it stood in the list it left. */
    val index: Int,
    /**
     * Hook for a future shatter (walk 32g, the second of two; [LibraryViewModel.departureSlot] is the first): where the
     * centred card was last painted, in window coordinates, taken from [GamelistCentreBounds] as the card left. Nothing
     * reads it yet.
     */
    val lastBounds: Rect? = null,
    /** Walk-32h: a hide the overlay breaks apart, or (the default) the house dissolve. */
    val kind: GamelistDepartureKind = GamelistDepartureKind.Dissolve,
) {
    val progress = Animatable(0f)

    var finished by mutableStateOf(false)
        internal set

    /** Flips once, [MimirShatterMotion.HeroHoldMs] after a shatter starts: the hero then hands over while the gap still closes. */
    var heroReleased by mutableStateOf(false)
        internal set

    /**
     * Whether the hero still shows the game that left. A dissolve holds it for the whole departure (it is over in one
     * overlay clock); a shatter holds it to [MimirShatterMotion.HeroHoldMs] and releases it while the gap runs on.
     */
    val holdsHero: Boolean get() = kind == GamelistDepartureKind.Dissolve || !heroReleased

    /** The gap the cards after the removed index still have to close: 1 at the start, 0 when it is shut. */
    fun gap(): Float = 1f - progress.value
}

/**
 * Where the CENTRED card was last laid out, in window coordinates. The centred card writes it as it is positioned, so it
 * always holds the seat of the card a hide or an un-star is about to send away: the place a future shatter launches from,
 * available to [LibraryViewModel.departureSlot] before the game leaves the list and copied into
 * [GamelistDeparture.lastBounds] as it does. Plain memory, not state: it is written every layout and read once.
 */
internal class GamelistCentreBounds {
    @Volatile var value: Rect? = null
}

/**
 * What the screen shows this composition: the departure to paint, if any, and the identity the carousel's centre and the
 * list's scroll state are remembered on. The identity outlasts a departure, so neither is re-created by the removal (a new
 * centre would seat itself at the new focus and cut the glide; a new scroll state would forget the rows it is animating),
 * and changes for every other change of the list, which is exactly when they were re-created before.
 */
internal class GamelistDepartureFrame(
    val departure: GamelistDeparture?,
    val lineage: Any,
    val centreBounds: GamelistCentreBounds,
)

/**
 * Carries a departure across recompositions. [observe] is called in composition with what is about to be drawn and is a
 * pure function of that and of what was last COMMITTED; [commit] is called from a SideEffect once the composition applied.
 * Nothing is remembered until then, so a composition that is abandoned leaves no trace, and calling [observe] twice with the
 * same inputs (a restarted composition) gives the same frame and the same departure, not two.
 *
 * The one thing it reads from state is [GamelistDeparture.finished], on every call, so a finished departure recomposes the
 * screen once and is dropped.
 */
internal class GamelistDepartureTracker {
    val centreBounds = GamelistCentreBounds()

    /**
     * What a composition showed. The keys are asked only when two DIFFERENT lists are compared ([judge]): a focus step shows the same list
     * at another centre, so it never makes them (audit 2026-10-14, RC-45: `games.map(::gameKey)` here ran, one string per game, on every step).
     */
    private class Shown(val games: List<GameRow>, val centred: Int, val scope: Any?, given: List<FocusKey>?) {
        val keys: List<FocusKey> by lazy(LazyThreadSafetyMode.NONE) { given ?: games.map(::gameKey) }
    }

    /** What this composition decided; applied to the tracker by [commit]. */
    private class Verdict(val departure: GamelistDeparture?, val lineage: Any, val keepsCommitted: Boolean)

    private class Observed(val shown: Shown, val verdict: Verdict)

    private var shown: Shown? = null
    private var lineage: Any = Any()
    private var departure: GamelistDeparture? = null
    private var observed: Observed? = null
    private var lastFrame: GamelistDepartureFrame? = null

    fun observe(
        games: List<GameRow>,
        centredIndex: Int,
        scope: Any?,
        leaving: (GameRow) -> Boolean,
        motionEnabled: Boolean,
        /** Walk-32h: whether the game that is leaving is one the overlay is breaking apart. Asked once, as it is seated. */
        shatters: (GameRow) -> Boolean = { false },
        /** The keys of [games] when the caller already has them (its [GamelistKeyIndex]); made only if two lists are compared and it has none. */
        keys: List<FocusKey>? = null,
    ): GamelistDepartureFrame {
        val seen = observed
        val analysed = if (
            seen != null && seen.shown.games === games && seen.shown.centred == centredIndex && seen.shown.scope == scope
        ) {
            seen
        } else {
            val now = Shown(games, centredIndex, scope, keys)
            Observed(now, judge(now, leaving, motionEnabled, shatters)).also { observed = it }
        }
        val verdict = analysed.verdict
        // `finished` is read on EVERY path, including the frame that seats a departure: that read is what subscribes this
        // composition to it, and without one nothing recomposes when the motion has run and the card would stay on screen.
        val candidate = if (verdict.keepsCommitted) departure else verdict.departure
        val departureNow = candidate?.takeUnless { it.finished }
        // The same frame while nothing in it has changed. A screen takes the frame as a parameter, and a new instance on every pass is a changed parameter:
        // the game shelf under the launch card was re-run for each pass that only moved a scope (owperf, 2026-10-15). Like `observed` above, a memo of a pure
        // answer: an abandoned composition leaves the same frame it would have made.
        val previous = lastFrame
        if (previous != null && previous.departure === departureNow && previous.lineage === verdict.lineage && previous.centreBounds === centreBounds) {
            return previous
        }
        return GamelistDepartureFrame(departureNow, verdict.lineage, centreBounds).also { lastFrame = it }
    }

    fun commit() {
        val seen = observed ?: return
        shown = seen.shown
        lineage = seen.verdict.lineage
        departure = if (seen.verdict.keepsCommitted) departure?.takeUnless { it.finished } else seen.verdict.departure
        // What was observed is now what is shown, so the next composition judges against it: a verdict remembered past this
        // point would keep saying "a departure was just seated" and would never read [GamelistDeparture.finished] again.
        observed = null
    }

    private fun judge(now: Shown, leaving: (GameRow) -> Boolean, motionEnabled: Boolean, shatters: (GameRow) -> Boolean): Verdict {
        val before = shown ?: return Verdict(null, lineage, keepsCommitted = true)
        // The same list is the same keys: a focus step moves the centre inside it and compares nothing.
        if (before.games === now.games || before.keys == now.keys) return Verdict(null, lineage, keepsCommitted = true)
        val removal = if (motionEnabled && before.scope == now.scope) {
            singleGamelistRemoval(before.games, now.games, leaving)
        } else {
            null
        }
        if (removal != null && gamelistDepartureSeats(removal, before.games.size, before.centred, now.centred)) {
            val active = departure?.takeUnless { it.finished }
            val seated = if (active?.game?.gameId == removal.game.gameId) {
                active
            } else {
                GamelistDeparture(
                    removal.game,
                    removal.index,
                    centreBounds.value,
                    kind = if (shatters(removal.game)) GamelistDepartureKind.Shatter else GamelistDepartureKind.Dissolve,
                )
            }
            return Verdict(seated, lineage, keepsCommitted = false)
        }
        return Verdict(null, Any(), keepsCommitted = false)
    }
}

/**
 * Runs [departure]: holds the pad until the gap has shut, plays the motion on the house overlay clock, and marks it
 * finished. The hold is released on cancellation too (a departure replaced by the next, or a screen that leaves), and the
 * gate lapses on its own if this never runs. A departure is only created when the animators are on, so there is no
 * reduced-motion branch here: the motion that would be skipped never starts.
 */
@Composable
internal fun GamelistDepartureRunner(departure: GamelistDeparture?) {
    LaunchedEffect(departure) {
        val leaving = departure ?: return@LaunchedEffect
        holdMimirInputUntilShown(leaving) { leaving.finished }
        when (leaving.kind) {
            GamelistDepartureKind.Dissolve ->
                leaving.progress.animateTo(1f, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
            // Walk-32h: the shards fly from 0; the hero hands over at 300 while the gap starts to close, on the house
            // easing over 360 ms; the pad is released at 660, when the gap has shut. Both clocks are animation clocks, so
            // a slowed animator slows them together.
            GamelistDepartureKind.Shatter -> coroutineScope {
                launch {
                    mimirHold(MimirShatterMotion.HeroHoldMs)
                    leaving.heroReleased = true
                }
                leaving.progress.animateTo(
                    1f,
                    tween(
                        MimirShatterMotion.GapGlideMs,
                        delayMillis = MimirShatterMotion.GapLeadMs,
                        easing = MimirMotion.Easing,
                    ),
                )
            }
        }
        leaving.finished = true
    }
}
