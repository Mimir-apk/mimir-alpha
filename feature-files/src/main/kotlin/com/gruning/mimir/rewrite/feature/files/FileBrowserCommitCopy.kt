package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources

// The commit row's words (moved here from FileBrowserSurface.kt in localization step 4, which is over its size budget). See the F-06
// section of that file for what a destination pick changes about the screen.

/**
 * The pinned commit affordance's own words.
 *
 * @param eyebrow the word stamped into the plate's metal (stored in sentence case).
 * @param label the sentence the player reads.
 */
internal data class FileBrowserCommitCopy(val eyebrow: String, val label: String)

/**
 * What the commit row says — **F-06 item 3, and the reason [FileBrowserCommitPurpose] is an enum on the
 * controller rather than a string.**
 *
 * One row does two jobs. Until this existed it said *"Use this folder"* for both, so a player who chose
 * "Extract in…" and walked to a destination was answering **"extract where?"** in front of a plate asking
 * whether to add this folder as a ROM source — a different question, whose real answer (in
 * [FileBrowserMode.PICK]) would have closed the browser and reported a source they never asked for. The
 * controller's `commitPurposeForCurrentMode` already refuses that (`destinationPick` outranks PICK's own
 * commit); this is the half the player can see.
 *
 * **[FileBrowserCommitPurpose.CHOOSE_SOURCE] and `null` render byte-identically to the row already observed
 * on hardware.** Not merely equivalent — the same two strings. That is this file's standing
 * discipline for an additive change (`mimirNorseBookPlateSafeTopDp`, `FileBrowserChecksumTone.IDLE`'s
 * colour), and `null` is unreachable at the only call site anyway: the row is rendered behind
 * `commitAvailable`, which is exactly `commitPurpose != null` derived from the same single predicate.
 *
 * **"Extract into this folder", deliberately not "Extract here".** The chooser one press away has a row
 * whose label IS *"Extract here"* and it means a **sibling of the archive** — reusing those exact words
 * for the directory the player has since navigated to would be the worst available wording, because the
 * player just read them meaning something else. The footer legend's own "A Extract here" for this row
 * carries the same referent shift and `fileBrowserFooterHints`' KDoc already argues it ("HERE means into
 * the folder I am looking at"); this label spells out the referent instead of relying on the argument.
 *
 * **G2 adds `MOVE_DESTINATION` / `COPY_DESTINATION` on the identical referent-shift argument, twice.**
 * `Move into…` and `Copy into…` are the chooser rows one press away, each naming a destination the player
 * has not chosen yet; `"Move into this folder"` / `"Copy into this folder"` name the one they have since
 * walked to, the same distinction `EXTRACT_DESTINATION`'s label already draws. No string in either arm
 * may imply the press is FREE. A completed move can now be walked back
 * (`FileQueueState.ReverseAvailable`, 2026-08-16) and a copy still cannot -- but the walk-back is a new
 * transfer that can collide or fail, and it does not put a departed game back on the shelf. See
 * `FileBrowserTransferChoice.kt`'s own KDoc for what a string here may and may not claim.
 */
internal fun fileBrowserCommitCopy(
    res: Resources,
    purpose: FileBrowserCommitPurpose?,
    imagePickHiddenCount: Int = 0,
    imagePickShowsAll: Boolean = false,
): FileBrowserCommitCopy =
    when (purpose) {
        FileBrowserCommitPurpose.EXTRACT_DESTINATION ->
            FileBrowserCommitCopy(res.getString(R.string.files_commit_extract_eyebrow), res.getString(R.string.files_commit_extract_label))
        FileBrowserCommitPurpose.MOVE_DESTINATION ->
            FileBrowserCommitCopy(res.getString(R.string.files_commit_move_eyebrow), res.getString(R.string.files_commit_move_label))
        FileBrowserCommitPurpose.COPY_DESTINATION ->
            FileBrowserCommitCopy(res.getString(R.string.files_commit_copy_eyebrow), res.getString(R.string.files_commit_copy_label))
        // Settings › Music (2026-09-30): the design's own Move-dialog word ("Use"), because nothing is added
        // to a library here -- the folder is simply the one Mimir will read music from.
        FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER ->
            FileBrowserCommitCopy(res.getString(R.string.files_commit_use_eyebrow), res.getString(R.string.files_commit_use_label))
        // The image picker's switch . The label says what is true now: how many files
        // are being left out, or that every file is showing and A goes back to images only.
        FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER ->
            FileBrowserCommitCopy(
                eyebrow = res.getString(FileBrowserImagePickToggleEyebrow),
                label = if (imagePickShowsAll) res.getString(FileBrowserImagesOnlyLabel) else fileBrowserAllFilesLabel(res, imagePickHiddenCount),
            )
        FileBrowserCommitPurpose.CHOOSE_SOURCE, null ->
            FileBrowserCommitCopy(res.getString(R.string.files_commit_add_eyebrow), res.getString(R.string.files_commit_add_label))
    }
