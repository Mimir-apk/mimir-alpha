package com.gruning.mimir.rewrite.core.audio

import android.media.AudioAttributes

/**
 * What a game Mimir launched leaves playing behind Mimir, and how the courtesy gate stops reading it as another app's music.
 *
 * **The defect (Thor, 2026-10-10).** A 3DS emulator Mimir had launched stayed STARTED in the background after Mimir returned to Home: an AAudio
 * player, `USAGE_MEDIA`, `CONTENT_TYPE_UNKNOWN`, holding no audio focus. [MimirMediaBed] counted it as an unowned stranger
 * (`blocked=true music=true observed=1 relevant=1 owned=0`), so the theme bed never sounded again.
 *
 * **What Mimir can see.** `AudioManager.getActivePlaybackConfigurations()` is anonymised for an app that does not hold MODIFY_AUDIO_ROUTING:
 * the client uid and pid, the player type and the player's own interface are hidden, and the public class offers the audio attributes and the
 * device and nothing else. So the owner of a stream is unknowable here, and `AudioPlaybackConfiguration.equals` is not a stable handle to a
 * player either ([mimirMediaBedRekeyClaims] measured it folding in the player's state). The only identity left is the stream's SIGNATURE
 * (usage and content type) and how many streams of it there are. This class therefore works in counts per signature, the same conservatism
 * the bed's own claims keep.
 *
 * **The rule.** Mimir arms the game launch ([MimirAudioFocus.onGameLaunchArmed]) and gets control back ([MimirAudioFocus.onReturnedToMimir]).
 * At the launch it counts the game-like streams that are somebody else's: those playing, less the ones already discounted as a game kept from
 * before. At the return it counts again, and everything beyond those is the game's: a budget per signature. While the game is not in front, up
 * to that many streams of that signature do not count as another app's music. Everything else still counts, so a stream that starts later, or
 * one more than the budget, blocks.
 *
 *  - A stream only qualifies when it LOOKS like a game's ([mimirKeptGameSignature]): `USAGE_GAME`, or `USAGE_MEDIA` with the unknown content
 *    type that default-configured native audio gets. Real music apps tag `CONTENT_TYPE_MUSIC` or `CONTENT_TYPE_MOVIE` ([isRealMediaPlayback])
 *    and never qualify, so the relevant-content filter ([mimirMediaBedCourtesyRelevantContent]) is NOT widened: an app that tags its music
 *    unknown is still a stranger everywhere except in the one count this class makes at a return from a Mimir launch.
 *  - A player with a media session that is PLAYING at the return, while no stream there is real media, cannot be told from the game by its
 *    attributes alone. Nothing new is discounted then ([strangerSessionPlaying]); the gate stays closed.
 *  - The budget follows the COUNT, and a drop in the count comes off the budget first. A fall in the number of streams of a signature takes
 *    that many off the discount at once (conservatively: the game is assumed to be what went), so a stranger left behind when the game closes
 *    is a stranger again, and so is a newcomer of the same signature after the game closed. The drop is only FINAL after [ReopenGraceMs]: a
 *    game that closes its stream and reopens it (a headphone or Bluetooth route change, a second launch in the same emulator) is the same
 *    game, so a count that comes back inside the grace gets its discount back.
 *  - A drop is measured against the count that has STOOD, not the highest one seen. A rise is real only once it has stood for the same grace: a
 *    route change that opens the new stream before it closes the old one (the count goes 1, 2, 1), or another app's short game-like stream,
 *    is a bounce, and the fall back takes nothing off the discount. A rise that stood past the grace is a real stranger, and when the count
 *    then falls the game is assumed to be what went (the case above), because Mimir cannot tell which of the two streams closed.
 *  - A cold start (no launch armed) snapshots nothing.
 *  - While the game is in front ([onLaunchArmed] .. [onReturned]) nothing is discounted: the bed is silent then anyway, and the claim is only
 *    "while that game is NOT in front".
 *
 * **Known limits** (accepted, not fixed: a stream's owner is unknowable here, so each is a count that cannot be told from the game's).
 *  - **The grace leak.** An app whose stream has the game's signature (`USAGE_MEDIA` + `CONTENT_TYPE_UNKNOWN`) that opens within [ReopenGraceMs]
 *    of the game's stream closing is read as the game reopened, and is discounted until its stream goes. It needs three things at once: the game's
 *    stream closed, an unknown-typed app opening inside that 2 s, and notification access off (with it, a playing media session at the return
 *    stops the discount; and a stream that opens after the grace is a stranger). An app that names its music `CONTENT_TYPE_MUSIC` or
 *    `CONTENT_TYPE_MOVIE` never qualifies.
 *  - **A same-signature stranger that stood.** One that has stood for the grace and then leaves takes the game's discount with it (above): the
 *    bed then waits for the next launch.
 *  - **An unknown-typed app started DURING the game.** Counted at the return, it is read as the game's: the launch snapshot cannot see a stream
 *    that did not exist yet. Without notification access nothing tells them apart.
 *  - **A restart** loses the discount, which lives in memory. It is seeded back ([seed]) only when the caller proves a launched game's task is
 *    still there (see [MimirMediaBed.seedKeptGame]); otherwise the game's stream is a stranger again until the next launch.
 *
 * Not thread-safe by itself: [MimirMediaBed] calls it under its lock. Generic over the configuration so a host test can drive it without
 * constructing an `AudioPlaybackConfiguration`. Times are `uptimeMillis` readings the caller passes in, so a test owns the clock.
 */
