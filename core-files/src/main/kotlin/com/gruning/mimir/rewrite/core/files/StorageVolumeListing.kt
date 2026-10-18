package com.gruning.mimir.rewrite.core.files

/**
 * MIMIR ADDITION — not vendored from upstream `SysAdminDoc/FileExplorer`. Sibling of
 * [LocalListingPolicy]: pure, `Context`-free policy that sits on top of the vendored
 * [StorageVolumeHelper] so the parts a unit test can actually reach are separated from the parts that
 * need a live Android `StorageManager`.
 *
 * **The defect this exists for**: the
 * browser was opened at `FolderBrowserEnumeration.storageRoots().firstOrNull()` — the FIRST root only,
 * always internal storage — and [FileBrowserController][com.gruning.mimir.rewrite.feature.files] cannot
 * navigate above its own root by construction, so an SD card was unreachable. [StorageVolumeHelper] had
 * been vendored with exactly the enumeration needed and was wired to nothing.
 *
 * **The mechanism.** [VOLUME_ROOT_PATH] is used as a browser root that lists the device's mounted
 * volumes as ordinary directory rows ([volumeRows]). Entering one descends into a real volume; backing
 * out returns to the chooser, from which the OTHER volume is one press away. No new navigation concept,
 * no new controller API, no change to how a directory is entered — the drive chooser IS a directory
 * listing, so every existing focus, tap and CANCEL behaviour applies to it unchanged.
 *
 * **[VOLUME_ROOT_PATH] is a real path, deliberately, not an invented sentinel.** `/storage` is where
 * Android actually mounts volumes, so the crumb trail and the back-stack stay truthful about where the
 * player is. Its REAL listing is not usable as a chooser, which is why [volumeRows] synthesises one:
 * `LocalListingPolicy.PLUMBING_PATHS` correctly excludes `/storage/emulated` and `/storage/self`, and
 * internal storage is `/storage/emulated/0` — one level BELOW the excluded directory — so a genuine
 * `listFiles("/storage")` shows the SD card and hides internal storage, the exact inverse of the bug.
 *
 * **The chooser appears only when there is something to choose** ([browserRoot]): one volume roots the
 * browser directly at that volume, exactly as before this change, so a single-storage device pays no
 * extra press and sees no behaviour difference at all.
 */
object StorageVolumeListing {

    /**
     * The browser root that lists volumes instead of files. Android's own mount point directory — see
     * this object's KDoc for why a real path is used rather than a synthetic sentinel.
     */
    const val VOLUME_ROOT_PATH: String = "/storage"

    /**
     * Where to root the browser when volume enumeration returns nothing at all.
     *
     * Not a new constant: this is the same literal `MimirSettingsCoordinator`'s own
     * `?: "/storage/emulated/0"` fallback already used, and the same path
     * `FolderBrowserEnumeration.storageRoots()` seeds its list with. Primary external storage's location
     * is fixed by the platform, so this is a restatement of an Android guarantee, not a guess.
     */
    const val PRIMARY_FALLBACK_PATH: String = "/storage/emulated/0"

    /**
     * Whether [path] is the volume chooser rather than a real directory.
     *
     * Trailing slashes are tolerated because a path can reach here from either a `File`/`Path`
     * round-trip or a raw string constant, and the two disagree about them. `/` itself is NOT the volume
     * root: the filesystem root is a real, listable directory and must keep listing normally — and it
     * needs no special case, since trimming its slashes leaves `""`, which is not [VOLUME_ROOT_PATH]
     * either. (An `.ifEmpty { "/" }` clause was written here first and then deleted: differential-testing
     * it showed it could not change this function's result for ANY input, so it was dead defensive code,
     * not an untested branch.)
     */
    fun isVolumeRoot(path: String): Boolean =
        path.trimEnd('/') == VOLUME_ROOT_PATH

