package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import androidx.annotation.StringRes
import com.gruning.mimir.rewrite.core.files.FileItem

/**
 * **[FileBrowserMode.PICK_IMAGE]'s one gate** keeps image browsing in Mimir: an in-app picker insulates
 * the Customize-media Upload from vendor picker behaviour (and from the fan surge every hop into
 * documentsui costs, the same cost that moved add-games in-app on 2026-08-13).
 *
 * The extensions a Customize-media upload can take, lower-case, one set. **Deliberately this module's
 * own list rather than `LocalListingPolicy.MEDIA_ART_EXTENSIONS`:** that set exists to DIM sidecars in a
 * ROM folder and carries `mp4`/`mp3`, which no still-image decoder can open; reusing it would advertise
 * A on a video and then fail the import. `gif` and `bmp` are here because `BitmapFactory` decodes both
 * (first frame for a GIF); `heif`/`heic` are not, because `CustomImageImportStore`'s bounded decoder
 * accepts them only where the platform codec exists, and a tile that is choosable on one handheld and
 * inert on another would be the advertised-but-inert shape this module refuses everywhere else.
 */
internal val FileBrowserImageExtensions: Set<String> = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/**
 * Whether a FILE named [name] is choosable in [mode] **as an image** -- the predicate the dispatch
 * table's CONFIRM arm (`imagePickAvailable`), the footer legend and `FileTile`'s dimming all read, so
 * the press, the advertised verb and the drawn state trace to one rule.
 *
 * Tests the mode FIRST, so it is `false` for every [FileBrowserMode.PICK] and [FileBrowserMode.MANAGE]
 * caller regardless of the name: R-21's single-game commit in PICK has its own gate
 * (`sourcePickAvailable`) and this one must not widen it. Case-insensitive on the extension because
 * the player's card is not (`Cover.PNG` is a PNG); a name with no dot has no extension and is refused
 * rather than guessed.
 */
internal fun fileBrowserFileChoosable(mode: FileBrowserMode, name: String): Boolean =
    mode == FileBrowserMode.PICK_IMAGE &&
        name.substringAfterLast('.', "").lowercase() in FileBrowserImageExtensions

/** The header title every mode but [FileBrowserMode.PICK_IMAGE] has always drawn. */
@StringRes
internal val FileBrowserFilesHeader: Int = R.string.files_header_files

/**
 * [FileBrowserMode.PICK_IMAGE]'s header title -- the app's own overlay-header idiom (`ᛗ · QUICK LAUNCH`,
 * `ᛗ · CUSTOMIZE MEDIA`, `ᛗ · CHOOSE A THEME`), so the screen the Upload button opens reads as the
 * picker's own next step rather than as the general file manager.
 */
@StringRes
internal val FileBrowserPickImageHeader: Int = R.string.files_header_pick_image

/** [FileBrowserMode.PICK_MUSIC_FOLDER]'s header, the same idiom: Settings › Music's own next step. */
@StringRes
internal val FileBrowserPickMusicFolderHeader: Int = R.string.files_header_pick_music_folder

/**
 * Which title `FileBrowserHeader` draws for [mode]. Exhaustive with no `else`, so a fifth mode cannot
 * inherit a title nobody chose for it.
 */
internal fun fileBrowserHeaderTitle(res: Resources, mode: FileBrowserMode): String = res.getString(
    when (mode) {
        FileBrowserMode.PICK, FileBrowserMode.MANAGE -> FileBrowserFilesHeader
        FileBrowserMode.PICK_IMAGE -> FileBrowserPickImageHeader
        FileBrowserMode.PICK_MUSIC_FOLDER -> FileBrowserPickMusicFolderHeader
    },
)

/**
 * What a screen reader is told about a file tile [FileBrowserMode.PICK_IMAGE] has dimmed: the reason it
 * is inert, not merely that it is. Attached (with `disabled()`) only to tiles [fileBrowserFileChoosable]
 * refuses, so every other mode's tile keeps its exact semantics node.
 */
@StringRes
internal val FileBrowserNotAnImageDescription: Int = R.string.files_pick_not_an_image

// ── the image picker's two views ─────────

