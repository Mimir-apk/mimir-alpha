package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import java.io.File

/**
 * F-11's decision layer for **Extract destinations**: here, a chosen folder, or a recognised platform folder.
 *
 * **The earlier change gives it the door, and the paragraph that used to end this KDoc is deleted rather than left
 * standing.** It said the policy was "finished and pinned; nothing calls it" and that
 * `FileBrowserStore.extractOptions` / `extractTo` had no production caller. After the routing change,
 * `SECONDARY` on an archive routes through [FileBrowserCommand.OfferExtract] into
 * [FileBrowserController.openExtractChooser], and the chooser's rows reach `extractTo` — see
 * [FileBrowserController]'s own extract region. A confidently-worded stale justification is worse than
 * no comment at all, which is the precedent [FileBrowserQueueFocus]' KDoc set when its own
 * dismiss-gap paragraph became false.
 *
 * **Remaining integration dependency:** the two sinks
 * ([FileBrowserController.resolveExtractOptions] / [FileBrowserController.onExtractTo]) are constructor
 * parameters defaulting **null**, and the one production construction site is
 * `FileBrowserStore.mount()` in `app/`. Until those two lines land, every gate in
 * this file answers "no chooser" and `SECONDARY` behaves exactly as it did before the earlier change.
 *
 * This file turns [ExtractDestinationOptions] into the rows a chooser draws. It re-decides nothing: the
 * *paths* are the policy's, and this only names them for a player.
 */
internal enum class FileBrowserExtractOptionKind {
    /** Option 1 — a fresh sibling directory named after the archive. Always available. */
    HERE,

    /** The smart destination — the library's own folder for the platform Mimir recognised. */
    PLATFORM_FOLDER,

    /** The explicit destination browse — the folder picker supplies the destination itself. */
    CHOOSE_FOLDER,
}

/**
 * One row of the Extract chooser.
 *
 * @param labelRes the row's words, a string resource; [labelArg] fills its one slot when it has one.
 * @param destination where this row extracts to, or **null** for [FileBrowserExtractOptionKind
 *   .CHOOSE_FOLDER], whose destination does not exist until the player picks one. Null is what forces a
 *   caller to route that row through a picker rather than silently extracting somewhere.
 * @param detail the second line — the destination folder's own name, so the player reads a place rather
 *   than a path they have to parse. Blank for the picker row, which has no place yet.
 */
internal data class FileBrowserExtractOption(
    val kind: FileBrowserExtractOptionKind,
    @StringRes val labelRes: Int,
    /** The one name a label carries ("Extract in GBA"): a platform code or a folder's own name, never translated. Null when the label is a plain string. */
    val labelArg: String?,
    val detail: String,
    val destination: String?,
) {
    /** The row's words: the string resource, with [labelArg] in its one slot. */
    fun label(res: Resources): String = if (labelArg == null) res.getString(labelRes) else res.getString(labelRes, labelArg)
}

/**
 * The rows the Extract chooser draws for [options], in operation order: "extract here" first,
 * then any smart platform destination, then explicit destination browsing.
 *
 * **An additional destination row appears only when it can actually finish.** Two independent ways it can:
 *  - Mimir named the platform unambiguously, so [ExtractDestinationOptions.suggestedPlatformFolder] is a
 *    real folder — the row extracts straight there.
 *  - A folder picker exists to supply a destination ([folderPickerAvailable]).
 *
 * With neither, the chooser is one row. When both are available, both rows remain visible: the player
 * can use the smart destination or browse to any other storage folder explicitly.
 *
 * **[folderPickerAvailable] is no longer hypothetical, and its consequence is the headline of the earlier change.**
 * The picker is this browser itself, entered through [FileBrowserController]'s destination-pick mode, so
 * [FileBrowserController.offerExtract] passes `true`. The picker row is then *always* present, including
 * after a smart platform row, so a recognised archive exposes both the suggested folder and an explicit
 * browse path to any other storage folder. The single-row shortcut below remains only for callers that
 * have not built a picker.
 *
 * The default stays `false` so a caller that has *not* built a picker still gets the truthful one-row
 * answer without having to know to ask — the same fail-closed default as [fileBrowserSelectionActions]'
 * Move, whose destination screen genuinely is still unbuilt.
 *
 * A single-row chooser is worth skipping entirely at the call site — see [fileBrowserExtractNeedsChooser].
 */