    /**
     * The volumes of [volumes], as the directory rows a listing would have produced.
     *
     * Primary storage first, then everything else by name — the order is fixed HERE rather than
     * inherited from [StorageVolumeHelper]'s iteration order, so the chooser cannot start reordering
     * itself because the platform returned `getExternalFilesDirs` in a different sequence.
     *
     * `path` is the volume's real mount point, so entering a row is an ordinary directory descent and
     * every downstream consumer (focus keys, breadcrumbs, the back-stack, extraction) keeps working on a
     * genuine path. Rows carry no size: a volume's byte totals live on [StorageVolume] itself
     * ([StorageVolume.totalBytes] / [StorageVolume.freeBytes] / [StorageVolume.usagePercent]) and are
     * offered to a UI from there, rather than being smuggled into a [FileItem] field that means
     * something else.
     *
     * [LocalListingPolicy.isPlumbingPath] is applied here too — a volume must clear the same bar an
     * ordinary row does, so the two surfaces cannot develop separate opinions about what may be shown.
     */
    fun volumeRows(volumes: List<StorageVolume>): List<FileItem> =
        volumes
            .distinctBy { it.path }
            .filterNot { it.path.isBlank() || LocalListingPolicy.isPlumbingPath(it.path) }
            .sortedWith(compareBy({ !it.isPrimary }, { it.name.lowercase() }))
            .map { volume ->
                FileItem(
                    name = volume.name,
                    path = volume.path,
                    isDirectory = true,
                    // Explicit rather than defaulted: FileItem derives `extension` from the NAME, and a
                    // volume name is a label ("SD Card (1A2B-3C4D)"), not a filename — a label that
                    // happened to contain a dot would otherwise be handed a nonsense extension.
                    extension = "",
                    mimeType = "inode/directory",
                    //  StorageVolumeHelper already ran a
                    // StatFs for each of these and this mapping was dropping both numbers on the floor,
                    // which is why the chooser could only say "Folder / Modified Unknown" about a 256GB
                    // card. Carried, not recomputed.
                    volumeFreeBytes = volume.freeBytes,
                    volumeTotalBytes = volume.totalBytes,
                )
            }

    /**
     * The word shown for [VOLUME_ROOT_PATH] in the breadcrumb trail.
     *
     * A copy decision, chosen for the drive chooser: `/storage`'s basename is the literal
     * string `storage`, which is plumbing vocabulary, and the row it labels is the drive chooser. The existing trail starts from a volume ("SD card › Roms"), so the level ABOVE the volumes needed
     * a name and the chooser needs a user-facing label.
     */
    const val VOLUME_ROOT_LABEL: String = "Drives"

    /**
     * Friendly breadcrumb labels for the browser, keyed by absolute path.
     *
     * The defect: `FileBrowserSurface(crumbLabels = …)` exists and was never filled, so the trail read
     * `storage › emulated › 0 › Roms` — four crumbs of plumbing vocabulary, none of which is a name the
     * player gave anything. With this it reads `Drives › SD Card › Roms`.
     *
     * Built from [volumeRows] rather than from [volumes] directly, for the same reason [browserRoot] is:
     * the labels must describe exactly the rows the chooser actually shows, so a volume filtered out by
     * [LocalListingPolicy.isPlumbingPath] cannot leave a label behind for a row that is not there. The
     * [VOLUME_ROOT_PATH] entry is unconditional — harmless when the browser is rooted at a single volume
     * (that path never appears in the trail, and `fileBrowserBreadcrumbs` simply never looks it up),
     * and required the moment the chooser IS the root.
     *
     * **Both key forms are safe.** `fileBrowserBreadcrumbs` trims trailing slashes from keys AND from
     * lookup paths before matching (see its own KDoc), so a mount point arriving as `/storage/1A2B-3C4D/`
     * still matches. A blank volume name falls back to the crumb's basename there too, so no entry here
     * has to be defended against.
     *
     * The mapping is pure: `List<StorageVolume>` in, `Map<String, String>` out, no `Context` or Android.
     * It sits beside [volumeRows] and [browserRoot], which it must agree with, and is unit-tested
     * without Robolectric. `app/` passes the result to `FileBrowserSurface`.
     */
    fun crumbLabels(volumes: List<StorageVolume>): Map<String, String> =
        volumeRows(volumes).associate { it.path to it.name } +
            (VOLUME_ROOT_PATH to VOLUME_ROOT_LABEL)

    /**
     * Where to open the browser, given the device's [volumes].
     *
     * Derived from [volumeRows]'s own output rather than from [volumes] directly, so "the chooser is
     * shown" and "the chooser has more than one row" cannot disagree: if filtering leaves a single
     * volume, the browser roots straight at it and no chooser is ever reachable to be empty or
     * one-sided.
     */
    fun browserRoot(volumes: List<StorageVolume>): String {
        val rows = volumeRows(volumes)
        return when (rows.size) {
            0 -> PRIMARY_FALLBACK_PATH
            1 -> rows.single().path
            else -> VOLUME_ROOT_PATH
        }
    }

    /**
     * Which of [volumes] [path] lives on, or null.
     *
     * **MIMIR ADDITION — F-08 and F-06 both need it, for the same reason.** Search is scoped to *"the
     * volume you are in"* (the design's own player-facing copy), and F-06 refuses to default an
     * extraction onto a different drive from the archive. Both questions are "which drive is this path
     * on", and answering them from two places would let the search scope and the extract default
     * disagree about where the player is standing.
     *
     * **Longest match wins, and the boundary is a separator.** A device with `/storage/emulated/0` and
     * `/storage/emulated/0/Roms` mounted (not a real Android arrangement, but nothing here should
     * depend on that) must attribute a file under `Roms` to the deeper volume. The separator check is
     * what keeps `/storage/1A2B-3C4D-backup` from being read as living on `/storage/1A2B-3C4D`; plain
     * `startsWith` gets that wrong, which is exactly the hazard [mountPointForExternalFilesDir]'s own
     * KDoc declines to harden against only because no real path can trip it there. Here a player's own
     * folder names reach this function, so it is hardened.
     *
     * [VOLUME_ROOT_PATH] itself is never a volume: it is the chooser, whose rows ARE the volumes.
     */
    fun volumeRootFor(path: String, volumes: List<StorageVolume>): String? =
        volumeRows(volumes)
            .map { it.path }
            .filter { root -> path == root || path.startsWith("$root/") }
            .maxByOrNull { it.length }

