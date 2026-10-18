package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperationStaging
import com.gruning.mimir.rewrite.core.files.LocalListingPolicy
import com.gruning.mimir.rewrite.core.ui.MimirShelfmarkTier

/**
 * One tile as the new browse folio sees it .
 * Replaces slice 5-6's flat-list [FileBrowserRow] and its pinned `ACTION` kind: the design itself has
 * no "choose this folder" affordance anywhere in the Browse screen, so every [FileBrowserTile] still
 * has a backing [FileItem] -- no `COMMIT` kind was added here. [FileBrowserMode.PICK]'s pinned commit
 * affordance (restored 2026-08-14 -- see [FileBrowserController]'s KDoc) is deliberately NOT a
 * [FileBrowserTile] for exactly that reason: it has no backing file, so it is modelled as its own tiny
 * type outside this enum rather than forcing a fake [FileItem] or a nullable [item] onto every reader
 * of this class. See [FileBrowserFocus.commitKey] / [FileBrowserFocusTracker] for how it still
 * participates in the same focus graph as a real tile.
 */
internal enum class FileBrowserTileKind { DIRECTORY, ARCHIVE, FILE }

/**
 * The design's tome proportion, width : height = 1 : 1.14 (`Mimir Files.dc.html:588`), passed to
 * `MimirNorseBookCover`'s additive `aspectRatio`.
 *
 * Load-bearing rather than decorative: at the design's 833x468dp viewport a 3:4 tome leaves only about
 * 12dp of vertical slack in this folio, against 58dp here. The child-count meta line adds roughly 17dp
 * per row, so on 3:4 the grid would collapse from eight tomes to four the moment counts appeared. The
 * shorter face is what pays for the count.
 */
internal const val FileBrowserTomeAspectRatio: Float = 1f / 1.14f

/**
 * How loudly the platform mark is struck on each tile kind. **No paint-time scale — the component's own
 * tier ladder, at its own size.**
 *
 * **Platform labels remained oversized after scales changed from 1.8x/1.6x to 34/20 and 30/20.**
 *
 * **What was actually measured this pass, and why the design's `34` never applied to this object.** The
 * design's number is the side of a SQUARE MASKED ICON: `glyph(g, size)` returns
 * `{ width: size, height: size, maskImage: url(assets/icons/platforms/<g>.svg) }`
 * (`Mimir Files.dc.html:559-562`), called as `this.glyph(e.g, tome ? 34 : 30)` at line 661. It carries no
 * text, no plate, no rim, no sheen — it is a silhouette 34px on a side.
 *
 * What this tree draws in that slot is [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark], and it
 * is a different object on purpose (trademark clearance — that component draws no vendor geometry at all):
 * a struck plate with a TEXT call number, whose width is `max(ink + 2 x 6dp padding, height x 1.4)`. So
 * matching the icon's HEIGHT dragged everything else with it. On the folio's own 118 x 134.5 dp tome
 * (`FileBrowserFocusRegistration.kt:108-122` derives the 118):
 *
 * ```
 *                       PICKER tier      x 34/20 scale (the previous oversized state)
 *  plate height              20 dp            34 dp       = 25% of the 134.5dp cover
 *  ink                       11 sp            18.7 sp     the tome's own TITLE is 11sp (see below)
 *  painted width "GBA"     ~ 35 dp          ~ 60 dp       = 51% of the 118dp cover
 *  painted width "SNES"    ~ 43 dp          ~ 72 dp       = 61% of the 118dp cover
 *  the design's icon                          34 dp square = 29% of the cover's width
 * ```
 *
 * **The ink is the defect.** `MimirNorseBookCover` sizes its title `(width x 0.085).coerceIn(11f, 18f)`,
 * which at 118dp clamps to **11sp**, and its eyebrow to 7sp. A 1.7x scale put the platform code at
 * ~18.7sp — **70% larger than the name of the folder it is captioning, and the largest type on the
 * tile.** That is what "reads too big" describes, and it is why a 6% trim changed nothing: height was
 * never the binding dimension. The plate's own component KDoc says what it is for — *"the shelfmark
 * captions a GAME COVER at ~18dp"* — and the folio had it at 34.
 *
 * **The fix is to stop scaling and let the component be its own size**, which lands the plate at ~30% of
 * the cover width against the design icon's 29% — the dimension the two objects actually share — and
 * returns the ink to 11sp, level with the title instead of over it.
 *
 * **The tome/file distinction survives, inside the component's own vocabulary rather than a scale.** The
 * design makes the tome mark louder than the file mark by 34:30 = 1.133; the shelfmark's own tier ladder
 * makes PICKER louder than FOCUSED by 20:18 = 1.111 (heights) and 11:10 (ink). Within 2% of the design's
 * relationship, expressed in the units the component already defines, with no override anywhere.
 *
 * **The perceived size needs device review.** Going to the component's native size is the defensible move after
 * two "too big" reports; if the mark now reads too QUIET on a 118dp tome, the correction is one tier, not
 * a new scale.
 */