/**
 * The image picker used to draw every file it could not take at 55% opacity, so most of a folder looked
 * broken. It now shows **images and folders only** by default; a row pinned above the listing says how many
 * files are being left out and flips to **All files** on A. In the All-files view the other files keep their
 * full card look, are marked `disabled()` with [FileBrowserNotAnImageDescription], and the footer does not
 * advertise A on them -- a card that looks choosable but refuses A is exactly what this file's first KDoc
 * rules out, and a toast was rejected for the same reason.
 *
 * @property partition what the picker lists: [full] itself, or [full] without the files it cannot take.
 * @property hiddenCount how many files in [full] are not choosable images. Counted in BOTH views: it is
 *   the label of the pinned row when the view is images-only, and the reason the row exists at all.
 */
internal data class FileBrowserImagePickListing(
    val partition: FileBrowserPartition,
    val hiddenCount: Int,
)

/**
 * The view of [full] the image picker should draw. Every other mode returns [full] untouched with a count of
 * zero, so PICK and MANAGE are byte-identical to before. Folders are never filtered: opening one is how an
 * image is reached.
 */
internal fun fileBrowserImagePickListing(
    mode: FileBrowserMode,
    showAll: Boolean,
    full: FileBrowserPartition,
): FileBrowserImagePickListing {
    if (mode != FileBrowserMode.PICK_IMAGE) return FileBrowserImagePickListing(full, 0)
    val choosable = full.files.filter { fileBrowserFileChoosable(mode, it.name) }
    val hidden = full.files.size - choosable.size
    return FileBrowserImagePickListing(
        partition = if (showAll || hidden == 0) full else full.copy(files = choosable),
        hiddenCount = hidden,
    )
}

/**
 * Search results as the image picker should show them: the same filter as the listing, applied before the
 * results are published so the keys the search screen registers and the tiles it draws cannot disagree.
 * Directories survive (a folder called `Covers` is a legitimate hit); every other mode is untouched.
 */
internal fun fileBrowserImagePickSearchResults(
    mode: FileBrowserMode,
    showAll: Boolean,
    results: List<FileItem>,
): List<FileItem> =
    if (mode != FileBrowserMode.PICK_IMAGE || showAll) {
        results
    } else {
        results.filter { it.isDirectory || fileBrowserFileChoosable(mode, it.name) }
    }

/** The inspector's reason line for [tile], or null when it needs none (a folder, an image, any other mode). */
internal fun fileBrowserImagePickNote(res: Resources, mode: FileBrowserMode, tile: FileBrowserTile?): String? =
    tile?.takeIf {
        mode == FileBrowserMode.PICK_IMAGE &&
            it.kind != FileBrowserTileKind.DIRECTORY &&
            !fileBrowserFileChoosable(mode, it.name)
    }?.let { res.getString(FileBrowserNotAnImageDescription) }

/** The pinned row's eyebrow in the image picker. Copy approved 2026-10-08. */
@StringRes
internal val FileBrowserImagePickToggleEyebrow: Int = R.string.files_pick_toggle_eyebrow

/** The pinned row's label while the picker lists images only: what is being left out. */
internal fun fileBrowserAllFilesLabel(res: Resources, hiddenCount: Int): String =
    res.getQuantityString(R.plurals.files_pick_all_files, hiddenCount, hiddenCount)

/** The pinned row's label while every file is listed: the way back to images only. */
@StringRes
internal val FileBrowserImagesOnlyLabel: Int = R.string.files_pick_images_only

/** What an empty listing says. */
@StringRes
internal val FileBrowserEmptyFolderNote: Int = R.string.files_browse_empty_folder

/** What an empty images-only listing says when files exist but none is an image: not "empty", which would be false. */
@StringRes
internal val FileBrowserNoImagesNote: Int = R.string.files_pick_no_images

/**
 * The note under the listing when nothing is listed. [hiddenCount] is [FileBrowserController.imagePickHiddenCount]:
 * an empty listing with files the picker is leaving out can only be the images-only view of a folder that holds
 * no images, and calling that folder "empty" directly under a row that says "All files · N hidden" would be a lie.
 */
internal fun fileBrowserEmptyNote(res: Resources, hiddenCount: Int): String =
    res.getString(if (hiddenCount > 0) FileBrowserNoImagesNote else FileBrowserEmptyFolderNote)
