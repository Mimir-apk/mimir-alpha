package com.gruning.mimir.rewrite.core.files

/**
 * MIMIR ADDITION — not vendored from upstream `SysAdminDoc/FileExplorer`. FileExplorer's
 * `LocalFileRepository.kt` (vendored alongside this file, slice 4) has no equivalent to either
 * predicate below: they re-apply, on top of the vendored enumerator, two of the three listing
 * policies that were previously encoded in Mimir's own (retired-but-not-yet-deleted)
 * `FolderBrowserEnumeration.kt` / `BrowserListing.kt`. The third policy (unreadable directories list empty, never throw) has no
 * standalone symbol here — it is pure control flow inside [LocalFileRepository.listFiles].
 *
 * Consumed by [LocalFileRepository.listFiles] and [LocalFileRepository.search] (this slice); a
 * future `feature-files` row renderer is expected to call [isExtractableArchive] directly to
 * decide whether to offer an "Extract" action, so this is public — unlike [ArchiveEntryPathPolicy]
 * (deliberately `internal`, since `ArchiveHelper`'s own public API is meant to be the only door in
 * for extraction itself; this object is about what to *offer*, not how to extract it).
 */
object LocalListingPolicy {

    /**
     * The supported archive set supersedes the earlier zip-only set in the retired `FolderBrowserEnumeration.OFFERABLE_ARCHIVES` /
     * `BrowserListing.EXTRACTABLE_EXTENSIONS = setOf("zip", "7z")`, `core-storage/.../BrowserListing.kt`).
     * Kept in lockstep with what [ArchiveHelper] (vendored, slice 3) can ACTUALLY extract:
     * `ArchiveFormat.ZIP.extension` = "zip", `ArchiveFormat.SEVEN_Z.extension` = "7z"
     * (`ArchiveHelper.kt`'s own enum).
     *
     * Deliberately NARROWER than [ArchiveHelper.isArchive], which also currently recognises the
     * tar family (tar/gz/bz2/xz/tgz/tbz2/txz) and zst. Those extensions are technically
     * extractable by `ArchiveHelper` today (inherited from upstream, kept in slice 3 — see
     * `ArchiveHelperExtractionTest.writableFormatsRoundTripThroughListAndExtract`, which exercises
     * `ArchiveFormat.TAR_GZ` successfully) — but the *current* policy only asks for zip + 7z,
     * and removing the tar family from `ArchiveHelper` itself is still-owed cleanup: supported offerable formats are zip + 7z, not tar/tgz/tbz2/txz.
     * Until that cleanup lands, this classification must not advertise those formats as offerable. `rar` is excluded
     * on *licence* grounds and is already pinned by
     * `ArchiveHelperExtractionTest.noRarPathExistsAnywhereInTheVendoredSurface`; this set does not
     * duplicate that pin, it composes with it (see `LocalListingPolicyTest`'s cross-check against
     * `ArchiveHelper.isArchive`).
     */
    val EXTRACTABLE_ARCHIVE_EXTENSIONS: Set<String> = setOf("zip", "7z")

    /** Whether [item] should be offered an "Extract" action. A directory never is, regardless of name. */
    fun isExtractableArchive(item: FileItem): Boolean =
        !item.isDirectory && item.extension.lowercase() in EXTRACTABLE_ARCHIVE_EXTENSIONS

    /**
     * RULING 2 (adoption spec § 5 — "plumbing roots excluded ... else `/proc` and friends appear
     * in the listing"). Matched by exact absolute path, not by bare name: a global name-based ban
     * (e.g. refusing every directory named "dev" anywhere in a listing) would conflict with this
     * tree's own documented principle that support material is "dimmed, NEVER hidden ... a browser
     * that lies about what is on disk is worse than none" (`BrowserListing.kt`'s own header
     * comment) — a folder a player genuinely created and named "dev" on their SD card must still
     * show. Absolute-path matching has no such false-positive risk: nothing other than the real OS
     * pseudo-filesystem can ever BE `/proc`, wherever in a tree it is encountered.
     *
     * `/proc`, `/sys`, `/dev` are the three named directly in the adoption spec. `/storage/self`
     * and `/storage/emulated` are "and friends": grounded in the retired
     * `FolderBrowserEnumeration.PLUMBING_DIRECTORIES = setOf("self", "emulated")`
     * (`app/src/main/kotlin/com/gruning/mimir/rewrite/FolderBrowserEnumeration.kt:97`) and its test
     * `` `storage roots exclude plumbing directories` `` — both are non-content redirects under
     * `/storage`, the same underlying concern the spec names, not a new invention. A broader
     * Android-specific denylist (`/acct`, `/config`, ...) was considered and deliberately NOT
     * added: no device evidence and no repo precedent grounds those specific names, and guessing
     * them would be exactly the unverified assertion this programme's no-vibe-coding rule forbids.
     * OWED if device verification (spec § 7, still outstanding for this whole feature) turns up
     * more.
     */
    val PLUMBING_PATHS: Set<String> = setOf(
        "/proc", "/sys", "/dev",
        "/storage/self", "/storage/emulated",
    )