    /**
     * The mount point of the volume owning an app-private `getExternalFilesDirs` entry, or null when
     * that entry belongs to primary storage (already enumerated) or is too short to carry one.
     *
     * Extracted verbatim from [StorageVolumeHelper.getStorageVolumes]'s own loop body so the one piece
     * of SD-card detection that is pure string arithmetic — and the piece the "cant swap between drives"
     * defect ultimately depends on — can be tested without a live `Context`. Behaviour is unchanged,
     * including upstream's plain `startsWith` primary-storage check: it is kept as-is rather than
     * hardened to a separator-aware comparison because no real device path can trip it
     * (`/storage/XXXX-XXXX/...` never begins with `/storage/emulated/0`), and changing vendored
     * behaviour with no device evidence would be a guess.
     *
     * @param externalFilesDir an absolute path from `Context.getExternalFilesDirs(null)`, e.g.
     *   `/storage/1A2B-3C4D/Android/data/com.gruning.mimir.rewrite/files`.
     * @param primaryStoragePath `Environment.getExternalStorageDirectory().absolutePath`.
     */
    fun mountPointForExternalFilesDir(externalFilesDir: String, primaryStoragePath: String): String? {
        if (externalFilesDir.startsWith(primaryStoragePath)) return null
        val segments = externalFilesDir.split("/")
        if (segments.size < 3) return null
        return "/${segments[1]}/${segments[2]}"
    }

    /**
     * The display name for a removable volume at [mountPoint], preferring Android's own live label
     * over the FAT-serial-derived fallback [StorageVolumeHelper.getStorageVolumes] has always shown.
     *
     * **Removable-volume label comparison:** Mimir showed a
     * removable card as `SD Card (6A7F-FCFE)` -- that mount point's own basename -- while the
     * yardstick app (EX File Manager) showed the same physical card as **`Thor`**, its custom label. The ticket names `android.os.storage.StorageVolume.getDescription(Context)` /
     * `.getUserLabel()` as the two candidate sources; only the first is actually callable here --
     * disassembling this module's own compileSdk-35 `android.jar` stub shows `getDescription`
     * present and public, `getUserLabel` absent from the public surface entirely, so it would not
     * link even if preferred. [StorageVolumeHelper] passes whatever it got back from that call as
     * [platformLabel]. **What `getDescription` actually returns for the Odin's card ("Thor" or
     * something else) is OWED device verification** -- this function only owns the "prefer it when
     * present, fall back honestly when not" policy, not the platform's own runtime behaviour.
     *
     * **Fall back honestly.** [platformLabel] is null the instant the caller had nothing usable --
     * no framework volume found for that path, the lookup threw, or the description came back
     * blank. This tree's standing rule for an unnameable thing is "no plate rather than a
     * meaningless one", so a missing platform label reproduces exactly today's synthesized name --
     * same string, same format -- rather than inventing anything new.
     *
     * **Internal storage is deliberately NOT routed through this.** [StorageVolumeHelper] keeps its
     * literal `"Internal Storage"` name for primary storage unconditionally. Android exposes no
     * rename affordance for built-in storage the way it does for a removable card, so any label
     * `getDescription` would return there is a generic platform string, not an user-assigned name
     * -- it carries none of the meaning G8 is about, and swapping a clean, controlled label for an
     * OEM-controlled one would be an unrequested change with no evidenced upside. G8's own evidence,
     * and the "Thor" example, are both about a removable card.
     *
     * **One caller** ([StorageVolumeHelper.getStorageVolumes]), and every consumer in the tree reads
     * the resulting [StorageVolume.name] through it, never re-derived: [volumeRows] (the drive
     * chooser), [crumbLabels] (the breadcrumb trail, and through it `feature-files`'
     * `fileBrowserInspectorFields`'s "Where" row and `fileBrowserSearchVolumeLabel`'s "on <volume>"
     * search status line -- both trace to [crumbLabels] by longest-prefix match, confirmed by
     * reading each call site). There is no second place a volume's name is computed, so fixing it
     * here is the whole fix.
     */
    fun externalVolumeName(mountPoint: String, platformLabel: String?): String {
        val fallback = "SD Card (${mountPoint.substringAfterLast('/')})"
        return platformLabel?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }
}
