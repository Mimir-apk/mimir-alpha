package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How loudly the inspector's identity chip reads. Mapped to palette tokens ONLY at the composable
 * call site (spec §3's chip recipe: `--mm-ember`/`--mm-success-fill`) -- this enum carries no color. */
internal enum class FileBrowserChipTone { NEUTRAL, PENDING, RECOGNISED, MUTED }

/**
 * Everything the standing inspector (adoption spec §6, task Build section) shows for one focused tile,
 * derived as a pure function so the pipeline is provably correct without composing anything.
 *
 * **The four fields the design draws that this type deliberately has no room for.** The omissions follow the existing data model and source evidence. The rule
 * this tree already follows is [com.gruning.mimir.rewrite.core.ui.MediaArtFrame]'s, restated verbatim in
 * [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark]'s own source: *"an unnameable platform gets
 * NO plate rather than a meaningless one. A blank or '?' shelfmark would be worse than the absence it is
 * standing in for."* A placeholder here would be strictly worse than absence, because each of these four
 * is a **factual claim about the player's own files** -- an empty art frame implies "no art exists", a
 * "Discs in this set: —" implies "this is not a set", a greyed note implies "nothing to warn you about".
 * Absence says only "Mimir has not looked", which is the truth:
 *  - **Art block** -- the design fills it from a fixed `art` map of four bundled PNGs keyed by platform
 *    name (`Mimir Files.dc.html:512`), reached only when a classifier has already named the platform.
 *    With `classifyPlatform = { null }` (spec §9) that key never exists, so the honest render is nothing.
 *  - **"Discs in this set"** -- needs a real m3u/set parser. The design reads a hand-authored `members`
 *    array (line 476); deriving it for real means *reading the file's contents*, which nothing in this
 *    module's `listDirectory`-only contract can do. No `members` field here at all.
 *  - **The note row** -- the design's two notes are a BIOS-payload warning (needs a BIOS kind this tile
 *    model does not have without a classifier) and a "7z extraction is not available yet" line that
 *    would silently reverse this tree's own zip+7z support (see [fileBrowserBadgeLabel]'s KDoc). No
 *    `note` field here.
 *
 * **Checksum is deliberately NOT a field here, as of F-05.** It used to be, as a permanently-constant
 * `checksumStatus` string, which is precisely why it went stale: the panel builds this object inside a
 * `remember(tile, currentPath, rootPath, crumbLabels)`, so a live result arriving from `app/` could not
 * have re-rendered it even once it existed. The checksum is a per-*operation* fact, not a per-tile one;
 * it lives in [FileBrowserChecksumState] and reaches the row directly. [checksumable] is the one
 * checksum-shaped thing that IS per-tile, and it stays.
 *
 * **Launches** never names a specific emulator app -- this tree has no id-to-emulator table wired into
 * `feature-files`, so naming one would assert an unverified mapping. It states only what [FileBrowserTile.platformIdValue] itself proves.
 */
internal data class FileBrowserInspectorFields(
    val name: String,
    val tag: String,
    val meta: String,
    val chipLabel: String,
    val chipTone: FileBrowserChipTone,
    val where: String,
    val modified: String,
    val launches: String,
    val openable: Boolean,
    val extractable: Boolean,
    /**
     * Whether a checksum is a meaningful question about this tile at all — **F-05**. A directory has no
     * digest, so the row and its Verify button are not drawn on a tome. Before this, they were: every
     * folder in the folio offered a button whose only possible outcome was a failure
     * (`LocalFileRepository.getChecksum` on a directory), and the design's own precedent for an action
     * that cannot succeed is [FileBrowserController]'s `commitAllowed` — do not offer it.
     *
     * ARCHIVE stays true: hashing a packaged ROM is exactly what a DAT comparison wants.
     */
    val checksumable: Boolean,
    /**
     * The reason this file cannot be chosen, when the browser is an image picker and the file is not an
     * image ("Not an image"); null everywhere else. The image picker's All-files view keeps every card at
     * full presence (nothing is faded), so the reason has to be said in words, and this row is where.
     */
    val uploadNote: String? = null,
)

/** Media-art kind for MP4, MP3 and PNG (F-07). Named
 * rather than inlined so the tile grid can reach the same string when it earns a badge. */
internal val FileBrowserMediaArtChip: Int = R.string.files_chip_media_art

/** The Launches row on a game Mimir recognises as a game but cannot place on a platform. Copy approved 2026-10-08. */
internal val FileBrowserNoPlatformLaunches: Int = R.string.files_launch_no_platform

/** The Launches row on an emulator BIOS: it is load-bearing, so the row says where it has to stay. */
internal val FileBrowserBiosLaunches: Int = R.string.files_launch_bios

/**
 * The standing inspector's width, `FileBrowserSurface`'s own `Modifier.width(...)`. Named because
 * [fileBrowserChecksumDigestWidthDp]'s fit is asserted against it: a 40-character SHA-1 has to be
 * measured against a real number, not a remembered one.
 */
internal const val FileBrowserInspectorWidthDp: Float = 270f

/** The width of every field row's label gutter ("Where", "Modified", "Checksum"), matching the design's
 * own `width:70px` (`Mimir Files.dc.html:198`). Named for the same reason as above. */
internal const val FileBrowserInspectorLabelWidthDp: Float = 70f

/** [epochMillis] <= 0 covers [com.gruning.mimir.rewrite.core.files.FileItem]'s own `0L` default
 * (a real filesystem read never reports epoch 0) -- shown honestly as "Unknown" rather than a
 * misleading 1 Jan 1970 timestamp. [zone] is injectable so this stays deterministic under test;
 * production call sites accept the device's own zone by default. The month is written in the host's language
 * ([filesLocale]); English stays "d MMM yyyy" on `Locale.US`, as it always was. */
internal fun fileBrowserFormatModified(res: Resources, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0L) return res.getString(R.string.files_inspector_unknown)
    return DateTimeFormatter.ofPattern("d MMM yyyy '·' HH:mm", res.filesLocale()).format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}

