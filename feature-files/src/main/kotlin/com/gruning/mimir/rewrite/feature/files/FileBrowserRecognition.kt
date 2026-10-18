package com.gruning.mimir.rewrite.feature.files

/**
 * Everything the host can tell the browser about one listing row, in place of the bare platform id the
 * classifier used to return.
 *
 * No file may be drawn as if it were
 * switched off, so every kind of file needs a card of its own, and the cards can only be as honest as what
 * the classifier is able to say. A bare `String?` said "this is a platform" or "I do not know", and threw
 * away the third answer the resolver actually has: *this looks like a game, but I cannot say whose*
 * (`.iso`, `.bin`, a folder name that disagrees with the extension). That row is still a game, and a card
 * that says GAME is more truthful than one that says nothing.
 *
 * Public because [FileBrowserController]'s constructor takes it as the classifier's return type and the app
 * module supplies the classifier; the type carries no behaviour.
 *
 * @param platformIdValue the recognised platform in the shelfmark vocabulary, or null. A directory carries
 *   this when its own name claims a platform.
 * @param gameLike the row is game content whose platform could not be settled (the extension is claimed by
 *   several platforms, the folder name contradicts it, or it needs folder evidence that is missing). Never
 *   true for a directory, and only read when [platformIdValue] is null.
 * @param bios the row is an emulator BIOS payload, by the resolver's own name and folder rules. A BIOS file
 *   is load-bearing for launching, so it wins over a platform it may also match.
 */
data class FileBrowserRecognition(
    val platformIdValue: String? = null,
    val gameLike: Boolean = false,
    val bios: Boolean = false,
) {
    companion object {
        /** The classifier knows nothing about this row. */
        val None: FileBrowserRecognition = FileBrowserRecognition()
    }
}
