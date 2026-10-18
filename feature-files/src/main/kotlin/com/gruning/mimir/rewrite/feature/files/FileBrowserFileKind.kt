package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileContentClass
import com.gruning.mimir.rewrite.core.files.LocalListingPolicy

/**
 * What a tile IS, for the purpose of how its card looks and what its badge says.
 *
 *  Files used to draw anything it did not
 * recognise at 55% opacity, so a perfectly healthy file looked switched off -- worst in the image-upload
 * picker, where most of a folder looked broken. The fix is not a nicer grey: every kind of file gets a card
 * of its own, with its own ground and its own word, and nothing is faded.
 */
internal enum class FileBrowserFileKind {
    /** A directory whose own name claims a platform. Leather tome, platform plate. */
    PLATFORM_FOLDER,

    /** Any other directory, Mimir's own working folders included. Leather tome, Mannaz rune. */
    FOLDER,

    /** A file the classifier named a platform for. Platform plate. */
    GAME,

    /** Game content whose platform could not be settled. Still a game: same ground, `GAME` badge. */
    GAME_UNKNOWN,

    /** Emulator save or save state (`srm`, `sav`, `state`, `st0`..`st9`). */
    SAVE,

    /** Other metadata a library tool writes beside games (`dat`, `db`, dotfiles ...). */
    DATA,

    /** Audio. */
    MUSIC,

    /** A still image. */
    IMAGE,

    /** Video. `mp4` lands here and not in [MUSIC] (lead resolution 2026-10-08), whatever core-audio indexes. */
    VIDEO,

    /** An archive, extractable (`zip`, `7z`) or not (`tar`, `rar` ...). */
    ARCHIVE,

    /** A readable document: text, configuration, markup, PDF. */
    DOC,

    /** An Android package. */
    APP,

    /** An emulator BIOS payload. Load-bearing for launching, so it says BIOS rather than passing as a game. */
    BIOS,

    /** Everything else. Full presence, the word `FILE`. */
    MISC,
}

/** The extensions that are save data. `st0`..`st9` are the numbered save-state slots. */
private val SaveExtensions: Set<String> =
    setOf("srm", "sav", "state") + (0..9).map { "st$it" }

/** PDF is a document (lead resolution 2026-10-08); everything else readable is `FileItem.isText`. */
private const val PdfExtension = "pdf"
private const val PdfMime = "application/pdf"

/**
 * THE type of [tile] -- one pure function beside [FileBrowserTile.support] and [FileBrowserTile.mediaArt],
 * read by the file tile, the search rows and the inspector so the same file can never be called three
 * different things.
 *
 * **Order, and why each step sits where it does:**
 *  1. A **directory** is a platform folder or a plain one; nothing else.
 *  2. **BIOS** before platform: `gba_bios.bin` in a `gba` folder resolves to GBA, and calling it a GBA game
 *     is exactly the mislabel the BIOS badge exists to prevent.
 *  3. **Platform recognised** -> [FileBrowserFileKind.GAME], archives included (a recognised `Mario.gba.zip`
 *     wears its platform plate, as it always has). Only a platform the shelfmark can NAME counts: an id with
 *     no code is treated as no platform at all, the same way the tome cover and the inspector already do.
 *  4. **Archive**, extractable or not, ahead of the game-like test: an unrecognised `.zip` is first of all
 *     an archive, and "extract it" is the thing the player can do with it.
 *  5. **Media-art and sidecar vocabularies**, by extension, from `core-files`' one list
 *     ([LocalListingPolicy.classifyContent]). Extension sets come before MIME because MIME is only filled
 *     in on the device (`LocalFileRepository`); a host test must set `mimeType` to reach the MIME steps.
 *     Readable sidecars (`txt`, `xml`, `cfg` ...) are documents; the rest are save data or other data.
 *  6. **Android package.** An `.apk` is unambiguous, and the platform registry knows the extension, so the
 *     game-like test below would otherwise call every downloaded app a game.
 *  7. **Game-like**, or a platform id nothing can name -> [FileBrowserFileKind.GAME_UNKNOWN].
 *  8. **MIME** for the audio, video and image formats the extension sets do not name, then text and PDF.
 *  9. [FileBrowserFileKind.MISC].
 */
