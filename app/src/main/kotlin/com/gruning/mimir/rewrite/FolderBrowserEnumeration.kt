package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.storage.scan.BrowserListing
import java.io.File

/**
 * Real filesystem enumeration, kept for its pinning tests only from slice 6 onward
 * (the swap to `feature-files`/`core-files`).
 *
 * [listDirectory] is no longer called by production code: the app now lists directories through
 * `core-files`'s `LocalFileRepository` instead (see `FileBrowserStore.kt`), which independently
 * re-applies the plumbing-path and unreadable-directory rulings this file originated -- see
 * `core-files/LocalListingPolicy.kt`'s own header, which cites this file's `PLUMBING_DIRECTORIES` as
 * grounding. [listDirectory] and [FolderBrowserEntry] (moved here from the now-deleted
 * `feature-library/FolderBrowserApi.kt`, slice 6) remain as differential fixtures:
 * `FolderBrowserEnumerationTest` pins the three listing rules re-applied on the vendored lister.
 *
 * **[storageRoots] is no longer live either, as of 2026-08-14.** It used to seed the browser's root path
 * from `MimirSettingsCoordinator`, but `.firstOrNull()` on its result is exactly what made an SD card
 * unreachable -- a `FileBrowserController` cannot navigate above
 * its own root, so seeding it with the first root only meant no other volume could ever be reached. The
 * browser now roots through `core-files`' `StorageVolumeListing.browserRoot`, off real enumerated
 * volumes, which also gives the chooser a friendly volume NAME rather than a bare path. [storageRoots]
 * is kept for the same reason [listDirectory] is -- its test pins the plumbing-directory ruling -- and
 * is now test-only. Retiring this whole file requires migrating those tests.
 *
 * Plain `java.io.File`, deliberately. The scan pipeline already walks real paths — `core-storage` contains
 * no `DocumentFile` usage at all — so SAF was only ever a *chooser*, and replacing the chooser does not
 * touch the storage layer.
 */
internal object FolderBrowserEnumeration {

    /**
     * Archives the browser may OFFER to extract.
     *
     * **zip only, for now.** `BrowserListing.EXTRACTABLE_EXTENSIONS` also contains
     * `7z` in anticipation of a permissively-licensed reader, but advertising an extraction that then fails
     * is worse than not offering it, so the UI is gated on what can actually be unpacked today. `rar` is
     * absent from both, by construction and by licence.
     */
    private val OFFERABLE_ARCHIVES = setOf("zip")

    /**
     * List one directory.
     *
     * @param classifyPlatform the resolver's own extension classifier, injected rather than duplicated —
     *   a second extension table here would drift from the first and the drift would be invisible.
     *
     * Unreadable directories return empty rather than throwing: a ROM tree can contain a folder the app
     * genuinely cannot enter, and a browser that crashes on one is worse than one that shows it as empty.
     */
    fun listDirectory(path: String, classifyPlatform: (String) -> String?): List<FolderBrowserEntry> {
        val children = runCatching { File(path).listFiles() }.getOrNull().orEmpty()
        val entries = children.map { file ->
            BrowserListing.Entry(
                name = file.name,
                isDirectory = file.isDirectory,
                isBios = isBiosPayload(file),
            )
        }
        // Ordering and kind come from the shared model, so the browser and any future surface agree.
        return BrowserListing.rows(entries, classifyPlatform).map { row ->
            FolderBrowserEntry(
                name = row.name,
                isDirectory = row.kind == BrowserListing.RowKind.DIRECTORY,
                isArchive = row.kind == BrowserListing.RowKind.ARCHIVE &&
                    row.name.substringAfterLast('.', "").lowercase() in OFFERABLE_ARCHIVES,
                isBios = row.kind == BrowserListing.RowKind.BIOS,
                isSupport = row.kind == BrowserListing.RowKind.SUPPORT || row.deEmphasised,
                platformLabel = row.platformHint,
            )
        }
    }

    /**
     * Storage roots a player can browse.
     *
     * Internal storage first, then removable volumes. `/storage` also holds `self` and `emulated`, which are
     * plumbing rather than places, so they are excluded — showing them would invite a player into a
     * directory that cannot contain their games.
     */
    fun storageRoots(storageDir: File = File("/storage")): List<String> = buildList {
        val internal = File("/storage/emulated/0")
        if (internal.isDirectory) add(internal.absolutePath)
        runCatching { storageDir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isDirectory && it.name !in PLUMBING_DIRECTORIES }
            .map { it.absolutePath }
            .sorted()
            .forEach { add(it) }
    }

    /**
     * A BIOS payload rather than a game.
     *
     * `GameNameResolver`'s own BIOS knowledge is private to `core-resolver`, so this is a deliberately
     * narrow restatement of the part that matters for browsing: the extension plus a name that says so.
     * It is intentionally conservative — mislabelling a game as BIOS hides it from the player's own
     * library, which is worse than showing a BIOS file plainly.
     */
    private fun isBiosPayload(file: File): Boolean {
        if (file.isDirectory) return false
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension !in BIOS_EXTENSIONS) return false
        val lower = file.name.lowercase()
        return BIOS_NAME_HINTS.any { lower.contains(it) }
    }

    private val BIOS_EXTENSIONS = setOf("bin", "rom", "ic1")
    private val BIOS_NAME_HINTS = setOf("bios", "scph", "boot", "firmware")
    private val PLUMBING_DIRECTORIES = setOf("self", "emulated")
}

/**
 * One filesystem entry as [FolderBrowserEnumeration.listDirectory] returns it.
 *
 * Formerly lived in the retired `feature-library` folder-picker lane (`FolderBrowserApi.kt`, deleted
 * slice 6 -- the swap to `feature-files`), which could
 * not depend on `core-storage` and so shuttled this hand-rolled type across that module boundary.
 * Moved here rather than deleted alongside the rest of that file: [FolderBrowserEnumeration] and its
 * test are KEPT per the adoption spec, and this is the type [FolderBrowserEnumeration.listDirectory]
 * returns. `feature-library`'s own `FolderBrowserEntry.toRow()` extension (which converted this into
 * the now-deleted `feature-library` `FolderBrowserRow`) did NOT move here with it -- it has no callers
 * once that module is gone.
 */
internal data class FolderBrowserEntry(
    val name: String,
    val isDirectory: Boolean,
    val isArchive: Boolean = false,
    val isBios: Boolean = false,
    val isSupport: Boolean = false,
    val platformLabel: String? = null,
)