/**
 * The one answer in this module to *"can Mimir name this platform out loud?"*, shared by the inspector's
 * chip/Launches rows and by the tome cover's glyph ([FileBrowserSurface]'s `TomeTile`).
 *
 * Deliberately not two reads of [mimirPlatformShelfmarkCode]. A recognised-but-uncodeable id -- one whose
 * label has no letter or digit for `shelfmarkInitials` to work with, so the curated table misses AND the
 * fallback returns blank -- makes [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark] draw
 * nothing at all (`MimirPlatformShelfmark.kt:89`, "an unnameable platform gets NO plate rather than a
 * meaningless one"). A caller that branches on `platformIdValue != null` instead of on the *code* would
 * then tell [com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover] to suppress its embossed Mannaz
 * (`MimirNorseBook.kt:290`) and hand it a plate that renders nothing -- a completely blank cover. Both
 * call sites branch on this function's null-ness so the fallback is always the Mannaz, never a void.
 */
internal fun fileBrowserPlatformCode(platformIdValue: String?): String? =
    platformIdValue?.let { mimirPlatformShelfmarkCode(it) }

/**
 * @param currentPath the directory currently open -- the **Where** row's subject. Reported as the
 *   design's friendly ` › `-joined trail, never the raw absolute path: see [where]'s own note below.
 * @param rootPath the browser's floor, required (not defaulted) precisely so no caller can silently
 *   fall back to a degraded trail. Only [fileBrowserFriendlyPath] uses it.
 * @param crumbLabels optional friendly labels for volume crumbs, keyed by absolute path -- see
 *   [fileBrowserBreadcrumbs] for the full contract and for why it is a map, not a single root label.
 */