internal val FileBrowserTomeGlyphTier: MimirShelfmarkTier = MimirShelfmarkTier.PICKER
internal val FileBrowserFileGlyphTier: MimirShelfmarkTier = MimirShelfmarkTier.FOCUSED

/**
 * @param machinery policy (adoption spec §4): `saves`, `media`, `.mimir` are Mimir's own folders -- no
 *   glyph, labelled "Mimir" on the cover, NEVER hidden and never dimmed. Directories only; a plain file is never machinery.
 * @param platformIdValue the recognised platform, or null when unrecognised. Always null when
 *   [machinery] is true (demotion wins over recognition -- see [fileBrowserPartition]) and always null
 *   under the default `classifyPlatform = { null }` this slice ships with (adoption spec §9: "platform
 *   recognition is unthreaded ... no classifier is wired today"). The parameter exists so the whole
 *   glyph/chip/Launches pipeline is provably wired once a real classifier lands, exercised here by
 *   tests that inject a fake one.
 * @param gameLike the classifier could not settle a platform but the row is game content all the same (see
 *   [FileBrowserRecognition.gameLike]). Only meaningful while [platformIdValue] is null.
 * @param bios the row is an emulator BIOS payload ([FileBrowserRecognition.bios]).
 */
internal data class FileBrowserTile(
    val item: FileItem,
    val kind: FileBrowserTileKind,
    val machinery: Boolean,
    val platformIdValue: String?,
    val gameLike: Boolean = false,
    val bios: Boolean = false,
) {
    /**
     * A row the player deleted that is still held for Undo ([FileItem.isPendingDelete]): drawn where it was with
     * "Deleting…" on its cover, and inert -- see [FileBrowserController.dispatch].
     */
    val deleting: Boolean get() = item.isPendingDelete

    val name: String get() = item.name
    val path: String get() = item.path

    /** Whether the SECONDARY gesture / "Extract here" button applies to this tile. */
    val extractable: Boolean get() = kind == FileBrowserTileKind.ARCHIVE

    /**
     * Whether this FILE is a sidecar rather than content -- `gamelist.xml`, `Pokemon Emerald.srm`,
     * `notes.txt`, and (via the media-art vocabulary) box art and theme audio.
     *
     * **This used to decide a 0.55 dim  and no longer paints anything.** Full presence replaced dimming throughout Files on 2026-10-08;
     * the requirement (a sidecar must not look like a game) survives as [fileBrowserFileKind], which
     * gives a sidecar a card of its own kind instead of a faded copy of a game's. The fact itself stays
     * because the type mapping and the inspector still build on it.
     *
     * Derived, not stored, so a construction site cannot forget it or disagree with [fileBrowserPartition]:
     *  - **[FileBrowserTileKind.FILE] only.** An ARCHIVE is the one ember-marked thing in its column (adoption
     *    spec §4) and a DIRECTORY is never a sidecar.
     *  - **Recognition wins.** A tile the classifier recognised is game content, mirroring
     *    `BrowserListing.row`'s own ordering (`platform != null -> GAME` decided before the support branch).
     *  - **The vocabulary is [com.gruning.mimir.rewrite.core.files.LocalListingPolicy.isSupportFile]**,
     *    `core-storage`'s own shipped `SUPPORT_EXTENSIONS` plus its dotfile rule.
     */
    val support: Boolean
        get() = kind == FileBrowserTileKind.FILE &&
            platformIdValue == null &&
            LocalListingPolicy.isSupportFile(item)

    /**
     * Box art, video snaps and theme audio are not "miscellaneous" — Mimir knows exactly what they are.
     * They are a REFINEMENT of the sidecar set: [support] still returns true for them
     * (`LocalListingPolicy.classifyContent` maps both `SUPPORT` and `MEDIA_ART` to `isSupportFile`). All
     * that changes is what the inspector calls them.
     *
     * **Derived, and gated on recognition first, for the same two reasons [support] is.** A construction
     * site cannot forget it, and a file the classifier recognised as game content is game content even
     * if it carries a media extension — mirroring `BrowserListing.row`'s own ordering.
     *
     * **The vocabulary is `core-files`', not this module's.** `LocalListingPolicy.isMediaArtFile` is the
     * one list of extensions; a second copy here is how three drifting copies of one helper happened in
     * this codebase before.
     */
    val mediaArt: Boolean
        get() = kind == FileBrowserTileKind.FILE &&
            platformIdValue == null &&
            LocalListingPolicy.isMediaArtFile(item)
}

