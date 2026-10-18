package com.gruning.mimir.rewrite.core.files

/**
 * MIMIR ADDITION — Extract offers both Extract here and Extract in a selected folder, with
 * an appropriate platform folder as the optional default when the ROM is recognized.
 *
 * **A wrong platform suggestion is worse than no suggestion.** This tree's own precedent
 * agrees — `MimirPlatformShelfmark` draws no plate rather than a meaningless one
 * (`MimirPlatformShelfmark.kt:87-89`), and `FileBrowserPlatformClassifier` is already fail-closed for
 * exactly this reason. So the default here is **absent by default** and appears only when the answer is
 * unambiguous.
 *
 * This object decides; it does not look anything up. Platform recognition lives in `core-resolver` and
 * the library's real ROM folders live in Room — neither is reachable from this module, and duplicating
 * either would drift. `app/` supplies both as plain values ([suggestedPlatformFolder]).
 */
object ExtractDestinationPolicy {

    /**
     * Where "Extract here" puts things: a fresh sibling directory named after the archive.
     *
     * `.../Roms/bundle.zip` → `.../Roms/bundle/`. Unchanged behaviour, lifted out of
     * `FileBrowserStore.extract` so option 1 and option 2 are decided by the same function rather than
     * by one inline expression and one new one.
     */
    fun extractHereDestination(archivePath: String): String {
        val separator = archivePath.lastIndexOf('/')
        val parent = if (separator > 0) archivePath.substring(0, separator) else ""
        val name = if (separator >= 0) archivePath.substring(separator + 1) else archivePath
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$parent/$base"
    }

    /**
     * The two extraction options, with the platform default resolved — or honestly absent.
     *
     * @param archivePath the archive being extracted.
     * @param platformId what [FileItem] recognition made of it, or null. A ZIP is an ARCHIVE, not a
     *   GAME, so this is the platform of the archive's *own name* — an `.iso`/`.chd` inside is not
     *   inspected, because reading the archive to guess would be both slow and a different claim than
     *   the one the tile makes.
     * @param platformFolders every folder the library actually sources that platform from, as absolute
     *   paths. Supplied by `app/` from the real library — see [suggestedPlatformFolder] for the three
     *   ways this still declines to answer.
     * @param volumeRootOf which volume a path is on, so a suggestion on another drive is refused. `app/`
     *   supplies this from `StorageVolumeListing`; the default treats everything as one volume, which is
     *   correct only for a caller that has already scoped its own input.
     */
    fun options(
        archivePath: String,
        platformId: String?,
        platformFolders: List<String>,
        volumeRootOf: (String) -> String? = { null },
    ): ExtractDestinationOptions = ExtractDestinationOptions(
        here = extractHereDestination(archivePath),
        suggestedPlatformFolder = suggestedPlatformFolder(
            archivePath = archivePath,
            platformId = platformId,
            platformFolders = platformFolders,
            volumeRootOf = volumeRootOf,
        ),
        platformId = platformId,
    )

    /**
     * The platform folder to offer as "Extract in…"'s default, or **null**.
     *
     * Null — no default, plain folder picker — in every one of these cases, each of which is a way the
     * suggestion could be wrong:
     *
     *  1. **The archive is not recognised** (`platformId == null`). The fail-closed classifier already
     *     refuses to name a platform it cannot prove; this must not be braver than the classifier.
     *  2. **The library has no folder for that platform.** A player who has never added GBA games has no
     *     "the GBA folder"; inventing `<volume>/Roms/GBA` would be fabricating a location.
     *  3. **The library has more than one folder for that platform on this volume.** Two candidates
     *     means the answer is a guess, and a guess is unsafe. The player picks.
     *  4. **The only candidate is on a different drive from the archive.** Extracting a 4 GB archive
     *     across volumes is a copy, not a move, and silently defaulting to it would be a surprise the
     *     player pays for in time and space.
     *
     * Case 3's filtering happens BEFORE the ambiguity check, deliberately: three folders of which
     * exactly one is on this volume is an unambiguous answer, not an ambiguous one.
     */
    fun suggestedPlatformFolder(
        archivePath: String,
        platformId: String?,
        platformFolders: List<String>,
        volumeRootOf: (String) -> String? = { null },
    ): String? {
        if (platformId.isNullOrBlank()) return null
        val archiveVolume = volumeRootOf(archivePath)
        val candidates = platformFolders
            .filter { it.isNotBlank() }
            .distinct()
            .filter { archiveVolume == null || volumeRootOf(it) == archiveVolume }
        return candidates.singleOrNull()
    }
}

/**
 * What the Extract action offers, as one value the surface renders and the tests read.
 *
 * @param here option 1, always present — `.../bundle.zip` → `.../bundle/`.
 * @param suggestedPlatformFolder option 2's pre-selected folder, or null when Mimir cannot honestly
 *   name one. **Null does not remove option 2** — "Extract in…" still opens the folder picker; it only
 *   removes the default, which is the intended fallback.
 * @param platformId what was recognised, carried so a surface can say *which* platform it is offering
 *   ("Extract in Game Boy Advance") rather than presenting an unexplained path.
 */
data class ExtractDestinationOptions(
    val here: String,
    val suggestedPlatformFolder: String?,
    val platformId: String?,
) {
    /** Whether the smart default exists. False means: offer the picker with nothing pre-selected. */
    val hasPlatformSuggestion: Boolean get() = suggestedPlatformFolder != null
}
