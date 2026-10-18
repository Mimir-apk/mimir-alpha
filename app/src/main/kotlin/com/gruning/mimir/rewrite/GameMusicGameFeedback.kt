package com.gruning.mimir.rewrite

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import kotlinx.coroutines.flow.StateFlow

/**
 * What one press of "Download music for this game" came to , in the order the player should hear of it.
 *
 * The press used to say nothing: the run's state reached only the Settings > Music card, so on Home or the Library a game
 * that got its sound, one that had none to find, a failure and a refusal all looked the same as nothing happening. A press is
 * now answered where it was asked, on Main's one message rail ([showMimirMessage]).
 */
internal sealed interface GameMusicPress {
    /** The run began. */
    data object Started : GameMusicPress

    /**
     * A download already holds the gate, so nothing started. [sameGame] is true when it is THIS game's own press still going
     * (a second press), false when it is the Settings batch or another game's run.
     */
    data class Busy(val sameGame: Boolean) : GameMusicPress

    /** The press was refused at the door: not connected, or an artwork enrich holds the shared gate. */
    data class Refused(val block: GameMusicBlock) : GameMusicPress

    /**
     * The run is over. [tally] is what it counted; [stopped] is true when it was cancelled (the Settings card's Cancel stops
     * a per-game run too), which the player did on purpose and is not told about.
     */
    data class Ended(val tally: GameMusicTally, val stopped: Boolean = false) : GameMusicPress
}

/**
 * One message for the rail: the text, its intention, the optional second line, and how long the rail keeps it ([holdMs]: it replaced a
 * 2.4 s glance that was gone before the sweep's screenshot, the earlier update).
 */
internal data class GameMusicNotice(
    val text: String,
    val messageClass: MimirMessageClass,
    val supportingText: String? = null,
    val holdMs: Long = GameMusicProgressHoldMs,
)

/** A "finding music" note stays up this long at the least: the run it announces rarely ends sooner, and its result replaces it in place. */
internal const val GameMusicProgressHoldMs = 4_000L

/** An outcome (saved, already has, none found) is read, not glanced at: longer than the progress note it replaces. */
internal const val GameMusicResultHoldMs = 6_000L

/** A refusal or a failure says what to do next (connect, wait, try later), so it stays longest. */
internal const val GameMusicRefusalHoldMs = 7_000L

/** Longest a game's name runs in a message before it is cut with an ellipsis; the rail is two short lines. */
internal const val GameMusicNameMaxChars = 36

/**
 * Where the ScreenScraper connection lives inside Settings: Appearance > Enrichment & artwork > Profile (its Connect row). The
 * supporting line carries the whole path from Settings, on one line: the sentence above it wraps at a large font, the path never does.
 * The Music page's own row opens the connect sheet directly.
 */
internal const val GameMusicConnectWhereCopy = "Settings › Appearance › Enrichment & artwork"

internal const val GameMusicNotConnectedCopy = "Connect ScreenScraper to download music."

internal const val GameMusicEnrichRunningCopy = "Artwork is downloading. Try again when it’s done."

/** The game's name as a message carries it: trimmed, on one line, cut at [GameMusicNameMaxChars], and "this game" when it has none. */
internal fun gameMusicDisplayName(raw: String): String {
    val flat = raw.trim().replace(Regex("\\s+"), " ")
    return when {
        flat.isEmpty() -> "this game"
        flat.length <= GameMusicNameMaxChars -> flat
        else -> flat.take(GameMusicNameMaxChars - 1).trimEnd() + "…"
    }
}

/** The name closed with [mark], unless it already ends in punctuation (a cut name ends in "…"): never "…." or "!.". */
private fun String.closing(mark: String): String = if (last() in ".!?…") this else this + mark

/**
 * The message a press's [press] earns, or null when it earns none (a run the player stopped). Pure: the whole copy table.
 *
 * What the tally can and cannot tell apart, for the record: written, failed and skipped are three disjoint counts, and the
 * per-game path adds [GameMusicTally.alreadyHad] inside skipped, so "saved", "couldn’t" and "already has music" are certain.
 * "No music found" is every OTHER skip: ScreenScraper had no record, the record had no clip, the clip had no usable audio,
 * or the platform has no ScreenScraper coverage. The tally cannot split those, and for the player they are the same answer.
 * A tally with nothing counted at all means the run never reached the provider (the connection gate closed under it), and
 * says so as a refusal rather than claiming a result.
 */