/**
 * Policy, adoption spec §4: machinery folders are matched by exact (case-insensitive) name, not
 * substring -- a player's own folder that merely CONTAINS one of these words (e.g. "my saves") must not
 * be swept in. Directories only; matching a plain file named "saves" would be nonsensical (the label is
 * a TOME treatment). They are labelled, never dimmed: see [fileBrowserTomeEyebrow].
 */
internal val FileBrowserMachineryNames: Set<String> = setOf(
    "saves",
    "media",
    ".mimir",
    // E2 alpha-cert, 2026-10-08: `LocalFileRepository` no longer lists this folder at all, so a real
    // listing never reaches this entry. It stays as the backstop for any listing source that does not
    // filter, where the label is still the right fallback.
    //
    // The staging root, added 2026-08-14 with the queue. A DELETE HELD FOR UNDO parks a real directory
    // in the folder the player is looking at, for as long as the hold lasts -- so without this it draws
    // as an ordinary tome and the delete looks like it created a folder instead of removing one. It now
    // says so on its cover instead of fading : [fileBrowserTomeEyebrow] reads
    // "Deleting…" for exactly this name. The player's files really are in there until the hold is
    // released.
    FileOperationStaging.ROOT_DIRECTORY.lowercase(),
)

internal fun fileBrowserIsMachineryFolder(item: FileItem): Boolean =
    item.isDirectory && item.name.lowercase() in FileBrowserMachineryNames

/**
 * The word a tile wears as its badge: the type's name, so no card is ever left without one.
 *
 * every type of file gets its own card and its own word. The old FILE badge was
 * a bare dash ("—") -- a mark that says nothing, on the card that most needed to say something -- and the
 * kind is now read from [fileBrowserFileKind], the one place the type is decided, so the file tile, the
 * search rows and the inspector's tag can never name one file three ways.
 *
 * Spec §4's "one marked thing per column -- the ZIP badge" still holds, by colour only (no filled or tinted chips): an extractable archive
 * ([FileBrowserTileKind.ARCHIVE]) is the only badge whose word and outline are ember, and `7z` vs `zip` is read from
 * the tile's own extension, never guessed (both are extractable under
 * [com.gruning.mimir.rewrite.core.files.LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS], policy
 * 2026-08-13; the design mockup's zip-only gate and "7z extraction is not available yet" note are
 * deliberately NOT ported). An archive Mimir cannot extract (`tar`, `rar`, `gz` ...) names its own format
 * and is drawn outlined like every other badge.
 *
 * A directory returns "DIR"; tomes never draw it (they are covers), but the label is defined for every
 * kind so nothing has to special-case a tome by omission.
 */
