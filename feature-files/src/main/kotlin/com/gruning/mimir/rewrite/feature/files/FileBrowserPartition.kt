package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.LocalListingPolicy

/**
 * A directory's listing, split into the two blocks the new folio renders separately (adoption spec §4:
 * *"BrowserListing's directories-first rule expressed as layout rather than sort"*) -- tomes above,
 * files below, each sorted independently rather than interleaved by a single directories-first
 * comparator. [tomes] holds every [FileBrowserTileKind.DIRECTORY] tile (machinery included, demoted not
 * excluded); [files] holds ARCHIVE and FILE tiles.
 */
internal data class FileBrowserPartition(
    val tomes: List<FileBrowserTile>,
    val files: List<FileBrowserTile>,
) {
    val isEmpty: Boolean get() = tomes.isEmpty() && files.isEmpty()
    val totalCount: Int get() = tomes.size + files.size

    /** Render/tab order: every tome, then every file. The order [FileBrowserFocus] keys off of. */
    fun orderedTiles(): List<FileBrowserTile> = tomes + files
}

/**
 * Classifies a raw directory listing into a [FileBrowserPartition].
 *
 * @param classifyPlatform recognises a [FileItem]: a platform, a game whose platform is unsettled, a BIOS
 *   payload, or nothing ([FileBrowserRecognition.None]). Defaults are supplied by [FileBrowserController],
 *   not here -- this function always requires one explicitly so a test can never forget to reason about
 *   it. Machinery folders are never classified (Mimir's own folders win over recognition, adoption spec §4)
 *   even if a caller's classifier would otherwise match one by name collision (e.g. a real platform folder
 *   a player happened to name "saves").
 */
internal fun fileBrowserPartition(
    items: List<FileItem>,
    classifyPlatform: (FileItem) -> FileBrowserRecognition,
): FileBrowserPartition {
    val tomes = mutableListOf<FileBrowserTile>()
    val files = mutableListOf<FileBrowserTile>()
    for (item in items) {
        val machinery = fileBrowserIsMachineryFolder(item)
        val kind = when {
            item.isDirectory -> FileBrowserTileKind.DIRECTORY
            LocalListingPolicy.isExtractableArchive(item) -> FileBrowserTileKind.ARCHIVE
            else -> FileBrowserTileKind.FILE
        }
        val recognition = if (machinery) FileBrowserRecognition.None else classifyPlatform(item)
        val tile = FileBrowserTile(
            item = item,
            kind = kind,
            machinery = machinery,
            platformIdValue = recognition.platformIdValue,
            gameLike = recognition.gameLike,
            bios = recognition.bios,
        )
        if (kind == FileBrowserTileKind.DIRECTORY) tomes.add(tile) else files.add(tile)
    }
    val byNameCaseInsensitive = compareBy<FileBrowserTile> { it.name.lowercase() }
    return FileBrowserPartition(
        tomes = tomes.sortedWith(byNameCaseInsensitive),
        files = files.sortedWith(byNameCaseInsensitive),
    )
}