    /** Whether [absolutePath] is a known plumbing mount/redirect that must never surface as a row. */
    fun isPlumbingPath(absolutePath: String): Boolean = absolutePath in PLUMBING_PATHS

    /**
     * POLICY 5 (2026-08-14): **support FILES are dimmed too, not only machinery folders.**
     *
     * The design applies `dim: 1` to support *files* as well as to machinery directories, and its own
     * executable `tileBase` gives both the same 0.55 opacity (`Mimir Files.dc.html:596`). Its fixture
     * data is the ground truth for what counts (lines 470-471, 492): `Pokemon Emerald.srm`,
     * `gamelist.xml` and `notes.txt` are `k: 'SUPPORT', dim: 1`; `Fire Emblem.ips` is `k: 'OTHER'` and
     * is **not** dimmed, and `gba_bios.bin` is `k: 'BIOS'` and is **not** dimmed either. A BIOS payload
     * is load-bearing for launching — the design's own inspector says so in player-facing copy ("BIOS
     * payload. Emulators need this file where it is — moving it will break launches.", line 818) — so
     * demoting it would be actively misleading.
     *
     * **The set is not invented here, and it is not a broad extension sweep.** It is this tree's own
     * `core-storage/.../BrowserListing.kt:70-73` `SUPPORT_EXTENSIONS`, the vocabulary Mimir's
     * own folder browser already shipped with, together with that same function's dotfile rule
     * (`BrowserListing.kt:112`, `entry.name.startsWith(".")`). It is COPIED rather than imported
     * because `feature-files` deliberately does not depend on `core-storage` (see that module's
     * `build.gradle.kts` header) and neither does this one; the duplication is disclosed here rather
     * than hidden, and it cross-checks cleanly against the design's fixture data above — every file the
     * design dims is in this set, and neither file the design leaves undimmed is.
     *
     * **F-07 (2026-08-14) split four extensions out of this set, and NONE were lost.**
     * `png`/`jpg`/`jpeg`/`webp` now live in [MEDIA_ART_EXTENSIONS]. This is no longer `BrowserListing`'s
     * 24 verbatim on its own — **the UNION of the two sets is**, plus `mp4`/`mp3`, and
     * `LocalListingPolicyTest` pins exactly that against a literal copy of the original 24 so a future
     * edit cannot quietly drop one. Dimming is unchanged for all four, because [isSupportFile] now
     * answers true for MEDIA_ART as well as SUPPORT.
     *
     * Consistency check against the two neighbours: `.dat` and `.xml` are support (a DAT inventory or a
     * `gamelist.xml` is metadata about content, not content). None of these overlaps
     * [EXTRACTABLE_ARCHIVE_EXTENSIONS], and [classifyContent] decides ARCHIVE before it ever reaches the
     * payload branches, so two classifications can never both claim a row.
     */
    val SUPPORT_FILE_EXTENSIONS: Set<String> = setOf(
        "srm", "sav", "state", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9",
        "txt", "dat", "xml", "cfg", "ini", "log", "nfo", "db",
    )

    /**
     * MP4, MP3 and PNG belong to the media/art classification.
     *
     * **The existing classification was checked first: `core-storage`'s
     * `BrowserListing` has NO media/art category.** Its `RowKind`
     * (`core-storage/.../scan/BrowserListing.kt:23-41`) is exactly `DIRECTORY, GAME, ARCHIVE, BIOS,
     * SUPPORT, OTHER` — six values, no media kind — and its `SUPPORT_EXTENSIONS`
     * (`BrowserListing.kt:70-73`) files `png`/`jpg`/`jpeg`/`webp` under SUPPORT with the saves and the
     * sidecars, and does not mention `mp4`/`mp3` at all. So this vocabulary is genuinely new. The NAME
     * is not invented either: the design's own Storage screen already labels this exact segment
     * **"Media & artwork"** (`Mimir Files.dc.html:506`).
     *
     * **Six extensions, not a sweep.** Three are `mp4`, `mp3`, `png`. The other three are
     * `jpg`/`jpeg`/`webp`, which this file's own [SUPPORT_FILE_EXTENSIONS] KDoc *already* called "the
     * artwork sidecars that live beside a ROM" — they were being classified as artwork in prose while
     * being labelled SUPPORT in code, which is precisely the classification inconsistency. Moving all
     * four images together keeps `boxart.png` and `boxart.jpg` from reading as different kinds of thing.
     * Nothing beyond those six is added: `mkv`, `wav`, `flac`, `gif`, `bmp` are all plausible and all
     * ungrounded — no design fixture or row in this tree's own vocabulary — so they stay
     * out until something grounds them, per this file's own precedent for refusing the broader Android
     * plumbing denylist.
     *
     * **Deliberately disjoint from [SUPPORT_FILE_EXTENSIONS]**, so no extension can be claimed by two
     * classes and the two sets can be checked for completeness against `BrowserListing`'s original 24 —
     * see `LocalListingPolicyTest`'s union pin, which fails if a future edit drops one on the floor.
     */
    val MEDIA_ART_EXTENSIONS: Set<String> = setOf(
        "mp4", "mp3", "png", "jpg", "jpeg", "webp",
    )

