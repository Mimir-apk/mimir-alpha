package com.gruning.mimir.rewrite.core.files

import android.webkit.MimeTypeMap
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletionEntry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * MIMIR ADDITION -- fix round after the E2 review, 2026-10-08: **a delete held for Undo stays visible, as
 * "Deleting…", until it is undone or released.**
 *
 * The listing hides `.mimir-staging` (a player cannot enter, move into or "tidy" Mimir's working folder), and
 * rightly so. But that also hid the only trace of a held delete: the file vanished from its folder and the
 * "Deleting…" label the Files cover was drawn for never rendered. Keep the
 * resolution (the staging folder stays hidden) and show the held delete itself: the row the player deleted,
 * back in its own folder under its own name, marked [FileItem.isPendingDelete].
 *
 * Built from the list of held deletes -- the same list Undo and the close-time finalise read -- never from
 * the staging folder. Pure given the filesystem: nothing here writes.
 */
object HeldDeleteListing {

    /**
     * [listing] of [directory], plus a row for each held delete whose original place is [directory].
     *
     * **By held identity, never by name.** A row exists only for an entry whose payload is still in staging,
     * and only where no real item stands at that path. A real file is never marked "Deleting…" and never
     * covered: if something else now has the held name (a file renamed or made there after the delete), it
     * is simply what the folder holds, and Undo will put the held one back beside it under a Keep-both name.
     * A payload that is gone gets no row (there is nothing to show).
     */
    fun overlay(
        directory: String,
        listing: List<FileItem>,
        held: Collection<StagedDeletion>,
    ): List<FileItem> {
        if (held.isEmpty()) return listing
        val here = normalised(directory)
        val entries = held.flatMap { it.entries }.filter { parentOf(it.originalPath) == here }
        if (entries.isEmpty()) return listing
        val real = listing.mapTo(HashSet()) { normalised(it.path) }
        val rows = entries
            .filter { normalised(it.originalPath) !in real }
            .mapNotNull(::rowFor)
            // Two deletes of the same name (deleted, remade, deleted again) are one row: a path is one focus key.
            .distinctBy { normalised(it.path) }
        return if (rows.isEmpty()) listing else listing + rows
    }

    /** The row for one held entry, read from its payload in staging; null when the payload is gone. */
    private fun rowFor(entry: StagedDeletionEntry): FileItem? {
        val staged = Paths.get(entry.stagedPath)
        val attrs = try {
            Files.readAttributes(staged, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return null
        }
        val name = File(entry.originalPath).name
        val extension = name.substringAfterLast('.', "")
        val isDirectory = attrs.isDirectory
        val mime = if (isDirectory) {
            "inode/directory"
        } else {
            // Guarded the way LocalFileRepository guards it: the plain JVM unit-test stub of MimeTypeMap throws.
            runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) }
                .getOrNull() ?: "application/octet-stream"
        }
        return FileItem(
            name = name,
            path = entry.originalPath,
            size = if (isDirectory) 0L else attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis(),
            isDirectory = isDirectory,
            isHidden = name.startsWith("."),
            mimeType = mime,
            extension = extension,
            childCount = if (isDirectory) childCountOf(staged) else null,
            isPendingDelete = true,
        )
    }

    private fun childCountOf(directory: Path): Int? = try {
        Files.newDirectoryStream(directory).use { stream -> stream.count() }
    } catch (_: Exception) {
        null
    }

    private fun normalised(path: String): String = Paths.get(path).toAbsolutePath().normalize().toString()

    private fun parentOf(path: String): String? = Paths.get(path).toAbsolutePath().normalize().parent?.toString()
}
