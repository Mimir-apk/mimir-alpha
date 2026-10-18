package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.files.discSetMoveOptions
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * Warn before a move can break a multi-disc game, and offer to move the full set. This is the
 * last screen between a chosen destination and the transfer.
 *
 * ## Why a screen and not a card
 *
 * The cheap build was a queue card: park the move in `AwaitingConflict`, ask the question there, reuse
 * the conflict machinery whole. It was rejected on evidence from the recorded device pass — a parked
 * operation is invisible until the player opens the queue, and in that pass the queue was never opened,
 * so the question sat unasked and unanswered while the card said nothing was happening. A warning the
 * player has to go looking for is the silent failure with extra steps.
 *
 * ## Why it is the LAST screen and not the first
 *
 * It opens after the destination is settled, on the same press that would have started the move. Asking
 * earlier — at *Move*, before a destination exists — would mean warning about a move that may never be
 * confirmed, and the warning's own repair ("take them all to the destination") has no destination to
 * name yet.
 *
 * ## What it never does
 *
 * **It never re-decides whether there is a set.** `LibraryRepository.discSetFor` already refused every
 * case that does not really break — duplicates, title-grouped sets with no playlist, files with no row —
 * and `DiscSetMovePolicy` already decided whether moving them together helps. This screen renders that
 * answer and reports a row back. A second opinion here is how two answers start disagreeing.
 */
internal object FileBrowserDiscSetFocus {

    /** Distinct by construction from `files/transfer` and `files/destination`. */
    val Scope: FocusScope = FocusScope("files/discset")

    fun optionKey(option: DiscSetMoveOption): FocusKey = FocusKey("$OPTION_PREFIX${option.name}")

    /** Which option [key] presses, or null when it is not one of this screen's rows. */
    fun optionOf(key: FocusKey?): DiscSetMoveOption? {
        val name = key?.value?.takeIf { it.startsWith(OPTION_PREFIX) }?.removePrefix(OPTION_PREFIX)
            ?: return null
        return DiscSetMoveOption.entries.firstOrNull { it.name == name }
    }

    private const val OPTION_PREFIX = "files/discset/"
}

/**
 * The open warning, as one value — **and the destination is carried here, never re-read.**
 *
 * [FileBrowserTransferChooser]'s rule, and the stakes are the same or higher: by the time this screen is
 * up the player has picked a destination and the pick session has been ended, so there is nowhere else
 * the destination still exists. Re-deriving it from the browse state would move a set into whatever
 * folder happens to be current.
 *
 * @param warning what the library found, already checked — see [DiscSetWarning].
 * @param destination where the move is going, settled before this screen opened.
 * @param rows the options as [discSetMoveOptions] decided them, snapshotted.
 */
internal data class FileBrowserDiscSetPrompt(
    val warning: DiscSetWarning,
    val destination: String,
    val rows: List<DiscSetMoveOption>,
)

/** Every focusable key the warning holds, in render order. */
internal fun fileBrowserDiscSetKeys(rows: List<DiscSetMoveOption>): List<FocusKey> =
    rows.map(FileBrowserDiscSetFocus::optionKey)

/**
 * The sentence the screen leads with.
 *
 * **Names the game and the disc, and says what breaks in the player's words, not the library's.** "Part
 * of a multi-disc group" is what the data says; "Disc 2 of 3" is what the player owns. A [
 * DiscSetWarning.discNumber] of 0 means the library never numbered this file — the playlist resolved it
 * but did not order it — so the sentence degrades to "one of 3 discs" rather than inventing "Disc 0".
 */
internal fun fileBrowserDiscSetHeadline(res: Resources, warning: DiscSetWarning): String =
    // One format string per case, with the disc number, the count and the game's own title in its slots: the sentence is never assembled from translated pieces.
    if (warning.discNumber > 0) {
        res.getString(R.string.files_discset_headline_numbered, warning.discNumber, warning.discCount, warning.title)
    } else {
        res.getString(R.string.files_discset_headline_unnumbered, warning.discCount, warning.title)
    }

/**
 * The line underneath, which is the actual warning.
 *
 * Two versions, because the honest statement differs: when the set can travel there is a repair on the
 * screen, and when it cannot there is only a choice. **Neither version says "are you sure".** The player
 * is told what happens to the game, once, and then given the presses — a confirmation that merely asks
 * for the press again is the pattern already removed from delete.
 */
internal fun fileBrowserDiscSetDetail(res: Resources, warning: DiscSetWarning): String = res.getString(fileBrowserDiscSetDetailRes(warning))