internal fun fileBrowserFileKind(tile: FileBrowserTile): FileBrowserFileKind {
    if (tile.kind == FileBrowserTileKind.DIRECTORY) {
        return if (fileBrowserPlatformCode(tile.platformIdValue) != null) {
            FileBrowserFileKind.PLATFORM_FOLDER
        } else {
            FileBrowserFileKind.FOLDER
        }
    }
    val item = tile.item
    if (tile.bios) return FileBrowserFileKind.BIOS
    if (fileBrowserPlatformCode(tile.platformIdValue) != null) return FileBrowserFileKind.GAME
    if (tile.kind == FileBrowserTileKind.ARCHIVE || item.isArchive) return FileBrowserFileKind.ARCHIVE

    val extension = item.extension.lowercase()
    when (LocalListingPolicy.classifyContent(item)) {
        FileContentClass.MEDIA_ART -> return when (extension) {
            "mp3" -> FileBrowserFileKind.MUSIC
            "mp4" -> FileBrowserFileKind.VIDEO
            else -> FileBrowserFileKind.IMAGE
        }
        FileContentClass.SUPPORT -> return when {
            item.isText -> FileBrowserFileKind.DOC
            extension in SaveExtensions -> FileBrowserFileKind.SAVE
            else -> FileBrowserFileKind.DATA
        }
        FileContentClass.ARCHIVE -> return FileBrowserFileKind.ARCHIVE
        FileContentClass.DIRECTORY, FileContentClass.OTHER -> Unit
    }

    if (item.isApk) return FileBrowserFileKind.APP
    // A platform id the shelfmark cannot name is no better than a platform that was never settled: both are a
    // game whose platform Mimir cannot say out loud, and [FileBrowserInspectorFields] already reads that id as
    // "unrecognised everywhere" rather than half-recognised.
    if (tile.gameLike || tile.platformIdValue != null) return FileBrowserFileKind.GAME_UNKNOWN
    return when {
        item.isAudio -> FileBrowserFileKind.MUSIC
        item.isVideo -> FileBrowserFileKind.VIDEO
        item.isImage -> FileBrowserFileKind.IMAGE
        item.isText || extension == PdfExtension || item.mimeType == PdfMime -> FileBrowserFileKind.DOC
        else -> FileBrowserFileKind.MISC
    }
}

/** Which of the theme's own colours a part of a card is struck in. */
internal enum class FileBrowserCardHue {
    /** No colour of its own: the plain surface. */
    NONE,

    /** The theme's accent: it plays in Mimir. */
    ACCENT,

    /** The theme's counter-accent: it is media. */
    COUNTER_ACCENT,

    /** The leather's top colour: it is a package. */
    COVER,
}

/**
 * How a card is struck, as data: a [wash] (a soft vertical gradient from the hue into the plain surface), a
 * [rule] (a 2dp line along the top edge) and optional [scanlines]. Pure, so the whole mapping can be
 * asserted without composing anything; `FileBrowserSurface` turns it into pixels.
 *
 * **Colour never carries the type on its own.** Every card also carries its badge word
 * ([fileBrowserBadgeLabel]) and its name in `textPrimary`; the hue is a second cue, so the type reads for a
 * player who cannot tell two hues apart and never depends on a theme's particular pair.
 */
internal data class FileBrowserCardStyle(
    val wash: FileBrowserCardHue,
    val rule: FileBrowserCardHue,
    val scanlines: Boolean,
)

/**
 * The card for [kind]. `accent` means it plays in Mimir, `counterAccent` means media; the pair is distinct
 * on all nine themes, and the leather stays with folders. Spec §4's "one filled thing per column" is kept
 * by the badge (only an extractable archive's is filled), not by the ground.
 */
internal fun fileBrowserCardStyle(kind: FileBrowserFileKind): FileBrowserCardStyle = when (kind) {
    FileBrowserFileKind.GAME, FileBrowserFileKind.GAME_UNKNOWN ->
        FileBrowserCardStyle(wash = FileBrowserCardHue.ACCENT, rule = FileBrowserCardHue.ACCENT, scanlines = false)
    FileBrowserFileKind.SAVE, FileBrowserFileKind.DATA, FileBrowserFileKind.BIOS ->
        FileBrowserCardStyle(wash = FileBrowserCardHue.NONE, rule = FileBrowserCardHue.ACCENT, scanlines = false)
    FileBrowserFileKind.MUSIC, FileBrowserFileKind.IMAGE, FileBrowserFileKind.VIDEO ->
        FileBrowserCardStyle(
            wash = FileBrowserCardHue.COUNTER_ACCENT,
            rule = FileBrowserCardHue.COUNTER_ACCENT,
            scanlines = false,
        )
    FileBrowserFileKind.ARCHIVE ->
        FileBrowserCardStyle(wash = FileBrowserCardHue.NONE, rule = FileBrowserCardHue.COVER, scanlines = false)
    FileBrowserFileKind.DOC ->
        FileBrowserCardStyle(wash = FileBrowserCardHue.NONE, rule = FileBrowserCardHue.NONE, scanlines = true)
    FileBrowserFileKind.APP, FileBrowserFileKind.MISC,
    FileBrowserFileKind.PLATFORM_FOLDER, FileBrowserFileKind.FOLDER,
    ->
        FileBrowserCardStyle(wash = FileBrowserCardHue.NONE, rule = FileBrowserCardHue.NONE, scanlines = false)
}

/** How strongly the hue is mixed into the card's top edge. Struck once, here, for every wash. */
internal const val FileBrowserCardWashFraction = 0.14f

/** The rule along a card's top edge, in dp. */
internal const val FileBrowserCardRuleDp = 2f