internal class MimirKeptGameStreams<T>(
    /** The stream's game-like signature, or null when it is not a game-like stream ([mimirKeptGameSignature]). */
    private val gameLikeSignature: (T) -> Any?,
    /** USAGE_MEDIA tagged music or movie: what a real player is ([isRealMediaPlayback]). */
    private val isRealMedia: (T) -> Boolean,
    private val graceMs: Long = ReopenGraceMs,
) {
    /** Game-like streams that were somebody else's when the launch was armed; null outside a launch window. */
    private var strangersAtLaunch: Map<Any, Int>? = null
    private var gameInFront = false

    /** The streams of each signature that are the kept game's. */
    private val budget = HashMap<Any, Int>()

    /** The count of each signature as last confirmed: what a drop is measured against. */
    private val confirmed = HashMap<Any, Int>()

    /** When a signature's count first fell below [confirmed], and the lowest it has been since: the grace in which a reopened stream is the same stream. */
    private val fellAtMs = HashMap<Any, Long>()
    private val lowest = HashMap<Any, Int>()

    /**
     * When a signature's count first rose above [confirmed], and the lowest it has been since (the streams that stood throughout): a rise that has
     * stood for the grace is confirmed, one that fell back to [confirmed] inside it was a bounce and never counted.
     */
    private val roseAtMs = HashMap<Any, Long>()
    private val riseFloor = HashMap<Any, Int>()

    /**
     * A Mimir game launch was armed: the game-like streams already playing, less the game kept from before, are not the new game's. Keeps the
     * first count of a launch window.
     */
    fun onLaunchArmed(unowned: Collection<T>, nowMs: Long) {
        settle(unowned, nowMs)
        gameInFront = true
        if (strangersAtLaunch == null) {
            strangersAtLaunch = counts(unowned).mapValues { (signature, present) -> (present - effective(signature, present)).coerceAtLeast(0) }
        }
    }

    /**
     * Control came back to Mimir after a launch it armed. [unowned] are the relevant streams that are not Mimir's own. Returns how many streams
     * are discounted now, for the log.
     */
    fun onReturned(unowned: Collection<T>, strangerSessionPlaying: Boolean, nowMs: Long): Int {
        settle(unowned, nowMs)
        gameInFront = false
        val atLaunch = strangersAtLaunch ?: return budget.values.sum()
        strangersAtLaunch = null
        if (strangerSessionPlaying && unowned.none(isRealMedia)) return budget.values.sum()
        counts(unowned).forEach { (signature, present) ->
            // Everything beyond the strangers is the game's: the game kept from before, if its stream is still there, and the one just left.
            budget[signature] = (present - (atLaunch[signature] ?: 0)).coerceIn(0, present)
            confirmed[signature] = present
            fellAtMs.remove(signature)
            lowest.remove(signature)
            clearRise(signature)
        }
        budget.values.removeAll { it == 0 }
        return budget.values.sum()
    }

    /**
     * A restart: this process never saw the launch, but the caller has proved [keptTasks] games Mimir launched are still running (an open orphan
     * record of the elapsed store, and the task lookup found the emulator's task). Discounts the game-like streams in [unowned] up to that many
     * in all, and returns how many. Nothing is seeded when a launch or a return has already been seen in this process (it owns the budget), when a
     * game is in front, or when [strangerSessionPlaying] and no stream there is real media (the same ambiguity as at a return).
     */
    fun seed(unowned: Collection<T>, keptTasks: Int, strangerSessionPlaying: Boolean, nowMs: Long): Int {
        if (keptTasks <= 0 || gameInFront || strangersAtLaunch != null || budget.isNotEmpty()) return 0
        if (strangerSessionPlaying && unowned.none(isRealMedia)) return 0
        settle(unowned, nowMs)
        var left = keptTasks
        counts(unowned).entries
            .sortedWith(compareByDescending<Map.Entry<Any, Int>> { it.value }.thenBy { it.key.toString() })
            .forEach { (signature, present) ->
                confirmed[signature] = present
                val take = minOf(left, present)
                if (take > 0) {
                    budget[signature] = take
                    left -= take
                }
            }
        return budget.values.sum()
    }

    /** The playback list changed at [nowMs]: a stream that is gone takes its share of the budget with it once the grace has passed. */
    fun onPlayback(relevant: Collection<T>, nowMs: Long) {
        if (budget.isEmpty() && confirmed.isEmpty()) return
        settle(relevant, nowMs)
    }

    /**
     * Whether the game is on screen, as the music control reads it between launches and returns: a game fronted from the Bottom game's tab, from
     * Android's Recents or by the quick switch has no launch edge (a Return or a HOME Resume of a Main game arms one when Main stops), so
     * [onLaunchArmed] never saw it come back. Only the flag moves (the budget and the counts stay), so the discount stops while the game is on
     * screen and is back, unchanged, when it is not. True when it changed.
     */
    fun setFront(front: Boolean): Boolean {
        if (gameInFront == front) return false
        gameInFront = front
        return true
    }

    /** How many of [relevant] are not another app's music: the game Mimir launched, which is not in front. */
    fun discount(relevant: Collection<T>): Int {
        if (gameInFront || budget.isEmpty()) return 0
        val now = counts(relevant)
        return budget.keys.sumOf { signature -> effective(signature, now[signature] ?: 0) }
    }

    /** True once a return has left a budget standing. */
    fun holding(): Boolean = budget.isNotEmpty()

    /** What the budget of [signature] is worth with [present] streams of it: the budget, less the streams that have gone since it was last confirmed, never more than there are. */
    private fun effective(signature: Any, present: Int): Int {
        val dropped = ((confirmed[signature] ?: 0) - present).coerceAtLeast(0)
        return ((budget[signature] ?: 0) - dropped).coerceIn(0, present)
    }

    /** Folds one look at the playback list into the confirmed counts and the budget. */
    private fun settle(relevant: Collection<T>, nowMs: Long) {
        val now = counts(relevant)
        (budget.keys + confirmed.keys + roseAtMs.keys + now.keys).toSet().forEach { signature ->
            val present = now[signature] ?: 0
            // A rise that has stood for the grace is real: the streams that stood are confirmed before this look is weighed against them.
            val roseAt = roseAtMs[signature]
            if (roseAt != null && nowMs - roseAt >= graceMs) {
                confirmed[signature] = riseFloor[signature] ?: (confirmed[signature] ?: 0)
                clearRise(signature)
            }
            val before = confirmed[signature] ?: 0
            val fellAt = fellAtMs[signature]
            if (present < before) {
                clearRise(signature)
                if (fellAt == null) {
                    fellAtMs[signature] = nowMs
                    lowest[signature] = present
                } else {
                    lowest[signature] = minOf(lowest[signature] ?: present, present)
                    if (nowMs - fellAt >= graceMs) {
                        commitDrop(signature, before, lowest[signature] ?: present)
                        confirmed[signature] = present
                    }
                }
            } else if (present > before && fellAt == null) {
                // More streams than have stood, with no fall in flux: a rise. It is not confirmed until it has stood for the grace, so a stream
                // that opens before its predecessor closes (a route change) and closes again is a bounce, not a count the fall comes off.
                if (roseAtMs[signature] == null) {
                    roseAtMs[signature] = nowMs
                    riseFloor[signature] = present
                } else {
                    riseFloor[signature] = minOf(riseFloor[signature] ?: present, present)
                }
            } else {
                // Back at the confirmed count or above: inside the grace it is the same stream reopened and nothing is lost; after it, the
                // streams that were gone are gone for good and whatever is here now is new.
                clearRise(signature)
                if (fellAt != null && nowMs - fellAt >= graceMs) commitDrop(signature, before, lowest[signature] ?: present)
                fellAtMs.remove(signature)
                lowest.remove(signature)
                confirmed[signature] = present
            }
            if ((budget[signature] ?: 0) == 0) budget.remove(signature)
            if ((confirmed[signature] ?: 0) == 0 && !fellAtMs.containsKey(signature) && !roseAtMs.containsKey(signature)) confirmed.remove(signature)
        }
    }

    private fun clearRise(signature: Any) {
        roseAtMs.remove(signature)
        riseFloor.remove(signature)
    }

    /** The drop outlasted the grace: [before] streams fell to [lowestSeen], those are gone for good, and the discount loses that many. */
    private fun commitDrop(signature: Any, before: Int, lowestSeen: Int) {
        val gone = (before - lowestSeen).coerceAtLeast(0)
        budget[signature] = ((budget[signature] ?: 0) - gone).coerceAtLeast(0)
        fellAtMs.remove(signature)
        lowest.remove(signature)
    }

    private fun counts(streams: Collection<T>): Map<Any, Int> {
        val result = HashMap<Any, Int>()
        streams.forEach { stream -> gameLikeSignature(stream)?.let { result[it] = (result[it] ?: 0) + 1 } }
        return result
    }

    internal companion object {
        /** How long a stream may be gone before it is gone for good: a game that closes and reopens its stream (a route change, a second launch in the same emulator) is the same game. */
        const val ReopenGraceMs = 2_000L
    }
}