/** Which of the warning's four sentences [fileBrowserDiscSetDetail] says, chosen from the library's checked facts. */
private fun fileBrowserDiscSetDetailRes(warning: DiscSetWarning): Int = when {
    // No playlist to name, and naming one would be a fabrication -- the reference GameCube sets are bare
    // (Disc 1)/(Disc 2) .iso pairs. What breaks is the disc swap: the emulator asks for the next disc
    // and it is in another folder now. Same loss, different mechanism, so a different sentence.
    // **Wording remains unreviewed.** The existing copy describes a single-folder GameCube set,
    // and it stands for that case below, untouched. It does not extend to this one: "The other discs stay
    // in this folder" is a checkable falsehood when they are already somewhere else, and a warning that
    // states one teaches the player the whole screen is guessing. Scoped rather than rewritten — the seam
    // is surfaced, not widened.
    !warning.hasPlaylist && warning.spansFolders -> R.string.files_discset_detail_split_folders
    !warning.hasPlaylist -> R.string.files_discset_detail_bare
    warning.wholeSetCanTravel -> R.string.files_discset_detail_playlist_by_name
    else -> R.string.files_discset_detail_playlist_fixed
}

/** Multi-disc warning copy verified on Odin. FileBrowserDiscSetChoiceTest pins the exact wording. */
/** The row's own label. */
internal fun fileBrowserDiscSetLabel(res: Resources, option: DiscSetMoveOption, warning: DiscSetWarning): String =
    when (option) {
        DiscSetMoveOption.MOVE_WHOLE_SET -> res.getString(R.string.files_discset_row_move_all, warning.discCount)
        DiscSetMoveOption.MOVE_SUBJECT_ONLY -> res.getString(R.string.files_discset_row_move_one)
        DiscSetMoveOption.CANCEL -> res.getString(R.string.files_discset_row_leave)
    }

/** The row's second line, or blank — which draws nothing, per the extract chooser's own rule. */
internal fun fileBrowserDiscSetDetail(res: Resources, option: DiscSetMoveOption, warning: DiscSetWarning): String =
    when (option) {
        // Names the playlist explicitly. "All 3 discs" would be a count the player can check against the
        // folder and find wrong -- the playlist is a fourth file, and it is the one that has to arrive.
        DiscSetMoveOption.MOVE_WHOLE_SET ->
            if (warning.hasPlaylist) {
                res.getString(R.string.files_discset_row_detail_with_playlist)
            } else {
                res.getString(R.string.files_discset_row_detail_together)
            }
        DiscSetMoveOption.MOVE_SUBJECT_ONLY ->
            if (warning.wholeSetCanTravel) res.getString(R.string.files_discset_row_detail_stops) else ""
        DiscSetMoveOption.CANCEL -> ""
    }

/** The warning's own normalised width. Arbitrary magnitude, like every grid in this module. */
private const val FileBrowserDiscSetGridWidth = 20_000f

/** One option row's vertical pitch. */
private const val FileBrowserDiscSetRowPitch = 3_000f

/**
 * Synthetic geometry for the warning's row at [index] — [fileBrowserTransferOptionBounds]' shape, for
 * [fileBrowserTransferOptionBounds]' reasons: full-width rows in a scope containing nothing else, so a
 * horizontal press has no geometrically valid answer and the dispatch absorbs it as the hard modal it is.
 */
internal fun fileBrowserDiscSetOptionBounds(index: Int): FocusBounds {
    val top = index * FileBrowserDiscSetRowPitch
    return FocusBounds(
        left = 0f,
        top = top,
        right = FileBrowserDiscSetGridWidth,
        bottom = top + FileBrowserDiscSetRowPitch,
    )
}

/**
 * The warning screen's focus slots — one per option row.
 *
 * A sixth tracker, for [FileBrowserTransferFocusTracker]'s reasons exactly: these registrations must
 * exist only while the warning is up and must ALL be gone when it closes, and the virtual registration is
 * not optional because `DefaultFocusAuthority.requestFocus` is a silent no-op on an unregistered key.
 */
internal class FileBrowserDiscSetFocusTracker {
    private val registered = linkedSetOf<FocusKey>()

    fun update(authority: FocusAuthority, scope: FocusScope, rows: List<DiscSetMoveOption>) {
        val desired = linkedSetOf<FocusKey>()
        rows.forEachIndexed { index, option ->
            val key = FileBrowserDiscSetFocus.optionKey(option)
            authority.registerVirtual(key, scope, fileBrowserDiscSetOptionBounds(index))
            desired.add(key)
        }
        registered.filterNot { it in desired }.forEach { key -> retire(authority, key) }
        registered.clear()
        registered.addAll(desired)
    }

    /** Unregisters everything this tracker registered. Idempotent. */
    fun clear(authority: FocusAuthority) {
        registered.forEach { key -> retire(authority, key) }
        registered.clear()
    }

    private fun retire(authority: FocusAuthority, key: FocusKey) {
        authority.unregisterVirtual(key)
        authority.unregister(key)
    }
}