    /**
     * Whether [item] is support material rather than content — dimmed, **never hidden**
     * (`BrowserListing.kt`'s own principle: "a file manager that hides what is on disk is worse than
     * none").
     *
     * Directories are never support material here: a demoted FOLDER is the separate machinery-folder policy
     * (`feature-files`' `FileBrowserMachineryNames`), which matches exact folder names, not extensions.
     * An extractable archive is never support either — it is the one filled thing in its column
     * (adoption spec §4) and dimming the row the player is meant to act on would fight that directly.
     *
     * Platform recognition is NOT consulted here and must not be: this object has no classifier, and
     * "a recognised game is never support" is applied by the caller that owns recognition
     * (`fileBrowserPartition`), mirroring `BrowserListing.row`'s own ordering where `platform != null ->
     * GAME` is decided before the support branch is ever reached.
     *
     * **F-07: this is now DERIVED from [classifyContent] rather than restating the rule.** It answers
     * true for both [FileContentClass.SUPPORT] and [FileContentClass.MEDIA_ART] — media art is a
     * different *kind* of thing but the same *presence* (not game content, demoted,
     * never hidden), so this one predicate stays the browser's single dimming rule and no rendering
     * surface has to learn about the split to keep behaving. Every extension that dimmed before F-07
     * still dims; `mp4`/`mp3` newly join, a behavior change beyond classification alone.
     */
    fun isSupportFile(item: FileItem): Boolean =
        when (classifyContent(item)) {
            FileContentClass.SUPPORT, FileContentClass.MEDIA_ART -> true
            FileContentClass.DIRECTORY, FileContentClass.ARCHIVE, FileContentClass.OTHER -> false
        }

    /**
     * Whether [item] is media art — box art, a video snap, a theme track. Never an archive, never
     * a directory.
     *
     * Narrower than [isSupportFile] on purpose: media art IS demoted (it is not game content), but it is
     * a *different kind of thing* from a save file or a `gamelist.xml`. [isSupportFile] stays the one dimming predicate so no surface has to know
     * about the split to keep rendering correctly.
     */
    fun isMediaArtFile(item: FileItem): Boolean = classifyContent(item) == FileContentClass.MEDIA_ART

    /**
     * The single ordered classification every predicate above now derives from, so two of them can never
     * disagree about one row.
     *
     * Order mirrors `BrowserListing.row`'s own (`core-storage/.../scan/BrowserListing.kt:98-116`):
     * directory first, then archive, then the payload kinds. GAME and BIOS are deliberately absent —
     * both need knowledge this module does not have and must not duplicate (platform recognition lives
     * in `core-resolver`, BIOS extensions in `GameNameResolver.BIOS_EXTENSIONS`), exactly as
     * `BrowserListing.Entry.isBios` is *injected* rather than decided there. Callers that own
     * recognition apply it ahead of this, which is what `FileBrowserTile.support` already does with its
     * `platformIdValue == null` gate.
     *
     * A dotfile is SUPPORT, not OTHER — `BrowserListing.kt:112`'s own rule, kept.
     */
    fun classifyContent(item: FileItem): FileContentClass {
        if (item.isDirectory) return FileContentClass.DIRECTORY
        if (isExtractableArchive(item)) return FileContentClass.ARCHIVE
        val extension = item.extension.lowercase()
        return when {
            extension in MEDIA_ART_EXTENSIONS -> FileContentClass.MEDIA_ART
            extension in SUPPORT_FILE_EXTENSIONS -> FileContentClass.SUPPORT
            item.name.startsWith(".") -> FileContentClass.SUPPORT
            else -> FileContentClass.OTHER
        }
    }
}

/**
 * What a non-directory row IS, as far as `core-files` can tell without a platform classifier.
 *
 * See [LocalListingPolicy.classifyContent] for why GAME and BIOS are absent and
 * [LocalListingPolicy.MEDIA_ART_EXTENSIONS] for the evidence behind [MEDIA_ART] specifically.
 */
enum class FileContentClass {
    /** A directory. Its demotion is the separate machinery rule, matched by folder name, not extension. */
    DIRECTORY,

    /** An archive this tree can actually extract — zip or 7z ([LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS]). */
    ARCHIVE,

    /** Box art, video snaps, theme audio. Demoted, never hidden. */
    MEDIA_ART,

    /** Saves, states, sidecars, dotfiles. Demoted, never hidden. */
    SUPPORT,

    /** Anything else on disk — including unrecognised game content, which a caller's classifier promotes. */
    OTHER,
}