internal fun fileBrowserExtractOptions(
    options: ExtractDestinationOptions,
    folderPickerAvailable: Boolean = false,
): List<FileBrowserExtractOption> = buildList {
    add(
        FileBrowserExtractOption(
            kind = FileBrowserExtractOptionKind.HERE,
            labelRes = R.string.files_extract_row_here,
            labelArg = null,
            detail = File(options.here).name,
            destination = options.here,
        ),
    )
    val suggestion = options.suggestedPlatformFolder
    if (suggestion != null) {
        // The platform's display code when this tree can strike one, the folder's own name otherwise --
        // never the raw platform id. A recognised-but-uncodeable id makes fileBrowserPlatformCode answer
        // null (see its KDoc), and "Extract in gba_unknown_7" is worse than "Extract in GBA Roms".
        val named = fileBrowserPlatformCode(options.platformId) ?: File(suggestion).name
        add(
            FileBrowserExtractOption(
                kind = FileBrowserExtractOptionKind.PLATFORM_FOLDER,
                labelRes = R.string.files_extract_row_in,
                labelArg = named,
                detail = File(suggestion).name,
                destination = suggestion,
            ),
        )
    }
    if (folderPickerAvailable) {
        add(
            FileBrowserExtractOption(
                kind = FileBrowserExtractOptionKind.CHOOSE_FOLDER,
                labelRes = R.string.files_extract_row_choose,
                labelArg = null,
                detail = "",
                destination = null,
            ),
        )
    }
}

/**
 * Whether the Extract press is worth a chooser at all.
 *
 * One option is not a choice. When the only row is "Extract here", a surface should perform it directly
 * — which is both what the player already gets today and one fewer press for the common case of an
 * archive Mimir cannot place.
 */
internal fun fileBrowserExtractNeedsChooser(rows: List<FileBrowserExtractOption>): Boolean = rows.size > 1

/**
 * Focus identity for the chooser's rows. Its own [FocusScope], for the reason stated on
 * [FileBrowserQueueFocus.Scope]: `DefaultFocusAuthority.entries` is keyed by [FocusKey] globally, so a
 * key shared with the folio behind the chooser would evict that tile from the graph.
 */
internal object FileBrowserExtractFocus {

    val Scope: FocusScope = FocusScope("files/extract")

    fun optionKey(kind: FileBrowserExtractOptionKind): FocusKey = FocusKey("$OPTION_PREFIX${kind.name}")

    /** Which option [key] presses, or null when it is not a chooser row. */
    fun optionOf(key: FocusKey?): FileBrowserExtractOptionKind? {
        val name = key?.value?.takeIf { it.startsWith(OPTION_PREFIX) }?.removePrefix(OPTION_PREFIX)
            ?: return null
        return FileBrowserExtractOptionKind.entries.firstOrNull { it.name == name }
    }

    private const val OPTION_PREFIX = "files/extract/"
}

/** Every focusable key the chooser holds, in render order. */
internal fun fileBrowserExtractKeys(rows: List<FileBrowserExtractOption>): List<FocusKey> =
    rows.map { FileBrowserExtractFocus.optionKey(it.kind) }

