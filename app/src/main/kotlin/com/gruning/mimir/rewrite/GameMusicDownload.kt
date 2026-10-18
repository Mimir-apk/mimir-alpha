package com.gruning.mimir.rewrite

/**
 * Why Settings > Music > Download game music cannot start right now. Null means nothing stands in the way.
 *
 * The two reasons are the two rows of copy the card has: "Connect ScreenScraper first" (and A goes to the
 * connect row), and the artwork enrich being mid-run, because the cost meter is process-global and the two
 * batches cannot share it.
 */
internal enum class GameMusicBlock {
    /** No ScreenScraper account is connected, or the build cannot reach the service. */
    NotConnected,

    /** An artwork enrich is running. The reverse also holds: the enrich cannot start while this one runs. */
    EnrichRunning,
}

/**
 * The game-music download as the Music card shows it. Every state carries [block], so the row can be
 * disabled and say why in whichever state it is drawn.
 *
 * The download fetches the game's launch sound, the clip's audio as an `-audio.m4a` beside the game's art,
 * and nothing else. It is an explicit action on Settings > Music and the per-game menu: the first-run enrich,
 * Settings > Download and the per-game Enrich fetch no sound.
 */
internal sealed interface GameMusicDownloadState {
    val block: GameMusicBlock?

    /** Nothing running. [have] of [total] eligible games already have a sound; pressing again fetches the rest. */
    data class Idle(
        val have: Int,
        val total: Int,
        override val block: GameMusicBlock? = null,
    ) : GameMusicDownloadState

    /**
     * A batch is running: [done] of [total] games attempted so far, [written] of them given a sound.
     *
     * [currentIndex] is the 1-based place of the game being looked up now (0 when no lookup is in flight) and [current] its name, cut
     * to one line, so the card names the work while a slow lookup runs. [stopping] is the press on Cancel, shown the moment it lands:
     * the work stops where it stands, and the card stays Running (this flag on) until it has.
     */
    data class Running(
        val done: Int,
        val total: Int,
        val written: Int,
        val currentIndex: Int = 0,
        val current: String? = null,
        val stopping: Boolean = false,
        override val block: GameMusicBlock? = null,
    ) : GameMusicDownloadState

    /**
     * The batch ended, finished or cancelled. [written] games were given a sound, [skipped] had no clip or no
     * decodable audio (they are not asked again soon), and [failed] were not reached because of an error.
     *
     * [stopped] is true when the player stopped it (or the work was cancelled): the card says "Stopped", never "Done", because the
     * games it did not reach are simply still missing. [failure] is the dominant reason among the failed games, the card's one hint.
     */
    data class Finished(
        val written: Int,
        val skipped: Int,
        val failed: Int,
        val stopped: Boolean = false,
        val failure: GameMusicFailure? = null,
        override val block: GameMusicBlock? = null,
    ) : GameMusicDownloadState
}

/** The same state with [block] swapped in: the block follows the gate and the connection, the rest follows the batch. */
internal fun GameMusicDownloadState.withBlock(block: GameMusicBlock?): GameMusicDownloadState = when (this) {
    is GameMusicDownloadState.Idle -> copy(block = block)
    is GameMusicDownloadState.Running -> copy(block = block)
    is GameMusicDownloadState.Finished -> copy(block = block)
}