/**
 * The signature of a stream that LOOKS like a game's, or null when it does not. `USAGE_GAME` is a game by declaration; `USAGE_MEDIA` with
 * `CONTENT_TYPE_UNKNOWN` is what native audio (AAudio, OpenSL) gets when the emulator names nothing, which is the measured case. Music, movie
 * and speech content types are an app that says what it plays and never qualify.
 */
internal fun mimirKeptGameSignature(usage: Int, contentType: Int): Any? = when {
    usage == AudioAttributes.USAGE_GAME -> usage to contentType
    usage == AudioAttributes.USAGE_MEDIA && contentType == AudioAttributes.CONTENT_TYPE_UNKNOWN -> usage to contentType
    else -> null
}

/**
 * The environment sample with the kept game taken out. [relevantPlaybackCount] is every relevant stream; [keptGameStreams] of them are the
 * game Mimir launched. They count neither toward [MimirMediaBedEnvironmentSample.activePlaybackCount] nor toward "music is active": a
 * player whose only observed streams are the kept game is not another app's music, exactly as one whose only stream is UI sonification is not.
 */
internal fun mimirMediaBedEnvironmentSample(
    musicActive: Boolean,
    observedPlaybackCount: Int,
    relevantPlaybackCount: Int,
    keptGameStreams: Int,
    ownedPlaybackCount: Int,
    callInterrupted: Boolean,
): MimirMediaBedEnvironmentSample {
    val strangers = (relevantPlaybackCount - keptGameStreams).coerceAtLeast(0)
    return MimirMediaBedEnvironmentSample(
        musicActive = mimirMediaBedMusicActiveForCourtesy(
            musicActive = musicActive,
            observedPlaybackCount = observedPlaybackCount,
            relevantPlaybackCount = strangers,
        ),
        activePlaybackCount = strangers,
        ownedPlaybackCount = ownedPlaybackCount,
        callInterrupted = callInterrupted,
        audioFocusLost = false,
    )
}