/**
 * The open chooser, as one value — **and the archive is carried here, never re-read from the focus ring.**
 *
 * D-2's own requirement, and it is the same class of defect the inspector's checksum row already had a
 * cross-file guard for: the chooser's rows are about ONE archive, resolved at the moment `SECONDARY` was
 * pressed. Reading [FileBrowserController.focusedTile] when a row is activated would extract whatever the
 * browse ring happens to be on — which is not even the archive in the general case, because the chooser's
 * own scope is active while it is open and the browse focus underneath it is free to have been changed by
 * anything that publishes into the browse scope in the meantime.
 *
 * @param rows the rows as resolved when the chooser opened. Snapshotted rather than re-derived on every
 *   read: [FileBrowserController] re-opens this exact chooser when a destination pick is abandoned, and a
 *   re-resolution there could legitimately answer differently (the library's ROM folders are a snapshot
 *   `app/` refreshes off the main thread) — so the player would press B and get a *different* pair of
 *   options than the one they were just looking at.
 */
internal data class FileBrowserExtractChoiceState(
    val archivePath: String,
    val rows: List<FileBrowserExtractOption>,
)

/**
 * A destination pick in progress — [FileBrowserExtractOptionKind.CHOOSE_FOLDER] was activated and the
 * browser is now being used to answer *"extract where?"*.
 *
 * **This is not a new screen.** The picker F-06 needs is the folder picker this browser already is; the
 * only thing that changes while this state is non-null is what the pinned commit affordance *means*
 * ([FileBrowserCommitPurpose]) and what `CANCEL` unwinds. Everything else — the folio, the D-pad, the
 * inspector, paging — is untouched, which is the whole reason the reuse pays: Move and Copy need exactly
 * this and will need no second browser either.
 *
 * @param archivePath the archive being extracted. The pick's own subject, for the reason
 *   [FileBrowserExtractChoiceState] states.
 * @param originPath the directory the pick began in. Restored when the pick is abandoned, so `CANCEL`
 *   cannot leave the player parked three folders deep in a place they only went to look at.
 * @param originStack the browse back-stack as it was then, snapshotted whole. [originPath] alone is not
 *   enough: `Up` walks [FileBrowserController]'s own stack, and restoring the path without the stack
 *   would leave the player somewhere whose parent chain is a different directory's.
 * @param rows the chooser's rows, carried so abandoning the pick restores the *same* options — see
 *   [FileBrowserExtractChoiceState.rows].
 */
internal data class FileBrowserDestinationPick(
    val archivePath: String,
    val originPath: String,
    val originStack: List<String>,
    val rows: List<FileBrowserExtractOption>,
)

/**
 * How much of a destination pick the player has left to unwind — what `CANCEL` means right now.
 *
 * **Ordered cancellation, with the alternative named.** `CANCEL` inside a destination
 * pick could have meant one of two things and this tree picks *both, in order*:
 *
 *  - [AWAY] — the player has navigated somewhere of their own since the pick began, so `CANCEL` is still
 *    plain `Up`. The rejected alternative was "CANCEL always abandons the pick": it is simpler, but it
 *    takes `Up` away for the entire duration of the pick, so a player who descends into the wrong folder
 *    has no way back out but to abandon and start again — while ordinary navigation must remain available during a pick.
 *  - [SETTLED] — nothing of the player's own navigation is left (they are standing where the pick began,
 *    or at the volume root with no parent at all), so the next `CANCEL` unwinds the *pick*, back to the
 *    chooser it came from. Not back to the browse listing: one press, one step, and the chooser is where
 *    a player who mis-pressed "Extract in…" actually wants to be — "Extract here" is the row next to it.
 *
 * **`CANCEL` therefore never closes the browser while a pick is open**, which is the one thing this
 * ordering deliberately costs: at the volume root it takes three presses (abandon the pick, dismiss the
 * chooser, then close) instead of one. That is the right direction to fail — losing a browse session by
 * reflex-pressing B is not recoverable, and the extra two presses are.
 */
internal enum class FileBrowserDestinationPickPhase {
    /** Somewhere the player navigated to since the pick began. `CANCEL` goes up one directory. */
    AWAY,

    /** The volume root, where every pick starts. `CANCEL` returns to the chooser. */
    SETTLED,
}