internal fun fileBrowserInspectorFields(
    res: Resources,
    tile: FileBrowserTile,
    currentPath: String,
    rootPath: String,
    crumbLabels: Map<String, String> = emptyMap(),
    zone: ZoneId = ZoneId.systemDefault(),
    uploadNote: String? = null,
): FileBrowserInspectorFields {
    val code = fileBrowserPlatformCode(tile.platformIdValue)
    val fileKind = fileBrowserFileKind(tile)
    val (chipLabel, chipTone) = when (tile.kind) {
        FileBrowserTileKind.DIRECTORY -> if (code != null) {
            res.getString(R.string.files_chip_platform_tome) to FileBrowserChipTone.RECOGNISED
        } else {
            res.getString(R.string.files_kind_folder) to FileBrowserChipTone.NEUTRAL
        }
        FileBrowserTileKind.ARCHIVE -> res.getString(R.string.files_kind_archive) to FileBrowserChipTone.PENDING
        // F-07: "Misc" is what Mimir says when it does not know. It DOES know what a .png or an .mp4 is,
        // so saying "Misc" there was under-reporting a fact it holds.
        // extends the same honesty to every kind: the chip names the type
        // [fileBrowserFileKind] decided, the same word the card's badge wears, so no file is left as an
        // anonymous "Misc" when Mimir can say what it is. A BIOS is identified whatever platform it also
        // matches; a platform the classifier named is otherwise game content whatever its extension.
        FileBrowserTileKind.FILE -> when {
            fileKind == FileBrowserFileKind.BIOS -> FileBrowserBiosWord to FileBrowserChipTone.RECOGNISED
            code != null -> res.getString(R.string.files_chip_recognised) to FileBrowserChipTone.RECOGNISED
            fileKind == FileBrowserFileKind.GAME_UNKNOWN -> res.getString(R.string.files_kind_game) to FileBrowserChipTone.NEUTRAL
            tile.mediaArt -> res.getString(FileBrowserMediaArtChip) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.SAVE -> res.getString(R.string.files_chip_save_data) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.DATA -> res.getString(R.string.files_kind_data) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.DOC -> res.getString(R.string.files_chip_document) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.APP -> res.getString(R.string.files_kind_app) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.MUSIC -> res.getString(R.string.files_kind_music) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.IMAGE -> res.getString(R.string.files_kind_image) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.VIDEO -> res.getString(R.string.files_kind_video) to FileBrowserChipTone.MUTED
            fileKind == FileBrowserFileKind.ARCHIVE -> res.getString(R.string.files_kind_archive) to FileBrowserChipTone.MUTED
            else -> res.getString(R.string.files_chip_misc) to FileBrowserChipTone.MUTED
        }
    }
    val launches = when (tile.kind) {
        FileBrowserTileKind.DIRECTORY ->
            if (code != null) res.getString(R.string.files_launch_platform_folder, code) else res.getString(R.string.files_launch_not_platform_folder)
        FileBrowserTileKind.ARCHIVE -> res.getString(R.string.files_launch_extract_first)
        FileBrowserTileKind.FILE -> when {
            fileKind == FileBrowserFileKind.BIOS -> res.getString(FileBrowserBiosLaunches)
            code != null -> res.getString(R.string.files_launch_runs_on, code)
            // A game with no platform is still a game: it says what Mimir could not settle instead of
            // calling the file "not game content".
            fileKind == FileBrowserFileKind.GAME_UNKNOWN -> res.getString(FileBrowserNoPlatformLaunches)
            else -> res.getString(R.string.files_launch_not_game)
        }
    }
    return FileBrowserInspectorFields(
        name = tile.name,
        tag = fileBrowserBadgeLabel(res, tile),
        meta = fileBrowserSizeText(res, tile.item),
        chipLabel = chipLabel,
        chipTone = chipTone,
        // The design's Where row is the FRIENDLY trail ("Roms › GBA"), not the raw mount path -- its
        // `pathStyle` prop defaults to 'friendly' and only a review-only 'raw' mode ever shows
        // `/storage/...` (`Mimir Files.dc.html:810`, `friendly()` at :619). This row previously
        // reported `currentPath` verbatim, which is what put a bare `/storage/emulated/0/Roms` in front
        // of a player on build d0b2c550. Derived through the crumb builder so the row and the header
        // trail cannot disagree, and so a real volume name lands in both at once when one is threaded.
        where = fileBrowserFriendlyPath(rootPath, currentPath, crumbLabels),
        modified = fileBrowserFormatModified(res, tile.item.lastModified, zone),
        launches = launches,
        // A row held for a pending delete ("Deleting…") is a label for what the player just did, not a thing:
        // the controller already refuses every verb on it (`takeUnless { it.deleting }` in `dispatch`,
        // `handleInspectorExtractTap`, `verifyFocusedChecksum`), so a drawn Open, Extract or Verify there was a
        // button that does nothing when pressed. Hidden, not disabled -- the panel has no disabled treatment.
        openable = tile.kind == FileBrowserTileKind.DIRECTORY && !tile.deleting,
        extractable = tile.extractable && !tile.deleting,
        checksumable = tile.kind != FileBrowserTileKind.DIRECTORY && !tile.deleting,
        uploadNote = uploadNote,
    )
}
