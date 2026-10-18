package com.gruning.mimir.rewrite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is holding [ArtworkOperationGate.process] right now. */
internal enum class ArtworkOperation {
    /** Nothing: either kind of operation may start. */
    NONE,

    /** One or more artwork enrich operations: Settings Download, the first-run batch, a per-game Enrich. */
    ENRICH,

    /** The game-music download, whole library or a single game. */
    GAME_MUSIC,
}

/**
 * The one process-wide gate that keeps the game-music download and the artwork enrich from running at the same time.
 *
 * **Why they must not overlap.** Both spend the same ScreenScraper account allowance (its thread count, its requests
 * per day), and both report their cost through a batch meter that is process-global. The download keeps a meter of its
 * own, but a per-game Enrich counts into [ArtworkFetchController.enrichCost] with no batch of its own at all, and a
 * download running beside a Settings Download would double the account's concurrency past what it allowed.
 *
 * **The rule.** An enrich claim is SHARED: any number of enrich operations may hold the gate together, exactly as they
 * could before there was a gate, and so a first-run batch's own per-game steps simply take their own shared claims and
 * need no way around it. A game-music claim is EXCLUSIVE: it is refused while anything holds the gate, and everything
 * else is refused while it holds the gate. Both directions are decided here and nowhere else, and the UI's disabled
 * state reads [active], so a row can never say "start" for something this would refuse.
 *
 * **How a claim ends.** Every holder releases in a `finally` or a completion handler, cancellation included. A [Claim]
 * releases once however many times it is released, so the two exits of a coroutine that is cancelled before it starts
 * cannot take the gate down twice.
 */
internal class ArtworkOperationGate {
    private val lock = Any()
    private var enrichClaims = 0
    private var gameMusicClaimed = false
    private val activeFlow = MutableStateFlow(ArtworkOperation.NONE)

    /** What holds the gate, as a flow, for the rows that are disabled while it is held. */
    val active: StateFlow<ArtworkOperation> = activeFlow.asStateFlow()

    /** A shared claim for an enrich operation, or null while the game-music download holds the gate. */
    fun tryClaimEnrich(): Claim? = synchronized(lock) {
        if (gameMusicClaimed) return null
        enrichClaims += 1
        publish()
        Claim(ArtworkOperation.ENRICH)
    }

    /** The exclusive claim for the game-music download, or null while anything at all holds the gate. */
    fun tryClaimGameMusic(): Claim? = synchronized(lock) {
        if (gameMusicClaimed || enrichClaims > 0) return null
        gameMusicClaimed = true
        publish()
        Claim(ArtworkOperation.GAME_MUSIC)
    }

    private fun publish() {
        activeFlow.value = when {
            gameMusicClaimed -> ArtworkOperation.GAME_MUSIC
            enrichClaims > 0 -> ArtworkOperation.ENRICH
            else -> ArtworkOperation.NONE
        }
    }

    /** One hold on the gate. [release] is idempotent. */
    inner class Claim internal constructor(private val kind: ArtworkOperation) {
        private var released = false

        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                when (kind) {
                    ArtworkOperation.ENRICH -> enrichClaims = (enrichClaims - 1).coerceAtLeast(0)
                    ArtworkOperation.GAME_MUSIC -> gameMusicClaimed = false
                    ArtworkOperation.NONE -> Unit
                }
                publish()
            }
        }
    }

    companion object {
        /** The process's gate: every real entry point claims this one. */
        val process = ArtworkOperationGate()
    }
}
