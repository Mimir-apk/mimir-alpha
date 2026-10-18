package com.gruning.mimir.rewrite.core.files

import android.net.Uri
import java.nio.file.attribute.PosixFilePermission

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.model`; unmodified otherwise. Upstream path:
 * `core/model/src/main/java/com/explorer/fileexplorer/core/model/FileItem.kt`.
 *
 * Universal file representation used across all providers
 * (local, root, SAF, archive).
 */
data class FileItem(
    val name: String,
    val path: String,
    val absolutePath: String = path,
    val uri: Uri? = null,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val isDirectory: Boolean = false,
    val isHidden: Boolean = false,
    val isSymlink: Boolean = false,
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val mimeType: String = if (isDirectory) "inode/directory" else "application/octet-stream",
    val extension: String = name.substringAfterLast('.', ""),
    val permissions: Set<PosixFilePermission> = emptySet(),
    val ownerName: String? = null,
    val groupName: String? = null,
    val symlinkTarget: String? = null,
    val childCount: Int? = null,
    /**
     * Free/total capacity, set ONLY on the synthesised rows [StorageVolumeListing.volumeRows] builds for
     * the drive chooser. Null everywhere else, which is what keeps [displaySize] honest: a real folder
     * has a child count and no capacity, a drive has capacity and no meaningful child count.
     *
     *  The numbers were already being computed —
     * `StorageVolumeHelper.getStorageVolumes()` runs a `StatFs` per volume and fills
     * [StorageVolume.totalBytes]/[StorageVolume.freeBytes] — and `volumeRows` was discarding both on the
     * way into this type. Nothing new is measured; two values that already existed stop being thrown away.
     */
    val volumeFreeBytes: Long? = null,
    val volumeTotalBytes: Long? = null,
    /**
     * A row for something the player deleted that is still held for Undo, drawn where it was under its own
     * name so the delete is visible as "Deleting…" instead of the file silently vanishing. Set ONLY by
     * [HeldDeleteListing]; a real directory listing never sets it. Such a row is a label, not a file: it
     * cannot be opened, selected or acted on.
     */
    val isPendingDelete: Boolean = false,
) {
    val isArchive: Boolean
        get() = extension.lowercase() in ARCHIVE_EXTENSIONS

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/")

    val isApk: Boolean
        get() = extension.equals("apk", ignoreCase = true)

    val isText: Boolean
        get() = mimeType.startsWith("text/") || extension.lowercase() in TEXT_EXTENSIONS

    /**
     * "119.4 GB free of 238.3 GB" for a drive-chooser row, else null.
     *
     * Requires BOTH halves and a positive total: a volume whose `StatFs` failed reports zeros, and
     * "0 B free of 0 B" is a worse answer than saying nothing. Same discipline as `childCount`'s own
     * null-vs-zero rule — "0 items" and "cannot read this folder" are different statements.
     */
    val volumeCapacity: String?
        get() {
            val total = volumeTotalBytes ?: return null
            val free = volumeFreeBytes ?: return null
            if (total <= 0L || free < 0L) return null
            return "${formatSize(free)} free of ${formatSize(total)}"
        }

    /**
     * The tile-sized form: **"317 GB free"**, no decimals and no total.
     *
     * Exists because the full [volumeCapacity] does not fit a tome's caption lane — measured on device,
     * "317.49 GB free of 435.85 GB" truncated to "317.49 GB free of 43…" the first time this shipped,
     * which is a worse answer than the short one. The full string still reaches the inspector, where the
     * lane is wide enough for it. Whole gigabytes on purpose: two decimal places of free space on a
     * 400 GB card is precision nobody reads and the exact figure is one focus away in the inspector.
     */
    val volumeCapacityCompact: String?
        get() {
            val free = volumeFreeBytes ?: return null
            val total = volumeTotalBytes ?: return null
            if (total <= 0L || free < 0L) return null
            return when {
                free < 1024L * 1024L * 1024L -> "${formatSize(free)} free"
                else -> "%.0f GB free".format(free / (1024.0 * 1024.0 * 1024.0))
            }
        }

    val displaySize: String
        get() = when {
            // Drives first: a volume row is also a directory, and its childCount is the number of
            // top-level folders on the card -- true, but not what anyone wants to read about a drive.
            volumeCapacity != null -> volumeCapacity!!
            // Child-count grammar: one child is "1 item". Zero and every other count keep the plural.
            isDirectory -> childCount?.let { count -> if (count == 1) "1 item" else "$count items" } ?: ""
            else -> formatSize(size)
        }

    companion object {
        // Includes rar/7z for display classification only. The extraction call site must enforce
        // the supported extraction formats; classification here does not permit extraction.
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "7z", "tar", "gz", "bz2", "xz", "rar", "zst",
            "tgz", "tbz2", "txz", "jar", "war", "ear"
        )
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "log", "csv", "tsv", "sh", "bash", "zsh", "fish",
            "py", "kt", "kts", "java", "js", "ts", "html", "css", "scss",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
            "gradle", "properties", "gitignore", "env", "dockerfile"
        )

        fun formatSize(size: Long): String = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
            else -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