internal fun fileBrowserBadgeLabel(res: Resources, tile: FileBrowserTile): String = when (fileBrowserFileKind(tile)) {
    FileBrowserFileKind.PLATFORM_FOLDER, FileBrowserFileKind.FOLDER -> res.getString(R.string.files_kind_dir)
    // A recognised archive wears its platform plate on the card, but anywhere there is no plate (search rows,
    // the inspector's tag) it must still say what it IS to the player who wants to extract it.
    FileBrowserFileKind.GAME ->
        if (tile.kind == FileBrowserTileKind.ARCHIVE) fileBrowserArchiveWord(tile) else res.getString(R.string.files_kind_game)
    FileBrowserFileKind.GAME_UNKNOWN -> res.getString(R.string.files_kind_game)
    FileBrowserFileKind.SAVE -> res.getString(R.string.files_kind_save)
    FileBrowserFileKind.DATA -> res.getString(R.string.files_kind_data)
    FileBrowserFileKind.MUSIC -> res.getString(R.string.files_kind_music)
    FileBrowserFileKind.IMAGE -> res.getString(R.string.files_kind_image)
    FileBrowserFileKind.VIDEO -> res.getString(R.string.files_kind_video)
    // The extension is the file system's own word for the format (a format identifier, left as it is); only a file with none says "Archive".
    FileBrowserFileKind.ARCHIVE ->
        if (tile.kind == FileBrowserTileKind.ARCHIVE) {
            fileBrowserArchiveWord(tile)
        } else {
            tile.item.extension.uppercase().ifBlank { res.getString(R.string.files_kind_archive) }
        }
    FileBrowserFileKind.DOC -> res.getString(R.string.files_kind_doc)
    FileBrowserFileKind.APP -> res.getString(R.string.files_kind_app)
    FileBrowserFileKind.BIOS -> FileBrowserBiosWord
    FileBrowserFileKind.MISC -> res.getString(R.string.files_kind_file)
}

/** ZIP or 7Z, read from the tile's own extension, for an archive Mimir can extract. */
private fun fileBrowserArchiveWord(tile: FileBrowserTile): String =
    if (tile.item.extension.equals("7z", ignoreCase = true)) "7Z" else "ZIP"

/**
 * The line a folder cover wears above its name. Plain folders say "Folder"; Mimir's own working folders
 * say so instead of fading out.
 *
 * A folder held for a pending delete says "Deleting…" ([FileBrowserTile.deleting]): the row the player deleted
 * stays in its folder, under its own name, until Undo is pressed or released, and the cover says what is
 * happening to it. The listing builds that row from the held-delete list (`HeldDeleteListing`); the staging
 * folder itself stays hidden, and the exact-name branch below is only the backstop for a listing source that
 * does not filter it.
 */
internal fun fileBrowserTomeEyebrow(res: Resources, tile: FileBrowserTile): String = when {
    tile.deleting -> res.getString(FileBrowserDeletingLabel)
    !tile.machinery -> res.getString(R.string.files_kind_folder)
    tile.name.equals(FileOperationStaging.ROOT_DIRECTORY, ignoreCase = true) -> res.getString(FileBrowserDeletingLabel)
    else -> FileBrowserMimirFolderLabel
}

/** The cover label on `saves`, `media` and `.mimir`. Copy approved 2026-10-08. It is the product's name, which is not translated. */
internal const val FileBrowserMimirFolderLabel = "Mimir"

/** The cover label on the staging folder while a delete is held for undo (a string resource). */
internal val FileBrowserDeletingLabel: Int = R.string.files_tile_deleting