internal fun gameMusicGameNotice(press: GameMusicPress, gameName: String): GameMusicNotice? {
    val name = gameMusicDisplayName(gameName)
    return when (press) {
        GameMusicPress.Started -> GameMusicNotice("Finding music for ${name.closing("…")}", MimirMessageClass.Guidance)
        is GameMusicPress.Busy ->
            if (press.sameGame) {
                GameMusicNotice("Still finding music for ${name.closing("…")}", MimirMessageClass.Guidance)
            } else {
                // The Settings batch, or another game's run, holds the gate: the house line for that, as the artwork entries say.
                GameMusicNotice(GameMusicDownloadingCopy, MimirMessageClass.Guidance, holdMs = GameMusicRefusalHoldMs)
            }
        is GameMusicPress.Refused -> when (press.block) {
            GameMusicBlock.NotConnected -> notConnectedNotice()
            GameMusicBlock.EnrichRunning -> GameMusicNotice(GameMusicEnrichRunningCopy, MimirMessageClass.Guidance, holdMs = GameMusicRefusalHoldMs)
        }
        is GameMusicPress.Ended -> {
            val tally = press.tally
            when {
                press.stopped -> null
                tally.written > 0 -> GameMusicNotice("Music saved for ${name.closing(".")}", MimirMessageClass.Confirmation, holdMs = GameMusicResultHoldMs)
                tally.failed > 0 -> GameMusicNotice("Couldn’t download music for ${name.closing(".")} Try again later.", MimirMessageClass.Warning, holdMs = GameMusicRefusalHoldMs)
                tally.alreadyHad > 0 -> GameMusicNotice("$name already has music.", MimirMessageClass.Guidance, holdMs = GameMusicResultHoldMs)
                tally.skipped > 0 -> GameMusicNotice("No music found for ${name.closing(".")}", MimirMessageClass.Guidance, holdMs = GameMusicResultHoldMs)
                else -> notConnectedNotice()
            }
        }
    }
}

private fun notConnectedNotice() = GameMusicNotice(
    text = GameMusicNotConnectedCopy,
    messageClass = MimirMessageClass.Warning,
    supportingText = GameMusicConnectWhereCopy,
    holdMs = GameMusicRefusalHoldMs,
)

/**
 * The menu's "Download music for this game": starts [gameId]'s run and hands every message it earns to [show] (Main's rail in
 * the app). One entry for Home Edit and the Library hero menu, which share the action. The Settings batch does not come
 * through here and says nothing new.
 */
internal fun MimirArtworkState.downloadGameMusicFromMenu(
    gameId: String,
    gameName: String,
    show: (GameMusicNotice) -> Unit,
    runForGame: suspend (Context, String, (GameMusicTally) -> Unit) -> GameMusicTally = GameMusicBatch::runForGame,
) {
    downloadMusicForGame(
        gameId = gameId,
        report = { press -> gameMusicGameNotice(press, gameName)?.let(show) },
        runForGame = runForGame,
    )
}

/**
 * Whether a game-music run is going, one game's press or the Settings batch (the gate admits one at a time): the menu's "Download music
 * for this game" shows busy while it is. Reads [GameMusicDownloadState.Running] from the same flow the Settings > Music card reads, and
 * only recomposes its caller when that turns on or off, not on each game a batch gets through.
 */
@Composable
internal fun rememberGameMusicRunning(state: StateFlow<GameMusicDownloadState>): Boolean {
    val current by state.collectAsState()
    val running by remember { derivedStateOf { current is GameMusicDownloadState.Running } }
    return running
}

/**
 * Whether the game-music row cannot run for want of a connected account: [GameMusicBlock.NotConnected], the block the Settings > Music card and the press itself
 * read ([gameMusicBlock]). It is the state Enrich's pill is dimmed from in the Library (`gamelistEnrichAllowed`, the account's readiness): both are fetches from the
 * provider and both need the connection, so both read as "needs setup" at rest, and both stay pressable (the press answers on the rail, "connect first").
 */
@Composable
internal fun rememberGameMusicNotConnected(state: StateFlow<GameMusicDownloadState>): Boolean {
    val current by state.collectAsState()
    val notConnected by remember { derivedStateOf { current.block == GameMusicBlock.NotConnected } }
    return notConnected
}