/**
 * The second line inside a tile, under the name. Mirrors the design's own two helpers rather than one
 * blanket rule, because the design deliberately branches (`Mimir Files.dc.html`, `metaOf` line 550 for
 * tomes and `shortMeta` line 576 for files):
 *  - **DIRECTORY** -- the child count alone ("146 items"). Live on the device as of policy 1; see
 *    below for the one case that still renders blank, deliberately.
 *  - **ARCHIVE** -- the size alone, with no extension prefix. Not an oversight in the design and not
 *    simplified away here: the ZIP/7Z badge ([fileBrowserBadgeLabel]) is already naming the format one
 *    line above, and repeating it would break "one filled thing per column" (spec §4) by giving the
 *    archive column two marks where every other column has one.
 *  - **FILE** -- `EXT · size` ("GBA · 8.0 MB"), the extension being the only identity an unrecognised
 *    file has until a real classifier lands. A file with no extension at all degrades to the size alone
 *    rather than rendering a leading "` · `".
 *
 * **CLOSED 2026-08-14 by policy 1 — the data gap this KDoc used to describe is filled.** For most
 * of this module's life a DIRECTORY's meta rendered blank on a real device, because
 * [com.gruning.mimir.rewrite.core.files.FileItem.displaySize] reads `childCount?.let { "$it items" }
 * ?: ""` (`FileItem.kt:54`) and `childCount` was *declared and read but never written* — the sole
 * production construction site, `LocalFileRepository.pathToFileItem`, did not pass it. It does now
 * (`LocalFileRepository.directoryChildCount`, one `File.list()` per directory row), pinned in
 * `core-files` by `LocalFileRepositoryTest`.
 *
 * **One case still renders blank, and it is a claim rather than a gap:** a directory the app cannot
 * read reports `null`, never `0`. "0 items" and "cannot read this folder" are different statements
 * about the filesystem, so the unreadable one draws nothing rather than a fabricated count — the same
 * refusal [fileBrowserFooterHints] makes ("a fixed legend would lie") and
 * [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark] makes (no plate rather than a wrong one).
 */
internal fun fileBrowserTileMeta(res: Resources, tile: FileBrowserTile): String = fileBrowserTileMetaCandidates(res, tile).first()

/**
 * [fileBrowserTileMeta] and what it may shorten to when its room is short, widest first: a file's `EXT · size` falls back to the size alone, the way an archive's already is (the
 * badge names the kind; the extension is the part that can be spared). A candidate is a whole figure or it is not offered: a number is never cut midway, and the tile
 * ([FileBrowserTileMeta]) takes the first one that stands on its line. Every kind but a file with both halves has the one candidate it always had.
 */
internal fun fileBrowserTileMetaCandidates(res: Resources, tile: FileBrowserTile): List<String> {
    // A held delete has no cover to wear the label on (only a folder does): a file or archive says it where its
    // size would be.
    if (tile.deleting && tile.kind != FileBrowserTileKind.DIRECTORY) return listOf(res.getString(FileBrowserDeletingLabel))
    // A drive gets the compact capacity, not the full "free of total": the tome caption lane is ~270px
    // and the full string truncated to "317.49 GB free of 43..." on the Odin. The inspector shows the
    // whole figure. See FileItem.volumeCapacityCompact.
    val size = fileBrowserVolumeCapacityText(res, tile.item, compact = true) ?: fileBrowserSizeText(res, tile.item)
    return when (tile.kind) {
        FileBrowserTileKind.DIRECTORY, FileBrowserTileKind.ARCHIVE -> listOf(size)
        FileBrowserTileKind.FILE -> {
            val extension = tile.item.extension.uppercase()
            when {
                extension.isBlank() -> listOf(size)
                size.isBlank() -> listOf(extension)
                else -> listOf(res.getString(R.string.files_tile_meta_ext_size, extension, size), size)
            }
        }
    }
}

/**
 * The focused tile as the verbs and the legend see it: a row held for a pending delete ("Deleting…") is a label for what
 * the player just did, not a thing, so to every action it is no tile at all. The controller applies the same rule to
 * every press (`takeUnless { it.deleting }`); the footer legend and the actions predicate read their tile through this,
 * or the legend keeps advertising `A OPEN` / `X ACTIONS` over a row that ignores both.
 */
internal fun FileBrowserTile?.actionableTile(): FileBrowserTile? = this?.takeUnless { it.deleting }
